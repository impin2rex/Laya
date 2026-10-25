package com.music.laya.ui.replay.cmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.music.laya.data.stats.ArtistFacts
import com.music.laya.data.stats.ListeningStats
import com.music.laya.data.stats.ReplayPeriod
import com.music.laya.data.stats.ReplaySummary
import com.music.laya.sharedui.resources.*
import com.music.laya.ui.replay.InitialTile
import com.music.laya.ui.replay.RankBadge
import com.music.laya.ui.replay.ReplayCreditCard
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.painterResource
import com.music.laya.data.stats.CivilDate
import com.music.laya.platform.ioDispatcher
import com.music.laya.platform.monthName
import com.music.laya.platform.runBlockingCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * The Replay's state: which stretch of listening is being shown, and the
 * numbers for it.
 *
 * Held here rather than in [com.music.laya.ui.MainViewModel] because it is
 * only alive while the page is: the summary is a merge of a few files and is
 * cheap to make, and keeping a copy of every chart in a view model that outlives
 * the screen would hold artwork URLs and a few hundred rows for the rest of the
 * session in exchange for saving a hundred milliseconds nobody would notice.
 *
 * The *period* does survive, because it is a choice rather than a result.
 */
class ReplayState(
    val period: ReplayPeriod,
    val summary: ReplaySummary?,
    val loading: Boolean,
    /**
     * `MM/YY` of the first month this device recorded anything, for the card.
     *
     * Deliberately all-time rather than the open period's own first month: a
     * card that says "member since" has to mean since you started, and reading
     * it off the period would have it announce a new membership every time the
     * chips were switched to This month.
     */
    val memberSince: String?,
)

/**
 * @param active whether the Replay is open in any of its three forms — the
 *   page, the stories, the share sheet. The state is hoisted to the app so all
 *   three read one set of numbers, and this is what stops that hoisting from
 *   costing a file merge on every cold start for a page most launches never
 *   open. It also means reopening Replay re-reads: whatever has been played
 *   since is on it.
 */
@Composable
fun rememberReplayState(active: Boolean): Pair<ReplayState, (ReplayPeriod) -> Unit> {
    var period by rememberSaveable { mutableStateOf(ReplayPeriod.THIS_YEAR) }
    var summary by remember { mutableStateOf<ReplaySummary?>(null) }
    var loading by remember { mutableStateOf(true) }
    var memberSince by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        // A directory listing, so off the composition's thread.
        memberSince = withContext(ioDispatcher) {
            ListeningStats.months().firstOrNull()?.let {
                "${it.month.toString().padStart(2, '0')}/${(it.year % 100).toString().padStart(2, '0')}"
            }
        }
    }
    LaunchedEffect(period, active) {
        if (!active) return@LaunchedEffect
        // Only the first read shows a spinner. Switching period must not blank
        // the charts for the beat it takes to merge the files — that reads as
        // the page breaking rather than as it answering a different question.
        loading = summary == null
        summary = ListeningStats.summary(period)
        loading = false

        // Artist pictures and pages arrive after the page has been built — see
        // [ArtistFacts.revision] — so the charts are rebuilt when they do.
        //
        // Collected inside the effect rather than as composed state on purpose:
        // this function is called from the app's root, so a revision held as
        // state would recompose the whole tree every time a lookup landed, even
        // with the Replay closed. `collectLatest` gives the debounce for free —
        // a burst of lookups cancels each pending delay and only the last one
        // gets as far as a rebuild.
        ArtistFacts.revision.drop(1).collectLatest {
            delay(SETTLE_MILLIS)
            summary = ListeningStats.summary(period)
        }
    }
    return ReplayState(period, summary, loading, memberSince) to
        { next: ReplayPeriod -> period = next }
}

/** How long a burst of artist lookups is allowed to settle before a rebuild. */
private const val SETTLE_MILLIS = 1_200L

/**
 * One run of a card's headline, and whether it is the emphasised part.
 *
 * The sentence lives here rather than in the story that draws it because it is
 * drawn twice — once on screen and once into the picture the share button
 * produces — and a card that says something different in the version people
 * send is worse than no picture at all.
 */
data class HeadlineRun(val text: String, val bold: Boolean)

private fun runs(vararg parts: Pair<String, Boolean>): List<HeadlineRun> =
    parts.map { HeadlineRun(it.first, it.second) }

