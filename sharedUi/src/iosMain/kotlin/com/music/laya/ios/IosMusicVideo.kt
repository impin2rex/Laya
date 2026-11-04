package com.music.laya.ios

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.addOutput
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
import platform.AVKit.AVPictureInPictureController
import platform.AVKit.AVPictureInPictureControllerDelegateProtocol
import platform.CoreGraphics.CGRectMake
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
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.QuartzCore.CACurrentMediaTime
import platform.QuartzCore.CATransaction
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
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
 *
 * One picture for every surface, as Android's engine: the mini player, the full player and full
 * screen all draw the same [frame], so expanding or shrinking the player hands over the picture
 * already playing — no reload, no thumbnail, the same moment. The mini player alone holds the
 * playlist to its smallest variants; a bigger surface lifts the cap and Apple's player climbs to
 * a sharper variant without a gap.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosMusicVideo {
    /** The audio the picture follows: set by the player as it starts. */
    var player: IosPlayer? = null

    val aspect = MutableStateFlow(16f / 9f)

    val quality = MutableStateFlow(
        MusicVideoQualityUi(held = NSUserDefaults.standardUserDefaults.integerForKey(KEY_QUALITY).toInt().takeIf { it > 0 }),
    )

    /** The newest frame of the playing picture, whichever surface shows it. */
    val frame = MutableStateFlow<VideoFrame?>(null)

    class VideoFrame(val videoId: String, val image: ImageBitmap)

    fun setQuality(height: Int?) {
        NSUserDefaults.standardUserDefaults.setInteger((height ?: 0).convert(), KEY_QUALITY)
        quality.value = quality.value.copy(held = height)
        applyCap()
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
    private suspend fun heights(url: String): List<Int> = runCatching {
        http.get(url).bodyAsText().lineSequence()
            .filter { it.startsWith("#EXT-X-STREAM-INF") }
            .filter { vp9 || "vp09" !in it }
            .mapNotNull { RESOLUTION.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .sortedDescending()
            .toList()
    }.getOrDefault(emptyList())

    // --- The one picture, and the surfaces showing it. Main thread. ---

    private val scope = MainScope()

    internal class Surface(val videoId: String, val mini: Boolean, var size: IntSize = IntSize.Zero) {
        /** Where it sits in the window, in pixels: where picture in picture lifts the picture from. */
        var bounds: androidx.compose.ui.geometry.Rect? = null
    }

    private val surfaces = ArrayList<Surface>()
    private var picture: HlsPicture? = null
    private var pictureId: String? = null
    private var loadJob: Job? = null
    private var releaseJob: Job? = null

    /** Shows [videoId] on a surface until the returned release is called. */
    internal fun claim(videoId: String, mini: Boolean): Surface {
        releaseJob?.cancel()
        val s = Surface(videoId, mini)
        surfaces += s
        refresh()
        return s
    }

    internal fun resize(surface: Surface, size: IntSize) {
        surface.size = size
        picture?.viewSize = viewSize()
    }

    internal fun release(surface: Surface) {
        surfaces.remove(surface)
        preparePictureInPicture()
        if (surfaces.isNotEmpty()) return refresh()
        // Kept a moment past the last surface: a sheet reopened, a rotation, the player expanded.
        releaseJob = scope.launch {
            delay(RELEASE_GRACE_MS)
            if (surfaces.isEmpty() && !pipActive) unload()
        }
    }

    /**
     * The box each frame is copied out at: the screen, whichever way it is held — never the
     * surface's own size. A new size is a new video output, which waits for the decoder's next
     * picture, so one sized to the surface froze the picture through every change of it: the
     * player opening from the mini player, the sleeve flying back, a rotation.
     */
    private fun viewSize() = if (surfaces.isEmpty() && !pipActive) IntSize.Zero else screenPx

    private val screenPx by lazy {
        platform.UIKit.UIScreen.mainScreen.nativeBounds.useContents { IntSize(size.width.toInt(), size.height.toInt()) }
    }

    private fun refresh() {
        // The latest surface's video: the next track's surface mounts before the last one's goes.
        load(surfaces.lastOrNull()?.videoId ?: return)
    }

    /** [want]'s picture, loaded unless it is the one in hand — and the one the floating window plays. */
    private fun load(want: String) {
        if (want == pictureId) return applyCap()
        unload()
        pictureId = want
        loadJob = scope.launch {
            vp9 // registered before the player chooses its variants
            val url = manifest(want) ?: return@launch run { pip?.stopPictureInPicture() }
            // Built off the main thread, as the canvas clip is: AVPlayer and its item wait on the media server.
            val made = withContext(NonCancellable + Dispatchers.Default) { HlsPicture(url) }
            if (pictureId != want) return@launch made.release()
            picture = made
            // The floating window goes on with the next video rather than the last one's frozen frame.
            pipLayer?.player = made.player
            made.viewSize = viewSize()
            applyCap()
            launch(Dispatchers.Default) { pullFrames(made, want) }
            launch { quality.value = quality.value.copy(heights = heights(url)) }
            preparePictureInPicture()
            follow(made)
        }
    }

    /** Only the mini player showing: its slot is all the picture needs. Otherwise the held height, or Auto. */
    private fun applyCap() {
        val p = picture ?: return
        val miniOnly = surfaces.isNotEmpty() && surfaces.all { it.mini } && !pipActive
        p.cap = if (miniOnly) MINI_HEIGHT else quality.value.held
        p.viewSize = viewSize()
    }

    private fun unload() {
        loadJob?.cancel()
        loadJob = null
        picture?.release()
        picture = null
        pictureId = null
        dropPictureInPicture()
        frame.value = null
        quality.value = quality.value.copy(heights = emptyList(), activeHeight = null)
    }

    /** Off the main thread: a new frame is copied out as soon as the decoder has one. */
    private suspend fun pullFrames(p: HlsPicture, videoId: String) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            if (foreground || pipActive) p.nextFrame()?.let { frame.value = VideoFrame(videoId, it) }
            delay(FRAME_POLL_MS)
        }
    }

    @Volatile private var foreground = true

    /** Holds the picture to the audio, on a timer rather than the frame clock, so it keeps time in picture in picture. */
    private suspend fun follow(p: HlsPicture) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            foreground = UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground
            val audio = player
            // Floating, and the track changed — the next one, the last one, or the switch to audio:
            // a new track starts as music, so the window has nothing of it to show and goes.
            val track = audio?.song
            if (pipActive && track != null && track.videoId != pictureId) pip?.stopPictureInPicture()
            if (audio != null) {
                val playing = audio.isPlaying && !audio.isLoading
                // Off screen and not floating: the picture rests.
                if (!pipActive || !pipControls(p, audio, playing)) p.follow(audio.audioClockMs(), playing && (foreground || pipActive))
                val shown = p.shownSize
                if (shown.height > 0) {
                    val q = quality.value
                    if (q.activeHeight != shown.height) quality.value = q.copy(activeHeight = shown.height)
                    aspect.value = shown.width.toFloat() / shown.height
                }
            }
            delay(SYNC_EVERY_MS)
        }
    }

    // --- Picture in picture. ---

    val pictureInPictureSupported: Boolean get() = AVPictureInPictureController.isPictureInPictureSupported()

    @Volatile private var pipActive = false
    private var pip: AVPictureInPictureController? = null
    private var pipLayer: AVPlayerLayer? = null

    /**
     * The view holding [pipLayer]. A player layer laid straight on the window's layer has no view
     * to tie it to a scene, and iOS refused it: "The UIScene for the content source has an
     * activation state other than UISceneActivationStateForegroundActive".
     */
    private var pipView: UIView? = null
    private val pipDelegate = PipDelegate()

    /** When the floating window's play button last disagreed with the audio, for [pipControls]. */
    private var pipDisagreeSince = 0L

    /**
     * Keeps the window ready while a music video plays in the player, as YouTube does: leaving the
     * app for the home screen or another app floats the picture on its own, and the button only
     * has to ask. The window plays from a player layer of its own, laid behind the app's content
     * over the picture on screen, so the picture stays Compose's and the window outlives the
     * player sheet closing. Let go of with the last full-size surface, unless it is floating.
     */
    private fun preparePictureInPicture() {
        if (!pictureInPictureSupported || pipActive) return
        val player = picture?.player
        val from = pipSource()
        if (player == null || from == null) return dropPictureInPicture()
        val window = keyWindow() ?: return
        val layer = pipLayer ?: AVPlayerLayer().apply {
            videoGravity = AVLayerVideoGravityResizeAspect
            val view = UIView().apply { userInteractionEnabled = false }
            view.layer.addSublayer(this)
            window.addSubview(view)
            window.sendSubviewToBack(view)
            pipView = view
            pipLayer = this
        }
        layer.player = player
        placePipLayer(window, from)
        if (pip == null) {
            TrackLog.d(TAG, "picture-in-picture ready", about = pictureId)
            pip = AVPictureInPictureController(playerLayer = layer).apply {
                delegate = pipDelegate
                // Skipping would only be undone by the picture following the audio.
                requiresLinearPlayback = true
                canStartPictureInPictureAutomaticallyFromInline = true
            }
        }
    }

    private fun dropPictureInPicture() {
        if (pipActive) return
        pip?.delegate = null
        pip = null
        pipView?.removeFromSuperview()
        pipView = null
        pipLayer = null
    }

    /** Where the window flies out of: the biggest picture on screen, the mini player's when it is the only one. */
    private fun pipSource() = surfaces.mapNotNull { it.bounds }.maxByOrNull { it.width * it.height }

    private fun placePipLayer(window: UIWindow, from: androidx.compose.ui.geometry.Rect) {
        val layer = pipLayer ?: return
        val view = pipView ?: return
        val scale = window.screen.scale
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        view.setFrame(CGRectMake(from.left / scale, from.top / scale, from.width / scale, from.height / scale))
        layer.frame = view.bounds
        CATransaction.commit()
    }

    /** The window's layer over the app ([front]) while it starts, else behind it, where the app draws the picture. */
    private fun pipFront(front: Boolean) {
        val view = pipView ?: return
        val window = view.window ?: return
        if (front) window.bringSubviewToFront(view) else window.sendSubviewToBack(view)
    }

    /** A surface moved: the window's layer follows it, to fly out of the picture where it is. */
    internal fun place(surface: Surface, bounds: androidx.compose.ui.geometry.Rect) {
        if (surface.bounds == bounds) return
        surface.bounds = bounds
        if (pipActive) return
        if (pipLayer == null) return preparePictureInPicture()
        val window = keyWindow() ?: return
        pipSource()?.let { placePipLayer(window, it) }
    }

    /** What picture in picture had to start from, for the log when it won't. */
    private fun pipState(): String {
        val layer = pipLayer ?: return "no layer"
        val frame = layer.frame.useContents { "${size.width.toInt()}x${size.height.toInt()}@${origin.x.toInt()},${origin.y.toInt()}" }
        return "layer=$frame ready=${layer.readyForDisplay} inWindow=${pipView?.window != null} " +
            "scene=${pipView?.window?.windowScene?.activationState} " +
            "possible=${pip?.pictureInPicturePossible} rate=${layer.player?.rate} item=${layer.player?.currentItem?.status} " +
            "app=${UIApplication.sharedApplication.applicationState}"
    }

    /** The button: floats the picture now. */
    fun enterPictureInPicture() {
        preparePictureInPicture()
        val controller = pip ?: return
        // Brought over the app while it starts — iOS starts the button's window only from a layer
        // it can see — and sent back behind once the window is up (see PipDelegate).
        pipFront(true)
        TrackLog.d(TAG, "picture-in-picture asked: ${pipState()}", about = pictureId)
        // Started in the tap itself where it can be: Apple's rule is picture in picture only in
        // answer to the user. Waited for only on the first tap, while the layer gets its picture.
        if (controller.pictureInPicturePossible) return controller.startPictureInPicture()
        scope.launch {
            // Possible once the layer has the player's picture: a moment after it is laid.
            repeat(40) {
                if (pip !== controller) return@launch
                if (controller.pictureInPicturePossible) return@launch controller.startPictureInPicture()
                delay(50)
            }
            TrackLog.w(TAG, "picture-in-picture never became possible")
            pipFront(false)
        }
    }

    /** The window is up, by the button or by leaving the app. */
    private fun pictureInPictureStarted() {
        TrackLog.d(TAG, "picture-in-picture starting", about = pictureId)
        pipActive = true // held from now, so a release in the meantime doesn't take the picture
        pipFront(false)
        applyCap()
    }

    private fun endPictureInPicture() {
        TrackLog.d(TAG, "picture-in-picture ended", about = pictureId)
        pipActive = false
        pipFront(false)
        if (surfaces.isEmpty()) unload() else applyCap()
        preparePictureInPicture()
    }

    /**
     * The floating window's play/pause acts on the silent picture; the audio is what it means. A
     * difference that lasts is the listener's, and goes to the audio — a passing one is the window
     * closing, which pauses the picture on its way out. True while the picture is left as the
     * window set it.
     */
    private fun pipControls(p: HlsPicture, audio: IosPlayer, playing: Boolean): Boolean {
        val pictureRuns = (p.player?.rate ?: 0f) > 0f
        if (pictureRuns == (p.lastRateSet > 0f) || pictureRuns == playing) {
            pipDisagreeSince = 0L
            return false
        }
        val now = currentTimeMillis()
        if (pipDisagreeSince == 0L) pipDisagreeSince = now
        if (now - pipDisagreeSince < PIP_INTENT_MS) return true
        pipDisagreeSince = 0L
        TrackLog.d(TAG, "picture-in-picture window ${if (pictureRuns) "played" else "paused"} the music", about = pictureId)
        audio.setPlaying(pictureRuns)
        p.lastRateSet = if (pictureRuns) 1f else 0f
        return true
    }

    private class PipDelegate : NSObject(), AVPictureInPictureControllerDelegateProtocol {
        override fun pictureInPictureControllerWillStartPictureInPicture(pictureInPictureController: AVPictureInPictureController) =
            pictureInPictureStarted()

        // The layer it flew from goes behind the app, which keeps drawing the picture.
        override fun pictureInPictureControllerDidStartPictureInPicture(pictureInPictureController: AVPictureInPictureController) {
            pipFront(false)
        }

        override fun pictureInPictureControllerDidStopPictureInPicture(pictureInPictureController: AVPictureInPictureController) =
            endPictureInPicture()

        override fun pictureInPictureController(
            pictureInPictureController: AVPictureInPictureController,
            failedToStartPictureInPictureWithError: NSError,
        ) {
            val e = failedToStartPictureInPictureWithError
            TrackLog.w(TAG, "picture-in-picture failed: ${e.domain} ${e.code} ${e.localizedDescription} " +
                "${e.userInfo} | ${pipState()}")
            endPictureInPicture()
        }

        override fun pictureInPictureController(
            pictureInPictureController: AVPictureInPictureController,
            restoreUserInterfaceForPictureInPictureStopWithCompletionHandler: (Boolean) -> Unit,
        ) = restoreUserInterfaceForPictureInPictureStopWithCompletionHandler(true)
    }

    private fun keyWindow(): UIWindow? = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
        .let { windows -> windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull() }

    /** Past this the picture jumps to the audio rather than catching up by rate. */
    const val SEEK_DRIFT_MS = 400L

    /** Under this the picture is left alone: a rate change per small wobble is a stutter of its own. */
    const val IGNORE_DRIFT_MS = 40L

    private const val SYNC_EVERY_MS = 100L
    private const val FRAME_POLL_MS = 8L
    private const val RELEASE_GRACE_MS = 4_000L
    private const val PIP_INTENT_MS = 400L

    /** The tallest variant the mini player's 40 pt slot asks for. */
    private const val MINI_HEIGHT = 240

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
private class HlsPicture(url: String) {
    var player: AVPlayer? = AVPlayer().apply {
        muted = true
        preventsDisplaySleepDuringVideoPlayback = true
        // Background playback left at Automatic: paused behind the app, except in picture in picture.
        replaceCurrentItemWithPlayerItem(AVPlayerItem.playerItemWithURL(NSURL.URLWithString(url)!!))
    }
        private set
    private var output: AVPlayerItemVideoOutput? = null
    private var outputSize = IntSize.Zero

