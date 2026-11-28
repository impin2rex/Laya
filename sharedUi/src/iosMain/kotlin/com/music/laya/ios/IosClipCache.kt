package com.music.laya.ios

import com.music.laya.data.canvas.AppleClip
import com.music.laya.platform.HttpRequest
import com.music.laya.platform.NetHttp
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * Canvas and artist clips on disk, as Android's CanvasCache keeps them: fetched once, then looped from
 * the file on every later visit. Apple's playlists are flattened to their one MP4 ([AppleClip]), which
 * also keeps HLS out of AVPlayerLooper. Least recently shown go first past [LIMIT_BYTES].
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal object IosClipCache {
    /** Android's CanvasCache limit: a clip is seconds of video, so this holds a few hundred. */
    private const val LIMIT_BYTES = 150L * 1024 * 1024

    private val fs = FileSystem.SYSTEM
    private val dir: Path by lazy {
        (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String).toPath() / "clips"
    }

    /** A file URL for the clip at [url]: kept from before, or fetched now and kept. Null to stream it instead. Blocking. */
    fun fileFor(url: String): String? {
        val target = dir / (url.encodeUtf8().sha256().hex() + ".mp4")
        if (fs.exists(target)) {
            NSFileManager.defaultManager.setAttributes(mapOf(NSFileModificationDate to NSDate()), target.toString(), null)
            return fileUrl(target)
        }
        val direct = AppleClip.directUrl(url) ?: return null
        val body = runCatching { NetHttp.execute(HttpRequest(direct)) }.getOrNull()
            ?.takeIf { it.isSuccessful && it.body.isNotEmpty() }?.body ?: return null
        return runCatching {
            fs.createDirectories(dir)
            val tmp = dir / "${target.name}.part"
            fs.write(tmp) { write(body) }
            fs.atomicMove(tmp, target)
            trim()
            fileUrl(target)
        }.getOrNull()
    }

    fun clear() {
        runCatching { fs.deleteRecursively(dir) }
    }

    private fun trim() {
        val files = fs.list(dir).mapNotNull { f -> fs.metadataOrNull(f)?.let { f to it } }
            .sortedByDescending { it.second.lastModifiedAtMillis ?: 0L }
        var kept = 0L
        files.forEach { (file, meta) ->
            kept += meta.size ?: 0L
            if (kept > LIMIT_BYTES) runCatching { fs.delete(file) }
        }
    }

    private fun fileUrl(path: Path): String = NSURL.fileURLWithPath(path.toString()).absoluteString ?: "file://$path"
}
