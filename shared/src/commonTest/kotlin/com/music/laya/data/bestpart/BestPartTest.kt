package com.music.laya.data.bestpart

import com.music.laya.data.lyrics.LyricLine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins the Best Part button's start: the bump in YouTube's "Most replayed" curve. */
class BestPartTest {

    /** A four-minute song in YouTube's hundred equal segments. */
    private val segment = 2_400L

    /** Hot at the very start, sagging as people drift off, the chorus standing up at [bumpFrom]..[bumpTo]. */
    private fun decaying(bumpFrom: Int? = null, bumpTo: Int? = null) = Heatmap(
        segment,
        List(100) { i ->
            val base = if (i == 0) 1f else 0.6f - 0.003f * i
            base + if (bumpFrom != null && bumpTo != null && i in bumpFrom..bumpTo) 0.25f else 0f
        },
    )

    private fun nextAnswer(markers: List<Float>, type: String = "MARKER_TYPE_HEATMAP"): JsonObject = buildJsonObject {
        putJsonObject("frameworkUpdates") {
            putJsonObject("entityBatchUpdate") {
                putJsonArray("mutations") {
                    // A chapters entity first: the same shape, and must be skipped.
                    addJsonObject {
                        putJsonObject("payload") {
                            putJsonObject("macroMarkersListEntity") {
                                putJsonObject("markersList") {
                                    put("markerType", "MARKER_TYPE_CHAPTERS")
                                    putJsonArray("markers") {}
                                }
                            }
                        }
                    }
                    addJsonObject {
                        putJsonObject("payload") {
                            putJsonObject("macroMarkersListEntity") {
                                putJsonObject("markersList") {
                                    put("markerType", type)
                                    putJsonArray("markers") {
                                        markers.forEachIndexed { i, heat ->
                                            addJsonObject {
                                                put("startMillis", (i * segment).toString())
                                                put("durationMillis", segment.toString())
                                                put("intensityScoreNormalized", heat)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun heatMarkersAreReadOutOfTheNextAnswer() {
        val heat = assertNotNull(BestPart.parseHeatmap(nextAnswer(decaying(40, 50).intensity)))
        assertEquals(segment, heat.segmentMs)
        assertEquals(100, heat.intensity.size)
        assertEquals(1f, heat.intensity[0])
    }

    @Test
    fun noHeatMarkersNoCurve() {
        assertNull(BestPart.parseHeatmap(nextAnswer(decaying().intensity, type = "MARKER_TYPE_CHAPTERS")))
        assertNull(BestPart.parseHeatmap(buildJsonObject { put("contents", "nothing") }))
        assertNull(BestPart.parseHeatmap(nextAnswer(List(6) { 0.5f })))
    }

    @Test
    fun chorusBumpWinsOverTheHotOpening() {
        val start = assertNotNull(BestPart.peakMs(decaying(40, 50), durationMs = 240_000))
        assertTrue(start in 38 * segment..41 * segment, "started at $start")
        assertEquals(start - 1_000, BestPart.startMs(decaying(40, 50), durationMs = 240_000))
    }

    @Test
    fun nothingToOffer() {
        assertNull(BestPart.peakMs(Heatmap(segment, List(100) { 0.4f }), durationMs = 240_000), "flat curve")
        assertNull(BestPart.peakMs(decaying(40, 50), durationMs = 50_000), "short song")
        assertNull(BestPart.peakMs(decaying(97, 99), durationMs = 240_000), "bump in the last half minute")
    }

    /** Verse lines once each, a four-line chorus twice: the chorus comes first at 40s. */
    private val song = listOf(
        10_000L to "first verse line one", 14_000L to "first verse line two",
        40_000L to "we sing this chorus loud", 44_000L to "we sing it all night long",
        70_000L to "second verse line one", 74_000L to "second verse line two",
        100_000L to "we sing this chorus loud", 104_000L to "we sing it all night long",
    ).map { (t, text) -> LyricLine(t, text) }

    @Test
    fun chorusIsTheFirstTimeTheMostRepeatedLineComesRound() {
        assertEquals(40_000L, BestPart.chorusMs(song, 240_000))
        assertNull(BestPart.chorusMs(song.take(3), 240_000), "too few lines")
        assertNull(BestPart.chorusMs(song, 50_000), "short song")
    }

    @Test
    fun noCurveFallsBackToTheChorusSnappedToItsLine() {
        assertEquals(39_750L, BestPart.pick(null, song, 240_000))
        // A curve's bump still wins over the chorus.
        val peak = assertNotNull(BestPart.peakMs(decaying(70, 80), 240_000))
        assertTrue(BestPart.pick(decaying(70, 80), song, 240_000)!! in (peak - 5_000)..(peak + 2_000))
        assertNull(BestPart.pick(null, emptyList(), 240_000))
    }
}
