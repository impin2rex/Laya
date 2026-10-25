import AVFoundation
import Foundation
import LayaKit
import MediaToolbox

/// Target curve shared by every item's tap. Plain pointers, no locks: the audio
/// thread only reads, and a torn read moves one 64-frame glide target a hair.
enum EqualizerTuning {
    static let slots = 10
    /// gains[0..<10], qs[10..<20], preamp, balance.
    static let values = UnsafeMutablePointer<Float>.allocate(capacity: 22)
    /// Mirror of Android's EqLayout: 0 bell, 1 low shelf, 2 high shelf.
    static let kinds = [1, 0, 0, 0, 0, 0, 2, 1, 0, 2]
    static let frequenciesHz: [Float] = [60, 150, 400, 1_000, 2_500, 6_000, 14_000, 250, 1_000, 4_000]
    static var eqOn = false
    /// Reverb or karaoke (IosDsp) is on.
    static var dsp = false
    static var skipSilence = false
    /// Whether the player is currently racing through silence; toggled from the audio thread.
    static var skipping = false
    static var onSkipping: ((Bool) -> Void)?
    /// The item whose tap runs IosDsp and skip-silence. IosDsp has one state, so while two decks overlap only the current track gets it.
    static var dspKey: UnsafeRawPointer?
    static var wanted: Bool { eqOn || dsp || skipSilence }

    static func set(gains: [Float], qs: [Float], preampDb: Float, balance: Float) {
        for i in 0..<slots { values[i] = gains[i]; values[slots + i] = qs[i] }
        values[20] = preampDb
        values[21] = balance
        eqOn = gains.contains { abs($0) > 0.01 } || abs(balance) > 0.0005
    }
}

/// The in-app equaliser on one player item, through an `MTAudioProcessingTap`:
/// a port of Android's `EqualizerProcessor` (trapezoidal state-variable bell and
/// shelf sections, gains gliding every 64 frames, preamp, balance).
final class EqualizerTap {
    private static let n = EqualizerTuning.slots
    private static let block = 64
    private static let glide: Float = 0.08
    private static let settledDb: Float = 0.01
    private static let maxChannels = 8
    /// Android's silence threshold (1024 of 32768) and minimum run (1 s).
    private static let silentLevel: Float = 0.03

    private let key: UnsafeRawPointer
    private var scratch: UnsafeMutablePointer<Float>?
    private var quietFrames = 0

    private var sampleRate: Float = 44_100
    private var channels = 2
    private var isFloat = false
    private var isInterleaved = false

    private var gain = [Float](repeating: 0, count: n)
    private var q = [Float](repeating: 0.707, count: n)
    private var preamp: Float = 0
    private var balance: Float = 0
    private var a1 = [Float](repeating: 0, count: n)
    private var a2 = [Float](repeating: 0, count: n)
    private var a3 = [Float](repeating: 0, count: n)
    private var mixIn = [Float](repeating: 1, count: n)
    private var mixBand = [Float](repeating: 0, count: n)
    private var mixLow = [Float](repeating: 0, count: n)
    private var active = [Int](repeating: 0, count: n)
    private var state = [Float](repeating: 0, count: maxChannels * n * 2)
    private var channelGain = [Float](repeating: 1, count: maxChannels)

    init(key: UnsafeRawPointer) { self.key = key }

    deinit { scratch?.deallocate() }

    func prepare(_ format: AudioStreamBasicDescription, maxFrames: Int) {
        sampleRate = Float(format.mSampleRate)
        channels = min(Int(format.mChannelsPerFrame), Self.maxChannels)
        isFloat = (format.mFormatFlags & kAudioFormatFlagIsFloat) != 0 && format.mBitsPerChannel == 32
        isInterleaved = (format.mFormatFlags & kAudioFormatFlagIsNonInterleaved) == 0
        for i in state.indices { state[i] = 0 }
        scratch?.deallocate()
        scratch = isFloat ? .allocate(capacity: maxFrames * channels) : nil
        if isFloat {
            IosDsp.shared.prepare(sampleRate: Int32(format.mSampleRate), channelCount: Int32(channels), maxFrames: Int32(maxFrames))
        }
    }

    private func forEachSample(_ buffers: UnsafeMutableAudioBufferListPointer, from: Int, count: Int,
                               _ body: (Int, UnsafeMutablePointer<Float>) -> Void) {
        if isInterleaved {
            guard let data = buffers.first?.mData?.assumingMemoryBound(to: Float.self) else { return }
            let stride = max(1, Int(buffers.first?.mNumberChannels ?? 1))
            for frame in from..<(from + count) {
                for c in 0..<min(stride, channels) { body(c, data + frame * stride + c) }
            }
        } else {
            for frame in from..<(from + count) {
                for (c, buffer) in buffers.enumerated() where c < channels {
                    guard let data = buffer.mData?.assumingMemoryBound(to: Float.self) else { continue }
                    body(c, data + frame)
                }
            }
        }
    }

