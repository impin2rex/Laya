package com.music.laya.ios

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.laya.data.YtMusicRepository
import com.music.laya.data.innertube.InnertubeParser
import com.music.laya.data.lyrics.LyricLine
import com.music.laya.data.lyrics.LyricsRepository
import com.music.laya.data.lyrics.LyricsSource
import com.music.laya.data.model.HomeShelf
import com.music.laya.data.model.LikeStatus
import com.music.laya.data.model.MoodGenreSection
import com.music.laya.data.model.SearchFilter
import com.music.laya.data.model.SearchResult
import com.music.laya.data.model.ShelfItem
import com.music.laya.data.model.Song
import com.music.laya.data.model.UiState
import com.music.laya.ui.LyricsProviderState
import com.music.laya.ui.components.BottomTab
import com.music.laya.ui.components.GlassNavBar
import com.music.laya.ui.components.LocalAppBackdrop
import com.music.laya.ui.components.LocalLiquidGlassEnabled
import com.music.laya.ui.components.backdrop.backdrops.layerBackdrop
import com.music.laya.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.music.laya.ui.components.floatingtabbar.rememberFloatingTabBarScrollConnection
import com.music.laya.ui.player.LocalPlayerDock
import com.music.laya.ui.player.PLAYER_PULL_FLING_VELOCITY
import com.music.laya.ui.player.PLAYER_SCRIM_ALPHA
import com.music.laya.ui.player.PlayerDock
import com.music.laya.ui.player.PlayerSheetMotion
import com.music.laya.ui.player.PlayerSheetShape
import com.music.laya.ui.theme.LayaTheme
import com.music.laya.ui.utils.guardSheetFromContentTouches
import com.music.laya.ui.player.NowPlayingScreen
import com.music.laya.ui.player.RepeatModes
import com.music.laya.ui.screens.ExploreScreen
import com.music.laya.ui.screens.HomeScreen
import com.music.laya.ui.screens.LibraryScreen
import com.music.laya.ui.screens.SearchScreen
import kotlinx.coroutines.launch
import platform.Foundation.NSProcessInfo

private fun ShelfItem.toSong() = videoId?.let {
    Song(it, title, InnertubeParser.artistFromSubtitle(subtitle), thumbnailUrl)
}

