package com.music.laya

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.laya.playback.TransitionFilterProcessor
import com.music.laya.playback.audio.AudioBlock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Pins [TransitionFilterProcessor]'s output bit for bit. The hashes were taken from the
 * implementation before its DSP moved to shared code (TransitionFilterDsp), so a pass proves the
 * move changed no sample.
 */
@UnstableApi
class TransitionFilterGoldenTest {
    private fun fnv(h: Long, bits: Int): Long = (h xor (bits.toLong() and 0xffffffffL)) * 0x100000001b3L

    @Test
    fun `float path output is unchanged`() {
        val rate = 48_000
        val p = TransitionFilterProcessor().apply { configure(rate, 2) }
        val rnd = Random(7)
        val block = AudioBlock(channelCount = 2, capacityFrames = 480)
        var h = SEED
        for (b in 0 until 200) {
            val t = b / 200f
            when {
                b < 20 -> p.setCutoffs(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
                b < 120 -> p.setCutoffs(20000f * Math.pow(0.015, (b - 20) / 100.0).toFloat(), 20f + 180f * t)
                else -> p.setCutoffs(500f, 180f)
            }
            if (b == 60) p.setEcho(0.25f, 0.8f, 1f)
            if (b == 100) p.setEcho(0.25f, 0f, 0f)
            if (b == 150) p.parkEcho()
            if (b == 170) p.open()
            for (i in 0 until 960) block.samples[i] = rnd.nextFloat() * 1.6f - 0.8f
            block.setFrameCount(480)
            p.process(block)
            for (i in 0 until 960) h = fnv(h, block.samples[i].toRawBits())
        }
        println("GOLDEN float=$h")
        assertEquals(FLOAT_HASH, h)
    }

    @Test
    fun `pcm16 path output is unchanged`() {
        val p = TransitionFilterProcessor()
        p.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val rnd = Random(9)
        var h = SEED
        for (b in 0 until 120) {
            if (b == 10) p.setCutoffs(3000f, 400f)
            if (b == 80) p.setCutoffs(20000f, 20f)
            val input = ByteBuffer.allocate(441 * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until 882) input.putShort((rnd.nextInt(60000) - 30000).toShort())
            input.flip()
            p.queueInput(input)
            val out = p.output.order(ByteOrder.nativeOrder())
            while (out.hasRemaining()) h = fnv(h, out.short.toInt())
        }
        println("GOLDEN pcm16=$h")
        assertEquals(PCM16_HASH, h)
    }

    private companion object {
        const val SEED = -3750763034362895579L
        const val FLOAT_HASH = 4250403824794652242L
        const val PCM16_HASH = -4033891025037571435L
    }
}
