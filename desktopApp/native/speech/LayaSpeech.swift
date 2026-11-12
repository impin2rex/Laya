// Voice search on macOS through Apple's own recognizer — the Speech framework
// Siri and Dictation use. Built into liblaya_speech.dylib and called from the
// JVM through JNA, so the API is plain C: start with languages and a
// callback, stop. Nothing here is bundled beyond this code: the recognition
// itself is the system's.
//
// Apple's recognizer hears one language per task, so to tell languages apart
// it runs one task per candidate language on the same microphone audio, and
// keeps the one most confident in what it heard.

import AVFoundation
import Foundation
import Speech

/// kind: 0 = words heard so far, 1 = final transcript, 2 = recognizer
/// unavailable for this language, 3 = microphone or speech access denied.
public typealias LayaSpeechCallback = @convention(c) (Int32, UnsafePointer<CChar>?) -> Void

/// One language's recognition of the shared audio.
private final class Lane {
    let request = SFSpeechAudioBufferRecognitionRequest()
    var task: SFSpeechRecognitionTask?
    var text = ""
    var confidence: Float = 0
    var done = false

    init() {
        request.shouldReportPartialResults = true
        request.taskHint = .search
    }
}

private final class Listener {
    static let shared = Listener()

    private let engine = AVAudioEngine()
    private var lanes: [Lane] = []
    private var finishing = false
    private var silence: DispatchWorkItem?
    private var callback: LayaSpeechCallback?
    private let queue = DispatchQueue(label: "laya.speech")

    /// [tags]: the language to show while listening first, then any others
    /// to listen for at the same time.
    func start(tags: [String], callback: @escaping LayaSpeechCallback) {
        queue.async {
            self.cancelLocked()
            self.callback = callback
            // Without these keys TCC kills the process outright rather than
            // asking — the case when run from Gradle instead of the .app.
            let info = Bundle.main.infoDictionary ?? [:]
            guard info["NSSpeechRecognitionUsageDescription"] != nil,
                  info["NSMicrophoneUsageDescription"] != nil
            else { return self.emit(2, nil) }
            let recognizers = Self.recognizers(for: tags)
            guard !recognizers.isEmpty else { return self.emit(2, nil) }

            SFSpeechRecognizer.requestAuthorization { status in
                guard status == .authorized else { return self.emit(3, nil) }
                AVCaptureDevice.requestAccess(for: .audio) { granted in
                    self.queue.async {
                        guard granted else { return self.emit(3, nil) }
                        if !self.begin(recognizers) { self.emit(2, nil) }
                    }
                }
            }
        }
    }

    func stop() { queue.async { self.finishLocked() } }

    private func begin(_ recognizers: [SFSpeechRecognizer]) -> Bool {
        let lanes = recognizers.map { _ in Lane() }
        let input = engine.inputNode
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { buffer, _ in
            for lane in lanes { lane.request.append(buffer) }
        }
        engine.prepare()
        do { try engine.start() } catch {
            input.removeTap(onBus: 0)
            return false
        }
        self.lanes = lanes
        finishing = false
        arm(seconds: 6)
        for (recognizer, lane) in zip(recognizers, lanes) {
            lane.task = recognizer.recognitionTask(with: lane.request) { result, error in
                self.queue.async { self.heard(lane, result, error) }
            }
        }
        return true
    }

    private func heard(_ lane: Lane, _ result: SFSpeechRecognitionResult?, _ error: Error?) {
        guard lanes.contains(where: { $0 === lane }) else { return }
        if let result {
            lane.text = result.bestTranscription.formattedString
            if result.isFinal {
                lane.done = true
                let segments = result.bestTranscription.segments
                lane.confidence = segments.isEmpty ? 0 : segments.map(\.confidence).reduce(0, +) / Float(segments.count)
            } else if !finishing, !lane.text.isEmpty {
                // Live words from the first language, or from whichever has any.
                let shown = lanes.first(where: { !$0.text.isEmpty })
                if shown === lane { emit(0, lane.text) }
                // A short pause ends the query, as on iOS.
                arm(seconds: 1.5)
            }
        }
        if error != nil { lane.done = true }
        // One language finishing with words ends the query; one giving up
        // empty-handed (no speech it recognised) leaves the others listening.
        if lane.done && !finishing && (!lane.text.isEmpty || lanes.allSatisfy(\.done)) { finishLocked() }
        if finishing && lanes.allSatisfy(\.done) { deliverLocked() }
    }

