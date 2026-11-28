package com.music.laya.ui.tv.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.music.laya.R
import com.music.laya.data.LikeState
import com.music.laya.data.SongInfo
import com.music.laya.data.SongInfoRepository
import com.music.laya.data.compactCount
import com.music.laya.data.YtMusicRepository
import com.music.laya.data.model.BrowseType
import com.music.laya.data.model.LikeStatus
import com.music.laya.data.model.PlaylistPrivacy
import com.music.laya.data.model.Song
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.NerdStats
import com.music.laya.playback.OriginalVersion
import com.music.laya.playback.QualityUpgrade
import com.music.laya.playback.SleepTimer
import com.music.laya.playback.canRevertToOriginal
import com.music.laya.playback.revertToOriginal
import com.music.laya.playback.upgradeQuality
import com.music.laya.playback.beginRadioQueue
import com.music.laya.playback.commitRadioQueue
import com.music.laya.playback.playSongs
import com.music.laya.playback.toMediaItem
import com.music.laya.ui.MainViewModel
import com.music.laya.ui.tv.components.TvActivityIndicator
import com.music.laya.ui.tv.components.TvArtwork
import com.music.laya.ui.tv.components.TvDialog
import com.music.laya.ui.tv.components.TvDialogButton
import com.music.laya.ui.tv.components.TvListRow
import com.music.laya.ui.tv.components.TvSongRow
import com.music.laya.ui.tv.components.TvTextField
import com.music.laya.ui.tv.components.tvInitialFocus
import com.music.laya.ui.tv.theme.TvGlass
import com.music.laya.ui.tv.theme.TvType
import kotlinx.coroutines.launch

/**
 * A song's actions from anywhere outside the player: works out whether it's
 * liked and where its album and artist pages are, then shows the menu.
 */
@Composable
fun TvSongMenu(
    song: Song,
    viewModel: MainViewModel,
    mediaController: MediaController?,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    onDismiss: () -> Unit,
) {
    val overrides by LikeState.overrides.collectAsState()
    val isLiked = overrides[song.videoId] == LikeStatus.LIKE
    TvSongActionMenuDialog(
        song = song,
        isLiked = isLiked,
        viewModel = viewModel,
        mediaController = mediaController,
        onToggleLike = { viewModel.toggleLike(song.videoId) },
        onOpenAlbum = song.albumId?.let { id ->
            { _: String -> onNavigateToDetail(id, song.albumName.orEmpty(), song.artist, song.thumbnailUrl, BrowseType.ALBUM) }
        },
        onOpenArtist = song.artistId?.let { id ->
            { _: String -> onNavigateToDetail(id, song.artist, "", null, BrowseType.ARTIST) }
        },
        onDismiss = onDismiss,
    )
}

/**
 * The tvOS action sheet for a track: the song itself at the top, then a short
 * list of plain actions — one line each, a glyph on the right, the white
 * platter on whichever has focus.
 */
