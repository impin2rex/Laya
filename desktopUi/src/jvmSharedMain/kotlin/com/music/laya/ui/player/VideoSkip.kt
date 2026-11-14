package com.music.laya.ui.player

import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.laya.data.settings.VideoScale
import com.music.laya.sharedui.resources.Res
import com.music.laya.sharedui.resources.video_scale_best_fit
import com.music.laya.sharedui.resources.video_scale_fill
import com.music.laya.sharedui.resources.video_scale_fit_screen
import com.music.laya.sharedui.resources.video_skip_seconds
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** One skip's worth of the playhead, as VLC and every phone video player step it. */
private const val SKIP_SECONDS = 10

/** How long the skip's mark stays after the last one; another on the same side meanwhile adds to it. */
private const val SKIP_FEEDBACK_MS = 800L

/** What the mark on the picture says: which side, and how far the run of skips has gone. */
internal data class SkipFeedback(val direction: Int, val seconds: Int, val id: Int)

/**
 * Ten seconds back or forward on a music video — a double tap on either half of the picture, or
 * the arrow keys on a computer. Skips in a run on the same side add up, 10, 20, 30 seconds, and are
 * counted from where the run began rather than from the playhead, which lags each seek by however
 * long the player takes to report it.
 */
@Stable
internal class VideoSkip(
    private val position: () -> Long,
    private val duration: () -> Long,
    private val seek: (Long) -> Unit,
) {
    var feedback by mutableStateOf<SkipFeedback?>(null)
        private set
    private var base = 0L

    /** [direction] -1 for back, 1 for forward. */
    fun skip(direction: Int) {
        val last = feedback
        val run = last != null && last.direction == direction
        if (!run) base = position()
        val seconds = if (run) last!!.seconds + SKIP_SECONDS else SKIP_SECONDS
        feedback = SkipFeedback(direction, seconds, (last?.id ?: 0) + 1)
        val end = duration().takeIf { it > 0L } ?: Long.MAX_VALUE
        seek((base + direction * seconds * 1_000L).coerceIn(0L, end))
    }

    internal fun settle(id: Int) {
        if (feedback?.id == id) feedback = null
    }
}

@Composable
internal fun rememberVideoSkip(position: () -> Long, duration: () -> Long, seek: (Long) -> Unit): VideoSkip {
    val currentPosition by rememberUpdatedState(position)
    val currentDuration by rememberUpdatedState(duration)
    val currentSeek by rememberUpdatedState(seek)
    return remember { VideoSkip({ currentPosition() }, { currentDuration() }, { currentSeek(it) }) }
}

/**
 * VLC's mark for a skip: the half of the picture it went on lit by a soft half-moon from that edge,
 * three arrows running the way it went, and how far the run has gone.
 */
@Composable
internal fun VideoSkipOverlay(skip: VideoSkip, modifier: Modifier = Modifier) {
    val feedback = skip.feedback
    LaunchedEffect(feedback?.id) {
        val shown = feedback ?: return@LaunchedEffect
        delay(SKIP_FEEDBACK_MS)
        skip.settle(shown.id)
    }
    // Held through the fade-out, after the feedback itself has gone.
    var last by remember { mutableStateOf<SkipFeedback?>(null) }
    if (feedback != null) last = feedback
    Box(modifier.fillMaxSize()) {
        for (direction in listOf(-1, 1)) {
            AnimatedVisibility(
                visible = feedback?.direction == direction,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(300)),
                modifier = Modifier
                    .align(if (direction < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.38f),
            ) {
                val shape = if (direction < 0) {
                    RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                } else {
                    RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
                }
                Box(
                    Modifier.fillMaxSize().clip(shape).background(Color.White.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SkipArrows(direction)
                        Text(
                            text = stringResource(Res.string.video_skip_seconds, last?.seconds ?: SKIP_SECONDS),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Three arrows lighting one after another, the way the skip went. */
@Composable
private fun SkipArrows(direction: Int) {
    val sweep by rememberInfiniteTransition(label = "skipArrows").animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Restart),
        label = "skipSweep",
    )
    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        for (i in 0 until 3) {
            // Lit in the order the skip runs: right to left going back.
            val step = if (direction > 0) i else 2 - i
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        alpha = if (sweep.toInt() == step) 1f else 0.35f
                        if (direction < 0) scaleX = -1f
                    },
            )
        }
    }
}

/** The name the aspect button shows as it changes, VLC's way. */
internal val VideoScale.label: StringResource
    get() = when (this) {
        VideoScale.BEST_FIT -> Res.string.video_scale_best_fit
        VideoScale.FIT_SCREEN -> Res.string.video_scale_fit_screen
        VideoScale.FILL -> Res.string.video_scale_fill
    }

/** [scale]'s name in the middle of the picture for a moment after it changes — not on first showing. */
@Composable
internal fun VideoScaleFlash(scale: VideoScale, modifier: Modifier = Modifier) {
    var seen by remember { mutableStateOf(scale) }
    var shown by remember { mutableStateOf<VideoScale?>(null) }
    LaunchedEffect(scale) {
        if (scale == seen) return@LaunchedEffect
        seen = scale
        shown = scale
        delay(900)
        shown = null
    }
    var last by remember { mutableStateOf(scale) }
    shown?.let { last = it }
    AnimatedVisibility(visible = shown != null, enter = fadeIn(tween(120)), exit = fadeOut(tween(300)), modifier = modifier) {
        Text(
            text = stringResource(last.label),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * The keyboard's way into a music video, VLC's keys: ← and → skip ten seconds, A changes the
 * aspect. The app hears the keys — they reach its window before anything in the player could take
 * focus — and hands them here, for whichever of the player's video views is up to act on and mark.
 */
object VideoKeys {
    internal val skips = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 8)

    /** [direction] -1 for back, 1 for forward. */
    fun skip(direction: Int) {
        skips.tryEmit(direction)
    }

    fun nextScale() = PlayerSettings.setVideoScale(PlayerSettings.videoScale.value.next)
}

/**
 * Keyboard focus on the picture while it is up. A window with nothing focused hands its keys to no
 * one, so without it the app's video keys — ←, →, A, F, Esc — went unheard; and an arrow's own
 * press is kept here, where Compose would otherwise move focus off the picture with it.
 */
@Composable
internal fun Modifier.videoKeyFocus(): Modifier {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    return this
        .focusRequester(focus)
        .onKeyEvent {
            it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                (it.key == androidx.compose.ui.input.key.Key.DirectionLeft || it.key == androidx.compose.ui.input.key.Key.DirectionRight)
        }
        .focusable()
}
