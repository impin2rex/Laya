package com.music.laya.ui.player

import android.view.Window

/** [PlayerSheetMotion.attachWindow] for the sheet's Android [Window]. */
fun PlayerSheetMotion.attachWindow(window: Window?) {
    window ?: return
    val saved = window.attributes.windowAnimations
    attachWindow { quiet -> window.setWindowAnimations(if (quiet) 0 else saved) }
}
