package com.music.laya.desktop

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopMiniPlayerPlacementTest {

    // Usable areas in logical points: menu bar / Dock / taskbar already taken off.
    private val screens = mapOf(
        "MacBook Air 13 default" to Rectangle(0, 25, 1470, 831),
        "MacBook Pro 16 default" to Rectangle(0, 37, 1728, 1080),
        "MacBook Air 13 larger text" to Rectangle(0, 25, 1024, 615),
        "1366x768 at 100%" to Rectangle(0, 0, 1366, 728),
        "1366x768 at 150%" to Rectangle(0, 0, 911, 485),
        "1920x1080 at 200%" to Rectangle(0, 0, 960, 500),
        "external monitor left of laptop" to Rectangle(-1920, 0, 1920, 1040),
    )

    @Test
    fun cardFitsWhollyOnEveryScreenInBothShapes() {
        for ((name, area) in screens) for (compact in listOf(false, true)) {
            val size = miniSize(compact, area)
            // Opened at the top right corner, and switched from the other shape with its right edge
            // flush against the screen's — the case that used to hang off.
            for (right in listOf(area.maxX.toFloat() - 24f, area.maxX.toFloat())) {
                val at = miniPosition(size, area, right = right, top = area.y + 24f)
                val x = at.x.value
                val y = at.y.value
                assertTrue(x >= area.x && x + size.width.value <= area.maxX + 0.01f, "$name compact=$compact x")
                assertTrue(y >= area.y && y + size.height.value <= area.maxY + 0.01f, "$name compact=$compact y")
            }
        }
    }

    @Test
    fun fullSizeWhereItFitsShrunkOnlyWhereItMust() {
        assertEquals(450f, miniSize(false, screens.getValue("MacBook Air 13 default")).height.value)
        val small = miniSize(false, screens.getValue("1366x768 at 150%"))
        assertTrue(small.height.value < 450f)
        // Shape kept.
        assertEquals(280f / 450f, small.width.value / small.height.value, 0.001f)
    }
}
