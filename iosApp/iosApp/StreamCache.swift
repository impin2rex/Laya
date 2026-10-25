import CryptoKit
import Foundation

/// Android's AudioCache for hosts that are not googlevideo: one file per URL in Caches/, filled by ONE sequential
/// GET (`Range: bytes=0-`, resumed from the last byte on a drop, 429 backed off), shared by every reader of that URL:
/// the AVPlayer item's ChunkedResourceLoader, the PcmReader behind Automix / karaoke / version alignment, and seeks.
/// Addon CDNs (Cloudflare on tracks.monochrome.st) answer HTTP 429 to anything more.
final class StreamCache {
    static let shared = StreamCache()
    /// AppSettings.DEFAULT_CACHE_LIMIT_BYTES: Android's default audio cache size.
    static let capBytes: Int64 = 512 << 20

    /// All download state lives on this queue; readers on other threads go through the entry's lock.
    fileprivate let queue = DispatchQueue(label: "laya.stream-cache")
    private let dir: URL
    private var entries: [String: Entry] = [:]
    private let lock = NSLock()

    private init() {
        dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("laya-stream")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    /// The entry for [url], with a reference held until `release()`. The download runs while any reference is held.
    func acquire(_ url: URL, headers: [String: String]) -> Entry {
        lock.lock()
        let key = url.absoluteString
        let entry: Entry
        if let have = entries[key] { entry = have } else {
            evict()
            let name = SHA256.hash(data: Data(key.utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
            entry = Entry(cache: self, url: url, headers: headers, path: dir.appendingPathComponent(name + ".bin"), id: String(name.prefix(6)))
            entries[key] = entry
        }
        lock.unlock()
        entry.retain()
        return entry
    }

    /// Oldest files out until the folder fits the cap; files a reader holds are kept. Called with `lock` held.
    private func evict() {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys)) ?? []
        var info = files.map { f -> (url: URL, size: Int64, date: Date) in
            let v = try? f.resourceValues(forKeys: Set(keys))
            return (f, Int64(v?.fileSize ?? 0), v?.contentModificationDate ?? .distantPast)
        }.sorted { $0.date < $1.date }
        var total = info.reduce(0) { $0 + $1.size }
        let inUse = Set(entries.values.filter { $0.inUse }.map { $0.path })
        while total > Self.capBytes, !info.isEmpty {
            let old = info.removeFirst()
            if inUse.contains(old.url) { continue }
            try? FileManager.default.removeItem(at: old.url)
            entries = entries.filter { $0.value.path != old.url }
            total -= old.size
        }
    }

    final class Entry: NSObject, URLSessionDataDelegate {
        fileprivate let path: URL
        private unowned let cache: StreamCache
        private let url: URL
        private let headers: [String: String]
        private let id: String
        private let lock = NSLock()
        // Guarded by `lock`: read by any thread, written on the cache queue.
        private var _written: Int64 = 0
        private var _length: Int64?
        private var _status = 0 // a refusal the retries gave up on
        private var _complete = false
        private var refs = 0
        private var waiters: [Int: (need: Int64, queue: DispatchQueue, block: () -> Void)] = [:]
        private var nextToken = 0
        // Cache queue only.
        private var session: URLSession?
        private var task: URLSessionDataTask?
        private var wfd: Int32 = -1
        private var rfd: Int32 = -1
        private var skip: Int64 = 0
        private var attempt = 0
        private var progressed = false
        private var connections = 0
        private static let retryDelays: [Double] = [0.5, 1, 2, 4]
        private static let throttleDelays: [Double] = [1, 2, 4, 8, 16]

        fileprivate init(cache: StreamCache, url: URL, headers: [String: String], path: URL, id: String) {
            self.cache = cache; self.url = url; self.headers = headers; self.path = path; self.id = id
            super.init()
            FileManager.default.createFile(atPath: path.path, contents: nil) // keeps what a previous run left
            wfd = open(path.path, O_WRONLY | O_APPEND)
            rfd = open(path.path, O_RDONLY)
            var st = stat()
            if fstat(rfd, &st) == 0 { _written = Int64(st.st_size) }
        }

        deinit { if wfd >= 0 { close(wfd) }; if rfd >= 0 { close(rfd) } }

        var inUse: Bool { lock.lock(); defer { lock.unlock() }; return refs > 0 }
        var state: (written: Int64, length: Int64?, complete: Bool, status: Int) {
            lock.lock(); defer { lock.unlock() }
            return (_written, _length, _complete, _status)
        }
        /// FLAC's STREAMINFO, once the first 26 bytes are on disk.
        var flacInfo: ChunkedResourceLoader.FlacInfo? { ChunkedResourceLoader.parseFlac(read(at: 0, count: 26)) }

        /// Up to [count] bytes at [offset] that are on disk now (fewer, or none, when the download has not got there).
        func read(at offset: Int64, count: Int) -> Data {
            let n = Int(max(0, min(Int64(count), state.written - offset)))
            guard n > 0, rfd >= 0 else { return Data() }
            var data = Data(count: n)
            let got = data.withUnsafeMutableBytes { pread(rfd, $0.baseAddress, n, off_t(offset)) }
            return got > 0 ? data.prefix(got) : Data()
        }

        /// Calls [block] on [queue] once [need] bytes are on disk, or the download is over (complete or refused).
        func wait(for need: Int64, on queue: DispatchQueue, _ block: @escaping () -> Void) -> Int {
            lock.lock()
            nextToken += 1
            let token = nextToken
            if _written >= need || _complete || _status != 0 {
                lock.unlock()
                queue.async(execute: block)
            } else {
                waiters[token] = (need, queue, block)
                lock.unlock()
            }
            return token
        }

        func cancelWait(_ token: Int) { lock.lock(); waiters[token] = nil; lock.unlock() }

        fileprivate func retain() {
            lock.lock(); refs += 1; lock.unlock()
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: path.path)
            cache.queue.async { self.startIfNeeded() }
        }

        func release() {
            lock.lock(); refs -= 1; let idle = refs <= 0; lock.unlock()
            // Nobody reads: stop downloading (the file stays; the next reader resumes from its end).
            if idle { cache.queue.async { self.task?.cancel(); self.task = nil } }
        }

        private func wake() {
            lock.lock()
            let due = waiters.filter { _written >= $0.value.need || _complete || _status != 0 }
            due.keys.forEach { waiters[$0] = nil }
            lock.unlock()
            due.values.forEach { $0.queue.async(execute: $0.block) }
        }

        // MARK: download (cache queue)

        private func startIfNeeded() {
            let s = state
            lock.lock(); let held = refs > 0; lock.unlock()
            guard held, task == nil, !s.complete, s.status == 0 else { return }
            if let length = s.length, s.written >= length { return finish() }
            if session == nil {
                let config = URLSessionConfiguration.default
                config.timeoutIntervalForRequest = 20
                config.httpMaximumConnectionsPerHost = 1
                let ops = OperationQueue()
                ops.underlyingQueue = cache.queue
                ops.maxConcurrentOperationCount = 1
                session = URLSession(configuration: config, delegate: self, delegateQueue: ops)
            }
            var request = URLRequest(url: url)
            request.setValue("bytes=\(s.written)-", forHTTPHeaderField: "Range")
            for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
            connections += 1
            progressed = false
            skip = 0
            print("StreamCache[\(id)]: connection #\(connections) \(url.host ?? "?") bytes=\(s.written)- (attempt \(attempt))")
            task = session?.dataTask(with: request)
            task?.resume()
        }

        private func finish() {
            lock.lock(); _complete = true; lock.unlock()
            print("StreamCache[\(id)]: complete, \(state.written) bytes in \(connections) connection(s)")
            task = nil
            session?.finishTasksAndInvalidate(); session = nil
            wake()
        }

        private func fail(_ status: Int) {
            print("StreamCache[\(id)]: giving up, HTTP \(status)")
            lock.lock(); _status = status; lock.unlock()
            task = nil
            wake()
        }

        /// Asks again from the last byte. Attempts count only failures without progress; 429 waits at least as long as Retry-After.
        private func retry(status: Int, retryAfter: Double = 0) {
            task = nil
            let delays = status == 429 ? Self.throttleDelays : Self.retryDelays
            if progressed { attempt = 0 }
            guard attempt < delays.count else { return fail(status == 0 ? NSURLErrorNetworkConnectionLost : status) }
            let delay = max(delays[attempt], retryAfter)
            attempt += 1
            print("StreamCache[\(id)]: broke at \(state.written) (HTTP \(status)), resuming in \(delay)s")
            cache.queue.asyncAfter(deadline: .now() + delay) { self.startIfNeeded() }
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
            guard dataTask === task else { return completionHandler(.cancel) }
            let http = response as? HTTPURLResponse
            let status = http?.statusCode ?? 0
            let written = state.written
            if status == 416, written > 0 { // already have it all (a finished file from an earlier run)
                completionHandler(.cancel)
                if let total = Self.total(http?.value(forHTTPHeaderField: "Content-Range")) {
                    lock.lock(); _length = total; lock.unlock()
                    return written >= total ? finish() : fail(status)
                }
                return fail(status)
            }
            guard (200...299).contains(status) else {
                completionHandler(.cancel)
                if status == 429 || status >= 500 || status == 0 {
                    return retry(status: status, retryAfter: http?.value(forHTTPHeaderField: "Retry-After").flatMap(Double.init) ?? 0)
                }
                return fail(status)
            }
            if status == 200 { skip = written } // the host ignored Range: the file starts over, the bytes we have are dropped
            let length = Self.total(http?.value(forHTTPHeaderField: "Content-Range"))
                ?? (status == 200 && response.expectedContentLength > 0 ? response.expectedContentLength : nil)
            if let length { lock.lock(); _length = length; lock.unlock() }
            wake() // readers waiting only for the length
            completionHandler(.allow)
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
            guard dataTask === task else { return }
            var chunk = data
            if skip > 0 {
                let drop = Int(min(skip, Int64(chunk.count)))
                chunk = chunk.subdata(in: drop..<chunk.count)
                skip -= Int64(drop)
            }
            if chunk.isEmpty { return }
            let ok = chunk.withUnsafeBytes { write(wfd, $0.baseAddress, chunk.count) } == chunk.count
            guard ok else { dataTask.cancel(); task = nil; return fail(NSURLErrorCannotWriteToFile) }
            lock.lock(); _written += Int64(chunk.count); lock.unlock()
            progressed = true
            if state.written >> 22 != (state.written - Int64(chunk.count)) >> 22 { print("StreamCache[\(id)]: \(state.written >> 20) MB of \(state.length.map { String($0 >> 20) } ?? "?")") }
            wake()
        }

        func urlSession(_ session: URLSession, task finished: URLSessionTask, didCompleteWithError error: Error?) {
            guard finished === task else { return }
            let s = state
            if let error, (error as NSError).code == NSURLErrorCancelled { return }
            if error == nil, s.length == nil || s.written >= s.length! {
                lock.lock(); _length = _length ?? _written; lock.unlock()
                return finish()
            }
            retry(status: 0)
        }

        /// "bytes 0-1023/4567890" or "bytes */4567890" -> 4567890
        private static func total(_ range: String?) -> Int64? {
            guard let range, let slash = range.lastIndex(of: "/") else { return nil }
            return Int64(range[range.index(after: slash)...])
        }
    }
}
