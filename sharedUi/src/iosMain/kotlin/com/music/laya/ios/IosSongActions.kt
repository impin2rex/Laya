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
        title = { Text("Sleep timer") },
        text = {
            Column {
                listOf(15, 30, 45, 60).forEach { m ->
                    PickRow("$m minutes" + if (player.sleepMinutes == m) "  ✓" else "") { player.startSleep(m); onDismiss() }
                }
                PickRow("End of track" + if (player.sleepAfterTrack) "  ✓" else "") { player.startSleepAfterTrack(); onDismiss() }
                if (player.sleepMinutes != null || player.sleepAfterTrack) PickRow("Turn off") { player.cancelSleep(); onDismiss() }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } },
    )
}

/** Same lookup as Android's SongInfoDialog (SongInfoRepository), same five rows. */
@Composable
internal fun IosSongInfoDialog(song: Song, onDismiss: () -> Unit) {
    val info by produceState<SongInfo?>(null, song) { value = SongInfoRepository.load(song) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Song info") },
        text = {
            Column {
                Text(song.title)
                Text(song.artist)
                if (info == null) Text("Loading…")
                else listOf(
                    "Album" to (info?.album ?: song.albumName),
                    "Release date" to (info?.releaseDate ?: info?.releaseYear),
                    "Views" to info?.views?.let(::compactCount),
                    "Likes" to info?.likes,
                    "Comments" to info?.comments,
                ).forEach { (k, v) ->
                    ListItem(
                        headlineContent = { Text(k) },
                        trailingContent = { Text(v ?: "—") },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } },
    )
}

/** Lists the account's playlists (the call Android's picker makes) and adds [song] to the tapped one. */
@Composable
internal fun IosPlaylistPicker(song: Song, onDismiss: () -> Unit, notify: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val playlists by produceState<List<UserPlaylist>?>(null) { value = YtMusicRepository.userPlaylists().getOrDefault(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                val list = playlists
                if (list == null) Text("Loading…") else if (list.isEmpty()) Text("No playlists")
                list?.forEach { p ->
                    PickRow(p.title) {
                        onDismiss()
                        scope.launch {
                            YtMusicRepository.addToPlaylist(p.playlistId, listOf(song.videoId))
                                .onSuccess { notify("Added to ${p.title}") }
                                .onFailure { notify("Couldn't add to ${p.title}") }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
