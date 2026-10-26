import CryptoKit
import Foundation
import LayaKit
import UIKit

/// Kotlin's WidgetHost: turns what the player publishes into the snapshot the widget extension draws
/// (LayaWidgetStore), with the covers fetched into the shared container first, since the extension can't wait on the
/// network. A changed cover keeps the old picture up until the new one is in, as Android's widgets hold their last.
final class WidgetHostImpl: NSObject, WidgetHost {
    private let queue = DispatchQueue(label: "laya.widgets")
    // Queue only.
    private var snapshot = LayaWidgetStore.load()
    private var artURL: String?
    private var likedURLs: [String] = []
    private var saved: LayaWidgetSnapshot?

    func publish(mediaId: String?, title: String, artist: String, artworkUrl: String?, isPlaying: Bool, hasPrevious: Bool,
                 hasNext: Bool, isLiked: Bool, canLike: Bool, shuffleEnabled: Bool) {
        queue.async {
            self.snapshot.mediaId = mediaId
            self.snapshot.title = title
            self.snapshot.artist = artist
            self.snapshot.isPlaying = isPlaying
            self.snapshot.hasPrevious = hasPrevious
            self.snapshot.hasNext = hasNext
            self.snapshot.isLiked = isLiked
            self.snapshot.canLike = canLike
            self.snapshot.shuffleEnabled = shuffleEnabled
            if artworkUrl != self.artURL {
                self.artURL = artworkUrl
                if artworkUrl == nil { self.snapshot.art = nil }
                self.fetch(artworkUrl, prefix: "art", maxPx: 720) { name in
                    guard self.artURL == artworkUrl else { return }
                    self.snapshot.art = name
                    self.commit()
                }
            }
            self.commit()
        }
    }

    func publishLiked(ids: [String], titles: [String], artworkUrls: [String]) {
        queue.async {
            self.likedURLs = artworkUrls
            self.snapshot.liked = ids.indices.map { i in
                LayaWidgetSnapshot.Liked(id: ids[i], title: titles[safe: i] ?? "",
                                         art: self.snapshot.liked.first { $0.id == ids[i] }?.art)
            }
            self.commit()
            for (i, url) in artworkUrls.enumerated() {
                self.fetch(url, prefix: "tile", maxPx: 240) { name in
                    guard self.likedURLs == artworkUrls, i < self.snapshot.liked.count else { return }
                    self.snapshot.liked[i].art = name
                    self.commit()
                }
            }
        }
    }

    /// Writes the snapshot when it changed, and drops the pictures it no longer names. Queue only.
    private func commit() {
        guard snapshot != saved else { return }
        saved = snapshot
        LayaWidgetStore.save(snapshot)
        let keep = Set([snapshot.art].compactMap { $0 } + snapshot.liked.compactMap(\.art))
        guard let dir = LayaWidgetStore.directory,
              let files = try? FileManager.default.contentsOfDirectory(atPath: dir.path) else { return }
        for f in files where (f.hasPrefix("art-") || f.hasPrefix("tile-")) && !keep.contains(f) {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent(f))
        }
    }

    /// The picture at [url] (remote, or a local cover path) saved no larger than [maxPx]; [done] on the queue with
    /// its file name, or nil when there is none. Reused when already saved.
    private func fetch(_ url: String?, prefix: String, maxPx: CGFloat, done: @escaping (String?) -> Void) {
        guard let url, !url.isEmpty, let dir = LayaWidgetStore.directory else { return done(nil) }
        let name = "\(prefix)-" + SHA256.hash(data: Data(url.utf8)).prefix(10).map { String(format: "%02x", $0) }.joined() + ".jpg"
        let file = dir.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: file.path) { return done(name) }
        let store: (Data?) -> Void = { data in
            let saved = data.flatMap(UIImage.init(data:)).flatMap { Self.downscaled($0, maxPx: maxPx).jpegData(compressionQuality: 0.85) }
            self.queue.async {
                guard let saved, (try? saved.write(to: file, options: .atomic)) != nil else { return done(nil) }
                done(name)
            }
        }
        if url.hasPrefix("/") { return store(FileManager.default.contents(atPath: url)) }
        guard let remote = URL(string: url) else { return done(nil) }
        URLSession.shared.dataTask(with: remote) { data, _, _ in store(data) }.resume()
    }

    private static func downscaled(_ image: UIImage, maxPx: CGFloat) -> UIImage {
        let w = image.size.width * image.scale, h = image.size.height * image.scale
        let k = min(1, maxPx / max(w, h, 1))
        let size = CGSize(width: (w * k).rounded(), height: (h * k).rounded())
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    }
}

private extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}
