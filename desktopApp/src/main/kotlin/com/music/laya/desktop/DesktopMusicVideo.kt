package com.music.laya.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

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
        // A pick is the listener asking again: an earlier step down doesn't overrule it.
        ceiling = Int.MAX_VALUE
        qualityGeneration.value++
    }

    /**
     * The tallest picture this machine has shown it can play: lowered to [STEP_DOWN_HEIGHT] the
     * first time a held height above it wouldn't open or keep up. What decodes in time is the
     * machine's, not the video's, so it holds for every video until a height is picked again.
     */
    @Volatile
    private var ceiling = Int.MAX_VALUE

    @Suppress("UNUSED_PARAMETER")
    private fun capFor(videoId: String): Int = minOf(_quality.value.held ?: AUTO_HEIGHT, ceiling)

    /**
     * The held height couldn't keep up — a 4K VP9 or AV1 picture decoded in software. Played at
     * [STEP_DOWN_HEIGHT] from now on, as the phone steps down a height its decoder refuses.
     * Returns whether there was anything lower to go to.
     */
    internal fun stepDown(videoId: String): Boolean {
        if (capFor(videoId) <= STEP_DOWN_HEIGHT) return false
        ceiling = STEP_DOWN_HEIGHT
        DesktopTrackLog.log("music video: ${_quality.value.held}p is too heavy here ($videoId); playing up to ${STEP_DOWN_HEIGHT}p")
        return true
    }

    /** Starts asking for [videoId]'s stream, if nothing has yet; cheap to call again. */
    fun prefetch(videoId: String, cap: Int = capFor(videoId)): Deferred<InnerTubeXResolver.VideoStream?>? {
        if (videoId in noPicture) return null
        val key = videoId to cap
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
        // Already decoding — on screen, or kept a moment after: its surface takes that one.
        if (stale?.first == videoId || videoId in pictures) return
        prepared = videoId to scope.async { open(videoId) }
        stale?.second?.let { job -> scope.launch { runCatching { job.await()?.decoder?.close() } } }
    }

    /** The decoder [prepare] opened for [videoId], or a fresh one. */
    internal suspend fun take(videoId: String): Opened? {
        val ready = prepared?.takeIf { it.first == videoId }?.second
        if (ready != null) prepared = null
        return ready?.await() ?: open(videoId)
    }

    /**
     * Opens [videoId]'s picture at its held height, stepping down the ladder — 1080p, then 720p —
     * when that one won't open here (an AV1 stream with no decoder for it, say), so a high pick
     * plays lower rather than never.
     */
    private suspend fun open(videoId: String): Opened? {
        val first = capFor(videoId)
        for (cap in listOf(first, STEP_DOWN_HEIGHT, AUTO_HEIGHT).filter { it <= first }.distinct()) {
            openAt(videoId, cap)?.let { return it }
            if (videoId in noPicture) return null
            // Next time it starts lower, rather than failing at the top again.
            if (cap > STEP_DOWN_HEIGHT) ceiling = STEP_DOWN_HEIGHT
        }
        return null
    }

    private suspend fun openAt(videoId: String, cap: Int): Opened? {
        // Retried a couple of times: right after launch the session is often not ready yet, and
        // the first ask fails where the next one answers.
        var stream: InnerTubeXResolver.VideoStream? = null
        for (attempt in 1..3) {
            stream = try {
                prefetch(videoId, cap)?.await()
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
        // Full size on the GPU path; [MAX_FRAME_EDGE] only bounds the CPU-converted fallback.
        val decoder = DesktopCanvasDecoder(maxEdge = minOf(maxOf(stream.width, stream.height), MAX_FRAME_EDGE), loop = false, windowed = true, hardware = true)
        val opened = withContext(Dispatchers.IO) { decoder.open(stream.url, stream.headers) }
        if (opened.isFailure) {
            DesktopTrackLog.log("music video: ${stream.height}p ${stream.mimeType} won't open here: ${opened.exceptionOrNull()?.message}")
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

    /** Bumped by every deliberate jump of the audio's playhead. */
    internal fun seekCount(): Int = playback?.value?.seeks ?: 0

    /** Past this the decoder restarts at the audio rather than catching up frame by frame. */
    internal const val RESTART_BEHIND_MS = 1_200L
    internal const val RESTART_AHEAD_MS = 600L

    /** Narrower than this is a cover held still, not a picture worth showing. */
    private const val MIN_VIDEO_ASPECT = 1.2f

    /** Where Auto opens: 720p starts quickly and decodes easily; a held height goes higher. */
    private const val AUTO_HEIGHT = 720

    /** Where a held height too heavy to open or keep up with steps down to. */
    private const val STEP_DOWN_HEIGHT = 1080
    private const val STREAM_TTL_MS = 60 * 60 * 1000L
    private const val KEY_QUALITY = "music_video_quality"
}

/**
 * The longest edge frames are scaled to: a 1080p frame, enough for a full-screen picture; past it,
 * each frame is only more bytes to convert and copy for a screen that can't tell.
 */
private const val MAX_FRAME_EDGE = 1920

/** A restart with no frame after this has not landed, and starts over. */
private const val LANDING_TIMEOUT_MS = 6_000L

/** Two restarts behind the audio inside this, at a height past 1080p, and it steps down. */
private const val SLOW_WINDOW_MS = 20_000L

/** How many decoded frames run ahead of the one on screen: a few hundred milliseconds of video. */
private const val FRAMES_AHEAD = 8

/**
 * One frame as the GPU draws it: the decoder's own luma and chroma [planes], which [YuvShader]
 * turns into colour and scales as it draws — or, for a format it doesn't take, a finished [rgb]
 * bitmap. Native memory: [close] once it is off screen.
 */
private class VideoFrame(
    val ptsMs: Long,
    val width: Int,
    val height: Int,
    /** Y, U and V. Empty for [rgb]. */
    val planes: List<Image> = emptyList(),
    val bt709: Boolean = true,
    val fullRange: Boolean = false,
    val rgb: Image? = null,
) {
    /**
     * [planes] as [YuvShader] draws them, made at the frame's first draw and kept for every draw
     * after — however many surfaces show it, and however often they redraw before the next one.
     */
    private var _paint: Paint? = null
    val paint: Paint
        get() = _paint ?: Paint().also { paint ->
            // The paint keeps its own reference to the shader.
            yuvShader(this).let { paint.shader = it; it.close() }
            _paint = paint
        }

    fun close() {
        _paint?.close()
        planes.forEach(Image::close)
        rgb?.close()
    }
}

/**
 * YUV to RGB on the GPU, sampled straight off the decoder's planes — the conversion and scaling
 * that took most of a 4K frame's CPU, done as the picture is drawn. Coordinates are luma pixels;
 * chroma is half size in both directions (4:2:0).
 */
private val YuvShader: RuntimeEffect by lazy {
    RuntimeEffect.makeForShader(
        """
        uniform shader luma;
        uniform shader chromaU;
        uniform shader chromaV;
        uniform float yOffset;
        uniform float yScale;
        uniform float cScale;
        uniform float4 k; // R from V, G from U, G from V, B from U
        half4 main(float2 p) {
            float y = luma.eval(p).r;
            float2 c = p * 0.5;
            float u = chromaU.eval(c).r;
            float v = chromaV.eval(c).r;
            y = (y - yOffset) * yScale;
            u = (u - 0.5) * cScale;
            v = (v - 0.5) * cScale;
            return half4(half(y + k.x * v), half(y - k.y * u - k.z * v), half(y + k.w * u), 1.0);
        }
        """.trimIndent(),
    )
}

private val Bilinear = FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)

/** [frame]'s planes as a shader, in the frame's own pixels. */
private fun yuvShader(frame: VideoFrame): Shader {
    val builder = RuntimeShaderBuilder(YuvShader)
    val sampled = frame.planes.map { it.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, Bilinear) }
    builder.child("luma", sampled[0])
    builder.child("chromaU", sampled[1])
    builder.child("chromaV", sampled[2])
    if (frame.fullRange) {
        builder.uniform("yOffset", 0f)
        builder.uniform("yScale", 1f)
        builder.uniform("cScale", 1f)
    } else {
        builder.uniform("yOffset", 16f / 255f)
        builder.uniform("yScale", 255f / 219f)
        builder.uniform("cScale", 255f / 224f)
    }
    if (frame.bt709) builder.uniform("k", 1.5748f, 0.1873f, 0.4681f, 1.8556f)
    else builder.uniform("k", 1.402f, 0.344136f, 0.714136f, 1.772f)
    val shader = builder.makeShader()
    builder.close()
    sampled.forEach(Shader::close)
    return shader
}

/** One run of the decoder from a point: its buffered frames, and the job filling them. */
private class Decoding(val frames: Channel<VideoFrame>, val job: Job) {
    /** Stops the run and lets go of what it had buffered. */
    suspend fun stop() {
        job.cancelAndJoin()
        while (true) frames.tryReceive().getOrNull()?.close() ?: break
    }
}

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
            val from = startMs - decoder.frameIntervalMillis
            // Reused frame to frame: Skia copies a raster image's bytes as it makes one.
            var planes: VideoPlanes? = null
            var pixels: ByteArray? = null
            while (isActive) {
                val frame = if (decoder.planarFormat) {
                    val p = decoder.nextPlanes(planes, skipBeforeMs = from)
                    if (p == null) {
                        if (decoder.planarFormat) break else continue
                    }
                    planes = p
                    planesFrame(p)
                } else {
                    val buffer = pixels ?: ByteArray(decoder.width * decoder.height * 4).also { pixels = it }
                    if (!decoder.nextFrame(buffer, skipBeforeMs = from)) break
                    val info = ImageInfo.makeN32(decoder.width, decoder.height, ColorAlphaType.OPAQUE)
                    VideoFrame(decoder.framePtsMs, decoder.width, decoder.height, rgb = Image.makeRaster(info, buffer, decoder.width * 4))
                }
                try {
                    frames.send(frame)
                } catch (e: CancellationException) {
                    frame.close()
                    throw e
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A frame the decoder or Skia couldn't take ends this run, not the app: the drawing
            // side sees no more frames and restarts.
            DesktopTrackLog.log("music video: decoding stopped: ${e.message}")
        } finally {
            frames.close()
        }
    }
    return Decoding(frames, job)
}

