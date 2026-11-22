package com.music.laya.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import coil3.PlatformContext
import com.music.laya.ui.share.Ink
import com.music.laya.ui.share.Pen
import com.music.laya.ui.share.argb
import com.music.laya.ui.share.artwork
import com.music.laya.ui.share.backdrop
import com.music.laya.ui.share.brandMark
import com.music.laya.ui.share.drawBitmap
import com.music.laya.ui.share.loadArtwork
import com.music.laya.ui.share.text
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The picked lines as one picture: the phone's `renderLyricsShareCard`, same numbers and same
 * size ladder, drawn with Compose's canvas. 1080 wide, only as tall as its words (9:16 at most),
 * or padded out to the full frame when [story] is set.
 */
internal suspend fun renderLyricsShareCard(
    context: PlatformContext,
    pen: Pen,
    logo: Painter?,
    card: LyricsShareRequest,
    story: Boolean = false,
    showLogo: Boolean = true,
): ImageBitmap = lyricsCardFrames(context, pen, logo, card, story, showLogo).render(null)

/**
 * The card laid out once, ready to be drawn at any moment of the song: the backdrop, the mark
 * and the footer go into one base picture, and the passage is drawn over it per frame by
 * [LyricsCardFrames] - the share video's frames, and the sheet's live preview.
 */
internal suspend fun lyricsCardFrames(
    context: PlatformContext,
    pen: Pen,
    logo: Painter?,
    card: LyricsShareRequest,
    story: Boolean = false,
    showLogo: Boolean = true,
): LyricsCardFrames {
    val cover = card.artworkUrl?.let { loadArtwork(context, it) }

    // With no mark the passage starts at the same margin the footer keeps below.
    val headTop = if (showLogo) HEADER_BOTTOM + GAP_ABOVE_BODY else FOOTER_PAD
    val frameRoom = MAX_CARD_H - FOOTER_THUMB - FOOTER_PAD - GAP_BELOW_BODY - headTop
    val plan = fittestPlan(pen, card.lines, frameRoom)
    val spare = if (story) (frameRoom - plan.content).coerceAtLeast(0f) else 0f
    val bodyTop = headTop + spare / 2
    val bodyBottom = bodyTop + plan.content
    val cardH = bodyBottom + spare / 2 + GAP_BELOW_BODY + FOOTER_THUMB + FOOTER_PAD
    val height = cardH.roundToInt()

    val base = drawBitmap(CARD_W, height) {
        backdrop(cover, 0f, CARD_W.toFloat(), cardH)
        if (showLogo) brandMark(pen, logo, CARD_W - CARD_MARGIN, HEADER_BASELINE)

        val thumbY = cardH - FOOTER_PAD - FOOTER_THUMB
        artwork(cover, card.song.title, CARD_MARGIN, thumbY, FOOTER_THUMB, false)
        val textX = CARD_MARGIN + FOOTER_THUMB + 34f
        val room = CARD_W - CARD_MARGIN - textX
        val title = pen.body(50f, argb(0xFFFFFFFF), bold = true)
        text(pen, pen.ellipsised(card.song.title, title, room), textX, thumbY + 74f, title)
        val artist = pen.body(42f, argb(0x99FFFFFF))
        text(pen, pen.ellipsised(card.song.artist, artist, room), textX, thumbY + 132f, artist)
    }

    var y = bodyTop
    val placed = plan.rows.map { row ->
        val baseline = y + row.gapBefore + pen.ascent(row.ink) +
            if (row.isGap) row.ink.size * GAP_MARKER_OFFSET else 0f
        val x = if (row.centered) CARD_W / 2f else CARD_MARGIN
        y += row.gapBefore + row.height
        val layout = pen.measurer.measure(row.text, row.ink.style, softWrap = false, maxLines = 1)
        PlacedLine(row, layout, Offset(x, baseline - layout.firstBaseline))
    }
    return LyricsCardFrames(CARD_W, height, base, placed, card.lines)
}

/** A row of the passage, measured once, and where its top-left sits on the card. */
internal class PlacedLine(val row: CardRow, val layout: TextLayoutResult, val topLeft: Offset)

/**
 * Draws the card at a moment of the song, the way the lyrics panel lights the same lines then:
 * the sung part of a word-synced line swept bright over a dim copy of the rest, a line-synced
 * line lit whole once it starts, the lines further from the one being sung fading down the
 * panel's falloff. With no position (the still card) every line is drawn lit.
 *
 * Simpler than the phone's painter: no word lift and no held-word swell, just the sweep.
 */
