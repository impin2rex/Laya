package com.music.laya.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.music.laya.data.innertube.InnerTubeXResolver
import com.music.laya.ui.player.MusicVideoQualityUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.roundToInt

/**
 * A music video's picture on the desktop, the way the phone's MusicVideoEngine plays one: the
 * video-only stream, decoded silently by FFmpeg and held to the audio engine's playhead. The audio
 * stays where it is — the engine, the EQ, Automix, Listen Together — and stays the clock.
 *
 * Decoding runs on its own a few frames ahead into a small buffer, so a slow read or a heavy frame
 * never holds up the one on screen; the drawing side only picks, from what is buffered, the frame
 * the audio has reached. A jump (a seek, a version swap, a stall) restarts the decoding at the
 * playhead, aimed ahead by what the last restart took to land.
 *
 * The slow parts — asking YouTube for the stream and opening it — are started early: the stream as
 * soon as the player knows the track has a video, the decoder the moment the switch is pressed, so
 * both are under way while the audio is still changing over.
 */
internal object DesktopMusicVideo {

    /** The audio the picture follows — set by the window once its engine exists. */
    @Volatile
    var playback: StateFlow<DesktopPlaybackState>? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _aspect = MutableStateFlow(16f / 9f)
    val aspect: StateFlow<Float> = _aspect.asStateFlow()

    private val persistence = DesktopPersistence()

    private val _quality = MutableStateFlow(
        MusicVideoQualityUi(held = persistence.string(KEY_QUALITY).toIntOrNull()),
    )
    val quality: StateFlow<MusicVideoQualityUi> = _quality.asStateFlow()

    /** Bumped by a new held height, so a surface reopens at it. */
    internal val qualityGeneration = MutableStateFlow(0)

    /** Tracks with no picture to show — a catalogue track's "video" is a still. */
    private val noPicture = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Streams asked for, by video and height, with when — a googlevideo URL lives about six hours. */
    private val streams = java.util.concurrent.ConcurrentHashMap<Pair<String, Int>, Pair<Long, Deferred<InnerTubeXResolver.VideoStream?>>>()

    /** A decoder opened ahead of its surface, waiting to be taken. */
    private var prepared: Pair<String, Deferred<Opened?>>? = null

    internal class Opened(val stream: InnerTubeXResolver.VideoStream, val decoder: DesktopCanvasDecoder)

    fun setQuality(height: Int?) {
        persistence.saveString(KEY_QUALITY, height?.toString().orEmpty())
        _quality.value = _quality.value.copy(held = height)
        qualityGeneration.value++
    }

    private fun capFor(): Int = _quality.value.held ?: AUTO_HEIGHT

    /** Starts asking for [videoId]'s stream, if nothing has yet; cheap to call again. */
    fun prefetch(videoId: String): Deferred<InnerTubeXResolver.VideoStream?>? {
        if (videoId in noPicture) return null
        val key = videoId to capFor()
        val now = System.currentTimeMillis()
        streams[key]?.let { (at, job) ->
            val failed = job.isCompleted && job.getCompletionExceptionOrNull() != null
            if (!failed && now - at < STREAM_TTL_MS) return job
        }
        while (streams.size > 8) streams.keys.firstOrNull()?.let(streams::remove)
        val job = scope.async { InnerTubeXResolver.extractVideo(videoId, key.second) }
        streams[key] = now to job
        return job
    }

    /** Asks for [videoId]'s stream and opens it now, for the surface about to show it. */
    fun prepare(videoId: String) {
        val stale = prepared
        if (stale?.first == videoId) return
        prepared = videoId to scope.async { open(videoId) }
        stale?.second?.let { job -> scope.launch { runCatching { job.await()?.decoder?.close() } } }
    }

    /** The decoder [prepare] opened for [videoId], or a fresh one. */
    internal suspend fun take(videoId: String): Opened? {
        val ready = prepared?.takeIf { it.first == videoId }?.second
        if (ready != null) prepared = null
        return ready?.await() ?: open(videoId)
    }

