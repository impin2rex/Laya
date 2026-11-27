package com.music.laya.ui.tv.screens

import androidx.compose.ui.res.stringResource
import com.music.laya.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import com.music.laya.ui.components.VoiceSearchButton
import com.music.laya.ui.tv.theme.TvGlass
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.music.laya.data.model.BrowseItem
import com.music.laya.data.model.BrowseType
import com.music.laya.data.model.EntityType
import com.music.laya.data.model.SearchFilter
import com.music.laya.data.model.SearchHistoryEntity
import com.music.laya.data.model.SearchResult
import com.music.laya.data.model.Song
import com.music.laya.data.model.UiState
import com.music.laya.playback.playSongs
import com.music.laya.ui.MainViewModel
import com.music.laya.ui.tv.components.TvActivityIndicator
import com.music.laya.ui.tv.components.TvButton
import com.music.laya.ui.tv.components.TvChromeScrollEffect
import com.music.laya.ui.tv.components.TvEmptyState
import com.music.laya.ui.tv.components.TvErrorState
import com.music.laya.ui.tv.components.TvListRow
import com.music.laya.ui.tv.components.TvLockup
import com.music.laya.ui.tv.components.TvSegmented
import com.music.laya.ui.tv.components.TvShelf
import com.music.laya.ui.tv.components.TvShelfTitle
import com.music.laya.ui.tv.components.TvSongRow
import com.music.laya.ui.tv.components.TvTextField
import com.music.laya.ui.tv.dialogs.TvSongMenu
import com.music.laya.ui.tv.theme.TvDimensions
import kotlinx.coroutines.launch

private val SearchFilters = listOf(
    SearchFilter.ALL,
    SearchFilter.SONGS,
    SearchFilter.ALBUMS,
    SearchFilter.ARTISTS,
    SearchFilter.PLAYLISTS,
)

