package com.music.laya.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.music.laya.data.model.QueueEntryIdentity
import com.music.laya.playback.AutoMixArtworkGate
import com.music.laya.playback.AutoMixArtworkTuning
import com.music.laya.playback.AutoMixVisualFeed
import com.music.laya.playback.AutoMixVisualState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/*
 * The player's surroundings during an AutoMix artwork transition: the ambient backdrop follows
 * the cover's reveal rather than its own fade, and an animated cover steps aside for the stills.
 * Only lifecycle boundaries recompose; the reveal itself is read in drawing.
 */

private fun AutoMixVisualState.dissolves(gate: AutoMixArtworkGate): Boolean =
    gate.allows(generation) && mixActive && !(swapSeconds != null && artworkWindow == null)

/**
 * The incoming track's share of the ambient backdrop, on the cover reveal's clock, while [entry]
 * is the incoming side of a dissolve — null otherwise, when the backdrop fades as it always has.
 * Held at 0 between the session's swap and the reveal's window, so the backdrop never runs ahead
 * of the artwork it surrounds.
 */
@Composable
internal fun rememberAutoMixAmbientEase(entry: QueueEntryIdentity?, gate: AutoMixArtworkGate): (() -> Float)? {
    val feed = AutoMixVisualFeed.state
    val holding by remember(entry, gate) {
        feed.map { state ->
            state?.takeIf { it.dissolves(gate) && it.artworkWindow != null && entry == it.incoming.identity }?.generation
        }.distinctUntilChanged()
    }.collectAsState(null)
    val generation = holding ?: return null
    return remember(generation) {
        {
            val live = feed.value
            if (live == null || live.generation != generation || !live.mixActive) 1f
            else AutoMixArtworkTuning.revealProgress(AutoMixRevealClock.progress(live, live.artworkProgress))
        }
    }
}

/**
 * Whether [entry]'s animated cover should stand aside for the AutoMix stills: from the mix's start
 * until the reveal has landed. The outgoing clip fades back to its cover (see
 * [AutoMixArtworkTuning.CANVAS_RETIRE_MS]) and the incoming one is withheld until the incoming
 * still is whole; the ordinary path then brings it in.
 */
@Composable
internal fun rememberAutoMixCanvasHold(entry: QueueEntryIdentity?, gate: AutoMixArtworkGate): Boolean {
    val feed = AutoMixVisualFeed.state
    val hold by remember(entry, gate) {
        feed.map { state ->
            state != null && state.dissolves(gate) && state.artworkProgress < 1f &&
                (entry == state.outgoing.identity || entry == state.incoming.identity)
        }.distinctUntilChanged()
    }.collectAsState(false)
    return hold
}
