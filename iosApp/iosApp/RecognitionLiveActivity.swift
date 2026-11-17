import ActivityKit
import Foundation
import LayaKit

/// Kotlin's RecognitionLiveActivityHost: ActivityKit is Swift-only, so the recognizer's state (RecognitionNotifier)
/// reaches the lock screen and the Dynamic Island through here.
final class RecognitionLiveActivityImpl: NSObject, RecognitionLiveActivityHost {
    /// An Activity<LayaRecognitionAttributes>, typed loosely so this class needs no availability gate itself.
    private var current: Any?

    func show(phase: String, title: String, subtitle: String) {
        guard #available(iOS 16.2, *) else { return }
        let content = ActivityContent(
            state: LayaRecognitionAttributes.ContentState(phase: phase, title: title, subtitle: subtitle),
            staleDate: nil
        )
        if let activity = (current as? ActivityKit.Activity<LayaRecognitionAttributes>)
            ?? ActivityKit.Activity<LayaRecognitionAttributes>.activities.first {
            current = activity
            Task { await activity.update(content) }
        } else if ActivityAuthorizationInfo().areActivitiesEnabled {
            // Fails from the background, which is fine: it is asked for as listening starts, with Laya in front.
            current = try? ActivityKit.Activity.request(attributes: LayaRecognitionAttributes(), content: content)
        }
    }

    /// Every one of them, not just [current]: one started by an earlier launch, or a second request, would
    /// otherwise sit on the lock screen saying "Listening…" for good.
    func end() {
        guard #available(iOS 16.2, *) else { return }
        current = nil
        Task {
            for activity in ActivityKit.Activity<LayaRecognitionAttributes>.activities {
                await activity.end(nil, dismissalPolicy: .immediate)
            }
        }
    }
}
