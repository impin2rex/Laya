import SwiftUI
import LayaKit

@main
struct iOSApp: App {
    /// One engine for the app's lifetime; Kotlin drives it.
    static let audioEngine = AVAudioEngineImpl()

    /// Google Cast; created at launch because the SDK wants its context set up early.
    static let castNative = CastNativeImpl()

    init() {
        IosKaraoke.shared.native = KaraokeNativeImpl()
        _ = iOSApp.castNative // statics are lazy
    }

    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
                .onOpenURL { url in _ = IosPartyKt.handleInviteUrl(url: url.absoluteString) }
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let host = StatusBarHost()
        host.embed(MainViewControllerKt.MainViewController(engine: iOSApp.audioEngine, cast: iOSApp.castNative, onStatusBarLight: { light in
            host.light = light.boolValue
        }))
        return host
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// Owns the status bar style for the Compose screen: light icons over dark content, dark over light.
final class StatusBarHost: UIViewController {
    var light = true { didSet { setNeedsStatusBarAppearanceUpdate() } }
    override var preferredStatusBarStyle: UIStatusBarStyle { light ? .lightContent : .darkContent }

    func embed(_ child: UIViewController) {
        addChild(child)
        child.view.frame = view.bounds
        child.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(child.view)
        child.didMove(toParent: self)
    }
}
