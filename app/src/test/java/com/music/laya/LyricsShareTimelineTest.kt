package com.music.laya

import com.music.laya.data.lyrics.LyricLine
import com.music.laya.ui.player.LyricsShareLine
import com.music.laya.ui.player.MAX_SHARE_MS
import com.music.laya.ui.player.shareTimeline
import com.music.laya.ui.player.singWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The share video's clock: which stretches of the song play, and which moment
 * of the track each output frame shows. Every platform's encoder reads it.
 */
class LyricsShareTimelineTest {
    private fun line(start: Long?, end: Long?, gap: Boolean = false) =
        LyricsShareLine(text = if (gap) "" else "words", subText = null, isGap = gap, startMs = start, endMs = end)

    @Test fun contiguousLinesPlayAsOneTake() {
        val timeline = shareTimeline(listOf(line(10_000, 14_000), line(14_000, 18_000)))!!
        assertEquals(listOf(10_000L to 18_000L), timeline.segments)
        assertEquals(8_000L, timeline.durationMs)
        assertEquals(4_000_000L, timeline.windows[1].fromUs)
    }

    @Test fun aSkipCutsStraightToTheNextLine() {
        val timeline = shareTimeline(listOf(line(10_000, 12_000), line(null, null, gap = true), line(30_000, 33_000)))!!
        assertEquals(listOf(10_000L to 12_000L, 30_000L to 33_000L), timeline.segments)
        // 1.5 s in is still the first stretch; 2.5 s in is half a second into the second.
        assertEquals(11_500L, timeline.trackMsAt(1_500_000))
        assertEquals(30_500L, timeline.trackMsAt(2_500_000))
        assertEquals(2, timeline.windows[1].line)
    }

    @Test fun framesMapOntoTheTrackAtTheFrameRate() {
        val timeline = shareTimeline(listOf(line(10_000, 11_000), line(50_000, 51_000)))!!
        assertEquals(60, timeline.frameCount(30))
        assertEquals(10_000L, timeline.trackMsAtFrame(0, 30))
        assertEquals(10_500L, timeline.trackMsAtFrame(15, 30))
        // Frame 30 is the first of the second stretch.
        assertEquals(50_000L, timeline.trackMsAtFrame(30, 30))
        // Past the end it holds the last moment rather than running off.
        assertEquals(51_000L, timeline.trackMsAtFrame(90, 30))
    }

    @Test fun theVideoIsCappedAtAStorysLength() {
        val timeline = shareTimeline(List(10) { line(it * 10_000L, it * 10_000L + 9_000L) })!!
        assertEquals(MAX_SHARE_MS, timeline.durationMs)
    }

    @Test fun unsyncedLinesHaveNoTimeline() {
        assertNull(shareTimeline(listOf(line(null, null), line(null, null))))
    }

    @Test fun aRunEndsWhereTheLastLineIsSungUntil() {
        val lines = listOf(
            LyricLine(10_000, "a"),
            LyricLine(14_000, "b", sungUntilMs = 16_000),
            LyricLine(40_000, "c"),
        )
        assertEquals(10_000L to 14_000L, singWindow(lines, 0, nextPicked = 1))
        // Sung until 16 s plus a beat of air, never into the long gap before "c".
        assertEquals(14_000L to 16_400L, singWindow(lines, 1, nextPicked = null))
    }
}
