package com.music.laya.playback

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * The YouTube upload each JioSaavn/addon/module row was matched to, once.
 *
 * Those catalogues have no idea what a YouTube id is, so a source row reaches
 * the queue without one, and "Revert to original" has to find the upload by
 * title, artist and runtime. Doing that every time is slow and can land on a
 * different upload from one revert to the next; remembered after the first
 * match, every later revert — and the error fallback — asks YouTube for that
 * exact video instead. See [com.music.laya.data.sources.SourceResolver.youtubeFallback].
 */
object YouTubeOriginals {

    private lateinit var prefs: SharedPreferences

    /** Track key → YouTube video id. */
    private val ids = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.all.forEach { (key, value) -> (value as? String)?.let { ids[key] = it } }
    }

    fun of(trackKey: String?): String? = trackKey?.let { ids[it] }

    /** Track keys whose upload is being looked up now, so a re-open doesn't start a second search. */
    private val lookingUp = ConcurrentHashMap.newKeySet<String>()

    /** Looked up this session and not found — not searched again on every re-open. */
    private val missed = ConcurrentHashMap.newKeySet<String>()

    /** Claims the lookup for [trackKey]; false when it is known, missed or already being found. */
    fun startLookup(trackKey: String): Boolean =
        ids[trackKey] == null && trackKey !in missed && lookingUp.add(trackKey)

    fun endLookup(trackKey: String, found: Boolean) {
        if (!found) missed += trackKey
        lookingUp -= trackKey
    }

    fun remember(trackKey: String?, videoId: String?) {
        if (trackKey.isNullOrBlank() || videoId.isNullOrBlank() || ids[trackKey] == videoId) return
        // ponytail: clear-all at the cap, not LRU; a re-match costs one search.
        if (ids.size >= MAX_REMEMBERED) {
            ids.clear()
            if (::prefs.isInitialized) prefs.edit().clear().apply()
        }
        ids[trackKey] = videoId
        if (::prefs.isInitialized) prefs.edit().putString(trackKey, videoId).apply()
    }

    private const val MAX_REMEMBERED = 2_000
    private const val PREFS_NAME = "laya_youtube_originals"
}
