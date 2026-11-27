/*
 * Home's dial grids (Speed Dial, Artists): ported from Melo (github.com/aarush-io/melo,
 * GPL-3.0) HomeSpeedDial.kt, reworked for Laya's cards, haptics and settings.
 */
package com.music.laya.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PushPin
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.laya.data.home.DialEntry
import com.music.laya.data.home.SpeedDial
import com.music.laya.data.model.CARD_ART_PX
import com.music.laya.data.model.ShelfItem
import com.music.laya.data.model.artworkAt
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.settings.HomeDialTileStyle
import com.music.laya.ui.components.CoverPlaceholder
import com.music.laya.ui.components.ShimmerBox
import com.music.laya.ui.components.contextClick
import com.music.laya.ui.components.longPressMenuClickable
import com.music.laya.ui.components.thumbnailBorder
import com.music.laya.ui.haptics.Haptic
import com.music.laya.ui.haptics.rememberHaptics
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Speed Dial's pages at most (melo's SPEED_DIAL_MAX_PAGES). */
const val SPEED_DIAL_MAX_PAGES = 3

/**
 * The Speed Dial: [rows] × 3 square tiles a page, up to [SPEED_DIAL_MAX_PAGES] pages with dots
 * under them. The dice owns the first page's bottom-right tile and History the last page's.
 * Tapping the dice runs [onLucky] while a light chases round it; the pick it returns is handed to
 * [onOpen] as soon as it is found, then the die lands on a face.
 */
@Composable
fun SpeedDialGrid(
    entries: List<DialEntry>,
    rows: Int,
    labels: Boolean,
    pinIcon: Boolean,
    newTags: Boolean,
    tileStyle: HomeDialTileStyle,
    luckyTile: Boolean,
    historyTile: Boolean,
    /** Recently played covers for the History tile's collage; empty leaves the plain clock. */
    historyCovers: List<String>,
    onOpen: (ShelfItem) -> Unit,
    onLongPress: (DialEntry) -> Unit,
    /** Dice roll: returns the pick to play (null = nothing to pick); the tile animates while it runs, then lands. */
    onLucky: suspend () -> ShelfItem?,
    onHistory: () -> Unit,
    historyLabel: String,
    newLabel: String,
    modifier: Modifier = Modifier,
    entering: Boolean = false,
    /** Room for the grid; a short page shrinks the tiles to no less than 80% of a third of the width. */
    maxGridHeight: Dp? = null,
    /** The faint chevron under the dots, a tap down to the feed; null hides it. */
    onScrollToFeed: (() -> Unit)? = null,
    moreLabel: String = "",
    luckyLabel: String = "",
) = DialGrid(
    entries, rows, labels, pinIcon, newTags, tileStyle,
    lucky = if (luckyTile) onLucky else null,
    history = if (historyTile) onHistory else null,
    historyLabel = historyLabel, newLabel = newLabel, maxPages = SPEED_DIAL_MAX_PAGES, historyCovers = historyCovers,
    onOpen = onOpen, onLongPress = onLongPress, modifier = modifier, entering = entering,
    maxGridHeight = maxGridHeight, onScrollToFeed = onScrollToFeed, moreLabel = moreLabel, luckyLabel = luckyLabel,
)

/** The Artists page: the same rounded tiles as Speed Dial (as melo draws it), paged with dots. */
@Composable
fun ArtistDialGrid(
    entries: List<DialEntry>,
    rows: Int,
    labels: Boolean,
    tileStyle: HomeDialTileStyle,
    onOpen: (ShelfItem) -> Unit,
    onLongPress: (DialEntry) -> Unit,
    modifier: Modifier = Modifier,
    pinIcon: Boolean = true,
    maxGridHeight: Dp? = null,
    onScrollToFeed: (() -> Unit)? = null,
    moreLabel: String = "",
) = DialGrid(
    entries, rows, labels, pinIcon = pinIcon, newTags = false, tileStyle = tileStyle, lucky = null, history = null,
    historyLabel = "", newLabel = "", maxPages = null, onOpen = onOpen, onLongPress = onLongPress,
    modifier = modifier, entering = false, maxGridHeight = maxGridHeight, onScrollToFeed = onScrollToFeed,
    moreLabel = moreLabel, luckyLabel = "",
)

