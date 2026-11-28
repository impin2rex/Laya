package com.music.laya.ios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.laya.data.NerdStats
import com.music.laya.data.SongInfo
import com.music.laya.data.SongInfoRepository
import com.music.laya.data.TrackLog
import com.music.laya.data.YtMusicRepository
import com.music.laya.data.compactCount
import com.music.laya.data.model.Song
import com.music.laya.data.model.UserPlaylist
import kotlinx.coroutines.launch
import platform.UIKit.UIPasteboard
import com.music.laya.sharedui.resources.Res
import com.music.laya.sharedui.resources.add_to_playlist
import com.music.laya.sharedui.resources.add_to_playlist_failed
import com.music.laya.sharedui.resources.added_to_named_playlist
import com.music.laya.sharedui.resources.after_this_song
import com.music.laya.sharedui.resources.cancel
import com.music.laya.sharedui.resources.done
import com.music.laya.sharedui.resources.loading
import com.music.laya.sharedui.resources.minute_count
import com.music.laya.sharedui.resources.no_playlists
import com.music.laya.sharedui.resources.sleep_timer
import com.music.laya.sharedui.resources.song_info
import com.music.laya.sharedui.resources.song_info_album
import com.music.laya.sharedui.resources.song_info_comments
import com.music.laya.sharedui.resources.song_info_likes
import com.music.laya.sharedui.resources.song_info_release_date
import com.music.laya.sharedui.resources.song_info_views
import com.music.laya.sharedui.resources.turn_off_timer
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Android's "Copy log": the same TrackLog export (header, NerdStats, the track's resolver/source/player lines) on the pasteboard. Returns the line count. */
internal suspend fun copyTrackLog(song: Song): Int {
    val text = TrackLog.forTrack(song, NerdStats.current.value)
    UIPasteboard.generalPasteboard.string = text
    return text.lines().count()
}

@Composable
private fun PickRow(label: String, onClick: () -> Unit) = ListItem(
    headlineContent = { Text(label) },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    modifier = Modifier.clickable(onClick = onClick),
)

/** Sleep timer picker with Android's presets (15/30/45/60) plus "end of track" and off. */
@Composable
internal fun IosSleepTimerDialog(player: IosPlayer, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sleep_timer)) },
        text = {
            Column {
                listOf(15, 30, 45, 60).forEach { m ->
                    PickRow(pluralStringResource(Res.plurals.minute_count, m, m) + if (player.sleepMinutes == m) "  ✓" else "") { player.startSleep(m); onDismiss() }
                }
                PickRow(stringResource(Res.string.after_this_song) + if (player.sleepAfterTrack) "  ✓" else "") { player.startSleepAfterTrack(); onDismiss() }
                if (player.sleepMinutes != null || player.sleepAfterTrack) PickRow(stringResource(Res.string.turn_off_timer)) { player.cancelSleep(); onDismiss() }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(Res.string.done)) } },
    )
}

/** Same lookup as Android's SongInfoDialog (SongInfoRepository), same five rows. */
@Composable
internal fun IosSongInfoDialog(song: Song, onDismiss: () -> Unit) {
    val info by produceState<SongInfo?>(null, song) { value = SongInfoRepository.load(song) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.song_info)) },
        text = {
            Column {
                Text(song.title)
                Text(song.artist)
                if (info == null) Text(stringResource(Res.string.loading))
                else listOf(
                    stringResource(Res.string.song_info_album) to (info?.album ?: song.albumName),
                    stringResource(Res.string.song_info_release_date) to (info?.releaseDate ?: info?.releaseYear),
                    stringResource(Res.string.song_info_views) to info?.views?.let(::compactCount),
                    stringResource(Res.string.song_info_likes) to info?.likes,
                    stringResource(Res.string.song_info_comments) to info?.comments,
                ).forEach { (k, v) ->
                    ListItem(
                        headlineContent = { Text(k) },
                        trailingContent = { Text(v ?: "—") },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(Res.string.done)) } },
    )
}

/** Lists the account's playlists (the call Android's picker makes) and adds [song] to the tapped one. */
@Composable
internal fun IosPlaylistPicker(song: Song, onDismiss: () -> Unit, notify: (String) -> Unit) =
    IosPlaylistPicker(listOf(song), onDismiss, notify)

/** The same picker for many tracks — the queue's selection. */
@Composable
internal fun IosPlaylistPicker(songs: List<Song>, onDismiss: () -> Unit, notify: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val playlists by produceState<List<UserPlaylist>?>(null) { value = YtMusicRepository.userPlaylists().getOrDefault(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.add_to_playlist)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                val list = playlists
                if (list == null) Text(stringResource(Res.string.loading)) else if (list.isEmpty()) Text(stringResource(Res.string.no_playlists))
                list?.forEach { p ->
                    PickRow(p.title) {
                        onDismiss()
                        scope.launch {
                            YtMusicRepository.addToPlaylist(p.playlistId, songs.map { it.videoId }.distinct())
                                .onSuccess { notify(getString(Res.string.added_to_named_playlist, p.title)) }
                                .onFailure { notify(getString(Res.string.add_to_playlist_failed, p.title)) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
