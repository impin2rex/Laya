package com.music.laya.ios

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.music.laya.data.TrackLog
import com.music.laya.data.innertube.InnerTubeXResolver
import com.music.laya.platform.currentTimeMillis
import com.music.laya.platform.ktorClient
import com.music.laya.ui.player.MusicVideoQualityUi
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerAudiovisualBackgroundPlaybackPolicyPauses
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.addOutput
import platform.AVFoundation.audiovisualBackgroundPlaybackPolicy
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.preferredMaximumResolution
import platform.AVFoundation.presentationSize
import platform.AVFoundation.preventsDisplaySleepDuringVideoPlayback
import platform.AVFoundation.rate
import platform.AVFoundation.removeOutput
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setRate
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreVideo.CVBufferRelease
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.QuartzCore.CACurrentMediaTime
import platform.posix.RTLD_DEFAULT
import platform.posix.dlsym
import platform.posix.memcpy
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The music video on iOS: the picture played silently over the main player's own audio and held
 * to its clock, as on the phone and the desktop.
 *
 * Played from YouTube's HLS playlist through Apple's own player, which is what it is made for: it
 * starts at once, seeks without reading the file through, and picks among the variants the ones
 * this iPhone decodes. Those are H.264 up to 1080p everywhere, and VP9 up to 4K from iOS 26.2 —
 * the first to let an app use the VP9 decoder, which [vp9] registers for it.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosMusicVideo {
    /** The audio the picture follows: set by the player as it starts. */
    var player: IosPlayer? = null

    val aspect = MutableStateFlow(16f / 9f)

    val quality = MutableStateFlow(
        MusicVideoQualityUi(held = NSUserDefaults.standardUserDefaults.integerForKey(KEY_QUALITY).toInt().takeIf { it > 0 }),
    )

    /** Bumped by a new held height, so a surface reopens at it. */
    val generation = MutableStateFlow(0)

    fun setQuality(height: Int?) {
        NSUserDefaults.standardUserDefaults.setInteger((height ?: 0).convert(), KEY_QUALITY)
        quality.value = quality.value.copy(held = height)
        generation.value++
    }

    /**
     * Whether this iOS decodes VP9 for the app: 26.2 or later, with the decoder registered. Asked
     * before the first picture, so Apple's player can choose the VP9 variants.
     */
    val vp9: Boolean by lazy {
        val version = NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion * 100 + minorVersion }
        if (version < 2602) return@lazy false
        // Looked up by name: the SDK this is built against may not declare it.
        val register = dlsym(RTLD_DEFAULT, "VTRegisterSupplementalVideoDecoderIfAvailable")
            ?.reinterpret<CFunction<(UInt) -> Unit>>() ?: return@lazy false
        register(VP9_CODEC)
        true
    }

    /** Playlists asked for, by video, with when: a googlevideo manifest lives about six hours. */
    private val manifests = mutableMapOf<String, Pair<Long, String>>()

    suspend fun manifest(videoId: String): String? {
        manifests[videoId]?.takeIf { currentTimeMillis() - it.first < MANIFEST_TTL_MS }?.let { return it.second }
        val url = try {
            InnerTubeXResolver.videoHlsManifest(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "video: no HLS playlist: ${e.message}", about = videoId)
            null
        } ?: return null
        if (manifests.size > 8) manifests.clear()
        manifests[videoId] = currentTimeMillis() to url
        return url
    }

    /** The heights [url]'s variants offer that this iPhone decodes, tallest first — the quality drawer's list. */
    suspend fun heights(url: String): List<Int> = runCatching {
        http.get(url).bodyAsText().lineSequence()
            .filter { it.startsWith("#EXT-X-STREAM-INF") }
            .filter { vp9 || "vp09" !in it }
            .mapNotNull { RESOLUTION.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .sortedDescending()
            .toList()
    }.getOrDefault(emptyList())

    /** Past this the picture jumps to the audio rather than catching up by rate. */
    const val SEEK_DRIFT_MS = 400L

    /** 'vp09' */
    private const val VP9_CODEC: UInt = 0x76703039u
    private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
    private val http by lazy { ktorClient { } }
    private const val MANIFEST_TTL_MS = 60 * 60 * 1000L
    private const val KEY_QUALITY = "music_video_quality"
    private const val TAG = "Laya"
}

