package com.music.laya.data.canvas

import com.music.laya.platform.HttpRequest
import com.music.laya.platform.NetHttp

/**
 * Apple's motion clips (artist headers, album motion art) as one file.
 *
 * Their HLS is a master playlist of video-only renditions, and each rendition's media playlist is a
 * single fragmented MP4 addressed by byte ranges, its init segment first. That file is the whole clip,
 * playable as it is, so a player can fetch it once and loop it from disk instead of streaming the
 * playlist again on every visit (and, for a player that reopens the manifest each pass, every loop).
 */
object AppleClip {
    /** Square clips are drawn behind a header, never full screen: 768² is sharp there at a third of 1080²'s bytes. */
    private const val MAX_WIDTH = 768

    /** The rendition to keep from [master] (H.264, so every decoder opens it), or null when there is none. */
    fun pickVariant(master: String, masterUrl: String): String? {
        val lines = master.lines().map { it.trim() }
        val variants = lines.withIndex()
            .filter { it.value.startsWith("#EXT-X-STREAM-INF:") }
            .mapNotNull { (i, info) ->
                val uri = lines.getOrNull(i + 1)?.takeIf { it.isNotEmpty() && !it.startsWith("#") } ?: return@mapNotNull null
                if (!info.contains("avc1")) return@mapNotNull null
                val width = RESOLUTION.find(info)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                val bandwidth = BANDWIDTH.find(info)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                Triple(resolve(uri, masterUrl), width, bandwidth)
            }
        val fits = variants.filter { it.second <= MAX_WIDTH }
        val pick = fits.maxWithOrNull(compareBy({ it.second }, { it.third })) ?: variants.minByOrNull { it.second }
        return pick?.first
    }

    /** The one file every segment of [media] is cut from, or null when the rendition is spread over several. */
    fun singleFile(media: String, mediaUrl: String): String? {
        val init = MAP_URI.find(media)?.groupValues?.get(1) ?: return null
        val segments = media.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct()
        if (segments.isEmpty() || segments.any { it != init }) return null
        return resolve(init, mediaUrl)
    }

    /**
     * [url] as one file to download: itself when it is already a file, the kept rendition's MP4 for an
     * Apple playlist, null when the playlist can't be flattened (stream it instead). Blocking; never throws.
     */
    fun directUrl(url: String): String? {
        if (!isManifest(url)) return url
        return runCatching {
            val variant = pickVariant(get(url) ?: return null, url) ?: return null
            singleFile(get(variant) ?: return null, variant)
        }.getOrNull()
    }

    private fun get(url: String): String? =
        NetHttp.execute(HttpRequest(url)).takeIf { it.isSuccessful }?.text()

    fun isManifest(url: String): Boolean = url.substringBefore('?').substringBefore('#').lowercase().endsWith(".m3u8")

    private fun resolve(uri: String, base: String): String =
        if (uri.startsWith("http://") || uri.startsWith("https://")) uri
        else base.substringBefore('?').substringBeforeLast('/') + "/" + uri

    private val RESOLUTION = Regex("""RESOLUTION=(\d+)x\d+""")
    private val BANDWIDTH = Regex("""(?<![-_A-Z])BANDWIDTH=(\d+)""")
    private val MAP_URI = Regex("""#EXT-X-MAP:URI="([^"]+)"""")
}
