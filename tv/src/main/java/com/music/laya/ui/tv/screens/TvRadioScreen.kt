package com.music.laya.ui.tv.screens

import androidx.compose.ui.res.stringResource
import com.music.laya.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.session.MediaController
import com.music.laya.data.model.BrowseType
import com.music.laya.data.model.HomeShelf
import com.music.laya.data.model.Song
import com.music.laya.data.model.UiState
import com.music.laya.playback.playSongs
import com.music.laya.ui.MainViewModel
import com.music.laya.ui.screens.groupMixes
import com.music.laya.ui.tv.components.TvEmptyState
import com.music.laya.ui.tv.components.TvErrorState
import com.music.laya.ui.tv.dialogs.TvSongMenu
import kotlinx.coroutines.launch

/**
 * Radio: the phone's "Mixed for you" page as shelves — the named stations first
 * as large cards, then Your Mixes, then a shelf per mood.
 */
@Composable
fun TvRadioScreen(
    viewModel: MainViewModel,
    mediaController: MediaController?,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    onNavigateToNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val signedIn by viewModel.signedIn.collectAsState()
    val account by viewModel.account.collectAsState()
    val mixesState by viewModel.mixes.collectAsState()
    val scope = rememberCoroutineScope()
    var menuSong by remember { mutableStateOf<Song?>(null) }
    LaunchedEffect(signedIn, account) { viewModel.onMixesShown() }

    if (!signedIn) {
        TvEmptyState(
            title = stringResource(R.string.tv_radio_sign_in_title),
            message = stringResource(R.string.tv_radio_sign_in_message),
            modifier = modifier,
        )
    } else when (val state = mixesState) {
        is UiState.Loading -> TvShelvesLoading(modifier)
        is UiState.Error -> TvErrorState(
            message = state.message,
            onRetry = viewModel::loadMixes,
            modifier = modifier,
        )
        is UiState.Success -> {
            val stationsTitle = stringResource(R.string.tv_stations)
            val mixesTitle = stringResource(R.string.tv_your_mixes)
            val shelves = remember(state.data, stationsTitle, mixesTitle) {
                val sections = groupMixes(state.data)
                listOfNotNull(
                    sections.stations.takeIf { it.isNotEmpty() }?.let { HomeShelf(title = stationsTitle, items = it) },
                    sections.yours.takeIf { it.isNotEmpty() }?.let { HomeShelf(title = mixesTitle, items = it) },
                ) + sections.moods.map { HomeShelf(title = it.name, items = it.items) }
            }
            if (shelves.isEmpty()) {
                TvEmptyState(
                    title = stringResource(R.string.tv_no_mixes_title),
                    message = stringResource(R.string.tv_no_mixes_message),
                    modifier = modifier,
                )
            } else {
                TvShelfFeed(
                    shelves = shelves,
                    onPlaySong = { song ->
                        scope.launch { mediaController?.playSongs(listOf(song), 0) }
                        onNavigateToNowPlaying()
                    },
                    onSongMenu = { menuSong = it },
                    onNavigateToDetail = onNavigateToDetail,
                    picksFirst = true,
                    modifier = modifier,
                )
            }
        }
    }

    menuSong?.let { song ->
        TvSongMenu(
            song = song,
            viewModel = viewModel,
            mediaController = mediaController,
            onDismiss = { menuSong = null },
            onNavigateToDetail = onNavigateToDetail,
        )
    }
}
