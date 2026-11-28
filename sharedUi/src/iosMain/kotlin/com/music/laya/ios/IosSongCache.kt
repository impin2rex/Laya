package com.music.laya.ios

import com.music.laya.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The song cache, which Swift's StreamCache keeps: the limit setting is pushed to it from here. */
internal object IosSongCache {
    private var engine: AudioEngine? = null

    fun install(engine: AudioEngine, scope: CoroutineScope) {
        this.engine = engine
        scope.launch { AppSettings.audioCacheLimitBytes.collect { engine.setCacheLimit(it) } }
    }

    fun clear() {
        engine?.clearCache()
    }
}