internal class LyricsCardFrames(
    val width: Int,
    val height: Int,
    private val base: ImageBitmap,
    private val rows: List<PlacedLine>,
    private val lines: List<LyricsShareLine>,
) {
    /** Each line's place among the sung lines (gaps don't count), for the falloff. */
    private val rank = IntArray(lines.size).also { ranks ->
        var next = 0
        lines.forEachIndexed { index, line -> ranks[index] = if (line.isGap) -1 else next++ }
    }

    /** The card at [positionMs] (track time; null for the still card). */
    fun render(positionMs: Long?): ImageBitmap = drawBitmap(width, height) { drawOn(this, positionMs) }

    /** Draws the card into [scope] at the card's own pixel size. */
    fun drawOn(scope: DrawScope, positionMs: Long?) {
        scope.drawImage(base)
        val active = positionMs?.let(::activeLine) ?: -1
        for (placed in rows) scope.drawRow(placed, positionMs, active)
    }

    /** The line being sung at [positionMs]: the last one to have started, or the first. */
    private fun activeLine(positionMs: Long): Int {
        var first = -1
        var last = -1
        lines.forEachIndexed { index, line ->
            if (line.isGap) return@forEachIndexed
            val start = line.startMs ?: line.timing?.timeMs ?: return@forEachIndexed
            if (first < 0) first = index
            if (positionMs >= start) last = index
        }
        return if (last >= 0) last else first
    }

    private fun DrawScope.drawRow(placed: PlacedLine, positionMs: Long?, active: Int) {
        val row = placed.row
        val line = lines.getOrNull(row.line)
        if (positionMs == null || row.isGap || line == null || active < 0) {
            drawText(placed.layout, topLeft = placed.topLeft)
            return
        }
        val distance = abs(rank[row.line] - rank[active]).coerceAtMost(LINE_FALLOFF.lastIndex)
        val alpha = LINE_FALLOFF[distance]
        val timing = line.timing
        val start = line.startMs ?: timing?.timeMs ?: 0L
        if (timing == null || !timing.isWordSynced) {
            // No word timings: the line lights whole the moment it starts.
            val lit = if (positionMs >= start) 1f else UNSUNG_ALPHA
            drawText(placed.layout, topLeft = placed.topLeft, alpha = alpha * lit)
            return
        }
        val length = row.text.length
        val revealedInLine = if (row.isSub) {
            val lead = timing.text.length.coerceAtLeast(1)
            (timing.revealedChars(positionMs) / lead).coerceIn(0f, 1f) * (line.subText?.length ?: 0)
        } else {
            timing.revealedChars(positionMs)
        }
        val revealed = (revealedInLine - row.start).coerceIn(0f, length.toFloat())
        if (positionMs >= timing.endMs || revealed >= length) {
            drawText(placed.layout, topLeft = placed.topLeft, alpha = alpha)
            return
        }
        drawText(placed.layout, topLeft = placed.topLeft, alpha = alpha * UNSUNG_ALPHA)
        if (revealed <= 0f) return
        val k = revealed.toInt().coerceIn(0, length)
        val here = placed.layout.getHorizontalPosition(k, true)
        val next = placed.layout.getHorizontalPosition((k + 1).coerceAtMost(length), true)
        val edge = placed.topLeft.x + here + (next - here) * (revealed - k)
        val bottom = placed.topLeft.y + placed.layout.size.height
        clipRect(left = 0f, top = placed.topLeft.y, right = edge, bottom = bottom) {
            drawText(placed.layout, topLeft = placed.topLeft, alpha = alpha)
        }
    }
}

internal class CardRow(
    val text: String,
    val ink: Ink,
    val height: Float,
    val gapBefore: Float,
    val centered: Boolean = false,
    val isGap: Boolean = false,
    /** Which of the request's lines this row belongs to. */
    val line: Int = -1,
    /** Where the row starts in its line's text (or in its sub-line's, for [isSub]). */
    val start: Int = 0,
    /** The translation or romanization under the line, rather than the line itself. */
    val isSub: Boolean = false,
)

private class Plan(val rows: List<CardRow>, val ascent: Float) {
    val content: Float
        get() = ascent + rows.fold(0f) { total, row -> total + row.gapBefore + row.height }
}

