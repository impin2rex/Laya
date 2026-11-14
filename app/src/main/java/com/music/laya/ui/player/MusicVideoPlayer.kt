package com.music.laya.ui.player

import android.graphics.Bitmap
import com.music.laya.data.settings.VideoCropSettler
import com.music.laya.data.settings.VideoCrop
import com.music.laya.data.settings.BlackBars
import com.music.laya.data.settings.VideoScale
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Rational
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.music.laya.data.Http
import com.music.laya.data.TrackLog
import com.music.laya.data.innertube.InnerTubeXResolver
import com.music.laya.data.model.Song
import com.music.laya.data.settings.AppSettings
import com.music.laya.playback.ChunkedDataSource
import com.music.laya.playback.toSong
import com.music.laya.ui.rememberIsForeground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A music video's picture, the way the web player's videoDeck plays one: a
 * second ExoPlayer, video only and silent, held to the main player's clock.
 *
 * The audio is untouched — it stays in [com.music.laya.playback.PlaybackService]
 * with everything that runs on it (the cache, the EQ, crossfade, Listen
 * Together) — and stays the clock. The picture follows it: its rate nudged a
 * few percent for small drift, a seek for a large one. It streams only while a
 * surface wants it, so a music video playing with the screen off or the player
 * shut costs no more data than the song.
 *
 * Surfaces take turns holding the one decoder, the last one mounted in front —
 * the full player over the mini player, the landscape player over either — as
 * only one of them is ever on screen at a time.
 */
@OptIn(UnstableApi::class)
object MusicVideoEngine {

    private const val TAG = "MusicVideo"

    /** Past this the picture jumps to the audio; under it, its rate is nudged. */
    private const val SEEK_DRIFT_MS = 450L
    private const val IGNORE_DRIFT_MS = 25L
    private const val SYNC_EVERY_MS = 250L

    /** No second seek inside this, while the first is still landing. */
    private const val SEEK_COOLDOWN_MS = 1_500L

    /** How long the picture outlives its last surface: a rotation, a sheet reopened. */
    private const val RELEASE_GRACE_MS = 4_000L

    /** Bounded ranges, for the same reason as the audio — see [ChunkedDataSource]. */
    private const val CHUNK_BYTES = 1L * 1024 * 1024

    /** A googlevideo URL lives about six hours; a resolved stream is reused well inside that. */
    private const val STREAM_TTL_MS = 60 * 60 * 1000L

    private val scope = MainScope()

    private var controller: Player? = null
    private var player: ExoPlayer? = null
    private var appContext: Context? = null

    /**
     * A quality change in progress: the new rendition opening off screen, muted and without a
     * surface, while [player] keeps playing the old one — swapped in once it can draw. See
     * [switchQuality].
     */
    private var standby: ExoPlayer? = null
    private var standbyJob: Job? = null
    private class Surface(val view: TextureView, val priority: Int)

    private val surfaces = ArrayList<Surface>()

    /** The surface in front: the highest priority, the latest of equals. */
    private fun top(): TextureView? = surfaces.lastOrNull { s -> surfaces.none { it.priority > s.priority } }?.view

    /** The video loaded (or loading) into [player]. */
    private var loadedId: String? = null
    private var loadJob: Job? = null
    private var syncJob: Job? = null
    private var releaseJob: Job? = null
    private var lastSeekAt = 0L
    private var seekStartedAt = 0L

    /** How far ahead of the audio a seek is aimed: what the last one took to land. */
    private var seekLeadMs = 250L

    /** Resolved streams by video: the stream, the height it was asked at, and when. */
    private val streams = LinkedHashMap<String, Triple<InnerTubeXResolver.VideoStream, Int, Long>>()

    /** Tracks with no picture to show, so a track change back to one doesn't ask again. */
    private val noPicture = HashSet<String>()

    private val _shownVideoId = MutableStateFlow<String?>(null)

    /** The video whose frames are on screen now, once its first one has landed. */
    val shownVideoId: StateFlow<String?> = _shownVideoId.asStateFlow()

    private val _frameOn = MutableStateFlow<TextureView?>(null)

    /** The surface the last frame was drawn to. */
    internal val frameOn: StateFlow<TextureView?> = _frameOn.asStateFlow()

    private val _quality = MutableStateFlow(MusicVideoQualityUi())