/**
 * One picture: a muted AVPlayer on the video's HLS playlist whose frames are pulled through an
 * AVPlayerItemVideoOutput scaled to the view, as [IosCanvasVideo]'s clip, and drawn by Compose.
 */
@OptIn(ExperimentalForeignApi::class)
private class HlsPicture(url: String, maxHeight: Int?) {
    private var player: AVPlayer? = AVPlayer().apply {
        muted = true
        preventsDisplaySleepDuringVideoPlayback = true
        audiovisualBackgroundPlaybackPolicy = AVPlayerAudiovisualBackgroundPlaybackPolicyPauses
        val item = AVPlayerItem.playerItemWithURL(NSURL.URLWithString(url)!!)
        // A held height caps the variants; Auto leaves the choice to Apple's player.
        if (maxHeight != null) item.preferredMaximumResolution = CGSizeMake(8192.0, maxHeight.toDouble())
        replaceCurrentItemWithPlayerItem(item)
    }
    private var output: AVPlayerItemVideoOutput? = null
    private var outputSize = IntSize.Zero
    private var image: Image? = null
    private var bytes = ByteArray(0)

    @Volatile private var seeking = false

    /** How far ahead a jump is aimed: what the last one took to land. */
    private var leadMs = 1_500L

    var viewSize = IntSize.Zero

    /** The size on screen now, once the player has chosen a variant; zero until then. */
    val shownSize: IntSize
        get() = player?.currentItem?.presentationSize?.useContents { IntSize(width.toInt(), height.toInt()) } ?: IntSize.Zero

    /** Held to the audio at [audioMs]: paused with it, and caught up by rate or a jump when it drifts. */
    fun follow(audioMs: Long, playing: Boolean) {
        val p = player ?: return
        if (seeking) return
        val videoMs = (CMTimeGetSeconds(p.currentTime()) * 1000).toLong()
        val drift = videoMs - audioMs
        if (abs(drift) > IosMusicVideo.SEEK_DRIFT_MS) {
            val target = audioMs + if (playing) leadMs else 0
            val tolerance = CMTimeMakeWithSeconds(0.05, 600)
            val startedAt = currentTimeMillis()
            seeking = true
            p.seekToTime(CMTimeMakeWithSeconds(target / 1000.0, 600), tolerance, tolerance) { _ ->
                if (playing) leadMs = ((currentTimeMillis() - startedAt) * 6 / 5).coerceIn(300L, 5_000L)
                seeking = false
            }
            return
        }
        val rate = if (!playing) 0f else (1f - drift / 2000f).coerceIn(0.95f, 1.05f)
        if (abs(p.rate - rate) > 0.004f) p.setRate(rate)
    }

    private fun outputFor(item: AVPlayerItem): AVPlayerItemVideoOutput? {
        val (nw, nh) = item.presentationSize.useContents { width.toInt() to height.toInt() }
        if (nw <= 0 || nh <= 0 || viewSize.width <= 0 || viewSize.height <= 0) return null
        // No more pixels than the view shows: the copy below is per frame.
        val k = min(1f, max(viewSize.width.toFloat() / nw, viewSize.height.toFloat() / nh))
        val want = IntSize((nw * k).roundToInt().coerceAtLeast(2), (nh * k).roundToInt().coerceAtLeast(2))
        if (output != null && want == outputSize) return output
        output?.let { item.removeOutput(it) }
        val o = AVPlayerItemVideoOutput(pixelBufferAttributes = mapOf<Any?, Any?>(
            "PixelFormatType" to NSNumber(unsignedInt = kCVPixelFormatType_32BGRA),
            "Width" to NSNumber(int = want.width),
            "Height" to NSNumber(int = want.height),
        ))
        item.addOutput(o)
        output = o
        outputSize = want
        return o
    }