    /** Images handed out, oldest first: one is closed only once two newer ones have taken its place on screen. */
    private val images = ArrayDeque<Image>()
    private var bytes = ByteArray(0)

    @Volatile private var seeking = false

    /** How far ahead a jump is aimed: what the last one took to land. */
    private var leadMs = 1_000L

    /** The rate last set here; anything else on the player was set by the picture-in-picture window. */
    var lastRateSet = 0f

    @Volatile var viewSize = IntSize.Zero

    /**
     * The tallest variant wanted, or null for any. Apple's player moves between variants without
     * a gap, so lifting it (the mini player expanded) sharpens the picture already playing.
     */
    var cap: Int? = -1
        set(value) {
            if (field == value) return
            field = value
            player?.currentItem?.preferredMaximumResolution =
                CGSizeMake(if (value == null) 0.0 else 8192.0, value?.toDouble() ?: 0.0)
        }

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
            // A loose landing is caught up by rate; an exact one makes the decoder read on from the keyframe.
            val tolerance = CMTimeMakeWithSeconds(0.2, 600)
            val startedAt = currentTimeMillis()
            seeking = true
            p.seekToTime(CMTimeMakeWithSeconds(target / 1000.0, 600), tolerance, tolerance) { _ ->
                if (playing) leadMs = ((currentTimeMillis() - startedAt) * 6 / 5).coerceIn(200L, 4_000L)
                seeking = false
            }
            setRate(p, if (playing) 1f else 0f)
            return
        }
        // A few steps rather than a rate per reading: each change re-times the decoder.
        val rate = when {
            !playing -> 0f
            drift > IosMusicVideo.IGNORE_DRIFT_MS -> 0.97f
            drift < -IosMusicVideo.IGNORE_DRIFT_MS -> 1.03f
            else -> 1f
        }
        setRate(p, rate)
    }

    private fun setRate(p: AVPlayer, rate: Float) {
        if (abs(p.rate - rate) > 0.004f) p.setRate(rate)
        lastRateSet = rate
    }

    private fun outputFor(item: AVPlayerItem): AVPlayerItemVideoOutput? {
        val (nw, nh) = item.presentationSize.useContents { width.toInt() to height.toInt() }
        val view = viewSize
        if (nw <= 0 || nh <= 0 || view.width <= 0 || view.height <= 0) return null
        // No more pixels than the screen shows, either way up: the copy below is per frame.
        val long = max(view.width, view.height).toFloat()
        val short = min(view.width, view.height).toFloat()
        val k = min(1f, min(long / max(nw, nh), short / min(nw, nh)))
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

    /** The newest decoded frame, or null when nothing new arrived since the last call. Off the main thread. */
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
            val img = Image.makeRaster(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL), bytes, rb)
            images.addLast(img)
            while (images.size > 3) images.removeFirst().close()
            return img.toComposeImageBitmap()
        } finally {
            CVBufferRelease(buf)
        }
    }

    fun release() {
        val p = player ?: return
        player = null
        p.pause()
        p.currentItem?.let { item -> output?.let { item.removeOutput(it) } }
        p.replaceCurrentItemWithPlayerItem(null)
        output = null
        // Left to the collector: the frame on screen may still be one of them.
        images.clear()
    }
}

