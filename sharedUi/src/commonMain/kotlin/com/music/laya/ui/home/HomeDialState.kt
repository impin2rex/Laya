/*
 * Home's dials, as dealt: ported from Melo (github.com/aarush-io/melo, GPL-3.0) HomeScreen's
 * dial state, reworked for Laya's feed.
 */
package com.music.laya.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.laya.data.YtMusicRepository
import com.music.laya.data.home.ArtistDial
import com.music.laya.data.home.DealtDial
import com.music.laya.data.home.DialEntry
import com.music.laya.data.home.DialKind
import com.music.laya.data.home.HomeDialRounds
import com.music.laya.data.home.SpeedDial
import com.music.laya.data.home.dialId
import com.music.laya.data.home.dialKind
import com.music.laya.data.home.historyCovers
import com.music.laya.data.home.toShelfItem
import com.music.laya.data.model.HomeShelf
import com.music.laya.data.model.LibraryPage
import com.music.laya.data.model.ShelfItem
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.stats.ListeningStats
import com.music.laya.data.stats.ReplayPeriod

/** What Home's dial pages show this round. */
data class HomeDials(
    val speedDial: List<DialEntry>,
    val artists: List<DialEntry>,
    /** The cards the dice picks from: tracks the listener played or YouTube picked for them. */
    val luckyPool: List<ShelfItem>,
    /** Covers for the History tile: what was played lately, then the most played, newest first. */
    val historyCovers: List<String>,
)

/**
 * Deals Home's dials from what is loaded: the Speed Dial from the feed (or, with a mood chip
 * picked, from that mood's feed) and the Artists page from the device's most played and the
 * library. The Speed Dial is dealt once a round (launch, or [HomeDialRounds.nextRound] on pull to
 * refresh) once [settled], so a song starting never moves a tile; until then the last launch's
 * deal is shown.
 */
@Composable
fun rememberHomeDials(
    shelves: List<HomeShelf>,
    /** The picked mood's shelves, or null for all of Home. */
    chipShelves: List<HomeShelf>?,
    library: LibraryPage?,
    /** The feed has arrived and nothing is still refreshing it. */
    settled: Boolean,
): HomeDials {
    val source by AppSettings.homeDialSource.collectAsStateWithLifecycle()
    val withArtists by AppSettings.homeArtistDial.collectAsStateWithLifecycle()
    val hidden by AppSettings.hiddenFromDial.collectAsStateWithLifecycle()
    val pinnedIds by AppSettings.pinnedPlaylists.collectAsStateWithLifecycle()
    val artistOrder by AppSettings.homeArtistDialOrder.collectAsStateWithLifecycle()
    val rows by AppSettings.homeDialRows.collectAsStateWithLifecycle()
    val round by HomeDialRounds.round.collectAsStateWithLifecycle()
    val dealtSpeed by HomeDialRounds.speedDial.collectAsStateWithLifecycle()

    // The device's own listening, read once a round.
    val stats by produceState<com.music.laya.data.stats.ReplaySummary?>(null, round) {
        value = runCatching { ListeningStats.summary(ReplayPeriod.ALL_TIME) }.getOrNull()
    }
    val recents = remember(shelves) {
        shelves.firstOrNull { it.title == YtMusicRepository.RECENT_TITLE }?.items.orEmpty()
    }
    val mostPlayed = remember(stats) { stats?.songs.orEmpty().map { it.song.toShelfItem() } }
    val libraryItems = remember(library) { library?.shelves.orEmpty().flatMap { it.items } }
    val pins = remember(pinnedIds, libraryItems) {
        pinnedIds.mapNotNull { id -> libraryItems.firstOrNull { it.browseId == id } }
    }
    val key = "$source|$withArtists"
    val live = remember(source, shelves, recents, mostPlayed, hidden, withArtists) {
        SpeedDial.pool(source, shelves, recents, mostPlayed, hidden, withArtists)
    }
    val saved = remember(key) { HomeDialRounds.saved(HomeDialRounds.SPEED_DIAL, key) }

    LaunchedEffect(round, key, settled, live) {
        val dealt = HomeDialRounds.speedDial.value
        if (!settled || live.isEmpty() || (dealt != null && dealt.round == round && dealt.key == key)) return@LaunchedEffect
        // At launch the last launch's tiles stay put while they are still to be had; a refresh deals anew.
        val kept = saved?.takeIf { dealt == null && round == 0 }
        val items = if (kept == null) live else {
            val available = live.mapTo(HashSet()) { it.dialId }
            (kept.filter { it.dialId in available } + live).distinctBy { it.dialId }.take(SpeedDial.MAX_ITEMS)
        }
        HomeDialRounds.speedDial.value = DealtDial(round, key, items)
        HomeDialRounds.save(HomeDialRounds.SPEED_DIAL, key, items)
    }

    val newReleases = remember(shelves) { SpeedDial.newReleaseIds(shelves) }
    val pageSize = rows * SpeedDial.COLUMNS
    val speedDial = remember(chipShelves, pins, dealtSpeed, saved, live, hidden, newReleases, pageSize, key) {
        if (chipShelves != null) {
            SpeedDial.category(chipShelves, hidden).map { DialEntry(it) }
        } else {
            val round = dealtSpeed?.takeIf { it.key == key }?.items ?: saved ?: live
            val dialPins = (if (withArtists) pins.filterNot { it.dialKind == DialKind.ARTIST } else pins)
            SpeedDial.entries(dialPins, round.filterNot { it.dialId in hidden }, live, pageSize, newReleases)
        }
    }
    val artists = remember(stats, libraryItems, shelves, hidden, artistOrder) {
        ArtistDial.tiles(stats?.artists.orEmpty(), libraryItems, shelves.flatMap { it.items }, hidden, artistOrder)
            .map { DialEntry(it) }
    }
    val lucky = remember(recents, mostPlayed, shelves, pins) {
        (recents + mostPlayed + shelves.filter { SpeedDial.shelfRank(it.title) <= 3 }.flatMap { it.items } + pins)
            .filter { it.dialKind == DialKind.TRACK || it.dialKind == DialKind.PLAYLIST }
            .distinctBy { it.dialId }
    }
    val historyCovers = remember(recents, mostPlayed) { historyCovers(recents + mostPlayed) }
    return HomeDials(speedDial, artists, lucky, historyCovers)
}