@Composable
fun TvSongActionMenuDialog(
    song: Song,
    isLiked: Boolean,
    viewModel: MainViewModel,
    mediaController: MediaController?,
    onToggleLike: () -> Unit,
    onOpenAlbum: ((String) -> Unit)? = null,
    onOpenArtist: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf(SongSheet.Actions) }
    val sleepDeadline by SleepTimer.deadline.collectAsState()
    val sleepAfterTrack by SleepTimer.afterTrack.collectAsState()
    val nerdStats by AppSettings.showNerdStats.collectAsState()
    val pinnedToOriginal by OriginalVersion.pinned.collectAsState()
    val upgradesInFlight by NerdStats.racingLossless.collectAsState()
    val playingItem = mediaController?.currentMediaItem?.takeIf { it.mediaId == song.videoId }
    val playingThis = playingItem != null
    // "Known": an unresolved track playing off the disk cache answers false and keeps the revert on offer.
    val playingYouTubesOwn = playingItem != null && QualityUpgrade.isKnownToBePlayingYouTubesOwn(
        song.videoId,
        playingItem.localConfiguration?.uri?.let(QualityUpgrade::cacheTag),
    )

    when (sheet) {
        SongSheet.Playlist -> {
            TvAddToPlaylistDialog(song = song, viewModel = viewModel, onDismiss = onDismiss)
            return
        }
        SongSheet.Sleep -> {
            TvSleepTimerDialog(onDismiss = onDismiss)
            return
        }
        SongSheet.Equalizer -> {
            TvEqualizerDialog(onDismiss = onDismiss)
            return
        }
        SongSheet.Info -> {
            TvSongInfoDialog(song = song, onDismiss = { sheet = SongSheet.Actions })
            return
        }
        SongSheet.Actions -> Unit
    }

    TvDialog(title = song.title, message = song.artist.ifBlank { null }, onDismissRequest = onDismiss) {
        TvArtwork(
            url = song.thumbnailUrl,
            px = 320,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .padding(bottom = 18.dp)
                .size(77.dp),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 288.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // The phone's version row (MainActivity's onRollbackToOriginal / onUpgradeQuality), for the song
            // playing: back to YouTube's own upload from a substituted copy, or a fresh look for a better one
            // from YouTube's own. Never both; nothing for a saved file, which no stream chose.
            if (playingThis && song.localUri == null) {
                val onOriginal = song.videoId in pinnedToOriginal || playingYouTubesOwn
                when {
                    onOriginal -> item {
                        TvListRow(
                            title = stringResource(R.string.upgrade_quality),
                            trailingIcon = Icons.Rounded.HighQuality,
                            enabled = song.videoId !in upgradesInFlight,
                            onClick = {
                                mediaController?.upgradeQuality()
                                onDismiss()
                            },
                        )
                    }
                    song.canRevertToOriginal() -> item {
                        TvListRow(
                            title = stringResource(R.string.revert_to_original),
                            trailingIcon = Icons.AutoMirrored.Rounded.Undo,
                            onClick = {
                                mediaController?.revertToOriginal()
                                onDismiss()
                            },
                        )
                    }
                }
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_play_next),
                    trailingIcon = Icons.AutoMirrored.Rounded.QueueMusic,
                    modifier = Modifier.tvInitialFocus(),
                    onClick = {
                        mediaController?.let { it.addMediaItem(it.currentMediaItemIndex + 1, song.toMediaItem()) }
                        onDismiss()
                    },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_play_last),
                    trailingIcon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                    onClick = {
                        mediaController?.addMediaItem(song.toMediaItem())
                        onDismiss()
                    },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_start_station),
                    trailingIcon = Icons.Rounded.Radio,
                    onClick = {
                        val controller = mediaController
                        if (controller != null) scope.launch { controller.startStation(song) }
                        onDismiss()
                    },
                )
            }
            item {
                TvListRow(
                    title = if (isLiked) stringResource(R.string.tv_unlove) else stringResource(R.string.tv_love),
                    trailingIcon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    onClick = {
                        onToggleLike()
                        onDismiss()
                    },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_add_to_playlist_ellipsis),
                    trailingIcon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    onClick = { sheet = SongSheet.Playlist },
                )
            }
            if (onOpenAlbum != null) {
                item {
                    TvListRow(
                        title = stringResource(R.string.tv_go_to_album),
                        trailingIcon = Icons.Rounded.Album,
                        onClick = {
                            onDismiss()
                            onOpenAlbum(song.albumId.orEmpty())
                        },
                    )
                }
            }
            if (onOpenArtist != null) {
                item {
                    TvListRow(
                        title = stringResource(R.string.tv_go_to_artist),
                        trailingIcon = Icons.Rounded.Person,
                        onClick = {
                            onDismiss()
                            onOpenArtist(song.artistId.orEmpty())
                        },
                    )
                }
            }
            item {
                TvListRow(
                    title = stringResource(R.string.song_info),
                    trailingIcon = Icons.Rounded.Info,
                    onClick = { sheet = SongSheet.Info },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.equalizer),
                    trailingIcon = Icons.Rounded.Equalizer,
                    onClick = { sheet = SongSheet.Equalizer },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_sleep_timer),
                    value = if (sleepDeadline != null || sleepAfterTrack) stringResource(R.string.tv_on) else stringResource(R.string.off),
                    trailingIcon = Icons.Rounded.Timer,
                    onClick = { sheet = SongSheet.Sleep },
                )
            }
            item {
                TvListRow(
                    title = stringResource(R.string.tv_stats_for_nerds),
                    value = if (nerdStats) stringResource(R.string.tv_on) else stringResource(R.string.off),
                    trailingIcon = Icons.Rounded.Info,
                    onClick = { AppSettings.setShowNerdStats(!nerdStats) },
                )
            }
        }
    }
}

private enum class SongSheet { Actions, Playlist, Sleep, Equalizer, Info }

/**
 * The phone's Song Info, as a TV sheet: the release and its YouTube counts,
 * one focusable row each so the remote can walk them. Back returns to the
 * song's actions rather than closing everything.
 */
@Composable
private fun TvSongInfoDialog(song: Song, onDismiss: () -> Unit) {
    var retry by remember(song.videoId) { mutableIntStateOf(0) }
    val info by produceState<SongInfo?>(null, song, retry) {
        value = null
        value = SongInfoRepository.load(song)
    }
    val unavailable = stringResource(R.string.song_info_unavailable)
    val rows = listOf(
        R.string.song_info_album to (info?.album ?: song.albumName),
        R.string.song_info_release_date to (info?.releaseDate ?: info?.releaseYear),
        R.string.song_info_views to info?.views?.let(::compactCount),
        R.string.song_info_likes to info?.likes,
        R.string.song_info_comments to info?.comments,
    )
    TvDialog(
        title = stringResource(R.string.song_info),
        message = listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" · "),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            rows.forEachIndexed { index, (label, value) ->
                TvListRow(
                    title = stringResource(label),
                    // "…" while the lookup is out; "Not available" once it has
                    // answered without this one.
                    value = value ?: if (info != null) unavailable else "…",
                    modifier = if (index == 0) Modifier.tvInitialFocus() else Modifier,
                    onClick = {},
                )
            }
            if (info?.failed == true) {
                Text(
                    text = stringResource(R.string.song_info_error),
                    style = TvType.Caption,
                    color = TvGlass.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TvListRow(title = stringResource(R.string.song_info_retry), onClick = { retry++ })
            }
            TvListRow(title = stringResource(R.string.done), onClick = onDismiss)
        }
    }
}

