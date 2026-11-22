package com.music.laya.ui.player

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Shader

/** AGSL from Android 13; before that the reveal is a blurred fade. */
internal actual fun autoMixMaskShader(seedX: Float, seedY: Float): AutoMixMaskShader? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AgslMask(seedX, seedY) else null

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