/** One page of shimmering tiles, laid out exactly as a dial's first page. */
@Composable
fun SpeedDialSkeleton(rows: Int, modifier: Modifier = Modifier, maxGridHeight: Dp? = null) {
    val safeRows = rows.coerceIn(3, 4)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val m = dialMetrics(maxWidth, safeRows, maxGridHeight)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = DIAL_EDGE + m.sideInset),
            verticalArrangement = Arrangement.spacedBy(DIAL_GAP),
        ) {
            repeat(safeRows) {
                Row(horizontalArrangement = Arrangement.spacedBy(DIAL_GAP)) {
                    repeat(SpeedDial.COLUMNS) { ShimmerBox(Modifier.size(m.side).dialTileOutset(), DIAL_SHAPE) }
                }
            }
        }
    }
}

private const val SLOT_BLANK = -1
private const val SLOT_LUCKY = -2
private const val SLOT_HISTORY = -3

/** The page's 16dp gutters, 8dp between tiles, 10dp corners. */
private val DIAL_GAP = 8.dp
private val DIAL_EDGE = 16.dp
private val DIAL_SHAPE = RoundedCornerShape(10.dp)
private const val DIAL_MIN_SCALE = 0.8f
private val DIAL_DOTS_HEIGHT = 16.dp
/** Between the pager's pages: the next page's first column peeks in at the edge. */
private val DIAL_PAGE_SPACING = 12.dp
/** How far a tile reaches past its cell on each side: the tiles read a touch bigger, 4dp apart. */
private val DIAL_TILE_OUTSET = 2.dp
private val DialLabelPadding = PaddingValues(horizontal = 8.dp - DIAL_TILE_OUTSET * 1.5f, vertical = 6.dp - DIAL_TILE_OUTSET)

/**
 * Where everything on the dial goes, slot by slot: an entry's index, Lucky, History or nothing.
 * Pages fill in order, so no page but the last has a gap. The dial ends on a full page where it
 * can: the last entries are left off to make it so, unless that would drop a pin or leave
 * nothing. Lucky owns the first page's bottom-right tile and History the last page's (just
 * before Lucky on a one-page dial).
 */
internal fun speedDialSlots(
    count: Int,
    lucky: Boolean,
    history: Boolean,
    rows: Int,
    maxPages: Int?,
    isPinned: (Int) -> Boolean = { false },
): IntArray {
    val pageSize = SpeedDial.COLUMNS * rows
    val actionCount = (if (lucky) 1 else 0) + (if (history) 1 else 0)
    var itemCount = maxPages?.let { count.coerceAtMost((pageSize * it - actionCount).coerceAtLeast(0)) } ?: count
    var needed = itemCount + actionCount
    if (needed == 0) return IntArray(0)
    val spill = needed % pageSize
    if (needed > pageSize && spill != 0) {
        val trimmed = itemCount - spill
        if (trimmed > 0 && (trimmed until itemCount).none(isPinned)) {
            itemCount = trimmed
            needed = itemCount + actionCount
        }
    }
    val pageCount = (needed + pageSize - 1) / pageSize
    val slots = IntArray(pageCount * pageSize) { SLOT_BLANK }
    if (lucky) slots[pageSize - 1] = SLOT_LUCKY
    if (history) slots[if (pageCount == 1 && lucky) pageSize - 2 else slots.lastIndex] = SLOT_HISTORY
    var next = 0
    for (slot in slots.indices) {
        if (next >= itemCount) break
        if (slots[slot] == SLOT_BLANK) slots[slot] = next++
    }
    return slots
}