    /** The heights the loaded video offers, the held one, and the one on screen. */
    val quality: StateFlow<MusicVideoQualityUi> = _quality.asStateFlow()

    private var prefs: android.content.SharedPreferences? = null

    /** What the loaded stream was asked for, so a failed high one can step down once. */
    private var loadedCap = 0

    /**
     * Videos that wouldn't decode here at the height asked for, and the lower cap they play at
     * instead. A height alone doesn't bound the picture: an ultra-wide 2560x1066 passes a 1080p
     * cap and is still too wide for a decoder that tops out at 1920 (an Android TV's VP9).
     */
    private val stepDown = HashMap<String, Int>()

    /** Heights of each video that failed to play here, shown greyed out in a quality picker. */
    private val failedHeights = HashMap<String, MutableSet<Int>>()

    /**
     * What Auto opens at with no metered connection: a TV's largest picture its own decoders
     * play, else [AUTO_HEIGHT] — a phone's screen gains nothing from more, and pays for it.
     */
    private var autoHeight = AUTO_HEIGHT

    private val _playing = MutableStateFlow(false)

    /** Whether the audio the picture follows is playing — what keeps the screen awake. */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _aspect = MutableStateFlow(16f / 9f)

    /** The picture's width over its height. */
    val aspect: StateFlow<Float> = _aspect.asStateFlow()

