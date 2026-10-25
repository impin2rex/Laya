package com.music.laya.data.sources

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.music.laya.BuildConfig
import com.music.laya.auth.EncryptedPrefs
import com.music.laya.playback.StreamContainer
import kotlinx.coroutines.CompletableDeferred

/**
 * The phone's half of the shared source machinery: the encrypted store the source list lives in,
 * the kinds only it can build (module indexes, JioSaavn), and ExoPlayer being told about manifests.
 */
abstract class AndroidSourcesBehaviour : SourcesHost {
    override val appVersion: String get() = BuildConfig.VERSION_NAME

    override val playsDolbyAtmos: Boolean get() = DeviceCodecs.playsDolbyAtmos

    override fun supports(kind: SourceKind) = true

    override fun build(config: SourceConfig): MusicSource? = when (config.kind) {
        SourceKind.CUSTOM_MODULE, SourceKind.MODULE -> ModuleSource(config)
        SourceKind.JIOSAAVN -> JioSaavnSource(config)
        else -> null
    }

    override fun acceptTransport(url: String, transport: String): Boolean {
        StreamContainer.declare(url, transport)
        return true
    }
}

class AndroidSourcesHost(context: Context) : AndroidSourcesBehaviour() {
    // Same repair and degradation as AuthStore; see EncryptedPrefs.
    private val prefs: SharedPreferences =
        EncryptedPrefs.open(context, "laya_sources", "laya_sources_plain")

    override var storedSources: String?
        get() = prefs.getString("sources", null)
        set(value) = prefs.edit().putString("sources", value).apply()

    /** One-shot migration: existing users must explicitly opt in again. */
    override var jioSaavnOptInDone: Boolean
        get() = prefs.getBoolean("jiosaavn_opt_in_v1", false)
        set(value) = prefs.edit().putBoolean("jiosaavn_opt_in_v1", value).apply()
}

/** The playback URI for a source-backed track; [PlaybackService] resolves it at open time. */
fun SourceRegistry.trackUri(configId: String, trackId: String): String =
    Uri.Builder()
        .scheme("laya")
        .authority("source")
        .appendQueryParameter("s", configId)
        .appendQueryParameter("t", trackId)
        .build()
        .toString()

/**
 * @param uri a `laya://source?...` URI as built by [trackUri].
 * @return the stream, or null when nothing enabled could serve the track.
 */
suspend fun SourceResolver.resolve(uri: Uri, late: CompletableDeferred<SourceStream?>? = null): SourceStream? {
    val configId = uri.getQueryParameter("s") ?: return null.also { late?.complete(null) }
    val trackId = uri.getQueryParameter("t") ?: return null.also { late?.complete(null) }
    return resolve(
        configId = configId,
        trackId = trackId,
        target = targetIn(uri),
        pickedMix = uri.getQueryParameter(SourceResolver.MIX_PARAM),
        late = late,
    )
}

/** Whether [uri] is an addon row played as itself — see [SourceResolver.MIX_PARAM]. */
fun SourceResolver.isPickedAddonRow(uri: Uri): Boolean =
    uri.authority == "source" && uri.getQueryParameter(SourceResolver.MIX_PARAM) != null

/**
 * The recording a playback URI describes, for matching it elsewhere.
 *
 * Title, artist and runtime ride in the URI because they are what a cross-source match is made on,
 * and the resolver runs on ExoPlayer's loader thread with nothing but a DataSpec in hand — see
 * [toMediaItem][com.music.laya.playback.toMediaItem].
 */
fun SourceResolver.targetIn(uri: Uri) = TrackMatcher.Target(
    title = uri.getQueryParameter("n").orEmpty(),
    artist = uri.getQueryParameter("a").orEmpty(),
    durationSec = uri.getQueryParameter("d")?.toIntOrNull(),
    album = uri.getQueryParameter("l"),
    isExplicit = uri.getQueryParameter("e")?.let { it == "1" },
    isVideo = uri.getQueryParameter("m") == "1",
)
