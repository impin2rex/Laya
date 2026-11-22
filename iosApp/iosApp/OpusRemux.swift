import Foundation

/// WebM (Matroska) Opus to CAF, so AVPlayer can play YouTube Premium's itag 774.
///
/// AVFoundation has no WebM demuxer but decodes Opus through AudioToolbox when it is in a CAF. Both
/// containers only frame the same Opus packets, so this reads the packets out of the WebM and writes them
/// into a CAF untouched: no decoding, no re-encoding, nothing lost.
enum OpusRemux {
    /// The CAF for a whole WebM Opus file, or nil when it is not one this can read (no Opus audio track,
    /// laced blocks, truncated before any packet).
    static func cafFromWebm(_ webm: Data) -> Data? {
        guard let track = WebmReader(webm).readOpus(limit: .max), !track.packets.isEmpty else { return nil }
        return caf(track)
    }

    /// A CAF of just the first [packets] packets of a WebM that is still downloading, so playback can start
    /// before the rest has arrived. Nil until that many are in (or the file is not one this can read); [bytes]
    /// [durationMs] is the audio they hold.
    static func head(of webm: Data, packets: Int) -> (caf: Data, durationMs: Int64)? {
        guard let track = WebmReader(webm).readOpus(limit: packets), track.packets.count >= packets else { return nil }
        let samples = Int64(track.packets.reduce(0) { $0 + frames(inPacket: $1) })
        return (caf(track), max(0, samples - Int64(track.preSkip)) * 1000 / 48_000)
    }

    struct Track {
        var channels = 2
        var preSkip = 312
        var packets: [Data] = []
    }

    // MARK: - CAF

    private static func caf(_ t: Track) -> Data {
        let framesPerPacket = frames(inPacket: t.packets[0])
        var out = Data("caff".utf8)
        out.be(UInt16(1)); out.be(UInt16(0))

        out.append(contentsOf: Array("desc".utf8)); out.be(Int64(32))
        out.be(Double(48_000).bitPattern)
        out.append(contentsOf: Array("opus".utf8))
        out.be(UInt32(0))                      // format flags
        out.be(UInt32(0))                      // bytes per packet: variable
        out.be(UInt32(framesPerPacket))
        out.be(UInt32(t.channels))
        out.be(UInt32(0))                      // bits per channel: compressed

        var sizes = Data()
        for p in t.packets { sizes.varint(p.count) }
        let total = Int64(t.packets.count) * Int64(framesPerPacket)
        out.append(contentsOf: Array("pakt".utf8)); out.be(Int64(24 + sizes.count))
        out.be(Int64(t.packets.count))
        out.be(max(0, total - Int64(t.preSkip)))   // valid frames: the end trim is not stored in WebM
        out.be(Int32(t.preSkip))                   // priming frames
        out.be(Int32(0))                           // remainder frames
        out.append(sizes)

        let payload = t.packets.reduce(0) { $0 + $1.count }
        out.append(contentsOf: Array("data".utf8)); out.be(Int64(4 + payload))
        out.be(UInt32(0))                          // edit count
        t.packets.forEach { out.append($0) }
        return out
    }

    /// The 48 kHz samples one packet decodes to, from its TOC byte (RFC 6716 section 3.1).
    static func frames(inPacket p: Data) -> Int {
        guard let toc = p.first else { return 960 }
        let config = Int(toc >> 3)
        let ms: Double
        switch config {
        case 0...11: ms = [10, 20, 40, 60][config % 4]
        case 12...15: ms = [10, 20][config % 2]
        default: ms = [2.5, 5, 10, 20][config % 4]
        }
        let count: Int
        switch toc & 3 {
        case 0: count = 1
        case 1, 2: count = 2
        default: count = p.count > 1 ? Int(p[p.startIndex + 1] & 0x3F) : 1
        }
        return Int(ms * 48) * count
    }

    // MARK: - WebM

    /// Walks the file flat: containers are entered, everything else is skipped by its size, so a Segment or
    /// Cluster of unknown size (a live-style stream) reads the same as a sized one.
    private struct WebmReader {
        let d: Data
        var pos: Int
        init(_ data: Data) { d = data; pos = data.startIndex }

        private static let containers: Set<UInt32> = [
            0x18538067, // Segment
            0x1654AE6B, // Tracks
            0xAE,       // TrackEntry
            0xE1,       // Audio
            0x1F43B675, // Cluster
            0xA0,       // BlockGroup
        ]

