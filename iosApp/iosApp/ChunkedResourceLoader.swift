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

    private let source: URL
    private let headers: [String: String]
    private let mimeType: String
    private var knownLength: Int64?
    private let chunkBytes: Int64
    private let session: URLSession
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
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 20
        config.httpMaximumConnectionsPerHost = 2
        self.session = URLSession(configuration: config)
        super.init()
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
    private func fetch(_ loadingRequest: AVAssetResourceLoadingRequest, from start: Int64, end: Int64) {
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
            self.queue.async {
                if self.cancelled.contains(key) || loadingRequest.isCancelled { return }
                if let error {
                    if (error as NSError).code == NSURLErrorCancelled { return }
                    loadingRequest.finishLoading(with: error)
                    self.tasks.removeValue(forKey: key)
                    return
                }
                let http = response as? HTTPURLResponse
                let status = http?.statusCode ?? 0
                guard (200...299).contains(status), let data else {
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
        default: return AVFileType.m4a.rawValue
        }
    }
}
