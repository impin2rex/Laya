package com.music.laya.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.laya.data.canvas.CanvasArtwork
import com.music.laya.data.canvas.CanvasSource
import com.music.laya.data.model.QueueEntryIdentity
import com.music.laya.data.model.Song
import com.music.laya.playback.AutoMixArtworkGate
import com.music.laya.playback.AutoMixArtworkTuning
import com.music.laya.playback.AutoMixVisualFeed
import com.music.laya.playback.AutoMixVisualPhase
import com.music.laya.playback.AutoMixVisualState
import com.music.laya.ui.rememberIsForeground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Live Canvas blend (BitChord v1.10): when both songs of a mix have an animated Canvas, the two
 * videos play side by side in one slot and the incoming one is revealed over the outgoing one
 * through the same organic mask as the stills. Anything else — a Spotify Canvas, a clip that is
 * not decoding by the time the reveal starts, a song with a still — keeps the plain path: the
 * clip fades back to its cover and the stills dissolve.
 */

/** One mix's two clips, from the moment it is armed to the moment the reveal has landed. */
internal class AutoMixCanvasTracks(
    val generation: Long,
    val outgoing: QueueEntryIdentity,
    val incoming: QueueEntryIdentity,
    pinnedClip: CanvasArtwork?,
    private val visualSample: () -> AutoMixVisualState? = { AutoMixVisualFeed.state.value },
) {
    /** The outgoing clip as it was on screen when the mix began; it outlives the session's swap. */
    var pinnedClip by mutableStateOf(pinnedClip)
    /** Hold the outgoing clip on its current frame: the reveal animates over that paused frame. */
    var freezeOutgoing by mutableStateOf(false)
    var started by mutableStateOf(false)
    var incomingClip by mutableStateOf<CanvasArtwork?>(null)
    var incomingReady by mutableStateOf(false)
    var incomingAspect by mutableStateOf(0f)
    private var incomingAccepted by mutableStateOf(false)
    private var choiceSealed by mutableStateOf(false)
    var outgoingReady by mutableStateOf(pinnedClip != null)
    /** The outgoing clip's shape, once the hero knows it: a clip of another shape cannot share its slot. */
    var outgoingAspect by mutableFloatStateOf(0f)
    var windowOpen by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)

    val liveIncoming get() = incomingAccepted && incomingReady
    val liveOutgoing get() = started && outgoingReady && pinnedClip != null

    /** The outgoing clip keeps playing while the incoming one may still make it: the cover stays out of the way. */
    val holdsLive get() = liveOutgoing && (liveIncoming || (!choiceSealed && incomingClip != null))

    fun beforeIncomingDeadline(state: AutoMixVisualState?): Boolean {
        if (state == null || state.generation != generation) return false
        if (state.phase == AutoMixVisualPhase.ARMED) return true
        if (!state.mixActive) return false
        val window = state.artworkWindow ?: return state.swapSeconds == null
        return state.audioSeconds < window.startSeconds - AutoMixArtworkTuning.CANVAS_READY_MARGIN_SECONDS
    }

    fun reportIncoming(ready: Boolean) {
        // A first-frame callback can run before the collector seals the choice,
        // even though the live audio sample has already passed the deadline.
        if (ready && (choiceSealed || incomingReady || !beforeIncomingDeadline(visualSample()))) return
        if (!ready && incomingAccepted && incomingReady) {
            // A lost surface or failed decoder may not rejoin this dissolve.
            incomingAccepted = false
            incomingClip = null
        }
        incomingReady = ready
    }

    fun reportOutgoing(ready: Boolean) {
        if (!ready) outgoingReady = false
    }

    /** The eased reveal on the display-frame clock. Reading it in a draw or update block keeps that block ticking. */
    fun reveal(): Float {
        val observed = progress
        val sample = visualSample()
        if (sample?.generation != generation || sample.artworkWindow == null) return 0f
        return AutoMixArtworkTuning.revealProgress(
            AutoMixRevealClock.progress(sample, maxOf(observed, sample.artworkProgress)),
        )
    }

    fun standbyOpacity(): Float = if (windowOpen && liveIncoming) reveal() else 0f

    /** Handed to the incoming clip's player; the one object lives as long as these tracks. */
    val standbyReveal = CanvasOrganicReveal(generation, ::standbyOpacity)

    /** Make the live/still choice once; a late frame cannot change it. */
    fun sealIncoming(): Boolean {
        if (!choiceSealed) {
            choiceSealed = true
            incomingAccepted = incomingReady && incomingClip != null && sharesFootprint()
        }
        return incomingAccepted
    }

    private fun sharesFootprint(): Boolean {
        val out = outgoingAspect
        val inc = incomingAspect
        if (out <= 0f || inc <= 0f) return true
        return (out >= 1f && inc >= 1f) || kotlin.math.abs(out - inc) < 0.01f
    }

    fun contains(entry: QueueEntryIdentity?) = entry == outgoing || entry == incoming
}

