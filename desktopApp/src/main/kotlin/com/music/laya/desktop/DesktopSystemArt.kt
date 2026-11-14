package com.music.laya.desktop

import com.music.laya.data.model.artworkAt
import com.music.laya.data.model.artworkFallback
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The cover the system's media controls show (MPRIS, Windows' media overlay), as the app's own.
 *
 * A music video's thumbnail is a 4:3 frame with black bars baked in; what is shown instead is its
 * 16:9 frame (hq720, or the 4:3 one on an old upload that has none) cut to its middle square and
 * kept on disk, for a shell that takes a file. Any other cover is square already and goes as it is.
 */
internal object DesktopSystemArt {
    /** [file], a `file://` URI to the square copy, when made; [remote], the picture it was cut from. */
    data class Art(val file: String?, val remote: String)

    /** Bumped when a square copy lands, so the shells publish it. */
    val changes = MutableStateFlow(0)

    private val made = ConcurrentHashMap<String, Art>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The art for [thumbnailUrl], or null while a video's square copy is still being made. */
    fun forUrl(thumbnailUrl: String?): Art? {
        if (thumbnailUrl.isNullOrBlank()) return null
        val frame = thumbnailUrl.artworkAt(ART_PX)
        val fallback = frame.artworkFallback()
        if (frame == null || fallback == null) return Art(file = null, remote = thumbnailUrl)
        made[thumbnailUrl]?.let { return it }
        if (pending.add(thumbnailUrl)) scope.launch {
            val art = square(frame, letterboxed = false) ?: square(fallback, letterboxed = true)
                ?: Art(file = null, remote = fallback)
            made[thumbnailUrl] = art
            pending.remove(thumbnailUrl)
            changes.value++
        }
        return null
    }

    private fun square(url: String, letterboxed: Boolean): Art? = runCatching {
        val target = DesktopMediaCache.pathFor(url, "square.jpg")
        if (!Files.isReadable(target) || Files.size(target) == 0L) {
            val bytes = URI(url).toURL().openConnection().apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }.getInputStream().use { it.readBytes() }
            val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@runCatching null
            Files.newOutputStream(target).use { ImageIO.write(squared(image, letterboxed), "jpg", it) }
        }
        Art(file = target.toUri().toString(), remote = url)
    }.getOrNull()

    /** The centred square of [image]; of a letterboxed 4:3 thumbnail, the square inside its 16:9 band. */
    internal fun squared(image: BufferedImage, letterboxed: Boolean): BufferedImage {
        val w = image.width
        val h = image.height
        val side = if (letterboxed) minOf(h, w * 9 / 16) else minOf(w, h)
        // Drawn onto plain RGB: ImageIO writes no JPEG from an image with alpha.
        return BufferedImage(side, side, BufferedImage.TYPE_INT_RGB).also { out ->
            val g = out.createGraphics()
            g.drawImage(image, -(w - side) / 2, -(h - side) / 2, null)
            g.dispose()
        }
    }

    private const val ART_PX = 1200
}
