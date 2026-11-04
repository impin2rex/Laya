package com.music.laya.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import platform.UIKit.UIApplication
import platform.UIKit.UIInterfaceOrientationMask
import platform.UIKit.UIInterfaceOrientationMaskLandscape
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UIWindowScene
import platform.UIKit.UIWindowSceneGeometryPreferencesIOS

/**
 * The window scene asked to turn, as YouTube does on an iPhone. A nudge, as on Android: the app
 * still supports every orientation it lists, so turning the phone afterwards turns the screen.
 */
private class SceneOrientation : ScreenOrientation {
    /** Turned to landscape here and not back yet: put back to portrait on the way out. */
    var turned = false

    override fun landscape() {
        turned = true
        request(UIInterfaceOrientationMaskLandscape)
    }

    override fun portrait() {
        turned = false
        request(UIInterfaceOrientationMaskPortrait)
    }

    private fun request(mask: UIInterfaceOrientationMask) {
        val scene = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>().firstOrNull() ?: return
        scene.requestGeometryUpdateWithPreferences(UIWindowSceneGeometryPreferencesIOS(mask)) { _ -> }
    }
}

@Composable
actual fun rememberScreenOrientation(): ScreenOrientation {
    val orientation = remember { SceneOrientation() }
    DisposableEffect(orientation) { onDispose { if (orientation.turned) orientation.portrait() } }
    return orientation
}
