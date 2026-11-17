// Music recognition's result as a macOS notification, with Try again: Apple's
// UserNotifications, which AWT's tray messages no longer reach. Built into
// liblaya_notify.dylib and called from the JVM through JNA
// (DesktopRecognitionNotifier.kt), so the API is plain C.

import Foundation
import UserNotifications

/// action: 0 = the notification was clicked, 1 = Try again.
public typealias LayaNotifyCallback = @convention(c) (Int32) -> Void

private let identifier = "laya.recognition"
private let retryCategory = "laya.recognition.retry"
private let retryAction = "retry"

private final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notifier()
    var callback: LayaNotifyCallback?

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler done: @escaping () -> Void
    ) {
        if response.notification.request.identifier == identifier {
            callback?(response.actionIdentifier == retryAction ? 1 : 0)
        }
        done()
    }

    /// Posted only while Laya's window isn't in front, but the app can still be the active one.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        done([.banner, .sound])
    }
}

/// Only inside Laya.app: run from Gradle (a bare java binary) there is no bundle
/// for the system to attribute a notification to, and UNUserNotificationCenter aborts.
@_cdecl("laya_notify_available")
public func laya_notify_available() -> Bool {
    Bundle.main.bundleIdentifier != nil && Bundle.main.bundleURL.pathExtension == "app"
}

/// [retryLabel]: the Try again button's label, or null for none.
@_cdecl("laya_notify_post")
public func laya_notify_post(
    _ title: UnsafePointer<CChar>,
    _ body: UnsafePointer<CChar>,
    _ retryLabel: UnsafePointer<CChar>?,
    _ callback: LayaNotifyCallback
) {
    guard laya_notify_available() else { return }
    let title = String(cString: title)
    let body = String(cString: body)
    let retry = retryLabel.map { String(cString: $0) }
    let center = UNUserNotificationCenter.current()
    Notifier.shared.callback = callback
    center.delegate = Notifier.shared
    if let retry {
        center.setNotificationCategories([
            UNNotificationCategory(
                identifier: retryCategory,
                actions: [UNNotificationAction(identifier: retryAction, title: retry, options: [.foreground])],
                intentIdentifiers: []
            ),
        ])
    }
    // Asks the first time; afterwards answers at once with what was chosen.
    center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
        guard granted else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        if retry != nil { content.categoryIdentifier = retryCategory }
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
        center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
    }
}
