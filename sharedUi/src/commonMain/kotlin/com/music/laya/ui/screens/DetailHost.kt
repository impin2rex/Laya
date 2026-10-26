package com.music.laya.ui.screens

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import coil3.request.ImageRequest
import com.music.laya.data.canvas.AppleArtistArt
import com.music.laya.data.canvas.ArtistSignature
import com.music.laya.data.canvas.CanvasArtwork
import com.music.laya.data.model.Song
import com.music.laya.data.settings.LocalMusicSort
import com.music.laya.ui.theme.ArtworkPalette
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The extras [DetailScreen] draws that only the phone app's data layer can answer — Apple Music
 * artist art, handwritten signatures, animated album covers, device folders. Every default is
 * "nothing known", which is how iOS runs; Android installs the real thing at start.
 */
interface DetailHost {
    fun cachedArt(name: String): AppleArtistArt? = null
    suspend fun artFor(name: String): AppleArtistArt? = null
    val signatureUpdates: StateFlow<Int> get() = NO_UPDATES
    fun signatureBase(enabled: Boolean, url: String): String? = null
    fun cachedSignature(name: String, base: String?): ArtistSignature? = null
    suspend fun signatureFor(name: String, base: String?): ArtistSignature? = null
    /** Turns a scanned signature into white ink on transparency (coil transformations are Android-only). */
    fun ImageRequest.Builder.signatureInk() {}
    suspend fun canvasForAlbum(album: String, artist: String): CanvasArtwork? = null
    fun folderIcon(browseId: String): ImageVector? = null
    fun isLocalArtist(browseId: String): Boolean = false
}

private val NO_UPDATES = MutableStateFlow(0)

object DetailExtras {
    @Volatile
    var host: DetailHost = object : DetailHost {}
}

/** What a device-folder page (Downloads, Local Music, …) adds to [DetailScreen]. Android only. */
interface DetailFolder {
    val sort: LocalMusicSort
    val selecting: Boolean
    val onDownload: ((List<Song>) -> Unit)?
    fun clearSelection()

    /** Remembers the Artists/Albums groups for [query]; the result adds the folder's list body. */
    @Composable
    fun rememberItems(
        browseId: String,
        rawSongs: List<Song>,
        query: String,
        queue: List<Song>,
        palette: ArtworkPalette,
        currentSong: Song?,
        isPlaying: Boolean,
        onSongClick: (List<Song>, Int) -> Unit,
        onSongLongPress: (Song) -> Unit,
        onSongSwipe: (Song) -> Unit,
    ): LazyListScope.(columns: Int) -> Unit

    @Composable
    fun SelectionBar(songs: List<Song>, modifier: Modifier)
}