/** The sentence at the top of [page]. */
fun ReplaySummary.storyHeadline(page: ReplayStoryPage): List<HeadlineRun> = when (page) {
    ReplayStoryPage.INTRO -> runs(
        replayString(Res.string.replay_intro_start) to false,
        " " to false,
        "Replay" to true,
        " " to false,
        replayString(Res.string.replay_intro_end) to false,
    )
    ReplayStoryPage.MINUTES -> runs(
        replayString(Res.string.replay_minutes_start) to false,
        " " to false,
        replayPlural(Res.plurals.replay_minutes_unit, formatMinutes(totalMs).replace(",", "").toIntOrNull() ?: 0,
            formatMinutes(totalMs),
        ) to true,
        " " to false,
        replayString(Res.string.replay_minutes_end) to false,
    )
    ReplayStoryPage.SONGS -> runs(
        replayString(Res.string.replay_songs_start) to false,
        " " to false,
        replayCount(totalPlays, Res.plurals.replay_song_count) to true,
        " " to false,
        (if (period == ReplayPeriod.THIS_WEEK) replayString(Res.string.replay_songs_end_week) else replayString(Res.string.replay_songs_end)) to false,
    )
    ReplayStoryPage.ARTISTS -> runs(
        (if (period == ReplayPeriod.THIS_WEEK) replayString(Res.string.replay_artist_start_week) else replayString(Res.string.replay_artist_start)) to false,
        " " to false,
        replayString(Res.string.replay_artist_focus) to true,
        " " to false,
        replayString(Res.string.replay_artist_end) to false,
    )
    ReplayStoryPage.ALBUMS -> runs(
        replayString(Res.string.replay_album_start) to false,
        " " to false,
        replayString(Res.string.replay_album_focus) to true,
        " " to false,
        replayString(Res.string.replay_album_end) to false,
    )
    ReplayStoryPage.GENRES -> runs(
        replayString(Res.string.replay_genre_start) to false,
        " " to false,
        replayString(Res.string.replay_genre_focus) to true,
        " " to false,
        replayString(Res.string.replay_genre_end) to false,
    )
    ReplayStoryPage.HABITS -> runs(
        replayString(Res.string.replay_habits_start) to false,
        " " to false,
        replayCount(distinctSongs, Res.plurals.replay_song_count) to true,
        " " to false,
        replayString(Res.string.replay_habits_middle) to false,
        " " to false,
        replayCount(distinctArtists, Res.plurals.replay_artist_count) to true,
        "." to false,
    )
    ReplayStoryPage.SUMMARY -> runs(
        replayString(Res.string.replay_summary_start) to false,
        " " to false,
        if (period == ReplayPeriod.THIS_WEEK) replayString(Res.string.replay_summary_end_week) to true else label to true,
        if (period == ReplayPeriod.THIS_WEEK) " in music." to false else " in music." to false,
    )
}

fun ReplayPeriod.localizedChip(): String = when (this) {
    ReplayPeriod.THIS_WEEK -> replayString(Res.string.replay_this_week)
    ReplayPeriod.THIS_MONTH -> replayString(Res.string.this_month)
    ReplayPeriod.THIS_YEAR -> replayString(Res.string.this_year)
    ReplayPeriod.ALL_TIME -> replayString(Res.string.all_time)
}

/** The period picker's short name for each stretch — see `PeriodPicker`. */
fun ReplayPeriod.localizedTab(): String = when (this) {
    ReplayPeriod.THIS_WEEK -> replayString(Res.string.replay_period_weekly)
    ReplayPeriod.THIS_MONTH -> replayString(Res.string.replay_period_monthly)
    ReplayPeriod.THIS_YEAR -> replayString(Res.string.replay_period_yearly)
    ReplayPeriod.ALL_TIME -> replayString(Res.string.replay_period_lifetime)
}

fun ReplaySummary.localizedLabel(): String = when (period) {
    ReplayPeriod.THIS_WEEK -> "This week"
    ReplayPeriod.THIS_MONTH -> CivilDate.today().let { monthName(it.month).replaceFirstChar { c -> c.titlecase() } + " " + it.year }
    ReplayPeriod.THIS_YEAR -> CivilDate.today().year.toString()
    ReplayPeriod.ALL_TIME -> replayString(Res.string.all_time)
}

/**
 * Which cover a card is washed in.
 *
 * The three cards that are *about* something take that thing's artwork. The rest
 * walk a pool of everything on the Replay, so a run of eight cards is lit by
 * eight different records rather than by the top song eight times.
 */
