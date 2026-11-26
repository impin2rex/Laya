/*
 * Home's dial pager and mood chips: ported from Melo (github.com/aarush-io/melo, GPL-3.0)
 * HomeSpeedDial.kt and MeloHeadings.kt, reworked for Laya.
 */
package com.music.laya.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.music.laya.data.settings.AppSettings
import com.music.laya.ui.haptics.Haptic
import com.music.laya.ui.haptics.rememberHaptics
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

private const val MIXES_PAGE = 0
private const val SPEED_DIAL_PAGE = 1

/** The slowest flick that turns a page, as a pager's own swipes have it. */
private val MIN_DIAL_FLING_VELOCITY = 400.dp
/** How far off a page counts as between two, as a share of a page. */
private const val DIAL_SETTLED_FRACTION = 0.001f
/** How many frames the dial in view has to itself before the one beside it is made up. */
private const val DIAL_BESIDE_AFTER_FRAMES = 3

/**
 * Speed Dial with the Mixes page to its left: no switch over them, the swipe is the way across.
 * Swiping right from Speed Dial's first page turns to the mixes; Speed Dial's own pages swipe
 * inside it as before. To land back on Speed Dial (Home tapped again, Home re-entered), key this
 * call so it is made afresh. The heading names the page in view; two dots under the pager say
 * which ([showDots] false leaves them off, e.g. when the Speed Dial draws its own).
 */
@Composable
fun HomeDialPager(
    speedDialTitle: String,
    mixesTitle: String?,
    speedDial: @Composable () -> Unit,
    mixes: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    onPageChange: (isMixes: Boolean) -> Unit = {},
    showDots: Boolean = true,
) {
    if (mixes == null || mixesTitle == null) {
        Column(modifier.fillMaxWidth()) {
            DialHeading(speedDialTitle)
            speedDial()
        }
        return
    }
    val state = rememberPagerState(initialPage = SPEED_DIAL_PAGE, pageCount = { 2 })
    val pageChange by rememberUpdatedState(onPageChange)
    LaunchedEffect(state) {
        snapshotFlow { state.settledPage }.collect { pageChange(it == MIXES_PAGE) }
    }
    val minFling = with(LocalDensity.current) { MIN_DIAL_FLING_VELOCITY.toPx() }
    val reversed = LocalLayoutDirection.current == LayoutDirection.Rtl
    val handoff = remember(state, minFling, reversed) { DialFlingHandoff(state, minFling, reversed) }
    // The frame that brings Home in builds the dial in view and nothing beside it; the other is
    // made up a few frames on, so coming back to Home isn't one long frame.
    var besideReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(DIAL_BESIDE_AFTER_FRAMES) { withFrameNanos { } }
        besideReady = true
    }
    Column(modifier.fillMaxWidth()) {
        Crossfade(targetState = state.targetPage == MIXES_PAGE, label = "dialHeading") { onMixes ->
            DialHeading(if (onMixes) mixesTitle else speedDialTitle)
        }
        HorizontalPager(
            state = state,
            verticalAlignment = Alignment.Top,
            key = { it },
            beyondViewportPageCount = if (besideReady) 1 else 0,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            Box(Modifier.fillMaxWidth().nestedScroll(handoff)) {
                if (page == MIXES_PAGE) mixes() else speedDial()
            }
        }
        if (showDots) DialDots(state.currentPage, 2)
    }
}

/** Melo's dial heading: headlineLarge, bold, in the page gutter. */
@Composable
private fun DialHeading(title: String) {
    Text(
        title,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
    )
}

/**
 * The page a swipe let go at [position] (pages, with the part of the next one dragged in) lands
 * on. [velocity] is the finger's along the pager, negative towards the next page. A flick faster
 * than [minFling] turns the page its way however little was dragged; a slower one settles on the
 * nearer page.
 */
internal fun dialSwipeTarget(position: Float, velocity: Float, minFling: Float, pageCount: Int): Int {
    val page = when {
        velocity <= -minFling -> ceil(position)
        velocity >= minFling -> floor(position)
        else -> round(position)
    }
    return page.toInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0))
}

/**
 * Ends a swipe that a dial inside carried over to this pager. The dials are pagers too, so every
 * swipe starts in one of them; past its first page the drag moves this pager, but the flick that
 * ends it was spent on the dial inside and reached this one as no speed at all, so the page only
 * turned when dragged more than halfway. This takes the flick first while the pager is between
 * pages and turns it, so the mixes swipe in as easily as the dial's own pages.
 */
private class DialFlingHandoff(
    private val state: PagerState,
    private val minFling: Float,
    private val reversed: Boolean,
) : NestedScrollConnection {
    override suspend fun onPreFling(available: Velocity): Velocity {
        val offset = state.currentPageOffsetFraction
        if (abs(offset) < DIAL_SETTLED_FRACTION) return Velocity.Zero
        val target = dialSwipeTarget(
            position = state.currentPage + offset,
            velocity = if (reversed) -available.x else available.x,
            minFling = minFling,
            pageCount = state.pageCount,
        )
        state.animateScrollToPage(target, animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
        return available
    }
}

/**
 * The row of mood chips over the dials. A chip is quiet glass until chosen, then takes the
 * accent at 86% with black or white ink by its lightness, dipping and springing back as it is
 * chosen. The row scrolls the chosen chip into view.
 */
@Composable
fun HomeCategoryChips(chips: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val rowState = rememberLazyListState()
    LaunchedEffect(chips, selected) {
        if (selected in chips.indices) rowState.animateScrollToItem(selected)
    }
    LazyRow(
        state = rowState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        itemsIndexed(chips) { index, title ->
            WashChip(title, index == selected) { onSelect(index) }
        }
    }
}

private val WashChipShape = RoundedCornerShape(12.dp)
private const val WashChipFill = 0.86f
private const val WashChipFillMs = 240
private const val WashChipPressScale = 0.88f

@Composable
private fun WashChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val fill by animateColorAsState(
        if (selected) accent.copy(alpha = WashChipFill) else colors.onSurface.copy(alpha = 0.10f),
        tween(WashChipFillMs), label = "washChipFill",
    )
    val ink by animateColorAsState(
        when {
            !selected -> colors.onBackground
            accent.luminance() > 0.5f -> Color.Black
            else -> Color.White
        },
        tween(WashChipFillMs), label = "washChipInk",
    )
    val scale = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(selected) }
    val reduced by AppSettings.reduceAnimation.collectAsState()
    LaunchedEffect(selected) {
        if (selected && !seen && !reduced) {
            scale.snapTo(WashChipPressScale)
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
        seen = selected
    }
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.Tab) {
                haptics.play(Haptic.Select)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .clip(WashChipShape)
                .background(fill)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = ink, fontSize = 15.sp, fontWeight = FontWeight.W600, maxLines = 1)
        }
    }
}
