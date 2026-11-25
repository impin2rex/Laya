package com.music.laya.data.settings

import kotlin.test.assertEquals
import com.music.laya.data.model.QueueTier
import com.music.laya.data.model.Song
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicVideoChoiceTest {
    private val video = Song("v", "Song", "Artist", null, isVideo = true)
    private val track = Song("t", "Song", "Artist", null)

    @Test
    fun videoShowsItsPictureOnlyWhenWatchedUnderMusicOnly() {
        assertFalse(MusicVideoChoice.pictureWanted(video, preferMusicOnly = true, watching = null))
        assertTrue(MusicVideoChoice.pictureWanted(video, preferMusicOnly = true, watching = "v"))
        assertTrue(MusicVideoChoice.pictureWanted(video, preferMusicOnly = false, watching = null))
    }

    @Test
    fun trackThatIsItsOwnVideoShowsItWhenSwitchedTo() {
        assertFalse(MusicVideoChoice.pictureWanted(track, preferMusicOnly = false, watching = null))
        assertTrue(MusicVideoChoice.pictureWanted(track, preferMusicOnly = true, watching = "t"))
        assertTrue(MusicVideoChoice.pictureWanted(track, preferMusicOnly = false, watching = "t"))
        assertFalse(MusicVideoChoice.pictureWanted(track, preferMusicOnly = false, watching = "v"))
    }

    @Test
    fun theAudioReleaseKeepsTheVideosPlaceInTheQueue() {
        val video = Song(
            videoId = "vid", title = "Song (Official Video)", artist = "A", thumbnailUrl = null, isVideo = true,
            queueTier = QueueTier.AUTOPLAY, queueEntryId = "entry-7", radioName = "Radio",
        )
        val audio = Song(videoId = "aud", title = "Song", artist = "A", thumbnailUrl = null, durationText = "3:01")
        val swapped = MusicVideoChoice.audioInPlaceOf(video, audio)
        assertEquals("aud", swapped.videoId)
        assertEquals("3:01", swapped.durationText) // the release's own length, which AutoMix cues by
        assertFalse(swapped.isVideo)
        assertTrue(swapped.isVideoOrigin)
        assertEquals(QueueTier.AUTOPLAY, swapped.queueTier)
        assertEquals("entry-7", swapped.queueEntryId)
        assertEquals("Radio", swapped.radioName)
    }

    @Test
    fun onlyAStreamedVideoOutsideAPartyIsSwappedAhead() {
        val video = Song(videoId = "v", title = "t", artist = "a", thumbnailUrl = null, isVideo = true)
        AppSettings.preferMusicOnly.value = true
        assertTrue(MusicVideoChoice.swapAheadWanted(video, inParty = false))
        assertFalse(MusicVideoChoice.swapAheadWanted(video, inParty = true))
        assertFalse(MusicVideoChoice.swapAheadWanted(video.copy(isVideo = false), inParty = false))
        assertFalse(MusicVideoChoice.swapAheadWanted(video.copy(localUri = "file:///v.mp4"), inParty = false))
        assertFalse(MusicVideoChoice.swapAheadWanted(null, inParty = false))
        AppSettings.preferMusicOnly.value = false
        assertFalse(MusicVideoChoice.swapAheadWanted(video, inParty = false))
    }
}