    /** The newest decoded frame, or null when nothing new arrived since the last call. */
    fun nextFrame(): ImageBitmap? {
        val item = player?.currentItem ?: return null
        val o = outputFor(item) ?: return null
        val t = o.itemTimeForHostTime(CACurrentMediaTime())
        if (!o.hasNewPixelBufferForItemTime(t)) return null
        val buf = o.copyPixelBufferForItemTime(t, null) ?: return null
        try {
            CVPixelBufferLockBaseAddress(buf, 1uL)
            val w = CVPixelBufferGetWidth(buf).toInt()
            val h = CVPixelBufferGetHeight(buf).toInt()
            val rb = CVPixelBufferGetBytesPerRow(buf).toInt()
            if (bytes.size != rb * h) bytes = ByteArray(rb * h)
            bytes.usePinned { memcpy(it.addressOf(0), CVPixelBufferGetBaseAddress(buf), (rb * h).convert()) }
            CVPixelBufferUnlockBaseAddress(buf, 1uL)
            image?.close()
            val img = Image.makeRaster(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL), bytes, rb)
            image = img
            return img.toComposeImageBitmap()
        } finally {
            CVBufferRelease(buf)
        }
    }

    fun release() {
        player?.pause()
        player?.currentItem?.let { item -> output?.let { item.removeOutput(it) } }
        player?.replaceCurrentItemWithPlayerItem(null)
        image?.close()
        image = null
        output = null
        player = null
    }
}

/** PlayerHost.MusicVideo on iOS: [videoId]'s picture, fitted into or cropped to these bounds. */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun IosMusicVideoSurface(videoId: String, fit: Boolean, onRenderedChanged: (Boolean) -> Unit, modifier: Modifier) {
    val generation by IosMusicVideo.generation.collectAsState()
    val report by rememberUpdatedState(onRenderedChanged)
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // Built off the main thread, as the canvas clip is: AVPlayer and its item wait on the media server.
    val picture by produceState<HlsPicture?>(null, videoId, generation) {
        IosMusicVideo.vp9 // registered before the player chooses its variants
        val url = IosMusicVideo.manifest(videoId) ?: return@produceState
        val held = IosMusicVideo.quality.value.held
        val made = withContext(NonCancellable + Dispatchers.Default) { HlsPicture(url, held) }
        value = made
        IosMusicVideo.quality.value = IosMusicVideo.quality.value.copy(heights = IosMusicVideo.heights(url))
        awaitDispose { made.release() }
    }
    var frame by remember(picture) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(picture) {
        val p = picture ?: return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val audio = IosMusicVideo.player ?: continue
            p.viewSize = viewSize
            p.follow(audio.audioClockMs(), audio.isPlaying && !audio.isLoading)
            p.nextFrame()?.let { f ->
                frame = f
                val shown = p.shownSize
                if (shown.height > 0) {
                    val q = IosMusicVideo.quality.value
                    if (q.activeHeight != shown.height) IosMusicVideo.quality.value = q.copy(activeHeight = shown.height)
                    IosMusicVideo.aspect.value = shown.width.toFloat() / shown.height
                }
            }
        }
    }
    val rendered = frame != null
    LaunchedEffect(rendered) { report(rendered) }
    DisposableEffect(Unit) { onDispose { report(false) } }
    val alpha by animateFloatAsState(if (rendered) 1f else 0f, tween(320), label = "musicVideoAlpha")

    Canvas(modifier.onSizeChanged { viewSize = it }.graphicsLayer { this.alpha = alpha }) {
        val f = frame ?: return@Canvas
        drawPicture(f, fit)
    }
}

/** [f] fitted into ([fit]) or cropped to this canvas, centred. */
private fun DrawScope.drawPicture(f: ImageBitmap, fit: Boolean) {
    val aspect = f.width.toFloat() / f.height
    val viewAspect = size.width / size.height
    val widthLimited = if (fit) aspect > viewAspect else aspect <= viewAspect
    val w = if (widthLimited) size.width else size.height * aspect
    val h = if (widthLimited) size.width / aspect else size.height
    clipRect {
        drawImage(
            f,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(f.width, f.height),
            dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
            dstSize = IntSize(w.toInt(), h.toInt()),
        )
    }
}
