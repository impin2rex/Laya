package com.music.laya.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.Image as CoilImage
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import com.music.laya.data.model.QueueEntryIdentity
import com.music.laya.data.model.ROW_ART_PX
import com.music.laya.data.model.Song
import com.music.laya.data.model.artworkAt
import com.music.laya.data.model.entryIdentityAt
import com.music.laya.playback.AutoMixArtworkGate
import com.music.laya.playback.AutoMixArtworkTuning
import com.music.laya.playback.AutoMixVisualFeed
import com.music.laya.playback.AutoMixVisualPhase
import com.music.laya.playback.AutoMixVisualState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/*
 * AutoMix's artwork transition (BitChord v1.10): as two songs mix, the cover dissolves from the
 * outgoing one to the incoming one through an organic, blur-to-sharp reveal (see
 * [autoMixIncomingReveal]) timed off the engines' audio clock (see [AutoMixVisualFeed]).
 *
 * Both pictures are pixels decoded before the overlap: the outgoing sleeve as it was on screen,
 * the incoming one prefetched while the mix was armed. Nothing joins once the mix has started,
 * so a late decode can never change the layer stack mid-reveal.
 */

/** The full-player and mini-player gates: AutoMix on, the transition's own switch on, not fenced off. */
@Composable
internal fun autoMixArtworkGate(mini: Boolean = false): AutoMixArtworkGate {
    val automix by PlayerSettings.smartFadeEnabled.collectAsStateWithLifecycle()
    val preferences by PlayerSettings.autoMixArtwork.collectAsStateWithLifecycle()
    return preferences.gate(automix, mini)
}

/** Hold pixels and hold-over key without retaining a request's platform context. */
internal class DecodedAutoMixCover(
    val image: CoilImage,
    val cacheKey: MemoryCache.Key?,
    val painter: Painter? = null,
)

internal fun SuccessResult.decodedAutoMixCover(painter: Painter? = null): DecodedAutoMixCover =
    DecodedAutoMixCover(image, memoryCacheKey, painter)

/** The prefetched results, retained separately by surface size. Main only. */
internal object AutoMixPreparedCover {
    private var latest: Pair<Long, DecodedAutoMixCover>? = null
    private var latestMini: Pair<Long, DecodedAutoMixCover>? = null
    var fullPrefetched: Long? = null
    var miniPrefetched: Long? = null

    fun retain(generation: Long, result: SuccessResult) {
        latest = generation to result.decodedAutoMixCover()
    }

    fun forGeneration(generation: Long): DecodedAutoMixCover? =
        latest?.takeIf { it.first == generation }?.second

    fun retainMini(generation: Long, result: SuccessResult) {
        latestMini = generation to result.decodedAutoMixCover()
    }

    fun miniForGeneration(generation: Long): DecodedAutoMixCover? =
        latestMini?.takeIf { it.first == generation }?.second
}

internal fun coverOccurrence(song: Song, queue: List<Song>, index: Int): QueueEntryIdentity? {
    val current = queue.getOrNull(index) ?: return null
    if (current.videoId != song.videoId || current.queueEntryId != song.queueEntryId) return null
    return queue.entryIdentityAt(index)
}

/** The incoming track's artwork as the queue has it, checked against the armed occurrence. */
private fun incomingArtworkUrl(state: AutoMixVisualState, queue: List<Song>): String? {
    val index = state.incoming.armedIndex
    val song = queue.getOrNull(index)?.takeIf { queue.entryIdentityAt(index) == state.incoming.identity }
    return song?.thumbnailUrl ?: state.incoming.artworkUrl
}

/**
 * Warms the incoming cover (and, for the full player, its ambient backdrop) while the mix is
 * armed, once per generation per surface. The reveal only ever uses what this retained.
 */
@Composable
internal fun AutoMixArtworkPrefetchEffect(queue: List<Song>, gate: AutoMixArtworkGate, mini: Boolean) {
    val context = LocalPlatformContext.current
    val currentQueue by rememberUpdatedState(queue)
    LaunchedEffect(context, gate, mini) {
        if (!gate.enabled) return@LaunchedEffect
        AutoMixVisualFeed.state
            .map { state -> state?.takeIf { it.phase == AutoMixVisualPhase.ARMED && gate.allows(it.generation) } }
            .distinctUntilChanged { a, b -> a?.generation == b?.generation }
            .collectLatest { state ->
                state ?: return@collectLatest
                val done = if (mini) AutoMixPreparedCover.miniPrefetched else AutoMixPreparedCover.fullPrefetched
                if (done == state.generation) return@collectLatest
                val url = incomingArtworkUrl(state, currentQueue) ?: return@collectLatest
                try {
                    prefetchAutoMixCover(context, url, state.generation, mini)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A cover that will not decode is a plain cut, as without the transition.
                }
            }
    }
}

