package com.music.laya.playback

import com.music.laya.data.model.QueueEntryIdentity
import kotlin.time.TimeSource

object AutoMixArtworkTuning {
    const val START_LAG_AFTER_SWAP = 0.6
    const val WINDOW_SECONDS = 2.6
    const val MIN_WINDOW_SECONDS = 0.8
    // Arming starts the incoming cover decode and ambient prep. A seek straight into
    // the mix arms right at the overlap; starting the reveal before that work settles
    // shares its frames with it and drops to low fps.
    const val MIN_PREPARE_LEAD_SECONDS = 1.5
    private const val LINEAR_REVEAL_WEIGHT = 0.25f
    const val BLUR_START_DP = 20f
    const val BLUR_END_PROGRESS = 0.85f
    const val BLUR_RADIUS_STEPS = 64
    const val NOISE_SCALE = 2.6f
    const val NOISE_SOFTNESS = 0.20f
    const val NOISE_LOW = 0.18f
    const val NOISE_HIGH = 0.82f
    const val NOISE_SEED_SPAN = 32f
    const val NOISE_OCTAVES = 3
    // The audio clock is sampled every 20-30ms, a quarter of a 120Hz display's frames.
    // The UI extrapolates between samples, at most this far, so a stalled controller
    // cannot run the reveal ahead of the audio.
    const val MAX_PROGRESS_EXTRAPOLATION_SECONDS = 0.12
    /** How long an outgoing animated cover takes to fade back to its still once a mix starts. */
    const val CANVAS_RETIRE_MS = 700

    /**
     * The artwork window for a swap observed at [swap], with the overlap ending at [end].
     * [preparedForSeconds] is how long ago the mix was armed. A late arm (a skip into the
     * overlap) should give the incoming artwork time to prepare before revealing, but that
     * lead is a preference, not a gate: it gives way to a minimum window, so a skip onto
     * the mix still dissolves instead of cutting. Then the cosmetic lag gives way, then
     * duration; never scheduled before the observed swap.
     */
    fun windowAfter(swap: Double, end: Double, preparedForSeconds: Double): AutoMixArtworkWindow? {
        val remaining = end - swap
        if (remaining < MIN_WINDOW_SECONDS) return null
        val prepareLag = (MIN_PREPARE_LEAD_SECONDS - preparedForSeconds).coerceIn(0.0, remaining - MIN_WINDOW_SECONDS)
        val duration = minOf(WINDOW_SECONDS, remaining - prepareLag)
        val lag = maxOf(prepareLag, minOf(START_LAG_AFTER_SWAP, remaining - duration))
        val start = swap + lag
        return AutoMixArtworkWindow(start, minOf(end, start + duration))
    }

    fun ease(progress: Float): Float = progress * progress * (3f - 2f * progress)

    fun revealProgress(progress: Float): Float {
        // Keep a gentle start and finish without hiding most of a short window in the tails.
        val smooth = ease(progress)
        return smooth + (progress - smooth) * LINEAR_REVEAL_WEIGHT
    }
}

private val clockOrigin = TimeSource.Monotonic.markNow()

/**
 * The monotonic clock both the engines' samples and the UI's frames are dated on.
 * Never 0, which [AutoMixVisualState.sampledAtNanos] reserves for "unknown".
 */
fun autoMixClockNanos(): Long = clockOrigin.elapsedNow().inWholeNanoseconds + 1

/** The index is the arming index, not a live index; use the entry identity where available. */
data class AutoMixVisualTrack(
    val identity: QueueEntryIdentity,
    val armedIndex: Int,
    val artworkUrl: String?,
)

enum class AutoMixVisualPhase { ARMED, MIXING, COMPLETED, CANCELLED }

data class AutoMixArtworkWindow(val startSeconds: Double, val endSeconds: Double)

/**
 * One pair across the session swap. All times are on this generation's audio axis, not wall time.
 * A null artwork window after the swap means the pair cannot fit a dissolve. On cancellation,
 * discard both layers and use the live session item (the accepted transport may still be settling).
 */
data class AutoMixVisualState(
    val generation: Long,
    val outgoing: AutoMixVisualTrack,
    val incoming: AutoMixVisualTrack,
    val phase: AutoMixVisualPhase = AutoMixVisualPhase.ARMED,
    val overlapStartSeconds: Double? = null,
    val audioSeconds: Double = 0.0,
    val swapSeconds: Double? = null,
    val artworkWindow: AutoMixArtworkWindow? = null,
    val artworkProgress: Float = 0f,
    /** [autoMixClockNanos] when [audioSeconds] was sampled; 0 when unknown. */
    val sampledAtNanos: Long = 0L,
) {
    val mixActive: Boolean get() = phase == AutoMixVisualPhase.MIXING

    /**
     * [artworkProgress] carried forward from its sample to a display frame at [frameNanos]
     * (same clock as [sampledAtNanos]), so the reveal moves every frame instead of in steps
     * at the controller's tick rate. Never behind the sample; capped ahead of it.
     */
    fun artworkProgressAt(frameNanos: Long): Float {
        val window = artworkWindow ?: return artworkProgress
        if (!mixActive || artworkProgress >= 1f || sampledAtNanos == 0L || frameNanos == 0L) return artworkProgress
        val span = window.endSeconds - window.startSeconds
        if (span <= 0.0) return artworkProgress
        val ahead = ((frameNanos - sampledAtNanos) / 1e9)
            .coerceIn(0.0, AutoMixArtworkTuning.MAX_PROGRESS_EXTRAPOLATION_SECONDS)
        val at = ((audioSeconds + ahead - window.startSeconds) / span).coerceIn(0.0, 1.0).toFloat()
        return maxOf(artworkProgress, at)
    }
}

/** Saved choices plus in-memory fences for mixes interrupted by a preference change. */
data class AutoMixArtworkPreferences(
    // Both off until the listener turns them on: AutoMix audio does not depend on them.
    val artTransition: Boolean = false,
    val miniPlayerTransition: Boolean = false,
    val skipArtworkThrough: Long = 0L,
    val skipMiniThrough: Long = 0L,
) {
    fun gate(automix: Boolean, mini: Boolean = false) = AutoMixArtworkGate(
        enabled = automix && (if (mini) miniPlayerTransition else artTransition),
        skipThrough = if (mini) skipMiniThrough else skipArtworkThrough,
    )

    fun allows(automix: Boolean, generation: Long, mini: Boolean = false): Boolean =
        gate(automix, mini).allows(generation)

    /**
     * [on] for the full player's transition. Turning it off fences the mix in flight, so
     * turning it straight back on cannot resurrect that generation halfway through.
     */
    fun withArtTransition(on: Boolean, liveGeneration: Long? = AutoMixVisualFeed.state.value?.generation) =
        copy(artTransition = on, skipArtworkThrough = if (on) skipArtworkThrough else maxOf(skipArtworkThrough, liveGeneration ?: 0L))

    fun withMiniPlayerTransition(on: Boolean, liveGeneration: Long? = AutoMixVisualFeed.state.value?.generation) =
        copy(miniPlayerTransition = on, skipMiniThrough = if (on) skipMiniThrough else maxOf(skipMiniThrough, liveGeneration ?: 0L))
}

data class AutoMixArtworkGate(val enabled: Boolean = true, val skipThrough: Long = 0L) {
    fun allows(generation: Long) = enabled && generation > skipThrough
}
