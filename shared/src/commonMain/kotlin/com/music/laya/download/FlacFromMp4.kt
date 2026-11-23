package com.music.laya.download

import com.music.laya.download.Mp4Boxes.boxes
import com.music.laya.download.Mp4Boxes.child
import com.music.laya.download.Mp4Boxes.find
import com.music.laya.download.Mp4Boxes.readU32
import okio.BufferedSink
import okio.Source

/**
 * FLAC-in-MP4 to native FLAC, for a manifest whose segments arrive one at a time.
 *
 * Tidal answers a lossless request with a DASH manifest (iOS rewrites it to HLS first), and its
 * segments are CMAF: one initialisation segment stating the stream, then `moof`+`mdat` fragments
 * whose samples *are* FLAC frames. A native `.flac` is therefore `fLaC`, the metadata blocks the
 * init segment's `dfLa` already carries, then every frame in order — no re-encoding anywhere, so
 * the audio is kept bit for bit.
 *
 * The frames have to be spilled somewhere before the file can be written, because STREAMINFO sits
 * in front of them and states a total sample count that is only known once the last fragment is
 * in — and a hi-res track is far too large to hold in memory while waiting. Hence the two steps:
 * [frames] for each segment as it arrives, then [finish] once they are all spilled.
 *
 * Fetching is left to the caller, which is what lets Android drive it with OkHttp and iOS with
 * Ktor over several segments at once.
 */
object FlacFromMp4 {

    /** What the initialisation segment states about the stream, kept for [Stream.finish]. */
    class Stream internal constructor(
        private val trackId: Long,
        private val timescale: Long,
        private val blocks: List<Pair<Int, ByteArray>>,
        private val trexDuration: Long,
        private val trexSize: Long,
    ) {

        /** Writes one segment's FLAC frames to [out] and returns the stream ticks they cover. */
        fun frames(segment: ByteArray, out: BufferedSink): Long {
            var ticks = 0L
            boxes(segment).filter { it.type == "moof" }.forEach { moof ->
                val samples = Mp4Boxes.samples(segment, moof, trackId, trexDuration, trexSize)
                    ?: error("Unreadable FLAC fragment")
                samples.forEach {
                    out.write(segment, it.offset, it.size)
                    ticks += it.duration
                }
            }
            return ticks
        }

        /**
         * Writes the finished file to [sink]: the magic, the metadata blocks with STREAMINFO's sample
         * count filled in from [ticks], then the frames spilled earlier, read back from [frames].
         *
         * Without that count a reader cannot seek, which is the whole reason the frames were spilled.
         */
        fun finish(ticks: Long, frames: Source, sink: BufferedSink) {
            if (ticks <= 0) error("Manifest held no audio")
            val info = blocks.first().second
            val rate = ((info[10].toLong() and 0xFF) shl 12) or ((info[11].toLong() and 0xFF) shl 4) or
                ((info[12].toLong() and 0xFF) shr 4)
            val totalSamples = if (timescale > 0 && rate > 0) ticks * rate / timescale else ticks
            info[13] = ((info[13].toInt() and 0xF0) or ((totalSamples shr 32).toInt() and 0x0F)).toByte()
            for (i in 0 until 4) info[14 + i] = (totalSamples shr (8 * (3 - i))).toByte()

            sink.write("fLaC".encodeToByteArray())
            blocks.forEachIndexed { i, (type, body) ->
                val last = if (i == blocks.lastIndex) 0x80 else 0
                sink.write(
                    byteArrayOf(
                        (last or type).toByte(),
                        (body.size shr 16).toByte(),
                        (body.size shr 8).toByte(),
                        body.size.toByte(),
                    ),
                )
                sink.write(body)
            }
            sink.writeAll(frames)
        }
    }

    /** Reads the initialisation segment, refusing anything that is not FLAC rather than mangling it. */
    fun open(init: ByteArray): Stream {
        val trak = find(init, "moov", "trak") ?: error("Not an MP4 initialisation segment")
        val trackId = readU32(
            init,
            (child(init, trak, "tkhd") ?: error("No track header")).let {
                it.content + if (init[it.content].toInt() == 1) 20 else 12
            },
        )
        val mdhd = find(init, "moov", "trak", "mdia", "mdhd") ?: error("No media header")
        val timescale = readU32(init, mdhd.content + if (init[mdhd.content].toInt() == 1) 20 else 12)
        val stsd = find(init, "moov", "trak", "mdia", "minf", "stbl", "stsd") ?: error("No sample description")
        // stsd: FullBox header and entry count, then the entries.
        val entry = boxes(init, stsd.content + 8, stsd.end).firstOrNull()
        if (entry?.type != "fLaC") error("Stream is ${entry?.type ?: "unknown"}, not FLAC")
        // AudioSampleEntry: 28 bytes of fields before its child boxes.
        val dfla = boxes(init, entry.content + 28, entry.end).firstOrNull { it.type == "dfLa" }
            ?: error("FLAC stream has no dfLa")
        val trex = find(init, "moov", "mvex", "trex")
        return Stream(
            trackId = trackId,
            timescale = timescale,
            blocks = metadataBlocks(init.copyOfRange(dfla.content + 4, dfla.end)),
            trexDuration = trex?.let { readU32(init, it.content + 12) } ?: 0L,
            trexSize = trex?.let { readU32(init, it.content + 16) } ?: 0L,
        )
    }

    /** `dfLa`'s payload as (type, body) pairs, STREAMINFO first as FLAC requires. */
    private fun metadataBlocks(payload: ByteArray): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var p = 0
        while (p + 4 <= payload.size) {
            val type = payload[p].toInt() and 0x7F
            val length = ((payload[p + 1].toInt() and 0xFF) shl 16) or
                ((payload[p + 2].toInt() and 0xFF) shl 8) or (payload[p + 3].toInt() and 0xFF)
            if (p + 4 + length > payload.size) break
            out += type to payload.copyOfRange(p + 4, p + 4 + length)
            val last = payload[p].toInt() and 0x80 != 0
            p += 4 + length
            if (last) break
        }
        if (out.firstOrNull()?.let { it.first == 0 && it.second.size == 34 } != true) error("FLAC stream has no STREAMINFO")
        return out
    }
}