    func process(_ buffers: UnsafeMutableAudioBufferListPointer, frames: Int, mediaTimeUs: Int64) {
        guard isFloat, frames > 0 else { return }
        let current = EqualizerTuning.dspKey == key
        if current && EqualizerTuning.skipSilence { listen(buffers, frames: frames) }
        // Karaoke's centre-cancel needs the untouched stereo image, so it runs before the EQ's balance (as on Android).
        if current && IosDsp.shared.active { runDsp(buffers, frames: frames, mediaTimeUs: mediaTimeUs) }
        equalize(buffers, frames: frames)
    }

    /// Skip silence. ponytail: AVPlayer taps can't drop frames, so a run of silence longer than a
    /// second is played at 4x instead (speed restored when sound returns, so the first ~100 ms of
    /// a note after a gap is sped up). A silent outro still plays out at 4x rather than being cut.
    private func listen(_ buffers: UnsafeMutableAudioBufferListPointer, frames: Int) {
        var peak: Float = 0
        for b in buffers {
            guard let d = b.mData?.assumingMemoryBound(to: Float.self) else { continue }
            for i in 0..<frames * Int(b.mNumberChannels) { peak = max(peak, abs(d[i])) }
        }
        quietFrames = peak < Self.silentLevel ? quietFrames + frames : 0
        let skipping = quietFrames > Int(sampleRate)
        if skipping != EqualizerTuning.skipping {
            EqualizerTuning.skipping = skipping
            EqualizerTuning.onSkipping?(skipping)
        }
    }

    /// Karaoke and reverb live in Kotlin (IosDsp) on interleaved samples.
    private func runDsp(_ buffers: UnsafeMutableAudioBufferListPointer, frames: Int, mediaTimeUs: Int64) {
        let dsp = IosDsp.shared
        if isInterleaved {
            if let data = buffers.first?.mData?.assumingMemoryBound(to: Float.self) { dsp.process(data: data, frames: Int32(frames), mediaTimeUs: mediaTimeUs) }
            return
        }
        guard let scratch, buffers.count >= channels else { return }
        for c in 0..<channels {
            guard let src = buffers[c].mData?.assumingMemoryBound(to: Float.self) else { return }
            for f in 0..<frames { scratch[f * channels + c] = src[f] }
        }
        dsp.process(data: scratch, frames: Int32(frames), mediaTimeUs: mediaTimeUs)
        for c in 0..<channels {
            let dst = buffers[c].mData!.assumingMemoryBound(to: Float.self)
            for f in 0..<frames { dst[f] = scratch[f * channels + c] }
        }
    }

    private func equalize(_ buffers: UnsafeMutableAudioBufferListPointer, frames: Int) {
        guard isFloat, frames > 0, sampleRate > 0 else { return }
        let target = EqualizerTuning.values
        var idle = abs(target[20]) < Self.settledDb && abs(preamp) < Self.settledDb
            && abs(target[21]) < 0.0005 && abs(balance) < 0.0005
        for s in 0..<Self.n where abs(target[s]) >= Self.settledDb || abs(gain[s]) >= Self.settledDb { idle = false }
        if idle { return }

        var done = 0
        while done < frames {
            let count = min(frames - done, Self.block)
            var nActive = 0
            for s in 0..<Self.n {
                gain[s] += (target[s] - gain[s]) * Self.glide
                q[s] = max(target[Self.n + s], 0.05)
                if abs(gain[s]) >= Self.settledDb {
                    coefficients(s)
                    active[nActive] = s
                    nActive += 1
                } else {
                    for c in 0..<Self.maxChannels { let i = (c * Self.n + s) * 2; state[i] = 0; state[i + 1] = 0 }
                }
            }
            preamp += (target[20] - preamp) * Self.glide
            balance += (target[21] - balance) * Self.glide
            let amp = pow(10, preamp / 20)
            for c in 0..<channels { channelGain[c] = amp }
            if channels == 2 {
                channelGain[0] = amp * min(1, 1 - balance)
                channelGain[1] = amp * min(1, 1 + balance)
            }
            forEachSample(buffers, from: done, count: count) { c, sample in
                var v = sample.pointee
                for k in 0..<nActive { v = section(active[k], c, v) }
                sample.pointee = v * channelGain[c]
            }
            for k in 0..<nActive {
                for c in 0..<channels {
                    let i = (c * Self.n + active[k]) * 2
                    if abs(state[i]) < 1e-12 { state[i] = 0 }
                    if abs(state[i + 1]) < 1e-12 { state[i + 1] = 0 }
                }
            }
            done += count
        }
    }

