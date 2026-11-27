package com.music.laya.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.music.laya.data.model.ShelfItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's dice tile (sharedUi HomeDialGrids' LuckyDiceTile), on the desktop: a die of nine squares on a
 * dark ground with soft washes of colour. A click sets a light chasing round its edge at one even speed, a
 * square at a time, until the pick is found (at least 450ms, at most 12s); then it comes to rest and lands on
 * a face. Desktop has no haptics; reduced animation skips the chase, as on the phone.
 */
@Composable
internal fun DesktopLuckyDiceTile(
    modifier: Modifier,
    shape: Shape,
    label: String,
    onRoll: suspend () -> ShelfItem?,
    onPick: (ShelfItem) -> Unit,
) {
    val reduced by DesktopPlayerSettings.reduceAnimation.collectAsState()
    val scope = rememberCoroutineScope()
    var face by remember { mutableIntStateOf(3) }
    val spin = remember { Animatable(0f) }
    val settle = remember { Animatable(1f) }
    var rolling by remember { mutableStateOf(false) }
    Box(
        modifier
            .desktopHoverLift(shape)
            .clip(shape)
            .background(DICE_GROUND)
            .clickable(role = Role.Button) {
                if (rolling) return@clickable
                rolling = true
                scope.launch {
                    val found = async { runCatching { onRoll() }.getOrNull() }
                    try {
                        if (reduced) {
                            found.await()?.let(onPick)
                            face = (1..6).random()
                            return@launch
                        }
                        spin.snapTo(0f)
                        coroutineScope {
                            val chase = launch {
                                spin.animateTo(
                                    DICE_WAIT_MAX_MS * DICE_SPEED,
                                    tween(DICE_WAIT_MAX_MS.toInt(), easing = LinearEasing),
                                )
                            }
                            settle.animateTo(0f, tween(DICE_FADE_MS))
                            delay(DICE_MIN_MS - DICE_FADE_MS)
                            val pick = withTimeoutOrNull(DICE_WAIT_MAX_MS - DICE_MIN_MS) { found.await() }
                            if (!found.isCompleted) found.cancel()
                            pick?.let(onPick)
                            chase.cancel()
                        }
                        // Out of that speed to a stop with no step in it: the curve starts at twice its mean
                        // speed, so its time makes that the speed the light had.
                        spin.animateTo(
                            spin.value + DICE_REST_STEPS,
                            tween((2 * DICE_REST_STEPS / DICE_SPEED).toInt(), easing = DICE_REST_EASING),
                        )
                        face = (1..6).random()
                        settle.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium))
                    } finally {
                        rolling = false
                    }
                }
            }
            .semantics {
                contentDescription = label
                if (rolling) stateDescription = "…"
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Crimson in from the upper left, violet from the lower right, a little cyan at the upper right.
            DICE_WASHES.forEach { wash ->
                drawRect(
                    Brush.radialGradient(
                        listOf(wash.color.copy(alpha = wash.alpha), wash.color.copy(alpha = 0f)),
                        center = Offset(w * wash.x, h * wash.y),
                        radius = size.maxDimension * wash.radius,
                    ),
                )
            }
            // Three squares of 80 with gaps of 30 on a 512 ground, dead centre.
            val unit = size.minDimension / 512f
            val cellSide = 80f * unit
            val gap = 30f * unit
            val gridSide = cellSide * 3 + gap * 2
            val left = (w - gridSide) / 2f
            val top = (h - gridSide) / 2f
            val head = spin.value % DICE_RING.size
            val toFace = settle.value
            val pips = DICE_FACES[face - 1]
            for (i in 0 until 9) {
                val ring = DICE_RING.indexOf(i)
                val spinner = if (ring < 0) 0f else {
                    val behind = ((head - ring) % DICE_RING.size + DICE_RING.size) % DICE_RING.size
                    (1f - behind / DICE_TAIL).coerceIn(0f, 1f)
                }
                val pip = if (i in pips) 1f else 0f
                val level = (spinner + (pip - spinner) * toFace).coerceIn(0f, 1f)
                // A lit square swells a touch with the landing's overshoot.
                val pop = if (pip > 0f && toFace > 1f) 1f + (toFace - 1f) * 0.6f else 1f
                val side = cellSide * pop
                val center = Offset(
                    left + (i % 3) * (cellSide + gap) + cellSide / 2f,
                    top + (i / 3) * (cellSide + gap) + cellSide / 2f,
                )
                if (level > 0f) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(DICE_LIT.copy(alpha = DICE_GLOW * level), DICE_LIT.copy(alpha = 0f)),
                            center = center,
                            radius = side * 1.05f,
                        ),
                        radius = side * 1.05f,
                        center = center,
                    )
                }
                drawRoundRect(
                    color = lerp(DICE_UNLIT, DICE_LIT, level),
                    topLeft = Offset(center.x - side / 2f, center.y - side / 2f),
                    size = Size(side, side),
                    cornerRadius = CornerRadius(side * 0.08f),
                )
            }
        }
    }
}

private class DiceWash(val color: Color, val x: Float, val y: Float, val radius: Float, val alpha: Float)

private val DICE_WASHES = listOf(
    DiceWash(Color(0xFFFA5A72), 0f, 0.15f, 0.62f, 0.26f),
    DiceWash(Color(0xFF8B5CF6), 1f, 0.85f, 0.68f, 0.26f),
    DiceWash(Color(0xFF38BDF8), 1f, 0.1f, 0.5f, 0.18f),
)

/** The phone's dark look; the desktop is always dark. */
private val DICE_GROUND = Color(0xFF141414)
private val DICE_UNLIT = Color(0xFF262626)
private val DICE_LIT = Color.White
private const val DICE_GLOW = 0.45f

/** The squares round the edge in the order the light goes, and each face's lit squares. */
private val DICE_RING = intArrayOf(0, 1, 2, 5, 8, 7, 6, 3)
private val DICE_FACES = listOf(
    setOf(4),
    setOf(2, 6),
    setOf(0, 4, 8),
    setOf(0, 2, 6, 8),
    setOf(0, 2, 4, 6, 8),
    setOf(0, 2, 3, 5, 6, 8),
)

/** The light's speed in squares a millisecond (once round in 720ms), its least time, its rest and its tail. */
private const val DICE_SPEED = 8f / 720f
private const val DICE_MIN_MS = 450L
private const val DICE_REST_STEPS = 2f
private val DICE_REST_EASING = Easing { 1f - (1f - it) * (1f - it) }
private const val DICE_TAIL = 3.2f
private const val DICE_FADE_MS = 140
/** The longest the die waits for the pick, so it can never be left turning. */
private const val DICE_WAIT_MAX_MS = 12_000L