    private val controllerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A new track starts as music: the floating window has nothing of it to show. The
            // same one again (repeat one) keeps it.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) VideoPictureInPicture.close()
            refresh()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playing.value = isPlaying
            sync()
        }
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = sync()
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // A seek on the audio: follow it at once rather than on the next tick.
            lastSeekAt = 0L
            sync()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            _frameOn.value = top()
            _shownVideoId.value = loadedId
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val width = videoSize.width * videoSize.pixelWidthHeightRatio
            if (width > 0f && videoSize.height > 0) {
                _aspect.value = width / videoSize.height
                _quality.value = _quality.value.copy(activeHeight = videoSize.height)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && seekStartedAt > 0L) {
                val took = System.currentTimeMillis() - seekStartedAt
                seekLeadMs = ((seekLeadMs + took) / 2).coerceIn(0L, 1_500L)
                seekStartedAt = 0L
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = loadedId ?: return
            val failedAt = _quality.value.activeHeight ?: loadedCap
            TrackLog.w(TAG, "video: playback failed at ${failedAt}p, ${loadedCap}p asked (${error.errorCodeName})", about = id)
            streams.remove(id)
            val failed = failedHeights.getOrPut(id) { mutableSetOf() }.apply { add(failedAt) }
            // Not every decoder plays every rendition: above 1080p YouTube serves VP9 and AV1, and
            // a TV's VP9 can top out narrower than an ultra-wide video. The next height down that
            // hasn't failed, until there is none.
            val lower = _quality.value.heights.firstOrNull { it < failedAt && it !in failed }
            if (lower != null) {
                stepDown[id] = lower
                load(id)
                return
            }
            noPicture += id
            unload()
        }
    }

    /** The app's MediaController, whose position the picture follows. Main thread. */
    fun attach(controller: Player?) {
        if (this.controller === controller) return
        this.controller?.removeListener(controllerListener)
        this.controller = controller
        controller?.addListener(controllerListener)
        _playing.value = controller?.isPlaying == true
        refresh()
    }

    /**
     * Puts the picture on [view] until the returned release is called, unless
     * a surface of higher [priority] holds it — the full player's outranks the
     * mini player's, whichever was mounted last. Main thread.
     */
    internal fun claim(view: TextureView, priority: Int): () -> Unit {
        releaseJob?.cancel()
        surfaces.removeAll { it.view === view }
        surfaces += Surface(view, priority)
        val app = view.context.applicationContext
        appContext = app
        if (prefs == null) {
            if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                autoHeight = decoderMaxHeight()
                TrackLog.d(TAG, "video: Auto on this TV opens at ${autoHeight}p")
            }
            prefs = app.getSharedPreferences("music_video", Context.MODE_PRIVATE).also { stored ->
                val held = stored.getInt(KEY_QUALITY, 0).takeIf { it > 0 }
                _quality.value = _quality.value.copy(held = held)
            }
        }
        val exo = ensurePlayer(app)
        if (top() === view) {
            _frameOn.value = null
            exo.setVideoTextureView(view)
            repaint()
        }
        refresh()
        return release@{
            val wasTop = top() === view
            surfaces.removeAll { it.view === view }
            if (!wasTop) return@release
            if (_frameOn.value === view) _frameOn.value = null
            // The player now, not the one this surface was claimed with: a quality change may
            // have swapped it since (see [promote]).
            val exo = player ?: return@release
            val next = top()
            if (next != null) {
                exo.setVideoTextureView(next)
                repaint()
                refresh()
            } else {
                exo.clearVideoTextureView(view)
                releaseJob = scope.launch {
                    delay(RELEASE_GRACE_MS)
                    if (surfaces.isEmpty()) unload()
                }
            }
        }
    }

    private fun ensurePlayer(context: Context): ExoPlayer =
        player ?: newPlayer(context).apply { addListener(playerListener) }.also { player = it }

    /** A silent, picture-only player: the audio is the main player's. */
    private fun newPlayer(context: Context): ExoPlayer = ExoPlayer.Builder(context)
        .build()
        .apply {
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }

    private fun mediaSource(stream: InnerTubeXResolver.VideoStream) =
        ProgressiveMediaSource.Factory(
            ChunkedDataSource.Factory(
                OkHttpDataSource.Factory(Http.client).setDefaultRequestProperties(stream.headers),
                CHUNK_BYTES,
            ),
        ).createMediaSource(MediaItem.fromUri(stream.url))

    /**
     * Changes the playing video's quality without stopping it, as YouTube does: the new
     * rendition opens on a second player, off screen, from the moment the audio is at, and the
     * old one plays on until the new one can draw — then the surface moves across in one step.
     *
     * A standby that fails isn't taken as the height failing: a TV often has a single hardware
     * decoder, which the playing picture is holding. It falls back to [load], which lets go of
     * the old picture first (keeping its last frame up) and does judge the height.
     */
    private fun switchQuality(videoId: String) {
        cancelStandby()
        val context = appContext ?: return load(videoId)
        val cap = capFor(videoId)
        standbyJob = scope.launch {
            val stream = try {
                cachedStream(videoId, cap) ?: withContext(Dispatchers.IO) {
                    InnerTubeXResolver.extractVideo(videoId, cap)
                }?.also { streams[videoId] = Triple(it, cap, System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackLog.w(TAG, "video: quality change couldn't resolve: ${e.message}", about = videoId)
                null
            }
            if (loadedId != videoId) return@launch
            // Nothing to change to, or the same rendition: the playing picture already is it.
            if (stream == null || stream.aspect < MIN_VIDEO_ASPECT || stream.height == _quality.value.activeHeight) {
                loadedCap = cap
                return@launch
            }
            val next = newPlayer(context)
            standby = next
            next.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && standby === next) promote(next, stream, cap)
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (standby !== next) return
                    TrackLog.w(TAG, "video: ${stream.height}p couldn't open beside the playing picture (${error.errorCodeName}); reloading", about = videoId)
                    cancelStandby()
                    if (loadedId == videoId) load(videoId)
                }
            })
            val start = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
            next.setMediaSource(mediaSource(stream), start + seekLeadMs)
            next.playWhenReady = controller?.isPlaying == true
            next.prepare()
        }
    }

    /** The standby can draw: it becomes the player, on the surface the old one was drawing to. */
    private fun promote(next: ExoPlayer, stream: InnerTubeXResolver.VideoStream, cap: Int) {
        standby = null
        standbyJob = null
        val old = player
        // A TextureView takes frames from one producer at a time: the old one lets go first, its
        // last frame staying up until the new one's lands on top of it.
        top()?.let { view ->
            old?.clearVideoTextureView(view)
            next.setVideoTextureView(view)
        }
        old?.removeListener(playerListener)
        old?.release()
        next.addListener(playerListener)
        player = next
        loadedCap = cap
        if (stream.aspect > 0f) _aspect.value = stream.aspect
        _quality.value = _quality.value.copy(heights = stream.heights, activeHeight = stream.height)
        TrackLog.d(TAG, "video: now ${stream.height}p, changed without a stop", about = loadedId)
        repaint()
        lastSeekAt = 0L
        sync()
    }

    private fun cancelStandby() {
        standbyJob?.cancel()
        standbyJob = null
        standby?.release()
        standby = null
    }

    /** A paused decoder handed a new surface draws nothing until asked to. */
    private fun repaint() {
        val exo = player ?: return
        if (!exo.playWhenReady && exo.playbackState != Player.STATE_IDLE) {
            exo.seekTo(exo.currentPosition)
        }
    }

    /** The track the picture should be of: the controller's, when it is a music video. */
    private fun wanted(): Song? {
        val song = controller?.currentMediaItem?.toSong() ?: return null
        return song.takeIf { it.isVideo && it.videoId !in noPicture }
    }

    private fun refresh() {
        val song = if (surfaces.isEmpty()) null else wanted()
        if (song == null) {
            // The picture is kept through the grace period after its last
            // surface goes; a track change to one with none drops it now.
            if (surfaces.isNotEmpty() || wanted()?.videoId != loadedId) unload()
            return
        }
        // Never reloaded mid-track for a surface change: the picture in hand moves to it.
        if (song.videoId == loadedId && capFor(song.videoId) <= loadedCap) {
            startSync()
            return
        }
        load(song.videoId)
    }

    /**
     * Holds the picture at [height] from now on, or lets it choose with null —
     * the web player's quality box. The playing video reloads at once, at the
     * moment the audio is at.
     */
    fun setQuality(height: Int?) {
        prefs?.edit()?.apply {
            if (height == null) remove(KEY_QUALITY) else putInt(KEY_QUALITY, height)
        }?.apply()
        _quality.value = _quality.value.copy(held = height)
        val id = loadedId ?: return
        streams.remove(id)
        // The step-down stays: Auto, or a pick above a height that failed here, would only fail
        // again. capFor takes the lower of the two.
        if (_shownVideoId.value == id && player != null) switchQuality(id) else load(id)
    }

    /**
     * Asks for [videoId]'s picture before any surface wants it — the track has a video the switch
     * may go to — so pressing it finds the stream already in hand. Any thread.
     */
    fun prefetch(videoId: String) {
        scope.launch {
            val cap = capFor(videoId)
            if (videoId in noPicture || videoId == loadedId || cachedStream(videoId, cap) != null) return@launch
            val stream = runCatching {
                withContext(Dispatchers.IO) { InnerTubeXResolver.extractVideo(videoId, cap) }
            }.getOrNull() ?: return@launch
            if (streams[videoId] == null) streams[videoId] = Triple(stream, cap, System.currentTimeMillis())
        }
    }

    /**
     * Whether [videoId] has a picture to show: its stream resolves and isn't a cover held still.
     * Asked before the switch to it is offered, so a track whose only "video" is nothing to watch
     * shows no switch rather than one that loads forever. The stream it finds is kept, as
     * [prefetch]'s is. Any thread.
     */
    suspend fun hasPicture(videoId: String): Boolean = withContext(Dispatchers.Main) {
        if (videoId in noPicture) return@withContext false
        val cap = capFor(videoId)
        val stream = cachedStream(videoId, cap) ?: runCatching {
            withContext(Dispatchers.IO) { InnerTubeXResolver.extractVideo(videoId, cap) }
        }.getOrNull()?.also { streams[videoId] = Triple(it, cap, System.currentTimeMillis()) }
        when {
            stream == null -> false
            stream.aspect < MIN_VIDEO_ASPECT -> {
                noPicture += videoId
                false
            }
            else -> true
        }
    }

    /** The height asked for: the held one, else what Auto opens at for this connection. */
    private fun capFor(videoId: String): Int {
        // The mini player asks for the same height as the full one: expanding it then hands the
        // playing picture to the bigger surface, rather than reloading it a size up.
        val metered = AppSettings.meteredConnection.value == true
        val auto = if (metered) METERED_HEIGHT else autoHeight
        val base = _quality.value.held ?: auto
        return stepDown[videoId]?.let { minOf(base, it) } ?: base
    }

    private fun load(videoId: String) {
        // The same video again — a quality change, or a step down after a failure: its last
        // frame stays up on the surface until the new rendition's first one replaces it, rather
        // than the player falling back to its artwork or lyrics for the reload.
        unload(keepPicture = videoId == loadedId && _shownVideoId.value == videoId)
        loadedId = videoId
        val cap = capFor(videoId)
        loadedCap = cap
        loadJob = scope.launch {
            val stream = try {
                cachedStream(videoId, cap) ?: withContext(Dispatchers.IO) {
                    InnerTubeXResolver.extractVideo(videoId, cap)
                }?.also { streams[videoId] = Triple(it, cap, System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not remembered as pictureless: the next surface or track
                // change asks again.
                TrackLog.w(TAG, "video: couldn't resolve the picture: ${e.message}", about = videoId)
                if (loadedId == videoId) {
                    loadedId = null
                    dropKeptPicture()
                }
                return@launch
            }
            if (loadedId != videoId) return@launch
            // A catalogue track's "video" is its cover held still; nothing to show.
            if (stream == null || stream.aspect < MIN_VIDEO_ASPECT) {
                noPicture += videoId
                loadedId = null
                dropKeptPicture()
                return@launch
            }
            val exo = player ?: return@launch
            val source = mediaSource(stream)
            if (stream.aspect > 0f) _aspect.value = stream.aspect
            _quality.value = _quality.value.copy(
                heights = stream.heights,
                activeHeight = stream.height,
                failed = failedHeights[videoId].orEmpty().toSet(),
            )
            val start = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
            exo.setMediaSource(source, start + seekLeadMs)
            exo.playWhenReady = controller?.isPlaying == true
            exo.prepare()
            lastSeekAt = System.currentTimeMillis()
            seekStartedAt = lastSeekAt
            startSync()
        }
    }

    /**
     * The largest height on the ladder a hardware VP9 or H.264 decoder here plays at 16:9 —
     * what a TV's Auto opens at. Software decoders don't count: they claim sizes they can't
     * keep up with.
     */
    private fun decoderMaxHeight(): Int {
        val ladder = intArrayOf(2160, 1440, 1080, 720)
        fun MediaCodecInfo.software() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isSoftwareOnly
            else name.startsWith("OMX.google.") || name.startsWith("c2.android.")
        val best = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && !it.software() }
                .flatMap { info ->
                    info.supportedTypes
                        .filter { it.equals("video/x-vnd.on2.vp9", true) || it.equals("video/avc", true) }
                        .mapNotNull { runCatching { info.getCapabilitiesForType(it).videoCapabilities }.getOrNull() }
                }
                .maxOfOrNull { caps -> ladder.firstOrNull { h -> caps.isSizeSupported(h * 16 / 9, h) } ?: 0 }
        }.getOrNull() ?: 0
        return if (best > 0) best else AUTO_HEIGHT
    }

    private fun cachedStream(videoId: String, cap: Int): InnerTubeXResolver.VideoStream? {
        val (stream, askedFor, at) = streams[videoId] ?: return null
        if (askedFor != cap || System.currentTimeMillis() - at > STREAM_TTL_MS) {
            streams.remove(videoId)
            return null
        }
        return stream
    }

    /** A frame kept up through a reload (see [load]) whose new rendition never came. */
    private fun dropKeptPicture() {
        _shownVideoId.value = null
        _frameOn.value = null
    }

    private fun unload(keepPicture: Boolean = false) {
        cancelStandby()
        loadJob?.cancel()
        loadJob = null
        syncJob?.cancel()
        syncJob = null
        loadedId = null
        if (!keepPicture) dropKeptPicture()
        _quality.value = _quality.value.copy(heights = emptyList(), activeHeight = null, failed = emptySet())
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        while (streams.size > 8) streams.remove(streams.keys.first())
    }

    private fun startSync() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (isActive) {
                sync()
                delay(SYNC_EVERY_MS)
            }
        }
    }

    /** Holds the picture to the audio: same play state, same rate, the same moment. */
    private fun sync() {
        val audio = controller ?: return
        val exo = player ?: return
        if (loadedId == null || exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_ENDED) return
        val speed = audio.playbackParameters.speed
        val audioPos = audio.currentPosition
        val now = System.currentTimeMillis()
        if (!audio.isPlaying) {
            exo.playWhenReady = false
            setSpeed(exo, speed)
            // Paused: the exact frame, so a scrub shows where it lands.
            if (abs(exo.currentPosition - audioPos) > 40L && now - lastSeekAt > 120L) seek(exo, audioPos)
            return
        }
        exo.playWhenReady = true
        if (exo.playbackState == Player.STATE_BUFFERING) return
        val drift = exo.currentPosition - audioPos
        if (abs(drift) > SEEK_DRIFT_MS) {
            if (now - lastSeekAt > SEEK_COOLDOWN_MS) seek(exo, audioPos + seekLeadMs)
            return
        }
        // Catch up (or wait) over about a second, rather than jumping.
        val nudge = if (abs(drift) > IGNORE_DRIFT_MS) (-drift / 1000f).coerceIn(-0.1f, 0.1f) else 0f
        setSpeed(exo, speed * (1f + nudge))
    }

    private fun seek(exo: ExoPlayer, positionMs: Long) {
        lastSeekAt = System.currentTimeMillis()
        seekStartedAt = lastSeekAt
        exo.seekTo(positionMs.coerceAtLeast(0L))
    }

    private fun setSpeed(exo: ExoPlayer, speed: Float) {
        if (abs(exo.playbackParameters.speed - speed) > 0.005f) {
            exo.playbackParameters = PlaybackParameters(speed)
        }
    }

    /** Narrower than this is a cover held still, not a picture worth showing. */
    private const val MIN_VIDEO_ASPECT = 1.2f

    /** Where Auto opens, as on the web player: the top of YouTube's H.264 ladder. */
    private const val AUTO_HEIGHT = 1080
    private const val METERED_HEIGHT = 480

    /** The mini player's surface, below every full-size one — see [claim]. */
    internal const val MINI_PLAYER_PRIORITY = 0
    private const val KEY_QUALITY = "quality"
}

