import ActivityKit
import AppIntents
import Foundation
import WidgetKit

/// What the home-screen widgets draw, as Android's MediaWidgetSnapshot + LikedWidgetSongs: persisted rather than read
/// live, because a widget is on screen while the app is not running. The app writes it (WidgetHostImpl), the
/// extension only reads it. Compiled into both targets.
struct LayaWidgetSnapshot: Codable, Equatable {
    struct Liked: Codable, Equatable { var id: String; var title: String; var art: String? }

    var mediaId: String?
    var title = ""
    var artist = ""
    /// File name of the cover in the shared container, once fetched.
    var art: String?
    /// Whether the transport shows a pause glyph: what the listener asked for, not whether audio is coming out yet,
    /// so a tap is answered at once (Android's playWhenReady).
    var isPlaying = false
    var hasPrevious = false
    var hasNext = false
    var isLiked = false
    /// Signed in, and not a local file: the heart is hidden, not greyed, otherwise (as in the player).
    var canLike = false
    var shuffleEnabled = false
    /// The first few Liked Music tracks, for the library widget's bottom row; empty when signed out.
    var liked: [Liked] = []

    var hasTrack: Bool { mediaId != nil }
}

/// The App Group container both targets share. Sideloaders that re-sign with a free account rename the group and
/// record the new name in Info.plist (ALTAppGroups), so that is asked first.
enum LayaWidgetStore {
    static let defaultGroup = "group.com.music.laya"
    static let kinds = ["LayaNowPlaying", "LayaNowPlayingPill", "LayaNowPlayingLibrary"]

    static var directory: URL? {
        let groups = (Bundle.main.object(forInfoDictionaryKey: "ALTAppGroups") as? [String] ?? []) + [defaultGroup]
        for group in groups {
            if let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) {
                let dir = url.appendingPathComponent("widget", isDirectory: true)
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                return dir
            }
        }
        return nil
    }

    static func load() -> LayaWidgetSnapshot {
        guard let url = directory?.appendingPathComponent("snapshot.json"), let data = try? Data(contentsOf: url),
              let snapshot = try? JSONDecoder().decode(LayaWidgetSnapshot.self, from: data) else { return LayaWidgetSnapshot() }
        return snapshot
    }

    static func save(_ snapshot: LayaWidgetSnapshot) {
        guard let url = directory?.appendingPathComponent("snapshot.json"), let data = try? JSONEncoder().encode(snapshot) else { return }
        try? data.write(to: url, options: .atomic)
        WidgetCenter.shared.reloadAllTimelines()
    }

    static func fileURL(_ name: String?) -> URL? { name.flatMap { directory?.appendingPathComponent($0) } }

    /// Flips the glyph a tapped button changes before the app has done anything, as Android's widget flips with
    /// playWhenReady: the app's own publish follows and settles it.
    static func applyOptimistically(_ command: String) {
        var s = load()
        guard s.hasTrack else { return }
        switch command {
        case "toggle": s.isPlaying.toggle()
        case "like" where s.canLike: s.isLiked.toggle()
        case "shuffle": s.shuffleEnabled.toggle()
        default: return
        }
        save(s)
    }
}

/// Where a widget button's command goes in the app process: set by the app at launch (to the Kotlin player), nil in
/// the extension. Returns false while the app has no player to run it yet (launched in the background, no UI).
enum LayaWidgetCommands {
    static var handler: ((String) -> Bool)?
    /// Brings the lock-screen lyrics card back if it is gone (LyricsLiveActivityImpl.restart).
    static var restartLyrics: (() -> Void)?
}

/// A widget button: previous, toggle, next, like or shuffle. An AudioPlaybackIntent, so the system runs it in the
/// app's process, where the player is, rather than in the extension. Also a LiveActivityIntent: iOS lets an app
/// start a Live Activity from the background only inside one, so a widget tap can bring the lyrics card back.
@available(iOS 17.0, *)
struct LayaWidgetIntent: AudioPlaybackIntent, LiveActivityIntent {
    static var title: LocalizedStringResource = "Laya playback"
    static var isDiscoverable = false

