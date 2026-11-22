package com.music.laya.ui.player

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.graphics.asAndroidColorFilter
import com.music.laya.playback.AutoMixArtworkTuning as Tuning
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The organic AutoMix reveal for a plain Android view (the Canvas video's TextureView), where
 * Compose's own layer modifiers do not reach the texture: the view's children are drawn into a
 * layer, which the same field the stills use then cuts with DstIn. API 33 uses the AGSL field,
 * older devices the baked one, so the video dissolves as the covers do on every supported API.
 * One instance per mix; its paint and shaders live as long as it does. Main thread only.
 */
class AutoMixLayerMask(generation: Long) {
    private val field: AutoMixMaskShader? = run {
        val random = Random(generation)
        val seedX = random.nextFloat() * Tuning.NOISE_SEED_SPAN
        val seedY = random.nextFloat() * Tuning.NOISE_SEED_SPAN
        runCatching { autoMixMaskShader(seedX, seedY) }.getOrNull()
    }
    private val paint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        isFilterBitmap = true
    }
    private var failed = false

    /** Opens the layer; hand the result to [end] once the content has been drawn. */
    fun begin(canvas: Canvas, width: Float, height: Float, progress: Float): Int =
        if (field == null || failed) canvas.saveLayerAlpha(0f, 0f, width, height, (progress * 255f).roundToInt())
        else canvas.saveLayer(0f, 0f, width, height, null)

    fun end(canvas: Canvas, layer: Int, width: Float, height: Float, progress: Float) {
        val mask = field
        if (mask != null && !failed) {
            runCatching {
                paint.shader = mask.shader(width, height, progress)
                paint.colorFilter = mask.colorFilter(progress)?.asAndroidColorFilter()
                canvas.drawRect(0f, 0f, width, height, paint)
            }.onFailure { failed = true }
        }
        canvas.restoreToCount(layer)
    }
}
