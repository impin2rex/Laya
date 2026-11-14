package com.music.laya.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoScaleTest {
    // A 16:9 picture on a 20:9 phone screen held sideways, and on a 4:3 tablet.
    @Test
    fun eachModeSizesThePicture() {
        assertEquals(1600f to 900f, VideoScale.BEST_FIT.frame(16f / 9f, 2000f, 900f))
        assertEquals(2000f to 1125f, VideoScale.FIT_SCREEN.frame(16f / 9f, 2000f, 900f))
        assertEquals(2000f to 900f, VideoScale.FILL.frame(16f / 9f, 2000f, 900f))
        assertEquals(1200f to 675f, VideoScale.BEST_FIT.frame(16f / 9f, 1200f, 900f))
        assertEquals(1600f to 900f, VideoScale.FIT_SCREEN.frame(16f / 9f, 1200f, 900f))
    }

    @Test
    fun aLetterboxedFilmFillsAWideScreen() {
        // 1280×720 frame carrying a 2.39:1 film: 12.2% black above and below. On a 16:9 screen
        // the film itself is fitted, so the frame is drawn taller than the screen, bars off it.
        val bars = VideoCrop(0f, 0.122f, 0f, 0.122f)
        val at = VideoScale.FIT_SCREEN.place(16f / 9f, bars, 1920f, 1080f)
        assertEquals(1080f / 0.756f, at.height, 0.5f)
        assertEquals(1920f * at.height / 1080f, at.width, 1f)
        assertEquals((1080f - at.height) / 2f, at.top, 0.5f)
        // No bars: exactly what frame() says, centred.
        val plain = VideoScale.BEST_FIT.place(16f / 9f, VideoCrop.NONE, 2000f, 900f)
        assertEquals(FramePlacement(200f, 0f, 1600f, 900f), plain)
    }

    @Test
    fun cyclesAndRestores() {
        assertEquals(VideoScale.FIT_SCREEN, VideoScale.BEST_FIT.next)
        assertEquals(VideoScale.BEST_FIT, VideoScale.FILL.next)
        assertEquals(VideoScale.FILL, VideoScale.named("FILL"))
        assertEquals(VideoScale.BEST_FIT, VideoScale.named(null))
        assertEquals(VideoScale.BEST_FIT, VideoScale.named("ZOOM_200"))
    }
}
