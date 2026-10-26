package com.music.laya

import okio.Path.Companion.toOkioPath
import android.app.Application
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import com.music.laya.auth.AuthStore
import com.music.laya.data.DebugLog
import com.music.laya.data.lyrics.LyricsTranslation
import androidx.appcompat.app.AppCompatDelegate
import com.music.laya.ui.player.AndroidPlayerHost
import com.music.laya.ui.player.PlayerPlatform
import com.music.laya.data.canvas.AndroidSpotifyHarvest
import com.music.laya.data.canvas.CanvasCache
import com.music.laya.data.smb.SmbCoverFetcher
import com.music.laya.data.webdav.WebDavCoilAuth
import com.music.laya.data.canvas.SpotifyToken
import com.music.laya.data.spotify.SpotifyLibrary
import com.music.laya.playback.AudioCache
import com.music.laya.playback.LastPlayed
import com.music.laya.playback.MusicLink
import com.music.laya.playback.BoundedContextPersistence
import com.music.laya.playback.OriginalVersion
import com.music.laya.data.innertube.Innertube
import com.music.laya.data.innertube.AndroidStreamHooks
import com.music.laya.data.listentogether.ListenTogether
import com.music.laya.data.listentogether.init
import com.music.laya.data.scrobbling.LastFM
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.settings.init
import com.music.laya.data.settings.SearchHistory
import com.music.laya.data.sources.SourceRegistry
import com.music.laya.data.sources.AndroidSourcesHost
import com.music.laya.data.stats.initArtistFacts
import com.music.laya.data.stats.initListening
import com.music.laya.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import kotlin.concurrent.thread