fun ReplaySummary.storyArtwork(page: ReplayStoryPage): String? {
    val pool = (
        songs.map { it.song.thumbnailUrl } +
            artists.map { it.artworkUrl } +
            albums.map { it.artworkUrl }
        ).filterNotNull().distinct()
    val pinned = when (page) {
        ReplayStoryPage.SONGS -> songs.firstOrNull()?.song?.thumbnailUrl
        ReplayStoryPage.ARTISTS -> artists.firstOrNull()?.artworkUrl
        ReplayStoryPage.ALBUMS -> albums.firstOrNull()?.artworkUrl
        else -> null
    }
    if (pinned != null) return pinned
    if (pool.isEmpty()) return null
    return pool[page.ordinal % pool.size]
}

/**
 * How far a card's palette is turned around the colour wheel.
 *
 * The mesh is sampled from artwork, and a Replay is frequently four covers by
 * two artists with the same art direction — which is a run of eight cards in one
 * shade of blue. Rotating the hue per card is what makes the story *look* like a
 * story: every one arrives a different colour, and because only the hue moves,
 * the saturation and lightness the mesh was tuned for are untouched, so no card
 * comes out muddy or blown out.
 *
 * The steps are irregular rather than an even eighth of the wheel: an even walk
 * reads as a colour-picker demo, and the gaps here keep neighbours far enough
 * apart to be obviously different without the run looking mechanical.
 */
fun storyHue(page: ReplayStoryPage): Float = when (page) {
    ReplayStoryPage.INTRO -> 0f
    ReplayStoryPage.MINUTES -> 40f
    ReplayStoryPage.SONGS -> 95f
    ReplayStoryPage.ARTISTS -> 145f
    ReplayStoryPage.ALBUMS -> 195f
    ReplayStoryPage.GENRES -> 240f
    ReplayStoryPage.HABITS -> 285f
    ReplayStoryPage.SUMMARY -> 325f
}

/** Which page of the story view something opens onto. */
enum class ReplayStoryPage {
    INTRO, MINUTES, ARTISTS, SONGS, ALBUMS, GENRES, HABITS, SUMMARY;

    companion object {
        val ordered: List<ReplayStoryPage> = entries
    }
}

// ── Formatting ──────────────────────────────────────────────────────────────

/**
 * Listening time, in the largest unit that still says something.
 *
 * Minutes up to a day's worth, then hours: "1,284 minutes" is a number people
 * read as a number, and "21 hours" is one they read as an amount. Past a
 * thousand hours neither works and it becomes days.
 */
fun formatListening(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 60 -> replayString(Res.string.minutes_short, minutes)
        minutes < 1_440 -> replayString(Res.string.hours_minutes_short, minutes / 60, minutes % 60)
        else -> replayString(Res.string.minutes_grouped_short, grouped(minutes))
    }
}

/** The headline figure on the minutes card: always minutes, always grouped. */
fun formatMinutes(ms: Long): String = grouped(ms / 60_000)

fun grouped(value: Long): String {
    val digits = kotlin.math.abs(value).toString()
    val body = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (value < 0) "-$body" else body
}

/**
 * Resource text without a composition: the headline, the cards and the poster are built outside
 * one, where Compose Resources only offers suspend lookups. The files are a few bytes and read
 * from the app bundle, so blocking for one is cheaper than threading a loader through every call.
 */
fun replayString(id: StringResource, vararg args: Any): String = runBlockingCompat { getString(id, *args) }

fun replayPlural(id: PluralStringResource, quantity: Int, vararg args: Any): String =
    runBlockingCompat { getPluralString(id, quantity, *args) }

fun replayCount(count: Int, plural: PluralStringResource): String =
    replayPlural(plural, count, grouped(count.toLong()))

/** "3 pm", "midnight" — an hour of the day said the way anyone would say it. */
fun formatHour(hour: Int): String = when (hour) {
    0 -> replayString(Res.string.midnight)
    12 -> replayString(Res.string.midday)
    in 1..11 -> replayString(Res.string.hour_am, hour)
    else -> replayString(Res.string.hour_pm, hour - 12)
}

/** `2026-08-14` as "14 August". */
fun formatDay(iso: String): String = CivilDate.parse(iso)?.let { "${it.day} ${monthName(it.month)}" } ?: iso


// ── Rows and cards ──────────────────────────────────────────────────────────

