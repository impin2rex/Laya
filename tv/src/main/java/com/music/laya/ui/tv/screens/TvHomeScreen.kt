package com.music.laya.ui.tv.screens

import com.music.laya.ui.tv.components.TvTabPill
import com.music.laya.data.model.MoodGenre
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.stringResource
import com.music.laya.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.music.laya.data.model.BrowseType
import com.music.laya.data.model.HomeShelf
import com.music.laya.data.model.ShelfItem
import com.music.laya.data.model.Song
import com.music.laya.data.model.UiState
import com.music.laya.playback.playSongs
import com.music.laya.ui.MainViewModel
import com.music.laya.ui.tv.components.TvActivityIndicator
import com.music.laya.ui.tv.components.TvCard
import com.music.laya.ui.tv.components.TvChromeScrollEffect
import com.music.laya.ui.tv.components.TvEmptyState
import com.music.laya.ui.tv.components.TvErrorState
import com.music.laya.ui.tv.components.ProvideTvFeedScrolling
import com.music.laya.ui.tv.components.TvLockup
import com.music.laya.ui.tv.components.TvShelf
import com.music.laya.ui.tv.components.TvShelfPlaceholder
import com.music.laya.ui.tv.dialogs.TvSongMenu
import com.music.laya.data.home.DialEntry
import com.music.laya.data.home.DialKind
import com.music.laya.data.home.dialId
import com.music.laya.data.home.dialKind
import com.music.laya.data.settings.AppSettings
import com.music.laya.ui.home.HomeDials
import com.music.laya.ui.home.rememberHomeDials
import com.music.laya.ui.tv.components.TvDialog
import com.music.laya.ui.tv.components.TvDialogList
import com.music.laya.ui.tv.components.TvListRow
import com.music.laya.ui.tv.theme.TvGlass
import com.music.laya.ui.tv.theme.TvType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import com.music.laya.ui.tv.theme.TvDimensions
import kotlinx.coroutines.launch