private fun planesFrame(p: VideoPlanes): VideoFrame {
    val cw = (p.width + 1) / 2
    val ch = (p.height + 1) / 2
    val y = Image.makeRaster(ImageInfo(p.width, p.height, ColorType.GRAY_8, ColorAlphaType.OPAQUE), p.y, p.yStride)
    val chroma = ImageInfo(cw, ch, ColorType.GRAY_8, ColorAlphaType.OPAQUE)
    val planes = listOf(y, Image.makeRaster(chroma, p.u, p.cStride), Image.makeRaster(chroma, p.v, p.cStride))
    return VideoFrame(p.ptsMs, p.width, p.height, planes, p.bt709, p.fullRange)
}

/**
 * One run of [videoId]'s picture at the height asked for now: opens it (or takes the one
 * [DesktopMusicVideo.prepare] opened), then keeps the frame the audio is on coming through
 * [onFrame] until cancelled. [onStepDown] when the height proves too heavy here, having stepped
 * the quality down — the caller starts a run at the lower one.
 */
private suspend fun CoroutineScope.playPicture(
    videoId: String,
    onFrame: (VideoFrame) -> Unit,
    onStepDown: () -> Unit,
) {
    val mountedAt = System.currentTimeMillis()
    val opened = DesktopMusicVideo.take(videoId) ?: return
    val decoder = opened.decoder
    // What the last restart took to land, so the next is aimed where the audio will be.
    var leadMs = 400L
    var restartedAt = System.currentTimeMillis()
    var landed = false
    val startAt = DesktopMusicVideo.audioPositionMs() ?: 0L
    var decoding = decodeFrom(decoder, startAt + if (DesktopMusicVideo.audioPlaying()) leadMs else 0L)
    var next: VideoFrame? = null
    try {
        var shownPts = -1L
        // When the picture had to start over behind the audio, lately: twice in a short while
        // at a height above 1080p is a decoder that can't keep up, and it steps down. A seek's
        // restart is the listener's doing, not the decoder's, and isn't counted.
        val fellBehindAt = ArrayDeque<Long>()
        var seeks = DesktopMusicVideo.seekCount()
        fun tooSlow(): Boolean {
            val now0 = DesktopMusicVideo.seekCount()
            if (now0 != seeks) { seeks = now0; return false }
            val now = System.currentTimeMillis()
            fellBehindAt.addLast(now)
            while (fellBehindAt.first() < now - SLOW_WINDOW_MS) fellBehindAt.removeFirst()
            return fellBehindAt.size >= 2 && opened.stream.height > 1080 && DesktopMusicVideo.stepDown(videoId)
        }
        suspend fun restart(at: Long) {
            decoding.stop()
            next?.close()
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
                candidate != null && target - candidate.ptsMs > DesktopMusicVideo.RESTART_BEHIND_MS -> {
                    if (landed && playing && tooSlow()) { onStepDown(); return }
                    // A restart that came up behind took longer than it was aimed ahead for (a 4K
                    // keyframe can be seconds away): aim the next one as far ahead as that took.
                    if (!landed && playing) leadMs = ((System.currentTimeMillis() - restartedAt) * 5 / 4).coerceIn(150L, 5_000L)
                    restart(target + if (playing) leadMs else 0L)
                }
                // A restart that never landed: the decoder couldn't get from the keyframe to the
                // audio before the audio moved on (4K decoded in software), or the read stalled.
                // Above 1080p that is a picture too heavy for here, and it steps down at once.
                !landed && System.currentTimeMillis() - restartedAt > LANDING_TIMEOUT_MS -> {
                    DesktopTrackLog.log("music video: no frame ${System.currentTimeMillis() - restartedAt}ms after restarting; ${opened.stream.height}p")
                    if (playing && opened.stream.height > 1080 && DesktopMusicVideo.stepDown(videoId)) {
                        onStepDown()
                        return
                    }
                    restart(target + leadMs)
                }
                // Starved after it had caught up — a stall on the network: start again ahead.
                // Not before a restart has landed, which would only start it over again.
                landed && candidate == null && target - shownPts > DesktopMusicVideo.RESTART_BEHIND_MS * 2 -> {
                    DesktopTrackLog.log("music video: fell ${target - shownPts}ms behind; restarting")
                    if (tooSlow()) { onStepDown(); return }
                    restart(target + leadMs)
                }
                // Paused, the first frame after a restart is the one to show even a frame past the
                // audio: the audio isn't coming to it.
                candidate != null && (candidate.ptsMs <= target + 8 || (!playing && !landed)) -> {
                    // Due: show the latest frame the audio has reached, skipping any it passed.
                    var due: VideoFrame = candidate
                    while (true) {
                        val after = decoding.frames.tryReceive().getOrNull() ?: break
                        if (after.ptsMs <= target + 8) {
                            due.close()
                            due = after
                        } else {
                            next = after
                            break
                        }
                    }
                    if (next === candidate) next = null
                    if (!landed) {
                        if (shownPts < 0) DesktopTrackLog.log("music video: first frame ${System.currentTimeMillis() - mountedAt}ms after the player asked, ${due.width}x${due.height} in ${if (decoder.decodingInHardware) "hardware" else "software"}")
                        landed = true
                        if (playing) leadMs = ((leadMs + System.currentTimeMillis() - restartedAt) / 2).coerceIn(150L, 5_000L)
                    }
                    onFrame(due)
                    shownPts = due.ptsMs
                }
            }
            val wait = next?.let { (it.ptsMs - target).coerceIn(2L, 16L) } ?: 8L
            delay(wait)
        }
    } finally {
        withContext(NonCancellable) {
            decoding.stop()
            next?.close()
        }
        withContext(NonCancellable + Dispatchers.IO) { decoder.close() }
    }
}

