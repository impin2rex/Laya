package com.music.laya.ios

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlinx.cinterop.useContents
import platform.AVFoundation.presentationSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.music.laya.ui.player.CanvasContentMode
import com.music.laya.ui.player.CanvasVideoSpec
import com.music.laya.ui.player.canvasOrganicReveal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerAudiovisualBackgroundPlaybackPolicyPauses
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.AVFoundation.duration
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.tracksWithMediaType
import platform.AVFoundation.AVPlayerLooper
import platform.AVFoundation.audiovisualBackgroundPlaybackPolicy
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.currentItem
import platform.AVFoundation.muted
import platform.AVFoundation.preventsDisplaySleepDuringVideoPlayback
import platform.AVFoundation.pause
import platform.AVFoundation.play
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.addOutput
import platform.AVFoundation.removeOutput
import platform.CoreVideo.CVBufferRelease
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.kCMPersistentTrackID_Invalid
import platform.AVFoundation.preferredMaximumResolution
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.QuartzCore.CACurrentMediaTime
import platform.posix.memcpy

/**
 * One clip: a muted, gapless-looping AVQueuePlayer whose frames are pulled through an AVPlayerItemVideoOutput.
 * Drawn by Compose (no UIKitView): an interop view sits behind the Metal layer and punches a hole that
 * lags animations and shows the page behind it until the first frame, so the video is just a Compose image.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private class CanvasPlayer(url: String) {
    // Dropped on release: each AVPlayer holds a connection to mediaplaybackd until the ObjC object goes, and a K/N
    // wrapper is only freed by a later GC. One clip per song piling up crashed the media server ("too many clients").
    // The pauses policy is kept for the iOS versions that honor it, but iOS 26 doesn't apply it: the
    // composable also pauses the clip itself whenever the app leaves the foreground (see the play gate).
    private var player: AVQueuePlayer? = AVQueuePlayer().apply {
        muted = true
        preventsDisplaySleepDuringVideoPlayback = false
        audiovisualBackgroundPlaybackPolicy = AVPlayerAudiovisualBackgroundPlaybackPolicyPauses
    }
    private var looper: AVPlayerLooper? = AVPlayerLooper.playerLooperWithPlayer(player!!, videoOnlyItem(url).apply { preferredMaximumResolution = CGSizeMake(1280.0, 1280.0) })
    private var item: AVPlayerItem? = null
    private var output: AVPlayerItemVideoOutput? = null
    private var image: Image? = null
    private var bytes = ByteArray(0)

    /** Displayed size in px, so the output can be decoded at that size instead of the clip's full one. */
    var viewSize = IntSize.Zero
    var mode = CanvasContentMode.CROP
    var alignTop = false

    fun play(on: Boolean) { if (on) player?.play() else player?.pause() }

    /** AVPlayerLooper swaps items each loop; an output belongs to one item, so re-attach on change. */
    private fun outputFor(cur: AVPlayerItem): AVPlayerItemVideoOutput? {
        // K/N hands out a fresh wrapper per call, so compare with isEqual, not identity.
        if (item?.isEqual(cur) != true || output == null) {
            // Needs the clip's size and the view's, to ask the decoder for what is actually shown.
            val (nw, nh) = cur.presentationSize.useContents { width.toInt() to height.toInt() }
            if (nw <= 0 || nh <= 0 || viewSize.width <= 0 || viewSize.height <= 0) return null
            val shown = placement(IntSize(nw, nh), androidx.compose.ui.geometry.Size(viewSize.width.toFloat(), viewSize.height.toFloat()), mode, alignTop).second
            val k = min(1f, max(shown.width.toFloat() / nw, shown.height.toFloat() / nh))
            output?.let { item?.removeOutput(it) }
            val o = AVPlayerItemVideoOutput(pixelBufferAttributes = mapOf<Any?, Any?>(
                "PixelFormatType" to NSNumber(unsignedInt = kCVPixelFormatType_32BGRA),
                "Width" to NSNumber(int = (nw * k).roundToInt().coerceAtLeast(2)),
                "Height" to NSNumber(int = (nh * k).roundToInt().coerceAtLeast(2)),
            ))
            cur.addOutput(o)
            item = cur
            output = o
        }
        return output!!
    }

    /** The newest decoded frame, or null when nothing new arrived since the last call. */
    fun nextFrame(): ImageBitmap? {
        val cur = player?.currentItem ?: return null
        val o = outputFor(cur) ?: return null
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

    /**
     * Android's captureAt: what the view shows of the clip (the contain margins excluded), no more
     * than [maxPx] on its longest edge, for the caller to average into a backdrop tint.
     */
    fun capture(maxPx: Int): ImageBitmap? {
        val img = image ?: return null
        val vw = viewSize.width.toFloat()
        val vh = viewSize.height.toFloat()
        if (vw <= 0f || vh <= 0f) return null
        val (off, size) = placement(IntSize(img.width, img.height), androidx.compose.ui.geometry.Size(vw, vh), mode, alignTop)
        val l = max(0f, off.x.toFloat()); val t = max(0f, off.y.toFloat())
        val r = min(vw, (off.x + size.width).toFloat()); val b = min(vh, (off.y + size.height).toFloat())
        if (r <= l || b <= t) return null
        val sx = img.width.toFloat() / size.width; val sy = img.height.toFloat() / size.height
        val src = Rect.makeLTRB((l - off.x) * sx, (t - off.y) * sy, (r - off.x) * sx, (b - off.y) * sy)
        val scale = min(1f, maxPx.toFloat() / max(vw, vh))
        val w = ((r - l) * scale).roundToInt().coerceAtLeast(1)
        val h = ((b - t) * scale).roundToInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(img, src, Rect.makeWH(w.toFloat(), h.toFloat()))
        val bmp = surface.makeImageSnapshot().toComposeImageBitmap()
        surface.close()
        return bmp
    }

    fun release() {
        looper?.disableLooping()
        player?.pause()
        output?.let { item?.removeOutput(it) }
        player?.removeAllItems()
        image?.close()
        image = null
        output = null
        item = null
        looper = null
        player = null
    }
}

