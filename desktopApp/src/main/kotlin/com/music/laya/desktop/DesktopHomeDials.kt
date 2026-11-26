/*
 * Home's dials (Speed Dial, Mixes, Artists) for the desktop: the phone's port of Melo
 * (github.com/aarush-io/melo, GPL-3.0), laid out for a wide window and a mouse. The dealing is
 * the shared data/home code; this is only how it is dealt into the window and drawn.
 */
package com.music.laya.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.laya.data.YtMusicRepository
import com.music.laya.data.home.ArtistDial
import com.music.laya.data.home.DealtDial
import com.music.laya.data.home.DialEntry
import com.music.laya.data.home.DialKind
import com.music.laya.data.home.HomeDialRounds
import com.music.laya.data.home.MixesDial
import com.music.laya.data.home.SpeedDial
import com.music.laya.data.home.dialId
import com.music.laya.data.home.dialKind
import com.music.laya.data.home.toShelfItem
import com.music.laya.data.model.CARD_ART_PX
import com.music.laya.data.model.HomeShelf
import com.music.laya.data.model.LibraryPage
import com.music.laya.data.model.ShelfItem
import com.music.laya.data.model.artworkAt
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.settings.HomeDialTileStyle
import com.music.laya.data.stats.RankedEntry
import com.music.laya.ui.components.PAGE_GUTTER
import com.music.laya.ui.components.ShimmerBox
import com.music.laya.ui.components.contextClick
import java.time.LocalTime

/** What Home's dial pages show this round. */
private class DesktopHomeDials(
    val speedDial: List<DialEntry>,
    val mixes: List<DialEntry>,
    val artists: List<DialEntry>,
    val luckyPool: List<ShelfItem>,
)

/**
 * As the phone's `rememberHomeDials`, without the mood chips (Explore has the moods here) and
 * with the desktop's own listening stats: each dial is dealt once a round, so a song starting
 * never moves a tile, and the last launch's deal shows until the feed settles.
 */
@Composable
private fun rememberDesktopHomeDials(
    shelves: List<HomeShelf>,
    mixes: List<ShelfItem>,
    library: LibraryPage?,
    settled: Boolean,
): DesktopHomeDials {
    val source by AppSettings.homeDialSource.collectAsState()
    val withArtists by AppSettings.homeArtistDial.collectAsState()
    val hidden by AppSettings.hiddenFromDial.collectAsState()
    val pinnedIds by AppSettings.pinnedPlaylists.collectAsState()
    val artistOrder by AppSettings.homeArtistDialOrder.collectAsState()
    val rows by AppSettings.homeDialRows.collectAsState()
    val round by HomeDialRounds.round.collectAsState()
    val dealtSpeed by HomeDialRounds.speedDial.collectAsState()
    val dealtMixes by HomeDialRounds.mixes.collectAsState()

    val stats = remember(round) { runCatching { DesktopListeningStats.summary() }.getOrNull() }
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
    var savedMixes by remember { mutableStateOf(HomeDialRounds.saved(HomeDialRounds.MIXES, "")) }

    LaunchedEffect(round, key, settled, live) {
        val dealt = HomeDialRounds.speedDial.value
        if (!settled || live.isEmpty() || (dealt != null && dealt.round == round && dealt.key == key)) return@LaunchedEffect
        // At launch the last launch's tiles stay put while they are still to be had; a reshuffle deals anew.
        val kept = saved?.takeIf { dealt == null && round == 0 }
        val items = if (kept == null) live else {
            val available = live.mapTo(HashSet()) { it.dialId }
            (kept.filter { it.dialId in available } + live).distinctBy { it.dialId }.take(SpeedDial.MAX_ITEMS)
        }
        HomeDialRounds.speedDial.value = DealtDial(round, key, items)
        HomeDialRounds.save(HomeDialRounds.SPEED_DIAL, key, items)
    }
    LaunchedEffect(round, settled, mixes, shelves) {
        if (!settled || HomeDialRounds.mixes.value?.round == round) return@LaunchedEffect
        val now = LocalTime.now()
        val items = MixesDial.deal(shelves, mixes, emptyList(), hidden, MixesDial.isSleepTime(now.hour * 60 + now.minute))
        if (items.isEmpty()) return@LaunchedEffect
        HomeDialRounds.mixes.value = DealtDial(round, "", items)
        HomeDialRounds.save(HomeDialRounds.MIXES, "", items)
        savedMixes = null
    }

    val newReleases = remember(shelves) { SpeedDial.newReleaseIds(shelves) }
    val pageSize = rows * SpeedDial.COLUMNS
    val speedDial = remember(pins, dealtSpeed, saved, live, hidden, newReleases, pageSize, key) {
        val dealt = dealtSpeed?.takeIf { it.key == key }?.items ?: saved ?: live
        val dialPins = if (withArtists) pins.filterNot { it.dialKind == DialKind.ARTIST } else pins
        SpeedDial.entries(dialPins, dealt.filterNot { it.dialId in hidden }, live, pageSize, newReleases)
    }
    val mixEntries = remember(dealtMixes, savedMixes, hidden) {
        (dealtMixes?.items ?: savedMixes.orEmpty()).filterNot { it.dialId in hidden }.map { DialEntry(it) }
    }
    val artists = remember(stats, libraryItems, shelves, hidden, artistOrder) {
        val played = stats?.artists.orEmpty().map { RankedEntry(it.title, it.subtitle, it.artworkUrl, it.browseId, it.ms, it.plays) }
        ArtistDial.tiles(played, libraryItems, shelves.flatMap { it.items }, hidden, artistOrder).map { DialEntry(it) }
    }
    val lucky = remember(recents, mostPlayed, shelves, pins) {
        (recents + mostPlayed + shelves.filter { SpeedDial.shelfRank(it.title) <= 3 }.flatMap { it.items } + pins)
            .filter { it.dialKind == DialKind.TRACK || it.dialKind == DialKind.PLAYLIST }
            .distinctBy { it.dialId }
    }
    return DesktopHomeDials(speedDial, mixEntries, artists, lucky)
}

