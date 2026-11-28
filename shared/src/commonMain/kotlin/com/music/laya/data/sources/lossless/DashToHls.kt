package com.music.laya.data.sources.lossless

import kotlin.math.ceil
import kotlin.math.max

/**
 * Rewrites a single-period DASH audio manifest as an HLS playlist pointing at the very same
 * (already absolute) fMP4 segment URLs — DASH and HLS describe the identical init-segment-plus-
 * numbered-media-segments layout, only the index in front differs. AVPlayer has no DASH engine of
 * its own but reads an HLS playlist natively, which is the only reason Tidal lossless plays on iOS
 * at all: nothing about the audio bytes changes, only which document names them.
 *
 * The parsing is the same narrow approach [OfflineDash][com.music.laya.download.OfflineDash] (the
 * Android download path) already proved on these exact manifests: `SegmentTemplate` with
 * `$Number$` only, one period, the lossless (or else highest-bandwidth) representation kept.
 * Encrypted or multi-period manifests are refused rather than half-played — [convert] returns null
 * and the caller falls back to its existing "can't open this" path.
 */
object DashToHls {

    fun convert(manifest: String): String? {
        if (Regex("<ContentProtection", RegexOption.IGNORE_CASE).containsMatchIn(manifest)) return null
        if (Regex("<Period[\\s>]").findAll(manifest).count() > 1) return null
        return runCatching { playlist(parse(manifest)) }.getOrNull()
    }

    private class Plan(val initialization: String, val media: List<String>, val durationsMs: List<Long>)
    private class Rendition(val bandwidth: Long, val codecs: String, val body: String)

    private fun parse(manifest: String): Plan {
        val rendition = best(manifest)
        val scope = rendition?.body?.takeIf { Regex("<SegmentTemplate", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            ?: manifest
        val template = Regex("<SegmentTemplate([^>]*)>", RegexOption.IGNORE_CASE).find(scope)?.groupValues?.get(1)
            ?: error("no segment template")
        fun attr(name: String) =
            Regex("""\b$name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(template)?.groupValues?.get(1)

        val initialization = attr("initialization")?.let(::unescape) ?: error("no initialization segment")
        val mediaTemplate = attr("media")?.let(::unescape) ?: error("no media template")
        if (!mediaTemplate.contains("\$Number\$")) error("unsupported media template")
        val timescale = attr("timescale")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
        val startNumber = attr("startNumber")?.toIntOrNull() ?: 1

        // A SegmentTimeline states each segment's real length (including a short final one); falling
        // back to a fixed duration repeated across the presentation is the other legal way to write this.
        val ticks = mutableListOf<Long>()
        Regex("<SegmentTimeline>(.*?)</SegmentTimeline>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(scope)?.groupValues?.get(1)
            ?.let { timeline ->
                Regex("<S\\b([^>]*)/?>", RegexOption.IGNORE_CASE).findAll(timeline).forEach { entry ->
                    val body = entry.groupValues[1]
                    fun of(name: String) = Regex("""\b$name\s*=\s*"([^"]*)"""").find(body)?.groupValues?.get(1)
                    val d = of("d")?.toLongOrNull() ?: return@forEach
                    // `r` is how many times the entry repeats, so r="56" is 57 segments.
                    repeat((of("r")?.toIntOrNull() ?: 0) + 1) { ticks += d }
                }
            }
        if (ticks.isEmpty()) {
            val d = attr("duration")?.toLongOrNull()?.takeIf { it > 0 } ?: error("no timeline or segment duration")
            val total = Regex("""mediaPresentationDuration\s*=\s*"([^"]*)"""").find(manifest)
                ?.groupValues?.get(1)?.let(::isoSeconds) ?: error("no stated presentation duration")
            repeat(max(1, ceil(total / (d / timescale)).toInt())) { ticks += d }
        }

        val media = ticks.indices.map { at -> mediaTemplate.replace("\$Number\$", (startNumber + at).toString()) }
        if (media.isEmpty()) error("no segments")
        val durationsMs = ticks.map { (it * 1000.0 / timescale).toLong() }
        return Plan(initialization, media, durationsMs)
    }

    /** The rendition to keep: lossless if there is one, else the highest bandwidth. */
    private fun best(manifest: String): Rendition? {
        val renditions = Regex(
            """<Representation\b([^>]*?)(?:/>|>(.*?)</Representation>)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(manifest).map { match ->
            val attrs = match.groupValues[1]
            fun attr(name: String) =
                Regex("""\b$name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)
            Rendition(
                bandwidth = attr("bandwidth")?.toLongOrNull() ?: 0L,
                codecs = attr("codecs").orEmpty().lowercase(),
                body = match.groupValues[2],
            )
        }.toList()
        return renditions.maxWithOrNull(compareBy({ it.codecs.isLossless() }, { it.bandwidth }))
    }

    private fun String.isLossless() = startsWith("flac") || startsWith("alac")

    /** [plan]'s own init/media URLs go in untouched — they are already absolute CDN links. */
    private fun playlist(plan: Plan): String = buildString {
        val targetMs = plan.durationsMs.maxOrNull() ?: 0L
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:7")
        appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
        appendLine("#EXT-X-TARGETDURATION:${ceil(targetMs / 1000.0).toInt()}")
        appendLine("""#EXT-X-MAP:URI="${plan.initialization}"""")
        plan.media.forEachIndexed { index, url ->
            appendLine("#EXTINF:${seconds(plan.durationsMs.getOrElse(index) { 0L })},")
            appendLine(url)
        }
        appendLine("#EXT-X-ENDLIST")
    }

    /** [ms] as `12.345` — ASCII digits only, so no locale's own numerals land in the playlist. */
    private fun seconds(ms: Long): String =
        "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"

    private fun unescape(value: String) = value
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    /** `PT3M50.461S` → `230.461`. Hours, minutes and seconds, which is all a track ever needs. */
    private fun isoSeconds(value: String): Double? {
        val match = Regex("""PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?""").find(value) ?: return null
        val (h, m, s) = match.destructured
        val seconds = (h.toDoubleOrNull() ?: 0.0) * 3600 + (m.toDoubleOrNull() ?: 0.0) * 60 + (s.toDoubleOrNull() ?: 0.0)
        return seconds.takeIf { it > 0 }
    }
}
