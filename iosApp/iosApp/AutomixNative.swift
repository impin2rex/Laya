import Foundation
import LayaKit
import OnnxRuntimeBindings

/// What Automix's analysis needs from the platform (see AutomixNative.kt): the track decoded to 22,050 Hz mono,
/// the C++ DSP analyzer (LayaAnalysis.mm) and the Beat This! model on ONNX Runtime. Worker threads only.
final class AutomixNativeImpl: NSObject, AutomixNative {
    private static let rate = 22_050
    /// BeatTracker.CHUNK_FRAMES / BORDER_FRAMES: the model reads 1500-frame chunks and has no context at their edges.
    private static let chunk = 1500
    private static let border = 6
    private static let mels = 128

    /// open-unmix's fixed input width and bin count (VocalModel in shared code).
    private static let vocalFrames = 960
    private static let vocalBins = 2049
    private static let vocalRate = 44_100.0

    private var env: ORTEnv?
    private var sessions: [String: (session: ORTSession, threads: Int)] = [:]
    private let lock = NSLock()

    func modelPath() -> String { Bundle.main.path(forResource: "beat_this_int8", ofType: "onnx") ?? "" }

    func vocalModelPath() -> String { Bundle.main.path(forResource: "vocals_umxhq_int8", ofType: "onnx") ?? "" }

    func vocalBands(modelPath: String, threads: Int32, url: String, headers: [String: String], mimeType: String,
                    contentLength: Int64, chunkBytes: Int64, startSec: Double, seconds: Double,
                    firstBin: Int32, lastBin: Int32) -> AutomixVocal? {
        guard !modelPath.isEmpty, let source = URL(string: url),
              let reader = PcmReader(source: source, headers: headers, mimeType: mimeType, contentLength: contentLength,
                                     chunkBytes: chunkBytes, rate: Self.vocalRate, channels: 2) else { return nil }
        defer { reader.close() }
        if startSec > 0 && reader.seek(frame: Int64(startSec * Self.vocalRate)) < 0 { return nil }
        let want = Int(seconds * Self.vocalRate)
        var left = [Float](), right = [Float]()
        left.reserveCapacity(want); right.reserveCapacity(want)
        let block = 32_768
        let buffer = UnsafeMutablePointer<Float>.allocate(capacity: block * 2)
        defer { buffer.deallocate() }
        while left.count < want {
            let n = Int(reader.read(into: buffer, maxFrames: Int32(min(block, want - left.count))))
            if n < 0 { return nil }
            if n == 0 { break }
            for i in 0..<n { left.append(buffer[2 * i]); right.append(buffer[2 * i + 1]) }
        }
        // Under a second of audio is a decode that ran out, not a region.
        guard left.count >= Int(Self.vocalRate), let session = session(modelPath, Int(threads)) else { return nil }
        var frames = 0
        let spectrogram: Data? = left.withUnsafeBufferPointer { l in
            right.withUnsafeBufferPointer { r in
                LayaAnalysis.vocalSpectrogramLeft(l.baseAddress!, right: r.baseAddress!, count: l.count, frames: &frames) as Data?
            }
        }
        guard let spectrogram, frames > 0, frames <= Self.vocalFrames else { return nil }
        left = []; right = []

        // Re-stride [channel][bin][frames] into the model's zero-padded [channel][bin][960].
        let bins = Self.vocalBins, fixed = Self.vocalFrames
        let input = NSMutableData(length: 2 * bins * fixed * 4)!
        let mix = input.mutableBytes.assumingMemoryBound(to: Float.self)
        spectrogram.withUnsafeBytes { raw in
            let src = raw.bindMemory(to: Float.self)
            for row in 0..<(2 * bins) { (mix + row * fixed).update(from: src.baseAddress! + row * frames, count: frames) }
        }
        let lo = Int(firstBin), hi = Int(lastBin), band = hi - lo + 1
        guard band > 0 else { return nil }
        do {
            let name = try session.inputNames().first ?? "input"
            let tensor = try ORTValue(tensorData: input, elementType: .float,
                                      shape: [1, 2, NSNumber(value: bins), NSNumber(value: fixed)])
            let outputName = try session.outputNames().first ?? "output"
            let result = try session.run(withInputs: [name: tensor], outputNames: [outputName], runOptions: nil)
            guard let out = try result[outputName]?.tensorData() as Data?, out.count >= 2 * bins * fixed * 4 else { return nil }
            var mixBand = [Float](repeating: 0, count: 2 * band * fixed)
            var targetBand = [Float](repeating: 0, count: 2 * band * fixed)
            out.withUnsafeBytes { raw in
                let tgt = raw.bindMemory(to: Float.self)
                for c in 0..<2 {
                    for b in 0..<band {
                        let from = (c * bins + lo + b) * fixed, to = (c * band + b) * fixed
                        for f in 0..<fixed { mixBand[to + f] = mix[from + f]; targetBand[to + f] = tgt[from + f] }
                    }
                }
            }
            return Vocal(frames: frames, mix: mixBand, target: targetBand)
        } catch {
            print("Automix: vocal inference failed: \(error)")
            return nil
        }
    }

