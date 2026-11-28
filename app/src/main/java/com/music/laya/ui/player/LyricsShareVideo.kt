package com.music.laya.ui.player

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import com.music.laya.playback.AudioCache
import com.music.laya.data.YtMusicRepository
import com.music.laya.data.model.SearchFilter
import com.music.laya.data.model.SearchResult
import com.music.laya.data.model.Song
import com.music.laya.data.sources.SourceRegistry
import com.music.laya.data.sources.TrackMatcher
import java.util.concurrent.ConcurrentHashMap
import com.music.laya.data.DebugLog as Log
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.music.laya.data.canvas.CanvasArtwork
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The lyrics card as an MP4, ready for a story.
 *
 * Under the card: the song's animated cover when [clip] is given — Spotify's
 * Canvas or Apple's motion artwork, cropped to the frame, blurred and dimmed a
 * little so white type stays legible — or else the player's backdrop, still.
 *
 * When the lines are synced the video is the song itself: the stretch of the
 * track the lines are sung over is cut from the song's YouTube Opus — see
 * [shareOpusFor] — and [painter] is asked for the card with each line lit as
 * the vocal reaches it; the cover loops underneath for as long as that takes.
 * If no Opus can be had (the cache reads it from the network when it isn't held) the same video
 * is made silent and [onSilent] says so, and unsynced lyrics
 * get the card as it is, held for [MIN_LENGTH_MS].
 *
 * Returns the file in the share folder, or null when nothing could be exported.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal suspend fun exportLyricsVideo(
    context: Context,
    request: LyricsShareRequest,
    clip: CanvasArtwork?,
    /** The card without its backdrop, drawn at any moment of the song. */
    painter: LyricsCardPainter,
    /** Called when a song-backed video had to be made without the song (no Opus, or it would not cut). */
    onSilent: () -> Unit = {},
): File? {
    val folder = File(context.cacheDir, SHARE_FOLDER).apply { mkdirs() }
    val out = File(folder, VIDEO_FILE_NAME)
    val timeline = shareTimeline(request.lines)
    // H.264 wants even sides; the card's height is whatever its lines came to.
    val width = painter.width and 1.inv()
    val height = painter.height and 1.inv()
    val overlay = SyncedOverlay(painter, timeline, width, height)
    val lengthMs = timeline?.durationMs ?: MIN_LENGTH_MS

    // The still backdrop, as an image the export can hold for the video's length.
    val backdrop = if (clip == null) {
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(folder, BACKDROP_FILE_NAME)
                val picture = renderLyricsShareBackdrop(context, request, height)
                file.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                file
            }.onFailure { Log.w(TAG, "lyrics video backdrop render failed", it) }.getOrNull()
        } ?: return null
    } else {
        null
    }
    val urls = clip?.let {
        listOfNotNull(it.url, it.fallbackUrl).distinct()
    }

    // Looked up once: for an addon or JioSaavn row it is a YouTube search.
    val opus = timeline?.let { shareOpusFor(request.song) }?.let { (factory, uri) ->
        ShareAudio(factory, uri, timeline)
    }

    suspend fun attempt(withAudio: Boolean): Boolean {
        val audio = if (withAudio) opus ?: return false else null
        if (backdrop != null) {
            val still = EditedMediaItem.Builder(
                MediaItem.Builder()
                    .setUri(Uri.fromFile(backdrop))
                    .setMimeType(MimeTypes.IMAGE_PNG)
                    .setImageDurationMs(lengthMs)
                    .build(),
            )
                .setFrameRate(FRAME_RATE)
                .setEffects(Effects(emptyList(), listOf(fit(width, height))))
                .build()
            return transform(context, EditedMediaItemSequence.Builder(still).build(), audio, overlay, out)
        }
        for (url in urls.orEmpty()) {
            val item = EditedMediaItem.Builder(MediaItem.fromUri(url))
                .setRemoveAudio(true)
                .setEffects(clipEffects(width, height))
                .build()
            val video = if (audio != null) {
                // The song sets the length; the cover loops under it.
                EditedMediaItemSequence.Builder(item).setIsLooping(true).build()
            } else {
                val length = probeLengthMs(context, url) ?: continue
                val repeats = ceil(lengthMs / length.toDouble()).toInt().coerceIn(1, MAX_REPEATS)
                EditedMediaItemSequence.Builder(List(repeats) { item }).build()
            }
            if (transform(context, video, audio, overlay, out)) return true
        }
        return false
    }

    if (opus != null && attempt(withAudio = true)) return out
    if (timeline != null) onSilent()
    if (attempt(withAudio = false)) return out
    Log.w(TAG, "lyrics video export failed on every attempt (clip=${clip != null}, audio=${opus != null})")
    return null
}

