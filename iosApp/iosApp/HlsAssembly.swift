import Foundation

/// An HLS playlist's segments fetched into the single file they already are.
///
/// Tidal answers a lossless request with a manifest, which DashToHls rewrites into a playlist of fMP4
/// segments. AVPlayer reads that playlist happily enough to start at once, but everything else about it
/// is a network stream: AVAssetReader refuses it outright (no tracks at all), a seek has to fetch the
/// segments at the offset first, and the automix blend cannot cue the incoming track in time because of
/// it. Those same segments written out behind their init segment are an ordinary MP4 holding the very
/// same FLAC — nothing is re-wrapped and nothing re-encoded — and it cues and seeks in milliseconds.
///
/// One file serves everyone who wants the track's bytes: the analysis decode, the vocal-band pass after
/// it, and the player swapping off the playlist. Whoever asks first pays for the fetch.
enum HlsAssembly {

    /// Segments in the air at once. Enough to keep a quick connection busy without analysis taking the
    /// pipe over from the audio being played, which the task priority below settles.
    private static let window = 24

    /// Fetches under way, by destination path (see [assemble]).
    private static let gate = NSLock()
    private static var inFlight: [String: DispatchGroup] = [:]

    /// Where [assemble] would put the track [playlist] names, whether or not it is there yet.
    private static func destination(for playlist: URL, initial: URL) -> URL {
        // Keyed by the rendition the init segment names rather than by the playlist file: that file is
        // rewritten every time the track is resolved again, so keying on it had the vocal-band pass miss
        // a track the decode already had and fetch all 20-odd MB a second time. The path survives a
        // re-resolve because only the query's tokens are reissued.
        let rendition = URLComponents(url: initial, resolvingAgainstBaseURL: false)?.path ?? initial.path
        let stem = "laya-analysis-\(playlist.deletingPathExtension().lastPathComponent)-\(fingerprint(rendition))"
        return FileManager.default.temporaryDirectory.appendingPathComponent("\(stem).mp4")
    }

    /// The assembled track if it is already on disk, without fetching anything. Cheap enough to ask on
    /// the main thread, which is why the player can check it while making an item.
    static func cached(for playlist: URL) -> URL? {
        guard let parts = parse(playlist) else { return nil }
        let file = destination(for: playlist, initial: parts.initial)
        return FileManager.default.fileExists(atPath: file.path) ? file : nil
    }

    /// The assembled track, fetching it if nobody has yet. Blocks, so never on the main thread.
    static func assemble(playlist: URL) -> URL? {
        guard let parts = parse(playlist) else { return nil }
        let file = destination(for: playlist, initial: parts.initial)
        if FileManager.default.fileExists(atPath: file.path) { return file }

        // One fetch per track: the player, the analysis decode and the next-track prefetch all ask, and a second
        // caller used to delete the first's half-written .part and start over it, so the first then moved a
        // truncated file into place as the whole track. Later callers wait for the first and take its file.
        gate.lock()
        if let running = inFlight[file.path] {
            gate.unlock()
            running.wait()
            return FileManager.default.fileExists(atPath: file.path) ? file : nil
        }
        let mine = DispatchGroup()
        mine.enter()
        inFlight[file.path] = mine
        gate.unlock()
        defer {
            gate.lock()
            inFlight[file.path] = nil
            gate.unlock()
            mine.leave()
        }

        // Written aside and moved into place at the end, so a fetch cut short partway through is never
        // picked up as a whole track by whoever asks next.
        let part = file.deletingPathExtension().appendingPathExtension("part")
        try? FileManager.default.removeItem(at: part)
        guard FileManager.default.createFile(atPath: part.path, contents: nil),
              let sink = try? FileHandle(forWritingTo: part) else { return nil }
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        config.httpMaximumConnectionsPerHost = window
        let session = URLSession(configuration: config)
        var ok = true
        defer {
            try? sink.close()
            session.invalidateAndCancel()
            if !ok { try? FileManager.default.removeItem(at: part) }
        }

        // A window at a time, written in playlist order once it has all arrived: concurrent enough to be
        // quick, while only a window's worth is ever held in memory rather than the whole track.
        let all = [parts.initial] + parts.segments
        for start in stride(from: 0, to: all.count, by: window) {
            let batch = all[start..<min(start + window, all.count)]
            var bytes = [Data?](repeating: nil, count: batch.count)
            let group = DispatchGroup()
            let guarded = NSLock()
            for (offset, url) in batch.enumerated() {
                group.enter()
                let task = session.dataTask(with: url) { data, response, _ in
                    defer { group.leave() }
                    guard (200...299).contains((response as? HTTPURLResponse)?.statusCode ?? 0) else { return }
                    guarded.lock()
                    bytes[offset] = data
                    guarded.unlock()
                }
                // Behind the audio being played: AVPlayer's own fetches come first, so a seek into a
                // track still being assembled does not queue behind it.
                task.priority = URLSessionTask.lowPriority
                task.resume()
            }
            guard group.wait(timeout: .now() + 90) == .success else { ok = false; return nil }
            for piece in bytes {
                guard let piece, !piece.isEmpty else { ok = false; return nil }
                sink.write(piece)
            }
        }
        try? sink.close()
        guard (try? FileManager.default.moveItem(at: part, to: file)) != nil else { ok = false; return nil }
        sweep(keeping: file)
        return file
    }