@Composable
fun TvHomeScreen(
    viewModel: MainViewModel,
    mediaController: MediaController?,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    onNavigateToNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val homeState by viewModel.home.collectAsState()
    val loadingMore by viewModel.homeLoadingMore.collectAsState()
    val scope = rememberCoroutineScope()
    var menuSong by remember { mutableStateOf<Song?>(null) }
    val chips by viewModel.homeChips.collectAsState()
    val chip by viewModel.homeChip.collectAsState()
    val chipShelves by viewModel.homeChipShelves.collectAsState()
    BackHandler(enabled = chip != null) { viewModel.selectHomeChip(null) }
    val playSong: (Song) -> Unit = { song ->
        scope.launch { mediaController?.playSongs(listOf(song), 0) }
        onNavigateToNowPlaying()
    }

    // The phone's Home dials (ported from Melo), dealt by the same shared state, as shelves.
    val speedDialOn by AppSettings.homeSpeedDial.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val recentsLoading by viewModel.homeRecentlyPlayedLoading.collectAsState()
    val library by viewModel.library.collectAsState()
    val dials = if (speedDialOn) {
        rememberHomeDials(
            shelves = (homeState as? UiState.Success)?.data.orEmpty(),
            chipShelves = if (chip != null) (chipShelves as? UiState.Success)?.data.orEmpty() else null,
            library = (library as? UiState.Success)?.data,
            settled = homeState is UiState.Success && MainViewModel.Feed.HOME !in refreshing && !recentsLoading,
        )
    } else null
    val openItem: (ShelfItem) -> Unit = { openShelfItem(it, playSong, onNavigateToDetail) }

    // Home's mood chips over the feed, as the phone has them, then the dials; back returns to all of Home.
    val chipRow: (@Composable () -> Unit)? = if (chips.isEmpty() && dials == null) null else {
        {
            Column(verticalArrangement = Arrangement.spacedBy(TvDimensions.ShelfSpacing - 11.dp)) {
                if (chips.isNotEmpty()) TvHomeChips(chips, chip, viewModel::selectHomeChip)
                if (dials != null) TvHomeDials(dials, chip?.title, openItem, onSongMenu = { menuSong = it })
            }
        }
    }

    if (chip != null) {
        val picked = chipShelves
        TvShelfFeed(
            shelves = (picked as? UiState.Success)?.data.orEmpty(),
            loadingMore = picked is UiState.Loading,
            onPlaySong = playSong,
            onSongMenu = { menuSong = it },
            onNavigateToDetail = onNavigateToDetail,
            header = chipRow,
            modifier = modifier,
        )
    } else when (val state = homeState) {
        is UiState.Loading -> TvShelvesLoading(modifier)
        is UiState.Error -> TvErrorState(
            message = state.message,
            onRetry = { viewModel.refresh(MainViewModel.Feed.HOME) },
            modifier = modifier,
        )
        is UiState.Success -> {
            if (state.data.isEmpty()) {
                TvEmptyState(
                    title = stringResource(R.string.tv_nothing_here_title),
                    message = stringResource(R.string.tv_nothing_here_signin_message),
                    modifier = modifier,
                )
            } else {
                TvShelfFeed(
                    shelves = state.data,
                    loadingMore = loadingMore,
                    onNearEnd = { viewModel.loadMoreHome() },
                    onPlaySong = playSong,
                    header = chipRow,
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
            onNavigateToDetail = onNavigateToDetail,
            onDismiss = { menuSong = null },
        )
    }
}

/** Home's mood chips, one focusable pill each; picking the one already picked returns to all of Home. */
@Composable
private fun TvHomeChips(chips: List<MoodGenre>, selected: MoodGenre?, onSelect: (MoodGenre?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = TvDimensions.SafeMarginHorizontal),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        items(chips, key = { it.browseId + it.params }) { chip ->
            val isSelected = chip == selected
            TvTabPill(
                label = chip.title,
                selected = isSelected,
                onClick = { onSelect(if (isSelected) null else chip) },
            )
        }
    }
}

/**
 * Home's dials as remote-friendly shelves: the Speed Dial (the picked mood's title while one is
 * picked), then Artists. Long press a tile to take it off the dial or pin a playlist.
 */
@Composable
private fun TvHomeDials(
    dials: HomeDials,
    chipTitle: String?,
    onOpen: (ShelfItem) -> Unit,
    onSongMenu: (Song) -> Unit,
) {
    val labels by AppSettings.homeDialLabels.collectAsState()
    val pinIcon by AppSettings.homeDialPinIcon.collectAsState()
    val newTags by AppSettings.homeDialNewReleaseTags.collectAsState()
    val lucky by AppSettings.homeLuckyTile.collectAsState()
    val withArtists by AppSettings.homeArtistDial.collectAsState()
    var menu by remember { mutableStateOf<DialEntry?>(null) }
    // null stands for the dice: a random pick from what was played and what YouTube picked.
    val speedTiles: List<DialEntry?> =
        (if (lucky && chipTitle == null && dials.luckyPool.isNotEmpty()) listOf(null) else emptyList()) + dials.speedDial

    Column(verticalArrangement = Arrangement.spacedBy(TvDimensions.ShelfSpacing - 11.dp)) {
        if (dials.speedDial.isNotEmpty()) {
            TvShelf(
                title = chipTitle ?: stringResource(R.string.tv_home_speed_dial),
                items = speedTiles,
                key = { it?.item?.dialId ?: "lucky" },
            ) { entry ->
                if (entry == null) {
                    TvLockup(
                        title = "",
                        subtitle = null,
                        artworkUrl = null,
                        width = 150.dp,
                        onClick = { dials.luckyPool.randomOrNull()?.let(onOpen) },
                        overlay = {
                            Box(Modifier.fillMaxSize().background(TvGlass.Placeholder), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Casino, null, tint = TvGlass.TextPrimary, modifier = Modifier.size(48.dp))
                            }
                        },
                    )
                } else {
                    TvDialTile(entry, labels, pinIcon, newTags, onOpen) { menu = entry }
                }
            }
        }
        if (withArtists && chipTitle == null && dials.artists.isNotEmpty()) {
            TvShelf(
                title = stringResource(R.string.artists),
                items = dials.artists,
                key = { it.item.dialId },
            ) { entry ->
                TvLockup(
                    title = if (labels) entry.item.title else "",
                    subtitle = null,
                    artworkUrl = entry.item.thumbnailUrl,
                    circle = true,
                    width = 120.dp,
                    onClick = { onOpen(entry.item) },
                    onLongClick = { menu = entry },
                )
            }
        }
    }

    menu?.let { entry ->
        val item = entry.item
        val dismiss = { menu = null }
        TvDialog(onDismissRequest = dismiss, title = item.title) {
            TvDialogList {
                TvListRow(
                    title = stringResource(R.string.tv_home_dial_remove),
                    onClick = { AppSettings.setHiddenFromDial(item.dialId, true); dismiss() },
                )
                val browseId = item.browseId
                if (item.dialKind == DialKind.PLAYLIST && browseId != null) {
                    TvListRow(
                        title = stringResource(if (entry.pinned) R.string.tv_home_dial_unpin else R.string.tv_home_dial_pin),
                        onClick = {
                            // Pinning brings back a tile taken off the dial.
                            if (AppSettings.togglePinnedPlaylist(browseId)) AppSettings.setHiddenFromDial(item.dialId, false)
                            dismiss()
                        },
                    )
                }
                item.toSong()?.let { song ->
                    TvListRow(title = stringResource(R.string.tv_home_dial_more), onClick = { dismiss(); onSongMenu(song) })
                }
            }
        }
    }
}

