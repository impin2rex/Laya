package com.music.laya.data.bestpart

import com.music.laya.data.innertube.Innertube
import com.music.laya.data.lyrics.LyricLine
import com.music.laya.data.lyrics.LyricsTranslation
import com.music.laya.data.model.Song
import com.music.laya.data.model.durationMillis
import com.music.laya.platform.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * YouTube's "Most replayed" curve for a video: how much each [segmentMs] stretch
 * of it gets replayed, 0..1, start to end.
 */
data class Heatmap(val segmentMs: Long, val intensity: List<Float>) {
    val durationMs: Long get() = segmentMs * intensity.size
}

/**
 * Where a song's best part starts: the stretch listeners keep dragging the
 * scrubber back to, read off YouTube's "Most replayed" curve. Ported from
 * BitChord's Replay year story, for the player's Best Part button.
 *
 * The parsing and the peak pick are pure, so the thresholds are pinned by tests.
 */
object BestPart {

    /** Nothing in a song's opening seconds counts: everyone "replays" the start. */
    const val SKIP_HEAD_MS = 5_000L

    /** A best part needs some song left after it. */
    const val SKIP_TAIL_MS = 30_000L

    /** Interludes and skits have no best part to skip to. */
    const val MIN_SONG_MS = 60_000L

    /** Fewer markers than this is a video YouTube has too few views on to chart. */
    private const val MIN_MARKERS = 10

    /** How far, as a share, the peak has to stand above the trend around it. */
    private const val MIN_RISE = 0.08f

    /** And at least this much in absolute terms, since curves are 0..1. */
    private const val MIN_LIFT = 0.03f

    /** The smoothing either side of a moment: one hot second shouldn't beat a hot chorus. */
    private const val SMOOTH_HALF_MS = 4_500.0

    /** The trend around a moment is the median of the curve this far either side. */
    private const val TREND_HALF_MS = 45_000.0

    /** A trend this low is treated as this, so a near-silent stretch can't make any bump look huge. */
    private const val TREND_FLOOR = 0.05f

    /** Started this much before the climb, so the jump doesn't open mid-word. */
    private const val LEAD_IN_MS = 1_000L

    private const val LOOKUP_TIMEOUT_MS = 8_000L

    private val lock = Mutex()

    private val disk = HeatmapStore(root = { LyricsTranslation.cacheDir })

    /** Curves already had this run (and the disk's, see [HeatmapStore]), by video id; null is "no curve". The lyrics are not kept: they come with the player. */
    private val known = HashMap<String, Heatmap?>()

