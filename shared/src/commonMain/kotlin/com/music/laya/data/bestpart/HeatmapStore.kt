package com.music.laya.data.bestpart

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import kotlin.time.Clock

/**
 * Most-replayed curves kept on disk between runs, one small JSON file under
 * [root] (the app's cache directory; null keeps nothing). A curve keeps for a
 * month, "this video has no curve" for a week (a new song soon has the views),
 * and at most [MAX_KEPT] videos are kept, newest first. Reads are served from
 * memory after the first load. Failed requests are never stored here.
 */
internal class HeatmapStore(
    private val root: () -> Path?,
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var loaded: Path? = null
    private val kept = HashMap<String, Stored>()

    /** What is known about [id]: not stored at all is null; stored without a curve is `Entry(null)`. Stale entries are returned only when [allowStale]. */
    @Synchronized
    fun get(id: String, allowStale: Boolean = false): Entry? {
        load()
        val s = kept[id] ?: return null
        val heat = s.heatmap()
        val ttl = if (heat != null) MONTH_MS else WEEK_MS
        return if (allowStale || now() - s.fetchedAt < ttl) Entry(heat) else null
    }

    @Synchronized
    fun put(id: String, heat: Heatmap?) {
        load()
        kept[id] = Stored(heat?.segmentMs ?: 0L, heat?.intensity.orEmpty(), now())
        if (kept.size > MAX_KEPT) {
            kept.entries.sortedByDescending { it.value.fetchedAt }.drop(MAX_KEPT).forEach { kept.remove(it.key) }
        }
        val dir = root() ?: return
        val target = dir / DIR / FILE
        val tmp = dir / DIR / "$FILE.tmp"
        runCatching {
            fs.createDirectories(dir / DIR)
            fs.write(tmp) { writeUtf8(json.encodeToString(File(kept.toMap()))) }
            fs.atomicMove(tmp, target)
        }.onFailure { runCatching { fs.delete(tmp) } }
    }

    private fun load() {
        val dir = root()
        if (dir == loaded) return
        loaded = dir
        kept.clear()
        val file = (dir ?: return) / DIR / FILE
        if (!fs.exists(file)) return
        runCatching { json.decodeFromString<File>(fs.read(file) { readUtf8() }).heats }
            .onSuccess { kept.putAll(it) }
            .onFailure { runCatching { fs.delete(file) } } // unreadable: start over
    }

    class Entry(val heat: Heatmap?)

    @Serializable
    private data class File(val heats: Map<String, Stored> = emptyMap())

    @Serializable
    private data class Stored(val segmentMs: Long, val intensity: List<Float>, val fetchedAt: Long) {
        fun heatmap(): Heatmap? = if (segmentMs > 0 && intensity.isNotEmpty()) Heatmap(segmentMs, intensity) else null
    }

    companion object {
        const val MAX_KEPT = 500
        const val MONTH_MS = 30L * 24 * 3600 * 1000
        const val WEEK_MS = 7L * 24 * 3600 * 1000
        private const val DIR = "bestpart"
        private const val FILE = "heatmaps.json"
    }
}
