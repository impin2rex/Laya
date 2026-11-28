package com.music.laya.data.sources

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamLengthProbeTest {
    @Test
    fun previewUnderFullRowIsTooShort() {
        val preview = 30L * 128_000 / 8 // 30 s at 128 kbps
        assertTrue(StreamLengthProbe.tooShort(preview, lossless = false, kbps = 320, expectedSec = 210))
        assertTrue(StreamLengthProbe.tooShort(preview, lossless = true, kbps = null, expectedSec = 210))
    }

    @Test
    fun fullFileIsKept() {
        assertFalse(StreamLengthProbe.tooShort(210L * 96_000 / 8, lossless = false, kbps = 320, expectedSec = 210))
        assertFalse(StreamLengthProbe.tooShort(210L * 800_000 / 8, lossless = true, kbps = null, expectedSec = 210))
    }

    @Test
    fun unmeasuredIsNotShort() {
        assertFalse(StreamLengthProbe.tooShort(0, lossless = false, kbps = null, expectedSec = 210))
        assertFalse(StreamLengthProbe.tooShort(1_000_000, lossless = false, kbps = null, expectedSec = 0))
    }
}