private class DialMetrics(val side: Dp, val gridHeight: Dp, val sideInset: Dp)

/** Square tiles a third of the content width, shrunk to no less than 80% only on a page too short for them. */
private fun dialMetrics(maxWidth: Dp, rows: Int, maxGridHeight: Dp?): DialMetrics {
    val contentWidth = maxWidth - DIAL_EDGE * 2
    val full = (contentWidth - DIAL_GAP * (SpeedDial.COLUMNS - 1)) / SpeedDial.COLUMNS
    val fit = maxGridHeight?.let { (it - DIAL_GAP * (rows - 1)) / rows }
    val side = fit?.let { minOf(full, maxOf(it, full * DIAL_MIN_SCALE)) } ?: full
    val inset = ((contentWidth - side * SpeedDial.COLUMNS - DIAL_GAP * (SpeedDial.COLUMNS - 1)) / 2).coerceAtLeast(0.dp)
    return DialMetrics(side, side * rows + DIAL_GAP * (rows - 1), inset)
}

@Composable
private fun DialGrid(
    entries: List<DialEntry>,
    rows: Int,
    labels: Boolean,
    pinIcon: Boolean,
    newTags: Boolean,
    tileStyle: HomeDialTileStyle,
    lucky: (suspend () -> ShelfItem?)?,
    history: (() -> Unit)?,
    historyLabel: String,
    historyCovers: List<String> = emptyList(),
    newLabel: String,
    maxPages: Int?,
    onOpen: (ShelfItem) -> Unit,
    onLongPress: (DialEntry) -> Unit,
    modifier: Modifier,
    entering: Boolean,
    maxGridHeight: Dp?,
    onScrollToFeed: (() -> Unit)?,
    moreLabel: String,
    luckyLabel: String,
) {
    val safeRows = rows.coerceIn(3, 4)
    val pageSize = SpeedDial.COLUMNS * safeRows
    val slots = remember(entries, lucky != null, history != null, safeRows, maxPages) {
        speedDialSlots(entries.size, lucky != null, history != null, safeRows, maxPages) { entries[it].pinned }
    }
    if (slots.isEmpty()) return
    val pageCount = slots.size / pageSize
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val reduced by AppSettings.reduceAnimation.collectAsState()
    val enter = entering && !reduced
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val m = dialMetrics(maxWidth, safeRows, maxGridHeight)
        val tile = Modifier.size(m.side).dialTileOutset()
        Column(Modifier.fillMaxWidth()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().height(m.gridHeight),
                contentPadding = PaddingValues(horizontal = DIAL_EDGE),
                pageSpacing = DIAL_PAGE_SPACING,
                verticalAlignment = Alignment.Top,
            ) { page ->
                Column(
                    Modifier.fillMaxSize().padding(horizontal = m.sideInset),
                    verticalArrangement = Arrangement.spacedBy(DIAL_GAP),
                ) {
                    repeat(safeRows) { rowIndex ->
                        Row(
                            Modifier.dialEntrance(enter && page == 0, rowIndex),
                            horizontalArrangement = Arrangement.spacedBy(DIAL_GAP),
                        ) {
                            repeat(SpeedDial.COLUMNS) { columnIndex ->
                                val slot = slots[page * pageSize + rowIndex * SpeedDial.COLUMNS + columnIndex]
                                when {
                                    slot >= 0 -> {
                                        val entry = entries[slot]
                                        DialTile(
                                            entry, labels, pinIcon, newTags, newLabel, tileStyle,
                                            stagger = rowIndex + columnIndex, reduced = reduced, modifier = tile,
                                            onClick = { onOpen(entry.item) }, onLongPress = { onLongPress(entry) },
                                        )
                                    }
                                    slot == SLOT_LUCKY && lucky != null ->
                                        LuckyDiceTile(tile, tileStyle, luckyLabel, reduced, lucky, onOpen)
                                    slot == SLOT_HISTORY && history != null ->
                                        HistoryDialTile(tile, tileStyle, labels, historyLabel, historyCovers, history)
                                    else -> Spacer(tile)
                                }
                            }
                        }
                    }
                }
            }
            if (pageCount > 1) DialDots(pagerState.currentPage, pageCount)
            if (onScrollToFeed != null) DialMoreHint(onScrollToFeed, moreLabel, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** Lays the tile out [DIAL_TILE_OUTSET] past its cell on every side, without moving the grid. */
private fun Modifier.dialTileOutset(): Modifier = layout { measurable, constraints ->
    val grow = DIAL_TILE_OUTSET.roundToPx()
    val placeable = measurable.measure(constraints.offset(2 * grow, 2 * grow))
    layout((placeable.width - 2 * grow).coerceAtLeast(0), (placeable.height - 2 * grow).coerceAtLeast(0)) {
        placeable.place(-grow, -grow)
    }
}

/** Melo's press: the tile dips to 96% under the finger and springs back. Off with reduced motion. */
@Composable
private fun Modifier.dialPress(reduced: Boolean): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed && !reduced) 0.96f else 1f,
        spring(dampingRatio = 0.8f, stiffness = 650f), label = "dialPress",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
}