    private func arm(seconds: Double) {
        silence?.cancel()
        let item = DispatchWorkItem { [weak self] in self?.finishLocked() }
        silence = item
        queue.asyncAfter(deadline: .now() + seconds, execute: item)
    }

    /// Stops listening and asks every language for its final answer; they're
    /// compared once all are in, or after a short wait for stragglers.
    private func finishLocked() {
        guard !lanes.isEmpty, !finishing else { return }
        finishing = true
        silence?.cancel()
        silence = nil
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        lanes.forEach { $0.request.endAudio() }
        let waiting = lanes
        queue.asyncAfter(deadline: .now() + 2) { [weak self] in
            guard let self, self.lanes.first === waiting.first else { return }
            self.deliverLocked()
        }
        if lanes.allSatisfy(\.done) { deliverLocked() }
    }

    /// The most confident language wins; one that never finished counts as
    /// unsure. An empty final still goes out, so the caller stops listening.
    private func deliverLocked() {
        guard !lanes.isEmpty else { return }
        let heard = lanes.filter { !$0.text.isEmpty }
        let best = heard.max { ($0.done ? $0.confidence : 0) < ($1.done ? $1.confidence : 0) }
        let text = (best ?? heard.first)?.text.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        lanes.forEach { $0.task?.cancel() }
        lanes = []
        finishing = false
        emit(1, text)
    }

    private func cancelLocked() {
        guard !lanes.isEmpty else { return }
        silence?.cancel()
        if !finishing {
            engine.stop()
            engine.inputNode.removeTap(onBus: 0)
        }
        lanes.forEach { $0.task?.cancel() }
        lanes = []
        finishing = false
    }

    private func emit(_ kind: Int32, _ text: String?) {
        guard let callback else { return }
        if let text { text.withCString { callback(kind, $0) } } else { callback(kind, nil) }
    }

    /// A recognizer per tag this Mac supports, one per language. Apple names
    /// Bangla by country differently across versions, so any variant of a
    /// language will do.
    private static func recognizers(for tags: [String]) -> [SFSpeechRecognizer] {
        let ids = SFSpeechRecognizer.supportedLocales().map { $0.identifier.replacingOccurrences(of: "_", with: "-") }
        var languages = Set<String>()
        return tags.compactMap { tag in
            let lang = tag.split(separator: "-").first.map(String.init) ?? tag
            guard !languages.contains(lang),
                  let id = ids.first(where: { $0.caseInsensitiveCompare(tag) == .orderedSame })
                    ?? ids.first(where: { $0.hasPrefix(lang + "-") }),
                  let recognizer = SFSpeechRecognizer(locale: Locale(identifier: id)),
                  recognizer.isAvailable
            else { return nil }
            languages.insert(lang)
            return recognizer
        }
    }
}

/// [tags]: comma-separated BCP-47 tags, the one to show live first.
@_cdecl("laya_speech_start")
public func laya_speech_start(_ tags: UnsafePointer<CChar>, _ callback: LayaSpeechCallback) {
    Listener.shared.start(tags: String(cString: tags).split(separator: ",").map(String.init), callback: callback)
}

/// The languages (en, hi, …) this Mac's recognizer has, comma-separated.
/// Freed by nobody: called once per run.
@_cdecl("laya_speech_languages")
public func laya_speech_languages() -> UnsafeMutablePointer<CChar>? {
    let languages = Set(SFSpeechRecognizer.supportedLocales().map { $0.identifier.split(whereSeparator: { $0 == "_" || $0 == "-" }).first.map(String.init) ?? "" })
    return strdup(languages.sorted().joined(separator: ","))
}

@_cdecl("laya_speech_stop")
public func laya_speech_stop() {
    Listener.shared.stop()
}
