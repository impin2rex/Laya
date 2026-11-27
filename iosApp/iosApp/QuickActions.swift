import SwiftUI
import UIKit
import LayaKit

/// The app icon's long-press menu (Home Screen quick actions); Kotlin publishes the items (LayaIosApp). A tap
/// goes to the same command queue as the widgets', which holds it until the app is ready on a cold launch.
final class QuickActionsAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Set before launch ends, or a notification tap that cold-starts the app (a lossless reminder) is dropped.
        RecognitionNotifier.shared.installDelegate()
        // Registered before launch ends, as BGTaskScheduler requires: renews lossless sessions in the background.
        IosLosslessBackground.shared.register()
        return true
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting session: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        // A cold launch brings its action here instead of to the scene delegate.
        if let item = options.shortcutItem { QuickActions.perform(item) }
        let config = UISceneConfiguration(name: nil, sessionRole: session.role)
        config.delegateClass = QuickActionsSceneDelegate.self
        return config
    }
}

final class QuickActionsSceneDelegate: NSObject, UIWindowSceneDelegate {
    func windowScene(
        _ windowScene: UIWindowScene,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        completionHandler(QuickActions.perform(shortcutItem))
    }
}

enum QuickActions {
    /// "search" or "recognize", the commands IosWidgets.perform already takes.
    @discardableResult
    static func perform(_ item: UIApplicationShortcutItem) -> Bool {
        guard item.type == "search" || item.type == "recognize" else { return false }
        _ = IosWidgets.shared.perform(command: item.type)
        return true
    }
}