private suspend fun prefetchAutoMixCover(context: PlatformContext, url: String, generation: Long, mini: Boolean) {
    val loader = SingletonImageLoader.get(context)
    fun stillArmed() = AutoMixVisualFeed.pending(generation)?.phase == AutoMixVisualPhase.ARMED
    if (mini) {
        // The mini surfaces size their decode from layout; warm a row-sized, inexact one.
        val result = loader.execute(
            ImageRequest.Builder(context).data(url.artworkAt(ROW_ART_PX)).size(ROW_ART_PX)
                .scale(Scale.FILL).precision(Precision.INEXACT).build(),
        )
        if (result is SuccessResult && stillArmed()) {
            AutoMixPreparedCover.retainMini(generation, result)
            AutoMixPreparedCover.miniPrefetched = generation
        }
        return
    }
    // The backdrop first: its mesh and blur are what the ambient cross-fades to with the reveal.
    prefetchArtworkAmbient(context, url)
    if (!stillArmed()) return
    val result = loader.execute(
        ImageRequest.Builder(context).data(url.artworkAt(ART_PX)).size(ART_PX)
            .scale(Scale.FILL).precision(Precision.INEXACT).build(),
    )
    if (result is SuccessResult && stillArmed()) {
        AutoMixPreparedCover.retain(generation, result)
        AutoMixPreparedCover.fullPrefetched = generation
    }
}

