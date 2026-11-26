/*
 * Home's dial pages and mood chips in Home's list: laid out as Melo's Home
 * (github.com/aarush-io/melo, GPL-3.0) lays them out.
 */
package com.music.laya.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.laya.data.home.DialEntry
import com.music.laya.data.home.DialKind
import com.music.laya.data.home.dialId
import com.music.laya.data.home.dialKind
import com.music.laya.data.model.HomeShelf
import com.music.laya.data.model.LibraryPage
import com.music.laya.data.model.MoodGenre
import com.music.laya.data.model.ShelfItem
import com.music.laya.data.model.UiState
import com.music.laya.data.settings.AppSettings
import com.music.laya.sharedui.resources.Res
import com.music.laya.sharedui.resources.artists
import com.music.laya.sharedui.resources.home_chip_all
import com.music.laya.sharedui.resources.home_dial_more
import com.music.laya.sharedui.resources.home_dial_new
import com.music.laya.sharedui.resources.home_dial_pin
import com.music.laya.sharedui.resources.home_dial_remove
import com.music.laya.sharedui.resources.home_dial_unpin
import com.music.laya.sharedui.resources.home_for_you
import com.music.laya.sharedui.resources.home_history_tile
import com.music.laya.sharedui.resources.home_mixes_dial
import com.music.laya.sharedui.resources.home_speed_dial
import org.jetbrains.compose.resources.stringResource

/** What a platform hands Home for its dials and chips; Home draws none without it. */
data class HomeDialInputs(
    val chips: List<MoodGenre>,
    val selectedChip: MoodGenre?,
    val onSelectChip: (MoodGenre?) -> Unit,
    /** The picked chip's feed. */
    val chipShelves: UiState<List<HomeShelf>>,
    /** The Mixes tab's cards (Mixed for you, or the mood stations signed out). */
    val mixes: List<ShelfItem>,
    val library: LibraryPage?,
    val onHistory: (() -> Unit)?,
)