/**
 * The phone's picture-in-picture for a playing music video, as YouTube's: the activity shrinks
 * into the system's floating window, showing only the picture ([PictureInPictureVideo]), while
 * the music goes on in the service. Tapping the window, or expanding it, brings the app back as
 * it was.
 */
object VideoPictureInPicture {
    private var activity: WeakReference<Activity>? = null
    private val scope = MainScope()
    private var watcher: Job? = null

    /** Whether the activity is in its picture-in-picture window now. */
    val active = MutableStateFlow(false)

    /**
     * Whether leaving the app floats the picture, as YouTube does: while a music video's frames
     * are on screen and it plays. A paused one, or music alone, leaves the app as any app leaves.
     */
    private var autoEnter = false

    fun attach(activity: Activity) {
        this.activity = WeakReference(activity)
        if (!supported(activity)) return
        watcher?.cancel()
        watcher = scope.launch {
            combine(MusicVideoEngine.frameOn, MusicVideoEngine.playing, MusicVideoEngine.aspect) { frameOn, playing, _ ->
                frameOn != null && playing
            }.collect { showing ->
                autoEnter = showing
                // From Android 12 the system floats the window itself as the app is left, with
                // the animation out of the picture; it only has to be told ahead of time.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { this@VideoPictureInPicture.activity?.get()?.setPictureInPictureParams(params()) }
                }
            }
        }
    }

    /** Before Android 12, from the activity leaving for the home screen or another app. */
    fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && autoEnter && !active.value) enter()
    }

    fun supported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /**
     * Puts the floating window away, the music going on in the service: the task goes behind
     * whatever is in front, as the window's own close button does. Nothing when not floating.
     */
    fun close() {
        if (!active.value) return
        activity?.get()?.moveTaskToBack(true)
    }

    fun enter() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val host = activity?.get() ?: return
        runCatching { host.enterPictureInPictureMode(params()) }
            .onFailure { TrackLog.w(TAG, "picture-in-picture refused: ${it.message}") }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun params(): PictureInPictureParams {
        // The window takes the picture's shape, within what the system allows (2.39:1 at most).
        val aspect = MusicVideoEngine.aspect.value.coerceIn(1f / 2.39f, 2.39f)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((aspect * 1000).toInt(), 1000))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(autoEnter)
        return builder.build()
    }

    private const val TAG = "Laya"
}

