package com.music.laya.data.settings

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
}