internal class AutoMixCoverState(
    private val miniOutgoing: ((AutoMixVisualState) -> DecodedAutoMixCover?)? = null,
    private val preparedIncoming: (Long) -> DecodedAutoMixCover? = if (miniOutgoing == null) {
        AutoMixPreparedCover::forGeneration
    } else AutoMixPreparedCover::miniForGeneration,
) {
    private class PairFrames(
        val generation: Long,
        val outgoing: QueueEntryIdentity,
        val incoming: QueueEntryIdentity,
        val frames: AutoMixCoverFrames,
    )

    private var current: QueueEntryIdentity? = null
    private var currentArt: PlayerArtwork? = null
    private var armedArt: PlayerArtwork? = null
    private var armedCover: DecodedAutoMixCover? = null
    private var armedGeneration: Long? = null
    private var seenMix: Long? = null
    private var frozen: PairFrames? = null
    private var active by mutableStateOf<PairFrames?>(null)
    private var promoted by mutableStateOf<Pair<QueueEntryIdentity, AutoMixCoverFrames>?>(null)
    private var progress by mutableFloatStateOf(0f)
    private var latest: AutoMixVisualState? = null

    fun bind(occurrence: QueueEntryIdentity?, art: PlayerArtwork?, visual: AutoMixVisualState?) {
        if (current != occurrence) {
            // Session metadata can move before the collector consumes the mix.
            // Retain this exact outgoing occurrence, not a general previous track.
            if (visual?.mixActive == true && visual.outgoing.identity == current && visual.incoming.identity == occurrence &&
                seenMix != visual.generation
            ) {
                armedGeneration = visual.generation
                armedArt = currentArt
                armedCover = promoted?.takeIf { it.first == current }?.second?.base
            }
            if (promoted?.first == current) promoted = null
        }
        current = occurrence
        currentArt = art
        if (visual?.phase == AutoMixVisualPhase.ARMED && visual.outgoing.identity == current) {
            armedGeneration = visual.generation
            armedArt = art
            armedCover = promoted?.takeIf { it.first == current }?.second?.base
        }
    }

    fun update(visual: AutoMixVisualState?, gate: AutoMixArtworkGate = AutoMixArtworkGate()) {
        latest = visual
        if (visual == null || visual.phase == AutoMixVisualPhase.CANCELLED || !gate.allows(visual.generation)) {
            if (visual != null) seenMix = visual.generation
            val pair = active
            // If the session already owns a decoded member of the pair, snap to it
            // without a Coil arrival fade. A new occurrence ignores this single frame.
            if (pair != null && visual?.generation == pair.generation) {
                val selection = current
                if (selection != null) pair.selectedFrames(selection)?.let { promoted = selection to it }
            }
            active = null
            frozen = null
            armedArt = null
            armedCover = null
            armedGeneration = null
            return
        }
        when (visual.phase) {
            AutoMixVisualPhase.ARMED -> {
                active = null
                frozen = null
                armedGeneration = visual.generation
                armedArt = currentArt.takeIf { visual.outgoing.identity == current }
                armedCover = promoted?.takeIf { it.first == visual.outgoing.identity }?.second?.base
            }
            AutoMixVisualPhase.MIXING -> {
                if (seenMix != visual.generation) {
                    // Choose once from pixels prepared before the overlap. Do not change
                    // the layer stack if a decode finishes after this generation starts.
                    seenMix = visual.generation
                    val outgoing = promoted?.takeIf { it.first == visual.outgoing.identity }?.second?.base
                        ?: armedCover.takeIf { armedGeneration == visual.generation }
                        ?: miniOutgoing?.invoke(visual)
                        ?: (armedArt.takeIf { armedGeneration == visual.generation }
                            ?: currentArt.takeIf { visual.outgoing.identity == current })
                            ?.decodedCoverBefore(visual.generation)
                    val incoming = preparedIncoming(visual.generation)
                    frozen = if (outgoing != null && incoming != null && visual.artworkProgress < 1f) {
                        PairFrames(
                            visual.generation, visual.outgoing.identity, visual.incoming.identity,
                            AutoMixCoverFrames(outgoing, incoming, visual.generation) {
                                AutoMixRevealClock.progress(latest?.takeIf { it.generation == visual.generation }, progress)
                            },
                        )
                    } else null
                    armedArt = null
                    armedCover = null
                }
                if (visual.swapSeconds != null && visual.artworkWindow == null) frozen = null
                val pair = frozen
                progress = visual.artworkProgress
                if (pair != null && visual.artworkWindow != null && visual.artworkProgress >= 1f) {
                    promote(pair)
                } else {
                    // The session may publish its new item before the clock sample
                    // for the swap. Keep the outgoing pixels pinned at zero through it.
                    active = pair
                }
            }
            AutoMixVisualPhase.COMPLETED -> {
                seenMix = visual.generation
                // Completion may be conflated with the final progress tick.
                frozen?.takeIf { it.generation == visual.generation && visual.artworkWindow != null }
                    ?.let(::promote)
                active = null
                frozen = null
                armedArt = null
                armedCover = null
            }
            AutoMixVisualPhase.CANCELLED -> Unit // handled above
        }
    }

    private fun promote(pair: PairFrames) {
        val incoming = pair.frames.incoming ?: return
        promoted = pair.incoming to AutoMixCoverFrames(incoming)
        active = null
        frozen = null
    }

    private fun PairFrames.selectedFrames(occurrence: QueueEntryIdentity?): AutoMixCoverFrames? =
        when (occurrence) {
            outgoing -> AutoMixCoverFrames(frames.base)
            incoming -> frames.incoming?.let { AutoMixCoverFrames(it) }
            else -> null
        }

    fun framesFor(occurrence: QueueEntryIdentity?, gate: AutoMixArtworkGate = AutoMixArtworkGate()): AutoMixCoverFrames? {
        val pair = active
        if (pair != null && (occurrence == pair.outgoing || occurrence == pair.incoming)) {
            return if (gate.allows(pair.generation)) pair.frames else pair.selectedFrames(occurrence)
        }
        return promoted?.takeIf { it.first == occurrence }?.second
    }

    /** Whether a dissolve is on screen for [occurrence], with the incoming picture still landing. */
    fun revealing(occurrence: QueueEntryIdentity?): Boolean =
        active?.let { occurrence == it.outgoing || occurrence == it.incoming } == true
}

@Composable
internal fun rememberAutoMixCover(
    occurrence: QueueEntryIdentity?,
    art: PlayerArtwork,
    gate: AutoMixArtworkGate,
    queue: List<Song>,
): AutoMixCoverState {
    val feed = AutoMixVisualFeed.state
    val cover = remember { AutoMixCoverState() }
    SideEffect { cover.bind(occurrence, art, feed.value?.takeIf { gate.allows(it.generation) }) }
    LaunchedEffect(cover, gate) { feed.collect { cover.update(it, gate) } }
    AutoMixRevealClockEffect()
    AutoMixArtworkPrefetchEffect(queue, gate, mini = false)
    return cover
}

private class DisplayedMiniCovers {
    private class Frame(
        val occurrence: QueueEntryIdentity,
        val cover: DecodedAutoMixCover,
        val decodedDuring: Long?,
    )

    private var latest: Frame? = null
    private var previous: Frame? = null

    fun before(visual: AutoMixVisualState): DecodedAutoMixCover? =
        latest.eligibleFor(visual)
            // The new row may report a success before the collector sees the mix.
            ?: previous.eligibleFor(visual).takeIf { latest?.decodedDuring == visual.generation }

