package com.music.laya.ios

import coil3.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.roundToInt
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * Port of Android's SignatureInkTransformation (same constants and cache key): scanned dark ink on
 * white paper becomes white strokes on transparency; images that don't read as scans pass through.
 */
internal object IosSignatureInk : Transformation() {
    override val cacheKey = "signature-ink-v1"
    private const val PAPER_LUMINANCE = 0.92f
    private const val SCAN_WHITE_FRACTION = 0.55f

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        if (w <= 0 || h <= 0) return input
        val src = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val px = input.readPixels(src, w * 4) ?: return input
        fun lum(o: Int) = (0.299f * (px[o].toInt() and 0xFF) + 0.587f * (px[o + 1].toInt() and 0xFF) +
            0.114f * (px[o + 2].toInt() and 0xFF)) / 255f
        var paper = 0
        for (i in 0 until w * h) if (lum(i * 4) >= PAPER_LUMINANCE) paper++
        if (paper.toFloat() / (w * h) < SCAN_WHITE_FRACTION) return input

        val out = ByteArray(px.size)
        for (i in 0 until w * h) {
            val o = i * 4
            val inAlpha = (px[o + 3].toInt() and 0xFF) / 255f
            val darkness = 1f - lum(o)
            if (inAlpha <= 0f || darkness <= 0f) continue
            val a = (darkness * inAlpha * 255f).roundToInt().coerceIn(0, 255).toByte()
            // premultiplied white: rgb == alpha
            out[o] = a; out[o + 1] = a; out[o + 2] = a; out[o + 3] = a
        }
        val dst = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        return Bitmap().also { it.allocPixels(dst); it.installPixels(dst, out, w * 4) }
    }
}
