package com.music.laya.ui.screens

import androidx.compose.runtime.Composable

/** System Back while [enabled]; a no-op where there is no Back (iOS, desktop). */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