/** A subtle light-catching rim drawn over the art: [glass] is the brighter, thicker one. */
private fun Modifier.artworkRim(shape: Shape, glass: Boolean): Modifier = drawWithCache {
    val width = if (glass) 1.25.dp.toPx() else 0.75.dp.toPx()
    val outline = shape.createOutline(size.copy(width = size.width - width, height = size.height - width), layoutDirection, this)
    val rim = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (glass) 0.48f else 0.22f),
        0.22f to Color.White.copy(alpha = 0.16f),
        0.65f to Color.White.copy(alpha = 0.06f),
        1f to Color.White.copy(alpha = 0.16f),
    )
    onDrawWithContent {
        drawContent()
        translate(width / 2f, width / 2f) { drawOutline(outline, rim, style = Stroke(width)) }
    }
}

private fun Modifier.dialRim(style: HomeDialTileStyle): Modifier = when (style) {
    HomeDialTileStyle.EDGE -> artworkRim(DIAL_SHAPE, glass = false)
    HomeDialTileStyle.GLASS -> artworkRim(DIAL_SHAPE, glass = true)
    HomeDialTileStyle.PLAIN -> thumbnailBorder(DIAL_SHAPE)
}

@Composable
private fun dialLabelStyle() = MaterialTheme.typography.labelLarge.let { it.copy(fontSize = (it.fontSize.value - 2f).sp) }

@Composable
private fun isDark() = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** What a tile's art animates on: the tags and badges change in place. */
private data class TileVisual(val title: String, val artworkUrl: String?)