private fun List<ShelfItem>.songs() = mapNotNull { it.toSong() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayaIosApp(engine: AudioEngine) {
    val scope = rememberCoroutineScope()
    val player = remember { IosPlayer(engine, scope) }
    var tab by remember { mutableIntStateOf(0) }
    var nowPlayingOpen by remember { mutableStateOf(false) }
    val playerDock = remember { PlayerDock() }
    val sheetMotion = remember { PlayerSheetMotion(scope, playerDock, { nowPlayingOpen }, { nowPlayingOpen = it }) }
    sheetMotion.windowInfo = LocalWindowInfo.current
    sheetMotion.flingVelocity = with(LocalDensity.current) { PLAYER_PULL_FLING_VELOCITY.toPx() }
    val snackbar = remember { SnackbarHostState() }
    val message by IosPlayerHost.messages.collectAsState()
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); IosPlayerHost.messages.value = null }
    }

    // Home / Explore: same calls MainViewModel makes for the first page.
    var home by remember { mutableStateOf<UiState<List<HomeShelf>>>(UiState.Loading) }
    var explore by remember { mutableStateOf<UiState<List<MoodGenreSection>>>(UiState.Loading) }
    suspend fun loadHome() {
        home = YtMusicRepository.home().fold({ UiState.Success(it.shelves) }, { UiState.Error(it.message ?: "Error") })
    }
    suspend fun loadExplore() {
        explore = YtMusicRepository.moodAndGenres().fold({ UiState.Success(it) }, { UiState.Error(it.message ?: "Error") })
    }
    LaunchedEffect(Unit) { loadHome(); loadExplore() }

    // Search
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(SearchFilter.ALL) }
    var results by remember { mutableStateOf<UiState<List<SearchResult>>?>(null) }
    suspend fun search() {
        if (query.isBlank()) return
        results = UiState.Loading
        results = YtMusicRepository.searchPage(query, filter).fold({ UiState.Success(it.rows) }, { UiState.Error(it.message ?: "Error") })
    }
    // ponytail: first page only (no loadMore), no history/suggestions yet.

    // Lyrics for the playing song, once its duration is known.
    val song = player.song
    var lyrics by remember(song?.videoId) { mutableStateOf<List<LyricLine>?>(null) }
    var lyricsSource by remember(song?.videoId) { mutableStateOf<LyricsSource?>(null) }
    var lyricsChecked by remember(song?.videoId) { mutableStateOf(false) }
    val durationKnown = player.durationMs > 0
    LaunchedEffect(song?.videoId, durationKnown) {
        if (song == null || !durationKnown) return@LaunchedEffect
        val found = LyricsRepository.lyrics(song.videoId, song.title, song.artist, player.durationMs, song.albumName)
        lyrics = found?.lines
        lyricsSource = found?.source
        lyricsChecked = true
    }

    // Test hook: `simctl launch` with SIMCTL_CHILD_LAYA_AUTOPLAY=<query> searches and plays the first hit.
    LaunchedEffect(Unit) {
        val q = NSProcessInfo.processInfo.environment["LAYA_AUTOPLAY"] as? String ?: return@LaunchedEffect
        YtMusicRepository.search(q, SearchFilter.SONGS).getOrNull()
            ?.mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
            ?.let { if (it.isNotEmpty()) player.play(it, 0) }
    }

    val homeList = rememberLazyListState()
    val exploreList = rememberLazyListState()
    val libraryList = rememberLazyListState()
    val searchList = rememberLazyListState()
    val homePull = rememberPullToRefreshState()
    val explorePull = rememberPullToRefreshState()
    val libraryPull = rememberPullToRefreshState()
    val tabs = remember {
        listOf(
            BottomTab("Home", Icons.Filled.Home),
            BottomTab("Explore", Icons.Filled.Explore),
            BottomTab("Library", Icons.Filled.LibraryMusic),
            BottomTab("Search", Icons.Filled.Search),
        )
    }
    val navBarScroll = rememberFloatingTabBarScrollConnection()
    var barHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    LayaTheme {
        // The glass samples this layer; opaque like Android's, so it has something to blur.
        val windowBackground = MaterialTheme.colorScheme.background
        val appBackdrop = rememberLayerBackdrop(onDraw = remember(windowBackground) { { drawRect(windowBackground); drawContent() } })
        CompositionLocalProvider(LocalLiquidGlassEnabled provides true, LocalAppBackdrop provides appBackdrop) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val noPadding = PaddingValues(bottom = barHeight)
                    Box(Modifier.fillMaxSize().layerBackdrop(appBackdrop).nestedScroll(navBarScroll)) {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                            when (tab) {
                            0 -> HomeScreen(
                                state = home,
                                listState = homeList,
                                onItemClick = { item, shelfTitle ->
                                    // The tapped card plus the rest of its shelf's tracks as the queue.
                                    val shelf = (home as? UiState.Success)?.data?.firstOrNull { it.title == shelfTitle }
                                    val tracks = shelf?.items?.songs().orEmpty()
                                    val tapped = item.toSong()
                                    when {
                                        tapped == null -> Unit // TODO(4d): detail pages
                                        else -> player.play(tracks.ifEmpty { listOf(tapped) },
                                            tracks.indexOfFirst { it.videoId == tapped.videoId }.coerceAtLeast(0))
                                    }
                                },
                                onRetry = { scope.launch { home = UiState.Loading; loadHome() } },
                                refreshing = false,
                                onRefresh = { scope.launch { loadHome() } },
                                pullState = homePull,
                                contentPadding = noPadding,
                                title = "Listen now",
                                signedIn = false, // TODO(4d): sign-in
                                currentSong = song,
                                isPlaying = player.isPlaying,
                            )
                            1 -> ExploreScreen(
                                state = explore,
                                listState = exploreList,
                                onCategoryClick = {}, // TODO(4d): mood/genre pages
                                onRetry = { scope.launch { explore = UiState.Loading; loadExplore() } },
                                refreshing = false,
                                onRefresh = { scope.launch { loadExplore() } },
                                pullState = explorePull,
                                contentPadding = noPadding,
                            )
                            2 -> LibraryScreen(
                                signedIn = false, // TODO(4d): sign-in, then YtMusicRepository.library()
                                state = UiState.Loading,
                                listState = libraryList,
                                onShelfItemClick = { it.toSong()?.let { s -> player.play(listOf(s), 0) } },
                                onShelfItemLongPress = {},
                                onNewPlaylist = {},
                                onShowAll = {},
                                replay = {},
                                onSignIn = {},
                                onRetry = {},
                                refreshing = false,
                                onRefresh = {},
                                pullState = libraryPull,
                                contentPadding = noPadding,
                                links = emptyList(),
                                deviceItems = emptyList(),
                            )
                            else -> SearchScreen(
                                query = query,
                                onQueryChange = { query = it },
                                filter = filter,
                                currentSong = song,
                                isPlaying = player.isPlaying,
                                onFilterChange = { filter = it; scope.launch { search() } },
                                results = results,
                                loadingMore = false,
                                onLoadMore = {},
                                listState = searchList,
                                scrollResetTrigger = 0,
                                focusRequested = false,
                                onFocusHandled = {},
                                onSongClick = { songs, i -> player.play(songs, i) },
                                onSongLongPress = {},
                                onSongSwipe = {},
                                onTopResultPlay = { player.play(listOf(it), 0) },
                                onTopResultPlaylist = {},
                                onBrowseClick = {}, // TODO(4d): detail pages
                                history = emptyList(),
                                suggestions = emptyList(),
                                typeaheadResults = emptyList(),
                                onSubmit = { scope.launch { search() } },
                                onSuggestionClick = { query = it; scope.launch { search() } },
                                onHistoryClick = {},
                                onHistoryRemove = {},
                                onHistoryClear = {},
                                contentPadding = noPadding,
                                topPadding = 0.dp,
                            )
                        }
                            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = barHeight))
                        }
                    }
                    GlassNavBar(
                        tabs = tabs,
                        selectedIndex = tab,
                        onTabSelected = { tab = it },
                        scrollConnection = navBarScroll,
                        song = song,
                        isPlaying = player.isPlaying,
                        isLoading = player.isLoading,
                        onPlayPause = player::togglePlayPause,
                        onNext = player::next,
                        onPrevious = player::previous,
                        onExpand = sheetMotion::open,
                        dock = playerDock,
                        pull = sheetMotion,
                        modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged {
                            barHeight = with(density) { it.height.toDp() }
                        },
                    )

                    if (nowPlayingOpen && song != null) {
                        val sheetState = remember {
                            SheetState(
                                skipPartiallyExpanded = true,
                                positionalThreshold = { with(density) { 56.dp.toPx() } },
                                velocityThreshold = { with(density) { 125.dp.toPx() } },
                                initialValue = if (sheetMotion.holding) SheetValue.Expanded else SheetValue.Hidden,
                                confirmValueChange = { value ->
                                    if (value == SheetValue.Hidden) { sheetMotion.close(); false } else true
                                },
                                skipHiddenState = false,
                            )
                        }
                        DisposableEffect(sheetState) {
                            sheetMotion.sheet = sheetState
                            playerDock.sheetOffset = sheetMotion::position
                            onDispose {
                                playerDock.sheetOffset = null
                                sheetMotion.onSheetGone()
                            }
                        }
                        ModalBottomSheet(
                            onDismissRequest = { nowPlayingOpen = false },
                            sheetState = sheetState,
                            scrimColor = Color.Transparent,
                            shape = PlayerSheetShape,
                            containerColor = Color.Transparent,
                            dragHandle = null,
                            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                            sheetMaxWidth = Dp.Unspecified,
                        ) {
                            Box(
                                Modifier
                                    .offset { IntOffset(0, sheetMotion.contentOffsetPx()) }
                                    .drawBehind {
                                        val open = playerDock.openFraction()
                                        if (open > 0f && !(open >= 1f && playerDock.attached)) {
                                            val top = sheetMotion.position().takeUnless { it.isNaN() } ?: 0f
                                            drawRect(
                                                Color.Black.copy(alpha = PLAYER_SCRIM_ALPHA * open),
                                                Offset(0f, -top.toInt().toFloat()),
                                                Size(size.width, size.height),
                                            )
                                        }
                                    }
                                    .guardSheetFromContentTouches(sheetState)
                                    .onSizeChanged {
                                        playerDock.sheetTravel = it.height.toFloat()
                                        sheetMotion.laidOut = true
                                    },
                            ) {
                                CompositionLocalProvider(LocalPlayerDock provides playerDock) {
                                    BoxWithConstraints(Modifier.fillMaxSize()) {
                                        NowPlayingScreen(
                                        song = song,
                                        isPlaying = player.isPlaying,
                                        isLoading = player.isLoading,
                                        position = player.position,
                                        durationMs = player.durationMs,
                                        audioVersionSwitching = false,
                                        qualityUpgraded = false,
                                        queue = player.queue,
                                        queueIndex = player.index,
                                        hasPrevious = player.hasPrevious,
                                        hasNext = player.hasNext,
                                        repeatMode = RepeatModes.OFF,
                                        shuffleEnabled = false,
                                        autoplayEnabled = false,
                                        signedIn = false,
                                        accountName = null,
                                        likeStatus = LikeStatus.INDIFFERENT,
                                        onToggleLike = {}, // TODO(4d): needs sign-in
                                        onPlayPause = player::togglePlayPause,
                                        onNext = player::next,
                                        onPrevious = player::previous,
                                        onBlockedControl = {},
                                        onSeek = player::seekTo,
                                        onSeekFraction = player::seekFraction,
                                        onToggleShuffle = {},
                                        onCycleRepeat = {},
                                        onToggleAutoplay = {},
                                        onJumpTo = player::jumpTo,
                                        onRemoveFromQueue = {},
                                        onMoveInQueue = { _, _ -> },
                                        onClearQueue = {},
                                        onOpenMenu = {},
                                        onOpenAlbum = {}, // TODO(4d): detail pages
                                        onOpenArtist = { _, _ -> },
                                        onOpenPlaybackSource = {},
                                        onListenTogether = {},
                                        lyrics = lyrics,
                                        lyricsSource = lyricsSource,
                                        lyricsProviderStates = LyricsSource.entries.associateWith { LyricsProviderState.NOT_FETCHED },
                                        onSelectLyricsProvider = {},
                                        lyricsUnavailable = lyricsChecked && lyrics == null,
                                        lyricsOffsetOpen = false,
                                        onDismissLyricsOffset = {},
                                        windowWidth = maxWidth,
                                        windowHeight = maxHeight,
                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
