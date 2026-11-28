package com.music.laya.ui.player

import com.music.laya.playback.AutoMixArtworkTuning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoftCoverTest {
    @Test
    fun softCoverFadesOutBeforeTheRevealEnds() {
        assertEquals(1f, softCoverAlpha(0f), 1e-6f)
        assertEquals(0f, softCoverAlpha(AutoMixArtworkTuning.BLUR_END_PROGRESS), 1e-6f)
        assertEquals(0f, softCoverAlpha(1f), 1e-6f)
        assertTrue(softCoverAlpha(0.2f) > softCoverAlpha(0.5f))
    }
}
