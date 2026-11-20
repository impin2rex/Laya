package com.music.laya.ui.screens

import com.music.laya.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import com.music.laya.data.model.HomeShelf
import com.music.laya.data.model.MoodGenre
import com.music.laya.data.model.MoodGenreSection
import com.music.laya.data.model.ShelfItem
import com.music.laya.data.model.UiState
import com.music.laya.ui.components.MessageState
import com.music.laya.ui.components.PAGE_GUTTER
import com.music.laya.ui.components.PullToRefresh
import com.music.laya.ui.components.ShimmerBox
import com.music.laya.ui.components.feedSkeleton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    state: UiState<List<MoodGenreSection>>,
    listState: LazyListState,
    onCategoryClick: (MoodGenre) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** The big "Explore" heading; the desktop's pages carry none. */
    showTitle: Boolean = true,
) {
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = moodColumns(maxWidth)
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (showTitle) {
                    item {
                        Text(
                            text = stringResource(Res.string.explore),
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            // 14dp under the title, the same gap Library leaves
                            // above its first card (8dp title + 6dp Replay row).
                            modifier = Modifier.padding(
                                start = PAGE_GUTTER,
                                end = PAGE_GUTTER,
                                top = 8.dp,
                                bottom = 14.dp,
                            ),
                        )
                    }
                }
                when (state) {
                    UiState.Loading -> item { ExploreSkeletonRows(columns) }
                    is UiState.Error -> item {
                        MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                    }
                    is UiState.Success -> {
                        // A category the server lists twice is drawn only once.
                        val seen = mutableSetOf<Pair<String, String?>>()
                        state.data.forEach { section ->
                            val cards = section.items.filter { seen.add(it.browseId to it.params) }
                            if (cards.isEmpty()) return@forEach
                            item(key = "header|${section.title}") { MoodSectionHeader(section.title) }
                            items(cards.chunked(columns), key = { row -> row.first().let { "${it.browseId}|${it.params}" } }) { row ->
                                MoodGenreRow(row = row, columns = columns, onCategoryClick = onCategoryClick)
                            }
                            item(key = "gap|${section.title}") { Spacer(Modifier.height(MOOD_SECTION_GAP)) }
                        }
                    }
                }
            }
        }
    }
}

/** The section's name over a hairline rule, as Spotify's browse page sets it. */
@Composable
private fun MoodSectionHeader(title: String) {
    Column(Modifier.padding(horizontal = PAGE_GUTTER).padding(bottom = 14.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = .1f)),
        )
    }
}

@Composable
private fun MoodGenreRow(
    row: List<MoodGenre>,
    columns: Int,
    onCategoryClick: (MoodGenre) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER)
            .padding(bottom = MOOD_SPACING),
    ) {
        row.forEach { item ->
            MoodGenreCard(
                item = item,
                onClick = { onCategoryClick(item) },
                modifier = Modifier.weight(1f),
            )
        }
        // Short rows keep their cards the size of a full row's.
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun MoodGenreCard(
    item: MoodGenre,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MoodGenreSleeve(
        item = item,
        modifier = modifier
            .aspectRatio(MOOD_CARD_ASPECT)
            .clip(MOOD_CARD_SHAPE)
            .clickable(onClick = onClick),
    )
}

/**
 * A tile washed in the category's colour, its name on the left and two of its
 * playlist covers fanned out on the right, the second tilted behind the first.
 *
 * Only the artwork: the caller sizes, clips and makes it clickable, which is
 * how the TV app wraps the same sleeve in its own focus treatment.
 */
@Composable
fun MoodGenreSleeve(
    item: MoodGenre,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val tint = remember(item.stripeColor, item.title) { moodTint(item) }
    BoxWithConstraints(
        modifier.background(
            Brush.linearGradient(listOf(lerp(MOOD_TILE, tint, .55f), lerp(MOOD_TILE, tint, .16f))),
        ),
    ) {
        // Cover sizes and offsets are pitched for a 76dp-tall tile and scale with it.
        val scale = maxHeight / 76.dp
        val coversLeft = maxWidth - (MOOD_COVERS_END + MOOD_COVERS_WIDTH) * scale
        // Where the title's last letter lands, in px from the card's left edge.
        var titleEnd by remember(item.title) { mutableStateOf(0f) }
        val density = LocalDensity.current
        // A cover the title runs over fades away, so the name stays readable.
        fun covered(cover: MoodCoverSpec) = titleEnd > with(density) { (coversLeft + cover.left * scale).toPx() }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = MOOD_COVERS_END * scale)
                .size(width = MOOD_COVERS_WIDTH * scale, height = maxHeight),
        ) {
            MoodCover(item.backThumbnailUrl, BACK_COVER, scale, faded = covered(BACK_COVER))
            MoodCover(item.thumbnailUrl, FRONT_COVER, scale, faded = covered(FRONT_COVER))
        }
        Text(
            text = item.title,
            style = titleStyle.copy(
                shadow = Shadow(Color.Black.copy(alpha = .35f), offset = Offset(0f, 1f), blurRadius = 6f),
            ),
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout ->
                val right = (0 until layout.lineCount).maxOf(layout::getLineRight)
                titleEnd = with(density) { MOOD_TITLE_PADDING.toPx() } + right
            },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = MOOD_TITLE_PADDING),
        )
    }
}