private enum class DialPage(val key: String, val fallback: String) {
    SPEED("home_speed_dial", "Speed Dial"),
    MIXES("home_mixes_dial", "Mixes"),
    ARTISTS("artists", "Artists"),
}

/** A dial slot: an entry, the dice, or History. */
private sealed interface DialSlot {
    data class Tile(val entry: DialEntry) : DialSlot
    data object Lucky : DialSlot
    data object History : DialSlot
}

/**
 * Home's dials on a desktop: the pages as tabs over one grid as wide as the window, as many
 * columns as fit ([DIAL_TILE] each) by the Rows setting, paged with arrows rather than swiped.
 * Right-click a tile for Remove / Pin / More options, as the phone's long press.
 */
@Composable
internal fun DesktopHomeDialsSection(
    shelves: List<HomeShelf>,
    mixes: List<ShelfItem>,
    library: LibraryPage?,
    loading: Boolean,
    settled: Boolean,
    onOpen: (ShelfItem) -> Unit,
    /** A track's own menu; null for anything else. */
    onMore: ((ShelfItem) -> Unit)?,
    onHistory: (() -> Unit)?,
) {
    val dials = rememberDesktopHomeDials(shelves, mixes, library, settled)
    val rows by AppSettings.homeDialRows.collectAsState()
    val withMixes by AppSettings.homeMixesDial.collectAsState()
    val withArtists by AppSettings.homeArtistDial.collectAsState()
    val luckyOn by AppSettings.homeLuckyTile.collectAsState()
    val pages = buildList {
        if (dials.speedDial.isNotEmpty()) add(DialPage.SPEED)
        if (withMixes && dials.mixes.isNotEmpty()) add(DialPage.MIXES)
        if (withArtists && dials.artists.isNotEmpty()) add(DialPage.ARTISTS)
    }
    var picked by remember { mutableStateOf(DialPage.SPEED) }
    val page = picked.takeIf { it in pages } ?: pages.firstOrNull()

    Column(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER).padding(bottom = 24.dp)) {
        if (page == null) {
            if (loading) DialSkeleton(rows)
            return@Column
        }
        val entries = when (page) {
            DialPage.SPEED -> dials.speedDial
            DialPage.MIXES -> dials.mixes
            DialPage.ARTISTS -> dials.artists
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = ((maxWidth + DIAL_GAP) / (DIAL_TILE + DIAL_GAP)).toInt().coerceAtLeast(SpeedDial.COLUMNS)
            val side = (maxWidth - DIAL_GAP * (columns - 1)) / columns
            val pageSize = columns * rows
            val lucky = page == DialPage.SPEED && luckyOn && dials.luckyPool.isNotEmpty()
            val history = page == DialPage.SPEED && onHistory != null
            val slots = buildList<DialSlot> {
                // As the phone's dial, it ends on a full page where it can: the spill past the
                // last whole page is left off, unless that would drop a pin.
                val actions = (if (lucky) 1 else 0) + (if (history) 1 else 0)
                val spill = (entries.size + actions) % pageSize
                val keep = if (entries.size + actions > pageSize && spill != 0 && entries.takeLast(spill).none { it.pinned }) {
                    entries.size - spill
                } else entries.size
                entries.take(keep).mapTo(this) { DialSlot.Tile(it) }
                // The dice owns the first page's last tile, History the dial's last.
                if (lucky) add(minOf(size, pageSize - 1), DialSlot.Lucky)
                if (history) add(DialSlot.History)
            }
            val pageCount = (slots.size + pageSize - 1) / pageSize
            var current by remember(page, pageSize) { mutableIntStateOf(0) }
            val shown = current.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            Column {
                DialHeader(pages, page, { picked = it }, shown, pageCount, { current = it })
                slots.drop(shown * pageSize).take(pageSize).chunked(columns).forEach { row ->
                    Row(Modifier.padding(bottom = DIAL_GAP), horizontalArrangement = Arrangement.spacedBy(DIAL_GAP)) {
                        row.forEach { slot ->
                            val tile = Modifier.size(side)
                            when (slot) {
                                is DialSlot.Tile -> DialTile(slot.entry, page == DialPage.MIXES, tile, onOpen, onMore)
                                DialSlot.Lucky -> ActionTile(Icons.Rounded.Casino, "", tile) {
                                    dials.luckyPool.randomOrNull()?.let(onOpen)
                                }
                                DialSlot.History -> ActionTile(
                                    Icons.Rounded.History,
                                    DesktopStrings["home_history_tile", "History"],
                                    tile,
                                ) { onHistory?.invoke() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The pages as tabs, a reshuffle, and the page arrows. */
@Composable
private fun DialHeader(
    pages: List<DialPage>,
    page: DialPage,
    onPick: (DialPage) -> Unit,
    current: Int,
    pageCount: Int,
    onPage: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        pages.forEach { option ->
            val selected = option == page
            Text(
                DesktopStrings[option.key, option.fallback],
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (selected) Color.White else DesktopSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .desktopHoverFill()
                    .clickable { onPick(option) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (page == DialPage.SPEED) {
            DialIconButton(Icons.Rounded.Refresh, enabled = true) { HomeDialRounds.nextRound() }
        }
        Spacer(Modifier.weight(1f))
        if (pageCount > 1) {
            Text("${current + 1} / $pageCount", color = DesktopSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            DialIconButton(Icons.Rounded.ChevronLeft, enabled = current > 0) { onPage(current - 1) }
            DialIconButton(Icons.Rounded.ChevronRight, enabled = current < pageCount - 1) { onPage(current + 1) }
        }
    }
}

@Composable
private fun DialIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).desktopHoverWash().clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) Color.White else DesktopSecondary.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DialTile(
    entry: DialEntry,
    mixBadge: Boolean,
    modifier: Modifier,
    onOpen: (ShelfItem) -> Unit,
    onMore: ((ShelfItem) -> Unit)?,
) {
    val labels by AppSettings.homeDialLabels.collectAsState()
    val pinIcon by AppSettings.homeDialPinIcon.collectAsState()
    val newTags by AppSettings.homeDialNewReleaseTags.collectAsState()
    val style by AppSettings.homeDialTileStyle.collectAsState()
    val item = entry.item
    var menu by remember { mutableStateOf(false) }
    val isNew = newTags && entry.isNewRelease
    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .desktopHoverLift(DIAL_SHAPE)
                .clip(DIAL_SHAPE)
                .background(Color.White.copy(alpha = 0.06f))
                .clickable { onOpen(item) }
                .contextClick { menu = true }
                .dialRim(style),
        ) {
            Icon(Icons.Rounded.MusicNote, null, tint = DesktopSecondary, modifier = Modifier.align(Alignment.Center).size(28.dp))
            AsyncImage(
                model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (labels) TileLabel(item.title)
            if (entry.pinned && pinIcon) {
                Icon(Icons.Rounded.PushPin, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(14.dp))
            }
            if (isNew) {
                Text(
                    DesktopStrings["home_dial_new", "New"],
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = SCRIM)).padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            } else if (mixBadge) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(8.dp).size(22.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = SCRIM)).border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(14.dp), tint = Color.White)
                }
            }
        }
        DialEntryMenu(menu, entry, onMore) { menu = false }
    }
}

/** A tile's right-click: take it off the dial, pin or unpin a playlist, or a track's own menu. */
@Composable
private fun DialEntryMenu(expanded: Boolean, entry: DialEntry, onMore: ((ShelfItem) -> Unit)?, onDismiss: () -> Unit) {
    val item = entry.item
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(DesktopStrings["home_dial_remove", "Remove from dial"]) },
            onClick = { onDismiss(); AppSettings.setHiddenFromDial(item.dialId, true) },
        )
        val browseId = item.browseId
        if (item.dialKind == DialKind.PLAYLIST && browseId != null) {
            DropdownMenuItem(
                text = {
                    Text(if (entry.pinned) DesktopStrings["home_dial_unpin", "Unpin"] else DesktopStrings["home_dial_pin", "Pin"])
                },
                onClick = {
                    onDismiss()
                    // Pinning brings back a tile taken off the dial.
                    if (AppSettings.togglePinnedPlaylist(browseId)) AppSettings.setHiddenFromDial(item.dialId, false)
                },
            )
        }
        if (onMore != null && item.videoId != null) {
            DropdownMenuItem(
                text = { Text(DesktopStrings["home_dial_more", "More options"]) },
                onClick = { onDismiss(); onMore(item) },
            )
        }
    }
}