    private suspend fun open(videoId: String): Opened? {
        // Retried a couple of times: right after launch the session is often not ready yet, and
        // the first ask fails where the next one answers.
        var stream: InnerTubeXResolver.VideoStream? = null
        for (attempt in 1..3) {
            stream = try {
                prefetch(videoId)?.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DesktopTrackLog.log("music video: couldn't resolve the picture for $videoId (try $attempt): ${e.message}")
                delay(1_500L * attempt)
                continue
            }
            break
        }
        if (stream == null || stream.aspect < MIN_VIDEO_ASPECT) {
            if (stream != null) noPicture += videoId
            return null
        }
        _aspect.value = stream.aspect
        _quality.value = _quality.value.copy(heights = stream.heights, activeHeight = stream.height)
        // Scaled to at most a 720p-class frame: the player shows it no bigger than the window, and
        // every pixel past that is one more to convert on each frame.
        val decoder = DesktopCanvasDecoder(maxEdge = minOf(maxOf(stream.width, stream.height), MAX_FRAME_EDGE), loop = false, windowed = true)
        val opened = withContext(Dispatchers.IO) { decoder.open(stream.url, stream.headers) }
        if (opened.isFailure) {
            DesktopTrackLog.log("music video: ${opened.exceptionOrNull()?.message}")
            withContext(NonCancellable + Dispatchers.IO) { decoder.close() }
            return null
        }
        return Opened(stream, decoder)
    }

    /** Where the audio is now: the last reading, run on by the time since while it plays. */
    internal fun audioPositionMs(): Long? {
        val state = playback?.value ?: return null
        if (!state.isPlaying || state.awaitingAudio || state.positionSampledAtNanos == 0L) return state.positionMs
        return state.positionMs + (System.nanoTime() - state.positionSampledAtNanos) / 1_000_000
    }

    internal fun audioPlaying(): Boolean = playback?.value?.isPlaying == true

    /** Past this the decoder restarts at the audio rather than catching up frame by frame. */
    internal const val RESTART_BEHIND_MS = 1_200L
    internal const val RESTART_AHEAD_MS = 600L

    /** Narrower than this is a cover held still, not a picture worth showing. */
    private const val MIN_VIDEO_ASPECT = 1.2f

    /** Where Auto opens: 720p starts quickly and decodes easily; a held height goes higher. */
    private const val AUTO_HEIGHT = 720
    private const val STREAM_TTL_MS = 60 * 60 * 1000L
    private const val KEY_QUALITY = "music_video_quality"
}

/** The longest edge frames are scaled to. */
private const val MAX_FRAME_EDGE = 1280

/** How many decoded frames run ahead of the one on screen: a few hundred milliseconds of video. */
private const val FRAMES_AHEAD = 8

private class VideoFrame(val ptsMs: Long, val image: ImageBitmap)

/** One run of the decoder from a point: its buffered frames, and the job filling them. */
private class Decoding(val frames: Channel<VideoFrame>, val job: Job)

/**
 * Decodes from [startMs] into a small buffer, dropping what comes before it so the first frame out
 * is the one asked for. The decoder is native and single-threaded: a run must be over (joined)
 * before the next one touches it.
 */
private fun CoroutineScope.decodeFrom(decoder: DesktopCanvasDecoder, startMs: Long): Decoding {
    val frames = Channel<VideoFrame>(FRAMES_AHEAD)
    val job = launch(Dispatchers.IO) {
        try {
            decoder.seek(startMs.coerceAtLeast(0L))
            val pixels = ByteArray(decoder.width * decoder.height * 4)
            val info = ImageInfo.makeN32(decoder.width, decoder.height, ColorAlphaType.OPAQUE)
            val from = startMs - decoder.frameIntervalMillis
            while (isActive && decoder.nextFrame(pixels)) {
                if (decoder.framePtsMs < from) continue
                // Handed to Skia rather than copied, so the next frame cannot be decoded into it.
                frames.send(VideoFrame(decoder.framePtsMs, Image.makeRaster(info, pixels.copyOf(), decoder.width * 4).toComposeImageBitmap()))
            }
        } finally {
            frames.close()
        }
    }
    return Decoding(frames, job)
}

/**
 * Where the playing music video shows on the desktop: [videoId]'s picture fitted into ([fit]) or
 * cropped to these bounds. Nothing until a frame has landed, reported through [onRenderedChanged],
 * so the caller's backdrop shows until then and the picture fades in over it.
 */
