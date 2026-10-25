package com.music.laya.ios

import com.music.laya.playback.smart.DeckFilters
import com.music.laya.playback.smart.PhaseLock
import com.music.laya.playback.smart.TempoEase
import com.music.laya.playback.smart.TempoRamp
import com.music.laya.playback.smart.TrackAnalysis
import com.music.laya.playback.smart.TransitionPlan
import com.music.laya.playback.smart.TransitionRender
import com.music.laya.playback.smart.headroomFor
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

/**
 * What Kotlin decided for the queued pair, read by the engine when it arms and begins the blend. [source] is the
 * planner's plan, or null for the classic fixed-length crossfade (Android's plain `Render()`).
 */
class IosTransitionPlan(
    val source: TransitionPlan?,
    /** Where the incoming track is cued, in its own seconds. */
    val cueSec: Double,
    /** The beatmatch stretch of the incoming and of the outgoing track. */
    val inRate: Double,
    val outRate: Double,
    /** One outgoing beat, for the speed-up ramp; 0 when there is no grid. */
    val beatMs: Long,
    val startMs: Long,
    val fadeMs: Long,
    val outAnalysis: TrackAnalysis,
    val inAnalysis: TrackAnalysis,
    /** The incoming track's length when known, 0 otherwise; a long fade into a short track tightens. */
    val inDurationSec: Double,
)

/**
 * The transition as the audio engine runs it. Android's CrossfadeController does this with two ExoPlayers and a 30 ms
 * ticker; here each deck's tap ([IosDeck]) renders its own side of the very same [TransitionRender] — fader, filter
 * ride, echo out — from its own clock, the media time of the buffers it processes, while this object holds what the
 * engine needs on the main thread: when the blend hands off and ends, the outgoing speed-up ramp, the phase lock that
 * nudges the incoming speed, and the log line.
 */
object IosTransition {
    const val NONE = 0
    const val INCOMING = 1
    const val OUTGOING = 2

    /** [drive]'s verdicts, as bits. */
    const val HANDOFF = 1
    const val DONE = 2

    /** The plan for the queued pair; set by the player, read by the engine. */
    @Volatile
    var plan: IosTransitionPlan? = null
        private set

    /** The blend in flight, read by every tap. */
    @Volatile
    internal var live: Live? = null
        private set

    private val ramp = TempoRamp()
    private val lock = PhaseLock()
    private val ease = TempoEase()
    private var handedOff = false
    private var nextLog = 0.0

    /** The incoming speed [drive] wants set, absolute (the listener's speed included), or NaN to leave it. */
    var inSpeed = Float.NaN
        private set
    private var curInSpeed = 1f

    fun setPlan(next: IosTransitionPlan?) {
        val old = plan
        plan = next
        // The ramp keeps its place across the planner's re-syncs; only a different transition restarts it.
        if (next == null || next.beatMs <= 0L || next.outRate == 1.0) {
            ramp.clear()
        } else if (old == null || old.outRate != next.outRate || old.beatMs != next.beatMs ||
            old.startMs != next.startMs || old.fadeMs != next.fadeMs
        ) {
            ramp.begin(next.outRate, next.beatMs, next.startMs + next.fadeMs, next.fadeMs)
        }
    }

    /**
     * Before the blend: the stretch the playing track should be at once its playhead is at [outPositionMs], times the
     * listener's [speed], or NaN when no new step has come due. One step per beat of the outgoing track.
     */
    fun rampRate(outPositionMs: Long, speed: Float): Float {
        val p = plan ?: return Float.NaN
        if (!ramp.pending) return Float.NaN
        val m = ramp.due(outPositionMs, p.outRate)
        return if (m.isNaN()) Float.NaN else (speed * m).toFloat()
    }

    /** Whether a ramp is still climbing, so the engine knows to keep stepping it. */
    val ramping: Boolean get() = ramp.pending

    /** The blend starts on the incoming tempo however the ramp went. */
    fun finishRamp() = ramp.finish()

