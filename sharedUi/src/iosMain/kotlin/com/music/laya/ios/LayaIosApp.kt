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
import com.music.laya.data.model.EntityType
import com.music.laya.data.model.SearchFilter
import com.music.laya.data.model.SearchHistoryEntity
import com.music.laya.data.model.toSong
import com.music.laya.ui.screens.SegmentedControl
import com.music.laya.sharedui.resources.library
import com.music.laya.sharedui.resources.search_source_addons
import com.music.laya.sharedui.resources.search_source_youtube
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay
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
import com.music.laya.ui.screens.DetailScreen
import com.music.laya.ui.screens.ExploreScreen
import com.music.laya.ui.screens.MoodGenrePlaylistsScreen
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.key
import com.music.laya.data.model.BrowseType
import com.music.laya.data.model.DetailPage
import com.music.laya.data.model.MoodGenre
import com.music.laya.data.model.withoutRepeatsOf
import com.music.laya.data.settings.AppSettings
import com.music.laya.ui.screens.HomeScreen
import com.music.laya.ui.screens.LibraryGridPage
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.music.laya.ui.screens.LibraryScreen
import com.music.laya.ui.screens.LibraryLink
import com.music.laya.ui.screens.SPOTIFY_BROWSE_ID
import com.music.laya.ui.screens.SpotifyLibraryScreen
import com.music.laya.data.model.isUnresolvedSpotify
import com.music.laya.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.laya.sharedui.resources.spotify
import com.music.laya.sharedui.resources.spotify_library_subtitle
import com.music.laya.sharedui.resources.spotify_logo
import androidx.compose.material.icons.rounded.Cloud
import org.jetbrains.compose.resources.stringResource
import com.music.laya.ui.screens.SearchScreen
import com.music.laya.ui.screens.EqualizerScreen
import com.music.laya.ui.screens.SettingsScreen
import com.music.laya.ui.onboarding.OnboardingScreen
import androidx.compose.animation.EnterTransition
import androidx.compose.material.icons.rounded.Settings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import platform.Foundation.NSProcessInfo
import androidx.compose.material.icons.rounded.History
import com.music.laya.data.LikeState
import com.music.laya.data.model.LibraryPage
import com.music.laya.sharedui.resources.Res
import com.music.laya.sharedui.resources.history_empty
import com.music.laya.sharedui.resources.library_empty
import com.music.laya.ui.screens.HistoryScreen
import org.jetbrains.compose.resources.getString

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
    // Pushed pages (one global stack; a tab tap clears it) and the long-press menu.
    var stack by remember { mutableStateOf(emptyList<IosPage>()) }
    var menuSong by remember { mutableStateOf<Song?>(null) }
    val repeat by AppSettings.repeatMode.collectAsState()
    val shuffle by AppSettings.shuffleEnabled.collectAsState()
    val autoplay by AppSettings.autoplay.collectAsState()
    val onboardingComplete by AppSettings.onboardingComplete.collectAsState()
    val swipeNext by AppSettings.swipeToPlayNext.collectAsState()
    val playerDock = remember { PlayerDock() }
    val sheetMotion = remember { PlayerSheetMotion(scope, playerDock, { nowPlayingOpen }, { nowPlayingOpen = it }) }
    sheetMotion.windowInfo = LocalWindowInfo.current
    sheetMotion.flingVelocity = with(LocalDensity.current) { PLAYER_PULL_FLING_VELOCITY.toPx() }
    val signedIn by IosAuth.signedIn.collectAsState()
    val account by IosAuth.account.collectAsState()
    var showLogin by remember { mutableStateOf(false) }
    val spotifyConnected = AppSettings.spotifySpdcToken.collectAsState().value.isNotBlank()
    var showSpotifyLogin by remember { mutableStateOf(false) }
    var showSpotifyImport by remember { mutableStateOf(false) }
    var editingSignatureApi by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val message by IosPlayerHost.messages.collectAsState()
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); IosPlayerHost.messages.value = null }
    }

    // Home / Explore: same calls MainViewModel makes for the first page.
    var home by remember { mutableStateOf<UiState<List<HomeShelf>>>(UiState.Loading) }
    var explore by remember { mutableStateOf<UiState<List<MoodGenreSection>>>(UiState.Loading) }
    // Mirrors MainViewModel: core feed + supplements + signed-in Recents load in parallel and publish as they land.
    val homeShelves = remember { mutableListOf<HomeShelf>() }
    val homeSeen = remember { mutableSetOf<String>() }
    var homeContinuation by remember { mutableStateOf<String?>(null) }
    var homePaging by remember { mutableStateOf(false) }
    var homePending by remember { mutableIntStateOf(0) }
    var recentsLoading by remember { mutableStateOf(false) }
    fun publishHome(shelves: List<HomeShelf>, prepend: Boolean = false) {
        if (prepend) {
            val titles = shelves.map { it.title.lowercase() }.toSet()
            homeSeen += titles
            homeShelves.removeAll { it.title.lowercase() in titles }
            homeShelves.addAll(0, shelves)
        } else {
            val added = shelves.withoutRepeatsOf(homeShelves).filter { homeSeen.add(it.title.lowercase()) }
            if (added.isEmpty()) return
            homeShelves += added
        }
        home = UiState.Success(homeShelves.toList())
    }
    suspend fun loadHome() = coroutineScope {
        homeShelves.clear(); homeSeen.clear(); homeContinuation = null; homePaging = false
        recentsLoading = signedIn
        homePending = 1 + YtMusicRepository.HOME_SUPPLEMENT_BROWSE_IDS.size
        launch {
            YtMusicRepository.home()
                .onSuccess { homeContinuation = it.continuation; publishHome(it.shelves) }
                .onFailure { if (home !is UiState.Success) home = UiState.Error(it.message ?: "Error") }
            homePending--
        }
        if (signedIn) launch {
            YtMusicRepository.homeRecentlyPlayed().onSuccess { shelf -> shelf?.let { publishHome(listOf(it), prepend = true) } }
            recentsLoading = false
        }
        YtMusicRepository.HOME_SUPPLEMENT_BROWSE_IDS.forEach { id ->
            launch {
                YtMusicRepository.homeSupplement(id).onSuccess { publishHome(it) }
                homePending--
            }
        }
    }
    fun loadMoreHome() {
        val token = homeContinuation ?: return
        if (homePaging) return
        homePaging = true
        scope.launch {
            YtMusicRepository.moreHome(token).onSuccess { feed ->
                val before = homeShelves.size
                publishHome(feed.shelves)
                // A page with nothing new means the feed looped back on itself; treat as exhausted.
                homeContinuation = feed.continuation.takeIf { homeShelves.size > before }
            }
            homePaging = false
        }
    }
    suspend fun loadExplore() = coroutineScope {
        explore = YtMusicRepository.moodAndGenres().fold({ UiState.Success(it) }, { UiState.Error(it.message ?: "Error") })
        // Category buttons carry no art; resolve it 4 at a time from the shelves they open (as MainViewModel does).
        val limiter = Semaphore(4)
        (explore as? UiState.Success)?.data?.flatMap { it.items }?.forEach { item ->
            launch {
                val art = limiter.withPermit { YtMusicRepository.moodGenreArtwork(item.browseId, item.params).getOrNull() } ?: return@launch
                val current = (explore as? UiState.Success)?.data ?: return@launch
                explore = UiState.Success(current.map { sec ->
                    sec.copy(items = sec.items.map { if (it.browseId == item.browseId && it.params == item.params) it.copy(thumbnailUrl = art) else it })
                })
            }
        }
    }
    LaunchedEffect(Unit) { launch { loadExplore() }; loadHome() }

    // Library: same calls MainViewModel.fetchLibrary makes (liked songs beyond page one are synced into LikeState).
    var library by remember { mutableStateOf<UiState<LibraryPage>>(UiState.Loading) }
    var libraryRefreshing by remember { mutableStateOf(false) }
    suspend fun loadLibrary() {
        library = YtMusicRepository.library().fold(
            onSuccess = { page ->
                page.likedContinuation?.let { token ->
                    scope.launch { YtMusicRepository.syncLikedMusic(token) { YtMusicRepository.moreSongs(it).getOrNull() } }
                }
                if (page.isEmpty) UiState.Error(getString(Res.string.library_empty)) else UiState.Success(page.copy(likedContinuation = null))
            },
            onFailure = { UiState.Error(it.message ?: "Error") },
        )
    }
    LaunchedEffect(signedIn) {
        if (signedIn) { library = UiState.Loading; loadLibrary(); loadHome() }
        else { library = UiState.Loading; stack = stack.filterNot { it is IosPage.Account || it is IosPage.History } }
    }
    fun openHistory() {
        val loading = IosPage.History(UiState.Loading)
        stack = stack + loading
        scope.launch {
            val songs = YtMusicRepository.history().fold(
                { if (it.isEmpty()) UiState.Error(getString(Res.string.history_empty)) else UiState.Success(it) },
                { UiState.Error(it.message ?: "Error") },
            )
            stack = stack.map { if (it === loading) IosPage.History(songs) else it }
        }
    }

    // Likes: optimistic via LikeState, rolled back if YouTube refuses (MainViewModel.setLike).
    val likes by LikeState.overrides.collectAsState()
    fun isLiked(id: String) = likes[id] == LikeStatus.LIKE
    fun toggleLike(id: String) {
        val previous = likes[id] ?: LikeStatus.INDIFFERENT
        val next = if (previous == LikeStatus.LIKE) LikeStatus.INDIFFERENT else LikeStatus.LIKE
        LikeState.set(id, next)
        scope.launch { YtMusicRepository.rate(id, next).onFailure { LikeState.set(id, previous) } }
    }
    // A track's own rating comes from its menu data; the playing track and the long-pressed one are looked up.
    LaunchedEffect(player.song?.videoId, signedIn) {
        val id = player.song?.videoId ?: return@LaunchedEffect
        if (signedIn) YtMusicRepository.songMenu(id).getOrNull()?.let { LikeState.rememberStated(id, it.likeStatus) }
    }
    LaunchedEffect(menuSong?.videoId, signedIn) {
        val id = menuSong?.videoId ?: return@LaunchedEffect
        if (signedIn) YtMusicRepository.songMenu(id).getOrNull()?.let { LikeState.rememberStated(id, it.likeStatus) }
    }

    fun openDetail(id: String, title: String, subtitle: String = "", thumb: String? = null, type: BrowseType = BrowseType.OTHER) {
        val base = loadingPage(id, title, subtitle, thumb, type)
        stack = stack + IosPage.Detail(base)
        if (id.startsWith(SPOTIFY_PAGE_PREFIX)) {
            scope.launch {
                loadSpotifyPage(id) { change ->
                    val open = stack.any { it is IosPage.Detail && it.page.browseId == id }
                    if (open) stack = stack.map { if (it is IosPage.Detail && it.page.browseId == id) IosPage.Detail(change(it.page)) else it }
                    open
                }
            }
            return
        }
        scope.launch {
            val loaded = loadDetail(base)
            stack = stack.map { if (it is IosPage.Detail && it.page === base) IosPage.Detail(loaded) else it }
        }
    }
    fun openMood(item: MoodGenre) {
        val loading = IosPage.Mood(item, UiState.Loading)
        stack = stack + loading
        scope.launch {
            val shelves = loadMood(item)
            stack = stack.map { if (it === loading) IosPage.Mood(item, shelves) else it }
        }
    }
    fun openShelfItem(item: ShelfItem) {
        item.browseId?.let { openDetail(it, item.title, item.subtitle, item.thumbnailUrl) }
    }
    fun swipe(song: Song) = if (swipeNext) player.playNext(song) else player.addToQueue(song)

    // Search
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(SearchFilter.ALL) }
    var results by remember { mutableStateOf<UiState<List<SearchResult>>?>(null) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var typeahead by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searchSubmitted by remember { mutableStateOf(false) }
    var searchSource by remember { mutableIntStateOf(0) } // 0 YouTube, 1 Library, 2 Addons
    var searchContinuation by remember { mutableStateOf<String?>(null) }
    var searchLoadingMore by remember { mutableStateOf(false) }
    val searchHistory by IosSearchHistory.recent.collectAsState()
    suspend fun search(anonymous: Boolean = true) {
        if (query.isBlank()) return
        searchSubmitted = true
        suggestions = emptyList(); typeahead = emptyList()
        searchContinuation = null
        results = UiState.Loading
        results = YtMusicRepository.searchPage(query, filter, anonymous).fold(
            { searchContinuation = it.continuation; UiState.Success(it.rows) },
            { UiState.Error(it.message ?: "Error") },
        )
    }
    fun onQueryChange(value: String) {
        query = value
        searchSubmitted = false
        if (value.isBlank()) { suggestions = emptyList(); typeahead = emptyList(); results = null }
    }
    fun searchFor(term: String) { query = term; scope.launch { search() } }
    // Typing: completions (led by the raw text) and live media, debounced; cancelled by the next keystroke or a submit.
    LaunchedEffect(query, searchSubmitted, searchSource) {
        if (searchSubmitted || searchSource != 0 || query.isBlank()) return@LaunchedEffect
        delay(200)
        val input = query
        coroutineScope {
            launch {
                YtMusicRepository.searchSuggestions(input).getOrNull()?.let { fetched ->
                    if (!searchSubmitted && query == input) {
                        suggestions = listOf(input) + fetched.filterNot { it.equals(input, ignoreCase = true) }
                    }
                }
            }
            launch {
                YtMusicRepository.searchTypeahead(input).getOrNull()?.let { page ->
                    if (!searchSubmitted && query == input) typeahead = page.rows.take(4)
                }
            }
        }
    }
    fun loadMoreSearch() {
        val token = searchContinuation ?: return
        if (searchLoadingMore) return
        searchLoadingMore = true
        scope.launch {
            val f = filter
            YtMusicRepository.searchContinuation(token, f).onSuccess { page ->
                if (f == filter) {
                    val current = (results as? UiState.Success)?.data.orEmpty()
                    results = UiState.Success(current + page.rows)
                    searchContinuation = page.continuation
                }
            }
            searchLoadingMore = false
        }
    }
    fun recordSong(song: Song) = IosSearchHistory.record(
        SearchHistoryEntity(song.videoId, song.title, song.artist, song.thumbnailUrl, EntityType.TRACK),
    )

    // Lyrics for the playing song, once its duration is known.
    val song = player.song
    var lyrics by remember(song?.videoId) { mutableStateOf<List<LyricLine>?>(null) }
    var lyricsSource by remember(song?.videoId) { mutableStateOf<LyricsSource?>(null) }
    var lyricsChecked by remember(song?.videoId) { mutableStateOf(false) }
    val durationKnown = player.durationMs > 0
    // Per-provider picker state, mirroring MainViewModel: hits are kept so choosing one is instant.
    var providerStates by remember(song?.videoId) { mutableStateOf(LyricsSource.entries.associateWith { LyricsProviderState.NOT_FETCHED }) }
    val providerHits = remember(song?.videoId) { mutableMapOf<LyricsSource, LyricsRepository.Result>() }
    var selectedProvider by remember(song?.videoId) { mutableStateOf<LyricsSource?>(null) }
    fun showLyrics(r: LyricsRepository.Result) { lyrics = r.lines; lyricsSource = r.source; lyricsChecked = true }
    fun fetchProvider(s: LyricsSource?) { // null = the automatic lookup across all enabled providers
        val sg = song ?: return
        val all = s == null
        scope.launch {
            val sources = if (all) AppSettings.lyricsSources.value else setOf(s!!)
            val found = LyricsRepository.lyrics(
                sg.videoId, sg.title, sg.artist, player.durationMs, sg.albumName, sources,
                if (all) AppSettings.lyricsSourceOrder.value else listOf(s!!), all && AppSettings.prioritizeSyllableSync.value,
                onSourceStarted = { providerStates = providerStates + (it to LyricsProviderState.FETCHING) },
                onSourceResult = { p, r ->
                    providerStates = providerStates + (p to if (r == null) LyricsProviderState.NOT_FOUND else LyricsProviderState.FOUND)
                    if (r != null) { providerHits[p] = r; if (selectedProvider == p) showLyrics(r) }
                },
                onSourceCancelled = { p ->
                    if (providerStates[p] == LyricsProviderState.FETCHING) providerStates = providerStates + (p to LyricsProviderState.NOT_FETCHED)
                    // A tap during the priority race: honour it with a dedicated request.
                    if (selectedProvider == p) fetchProvider(p)
                },
            )
            if (all) {
                val chosen = selectedProvider?.let(providerHits::get) ?: found
                lyrics = chosen?.lines
                lyricsSource = chosen?.source
                lyricsChecked = true
            }
        }
    }
    LaunchedEffect(song?.videoId, durationKnown) {
        if (song == null || !durationKnown) return@LaunchedEffect
        fetchProvider(null)
    }
    fun selectProvider(s: LyricsSource) {
        selectedProvider = s
        when (providerStates[s]) {
            LyricsProviderState.FOUND -> providerHits[s]?.let(::showLyrics)
            LyricsProviderState.NOT_FETCHED, null -> fetchProvider(s)
            else -> Unit // FETCHING applies on completion; NOT_FOUND is inert
        }
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
                            val top = stack.lastOrNull()
                            when (if (top == null) tab else -1) {
                            -1 -> {
                                when (top) {
                                    is IosPage.Detail -> key(top) {
                                        var activeShelf by remember { mutableStateOf<HomeShelf?>(null) }
                                        val page = top.page
                                        DetailScreen(
                                            page = page,
                                            currentSong = song,
                                            isPlaying = player.isPlaying,
                                            onSongClick = { songs, i ->
                                                // A Spotify page lists songs not (or never) found on YouTube Music; play the rest in order.
                                                val picked = songs.getOrNull(i)
                                                if (picked?.isUnresolvedSpotify != true) {
                                                    val playable = songs.filterNot { it.isUnresolvedSpotify }
                                                    player.play(playable, playable.indexOf(picked).coerceAtLeast(0))
                                                }
                                            },
                                            onSongLongPress = { menuSong = it },
                                            onSongSwipe = ::swipe,
                                            onShuffle = { songs ->
                                                if (songs.isNotEmpty()) {
                                                    AppSettings.setShuffleEnabled(true)
                                                    player.play(songs, songs.indices.random())
                                                }
                                            },
                                            onSectionItemClick = { item ->
                                                item.browseId?.let { openDetail(it, item.title, item.subtitle, item.thumbnailUrl, BrowseType.ALBUM) }
                                            },
                                            onArtistClick = { id, name -> openDetail(id, name, "Artist", null, BrowseType.ARTIST) },
                                            onAddSuggested = {}, // needs sign-in
                                            contentPadding = noPadding,
                                            activeShelf = activeShelf,
                                            onActiveShelfChange = { activeShelf = it },
                                        )
                                    }
                                    is IosPage.Mood -> MoodGenrePlaylistsScreen(
                                        title = top.item.title,
                                        state = top.shelves,
                                        listState = rememberLazyListState(),
                                        onItemClick = { item ->
                                            item.toSong()?.let { player.play(listOf(it), 0) } // AutoPlay carries on as the radio
                                                ?: openShelfItem(item)
                                        },
                                        onRetry = { stack = stack.dropLast(1); openMood(top.item) },
                                        contentPadding = PaddingValues(top = 56.dp, bottom = barHeight),
                                    )
                                    IosPage.Settings -> SettingsScreen(
                                        windowWidth = this@BoxWithConstraints.maxWidth,
                                        signedIn = signedIn,
                                        account = account,
                                        onSignIn = { showLogin = true },
                                        onSignOut = { scope.launch { IosAuth.signOut() } },
                                        contentPadding = PaddingValues(top = 56.dp, bottom = barHeight),
                                        // Opens the account page; Last.fm / Spotify / Discord live behind it on Android (5e).
                                        onEqualizer = { stack = stack + IosPage.Equalizer },
                                        onAccountScrobbling = { stack = stack + IosPage.Account },
                                        onSpotifyCanvasAuth = { stack = stack + IosPage.Account },
                                        onEditSignatureApi = { editingSignatureApi = true },
                                    )
                                    IosPage.Spotify -> SpotifyLibraryScreen(
                                        onOpenPlaylist = { openDetail(SPOTIFY_PAGE_PREFIX + it.id, it.name, it.owner ?: "Spotify", it.imageUrl, BrowseType.PLAYLIST) },
                                        onOpenLogin = { showSpotifyLogin = true },
                                        onOpenTokenSetup = null,
                                        contentPadding = PaddingValues(top = 56.dp, bottom = barHeight),
                                    )
                                    IosPage.Equalizer -> EqualizerScreen(
                                        contentPadding = PaddingValues(top = 56.dp, bottom = barHeight),
                                    )
                                    IosPage.Account -> IosAccountPage(
                                        signedIn = signedIn,
                                        account = account,
                                        onSignIn = { showLogin = true },
                                        onSignOut = { scope.launch { IosAuth.signOut() } },
                                        onSpotify = { if (spotifyConnected) stack = stack + IosPage.Spotify else showSpotifyLogin = true },
                                        onSpotifyDisconnect = { scope.launch { IosSpotify.signOut() } },
                                        contentPadding = PaddingValues(top = 56.dp, bottom = barHeight),
                                    )
                                    is IosPage.History -> HistoryScreen(
                                        state = top.songs,
                                        listState = rememberLazyListState(),
                                        currentSong = song,
                                        isPlaying = player.isPlaying,
                                        onSongClick = { songs, i -> player.play(songs, i) },
                                        onSongLongPress = { menuSong = it },
                                        onSongSwipe = ::swipe,
                                        onRetry = { stack = stack.dropLast(1); openHistory() },
                                        contentPadding = PaddingValues(top = 56.dp, bottom = barHeight),
                                    )
                                    is IosPage.ShowAll -> LibraryGridPage(
                                        shelf = top.shelf,
                                        gridState = rememberLazyGridState(),
                                        onItemClick = { it.toSong()?.let { s -> player.play(listOf(s), 0) } ?: openShelfItem(it) },
                                        onItemLongPress = { it.toSong()?.let { s -> menuSong = s } },
                                        contentPadding = PaddingValues(top = 56.dp, bottom = barHeight),
                                    )
                                    null -> Unit
                                }
                            }
                            0 -> HomeScreen(
                                state = home,
                                listState = homeList,
                                onItemClick = { item, shelfTitle ->
                                    // The tapped card plus the rest of its shelf's tracks as the queue.
                                    val shelf = (home as? UiState.Success)?.data?.firstOrNull { it.title == shelfTitle }
                                    val tracks = shelf?.items?.songs().orEmpty()
                                    val tapped = item.toSong()
                                    when {
                                        tapped == null -> openShelfItem(item)
                                        else -> player.play(tracks.ifEmpty { listOf(tapped) },
                                            tracks.indexOfFirst { it.videoId == tapped.videoId }.coerceAtLeast(0))
                                    }
                                },
                                onItemLongPress = { it.toSong()?.let { s -> menuSong = s } },
                                onRetry = { scope.launch { home = UiState.Loading; loadHome() } },
                                refreshing = false,
                                onRefresh = { scope.launch { loadHome() } },
                                pullState = homePull,
                                contentPadding = noPadding,
                                title = "Listen now",
                                signedIn = signedIn,
                                onSignIn = { showLogin = true },
                                currentSong = song,
                                isPlaying = player.isPlaying,
                                onLoadMore = ::loadMoreHome,
                                loadingMore = homePaging || homePending > 0,
                                recentlyPlayedLoading = recentsLoading,
                            )
                            1 -> ExploreScreen(
                                state = explore,
                                listState = exploreList,
                                onCategoryClick = ::openMood,
                                onRetry = { scope.launch { explore = UiState.Loading; loadExplore() } },
                                refreshing = false,
                                onRefresh = { scope.launch { loadExplore() } },
                                pullState = explorePull,
                                contentPadding = noPadding,
                            )
                            2 -> LibraryScreen(
                                signedIn = signedIn,
                                state = library,
                                listState = libraryList,
                                onShelfItemClick = {
                                    if (it.browseId == SPOTIFY_BROWSE_ID) stack = stack + IosPage.Spotify
                                    else it.toSong()?.let { s -> player.play(listOf(s), 0) } ?: openShelfItem(it)
                                },
                                onShelfItemLongPress = { it.toSong()?.let { s -> menuSong = s } },
                                onNewPlaylist = {},
                                onShowAll = { stack = stack + IosPage.ShowAll(it) },
                                replay = {},
                                onSignIn = { showLogin = true },
                                onRetry = { scope.launch { library = UiState.Loading; loadLibrary() } },
                                refreshing = libraryRefreshing,
                                onRefresh = { scope.launch { libraryRefreshing = true; loadLibrary(); libraryRefreshing = false } },
                                pullState = libraryPull,
                                contentPadding = noPadding,
                                onImportSpotifyPlaylist = { if (signedIn) showSpotifyImport = true else showLogin = true },
                                links = listOfNotNull(
                                    // Only while signed in to Spotify; it opens that account's playlists rather than a browse page.
                                    LibraryLink(
                                        item = ShelfItem(
                                            title = stringResource(Res.string.spotify),
                                            subtitle = stringResource(Res.string.spotify_library_subtitle),
                                            thumbnailUrl = null,
                                            videoId = null,
                                            browseId = SPOTIFY_BROWSE_ID,
                                        ),
                                        icon = Icons.Rounded.Cloud,
                                        logo = Res.drawable.spotify_logo,
                                    ).takeIf { spotifyConnected },
                                ),
                                deviceItems = emptyList(),
                            )
                            else -> SearchScreen(
                                query = query,
                                onQueryChange = ::onQueryChange,
                                filter = filter,
                                currentSong = song,
                                isPlaying = player.isPlaying,
                                onFilterChange = { filter = it; scope.launch { search() } },
                                results = results,
                                loadingMore = searchLoadingMore,
                                onLoadMore = ::loadMoreSearch,
                                listState = searchList,
                                scrollResetTrigger = 0,
                                focusRequested = false,
                                onFocusHandled = {},
                                onSongClick = { songs, i ->
                                    songs.getOrNull(i)?.let(::recordSong)
                                    player.play(songs, i)
                                },
                                onSongLongPress = { menuSong = it },
                                onSongSwipe = ::swipe,
                                onTopResultPlay = { recordSong(it); player.play(listOf(it), 0) },
                                onTopResultPlaylist = { recordSong(it); menuSong = it },
                                onBrowseClick = {
                                    IosSearchHistory.record(SearchHistoryEntity(
                                        it.browseId, it.title, it.subtitle, it.thumbnailUrl,
                                        when (it.type) {
                                            BrowseType.ALBUM -> EntityType.ALBUM
                                            BrowseType.ARTIST -> EntityType.ARTIST
                                            BrowseType.PLAYLIST -> EntityType.PLAYLIST
                                            else -> EntityType.TRACK
                                        },
                                    ))
                                    openDetail(it.browseId, it.title, it.subtitle, it.thumbnailUrl, it.type)
                                },
                                history = searchHistory,
                                suggestions = suggestions,
                                typeaheadResults = typeahead,
                                onSubmit = { scope.launch { search(anonymous = false) } },
                                onSuggestionClick = ::searchFor,
                                onHistoryClick = { e ->
                                    val s = e.toSong()
                                    when {
                                        s != null -> player.play(listOf(s), 0)
                                        e.id.startsWith("q:") -> searchFor(e.title)
                                        else -> openDetail(e.id, e.title, e.subtitle, e.artworkUrl)
                                    }
                                },
                                onHistoryRemove = IosSearchHistory::remove,
                                onHistoryClear = IosSearchHistory::clear,
                                onTypeaheadLongPress = { menuSong = it },
                                sourceSwitcher = {
                                    SegmentedControl(
                                        options = listOf(
                                            stringResource(Res.string.search_source_youtube),
                                            stringResource(Res.string.library),
                                            stringResource(Res.string.search_source_addons),
                                        ),
                                        selectedIndex = searchSource,
                                        onSelect = {
                                            searchSource = it
                                            suggestions = emptyList(); typeahead = emptyList()
                                            if (it == 0 && query.isNotBlank()) scope.launch { search() }
                                        },
                                    )
                                },
                                // No Local Music folder or addons on iOS yet: both sources answer "nothing", as Android does with none.
                                searchingLibrary = searchSource == 1,
                                libraryResults = if (query.isBlank()) null else UiState.Success(emptyList()),
                                searchingAddons = searchSource == 2,
                                addonResults = if (searchSubmitted) UiState.Success(emptyList()) else null,
                                contentPadding = noPadding,
                                // Below the floating account pill, as Android leaves room for its top bar.
                                topPadding = com.music.laya.ui.components.topBarContentPadding(),
                            )
                        }
                            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = barHeight))
                            if (stack.isEmpty()) {
                                IosTopPill(
                                    account = account,
                                    onHistory = if (tab == 2 && signedIn) ({ openHistory() }) else null,
                                    onAccount = { stack = stack + IosPage.Settings },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                                )
                            } else {
                                IconButton(
                                    onClick = { stack = stack.dropLast(1) },
                                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), CircleShape),
                                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                            }
                        }
                    }
                    GlassNavBar(
                        tabs = tabs,
                        selectedIndex = tab,
                        onTabSelected = { tab = it; stack = emptyList() },
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

                    AnimatedVisibility(
                        visible = !onboardingComplete,
                        enter = EnterTransition.None,
                        exit = slideOutVertically { it },
                    ) {
                        OnboardingScreen(
                            signedIn = signedIn,
                            initialName = "",
                            onNameChosen = {}, // Phase 6: Listen Together nickname
                            onSignIn = { showLogin = true },
                            onFinish = AppSettings::setOnboardingComplete,
                        )
                    }

                    menuSong?.let { m ->
                        IosSongMenu(
                            song = m,
                            onDismiss = { menuSong = null },
                            onPlayNext = { player.playNext(m) },
                            onAddToQueue = { player.addToQueue(m) },
                            onOpenAlbum = { openDetail(it, m.albumName.orEmpty(), m.artist, m.thumbnailUrl, BrowseType.ALBUM) },
                            onOpenArtist = { openDetail(it, m.artist, "Artist", null, BrowseType.ARTIST) },
                            liked = if (signedIn) isLiked(m.videoId) else null,
                            onToggleLike = { toggleLike(m.videoId) },
                        )
                    }

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
                                        repeatMode = repeat,
                                        shuffleEnabled = shuffle,
                                        autoplayEnabled = autoplay,
                                        signedIn = signedIn,
                                        accountName = account?.name,
                                        likeStatus = if (isLiked(song.videoId)) LikeStatus.LIKE else LikeStatus.INDIFFERENT,
                                        onToggleLike = { toggleLike(song.videoId) },
                                        onPlayPause = player::togglePlayPause,
                                        onNext = player::next,
                                        onPrevious = player::previous,
                                        onBlockedControl = {},
                                        onSeek = player::seekTo,
                                        onSeekFraction = player::seekFraction,
                                        onToggleShuffle = { player.setShuffle(!shuffle) },
                                        onCycleRepeat = player::cycleRepeat,
                                        onToggleAutoplay = { player.setAutoplay(!autoplay) },
                                        onJumpTo = player::jumpTo,
                                        onRemoveFromQueue = player::removeAt,
                                        onMoveInQueue = player::move,
                                        onClearQueue = player::clearUserQueue,
                                        onOpenMenu = { menuSong = song },
                                        onOpenAlbum = { id ->
                                            sheetMotion.close()
                                            openDetail(id, song.albumName.orEmpty(), song.artist, song.thumbnailUrl, BrowseType.ALBUM)
                                        },
                                        onOpenArtist = { id, name ->
                                            sheetMotion.close()
                                            (id ?: song.artistId)?.let { openDetail(it, name, "Artist", null, BrowseType.ARTIST) }
                                        },
                                        onOpenPlaybackSource = {},
                                        onListenTogether = {},
                                        lyrics = lyrics,
                                        lyricsSource = lyricsSource,
                                        lyricsProviderStates = providerStates,
                                        onSelectLyricsProvider = ::selectProvider,
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
                    if (showLogin) IosLoginScreen(onClose = { showLogin = false })
                    if (showSpotifyLogin) IosSpotifyLoginScreen(onClose = { showSpotifyLogin = false })
                    if (showSpotifyImport) IosSpotifyImportDialog(
                        onImported = { id, name, count ->
                            IosPlayerHost.showMessage(name)
                            openDetail(id, name, "$count songs", null, BrowseType.PLAYLIST)
                        },
                        onDismiss = { showSpotifyImport = false },
                    )
                    if (editingSignatureApi) IosSignatureApiEditor(onDismiss = { editingSignatureApi = false })
                }
            }
        }
    }
}
