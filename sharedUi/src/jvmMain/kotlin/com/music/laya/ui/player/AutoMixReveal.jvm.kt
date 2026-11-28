package com.music.laya.ui.player

import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.asComposeShader
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

// Compiled once per process; the field is the same for every generation, only its seed moves.
private val effect by lazy { RuntimeEffect.makeForShader(AUTOMIX_MOTTLED_MASK) }

internal actual fun autoMixMaskShader(seedX: Float, seedY: Float): AutoMixMaskShader? = SkiaMask(seedX, seedY)

internal actual val blurEffectSupported: Boolean = true

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
        return builder.makeShader().asComposeShader().also {
            last = it
            lastWidth = width
            lastHeight = height
            lastProgress = progress
        }
    }
}

/** Never hardware-backed here. */
internal actual fun softwareDrawable(image: coil3.Image): coil3.Image = image