/** All the activity shows in its picture-in-picture window: the playing video, filling it. */
@Composable
fun PictureInPictureVideo() {
    val shown by MusicVideoEngine.shownVideoId.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        shown?.let { id ->
            AndroidMusicVideo(
                videoId = id,
                scale = VideoScale.BEST_FIT,
                onRenderedChanged = {},
                modifier = Modifier.fillMaxSize(),
                priority = PICTURE_IN_PICTURE_PRIORITY,
            )
        }
    }
}

/** Above the full player's surface: while in its window, the picture is that window's. */
private const val PICTURE_IN_PICTURE_PRIORITY = 3

/**
 * Where the playing music video shows: [videoId]'s picture, fitted into or
 * cropped to these bounds. Shows nothing until a frame of that video has
 * landed here, so a caller keeps its still artwork underneath and the picture
 * fades in over it. Holds the picture only while the app is on screen.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun AndroidMusicVideo(
    videoId: String,
    scale: VideoScale,
    onRenderedChanged: (Boolean) -> Unit,
    modifier: Modifier,
    /** Which surface wins when two are mounted: the full player's, over the mini player's. */
    priority: Int = 1,
) {
    // Paused but on screen in its picture-in-picture window, which is all that window shows.
    val inPictureInPicture by VideoPictureInPicture.active.collectAsStateWithLifecycle()
    val foreground = rememberIsForeground() || inPictureInPicture
    var view by remember { mutableStateOf<TextureView?>(null) }
    DisposableEffect(view, foreground, priority) {
        val target = view
        val release = if (target != null && foreground) MusicVideoEngine.claim(target, priority) else null
        onDispose { release?.invoke() }
    }

    val shown by MusicVideoEngine.shownVideoId.collectAsStateWithLifecycle()
    val frameOn by MusicVideoEngine.frameOn.collectAsStateWithLifecycle()
    val aspect by MusicVideoEngine.aspect.collectAsStateWithLifecycle()
    val rendered = view != null && frameOn === view && shown == videoId
    // Already playing on another surface (the mini player, before the full one took it): the
    // picture is in hand and lands here on the next frame, so no thumbnail and no fade-in for it.
    val handedOver = view != null && shown == videoId
    val playing by MusicVideoEngine.playing.collectAsStateWithLifecycle()

    // A video being watched keeps the screen awake, as any video player does —
    // only while it plays, and only on the surface its frames are reaching.
    val awake = rendered && playing
    LaunchedEffect(view, awake) { view?.keepScreenOn = awake }

    val report by rememberUpdatedState(onRenderedChanged)
    LaunchedEffect(handedOver) { report(handedOver) }
    DisposableEffect(Unit) { onDispose { report(false) } }

    val alpha by animateFloatAsState(
        targetValue = if (handedOver) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "musicVideoAlpha",
    )

    // The video's own black bars, read off what the view shows about once a second (a 160×90 copy
    // of it) and cut off as it is laid out — see [VideoCropSettler].
    var crop by remember(videoId) { mutableStateOf(VideoCrop.NONE) }
    val settler = remember(videoId) { VideoCropSettler() }
    LaunchedEffect(view, videoId, rendered) {
        val target = view ?: return@LaunchedEffect
        if (!rendered) return@LaunchedEffect
        val sample = Bitmap.createBitmap(BARS_GRID_W, BARS_GRID_H, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(BARS_GRID_W * BARS_GRID_H)
        try {
            while (true) {
                delay(BARS_EVERY_MS)
                if (!target.isAvailable) continue
                target.getBitmap(sample)
                sample.getPixels(pixels, 0, BARS_GRID_W, 0, 0, BARS_GRID_W, BARS_GRID_H)
                crop = settler.offer(
                    BlackBars.detect(BARS_GRID_W, BARS_GRID_H) { x, y ->
                        val c = pixels[y * BARS_GRID_W + x]
                        maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
                    },
                )
            }
        } finally {
            sample.recycle()
        }
    }

    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // Blend rather than punch a hole: the still art stays
                    // visible underneath for the length of the fade.
                    isOpaque = false
                    this.alpha = 0f
                    view = this
                }
            },
            update = { it.alpha = alpha },
            onRelease = { if (view === it) view = null },
            // The picture letterboxed into the bounds, overflowing them and cropped
            // by the clip above, or stretched to them — see [VideoScale].
            modifier = Modifier.layout { measurable, constraints ->
                val width = constraints.maxWidth
                val height = if (constraints.hasBoundedHeight) {
                    constraints.maxHeight
                } else {
                    (width / aspect).roundToInt()
                }
                // The whole frame, placed so the picture inside its own bars sits as [scale] says.
                val at = scale.place(aspect, crop, width.toFloat(), height.toFloat())
                val w = at.width.roundToInt()
                val h = at.height.roundToInt()
                val placeable = measurable.measure(Constraints.fixed(w.coerceAtLeast(1), h.coerceAtLeast(1)))
                layout(width, height) {
                    placeable.place(at.left.roundToInt(), at.top.roundToInt())
                }
            },
        )
    }
}

/** How often the picture is read for its black bars, and the size of the copy read. */
private const val BARS_EVERY_MS = 1_000L
private const val BARS_GRID_W = 160
private const val BARS_GRID_H = 90
