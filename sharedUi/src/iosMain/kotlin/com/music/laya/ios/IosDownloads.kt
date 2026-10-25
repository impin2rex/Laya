package com.music.laya.ios

import com.music.laya.data.TrackLog
import com.music.laya.data.innertube.PlayerClient
import com.music.laya.data.innertube.StreamResolver
import com.music.laya.data.model.DetailPage
import com.music.laya.data.model.Song
import com.music.laya.data.model.UiState
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.settings.DownloadQuality
import com.music.laya.data.sources.SourceResolver
import com.music.laya.data.sources.SourceStream
import com.music.laya.data.sources.TrackMatcher
import com.music.laya.platform.ioDispatcher
import com.music.laya.platform.ktorClient
import com.music.laya.playback.youtubeSeedFor
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970

/** Where a track is between "not on this device" and "on it" (Android's DownloadState). */
internal sealed interface IosDownloadState {
    data object Queued : IosDownloadState
    /** 0f until the length is known. */
    data class Running(val fraction: Float) : IosDownloadState
    data class Failed(val reason: String) : IosDownloadState
}

/** Browse id of the Library's Downloads page, the same one Android uses. */
internal const val DOWNLOADS_BROWSE_ID = "local:downloads"

/**
 * Song downloads, the iOS twin of Android's `Downloads`: same quality setting, same source-first
 * route (an addon's bit-exact file when the setting keeps lossless, else YouTube's AAC), saved in
 * Application Support/Downloads (excluded from backup) with an index and the artwork beside it.
 * [IosPlayer] prefers [fileUrl] over any network stream.
 *
 * ponytail: runs in the app's own process, so a transfer survives a short backgrounding (a track is
 * a few MB) but not the app being killed; a background URLSession is the upgrade if that matters.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal object IosDownloads {
    private const val SOURCE_LOOKUP_MS = 20_000L
    private const val CHUNK_BYTES = 2L * 1024 * 1024

    @Serializable
    private class Entry(
        val id: String, val title: String, val artist: String, val file: String,
        val artwork: String? = null, val thumb: String? = null, val duration: String? = null,
        val album: String? = null, val albumId: String? = null, val artistId: String? = null,
        val explicit: Boolean? = null, val video: Boolean = false, val badge: String? = null, val added: Double = 0.0,
    )

    private class Route(val ext: String, val badge: String?, val fetch: suspend (BufferedSink, (Long, Long) -> Unit) -> Unit)

    private val fs = FileSystem.SYSTEM
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { ktorClient { } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val gate = Mutex() // one transfer at a time, the rest read "Queued"
    private val jobs = mutableMapOf<String, Job>()

    private val dir: Path by lazy {
        val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        (base.toPath() / "Downloads").also {
            fs.createDirectories(it)
            // A transfer cut off by the app being killed leaves its .part behind.
            fs.list(it).filter { f -> f.name.endsWith(".part") }.forEach { f -> fs.delete(f) }
            NSURL.fileURLWithPath(it.toString()).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
        }
    }
    private val indexFile get() = dir / "index.json"

    private val _active = MutableStateFlow<Map<String, IosDownloadState>>(emptyMap())
    val active: StateFlow<Map<String, IosDownloadState>> = _active.asStateFlow()

    /** videoId to what was saved for it. Entries are checked against the disk before anything plays them. */
    private val saved = MutableStateFlow(readIndex())
    /** For the mark on rows and the menu row; a change here is also the cue to rebuild [page]. */
    val savedIds: StateFlow<Set<String>> get() = savedIdsFlow
    private val savedIdsFlow = MutableStateFlow(saved.value.keys)

    private fun readIndex(): Map<String, Entry> = runCatching {
        json.decodeFromString(ListSerializer(Entry.serializer()), fs.read(indexFile) { readUtf8() }).associateBy { it.id }
    }.getOrDefault(emptyMap())

    private fun writeIndex(entries: Map<String, Entry>) {
        saved.value = entries
        savedIdsFlow.value = entries.keys
        val tmp = dir / "index.json.tmp"
        fs.write(tmp) { writeUtf8(json.encodeToString(ListSerializer(Entry.serializer()), entries.values.toList())) }
        fs.atomicMove(tmp, indexFile)
    }

    // ---- Reading ------------------------------------------------------------

    private fun fileOf(e: Entry) = dir / e.file

    private fun onDisk(e: Entry) = fs.metadataOrNull(fileOf(e))?.size?.let { it > 0 } == true

    /** file:// URL of the saved copy of [videoId], or null (and the stale record dropped) when it is gone. */
    fun fileUrl(videoId: String): String? {
        val e = saved.value[videoId] ?: return null
        if (!onDisk(e)) { writeIndex(saved.value - videoId); return null }
        return "file://${fileOf(e)}"
    }

    private fun localSong(e: Entry) = Song(
        videoId = e.id, title = e.title, artist = e.artist,
        thumbnailUrl = e.artwork?.takeIf { fs.exists(dir / it) }?.let { "file://${dir / it}" } ?: e.thumb,
        durationText = e.duration, artistId = e.artistId, albumId = e.albumId, albumName = e.album,
        isExplicit = e.explicit, isVideo = e.video,
        localUri = "file://${fileOf(e)}", localPath = fileOf(e).toString(), downloadFormat = e.badge,
    )

    /** The Downloads page, newest first. */
    fun page(): DetailPage {
        val songs = saved.value.values.filter(::onDisk).sortedByDescending { it.added }.map(::localSong)
        return DetailPage(DOWNLOADS_BROWSE_ID, "Downloads", if (songs.size == 1) "1 song" else "${songs.size} songs", songs.firstOrNull()?.thumbnailUrl, UiState.Success(songs))
    }

    // ---- Asking -------------------------------------------------------------

    fun enqueue(song: Song) {
        val id = song.videoId
        if (id in jobs || fileUrl(id) != null) return
        if (!AppSettings.downloadsAllowedNow) return fail(id, "Waiting for Wi-Fi (see Settings, Downloads)")
        set(id, IosDownloadState.Queued)
        jobs[id] = scope.launch {
            try {
                gate.withLock { withContext(ioDispatcher) { run(song) } }
                clear(id)
            } catch (e: CancellationException) {
                clear(id)
                throw e
            } catch (e: Throwable) {
                TrackLog.w("Laya", "download of $id failed: ${e.message}", about = id)
                fail(id, e.message?.takeIf { it.length < 80 } ?: "Download failed")
            } finally {
                jobs.remove(id)
            }
        }
    }

    fun cancel(videoId: String) { jobs[videoId]?.cancel() }

    fun dismissFailure(videoId: String) { if (_active.value[videoId] is IosDownloadState.Failed) clear(videoId) }

    fun delete(videoId: String) {
        val e = saved.value[videoId] ?: return
        writeIndex(saved.value - videoId)
        fs.delete(fileOf(e), mustExist = false)
        e.artwork?.let { fs.delete(dir / it, mustExist = false) }
    }

    private fun set(id: String, state: IosDownloadState) = _active.update { it + (id to state) }
    private fun clear(id: String) = _active.update { it - id }
    private fun fail(id: String, reason: String) = set(id, IosDownloadState.Failed(reason))

    // ---- Doing --------------------------------------------------------------

    private suspend fun run(song: Song) {
        val id = song.videoId
        set(id, IosDownloadState.Running(0f))
        val quality = AppSettings.downloadQuality.value // pinned for this file
        val route = routeFor(song, quality)
        val name = id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("") + "-" + id.hashCode().toUInt().toString(16)
        val target = dir / "$name.${route.ext}"
        val tmp = dir / "$name.part"
        try {
            var shown = -1
            fs.sink(tmp).buffer().use { sink ->
                route.fetch(sink) { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != shown) { shown = pct; set(id, IosDownloadState.Running(pct / 100f)) }
                }
            }
            check(fs.metadataOrNull(tmp)?.size?.let { it > 0 } == true) { "Nothing was downloaded" }
            fs.atomicMove(tmp, target)
        } finally {
            fs.delete(tmp, mustExist = false)
        }
        val artwork = song.thumbnailUrl?.takeIf { it.startsWith("http") }?.let { saveArtwork(it, dir / "$name.jpg") }
        writeIndex(
            saved.value + (id to Entry(
                id, song.title, song.artist, target.name, artwork?.name, song.thumbnailUrl, song.durationText, song.albumName,
                song.albumId, song.artistId, song.isExplicit, song.isVideo, route.badge, NSDate().timeIntervalSince1970,
            )),
        )
        TrackLog.d("Laya", "downloaded $id as ${target.name} (${route.badge ?: route.ext})", about = id)
    }

    private suspend fun saveArtwork(url: String, to: Path): Path? = runCatching {
        // Rows carry a 120px thumbnail; the saved copy also fills the player and the lock screen.
        val bytes = client.get(url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")).bodyAsBytes()
        fs.write(to) { write(bytes) }
        to
    }.getOrNull()

    /** The stream to keep: a source's file when one is storable, else YouTube's AAC under the quality's ceiling. */
    private suspend fun routeFor(song: Song, quality: DownloadQuality): Route {
        fromSources(song, quality)?.let { (stream, ext) ->
            val format = stream.format
            return Route(
                ext, if (format.isDolbyAtmos) "DOLBY" else if (ext == "flac") "FLAC" else null,
            ) { sink, progress -> fetchDirect(stream.url, stream.headers, sink, progress) }
        }
        val ytId = youtubeSeedFor(song) ?: error("No YouTube match for this track")
        // m4a only: AVPlayer can't play the WebM/Opus rungs.
        val stream = StreamResolver.resolveForDownload(ytId, quality.maxKbps, requireM4a = true)
        return Route("m4a", if (stream.kbps > 0) "AAC · ${stream.kbps} kbps" else null) { sink, progress -> fetchRanged(stream.url, sink, progress) }
    }

    /** Android's fromSources: bounded, and null is not a failure, just YouTube's turn. Manifests (HLS/DASH) are not assembled on iOS. */
    private suspend fun fromSources(song: Song, quality: DownloadQuality): Pair<SourceStream, String>? {
        val stream = withTimeoutOrNull(SOURCE_LOOKUP_MS) {
            try {
                SourceResolver.forDownload(TrackMatcher.targetOf(song), SourceResolver.requestForDownload(quality))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackLog.w("Laya", "source lookup failed for ${song.videoId}: ${e.message}", about = song.videoId)
                null
            }
        } ?: return null
        val path = stream.url.substringBefore('?').lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return null
        val ext = when (stream.format.codec?.lowercase()?.trim()) {
            "flac", "x-flac" -> "flac"
            "wav", "x-wav", "wave" -> "wav"
            "aiff" -> "aiff"
            "mp3" -> "mp3"
            "aac" -> if (path.endsWith(".aac")) "aac" else "m4a"
            "alac", "m4a", "mp4", "eac3-joc", "ec3-joc", "dolby-atmos" -> "m4a"
            else -> return null
        }
        return stream to ext
    }

    // ---- Fetching -----------------------------------------------------------

    /** googlevideo paces one long GET to playback speed but serves bounded ranges at line rate, hence ranges (as Android's Downloader.fetch). */
    private suspend fun fetchRanged(url: String, sink: BufferedSink, progress: (Long, Long) -> Unit) {
        val headers = StreamResolver.mediaHeadersFor(url)
        val total = Url(url).parameters["clen"]?.toLongOrNull()?.takeIf { it > 0 } ?: client.get(url) {
            headers.forEach { (k, v) -> header(k, v) }
            header("Range", "bytes=0-0")
        }.headers["Content-Range"]?.substringAfter('/', "")?.toLongOrNull() ?: error("Track unavailable: no length to fetch")
        var pos = 0L
        while (pos < total) {
            val len = minOf(CHUNK_BYTES, PlayerClient.rangeBytesFor(url), total - pos)
            val r = client.get(url) {
                headers.forEach { (k, v) -> header(k, v) }
                header("Range", "bytes=$pos-${pos + len - 1}")
            }
            if (r.status.value !in 200..299) error("Download failed (HTTP ${r.status.value})")
            val bytes = r.bodyAsBytes()
            if (bytes.isEmpty() || bytes.size > len) error("Download stalled at ${pos}B")
            sink.write(bytes)
            pos += bytes.size
            progress(pos, total)
        }
    }

    /**
     * One plain GET, for a source's file (nothing paces it). A connection dropped part way (a lossless
     * file is tens of MB) resumes with Range up to [RESUMES] times, but only when the server answers 206.
     */
    private suspend fun fetchDirect(url: String, headers: Map<String, String>, sink: BufferedSink, progress: (Long, Long) -> Unit) {
        var written = 0L
        var total: Long? = null
        var attempt = 0
        while (true) {
            try {
                client.prepareGet(url) {
                    headers.forEach { (k, v) -> header(k, v) }
                    if (written > 0) header("Range", "bytes=$written-")
                }.execute { r ->
                    if (written > 0 && r.status.value != 206) error("Download stopped at ${written}B")
                    if (r.status.value !in 200..299) error("Download failed (HTTP ${r.status.value})")
                    if (written == 0L) total = r.contentLength()?.takeIf { it > 0 }
                    val channel = r.bodyAsChannel()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = channel.readAvailable(buffer, 0, buffer.size)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                        written += n
                        total?.let { progress(written, it) }
                    }
                }
                if (total != null && written < total!!) error("Download stopped at ${written}B of $total")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (written == 0L || ++attempt > RESUMES) throw e
                TrackLog.w("Laya", "download dropped at ${written}B (${e.message}); resuming")
            }
        }
    }

    private const val RESUMES = 3
}
