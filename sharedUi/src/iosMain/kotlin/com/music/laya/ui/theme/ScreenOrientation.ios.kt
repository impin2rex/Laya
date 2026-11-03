package com.music.laya.ui.theme

import androidx.compose.runtime.Composable

private object NoOrientation : ScreenOrientation {
    override fun landscape() = Unit
    override fun portrait() = Unit
}

// ponytail: iOS rotates with the device; forcing it needs a UIKit geometry request, add when asked.
@Composable
actual fun rememberScreenOrientation(): ScreenOrientation = NoOrientation