    private fun Frame?.eligibleFor(visual: AutoMixVisualState): DecodedAutoMixCover? =
        this?.takeIf { it.occurrence == visual.outgoing.identity && it.decodedDuring != visual.generation }?.cover

    fun rememberSuccess(occurrence: QueueEntryIdentity, state: AsyncImagePainter.State.Success, visual: AutoMixVisualState?) {
        // Coil's success painter is the decoded endpoint, not its arrival crossfade.
        val cover = state.result.decodedAutoMixCover(state.painter)
        val active = visual?.takeIf { it.mixActive }
        if (active != null && active.outgoing.identity == occurrence && before(active) != null) return
        if (latest?.occurrence != occurrence) previous = latest
        latest = Frame(occurrence, cover, active?.generation)
    }
}

/**
 * The mini players' artwork slot — the phone's bar and the glass nav bar's docked player — owned
 * once by the host that knows the queue, and drawn inside the bars' existing frames: `(model,
 * modifier)`. Null outside such a host, where the bars draw their plain cover.
 */
val LocalMiniAutoMixArtwork = compositionLocalOf<(@Composable (String?, Modifier) -> Unit)?> { null }

@Composable
fun rememberMiniAutoMixArtwork(
    song: Song?,
    queue: List<Song>,
    index: Int,
): @Composable (String?, Modifier) -> Unit {
    val occurrence = remember(song?.videoId, song?.queueEntryId, queue, index) {
        song?.let { coverOccurrence(it, queue, index) }
    }
    val feed = AutoMixVisualFeed.state
    val displayed = remember { DisplayedMiniCovers() }
    val cover = remember { AutoMixCoverState(displayed::before) }
    val gate = autoMixArtworkGate(mini = true)
    SideEffect { cover.bind(occurrence, null, feed.value?.takeIf { gate.allows(it.generation) }) }
    LaunchedEffect(cover, gate) { feed.collect { cover.update(it, gate) } }
    AutoMixRevealClockEffect()
    AutoMixArtworkPrefetchEffect(queue, gate, mini = true)

    return remember(cover, occurrence, gate) {
        { model, modifier ->
            AutoMixCover(cover.framesFor(occurrence, gate), modifier, blur = false) { artworkModifier ->
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onSuccess = { state ->
                        if (occurrence != null && state.result.request.data == model) {
                            displayed.rememberSuccess(occurrence, state, feed.value)
                        }
                    },
                    modifier = artworkModifier,
                )
            }
        }
    }
}

internal class AutoMixCoverFrames(
    val base: DecodedAutoMixCover,
    val incoming: DecodedAutoMixCover? = null,
    val generation: Long = 0L,
    val progress: () -> Float = { 0f },
)

/** The frame, clip and shadow belong to the caller. */
@Composable
internal fun AutoMixCover(
    frames: AutoMixCoverFrames?,
    modifier: Modifier,
    blur: Boolean,
    fallback: @Composable (Modifier) -> Unit,
) {
    if (frames == null) {
        fallback(modifier)
        return
    }
    val incoming = frames.incoming
    Box(modifier) {
        DecodedCover(frames.base, Modifier.fillMaxSize())
        if (incoming != null) {
            val eased = remember(frames) { { AutoMixArtworkTuning.revealProgress(frames.progress()) } }
            AutoMixIncomingStill(incoming, eased, frames.generation, blur = blur)
        }
    }
}

@Composable
internal fun AutoMixIncomingStill(
    result: DecodedAutoMixCover,
    eased: () -> Float,
    generation: Long,
    blur: Boolean = true,
) {
    val appReduced = PlayerSettings.reduceAnimation.collectAsState()
    val systemMotion = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    val reducedMotion = remember(appReduced, systemMotion) { { appReduced.value || systemMotion?.scaleFactor == 0f } }
    DecodedCover(
        result,
        Modifier.fillMaxSize().then(autoMixIncomingReveal(eased, reducedMotion, generation, blur)),
    )
}

@Composable
internal fun DecodedCover(result: DecodedAutoMixCover, modifier: Modifier) {
    val context = LocalPlatformContext.current
    val painter = result.painter ?: remember(result.image, context) { result.image.asPainter(context) }
    Image(painter, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}

/** The player's sleeve: the AutoMix pair while one is on screen, the ordinary cover otherwise. */
@Composable
internal fun AutoMixPlayerCover(
    art: PlayerArtwork,
    frames: AutoMixCoverFrames?,
    modifier: Modifier,
) {
    AutoMixCover(frames, modifier, blur = true) { artworkModifier ->
        AsyncImage(
            model = art.request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onState = art::onState,
            modifier = artworkModifier,
        )
    }
}
