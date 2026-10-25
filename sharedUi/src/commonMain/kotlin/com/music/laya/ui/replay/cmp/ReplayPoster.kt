package com.music.laya.ui.replay.cmp

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import coil3.PlatformContext
import com.music.laya.data.stats.ReplaySummary
import com.music.laya.sharedui.resources.*
import com.music.laya.ui.share.Ink
import com.music.laya.ui.share.Pen
import com.music.laya.ui.share.argb
import com.music.laya.ui.share.artwork
import com.music.laya.ui.share.backdrop
import com.music.laya.ui.share.brandMark
import com.music.laya.ui.share.drawBitmap
import com.music.laya.ui.share.loadArtwork
import com.music.laya.ui.share.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The Replay as one 1080x1920 picture: the phone's `ReplayPoster`, laid out with the same
 * numbers and drawn with Compose's canvas so the iPhone sends the same card.
 *
 * With [page] null it is the whole-Replay poster (totals, two charts, albums, genres); with a
 * story page it is that card — the headline sentence, then the page's own chart.
 */
internal suspend fun renderReplayPoster(
    context: PlatformContext,
    pen: Pen,
    logo: Painter?,
    summary: ReplaySummary,
    holder: String,
    memberSince: String?,
    page: ReplayStoryPage? = null,
): ImageBitmap = coroutineScope {
    val songs = summary.songRows(POSTER_ROWS)
    val artists = summary.artistRows(POSTER_ROWS)
    val albums = summary.albumRows(POSTER_ALBUMS)
    val covers = (songs + artists + albums)
        .mapNotNull { it.artworkUrl }
        .distinct()
        .associateWith { url -> async(Dispatchers.Default) { loadArtwork(context, url) } }
        .mapValues { it.value.await() }

    withContext(Dispatchers.Default) {
        val lit = page?.let { summary.storyArtwork(it) } ?: songs.firstOrNull()?.artworkUrl
        val headline = page?.let { summary.storyHeadline(it) }
        drawBitmap(POSTER_W, POSTER_H) {
            backdrop(covers[lit], page?.let(::storyHue) ?: 0f, POSTER_W.toFloat(), POSTER_H.toFloat())
            var y = drawHeader(pen, logo, summary, holder, memberSince)
            if (page == null) {
                y = drawTotals(pen, summary, y)
                y = drawColumns(pen, songs, artists, covers, y)
                y = drawAlbums(pen, albums, covers, y)
                drawGenres(pen, summary, y)
            } else {
                y = drawRuns(pen, headline.orEmpty(), MARGIN, y, CONTENT_W)
                drawCard(pen, summary, page, covers, y + 56f)
            }
            drawFooter(pen)
        }
    }
}

// ── Story cards ────────────────────────────────────────────────────────────

private fun DrawScope.drawCard(
    pen: Pen,
    summary: ReplaySummary,
    page: ReplayStoryPage,
    covers: Map<String, ImageBitmap?>,
    top: Float,
) {
    when (page) {
        ReplayStoryPage.INTRO, ReplayStoryPage.MINUTES -> drawCollage(summary, covers, top)
        ReplayStoryPage.SONGS -> drawLeaderboard(pen, summary.songRows(CARD_ROWS), covers, top, false)
        ReplayStoryPage.ARTISTS -> drawLeaderboard(pen, summary.artistRows(CARD_ROWS), covers, top, true)
        ReplayStoryPage.ALBUMS -> drawLeaderboard(pen, summary.albumRows(CARD_ROWS), covers, top, false)
        ReplayStoryPage.GENRES -> drawBigList(pen, summary.genreRows(CARD_ROWS), top)
        ReplayStoryPage.HABITS -> drawHabits(pen, summary, top)
        ReplayStoryPage.SUMMARY -> drawRecap(pen, summary, top)
    }
}