@Composable
fun TvSearchScreen(
    viewModel: MainViewModel,
    mediaController: MediaController?,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    onNavigateToNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val query by viewModel.query.collectAsState()
    val youTubeResults by viewModel.results.collectAsState()
    // The Addons tab answers from every enabled addon and Tidal/Qobuz instead of YouTube Music.
    val searchSource by viewModel.searchSource.collectAsState()
    val addonResults by viewModel.addonResults.collectAsState()
    val searchingAddons = searchSource == com.music.laya.ui.SearchSource.ADDONS
    val resultsState: UiState<List<SearchResult>>? = if (searchingAddons) {
        when (val state = addonResults) {
            null -> null
            UiState.Loading -> UiState.Loading
            is UiState.Error -> UiState.Error(state.message)
            is UiState.Success -> UiState.Success(state.data.map { SearchResult.Track(it) })
        }
    } else {
        youTubeResults
    }
    val filter by viewModel.filter.collectAsState()
    val history by viewModel.searchHistory.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val loadingMore by viewModel.searchLoadingMore.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var menuSong by remember { mutableStateOf<Song?>(null) }

    val listState = rememberLazyListState()
    TvChromeScrollEffect(listState)

    val playSong: (Song) -> Unit = { song ->
        scope.launch { mediaController?.playSongs(listOf(song), 0) }
        onNavigateToNowPlaying()
    }
    // Whatever is played or opened from here is remembered, as the phone does,
    // so it shows under Recent Searches next time.
    val playResult: (Song) -> Unit = { song ->
        viewModel.recordEntity(
            SearchHistoryEntity(
                id = song.videoId,
                title = song.title,
                subtitle = song.artist,
                artworkUrl = song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ),
        )
        playSong(song)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Most TV remotes route their microphone to the assistant only; offer recognition where the box has one of its own.
    val hasMicrophone = remember { com.music.laya.ui.tv.DeviceType.hasMicrophone(context) }
    var recognizing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (recognizing) {
        TvRecognition(onPlay = { recognizing = false; playSong(it) }, onClose = { recognizing = false })
        return
    }
    val openBrowse: (BrowseItem) -> Unit = { item ->
        viewModel.recordEntity(
            SearchHistoryEntity(
                id = item.browseId,
                title = item.title,
                subtitle = item.subtitle,
                artworkUrl = item.thumbnailUrl,
                entityType = when (item.type) {
                    BrowseType.ALBUM -> EntityType.ALBUM
                    BrowseType.ARTIST -> EntityType.ARTIST
                    BrowseType.PLAYLIST -> EntityType.PLAYLIST
                    else -> EntityType.TRACK
                },
            ),
        )
        onNavigateToDetail(item.browseId, item.title, item.subtitle, item.thumbnailUrl, item.type)
    }

    val results = (resultsState as? UiState.Success)?.data
    val deduped = remember(results) { results?.deduplicated().orEmpty() }
    if (results != null && filter != SearchFilter.ALL) {
        val nearEnd by remember(listState) {
            derivedStateOf {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= listState.layoutInfo.totalItemsCount - 3
            }
        }
        LaunchedEffect(nearEnd, results.size) { if (nearEnd) viewModel.loadMoreSearchResults() }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = TvDimensions.ContentTop, bottom = 45.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "field") {
            Column(
                modifier = Modifier.padding(horizontal = TvDimensions.SafeMarginHorizontal),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.widthIn(max = 576.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvTextField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = stringResource(R.string.tv_search_placeholder),
                        leadingIcon = Icons.Rounded.Search,
                        imeAction = ImeAction.Search,
                        onImeAction = {
                            keyboard?.hide()
                            viewModel.submitSearch()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    // Its own D-pad stop beside the field; the ring shows when it's focused.
                    var micFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .onFocusChanged { micFocused = it.hasFocus }
                            .background(
                                if (micFocused) TvGlass.OnPlatter.copy(alpha = 0.25f) else Color.Transparent,
                                CircleShape,
                            )
                            .padding(4.dp),
                    ) {
                        VoiceSearchButton(onPartial = viewModel::onQueryChange, onResult = { spoken ->
                            viewModel.onQueryChange(spoken)
                            viewModel.submitSearch()
                        })
                    }
                    if (hasMicrophone) {
                        var recognizeFocused by remember { mutableStateOf(false) }
                        androidx.compose.material3.IconButton(
                            onClick = { recognizing = true },
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .onFocusChanged { recognizeFocused = it.hasFocus }
                                .background(
                                    if (recognizeFocused) TvGlass.OnPlatter.copy(alpha = 0.25f) else Color.Transparent,
                                    CircleShape,
                                ),
                        ) {
                            androidx.compose.material3.Icon(
                                com.music.laya.ui.screens.RecognitionIcon,
                                contentDescription = androidx.compose.ui.res.stringResource(com.music.laya.R.string.recognize_title),
                                // The voice-search mic's colour beside it; OnPlatter is for a light platter, black here.
                                tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                TvSegmented(
                    options = listOf(
                        stringResource(R.string.search_source_youtube),
                        stringResource(R.string.search_source_addons),
                    ),
                    selectedIndex = if (searchingAddons) 1 else 0,
                    onSelect = {
                        viewModel.setSearchSource(
                            if (it == 1) com.music.laya.ui.SearchSource.ADDONS else com.music.laya.ui.SearchSource.YOUTUBE,
                        )
                    },
                )
                if (resultsState != null && !searchingAddons) {
                    Spacer(modifier = Modifier.height(14.dp))
                    TvSegmented(
                        options = SearchFilters.map { it.label },
                        selectedIndex = SearchFilters.indexOf(filter).coerceAtLeast(0),
                        onSelect = { viewModel.onFilterChange(SearchFilters[it]) },
                    )
                }
                Spacer(modifier = Modifier.height(11.dp))
            }
        }

        when (val state = resultsState) {
            null -> if (query.isNotBlank() && suggestions.isNotEmpty()) {
                items(suggestions, key = { "suggest:$it" }) { suggestion ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TvListRow(
                            title = suggestion,
                            leadingIcon = Icons.Rounded.Search,
                            resting = androidx.compose.ui.graphics.Color.Transparent,
                            modifier = Modifier.widthIn(max = 576.dp),
                            onClick = {
                                keyboard?.hide()
                                viewModel.searchFor(suggestion)
                            },
                        )
                    }
                }
            } else {
                idleContent(
                    history = history,
                    onHistory = { entity ->
                        val type = when (entity.entityType) {
                            EntityType.ALBUM -> BrowseType.ALBUM
                            EntityType.ARTIST -> BrowseType.ARTIST
                            EntityType.PLAYLIST -> BrowseType.PLAYLIST
                            EntityType.TRACK -> null
                        }
                        when {
                            type != null -> onNavigateToDetail(entity.id, entity.title, entity.subtitle, entity.artworkUrl, type)
                            // A typed query with nothing behind it: run it again.
                            entity.id.startsWith("q:") || entity.id.isBlank() -> viewModel.searchFor(entity.title)
                            else -> playSong(
                                Song(
                                    videoId = entity.id,
                                    title = entity.title,
                                    artist = entity.subtitle,
                                    thumbnailUrl = entity.artworkUrl,
                                ),
                            )
                        }
                    },
                    onClearHistory = viewModel::clearSearchHistory,
                )
            }
            UiState.Loading -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    TvActivityIndicator()
                }
            }
            is UiState.Error -> item(key = "error") {
                TvErrorState(message = state.message, onRetry = viewModel::submitSearch, modifier = Modifier.height(256.dp))
            }
            is UiState.Success -> if (deduped.isEmpty()) {
                item(key = "empty") {
                    TvEmptyState(
                        title = stringResource(R.string.tv_no_results_title),
                        message = stringResource(R.string.tv_no_results_message, query),
                        modifier = Modifier.height(256.dp),
                    )
                }
            } else if (filter == SearchFilter.ALL && !searchingAddons) {
                groupedResults(deduped, playResult, openBrowse, onSongMenu = { menuSong = it })
            } else {
                filteredResults(deduped, playResult, openBrowse, onSongMenu = { menuSong = it })
                if (loadingMore) {
                    item(key = "more") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            TvActivityIndicator()
                        }
                    }
                }
            }
        }
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

/**
 * Before a search: what was searched for lately, laid out as the results are —
 * a grid of lockups — with a Clear on its heading.
 */
private fun LazyListScope.idleContent(
    history: List<com.music.laya.data.model.SearchHistoryEntity>,
    onHistory: (com.music.laya.data.model.SearchHistoryEntity) -> Unit,
    onClearHistory: () -> Unit,
) {
    if (history.isEmpty()) {
        item(key = "idleEmpty") {
            TvEmptyState(
                title = stringResource(R.string.tv_search_laya_title),
                message = stringResource(R.string.tv_search_laya_message),
                modifier = Modifier.height(240.dp),
            )
        }
        return
    }
    item(key = "recentTitle") {
        TvShelfTitle(
            title = stringResource(R.string.tv_recent_searches),
            trailing = { TvButton(text = stringResource(R.string.clear), onClick = onClearHistory) },
            modifier = Modifier.padding(top = 6.dp, bottom = 5.dp),
        )
    }
    items(history.chunked(GridColumns), key = { row -> "recent:" + row.first().let { "${it.entityType}:${it.id}:${it.title}" } }) { row ->
        TvGridRow(count = row.size) { index, cell ->
            val entity = row[index]
            TvLockup(
                title = entity.title,
                subtitle = entity.subtitle.takeIf { it.isNotBlank() },
                artworkUrl = entity.artworkUrl,
                circle = entity.entityType == EntityType.ARTIST,
                width = Dp.Unspecified,
                onClick = { onHistory(entity) },
                modifier = cell,
            )
        }
    }
}

/** Columns in Search's grids: the recent searches and a single-kind result list. */
private const val GridColumns = 5

/**
 * One row of a five-column grid, aligned to the safe margins. Short rows keep
 * their cells the width of a full row's.
 */
@Composable
private fun TvGridRow(count: Int, cell: @Composable RowScope.(index: Int, cell: Modifier) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = TvDimensions.SafeMarginHorizontal, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(TvDimensions.CardSpacing),
    ) {
        repeat(count) { index -> cell(index, Modifier.weight(1f)) }
        repeat(GridColumns - count) { Spacer(Modifier.weight(1f)) }
    }
}

