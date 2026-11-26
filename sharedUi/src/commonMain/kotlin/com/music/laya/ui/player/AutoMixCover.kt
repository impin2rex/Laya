package com.music.laya.ui.player

import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
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
internal fun AutoMixArtworkPrefetchEffect(
    queue: List<Song>,
    gate: AutoMixArtworkGate,
    mini: Boolean,
    miniPx: Int = ROW_ART_PX,
) {
    val context = LocalPlatformContext.current
    val currentQueue by rememberUpdatedState(queue)
    LaunchedEffect(context, gate, mini, miniPx) {
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
                    prefetchAutoMixCover(context, url, state.generation, mini, miniPx)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A cover that will not decode is a plain cut, as without the transition.
                }
            }
    }
}

private suspend fun prefetchAutoMixCover(
    context: PlatformContext,
    url: String,
    generation: Long,
    mini: Boolean,
    miniPx: Int,
) {
    val loader = SingletonImageLoader.get(context)
    fun stillArmed() = AutoMixVisualFeed.pending(generation)?.phase == AutoMixVisualPhase.ARMED
    if (mini) {
        // The mini surfaces size their decode from layout; warm one at the surface's size, inexact.
        val result = loader.execute(
            ImageRequest.Builder(context).data(url.artworkAt(miniPx)).size(miniPx)
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
    /** The decode size of the bar's cover; the phone's row art unless a bigger surface asks. */
    px: Int = ROW_ART_PX,
    /**
     * A surface's own switch in place of the mini player's — the Android lock screen's. Null: the mini
     * player's. Either way only while Automix is on.
     */
    ownSwitch: Boolean? = null,
): @Composable (String?, Modifier) -> Unit {
    val occurrence = remember(song?.videoId, song?.queueEntryId, queue, index) {
        song?.let { coverOccurrence(it, queue, index) }
    }
    val feed = AutoMixVisualFeed.state
    val displayed = remember { DisplayedMiniCovers() }
    val cover = remember { AutoMixCoverState(displayed::before) }
    val miniGate = autoMixArtworkGate(mini = true)
    val automix by PlayerSettings.smartFadeEnabled.collectAsStateWithLifecycle()
    val gate = if (ownSwitch == null) miniGate else AutoMixArtworkGate(enabled = automix && ownSwitch)
    SideEffect { cover.bind(occurrence, null, feed.value?.takeIf { gate.allows(it.generation) }) }
    LaunchedEffect(cover, gate) { feed.collect { cover.update(it, gate) } }
    AutoMixRevealClockEffect()
    AutoMixArtworkPrefetchEffect(queue, gate, mini = true, miniPx = px)

    return remember(cover, occurrence, gate) {
        { model, modifier ->
            AutoMixCover(cover.framesFor(occurrence, gate), modifier, blur = false, revealKey = model, reveal = true) { artworkModifier ->
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
    /**
     * The artwork [fallback] draws (a Coil model), so a change of song outside an Automix blend
     * dissolves too: the last cover stays underneath while the next is revealed over it
     * ([SongChangeReveal]). Null while the next song's artwork is still being found.
     */
    revealKey: Any? = null,
    /** Whether to reveal at all; a caller with no artwork model to give leaves it off. */
    reveal: Boolean = revealKey != null,
    fallback: @Composable (Modifier) -> Unit,
) {
    if (frames == null) {
        if (!reveal) fallback(modifier) else SongChangeReveal(revealKey, modifier, blur, fallback)
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
    // Below Android 12 the blur effect is a no-op: crossfade from a soft copy instead.
    val soft = if (blur && !blurEffectSupported) {
        val context = LocalPlatformContext.current
        remember(result.image, result.painter, context) {
            softCoverOf(result.painter ?: result.image.asPainter(context))
        }
    } else null
    DecodedCover(
        result,
        Modifier.fillMaxSize().then(autoMixIncomingReveal(eased, reducedMotion, generation, blur, soft)),
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
    AutoMixCover(frames, modifier, blur = true, revealKey = art.request) { artworkModifier ->
        AsyncImage(
            model = art.request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onState = art::onState,
            modifier = artworkModifier,
        )
    }
}

/**
 * Automix's artwork dissolve for any other change of song (a skip, a plain next, Automix off), as
 * Melo's cover mix: the last cover is kept underneath while the next is revealed over it, eased,
 * sharpening out of a blur, over [SONG_CHANGE_REVEAL_MS]. Reduce animation makes it a cut.
 *
 * Nothing here writes state while composing: the player recomposes on every progress tick, and a
 * write there would recompose it again each time.
 */
@Composable
private fun SongChangeReveal(
    key: Any?,
    modifier: Modifier,
    blur: Boolean,
    content: @Composable (Modifier) -> Unit,
) {
    val reduced by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    // The artwork last shown, kept as the model itself: a composable lambda is updated in place
    // by Compose, so a kept one would draw the new song's cover, not the old. Never written while
    // composing. A null key (the next song's artwork still being found) changes nothing.
    val shown = remember { ShownCover() }
    val outgoing = remember(key) {
        val before = shown.key
        if (key != null && before != null && before != key && !reduced) before else null
    }
    SideEffect { if (key != null) shown.key = key }
    val reveal = remember(key) { Animatable(if (outgoing == null) 1f else 0f) }
    var done by remember(key) { mutableStateOf(outgoing == null) }
    LaunchedEffect(key) {
        if (outgoing == null) return@LaunchedEffect
        reveal.animateTo(1f, tween(SONG_CHANGE_REVEAL_MS, easing = LinearEasing))
        done = true
    }
    val density = LocalDensity.current.density
    val blurs = remember(density) { arrayOfNulls<androidx.compose.ui.graphics.RenderEffect>(REVEAL_BLUR_STEPS + 1) }
    Box(modifier) {
        if (!done && outgoing != null) {
            // The last song's cover, from Coil's memory cache, under the next.
            AsyncImage(
                model = outgoing,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        content(
            if (done) Modifier.fillMaxSize() else Modifier.fillMaxSize().graphicsLayer {
                val p = reveal.value
                val eased = p * p * (3f - 2f * p)
                val revealed = eased + (p - eased) * 0.25f
                alpha = revealed
                renderEffect = if (!blur || revealed >= 0.85f) null else {
                    // Melo's blur steps: a handful of radii, each made once and reused.
                    val left = 1f - revealed / 0.85f
                    val step = (left * left * REVEAL_BLUR_STEPS).roundToInt()
                    if (step == 0) null else blurs[step] ?: run {
                        val radius = 20f * density * step / REVEAL_BLUR_STEPS
                        BlurEffect(radius, radius, TileMode.Clamp).also { blurs[step] = it }
                    }
                }
            },
        )
    }
}

/**
 * The song-change dissolve for a cover drawn by its own surface (the TV player): [content] draws
 * the cover of [model] (a Coil model) with the modifier it is given.
 */
@Composable
fun SongChangeCover(model: Any?, modifier: Modifier, content: @Composable (Modifier) -> Unit) =
    SongChangeReveal(model, modifier, blur = true, content)

private class ShownCover {
    var key: Any? = null
}

private const val REVEAL_BLUR_STEPS = 64

/** Melo's cover-mix length. */
private const val SONG_CHANGE_REVEAL_MS = 2_600
