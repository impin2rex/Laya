/*
 * Shazam audio fingerprint ("DejaVu" signature), ported to Kotlin common from
 * ArchiveTune's shazamkit module (© Rukamori, GPL-3.0,
 * github.com/rukamori/ArchiveTune), itself following SongRec's reverse
 * engineering of the format. JVM-only pieces (ByteBuffer, CRC32, Base64,
 * ByteArrayOutputStream) are replaced with okio and stdlib so it runs on iOS.
 */
package com.music.laya.data.recognition

import okio.Buffer
import kotlin.io.encoding.Base64
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

internal class ShazamSignature(val uri: String, val sampleDurationMs: Long)

/** Signature of [samples] (16 kHz, mono, 16-bit), or null when the audio holds no usable peaks (silence). */
internal fun shazamSignature(samples: ShortArray): ShazamSignature? {
    val gen = SignatureGenerator()
    var pos = 0
    while (pos + 128 <= samples.size) {
        gen.process(samples, pos)
        pos += 128
    }
    return gen.signature()
}

private const val SAMPLE_RATE = 16_000

private class SignatureGenerator {
    private val window = DoubleArray(2048) { i -> 0.5 - 0.5 * cos(2.0 * PI * (i + 1) / 2049) }
    private val fft = Fft(2048)
    private val real = DoubleArray(2048)
    private val imag = DoubleArray(2048)

    private val ring = IntArray(2048)
    private var ringPos = 0

    private val fftOutputs = Array(256) { DoubleArray(1025) }
    private var fftPos = 0

    private val spreadOutputs = Array(256) { DoubleArray(1025) }
    private var spreadPos = 0
    private var spreadWritten = 0

    private var numberSamples = 0
    private val bandToPeaks = HashMap<Int, MutableList<Peak>>()

    fun process(samples: ShortArray, start: Int) {
        numberSamples += 128
        doFft(samples, start)
        doPeakSpreading()
        if (spreadWritten >= 46) doPeakRecognition()
    }

    fun signature(): ShazamSignature? {
        if (bandToPeaks.isEmpty()) return null
        return ShazamSignature(
            uri = "data:audio/vnd.shazam.sig;base64," + Base64.Default.encode(encode()),
            sampleDurationMs = numberSamples * 1000L / SAMPLE_RATE,
        )
    }

    private fun doFft(samples: ShortArray, start: Int) {
        for (i in start until start + 128) {
            ring[ringPos] = samples[i].toInt()
            ringPos = (ringPos + 1) % 2048
        }
        var p = ringPos
        for (i in 0 until 2048) {
            real[i] = ring[p] * window[i]
            imag[i] = 0.0
            p = (p + 1) % 2048
        }
        fft.run(real, imag)
        val out = fftOutputs[fftPos]
        for (k in 0..1024) {
            val v = (real[k] * real[k] + imag[k] * imag[k]) / 131072.0
            out[k] = if (v < 1e-10) 1e-10 else v
        }
        fftPos = (fftPos + 1) % 256
    }

    private fun doPeakSpreading() {
        val origin = fftOutputs[(fftPos - 1).mod(256)]
        val spread = origin.copyOf()
        for (i in 0..1022) spread[i] = max(origin[i], max(origin[i + 1], origin[i + 2]))

        val s1 = spreadOutputs[(spreadPos - 1).mod(256)]
        val s2 = spreadOutputs[(spreadPos - 3).mod(256)]
        val s3 = spreadOutputs[(spreadPos - 6).mod(256)]
        for (bin in 0..1024) {
            s1[bin] = max(spread[bin], s1[bin])
            s2[bin] = max(s1[bin], s2[bin])
            s3[bin] = max(s2[bin], s3[bin])
        }
        spread.copyInto(spreadOutputs[spreadPos])
        spreadPos = (spreadPos + 1) % 256
        spreadWritten++
    }

