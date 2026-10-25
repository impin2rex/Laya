package com.music.laya.ios

import com.music.laya.playback.Karaoke
import com.music.laya.playback.KaraokeProcessor
import com.music.laya.playback.ReverbProcessor
import com.music.laya.playback.VocalStems
import com.music.laya.playback.ReverbSettings
import com.music.laya.playback.smart.TransitionFilterDsp
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import platform.Foundation.NSLock
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.min

/**
 * Stereo widening for Settings > Spatial audio: Android's SpatialAudioProcessor (app/, media3-bound so
 * not shared) — mid/side widening plus a short low-passed cross-feed. Realtime-safe: the delay line is
 * sized in [configure], [process] allocates nothing.
 */
private class StereoWidener {
    private val widthGain = 2.5f
    private val outputGain = 0.82f
    private val crossfeedGain = 0.2f
    private val lowpassCoeff = 0.3f
    private var delayLeft = FloatArray(0)
    private var delayRight = FloatArray(0)
    private var index = 0
    private var lowpassLeft = 0f
    private var lowpassRight = 0f

    fun configure(sampleRate: Int, channels: Int) {
        val size = if (channels == 2 && sampleRate > 0) (sampleRate * 15 / 1000f).toInt().coerceAtLeast(1) else 0
        if (delayLeft.size != size) { delayLeft = FloatArray(size); delayRight = FloatArray(size) }
        reset()
    }

    fun reset() {
        delayLeft.fill(0f); delayRight.fill(0f)
        index = 0; lowpassLeft = 0f; lowpassRight = 0f
    }

    /** [n] interleaved stereo samples, in place; anything but stereo passes through. */
    fun process(s: FloatArray, n: Int) {
        val size = delayLeft.size
        if (size == 0) return
        var i = 0
        while (i + 1 < n) {
            val left = s[i]
            val right = s[i + 1]
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f * widthGain
            lowpassLeft += lowpassCoeff * (delayRight[index] - lowpassLeft)
            lowpassRight += lowpassCoeff * (delayLeft[index] - lowpassRight)
            delayLeft[index] = left
            delayRight[index] = right
            index = (index + 1) % size
            s[i] = (mid + side + lowpassLeft * crossfeedGain) * outputGain
            s[i + 1] = (mid - side + lowpassRight * crossfeedGain) * outputGain
            i += 2
        }
    }
}

