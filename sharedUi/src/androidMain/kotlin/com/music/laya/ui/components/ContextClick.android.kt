package com.music.laya.ui.components

import androidx.compose.ui.Modifier

actual fun Modifier.contextClick(onClick: (() -> Unit)?): Modifier = this
