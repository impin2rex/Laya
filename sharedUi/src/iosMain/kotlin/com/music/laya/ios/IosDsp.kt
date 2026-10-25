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
 * Karaoke then reverb (Android's DspChain order, minus the stages iOS lacks) for the Swift
 * audio tap (iosApp/iosApp/EqualizerTap.swift). [process] runs on the realtime audio thread:
 * no allocation, no locks. Karaoke is classic centre-cancel until [bindStems] hands it the track's neural vocal.
 */
@OptIn(ExperimentalForeignApi::class)
object IosDsp {
    private val reverb = ReverbProcessor()
    private val karaoke = KaraokeProcessor(Karaoke::reportUnavailable, Karaoke::reportNeural)

    @kotlin.concurrent.Volatile
    private var resetPending = false
    private var samples = FloatArray(0)
    private var rate = 0
    private var channels = 0
    private var frameAt = Long.MIN_VALUE

    /** Whether the tap needs to call [process]: on, or off with a tail / crossfade still running. */
    val active: Boolean get() = reverb.isEnabled || reverb.isActive || karaoke.isEnabled || karaoke.isActive

    internal fun setReverb(enabled: Boolean, settings: ReverbSettings) = reverb.setTarget(enabled, settings)
    internal fun setKaraoke(enabled: Boolean, level: Float) { karaoke.setEnabled(enabled); karaoke.setLevel(level) }

    /** The neural vocal of the track playing (IosPlayer), or null for none. */
    internal fun bindStems(stems: VocalStems?) = karaoke.bind(stems)

    /** Not realtime. Taps of queued items prepare while another plays, so only a format change reconfigures. */
    fun prepare(sampleRate: Int, channelCount: Int, maxFrames: Int) {
        if (sampleRate != rate || channelCount != channels) {
            rate = sampleRate; channels = channelCount
            karaoke.configure(sampleRate, channelCount)
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
        if (resetPending) { resetPending = false; karaoke.reset(); reverb.reset(); frameAt = Long.MIN_VALUE }
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
        reverb.process(s, n)
        for (i in 0 until n) data[i] = s[i]
    }
}
