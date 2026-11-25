import ActivityKit
import Foundation
import LayaKit
import UIKit

/// Kotlin's LyricsLiveActivityHost: ActivityKit is Swift-only, so the playing line (IosLyricsActivity) reaches the
/// lock screen and the Dynamic Island through here.
final class LyricsLiveActivityImpl: NSObject, LyricsLiveActivityHost {
    /// An Activity<LayaLyricsAttributes>, typed loosely so this class needs no availability gate itself.
    private var current: Any?
    /// The last ActivityContent asked for, so restart() can put the card back with no new player tick.
    private var last: Any?
    /// The widget cover the thumbnail was last made from, and the thumbnail's own name.
    private var thumbFrom: String?
    private var thumb: String?

    func show(title: String, artist: String, line: String, next: String) -> Bool {
        guard #available(iOS 16.2, *) else { return false }
        let state = LayaLyricsAttributes.ContentState(title: title, artist: artist, line: line, next: next, art: thumbnail())
        let content = ActivityContent(state: state, staleDate: nil)
        last = content
        // One swiped away or timed out by iOS (8 h) takes no updates: drop it so a new one starts in front.
        let live = ((current as? ActivityKit.Activity<LayaLyricsAttributes>).map { [$0] } ?? [])
            + ActivityKit.Activity<LayaLyricsAttributes>.activities
        if let activity = live.first(where: { $0.activityState == .active }) {
            current = activity
            Task { await activity.update(content) }
            return true
        }
        // iOS starts one only for an app on screen; from the background the next line tries again.
        guard UIApplication.shared.applicationState == .active, ActivityAuthorizationInfo().areActivitiesEnabled else {
            return false
        }
        current = try? ActivityKit.Activity.request(attributes: LayaLyricsAttributes(), content: content)
        return current != nil
    }

    /// From a widget tap (LayaWidgetIntent, a LiveActivityIntent): the one moment iOS allows a start from the
    /// background. Puts back a card swiped away or timed out, with the last content; the next tick updates it.
    func restart() {
        guard #available(iOS 16.2, *), let content = last as? ActivityContent<LayaLyricsAttributes.ContentState>,
              !ActivityKit.Activity<LayaLyricsAttributes>.activities.contains(where: { $0.activityState == .active })
        else { return }
        current = try? ActivityKit.Activity.request(attributes: LayaLyricsAttributes(), content: content)
    }

    /// Every one of them, as RecognitionLiveActivityImpl.end: one left by an earlier launch included.
    func end() {
        guard #available(iOS 16.2, *) else { return }
        current = nil
        last = nil
        Task {
            for activity in ActivityKit.Activity<LayaLyricsAttributes>.activities {
                await activity.end(nil, dismissalPolicy: .immediate)
            }
        }
    }

    /// A 120 px copy of the home-screen widgets' cover (WidgetHostImpl fetches it at 720): a Live Activity is drawn
    /// in a small memory budget, and a full-size cover can leave it blank.
    private func thumbnail() -> String? {
        let source = LayaWidgetStore.load().art
        if source == thumbFrom { return thumb }
        thumbFrom = source
        thumb = nil
        guard let url = LayaWidgetStore.fileURL(source), let image = UIImage(contentsOfFile: url.path) else { return nil }
        let side: CGFloat = 120
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let small = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: side, height: side))
        }
        let name = "lyrics_thumb_" + (source ?? "none")
        guard let data = small.jpegData(compressionQuality: 0.8),
              let out = LayaWidgetStore.fileURL(name), (try? data.write(to: out, options: .atomic)) != nil else { return nil }
        // The previous song's thumbnail is no longer referenced by anything.
        if let dir = LayaWidgetStore.directory,
           let old = try? FileManager.default.contentsOfDirectory(atPath: dir.path) {
            for file in old where file.hasPrefix("lyrics_thumb_") && file != name {
                try? FileManager.default.removeItem(at: dir.appendingPathComponent(file))
            }
        }
        thumb = name
        return name
    }
}
