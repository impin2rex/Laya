/*
 * The Home dials (Speed Dial, Artists): ported from Melo (github.com/aarush-io/melo,
 * GPL-3.0), reworked for Laya's YouTube Music shelves and on-device listening stats.
 */
package com.music.laya.data.home

import com.music.laya.data.model.HomeShelf
import com.music.laya.data.model.ShelfItem
import com.music.laya.data.model.Song
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.settings.HomeDialSource
import com.music.laya.data.stats.RankedEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** What a dial tile opens. */
enum class DialKind { TRACK, ALBUM, PLAYLIST, ARTIST }

/** What [this] card is, from its ids: a video is a track, else the browse id's prefix says. */
val ShelfItem.dialKind: DialKind?
    get() {
        if (videoId != null) return DialKind.TRACK
        val id = browseId ?: return null
        return when {
            id.startsWith("MPRE") || id.startsWith("OLAK") -> DialKind.ALBUM
            id.startsWith("VL") || id.startsWith("RD") || id.startsWith("PL") -> DialKind.PLAYLIST
            id.startsWith("UC") || id.startsWith("MPLA") -> DialKind.ARTIST
            else -> null
        }
    }

/** One id for a card whatever it is, as the dials and "Remove from dial" key it. */
val ShelfItem.dialId: String get() = videoId ?: browseId ?: title

fun Song.toShelfItem(): ShelfItem = ShelfItem(title, artist, thumbnailUrl, videoId, null)

/** A tile on a dial: the card, and what the dial adds to it. */
data class DialEntry(
    val item: ShelfItem,
    val pinned: Boolean = false,
    val isNewRelease: Boolean = false,
)

/** The Speed Dial's tiles, before pins and hidden ones are applied. */
object SpeedDial {
    const val COLUMNS = 3
    /** More than one page holds, so a tile taken off is replaced from the same deal. */
    const val MAX_ITEMS = 36

    /** Where a Home shelf's cards go in the YouTube dial, by its title, in the languages YouTube names it. */
    fun shelfRank(title: String): Int = when (title.trim().lowercase()) {
        "speed dial" -> 0
        "listen again", "nghe lại", "もう一度聴く", "nochmal hören", "à réécouter", "escuchar de nuevo", "ouvir novamente" -> 1
        "forgotten favorites", "forgotten favourites", "giai điệu quen thuộc", "よく聴いたお気に入りの曲" -> 2
        "quick picks", "lựa chọn nhanh", "chọn nhanh đài phát", "クイック ミックス", "schnellauswahl", "sélection rapide",
        "selección rápida", "escolhas rápidas" -> 3
        else -> OTHER
    }
    private const val OTHER = 4

    /**
     * The tiles past the pins. YouTube: its own Speed dial shelf whole and in order, then Listen
     * again, what was played lately, forgotten favourites, Quick picks, then the rest of Home.
     * History: what was played lately, then the device's most played.
     */
    fun pool(
        source: HomeDialSource,
        shelves: List<HomeShelf>,
        recents: List<ShelfItem>,
        mostPlayed: List<ShelfItem>,
        hidden: Set<String>,
        withArtists: Boolean,
    ): List<ShelfItem> {
        fun ranked(rank: Int) = shelves.filter { shelfRank(it.title) == rank }.flatMap { it.items }
        val playedLately = recents + mostPlayed
        val items = when (source) {
            HomeDialSource.HISTORY -> playedLately + shelves.flatMap { it.items }
            HomeDialSource.YOUTUBE -> ranked(0) + ranked(1) + playedLately + ranked(2) + ranked(3) +
                shelves.filter { shelfRank(it.title) == OTHER }.flatMap { it.items }
        }
        return items.asSequence()
            .filter { it.dialKind != null }
            // With the Artists page on, artists are its own.
            .filterNot { withArtists && it.dialKind == DialKind.ARTIST }
            .filterNot { it.dialId in hidden }
            .distinctBy { it.dialId }
            .take(MAX_ITEMS)
            .toList()
    }

    /** A mood's dial: its shelves dealt in turn, songs only unless it has none. */
    fun category(shelves: List<HomeShelf>, hidden: Set<String>): List<ShelfItem> {
        val longest = shelves.maxOfOrNull { it.items.size } ?: return emptyList()
        val picks = (0 until longest).flatMap { round -> shelves.mapNotNull { it.items.getOrNull(round) } }
            .filter { it.dialKind != null && it.dialId !in hidden }
            .distinctBy { it.dialId }
        return picks.filter { it.dialKind == DialKind.TRACK }.ifEmpty { picks }.take(MAX_ITEMS)
    }

