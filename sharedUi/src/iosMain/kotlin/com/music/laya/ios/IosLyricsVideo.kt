package com.music.laya.ios

import com.music.laya.data.model.Song
import com.music.laya.ui.share.VideoSharer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import kotlin.coroutines.resume

/**
 * What the lyrics share video needs from Swift (iosApp/iosApp/LyricsVideoNative.swift):
 * AVFoundation's H.264 writer for the frames, and the song's audio cut to the picked stretches
 * and muxed under them. Called from a worker thread, never the main one; every call blocks.
 */
@OptIn(ExperimentalForeignApi::class)
interface LyricsVideoNative {
    /** Opens a silent H.264 writer for [width] x [height] frames at [fps]; false if it can't be set up. */
    fun begin(path: String, width: Int, height: Int, fps: Int): Boolean

    /** Frame [index]: [pixels] is `width * height` BGRA pixels, row by row (Kotlin's ARGB ints, little-endian). */
    fun append(pixels: CPointer<ByteVar>, index: Int): Boolean

    /**
     * Closes the frames and writes the MP4 to begin's path: with the stream at [audioUrl] (a file
     * or the player's own source, [headers] and all) cut to [segmentsMs] — start, end, start, end
     * — laid end to end as AAC, or silent when [audioUrl] is null or its audio can't be read.
     */
    fun finish(
        audioUrl: String?,
        headers: Map<String, String>,
        mimeType: String,
        contentLength: Long,
        chunkBytes: Long,
        segmentsMs: List<Long>,
    ): Boolean

    /** Drops a half-written video. */
    fun cancel()
}

object IosLyricsVideo {
    var native: LyricsVideoNative? = null
}

/** The player's opened stream for a song, as Karaoke and Automix read it. */
internal class IosShareStream(
    val url: String,
    val headers: Map<String, String>,
    val mime: String,
    val length: Long,
    val chunkBytes: Long,
)

/**
 * The share video on iOS: the common frames through [LyricsVideoNative], the song from the stream
 * the player opened it with — so a song that was never played this run (nothing to cut from)
 * comes out silent rather than starting a second resolve here.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosVideoSharer : VideoSharer {
    override suspend fun encode(
        song: Song,
        width: Int,
        height: Int,
        fps: Int,
        frames: Int,
        segments: List<Pair<Long, Long>>,
        frame: suspend (index: Int, argb: IntArray) -> Unit,
    ): String? = withContext(Dispatchers.Default) {
        val native = IosLyricsVideo.native ?: return@withContext null
        val path = NSTemporaryDirectory() + VIDEO_FILE_NAME
        NSFileManager.defaultManager.removeItemAtPath(path, null)
        if (!native.begin(path, width, height, fps)) return@withContext null
        val argb = IntArray(width * height)
        try {
            for (index in 0 until frames) {
                frame(index, argb)
                val ok = argb.usePinned { native.append(it.addressOf(0).reinterpret(), index) }
                if (!ok) {
                    native.cancel()
                    return@withContext null
                }
            }
        } catch (e: CancellationException) {
            native.cancel()
            throw e
        }
        val source = IosPlayer.shareStream(song.videoId)
        val written = native.finish(
            audioUrl = source?.url,
            headers = source?.headers.orEmpty(),
            mimeType = source?.mime.orEmpty(),
            contentLength = source?.length ?: 0L,
            chunkBytes = source?.chunkBytes ?: 0L,
            segmentsMs = segments.flatMap { listOf(it.first, it.second) },
        )
        if (written) path else null
    }

    override suspend fun share(path: String): Boolean = withContext(Dispatchers.Main) {
        presentShare(listOf(NSURL.fileURLWithPath(path)))
        true
    }

    override suspend fun save(path: String, name: String): Boolean {
        val allowed = suspendCancellableCoroutine { cont ->
            PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
                cont.resume(status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited)
            }
        }
        if (!allowed) return false
        return suspendCancellableCoroutine { cont ->
            PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                { PHAssetChangeRequest.creationRequestForAssetFromVideoAtFileURL(NSURL.fileURLWithPath(path)) },
                completionHandler = { ok, error ->
                    if (error != null) println("IosVideoSharer: save failed ${error.localizedDescription}")
                    cont.resume(ok)
                },
            )
        }
    }
}

private const val VIDEO_FILE_NAME = "lyrics.mp4"