class LayaApplication : Application(), SingletonImageLoader.Factory {

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        com.music.laya.ui.theme.SFProDisplay = androidx.compose.ui.text.font.FontFamily(
            androidx.compose.ui.text.font.Font(R.font.sf_pro_display_regular, androidx.compose.ui.text.font.FontWeight.W400),
            androidx.compose.ui.text.font.Font(R.font.sf_pro_display_medium, androidx.compose.ui.text.font.FontWeight.W500),
            androidx.compose.ui.text.font.Font(R.font.sf_pro_display_semibold, androidx.compose.ui.text.font.FontWeight.W600),
            androidx.compose.ui.text.font.Font(R.font.sf_pro_display_bold, androidx.compose.ui.text.font.FontWeight.W700),
            androidx.compose.ui.text.font.Font(R.font.sf_pro_display_heavy, androidx.compose.ui.text.font.FontWeight.W800),
        )
        // The player is drawn by the shared UI module; this is what it reads
        // underneath — settings, the Canvas decoder, outputs, the party.
        AndroidStreamHooks.installEarly()
        // Karaoke's vocal model ships in the assets; ONNX Runtime wants a file,
        // so it is copied out the first time karaoke runs. Where builds that
        // downloaded it kept their copy goes.
        java.io.File(filesDir, "models").deleteRecursively()
        com.music.laya.playback.karaoke.KaraokeModel.install {
            unpackAsset(com.music.laya.playback.karaoke.KaraokeModel.ASSET)
        }
        // The song being sung, separated: one at a time, in the cache, cleared at launch.
        com.music.laya.playback.karaoke.KaraokeStems.install(java.io.File(cacheDir, "karaoke").toOkioPath())
        PlayerPlatform.install(AndroidPlayerHost(this))
        com.music.laya.ui.screens.PartyPlatform.host = com.music.laya.ui.AndroidPartyPlatform(this)
        com.music.laya.ui.AppUi.install(com.music.laya.ui.AndroidAppUiHost)
        com.music.laya.ui.screens.DetailExtras.host = com.music.laya.ui.AndroidDetailHost
        com.music.laya.ui.AndroidSettingsHost.install(this)
        com.music.laya.ui.screens.SettingsExtras.host = com.music.laya.ui.AndroidSettingsHost
        // The shared data layer (lyrics, the YouTube Music client) logs to
        // logcat on debug builds only, as the app's own DebugLog always has.
        if (BuildConfig.DEBUG) {
            com.music.laya.playback.smart.AnalysisLog.install { message, error ->
                android.util.Log.w("LayaAnalysis", message, error)
            }
            DebugLog.sink = DebugLog.Sink { level, tag, message, error ->
                when (level) {
                    'D' -> android.util.Log.d(tag, message, error)
                    'I' -> android.util.Log.i(tag, message, error)
                    'W' -> android.util.Log.w(tag, message, error)
                    else -> android.util.Log.e(tag, message, error)
                }
            }
        }
        // The Spotify library and import live in the shared module; the tokens are the WebView's.
        SpotifyLibrary.auth = SpotifyLibrary.Auth {
            SpotifyToken.accessToken()?.let { SpotifyLibrary.Tokens(it, SpotifyToken.clientToken()) }
        }
        // The per-app language picker, which YouTube Music's `hl` follows.
        // Full tag (zh-Hant / zh-Hans preserved); Innertube maps to hl/gl.
        Innertube.appLanguage = { AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag() }
        migrateScriptOnlyAppLocale()
        registerSearchShortcut()
        LyricsTranslation.cacheDir = cacheDir.toOkioPath()
        // PlaybackService shares this process, so seeding the cookie here means
        // stream resolution is authenticated from the first play onwards.
        authStore = AuthStore(this)
        // Opened off the main thread, alongside everything below: none of these
        // reads a setting or the session, and between them they are the slowest
        // opens at startup — SourceRegistry's encrypted store most of all.
        // Started only once [AuthStore] exists, because both encrypted stores
        // share one keystore master key and a first launch must not have two
        // threads racing to create it. Joined before onCreate returns, so
        // nothing that runs after startup can see any of them half open.
        val backgroundInit = thread(name = "startup-init") {
            SourceRegistry.init(AndroidSourcesHost(this))
            AndroidStreamHooks.initInnerTubeX(this)
            // Its own directory: canvas clips are looping video, not audio, and
            // belong in a cache AudioCache's own limit and eviction policy were
            // never sized for. See CanvasCache's doc for why this one exists at
            // all — it is the fix for canvas clips re-fetching the same few
            // seconds of video from the network on every loop.
            CanvasCache.init(this)
        }
        // Migration-safe: an old single cookie becomes the first encrypted
        // session, while newer installs restore the profile the listener chose.
        val restoredSession = authStore.activeSession
        if (restoredSession != null && authStore.activeAccountId == null) {
            authStore.select(restoredSession.accountId, restoredSession.activeProfileId)
        }
        authStore.cookie = restoredSession?.cookie
        Innertube.cookie = restoredSession?.cookie
        // Which account that cookie actually acts as. Read here rather than on
        // demand so the answer is usually in hand before the first request needs
        // it: a play registered under the wrong account is indistinguishable, to
        // the listener, from one that was never registered at all. Fire and
        // forget — every caller works without it, just less precisely.
        if (restoredSession != null) {
            // After the cookie, never before: setting the cookie clears any
            // channel the last session was acting as, so restoring the choice
            // first would restore it into the value about to be wiped.
            restoredSession.profiles.firstOrNull { it.profileId == restoredSession.activeProfileId }
                ?.let { Innertube.selectChannel(it.pageId, it.dataSyncId, it.authUser) }
            CoroutineScope(Dispatchers.IO).launch { Innertube.ensureSessionScope() }
        }
        AppSettings.init(this, authStore)
        // Before anything resolves a track: an addon with `checkValidLossless`
        // is gated on this, and the gate reads "no" until it has looked.
        com.music.laya.playback.audio.LosslessOutput.init(this)
        // Restores a party this device is still a member of, so a process death
        // mid-session is something the rest of the party never sees. The socket
        // and the clock offset are not restored — both are re-established on
        // the next connect, which is the only way to be sure they are current.
        ListenTogether.init(this)
        SearchHistory.init(this)
        LastPlayed.init(this)
        BoundedContextPersistence.init(this)
        com.music.laya.playback.PartyPersonalQueueStash.init(this)
        // Which tracks the listener has reverted to YouTube's own upload. Read
        // by [Song.toMediaItem], so it has to be open before the restart
        // snapshot below is turned back into queue items.
        OriginalVersion.init(this)
        com.music.laya.playback.YouTubeOriginals.init(this)
        // What's already saved to Downloads, so the song menu can say so
        // without a media-store query per row.
        Downloads.init(this)
        // The device's own listening record. Opened here rather than in
        // PlaybackService because the Replay page reads it from the UI side and
        // both live in this process — one owner, one directory.
        initListening(this)
        // After AppSettings, whose switch decides whether half of it runs.
        initArtistFacts(this)
        // One cache directory can only be opened once per process, and
        // PlaybackService shares this one — so it's opened here, not there.
        AudioCache.init(this)
        // The offscreen WebView that mints a Spotify access token from the
        // listener's own session cookie needs a Context, and nothing in the
        // suspend call chain that reaches it (a track's canvas lookup) has
        // one to hand — see SpotifyToken's doc for why.
        AndroidSpotifyHarvest.install(this)
        // A sideloaded update is just a new APK over the old one, so app data —
        // including whatever the old build left in the image cache — survives
        // it untouched. Wipe that on the first launch of a higher versionCode so
        // a format change between builds can't serve stale bytes.
        //
        // The song cache is deliberately *not* wiped here any more. It is the
        // Cached songs folder and what plays offline, and clearing it on every
        // update is what made the whole folder disappear overnight whenever a
        // new build was installed. A build that really does change its keys or
        // its bytes says so through AudioCache's own cache format instead.
        if (AppSettings.consumeVersionUpdate(BuildConfig.VERSION_CODE)) {
            SingletonImageLoader.get(this).let { loader ->
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
        }
        // Initialize LastFM with saved settings if available
        initLastfm()
        // The public "apps open right now" count; see Presence.
        com.music.laya.data.presence.AndroidPresence.install(this)
        backgroundInit.join()
    }

