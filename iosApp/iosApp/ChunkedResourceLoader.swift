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

/// Hands AVPlayer an HLS playlist it refuses to read from disk: a file URL is rejected outright
/// (-11800, no tracks), while the identical bytes under a scheme of our own load. Tidal's lossless
/// arrives as a DASH manifest that DashToHls rewrites into one of these, pointing at absolute CDN
/// segment URLs, so AVFoundation fetches the audio itself and only the playlist comes through here.
/// An item playing a Tidal playlist, one way or the other: the engine swaps it for the assembled file once that is in.
protocol PlaylistBacked: AnyObject {
    /// The playlist file this was made from, so the engine can ask [HlsAssembly] about the same track.
    var source: URL { get }
    /// The same track as one file, once HlsAssembly has it.
    var assembled: URL? { get set }
}

final class PlaylistResourceLoader: NSObject, AVAssetResourceLoaderDelegate, PlaylistBacked {
    static let scheme = "bitchord-playlist"

    let queue = DispatchQueue(label: "bitchord.playlist-loader")
    let proxyURL: URL
    let source: URL
    var assembled: URL?
    private let playlist: Data

    init?(file: URL) {
        guard let data = try? Data(contentsOf: file),
              var components = URLComponents(url: file, resolvingAgainstBaseURL: false) else { return nil }
        components.scheme = PlaylistResourceLoader.scheme
        guard let proxy = components.url else { return nil }
        playlist = data
        proxyURL = proxy
        source = file
        super.init()
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        loadingRequest.contentInformationRequest?.contentType = "public.m3u-playlist"
        loadingRequest.contentInformationRequest?.contentLength = Int64(playlist.count)
        loadingRequest.contentInformationRequest?.isByteRangeAccessSupported = true
        if let wanted = loadingRequest.dataRequest {
            let start = min(Int(wanted.requestedOffset), playlist.count)
            let end = wanted.requestsAllDataToEndOfResource
                ? playlist.count
                : min(start + wanted.requestedLength, playlist.count)
            wanted.respond(with: playlist.subdata(in: start..<end))
        }
        loadingRequest.finishLoading()
        return true
    }
}

/// A Tidal HLS playlist's segments served to AVPlayer as the one fragmented MP4 they add up to (the init segment,
/// then each media segment in order — the very file HlsAssembly writes), each byte range fetched off the CDN the
/// moment AVPlayer asks for it. It streams exactly like the playlist and starts as quickly, but AVPlayer sees an
/// ordinary file with an audio track instead of an HLS stream, and only then does it run an MTAudioProcessingTap:
/// on the playlist itself the equaliser, reverb and karaoke stay silent until the assembled file takes over.
///
/// AVPlayer wants the file's length before its first byte, so every segment's size is asked for up front, all at
/// once, with a one-byte range (its Content-Range names the size; a GET, because the URLs are signed for one).
/// A CDN that won't answer a range, or a file AVPlayer won't play, fails the item; the engine then remembers the
/// track as refused and plays the playlist instead.
final class SegmentedResourceLoader: NSObject, AVAssetResourceLoaderDelegate, PlaylistBacked {
    static let scheme = "bitchord-segments"
    /// Ranges are fetched at most this long, so the first audio and a seek's never wait on a whole segment.
    private static let pieceBytes: Int64 = 512 * 1024
    /// Renditions (the init segment's path, which outlives a re-resolve) that failed this way. Main thread only.
    private static var refused = Set<String>()

