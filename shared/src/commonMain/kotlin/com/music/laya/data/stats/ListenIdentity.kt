package com.music.laya.data.stats

import com.music.laya.data.model.Song
import com.music.laya.platform.ConcurrentMap

/**
 * Which track a listen is of, when what is playing is one of its versions.
 *
 * The audio/video switch puts another media id in the player mid-listen. History — the account's
 * on YouTube and the device's own stats — must go on counting the track being listened to, not
 * whichever cut of it is coming out: switching to the music video keeps the listen the audio
 * release's, and switching from a video to its audio release makes it the audio release's. So the
 * listen is always of the audio cut, once one is in play; a video played on its own is a listen of
 * the video, under the usual rules.
 */
object ListenIdentity {

    /** The listen each played version belongs to, by its media id. */
    private val listenOf = ConcurrentMap<String, Song>()

    /** [from] became [to] mid-listen. Returns the track the listen is of from here on. */
    fun onVersionSwapped(from: Song, to: Song): Song {
        val listen = if (to.isVideo) of(from) else to
        // A handful of pairs is all a session switches between; nothing needs keeping longer.
        if (listenOf.size > MAX_PAIRS) listenOf.clear()
        listenOf[from.videoId] = listen
        listenOf[to.videoId] = listen
        return listen
    }

    /** A switch to another version that didn't go through: [from] is a listen of [listen] again. */
    fun revert(from: Song, listen: Song) {
        listenOf[from.videoId] = listen
    }

    /** The track [song] is a listen of — itself, unless it is a version switched to. */
    fun of(song: Song): Song = listenOf[song.videoId] ?: song

    /** As [of], for a bare media id. */
    fun idOf(videoId: String): String = listenOf[videoId]?.videoId ?: videoId

    private const val MAX_PAIRS = 64
}
