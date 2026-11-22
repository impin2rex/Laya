package com.music.laya.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shader
import java.nio.ByteBuffer

/** AGSL from Android 13; before that the same field is baked into a bitmap and thresholded by a colour filter. */
internal actual fun autoMixMaskShader(seedX: Float, seedY: Float): AutoMixMaskShader? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AgslMask(seedX, seedY) else BakedMask(seedX, seedY)

/** Compose's BlurEffect needs RenderEffect, API 31; below it the reveal crossfades from a soft stand-in. */
internal actual val blurEffectSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

private const val RAMP_STEPS = 128

// The ramp's filters are the same for every layer: built once, on first use of each step.
private val rampFilters = arrayOfNulls<ColorFilter>(RAMP_STEPS + 1)

private fun rampFilter(progress: Float): ColorFilter {
    val step = (progress.coerceIn(0f, 1f) * RAMP_STEPS).toInt()
    return rampFilters[step] ?: run {
        val (scale, bias) = autoMixRamp(step / RAMP_STEPS.toFloat())
        // Alpha out = alpha in * scale + bias; the colour is irrelevant to a DstIn mask.
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, scale, bias * 255f,
                ),
            ),
        ).also { rampFilters[step] = it }
    }
}

/** The field as an alpha-only bitmap, one 16 KB decode-free allocation per generation. */
private class BakedMask(seedX: Float, seedY: Float) : AutoMixMaskShader {
    private val shader = BitmapShader(
        Bitmap.createBitmap(AUTOMIX_FIELD_SIZE, AUTOMIX_FIELD_SIZE, Bitmap.Config.ALPHA_8).also {
            it.copyPixelsFromBuffer(ByteBuffer.wrap(autoMixField(seedX, seedY)))
        },
        android.graphics.Shader.TileMode.CLAMP,
        android.graphics.Shader.TileMode.CLAMP,
    )
    private val matrix = Matrix()
    private var appliedWidth = 0f
    private var appliedHeight = 0f

    override fun shader(width: Float, height: Float, progress: Float): Shader {
        if (width != appliedWidth || height != appliedHeight) {
            matrix.setScale(width / AUTOMIX_FIELD_SIZE, height / AUTOMIX_FIELD_SIZE)
            shader.setLocalMatrix(matrix)
            appliedWidth = width
            appliedHeight = height
        }
        return shader
    }

    override fun colorFilter(progress: Float): ColorFilter = rampFilter(progress)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslMask(seedX: Float, seedY: Float) : AutoMixMaskShader {
    private val mask = RuntimeShader(AUTOMIX_MOTTLED_MASK).apply { setFloatUniform("seedOffset", seedX, seedY) }
    private var appliedWidth = 0f
    private var appliedHeight = 0f
    private var appliedProgress = -1f

    override fun shader(width: Float, height: Float, progress: Float): Shader {
        // Each uniform write discards Android's native shader instance; skip unchanged ones.
        if (width != appliedWidth || height != appliedHeight) {
            mask.setFloatUniform("bounds", width, height)
            appliedWidth = width
            appliedHeight = height
        }
        if (progress != appliedProgress) {
            mask.setFloatUniform("progress", progress)
            appliedProgress = progress
        }
        return mask
    }
}
