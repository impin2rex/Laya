import AVFoundation
import Foundation
import LayaKit

/// The lyrics share video: the frames Kotlin draws (IosLyricsVideo.kt) written as H.264 by an
/// AVAssetWriter, then the song's audio — decoded through Karaoke's PcmReader from the stream the
/// player opened, cut to the picked stretches and encoded to AAC — muxed under them by a passthrough
/// export. Every call blocks; Kotlin makes them from a worker thread.
final class LyricsVideoNativeImpl: NSObject, LyricsVideoNative {
    private var writer: AVAssetWriter?
    private var input: AVAssetWriterInput?
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var outPath = ""
    private var width = 0
    private var height = 0
    private var fps: Int32 = 30
    private var frames: Int64 = 0
    private var hadAudio = false

    func lastFinishHadAudio() -> Bool { hadAudio }

    private var silentURL: URL { URL(fileURLWithPath: outPath + ".video.mp4") }
    private var audioURL: URL { URL(fileURLWithPath: outPath + ".audio.m4a") }

    func begin(path: String, width: Int32, height: Int32, fps: Int32) -> Bool {
        cancel()
        outPath = path
        self.width = Int(width)
        self.height = Int(height)
        self.fps = fps
        frames = 0
        try? FileManager.default.removeItem(at: silentURL)
        guard let writer = try? AVAssetWriter(outputURL: silentURL, fileType: .mp4) else { return false }
        let settings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: Int(width),
            AVVideoHeightKey: Int(height),
            // Comfortably past what Instagram re-encodes a 1080p story to (Android's VIDEO_BITRATE).
            AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 10_000_000],
        ]
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: Int(width),
            kCVPixelBufferHeightKey as String: Int(height),
        ])
        guard writer.canAdd(input) else { return false }
        writer.add(input)
        guard writer.startWriting() else { return false }
        writer.startSession(atSourceTime: .zero)
        self.writer = writer
        self.input = input
        self.adaptor = adaptor
        return true
    }

    func append(pixels: UnsafeMutableRawPointer, index: Int32) -> Bool {
        autoreleasepool {
            guard let writer, let input, let adaptor else { return false }
            while !input.isReadyForMoreMediaData {
                if writer.status != .writing { return false }
                Thread.sleep(forTimeInterval: 0.005)
            }
            guard writer.status == .writing, let pool = adaptor.pixelBufferPool else { return false }
            var made: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &made)
            guard let buffer = made else { return false }
            CVPixelBufferLockBaseAddress(buffer, [])
            if let base = CVPixelBufferGetBaseAddress(buffer) {
                let dstRow = CVPixelBufferGetBytesPerRow(buffer)
                let srcRow = width * 4
                for y in 0..<height {
                    memcpy(base + y * dstRow, pixels + y * srcRow, srcRow)
                }
            }
            CVPixelBufferUnlockBaseAddress(buffer, [])
            frames = Int64(index) + 1
            return adaptor.append(buffer, withPresentationTime: CMTime(value: Int64(index), timescale: fps))
        }
    }

    func finish(audioUrl: String?, headers: [String: String], mimeType: String, contentLength: Int64,
                chunkBytes: Int64, segmentsMs: [KotlinLong]) -> Bool {
        hadAudio = false
        guard let writer, let input else { return false }
        input.markAsFinished()
        let done = DispatchSemaphore(value: 0)
        writer.finishWriting { done.signal() }
        done.wait()
        self.writer = nil
        self.input = nil
        self.adaptor = nil
        guard writer.status == .completed else { return false }

        let out = URL(fileURLWithPath: outPath)
        try? FileManager.default.removeItem(at: out)
        let segments = stride(from: 0, to: segmentsMs.count - 1, by: 2).map {
            (segmentsMs[$0].int64Value, segmentsMs[$0 + 1].int64Value)
        }
        if let audioUrl, let source = URL(string: audioUrl), writeAudio(source, headers, mimeType, contentLength, chunkBytes, segments),
           mux(audio: audioURL, into: out) {
            hadAudio = true
            cleanUp()
            return true
        }
        // No song to cut from (or it would not decode): the silent video is still a video.
        print("LyricsVideo: no audio, sharing the video silent")
        do { try FileManager.default.moveItem(at: silentURL, to: out) } catch { return false }
        cleanUp()
        return true
    }

    func cancel() {
        input?.markAsFinished()
        writer?.cancelWriting()
        writer = nil
        input = nil
        adaptor = nil
        cleanUp()
    }

    private func cleanUp() {
        try? FileManager.default.removeItem(at: silentURL)
        try? FileManager.default.removeItem(at: audioURL)
    }

    /// The stretches of [source], back to back, as AAC: 44.1 kHz stereo, as PcmReader decodes.
    private func writeAudio(_ source: URL, _ headers: [String: String], _ mime: String, _ length: Int64,
                            _ chunk: Int64, _ segments: [(Int64, Int64)]) -> Bool {
        guard !segments.isEmpty,
              let reader = PcmReader(source: source, headers: headers, mimeType: mime, contentLength: length, chunkBytes: chunk)
        else { return false }
        defer { reader.close() }
        try? FileManager.default.removeItem(at: audioURL)
        let rate = 44_100.0
        guard let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 2),
              let file = try? AVAudioFile(forWriting: audioURL, settings: [
                  AVFormatIDKey: kAudioFormatMPEG4AAC,
                  AVSampleRateKey: rate,
                  AVNumberOfChannelsKey: 2,
                  AVEncoderBitRateKey: 256_000,
              ], commonFormat: .pcmFormatFloat32, interleaved: false)
        else { return false }
        let block = 16_384
        let interleaved = UnsafeMutablePointer<Float>.allocate(capacity: block * 2)
        defer { interleaved.deallocate() }
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(block)),
              let left = buffer.floatChannelData?[0], let right = buffer.floatChannelData?[1]
        else { return false }
        var wrote: Int64 = 0
        for (startMs, endMs) in segments {
            let from = startMs * Int64(rate) / 1000
            var remaining = (endMs - startMs) * Int64(rate) / 1000
            guard reader.seek(frame: from) >= 0 else { return false }
            while remaining > 0 {
                let want = Int32(min(Int64(block), remaining))
                let got = Int(reader.read(into: UnsafeMutableRawPointer(interleaved), maxFrames: want))
                if got <= 0 { break }
                for i in 0..<got {
                    left[i] = interleaved[2 * i]
                    right[i] = interleaved[2 * i + 1]
                }
                buffer.frameLength = AVAudioFrameCount(got)
                do { try file.write(from: buffer) } catch { return false }
                remaining -= Int64(got)
                wrote += Int64(got)
            }
        }
        return wrote > 0
    }

    /// The silent video with [audio] under it, both copied as they are (no second encode).
    private func mux(audio: URL, into out: URL) -> Bool {
        let video = AVURLAsset(url: silentURL)
        let sound = AVURLAsset(url: audio)
        guard let videoTrack = video.tracks(withMediaType: .video).first,
              let audioTrack = sound.tracks(withMediaType: .audio).first
        else { return false }
        let composition = AVMutableComposition()
        let length = video.duration
        do {
            try composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)?
                .insertTimeRange(CMTimeRange(start: .zero, duration: length), of: videoTrack, at: .zero)
            try composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)?
                .insertTimeRange(CMTimeRange(start: .zero, duration: CMTimeMinimum(length, sound.duration)), of: audioTrack, at: .zero)
        } catch {
            print("LyricsVideo: compose failed: \(error)")
            return false
        }
        guard let export = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetPassthrough) else { return false }
        export.outputURL = out
        export.outputFileType = .mp4
        let done = DispatchSemaphore(value: 0)
        export.exportAsynchronously { done.signal() }
        done.wait()
        if export.status != .completed { print("LyricsVideo: export failed: \(String(describing: export.error))") }
        return export.status == .completed
    }
}
