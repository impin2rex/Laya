package com.music.laya.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import com.music.laya.data.settings.AppSettings
import kotlin.math.hypot
import kotlinx.coroutines.delay

/** Once per process: a recreated window (rotation, theme change, back from the background) skips it. */
private var splashShown = false

/** How the app icon set in Settings draws the logo, which the splash follows. */
enum class SplashLogo {
    /** The default and the glass icons: the logo in the icon's indigo-pink-peach gradient. */
    GRADIENT,
    /** The "Clear" glass icons: the logo in white. */
    CLEAR,
}

/**
 * The cold-start splash, drawn over the app (which composes and loads underneath): the logo, in
 * the look of the app icon set in Settings ([logo]), bobs on black, springs back with a gleam,
 * and the whole thing fades away. Black, as the window is before the first frame, so the platform's own launch screen runs straight into it.
 */
@Composable
fun ColdStartSplash(logo: SplashLogo = SplashLogo.GRADIENT) {
    val enabled = remember { AppSettings.splashScreen.value }
    var visible by remember { mutableStateOf(!splashShown && enabled) }
    if (!visible) return
    val reduced = remember { AppSettings.reduceAnimation.value }
    var bobbing by remember { mutableStateOf(false) }
    // 0 while the splash covers the app; 1 once Melo's iris has opened it all the way.
    val open = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        splashShown = true
        // The app composes underneath in the splash's first frames, the heaviest of the whole
        // start: the logo waits them out standing still, and bobs once the frames come freely.
        repeat(SETTLE_FRAMES) { withFrameNanos { } }
        bobbing = true
        delay(BOB_MS)
        bobbing = false
        delay(SETTLE_MS)
        if (reduced) fade.animateTo(0f, tween(EXIT_FADE_MS)) else open.animateTo(1f, tween(IRIS_MS, easing = IRIS_EASING))
        visible = false
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = fade.value
                // Offscreen, so the iris below can cut the black away and leave the app showing.
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawRect(Color.Black)
                drawContent()
                // Melo's launch iris: clear inside a circle growing from the logo, past a soft
                // edge, until it is past the farthest corner and the app shows whole.
                val p = open.value
                if (p > 0f) {
                    val feather = IRIS_FEATHER.toPx()
                    val far = hypot(size.width / 2f, size.height / 2f)
                    val outer = p * (far + feather)
                    val inner = (outer - feather).coerceAtLeast(0f) / outer
                    drawRect(
                        Brush.radialGradient(
                            0f to Color.Transparent,
                            inner to Color.Transparent,
                            1f to Color.Black,
                            center = center,
                            radius = outer,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        LayaMark(
            refreshing = bobbing,
            pullFraction = { 0f },
            height = 72.dp,
            color = Color.White,
            shineColor = if (logo == SplashLogo.CLEAR) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.45f),
            gradient = if (logo == SplashLogo.GRADIENT) ICON_GRADIENT else null,
            // As the iris opens the logo grows a quarter and fades, as Melo's M does.
            modifier = Modifier.graphicsLayer {
                val p = open.value
                scaleX = 1f + LOGO_GROW * p
                scaleY = 1f + LOGO_GROW * p
                alpha = 1f - p
            },
        )
    }
}

/** Sampled from the launcher icon's logo, bottom-left to top-right. */
private val ICON_GRADIENT = listOf(Color(0xFF5D44E5), Color(0xFFCC68E5), Color(0xFFFCAFAA))

/** Frames the logo holds still for while the app composes under it. */
private const val SETTLE_FRAMES = 4

private const val BOB_MS = 850L
private const val SETTLE_MS = 450L
/** Melo's iris: how long the splash takes to open into the app, on its curve, and its soft edge. */
private const val IRIS_MS = 700
private val IRIS_EASING = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private val IRIS_FEATHER = 140.dp

/** How much the logo grows as the iris opens. */
private const val LOGO_GROW = 0.25f

/** With Reduce animation on, the splash just fades, quickly. */
private const val EXIT_FADE_MS = 160