/**
 * One deck's audio chain, the counterpart of one Android `DspChain` (every player there has its own): karaoke,
 * spatial widening, then — after the Swift equaliser — reverb and the transition filter with its fader. Each tap
 * (iosApp/iosApp/EqualizerTap.swift) holds one, so during a blend the two tracks never feed a shared stateful
 * processor, and the reverb tail of the track leaving rings out on its own deck.
 *
 * [processPre] and [processPost] run on the realtime audio thread: no allocation, no locks.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDeck internal constructor() {
    /** The player item this deck's tap sits on (its address), 0 while the deck is free. */
    @Volatile
    var key = 0L
        internal set

    internal val reverb = ReverbProcessor()
    private val widener = StereoWidener()
    internal val karaoke = KaraokeProcessor(
        Karaoke::reportUnavailable,
        Karaoke::reportNeural,
        // The spectral estimate covers while the model catches up (a seek, or switching on),
        // rather than the vocal leaking for those seconds.
        holdSeconds = 0,
    )

    /** The transition filter and echo of this deck's track; aimed from [IosTransition]'s renders. */
    internal val filter = TransitionFilterDsp()

    @Volatile
    private var resetPending = false
    private var samples = FloatArray(0)
    private var rate = 0
    private var channels = 0
    private var frameAt = Long.MIN_VALUE

    // ---- Transition bookkeeping (audio thread writes, main thread reads for logs and the phase lock) ----
    private var side = IosTransition.NONE
    private var lastEnd = Double.NaN

    /** Media time and host time of the last buffer this deck produced, for the phase lock. */
    @Volatile var lastMediaSec = Double.NaN; private set
    @Volatile var lastHostSec = Double.NaN; private set
    @Volatile var lastProgress = 0.0; private set
    @Volatile var lastGain = 1f; private set
    @Volatile var lastLowPassHz = TransitionFilterDsp.OPEN_HZ; private set
    @Volatile var lastHighPassHz = TransitionFilterDsp.OFF_HZ; private set
    @Volatile var lastEcho = false; private set

    /** Whether the tap needs to call [processPre]: widening, or Karaoke on (or ringing out). */
    fun wantsPre(): Boolean = IosDsp.spatial || karaoke.isEnabled || karaoke.isActive

    /** Whether the tap needs to call [processPost]: reverb (or its tail), or this deck is in a blend. */
    fun wantsPost(): Boolean =
        reverb.isEnabled || reverb.isActive || IosTransition.live != null || side != IosTransition.NONE ||
            filter.isFiltering || filter.isEchoing

    /** Not realtime. A tap prepares when its pipeline is built; only a format change reconfigures. */
    fun prepare(sampleRate: Int, channelCount: Int, maxFrames: Int) {
        if (sampleRate != rate || channelCount != channels) {
            rate = sampleRate; channels = channelCount
            karaoke.configure(sampleRate, channelCount)
            widener.configure(sampleRate, channelCount)
            reverb.configure(sampleRate, channelCount)
            // The echo's ring is sized here so setting an echo on the audio thread allocates nothing.
            filter.configure(sampleRate, channelCount, preallocateEcho = true)
        }
        if (samples.size < maxFrames * channelCount) samples = FloatArray(maxFrames * channelCount)
    }

    /** A seek or a new track: the processors drop their history on the next block. */
    fun reset() { resetPending = true }

    private fun applyReset() {
        if (!resetPending) return
        resetPending = false
        karaoke.reset(); widener.reset(); reverb.reset(); filter.flush()
        frameAt = Long.MIN_VALUE
        lastEnd = Double.NaN
    }

    /** Karaoke, then spatial widening, on [frames] interleaved frames of the configured channel count, in place. */
    fun processPre(data: CPointer<FloatVar>, frames: Int, mediaTimeUs: Long) {
        val n = frames * channels
        val s = samples
        if (n == 0 || n > s.size) return
        applyReset()
        // AVPlayer's tap timestamps wander by a block or two, which the stems read as a seek and
        // realign on. Counted from the first block instead, like Android's sink position; only a
        // jump of over a second (a seek or loop that skipped reset()) re-reads the tap's clock.
        val time = if (mediaTimeUs == Long.MIN_VALUE || rate <= 0) {
            frameAt = Long.MIN_VALUE
            mediaTimeUs
        } else {
            val tapFrame = mediaTimeUs * rate / 1_000_000
            if (frameAt == Long.MIN_VALUE || abs(tapFrame - frameAt) > rate) frameAt = tapFrame
            (frameAt * 1_000_000 / rate).also { frameAt += frames }
        }
        for (i in 0 until n) s[i] = data[i]
        karaoke.process(s, n, time)
        if (IosDsp.spatial) widener.process(s, n)
        for (i in 0 until n) data[i] = s[i]
    }

    /**
     * Reverb, then the transition: this deck's filter ride, echo out and fader, driven by where [mediaSec] puts the
     * buffer in its own track — Android's order, and its controller's clock, per deck. [hostSec] is when the tap ran.
     */
    fun processPost(data: CPointer<FloatVar>, frames: Int, mediaSec: Double, hostSec: Double) {
        val n = frames * channels
        val s = samples
        if (n == 0 || n > s.size) return
        applyReset()
        for (i in 0 until n) s[i] = data[i]
        if (reverb.isEnabled || reverb.isActive) reverb.process(s, n)
        transition(s, frames, mediaSec, hostSec)
        for (i in 0 until n) data[i] = s[i]
    }

    private fun transition(s: FloatArray, frames: Int, mediaSec: Double, hostSec: Double) {
        val live = IosTransition.live
        val mine = live?.sideOf(key) ?: IosTransition.NONE
        if (mine == IosTransition.NONE) {
            // Not (or no longer) in a blend: the filter glides open and the echo is cut, as Android's finish() does.
            if (side != IosTransition.NONE) { side = IosTransition.NONE; filter.open(); filter.parkEcho() }
            if (filter.isFiltering) filter.filter(s, frames)
            return
        }
        live ?: return
        val render = live.renderFor(mine)
        if (side != mine) {
            side = mine
            live.filtersFor(mine).dsp = filter
            filter.open()
            filter.parkEcho()
        }
        val sr = rate.toDouble()
        val t0 = if (mediaSec.isNaN()) lastEnd else mediaSec
        if (t0.isNaN() || sr <= 0.0) return
        lastMediaSec = t0
        lastHostSec = hostSec
        lastEnd = t0 + frames / sr
        var f = 0
        var pb = 0.0
        var gb = 1f
        while (f < frames) {
            val n = min(SUB_BLOCK, frames - f)
            val ta = t0 + f / sr
            val tb = t0 + (f + n) / sr
            val pa = live.progress(mine, ta).toFloat()
            // The ride is aimed once per sub-block at the blend as it is now, as Android's 30 ms tick aims it.
            if (mine == IosTransition.INCOMING) {
                render.rideFilters(pa, null)
            } else if (pa > 0f) {
                render.rideFilters(pa, pa)
            }
            filter.process(s, n, f)
            val ga = live.level(mine, pa, live.u(mine, ta).toFloat())
            pb = live.progress(mine, tb)
            gb = live.level(mine, pb.toFloat(), live.u(mine, tb).toFloat())
            val step = (gb - ga) / n
            var g = ga
            val ch = channels
            for (i in 0 until n) {
                g += step
                val base = (f + i) * ch
                for (c in 0 until ch) s[base + c] *= g
            }
            f += n
        }
        lastProgress = pb
        lastGain = gb
        lastLowPassHz = filter.lowPassTargetHz
        lastHighPassHz = filter.highPassTargetHz
        lastEcho = filter.isEchoing
    }

    /** Where this deck's track is at host time [hostSec] if it carries on at [speed], from its last buffer. */
    fun mediaAt(hostSec: Double, speed: Double): Double {
        val m = lastMediaSec
        val h = lastHostSec
        return if (m.isNaN() || h.isNaN()) Double.NaN else m + (hostSec - h) * speed
    }

    private companion object {
        /** Frames between fader steps and filter aims; the filter's own coefficient glide is 64 too. */
        const val SUB_BLOCK = 64
    }
}

