package com.music.laya.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.laya.data.settings.AppSettings
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

// ic_logo's two pieces, in its own viewport (219.55 x 281.52, drawn offset by ORIGIN).
private val BigPiece = PathParser().parsePathString(
    "M 155.07 285.59 L 308.62 159.01 A 33 33 0 0 1 350.60 209.93 L 315.88 238.56 A 19.3 19.3 0 0 0 324.31 272.36 " +
        "A 33.5 33.5 0 0 1 338.95 331.00 L 278.76 380.61 A 33.5 33.5 0 0 1 236.14 328.91 L 284.75 288.84 " +
        "A 9.5 9.5 0 0 0 272.67 274.18 L 197.05 336.51 A 33 33 0 0 1 155.07 285.59 Z",
).toPath()
private val SmallPiece = PathParser().parsePathString(
    "M 155.92 173.43 L 227.68 114.27 A 33 33 0 0 1 269.66 165.20 L 197.90 224.35 A 33 33 0 0 1 155.92 173.43 Z",
).toPath()
private const val VIEW_W = 219.55f
private const val VIEW_H = 281.52f
private val ORIGIN = Offset(143.06f, 106.74f)

/** How far (viewport units) a piece rises at the top of its bob, and when pulled all the way. */
private const val LIFT = 26f
private const val BOB_SECONDS = 0.9f
private const val SHINE_SECONDS = 1.4f

/**
 * Laya's logo, doubling as Home's pull-to-refresh indicator: the small piece lifts and a light
 * sweeps across as the feed is pulled, the two pieces rise and fall in turn while the refresh
 * runs, and they spring back to rest with one last gleam. Still under Reduce animation.
 */
@Composable
fun LayaMark(
    refreshing: Boolean,
    pullFraction: () -> Float,
    height: Dp,
    color: Color,
    shineColor: Color,
    modifier: Modifier = Modifier,
    /** Draws the logo in the app icon's gradient (bottom-left to top-right) instead of [color]. */
    gradient: List<Color>? = null,
) {
    val reduced by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val clock = remember { mutableFloatStateOf(0f) }
    var bouncing by remember { mutableStateOf(false) }
    val released = remember { floatArrayOf(0f, 0f) }
    val settle = remember { Animatable(1f) }
    val gleam = remember { Animatable(-1f) }

    LaunchedEffect(refreshing, reduced) {
        when {
            reduced -> { bouncing = false; settle.snapTo(1f) }
            refreshing -> {
                settle.snapTo(1f)
                bouncing = true
                val start = withFrameNanos { it }
                while (true) clock.floatValue = (withFrameNanos { it } - start) / 1_000_000_000f
            }
            bouncing -> {
                released[0] = bob(clock.floatValue, 0f)
                released[1] = bob(clock.floatValue, PI.toFloat())
                bouncing = false
                settle.snapTo(0f)
                launch {
                    gleam.snapTo(0f)
                    gleam.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
                    gleam.snapTo(-1f)
                }
                settle.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 260f))
            }
        }
    }

    Canvas(modifier.height(height).aspectRatio(VIEW_W / VIEW_H)) {
        val pull = if (reduced || bouncing) 0f else pullFraction().coerceIn(0f, 1f)
        // Rise of each piece, in viewport units (up is positive).
        val (small, big) = when {
            reduced -> 0f to 0f
            bouncing -> bob(clock.floatValue, 0f) to bob(clock.floatValue, PI.toFloat())
            settle.isRunning -> lerp(released[0], 0f, settle.value) to lerp(released[1], 0f, settle.value)
            else -> LIFT * (pull * 1.6f).coerceIn(0f, 1f) to LIFT * 0.4f * (pull * 1.6f - 0.6f).coerceIn(0f, 1f)
        }
        val sweep = when {
            reduced -> -1f
            gleam.value >= 0f -> gleam.value
            bouncing -> (clock.floatValue / SHINE_SECONDS) % 1f
            pull > 0f -> pull
            else -> -1f
        }
        val shine = if (sweep in 0f..1f) {
            val x = ORIGIN.x + lerp(-0.45f, 1.45f, sweep) * VIEW_W
            Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to shineColor,
                1f to Color.Transparent,
                start = Offset(x - VIEW_W * 0.25f, ORIGIN.y + VIEW_H),
                end = Offset(x + VIEW_W * 0.25f, ORIGIN.y),
            )
        } else null
        val dim = if (reduced && refreshing) 0.6f else 1f
        val fill: Brush = if (gradient != null) {
            Brush.linearGradient(
                gradient.map { it.copy(alpha = it.alpha * dim) },
                start = Offset(ORIGIN.x, ORIGIN.y + VIEW_H),
                end = Offset(ORIGIN.x + VIEW_W, ORIGIN.y),
            )
        } else {
            SolidColor(color.copy(alpha = color.alpha * dim))
        }
        withTransform({
            scale(size.width / VIEW_W, size.height / VIEW_H, Offset.Zero)
            translate(-ORIGIN.x, -ORIGIN.y)
        }) {
            for ((path, rise) in listOf(SmallPiece to small, BigPiece to big)) {
                translate(top = -rise) {
                    drawPath(path, fill)
                    if (shine != null) drawPath(path, shine)
                }
            }
        }
    }
}

/** A piece's rise mid-refresh: the two alternate, easing in over the first moment. */
private fun bob(seconds: Float, phase: Float): Float =
    LIFT * (0.5f + 0.5f * sin(2f * PI.toFloat() * seconds / BOB_SECONDS + phase)) * min(1f, seconds / 0.3f)
