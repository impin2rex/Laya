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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.music.laya.ui.player.CanvasContentMode
import com.music.laya.ui.player.CanvasVideoSpec
import kotlinx.cinterop.useContents
import kotlinx.coroutines.delay
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerLooper
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.currentItem
import platform.AVFoundation.muted
import platform.AVFoundation.preventsDisplaySleepDuringVideoPlayback
import platform.AVFoundation.presentationSize
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView

/** Reports its own layout passes: Compose sets this view's frame, and the video layer has to follow. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private class Container : UIView(CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    var onLayout: (() -> Unit)? = null
    override fun layoutSubviews() {
        super.layoutSubviews()
        onLayout?.invoke()
    }
}

/** One clip: a muted, gapless-looping AVQueuePlayer drawn by an AVPlayerLayer inside a clipping container. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private class CanvasPlayer(url: String) {
    val container = Container().apply { clipsToBounds = true; backgroundColor = UIColor.clearColor; userInteractionEnabled = false }
    private val player = AVQueuePlayer().apply { muted = true; preventsDisplaySleepDuringVideoPlayback = false }
    private val looper = AVPlayerLooper.playerLooperWithPlayer(player, AVPlayerItem.playerItemWithURL(NSURL.URLWithString(url)!!))
    private val layer = AVPlayerLayer.playerLayerWithPlayer(player).apply { videoGravity = AVLayerVideoGravityResize; hidden = true }

    init { container.layer.addSublayer(layer) }

    fun play(on: Boolean) = if (on) player.play() else player.pause()

    /** Width / height of the clip, 0 until the item has decoded its size. */
    fun aspect(): Float = player.currentItem?.presentationSize?.useContents { if (width > 0.0 && height > 0.0) (width / height).toFloat() else 0f } ?: 0f

    fun readyForDisplay() = layer.readyForDisplay

    /** Android's applyContentTransform: crop to fill, or fit a portrait clip (pinned to the top when asked). */
    fun layout(aspect: Float, mode: CanvasContentMode, alignTop: Boolean) {
        val w = container.bounds.useContents { size.width }
        val h = container.bounds.useContents { size.height }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        if (aspect <= 0f || w <= 0.0 || h <= 0.0) {
            layer.hidden = true
        } else {
            val viewAspect = w / h
            val fit = mode == CanvasContentMode.FIT_PORTRAIT && aspect < 1f
            val widthLimited = if (fit) aspect > viewAspect else aspect <= viewAspect
            val lw = if (widthLimited) w else h * aspect
            val lh = if (widthLimited) w / aspect else h
            val y = if (fit && alignTop) 0.0 else (h - lh) / 2
            layer.frame = CGRectMake((w - lw) / 2, y, lw, lh)
            layer.hidden = false
        }
        CATransaction.commit()
    }

    fun release() {
        looper.disableLooping()
        player.pause()
        layer.player = null
        layer.removeFromSuperlayer()
    }
}

/** PlayerHost.CanvasVideo on iOS: the Spotify Canvas loop behind the artwork. Frame capture / bottom fade are not implemented. */
@Composable
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal fun IosCanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) {
    val current by rememberUpdatedState(spec)
    val clip = remember(spec.canvas.url) { CanvasPlayer(spec.canvas.url) }
    var rendered by remember(clip) { mutableStateOf(false) }
    var aspect by remember(clip) { mutableStateOf(0f) }

    DisposableEffect(clip) {
        onDispose {
            clip.release()
            // The parent owns the still/canvas handoff: never leave it holding a clip that no longer exists.
            current.onRenderedChanged(false)
            current.onCoverChanged(0f)
            current.onAspectRatioChanged(0f)
        }
    }
    LaunchedEffect(clip) {
        while (true) {
            val a = clip.aspect()
            if (a != aspect) { aspect = a; rendered = false; current.onAspectRatioChanged(a) }
            val ready = a > 0f && clip.readyForDisplay()
            if (ready != rendered) rendered = ready
            delay(150)
        }
    }
    LaunchedEffect(rendered) { current.onRenderedChanged(rendered) }
    LaunchedEffect(clip, spec.pausedForTransition) { clip.play(!spec.pausedForTransition) }

    val alpha by animateFloatAsState(if (rendered) 1f else 0f, tween(320), label = "canvasAlpha")
    LaunchedEffect(clip) { snapshotFlow { alpha * current.presentationAlpha() }.collect { current.onCoverChanged(it) } }

    clip.container.onLayout = { clip.layout(aspect, current.contentMode, current.alignPortraitTop) }
    UIKitView(
        factory = { clip.container },
        modifier = modifier,
        update = { view ->
            // Set on the view itself: a Compose alpha layer does not reach a native view.
            view.alpha = if (current.contentMode == CanvasContentMode.FIT_PORTRAIT && aspect <= 0f) 0.0 else (alpha * current.presentationAlpha()).toDouble()
            clip.layout(aspect, current.contentMode, current.alignPortraitTop)
        },
        properties = UIKitInteropProperties(isInteractive = false, isNativeAccessibilityEnabled = false),
    )
}