/**
 * The clip's video track alone, in a composition with no audio track at all. Muting wasn't enough (a rendering
 * silent audio track is still playback iOS credits to the app), and disabling the item's audio tracks wasn't
 * either: AVPlayerLooper plays fresh copies whose tracks arrive only once each copy is ready, so every loop
 * began with its audio track enabled and flipped the lock screen / Dynamic Island back to the playing state
 * while the music sat paused. An item with no audio track never opens an audio pipeline to be credited.
 * Sync track/duration loads are fine here: CanvasPlayer is built off the main thread (see produceState below).
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun videoOnlyItem(url: String): AVPlayerItem {
    val asset = AVURLAsset.URLAssetWithURL(NSURL.URLWithString(url)!!, null)
    val video = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
        ?: return AVPlayerItem.playerItemWithAsset(asset)
    val comp = AVMutableComposition.composition()
    val track = comp.addMutableTrackWithMediaType(AVMediaTypeVideo, kCMPersistentTrackID_Invalid)
        ?: return AVPlayerItem.playerItemWithAsset(asset)
    track.insertTimeRange(CMTimeRangeMake(CMTimeMake(0, 1), asset.duration), video, CMTimeMake(0, 1), null)
    track.preferredTransform = video.preferredTransform
    return AVPlayerItem.playerItemWithAsset(comp)
}

/** Android's applyContentTransform: crop to fill, or fit a portrait clip (pinned to the top when asked). */
private fun placement(frame: IntSize, view: androidx.compose.ui.geometry.Size, mode: CanvasContentMode, alignTop: Boolean): Pair<IntOffset, IntSize> {
    val aspect = frame.width.toFloat() / frame.height
    val viewAspect = view.width / view.height
    val fit = mode == CanvasContentMode.FIT_PORTRAIT && aspect < 1f
    val widthLimited = if (fit) aspect > viewAspect else aspect <= viewAspect
    val lw = if (widthLimited) view.width else view.height * aspect
    val lh = if (widthLimited) view.width / aspect else view.height
    val y = if (fit && alignTop) 0f else (view.height - lh) / 2
    return IntOffset(((view.width - lw) / 2).toInt(), y.toInt()) to IntSize(lw.toInt(), lh.toInt())
}

