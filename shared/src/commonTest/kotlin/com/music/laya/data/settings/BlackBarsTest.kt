package com.music.laya.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlackBarsTest {
    // A 160×90 grid; [picture] is lit, everything else black at limited range's 16.
    private fun frame(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0, dark: Boolean = false) =
        BlackBars.detect(160, 90) { x, y ->
            val inside = x >= left && x < 160 - right && y >= top && y < 90 - bottom
            if (inside && !dark) 120 else 16
        }

    @Test
    fun letterboxAndPillarboxAreFound() {
        // A 2.39:1 film in 16:9: about 12% black above and below.
        val film = frame(top = 11, bottom = 11)!!
        assertEquals(11f / 90f, film.top)
        assertEquals(0f, film.left)
        // A 4:3 clip in 16:9: 12.5% black each side.
        val boxed = frame(left = 20, right = 20)!!
        assertEquals(20f / 160f, boxed.left)
        assertEquals(0f, boxed.top)
        assertTrue(frame()!!.isNone)
    }

    @Test
    fun darkFramesAndHairlinesSayNothing() {
        assertNull(frame(dark = true))
        assertTrue(frame(top = 1)!!.isNone)
        // Two thirds of the frame black is a dark scene, not a bar.
        assertEquals(0f, frame(top = 60)!!.top)
    }

    @Test
    fun theCropSettlesOnTheLeastOfRecentReadings() {
        val settler = VideoCropSettler()
        val bars = VideoCrop(0f, 0.12f, 0f, 0.12f)
        assertTrue(settler.offer(bars).isNone)
        assertTrue(settler.offer(null).isNone)
        assertTrue(settler.offer(bars).isNone)
        assertEquals(bars, settler.offer(bars))
        // One shot with a dark sky reads deeper at the top; the crop doesn't follow it.
        assertEquals(bars, settler.offer(VideoCrop(0f, 0.30f, 0f, 0.12f)))
        assertEquals(16f / 9f * 1f / 0.76f, bars.aspect(16f / 9f), 0.0001f)
    }
}
