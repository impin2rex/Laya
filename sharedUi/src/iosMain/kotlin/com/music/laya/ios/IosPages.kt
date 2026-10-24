package com.music.laya.ios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.music.laya.data.YtMusicRepository
import com.music.laya.data.model.BrowseType
import com.music.laya.data.model.DetailPage
import com.music.laya.data.model.HomeShelf
import com.music.laya.data.model.MoodGenre
import com.music.laya.data.model.Song
import com.music.laya.data.model.UiState
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

/** A pushed page: an album / artist / playlist, or a mood / genre's playlists. */
internal sealed interface IosPage {
    class Detail(val page: DetailPage) : IosPage
    class Mood(val item: MoodGenre, val shelves: UiState<List<HomeShelf>>) : IosPage
    data object Settings : IosPage
}

/** YouTube browse ids are prefixed by kind (Android's MainViewModel.browseTypeOf, minus the device-local kinds). */
private fun browseTypeOf(id: String, fallback: BrowseType) = when {
    id.startsWith("UC") -> BrowseType.ARTIST
    id.startsWith("MPREb") || id.startsWith("VLOLAK") || id.startsWith("OLAK") -> BrowseType.ALBUM
    id.startsWith("VL") || id.startsWith("PL") -> BrowseType.PLAYLIST
    else -> fallback
}

internal fun loadingPage(browseId: String, title: String, subtitle: String, thumb: String?, type: BrowseType) =
    DetailPage(browseId, title, subtitle, thumb, UiState.Loading, browseTypeOf(browseId, type))

private fun friendly(e: Throwable) = e.message ?: "Something went wrong"

/**
 * Fills a [loadingPage] — the same two calls MainViewModel.openDetail makes.
 * ponytail: first page of tracks only (no continuation fill-in), no device-local or Spotify pages.
 */
internal suspend fun loadDetail(base: DetailPage): DetailPage {
    val fallback = base.thumbnailUrl
    fun List<Song>.withArt(art: String?) = if (art == null) this else map { if (it.thumbnailUrl == null) it.copy(thumbnailUrl = art) else it }
    return if (base.type == BrowseType.ARTIST) {
        YtMusicRepository.artistPage(base.browseId).fold({ p ->
            base.copy(
                songs = if (p.songs.isEmpty()) UiState.Error("No tracks here") else UiState.Success(p.songs.withArt(fallback ?: p.thumbnailUrl)),
                sections = p.sections,
                thumbnailUrl = p.thumbnailUrl ?: fallback,
                title = p.name ?: base.title,
                description = p.description,
                subscriberCountText = p.subscriberCountText,
                monthlyListenerCount = p.monthlyListenerCount,
            )
        }, { base.copy(songs = UiState.Error(friendly(it))) })
    } else {
        YtMusicRepository.browseSongs(base.browseId).fold({ p ->
            val header = p.header
            base.copy(
                songs = if (p.songs.isEmpty()) UiState.Error("No tracks here") else UiState.Success(p.songs.withArt(fallback ?: header?.thumbnailUrl)),
                suggestedSongs = p.suggested.withArt(fallback ?: header?.thumbnailUrl),
                continuation = p.continuation,
                description = p.description,
                title = if (base.title.isBlank()) header?.title ?: base.title else base.title,
                subtitle = if (header != null && header.subtitle.isNotBlank() &&
                    (base.type == BrowseType.ALBUM || base.subtitle.isBlank())) header.subtitle else base.subtitle,
                thumbnailUrl = fallback ?: header?.thumbnailUrl,
            )
        }, { base.copy(songs = UiState.Error(friendly(it))) })
    }
}

internal suspend fun loadMood(item: MoodGenre): UiState<List<HomeShelf>> =
    YtMusicRepository.moodGenreShelves(item.browseId, item.params).fold(
        { if (it.isEmpty()) UiState.Error("Nothing to explore") else UiState.Success(it) },
        { UiState.Error(friendly(it)) },
    )

/** Long-press menu. Account actions (like, playlists, library) and downloads are Phase 5/6, so not offered. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IosSongMenu(
    song: Song,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    @Composable
    fun Action(icon: ImageVector, label: String, onClick: () -> Unit) = ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable { onDismiss(); onClick() },
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            ListItem(
                headlineContent = { Text(song.title, maxLines = 1) },
                supportingContent = { Text(song.artist, maxLines = 1) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
            Action(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next", onPlayNext)
            Action(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", onAddToQueue)
            song.albumId?.let { id -> Action(Icons.Rounded.Album, "Open album") { onOpenAlbum(id) } }
            song.artistId?.let { id -> Action(Icons.Rounded.Person, "Open artist") { onOpenArtist(id) } }
            Action(Icons.Rounded.Share, "Share") { shareText("https://music.youtube.com/watch?v=${song.videoId}") }
        }
    }
}

/** The system share sheet, presented from whatever view controller is on top. */
private fun shareText(text: String) {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return
    while (true) top = top.presentedViewController ?: break
    top.presentViewController(UIActivityViewController(listOf(text), null), true, null)
}
