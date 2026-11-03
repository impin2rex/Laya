package com.music.laya.data.stats

import com.music.laya.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

class ListenIdentityTest {

    private fun song(id: String, video: Boolean) =
        Song(videoId = id, title = "Song", artist = "Artist", thumbnailUrl = "", isVideo = video)

    @Test
    fun audioToVideoAndBackStaysTheAudio() {
        val audio = song("audioAAAAA1", video = false)
        val video = song("videoVVVVV1", video = true)
        ListenIdentity.onVersionSwapped(audio, video)
        assertEquals(audio.videoId, ListenIdentity.idOf(video.videoId))
        ListenIdentity.onVersionSwapped(video, audio)
        assertEquals(audio.videoId, ListenIdentity.idOf(audio.videoId))
        // Again and again: still the one listen.
        ListenIdentity.onVersionSwapped(audio, video)
        ListenIdentity.onVersionSwapped(video, audio)
        assertEquals(audio.videoId, ListenIdentity.idOf(video.videoId))
        assertEquals(audio.videoId, ListenIdentity.idOf(audio.videoId))
    }

    @Test
    fun videoToAudioBecomesTheAudio() {
        val video = song("videoVVVVV2", video = true)
        val audio = song("audioAAAAA2", video = false)
        assertEquals(video.videoId, ListenIdentity.idOf(video.videoId))
        ListenIdentity.onVersionSwapped(video, audio)
        assertEquals(audio.videoId, ListenIdentity.idOf(video.videoId))
        // Back to the video to watch it: the listen stays the audio release's.
        ListenIdentity.onVersionSwapped(audio, video)
        assertEquals(audio.videoId, ListenIdentity.idOf(video.videoId))
    }

    @Test
    fun aFailedSwapIsUndone() {
        val video = song("videoVVVVV3", video = true)
        val audio = song("audioAAAAA3", video = false)
        val listen = ListenIdentity.of(video)
        ListenIdentity.onVersionSwapped(video, audio)
        ListenIdentity.revert(video, listen)
        assertEquals(video.videoId, ListenIdentity.idOf(video.videoId))
    }
}