/** Where a [PictureSession] runs: the UI thread, so a frame is never closed in the middle of a draw. */
private val PictureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** Every video being decoded, by its id. Touched only on the UI thread. */
private val pictures = HashMap<String, PictureSession>()

/**
 * After the last surface leaves a video, how long its decoding is kept: long enough for a surface
 * taking over (the player opening from the mini player, the theater giving way to the sleeve,
 * picture-in-picture) to find it still playing — as the phone's MusicVideoEngine keeps its player.
 */
private const val RELEASE_GRACE_MS = 4_000L

/**
 * One video's picture, decoded once for every surface showing it — the full player, the mini
 * player, picture-in-picture — so moving between them only moves where it is drawn: no decoder
 * opened again, no first frame waited for, the thumbnail never back in between.
 */
private class PictureSession(val videoId: String) {
    /** The frame the audio is on, drawn by every surface. Closed only here. */
    var frame by mutableStateOf<VideoFrame?>(null)
    var surfaces = 0
    var release: Job? = null

    // Each quality is its own run of the picture. A new one — a pick in the quality drawer, or a
    // step down — starts beside the one on screen, which keeps playing until the new one's first
    // frame is ready, as YouTube changes quality: no blank, no freeze, and a height that never
    // comes up simply leaves the old one playing.
    val job = PictureScope.launch {
        // Bumped when the picture steps down a height, to reopen at the lower one.
        val reopen = MutableStateFlow(0)
        var showing: Job? = null
        combine(DesktopMusicVideo.qualityGeneration, reopen) { generation, step -> generation to step }.collect {
            val previous = showing
            showing = launch {
                playPicture(
                    videoId = videoId,
                    onFrame = { shown ->
                        if (previous?.isActive == true) previous.cancel()
                        // Drawn on this same thread, so the old one is never in a draw when closed.
                        val old = frame
                        frame = shown
                        if (old !== shown) old?.close()
                    },
                    onStepDown = { reopen.value++ },
                )
            }
        }
    }
}