/** Scaled and cropped to fill the card's frame. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun fit(width: Int, height: Int) =
    Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)

/**
 * Blurred at a quarter of the size and scaled back up: the same picture as
 * blurring the full frame with four times the radius, for a sixteenth of the
 * pixels. Then dimmed, under white type.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun clipEffects(width: Int, height: Int) = Effects(
    emptyList(),
    listOf(
        fit((width / 4) and 1.inv(), (height / 4) and 1.inv()),
        GaussianBlur(BLUR_SIGMA),
        fit(width, height),
        RgbAdjustment.Builder().setRedScale(DIM).setGreenScale(DIM).setBlueScale(DIM).build(),
    ),
)

/**
 * The card over every frame, drawn at the moment of the song that frame plays —
 * the sweep, the lifts and the held words moving exactly as they do in the
 * player. One bitmap, redrawn in place on the export's GL thread; the redraw
 * changes its generation id, which is what tells the overlay to upload it.
 * Without a timeline (unsynced lyrics) the still card, drawn once.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class SyncedOverlay(
    private val painter: LyricsCardPainter,
    private val timeline: ShareTimeline?,
    width: Int,
    height: Int,
) : BitmapOverlay() {
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private var drawnAt: Long? = Long.MIN_VALUE

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val at = timeline?.trackMsAt(presentationTimeUs)
        if (at != drawnAt) {
            painter.render(at, bitmap)
            drawnAt = at
        }
        return bitmap
    }
}

/**
 * Which items are the song: those go through the player's chain, everything
 * else — the cover clip, Apple's HLS — through a plain one, so the clip never
 * lands in the audio cache or in front of the stream resolver.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class RoutingSourceFactory(
    private val plain: MediaSource.Factory,
    private val song: MediaSource.Factory,
) : MediaSource.Factory {
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        plain.setDrmSessionManagerProvider(provider)
        song.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        plain.setLoadErrorHandlingPolicy(policy)
        song.setLoadErrorHandlingPolicy(policy)
    }

    override fun getSupportedTypes(): IntArray = plain.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        if (mediaItem.localConfiguration?.tag === SongAudio) song.createMediaSource(mediaItem)
        else plain.createMediaSource(mediaItem)
}

/** Marks the song's own items for [RoutingSourceFactory]. */
private object SongAudio

/** Where the share video's audio is read from, and which stretches of it. */
private class ShareAudio(val factory: DataSource.Factory, val uri: Uri, val timeline: ShareTimeline)

/**
 * The song's YouTube Opus, whatever it is playing from right now.
 *
 * A share is cut from the same rendition every time — the Opus YouTube serves
 * and the player fetches first — rather than from whichever stream playback
 * settled on: Direct Source Stream's addon file, JioSaavn, a lossless upgrade.
 * A YouTube track reads its own; an addon or JioSaavn row (and a file on the
 * device) is matched to its YouTube recording first, the way Automix's analysis
 * is, and has no audio when nothing matches. See [AudioCache.opusShareSource].
 */
private suspend fun shareOpusFor(song: Song): Pair<DataSource.Factory, Uri>? {
    val id = youTubeIdOf(song) ?: return null
    return AudioCache.opusShareSource(id)
}

private suspend fun youTubeIdOf(song: Song): String? {
    val key = song.videoId
    if (key.isBlank()) return null
    val local = key.startsWith("content://") || key.startsWith("file://")
    if (!local && SourceRegistry.parseTrackKey(key) == null) return key
    youTubeMatches[key]?.let { return it }
    val target = TrackMatcher.targetOf(song).copy(isVideo = false)
    if (target.title.isBlank()) return null
    return withContext(Dispatchers.IO) {
        for (query in TrackMatcher.queries(target)) {
            val candidates = runCatching { YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull() }
                .getOrNull()
                ?.filterIsInstance<SearchResult.Track>()
                ?.map { it.song }
                .orEmpty()
            val match = TrackMatcher.best(candidates, target) ?: continue
            youTubeMatches[key] = match.videoId
            return@withContext match.videoId
        }
        null
    }
}

/** Addon and JioSaavn rows already matched to YouTube, so a second export doesn't search again. */
private val youTubeMatches = ConcurrentHashMap<String, String>()

