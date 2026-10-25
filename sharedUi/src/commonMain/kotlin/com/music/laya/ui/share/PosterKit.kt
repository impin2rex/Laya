package com.music.laya.ui.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.music.laya.data.model.CARD_ART_PX
import com.music.laya.data.model.artworkAt
import com.music.laya.ui.graphics.ColorUtils
import com.music.laya.ui.graphics.forPixelAccess
import com.music.laya.ui.graphics.paletteSwatches
import com.music.laya.ui.graphics.toImageBitmap
import kotlin.math.min

/**
 * What the share sheets need from the platform: turning a finished picture into something the
 * system can send, and putting it in the photo library. The drawing itself is common code.
 */
interface ImageSharer {
    /** Opens the system share sheet on [image]. False if the picture could not be handed over. */
    suspend fun share(image: ImageBitmap, fileName: String): Boolean

    /** Adds [image] to the photo library. False if that was refused or failed. */
    suspend fun saveToPhotos(image: ImageBitmap, name: String): Boolean
}

/** Null where nothing can take a picture (so the Share/Save UI is left out). */
val LocalImageSharer = staticCompositionLocalOf<ImageSharer?> { null }

/**
 * An Android `Paint` for text, as a value: a face, a size in pixels, a colour, a tracking and
 * an alignment. The cards are laid out in 1080-wide pixels exactly as the phone's canvas
 * renderer does, so the numbers carry over unchanged.
 */
internal class Ink(
    val style: TextStyle,
    val right: Boolean = false,
) {
    val size: Float get() = style.fontSize.value
    fun toRight() = Ink(style, right = true)
}

/** The three faces the cards use: Heavy for headings, Semibold for emphasis, Regular for the rest. */
internal class Pen(val measurer: TextMeasurer, private val family: FontFamily) {
    private fun ink(weight: FontWeight, size: Float, color: Color, tracking: Float = 0f) = Ink(
        TextStyle(
            fontFamily = family,
            fontWeight = weight,
            fontSize = size.sp,
            color = color,
            letterSpacing = tracking.em,
        ),
    )

    fun heading(size: Float, color: Color, tracking: Float = 0f) = ink(FontWeight(800), size, color, tracking)

    fun body(size: Float, color: Color, bold: Boolean = false) =
        ink(if (bold) FontWeight(600) else FontWeight(400), size, color)

    fun label(size: Float, color: Color, tracking: Float = 0.10f) = ink(FontWeight(600), size, color, tracking)

    fun width(text: String, ink: Ink): Float =
        measurer.measure(text, ink.style, softWrap = false, maxLines = 1).size.width.toFloat()

    /** Distance from the top of a line to its baseline: Android's `-paint.ascent()`. */
    fun ascent(ink: Ink): Float = measurer.measure("Hg", ink.style, softWrap = false, maxLines = 1).firstBaseline

    /** Below-baseline extent: Android's `paint.descent()`. */
    fun descent(ink: Ink): Float {
        val layout = measurer.measure("Hg", ink.style, softWrap = false, maxLines = 1)
        return layout.size.height - layout.firstBaseline
    }

    /** How many leading characters of [text] fit in [width] (Android's `breakText`). */
    fun breakText(text: String, ink: Ink, width: Float): Int {
        if (width(text, ink) <= width) return text.length
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (width(text.substring(0, mid), ink) <= width) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun ellipsised(text: String, ink: Ink, width: Float): String {
        if (width(text, ink) <= width) return text
        var end = text.length
        while (end > 1 && width(text.take(end) + "…", ink) > width) end--
        return text.take(end).trimEnd() + "…"
    }
}

/** Text with its baseline at [baseline]; right-aligned inks end at [x] instead of starting there. */
internal fun DrawScope.text(pen: Pen, text: String, x: Float, baseline: Float, ink: Ink) {
    val layout = pen.measurer.measure(text, ink.style, softWrap = false, maxLines = 1)
    val left = if (ink.right) x - layout.size.width else x
    drawText(layout, topLeft = Offset(left, baseline - layout.firstBaseline))
}

/** Draws into a fresh [width] x [height] bitmap, with one pixel per unit. */
internal fun drawBitmap(width: Int, height: Int, block: DrawScope.() -> Unit): ImageBitmap {
    val bitmap = ImageBitmap(width, height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat()), block)
    return bitmap
}

/** [argb] as `0xAARRGGBB`, the way the phone's card code spells its colours. */
internal fun argb(value: Long) = Color(value)

// ── Artwork ────────────────────────────────────────────────────────────────

