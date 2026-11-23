package com.music.laya.download

import com.music.laya.data.Http
import com.music.laya.download.Mp4Boxes.boxes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.OutputStream

/**
 * A manifest's segments joined back into one ordinary file, so a lossless
 * download is a `.flac` whether it stays in the app or is exported.
 *
 * [OfflineHls] and [OfflineDash] keep a manifest as a package only Laya can
 * play, which is why [Downloads.routeFor][Downloads] used to decline a manifest
 * outright whenever "export downloads" was on — and declining sent the track to
 * YouTube. With Tidal answering every lossless request with a manifest, that
 * meant an exporting user who heard a 24-bit FLAC in the player got a 131kbps
 * AAC file on disk, with nothing in the log between the two:
 *
 * ```
 *   15:03:44  download: 'Katiya Karun' from Unified (P kuxhagra) at FLAC · 24-bit
 *   15:03:44  downloading Hmell3OeY5A as .m4a (131kbps audio/mp4; codecs="mp4a.40.2", Lossless)
 * ```
 *
 * The segments are CMAF: one initialisation segment and a run of `moof`+`mdat`
 * fragments. How they come back together depends on what is being filed:
 *
 *  - **`.flac`** — the samples *are* FLAC frames, and the sample entry's `dfLa`
 *    holds the stream's own metadata blocks, so the file is `fLaC`, those
 *    blocks, then every frame in order. That is a native FLAC any player opens,
 *    and the one [FlacTagger] tags. STREAMINFO's sample count is rewritten from
 *    the fragments' durations: without it Media3's FLAC reader cannot seek.
 *  - **`.m4a`** — init then fragments, verbatim, as a fragmented MP4. Each
 *    segment's own `styp`/`sidx` is dropped, since a per-segment index read as
 *    the whole file's is wrong; tagging then adds one index for the lot
 *    ([Mp4Sidx]).
 */
internal object ManifestFile {

    /** The extensions this can produce; a manifest bound for anything else stays declined. */
    val EXTENSIONS = setOf("flac", "m4a")

    suspend fun write(
        cacheDir: File,
        url: String,
        headers: Map<String, String>,
        dash: Boolean,
        extension: String,
        sink: OutputStream,
        onProgress: (Long, Long) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        fun fetch(target: String): ByteArray {
            val request = Request.Builder().url(target).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            return Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Segment failed (HTTP ${response.code})")
                response.body.bytes()
            }
        }

        // Tidal's manifest arrives as text and is kept in a cache file; its segment URLs are absolute.
        val local = url.startsWith("file:")
        val base = url.toHttpUrlOrNull() ?: if (local) null else error("Invalid manifest URL")
        val index = if (local) File(java.net.URI(url)).readText() else String(fetch(url), Charsets.UTF_8)
        val (initRef, mediaRefs) = if (dash) {
            OfflineDash.parse(index).let { it.initialization to it.media }
        } else {
            hlsSegments(index)
        }
        fun resolve(ref: String) = (ref.toHttpUrlOrNull() ?: base?.resolve(ref))?.toString() ?: error("Invalid segment URL")
        val total = mediaRefs.size + 1L

        val init = fetch(resolve(initRef))
        onProgress(1, total)
        // Lazy, so a hi-res track is never more than one segment in memory.
        val scope = coroutineContext
        val media = mediaRefs.asSequence().mapIndexed { i, ref ->
            scope.ensureActive()
            fetch(resolve(ref)).also { onProgress(i + 2L, total) }
        }

        assemble(cacheDir, extension, init, media, sink)
        sink.flush()
    }

    /** Everything after the fetching: [init] and [media] in, one [extension] file out. */
    internal fun assemble(cacheDir: File, extension: String, init: ByteArray, media: Sequence<ByteArray>, sink: OutputStream) {
        when (extension) {
            "flac" -> writeFlac(cacheDir, init, media, sink)
            "m4a" -> {
                sink.write(init)
                media.forEach { segment ->
                    boxes(segment).filter { it.type != "styp" && it.type != "sidx" }.forEach {
                        sink.write(segment, it.offset, it.size)
                    }
                }
            }
            else -> error("Can't save a .$extension from a manifest")
        }
    }

    /** The init segment and media segments of an HLS media playlist, refusing what [OfflineHls] refuses. */
    internal fun hlsSegments(playlist: String): Pair<String, List<String>> {
        if (!playlist.startsWith("#EXTM3U")) error("Invalid HLS playlist")
        if (playlist.contains("#EXT-X-KEY:") && !playlist.contains("METHOD=NONE")) {
            error("Encrypted HLS cannot be saved")
        }
        val init = Regex("""#EXT-X-MAP:.*URI="([^"]+)"""").find(playlist)?.groupValues?.get(1)
            ?: error("HLS playlist has no initialisation segment")
        val media = playlist.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .toList()
        if (media.isEmpty()) error("HLS playlist has no fragments")
        return init to media
    }

    /**
     * FLAC-in-MP4 to native FLAC, by the shared [FlacFromMp4] that iOS writes its downloads with
     * too. The frames go to a temporary file first because STREAMINFO, which comes before them,
     * carries a total that is only known once the last fragment is in — and a hi-res track is too
     * large to hold in memory while waiting for it.
     */
    private fun writeFlac(cacheDir: File, init: ByteArray, media: Sequence<ByteArray>, sink: OutputStream) {
        val stream = FlacFromMp4.open(init)
        val frames = File.createTempFile("manifest-", ".flac", cacheDir)
        try {
            var ticks = 0L
            frames.sink().buffer().use { out -> media.forEach { ticks += stream.frames(it, out) } }
            frames.source().buffer().use { spilled ->
                sink.sink().buffer().use { out -> stream.finish(ticks, spilled, out) }
            }
        } finally {
            frames.delete()
        }
    }
}