    /**
     * Coil's own transport, separate from [Http][com.music.laya.data.Http]'s
     * playback-tuned client.
     *
     * Wikimedia (which serves signature art) answers generic clients with
     * HTTP 403 under its User-Agent policy, so every image request carries an
     * identifying agent. Anything else falls through to Coil's own fetchers.
     */
    private val coilHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header(
                            "User-Agent",
                            "Laya/${BuildConfig.VERSION_NAME} (Android; +https://github.com/impin2rex/Laya)",
                        )
                        .build(),
                )
            }
            .build()
    }

    /**
     * Artwork loading, which was previously left entirely on Coil's defaults.
     *
     * The defaults aren't unreasonable, but the disk cache is sized at 2% of
     * free space — which on a full phone is the 10MB floor, a few screens of
     * covers, and covers are exactly the thing worth still having tomorrow.
     * Naming a directory alongside it keeps that cache somewhere identifiable
     * rather than in the process's temp dir.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            // Covers on the WebDAV server need the credential or every one
            // of them 401s — which reads as "this track has no artwork".
            // Coil's own transport never sees Http.client's interceptor, so
            // the header is attached per request instead. See WebDavCoilAuth.
            .components {
                add(WebDavCoilAuth())
                // Covers filed on the SMB share; anything else falls
                // through to Coil's own fetchers. See SmbCoverFetcher.
                add(SmbCoverFetcher.Factory())
                // Artist signatures are usually SVG, which Coil can't
                // decode without this. See ArtistSignatureRepository.
                add(SvgDecoder.Factory())
                // Wikimedia 403s Coil's default client; see [coilHttpClient].
                add(OkHttpNetworkFetcherFactory(callFactory = { coilHttpClient }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            // Covers arriving with a hard cut read as the list flickering as
            // it scrolls; a short fade reads as them developing.
            .crossfade(200)
            .build()

    private fun initLastfm() {
        val sessionKey = AppSettings.lastfmSessionKey.value
        if (sessionKey.isBlank()) return
        val endpoint = AppSettings.lastfmEndpoint.value.ifBlank { LastFM.DEFAULT_API_ENDPOINT }
        val apiKey = AppSettings.lastfmApiKey.value.trim()
        val secret = AppSettings.lastfmSecret.value.trim()
        if (apiKey.isBlank() || secret.isBlank()) return
        LastFM.configure(
            endpoint = endpoint,
            apiKey = apiKey,
            secret = secret,
            sessionKey = sessionKey,
        )
    }

    /**
     * The launcher's long-press Search entry, as Spotify has. Dynamic rather than
     * a static shortcuts.xml because the dev and prod flavours are different
     * packages, and a static shortcut has to name its package outright.
     * Re-published on every start, which also re-labels it after a language
     * change; a launcher that rate-limits or refuses shortcuts just goes without.
     */
    private fun registerSearchShortcut() {
        val shortcut = ShortcutInfoCompat.Builder(this, SHORTCUT_SEARCH)
            .setShortLabel(getString(R.string.search))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_search))
            .setIntent(Intent(MusicLink.ACTION_OPEN_SEARCH, null, this, MainActivity::class.java))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(this, shortcut) }
    }

    companion object {
        lateinit var authStore: AuthStore
            private set

        private const val SHORTCUT_SEARCH = "search"

        /**
         * One-time move from region-less script tags to regional ones.
         *
         * Early builds of the Simplified/Traditional picker stored `zh-Hant` /
         * `zh-Hans`. Android Views resolve those (script qualifiers), but the
         * shared Compose resources only match language + region — so Settings
         * read Traditional while the player read Simplified. Regional tags
         * match on both systems. Bare `zh` is left alone: it historically
         * meant Simplified.
         */
        private fun migrateScriptOnlyAppLocale() {
            val current = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
            val replacement = when (current) {
                "zh-Hant" -> "zh-TW"
                "zh-Hans" -> "zh-CN"
                else -> return
            }
            AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(replacement),
            )
        }
    }

    /**
     * [name] from the assets as a file, copied once. Written beside and then
     * renamed, so a copy cut short by the process dying is never taken for the
     * whole one.
     */
    private fun unpackAsset(name: String): String {
        val file = java.io.File(filesDir, name)
        val expected = runCatching { assets.openFd(name).use { it.length } }.getOrDefault(-1L)
        if (file.exists() && (expected < 0 || file.length() == expected)) return file.path
        val partial = java.io.File(filesDir, "$name.partial")
        assets.open(name).use { input -> partial.outputStream().use { input.copyTo(it) } }
        partial.renameTo(file)
        return file.path
    }
}