/** [videoId]'s picture for one more surface: the one already decoding, or a new one. */
private fun attachPicture(videoId: String): PictureSession {
    val session = pictures.getOrPut(videoId) { PictureSession(videoId) }
    session.release?.cancel()
    session.release = null
    session.surfaces++
    return session
}

/**
 * One surface fewer on [session]; the last one out lets it go after [RELEASE_GRACE_MS] — at once
 * when the audio has moved off the video, so it isn't left chasing another track's playhead.
 */
private fun detachPicture(session: PictureSession) {
    if (--session.surfaces > 0) return
    val playing = DesktopMusicVideo.playback?.value?.song?.videoId == session.videoId
    session.release = PictureScope.launch {
        if (playing) delay(RELEASE_GRACE_MS)
        pictures.remove(session.videoId)
        session.job.cancelAndJoin()
        session.frame?.close()
        session.frame = null
    }
}

/** Holds a surface's place on its [PictureSession] for as long as the surface is composed. */
private class PictureUse(videoId: String) : RememberObserver {
    val session = attachPicture(videoId)
    override fun onRemembered() = Unit
    override fun onForgotten() = detachPicture(session)
    override fun onAbandoned() = detachPicture(session)
}

/** Whether [videoId]'s picture has a frame up now — a surface opening on it shows it at once. */
internal fun musicVideoRendered(videoId: String): Boolean = pictures[videoId]?.frame != null

