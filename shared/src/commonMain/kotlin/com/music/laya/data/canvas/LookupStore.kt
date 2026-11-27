package com.music.laya.data.canvas

import com.music.laya.data.lyrics.LyricsTranslation
import com.music.laya.platform.currentTimeMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okio.FileSystem

/**
 * A name-keyed lookup's answers kept on disk, so an artist page opened again after a restart draws
 * its art and signature without asking the network first. A hit is kept [HIT_TTL_MS], a "has none"
 * [MISS_TTL_MS] (the artist may get art later), at most [MAX_ENTRIES], oldest out. Lives in the app's
 * cache directory ([LyricsTranslation.cacheDir]); with none set, it keeps nothing.
 */
class LookupStore<T : Any>(private val name: String, valueSerializer: KSerializer<T>) {
    @Serializable
    private class Stored(val value: String? = null, val at: Long = 0)

    private val values = valueSerializer
    private val fileSerializer = MapSerializer(String.serializer(), Stored.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val guard = SynchronizedObject()
    private var loaded: LinkedHashMap<String, Stored>? = null

    private val file get() = LyricsTranslation.cacheDir?.let { it / DIRECTORY / "$name.json" }

    private fun entries(): LinkedHashMap<String, Stored> = loaded ?: LinkedHashMap<String, Stored>().also { map ->
        file?.let { f ->
            runCatching { json.decodeFromString(fileSerializer, FileSystem.SYSTEM.read(f) { readUtf8() }) }.getOrNull()?.let(map::putAll)
        }
        loaded = map
    }

    /** The kept answer for [key]: null when there is none (or it went stale), `Result(null)` for a known "has none". */
    fun get(key: String): Result<T?>? = synchronized(guard) {
        val stored = entries()[key] ?: return@synchronized null
        val ttl = if (stored.value == null) MISS_TTL_MS else HIT_TTL_MS
        if (currentTimeMillis() - stored.at > ttl) return@synchronized null
        Result.success(stored.value?.let { runCatching { json.decodeFromString(values, it) }.getOrNull() ?: return@synchronized null })
    }

    /** Remembers [value] for [key] and writes the file. Call off the main thread. */
    fun put(key: String, value: T?) = synchronized(guard) {
        val map = entries()
        map.remove(key)
        map[key] = Stored(value?.let { json.encodeToString(values, it) }, currentTimeMillis())
        while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
        save(map)
    }

    fun clear() = synchronized(guard) {
        loaded = LinkedHashMap()
        file?.let { runCatching { FileSystem.SYSTEM.delete(it) } }
    }

    private fun save(map: Map<String, Stored>) {
        val f = file ?: return
        runCatching {
            FileSystem.SYSTEM.createDirectories(f.parent!!)
            val tmp = f.parent!! / "$name.tmp"
            FileSystem.SYSTEM.write(tmp) { writeUtf8(json.encodeToString(fileSerializer, map)) }
            FileSystem.SYSTEM.atomicMove(tmp, f)
        }
    }

    private companion object {
        const val DIRECTORY = "lookups"
        const val MAX_ENTRIES = 1_000
        const val HIT_TTL_MS = 14L * 24 * 3600 * 1000
        const val MISS_TTL_MS = 3L * 24 * 3600 * 1000
    }
}
