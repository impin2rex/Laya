package com.music.laya.ui.components.backdrop.internal

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asSkiaColorFilter
import com.music.laya.ui.components.backdrop.SkiaRuntimeShader
import com.music.laya.ui.components.backdrop.RuntimeShader
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.MaskFilter

internal actual fun RenderEffect?.chain(other: RenderEffect): RenderEffect =
    if (this != null) {
        ImageFilter.makeCompose(other.asSkiaImageFilter(), this.asSkiaImageFilter()).asComposeRenderEffect()
    } else {
        other
    }

internal actual fun RuntimeShaderEffect(runtimeShader: RuntimeShader, uniformShaderName: String): RenderEffect =
    ImageFilter.makeRuntimeShader((runtimeShader as SkiaRuntimeShader).builder, uniformShaderName, null)
        .asComposeRenderEffect()

internal actual fun ColorFilterEffect(renderEffect: RenderEffect?, colorFilter: ColorFilter): RenderEffect =
    ImageFilter.makeColorFilter(colorFilter.asSkiaColorFilter(), renderEffect?.asSkiaImageFilter(), null)
        .asComposeRenderEffect()

internal actual fun Paint.blur(radius: Float) {
    // Android's BlurMaskFilter radius -> Skia sigma, as Skia itself converts it.
    asFrameworkPaint().maskFilter =
        if (radius > 0f) MaskFilter.makeBlur(FilterBlurMode.NORMAL, 0.57735f * radius + 0.5f) else null
}

internal actual fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?) {
    asFrameworkPaint().shader = (runtimeShader as SkiaRuntimeShader?)?.builder?.makeShader()
}
