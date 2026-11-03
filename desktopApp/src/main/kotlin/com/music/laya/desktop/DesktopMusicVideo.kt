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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
 * The picture follows it: frames are drawn when the playhead reaches them, dropped when it has
 * passed them, and a jump (a seek, a version swap, a stall) seeks the decoder after it. It is only
 * fetched while a surface is on screen, so a video with the player shut costs no more than the song.
 */
internal object DesktopMusicVideo {

    /** The audio the picture follows — set by the window once its engine exists. */
    @Volatile
    var playback: StateFlow<DesktopPlaybackState>? = null

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

    fun setQuality(height: Int?) {
        persistence.saveString(KEY_QUALITY, height?.toString().orEmpty())
        _quality.value = _quality.value.copy(held = height)
        qualityGeneration.value++
    }

    internal fun capFor(): Int = _quality.value.held ?: AUTO_HEIGHT

    internal suspend fun resolve(videoId: String): InnerTubeXResolver.VideoStream? {
        if (videoId in noPicture) return null
        val stream = withContext(Dispatchers.IO) { InnerTubeXResolver.extractVideo(videoId, capFor()) }
        if (stream == null || stream.aspect < MIN_VIDEO_ASPECT) {
            noPicture += videoId
            return null
        }
        _aspect.value = stream.aspect
        _quality.value = _quality.value.copy(heights = stream.heights, activeHeight = stream.height)
        return stream
    }

    /** Where the audio is now: the last reading, run on by the time since while it plays. */
    internal fun audioPositionMs(): Long? {
        val state = playback?.value ?: return null
        if (!state.isPlaying || state.awaitingAudio || state.positionSampledAtNanos == 0L) return state.positionMs
        return state.positionMs + (System.nanoTime() - state.positionSampledAtNanos) / 1_000_000
    }

    /** Past this the decoder seeks to the audio rather than decoding its way there. */
    internal const val SEEK_BEHIND_MS = 1_500L
    internal const val SEEK_AHEAD_MS = 400L

    /** Narrower than this is a cover held still, not a picture worth showing. */
    private const val MIN_VIDEO_ASPECT = 1.2f

    /** Where Auto opens, as on the phone: the top of YouTube's H.264 ladder. */
    private const val AUTO_HEIGHT = 1080
    private const val KEY_QUALITY = "music_video_quality"
}

/** The longest edge frames are scaled to. */
private const val MAX_FRAME_EDGE = 1280

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
        val stream = try {
            DesktopMusicVideo.resolve(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DesktopTrackLog.log("music video: couldn't resolve the picture for $videoId: ${e.message}")
            null
        } ?: return@LaunchedEffect
        // Scaled to at most a 720p-class frame: the player shows it no bigger than the window, and
        // every pixel past that is one more to convert on each frame.
        val decoder = DesktopCanvasDecoder(maxEdge = minOf(maxOf(stream.width, stream.height), MAX_FRAME_EDGE), loop = false)
        val opened = withContext(Dispatchers.IO) { decoder.open(stream.url, stream.headers) }
        if (opened.isFailure) {
            DesktopTrackLog.log("music video: ${opened.exceptionOrNull()?.message}")
            withContext(Dispatchers.IO) { decoder.close() }
            return@LaunchedEffect
        }
        DesktopTrackLog.log("music video: ${decoder.width}x${decoder.height} for $videoId")
        try {
            val pixels = ByteArray(decoder.width * decoder.height * 4)
            val info = ImageInfo.makeN32(decoder.width, decoder.height, ColorAlphaType.OPAQUE)
            var seekLeadMs = 800L
            while (isActive) {
                val target = DesktopMusicVideo.audioPositionMs() ?: run { delay(100); continue }
                val pts = decoder.framePtsMs
                val drawn = withContext(Dispatchers.IO) {
                    // A jump on the audio, or a picture too far adrift: start again from the
                    // keyframe before the playhead and decode up to it.
                    if (pts < 0 || target - pts > DesktopMusicVideo.SEEK_BEHIND_MS ||
                        // Ahead by up to the lead is a seek still landing: waited out, not redone.
                        pts - target > DesktopMusicVideo.SEEK_AHEAD_MS + seekLeadMs
                    ) {
                        // Aimed ahead by what the last one took to land: the audio moves on
                        // while the decoder works its way up from the keyframe. Paused, it doesn't.
                        val playing = DesktopMusicVideo.playback?.value?.isPlaying == true
                        val aim = target + if (playing) seekLeadMs else 0L
                        val started = System.currentTimeMillis()
                        if (pts >= 0 || aim > 0) decoder.seek(aim)
                        if (pts >= 0) DesktopTrackLog.log("music video: resync, ${pts - target}ms off the audio")
                        var any = false
                        while (decoder.nextFrame(pixels)) {
                            any = true
                            if (decoder.framePtsMs >= aim - decoder.frameIntervalMillis) break
                        }
                        if (playing) {
                            seekLeadMs = ((seekLeadMs + System.currentTimeMillis() - started) / 2).coerceIn(0L, 3_000L)
                        }
                        any
                    } else if (pts + decoder.frameIntervalMillis / 2 < target) {
                        // Behind by a frame or more: drop to the one the audio is on.
                        var any = false
                        while (decoder.nextFrame(pixels)) {
                            any = true
                            if (decoder.framePtsMs + decoder.frameIntervalMillis / 2 >= target) break
                        }
                        any
                    } else {
                        false
                    }
                }
                if (drawn) {
                    // Handed to Skia rather than copied, so the next frame cannot be decoded into it.
                    frame = Image.makeRaster(info, pixels.copyOf(), decoder.width * 4).toComposeImageBitmap()
                }
                // Past the video's end the decoder has nothing more and this idles at frame pace.
                delay((decoder.framePtsMs - target).coerceIn(4L, decoder.frameIntervalMillis))
            }
        } finally {
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
