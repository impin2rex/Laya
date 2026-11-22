import AVFoundation
import Foundation

/// Serves AVPlayer's byte-range requests from googlevideo in bounded chunks,
/// sending the minting client's headers on each.
final class ChunkedResourceLoader: NSObject, AVAssetResourceLoaderDelegate {
    static let scheme = "bitchord-stream"

    let queue = DispatchQueue(label: "bitchord.resource-loader")
    let proxyURL: URL
    var onHTTPError: ((Int) -> Void)?
    private(set) var reportedHTTPError = false
    /// The stream's size once a response has stated it (or the caller knew it).
    var streamLength: Int64? { knownLength }
    /// FLAC's STREAMINFO as read from the bytes actually served, set when the first chunk lands.
    var flacInfo: FlacInfo? { chunkFlac ?? entry?.flacInfo }
    private var chunkFlac: FlacInfo?

    struct FlacInfo { let sampleRate: Int; let channels: Int; let bitDepth: Int; let totalSamples: Int64 }

    /// "fLaC" + metadata header + STREAMINFO: rate (20 bits), channels-1 (3), bits-1 (5), samples (36).
    static func parseFlac(_ data: Data) -> FlacInfo? {
        let b = [UInt8](data.prefix(26))
        guard b.count >= 26, b[0] == 0x66, b[1] == 0x4C, b[2] == 0x61, b[3] == 0x43 else { return nil }
        let rate = Int(b[18]) << 12 | Int(b[19]) << 4 | Int(b[20]) >> 4
        let samples = Int64(b[21] & 0x0F) << 32 | Int64(b[22]) << 24 | Int64(b[23]) << 16 | Int64(b[24]) << 8 | Int64(b[25])
        return FlacInfo(sampleRate: rate, channels: Int((b[20] >> 1) & 7) + 1,
                        bitDepth: Int((b[20] & 1) << 4 | b[21] >> 4) + 1, totalSamples: samples)
    }

    private let source: URL
    private let headers: [String: String]
    let mimeType: String
    private(set) var knownLength: Int64?
    private let chunkBytes: Int64
    private var session: URLSession!
    private var tasks: [ObjectIdentifier: URLSessionDataTask] = [:]
    private var cancelled: Set<ObjectIdentifier> = []
    /// Set once the session is invalidated; creating a task after that throws.
    private var invalidated = false

    init(source: URL, headers: [String: String], mimeType: String, knownLength: Int64?, chunkBytes: Int64) {
        self.source = source
        self.headers = headers
        self.mimeType = mimeType
        self.knownLength = knownLength
        self.chunkBytes = chunkBytes
        var components = URLComponents(url: source, resolvingAgainstBaseURL: false)!
        components.scheme = ChunkedResourceLoader.scheme
        self.proxyURL = components.url!
        super.init()
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 20
        config.httpMaximumConnectionsPerHost = 2
        self.session = URLSession(configuration: config)
    }

    /// The file's last byte has been handed to AVPlayer: whatever it still waits for after this does not exist.
    private(set) var deliveredEnd = false

    /// googlevideo states the track's length in seconds as `dur=`. AVFoundation misreads some of
    /// those fragmented MP4s (itag 140 from the VISIONOS client: 215.7 s reported as 431.5 s, even
    /// from a local file, while AudioToolbox agrees with `dur`), so this is the length to show.
    /// Addon streams carry none.
    var durationHint: Double? {
        URLComponents(url: source, resolvingAgainstBaseURL: false)?
            .queryItems?.first { $0.name == "dur" }?.value.flatMap(Double.init)
    }

    /// The item reading through this was opened with AVURLAssetPreferPreciseDurationAndTimingKey. Main thread.
    var precise = false
    /// Seeked while not precise: the item's clock has been AVFoundation's estimate since. Main thread.
    var estimatedSeek = false
    /// A FLAC served from StreamCache whose file is now all on disk, so an asset over it can be precise.
    var flacOnDisk: Bool { streaming && flacInfo != nil && (entry?.state.complete ?? false) }

    /// A new loader over the same stream, for an item reopening it.
    func reopened() -> ChunkedResourceLoader {
        ChunkedResourceLoader(source: source, headers: headers, mimeType: mimeType, knownLength: knownLength, chunkBytes: chunkBytes)
    }