/** The largest size on [FIT_LADDER] whose rows fit in [room], or the bottom rung (the frame grows, not the type shrinks). */
private fun fittestPlan(pen: Pen, lines: List<LyricsShareLine>, room: Float): Plan {
    for (size in FIT_LADDER) {
        val plan = planAt(pen, lines, size)
        if (plan.content <= room) return plan
    }
    return planAt(pen, lines, FIT_LADDER.last())
}

private fun planAt(pen: Pen, lines: List<LyricsShareLine>, size: Float): Plan {
    val primary = pen.heading(size, argb(0xFFFFFFFF), TRACKING)
    val subSize = (size * SUB_RATIO).coerceAtLeast(SUB_FLOOR)
    val secondary = pen.heading(subSize, SUB_COLOR, SUB_TRACKING)

    val rows = mutableListOf<CardRow>()
    lines.forEachIndexed { index, line ->
        if (line.isGap) {
            val marker = pen.body(size * 0.65f, argb(0x66FFFFFF))
            rows += CardRow("(...)", marker, pen.ascent(marker) + pen.descent(marker), 0f, isGap = true, line = index)
            return@forEachIndexed
        }
        var firstOfLine = true
        fun add(text: String, ink: Ink, height: Float, start: Int, isSub: Boolean) {
            val gap = when {
                rows.isEmpty() -> 0f
                firstOfLine && index > 0 -> size * 0.55f
                else -> 0f
            }
            rows += CardRow(text, ink, height, gap, line = index, start = start, isSub = isSub)
            firstOfLine = false
        }
        // Each row remembers where it starts in its line, so the sweep (which
        // counts characters of the whole line) can be found within it.
        fun rows(source: String, ink: Ink, height: Float, isSub: Boolean) {
            var cursor = 0
            wrap(pen, source, ink, CARD_CONTENT_W).forEach { piece ->
                val at = source.indexOf(piece, cursor).takeIf { it >= 0 } ?: cursor
                cursor = at + piece.length
                add(piece, ink, height, at, isSub)
            }
        }
        rows(line.text, primary, size * LEAD, isSub = false)
        val sub = line.subText
        if (!sub.isNullOrBlank() && sub != line.text) {
            rows(sub, secondary, subSize * SUB_LEAD, isSub = true)
        }
    }
    return Plan(rows, pen.ascent(primary))
}

/** Greedy wrap at [width], breaking inside a word when a script has no spaces to break on. */
private fun wrap(pen: Pen, text: String, ink: Ink, width: Float): List<String> {
    val source = text.trim()
    if (source.isEmpty()) return listOf("")

    val out = mutableListOf<String>()
    var rest = source
    while (rest.isNotEmpty()) {
        val fit = pen.breakText(rest, ink, width)
        if (fit <= 0) {
            out += rest
            break
        }
        val space = rest.lastIndexOf(' ', fit - 1)
        val take = if (space > 0 && fit < rest.length) space else fit
        if (take <= 0) {
            out += rest
            break
        }
        out += rest.substring(0, take).trimEnd()
        rest = rest.substring(take).trimStart()
    }
    return out.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
}

private const val CARD_W = 1080
private const val MAX_CARD_H = 1920f
private const val CARD_MARGIN = 72f
private const val CARD_CONTENT_W = CARD_W - CARD_MARGIN * 2
private const val HEADER_BASELINE = 132f
private const val HEADER_BOTTOM = 170f
private const val GAP_ABOVE_BODY = 20f
private const val GAP_BELOW_BODY = 20f
private const val FOOTER_THUMB = 160f
private const val FOOTER_PAD = 96f
private const val SUB_FLOOR = 24f
private const val GAP_MARKER_OFFSET = 0.18f
private val FIT_LADDER = floatArrayOf(96f, 88f, 80f, 72f, 64f, 56f, 50f, 44f, 38f, 32f)
private const val TRACKING = -0.7f / 34f
private const val SUB_TRACKING = -0.7f / 20f
private const val LEAD = 41f / 34f
private const val SUB_LEAD = 25f / 20f
private const val SUB_RATIO = 20f / 34f
private val SUB_COLOR = argb(0xD9FFFFFF)

/** The words not yet sung: the panel's UNSUNG_ALPHA. */
private const val UNSUNG_ALPHA = 0.45f

/** Lines further from the one being sung: the panel's LINE_FALLOFF_ALPHA. */
private val LINE_FALLOFF = floatArrayOf(1f, 0.8f, 0.7f, 0.58f, 0.46f)