private fun DrawScope.drawLeaderboard(
    pen: Pen,
    rows: List<ReplayRow>,
    covers: Map<String, ImageBitmap?>,
    top: Float,
    circular: Boolean,
) {
    val lead = rows.firstOrNull() ?: return
    val hero = 348f
    artwork(lead.artworkUrl?.let { covers[it] }, lead.title, MARGIN, top, hero, circular)

    val textX = MARGIN + hero + 48f
    val textWidth = POSTER_W - MARGIN - textX
    val title = pen.heading(78f, WHITE)
    text(pen, pen.ellipsised(lead.title, title, textWidth), textX, top + 86f, title)
    var y = top + 86f
    lead.subtitle?.let {
        val sub = pen.body(50f, argb(0xB3FFFFFF))
        y += 62f
        text(pen, pen.ellipsised(it, sub, textWidth), textX, y, sub)
    }
    val stats = pen.body(44f, argb(0x8CFFFFFF))
    text(pen, "${formatListening(lead.ms)} · ${replayCount(lead.plays, Res.plurals.replay_play_count)}", textX, y + 62f, stats)

    var rowY = top + hero + 90f
    rows.drop(1).forEach { row ->
        text(pen, row.rank.toString(), MARGIN, rowY + 72f, pen.heading(44f, argb(0x73FFFFFF)))
        val artX = MARGIN + 62f
        artwork(row.artworkUrl?.let { covers[it] }, row.title, artX, rowY, 108f, circular)
        val name = pen.body(48f, WHITE, bold = true)
        val nameX = artX + 108f + 28f
        val stat = pen.body(38f, argb(0x80FFFFFF))
        val statWidth = pen.width(formatListening(row.ms), stat)
        text(pen, pen.ellipsised(row.title, name, POSTER_W - MARGIN - nameX - statWidth - 32f), nameX, rowY + 72f, name)
        text(pen, formatListening(row.ms), POSTER_W - MARGIN, rowY + 72f, stat.toRight())
        rowY += 148f
    }
}

private fun DrawScope.drawBigList(pen: Pen, rows: List<ReplayRow>, top: Float) {
    val lead = rows.firstOrNull() ?: return
    val word = pen.heading(150f, WHITE)
    text(pen, pen.ellipsised(lead.title, word, CONTENT_W), MARGIN, top + 120f, word)
    text(pen, formatListening(lead.ms), MARGIN, top + 186f, pen.body(46f, argb(0x99FFFFFF)))

    var y = top + 300f
    rows.drop(1).forEach { row ->
        text(pen, row.rank.toString(), MARGIN, y, pen.heading(48f, argb(0x73FFFFFF)))
        text(pen, row.title, MARGIN + 80f, y, pen.body(52f, WHITE, bold = true))
        text(pen, formatListening(row.ms), POSTER_W - MARGIN, y, pen.body(38f, argb(0x80FFFFFF)).toRight())
        y += 106f
    }
}

private fun DrawScope.drawHabits(pen: Pen, summary: ReplaySummary, top: Float) {
    var y = top + 40f
    fun stat(value: String, label: String) {
        text(pen, value, MARGIN, y, pen.heading(84f, WHITE))
        text(pen, label, MARGIN, y + 56f, pen.body(42f, argb(0x99FFFFFF)))
        y += 176f
    }
    if (summary.distinctAlbums > 0) {
        stat(grouped(summary.distinctAlbums.toLong()), replayString(Res.string.different_albums))
    }
    summary.busiestDay?.let {
        stat(formatDay(it), replayString(Res.string.your_biggest_day, formatListening(summary.busiestDayMs)))
    }
    summary.peakHour?.let { stat(formatHour(it), replayString(Res.string.when_you_listen_the_most)) }
}

private fun DrawScope.drawRecap(pen: Pen, summary: ReplaySummary, top: Float) {
    var y = top + 40f
    fun line(label: String, value: String) {
        text(pen, label, MARGIN, y, pen.body(42f, argb(0x80FFFFFF)))
        val v = pen.body(52f, WHITE, bold = true)
        text(pen, pen.ellipsised(value, v, CONTENT_W - 320f), MARGIN + 320f, y, v)
        y += 96f
    }
    line(replayString(Res.string.minutes), formatMinutes(summary.totalMs))
    summary.songs.firstOrNull()?.let { line(replayString(Res.string.top_song), it.song.title) }
    summary.artists.firstOrNull()?.let { line(replayString(Res.string.top_artist), it.title) }
    summary.albums.firstOrNull()?.let { line(replayString(Res.string.top_album), it.title) }
    summary.genres.firstOrNull()?.let { line(replayString(Res.string.top_genre), it.title) }
}

