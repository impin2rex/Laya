package com.music.laya.data.sources.lossless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashToHlsTest {

    /** The shape Tidal actually serves: one period, one FLAC representation, a repeating timeline. */
    private fun manifest(
        timeline: String = """<S d="176400" r="50" /><S d="5292" />""",
        protection: String = "",
        periods: Int = 1,
    ) = buildString {
        append("""<?xml version="1.0"?><MPD mediaPresentationDuration="PT3M24.12S">""")
        repeat(periods) {
            append("""<Period><AdaptationSet mimeType="audio/mp4">""")
            append("""<Representation id="0" codecs="flac" bandwidth="928000">$protection""")
            append("""<SegmentTemplate timescale="44100" startNumber="1"""")
            append(""" initialization="https://cdn.tidal.com/t/0.mp4?token=a&amp;info=b"""")
            append(""" media="https://cdn.tidal.com/t/${'$'}Number${'$'}.mp4?token=a&amp;info=b">""")
            append("<SegmentTimeline>$timeline</SegmentTimeline>")
            append("</SegmentTemplate></Representation></AdaptationSet></Period>")
        }
        append("</MPD>")
    }

    @Test
    fun namesEverySegmentOnceWithItsOwnLength() {
        val playlist = DashToHls.convert(manifest())!!
        val lines = playlist.trim().lines()

        assertEquals("#EXTM3U", lines.first())
        assertTrue(lines.contains("""#EXT-X-MAP:URI="https://cdn.tidal.com/t/0.mp4?token=a&info=b""""))
        assertEquals("#EXT-X-ENDLIST", lines.last())

        // 51 full segments (d, then r="50" more) and the short tail: 4.0s each, 0.12s at the end.
        val segments = lines.filter { it.startsWith("https://") }
        assertEquals(52, segments.size)
        assertEquals("https://cdn.tidal.com/t/1.mp4?token=a&info=b", segments.first())
        assertEquals("https://cdn.tidal.com/t/52.mp4?token=a&info=b", segments.last())

        val lengths = lines.filter { it.startsWith("#EXTINF:") }.map { it.removePrefix("#EXTINF:").trimEnd(',') }
        assertEquals(52, lengths.size)
        assertEquals("4.000", lengths.first())
        assertEquals("0.120", lengths.last())
        // Rounded up from the longest segment, which is what a player is promised to buffer for.
        assertTrue(lines.contains("#EXT-X-TARGETDURATION:4"))
    }

    /** Lengths add up to the stated runtime, so the seekbar and the audio agree. */
    @Test
    fun runsAsLongAsTheManifestSays() {
        val seconds = DashToHls.convert(manifest())!!.lines()
            .filter { it.startsWith("#EXTINF:") }
            .sumOf { it.removePrefix("#EXTINF:").trimEnd(',').toDouble() }
        assertTrue(seconds in 204.0..204.2, "total was $seconds")
    }

    /** No timeline: a fixed segment length spread across the stated runtime instead. */
    @Test
    fun countsFixedLengthSegmentsFromTheRuntime() {
        val fixed = manifest().replace(
            Regex("<SegmentTimeline>.*?</SegmentTimeline>"),
            "",
        ).replace("""startNumber="1"""", """startNumber="1" duration="176400"""")
        val segments = DashToHls.convert(fixed)!!.lines().count { it.startsWith("https://") }
        assertEquals(52, segments) // 204.12s over 4s segments, the last one part-full
    }

    @Test
    fun refusesWhatItCannotPlayWhole() {
        assertNull(DashToHls.convert(manifest(protection = """<ContentProtection schemeIdUri="x" />""")))
        assertNull(DashToHls.convert(manifest(periods = 2)))
        assertNull(DashToHls.convert("""{"urls":["https://cdn.tidal.com/whole.flac"]}"""))
        assertNull(DashToHls.convert(""))
    }

    /** Lossless wins over a fatter lossy rendition; bandwidth only breaks ties within a kind. */
    @Test
    fun keepsTheLosslessRendition() {
        val mixed = manifest().replace(
            """<Representation id="0" codecs="flac" bandwidth="928000">""",
            """<Representation id="1" codecs="mp4a.40.2" bandwidth="1411000">""" +
                """<SegmentTemplate timescale="44100" startNumber="1" initialization="https://cdn.tidal.com/lossy/0.mp4"""" +
                """ media="https://cdn.tidal.com/lossy/${'$'}Number${'$'}.mp4">""" +
                """<SegmentTimeline><S d="176400" r="50" /></SegmentTimeline></SegmentTemplate></Representation>""" +
                """<Representation id="0" codecs="flac" bandwidth="928000">""",
        )
        val playlist = DashToHls.convert(mixed)!!
        assertTrue(playlist.contains("https://cdn.tidal.com/t/1.mp4"), playlist.take(400))
        assertTrue(!playlist.contains("/lossy/"), "kept the lossy rendition")
    }
}