@Composable
internal fun DesktopMusicVideoSurface(
    videoId: String,
    fit: Boolean,
    onRenderedChanged: (Boolean) -> Unit,
    modifier: Modifier,
) {
    var frame by remember(videoId) { mutableStateOf<ImageBitmap?>(null) }
    val generation by DesktopMusicVideo.qualityGeneration.collectAsState()
    val report by rememberUpdatedState(onRenderedChanged)

    LaunchedEffect(videoId, generation) {
        val mountedAt = System.currentTimeMillis()
        val opened = DesktopMusicVideo.take(videoId) ?: return@LaunchedEffect
        val decoder = opened.decoder
        // What the last restart took to land, so the next is aimed where the audio will be.
        var leadMs = 400L
        var restartedAt = System.currentTimeMillis()
        var landed = false
        val startAt = DesktopMusicVideo.audioPositionMs() ?: 0L
        var decoding = decodeFrom(decoder, startAt + if (DesktopMusicVideo.audioPlaying()) leadMs else 0L)
        try {
            var next: VideoFrame? = null
            var shownPts = -1L
            suspend fun restart(at: Long) {
                decoding.job.cancelAndJoin()
                next = null
                landed = false
                restartedAt = System.currentTimeMillis()
                decoding = decodeFrom(decoder, at)
            }
            while (isActive) {
                val target = DesktopMusicVideo.audioPositionMs() ?: run { delay(50); continue }
                val playing = DesktopMusicVideo.audioPlaying()
                if (next == null) next = decoding.frames.tryReceive().getOrNull()
                val candidate = next
                when {
                    // The audio jumped back, or far ahead of anything buffered: start over there.
                    candidate != null && candidate.ptsMs - target > DesktopMusicVideo.RESTART_AHEAD_MS + leadMs ->
                        restart(target + if (playing) leadMs else 0L)
                    candidate != null && target - candidate.ptsMs > DesktopMusicVideo.RESTART_BEHIND_MS ->
                        restart(target + if (playing) leadMs else 0L)
                    // Starved after it had caught up — a stall on the network: start again ahead.
                    // Not before a restart has landed, which would only start it over again.
                    landed && candidate == null && target - shownPts > DesktopMusicVideo.RESTART_BEHIND_MS * 2 -> {
                        DesktopTrackLog.log("music video: fell ${target - shownPts}ms behind; restarting")
                        restart(target + leadMs)
                    }
                    candidate != null && candidate.ptsMs <= target + 8 -> {
                        // Due: show the latest frame the audio has reached, skipping any it passed.
                        var due: VideoFrame = candidate
                        while (true) {
                            val after = decoding.frames.tryReceive().getOrNull() ?: break
                            if (after.ptsMs <= target + 8) due = after else { next = after; break }
                        }
                        if (next === candidate) next = null
                        if (!landed) {
                            if (shownPts < 0) DesktopTrackLog.log("music video: first frame ${System.currentTimeMillis() - mountedAt}ms after the player asked")
                            landed = true
                            if (playing) leadMs = ((leadMs + System.currentTimeMillis() - restartedAt) / 2).coerceIn(150L, 2_500L)
                        }
                        frame = due.image
                        shownPts = due.ptsMs
                    }
                }
                val wait = next?.let { (it.ptsMs - target).coerceIn(2L, 16L) } ?: 8L
                delay(wait)
            }
        } finally {
            withContext(NonCancellable) { decoding.job.cancelAndJoin() }
            withContext(NonCancellable + Dispatchers.IO) { decoder.close() }
        }
    }

    val rendered = frame != null
    LaunchedEffect(rendered) { report(rendered) }
    DisposableEffect(Unit) { onDispose { report(false) } }

    val alpha by animateFloatAsState(
        targetValue = if (rendered) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "musicVideoAlpha",
    )
    Canvas(modifier.graphicsLayer { this.alpha = alpha; clip = true }) {
        val image = frame ?: return@Canvas
        val clipAspect = image.width.toFloat() / image.height
        val viewAspect = size.width / size.height
        // Letterboxed into the bounds, or overflowing them and cropped by the clip.
        val (w, h) = if ((clipAspect > viewAspect) == fit) {
            size.width to size.width / clipAspect
        } else {
            size.height * clipAspect to size.height
        }
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(((size.width - w) / 2f).roundToInt(), ((size.height - h) / 2f).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
        )
    }
}
