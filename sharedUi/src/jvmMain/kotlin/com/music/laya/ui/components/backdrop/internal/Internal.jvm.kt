package com.music.laya.ui.components.backdrop.internal

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RenderEffect
import com.music.laya.ui.components.backdrop.RuntimeShader

// Never reached: the desktop reports isRenderEffectSupported() false, see Backdrop.jvm.kt.
internal actual fun RenderEffect?.chain(other: RenderEffect): RenderEffect = other

internal actual fun RuntimeShaderEffect(runtimeShader: RuntimeShader, uniformShaderName: String): RenderEffect =
    error("unsupported")

internal actual fun ColorFilterEffect(renderEffect: RenderEffect?, colorFilter: ColorFilter): RenderEffect =
    error("unsupported")

internal actual fun Paint.blur(radius: Float) {}

internal actual fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?) {}
