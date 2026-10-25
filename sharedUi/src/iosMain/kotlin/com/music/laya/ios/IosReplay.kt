@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.music.laya.ios

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import com.music.laya.data.model.Song
import com.music.laya.data.stats.ListeningStats
import com.music.laya.ui.share.ImageSharer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIImage
import kotlin.coroutines.resume

/**
 * Where a finished picture goes on iOS: the system share sheet (a PNG file in the temp folder, so
 * Messages, AirDrop and Save Image all see an image), and the photo library through PhotoKit.
 * Saving needs only add access — NSPhotoLibraryAddUsageDescription in Info.plist.
 */
internal object IosImageSharer : ImageSharer {
    private fun png(image: ImageBitmap): NSData? {
        val skia = org.jetbrains.skia.Image.makeFromBitmap(image.asSkiaBitmap())
        val bytes = skia.encodeToData(EncodedImageFormat.PNG)?.bytes ?: return null
        return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    }

    override suspend fun share(image: ImageBitmap, fileName: String): Boolean = withContext(Dispatchers.Main) {
        val data = png(image) ?: return@withContext false
        val path = NSTemporaryDirectory() + fileName
        if (!data.writeToFile(path, true)) return@withContext false
        presentShare(listOf(NSURL.fileURLWithPath(path)))
        true
    }

    override suspend fun saveToPhotos(image: ImageBitmap, name: String): Boolean {
        val data = withContext(Dispatchers.Default) { png(image) } ?: return false
        val uiImage = UIImage(data = data)
        val allowed = suspendCancellableCoroutine { cont ->
            PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
                cont.resume(status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited)
            }
        }
        if (!allowed) return false
        return suspendCancellableCoroutine { cont ->
            PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                { PHAssetChangeRequest.creationRequestForAssetFromImage(uiImage) },
                completionHandler = { ok, error ->
                    if (error != null) println("IosImageSharer: save failed ${error.localizedDescription}")
                    cont.resume(ok)
                },
            )
        }
    }
}

/**
 * Debug only, never run in normal use: with `SIMCTL_CHILD_LAYA_SEED_PLAYS=1` fills the listening
 * record with a few weeks of made-up plays so Replay has something to draw on the simulator.
 * Called from the same env-hook block as LAYA_OPEN, and a no-op unless that variable is set.
 */
internal fun seedFakePlays() {
    val day = 86_400_000L
    val now = com.music.laya.platform.currentTimeMillis()
    fun art(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    val tracks = listOf(
        Song(videoId = "dQw4w9WgXcQ", title = "Never Gonna Give You Up", artist = "Rick Astley", thumbnailUrl = art("dQw4w9WgXcQ"), albumName = "Whenever You Need Somebody"),
        Song(videoId = "kJQP7kiw5Fk", title = "Despacito", artist = "Luis Fonsi", thumbnailUrl = art("kJQP7kiw5Fk"), albumName = "Vida"),
        Song(videoId = "9bZkp7q19f0", title = "Gangnam Style", artist = "PSY", thumbnailUrl = art("9bZkp7q19f0"), albumName = "Psy 6 (Six Rules), Part 1"),
        Song(videoId = "JGwWNGJdvx8", title = "Shape of You", artist = "Ed Sheeran", thumbnailUrl = art("JGwWNGJdvx8"), albumName = "Divide"),
        Song(videoId = "RgKAFK5djSk", title = "See You Again", artist = "Wiz Khalifa", thumbnailUrl = art("RgKAFK5djSk"), albumName = "Furious 7"),
        Song(videoId = "fRh_vgS2dFE", title = "Sorry", artist = "Justin Bieber", thumbnailUrl = art("fRh_vgS2dFE"), albumName = "Purpose"),
    )
    // Today and the 20 days before it, 6 to 11 plays a day, weighted so a clear top song and artist emerge.
    for (back in 0..20) {
        for (i in 0 until 6 + back % 6) {
            val song = tracks[(i * (i + 1) / 2 + back) % tracks.size].let { if (i % 3 == 0) tracks[0] else if (i % 4 == 0) tracks[3] else it }
            val at = now - back * day - (i * 3L + 1L) * 3_600_000L / 2 - (back % 5) * 60_000L
            ListeningStats.record(song, 3 * 60_000L + (i * 7L) * 1000, true, at)
        }
    }
    ListeningStats.flush()
    println("LAYA_SEED_PLAYS seeded ${tracks.size} tracks over 21 days")
}