private fun DrawScope.drawCollage(summary: ReplaySummary, covers: Map<String, ImageBitmap?>, top: Float) {
    val squares = summary.songs.mapNotNull { it.song.thumbnailUrl }.distinct().take(3)
    val faces = summary.artists.mapNotNull { it.artworkUrl }.distinct().filterNot { it in squares }.take(3)
    val squareAt = listOf(Triple(0.22f, 0.30f, 500f), Triple(0.02f, 0.06f, 260f), Triple(0.66f, 0.00f, 215f))
    val faceAt = listOf(Triple(0.00f, 0.62f, 185f), Triple(0.72f, 0.32f, 225f), Triple(0.46f, 0.76f, 200f))
    val height = 900f
    squares.forEachIndexed { index, url ->
        val (fx, fy, size) = squareAt[index]
        artwork(covers[url], "", MARGIN + CONTENT_W * fx, top + height * fy, size, false)
    }
    faces.forEachIndexed { index, url ->
        val (fx, fy, size) = faceAt[index]
        artwork(covers[url], "", MARGIN + CONTENT_W * fx, top + height * fy, size, true)
    }
}

// ── Whole-Replay poster ────────────────────────────────────────────────────

private fun DrawScope.drawHeader(
    pen: Pen,
    logo: Painter?,
    summary: ReplaySummary,
    holder: String,
    memberSince: String?,
): Float {
    val label = summary.localizedLabel()
    val title = if (label.length == 4 && label.all { it.isDigit() }) "Replay ${label.takeLast(2)}" else "Replay · $label"
    text(pen, title, MARGIN, 132f, pen.heading(46f, WHITE))
    brandMark(pen, logo, POSTER_W - MARGIN, 132f)

    val credit = listOfNotNull(
        holder.ifBlank { replayString(Res.string.default_replay_holder) },
        memberSince?.let { replayString(Res.string.member_since_value, it) },
    ).joinToString(" · ")
    text(pen, credit.uppercase(), MARGIN, 182f, pen.label(24f, argb(0x8CFFFFFF)))
    return 300f
}

private fun DrawScope.drawTotals(pen: Pen, summary: ReplaySummary, top: Float): Float {
    text(pen, formatMinutes(summary.totalMs), MARGIN, top + 120f, pen.heading(148f, WHITE))
    text(
        pen,
        replayString(Res.string.minutes_listened).uppercase(),
        MARGIN,
        top + 176f,
        pen.label(30f, argb(0xB3FFFFFF), tracking = 0.14f),
    )
    text(
        pen,
        "${replayCount(summary.totalPlays, Res.plurals.replay_play_count)} · " +
            "${replayCount(summary.distinctSongs, Res.plurals.replay_song_count)} · " +
            replayCount(summary.distinctArtists, Res.plurals.replay_artist_count),
        MARGIN,
        top + 232f,
        pen.body(30f, argb(0x99FFFFFF)),
    )
    return top + 320f
}

private fun DrawScope.drawColumns(
    pen: Pen,
    songs: List<ReplayRow>,
    artists: List<ReplayRow>,
    covers: Map<String, ImageBitmap?>,
    top: Float,
): Float {
    val columnWidth = (POSTER_W - MARGIN * 2 - COLUMN_GAP) / 2f
    val right = MARGIN + columnWidth + COLUMN_GAP

    val heading = pen.label(28f, argb(0xB3FFFFFF), tracking = 0.16f)
    text(pen, replayString(Res.string.top_songs).uppercase(), MARGIN, top, heading)
    text(pen, replayString(Res.string.top_artists).uppercase(), right, top, heading)

    val rows = maxOf(songs.size, artists.size)
    var y = top + 54f
    repeat(rows) { index ->
        songs.getOrNull(index)?.let { drawRow(pen, it, covers, MARGIN, y, columnWidth, false) }
        artists.getOrNull(index)?.let { drawRow(pen, it, covers, right, y, columnWidth, true) }
        y += ROW_HEIGHT
    }
    return y + 40f
}

private fun DrawScope.drawRow(
    pen: Pen,
    row: ReplayRow,
    covers: Map<String, ImageBitmap?>,
    x: Float,
    y: Float,
    width: Float,
    circular: Boolean,
) {
    text(pen, row.rank.toString(), x, y + ART * 0.68f, pen.heading(34f, if (row.rank == 1) ACCENT else argb(0x73FFFFFF)))
    val artX = x + 44f
    artwork(row.artworkUrl?.let { covers[it] }, row.title, artX, y, ART, circular)

    val textX = artX + ART + 20f
    val textWidth = width - (textX - x)
    val title = pen.body(30f, WHITE, bold = true)
    text(pen, pen.ellipsised(row.title, title, textWidth), textX, y + 36f, title)
    val sub = pen.body(25f, argb(0x99FFFFFF))
    val detail = listOfNotNull(row.subtitle, formatListening(row.ms)).joinToString(" · ")
    text(pen, pen.ellipsised(detail, sub, textWidth), textX, y + 72f, sub)
}

