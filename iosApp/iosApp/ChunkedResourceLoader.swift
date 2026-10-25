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
    private(set) var flacInfo: FlacInfo?

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
        // Delegate callbacks (the streaming path) run on the loader's own serial queue, like everything else here.
        let callbacks = OperationQueue()
        callbacks.underlyingQueue = queue
        callbacks.maxConcurrentOperationCount = 1
        self.session = URLSession(configuration: config, delegate: self, delegateQueue: callbacks)
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

    func cancelAll() {
        queue.async {
            self.invalidated = true
            self.tasks.values.forEach { $0.cancel() }
            self.tasks.removeAll()
            self.session.invalidateAndCancel()
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
                if start == 0, self.flacInfo == nil { self.flacInfo = Self.parseFlac(data) }
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

    /// PlayerClient.rangeBytesFor gives no bound for hosts other than googlevideo: as Android's DataSource, one open
    /// request per loading request, handed to AVPlayer as bytes arrive. Chunking those hosts in 1 MiB requests drew
    /// HTTP 429 from addon CDNs (tracks.monochrome.st), failing starts, seeks and endings.
    private var streaming: Bool { chunkBytes >= Int64.max / 2 }

    private final class Stream {
        let request: AVAssetResourceLoadingRequest
        let key: ObjectIdentifier
        /// Next byte AVPlayer gets; [end] inclusive (Int64.max - 1 = to the end of the file).
        var next: Int64
        let end: Int64
        let attempt: Int
        let from: Int64
        /// A 200 to a ranged ask is the whole file: bytes before [from] are dropped.
        var skip: Int64 = 0
        var head = Data()
        init(request: AVAssetResourceLoadingRequest, key: ObjectIdentifier, from: Int64, end: Int64, attempt: Int) {
            self.request = request; self.key = key; self.next = from; self.from = from; self.end = end; self.attempt = attempt
        }
    }

    private var streams: [Int: Stream] = [:]

    private func stream(_ lr: AVAssetResourceLoadingRequest, from start: Int64, end: Int64, attempt: Int) {
        let key = ObjectIdentifier(lr)
        if invalidated || cancelled.contains(key) || lr.isCancelled { return }
        if start > end {
            lr.finishLoading()
            tasks.removeValue(forKey: key)
            return
        }
        var request = URLRequest(url: source)
        request.setValue(end >= Int64.max - 1 ? "bytes=\(start)-" : "bytes=\(start)-\(end)", forHTTPHeaderField: "Range")
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        let task = session.dataTask(with: request)
        streams[task.taskIdentifier] = Stream(request: lr, key: key, from: start, end: end, attempt: attempt)
        tasks[key] = task
        task.resume()
    }

    /// Asks again from where [s] stopped. Attempts count only failures without progress, so a long stream may drop and
    /// resume more than once; 429 waits at least as long as the server's Retry-After.
    private func resume(_ s: Stream, retryAfter: Double = 0, throttled: Bool = false) -> Bool {
        let attempt = s.next > s.from ? 0 : s.attempt
        // A rate limiter (Cloudflare's 429 on addon CDNs) needs longer than a dropped connection: about 30 s in all.
        let delays = throttled ? Self.throttleDelays : Self.retryDelays
        guard attempt < delays.count else { return false }
        let delay = max(delays[attempt], retryAfter)
        print("Loader: stream at \(s.next) broke, resuming in \(delay)s (try \(attempt + 1))")
        queue.asyncAfter(deadline: .now() + delay) {
            self.stream(s.request, from: s.next, end: s.end, attempt: attempt + 1)
        }
        return true
    }

    private func close(_ s: Stream, _ task: URLSessionTask, error: Error? = nil) {
        streams[task.taskIdentifier] = nil
        tasks.removeValue(forKey: s.key)
        task.cancel()
        if let error { s.request.finishLoading(with: error) } else { s.request.finishLoading() }
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

extension ChunkedResourceLoader: URLSessionDataDelegate {
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let s = streams[dataTask.taskIdentifier] else { return completionHandler(.cancel) }
        let http = response as? HTTPURLResponse
        let status = http?.statusCode ?? 0
        guard (200...299).contains(status) else {
            completionHandler(.cancel)
            streams[dataTask.taskIdentifier] = nil
            print("Loader: stream at \(s.next) answered HTTP \(status)")
            if status == 429 || status >= 500 || status == 0,
               resume(s, retryAfter: http?.value(forHTTPHeaderField: "Retry-After").flatMap(Double.init) ?? 0, throttled: status == 429) { return }
            if [403, 404, 410].contains(status), !reportedHTTPError {
                reportedHTTPError = true
                onHTTPError?(status)
            }
            tasks.removeValue(forKey: s.key)
            s.request.finishLoading(with: NSError(domain: "BitChord", code: status, userInfo: [NSLocalizedDescriptionKey: "HTTP \(status)"]))
            return
        }
        if status == 200 { s.skip = s.next } // the host ignored Range
        if knownLength == nil {
            knownLength = Self.totalLength(http) ?? (status == 200 && response.expectedContentLength > 0 ? response.expectedContentLength : nil)
        }
        if let length = knownLength { s.request.contentInformationRequest?.contentLength = length }
        if s.request.dataRequest == nil { // only the type-and-length ask
            completionHandler(.cancel)
            return close(s, dataTask)
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard let s = streams[dataTask.taskIdentifier] else { return }
        if cancelled.contains(s.key) || s.request.isCancelled {
            streams[dataTask.taskIdentifier] = nil
            return dataTask.cancel()
        }
        var chunk = data
        if s.skip > 0 {
            let drop = Int(min(s.skip, Int64(chunk.count)))
            chunk = chunk.subdata(in: drop..<chunk.count)
            s.skip -= Int64(drop)
            if chunk.isEmpty { return }
        }
        let room = s.end - s.next + 1
        if Int64(chunk.count) > room { chunk = chunk.prefix(Int(room)) }
        if flacInfo == nil, s.next == Int64(s.head.count), s.head.count < 26 {
            s.head.append(chunk.prefix(26 - s.head.count))
            if s.head.count >= 26 { flacInfo = Self.parseFlac(s.head) }
        }
        s.request.dataRequest?.respond(with: chunk)
        s.next += Int64(chunk.count)
        if let length = knownLength, s.next >= length { deliveredEnd = true }
        if s.next > s.end { close(s, dataTask) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let s = streams.removeValue(forKey: task.taskIdentifier) else { return }
        if cancelled.contains(s.key) || s.request.isCancelled { return }
        let short = knownLength.map { s.next < min(s.end + 1, $0) } ?? false
        if let error, (error as NSError).code == NSURLErrorCancelled { return }
        if error != nil || short {
            if resume(s) { return }
            tasks.removeValue(forKey: s.key)
            return s.request.finishLoading(with: error ?? NSError(domain: NSURLErrorDomain, code: NSURLErrorNetworkConnectionLost))
        }
        tasks.removeValue(forKey: s.key)
        s.request.finishLoading()
    }
}
