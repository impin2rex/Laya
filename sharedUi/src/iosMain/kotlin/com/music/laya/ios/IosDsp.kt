package com.music.laya.ios

import com.music.laya.playback.Karaoke
import com.music.laya.playback.KaraokeProcessor
import com.music.laya.playback.ReverbProcessor
import com.music.laya.playback.ReverbSettings
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set

/**
 * Karaoke then reverb (Android's DspChain order, minus the stages iOS lacks) for the Swift
 * audio tap (iosApp/iosApp/EqualizerTap.swift). [process] runs on the realtime audio thread:
 * no allocation, no locks. Classic centre-cancel karaoke only; no stems are ever bound, so
 * the neural path stays off.
 */
@OptIn(ExperimentalForeignApi::class)
object IosDsp {
    private val reverb = ReverbProcessor()
    private val karaoke = KaraokeProcessor(Karaoke::reportUnavailable)

    @kotlin.concurrent.Volatile
    private var resetPending = false
    private var samples = FloatArray(0)
    private var rate = 0
    private var channels = 0

    /** Whether the tap needs to call [process]: on, or off with a tail / crossfade still running. */
    val active: Boolean get() = reverb.isEnabled || reverb.isActive || karaoke.isEnabled || karaoke.isActive

    internal fun setReverb(enabled: Boolean, settings: ReverbSettings) = reverb.setTarget(enabled, settings)
    internal fun setKaraoke(enabled: Boolean, level: Float) { karaoke.setEnabled(enabled); karaoke.setLevel(level) }

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
    fun process(data: CPointer<FloatVar>, frames: Int) {
        val n = frames * channels
        val s = samples
        if (n == 0 || n > s.size) return
        if (resetPending) { resetPending = false; karaoke.reset(); reverb.reset() }
        for (i in 0 until n) s[i] = data[i]
        karaoke.process(s, n)
        reverb.process(s, n)
        for (i in 0 until n) data[i] = s[i]
    }
}