    private fun doPeakRecognition() {
        val fft46 = fftOutputs[(fftPos - 46).mod(256)]
        val spread49 = spreadOutputs[(spreadPos - 49).mod(256)]

        for (bin in 10..1014) {
            val energy = fft46[bin]
            if (energy < 1.0 / 64.0 || energy < spread49[bin - 1]) continue

            var maxNeighbor = 0.0
            for (o in NEIGHBOR_OFFSETS) maxNeighbor = max(maxNeighbor, spread49[bin + o])
            if (energy <= maxNeighbor) continue

            var maxTimeNeighbor = maxNeighbor
            for (o in TIME_OFFSETS) maxTimeNeighbor = max(maxTimeNeighbor, spreadOutputs[(spreadPos + o).mod(256)][bin - 1])
            if (energy <= maxTimeNeighbor) continue

            val magnitude = magnitude(energy)
            val before = magnitude(fft46[bin - 1])
            val after = magnitude(fft46[bin + 1])
            val variation1 = magnitude * 2 - before - after
            if (variation1 <= 0) continue

            val correctedBin = bin * 64.0 + (after - before) * 32.0 / variation1
            val hz = correctedBin * (SAMPLE_RATE / 2.0 / 1024.0 / 64.0)
            val band = when {
                hz < 250 -> continue
                hz <= 520 -> 0
                hz <= 1450 -> 1
                hz <= 3500 -> 2
                hz <= 5500 -> 3
                else -> continue
            }
            bandToPeaks.getOrPut(band) { mutableListOf() }
                .add(Peak(spreadWritten - 46, magnitude.toInt(), correctedBin.toInt()))
        }
    }

    private fun magnitude(energy: Double) = ln(max(1.0 / 64.0, energy)) * 1477.3 + 6144.0

    private fun encode(): ByteArray {
        val contents = Buffer()
        for ((band, peaks) in bandToPeaks.entries.sortedBy { it.key }) {
            val bytes = Buffer()
            var lastPass = 0
            for (peak in peaks) {
                if (peak.pass - lastPass >= 255) {
                    bytes.writeByte(0xFF)
                    bytes.writeIntLe(peak.pass)
                    lastPass = peak.pass
                }
                bytes.writeByte(peak.pass - lastPass)
                bytes.writeShortLe(peak.magnitude)
                bytes.writeShortLe(peak.bin)
                lastPass = peak.pass
            }
            val size = bytes.size.toInt()
            contents.writeIntLe(0x60030040 + band)
            contents.writeIntLe(size)
            contents.writeAll(bytes)
            repeat((-size).mod(4)) { contents.writeByte(0) }
        }

        val contentSize = contents.size.toInt() + 8
        val out = Buffer()
            .writeIntLe(0xCAFE2580.toInt())
            .writeIntLe(0) // CRC32, patched below
            .writeIntLe(contentSize)
            .writeIntLe(0x94119C00.toInt())
            .writeIntLe(0).writeIntLe(0).writeIntLe(0)
            .writeIntLe(3 shl 27) // sample rate id: 16 kHz
            .writeIntLe(0).writeIntLe(0)
            .writeIntLe((numberSamples + SAMPLE_RATE * 0.24).toInt())
            .writeIntLe((15 shl 19) + 0x40000)
            .writeIntLe(0x40000000)
            .writeIntLe(contentSize)
        out.writeAll(contents)

        val bytes = out.readByteArray()
        val crc = crc32(bytes, 8)
        for (i in 0 until 4) bytes[4 + i] = (crc ushr (8 * i)).toByte()
        return bytes
    }

    private class Peak(val pass: Int, val magnitude: Int, val bin: Int)

    private companion object {
        val NEIGHBOR_OFFSETS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
        val TIME_OFFSETS = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)
    }
}

private val CRC_TABLE = IntArray(256) { n ->
    var c = n
    repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
    c
}

internal fun crc32(bytes: ByteArray, from: Int = 0): Int {
    var c = -1
    for (i in from until bytes.size) c = CRC_TABLE[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
    return c.inv()
}

/** Iterative radix-2 FFT, in place. */
private class Fft(private val n: Int) {
    private val cosTable = DoubleArray(n / 2) { cos(2.0 * PI * it / n) }
    private val sinTable = DoubleArray(n / 2) { sin(2.0 * PI * it / n) }
    private val bitReversal = IntArray(n).also { out ->
        val bits = n.countTrailingZeroBits()
        for (i in 0 until n) out[i] = reverseBits(i, bits)
    }

    fun run(real: DoubleArray, imag: DoubleArray) {
        for (i in 0 until n) {
            val j = bitReversal[i]
            if (j > i) {
                real[i] = real[j].also { real[j] = real[i] }
                imag[i] = imag[j].also { imag[j] = imag[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (j in 0 until half) {
                    val a = i + j
                    val b = a + half
                    val tre = real[b] * cosTable[k] + imag[b] * sinTable[k]
                    val tim = -real[b] * sinTable[k] + imag[b] * cosTable[k]
                    real[b] = real[a] - tre
                    imag[b] = imag[a] - tim
                    real[a] += tre
                    imag[a] += tim
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}

private fun reverseBits(value: Int, bits: Int): Int {
    var x = value
    var y = 0
    repeat(bits) {
        y = (y shl 1) or (x and 1)
        x = x ushr 1
    }
    return y
}