    func cancelAll() {
        queue.async {
            self.invalidated = true
            self.tasks.values.forEach { $0.cancel() }
            self.tasks.removeAll()
            self.remuxTask?.cancel()
            self.remuxWaiters.removeAll()
            self.session.invalidateAndCancel()
            self.releaseStreaming()
        }
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        let key = ObjectIdentifier(loadingRequest)
        cancelled.remove(key)

        if isWebmOpus { remuxWaiters.append(loadingRequest); serveRemuxed(); return true }

        if let info = loadingRequest.contentInformationRequest {
            info.contentType = uti(for: mimeType)
            info.isByteRangeAccessSupported = true
            if let length = knownLength {
                info.contentLength = length
            }
        }

        guard let dataRequest = loadingRequest.dataRequest else {
            if knownLength != nil { loadingRequest.finishLoading() } else { fetch(loadingRequest, from: 0, end: 1) }
            return true
        }

        let start = dataRequest.requestedOffset
        var end: Int64
        if dataRequest.requestsAllDataToEndOfResource {
            end = (knownLength ?? Int64.max) - 1
        } else {
            end = start + Int64(dataRequest.requestedLength) - 1
        }
        if let length = knownLength { end = min(end, length - 1) }
        fetch(loadingRequest, from: start, end: end)
        return true
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        didCancel loadingRequest: AVAssetResourceLoadingRequest
    ) {
        let key = ObjectIdentifier(loadingRequest)
        cancelled.insert(key)
        remuxWaiters.removeAll { $0 === loadingRequest }
        tasks.removeValue(forKey: key)?.cancel()
        if let s = serves.removeValue(forKey: key), let t = s.token { entry?.cancelWait(t) }
    }

    /// Fetches [from, end] one chunk at a time, handing each to AVPlayer as it lands.
    /// A chunk that fails for a passing reason (dropped connection, timeout, 5xx, 429) is asked
    /// again before the item is failed: one bad range must not end the whole track.
    private static let retryDelays: [Double] = [0.5, 1, 2, 4]
    private static let throttleDelays: [Double] = [1, 2, 4, 8, 16]
    /// Test hook (env only): this many requests past the first 2 MB fail, as a dropped connection or as LAYA_FAIL_STATUS.
    private static var injectedFailures = Int(ProcessInfo.processInfo.environment["LAYA_FAIL_RANGE"] ?? "") ?? 0
    private static let injectedStatus = Int(ProcessInfo.processInfo.environment["LAYA_FAIL_STATUS"] ?? "")

    private func retry(_ loadingRequest: AVAssetResourceLoadingRequest, from start: Int64, end: Int64, attempt: Int) -> Bool {
        guard attempt < Self.retryDelays.count else { return false }
        print("Loader: range at \(start) failed, retry \(attempt + 1)")
        queue.asyncAfter(deadline: .now() + Self.retryDelays[attempt]) {
            self.fetch(loadingRequest, from: start, end: end, attempt: attempt + 1)
        }
        return true
    }

    private func fetch(_ loadingRequest: AVAssetResourceLoadingRequest, from start: Int64, end: Int64, attempt: Int = 0) {
        if streaming { return stream(loadingRequest, from: start, end: end, attempt: attempt) }
        let key = ObjectIdentifier(loadingRequest)
        if invalidated || cancelled.contains(key) || loadingRequest.isCancelled { return }
        if start > end {
            loadingRequest.finishLoading()
            tasks.removeValue(forKey: key)
            return
        }
        let chunkEnd = min(end, start + chunkBytes - 1)

        var request = URLRequest(url: source)
        request.setValue("bytes=\(start)-\(chunkEnd)", forHTTPHeaderField: "Range")
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }

        let task = session.dataTask(with: request) { [weak self] data, response, error in
            guard let self else { return }
            var error = error
            var injected: Int?
            if start > 2_000_000, Self.injectedFailures > 0 {
                Self.injectedFailures -= 1
                injected = Self.injectedStatus
                if injected == nil { error = NSError(domain: NSURLErrorDomain, code: NSURLErrorNetworkConnectionLost) }
                print("Loader: injected failure at \(start) (attempt \(attempt))")
            }
            self.queue.async {
                if self.cancelled.contains(key) || loadingRequest.isCancelled { return }
                if let error {
                    if (error as NSError).code == NSURLErrorCancelled { return }
                    if self.retry(loadingRequest, from: start, end: end, attempt: attempt) { return }
                    loadingRequest.finishLoading(with: error)
                    self.tasks.removeValue(forKey: key)
                    return
                }
                let http = response as? HTTPURLResponse
                let status = injected ?? http?.statusCode ?? 0
                guard injected == nil, (200...299).contains(status), let data else {
                    if status == 429 || status >= 500 || status == 0,
                       self.retry(loadingRequest, from: start, end: end, attempt: attempt) { return }
                    if [403, 404, 410].contains(status), !self.reportedHTTPError {
                        self.reportedHTTPError = true
                        self.onHTTPError?(status)
                    }
                    loadingRequest.finishLoading(with: NSError(
                        domain: "BitChord", code: status,
                        userInfo: [NSLocalizedDescriptionKey: "HTTP \(status)"]
                    ))
                    self.tasks.removeValue(forKey: key)
                    return
                }
                if start == 0, self.chunkFlac == nil { self.chunkFlac = Self.parseFlac(data) }
                if self.knownLength == nil, let total = Self.totalLength(http) {
                    self.knownLength = total
                    loadingRequest.contentInformationRequest?.contentLength = total
                }
                if loadingRequest.contentInformationRequest != nil && loadingRequest.dataRequest == nil {
                    loadingRequest.finishLoading()
                    self.tasks.removeValue(forKey: key)
                    return
                }
                loadingRequest.dataRequest?.respond(with: data)
                let next = start + Int64(data.count)
                let finalEnd = min(end, (self.knownLength ?? Int64.max) - 1)
                if let length = self.knownLength, next >= length { self.deliveredEnd = true }
                if data.isEmpty || next > finalEnd {
                    loadingRequest.finishLoading()
                    self.tasks.removeValue(forKey: key)
                } else {
                    self.fetch(loadingRequest, from: next, end: finalEnd)
                }
            }
        }
        tasks[key] = task
        task.resume()
    }

    // MARK: - WebM Opus (YouTube Premium, itag 774)

    /// AVFoundation cannot read WebM, so the Opus packets are rewritten into a CAF (OpusRemux) and AVPlayer is
    /// served that. CAF needs its packet table up front, so a growing CAF is not possible; instead playback starts
    /// on a head CAF of the first `headPackets` (40 s) as soon as they have arrived, while the rest keeps
    /// downloading. When the whole file is down the full CAF is ready: `onUpgrade` tells the engine, which swaps
    /// to a loader reopened with it (`reopenedWhole`) gaplessly. Until then the head is all there is: a seek past
    /// it waits for the upgrade (see `headOnlyState`).
    private var isWebmOpus: Bool { mimeType.lowercased().hasPrefix("audio/webm") }
    private var remuxWaiters: [AVAssetResourceLoadingRequest] = []
    private var remuxTask: URLSessionDataTask?
    private var remuxStarted = false
    /// The CAF AVPlayer is being served: the head while `headOnly`, else the whole file.
    private var caf: Data?
    private var remuxError: Error?
    private var webm = Data()
    private var headTried = 0
    private var headMs: Int64 = 0
    private var headOnly = false
    private var fullCaf: Data?

    /// A head is cut once this many 20 ms packets (40 s) are in.
    private static let headPackets = 40 * 50
    /// Only retry the head after this much more has arrived: each try re-reads it from the start.
    private static let headRetryBytes = 256 * 1024

    /// Called on the loader's queue once the whole file is remuxed and the playing item is only the head.
    var onUpgrade: (() -> Void)?
    /// Set by the engine for a seek that landed past the head: made once the whole file is in.
    var deferredSeekMs: Int64?
    var deferredResume = false

    /// The head's audio length and whether the whole file is ready, or nil once the item is the whole file.
    var headOnlyState: (ms: Int64, ready: Bool)? {
        queue.sync { headOnly ? (headMs, fullCaf != nil) : nil }
    }

    /// A loader over the same stream that is handed the finished CAF, for the item taking over from the head.
    func reopenedWhole() -> ChunkedResourceLoader? {
        queue.sync {
            guard let full = fullCaf else { return nil }
            let fresh = ChunkedResourceLoader(source: source, headers: headers, mimeType: mimeType,
                                              knownLength: Int64(full.count), chunkBytes: chunkBytes)
            fresh.caf = full
            fresh.remuxStarted = true
            return fresh
        }
    }

    private func serveRemuxed() {
        if let caf {
            for lr in remuxWaiters { answer(lr, from: caf) }
            remuxWaiters.removeAll()
        } else if let remuxError {
            for lr in remuxWaiters { lr.finishLoading(with: remuxError) }
            remuxWaiters.removeAll()
        } else if !remuxStarted {
            remuxStarted = true
            downloadWebm(attempt: 0)
        }
    }

    private func answer(_ lr: AVAssetResourceLoadingRequest, from caf: Data) {
        guard !lr.isCancelled else { return }
        if let info = lr.contentInformationRequest {
            info.contentType = "com.apple.coreaudio-format"
            info.isByteRangeAccessSupported = true
            info.contentLength = Int64(caf.count)
        }
        if let data = lr.dataRequest {
            let start = Int(min(Int64(caf.count), data.requestedOffset))
            let end = data.requestsAllDataToEndOfResource ? caf.count : min(caf.count, start + data.requestedLength)
            data.respond(with: caf.subdata(in: start ..< end))
            // The head's end is not the track's: only the whole file reaching AVPlayer means it has all been delivered.
            if end >= caf.count, !headOnly { deliveredEnd = true }
        }
        lr.finishLoading()
    }

    /// Appends the next range of the stream to `webm` until the file is whole.
    private func downloadWebm(attempt: Int) {
        if invalidated { return }
        let start = Int64(webm.count)
        var request = URLRequest(url: source)
        request.setValue("bytes=\(start)-\(start + chunkBytes - 1)", forHTTPHeaderField: "Range")
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        let task = session.dataTask(with: request) { [weak self] data, response, error in
            guard let self else { return }
            self.queue.async {
                if self.invalidated || (error as NSError?)?.code == NSURLErrorCancelled { return }
                let http = response as? HTTPURLResponse
                let status = http?.statusCode ?? 0
                guard error == nil, (200...299).contains(status), let data else {
                    if status == 416, !self.webm.isEmpty { return self.finishRemux() } // asked past the end
                    if error != nil || status == 429 || status >= 500 || status == 0, attempt < Self.retryDelays.count {
                        self.queue.asyncAfter(deadline: .now() + Self.retryDelays[attempt]) {
                            self.downloadWebm(attempt: attempt + 1)
                        }
                        return
                    }
                    if [403, 404, 410].contains(status), !self.reportedHTTPError {
                        self.reportedHTTPError = true
                        self.onHTTPError?(status)
                    }
                    return self.failRemux(NSError(domain: "BitChord", code: status,
                                                  userInfo: [NSLocalizedDescriptionKey: "HTTP \(status)"]))
                }
                self.webm.append(data)
                let total = Self.totalLength(http) ?? (status == 200 ? Int64(self.webm.count) : nil)
                if data.isEmpty || total.map({ Int64(self.webm.count) >= $0 }) ?? false {
                    self.finishRemux()
                } else {
                    self.serveHeadIfReady()
                    self.downloadWebm(attempt: 0)
                }
            }
        }
        remuxTask = task
        task.resume()
    }

    /// Starts playback on the first packets while the rest downloads.
    private func serveHeadIfReady() {
        guard caf == nil, webm.count - headTried >= Self.headRetryBytes else { return }
        headTried = webm.count
        guard let head = OpusRemux.head(of: webm, packets: Self.headPackets) else { return }
        caf = head.caf
        headMs = head.durationMs
        headOnly = true
        knownLength = Int64(head.caf.count)
        serveRemuxed()
    }

    private func finishRemux() {
        let bytes = webm
        webm = Data()
        guard let converted = OpusRemux.cafFromWebm(bytes) else {
            return failRemux(NSError(domain: "BitChord", code: -1,
                                     userInfo: [NSLocalizedDescriptionKey: "Premium audio is not a readable WebM Opus stream"]))
        }
        if headOnly {
            // AVPlayer holds the head's length and bytes; the engine swaps in a loader that serves this.
            fullCaf = converted
            onUpgrade?()
        } else {
            caf = converted
            knownLength = Int64(converted.count)
            serveRemuxed()
        }
    }

    private func failRemux(_ error: Error) {
        // After the head started, the engine keeps playing it; a failure then only costs the upgrade.
        if headOnly { return print("Loader: Premium download failed after the head started: \(error.localizedDescription)") }
        remuxError = error
        serveRemuxed()
    }

    // MARK: - Streaming (any host but googlevideo)

    /// PlayerClient.rangeBytesFor gives no bound for hosts other than googlevideo. Those are served from StreamCache's
    /// one sequential download per URL (shared with the Automix / karaoke / alignment readers): bytes already on disk
    /// are answered at once, later ones as they arrive. A seek far ahead waits for the download to reach it.
    /// ponytail: no second ranged request for a far seek (FLAC is 30-100 MB, seconds on Wi-Fi); add one if that wait proves too long.
    private var streaming: Bool { chunkBytes >= Int64.max / 2 }

    private final class Serve {
        let request: AVAssetResourceLoadingRequest
        var next: Int64
        var end: Int64 // inclusive; Int64.max - 1 = to the end of the file
        var token: Int?
        init(_ request: AVAssetResourceLoadingRequest, from: Int64, end: Int64) { self.request = request; next = from; self.end = end }
    }

    private var serves: [ObjectIdentifier: Serve] = [:]
    private var entry: StreamCache.Entry?
    private var released = false

    private func stream(_ lr: AVAssetResourceLoadingRequest, from start: Int64, end: Int64, attempt: Int) {
        if entry == nil { entry = StreamCache.shared.acquire(source, headers: headers) }
        let s = Serve(lr, from: start, end: end)
        serves[ObjectIdentifier(lr)] = s
        pump(s)
    }

    private func finish(_ s: Serve, error: Error? = nil) {
        let key = ObjectIdentifier(s.request)
        serves[key] = nil
        if let error { s.request.finishLoading(with: error) } else { s.request.finishLoading() }
    }

    private func pump(_ s: Serve) {
        let key = ObjectIdentifier(s.request)
        guard !invalidated, serves[key] === s, !cancelled.contains(key), !s.request.isCancelled, let entry else { serves[key] = nil; return }
        s.token = nil
        let st = entry.state
        if knownLength == nil { knownLength = st.length }
        if let length = knownLength {
            s.request.contentInformationRequest?.contentLength = length
            s.end = min(s.end, length - 1)
        }
        let refused = st.status != 0 && st.written <= s.next
        if refused { return fail(s, st.status) }
        guard s.request.contentInformationRequest == nil || knownLength != nil || st.complete else { return waitFor(s, entry, need: 1) }
        guard let data = s.request.dataRequest else { return finish(s) } // only the type-and-length ask
        while s.next <= s.end {
            let chunk = entry.read(at: s.next, count: Int(min(256 * 1024, s.end - s.next + 1)))
            if chunk.isEmpty { break }
            data.respond(with: chunk)
            s.next += Int64(chunk.count)
            if let length = knownLength, s.next >= length { deliveredEnd = true }
        }
        let now = entry.state
        if s.next > s.end || (now.complete && s.next >= now.written) { return finish(s) }
        if now.status != 0 && now.written <= s.next { return fail(s, now.status) }
        waitFor(s, entry, need: s.next + 1)
    }

    private func waitFor(_ s: Serve, _ entry: StreamCache.Entry, need: Int64) {
        s.token = entry.wait(for: need, on: queue) { [weak self, weak s] in if let self, let s { self.pump(s) } }
    }

    private func fail(_ s: Serve, _ status: Int) {
        if [403, 404, 410].contains(status), !reportedHTTPError {
            reportedHTTPError = true
            onHTTPError?(status)
        }
        finish(s, error: NSError(domain: "BitChord", code: status, userInfo: [NSLocalizedDescriptionKey: "HTTP \(status)"]))
    }

    private func releaseStreaming() {
        serves.values.forEach { s in s.token.map { entry?.cancelWait($0) } }
        serves.removeAll()
        if !released { released = true; entry?.release() }
    }

    /// "bytes 0-1023/4567890" → 4567890
    private static func totalLength(_ response: HTTPURLResponse?) -> Int64? {
        guard let range = response?.value(forHTTPHeaderField: "Content-Range"),
              let slash = range.lastIndex(of: "/") else { return nil }
        return Int64(range[range.index(after: slash)...])
    }

    private func uti(for mime: String) -> String {
        let base = mime.split(separator: ";").first.map(String.init)?.trimmingCharacters(in: .whitespaces) ?? ""
        switch base {
        case "audio/mp4", "audio/m4a", "audio/x-m4a": return AVFileType.m4a.rawValue
        case "audio/mpeg": return AVFileType.mp3.rawValue
        case "audio/aac": return "public.aac-audio"
        // Addon streams: FLAC plays natively from iOS 11 (ALAC arrives as audio/mp4 above).
        case "audio/flac", "audio/x-flac": return "org.xiph.flac"
        case "audio/wav", "audio/x-wav": return AVFileType.wav.rawValue
        case "audio/aiff", "audio/x-aiff": return AVFileType.aiff.rawValue
        default: return AVFileType.m4a.rawValue
        }
    }
}
