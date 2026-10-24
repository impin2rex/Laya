package com.music.laya.ui.components

import androidx.compose.ui.Modifier

// No secondary button on a touchscreen; long-press has its own path.
actual fun Modifier.contextClick(onClick: (() -> Unit)?): Modifier = this