    func decode(url: String, headers: [String: String], mimeType: String, contentLength: Int64, chunkBytes: Int64) -> AutomixPcm? {
        guard let source = URL(string: url),
              let reader = PcmReader(source: source, headers: headers, mimeType: mimeType, contentLength: contentLength,
                                     chunkBytes: chunkBytes, rate: Double(Self.rate), channels: 1) else { return nil }
        defer { reader.close() }
        var samples = [Float]()
        if reader.durationFrames > 0 { samples.reserveCapacity(Int(reader.durationFrames) + 4096) }
        let block = 65_536
        let buffer = UnsafeMutablePointer<Float>.allocate(capacity: block)
        defer { buffer.deallocate() }
        while true {
            let n = Int(reader.read(into: buffer, maxFrames: Int32(block)))
            if n < 0 { return nil }
            if n == 0 { break }
            samples.append(contentsOf: UnsafeBufferPointer(start: buffer, count: n))
        }
        return samples.isEmpty ? nil : PcmBuffer(samples)
    }

    func beatLogits(modelPath: String, threads: Int32, pcm: UnsafeMutableRawPointer, count: Int32) -> AutomixLogits? {
        var frames = 0
        guard let mel = LayaAnalysis.beatSpectrogram(pcm.assumingMemoryBound(to: Float.self), count: Int(count), frames: &frames), frames > 0,
              let session = session(modelPath, Int(threads)) else { return nil }
        var beat = [Float](repeating: 0, count: frames)
        var downbeat = [Float](repeating: 0, count: frames)
        let values = mel as Data
        let stride = Self.chunk - 2 * Self.border
        do {
            let input = try session.inputNames().first ?? "input"
            let outputs = try session.outputNames()
            var start = 0
            while start < frames {
                let length = min(Self.chunk, frames - start)
                if length <= 2 * Self.border && start > 0 { break }
                let bytes = values.subdata(in: start * Self.mels * 4 ..< (start + length) * Self.mels * 4)
                let tensor = try ORTValue(tensorData: NSMutableData(data: bytes), elementType: .float,
                                          shape: [1, NSNumber(value: length), NSNumber(value: Self.mels)])
                let result = try session.run(withInputs: [input: tensor], outputNames: Set(outputs.prefix(2)), runOptions: nil)
                guard let b = try result[outputs[0]]?.tensorData() as Data?, let d = try result[outputs[1]]?.tensorData() as Data?,
                      b.count >= length * 4, d.count >= length * 4 else { return nil }
                let from = start == 0 ? 0 : Self.border
                let to = start + length >= frames ? length : length - Self.border
                b.withUnsafeBytes { raw in
                    d.withUnsafeBytes { rawD in
                        let bf = raw.bindMemory(to: Float.self), df = rawD.bindMemory(to: Float.self)
                        for i in from..<to where start + i < frames { beat[start + i] = bf[i]; downbeat[start + i] = df[i] }
                    }
                }
                if start + length >= frames { break }
                start += stride
            }
        } catch {
            print("Automix: beat inference failed: \(error)")
            return nil
        }
        return Logits(beat: beat, downbeat: downbeat)
    }

    /// One session kept per model (parsing the graph costs far more than a run), rebuilt when the performance mode changes the thread count.
    private func session(_ path: String, _ threads: Int) -> ORTSession? {
        lock.lock(); defer { lock.unlock() }
        if let kept = sessions[path], kept.threads == threads { return kept.session }
        do {
            let env = try self.env ?? ORTEnv(loggingLevel: .warning)
            let options = try ORTSessionOptions()
            try options.setIntraOpNumThreads(Int32(threads))
            try options.setGraphOptimizationLevel(.all)
            let made = try ORTSession(env: env, modelPath: path, sessionOptions: options)
            self.env = env; sessions[path] = (made, threads)
            return made
        } catch {
            print("Automix: model failed to load: \(error)")
            return nil
        }
    }
}

private final class PcmBuffer: NSObject, AutomixPcm {
    private var samples: [Float]
    init(_ samples: [Float]) { self.samples = samples }

    var frames: Int32 { Int32(samples.count) }

    func doCopy(into: UnsafeMutableRawPointer, from: Int32, count: Int32) {
        samples.withUnsafeBufferPointer { into.assumingMemoryBound(to: Float.self).update(from: $0.baseAddress! + Int(from), count: Int(count)) }
    }

    func features(durationSeconds: Double) -> String? {
        samples.withUnsafeBufferPointer {
            LayaAnalysis.featuresJSON($0.baseAddress!, count: $0.count, rate: 22_050, duration: durationSeconds)
        }
    }

    func close() { samples = [] }
}

private final class Logits: NSObject, AutomixLogits {
    private let beat: [Float], downbeat: [Float]
    init(beat: [Float], downbeat: [Float]) { self.beat = beat; self.downbeat = downbeat }

    var frames: Int32 { Int32(beat.count) }

    func doCopy(beat out: UnsafeMutableRawPointer, downbeat outDown: UnsafeMutableRawPointer) {
        beat.withUnsafeBufferPointer { out.assumingMemoryBound(to: Float.self).update(from: $0.baseAddress!, count: beat.count) }
        downbeat.withUnsafeBufferPointer { outDown.assumingMemoryBound(to: Float.self).update(from: $0.baseAddress!, count: downbeat.count) }
    }
}

private final class Vocal: NSObject, AutomixVocal {
    private let mix: [Float], target: [Float]
    let frames: Int32
    init(frames: Int, mix: [Float], target: [Float]) { self.frames = Int32(frames); self.mix = mix; self.target = target }

    var count: Int32 { Int32(mix.count) }

    func doCopy(mix out: UnsafeMutableRawPointer, target outTarget: UnsafeMutableRawPointer) {
        mix.withUnsafeBufferPointer { out.assumingMemoryBound(to: Float.self).update(from: $0.baseAddress!, count: mix.count) }
        target.withUnsafeBufferPointer { outTarget.assumingMemoryBound(to: Float.self).update(from: $0.baseAddress!, count: target.count) }
    }
}
