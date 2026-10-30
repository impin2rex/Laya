package com.music.laya.ios

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeUIViewController
import com.music.laya.sharedui.resources.Res
import com.music.laya.ui.theme.SFProDisplay
import kotlinx.coroutines.runBlocking
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.svg.SvgDecoder
import com.music.laya.ui.screens.DetailExtras
import com.music.laya.data.DebugLog
import com.music.laya.data.stats.ArtistFacts
import com.music.laya.data.stats.ListeningStats
import com.music.laya.data.TrackLog
import com.music.laya.data.innertube.InnerTubeXResolver
import com.music.laya.data.lyrics.LyricsTranslation
import com.music.laya.ui.AppUi
import com.music.laya.ui.player.PlayerPlatform
import com.music.laya.ui.screens.SettingsExtras
import com.music.laya.playback.karaoke.KaraokeModel
import com.music.laya.playback.karaoke.KaraokeStems
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.sources.SourceRegistry
import com.music.laya.data.settings.AppSettingsHost
import com.russhwolf.settings.NSUserDefaultsSettings

/** Entry point called from Swift: hosts the UI with the Swift-side AVFoundation [engine]; [onStatusBarLight] tells the host VC which status bar icon colour the screen needs. */
@OptIn(ExperimentalComposeUiApi::class)
fun MainViewController(engine: AudioEngine, cast: CastNative, onStatusBarLight: (Boolean) -> Unit): UIViewController {
    // Not NSLog: Kotlin strings passed through its C varargs crash (EXC_BAD_ACCESS).
    DebugLog.sink = DebugLog.Sink { level, tag, message, error -> println("$tag/$level: $message ${error ?: ""}") }
    TrackLog.echo = { level, tag, message, error -> println("$tag/$level: $message ${error ?: ""}") }
    SingletonImageLoader.setSafe { ctx -> ImageLoader.Builder(ctx).components { add(KtorNetworkFetcherFactory()); add(SvgDecoder.Factory()) }.build() }
    val dir = NSFileManager.defaultManager
        .URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first().let { (it as platform.Foundation.NSURL).path!! }
    LyricsTranslation.cacheDir = NSFileManager.defaultManager
        .URLsForDirectory(NSCachesDirectory, NSUserDomainMask).first().let { (it as platform.Foundation.NSURL).path!! }.toPath()
    InnerTubeXResolver.init(
        filesDir = dir.toPath(),
        // ponytail: in-memory, so cached player config is refetched each launch; NSUserDefaults when that hurts.
        store = object : InnerTubeXResolver.ConfigStore {
            private val m = mutableMapOf<String, Any>()
            override fun getString(key: String) = m[key] as? String
            override fun getLong(key: String) = m[key] as? Long
            override fun putString(key: String, value: String) { m[key] = value }
            override fun putLong(key: String, value: Long) { m[key] = value }
        },
        poTokenProvider = null,
    )
    KaraokeModel.install { NSBundle.mainBundle.pathForResource("UVR_MDXNET_9482", "onnx")!! }
    KaraokeStems.install(LyricsTranslation.cacheDir!!.div("karaoke"))
    KaraokeStems.exactClock = true // IosDsp counts the tap's frames, so the offset holds across seeks
    AppSettings.init(NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults), object : AppSettingsHost() { override val signatureApiUrl = SIGNATURE_API_URL })
    // The device's own listening record (Replay), under Application Support the way Android keeps it in filesDir.
    ListeningStats.init(dir.toPath() / ListeningStats.DIRECTORY)
    ArtistFacts.init(dir.toPath() / ArtistFacts.FILE_NAME)
    watchIosConnection()
    IosParty.install()
    com.music.laya.ui.screens.PartyPlatform.host = IosPartyPlatform
    SourceRegistry.init(IosSourcesHost)
    // Names only, in the order they are asked: addon addresses carry their owner's token.
    TrackLog.sourcesLine = {
        "sources: " + SourceRegistry.active().joinToString(" > ") { it.displayName }.ifEmpty { "none" } +
            " substitution=${com.music.laya.data.sources.SourceResolver.canSubstituteForYouTube()}"
    }
    DetailExtras.host = IosDetailHost
    IosSpotify.restore()
    com.music.laya.playback.smart.AnalysisLog.install { m, e -> println("Analysis: $m ${e ?: ""}") }
    IosAuth.restore() // Keychain session -> Innertube, before the first request
    AppUi.install(IosAppUiHost)
    SettingsExtras.host = IosSettingsHost
    PlayerPlatform.install(IosPlayerHost)
    IosRoute.engine = engine
    IosCast.install(cast)
    // Same bundled SF Pro Display Android installs in LayaApplication; read up front so the theme sees it on first composition.
    SFProDisplay = FontFamily(
        mapOf(400 to "regular", 500 to "medium", 600 to "semibold", 700 to "bold", 800 to "heavy").map { (w, n) ->
            Font("sf_pro_display_$n", runBlocking { Res.readBytes("font/sf_pro_display_$n.otf") }, FontWeight(w))
        },
    )
    // Not opaque: an opaque Metal layer clears to white wherever nothing is drawn, which shows as a white band
    // while the canvas resizes on rotation. Transparent lets the host's black background show instead.
    return ComposeUIViewController(configure = { opaque = false }) { LayaIosApp(engine, onStatusBarLight) }
}