/**
 * A station seeded from [song], as the phone builds one: YouTube's radio for
 * it, with the song itself first. If it's already the one playing, it keeps
 * playing and only the rest of the queue is replaced.
 */
private suspend fun MediaController.startStation(song: Song) {
    val related = YtMusicRepository.radio(song.videoId).getOrNull()
        ?.filterNot { it.videoId == song.videoId }
        ?: return
    beginRadioQueue()
    val index = currentMediaItemIndex
    if (index >= 0 && currentMediaItem?.mediaId == song.videoId) {
        // Station first, old tail after: removing first leaves the current
        // track briefly last, and AutoPlay answers that by appending this same
        // radio a second time.
        addMediaItems(index + 1, related.map { it.toMediaItem() })
        val oldTail = index + 1 + related.size
        if (oldTail < mediaItemCount) removeMediaItems(oldTail, mediaItemCount)
        if (index > 0) removeMediaItems(0, index)
    } else {
        playSongs(listOf(song) + related, 0)
    }
    commitRadioQueue()
}

/** Picks one of the account's playlists for the song, or names a new one for it. */
@Composable
fun TvAddToPlaylistDialog(
    song: Song,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val playlists by viewModel.playlists.collectAsState()
    val loading by viewModel.playlistsLoading.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (playlists.isEmpty()) viewModel.loadPlaylists() }

    val done = result
    when {
        done != null -> TvDialog(title = done, onDismissRequest = onDismiss) {
            TvDialogButton(text = stringResource(R.string.tv_ok), onClick = onDismiss, initialFocus = true)
        }
        creating -> TvDialog(
            title = stringResource(R.string.tv_new_playlist_title),
            message = stringResource(R.string.tv_new_playlist_message),
            onDismissRequest = { creating = false },
        ) {
            val create = {
                if (name.isNotBlank()) {
                    viewModel.createPlaylist(name.trim(), PlaylistPrivacy.PRIVATE, song)
                    result = context.getString(R.string.tv_added_to, name.trim())
                }
            }
            TvTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.tv_playlist_name),
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
                onImeAction = create,
                modifier = Modifier.tvInitialFocus(),
            )
            Spacer(modifier = Modifier.size(16.dp))
            TvDialogButton(text = stringResource(R.string.tv_create), enabled = name.isNotBlank(), onClick = create)
            Spacer(modifier = Modifier.size(6.dp))
            TvDialogButton(text = stringResource(R.string.cancel), onClick = { creating = false })
        }
        else -> TvDialog(title = stringResource(R.string.tv_add_to_playlist), message = song.title, onDismissRequest = onDismiss) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 272.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                item {
                    TvListRow(
                        title = stringResource(R.string.tv_new_playlist_ellipsis),
                        trailingIcon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                        modifier = Modifier.tvInitialFocus(),
                        onClick = { creating = true },
                    )
                }
                if (loading && playlists.isEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                            TvActivityIndicator(size = 21.dp)
                        }
                    }
                }
                items(playlists, key = { it.playlistId }) { playlist ->
                    TvListRow(
                        title = playlist.title,
                        value = playlist.subtitle.takeIf { it.isNotBlank() },
                        onClick = {
                            viewModel.addToPlaylists(listOf(playlist), song) { added, already, _ ->
                                result = when {
                                    added > 0 -> context.getString(R.string.tv_added_to, playlist.title)
                                    already > 0 -> context.getString(R.string.tv_already_in, playlist.title)
                                    else -> context.getString(R.string.tv_couldnt_add_to, playlist.title)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Stop playback after a while, or at the end of this song. */
@Composable
fun TvSleepTimerDialog(onDismiss: () -> Unit) {
    val deadline by SleepTimer.deadline.collectAsState()
    val afterTrack by SleepTimer.afterTrack.collectAsState()
    val active = deadline != null || afterTrack

    TvDialog(title = stringResource(R.string.tv_sleep_timer), message = stringResource(R.string.tv_sleep_timer_message), onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(15, 30, 45, 60).forEachIndexed { index, minutes ->
                TvListRow(
                    title = stringResource(R.string.tv_minutes, minutes),
                    modifier = if (index == 0) Modifier.tvInitialFocus() else Modifier,
                    onClick = {
                        SleepTimer.start(minutes)
                        onDismiss()
                    },
                )
            }
            TvListRow(
                title = stringResource(R.string.tv_end_of_current_song),
                trailingIcon = if (afterTrack) Icons.Rounded.Check else null,
                onClick = {
                    SleepTimer.startAfterTrack()
                    onDismiss()
                },
            )
            if (active) {
                TvListRow(
                    title = stringResource(R.string.tv_turn_off_timer),
                    destructive = true,
                    onClick = {
                        SleepTimer.cancel()
                        onDismiss()
                    },
                )
            }
        }
    }
}