    /// The init segment and the media segments [playlist] names, or nil if it names no whole track:
    /// without the init segment the media segments describe no stream at all.
    static func parse(_ playlist: URL) -> (initial: URL, segments: [URL])? {
        guard let text = try? String(contentsOf: playlist, encoding: .utf8) else { return nil }
        var segments: [URL] = []
        var initial: URL?
        for line in text.split(separator: "\n") {
            let entry = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if entry.hasPrefix("#EXT-X-MAP:") {
                guard let quoted = entry.range(of: "URI=\"[^\"]*\"", options: .regularExpression) else { continue }
                initial = URL(string: String(entry[quoted].dropFirst(5).dropLast()))
            } else if !entry.hasPrefix("#"), !entry.isEmpty {
                URL(string: entry).map { segments.append($0) }
            }
        }
        guard let initial, !segments.isEmpty else { return nil }
        return (initial, segments)
    }

    /// FNV-1a of [text]. Swift's own hashValue is seeded per process, so it would name the same rendition
    /// differently after a relaunch and fetch again what is already sitting on disk.
    private static func fingerprint(_ text: String) -> String {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in text.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x100_0000_01b3
        }
        return String(hash, radix: 16)
    }

    /// Clear all cache: every assembled track but the last couple of minutes' (the playing and queued ones).
    static func clearAll() {
        let directory = FileManager.default.temporaryDirectory
        let recent = Date().addingTimeInterval(-120)
        let entries = (try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        for entry in entries where entry.lastPathComponent.hasPrefix("laya-analysis-") {
            let touched = (try? entry.resourceValues(forKeys: [.contentModificationDateKey]))?
                .contentModificationDate ?? .distantPast
            if touched < recent { try? FileManager.default.removeItem(at: entry) }
        }
    }

    /// Clears assembled tracks nothing is reading any more. Anything recent is left alone: the track
    /// playing and the one queued after it are wanted at the same time, each with its own file.
    private static func sweep(keeping file: URL) {
        let directory = FileManager.default.temporaryDirectory
        let stale = Date().addingTimeInterval(-600)
        let entries = (try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        for entry in entries
        where entry != file && entry.lastPathComponent.hasPrefix("laya-analysis-") {
            let touched = (try? entry.resourceValues(forKeys: [.contentModificationDateKey]))?
                .contentModificationDate ?? .distantPast
            if touched < stale { try? FileManager.default.removeItem(at: entry) }
        }
    }
}
