package com.music.laya.data.sources

import com.music.laya.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * How big a stream's file is, without reading any of it — for catching a source
 * that answers a full-length row with its 30-second preview. Nothing in the
 * catalogue answer tells those apart; the file's size can, because a file has to
 * be big enough to hold the audio it claims to. Used by Direct Source Stream on
 * the phone and the desktop, whose answer plays with no audition behind it.
 */
object StreamLengthProbe {

    /**
     * The file's total size in bytes, or null when the server won't say. One ranged
     * GET for a single byte, which every server that streams audio supports for
     * seeking; a server ignoring the range answers 200 with the whole length.
     */
    suspend fun bytesOf(url: String, headers: Map<String, String> = emptyMap()): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .header("Range", "bytes=0-0")
                .build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    206 -> response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                    200 -> response.header("Content-Length")?.toLongOrNull()
                    else -> null
                }?.takeIf { it > 0 }
            }
        }.getOrNull()
    }

    /**
     * Whether [bytes] can't hold [expectedSec] of audio even at the lowest bitrate
     * this kind of stream plausibly runs at, give or take [SLACK_SEC]. Leaning that
     * way means a real copy is never refused for an optimistic bitrate claim.
     */
    fun tooShort(bytes: Long, lossless: Boolean, kbps: Int?, expectedSec: Int): Boolean {
        if (bytes <= 0 || expectedSec <= 0) return false
        val floorKbps = if (lossless) {
            LOSSLESS_FLOOR_KBPS
        } else {
            minOf(kbps?.takeIf { it > 0 } ?: LOSSY_FLOOR_KBPS, LOSSY_FLOOR_KBPS)
        }
        return bytes * 8.0 / (floorKbps * 1000.0) < expectedSec - SLACK_SEC
    }

    private val client by lazy { Http.client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build() }

    private const val LOSSLESS_FLOOR_KBPS = 200
    private const val LOSSY_FLOOR_KBPS = 64
    private const val SLACK_SEC = 5
}
