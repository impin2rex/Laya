package com.music.laya.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.music.laya.playback.AutoMixArtworkTuning as Tuning
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The incoming AutoMix artwork's organic reveal: a soft value-noise field that the picture grows
 * through, and a blur that sharpens as it lands — BitChord's AndroidAutoMixReveal, made shared so
 * iOS and the desktop draw the same field. The field is one SkSL source; Android runs it as AGSL
 * (API 33+), the other platforms through Skia's RuntimeEffect.
 */
internal interface AutoMixMaskShader {
    /** The field at [progress] over a [width] x [height] layer. May return the same, updated, instance. */
    fun shader(width: Float, height: Float, progress: Float): Shader

    /** A filter the mask's paint applies on top of [shader] for [progress], for masks that bake the field. */
    fun colorFilter(progress: Float): ColorFilter? = null
}

/** Null where the platform cannot draw the field at all or it failed to compile. Android before 13 bakes it. */
internal expect fun autoMixMaskShader(seedX: Float, seedY: Float): AutoMixMaskShader?

/** Whether [BlurEffect] does anything here: it is a no-op on Android before 12. */
internal expect val blurEffectSupported: Boolean

/** [image] as one a software canvas can draw ([softCoverOf]): Android's hardware bitmaps copied, anything else as is. */
internal expect fun softwareDrawable(image: coil3.Image): coil3.Image

/** How much of the soft stand-in still shows at [eased]: the blur's own quadratic fall-off, gone at its end. */
internal fun softCoverAlpha(eased: Float): Float {
    val remaining = (1f - eased / Tuning.BLUR_END_PROGRESS).coerceIn(0f, 1f)
    return remaining * remaining
}

/**
 * A tiny, cover-cropped copy of [painter]. Drawn stretched over the layer with bilinear filtering
 * it reads as a heavy blur, so the blur-to-sharp step needs no RenderEffect. Built once per cover.
 */
internal fun softCoverOf(painter: Painter): ImageBitmap {
    val side = SOFT_COVER_PX
    val bitmap = ImageBitmap(side, side)
    val source = painter.intrinsicSize.takeIf { it.isSpecified && it.width > 0f && it.height > 0f } ?: Size(1f, 1f)
    val scale = maxOf(side / source.width, side / source.height)
    val w = source.width * scale
    val h = source.height * scale
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(side.toFloat(), side.toFloat())) {
        translate((side - w) / 2f, (side - h) / 2f) { with(painter) { draw(Size(w, h)) } }
    }
    return bitmap
}

private const val SOFT_COVER_PX = 40

/** Draws [soft] over the content at [eased]'s remaining blur. Allocation-free: inline sizes, no objects. */
private fun DrawScope.drawSoftCover(soft: ImageBitmap?, eased: Float) {
    if (soft == null) return
    val alpha = softCoverAlpha(eased)
    if (alpha <= 0f) return
    drawImage(
        soft,
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

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
    val paint = Paint().apply {
        blendMode = BlendMode.DstIn
        filterQuality = FilterQuality.Low // a baked field is stretched over the layer
    }
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
    /** [softCoverOf] the picture, for platforms where the blur effect does nothing; null elsewhere. */
    soft: ImageBitmap? = null,
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
    if (mask == null) {
        return Modifier.graphicsLayer { alpha = eased() }.then(blurLayer).then(
            if (soft == null) Modifier else Modifier.drawWithContent {
                drawContent()
                if (!reducedMotion()) drawSoftCover(soft, eased())
            },
        )
    }
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
            drawSoftCover(soft, e)
            val shader = runCatching { field.shader(size.width, size.height, e) }
                .onFailure { autoMixRevealFailed = true }.getOrNull() ?: return@drawWithContent
            layer.paint.shader = shader
            layer.paint.colorFilter = field.colorFilter(e)
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

/** Side of the baked field the platforms without runtime shaders stretch over the layer. */
internal const val AUTOMIX_FIELD_SIZE = 128

private fun glMod(x: Float, y: Float) = x - y * floor(x / y)

private fun hashCell(x: Float, y: Float): Float {
    var n = glMod(x * 127f + y * 197f, 251f)
    n = glMod(n * (n + 73f) + x * 97f + y * 53f, 251f)
    n = glMod(n * (n + 37f) + x * 31f + y * 109f, 251f)
    return n / 251f
}

private fun valueNoise(px: Float, py: Float): Float {
    val cx = floor(px)
    val cy = floor(py)
    var fx = px - cx
    var fy = py - cy
    fx = fx * fx * (3f - 2f * fx)
    fy = fy * fy * (3f - 2f * fy)
    val top = hashCell(cx, cy) + (hashCell(cx + 1f, cy) - hashCell(cx, cy)) * fx
    val bottom = hashCell(cx, cy + 1f) + (hashCell(cx + 1f, cy + 1f) - hashCell(cx, cy + 1f)) * fx
    return top + (bottom - top) * fy
}

/**
 * [ORGANIC_MASK_FIELD]'s noise, normalised to 0..255 over a [size] square: the same field the
 * runtime shader draws, baked once per generation for Android before 13, which has no
 * RuntimeShader. [autoMixRamp] then does what the shader's threshold does, per frame.
 */
internal fun autoMixField(seedX: Float, seedY: Float, size: Int = AUTOMIX_FIELD_SIZE): ByteArray {
    val out = ByteArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        var px = (x + 0.5f) / size * Tuning.NOISE_SCALE + seedX
        var py = (y + 0.5f) / size * Tuning.NOISE_SCALE + seedY
        var noise = 0f
        var weight = 0.5f
        var total = 0f
        repeat(Tuning.NOISE_OCTAVES) {
            noise += valueNoise(px, py) * weight
            total += weight
            px = px * 2f + 3.9f
            py = py * 2f + 5.1f
            weight *= 0.5f
        }
        val field = ((noise / total - Tuning.NOISE_LOW) / (Tuning.NOISE_HIGH - Tuning.NOISE_LOW)).coerceIn(0f, 1f)
        out[y * size + x] = (field * 255f + 0.5f).toInt().toByte()
    }
    return out
}

/**
 * The shader's coverage `smoothstep(threshold - s, threshold + s, field)` as the linear ramp
 * `field * scale + bias` (clamped by the pipeline): what a colour filter on the baked field can do.
 */
internal fun autoMixRamp(progress: Float): Pair<Float, Float> {
    val softness = Tuning.NOISE_SOFTNESS
    val threshold = (1f + softness) - (1f + 2f * softness) * progress.coerceIn(0f, 1f)
    val scale = 1f / (2f * softness)
    return scale to -(threshold - softness) * scale
}