/** A Speed Dial or Mixes tile: the artwork, its pin badge and "New" tag as set, the title if shown. */
@Composable
private fun TvDialTile(
    entry: DialEntry,
    labels: Boolean,
    pinIcon: Boolean,
    newTags: Boolean,
    onOpen: (ShelfItem) -> Unit,
    onLongClick: () -> Unit,
) {
    TvLockup(
        title = if (labels) entry.item.title else "",
        subtitle = null,
        artworkUrl = entry.item.thumbnailUrl,
        width = 150.dp,
        onClick = { onOpen(entry.item) },
        onLongClick = onLongClick,
        overlay = {
            if (entry.pinned && pinIcon) {
                Icon(
                    Icons.Rounded.PushPin, null, tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(4.dp),
                )
            }
            if (entry.isNewRelease && newTags) {
                Text(
                    stringResource(R.string.tv_home_dial_new),
                    style = TvType.Caption,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        },
    )
}

/** Placeholder shelves while a feed loads. */
@Composable
internal fun TvShelvesLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = TvDimensions.ContentTop + 6.dp),
        verticalArrangement = Arrangement.spacedBy(TvDimensions.ShelfSpacing),
    ) {
        TvShelfPlaceholder(width = 160.dp, count = 5)
        TvShelfPlaceholder()
    }
}

/**
 * A vertical run of shelves under the tab bar — Home, an Explore category, an
 * artist's releases. With [picksFirst] the first shelf gets the large Top Picks
 * cards; every other shelf is lockups.
 */