internal class AutoMixCanvasPair {
    var visual by mutableStateOf<AutoMixCanvasTracks?>(null)
        private set
    /** The incoming side of a mix that landed live, until the selected track's own canvas takes over. */
    var promoted by mutableStateOf<AutoMixCanvasTracks?>(null)
        private set
    private var shownEntry: QueueEntryIdentity? = null
    private var shownClip: CanvasArtwork? = null
    private var seenGeneration: Long? = null

    fun displayed(entry: QueueEntryIdentity?, clip: CanvasArtwork?, rendered: Boolean) {
        if (promoted?.incoming != entry) promoted = null
        shownEntry = entry
        shownClip = clip.takeIf { rendered }
    }

    fun start(
        state: AutoMixVisualState,
        entry: QueueEntryIdentity?,
        gate: AutoMixArtworkGate,
    ): AutoMixCanvasTracks? {
        if (!gate.allows(state.generation) || seenGeneration == state.generation || entry != state.outgoing.identity) return null
        // A not-yet-bound UI selection must not consume the generation.
        seenGeneration = state.generation
        return AutoMixCanvasTracks(
            state.generation, state.outgoing.identity, state.incoming.identity,
            shownClip.takeIf { shownEntry == entry }?.takeUnless { it.retiresBeforeAutoMix },
        ).also { visual = it }
    }

    fun clear() {
        visual = null
    }

    fun beginMix(tracks: AutoMixCanvasTracks) {
        tracks.outgoingReady = tracks.pinnedClip != null
        tracks.started = true
    }

    fun finish(tracks: AutoMixCanvasTracks) {
        promoted = tracks.takeIf { it.liveIncoming }
        clear()
    }

    fun invalidatePromotion() { promoted = null }
}

