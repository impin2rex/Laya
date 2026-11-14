package com.music.laya.desktop

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopSystemArtTest {
    @Test
    fun wideFrameIsCutToItsMiddleSquare() {
        val out = DesktopSystemArt.squared(BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB), letterboxed = false)
        assertEquals(720 to 720, out.width to out.height)
    }

    @Test
    fun letterboxedThumbnailLosesItsBars() {
        // 480x360 with black bars above and below the 16:9 band (y 45..315), white inside it.
        val thumb = BufferedImage(480, 360, BufferedImage.TYPE_INT_RGB)
        thumb.createGraphics().apply { color = Color.WHITE; fillRect(0, 45, 480, 270); dispose() }
        val out = DesktopSystemArt.squared(thumb, letterboxed = true)
        assertEquals(270 to 270, out.width to out.height)
        assertEquals(Color.WHITE.rgb, out.getRGB(0, 0))
        assertEquals(Color.WHITE.rgb, out.getRGB(269, 269))
    }
}
