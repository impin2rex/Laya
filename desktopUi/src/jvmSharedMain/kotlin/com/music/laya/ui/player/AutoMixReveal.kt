package com.music.laya.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.music.laya.playback.AutoMixArtworkTuning as Tuning
import kotlin.math.roundToInt
import kotlin.random.Random
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.RuntimeEffect

/**
 * The incoming AutoMix artwork's organic reveal: a soft value-noise field that the picture grows
 * through, and a blur that sharpens as it lands — BitChord's AndroidAutoMixReveal, made shared so
 * iOS and the desktop draw the same field. The field is one SkSL source; Android runs it as AGSL
 * (API 33+), the other platforms through Skia's RuntimeEffect.
 */
internal interface AutoMixMaskShader {
    /** The field at [progress] over a [width] x [height] layer. May return the same, updated, instance. */
    fun shader(width: Float, height: Float, progress: Float): Shader
}

/** Null where runtime shaders are unavailable (Android before 13) or failed to compile. */

// UI thread only. A driver/shader failure must not be retried on every transition.
internal var autoMixRevealFailed = false

private var cachedBlur: BlurSteps? = null

private class BlurSteps(val density: Float) {
    // Immutable effects are safe to share. Only instantiate the radii actually drawn.
    private val effects = arrayOfNulls<RenderEffect>(Tuning.BLUR_RADIUS_STEPS + 1)

    fun at(eased: Float): RenderEffect? {
        if (eased <= 0f || eased >= Tuning.BLUR_END_PROGRESS) return null
        val remaining = 1f - eased / Tuning.BLUR_END_PROGRESS
        val step = (remaining * remaining * Tuning.BLUR_RADIUS_STEPS).roundToInt()
        if (step == 0) return null
        return effects[step] ?: run {
            val radius = Tuning.BLUR_START_DP * density * step / Tuning.BLUR_RADIUS_STEPS
            BlurEffect(radius, radius, TileMode.Clamp).also { effects[step] = it }
        }
    }
}

private class IncomingMask {
    // Paints for the layer's whole life: only their shader or alpha moves per frame.
    val paint = Paint().apply { blendMode = BlendMode.DstIn }
    val fade = Paint()
}

/** [autoMixIncomingReveal] for a clip: the organic mask over the video's own drawing, without the blur. */
@Composable
fun Modifier.canvasOrganicReveal(reveal: CanvasOrganicReveal?): Modifier {
    if (reveal == null) return this
    val reduced = PlayerSettings.reduceAnimation.collectAsState()
    return this.then(autoMixIncomingReveal({ reveal.progress() }, { reduced.value }, reveal.generation, blurEnabled = false))
}

/** Only the incoming picture is isolated by the effect, never the outgoing one under it. */
@Composable
internal fun autoMixIncomingReveal(
    eased: () -> Float,
    reducedMotion: () -> Boolean,
    generation: Long,
    blurEnabled: Boolean,
): Modifier {
    val density = LocalDensity.current.density
    val blur = if (blurEnabled) remember(density) {
        cachedBlur?.takeIf { it.density == density } ?: BlurSteps(density).also { cachedBlur = it }
    } else null
    val mask = remember(generation) {
        if (autoMixRevealFailed) null else {
            val random = Random(generation)
            runCatching { autoMixMaskShader(random.nextFloat() * Tuning.NOISE_SEED_SPAN, random.nextFloat() * Tuning.NOISE_SEED_SPAN) }
                .onFailure { autoMixRevealFailed = true }.getOrNull()?.let { field -> field to IncomingMask() }
        }
    }
    val blurLayer = Modifier.graphicsLayer {
        renderEffect = if (blur == null || reducedMotion()) null else blur.at(eased())
    }
    if (mask == null) return Modifier.graphicsLayer { alpha = eased() }.then(blurLayer)
    val (field, layer) = mask
    return Modifier
        // The mask cuts this layer only: DstIn against the picture it holds.
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            val e = eased()
            if (e <= 0f) return@drawWithContent
            if (e >= 1f) {
                drawContent()
                return@drawWithContent
            }
            if (reducedMotion() || autoMixRevealFailed) {
                drawIntoCanvas { canvas ->
                    layer.fade.alpha = e
                    canvas.saveLayer(Rect(0f, 0f, size.width, size.height), layer.fade)
                    drawContent()
                    canvas.restore()
                }
                return@drawWithContent
            }
            drawContent()
            val shader = runCatching { field.shader(size.width, size.height, e) }
                .onFailure { autoMixRevealFailed = true }.getOrNull() ?: return@drawWithContent
            layer.paint.shader = shader
            drawIntoCanvas { it.drawRect(0f, 0f, size.width, size.height, layer.paint) }
        }
        .then(blurLayer)
}

