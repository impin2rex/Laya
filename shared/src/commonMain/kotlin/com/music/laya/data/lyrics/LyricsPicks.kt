package com.music.laya.data.lyrics

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The provider picked by hand in the player's lyrics drawer, per track, for as long as the app runs.
 *
 * Every player keys its lyrics on the playing track, so leaving a song (for its music video, or for
 * another track) and coming back used to drop the pick and settle on the automatic choice again.
 * Kept here instead, where each platform's player and the lock screen's own lookup read it.
 */
object LyricsPicks {
    private val lock = SynchronizedObject()
    private val picks = mutableMapOf<String, LyricsSource>()

    /** Bumped on every pick, so a lookup running outside the player (the lock screen's) can redo its own. */
    val version = MutableStateFlow(0)

    operator fun get(videoId: String): LyricsSource? = synchronized(lock) { picks[videoId] }

    operator fun set(videoId: String, source: LyricsSource) {
        synchronized(lock) { picks[videoId] = source }
        version.update { it + 1 }
    }
}
