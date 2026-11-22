package com.music.laya.ui.player

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AutoMixRevealTest {
    private fun coverage(field: Int, progress: Float): Float {
        val (scale, bias) = autoMixRamp(progress)
        return (field / 255f * scale + bias).coerceIn(0f, 1f)
    }

    @Test
    fun bakedFieldIsDeterministicAndSpansTheRamp() {
        val a = autoMixField(3.5f, 7.25f)
        assertEquals(AUTOMIX_FIELD_SIZE * AUTOMIX_FIELD_SIZE, a.size)
        assertContentEquals(a, autoMixField(3.5f, 7.25f))
        assertNotEquals(a.toList(), autoMixField(9f, 1f).toList())
        val values = a.map { it.toInt() and 0xFF }
        assertTrue(values.min() < 64 && values.max() > 192, "the field uses most of its range")
    }

    @Test
    fun rampStartsEmptyAndEndsFullWholeLayer() {
        for (field in listOf(0, 128, 255)) {
            assertEquals(0f, coverage(field, 0f), 0.001f)
            assertEquals(1f, coverage(field, 1f), 0.001f)
        }
    }

    @Test
    fun rampUncoversHighFieldFirstAndNeverRecovers() {
        var previous = 0f
        for (step in 0..20) {
            val high = coverage(230, step / 20f)
            assertTrue(high >= previous)
            assertTrue(high >= coverage(30, step / 20f))
            previous = high
        }
    }
}
