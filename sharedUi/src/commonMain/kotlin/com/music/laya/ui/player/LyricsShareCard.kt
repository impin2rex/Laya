package com.music.laya.ui.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
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
): ImageBitmap {
    val cover = card.artworkUrl?.let { loadArtwork(context, it) }

    val headTop = HEADER_BOTTOM + GAP_ABOVE_BODY
    val frameRoom = MAX_CARD_H - FOOTER_THUMB - FOOTER_PAD - GAP_BELOW_BODY - headTop
    val plan = fittestPlan(pen, card.lines, frameRoom)
    val spare = if (story) (frameRoom - plan.content).coerceAtLeast(0f) else 0f
    val bodyTop = headTop + spare / 2
    val bodyBottom = bodyTop + plan.content
    val cardH = bodyBottom + spare / 2 + GAP_BELOW_BODY + FOOTER_THUMB + FOOTER_PAD

    return drawBitmap(CARD_W, cardH.roundToInt()) {
        backdrop(cover, 0f, CARD_W.toFloat(), cardH)
        brandMark(pen, logo, CARD_W - CARD_MARGIN, HEADER_BASELINE)

        var y = bodyTop
        for (row in plan.rows) {
            val baseline = y + row.gapBefore + pen.ascent(row.ink) +
                if (row.isGap) row.ink.size * GAP_MARKER_OFFSET else 0f
            val x = if (row.centered) CARD_W / 2f else CARD_MARGIN
            text(pen, row.text, x, baseline, row.ink)
            y += row.gapBefore + row.height
        }

        val thumbY = cardH - FOOTER_PAD - FOOTER_THUMB
        artwork(cover, card.song.title, CARD_MARGIN, thumbY, FOOTER_THUMB, false)
        val textX = CARD_MARGIN + FOOTER_THUMB + 34f
        val room = CARD_W - CARD_MARGIN - textX
        val title = pen.body(50f, argb(0xFFFFFFFF), bold = true)
        text(pen, pen.ellipsised(card.song.title, title, room), textX, thumbY + 74f, title)
        val artist = pen.body(42f, argb(0x99FFFFFF))
        text(pen, pen.ellipsised(card.song.artist, artist, room), textX, thumbY + 132f, artist)
    }
}

private class Row(
    val text: String,
    val ink: Ink,
    val height: Float,
    val gapBefore: Float,
    val centered: Boolean = false,
    val isGap: Boolean = false,
)

private class Plan(val rows: List<Row>, val ascent: Float) {
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

    val rows = mutableListOf<Row>()
    lines.forEachIndexed { index, line ->
        if (line.isGap) {
            val marker = pen.body(size * 0.65f, argb(0x66FFFFFF))
            rows += Row("(...)", marker, pen.ascent(marker) + pen.descent(marker), 0f, isGap = true)
            return@forEachIndexed
        }
        var firstOfLine = true
        fun add(text: String, ink: Ink, height: Float) {
            val gap = when {
                rows.isEmpty() -> 0f
                firstOfLine && index > 0 -> size * 0.55f
                else -> 0f
            }
            rows += Row(text, ink, height, gap)
            firstOfLine = false
        }
        wrap(pen, line.text, primary, CARD_CONTENT_W).forEach { add(it, primary, size * LEAD) }
        val sub = line.subText
        if (!sub.isNullOrBlank() && sub != line.text) {
            wrap(pen, sub, secondary, CARD_CONTENT_W).forEach { add(it, secondary, subSize * SUB_LEAD) }
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
