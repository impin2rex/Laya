package com.music.laya.data

import android.content.Context
import androidx.startup.Initializer
import com.music.laya.data.settings.TvSettings
import com.music.laya.ui.player.CanvasPlaybackLimits
import com.music.laya.ui.tv.DeviceType

/**
 * Runs once at process start, before any activity or the playback service, so
 * the TV-only hooks into the shared code are in place whichever of them gets
 * there first. Registered in the TV manifest.
 */
class TvStartup : Initializer<Unit> {
    override fun create(context: Context) {
        TvSettings.init(context)
        TvUsbMusic.install()
        // A looping canvas clip at 24 fps is plenty behind the player and spares the TV GPU.
        if (DeviceType.isTv(context)) CanvasPlaybackLimits.maxFrameRate = 24
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
