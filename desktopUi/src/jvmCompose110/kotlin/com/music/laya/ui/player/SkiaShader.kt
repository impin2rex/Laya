package com.music.laya.ui.player

import androidx.compose.ui.graphics.Shader

/** Compose 1.10 (the Windows build): Compose's Shader is skia's, so there is nothing to convert. */
internal fun org.jetbrains.skia.Shader.toComposeShader(): Shader = this