@Composable
private fun DialTile(
    entry: DialEntry,
    showLabel: Boolean,
    showPinIcon: Boolean,
    showNewTag: Boolean,
    newLabel: String,
    tileStyle: HomeDialTileStyle,
    /** The tile's diagonal (row + column), so new art sweeps across the grid corner to corner. */
    stagger: Int,
    reduced: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val item = entry.item
    val foreground = if (isDark()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary
    val isNew = showNewTag && entry.isNewRelease
    Box(
        modifier
            .dialPress(reduced)
            .clip(DIAL_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (tileStyle == HomeDialTileStyle.GLASS) Modifier else Modifier.thumbnailBorder(DIAL_SHAPE))
            // combinedClickable under it gives the long press its own haptic.
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress)
            .semantics {
                contentDescription = listOf(item.title, item.subtitle).filter(String::isNotBlank).joinToString(", ")
            },
    ) {
        AnimatedContent(
            targetState = TileVisual(item.title, item.thumbnailUrl.artworkAt(CARD_ART_PX)),
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val delay = if (reduced) 0 else stagger * 70
                val enter = if (reduced) fadeIn(tween(160)) else {
                    val timing = tween<Float>(380, delay, FastOutSlowInEasing)
                    fadeIn(timing) + scaleIn(timing, initialScale = 0.94f) +
                        slideIn(tween(380, delay, FastOutSlowInEasing)) { IntOffset(-it.width / 10, -it.height / 10) }
                }
                val exit = if (reduced) fadeOut(tween(120)) else {
                    val timing = tween<Float>(170, delay, FastOutSlowInEasing)
                    fadeOut(timing) + scaleOut(timing, targetScale = 0.98f)
                }
                enter togetherWith exit
            },
            label = "dialTile",
        ) { shown ->
            Box(Modifier.fillMaxSize()) {
                CoverPlaceholder(Icons.Rounded.MusicNote, Modifier.fillMaxSize())
                AsyncImage(
                    model = shown.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (showLabel) {
                    Box(
                        Modifier.fillMaxWidth().height(56.dp).align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
                    )
                    Text(
                        shown.title,
                        modifier = Modifier.align(Alignment.BottomStart).padding(DialLabelPadding),
                        style = dialLabelStyle(),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.pinned && showPinIcon) {
                    Icon(
                        Icons.Rounded.PushPin, null,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(14.dp),
                        tint = Color.White,
                    )
                }
                if (isNew) {
                    Text(
                        newLabel,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = SCRIM))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = foreground,
                        maxLines = 1,
                    )
                }
                if (tileStyle != HomeDialTileStyle.PLAIN) Box(Modifier.fillMaxSize().dialRim(tileStyle))
            }
        }
    }
}

private const val SCRIM = 0.55f

/**
 * The History tile: the latest [covers] (up to four) as a collage under a dark scrim, the history
 * clock in the middle and, with labels on, its name at the bottom. No covers: a dark see-through
 * tile with a larger clock.
 */
