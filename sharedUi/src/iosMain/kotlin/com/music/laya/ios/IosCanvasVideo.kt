package com.music.laya.ios

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.music.laya.ui.player.CanvasContentMode
import com.music.laya.ui.player.CanvasVideoSpec
import kotlinx.coroutines.delay
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerLooper
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
    private val player = AVQueuePlayer().apply { muted = true; preventsDisplaySleepDuringVideoPlayback = false }
    private val looper = AVPlayerLooper.playerLooperWithPlayer(player, AVPlayerItem.playerItemWithURL(NSURL.URLWithString(url)!!).apply { preferredMaximumResolution = CGSizeMake(1280.0, 1280.0) })
    private var item: AVPlayerItem? = null
    private var output: AVPlayerItemVideoOutput? = null
    private var image: Image? = null
    private var bytes = ByteArray(0)

    fun play(on: Boolean) = if (on) player.play() else player.pause()

    /** AVPlayerLooper swaps items each loop; an output belongs to one item, so re-attach on change. */
    private fun outputFor(cur: AVPlayerItem): AVPlayerItemVideoOutput {
        // K/N hands out a fresh wrapper per call, so compare with isEqual, not identity.
        if (item?.isEqual(cur) != true || output == null) {
            output?.let { item?.removeOutput(it) }
            val o = AVPlayerItemVideoOutput(pixelBufferAttributes = mapOf<Any?, Any?>("PixelFormatType" to NSNumber(unsignedInt = kCVPixelFormatType_32BGRA)))
            cur.addOutput(o)
            item = cur
            output = o
        }
        return output!!
    }

    /** The newest decoded frame, or null when nothing new arrived since the last call. */
    fun nextFrame(): ImageBitmap? {
        val cur = player.currentItem ?: return null
        val o = outputFor(cur)
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
        looper.disableLooping()
        player.pause()
        output?.let { item?.removeOutput(it) }
        player.removeAllItems()
        image?.close()
    }
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

/** PlayerHost.CanvasVideo on iOS: the Spotify Canvas loop behind the artwork. Frame capture / bottom fade are not implemented. */
@Composable
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal fun IosCanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) {
    val current by rememberUpdatedState(spec)
    val clip = remember(spec.canvas.url) { CanvasPlayer(spec.canvas.url) }
    var frame by remember(clip) { mutableStateOf<ImageBitmap?>(null) }
    // Rendered only once a real frame exists, so the parent keeps the artwork opaque until then.
    val rendered = frame != null
    val aspect = frame?.let { it.width.toFloat() / it.height } ?: 0f

    DisposableEffect(clip) {
        onDispose {
            clip.release()
            // The parent owns the still/canvas handoff: never leave it holding a clip that no longer exists.
            current.onRenderedChanged(false)
            current.onCoverChanged(0f)
            current.onAspectRatioChanged(0f)
        }
    }
    LaunchedEffect(clip, spec.pausedForTransition) {
        clip.play(!spec.pausedForTransition)
        // Poll once per display frame, only while playing: a paused clip has nothing new to show.
        while (!spec.pausedForTransition) {
            androidx.compose.runtime.withFrameNanos { }
            clip.nextFrame()?.let { frame = it }
        }
    }
    LaunchedEffect(aspect) { current.onAspectRatioChanged(aspect) }
    LaunchedEffect(rendered) { current.onRenderedChanged(rendered) }

    val alpha by animateFloatAsState(if (rendered) 1f else 0f, tween(320), label = "canvasAlpha")
    LaunchedEffect(clip) { snapshotFlow { alpha * current.presentationAlpha() }.collect { current.onCoverChanged(it) } }

    Canvas(modifier.graphicsLayer { this.alpha = if (current.contentMode == CanvasContentMode.FIT_PORTRAIT && aspect <= 0f) 0f else alpha * current.presentationAlpha() }) {
        val f = frame ?: return@Canvas
        val (off, size) = placement(IntSize(f.width, f.height), this.size, current.contentMode, current.alignPortraitTop)
        clipRect { drawImage(f, srcOffset = IntOffset.Zero, srcSize = IntSize(f.width, f.height), dstOffset = off, dstSize = size) }
    }
}