/** The clip's length, read off a muted player that is released straight after. */
@androidx.annotation.OptIn(UnstableApi::class)
private suspend fun probeLengthMs(context: Context, url: String): Long? = withContext(Dispatchers.Main) {
    val player = ExoPlayer.Builder(context).build()
    try {
        withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching {
                suspendCancellableCoroutine { cont ->
                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY && cont.isActive) {
                                cont.resume(player.duration.takeIf { it != C.TIME_UNSET && it > 0 })
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                    player.volume = 0f
                    player.setMediaItem(MediaItem.fromUri(url))
                    player.prepare()
                }
            }.onFailure { Log.w(TAG, "lyrics video cover length probe failed", it) }.getOrNull()
        }
    } finally {
        player.release()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private suspend fun transform(
    context: Context,
    video: EditedMediaItemSequence,
    /** The Opus and the stretches to cut from it; null for a silent video. */
    audio: ShareAudio?,
    overlay: BitmapOverlay,
    out: File,
): Boolean {
    val sequences = mutableListOf(video)
    var songFactory: MediaSource.Factory = DefaultMediaSourceFactory(context)
    if (audio != null) {
        val pieces = audio.timeline.segments.map { (start, end) ->
            EditedMediaItem.Builder(
                MediaItem.Builder()
                    .setUri(audio.uri)
                    .setTag(SongAudio)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(start)
                            .setEndPositionMs(end)
                            .build(),
                    )
                    .build(),
            )
                .setRemoveVideo(true)
                .build()
        }
        sequences += EditedMediaItemSequence.Builder(pieces).build()
        songFactory = DefaultMediaSourceFactory(audio.factory)
    }
    // The card goes on over the whole composition, so its clock is the
    // video's own — the same however many times the cover has looped.
    val composition = Composition.Builder(sequences)
        .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(overlay)))))
        .build()

    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            // Building or starting the Transformer can itself throw — a codec or GL
            // context the device doesn't have, say — rather than reaching the
            // listener below. Caught here so that throw resumes false instead of
            // escaping un-awaited: left to propagate normally it would skip the
            // silent-video fallback this attempt is one half of (see [attempt]).
            try {
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setAssetLoaderFactory(
                        DefaultAssetLoaderFactory(
                            context,
                            DefaultDecoderFactory.Builder(context).build(),
                            Clock.DEFAULT,
                            RoutingSourceFactory(DefaultMediaSourceFactory(context), songFactory),
                            DataSourceBitmapLoader(context),
                        ),
                    )
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE).build(),
                            )
                            .build(),
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            Log.w(TAG, "lyrics video export failed (audio=${audio != null})", exportException)
                            if (cont.isActive) cont.resume(false)
                        }
                    })
                    .build()
                out.delete()
                transformer.start(composition, out.path)
                // The sheet closed mid-export: stop encoding, on the thread it runs on.
                cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
            } catch (e: Exception) {
                Log.w(TAG, "lyrics video transformer setup failed (audio=${audio != null})", e)
                if (cont.isActive) cont.resume(false)
            }
        }
    }
}

/** [video] handed to another app, through the same FileProvider folder the card goes through. */
internal fun shareVideoIntent(context: Context, video: File): Intent? = runCatching {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", video)
    Intent(Intent.ACTION_SEND)
        .setType(VIDEO_MIME)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}.getOrNull()

/** Copies [video] into the device's Movies/Laya, where a gallery will find it. */
internal suspend fun saveVideoToGallery(context: Context, video: File, label: String): Boolean =
    withContext(Dispatchers.IO) {
        val name = "laya-lyrics-${label.replace(' ', '-').lowercase(Locale.ROOT)}.mp4"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                // Pending while it is written: the gallery doesn't list a row
                // until it is cleared, so "Saved" never names a half-copied file.
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, VIDEO_MIME)
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Laya")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri: Uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("no row")
                try {
                    val written = resolver.openOutputStream(uri)?.use { sink ->
                        video.inputStream().use { it.copyTo(sink) }
                    } ?: error("no stream")
                    check(written == video.length()) { "short write" }
                    val published = resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                        null,
                        null,
                    )
                    check(published == 1) { "not published" }
                } catch (e: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Laya")
                dir.mkdirs()
                video.copyTo(File(dir, name), overwrite = true)
            }
            true
        }.onFailure { Log.w(TAG, "lyrics video gallery save failed", it) }.getOrDefault(false)
    }

private const val SHARE_FOLDER = "shared"
private const val VIDEO_FILE_NAME = "lyrics.mp4"
private const val VIDEO_MIME = "video/mp4"

/** Long enough to read five lines in a story before it loops. */
private const val MIN_LENGTH_MS = 10_000L

private const val FRAME_RATE = 30
private const val BACKDROP_FILE_NAME = "lyrics-backdrop.png"
private const val TAG = "LyricsShareVideo"
private const val MAX_REPEATS = 6
private const val PROBE_TIMEOUT_MS = 10_000L

/** At quarter size: about the blur the preview draws at full size. */
private const val BLUR_SIGMA = 12f

/**
 * The clip at 60%: about as dark as the player's own backdrop, so a bright
 * Canvas sits under the words the way the gradient does.
 */
private const val DIM = 0.6f

/** Comfortably past what Instagram re-encodes a 1080p story to. */
private const val VIDEO_BITRATE = 10_000_000
