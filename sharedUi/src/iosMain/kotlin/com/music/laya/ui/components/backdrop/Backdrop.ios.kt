package com.music.laya.ui.components.backdrop

import androidx.compose.ui.graphics.Color
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

// Skia has both natively; AGSL is SkSL, so the Android shader sources run unchanged.
actual fun isRenderEffectSupported(): Boolean = true

actual fun isRuntimeShaderSupported(): Boolean = true

actual fun RuntimeShader(shaderString: String): RuntimeShader =
    SkiaRuntimeShader(RuntimeShaderBuilder(RuntimeEffect.makeForShader(shaderString)))

internal class SkiaRuntimeShader(val builder: RuntimeShaderBuilder) : RuntimeShader {
    override fun setFloatUniform(name: String, value: Float) = builder.uniform(name, value)
    override fun setFloatUniform(name: String, value1: Float, value2: Float) = builder.uniform(name, value1, value2)
    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float) =
        builder.uniform(name, value1, value2, value3)
    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float) =
        builder.uniform(name, value1, value2, value3, value4)
    override fun setFloatUniform(name: String, values: FloatArray) = builder.uniform(name, values)

    override fun setIntUniform(name: String, value: Int) = builder.uniform(name, value)
    override fun setIntUniform(name: String, value1: Int, value2: Int) = builder.uniform(name, value1, value2)
    override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int) =
        builder.uniform(name, value1, value2, value3)
    override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int, value4: Int) =
        builder.uniform(name, value1, value2, value3, value4)
    override fun setIntUniform(name: String, values: IntArray) = when (values.size) {
        1 -> builder.uniform(name, values[0])
        2 -> builder.uniform(name, values[0], values[1])
        3 -> builder.uniform(name, values[0], values[1], values[2])
        else -> builder.uniform(name, values[0], values[1], values[2], values[3])
    }

    override fun setColorUniform(name: String, color: Color) =
        builder.uniform(name, color.red, color.green, color.blue, color.alpha)
}
