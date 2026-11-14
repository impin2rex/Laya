package com.music.laya.playback

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.music.laya.data.model.artworkFallback

/**
 * The cover the system shows — notification, lock screen, car, Bluetooth: square, as the app's own.
 *
 * A music video's cover is its 16:9 frame (hq720, see `artworkAt`), which the system would show
 * wide or crop its own way; it is cut to its middle square here. An old upload has no hq720: its
 * 4:3 thumbnail stands in, whose picture is the 16:9 band between baked-in black bars, so its
 * square is taken from inside that band.
 */
@UnstableApi
class SquareArtBitmapLoader(private val inner: BitmapLoader) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = inner.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        Futures.transform(inner.decodeBitmap(data), { squared(it, letterboxed = false) }, direct)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val loaded = Futures.transform(inner.loadBitmap(uri), { squared(it, letterboxed = false) }, direct)
        val fallback = uri.toString().artworkFallback() ?: return loaded
        return Futures.catchingAsync(loaded, Throwable::class.java, {
            Futures.transform(inner.loadBitmap(Uri.parse(fallback)), { squared(it, letterboxed = true) }, direct)
        }, direct)
    }

    private val direct = MoreExecutors.directExecutor()

    internal companion object {
        /** The centred square of [bitmap]; of a letterboxed 4:3 thumbnail, the square inside its 16:9 band. */
        fun squared(bitmap: Bitmap, letterboxed: Boolean): Bitmap {
            val side = if (letterboxed) minOf(bitmap.height, bitmap.width * 9 / 16) else minOf(bitmap.width, bitmap.height)
            // Square already, near enough: an album cover is left exactly as it came.
            if (side <= 0 || maxOf(bitmap.width, bitmap.height) - side <= side / 50) return bitmap
            return Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        }
    }
}
