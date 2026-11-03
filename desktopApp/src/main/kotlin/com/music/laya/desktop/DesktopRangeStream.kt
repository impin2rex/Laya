package com.music.laya.desktop

import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_SIZE
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avio_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avio_context_free
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.av_free
import org.bytedeco.ffmpeg.global.avutil.av_malloc
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import com.music.laya.data.innertube.StreamResolver
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

/** Reads a remote file in bounded windows rather than as one open-ended request. */
internal class DesktopRangeStream(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val windowBytes: Int = DEFAULT_WINDOW,
) {

    /** Total length in bytes, or -1 until the first response has stated one. */
    var length: Long = -1L
        private set

    private var position = 0L
    private var window = ByteArray(0)

    /** Where [window] starts in the file; -1 when nothing is held. */
    private var windowStart = -1L

    /** The window after the one held, already on its way: a read never waits on the network mid-file. */
    private var ahead: Pair<Long, CompletableFuture<HttpResponse<ByteArray>?>>? = null

    /** Where the next read will come from. */
    fun position(): Long = position

    fun seek(offset: Long) {
        position = offset.coerceAtLeast(0)
    }

    /**
     * Fills up to [count] bytes at the current position, returning how many — or -1 at the end of
     * the file.
     */
    fun read(into: ByteArray, count: Int): Int {
        if (count <= 0) return 0
        if (length >= 0 && position >= length) return -1
        if (!holds(position) && !fetchWindowFor(position)) return -1
        val offset = (position - windowStart).toInt()
        val available = window.size - offset
        if (available <= 0) return -1
        val taken = minOf(count, available)
        System.arraycopy(window, offset, into, 0, taken)
        position += taken
        return taken
    }

    private fun holds(at: Long): Boolean =
        windowStart >= 0 && at >= windowStart && at < windowStart + window.size

    /** Fetches the window containing [at], from the one fetched ahead when it is that one. */
    private fun fetchWindowFor(at: Long): Boolean {
        val start = at - (at % windowBytes)
        val pending = ahead?.takeIf { it.first == start }?.second
        if (pending == null) ahead?.second?.cancel(true)
        ahead = null
        val response = pending?.let { runCatching { it.join() }.getOrNull() }
            ?: runCatching { client.send(request(start), HttpResponse.BodyHandlers.ofByteArray()) }.getOrNull()
            ?: return false
        if (response.statusCode() !in 200..299) {
            // A URL cleared before playback has been refused mid-track: tell the resolver, as the
            // phone's ChunkedDataSource does, so it forgets it and benches the client that minted it.
            StreamResolver.onPlaybackRefused(url, response.statusCode())
            return false
        }

        val body = response.body()
        // A server that honoured the range says where the bytes came from and how many there are in
        // total.
        val stated = response.headers().firstValue("content-range").orElse(null)
        if (stated != null) {
            windowStart = stated.substringAfter("bytes ").substringBefore('-').trim().toLongOrNull() ?: start
            length = stated.substringAfterLast('/').trim().toLongOrNull() ?: length
        } else {
            windowStart = 0
            length = body.size.toLong()
        }
        window = body
        if (length < 0) length = windowStart + body.size
        val next = windowStart + body.size
        if (stated != null && next < length) {
            ahead = next to client.sendAsync(request(next), HttpResponse.BodyHandlers.ofByteArray())
                .exceptionally { null }
        }
        return body.isNotEmpty()
    }

    private fun request(start: Long): HttpRequest {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Range", "bytes=$start-${start + windowBytes - 1}")
            .header("Accept", "*/*")
            // Anything else would arrive compressed and the byte offsets the caller is counting in
            // would stop meaning anything.
            .header("Accept-Encoding", "identity")
        headers.forEach { (name, value) -> runCatching { request.header(name, value) } }
        return request.GET().build()
    }

    /** Lets go of the window on its way, for a stream no longer read. */
    fun close() {
        ahead?.second?.cancel(true)
        ahead = null
    }

    private companion object {
        /** Comfortably inside what Google will serve. */
        const val DEFAULT_WINDOW = 512 * 1024

        val client: HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }
}

/**
 * FFmpeg reading through [DesktopRangeStream] rather than its own HTTP client: a format context
 * whose bytes arrive a window at a time, which googlevideo serves at full speed where one
 * open-ended request is throttled to about real time. [close] after the context is closed —
 * custom I/O is not freed by avformat_close_input.
 */
internal class DesktopRangeIo(url: String, headers: Map<String, String>, windowBytes: Int? = null) {
    private val source = if (windowBytes != null) DesktopRangeStream(url, headers, windowBytes) else DesktopRangeStream(url, headers)
    private val scratch = ByteArray(IO_BUFFER_BYTES)
    private var read: Read_packet_Pointer_BytePointer_int? = object : Read_packet_Pointer_BytePointer_int() {
        override fun call(opaque: Pointer?, buffer: BytePointer?, size: Int): Int {
            if (buffer == null) return AVERROR_EOF
            val taken = source.read(scratch, minOf(size, scratch.size))
            if (taken <= 0) return AVERROR_EOF
            buffer.put(scratch, 0, taken)
            return taken
        }
    }
    private var seek: Seek_Pointer_long_int? = object : Seek_Pointer_long_int() {
        override fun call(opaque: Pointer?, offset: Long, whence: Int): Long {
            // FFmpeg asks for the length this way rather than with a seek.
            if (whence == AVSEEK_SIZE) return source.length
            val target = when (whence) {
                SEEK_CUR -> source.position() + offset
                SEEK_END -> source.length + offset
                else -> offset
            }
            source.seek(target)
            return target
        }
    }
    private var avio: AVIOContext? = avio_alloc_context(
        BytePointer(av_malloc(IO_BUFFER_BYTES.toLong())), IO_BUFFER_BYTES, 0, null, read, null, seek,
    )

    /** avformat_open_input sets AVFMT_FLAG_CUSTOM_IO itself when it finds a pb already there. */
    val context: AVFormatContext = avformat_alloc_context().also { it.pb(avio) }

    fun close() {
        source.close()
        avio?.let {
            av_free(it.buffer())
            it.buffer(null as BytePointer?)
            avio_context_free(it)
        }
        avio = null
        read?.deallocate()
        seek?.deallocate()
        read = null
        seek = null
    }

    private companion object {
        /** How much FFmpeg buffers between calls into [DesktopRangeStream]. */
        const val IO_BUFFER_BYTES = 64 * 1024
        const val SEEK_CUR = 1
        const val SEEK_END = 2
    }
}