private val ORGANIC_MASK_FIELD = """
    uniform float2 bounds;
    uniform float progress;
    uniform float2 seedOffset;

    float hashCell(float2 cell) {
        // Adjacent cells must agree on their shared corners. On Adreno the
        // amplified sine hash produced discontinuities at those lattice lines.
        // Mix the small integer-valued cell coordinates in a bounded range.
        float n = mod(cell.x * 127.0 + cell.y * 197.0, 251.0);
        n = mod(n * (n + 73.0) + cell.x * 97.0 + cell.y * 53.0, 251.0);
        n = mod(n * (n + 37.0) + cell.x * 31.0 + cell.y * 109.0, 251.0);
        return n / 251.0;
    }

    float valueNoise(float2 point) {
        float2 cell = floor(point);
        float2 f = fract(point);
        f = f * f * (3.0 - 2.0 * f);
        return mix(mix(hashCell(cell), hashCell(cell + float2(1.0, 0.0)), f.x),
                   mix(hashCell(cell + float2(0.0, 1.0)), hashCell(cell + float2(1.0)), f.x), f.y);
    }

    float revealCoverage(float2 xy) {
        // Keep the pattern broad over the whole artwork, even on tall or wide covers.
        // Translate the smooth field once per generation instead of warping its lattice.
        float2 point = xy / bounds * ${Tuning.NOISE_SCALE} + seedOffset;
        float noise = 0.0;
        float weight = 0.5;
        float total = 0.0;
        for (int i = 0; i < ${Tuning.NOISE_OCTAVES}; ++i) {
            noise += valueNoise(point) * weight;
            total += weight;
            point = point * 2.0 + float2(3.9, 5.1);
            weight *= 0.5;
        }
        float softness = ${Tuning.NOISE_SOFTNESS};
        float threshold = mix(1.0 + softness, -softness, progress);
        float field = clamp((noise / total - ${Tuning.NOISE_LOW}) / (${Tuning.NOISE_HIGH} - ${Tuning.NOISE_LOW}), 0.0, 1.0);
        return smoothstep(threshold - softness, threshold + softness, field);
    }
""".trimIndent()

/** Alpha-only field, drawn DstIn over the incoming picture. */
internal val AUTOMIX_MOTTLED_MASK = """
    $ORGANIC_MASK_FIELD
    half4 main(float2 xy) {
        return half4(0.0, 0.0, 0.0, revealCoverage(xy));
    }
""".trimIndent()

// Compiled once per process; the field is the same for every generation, only its seed moves.
private val effect by lazy { RuntimeEffect.makeForShader(AUTOMIX_MOTTLED_MASK) }
internal fun autoMixMaskShader(seedX: Float, seedY: Float): AutoMixMaskShader? = SkiaMask(seedX, seedY)

private class SkiaMask(seedX: Float, seedY: Float) : AutoMixMaskShader {
    private val builder = RuntimeShaderBuilder(effect).apply { uniform("seedOffset", seedX, seedY) }
    private var last: Shader? = null
    private var lastWidth = 0f
    private var lastHeight = 0f
    private var lastProgress = -1f

    // A Skia shader is immutable, so a moved field is a new one: one small native object a frame
    // while the reveal runs, released by Skia's cleaner (a recorded frame may still hold it).
    override fun shader(width: Float, height: Float, progress: Float): Shader {
        last?.takeIf { width == lastWidth && height == lastHeight && progress == lastProgress }?.let { return it }
        builder.uniform("bounds", width, height)
        builder.uniform("progress", progress)
        return builder.makeShader().toComposeShader().also {
            last = it
            lastWidth = width
            lastHeight = height
            lastProgress = progress
        }
    }
}
