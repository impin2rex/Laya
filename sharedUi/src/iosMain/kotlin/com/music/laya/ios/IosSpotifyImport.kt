package com.music.laya.ios

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.music.laya.data.YtMusicRepository
import com.music.laya.data.model.PlaylistPrivacy
import com.music.laya.data.spotify.SpotifyImportTrack
import com.music.laya.data.spotify.SpotifyImporter
import com.music.laya.sharedui.resources.Res
import com.music.laya.sharedui.resources.cancel
import com.music.laya.sharedui.resources.close
import com.music.laya.sharedui.resources.spotify_import_button
import com.music.laya.sharedui.resources.spotify_import_description
import com.music.laya.sharedui.resources.spotify_import_failed
import com.music.laya.sharedui.resources.spotify_import_fetching
import com.music.laya.sharedui.resources.spotify_import_hint
import com.music.laya.sharedui.resources.spotify_import_invalid
import com.music.laya.sharedui.resources.spotify_import_matching
import com.music.laya.sharedui.resources.spotify_import_none
import com.music.laya.sharedui.resources.spotify_import_title
import com.music.laya.sharedui.resources.spotify_import_unmatched
import com.music.laya.ui.components.PillTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Android's SpotifyImportAlert, minus the language chips and privacy picker: paste a public playlist link, match its
 * songs on YouTube Music, create a private playlist on the signed-in account. [onImported] gets the new browse id.
 */
@Composable
internal fun IosSpotifyImportDialog(onImported: (browseId: String, title: String, count: Int) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var unmatched by remember { mutableStateOf<List<SpotifyImportTrack>?>(null) }

    fun start() {
        val id = SpotifyImporter.extractPlaylistId(link)
        working = true
        failed = false
        scope.launch {
            try {
                if (id == null) error(getString(Res.string.spotify_import_invalid))
                status = getString(Res.string.spotify_import_fetching)
                val (name, tracks) = SpotifyImporter.fetchPlaylistTracks(id)
                val (songs, missed) = SpotifyImporter.resolveToSongs(tracks, "No Preference") { done, total ->
                    scope.launch { status = getString(Res.string.spotify_import_matching, done, total) }
                }
                if (songs.isEmpty()) error(getString(Res.string.spotify_import_none))
                val ids = songs.map { it.videoId }
                val playlistId = YtMusicRepository.createPlaylist(name, PlaylistPrivacy.PRIVATE, ids.take(50)).getOrThrow()
                ids.drop(50).chunked(50).forEach { YtMusicRepository.addToPlaylist(playlistId, it) }
                onImported("VL$playlistId", name, songs.size)
                if (missed.isEmpty()) onDismiss() else unmatched = missed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = getString(Res.string.spotify_import_failed, e.message.orEmpty())
                failed = true
            }
            working = false
        }
    }

    val missed = unmatched
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(Res.string.spotify_import_title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (missed != null) {
                    Text(stringResource(Res.string.spotify_import_unmatched, missed.size), Modifier.padding(bottom = 8.dp))
                    missed.forEach {
                        Text(
                            if (it.artist.isBlank()) it.title else "${it.title} · ${it.artist}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        status ?: stringResource(Res.string.spotify_import_description),
                        Modifier.padding(bottom = 12.dp),
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    PillTextField(
                        value = link,
                        onValueChange = { link = it; if (failed) { status = null; failed = false } },
                        placeholder = stringResource(Res.string.spotify_import_hint),
                        enabled = !working,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
            }
        },
        confirmButton = {
            if (missed != null) TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) }
            else TextButton(onClick = ::start, enabled = link.isNotBlank() && !working) { Text(stringResource(Res.string.spotify_import_button)) }
        },
        dismissButton = {
            if (missed == null) TextButton(onClick = onDismiss, enabled = !working) { Text(stringResource(Res.string.cancel)) }
        },
    )
}