private fun DrawScope.drawAlbums(
    pen: Pen,
    albums: List<ReplayRow>,
    covers: Map<String, ImageBitmap?>,
    top: Float,
): Float {
    if (albums.isEmpty()) return top
    text(pen, replayString(Res.string.top_albums).uppercase(), MARGIN, top, pen.label(28f, argb(0xB3FFFFFF), tracking = 0.16f))
    val size = 200f
    val gap = (POSTER_W - MARGIN * 2 - size * POSTER_ALBUMS) / (POSTER_ALBUMS - 1)
    val y = top + 40f
    albums.take(POSTER_ALBUMS).forEachIndexed { index, album ->
        val x = MARGIN + index * (size + gap)
        artwork(album.artworkUrl?.let { covers[it] }, album.title, x, y, size, false)
        val name = pen.body(25f, WHITE, bold = true)
        text(pen, pen.ellipsised(album.title, name, size), x, y + size + 38f, name)
        val sub = pen.body(22f, argb(0x8CFFFFFF))
        text(pen, pen.ellipsised(album.subtitle ?: formatListening(album.ms), sub, size), x, y + size + 70f, sub)
    }
    return y + size + 130f
}

private fun DrawScope.drawGenres(pen: Pen, summary: ReplaySummary, top: Float) {
    val genres = summary.genreRows(POSTER_GENRES)
    if (genres.isEmpty()) return
    text(pen, replayString(Res.string.top_genres).uppercase(), MARGIN, top, pen.label(28f, argb(0xB3FFFFFF), tracking = 0.16f))
    val ink = pen.body(32f, WHITE, bold = true)
    var x = MARGIN
    val y = top + 44f
    for (genre in genres) {
        val width = pen.width(genre.title, ink) + 44f
        if (x + width > POSTER_W - MARGIN) return
        drawRoundRect(argb(0x26FFFFFF), Offset(x, y), Size(width, 62f), CornerRadius(31f))
        text(pen, genre.title, x + 22f, y + 42f, ink)
        x += width + 14f
    }
}

private fun DrawScope.drawFooter(pen: Pen) {
    text(
        pen,
        replayString(Res.string.counted_on_device_with_laya),
        MARGIN,
        POSTER_H - 64f,
        pen.label(24f, argb(0x73FFFFFF), tracking = 0.08f),
    )
}

/**
 * The story headline set in two weights, wrapped by hand: a word at a time, the bold runs in
 * Heavy and the rest in Semibold, so a run can start mid-line the way the on-screen text does.
 */
private fun DrawScope.drawRuns(pen: Pen, runs: List<HeadlineRun>, x: Float, top: Float, maxWidth: Float): Float {
    val bold = pen.heading(HEADLINE_SIZE, WHITE)
    val plain = pen.body(HEADLINE_SIZE, argb(0x9EFFFFFF), bold = true)

    val text = StringBuilder()
    val weights = ArrayList<Boolean>()
    runs.forEach { run ->
        text.append(run.text)
        repeat(run.text.length) { weights += run.bold }
    }

    var lineX = x
    var y = top + HEADLINE_SIZE
    var index = 0
    while (index < text.length) {
        if (text[index].isWhitespace()) {
            index++
            continue
        }
        var end = index
        while (end < text.length && !text[end].isWhitespace()) end++
        val word = text.substring(index, end)
        val ink: Ink = if (weights[index]) bold else plain
        val width = pen.width("$word ", ink)
        if (lineX + width - x > maxWidth && lineX > x) {
            lineX = x
            y += HEADLINE_LEADING
        }
        text(pen, word, lineX, y, ink)
        lineX += width
        index = end
    }
    return y + 24f
}

private const val POSTER_W = 1080
private const val POSTER_H = 1920
private const val MARGIN = 72f
private const val CONTENT_W = POSTER_W - MARGIN * 2
private const val HEADLINE_SIZE = 88f
private const val HEADLINE_LEADING = 108f
private const val CARD_ROWS = 5
private const val COLUMN_GAP = 36f
private const val ROW_HEIGHT = 116f
private const val ART = 84f
private val ACCENT = argb(0xFFFA2D48)
private val WHITE = argb(0xFFFFFFFF)
private const val POSTER_ROWS = 5
private const val POSTER_ALBUMS = 3
private const val POSTER_GENRES = 4