/** PlayerHost.CanvasVideo on iOS: the Spotify Canvas loop behind the artwork. Frame capture and bottom fade mirror Android's. */
@Composable
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal fun IosCanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) {
    val current by rememberUpdatedState(spec)
    // Built off the main thread: AVQueuePlayer() and AVPlayerItem(URL:) each wait on a synchronous XPC call to the media
    // server, and a busy one held the main thread until the watchdog killed the app (0x8BADF00D). Not cancellable
    // while it is built, so a clip made for a song already gone is still released, not leaked with its connection.
    val clip by produceState<CanvasPlayer?>(null, spec.canvas.url) {
        // From disk when this clip was shown before; fetched once otherwise, streamed only when it can't be.
        val source = withContext(Dispatchers.Default) { IosClipCache.fileFor(spec.canvas.url) } ?: spec.canvas.url
        val made = withContext(NonCancellable + Dispatchers.Default) { CanvasPlayer(source) }
        value = made
        awaitDispose { made.release() }
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember(clip) { mutableStateOf<ImageBitmap?>(null) }
    // Rendered only once a real frame exists, so the parent keeps the artwork opaque until then.
    val rendered = frame != null
    val aspect = frame?.let { it.width.toFloat() / it.height } ?: 0f

    DisposableEffect(clip) {
        onDispose {
            // The parent owns the still/canvas handoff: never leave it holding a clip that no longer exists.
            current.onRenderedChanged(false)
            current.onCoverChanged(0f)
            current.onAspectRatioChanged(0f)
        }
    }
    // Gated on the app being on screen, as Android's CanvasArtworkPlayer gates its decoder. Not left to
    // audiovisualBackgroundPlaybackPolicy: iOS 26 no longer applies the pauses policy, so a backgrounded
    // clip looped on at rate 1 and its mediaplaybackd session re-asserted IsPlaying=1 every loop — playback
    // the system credits to the app, keeping the lock screen / Dynamic Island on the playing state over
    // paused music no matter what the app published to MPNowPlayingInfoCenter.
    val foreground = com.music.laya.ui.rememberIsForeground()
    LaunchedEffect(clip, spec.pausedForTransition, foreground) {
        val clip = clip ?: return@LaunchedEffect
        val run = foreground && !spec.pausedForTransition
        clip.play(run)
        // Poll once per display frame, only while playing: a paused clip has nothing new to show.
        while (run) {
            androidx.compose.runtime.withFrameNanos { }
            clip.nextFrame()?.let { frame = it }
        }
    }
    clip?.let {
        it.viewSize = viewSize
        it.mode = spec.contentMode
        it.alignTop = spec.alignPortraitTop
    }
    // Same cadence as Android: one capture once the first frame shows, then every refreshFrameEveryMs if asked.
    LaunchedEffect(clip, rendered, spec.refreshFrameEveryMs, spec.frameCapturePx, aspect) {
        val clip = clip ?: return@LaunchedEffect
        if (!rendered) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        clip.capture(current.frameCapturePx)?.let { current.onFrameCaptured(it) }
        val every = spec.refreshFrameEveryMs ?: return@LaunchedEffect
        while (true) {
            delay(every)
            if (!current.pausedForTransition) clip.capture(current.frameCapturePx)?.let { current.onFrameCaptured(it) }
        }
    }
    LaunchedEffect(aspect) { current.onAspectRatioChanged(aspect) }
    LaunchedEffect(rendered) { current.onRenderedChanged(rendered) }

    val alpha by animateFloatAsState(if (rendered) 1f else 0f, tween(320), label = "canvasAlpha")
    LaunchedEffect(clip) { snapshotFlow { alpha * current.presentationAlpha() }.collect { current.onCoverChanged(it) } }

    Canvas(modifier.onSizeChanged { viewSize = it; clip?.viewSize = it }.graphicsLayer {
        this.alpha = if (current.contentMode == CanvasContentMode.FIT_PORTRAIT && aspect <= 0f) 0f else alpha * current.presentationAlpha()
        if (current.bottomFade > 0.001f) compositingStrategy = CompositingStrategy.Offscreen
    }.canvasOrganicReveal(spec.organicReveal)) {
        val f = frame ?: return@Canvas
        val (off, size) = placement(IntSize(f.width, f.height), this.size, current.contentMode, current.alignPortraitTop)
        clipRect { drawImage(f, srcOffset = IntOffset.Zero, srcSize = IntSize(f.width, f.height), dstOffset = off, dstSize = size) }
        // Android's FadingBottomFrame: a DST_IN gradient, opaque down to (1 - fade) of the clip's bottom edge, clear at it.
        val fade = current.bottomFade.coerceIn(0f, 1f)
        val endY = (current.bottomFadeEndPx ?: this.size.height).coerceIn(0f, this.size.height)
        if (fade > 0.001f && endY > 0f) {
            drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = endY * (1f - fade), endY = endY), blendMode = BlendMode.DstIn)
        }
    }
}