    /**
     * Starts the blend: [outKey] is the item the playing track sits on, [inKey] the one arriving. False when either
     * deck has no tap to render through, in which case the engine does not blend.
     */
    fun begin(outKey: Long, inKey: Long, startSec: Double, fadeSec: Double, outDurationSec: Double, nowMs: Long): Boolean {
        val p = plan
        val out = IosDsp.deck(outKey)
        val inn = IosDsp.deck(inKey)
        if (out == null || inn == null) {
            println("Transition: no tap on ${if (out == null) "outgoing" else "incoming"} deck; not blending")
            return false
        }
        val mediaRatio = (p?.inRate ?: 1.0) / (p?.outRate ?: 1.0)
        val inCap = p?.inDurationSec?.takeIf { it > 0.0 }?.let { max(0.0, it - p.cueSec) / 3 } ?: Double.MAX_VALUE
        val span = max(min(fadeSec, inCap), 0.001)
        val advanced = p?.source != null
        val wallSpanIn = if (advanced) span * mediaRatio else span
        handedOff = false
        inSpeed = Float.NaN
        curInSpeed = Float.NaN
        lock.start(nowMs)
        nextLog = 0.0
        live = Live(p, outKey, inKey, startSec, span, wallSpanIn, outDurationSec)
        val r = live!!.outRender
        println(
            "Transition: begin ${if (p?.source != null) "smart" else "classic"} style=${r.style} variant=${r.filterVariant} " +
                "start=$startSec fade=$fadeSec span=$span cue=${p?.cueSec} rate=${p?.inRate}/${p?.outRate} " +
                "advanced=${r.advanced} lock=${r.phaseLock} handoff=${r.handoffAt} bassSwap=${r.bassSwap}@${r.swapAt} " +
                "echo=${if (r.echo) "${r.echoSecondsF}s@${r.echoAt}" else "off"} sweep=${r.filterSweep} " +
                "clash=${r.clash} filterSwapAt=${r.filterSwapAt}",
        )
        return true
    }

    /** The blend is over (or abandoned): every tap goes back to passing its track through. */
    fun end() {
        live = null
        handedOff = false
        lock.stop()
    }

    /** Tells the phase lock the incoming deck's speed the engine has set (so a nudge in flight is remembered). */
    fun speedSet(absolute: Float) { curInSpeed = absolute }

    /**
     * One look at the blend, on the main thread: where [outPosSec] and [inPosSec] (the decks' clocks) put its progress
     * (Android's driveFade), the phase lock, and the log. The verdict is [HANDOFF] when the session should move to the
     * incoming track and [DONE] when the blend has run its course; [inSpeed] is set when the incoming speed should
     * change.
     */
    fun drive(
        nowMs: Long, hostSec: Double, outPosSec: Double, inPosSec: Double,
        outPlaying: Boolean, inPlaying: Boolean, speed: Float,
    ): Int {
        val l = live ?: return DONE
        val r = l.outRender
        val elapsed = max(0.0, inPosSec - l.cueSec)
        val incomingProgress = (elapsed / l.wallSpanIn).coerceIn(0.0, 1.0)
        // Duration/decoder timing can put the real file end ahead of the analysed anchor; then the outgoing deck must
        // still reach zero before its final sample.
        val outRemaining = if (l.outDurationSec > 0) l.outDurationSec - outPosSec else Double.NaN
        val endDriven = if (outRemaining.isNaN()) 0.0 else ((l.span - outRemaining) / l.span).coerceIn(0.0, 1.0)
        val progress = max(incomingProgress, endDriven)

        var verdict = 0
        if (!handedOff && (progress >= r.handoffAt || (!outRemaining.isNaN() && outRemaining <= HANDOFF_GUARD_SEC))) {
            handedOff = true
            verdict = verdict or HANDOFF
        }
        // An echo out rings past the end of the blend on the outgoing deck's own audio; that deck is kept until it has.
        val tailDone = !r.echo || elapsed / l.wallSpanIn >= r.echoAt + r.echoTailMs / (l.wallSpanIn * 1000.0)
        if (progress >= 1.0 && tailDone) verdict = verdict or DONE

        inSpeed = Float.NaN
        if (r.phaseLock && verdict and DONE == 0) {
            val p = l.plan
            val out = IosDsp.deck(l.outKey)
            val inn = IosDsp.deck(l.inKey)
            if (p != null && out != null && inn != null) {
                val outSpeed = speed * p.outRate
                val inCur = if (curInSpeed.isNaN()) speed * p.inRate else curInSpeed.toDouble()
                // Both read at one host time, from what each tap last produced: the same pipeline stage on either
                // deck, so the start latency of the incoming player cancels instead of reading as phase error.
                val error = lock.error(
                    p.outAnalysis, out.mediaAt(hostSec, outSpeed.toDouble()),
                    p.inAnalysis, inn.mediaAt(hostSec, inCur),
                    p.inRate / p.outRate,
                )
                val s = lock.update(
                    nowMs, progress.toFloat(), outPlaying, inPlaying,
                    base = (speed * p.inRate).toFloat(), incomingPlaybackRate = p.inRate, errorSeconds = error,
                )
                if (!s.isNaN()) {
                    inSpeed = s
                    curInSpeed = s
                    println(
                        "Transition: phase median=${fmt(lock.lastMedian * 1000)}ms -> in speed ${fmt(s.toDouble())} " +
                            "(nudging=${lock.nudging})",
                    )
                }
                if (hostSec >= nextLog) {
                    nextLog = hostSec + LOG_EVERY_SEC
                    println(logLine(l, progress, error))
                }
            }
        } else if (hostSec >= nextLog) {
            nextLog = hostSec + LOG_EVERY_SEC
            println(logLine(l, progress, Double.NaN))
        }
        return verdict
    }