@Composable
private fun HistoryDialTile(
    modifier: Modifier,
    tileStyle: HomeDialTileStyle,
    showLabel: Boolean,
    label: String,
    covers: List<String>,
    onClick: () -> Unit,
) {
    val reduced by AppSettings.reduceAnimation.collectAsState()
    Box(
        modifier
            .dialPress(reduced)
            .clip(DIAL_SHAPE)
            .background(Color(0xFF141414).copy(alpha = 0.5f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        if (covers.isNotEmpty()) {
            CoverCollage(covers, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = SCRIM)))
        }
        Icon(
            Icons.Rounded.History, null,
            modifier = Modifier.align(Alignment.Center).fillMaxSize(if (covers.isEmpty()) 0.36f else 0.28f),
            tint = Color.White.copy(alpha = 0.85f),
        )
        if (showLabel) {
            Text(
                label,
                modifier = Modifier.align(Alignment.BottomStart).padding(DialLabelPadding),
                style = dialLabelStyle(),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.fillMaxSize().dialRim(tileStyle))
    }
}

/** One cover fills the tile; two sit side by side; three are one tall and two stacked; four a 2×2. */
@Composable
private fun CoverCollage(covers: List<String>, modifier: Modifier) {
    val columns = if (covers.size <= 2) covers.map { listOf(it) } else listOf(covers.take(covers.size - 2), covers.takeLast(2))
    Row(modifier) {
        columns.forEach { column ->
            Column(Modifier.weight(1f).fillMaxHeight()) {
                column.forEach { url ->
                    AsyncImage(
                        model = url.artworkAt(CARD_ART_PX),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * A die of nine squares on a dark ground (light in the light theme) with soft washes of colour.
 * A tap sets a light chasing round its edge at one even speed, a tick per square, until the pick
 * is found (at least 450ms, at most 12s); then it comes to rest and lands on a face with a firmer beat.
 */
@Composable
private fun LuckyDiceTile(
    modifier: Modifier,
    tileStyle: HomeDialTileStyle,
    label: String,
    reduced: Boolean,
    onRoll: suspend () -> ShelfItem?,
    onPick: (ShelfItem) -> Unit,
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var face by remember { mutableIntStateOf(3) }
    val spin = remember { Animatable(0f) }
    val settle = remember { Animatable(1f) }
    var rolling by remember { mutableStateOf(false) }
    val look = if (isDark()) DICE_DARK else DICE_LIGHT
    Box(
        modifier
            .dialPress(reduced)
            .clip(DIAL_SHAPE)
            .background(look.ground)
            .clickable(role = Role.Button) {
                if (rolling) return@clickable
                rolling = true
                scope.launch {
                    val found = async { runCatching { onRoll() }.getOrNull() }
                    try {
                        if (reduced) {
                            found.await()?.let(onPick)
                            face = (1..6).random()
                            haptics.play(Haptic.Select)
                            return@launch
                        }
                        spin.snapTo(0f)
                        var square = 0
                        val tick: Animatable<Float, AnimationVector1D>.() -> Unit = {
                            val now = value.toInt()
                            if (now != square) {
                                square = now
                                haptics.play(Haptic.Tick)
                            }
                        }
                        coroutineScope {
                            val chase = launch {
                                spin.animateTo(
                                    DICE_WAIT_MAX_MS * DICE_SPEED,
                                    tween(DICE_WAIT_MAX_MS.toInt(), easing = LinearEasing),
                                    block = tick,
                                )
                            }
                            settle.animateTo(0f, tween(DICE_FADE_MS))
                            delay(DICE_MIN_MS - DICE_FADE_MS)
                            val pick = withTimeoutOrNull(DICE_WAIT_MAX_MS - DICE_MIN_MS) { found.await() }
                            if (!found.isCompleted) found.cancel()
                            pick?.let(onPick)
                            chase.cancelAndJoin()
                        }
                        // Out of that speed to a stop with no step in it: the curve starts at twice its
                        // mean speed, so its time makes that the speed the light had.
                        spin.animateTo(
                            spin.value + DICE_REST_STEPS,
                            tween((2 * DICE_REST_STEPS / DICE_SPEED).toInt(), easing = DICE_REST_EASING),
                            block = tick,
                        )
                        face = (1..6).random()
                        haptics.play(Haptic.Select)
                        settle.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium))
                    } finally {
                        rolling = false
                    }
                }
            }
            .semantics {
                contentDescription = label
                if (rolling) stateDescription = "…"
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Crimson in from the upper left, violet from the lower right, a little cyan at the upper right.
            DICE_WASHES.forEach { wash ->
                drawRect(
                    Brush.radialGradient(
                        listOf(wash.color.copy(alpha = wash.alpha * look.washStrength), wash.color.copy(alpha = 0f)),
                        center = Offset(w * wash.x, h * wash.y),
                        radius = size.maxDimension * wash.radius,
                    ),
                )
            }
            // Three squares of 80 with gaps of 30 on a 512 ground, dead centre.
            val unit = size.minDimension / 512f
            val cellSide = 80f * unit
            val gap = 30f * unit
            val gridSide = cellSide * 3 + gap * 2
            val left = (w - gridSide) / 2f
            val top = (h - gridSide) / 2f
            val head = spin.value % DICE_RING.size
            val toFace = settle.value
            val pips = DICE_FACES[face - 1]
            for (i in 0 until 9) {
                val ring = DICE_RING.indexOf(i)
                val spinner = if (ring < 0) 0f else {
                    val behind = ((head - ring) % DICE_RING.size + DICE_RING.size) % DICE_RING.size
                    (1f - behind / DICE_TAIL).coerceIn(0f, 1f)
                }
                val pip = if (i in pips) 1f else 0f
                val level = (spinner + (pip - spinner) * toFace).coerceIn(0f, 1f)
                // A lit square swells a touch with the landing's overshoot.
                val pop = if (pip > 0f && toFace > 1f) 1f + (toFace - 1f) * 0.6f else 1f
                val side = cellSide * pop
                val center = Offset(
                    left + (i % 3) * (cellSide + gap) + cellSide / 2f,
                    top + (i / 3) * (cellSide + gap) + cellSide / 2f,
                )
                if (level > 0f) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(look.lit.copy(alpha = look.glow * level), look.lit.copy(alpha = 0f)),
                            center = center,
                            radius = side * 1.05f,
                        ),
                        radius = side * 1.05f,
                        center = center,
                    )
                }
                drawRoundRect(
                    color = lerp(look.unlit, look.lit, level),
                    topLeft = Offset(center.x - side / 2f, center.y - side / 2f),
                    size = Size(side, side),
                    cornerRadius = CornerRadius(side * 0.08f),
                )
            }
        }
        Box(Modifier.fillMaxSize().dialRim(tileStyle))
    }
}

private class DiceWash(val color: Color, val x: Float, val y: Float, val radius: Float, val alpha: Float)

private val DICE_WASHES = listOf(
    DiceWash(Color(0xFFFA5A72), 0f, 0.15f, 0.62f, 0.26f),
    DiceWash(Color(0xFF8B5CF6), 1f, 0.85f, 0.68f, 0.26f),
    DiceWash(Color(0xFF38BDF8), 1f, 0.1f, 0.5f, 0.18f),
)

private class DiceLook(val ground: Color, val unlit: Color, val lit: Color, val glow: Float, val washStrength: Float)

private val DICE_DARK = DiceLook(Color(0xFF141414), Color(0xFF262626), Color.White, 0.45f, 1f)
private val DICE_LIGHT = DiceLook(Color(0xFFF4F4F6), Color(0xFFDCDCE2), Color(0xFF161618), 0.18f, 1.25f)

/** The squares round the edge in the order the light goes, and each face's lit squares. */
private val DICE_RING = intArrayOf(0, 1, 2, 5, 8, 7, 6, 3)
private val DICE_FACES = listOf(
    setOf(4),
    setOf(2, 6),
    setOf(0, 4, 8),
    setOf(0, 2, 6, 8),
    setOf(0, 2, 4, 6, 8),
    setOf(0, 2, 3, 5, 6, 8),
)

/** The light's speed in squares a millisecond (once round in 720ms), its least time, its rest and its tail. */
private const val DICE_SPEED = 8f / 720f
private const val DICE_MIN_MS = 450L
private const val DICE_REST_STEPS = 2f
private val DICE_REST_EASING = Easing { 1f - (1f - it) * (1f - it) }
private const val DICE_TAIL = 3.2f
private const val DICE_FADE_MS = 140
/** The longest the die waits for the pick, so it can never be left turning. */
private const val DICE_WAIT_MAX_MS = 12_000L

/** A dial row rising into place, [row] by row, when [entering]; nothing otherwise. */
@Composable
private fun Modifier.dialEntrance(entering: Boolean, row: Int): Modifier {
    if (!entering) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(row * 70L)
        progress.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        val scale = 0.92f + 0.08f * p
        scaleX = scale
        scaleY = scale
        translationY = (1f - p) * 24.dp.toPx()
    }
}

@Composable
internal fun DialDots(current: Int, count: Int) {
    val color = MaterialTheme.colorScheme.onBackground
    Row(
        Modifier.fillMaxWidth().height(DIAL_DOTS_HEIGHT).padding(top = 10.dp)
            .semantics { contentDescription = "${current + 1} / $count" },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            Box(Modifier.size(6.dp).clip(CircleShape).background(color.copy(alpha = if (index == current) 0.9f else 0.3f)))
        }
    }
}

/** The faint chevron under the page dots: there is more below, and a tap down to the feed. */
@Composable
private fun DialMoreHint(onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    Box(
        modifier.padding(top = 4.dp).size(width = 56.dp, height = 22.dp).clip(RoundedCornerShape(11.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.KeyboardArrowDown, null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
            modifier = Modifier.size(22.dp),
        )
    }
}
