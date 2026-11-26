/*
 * The colour background behind every page: ported from Melo's ambient background
 * (github.com/aarush-io/melo, GPL-3.0, AmbientBlobs.kt), with Laya's own artwork palette.
 */
package com.music.laya.ui.theme

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.music.laya.data.settings.AppSettings
import com.music.laya.ui.player.artworkMeshColors
import com.music.laya.ui.graphics.forPixelAccess
import com.music.laya.ui.graphics.toImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** The playing song's artwork, set by whoever knows the player; read by the app's root to colour the background. */
object AmbientArtwork {
    val url = MutableStateFlow<String?>(null)
}

/** The colour background's drawing, provided by the app's root to the container every page is drawn in. */
val LocalAmbientBackground = androidx.compose.runtime.compositionLocalOf<(DrawScope.() -> Unit)?> { null }

/**
 * Draws [draw] (from [rememberAmbientBackground]) in a graphics layer of its own, under the
 * pages: its drift and colour fades redraw only that layer, never the pages above it or the
 * layer the glass records them into.
 */
@Composable
fun AmbientBackgroundLayer(draw: (DrawScope.() -> Unit)?, modifier: Modifier) {
    if (draw == null) return
    Spacer(modifier.graphicsLayer { }.drawBehind(draw))
}

/** The colours behind every page with nothing playing: the app icon's, in the look picked in Settings. */
fun ambientIdleColors(clearIcon: Boolean): List<Color> =
    if (clearIcon) listOf(Color(0xFF7D8594), Color(0xFF5F6675)) else listOf(Color(0xFF5D44E5), Color(0xFFCC68E5))

/**
 * Melo's colour background: three soft blobs of colour taken from the playing song's artwork,
 * drifting slowly over the whole window behind every page. A new song's colours fade in over
 * [COLOR_FADE_MS] once its artwork is read (the last song's hold until then); with nothing
 * playing it takes [idle]. Returns what to draw behind the pages, or null with the setting off.
 */
@Composable
fun rememberAmbientBackground(idle: List<Color>): (DrawScope.() -> Unit)? {
    val enabled by AppSettings.colorBackground.collectAsStateWithLifecycle()
    val reduced by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val artwork by AmbientArtwork.url.collectAsStateWithLifecycle()
    val context = LocalPlatformContext.current

    var songColors by remember { mutableStateOf<List<Color>?>(null) }
    LaunchedEffect(artwork, enabled) {
        if (!enabled) return@LaunchedEffect
        val url = artwork ?: run { songColors = null; return@LaunchedEffect }
        val request = ImageRequest.Builder(context).data(url).size(128).forPixelAccess().build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toImageBitmap()
            ?: return@LaunchedEffect // the last song's colours stay
        songColors = withContext(Dispatchers.Default) { artworkMeshColors(bitmap) }
    }
    val spec = if (reduced) snap<Color>() else tween(COLOR_FADE_MS)
    val main by animateColorAsState(ambientTone(songColors?.getOrNull(0) ?: idle[0]), spec, label = "ambientMain")
    val second by animateColorAsState(
        ambientTone(songColors?.getOrNull(1) ?: idle.getOrNull(1) ?: companion(idle[0])),
        spec,
        label = "ambientSecond",
    )

    // The drift: one slow loop, stepped rather than drawn every frame, and still while the app is
    // in the background or Reduce animation is on.
    val phase = remember { mutableFloatStateOf(START_PHASE) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(reduced, enabled, resumed.isAtLeast(Lifecycle.State.RESUMED)) {
        if (reduced || !enabled || !resumed.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        while (true) {
            delay(STEP_MS)
            phase.floatValue = (phase.floatValue + STEP_MS.toFloat() / CYCLE_MS) % 1f
        }
    }
    if (!enabled) return null
    return remember {
        {
            val w = size.width
            val h = size.height
            val t = phase.floatValue * 2f * PI.toFloat()
            val radius = max(w, h) * 0.48f
            fun blob(color: Color, alpha: Float, x: Float, y: Float) {
                val center = Offset(w * x, h * y)
                drawCircle(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.45f), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
            val a = ALPHA
            blob(main, a, 0.5f + 0.38f * sin(t), 0.3f + 0.24f * sin(2 * t + 0.6f))
            blob(Color(main.red * 0.7f, main.green * 0.7f, main.blue * 0.7f), a * 0.9f,
                0.5f + 0.38f * cos(2 * t + 1.3f), 0.6f + 0.3f * sin(t + 2.2f))
            blob(second, a * 0.95f, 0.5f + 0.4f * sin(3 * t + 2.4f), 0.5f + 0.34f * cos(t + 0.9f))
        }
    }
}

/** The colour at a brightness that reads as a soft glow in either theme: HSV value held to 0.6–0.85. */
private fun ambientTone(color: Color): Color {
    val (h, s, v) = color.toHsv()
    return Color.hsv(h, s, v.coerceIn(0.6f, 0.85f), color.alpha)
}

/** A second colour beside [color]: 28° on round the wheel, a little darker. */
private fun companion(color: Color): Color {
    val (h, s, v) = color.toHsv()
    return Color.hsv((h + 28f) % 360f, s, v * 0.8f)
}

private fun Color.toHsv(): Triple<Float, Float, Float> {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == red -> 60f * (((green - blue) / d) % 6f)
        max == green -> 60f * ((blue - red) / d + 2f)
        else -> 60f * ((red - green) / d + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return Triple(h, if (max == 0f) 0f else d / max, max)
}

private const val ALPHA = 0.30f
private const val COLOR_FADE_MS = 900
private const val CYCLE_MS = 36_000
private const val STEP_MS = 80L
private const val START_PHASE = 0.15f
