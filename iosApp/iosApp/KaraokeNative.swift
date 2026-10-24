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

    private let env: ORTEnv
    private let session: ORTSession
    private let inputName: String
    private let outputName: String

    init(path: String, threads: Int) throws {
        env = try ORTEnv(loggingLevel: .warning)
        let options = try ORTSessionOptions()
        try options.setIntraOpNumThreads(Int32(threads))
        try options.setGraphOptimizationLevel(.all)
        session = try ORTSession(env: env, modelPath: path, sessionOptions: options)
        inputName = try session.inputNames().first ?? "input"
        outputName = try session.outputNames().first ?? "output"
    }

    func run(input: UnsafeMutableRawPointer, output: UnsafeMutableRawPointer) -> Bool {
        do {
            // Both tensors wrap Kotlin's pinned arrays in place.
            let inValue = try ORTValue(tensorData: NSMutableData(bytesNoCopy: input, length: Self.bytes, freeWhenDone: false),
                                       elementType: .float, shape: Self.shape)
            let outValue = try ORTValue(tensorData: NSMutableData(bytesNoCopy: output, length: Self.bytes, freeWhenDone: false),
                                        elementType: .float, shape: Self.shape)
            try session.run(withInputs: [inputName: inValue], outputs: [outputName: outValue], runOptions: nil)
            return true
        } catch {
            print("Karaoke: inference failed: \(error)")
            return false
        }
    }

    func close() {}
}

/// AVAssetReader over the same chunked stream the player reads, as 44.1 kHz interleaved stereo float.
private final class PcmReader: NSObject, KaraokePcmSource {
    private static let rate = 44_100.0
    private let asset: AVURLAsset
    private let loader: ChunkedResourceLoader?
    private var reader: AVAssetReader?
    private var output: AVAssetReaderTrackOutput?
    private var pending: [Float] = []
    private var pendingPos = 0

    let durationFrames: Int64

    init?(source: URL, headers: [String: String], mimeType: String, contentLength: Int64, chunkBytes: Int64) {
        if source.isFileURL {
            asset = AVURLAsset(url: source)
            loader = nil
        } else {
            let l = ChunkedResourceLoader(source: source, headers: headers, mimeType: mimeType,
                                          knownLength: contentLength > 0 ? contentLength : nil, chunkBytes: max(64 * 1024, chunkBytes))
            asset = AVURLAsset(url: l.proxyURL)
            asset.resourceLoader.setDelegate(l, queue: l.queue)
            loader = l
        }
        let seconds = asset.duration.seconds
        durationFrames = seconds.isFinite ? Int64(seconds * Self.rate) : -1
        super.init()
        if !start(at: 0) { return nil }
    }

    private func start(at frame: Int64) -> Bool {
        reader?.cancelReading()
        reader = nil
        output = nil
        pending = []
        pendingPos = 0
        guard let track = asset.tracks(withMediaType: .audio).first, let r = try? AVAssetReader(asset: asset) else { return false }
        let o = AVAssetReaderTrackOutput(track: track, outputSettings: [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: Self.rate,
            AVNumberOfChannelsKey: 2,
            AVLinearPCMBitDepthKey: 32,
            AVLinearPCMIsFloatKey: true,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ])
        o.alwaysCopiesSampleData = false
        guard r.canAdd(o) else { return false }
        r.add(o)
        if frame > 0 { r.timeRange = CMTimeRange(start: CMTime(seconds: Double(frame) / Self.rate, preferredTimescale: 44_100), duration: .positiveInfinity) }
        guard r.startReading() else { return false }
        reader = r
        output = o
        return true
    }

    func read(into destination: UnsafeMutableRawPointer, maxFrames: Int32) -> Int32 {
        let want = Int(maxFrames) * 2
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
        let n = min(have, want) / 2 * 2
        pending.withUnsafeBufferPointer { destination.copyMemory(from: $0.baseAddress! + pendingPos, byteCount: n * MemoryLayout<Float>.size) }
        pendingPos += n
        return Int32(n / 2)
    }

    func seek(frame: Int64) -> Int64 { start(at: frame) ? frame : -1 }

    func close() {
        reader?.cancelReading()
        reader = nil
        loader?.cancelAll()
    }
}
