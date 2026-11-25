package com.music.laya.ui.components

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.music.laya.data.model.Song
import com.music.laya.data.aifilter.AiSongs
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape

fun Modifier.thumbnailBorder(shape: Shape): Modifier = composed {
    this.border(
        width = 1.dp,
        color = if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.15f),
        shape = shape
    )
}

/** A song title with the catalogue-standard explicit badge in front of it. */
@Composable
fun ExplicitSongTitle(
    song: Song,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (song.isExplicit == true) {
            ExplicitBadge(color)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = song.title,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        val aiFlagged by AiSongs.flagged.collectAsState()
        if (song.videoId in aiFlagged) {
            Spacer(Modifier.width(6.dp))
            AiBadge(color)
        }
    }
}

/**
 * The AI-generated mark: "AI" in a hairline pill, after the title. Only says what
 * [AiSongs] already knows; the queue and the player menu ask it to look songs up.
 */
@Composable
fun AiBadge(color: Color, modifier: Modifier = Modifier) {
    Text(
        text = "AI",
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = color,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp),
    )
}

/**
 * The explicit mark, as Apple Music draws it: a filled tile with the E cut
 * out of it, so whatever sits behind — a list row, the player's artwork —
 * shows through the letter. Used everywhere a track is marked explicit.
 *
 * The cut-out is a real hole rather than the letter painted in a background
 * colour, which is why it draws into its own offscreen layer: DstOut can then
 * only erase the tile, never what's under it.
 */
@Composable
fun ExplicitBadge(color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
    val letter = remember(measurer, style) { measurer.measure("E", style) }
    // Same footprint the outlined badge had — the letter's line box plus 3dp
    // a side — so no row shifts.
    val size = with(LocalDensity.current) {
        DpSize(
            letter.size.width.toDp() + 6.dp,
            letter.size.height.toDp(),
        )
    }
    Canvas(
        modifier
            .size(size)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        drawRoundRect(color = color, cornerRadius = CornerRadius(2.dp.toPx()))
        drawText(
            textLayoutResult = letter,
            topLeft = Offset(
                (this.size.width - letter.size.width) / 2f,
                (this.size.height - letter.size.height) / 2f,
            ),
            blendMode = BlendMode.DstOut,
        )
    }
}

/**
 * What goes before a title in the player: the explicit mark, then the "AI" one; null when the
 * song carries neither. Has the playing song looked up, so its badge appears without the queue open.
 */
@Composable
fun titleBadges(song: Song, color: Color): (@Composable () -> Unit)? {
    LaunchedEffect(song.videoId) { AiSongs.request(listOf(song.videoId)) }
    val aiFlagged by AiSongs.flagged.collectAsState()
    val ai = song.videoId in aiFlagged
    val explicit = song.isExplicit == true
    if (!ai && !explicit) return null
    return {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (explicit) ExplicitBadge(color)
            if (explicit && ai) Spacer(Modifier.width(6.dp))
            if (ai) AiBadge(color)
        }
    }
}
