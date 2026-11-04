package com.music.laya.data

import com.music.laya.data.YtMusicRepository.isStillPictureUpload
import com.music.laya.data.model.Song
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StillPictureUploadTest {

    private fun titled(title: String) = Song(videoId = "x", title = title, artist = "A", thumbnailUrl = null, isVideo = true)

    @Test
    fun audioUploadsAreNotVideos() {
        assertTrue(titled("Khat (Official Audio) | Navjot Ahuja").isStillPictureUpload())
        assertTrue(titled("Khat - Navjot Ahuja [Audio]").isStillPictureUpload())
        assertTrue(titled("Tum Se Tum Tak | Full Audio Song").isStillPictureUpload())
        assertTrue(titled("Song Name (Visualizer)").isStillPictureUpload())
    }

    @Test
    fun realVideosStay() {
        assertFalse(titled("Chann (Official Music Video)").isStillPictureUpload())
        assertFalse(titled("Abar Phire Ele - Lyrical | Arijit Singh").isStillPictureUpload())
        assertFalse(titled("Song (Official Audio & Video)").isStillPictureUpload())
        assertFalse(titled("Audiophile Mix").isStillPictureUpload())
    }
}
