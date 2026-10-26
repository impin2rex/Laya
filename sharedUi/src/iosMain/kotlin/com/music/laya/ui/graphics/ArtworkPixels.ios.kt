package com.music.laya.ui.graphics

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.Image
import coil3.request.ImageRequest
import coil3.toBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

// Skia bitmaps are always CPU-readable.
actual fun ImageRequest.Builder.forPixelAccess(): ImageRequest.Builder = this

actual fun Image.toImageBitmap(): ImageBitmap = toBitmap().asComposeImageBitmap()

actual fun imageBitmapOf(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
    // BGRA_8888 little-endian (arm64) is ARGB as an int, byte for byte.
    val bytes = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        val p = pixels[i]
        bytes[i * 4] = p.toByte()
        bytes[i * 4 + 1] = (p shr 8).toByte()
        bytes[i * 4 + 2] = (p shr 16).toByte()
        bytes[i * 4 + 3] = (p shr 24).toByte()
    }
    bitmap.installPixels(bytes)
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}

actual fun paletteSwatches(image: ImageBitmap, maxColors: Int, clearFilters: Boolean): List<Swatch> =
    PaletteQuantizer.swatches(image, maxColors, clearFilters)
