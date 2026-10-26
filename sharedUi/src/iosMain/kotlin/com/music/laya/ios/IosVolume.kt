package com.music.laya.ios

import com.music.laya.ui.player.SystemVolume
import kotlinx.coroutines.flow.MutableStateFlow

/** The Now Playing volume bar's system volume: [IosPlayer] feeds [level] from the engine and wires [setter] to it. */
internal object IosVolume : SystemVolume {
    override val level = MutableStateFlow(1f)
    var setter: ((Float) -> Unit)? = null
    override fun set(level: Float) { this.level.value = level; setter?.invoke(level) }
    override fun refresh() = Unit
}