        /// Stops after [limit] packets, so reading the head of a partial download costs the head, not the file.
        func readOpus(limit: Int) -> Track? {
            var r = self
            var track = Track()
            var opusNumber: UInt64?
            var entryNumber: UInt64 = 0
            var entryIsOpus = false
            while r.pos < r.d.endIndex {
                guard let id = r.id(), let size = r.size() else { break }
                if Self.containers.contains(id) {
                    if id == 0xAE { entryNumber = 0; entryIsOpus = false }
                    continue
                }
                let len = size ?? UInt64(r.d.endIndex - r.pos)
                guard len <= UInt64(r.d.endIndex - r.pos) else {
                    // A block cut short by the end of the download: what came before it is still good.
                    break
                }
                let body = r.d[r.pos ..< r.pos + Int(len)]
                r.pos += Int(len)
                switch id {
                case 0xD7: entryNumber = body.uint()
                case 0x86:
                    entryIsOpus = String(decoding: body, as: UTF8.self) == "A_OPUS"
                    if entryIsOpus, opusNumber == nil { opusNumber = entryNumber }
                case 0x63A2:
                    // OpusHead: version, channels, pre-skip (LE16), input rate, gain, mapping.
                    let h = [UInt8](body)
                    if h.count >= 12, h[0..<8].elementsEqual("OpusHead".utf8) {
                        track.channels = Int(h[9])
                        track.preSkip = Int(h[10]) | Int(h[11]) << 8
                    }
                case 0xA3, 0xA1:
                    guard let block = Self.block(body) else { return nil }
                    if block.track == opusNumber ?? block.track { track.packets.append(block.frame) }
                    if track.packets.count >= limit { return opusNumber == nil ? nil : track }
                default: break
                }
            }
            return opusNumber == nil ? nil : track
        }

        /// A SimpleBlock or Block: track number, 16-bit timecode, flags, then the frame (no lacing).
        private static func block(_ b: Data) -> (track: UInt64, frame: Data)? {
            var r = WebmReader(b)
            guard let number = r.vint(keepMarker: false), r.pos + 3 <= b.endIndex else { return nil }
            let flags = b[r.pos + 2]
            guard flags & 0x06 == 0 else { return nil }
            return (number, Data(b[(r.pos + 3)...]))
        }

        private mutating func id() -> UInt32? {
            guard pos < d.endIndex else { return nil }
            let first = d[pos]
            let n = first.leadingZeroBitCount + 1
            guard n <= 4, pos + n <= d.endIndex else { return nil }
            var v: UInt32 = 0
            for i in 0 ..< n { v = v << 8 | UInt32(d[pos + i]) }
            pos += n
            return v
        }

        /// The element size; the outer nil is a truncated read, the inner nil is "unknown".
        private mutating func size() -> UInt64?? {
            guard pos < d.endIndex else { return nil }
            let n = d[pos].leadingZeroBitCount + 1
            guard let v = vint(keepMarker: false) else { return nil }
            return v == (UInt64(1) << UInt64(7 * n)) - 1 ? .some(nil) : .some(v)
        }

        private mutating func vint(keepMarker: Bool) -> UInt64? {
            guard pos < d.endIndex else { return nil }
            let first = d[pos]
            let n = first.leadingZeroBitCount + 1
            guard n <= 8, pos + n <= d.endIndex else { return nil }
            var v = keepMarker ? UInt64(first) : UInt64(first) & ((UInt64(1) << UInt64(8 - n)) - 1)
            for i in 1 ..< n { v = v << 8 | UInt64(d[pos + i]) }
            pos += n
            return v
        }
    }
}

private extension Data {
    func uint() -> UInt64 { reduce(0) { $0 << 8 | UInt64($1) } }

    mutating func be<T: FixedWidthInteger>(_ v: T) {
        var big = v.bigEndian
        Swift.withUnsafeBytes(of: &big) { append(contentsOf: $0) }
    }

    /// CAF's packet sizes: seven bits a byte, most significant first, the high bit set on all but the last.
    mutating func varint(_ n: Int) {
        var groups: [UInt8] = [UInt8(n & 0x7F)]
        var rest = n >> 7
        while rest > 0 { groups.append(UInt8(rest & 0x7F) | 0x80); rest >>= 7 }
        append(contentsOf: groups.reversed())
    }
}