/**
 * One line of one chart, with the four categories flattened onto a common
 * shape.
 *
 * Songs, artists, albums and genres are unlike enough in the data layer to be
 * kept apart there and alike enough on screen to be drawn once. [rank] is
 * carried on the row rather than derived from its index because the same row is
 * drawn in three places — the page, a story, the shared poster — and only one of
 * them has an index to hand.
 */
data class ReplayRow(
    val key: String,
    val rank: Int,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val ms: Long,
    val plays: Int,
)

fun ReplaySummary.songRows(limit: Int): List<ReplayRow> =
    songs.take(limit).mapIndexed { index, entry ->
        ReplayRow(
            key = entry.song.videoId,
            rank = index + 1,
            title = entry.song.title,
            subtitle = entry.song.artist.takeIf { it.isNotBlank() },
            artworkUrl = entry.song.thumbnailUrl,
            ms = entry.ms,
            plays = entry.plays,
        )
    }

fun ReplaySummary.artistRows(limit: Int): List<ReplayRow> =
    artists.take(limit).mapIndexed { index, entry ->
        ReplayRow(
            key = entry.title,
            rank = index + 1,
            title = entry.title,
            subtitle = null,
            artworkUrl = entry.artworkUrl,
            ms = entry.ms,
            plays = entry.plays,
        )
    }

fun ReplaySummary.albumRows(limit: Int): List<ReplayRow> =
    albums.take(limit).mapIndexed { index, entry ->
        ReplayRow(
            key = entry.title + "|" + entry.subtitle.orEmpty(),
            rank = index + 1,
            title = entry.title,
            subtitle = entry.subtitle,
            artworkUrl = entry.artworkUrl,
            ms = entry.ms,
            plays = entry.plays,
        )
    }

/**
 * Genres, with the artwork deliberately dropped.
 *
 * The cover of whichever artist happened to lead the genre is not a picture of
 * the genre, and putting it there makes a row of five look like five artists
 * mislabelled. [com.music.laya.ui.replay.InitialTile] stands in instead.
 */
fun ReplaySummary.genreRows(limit: Int): List<ReplayRow> =
    genres.take(limit).mapIndexed { index, entry ->
        ReplayRow(
            key = entry.title,
            rank = index + 1,
            title = entry.title,
            subtitle = null,
            artworkUrl = null,
            ms = entry.ms,
            plays = entry.plays,
        )
    }

/** One of the Replay headline cards shown on Library and inside Replay. */
data class ReplayHeroCard(
    val label: String,
    val value: String,
    val detail: String?,
    val artworkUrl: String?,
    val page: ReplayStoryPage,
)

/**
 * The four headline facts, in their Library navigation order: overview, songs,
 * artists and albums.
 *
 * Minutes leads because it is the one figure that needs no context to mean
 * something. A category with nothing in it is left out rather than shown empty:
 * a Replay of loose singles has no album chart, and a card reading "—" is worse
 * than three cards.
 */
fun ReplaySummary.cards(): List<ReplayHeroCard> = buildList {
    add(
        ReplayHeroCard(
            label = replayString(Res.string.minutes_listened),
            value = formatMinutes(totalMs),
            detail = "${replayCount(totalPlays, Res.plurals.replay_play_count)} · " +
                localizedLabel(),
            artworkUrl = songs.firstOrNull()?.song?.thumbnailUrl,
            page = ReplayStoryPage.MINUTES,
        ),
    )
    songs.firstOrNull()?.let {
        add(
            ReplayHeroCard(
                label = replayString(Res.string.top_song),
                value = it.song.title,
                detail = "${it.song.artist} · ${replayCount(it.plays, Res.plurals.replay_play_count)}",
                artworkUrl = it.song.thumbnailUrl,
                page = ReplayStoryPage.SONGS,
            ),
        )
    }
    artists.firstOrNull()?.let {
        add(
            ReplayHeroCard(
                label = replayString(Res.string.top_artist),
                value = it.title,
                detail = "${formatListening(it.ms)} · " +
                    replayCount(it.plays, Res.plurals.replay_play_count),
                artworkUrl = it.artworkUrl,
                page = ReplayStoryPage.ARTISTS,
            ),
        )
    }
    albums.firstOrNull()?.let {
        add(
            ReplayHeroCard(
                label = replayString(Res.string.top_album),
                value = it.title,
                detail = listOfNotNull(it.subtitle, formatListening(it.ms)).joinToString(" · "),
                artworkUrl = it.artworkUrl,
                page = ReplayStoryPage.ALBUMS,
            ),
        )
    }
}