    /** Ids of the cards on Home's new-release shelves, which the dial tags "New". */
    fun newReleaseIds(shelves: List<HomeShelf>): Set<String> =
        shelves.filter { it.title.contains("release", ignoreCase = true) || it.title.contains("new music", ignoreCase = true) }
            .flatMap { it.items }.mapTo(HashSet()) { it.dialId }

    /** Pins first, then the round's deal, topped up from [live] when the deal is short of a page. */
    fun entries(pins: List<ShelfItem>, dealt: List<ShelfItem>, live: List<ShelfItem>, pageSize: Int, newReleases: Set<String>): List<DialEntry> {
        val pinned = pins.mapTo(HashSet()) { it.dialId }
        val picks = dealt.filterNot { it.dialId in pinned }
        val fill = if (pins.size + picks.size < pageSize) live.filterNot { it.dialId in pinned } else emptyList()
        return (pins.map { DialEntry(it, pinned = true) } + (picks + fill).map { DialEntry(it, isNewRelease = it.dialId in newReleases) })
            .distinctBy { it.item.dialId }
            .take(maxOf(pageSize, pins.size))
    }
}

/** The History tile's collage: the first four distinct covers of [played], newest first. */
fun historyCovers(played: List<ShelfItem>): List<String> =
    played.mapNotNull { it.thumbnailUrl?.takeIf(String::isNotBlank) }.distinct().take(4)

/** The Artists page: the most played artists here, then the library's, then Home's. */
object ArtistDial {
    const val SIZE = 36

    fun tiles(
        mostPlayed: List<RankedEntry>,
        library: List<ShelfItem>,
        feed: List<ShelfItem>,
        hidden: Set<String>,
        order: List<String>,
    ): List<ShelfItem> {
        val played = mostPlayed.mapNotNull { entry ->
            val id = entry.browseId?.takeIf { it.startsWith("UC") } ?: return@mapNotNull null
            ShelfItem(entry.title, "", entry.artworkUrl, null, id)
        }
        val tiles = (played + (library + feed).filter { it.dialKind == DialKind.ARTIST }.map(::asChannel))
            .distinctBy { it.dialId }
            .filterNot { it.dialId in hidden }
            .take(SIZE)
        val rank = order.withIndex().associate { it.value to it.index }
        return tiles.withIndex()
            .sortedWith(compareBy<IndexedValue<ShelfItem>> { rank[it.value.dialId] ?: Int.MAX_VALUE }.thenBy { it.index })
            .map { it.value }
    }

    /** The library gives some artists as their library page ("MPLA" + channel id); the dial opens the channel. */
    private fun asChannel(item: ShelfItem): ShelfItem =
        item.browseId?.takeIf { it.startsWith("MPLA") }?.let { item.copy(browseId = it.removePrefix("MPLA")) } ?: item
}

/** A dial as dealt for one round. */
data class DealtDial(val round: Int, val key: String, val items: List<ShelfItem>)

/**
 * The rounds the dials are dealt in: one at launch and one per pull to refresh, so a song
 * starting never moves a tile. The last deal is saved, and the next launch shows it at once.
 */
object HomeDialRounds {
    private val _round = MutableStateFlow(0)
    val round: StateFlow<Int> get() = _round
    /** When this round began (epoch ms); the feed must have arrived after it to be dealt from. */
    var roundStartedAt = 0L
        private set
    val speedDial = MutableStateFlow<DealtDial?>(null)

    fun nextRound() {
        roundStartedAt = Clock.System.now().toEpochMilliseconds()
        _round.value++
    }

    @Serializable
    private data class Saved(val key: String, val items: List<SavedItem>)
    @Serializable
    private data class SavedItem(val title: String, val subtitle: String, val thumb: String?, val video: String?, val browse: String?)

    private val json = Json { ignoreUnknownKeys = true }

    /** The last launch's deal of dial [name] when it was dealt under [key], else null. */
    fun saved(name: String, key: String): List<ShelfItem>? =
        AppSettings.savedHomeDial(name)
            ?.let { runCatching { json.decodeFromString(Saved.serializer(), it) }.getOrNull() }
            ?.takeIf { it.key == key }
            ?.items?.map { ShelfItem(it.title, it.subtitle, it.thumb, it.video, it.browse) }

    fun save(name: String, key: String, items: List<ShelfItem>) {
        val saved = Saved(key, items.map { SavedItem(it.title, it.subtitle, it.thumbnailUrl, it.videoId, it.browseId) })
        AppSettings.saveHomeDial(name, json.encodeToString(Saved.serializer(), saved))
    }

    const val SPEED_DIAL = "speed"
}