/**
 * The pool of decks and the settings they share. Reverb, karaoke and spatial settings are applied to every deck
 * (from the main thread, where they may allocate); the audio thread only reads them.
 */
@OptIn(ExperimentalForeignApi::class)
object IosDsp {
    private const val DECKS = 4
    private val decks = Array(DECKS) { IosDeck() }
    private val taken = BooleanArray(DECKS)
    private val lock = NSLock()

    @Volatile
    internal var spatial = false
        private set
    private var reverbEnabled = false
    private var reverbSettings = ReverbSettings.DEFAULT
    private var karaokeEnabled = false
    private var karaokeLevel = 0f

    /** The playing item (as opposed to one fading in), whose deck takes the track's neural vocal. */
    @Volatile
    private var currentKey = 0L
    private var stems: VocalStems? = null

    internal fun setReverb(enabled: Boolean, settings: ReverbSettings) {
        reverbEnabled = enabled; reverbSettings = settings
        decks.forEach { it.reverb.setTarget(enabled, settings) }
    }

    internal fun setSpatial(enabled: Boolean) { spatial = enabled }

    internal fun setKaraoke(enabled: Boolean, level: Float) {
        karaokeEnabled = enabled; karaokeLevel = level
        decks.forEach { it.karaoke.setEnabled(enabled); it.karaoke.setLevel(level) }
    }

    /** The neural vocal of the track playing (IosPlayer), or null for none. */
    internal fun bindStems(stems: VocalStems?) {
        this.stems = stems
        deck(currentKey)?.karaoke?.bind(stems)
    }

    /** The playing item's address; from now its deck is the one the neural vocal and a [reset] belong to. */
    fun setCurrent(key: Long) {
        currentKey = key
        deck(key)?.karaoke?.bind(stems)
    }

    /** A deck for the tap on the item at [key]. Main thread. */
    fun acquire(key: Long): IosDeck {
        lock.lock()
        val i = taken.indexOfFirst { !it }
        val deck = if (i >= 0) decks[i].also { taken[i] = true } else IosDeck()
        lock.unlock()
        deck.key = key
        deck.reset()
        deck.reverb.setTarget(reverbEnabled, reverbSettings)
        deck.karaoke.setEnabled(karaokeEnabled)
        deck.karaoke.setLevel(karaokeLevel)
        deck.karaoke.bind(if (key == currentKey) stems else null)
        return deck
    }

    /** The tap on that item is gone. */
    fun release(deck: IosDeck) {
        lock.lock()
        deck.key = 0L
        val i = decks.indexOfFirst { it === deck }
        if (i >= 0) taken[i] = false
        lock.unlock()
    }

    /** The deck on the item at [key], if its tap is installed. */
    fun deck(key: Long): IosDeck? {
        if (key == 0L) return null
        for (d in decks) if (d.key == key) return d
        return null
    }

    /** A seek or a new track on the playing item: its processors drop their history. */
    fun reset() { deck(currentKey)?.reset() }
}
