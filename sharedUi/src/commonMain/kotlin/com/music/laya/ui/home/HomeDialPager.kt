/*
 * Home's dial pager and mood chips: ported from Melo (github.com/aarush-io/melo, GPL-3.0)
 * HomeSpeedDial.kt and MeloHeadings.kt, reworked for Laya.
 */
package com.music.laya.ui.home

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.music.laya.data.settings.AppSettings
import com.music.laya.ui.haptics.Haptic
import com.music.laya.ui.haptics.rememberHaptics

/** A dial page: its heading, then the dial. */
@Composable
fun HomeDialPager(
    title: String,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        DialHeading(title)
        content()
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