/** The chips, then the Speed Dial (with Mixes beside it) and the Artists page, as Home's first items. */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.homeDialItems(
    inputs: HomeDialInputs,
    dials: HomeDials?,
    loading: Boolean,
    onOpen: (ShelfItem) -> Unit,
    onMore: ((ShelfItem) -> Unit)?,
    /** Each dial fills one screen: its grid shrinks to this (never below 80%). */
    maxGridHeight: Dp?,
    onScrollToFeed: () -> Unit,
    withArtists: Boolean,
) {
    // The chips scroll away with the list, as Melo's do; the title above them stays.
    if (inputs.chips.isNotEmpty()) {
        item(key = HOME_CHIPS_KEY, contentType = HOME_CHIPS_KEY) {
            val all = stringResource(Res.string.home_chip_all)
            HomeCategoryChips(
                chips = listOf(all) + inputs.chips.map { it.title },
                selected = inputs.selectedChip?.let { inputs.chips.indexOf(it) + 1 } ?: 0,
                onSelect = { inputs.onSelectChip(if (it == 0) null else inputs.chips[it - 1]) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
    }
    if (dials == null) return
    item(key = SPEED_DIAL_KEY, contentType = SPEED_DIAL_KEY) {
        DialPages(inputs, dials, loading, onOpen, onMore, maxGridHeight, onScrollToFeed)
    }
    if (showsArtistPage(inputs, dials, withArtists)) {
        item(key = ARTIST_DIAL_KEY, contentType = ARTIST_DIAL_KEY) {
            ArtistPage(dials.artists, onOpen, onMore, maxGridHeight, onScrollToFeed)
        }
    }
}

@Composable
private fun DialPages(
    inputs: HomeDialInputs,
    dials: HomeDials,
    loading: Boolean,
    onOpen: (ShelfItem) -> Unit,
    onMore: ((ShelfItem) -> Unit)?,
    maxGridHeight: Dp?,
    onScrollToFeed: () -> Unit,
) {
    val rows by AppSettings.homeDialRows.collectAsStateWithLifecycle()
    val labels by AppSettings.homeDialLabels.collectAsStateWithLifecycle()
    val pinIcon by AppSettings.homeDialPinIcon.collectAsStateWithLifecycle()
    val newTags by AppSettings.homeDialNewReleaseTags.collectAsStateWithLifecycle()
    val style by AppSettings.homeDialTileStyle.collectAsStateWithLifecycle()
    val lucky by AppSettings.homeLuckyTile.collectAsStateWithLifecycle()
    val withMixes by AppSettings.homeMixesDial.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf<DialEntry?>(null) }

    if (dials.speedDial.isEmpty() && loading) {
        SpeedDialSkeleton(rows, maxGridHeight = maxGridHeight)
    } else if (dials.speedDial.isNotEmpty()) {
        val showMixes = withMixes && inputs.selectedChip == null && dials.mixes.isNotEmpty()
        HomeDialPager(
            speedDialTitle = inputs.selectedChip?.title ?: stringResource(Res.string.home_speed_dial),
            mixesTitle = if (showMixes) stringResource(Res.string.home_mixes_dial) else null,
            speedDial = {
                SpeedDialGrid(
                    entries = dials.speedDial,
                    rows = rows,
                    labels = labels,
                    pinIcon = pinIcon,
                    newTags = newTags,
                    tileStyle = style,
                    luckyTile = lucky && inputs.selectedChip == null && dials.luckyPool.isNotEmpty(),
                    historyTile = inputs.onHistory != null && inputs.selectedChip == null,
                    onOpen = onOpen,
                    onLongPress = { menu = it },
                    onLucky = { dials.luckyPool.randomOrNull() },
                    onHistory = { inputs.onHistory?.invoke() },
                    historyLabel = stringResource(Res.string.home_history_tile),
                    newLabel = stringResource(Res.string.home_dial_new),
                    maxGridHeight = maxGridHeight,
                    onScrollToFeed = onScrollToFeed,
                    moreLabel = stringResource(Res.string.home_for_you),
                )
            },
            mixes = if (showMixes) {
                {
                    MixesDialGrid(
                        dials.mixes, rows, labels, style, onOpen, { menu = it },
                        maxGridHeight = maxGridHeight, onScrollToFeed = onScrollToFeed,
                        moreLabel = stringResource(Res.string.home_for_you),
                    )
                }
            } else null,
        )
    }
    menu?.let { entry -> DialEntryMenu(entry, onMore, onDismiss = { menu = null }) }
}

@Composable
private fun ArtistPage(
    artists: List<DialEntry>,
    onOpen: (ShelfItem) -> Unit,
    onMore: ((ShelfItem) -> Unit)?,
    maxGridHeight: Dp?,
    onScrollToFeed: () -> Unit,
) {
    val rows by AppSettings.homeDialRows.collectAsStateWithLifecycle()
    val labels by AppSettings.homeDialLabels.collectAsStateWithLifecycle()
    val style by AppSettings.homeDialTileStyle.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf<DialEntry?>(null) }
    HomeDialPager(
        speedDialTitle = stringResource(Res.string.artists),
        mixesTitle = null,
        speedDial = {
            ArtistDialGrid(
                artists, rows, labels, style, onOpen, { menu = it },
                maxGridHeight = maxGridHeight, onScrollToFeed = onScrollToFeed,
                moreLabel = stringResource(Res.string.home_for_you),
            )
        },
        mixes = null,
    )
    menu?.let { entry -> DialEntryMenu(entry, onMore, onDismiss = { menu = null }) }
}

/** A tile's long press: take it off the dial, pin or unpin a playlist, or the item's own menu. */
@Composable
private fun DialEntryMenu(entry: DialEntry, onMore: ((ShelfItem) -> Unit)?, onDismiss: () -> Unit) {
    val item = entry.item
    val pinnable = item.dialKind == DialKind.PLAYLIST && item.browseId != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title, maxLines = 2) },
        text = {
            androidx.compose.foundation.layout.Column {
                TextButton(onClick = { AppSettings.setHiddenFromDial(item.dialId, true); onDismiss() }) {
                    Text(stringResource(Res.string.home_dial_remove))
                }
                if (pinnable) {
                    TextButton(onClick = {
                        // Pinning brings back a tile taken off the dial.
                        if (AppSettings.togglePinnedPlaylist(item.browseId!!)) AppSettings.setHiddenFromDial(item.dialId, false)
                        onDismiss()
                    }) {
                        Text(stringResource(if (entry.pinned) Res.string.home_dial_unpin else Res.string.home_dial_pin))
                    }
                }
                if (onMore != null) {
                    TextButton(onClick = { onDismiss(); onMore(item) }) { Text(stringResource(Res.string.home_dial_more)) }
                }
            }
        },
        confirmButton = {},
    )
}

const val HOME_CHIPS_KEY = "home_chips"
const val SPEED_DIAL_KEY = "speed_dial"
const val ARTIST_DIAL_KEY = "artist_dial"

/** Whether the Artists page follows the Speed Dial: on, no mood picked, and some artists to show. */
fun showsArtistPage(inputs: HomeDialInputs, dials: HomeDials, withArtists: Boolean): Boolean =
    withArtists && inputs.selectedChip == null && dials.artists.isNotEmpty()
