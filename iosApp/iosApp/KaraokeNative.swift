import AVFoundation
import Foundation
import LayaKit
import OnnxRuntimeBindings

/// What neural karaoke needs from the platform: ONNX Runtime for the MDX model, and a decode of
/// the playing track's stream. Called from KaraokeStems' worker threads, never the audio thread.
final class KaraokeNativeImpl: NSObject, KaraokeNative {
    func openModel(path: String, threads: Int32) -> KaraokeModelRun? {
        do { return try OrtModelRun(path: path, threads: Int(threads)) } catch {
            print("Karaoke: model failed to load: \(error)")
            return nil
        }
    }

    func openPcm(url: String, headers: [String: String], mimeType: String, contentLength: Int64, chunkBytes: Int64) -> KaraokePcmSource? {
        guard let source = URL(string: url) else { return nil }
        return PcmReader(source: source, headers: headers, mimeType: mimeType, contentLength: contentLength, chunkBytes: chunkBytes)
    }
}

private final class OrtModelRun: NSObject, KaraokeModelRun {
    // [1, 4, dimF, 256] in and out: MdxModel.MDX_9482's dimF.
    private static let shape: [NSNumber] = [1, 4, 2048, 256]
    private static let bytes = 4 * 2048 * 256 * MemoryLayout<Float>.size

    private var env: ORTEnv?
    private var session: ORTSession?
    private let inputName: String
    private let outputName: String

    init(path: String, threads: Int) throws {
        let env = try ORTEnv(loggingLevel: .warning)
        let options = try ORTSessionOptions()
        try options.setIntraOpNumThreads(Int32(threads))
        try options.setGraphOptimizationLevel(.all)
        let session = try ORTSession(env: env, modelPath: path, sessionOptions: options)
        inputName = try session.inputNames().first ?? "input"
        outputName = try session.outputNames().first ?? "output"
        self.env = env
        self.session = session
    }

    /// Called on KaraokeStems' Kotlin/Native threads, which drain no autorelease pool: without one here each run's
    /// 8 MB in and 8 MB out stayed until the thread ended (measured: +17 MB per segment, 1.9 GB for one song).
    func run(input: UnsafeMutableRawPointer, output: UnsafeMutableRawPointer) -> Bool {
        autoreleasepool {
            guard let session else { return false }
            do {
                // NSMutableData may copy even with bytesNoCopy, so a pre-bound output tensor can be written
                // somewhere Kotlin never sees (the vocal came back silent). ORT returns its own output; copy it back.
                let inValue = try ORTValue(tensorData: NSMutableData(bytes: input, length: Self.bytes),
                                           elementType: .float, shape: Self.shape)
                let outputs = try session.run(withInputs: [inputName: inValue], outputNames: [outputName], runOptions: nil)
                guard let result = try outputs[outputName]?.tensorData(), result.length >= Self.bytes else { return false }
                memcpy(output, result.bytes, Self.bytes)
                return true
            } catch {
                print("Karaoke: inference failed: \(error)")
                return false
            }
        }
    }

    /// The session's memory goes now, not whenever a K/N GC gets round to this object's wrapper.
    func close() {
        session = nil
        env = nil
    }
}

/// AVAssetReader over the same chunked stream the player reads, as 44.1 kHz interleaved stereo float.
final class PcmReader: NSObject, KaraokePcmSource {
    private let rate: Double
    private let channels: Int
    private var asset: AVURLAsset?
    private var loader: ChunkedResourceLoader?
    private var reader: AVAssetReader?
    private var output: AVAssetReaderTrackOutput?
    private var pending: [Float] = []
    private var pendingPos = 0

    let durationFrames: Int64

    init?(source: URL, headers: [String: String], mimeType: String, contentLength: Int64, chunkBytes: Int64, rate: Double = 44_100, channels: Int = 2) {
        self.rate = rate
        self.channels = channels
        if source.isFileURL {
            asset = AVURLAsset(url: source)
            loader = nil
        } else {
            let l = ChunkedResourceLoader(source: source, headers: headers, mimeType: mimeType,
                                          knownLength: contentLength > 0 ? contentLength : nil, chunkBytes: max(64 * 1024, chunkBytes))
            asset = AVURLAsset(url: l.proxyURL)
            asset?.resourceLoader.setDelegate(l, queue: l.queue)
            loader = l
        }
        let seconds = asset?.duration.seconds ?? .nan
        durationFrames = seconds.isFinite ? Int64(seconds * rate) : -1
        super.init()
        if !start(at: 0) { close(); return nil }
    }

    /// Like read, called on Kotlin/Native threads, which drain no autorelease pool.
    private func start(at frame: Int64) -> Bool { autoreleasepool { startNow(at: frame) } }

    private func startNow(at frame: Int64) -> Bool {
        reader?.cancelReading()
        reader = nil
        output = nil
        pending = []
        pendingPos = 0
        guard let asset, let track = asset.tracks(withMediaType: .audio).first, let r = try? AVAssetReader(asset: asset) else { return false }
        let o = AVAssetReaderTrackOutput(track: track, outputSettings: [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: rate,
            AVNumberOfChannelsKey: channels,
            AVLinearPCMBitDepthKey: 32,
            AVLinearPCMIsFloatKey: true,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ])
        o.alwaysCopiesSampleData = false
        guard r.canAdd(o) else { return false }
        r.add(o)
        if frame > 0 { r.timeRange = CMTimeRange(start: CMTime(seconds: Double(frame) / rate, preferredTimescale: 44_100), duration: .positiveInfinity) }
        guard r.startReading() else { return false }
        reader = r
        output = o
        return true
    }

    /// Called on Kotlin/Native threads (karaoke, Automix, version alignment), which drain no autorelease pool:
    /// whatever AVAssetReader autoreleases per sample buffer would otherwise stay until the thread ends.
    func read(into destination: UnsafeMutableRawPointer, maxFrames: Int32) -> Int32 {
        autoreleasepool { readNow(into: destination, maxFrames: maxFrames) }
    }

    private func readNow(into destination: UnsafeMutableRawPointer, maxFrames: Int32) -> Int32 {
        let want = Int(maxFrames) * channels
        while pending.count - pendingPos < want, let sample = output?.copyNextSampleBuffer() {
            guard let block = CMSampleBufferGetDataBuffer(sample) else { continue }
            let floats = CMBlockBufferGetDataLength(block) / MemoryLayout<Float>.size
            let old = pending.count - pendingPos
            pending = Array(pending[pendingPos...]) + [Float](repeating: 0, count: floats)
            pendingPos = 0
            pending.withUnsafeMutableBytes { _ = CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: floats * MemoryLayout<Float>.size, destination: $0.baseAddress! + old * MemoryLayout<Float>.size) }
        }
        let have = pending.count - pendingPos
        if have == 0 { return reader?.status == .failed ? -1 : 0 }
        let n = min(have, want) / channels * channels
        pending.withUnsafeBufferPointer { destination.copyMemory(from: $0.baseAddress! + pendingPos, byteCount: n * MemoryLayout<Float>.size) }
        pendingPos += n
        return Int32(n / channels)
    }

    func seek(frame: Int64) -> Int64 { start(at: frame) ? frame : -1 }

    func close() {
        // Each AVURLAsset / AVAssetReader holds a mediaplaybackd XPC connection until freed: release them now, not at a K/N GC.
        reader?.cancelReading()
        reader = nil
        output = nil
        asset?.cancelLoading()
        asset = nil
        loader?.cancelAll()
        loader = nil
    }
}
