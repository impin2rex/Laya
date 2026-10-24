import SwiftUI
import LayaKit

@main
struct iOSApp: App {
    /// One engine for the app's lifetime; Kotlin drives it.
    static let audioEngine = AVAudioEngineImpl()

    init() {
        IosKaraoke.shared.native = KaraokeNativeImpl()
    }

    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(engine: iOSApp.audioEngine)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