@Composable
private fun BoxScope.MoodCover(url: String?, spec: MoodCoverSpec, scale: Float, faded: Boolean) {
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .offset(spec.x * scale, spec.y * scale)
            .size(spec.size * scale)
            .graphicsLayer {
                rotationZ = spec.rotation
                alpha = if (faded) MOOD_COVER_FADED_ALPHA else 1f
            }
            .clip(MOOD_COVER_SHAPE)
            .background(Color.White.copy(alpha = .08f)),
    ) {
        url?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * YouTube's button colour pulled toward a mid grey, so neon and near-black
 * hues alike land as the muted washes Spotify's browse tiles use.
 */
private fun moodTint(item: MoodGenre): Color {
    val base = item.stripeColor?.let { Color(it.toInt()) } ?: fallbackMoodColor(item.title)
    fun lift(c: Float) = (c + (170f / 255f - c) * .45f).coerceIn(40f / 255f, 220f / 255f)
    return Color(lift(base.red), lift(base.green), lift(base.blue))
}

/** For a button that came without YouTube's colour: muted, never neon. */
private fun fallbackMoodColor(title: String): Color = when ((title.hashCode() and Int.MAX_VALUE) % 8) {
    0 -> Color(0xFFCC6A55)
    1 -> Color(0xFFC07A92)
    2 -> Color(0xFF9C8AC0)
    3 -> Color(0xFF8090C8)
    4 -> Color(0xFFD0A060)
    5 -> Color(0xFF6A88B0)
    6 -> Color(0xFF7AAED0)
    else -> Color(0xFF86B890)
}

/** The dark ground the tint is washed over. */
private val MOOD_TILE = Color(0xFF121214)

/** Gap between mood cards, in both directions. */
private val MOOD_SPACING = 10.dp

/** Space after a section's last row, before the next header. */
private val MOOD_SECTION_GAP = 22.dp

private val MOOD_CARD_SHAPE = RoundedCornerShape(14.dp)

private val MOOD_COVER_SHAPE = RoundedCornerShape(8.dp)

private val MOOD_TITLE_PADDING = 14.dp

/** How much of a cover still shows once the title runs over it. */
private const val MOOD_COVER_FADED_ALPHA = .22f

/** One fanned cover, in dp at a 76dp-tall tile, placed in the covers' box. */
private class MoodCoverSpec(val size: Dp, val rotation: Float, val x: Dp, val y: Dp) {
    /** Left edge of the tilted square's bounds: its corner swings out past [x]. */
    val left: Dp = x + size / 2 - size / 2 * (abs(cos(rotation * PI / 180)) + abs(sin(rotation * PI / 180))).toFloat()
}

private val BACK_COVER = MoodCoverSpec(size = 44.dp, rotation = -24f, x = (-2).dp, y = 2.dp)
private val FRONT_COVER = MoodCoverSpec(size = 48.dp, rotation = -6f, x = 28.dp, y = 13.dp)

/** The covers' box at a 76dp-tall tile, and its inset from the card's right edge. */
private val MOOD_COVERS_WIDTH = 84.dp
private val MOOD_COVERS_END = 2.dp

/** Width over height; a two-column phone row lands at about 76dp tall. */
const val MOOD_CARD_ASPECT = 2.1f

/**
 * The narrowest a mood card is let get before the row drops a column.
 *
 * Pitched so every phone still gets its two (the widest, 448dp, has room for
 * 1.8 of these), while a tablet or a window lays out as many as fit rather
 * than two cards each half a screen wide.
 */
private val MOOD_MIN_CARD_WIDTH = 220.dp

private const val MOOD_MAX_COLUMNS = 6

private fun moodColumns(available: Dp): Int {
    val row = available - PAGE_GUTTER * 2
    return ((row + MOOD_SPACING) / (MOOD_MIN_CARD_WIDTH + MOOD_SPACING)).toInt().coerceIn(2, MOOD_MAX_COLUMNS)
}

@Composable
private fun ExploreSkeletonRows(columns: Int) {
    Column(Modifier.padding(horizontal = PAGE_GUTTER)) {
        repeat(6) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
                modifier = Modifier.padding(bottom = MOOD_SPACING),
            ) {
                repeat(columns) {
                    ShimmerBox(Modifier.weight(1f).aspectRatio(MOOD_CARD_ASPECT), MOOD_CARD_SHAPE)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodGenrePlaylistsScreen(
    title: String,
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
        }
        when (state) {
            UiState.Loading -> feedSkeleton()
            is UiState.Error -> item {
                MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
            }
            is UiState.Success -> items(state.data, key = { it.title }) { shelf ->
                Shelf(shelf = shelf, onItemClick = onItemClick)
            }
        }
    }
}