/** "All": the best matches first, then a shelf per kind. */
private fun LazyListScope.groupedResults(
    results: List<SearchResult>,
    onPlaySong: (Song) -> Unit,
    onOpen: (BrowseItem) -> Unit,
    onSongMenu: (Song) -> Unit,
) {
    val songs = results.mapNotNull { it.song() }
    val browse = results.filterIsInstance<SearchResult.Browse>().map { it.item }

    item(key = "top") {
        TvShelf(title = stringResource(R.string.tv_top_results), items = results.take(10), key = { it.resultKey() }) { result ->
            ResultLockup(result, onPlaySong, onOpen, onSongMenu)
        }
    }
    if (songs.isNotEmpty()) {
        item(key = "songsTitle") { TvShelfTitle(title = stringResource(R.string.songs)) }
        songGrid(songs.take(12), onPlaySong, onSongMenu, keyPrefix = "song")
    }
    listOf(
        BrowseType.ARTIST to R.string.artists,
        BrowseType.ALBUM to R.string.albums,
        BrowseType.PLAYLIST to R.string.playlists,
        BrowseType.OTHER to R.string.more,
    ).forEach { (type, title) ->
        val group = browse.filter { it.type == type }
        if (group.isNotEmpty()) {
            item(key = "group:$type") {
                TvShelf(title = stringResource(title), items = group, key = { it.browseId }) { item ->
                    TvLockup(
                        title = item.title,
                        subtitle = if (type == BrowseType.ARTIST) null else item.subtitle,
                        artworkUrl = item.thumbnailUrl,
                        circle = type == BrowseType.ARTIST,
                        width = if (type == BrowseType.ARTIST) 120.dp else 131.dp,
                        onClick = { onOpen(item) },
                    )
                }
            }
        }
    }
}

