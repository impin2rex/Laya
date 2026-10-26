package com.music.laya.ui.components.backdrop

// The desktop draws no backdrop glass (it uses Haze), so every effect reports itself off
// and the bodies below are never reached.
actual fun isRenderEffectSupported(): Boolean = false

actual fun isRuntimeShaderSupported(): Boolean = false

actual fun RuntimeShader(shaderString: String): RuntimeShader = error("Backdrop shaders are not used on the desktop")