/**
 * Where the playing music video shows on the desktop: [videoId]'s picture fitted into ([fit]) or
 * cropped to these bounds. Nothing until a frame has landed, reported through [onRenderedChanged],
 * so the caller's backdrop shows until then and the picture fades in over it. Every surface on the
 * same video draws the one [PictureSession], so one opening where another was picks it up as is.
 */
@Composable
internal fun DesktopMusicVideoSurface(
    videoId: String,
    fit: Boolean,
    onRenderedChanged: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val session = remember(videoId) { PictureUse(videoId) }.session
    val report by rememberUpdatedState(onRenderedChanged)

    // Derived, so a new frame redraws the canvas without recomposing the surface.
    val rendered by remember(session) { derivedStateOf { session.frame != null } }
    LaunchedEffect(rendered) { report(rendered) }

    // From 1 at once when the video already has a frame up: nothing to fade in.
    val alpha by animateFloatAsState(
        targetValue = if (rendered) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "musicVideoAlpha",
    )
    Canvas(modifier.graphicsLayer { this.alpha = alpha; clip = true }) {
        val shown = session.frame ?: return@Canvas
        val clipAspect = shown.width.toFloat() / shown.height
        val viewAspect = size.width / size.height
        // Letterboxed into the bounds, or overflowing them and cropped by the clip.
        val (w, h) = if ((clipAspect > viewAspect) == fit) {
            size.width to size.width / clipAspect
        } else {
            size.height * clipAspect to size.height
        }
        val left = (size.width - w) / 2f
        val top = (size.height - h) / 2f
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val rgb = shown.rgb
            if (rgb != null) {
                native.drawImageRect(
                    rgb,
                    org.jetbrains.skia.Rect.makeWH(rgb.width.toFloat(), rgb.height.toFloat()),
                    org.jetbrains.skia.Rect.makeXYWH(left, top, w, h),
                    Bilinear,
                    null,
                    true,
                )
            } else {
                // The frame's own shader, in its own pixels, scaled here to the box.
                native.save()
                native.translate(left, top)
                native.scale(w / shown.width, h / shown.height)
                native.drawRect(org.jetbrains.skia.Rect.makeWH(shown.width.toFloat(), shown.height.toFloat()), shown.paint)
                native.restore()
            }
        }
    }
}

/**
 * The playing music video in a mini player's cover slot, cropped to it, when asked for in
 * Settings; nothing for a track with no picture ([videoId] null), so the cover under it stays.
 * The same [PictureSession] as the full player's, so opening the player carries the picture over.
 */
@Composable
internal fun DesktopMiniPlayerVideo(videoId: String?, modifier: Modifier) {
    val wanted by DesktopAppearanceSettings.miniPlayerVideo.collectAsState()
    if (wanted && videoId != null) DesktopMusicVideoSurface(videoId, fit = false, onRenderedChanged = {}, modifier = modifier)
}
