package com.music.laya.ios

import com.music.laya.playback.Karaoke
import com.music.laya.playback.KaraokeProcessor
import com.music.laya.playback.ReverbProcessor
import com.music.laya.playback.VocalStems
import com.music.laya.playback.ReverbSettings
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set

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
 * Karaoke, then spatial widening, then reverb (Android's DspChain order, minus the stages iOS lacks) for the Swift
 * audio tap (iosApp/iosApp/EqualizerTap.swift). [process] runs on the realtime audio thread:
 * no allocation, no locks. Karaoke is classic centre-cancel until [bindStems] hands it the track's neural vocal.
 */
@OptIn(ExperimentalForeignApi::class)
object IosDsp {
    private val reverb = ReverbProcessor()
    private val widener = StereoWidener()
    @kotlin.concurrent.Volatile
    private var spatial = false
    private val karaoke = KaraokeProcessor(
        Karaoke::reportUnavailable,
        Karaoke::reportNeural,
        // The spectral estimate covers while the model catches up (a seek, or switching on),
        // rather than the vocal leaking for those seconds.
        holdSeconds = 0,
    )

    @kotlin.concurrent.Volatile
    private var resetPending = false
    private var samples = FloatArray(0)
    private var rate = 0
    private var channels = 0
    private var frameAt = Long.MIN_VALUE

    /** Whether the tap needs to call [process]: on, or off with a tail / crossfade still running. */
    val active: Boolean get() = spatial || reverb.isEnabled || reverb.isActive || karaoke.isEnabled || karaoke.isActive

    internal fun setReverb(enabled: Boolean, settings: ReverbSettings) = reverb.setTarget(enabled, settings)
    internal fun setSpatial(enabled: Boolean) { spatial = enabled }
    internal fun setKaraoke(enabled: Boolean, level: Float) { karaoke.setEnabled(enabled); karaoke.setLevel(level) }

    /** The neural vocal of the track playing (IosPlayer), or null for none. */
    internal fun bindStems(stems: VocalStems?) = karaoke.bind(stems)

    /** Not realtime. Taps of queued items prepare while another plays, so only a format change reconfigures. */
    fun prepare(sampleRate: Int, channelCount: Int, maxFrames: Int) {
        if (sampleRate != rate || channelCount != channels) {
            rate = sampleRate; channels = channelCount
            karaoke.configure(sampleRate, channelCount)
            widener.configure(sampleRate, channelCount)
            reverb.configure(sampleRate, channelCount)
        }
        if (samples.size < maxFrames * channelCount) samples = FloatArray(maxFrames * channelCount)
    }

    /** A seek or a new track: the processors drop their history on the next block. */
    fun reset() { resetPending = true }

    /** [data]: [frames] interleaved frames of the configured channel count, processed in place. */
    fun process(data: CPointer<FloatVar>, frames: Int, mediaTimeUs: Long) {
        val n = frames * channels
        val s = samples
        if (n == 0 || n > s.size) return
        if (resetPending) { resetPending = false; karaoke.reset(); widener.reset(); reverb.reset(); frameAt = Long.MIN_VALUE }
        // AVPlayer's tap timestamps wander by a block or two, which the stems read as a seek and
        // realign on. Counted from the first block instead, like Android's sink position; only a
        // jump of over a second (a seek or loop that skipped reset()) re-reads the tap's clock.
        val time = if (mediaTimeUs == Long.MIN_VALUE || rate <= 0) {
            frameAt = Long.MIN_VALUE
            mediaTimeUs
        } else {
            val tapFrame = mediaTimeUs * rate / 1_000_000
            if (frameAt == Long.MIN_VALUE || kotlin.math.abs(tapFrame - frameAt) > rate) frameAt = tapFrame
            (frameAt * 1_000_000 / rate).also { frameAt += frames }
        }
        for (i in 0 until n) s[i] = data[i]
        karaoke.process(s, n, time)
        if (spatial) widener.process(s, n)
        reverb.process(s, n)
        for (i in 0 until n) data[i] = s[i]
    }
}
