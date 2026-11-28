/*
 * Home's snapping fling over its dial pages: ported from Melo (github.com/aarush-io/melo,
 * GPL-3.0) DialPageFling.kt and EvenFling.kt.
 */
package com.music.laya.ui.home

import androidx.compose.animation.core.Animation
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.DecayAnimation
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import com.music.laya.data.settings.AppSettings
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Home's LazyColumn fling over its dial pages: let go anywhere on one and the list settles on
 * that page or on what follows it, like turning a page. Elsewhere it glides on as usual until a
 * dial page comes on screen, then settles on whichever side the glide would have shown more of.
 * With reduce animation on it is the plain fling.
 *
 * @param listState Home's list.
 * @param pageKeys the LazyColumn item keys of the dial pages, top to bottom (e.g. `"speed_dial"`,
 *   then `"artist_dial"` when shown). A page's follower rests at the next key's place, the last
 *   one's at [nextOffsetPx].
 * @param restOffsetPx where the page with this key rests, measured from the list's content start
 *   (e.g. under pinned chips: the chips' height plus item spacing).
 * @param nextOffsetPx where whatever follows the last dial page comes to rest.
 */
@Composable
fun rememberDialFling(
    listState: LazyListState,
    pageKeys: List<Any>,
    restOffsetPx: (key: Any) -> Int,
    nextOffsetPx: () -> Int,
): FlingBehavior {
    val reduced by AppSettings.reduceAnimation.collectAsState()
    if (reduced) return ScrollableDefaults.flingBehavior()
    val decay = rememberSplineBasedDecay<Float>()
    val keys by rememberUpdatedState(pageKeys)
    val rest by rememberUpdatedState(restOffsetPx)
    val next by rememberUpdatedState(nextOffsetPx)
    return remember(listState, decay) { DialPageFling(listState, decay, { keys }, { rest(it) }, { next() }) }
}

/** How fast a release has to be, in px/s, to count as a flick one way or the other. */
private const val PAGE_FLING_VELOCITY = 600f

private class DialPageFling(
    private val listState: LazyListState,
    private val decay: DecayAnimationSpec<Float>,
    private val pageKeys: () -> List<Any>,
    private val restOffsetPx: (key: Any) -> Int,
    private val nextOffsetPx: () -> Int,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val (toTop, toNext) = straddlingPage() ?: return glide(initialVelocity)
        val forward = when {
            initialVelocity > PAGE_FLING_VELOCITY -> true
            initialVelocity < -PAGE_FLING_VELOCITY -> false
            // A slow release: whichever it is past a third of the way to.
            else -> -toTop > (toNext - toTop) / 3f
        }
        settle(if (forward) toNext else toTop, initialVelocity)
        return 0f
    }

    /**
     * The dial page holding the top of the screen, as the scrolls that bring it back to rest and
     * that bring what follows it up to its own place. Null when no dial page does.
     */
    private fun straddlingPage(): Pair<Float, Float>? {
        val keys = pageKeys()
        fun followingRest(key: Any): Int {
            val i = keys.indexOf(key)
            return if (i in 0 until keys.lastIndex) restOffsetPx(keys[i + 1]) else nextOffsetPx()
        }
        val info = listState.layoutInfo
        val page = info.visibleItemsInfo.firstOrNull { item ->
            item.key in keys && item.offset <= restOffsetPx(item.key) && item.offset + item.size > followingRest(item.key)
        } ?: return null
        return (page.offset - restOffsetPx(page.key)).toFloat() to
            (page.offset + page.size + info.mainAxisItemSpacing - followingRest(page.key)).toFloat()
    }

    /** The ordinary glide, cut short once a dial page comes on screen. */
    private suspend fun ScrollScope.glide(initialVelocity: Float): Float {
        if (abs(initialVelocity) <= 1f) return finishOnPage(0f, initialVelocity)
        var remaining = initialVelocity
        var caught = false
        var last = 0f
        DecayAnimation(decay, Float.VectorConverter, 0f, initialVelocity).runOnFrames { value, velocity ->
            val delta = value - last
            val consumed = scrollBy(delta)
            last = value
            remaining = velocity
            when {
                straddlingPage() != null -> { caught = true; false }
                abs(delta - consumed) > 0.5f -> { remaining = 0f; false }
                else -> true
            }
        }
        return if (caught) finishOnPage(remaining, remaining) else remaining
    }

    /** Settles on the dial page's side the glide at [velocity] would have shown more of. */
    private suspend fun ScrollScope.finishOnPage(velocity: Float, handOver: Float): Float {
        val (toTop, toNext) = straddlingPage() ?: return velocity
        val lands = decay.calculateTargetValue(0f, velocity)
        settle(if (lands > (toTop + toNext) / 2f) toNext else toTop, handOver)
        return 0f
    }

    private suspend fun ScrollScope.settle(target: Float, initialVelocity: Float) {
        if (abs(target) < 1f) return
        var scrolled = 0f
        TargetBasedAnimation(
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            typeConverter = Float.VectorConverter,
            initialValue = 0f,
            targetValue = target,
            initialVelocity = initialVelocity,
        ).runOnFrames(scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) { value, _ ->
            scrolled += scrollBy(value - scrolled)
            true
        }
    }
}

/** First-frame lead: the frame the finger lifts on already moved the list, so the curve starts one frame in. */
private const val LEAD_NANOS = 8_333_333L

/**
 * Runs this motion on the frame clock by each frame's own (nanosecond) time. [onFrame] has the
 * value and velocity at each frame and says whether to go on. [scale] 0 jumps to the end.
 * ponytail: melo also measures the display's frame beat for the lead and interpolates between
 * whole milliseconds; this uses a fixed 120 Hz lead. Port its FrameBeat if a high-refresh judder shows.
 */
private suspend fun Animation<Float, AnimationVector1D>.runOnFrames(
    scale: Float = 1f,
    onFrame: (value: Float, velocity: Float) -> Boolean,
) {
    if (scale <= 0f) {
        onFrame(targetValue, 0f)
        return
    }
    var start = -1L
    do {
        val more = withFrameNanos { now ->
            if (start < 0) start = now - (LEAD_NANOS * scale).toLong()
            val play = ((now - start) / scale.toDouble()).toLong()
            if (play >= durationNanos) {
                onFrame(targetValue, 0f)
                false
            } else {
                onFrame(getValueFromNanos(play), getVelocityVectorFromNanos(play).value)
            }
        }
    } while (more)
}