@Composable
internal fun rememberAutoMixCanvasPair(
    entry: QueueEntryIdentity?,
    queue: List<Song>,
    isPlaying: Boolean,
    layoutKey: Any?,
    artworkGate: AutoMixArtworkGate,
): AutoMixCanvasPair {
    val host = PlayerPlatform.host
    val feed = AutoMixVisualFeed.state
    val enabled by PlayerSettings.animatedCanvas.collectAsStateWithLifecycle()
    val metered by PlayerSettings.meteredConnection.collectAsStateWithLifecycle()
    val cellular by PlayerSettings.canvasOverCellular.collectAsStateWithLifecycle()
    val foreground = rememberIsForeground()
    val canvasAllowed = enabled && (metered != true || cellular)
    val allowed = canvasAllowed && foreground && isPlaying
    val currentEntry by rememberUpdatedState(entry)
    val currentQueue by rememberUpdatedState(queue)
    val pair = remember { AutoMixCanvasPair() }
    LaunchedEffect(canvasAllowed, layoutKey) { pair.invalidatePromotion() }
    // A layout change cancels the effect but cannot prepare the same generation again.
    LaunchedEffect(allowed, layoutKey, artworkGate) {
        if (!allowed || !artworkGate.enabled) return@LaunchedEffect
        var active: AutoMixCanvasTracks? = null
        var resolve: Job? = null
        var timeout: Job? = null
        var sealed = false
        fun stopPreparing() {
            sealed = true
            active?.incomingReady = false
            active?.incomingClip = null
            active?.sealIncoming()
            resolve?.cancel()
            timeout?.cancel()
        }
        fun clear() {
            stopPreparing()
            active = null
            pair.clear()
        }
        try {
            launch {
                snapshotFlow { currentEntry }.collect { selected ->
                    if (active?.contains(selected) == false) clear()
                }
            }
            feed.collect { state ->
                if (state == null || (!state.mixActive && state.phase != AutoMixVisualPhase.ARMED) ||
                    state.artworkProgress >= 1f ||
                    (state.swapSeconds != null && state.artworkWindow == null)
                ) {
                    val tracks = active
                    if (tracks != null && state?.generation == tracks.generation &&
                        state.artworkWindow != null &&
                        (state.artworkProgress >= 1f || state.phase == AutoMixVisualPhase.COMPLETED) &&
                        currentEntry == tracks.incoming
                    ) {
                        resolve?.cancel()
                        timeout?.cancel()
                        active = null
                        pair.finish(tracks)
                    } else if (tracks != null) clear()
                    return@collect
                }
                if (active?.generation != state.generation) {
                    clear()
                    val tracks = pair.start(state, currentEntry, artworkGate) ?: return@collect
                    active = tracks
                    sealed = false
                    val index = state.incoming.armedIndex
                    val song = currentQueue.getOrNull(index)?.takeIf {
                        coverOccurrence(it, currentQueue, index) == tracks.incoming && !showsMusicVideo(it)
                    } ?: return@collect
                    resolve = launch {
                        val clip = try {
                            host.cachedCanvas(song) ?: host.canvasFor(song)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                            // An incoming Spotify Canvas reveals as its cover; the ordinary
                            // selected-track path brings the clip in once the mix has promoted.
                            ?.takeUnless { it.retiresBeforeAutoMix } ?: return@launch
                        if (sealed || active !== tracks || !tracks.beforeIncomingDeadline(feed.value)) return@launch
                        tracks.incomingClip = tracks.pinnedClip?.takeIf { it.url == clip.url } ?: clip
                        if (clip.url == tracks.pinnedClip?.url) tracks.incomingReady = true
                    }
                }
                val tracks = active ?: return@collect
                if (state.phase == AutoMixVisualPhase.ARMED) return@collect
                if (!tracks.started) {
                    pair.beginMix(tracks)
                    timeout = launch {
                        delay(AutoMixArtworkTuning.CANVAS_PREPARE_TIMEOUT_MS)
                        if (active === tracks && !sealed && !tracks.incomingReady) stopPreparing()
                    }
                }
                tracks.progress = state.artworkProgress
                val window = state.artworkWindow
                if (!tracks.freezeOutgoing && window != null &&
                    state.audioSeconds >= window.startSeconds - AutoMixArtworkTuning.CANVAS_READY_MARGIN_SECONDS
                ) tracks.freezeOutgoing = true
                if (!sealed && !tracks.beforeIncomingDeadline(state)) {
                    if (!tracks.sealIncoming()) stopPreparing() else sealed = true
                }
                tracks.windowOpen = state.artworkWindow != null
            }
        } finally {
            clear()
        }
    }
    return pair
}

/** Keep the still/still shader out of any dissolve with a live layer. */
internal fun AutoMixCoverFrames?.baseUnderCanvas(pair: AutoMixCanvasTracks?): AutoMixCoverFrames? =
    if (this != null && pair != null && (pair.liveOutgoing || pair.liveIncoming)) AutoMixCoverFrames(base) else this

/** Spotify's full-screen Canvas never takes part in an AutoMix reveal; it fades back to its cover. */
internal val CanvasArtwork.retiresBeforeAutoMix: Boolean get() = source == CanvasSource.SPOTIFY

/** Both videos use one keyed call site, so the standby is the same player once it becomes the current clip. */
@Composable
internal fun CanvasPresentationHost(
    current: CanvasArtwork?,
    standby: CanvasArtwork? = null,
    render: @Composable (CanvasArtwork, Boolean) -> Unit,
) {
    val clips = listOfNotNull(current, standby?.takeUnless { it.url == current?.url })
    for (clip in clips) key(clip.url) { render(clip, clip.url != current?.url) }
}

@Composable
internal fun RememberDisplayedAutoMixCanvas(
    pair: AutoMixCanvasPair,
    entry: QueueEntryIdentity?,
    canvas: CanvasArtwork?,
    rendered: Boolean,
) {
    SideEffect { pair.displayed(entry, canvas, rendered) }
}
