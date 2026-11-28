package com.music.laya.data.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppleClipTest {
    private val master = """
        #EXTM3U
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=182866,CODECS="avc1.64001f",RESOLUTION=486x486,URI="https://x/iframes.m3u8"
        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=2167535,BANDWIDTH=2703492,CODECS="avc1.64001f",RESOLUTION=768x768
        https://x/a/video_768.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2183440,CODECS="hvc1.2.20000000.L123.B0",RESOLUTION=768x768
        https://x/a/hevc_768.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=325633,CODECS="avc1.64001f",RESOLUTION=360x360
        video_360.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=6694579,CODECS="avc1.640020",RESOLUTION=1080x1080
        https://x/a/video_1080.m3u8
    """.trimIndent()

    @Test
    fun picksTheLargestH264RenditionWithinTheCap() {
        assertEquals("https://x/a/video_768.m3u8", AppleClip.pickVariant(master, "https://x/m/master.m3u8"))
    }

    @Test
    fun resolvesRelativeRenditionsAgainstTheMaster() {
        val small = master.lines().filterNot { it.contains("768") || it.contains("1080") }.joinToString("\n")
        assertEquals("https://x/m/video_360.m3u8", AppleClip.pickVariant(small, "https://x/m/master.m3u8?a=1"))
    }

    @Test
    fun aByteRangePlaylistIsOneFile() {
        val media = """
            #EXTM3U
            #EXT-X-MAP:URI="clip-.mp4",BYTERANGE="897@0"
            #EXTINF:3.58,
            #EXT-X-BYTERANGE:1037078@897
            clip-.mp4
            #EXTINF:3.58,
            #EXT-X-BYTERANGE:1212150@1037975
            clip-.mp4
        """.trimIndent()
        assertEquals("https://x/a/clip-.mp4", AppleClip.singleFile(media, "https://x/a/video_768.m3u8"))
    }

    @Test
    fun separateSegmentFilesAreNotOneFile() {
        val media = "#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\nseg1.m4s\n#EXTINF:4,\nseg2.m4s"
        assertNull(AppleClip.singleFile(media, "https://x/a/v.m3u8"))
    }
}
