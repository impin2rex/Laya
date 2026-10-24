package com.music.laya.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable

// ponytail: iOS picks status bar tint itself (UIViewControllerBasedStatusBarAppearance); no override yet.
@Composable
actual fun StatusBarIcons(dark: Boolean) = Unit

@Composable
actual fun HideStatusBar(hidden: Boolean) = Unit

@Composable
actual fun stableStatusBarInsets(): WindowInsets = WindowInsets.statusBars
