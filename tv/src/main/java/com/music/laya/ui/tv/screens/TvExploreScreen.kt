package com.music.laya.ui.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.music.laya.data.model.BrowseType
import com.music.laya.data.model.MoodGenre
import com.music.laya.data.model.MoodGenreSection
import com.music.laya.data.model.Song
import com.music.laya.data.model.UiState
import com.music.laya.playback.playSongs
import com.music.laya.ui.MainViewModel
import com.music.laya.ui.screens.MOOD_CARD_ASPECT
import com.music.laya.ui.tv.components.TvChromeScrollEffect
import com.music.laya.ui.tv.components.TvErrorState
import com.music.laya.ui.tv.components.TvMoodCard
import com.music.laya.ui.tv.dialogs.TvSongMenu
import com.music.laya.ui.tv.theme.TvDimensions
import com.music.laya.ui.tv.theme.TvGlass
import com.music.laya.ui.tv.theme.TvType
import kotlinx.coroutines.launch

/**
 * Explore, laid out as the phone's: the server's sections ("Moods & moments",
 * "Genres") each under its own header, every category a tinted tile with two
 * fanned covers. Choosing one opens its playlists as shelves; Back returns to the grid.
 */
@Composable
fun TvExploreScreen(
    viewModel: MainViewModel,
    mediaController: MediaController?,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    onNavigateToNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected by viewModel.selectedMoodGenre.collectAsState()
    val mood = selected
    if (mood == null) {
        val explore by viewModel.explore.collectAsState()
        TvMoodGrid(
            state = explore,
            onCategory = viewModel::openMoodGenre,
            onRetry = { viewModel.loadExplore() },
            modifier = modifier,
        )
    } else {
        BackHandler { viewModel.closeMoodGenre() }
        TvMoodPage(
            mood = mood,
            viewModel = viewModel,
            mediaController = mediaController,
            onNavigateToDetail = onNavigateToDetail,
            onNavigateToNowPlaying = onNavigateToNowPlaying,
            modifier = modifier,
        )
    }
}

@Composable
private fun TvMoodGrid(
    state: UiState<List<MoodGenreSection>>,
    onCategory: (MoodGenre) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    TvChromeScrollEffect(gridState)

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(MOOD_MIN_WIDTH),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = TvDimensions.SafeMarginHorizontal,
            end = TvDimensions.SafeMarginHorizontal,
            top = TvDimensions.ContentTop + 3.dp,
            bottom = 38.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
        verticalArrangement = Arrangement.spacedBy(MOOD_SPACING),
    ) {
        when (state) {
            UiState.Loading -> items(16) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(MOOD_CARD_ASPECT)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color.White.copy(alpha = 0.06f)),
                )
            }
            is UiState.Error -> item(span = { GridItemSpan(maxLineSpan) }) {
                TvErrorState(message = state.message, onRetry = onRetry, modifier = Modifier.padding(top = 48.dp))
            }
            is UiState.Success -> {
                // A category the server lists twice is drawn only once.
                val seen = mutableSetOf<Pair<String, String?>>()
                state.data.forEachIndexed { index, section ->
                    val cards = section.items.filter { seen.add(it.browseId to it.params) }
                    if (cards.isEmpty()) return@forEachIndexed
                    item(key = "header|${section.title}", span = { GridItemSpan(maxLineSpan) }) {
                        TvMoodSectionHeader(section.title, Modifier.padding(top = if (index == 0) 0.dp else 14.dp))
                    }
                    items(cards, key = { "${it.browseId}|${it.params}" }) { item ->
                        TvMoodCard(item = item, onClick = { onCategory(item) })
                    }
                }
            }
        }
    }
}

/** The section's name over a hairline rule, as the phone's Explore sets it. */
@Composable
private fun TvMoodSectionHeader(title: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = title,
            style = TvType.Title,
            color = TvGlass.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(TvGlass.TextPrimary.copy(alpha = 0.1f)),
        )
    }
}

@Composable
private fun TvMoodPage(
    mood: MoodGenre,
    viewModel: MainViewModel,
    mediaController: MediaController?,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    onNavigateToNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shelves by viewModel.moodGenreShelves.collectAsState()
    val scope = rememberCoroutineScope()
    var menuSong by remember { mutableStateOf<Song?>(null) }

    when (val state = shelves) {
        UiState.Loading -> TvShelvesLoading(modifier)
        is UiState.Error -> TvErrorState(
            message = state.message,
            onRetry = { viewModel.openMoodGenre(mood) },
            modifier = modifier,
        )
        is UiState.Success -> TvShelfFeed(
            shelves = state.data,
            onPlaySong = { song ->
                scope.launch { mediaController?.playSongs(listOf(song), 0) }
                onNavigateToNowPlaying()
            },
            onSongMenu = { menuSong = it },
            onNavigateToDetail = onNavigateToDetail,
            header = {
                Text(
                    text = mood.title,
                    style = TvType.LargeTitle,
                    color = TvGlass.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        start = TvDimensions.SafeMarginHorizontal,
                        end = TvDimensions.SafeMarginHorizontal,
                        bottom = 3.dp,
                    ),
                )
            },
            modifier = modifier,
        )
    }

    menuSong?.let { song ->
        TvSongMenu(
            song = song,
            viewModel = viewModel,
            mediaController = mediaController,
            onNavigateToDetail = onNavigateToDetail,
            onDismiss = { menuSong = null },
        )
    }
}

/** The phone's own floor for a mood card's width; the grid fits as many columns as that allows. */
private val MOOD_MIN_WIDTH = 176.dp
private val MOOD_SPACING = 14.dp