/** The sleeve at [url], decoded for pixel access, or null if it could not be loaded. */
internal suspend fun loadArtwork(context: PlatformContext, url: String): ImageBitmap? = runCatching {
    val request = ImageRequest.Builder(context)
        .data(url.artworkAt(CARD_ART_PX))
        .forPixelAccess()
        .build()
    (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toImageBitmap()
}.getOrNull()

/** A square of [bitmap] (centre-cropped) in a rounded or round frame, or a flat colour keyed off [fallback]. */
internal fun DrawScope.artwork(
    bitmap: ImageBitmap?,
    fallback: String,
    x: Float,
    y: Float,
    size: Float,
    circular: Boolean,
) {
    val radius = if (circular) size / 2f else size * 0.10f
    val path = Path().apply {
        addRoundRect(RoundRect(Rect(x, y, x + size, y + size), CornerRadius(radius)))
    }
    if (bitmap != null) {
        val side = min(bitmap.width, bitmap.height)
        clipPath(path) {
            drawImage(
                image = bitmap,
                srcOffset = IntOffset((bitmap.width - side) / 2, (bitmap.height - side) / 2),
                srcSize = IntSize(side, side),
                dstOffset = IntOffset(x.toInt(), y.toInt()),
                dstSize = IntSize(size.toInt(), size.toInt()),
                filterQuality = FilterQuality.High,
            )
        }
    } else {
        val hue = (fallback.hashCode().toFloat() % 360f + 360f) % 360f
        drawPath(path, Color(ColorUtils.HSLToColor(floatArrayOf(hue, 0.55f, 0.45f))))
    }
}

/** The ic_logo mark, tinted, with its right edge at [right] and its baseline at [baseline]. */
internal fun DrawScope.logo(painter: Painter?, right: Float, baseline: Float) {
    painter ?: return
    translate(right - LOGO_W, baseline - LOGO_H) {
        with(painter) { draw(Size(LOGO_W, LOGO_H), colorFilter = ColorFilter.tint(argb(0xE6FFFFFF))) }
    }
}

internal const val LOGO_W = 34f
internal const val LOGO_H = 44f

// ── Backdrop ───────────────────────────────────────────────────────────────

/**
 * The four colours of [lead]'s artwork (androidx Palette's default filter, most-populous first,
 * topped up from a fixed set), pushed to the saturation and lightness the cards want.
 */
internal fun paletteOf(lead: ImageBitmap?): List<Int> {
    val fallback = listOf(0xFF3A1C71.toInt(), 0xFFD76D77.toInt(), 0xFF2B5876.toInt(), 0xFFFFAF7B.toInt())
    val swatches = lead?.let {
        runCatching { paletteSwatches(it, 24, false).sortedByDescending { s -> s.population }.map { s -> s.rgb } }
            .getOrNull()
    }.orEmpty()
    if (swatches.isEmpty()) return fallback
    return (swatches + fallback).take(4).map(::tuned)
}

private fun hsl(color: Int) = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }

internal fun rotated(color: Int, degrees: Float): Int {
    if (degrees == 0f) return color
    val hsl = hsl(color)
    hsl[0] = (hsl[0] + degrees) % 360f
    return ColorUtils.HSLToColor(hsl)
}

private fun tuned(color: Int): Int {
    val hsl = hsl(color)
    hsl[1] = (hsl[1] * 1.35f).coerceAtMost(1f)
    hsl[2] = hsl[2].coerceIn(0.28f, 0.58f)
    return ColorUtils.HSLToColor(hsl)
}

private fun dimmed(color: Int): Int {
    val hsl = hsl(color)
    hsl[2] = 0.10f
    return ColorUtils.HSLToColor(hsl)
}

private fun withAlpha(color: Int, alpha: Int) = Color(color).copy(alpha = alpha / 255f)

/** The cards' shared wash: four soft blobs of the artwork's colours over a near-black ground, shaded top to bottom. */
internal fun DrawScope.backdrop(lead: ImageBitmap?, hue: Float, width: Float, height: Float) {
    val colors = paletteOf(lead).map { rotated(it, hue) }
    drawRect(Color(dimmed(colors.first())), Offset.Zero, Size(width, height))

    val anchors = listOf(0.20f to 0.16f, 0.84f to 0.22f, 0.76f to 0.66f, 0.18f to 0.78f)
    colors.forEachIndexed { index, color ->
        val (fx, fy) = anchors[index]
        val center = Offset(width * fx, height * fy)
        val radius = width * 0.95f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(withAlpha(color, 210), withAlpha(color, 0)),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
    drawRect(
        brush = Brush.verticalGradient(
            0f to argb(0x8C000000),
            0.42f to argb(0x59000000),
            1f to argb(0xCC000000),
            startY = 0f,
            endY = height,
        ),
        size = Size(width, height),
    )
}

/** The device-wide mark most cards carry: [pen]'s "Laya" with the logo to its left. */
internal fun DrawScope.brandMark(pen: Pen, painter: Painter?, right: Float, baseline: Float) {
    val brand = pen.heading(46f, argb(0xE6FFFFFF)).toRight()
    text(pen, "Laya", right, baseline, brand)
    logo(painter, right - pen.width("Laya", brand) - 20f, baseline)
}
