package com.music.laya.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.music.laya.data.canvas.CanvasArtwork
import com.music.laya.data.model.Song
import com.music.laya.playback.AutoMixArtworkTuning

/**
 * The live Canvas blend (see AutoMixCanvas.kt) for a host that lays out its own stage, the TV's:
 * the selected track's [canvas] as the host resolved it, plus the AutoMix hand-over. When both
 * songs of a mix have a clip the two play side by side and the incoming one is revealed through
 * the organic mask; otherwise the clip fades back to the host's still beneath it. Draws nothing
 * but the clip(s): the cover stays the host's own.
 */
@Composable
fun AutoMixCanvasLayer(
    song: Song,
    queue: List<Song>,
    queueIndex: Int,
    isPlaying: Boolean,
    canvas: CanvasArtwork?,
    modifier: Modifier = Modifier,
) {
    val coverEntry = remember(song.videoId, song.queueEntryId, queue, queueIndex) {
        coverOccurrence(song, queue, queueIndex)
    }
    val gate = autoMixArtworkGate()
    val canvasPair = rememberAutoMixCanvasPair(coverEntry, queue, isPlaying, false, gate)
    val pair = canvasPair.visual?.takeIf { gate.allows(it.generation) && it.contains(coverEntry) }
    val promoted = canvasPair.promoted?.takeIf { it.incoming == coverEntry }
    val hold = rememberAutoMixCanvasHold(coverEntry, gate) && pair?.holdsLive != true
    val retire = remember { Animatable(1f) }
    LaunchedEffect(hold) {
        if (hold) {
            retire.animateTo(0f, tween(AutoMixArtworkTuning.CANVAS_RETIRE_MS, easing = FastOutSlowInEasing))
        } else {
            retire.snapTo(1f)
        }
    }
    val retired by remember { derivedStateOf { retire.value <= 0f } }
    val shown = (pair?.takeIf { it.liveOutgoing }?.pinnedClip ?: promoted?.incomingClip ?: canvas)
        ?.takeUnless { hold && retired }
    var aspect by remember(shown) { mutableFloatStateOf(promoted?.incomingAspect ?: 0f) }
    SideEffect { pair?.outgoingAspect = aspect }
    val promotedLive = promoted?.takeIf { it.liveIncoming && it.incomingClip?.url == shown?.url } != null
    var rendered by remember(shown?.url) { mutableStateOf(promotedLive) }
    RememberDisplayedAutoMixCanvas(canvasPair, coverEntry, shown, rendered)
    CanvasPresentationHost(shown, pair?.incomingClip) { clip, standby ->
        CanvasArtworkPlayer(
            canvas = clip,
            isPlaying = isPlaying,
            modifier = modifier,
            organicReveal = if (standby) pair?.standbyReveal else null,
            pausedForTransition = if (standby) {
                pair?.started != true
            } else {
                pair?.takeIf { it.liveOutgoing }?.freezeOutgoing == true
            },
            onAspectRatioChanged = { if (standby) pair?.incomingAspect = it else aspect = it },
            presentationAlpha = { retire.value },
            onRenderedChanged = {
                if (standby) {
                    pair?.reportIncoming(it)
                } else {
                    rendered = it
                    pair?.reportOutgoing(it)
                }
            },
        )
    }
}
