package com.music.laya.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.laya.playback.audio.AudioBlock
import com.music.laya.playback.audio.PcmBoundary
import com.music.laya.playback.smart.TransitionFilterDsp
import java.nio.ByteOrder

/**
 * The Media3 shell around [TransitionFilterDsp], the filter a track rides through an Automix
 * transition: a low-pass that can close over the outgoing track, a high-pass that can lift the
 * low end out of one side of a blend, and the echo out.
 *
 * The DSP itself lives in shared code so iOS runs the very same filters; this class only adapts
 * it to Media3's 16-bit path and to the float [AudioBlock] chain.
 */
@UnstableApi
class TransitionFilterProcessor : BaseAudioProcessor() {

    private val dsp = TransitionFilterDsp()

    private var channelCount = 0

    /** Float scratch for the 16-bit path, grown on demand. */
    private var scratch = FloatArray(0)

    /**
     * How far ahead of the speaker this filter is running, in media microseconds; written by the
     * sink, 0 when it has nothing to say.
     *
     * The filter runs where audio is *written*, not where it is heard, and on a float route the
     * output buffer behind it holds about four seconds — Media3 sizes it for 8x playback once the
     * AudioTrack is doing the speed. A cutoff set now is therefore heard seconds from now, while
     * a fader set now is heard at once. [CrossfadeController] reads this to aim each filter at
     * the point in the blend its audio will actually be played.
     */
    var leadUs: Long
        get() = dsp.leadUs
        set(value) {
            dsp.leadUs = value
        }

    /**
     * Aims the filter. [lowPassHz] at or above [OPEN_HZ] and [highPassHz] at or below [OFF_HZ]
     * mean "not filtering", which is the state this returns to between transitions.
     */
    fun setCutoffs(lowPassHz: Float, highPassHz: Float) = dsp.setCutoffs(lowPassHz, highPassHz)

    /** Parks both filters. Glided, not snapped. */
    fun open() = dsp.open()

    val isFiltering: Boolean
        get() = dsp.isFiltering

    /** Engages the echo out, or moves it — see [TransitionFilterDsp.setEcho]. */
    fun setEcho(delaySeconds: Float, send: Float, dry: Float) = dsp.setEcho(delaySeconds, send, dry)

    /**
     * Drops the echo and puts the dry signal back. For the end of a transition, when the track it
     * belonged to is gone or going: a tail still ringing is cut, not faded.
     */
    fun parkEcho() = dsp.parkEcho()

    /**
     * Configures the Float32 DSP engine for [sampleRate] and [channelCount].
     */
    fun configure(sampleRate: Int, channelCount: Int) {
        this.channelCount = channelCount
        dsp.configure(sampleRate, channelCount)
    }

    /**
     * Processes interleaved Float32 audio samples in [block] in-place.
     * Preserves dynamic headroom without clamping to [-1.0f, +1.0f].
     */
    fun process(block: AudioBlock) = dsp.process(block.samples, block.frameCount)

    /**
     * 16-bit PCM only.
     */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount < 1) {
            Log.w(
                TAG,
                "Transition filtering inactive: encoding=${inputAudioFormat.encoding} " +
                    "channels=${inputAudioFormat.channelCount} is not 16-bit PCM",
            )
            return AudioProcessor.AudioFormat.NOT_SET
        }
        configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) = dsp.flush()

    override fun onReset() {
        dsp.reset()
        channelCount = 0
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val bytesPerFrame = BYTES_PER_SAMPLE * channelCount
        if (bytesPerFrame == 0) return
        val frameCount = inputBuffer.remaining() / bytesPerFrame
        if (frameCount == 0) return
        val outputBuffer = replaceOutputBuffer(frameCount * bytesPerFrame)

        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        val samples = frameCount * channelCount
        if (scratch.size < samples) scratch = FloatArray(samples)
        val start = inputBuffer.position()
        val invScale = 1.0f / 32768.0f
        for (i in 0 until samples) scratch[i] = inputBuffer.getShort(start + i * BYTES_PER_SAMPLE).toFloat() * invScale
        if (dsp.filter(scratch, frameCount)) {
            for (i in 0 until samples) outputBuffer.putShort(PcmBoundary.clamp16FromFloat(scratch[i]))
            inputBuffer.position(start + samples * BYTES_PER_SAMPLE)
        } else {
            outputBuffer.put(inputBuffer)
        }
        outputBuffer.flip()
    }

    companion object {
        private const val TAG = "LayaTransitionFilter"

        /** A low-pass at or above this is doing nothing audible, so it counts as off. */
        const val OPEN_HZ = TransitionFilterDsp.OPEN_HZ

        /** A high-pass at or below this is doing nothing audible, so it counts as off. */
        const val OFF_HZ = TransitionFilterDsp.OFF_HZ

        /** Nothing musical wants the low end lifted above this, and a typo shouldn't be able to. */
        const val MAX_HIGH_PASS_HZ = TransitionFilterDsp.MAX_HIGH_PASS_HZ

        private const val BYTES_PER_SAMPLE = 2
    }
}