/** The dice or History: a dark tile with its glyph in the middle. */
@Composable
private fun ActionTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val style by AppSettings.homeDialTileStyle.collectAsState()
    val labels by AppSettings.homeDialLabels.collectAsState()
    Box(
        modifier
            .desktopHoverLift(DIAL_SHAPE)
            .clip(DIAL_SHAPE)
            .background(Color(0xFF141414).copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .dialRim(style),
    ) {
        Icon(icon, label, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.Center).fillMaxSize(0.36f))
        if (labels && label.isNotEmpty()) TileLabel(label)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.TileLabel(text: String) {
    Box(
        Modifier.fillMaxWidth().height(56.dp).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
    )
    Text(
        text,
        modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Edge and Glass are a light rim over the art (Glass the brighter); Plain has none. */
private fun Modifier.dialRim(style: HomeDialTileStyle): Modifier = when (style) {
    HomeDialTileStyle.PLAIN -> this
    HomeDialTileStyle.EDGE -> border(0.75.dp, Color.White.copy(alpha = 0.16f), DIAL_SHAPE)
    HomeDialTileStyle.GLASS -> border(
        1.25.dp,
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.48f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.16f))),
        DIAL_SHAPE,
    )
}

@Composable
private fun DialSkeleton(rows: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 44.dp)) {
        val columns = ((maxWidth + DIAL_GAP) / (DIAL_TILE + DIAL_GAP)).toInt().coerceAtLeast(SpeedDial.COLUMNS)
        val side = (maxWidth - DIAL_GAP * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(DIAL_GAP)) {
            repeat(rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(DIAL_GAP)) {
                    repeat(columns) { ShimmerBox(Modifier.size(side), DIAL_SHAPE) }
                }
            }
        }
    }
}

/** The narrowest a tile gets: a wider window adds a column once each would still be this wide. */
private val DIAL_TILE = 170.dp
private val DIAL_GAP = 10.dp
private val DIAL_SHAPE = RoundedCornerShape(10.dp)
private const val SCRIM = 0.55f
