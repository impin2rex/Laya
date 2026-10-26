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
}

/// A widget button: previous, toggle, next, like or shuffle. An AudioPlaybackIntent, so the system runs it in the
/// app's process, where the player is, rather than in the extension.
@available(iOS 17.0, *)
struct LayaWidgetIntent: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Laya playback"
    static var isDiscoverable = false

    @Parameter(title: "Command") var command: String

    init() {}
    init(_ command: String) { self.command = command }

    func perform() async throws -> some IntentResult {
        LayaWidgetStore.applyOptimistically(command)
        #if !LAYA_WIDGET_EXTENSION
        // No player yet (the app was started in the background for this): bring it up; the command is queued and
        // runs as soon as the player exists.
        if LayaWidgetCommands.handler?(command) != true {
            throw needsToContinueInForegroundError()
        }
        #endif
        return .result()
    }
}

#if !LAYA_WIDGET_EXTENSION
// Not available to an extension, and only the app's copy ever runs.
@available(iOS 17.0, *)
extension LayaWidgetIntent: ForegroundContinuableIntent {}
#endif
