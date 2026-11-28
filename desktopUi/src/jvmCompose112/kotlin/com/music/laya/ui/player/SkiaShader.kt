package com.music.laya.ui.player

import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.asComposeShader

/** Compose 1.12 (macOS, Linux): skia's Shader wrapped in Compose's own. */
internal fun org.jetbrains.skia.Shader.toComposeShader(): Shader = asComposeShader()
