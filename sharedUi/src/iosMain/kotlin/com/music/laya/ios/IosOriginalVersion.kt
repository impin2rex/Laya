package com.music.laya.ios

import com.russhwolf.settings.NSUserDefaultsSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSUserDefaults

/**
 * The tracks the listener sent back to YouTube's own audio by hand and wants kept there (Android's OriginalVersion):
 * no source is asked about them again until "Upgrade quality" clears the pin. Newline-joined in NSUserDefaults, oldest first.
 */
internal object IosOriginalVersion {
    private const val KEY = "original_version_pinned"
    private const val MAX_PINNED = 500
    private val prefs = NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults)
    private val _pinned = MutableStateFlow(prefs.getString(KEY, "").split('\n').filter { it.isNotBlank() }.toCollection(LinkedHashSet()) as Set<String>)
    val pinned: StateFlow<Set<String>> = _pinned

    fun isPinned(videoId: String) = videoId in _pinned.value

    fun pin(videoId: String) = save((_pinned.value - videoId + videoId).toList().takeLast(MAX_PINNED).toSet())

    fun unpin(videoId: String) = if (isPinned(videoId)) save(_pinned.value - videoId) else Unit

    private fun save(ids: Set<String>) {
        _pinned.value = ids
        prefs.putString(KEY, ids.joinToString("\n"))
    }
}