    private func coefficients(_ s: Int) {
        let a = pow(10, gain[s] / 40)
        let f = min(max(EqualizerTuning.frequenciesHz[s], 10), sampleRate * 0.45)
        let base = tan(Float.pi * f / sampleRate)
        let g: Float, k: Float
        switch EqualizerTuning.kinds[s] {
        case 1:
            g = base / sqrt(a); k = 1 / q[s]
            mixIn[s] = 1; mixBand[s] = k * (a - 1); mixLow[s] = a * a - 1
        case 2:
            g = base * sqrt(a); k = 1 / q[s]
            mixIn[s] = a * a; mixBand[s] = k * (1 - a) * a; mixLow[s] = 1 - a * a
        default:
            g = base; k = 1 / (q[s] * a)
            mixIn[s] = 1; mixBand[s] = k * (a * a - 1); mixLow[s] = 0
        }
        let d = 1 / (1 + g * (g + k))
        a1[s] = d; a2[s] = g * d; a3[s] = g * (g * d)
    }

    @inline(__always)
    private func section(_ s: Int, _ c: Int, _ input: Float) -> Float {
        let i = (c * Self.n + s) * 2
        let ic1 = state[i], ic2 = state[i + 1]
        let v3 = input - ic2
        let v1 = a1[s] * ic1 + a2[s] * v3
        let v2 = ic2 + a2[s] * ic1 + a3[s] * v3
        state[i] = 2 * v1 - ic1
        state[i + 1] = 2 * v2 - ic2
        return mixIn[s] * input + mixBand[s] * v1 + mixLow[s] * v2
    }

    // MARK: - Attaching

    /// Puts the tap on [item]'s first audio track once its tracks are known
    /// (never blocking main on a network asset). Best effort: no tap, no EQ.
    static func attach(to item: AVPlayerItem) {
        let asset = item.asset
        if asset.statusOfValue(forKey: "tracks", error: nil) == .loaded {
            install(on: item)
            return
        }
        asset.loadValuesAsynchronously(forKeys: ["tracks"]) { [weak item] in
            DispatchQueue.main.async { if let item { install(on: item) } }
        }
    }

    private static func install(on item: AVPlayerItem) {
        guard item.audioMix == nil, let track = item.asset.tracks(withMediaType: .audio).first else { return }
        let tap = EqualizerTap(key: UnsafeRawPointer(Unmanaged.passUnretained(item).toOpaque()))
        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: Unmanaged.passRetained(tap).toOpaque(),
            init: { _, clientInfo, storageOut in storageOut.pointee = clientInfo },
            finalize: { tap in Unmanaged<EqualizerTap>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).release() },
            prepare: { tap, maxFrames, format in
                Unmanaged<EqualizerTap>.fromOpaque(MTAudioProcessingTapGetStorage(tap))
                    .takeUnretainedValue().prepare(format.pointee, maxFrames: Int(maxFrames))
            },
            unprepare: nil,
            process: { tap, frames, _, bufferList, framesOut, flagsOut in
                var range = CMTimeRange()
                guard MTAudioProcessingTapGetSourceAudio(tap, frames, bufferList, flagsOut, &range, framesOut) == noErr else { return }
                // Where this block sits in the track, which lines Karaoke's neural vocal up with it.
                let us = range.start.isNumeric ? Int64(range.start.seconds * 1_000_000) : Int64.min
                Unmanaged<EqualizerTap>.fromOpaque(MTAudioProcessingTapGetStorage(tap))
                    .takeUnretainedValue()
                    .process(UnsafeMutableAudioBufferListPointer(bufferList), frames: Int(framesOut.pointee), mediaTimeUs: us)
            }
        )
        guard let out = adopt(create(MTAudioProcessingTapCreate, &callbacks)) else {
            Unmanaged.passUnretained(tap).release()
            return
        }
        let parameters = AVMutableAudioMixInputParameters(track: track)
        parameters.audioTapProcessor = out
        let mix = AVMutableAudioMix()
        mix.inputParameters = [parameters]
        item.audioMix = mix
    }
}

// MTAudioProcessingTapCreate's out-parameter is `Unmanaged<MTAudioProcessingTap>?` in the
// Xcode 16 SDK (CI) and `MTAudioProcessingTap?` in newer ones; inferring T compiles on both.
private func create<T>(
    _ make: (CFAllocator?, UnsafePointer<MTAudioProcessingTapCallbacks>, MTAudioProcessingTapCreationFlags, UnsafeMutablePointer<T?>) -> OSStatus,
    _ callbacks: UnsafePointer<MTAudioProcessingTapCallbacks>
) -> T? {
    var out: T?
    return make(kCFAllocatorDefault, callbacks, kMTAudioProcessingTapCreationFlag_PostEffects, &out) == noErr ? out : nil
}

private func adopt(_ tap: Unmanaged<MTAudioProcessingTap>?) -> MTAudioProcessingTap? { tap?.takeRetainedValue() }
private func adopt(_ tap: MTAudioProcessingTap?) -> MTAudioProcessingTap? { tap }
