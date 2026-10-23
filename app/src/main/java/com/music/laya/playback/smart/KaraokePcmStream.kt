package com.music.laya.playback.smart

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import com.music.laya.playback.karaoke.PcmStream
import java.io.IOException
import java.nio.ByteOrder

/**
 * The karaoke separator's own decode of a track, through the platform codec —
 * see [com.music.laya.playback.karaoke.KaraokeStems]. Planar stereo; mono is
 * doubled, anything wider keeps its front pair, as the sink's karaoke stage does.
 */
internal class KaraokePcmStream private constructor(
    private val source: MediaDataSource,
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    override val sampleRate: Int,
    override val durationFrames: Long,
    private var channels: Int,
) : PcmStream {

    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var float = false

    /**
     * To the sync sample at or before [frame], counting on from its timestamp.
     * Coarse — WebM's are whole milliseconds — but the stems measure that.
     */
    override fun seek(frame: Long): Long {
        extractor.seekTo(frame * 1_000_000 / sampleRate, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false
        val landedUs = extractor.sampleTime
        return if (landedUs < 0) -1L else landedUs * sampleRate / 1_000_000
    }

    override fun next(): Array<FloatArray>? {
        while (true) {
            if (!inputDone) {
                // Not waited on: the codec usually has room, and waiting here
                // when it hasn't held up every output behind it.
                val inputIndex = codec.dequeueInputBuffer(0)
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex) ?: throw IOException("no input buffer")
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outputIndex = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    float = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                outputIndex >= 0 -> {
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val chunk = if (info.size > 0) convert(outputIndex) else null
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (chunk != null) return chunk
                    if (end) return null
                }
            }
        }
    }

    private fun convert(index: Int): Array<FloatArray> {
        val buffer = codec.getOutputBuffer(index)!!.duplicate().order(ByteOrder.nativeOrder())
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val width = channels.coerceAtLeast(1)
        val frames = info.size / (width * if (float) 4 else 2)
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        if (float) {
            val values = buffer.asFloatBuffer()
            for (frame in 0 until frames) {
                left[frame] = values.get(frame * width)
                right[frame] = values.get(frame * width + if (width > 1) 1 else 0)
            }
        } else {
            val values = buffer.asShortBuffer()
            for (frame in 0 until frames) {
                left[frame] = values.get(frame * width) / 32_768f
                right[frame] = values.get(frame * width + if (width > 1) 1 else 0) / 32_768f
            }
        }
        return arrayOf(left, right)
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
        runCatching { source.close() }
    }

    companion object {
        private const val OUTPUT_TIMEOUT_US = 2_000L

        /** Opens [source] for decoding, taking ownership of it; null when it isn't decodable audio. */
        fun open(source: MediaDataSource): KaraokePcmStream? {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            return try {
                extractor.setDataSource(source)
                val track = (0 until extractor.trackCount).first { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION) * rate / 1_000_000
                } else {
                    -1L
                }
                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(format, null, null, 0)
                codec.start()
                KaraokePcmStream(
                    source = source,
                    extractor = extractor,
                    codec = codec,
                    sampleRate = rate,
                    durationFrames = duration,
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                )
            } catch (error: Exception) {
                runCatching { codec?.release() }
                runCatching { extractor.release() }
                runCatching { source.close() }
                null
            }
        }
    }
}