    /// iOS 26 deprecated ForegroundContinuableIntent and ignores its throw from a widget button, leaving the
    /// tap dead when no player was running; dynamic foreground is its replacement and keeps the throw working.
    @available(iOS 26.0, *)
    static var supportedModes: IntentModes { [.background, .foreground(.dynamic)] }

    @Parameter(title: "Command") var command: String

    init() {}
    init(_ command: String) { self.command = command }

    func perform() async throws -> some IntentResult {
        #if !LAYA_WIDGET_EXTENSION
        if LayaWidgetCommands.handler?(command) == true {
            // Running now (the player is up, the app possibly in the background): answer the tap at once.
            LayaWidgetStore.applyOptimistically(command)
            LayaWidgetCommands.restartLyrics?()
            return .result()
        }
        // No player yet (the app was started in the background for this tap): the command is queued, so bring
        // the app up to build one. The glyph is not flipped here — the tap has done nothing yet, and the app's
        // own publish settles it once the queued command runs.
        throw needsToContinueInForegroundError()
        #else
        return .result()
        #endif
    }
}

#if !LAYA_WIDGET_EXTENSION
// Not available to an extension, and only the app's copy ever runs.
@available(iOS 17.0, *)
extension LayaWidgetIntent: ForegroundContinuableIntent {}
#endif

/// "Recognize Music": the Control Center control and the Shortcuts / Siri / Action Button action. Opens Laya on its
/// recognition page and starts listening. iOS lets an app start the microphone only from the foreground (a background
/// AudioRecordingIntent must start a Live Activity, which itself needs the foreground), so the app is brought forward
/// rather than listening behind the lock screen.
struct LayaRecognizeIntent: AppIntent {
    static var title: LocalizedStringResource = "Recognize Music"
    static var description = IntentDescription("Identify the song playing around you with Laya.")
    static var openAppWhenRun = true

    func perform() async throws -> some IntentResult {
        #if !LAYA_WIDGET_EXTENSION
        // Queued until the UI is up when the intent cold-starts the app.
        _ = LayaWidgetCommands.handler?("recognize")
        #endif
        return .result()
    }
}

#if !LAYA_WIDGET_EXTENSION
struct LayaShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: LayaRecognizeIntent(),
            phrases: ["Recognize music with \(.applicationName)", "What's this song in \(.applicationName)"]
        )
    }
}
#endif

// MARK: - Music recognition's Live Activity

/// "Listening…" / "Identifying…" on the lock screen and in the Dynamic Island while Laya listens in the background —
/// iOS's counterpart to Android's listening notification. Started and updated by the app (RecognitionLiveActivityImpl),
/// drawn by the widget extension (RecognitionLiveActivity). Ends when the result is in; the result is a notification.
@available(iOS 16.1, *)
struct LayaRecognitionAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// "listening" or "identifying".
        var phase: String
        var title: String
        var subtitle: String
    }
}

/// The Live Activity's Cancel: a LiveActivityIntent, so it runs in the app's process, where the recognizer is.
@available(iOS 17.0, *)
struct LayaCancelRecognitionIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Stop listening"
    static var isDiscoverable = false

    init() {}

    func perform() async throws -> some IntentResult {
        #if !LAYA_WIDGET_EXTENSION
        await MainActor.run { _ = LayaWidgetCommands.handler?("recognize_cancel") }
        #endif
        // Here as well, not left to the recognizer's state: the tap must clear it even if that never arrives.
        if #available(iOS 16.2, *) {
            for activity in ActivityKit.Activity<LayaRecognitionAttributes>.activities {
                await activity.end(nil, dismissalPolicy: .immediate)
            }
        }
        return .result()
    }
}

// MARK: - Lock-screen lyrics' Live Activity

/// The playing lyric line on the lock screen and in the Dynamic Island, beside the system's Now Playing card (which
/// keeps the controls). Started and updated by the app (LyricsLiveActivityImpl, fed by IosLyricsActivity), drawn by
/// the widget extension (LyricsLiveActivity).
@available(iOS 16.1, *)
struct LayaLyricsAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var title: String
        var artist: String
        /// The sung line, or the title when the song has no synced lyrics.
        var line: String
        /// The line after it, or the artist.
        var next: String
        /// File name of a small cover in the shared container (LayaWidgetStore), once there is one.
        var art: String?
    }
}
