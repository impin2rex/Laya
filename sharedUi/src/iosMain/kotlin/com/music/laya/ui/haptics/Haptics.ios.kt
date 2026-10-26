package com.music.laya.ui.haptics

import androidx.compose.runtime.Composable
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle

actual class Haptics {
    // ponytail: one impact per haptic, no multi-beat patterns; CHHapticEngine when the patterns matter.
    actual fun play(haptic: Haptic) {
        val style = when (haptic) {
            Haptic.Tick -> UIImpactFeedbackStyle.UIImpactFeedbackStyleLight
            Haptic.Tap, Haptic.Select, Haptic.ToggleOff, Haptic.SkipPrevious, Haptic.Pause ->
                UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium
            Haptic.ToggleOn, Haptic.SkipNext, Haptic.Resume, Haptic.Expand ->
                UIImpactFeedbackStyle.UIImpactFeedbackStyleRigid
        }
        UIImpactFeedbackGenerator(style).impactOccurred()
    }
}

private val IosHaptics = Haptics()

@Composable
actual fun rememberHaptics(): Haptics = IosHaptics