    let queue = DispatchQueue(label: "bitchord.segments-loader")
    let proxyURL: URL
    let source: URL
    var assembled: URL?
    /// Which rendition this is, for [refuse].
    let key: String
    private let parts: [URL]
    /// Where each part starts in the file, then the file's length; nil until every size is in.
    private var offsets: [Int64]?
    private var failed = false
    private var waiting: [AVAssetResourceLoadingRequest] = []
    private var tasks: [ObjectIdentifier: URLSessionTask] = [:]
    private let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        config.httpMaximumConnectionsPerHost = 16
        return URLSession(configuration: config)
    }()

    /// Nil for a playlist that names no whole track, or a rendition already refused.
    init?(file: URL) {
        guard let track = HlsAssembly.parse(file) else { return nil }
        let key = Self.key(track.initial)
        guard !Self.refused.contains(key),
              let proxy = URL(string: "\(Self.scheme)://track/\(UUID().uuidString).mp4") else { return nil }
        self.key = key
        parts = [track.initial] + track.segments
        proxyURL = proxy
        source = file
        super.init()
        measure()
    }

    deinit { session.invalidateAndCancel() }

    static func refuse(_ key: String) { refused.insert(key) }

    /// Whether [init] would take [file] (it names a whole track whose rendition was not refused). Reads the file.
    static func serves(_ file: URL) -> Bool {
        HlsAssembly.parse(file).map { !refused.contains(key($0.initial)) } ?? false
    }

    private static func key(_ initial: URL) -> String {
        URLComponents(url: initial, resolvingAgainstBaseURL: false)?.path ?? initial.path
    }

    private func measure() {
        var sizes = [Int64?](repeating: nil, count: parts.count)
        let group = DispatchGroup()
        for (i, url) in parts.enumerated() {
            group.enter()
            var request = URLRequest(url: url)
            request.setValue("bytes=0-0", forHTTPHeaderField: "Range")
            session.dataTask(with: request) { [weak self] _, response, _ in
                defer { group.leave() }
                let http = response as? HTTPURLResponse
                let total = (http?.value(forHTTPHeaderField: "Content-Range")?.split(separator: "/").last).flatMap { Int64($0) }
                self?.queue.async { sizes[i] = http?.statusCode == 206 ? total : nil }
            }.resume()
        }
        group.notify(queue: queue) { [weak self] in
            guard let self else { return }
            guard sizes.allSatisfy({ ($0 ?? 0) > 0 }) else {
                print("SegmentedResourceLoader: the CDN gave no segment sizes; failing over to the playlist")
                self.failed = true
                self.waiting.forEach { $0.finishLoading(with: URLError(.cannotParseResponse)) }
                self.waiting = []
                return
            }
            var at: Int64 = 0
            self.offsets = [0] + sizes.map { at += $0!; return at }
            let ready = self.waiting
            self.waiting = []
            ready.forEach(self.serve)
        }
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        if failed { loadingRequest.finishLoading(with: URLError(.cannotParseResponse)) }
        else if offsets == nil { waiting.append(loadingRequest) }
        else { serve(loadingRequest) }
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        waiting.removeAll { $0 === loadingRequest }
        tasks.removeValue(forKey: ObjectIdentifier(loadingRequest))?.cancel()
    }

    private func serve(_ request: AVAssetResourceLoadingRequest) {
        guard let offsets, let length = offsets.last else { return }
        if let info = request.contentInformationRequest {
            info.contentType = AVFileType.mp4.rawValue
            info.contentLength = length
            info.isByteRangeAccessSupported = true
        }
        guard let data = request.dataRequest else { return request.finishLoading() }
        let end = data.requestsAllDataToEndOfResource
            ? length : min(length, data.requestedOffset + Int64(data.requestedLength))
        fetch(request, from: data.currentOffset, to: end)
    }

    /// [from]..<[to] of the file into [request], a piece at a time and never across a segment's end.
    private func fetch(_ request: AVAssetResourceLoadingRequest, from: Int64, to end: Int64) {
        guard let offsets, !request.isCancelled else { return }
        guard from < end else { tasks[ObjectIdentifier(request)] = nil; return request.finishLoading() }
        // The part [from] falls in: the last one starting at or before it.
        var lo = 0, hi = parts.count - 1
        while lo < hi { let mid = (lo + hi + 1) / 2; if offsets[mid] <= from { lo = mid } else { hi = mid - 1 } }
        let part = lo
        let stop = min(end, offsets[part + 1], from + Self.pieceBytes)
        var get = URLRequest(url: parts[part])
        get.setValue("bytes=\(from - offsets[part])-\(stop - offsets[part] - 1)", forHTTPHeaderField: "Range")
        let task = session.dataTask(with: get) { [weak self] body, response, error in
            self?.queue.async {
                guard let self, !request.isCancelled, !request.isFinished else { return }
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                guard error == nil, status == 206, let body, Int64(body.count) == stop - from else {
                    if (error as? URLError)?.code == .cancelled { return }
                    self.tasks[ObjectIdentifier(request)] = nil
                    return request.finishLoading(with: error ?? URLError(.badServerResponse))
                }
                request.dataRequest?.respond(with: body)
                self.fetch(request, from: stop, to: end)
            }
        }
        tasks[ObjectIdentifier(request)] = task
        task.resume()
    }
}