/** PlayerHost.MusicVideo on iOS: the playing picture, fitted into or cropped to these bounds. */
@Composable
internal fun IosMusicVideoSurface(
    videoId: String,
    fit: Boolean,
    onRenderedChanged: (Boolean) -> Unit,
    modifier: Modifier,
    /** The mini player's cover slot, which alone holds the picture to its smallest variants. */
    mini: Boolean = false,
) {
    val report by rememberUpdatedState(onRenderedChanged)
    val surface = remember(videoId, mini) { IosMusicVideo.claim(videoId, mini) }
    DisposableEffect(surface) { onDispose { IosMusicVideo.release(surface) } }

    // Read in the draw phase only: a new frame redraws the canvas, it doesn't recompose.
    val frame = IosMusicVideo.frame.collectAsState()
    val rendered by remember(videoId) { derivedStateOf { frame.value?.videoId == videoId } }
    LaunchedEffect(rendered) { report(rendered) }
    DisposableEffect(Unit) { onDispose { report(false) } }

    // Already playing on another surface: shown at once, with no fade-in over the thumbnail.
    val alpha = remember(videoId) { Animatable(if (rendered) 1f else 0f) }
    LaunchedEffect(rendered) { alpha.animateTo(if (rendered) 1f else 0f, tween(320)) }

    Canvas(
        modifier
            .onSizeChanged { IosMusicVideo.resize(surface, it) }
            .onGloballyPositioned { IosMusicVideo.place(surface, it.boundsInWindow()) }
            .graphicsLayer { this.alpha = alpha.value }) {
        val f = frame.value?.takeIf { it.videoId == videoId } ?: return@Canvas
        drawPicture(f.image, fit)
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
