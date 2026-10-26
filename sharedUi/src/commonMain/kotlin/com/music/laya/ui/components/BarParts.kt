package com.music.laya.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.music.laya.data.model.ROW_ART_PX
import com.music.laya.data.model.Song
import com.music.laya.data.model.artworkAt
import com.music.laya.ui.components.thumbnailBorder
import com.music.laya.ui.haptics.Haptic
import com.music.laya.ui.haptics.rememberHaptics
import com.music.laya.ui.player.PlayerDock
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.music.laya.sharedui.resources.Res
import com.music.laya.sharedui.resources.ic_player_next
import com.music.laya.sharedui.resources.widget_next
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

// Pieces of the phone's MiniPlayer / FloatingBottomBar that the glass nav bar shares.

data class BottomTab(
    val label: String,
    val icon: ImageVector,
)

/**
 * The gap between the pill's glass edge and the tabs inside it.
 *
 * Tighter than the 8 it was, which shows up as a selection indicator reaching
 * closer to the edge on all four sides rather than floating in the middle of a
 * wide margin.
 *
 * Shared with [GlassNavBar], which is meant to measure the same as this bar
 * rather than merely near it.
 */
val PILL_INSET = 6.dp

/**
 * How far the floating bars stand off the bottom edge: the system navigation
 * bar's inset, but never less than a gesture bar's.
 *
 * With the gesture hint (or the navigation bar itself) switched off that inset
 * is zero, and the tab bar dropped onto the screen's very edge. It keeps the
 * height it has with the hint showing instead. Three-button navigation is
 * taller than the floor, so it still wins there.
 */
val floatingBarInsets: WindowInsets
    @Composable get() = WindowInsets.navigationBars.union(WindowInsets(bottom = GESTURE_BAR_FLOOR))

/** A gesture bar's inset — 45px at the test phone's 3x density. */
private val GESTURE_BAR_FLOOR = 15.dp

/**
 * Each tab's own vertical padding, and the counterweight to [PILL_INSET].
 *
 * The pill has no height of its own — it is whatever its contents come to — so
 * taking 2dp off the inset above would have shortened the whole bar by 4. The
 * same 2dp is added back here instead, which leaves the bar's outer height
 * exactly where it was and moves the boundary rather than the bar. The two
 * numbers are a pair: change one and the bar's height moves unless the other
 * moves against it.
 */
val TAB_VERTICAL_PADDING = 9.dp

/** The gap between a tab's glyph and its label, in both bars. */
val TAB_ICON_LABEL_GAP = 2.dp

/** Distance that makes a horizontal drag an intentional track change. */
val TRACK_SWIPE_THRESHOLD = 72.dp

/**
 * How much further a drag may go sideways than up and still be a pull. A
 * thumb swiping up from the bottom of the screen travels on an arc, and judged
 * level, 1:1, a good share of real pulls came out sideways — too short to skip
 * a track, so they did nothing at all. Steeper than about 34 degrees above
 * level is a pull.
 */
const val PULL_BIAS = 1.5f

/**
 * The finger distance over which the bar's own rise slows to about half its
 * starting rate. It is the player that follows the finger; the bar gives a
 * little and then less, so the pull visibly takes hold of something before
 * the player has come up over it.
 */
val PULL_LIFT_EASE = 72.dp

/** The bar's rise per pixel of finger as a pull starts. */
const val PULL_LIFT_RATE = 0.5f

/**
 * How far the bar rises for [pulled] pixels of finger: [PULL_LIFT_RATE] of the
 * finger to begin with, slowing the further it goes — a rubber band with no
 * end to it, so the bar keeps creeping up for as long as the finger does
 * rather than meeting a ceiling. Its rate is RATE / sqrt(1 + pulled / ease).
 */
fun pullLift(pulled: Float, ease: Float): Float =
    if (pulled <= 0f || ease <= 0f) {
        0f
    } else {
        PULL_LIFT_RATE * 2f * ease * (sqrt(1f + pulled / ease) - 1f)
    }


/**
 * Both of the bar's drags, for both mini-player materials: sideways through
 * the queue — a left swipe advances, a right one goes back, matching the full
 * player's artwork — and up into the player through [pull].
 *
 * One detector rather than one per axis, so a gesture is decided once, by the
 * way it is heading as it passes the touch slop. Two detectors raced each
 * other to their own axis's slop, and the sideways one was asked first — see
 * [PULL_BIAS] for what that did to pulls. The tap to expand is left to the
 * clickable, and is cancelled by this consuming the drag, as it was before.
 *
 * Under a pull the bar itself rises with it, less and less ([pullLift]), so this goes
 * where the bar's own transforms start: the finger is read outside that lift,
 * so a rising bar doesn't take distance off the finger it is following.
 *
 * Track swipes wait until the finger lifts, so one long gesture never skips
 * more than one item.
 */