/** One kind only: songs as a three-column list, everything else as a grid of lockups. */
private fun LazyListScope.filteredResults(
    results: List<SearchResult>,
    onPlaySong: (Song) -> Unit,
    onOpen: (BrowseItem) -> Unit,
    onSongMenu: (Song) -> Unit,
) {
    val songs = results.mapNotNull { it.song() }
    if (songs.size >= results.size / 2) {
        songGrid(songs, onPlaySong, onSongMenu, keyPrefix = "fsong")
        return
    }
    val browse = results.filterIsInstance<SearchResult.Browse>().map { it.item }
    items(browse.chunked(GridColumns), key = { row -> "frow:${row.first().browseId}" }) { row ->
        TvGridRow(count = row.size) { index, cell ->
            val item = row[index]
            TvLockup(
                title = item.title,
                subtitle = if (item.type == BrowseType.ARTIST) null else item.subtitle,
                artworkUrl = item.thumbnailUrl,
                circle = item.type == BrowseType.ARTIST,
                width = Dp.Unspecified,
                onClick = { onOpen(item) },
                modifier = cell,
            )
        }
    }
}

private fun LazyListScope.songGrid(
    songs: List<Song>,
    onPlaySong: (Song) -> Unit,
    onSongMenu: (Song) -> Unit,
    keyPrefix: String,
) {
    items(songs.chunked(3), key = { row -> "$keyPrefix:${row.first().videoId}" }) { row ->
        Row(
            modifier = Modifier.padding(horizontal = TvDimensions.SafeMarginHorizontal - 11.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            row.forEach { song ->
                TvSongRow(
                    song = song,
                    showArtwork = true,
                    onClick = { onPlaySong(song) },
                    onLongClick = { onSongMenu(song) },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ResultLockup(
    result: SearchResult,
    onPlaySong: (Song) -> Unit,
    onOpen: (BrowseItem) -> Unit,
    onSongMenu: (Song) -> Unit,
) {
    val song = result.song()
    if (song != null) {
        TvLockup(
            title = song.title,
            subtitle = song.artist,
            artworkUrl = song.thumbnailUrl,
            width = 131.dp,
            onClick = { onPlaySong(song) },
            onLongClick = { onSongMenu(song) },
        )
    } else if (result is SearchResult.Browse) {
        val item = result.item
        TvLockup(
            title = item.title,
            subtitle = if (item.type == BrowseType.ARTIST) null else item.subtitle,
            artworkUrl = item.thumbnailUrl,
            circle = item.type == BrowseType.ARTIST,
            width = if (item.type == BrowseType.ARTIST) 120.dp else 131.dp,
            onClick = { onOpen(item) },
        )
    }
}

private fun SearchResult.song(): Song? = when (this) {
    is SearchResult.TopTrack -> song
    is SearchResult.Track -> song
    is SearchResult.Browse -> null
}

private fun SearchResult.resultKey(): String = when (this) {
    is SearchResult.TopTrack -> "t:${song.videoId}"
    is SearchResult.Track -> "t:${song.videoId}"
    is SearchResult.Browse -> "b:${item.browseId}"
}

/** Drops repeats: the same video id, the same title-and-artist, or the same browse id. */
private fun List<SearchResult>.deduplicated(): List<SearchResult> {
    val ids = HashSet<String>()
    val names = HashSet<String>()
    return filter { result ->
        when (result) {
            is SearchResult.Browse -> result.item.browseId.isBlank() || ids.add("b:" + result.item.browseId)
            else -> {
                val song = result.song() ?: return@filter true
                val name = song.title.trim().lowercase() + "|" + song.artist.trim().lowercase()
                (song.videoId.isBlank() || ids.add("t:" + song.videoId)) && names.add(name)
            }
        }
    }
}

/** Music recognition over the Search page; Back returns to it. In-app only — the app is on screen the whole time. */
@Composable
private fun TvRecognition(onPlay: (Song) -> Unit, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.activity.compose.BackHandler(onBack = onClose)
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { com.music.laya.data.recognition.MusicRecognizer.cancel() }
    }
    // The phone's path: the microphone permission, then the listening service.
    com.music.laya.ui.screens.RecognitionScreen(
        onListen = { com.music.laya.recognition.listen(context, notifyResult = false) },
        onPlay = onPlay,
        contentPadding = PaddingValues(top = TvDimensions.ContentTop, bottom = 45.dp),
        modifier = Modifier.padding(horizontal = TvDimensions.SafeMarginHorizontal),
    )
}