@Composable
internal fun TvShelfFeed(
    shelves: List<HomeShelf>,
    onPlaySong: (Song) -> Unit,
    onSongMenu: (Song) -> Unit,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    modifier: Modifier = Modifier,
    picksFirst: Boolean = false,
    loadingMore: Boolean = false,
    onNearEnd: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    TvChromeScrollEffect(listState)

    if (onNearEnd != null) {
        val nearEnd by remember(listState) {
            derivedStateOf {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= listState.layoutInfo.totalItemsCount - 3
            }
        }
        LaunchedEffect(nearEnd, shelves.size) { if (nearEnd) onNearEnd() }
    }

    // The first shelf's cards rest just under its title: ContentTop, the title's
    // line, then the row's own top padding. Every focused shelf stops there.
    ProvideTvFeedScrolling(anchor = TvDimensions.ContentTop + 19.dp + 11.dp) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = TvDimensions.ContentTop, bottom = 45.dp),
        verticalArrangement = Arrangement.spacedBy(TvDimensions.ShelfSpacing - 11.dp),
    ) {
        if (header != null) {
            item(key = "header", contentType = "header") { header() }
        }
        itemsIndexed(
            items = shelves,
            key = { index, shelf -> "${shelf.title}#$index" },
            contentType = { index, shelf ->
                when {
                    picksFirst && index == 0 -> "picks"
                    shelf.isArtistShelf() -> "artists"
                    else -> "lockups"
                }
            },
        ) { index, shelf ->
            val onItem: (ShelfItem) -> Unit = { item ->
                openShelfItem(item, onPlaySong, onNavigateToDetail)
            }
            val onItemMenu: (ShelfItem) -> (() -> Unit)? = { item ->
                item.toSong()?.let { song -> { onSongMenu(song) } }
            }
            when {
                picksFirst && index == 0 -> TvShelf(
                    title = shelf.title,
                    subtitle = shelf.subtitle.takeIf { it.isNotBlank() },
                    items = shelf.items,
                    key = { it.itemKey() },
                ) { item ->
                    TvCard(
                        title = item.title,
                        subtitle = item.subtitle,
                        artworkUrl = item.thumbnailUrl,
                        cardWidth = 163.dp,
                        onClick = { onItem(item) },
                        onLongClick = onItemMenu(item),
                    )
                }
                shelf.isArtistShelf() -> TvShelf(
                    title = shelf.title,
                    subtitle = shelf.subtitle.takeIf { it.isNotBlank() },
                    items = shelf.items,
                    key = { it.itemKey() },
                ) { item ->
                    TvLockup(
                        title = item.title,
                        subtitle = null,
                        artworkUrl = item.thumbnailUrl,
                        circle = true,
                        width = 120.dp,
                        onClick = { onItem(item) },
                    )
                }
                else -> TvShelf(
                    title = shelf.title,
                    subtitle = shelf.subtitle.takeIf { it.isNotBlank() },
                    items = shelf.items,
                    key = { it.itemKey() },
                ) { item ->
                    TvLockup(
                        title = item.title,
                        subtitle = item.subtitle,
                        artworkUrl = item.thumbnailUrl,
                        width = 131.dp,
                        onClick = { onItem(item) },
                        onLongClick = onItemMenu(item),
                    )
                }
            }
        }
        if (loadingMore) {
            item(key = "more", contentType = "more") {
                Box(modifier = Modifier.fillMaxWidth().padding(19.dp), contentAlignment = Alignment.Center) {
                    TvActivityIndicator()
                }
            }
        }
    }
    }
}

private fun ShelfItem.itemKey(): String = videoId ?: browseId ?: title

private fun HomeShelf.isArtistShelf(): Boolean =
    items.isNotEmpty() && items.count { it.browseId?.startsWith("UC") == true } * 2 > items.size

internal fun ShelfItem.toSong(): Song? = videoId?.let {
    Song(videoId = it, title = title, artist = subtitle, thumbnailUrl = thumbnailUrl)
}

internal fun browseTypeOf(browseId: String): BrowseType = when {
    browseId.startsWith("UC") || browseId.startsWith("FEmusic_artist") -> BrowseType.ARTIST
    browseId.startsWith("MPRE") || browseId.startsWith("FEmusic_album") -> BrowseType.ALBUM
    browseId.startsWith("VL") || browseId.startsWith("PL") || browseId.startsWith("RD") || browseId == "LM" -> BrowseType.PLAYLIST
    else -> BrowseType.OTHER
}

internal fun openShelfItem(
    item: ShelfItem,
    onPlaySong: (Song) -> Unit,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
) {
    val song = item.toSong()
    val browseId = item.browseId
    when {
        song != null -> onPlaySong(song)
        browseId != null -> onNavigateToDetail(browseId, item.title, item.subtitle, item.thumbnailUrl, browseTypeOf(browseId))
    }
}