    /**
     * Where [song]'s best part starts, in ms, or null when there is none to
     * offer: neither a bump in the curve nor a chorus in the
     * synced [lines]. A failed lookup is not remembered, so the next play of
     * the song asks again; a song with no curve falls back to its chorus.
     */
    suspend fun startFor(song: Song, lines: List<LyricLine>? = null): Long? {
        val id = song.videoId
        val duration = song.durationText.durationMillis()
        val synced = lines.orEmpty().takeIf { l -> l.any { it.timeMs > 0L } }.orEmpty()
        // A file on the device, or an addon's track, has no YouTube curve: its chorus, from the lyrics
        // the player holds (a local file's embedded ones come first there), is all there is to offer.
        if (song.localUri != null || !YOUTUBE_ID.matches(id)) return pick(null, synced, duration)
        val cached = lock.withLock {
            if (id in known) {
                listOf(known[id])
            } else {
                withContext(ioDispatcher) { disk.get(id) }?.let { listOf(it.heat).also { _ -> known[id] = it.heat } }
            }
        }
        val heat = if (cached != null) {
            cached.single()
        } else {
            // No answer (offline, timed out) says nothing about the video: it is not remembered.
            val answer = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                try {
                    Innertube.webNext(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            if (answer == null) {
                withContext(ioDispatcher) { disk.get(id, allowStale = true)?.heat } // stale beats nothing offline
            } else {
                parseHeatmap(answer).also {
                    lock.withLock { known[id] = it }
                    withContext(ioDispatcher) { disk.put(id, it) }
                }
            }
        }
        return pick(heat, synced, duration)
    }

    /** The best part from the curve's bump if it has one, else the first chorus in the synced [lines]; null when neither does. */
    fun pick(heat: Heatmap?, lines: List<LyricLine>, durationMs: Long): Long? {
        val starts = lines.filter { it.timeMs >= 0 && !it.isGap }.map { it.timeMs }
        val target = heat?.let { peakMs(it, durationMs) } ?: chorusMs(lines, durationMs) ?: return null
        return snap(target, starts)
    }

    /**
     * The first time the chorus comes round, from time-synced [lines]: the
     * earliest line of the most repeated lyric, walked back over the lines of
     * its block that come before it. Null when nothing repeats.
     */
    fun chorusMs(lines: List<LyricLine>, durationMs: Long): Long? {
        if (durationMs in 1 until MIN_SONG_MS) return null
        val timed = lines.filter { it.timeMs >= 0 && !it.isGap && it.text.isNotBlank() }
        if (timed.size < 4) return null
        val keys = timed.map { line -> line.text.lowercase().filter { it.isLetterOrDigit() } }
        val counts = keys.filter { it.length >= 4 }.groupingBy { it }.eachCount()
        val most = counts.values.maxOrNull() ?: return null
        if (most < 2) return null
        val limit = if (durationMs > 0) durationMs - SKIP_TAIL_MS else Long.MAX_VALUE
        val first = keys.indices.firstOrNull { i ->
            counts[keys[i]] == most && timed[i].timeMs in SKIP_HEAD_MS..limit
        } ?: return null
        // Lines that repeat about as often are the same block: a chorus is heard from its first line, not its hook.
        var start = first
        while (start - 1 >= 0) {
            val count = counts[keys[start - 1]] ?: 0
            if (count < 2 || count < most - 1) break
            if (timed[start - 1].timeMs < SKIP_HEAD_MS || first - start >= 4) break
            start--
        }
        return timed[start].timeMs
    }

    /**
     * Pulls [targetMs] back to a natural start: a lyric line beginning near it
     * (just before, or a moment after), else a second before it, so the jump
     * doesn't open mid-word.
     */
    fun snap(targetMs: Long, lineStarts: List<Long>): Long {
        lineStarts
            .filter { it in (targetMs - 4_000)..(targetMs + 1_500) }
            .minByOrNull { abs(it - targetMs) + if (it > targetMs) 1_500 else 0 }
            ?.let { return (it - 250).coerceAtLeast(0) }
        return (targetMs - LEAD_IN_MS).coerceAtLeast(0)
    }

    /** [peakMs] pulled back a beat, so the skip lands just before the climb. */
    fun startMs(heat: Heatmap, durationMs: Long = 0): Long? =
        peakMs(heat, durationMs)?.let { (it - LEAD_IN_MS).coerceAtLeast(0) }

    /**
     * The heat markers in a youtube.com `next` answer, or null when the video
     * has none. They sit in a `macroMarkersListEntity` under the answer's
     * framework updates, which have moved around more than once, so this looks
     * for the entity anywhere in the answer rather than down a fixed path.
     */
    fun parseHeatmap(answer: JsonObject): Heatmap? {
        val entity = findAll(answer, "macroMarkersListEntity")
            .filterIsInstance<JsonObject>()
            .firstOrNull { it.obj("markersList")?.str("markerType") == "MARKER_TYPE_HEATMAP" }
            ?: return null
        val markers = (entity.obj("markersList")?.get("markers") as? JsonArray)
            ?.mapNotNull { element ->
                val marker = element as? JsonObject ?: return@mapNotNull null
                val start = marker.str("startMillis")?.toLongOrNull() ?: return@mapNotNull null
                val length = marker.str("durationMillis")?.toLongOrNull() ?: return@mapNotNull null
                val heat = marker.str("intensityScoreNormalized")?.toFloatOrNull() ?: return@mapNotNull null
                Triple(start, length, heat.coerceIn(0f, 1f))
            }
            ?.sortedBy { it.first }
            ?: return null
        if (markers.size < MIN_MARKERS) return null
        // Equal segments (a hundredth of the video each); the median in case the last is cut short.
        val segment = markers.map { it.second }.sorted()[markers.size / 2]
        if (segment <= 0) return null
        return Heatmap(segment, markers.map { it.third })
    }

    /**
     * Where the most replayed stretch begins, in ms, for a song [durationMs]
     * long (the curve's own length if 0). Null when nothing stands out or the
     * song is too short to have a best part.
     *
     * A curve mostly falls from start to end, as people drift off, so its
     * maximum is nearly always the first seconds. What marks the best part is
     * a bump above that falling trend: every moment is measured against the
     * median of the curve around it and the biggest rise wins.
     */
    fun peakMs(heat: Heatmap, durationMs: Long = 0): Long? {
        val duration = durationMs.takeIf { it > 0 } ?: heat.durationMs
        val n = heat.intensity.size
        if (duration < MIN_SONG_MS || n < MIN_MARKERS) return null
        val seg = heat.segmentMs

        val half = (SMOOTH_HALF_MS / seg).roundToInt().coerceAtLeast(1)
        val smooth = FloatArray(n) { i ->
            val from = (i - half).coerceAtLeast(0)
            val to = (i + half).coerceAtMost(n - 1)
            (from..to).sumOf { heat.intensity[it].toDouble() }.toFloat() / (to - from + 1)
        }
        val wide = (TREND_HALF_MS / seg).roundToInt().coerceAtLeast(3)
        val trend = FloatArray(n) { i ->
            // The opening segment is left out: its heat is everyone pressing play.
            val window = ((i - wide).coerceAtLeast(1)..(i + wide).coerceAtMost(n - 1)).map { smooth[it] }.sorted()
            window[window.size / 2]
        }

        fun rise(i: Int) = smooth[i] / trend[i].coerceAtLeast(TREND_FLOOR) - 1f

        val head = maxOf(SKIP_HEAD_MS, 2 * seg)
        val candidates = (0 until n).filter { i -> i * seg >= head && i * seg <= duration - SKIP_TAIL_MS }
        if (candidates.isEmpty()) return null
        val peak = candidates.maxBy { rise(it) }
        if (rise(peak) < MIN_RISE || smooth[peak] - trend[peak] < MIN_LIFT) return null

        // Back from the peak to where the heat starts climbing towards it: half
        // the peak's rise. That is where the chorus starts, not where it's hottest.
        val floor = rise(peak) * 0.5f
        var start = peak
        while (start - 1 in candidates && rise(start - 1) >= floor) start--
        // The smoothed curve climbs gradually; the raw segments say where it jumps.
        val jump = (start..peak).firstOrNull { i ->
            heat.intensity[i] / trend[i].coerceAtLeast(TREND_FLOOR) - 1f >= floor
        } ?: start
        return jump * seg
    }

    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")

    private fun findAll(element: JsonElement, key: String): List<JsonElement> = buildList {
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> if (k == key) add(v) else walk(v) }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(element)
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

    /** YouTube sends the times as strings and the heat as a number: either, as text. */
    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