    private fun logLine(l: Live, progress: Double, error: Double): String {
        val out = IosDsp.deck(l.outKey)
        val inn = IosDsp.deck(l.inKey)
        fun side(d: IosDeck?) = if (d == null) "-" else
            "lp=${fmt(d.lastLowPassHz.toDouble())} hp=${fmt(d.lastHighPassHz.toDouble())} g=${fmt(d.lastGain.toDouble())}" +
                (if (d.lastEcho) " echo" else "")
        return "Transition: p=${fmt(progress)} out[${side(out)}] in[${side(inn)}] " +
            "speed in=${fmt(curInSpeed.toDouble())} phaseErr=${if (error.isNaN()) "n/a" else fmt(error * 1000) + "ms"}"
    }

    // ---- After the blend: the tempo ease on a track that was stretched for a blend that never came ----

    /** Starts walking a player off a stretch of [rate] it was sped to; false when there is nothing to ease. */
    fun beginEase(rate: Double, analysis: TrackAnalysis, positionMs: Long): Boolean = ease.begin(rate, analysis, positionMs)

    /** The ease has lost the playhead (a seek, a skip): the engine should snap back to the listener's speed. */
    fun easeLost(positionMs: Long): Boolean = ease.lost(positionMs)

    /** The next ease step as an absolute speed, [easeDone] when it has arrived, or NaN when none is due. */
    fun easeStep(positionMs: Long, speed: Float): Float {
        val s = ease.step(positionMs)
        return when {
            s.isNaN() -> Float.NaN
            s == TempoEase.DONE -> EASE_DONE_SPEED
            else -> (speed * s).toFloat()
        }
    }

    const val EASE_DONE_SPEED = -1f

    /** The outgoing deck's stretch to ease from (the plan's), for the engine. */
    val outRate: Double get() = plan?.outRate ?: 1.0
    val outAnalysis: TrackAnalysis get() = plan?.outAnalysis ?: TrackAnalysis()

    private fun fmt(v: Double): String {
        val r = kotlin.math.round(v * 1000) / 1000
        return r.toString()
    }

    private const val HANDOFF_GUARD_SEC = 0.4
    private const val LOG_EVERY_SEC = 0.5

    /** A blend in flight. Immutable but for the filters' bindings, each touched by one deck's audio thread. */
    internal class Live(
        val plan: IosTransitionPlan?,
        val outKey: Long,
        val inKey: Long,
        /** Where the fade starts on the outgoing track, and how long it runs in its seconds, capped by the incoming track. */
        val startSec: Double,
        val span: Double,
        /** The fade as the incoming timeline covers it: a stretched track covers more of its own in the same time. */
        val wallSpanIn: Double,
        val outDurationSec: Double,
    ) {
        private val outFilters = DeckFilters(null, DeckFilters.Side.OUTGOING)
        private val inFilters = DeckFilters(null, DeckFilters.Side.INCOMING)
        val outRender: TransitionRender = make(outFilters)
        val inRender: TransitionRender = make(inFilters)
        val cueSec: Double get() = plan?.cueSec ?: 0.0
        private val wallSpanMs = (wallSpanIn * 1000).toFloat()

        private fun make(f: DeckFilters): TransitionRender {
            val source = plan?.source
            return if (source != null) TransitionRender.of(source, advanced = true, tracks = f) else TransitionRender(tracks = f)
        }

        fun sideOf(key: Long) = when (key) {
            inKey -> INCOMING
            outKey -> OUTGOING
            else -> NONE
        }

        fun renderFor(side: Int) = if (side == INCOMING) inRender else outRender
        fun filtersFor(side: Int) = if (side == INCOMING) inFilters else outFilters

        /** How far through the blend a deck's own clock [t] says it is, unclamped. */
        fun u(side: Int, t: Double): Double =
            if (side == INCOMING) (t - cueSec) / wallSpanIn else (t - startSec) / span

        /** [u] clamped, and for the outgoing deck pushed on by the file running out (Android's `endDrivenProgress`). */
        fun progress(side: Int, t: Double): Double {
            val own = u(side, t).coerceIn(0.0, 1.0)
            if (side == INCOMING || outDurationSec <= 0) return own
            val endDriven = ((span - (outDurationSec - t)) / span).coerceIn(0.0, 1.0)
            return max(own, endDriven)
        }

        /** The fader of [side] at [p], trimmed for the headroom two loud masters need while they overlap. */
        fun level(side: Int, p: Float, u: Float): Float {
            val r = renderFor(side)
            val fader = if (side == INCOMING) r.mixRise(p) else r.mixFall(p)
            return fader * headroomFor(r.mixRise(p), r.outgoingLevel(u, wallSpanMs))
        }
    }
}