@Composable
fun Modifier.miniPlayerGestures(
    pull: MiniPlayerPull?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    /** Listening in a party whose host holds the controls. Swipes say so instead of skipping. */
    locked: Boolean = false,
    onBlocked: () -> Unit = {},
): Modifier {
    // Playback state updates can recompose the bar while a finger is down.
    // Keep the gesture coroutine alive through those updates while still
    // dispatching to the latest controller callbacks when the drag finishes.
    val currentPull by rememberUpdatedState(pull)
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    val currentLocked by rememberUpdatedState(locked)
    val currentOnBlocked by rememberUpdatedState(onBlocked)
    val scope = rememberCoroutineScope()
    // Read at draw only, in the layer below: a pull moves the bar without
    // recomposing it.
    val lift = remember { mutableFloatStateOf(0f) }
    val settle = remember { arrayOfNulls<Job>(1) }
    return pointerInput(Unit) {
        val trackThreshold = TRACK_SWIPE_THRESHOLD.toPx()
        val liftEase = PULL_LIFT_EASE.toPx()
        val maxVelocity = viewConfiguration.maximumFlingVelocity
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pointer = down.id
            // Up to the slop: which way this is heading, or nothing at all.
            var start: PointerInputChange? = null
            var vertical = false
            while (start == null) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == pointer }
                    ?: return@awaitEachGesture
                // Lifted inside the slop is a tap, and the tap is the clickable's.
                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                val moved = change.position - down.position
                if (moved.getDistance() < viewConfiguration.touchSlop) continue
                vertical = moved.y < 0f && abs(moved.x) < -moved.y * PULL_BIAS
                // Downwards has nothing to do on the bar, and nor has a pull
                // with no player to pull: left as they always were, a drag that
                // ends inside the bar still counts as its tap.
                if (!vertical && abs(moved.x) < abs(moved.y)) return@awaitEachGesture
                if (vertical && currentPull == null) return@awaitEachGesture
                change.consume()
                start = change
            }
            val slopped = start

            if (!vertical) {
                var dragged = slopped.position.x - down.position.x
                // Read before consuming: a consumed change reports no movement.
                val finished = drag(pointer) { change ->
                    dragged += change.positionChange().x
                    change.consume()
                }
                if (!finished) return@awaitEachGesture
                val crossed = dragged <= -trackThreshold || dragged >= trackThreshold
                when {
                    currentLocked -> if (crossed) currentOnBlocked()
                    dragged <= -trackThreshold -> currentOnNext()
                    dragged >= trackThreshold -> currentOnPrevious()
                }
                return@awaitEachGesture
            }

            val target = currentPull ?: return@awaitEachGesture
            settle[0]?.cancel()
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            tracker.addPointerInputChange(slopped)
            // The player gets all of it from the touch down, slop included, so
            // it starts out level with the finger. The bar starts from here,
            // from nothing: lifted by the slop as well, it jumped a few pixels
            // in one frame before it began to follow.
            target.drag(slopped.position.y - down.position.y)
            var pulled = 0f
            val finished = drag(pointer) { change ->
                val delta = change.positionChange().y
                change.consume()
                tracker.addPointerInputChange(change)
                pulled -= delta
                target.drag(delta)
                lift.floatValue = pullLift(pulled, liftEase)
            }
            target.release(
                if (finished) tracker.calculateVelocity().y.coerceIn(-maxVelocity, maxVelocity) else 0f,
            )
            // Let go, the bar springs home: under the player on its way up, or
            // in full view if the pull fell short and the player went back.
            val from = lift.floatValue
            settle[0] = scope.launch {
                animate(
                    initialValue = from,
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.65f,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ) { value, _ -> lift.floatValue = value }
            }
        }
    }.graphicsLayer { translationY = -lift.floatValue }
}

/**
 * The mini player's cover as one end of the player opening and closing — see
 * [PlayerDock]. Shared by both mini-player materials.
 *
 * Reports where it is on being placed, and nothing more: the coordinates are
 * the same object for as long as the cover is on screen, and the player turns
 * them into a position only on the frames it is actually flying to them, so a
 * bar folding and unfolding on every scroll pays nothing for this.
 *
 * Stands aside while the player's own artwork is on its way to or from here.
 * Read at draw, off the sheet's offset — a layer property per frame of the
 * sheet's travel, never a recomposition of the bar.
 */
@Composable
fun Modifier.playerDockArt(dock: PlayerDock?, corner: Dp): Modifier {
    if (dock == null) return this
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    DisposableEffect(dock) {
        onDispose { placed[0]?.let(dock::releaseMiniArt) }
    }
    return this
        .onPlaced { coordinates ->
            placed[0] = coordinates
            dock.reportMiniArt(coordinates, corner)
        }
        .graphicsLayer { alpha = if (dock.coversMiniArt) 0f else 1f }
}


/**
 * The full player's skip-forward glyph, at the mini player's scale.
 *
 * Sized against the play glyph beside it the way the full player sizes it
 * against its own play button — about half as wide again and two thirds as
 * tall — and squashed by the same 0.85, so the two players' next buttons
 * read as one glyph at two sizes. Shared with the glass bar's docked player.
 */
@Composable
fun MiniPlayerNextGlyph(tint: Color) {
    Icon(
        painter = painterResource(Res.drawable.ic_player_next),
        contentDescription = stringResource(Res.string.widget_next),
        tint = tint,
        modifier = Modifier
            .size(NEXT_GLYPH_SIZE)
            .graphicsLayer { scaleY = 0.85f },
    )
}

private val NEXT_GLYPH_SIZE = 25.dp
