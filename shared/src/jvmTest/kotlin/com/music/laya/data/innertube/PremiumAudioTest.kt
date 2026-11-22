package com.music.laya.data.innertube

import com.metrolist.innertubex.extraction.AudioQuality
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PremiumAudioTest {

    @AfterTest
    fun reset() {
        StreamResolver.premiumAudio = { false }
        Innertube.cookie = null
    }

    @Test
    fun `premium asks for the highest rung so 774 wins over 251`() {
        assertEquals(true to AudioQuality.HIGH, InnerTubeXResolver.ask(Int.MAX_VALUE, requireM4a = false, premium = true))
    }

    @Test
    fun `non-premium keeps the ordinary best-format ask`() {
        assertEquals(false to AudioQuality.AUTO, InnerTubeXResolver.ask(Int.MAX_VALUE, requireM4a = false, premium = false))
    }

    @Test
    fun `an AAC-only player never asks for Premium's Opus`() {
        assertEquals(false to AudioQuality.MP4, InnerTubeXResolver.ask(Int.MAX_VALUE, requireM4a = true, premium = true))
    }

    @Test
    fun `a ceiling under 256kbps cannot keep 774`() {
        assertEquals(false to AudioQuality.LOW, InnerTubeXResolver.ask(64, requireM4a = false, premium = true))
        assertEquals(false to AudioQuality.AUTO, InnerTubeXResolver.ask(160, requireM4a = false, premium = true))
        assertEquals(true to AudioQuality.HIGH, InnerTubeXResolver.ask(256, requireM4a = false, premium = true))
    }

    @Test
    fun `only the first signed-in ask takes Premium's route`() {
        StreamResolver.premiumAudio = { true }
        assertFalse(StreamResolver.premiumFor(0), "signed out")
        Innertube.cookie = "SAPISID=x"
        assertTrue(StreamResolver.premiumFor(0))
        // A miss or a 403 on 774 retries the ordinary way, landing on 251.
        assertFalse(StreamResolver.premiumFor(1))
        assertFalse(StreamResolver.premiumFor(2))
        StreamResolver.premiumAudio = { false }
        assertFalse(StreamResolver.premiumFor(0), "toggle off")
    }
}
