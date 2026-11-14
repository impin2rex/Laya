package com.music.laya.data.settings

import com.music.laya.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Whether a music video's picture is up, under [AppSettings.preferMusicOnly].
 *
 * With the preference on, a video plays as a song until the listener asks to see it — the player's
 * video toggle — and [watching] is that ask. It names one video and answers for no other: each
 * platform clears it when the track moves on, so a video coming round again starts as music.
 */
object MusicVideoChoice {
    /** The video the listener switched to; null while none. */
    val watching = MutableStateFlow<String?>(null)

    /**
     * Whether [song]'s picture should be up, for the given preference and [watching]. A track not
     * marked as a video still has one when the listener switched to it and the video found for it
     * was the very same upload (a song whose id is its own music video).
     */
    fun pictureWanted(song: Song?, preferMusicOnly: Boolean, watching: String?): Boolean =
        song != null && (watching == song.videoId || (song.isVideo && !preferMusicOnly))

    /** As [pictureWanted], read off the current values — for code outside composition. */
    fun pictureWanted(song: Song?): Boolean =
        pictureWanted(song, AppSettings.preferMusicOnly.value, watching.value)

    /** Shows or hides [videoId]'s picture — the toggle when a video has no audio version to swap to. */
    fun toggle(videoId: String) {
        watching.value = if (watching.value == videoId) null else videoId
    }

    /** The track moved on to [videoId]: an ask for another video no longer stands. */
    fun onTrackChanged(videoId: String?) {
        if (watching.value != videoId) watching.value = null
    }
}
