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
     * The video the listener put the picture of away, with the preference off: it plays on as music.
     * [watching] outranks it, so a later switch back to the video by choice brings the picture up.
     */
    val hiding = MutableStateFlow<String?>(null)

    /**
     * Whether [song]'s picture should be up, for the given preference, [watching] and [hiding]. A
     * track not marked as a video still has one when the listener switched to it and the video found
     * for it was the very same upload (a song whose id is its own music video).
     */
    fun pictureWanted(song: Song?, preferMusicOnly: Boolean, watching: String?, hiding: String?): Boolean =
        song != null && (watching == song.videoId || (song.isVideo && !preferMusicOnly && hiding != song.videoId))

    /** As [pictureWanted], read off the current values — for code outside composition. */
    fun pictureWanted(song: Song?): Boolean =
        pictureWanted(song, AppSettings.preferMusicOnly.value, watching.value, hiding.value)

    /**
     * Shows or hides [song]'s picture — the toggle when a video has no audio version to swap to,
     * with the preference on (a video starts as music) or off (it starts with its picture up).
     */
    fun toggle(song: Song) {
        if (pictureWanted(song)) {
            watching.value = null
            hiding.value = song.videoId
        } else {
            hiding.value = null
            watching.value = song.videoId
        }
    }

    /**
     * [audio], the audio release found for the queued [video], made to take the video's place in
     * the queue: its row, tier and where it is playing from stay the video's. Each player swaps the
     * *next* video for it ahead of the transition under [AppSettings.preferMusicOnly], because AutoMix
     * never mixes into a video and the swap on arrival came too late for the blend.
     */
    fun audioInPlaceOf(video: Song, audio: Song): Song = audio.copy(
        isVideoOrigin = true,
        queueTier = video.queueTier,
        queueEntryId = video.queueEntryId,
        radioName = video.radioName,
        playbackSource = video.playbackSource,
        playbackSourceType = video.playbackSourceType,
        playbackSourceId = video.playbackSourceId,
    )

    /** Whether the queued [next] track should be swapped for its audio release ahead of time. */
    fun swapAheadWanted(next: Song?, inParty: Boolean): Boolean =
        next != null && next.isVideo && next.localUri == null && !inParty && AppSettings.preferMusicOnly.value

    /** The track moved on to [videoId]: an ask for another video no longer stands. */
    fun onTrackChanged(videoId: String?) {
        if (watching.value != videoId) watching.value = null
        if (hiding.value != videoId) hiding.value = null
    }
}
