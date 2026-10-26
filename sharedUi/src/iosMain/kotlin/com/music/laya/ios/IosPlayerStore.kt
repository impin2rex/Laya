package com.music.laya.ios

import com.music.laya.data.model.PlaybackSourceType
import com.music.laya.data.model.QueueTier
import com.music.laya.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/**
 * Restart snapshot of the queue, the iOS twin of Android's LastPlayed (same window: 25 back, 50 ahead).
 * Shuffle / repeat / AutoPlay already persist in AppSettings. The queue JSON is written on queue
 * changes only; position / duration are scalars, cheap enough for the progress cadence.
 */
internal object IosPlayerStore {
    class Snapshot(val songs: List<Song>, val index: Int, val positionMs: Long, val durationMs: Long)

    @Serializable
    private class Track(
        val id: String, val title: String, val artist: String, val artwork: String? = null,
        val duration: String? = null, val album: String? = null, val albumId: String? = null,
        val artistId: String? = null, val explicit: Boolean? = null, val video: Boolean = false,
        val tier: String = "CONTEXT", val entryId: String? = null, val radio: String? = null,
        val source: String? = null, val sourceType: String? = null, val sourceId: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val d get() = NSUserDefaults.standardUserDefaults
    private const val BACK = 25
    private const val AHEAD = 50

    fun saveQueue(songs: List<Song>, index: Int) {
        if (songs.isEmpty()) return clear()
        val at = index.coerceIn(songs.indices)
        val from = (at - BACK).coerceAtLeast(0)
        val tracks = songs.subList(from, (at + 1 + AHEAD).coerceAtMost(songs.size)).map {
            Track(
                it.videoId, it.title, it.artist, it.thumbnailUrl, it.durationText, it.albumName, it.albumId,
                it.artistId, it.isExplicit, it.isVideo, it.queueTier.name, it.queueEntryId, it.radioName,
                it.playbackSource, it.playbackSourceType?.name, it.playbackSourceId,
            )
        }
        d.setObject(json.encodeToString(tracks), "ios_last_queue")
        d.setInteger((at - from).toLong(), "ios_last_index")
    }

    fun saveState(positionMs: Long, durationMs: Long) {
        d.setInteger(positionMs.coerceAtLeast(0), "ios_last_position")
        d.setInteger(durationMs.coerceAtLeast(0), "ios_last_duration")
    }

    fun clear() {
        listOf("ios_last_queue", "ios_last_index", "ios_last_position", "ios_last_duration").forEach(d::removeObjectForKey)
    }

    fun load(): Snapshot? {
        val raw = d.stringForKey("ios_last_queue") ?: return null
        val tracks = runCatching { json.decodeFromString<List<Track>>(raw) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val songs = tracks.map {
            Song(
                videoId = it.id, title = it.title, artist = it.artist, thumbnailUrl = it.artwork,
                durationText = it.duration, albumName = it.album, albumId = it.albumId, artistId = it.artistId,
                isExplicit = it.explicit, isVideo = it.video,
                queueTier = runCatching { QueueTier.valueOf(it.tier) }.getOrDefault(QueueTier.CONTEXT),
                queueEntryId = it.entryId, radioName = it.radio, playbackSource = it.source,
                playbackSourceType = it.sourceType?.let { s -> runCatching { PlaybackSourceType.valueOf(s) }.getOrNull() },
                playbackSourceId = it.sourceId,
            )
        }
        return Snapshot(
            songs, d.integerForKey("ios_last_index").toInt().coerceIn(songs.indices),
            d.integerForKey("ios_last_position"), d.integerForKey("ios_last_duration"),
        )
    }
}
