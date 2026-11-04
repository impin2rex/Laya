package com.music.laya.desktop

import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avutil.AVBufferRef
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX
import org.bytedeco.ffmpeg.global.avcodec.avcodec_get_hw_config
import org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_D3D11VA
import org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_VAAPI
import org.bytedeco.ffmpeg.global.avutil.AV_HWDEVICE_TYPE_VIDEOTOOLBOX
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NONE
import org.bytedeco.ffmpeg.global.avutil.AVCOL_RANGE_JPEG
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT470BG
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT709
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_SMPTE170M
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUVJ420P
import org.bytedeco.ffmpeg.global.avutil.av_buffer_ref
import org.bytedeco.ffmpeg.global.avutil.av_buffer_unref
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.global.avutil.av_hwdevice_ctx_create
import org.bytedeco.ffmpeg.global.avutil.av_hwframe_transfer_data
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_seek_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_image_fill_arrays
import org.bytedeco.ffmpeg.global.avutil.av_image_get_buffer_size
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer

/**
 * The motion artwork, decoded here rather than played by a second media stack — and a music
 * video's picture, which asks for a bigger [maxEdge], no [loop], and seeks.
 */
internal class DesktopCanvasDecoder(
    private val maxEdge: Int = MAX_EDGE,
    private val loop: Boolean = true,
    /** Read in bounded windows — see [DesktopRangeIo]. For googlevideo, which throttles one long request. */
    private val windowed: Boolean = false,
    /**
     * Decode on the machine's video hardware where it has a decoder for the stream — VideoToolbox,
     * D3D11 or VA-API — as a browser does; a 4K VP9 picture is beyond the CPU in real time. Falls
     * back to software, silently, where there is none.
     */
    private val hardware: Boolean = false,
) {
    private var rangeIo: DesktopRangeIo? = null
    private var hwDevice: AVBufferRef? = null
    private var hwPixelFormat = AV_PIX_FMT_NONE
    private var hwFrame: AVFrame? = null

    /** Whether frames are coming off the video hardware; known once the first has. */
    var decodingInHardware: Boolean = false
        private set


    private var format: AVFormatContext? = null
    private var codec: AVCodecContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null
    private var scaler: SwsContext? = null
    private var buffer: BytePointer? = null
    private var view: java.nio.ByteBuffer? = null
    private var planes: PointerPointer<BytePointer>? = null
    private var strides: IntPointer? = null
    private var scalerFormat = -1
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var streamIndex = -1
    private var timeBase = 0.0

    /** Where in the stream the last frame handed back sits, in milliseconds; -1 before one. */
    var framePtsMs: Long = -1L
        private set

    /** Seconds per frame, from the stream's own rate; used to pace playback. */
    var frameIntervalMillis: Long = 40L
        private set

    /** The size frames are handed back at, after scaling. */
    var width: Int = 0
        private set

    var height: Int = 0
        private set

    fun open(url: String, headers: Map<String, String> = emptyMap()): Result<Unit> = runCatching {
        DesktopAudioDecoder.ensureNetworkReady()
        val options = AVDictionary(null)
        if (headers.isNotEmpty()) {
            av_dict_set(
                options,
                "headers",
                headers.entries.joinToString("") { (name, value) -> "$name: $value\r\n" },
                0,
            )
        }
        av_dict_set(options, "rw_timeout", "15000000", 0)

        val opened = if (windowed) {
            DesktopRangeIo(url, headers, VIDEO_WINDOW_BYTES).also { rangeIo = it }.context
        } else {
            AVFormatContext(null)
        }
        val source = if (windowed) null else singleVariant(url, headers) ?: url
        check(avformat_open_input(opened, source, null, options) >= 0) { "could not open the clip" }
        format = opened
        check(avformat_find_stream_info(opened, null as AVDictionary?) >= 0) { "no stream info" }
        streamIndex = av_find_best_stream(
            opened, AVMEDIA_TYPE_VIDEO, -1, -1, null as org.bytedeco.ffmpeg.avcodec.AVCodec?, 0,
        )
        check(streamIndex >= 0) { "no video stream" }

        val stream = opened.streams(streamIndex)
        stream.time_base().let { if (it.den() > 0) timeBase = it.num().toDouble() / it.den() }
        val parameters = stream.codecpar()
        val decoder = avcodec_find_decoder(parameters.codec_id()) ?: error("no decoder for this clip")
        val context = avcodec_alloc_context3(decoder)
        check(avcodec_parameters_to_context(context, parameters) >= 0) { "bad codec parameters" }
        if (hardware) attachHardware(decoder, context)
        // Every core FFmpeg can use: a 1080p VP9 music video is beyond one thread in real time. On
        // the hardware path one thread feeds it; frame threads only contend for the one session.
        context.thread_count(if (hwDevice != null) 1 else 0)
        check(avcodec_open2(context, decoder, null as AVDictionary?) >= 0) { "decoder refused to open" }
        codec = context

        sourceWidth = context.width()
        sourceHeight = context.height()
        check(sourceWidth > 0 && sourceHeight > 0) { "the clip has no size" }

        // Scaled down on the way out.
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(sourceWidth, sourceHeight))
        width = ((sourceWidth * scale).toInt() / 2) * 2
        height = ((sourceHeight * scale).toInt() / 2) * 2

        val rate = stream.avg_frame_rate()
        if (rate.num() > 0 && rate.den() > 0) {
            // Never faster than [MIN_INTERVAL_MS]: a sleeve does not need sixty frames a second,
            // and the cost of one is a bitmap.
            frameIntervalMillis = (1_000L * rate.den() / rate.num()).coerceIn(MIN_INTERVAL_MS, 200L)
        }

        // Wired by FFmpeg rather than by hand.
        val size = av_image_get_buffer_size(AV_PIX_FMT_BGRA, width, height, 1)
        buffer = BytePointer(size.toLong())
        view = buffer?.asByteBuffer()
        planes = PointerPointer<BytePointer>(4)
        strides = IntPointer(4L)
        check(
            av_image_fill_arrays(planes, strides, buffer, AV_PIX_FMT_BGRA, width, height, 1) >= 0,
        ) { "could not lay out the frame buffer" }
        packet = av_packet_alloc()
        frame = av_frame_alloc()
        if (hwDevice != null) hwFrame = av_frame_alloc()
    }.onFailure { close() }

    /**
     * Hands [context] the platform's hardware device, if [decoder] has a configuration for it. The
     * decoder's default format negotiation then picks the hardware format by itself.
     */
    private fun attachHardware(decoder: AVCodec, context: AVCodecContext) {
        val type = when {
            DesktopPlatform.isMac -> AV_HWDEVICE_TYPE_VIDEOTOOLBOX
            DesktopPlatform.isWindows -> AV_HWDEVICE_TYPE_D3D11VA
            else -> AV_HWDEVICE_TYPE_VAAPI
        }
        var index = 0
        while (true) {
            val config = avcodec_get_hw_config(decoder, index++) ?: return
            if (config.device_type() != type || config.methods() and AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX == 0) continue
            val device = AVBufferRef()
            if (av_hwdevice_ctx_create(device, type, null as String?, null, 0) < 0) return
            hwDevice = device
            hwPixelFormat = config.pix_fmt()
            context.hw_device_ctx(av_buffer_ref(device))
            return
        }
    }

    /**
     * The next frame as BGRA bytes, looping at the end. Frames before [skipBeforeMs] are decoded
     * and let go without being copied back or converted — the catch-up from a keyframe after a
     * seek, which at 4K is most of what a seek costs.
     */
    fun nextFrame(into: ByteArray, skipBeforeMs: Long = Long.MIN_VALUE): Boolean {
        val picture = nextPicture(skipBeforeMs) ?: return false
        val converter = scalerFor(picture.format()) ?: return false
        sws_scale(converter, picture.data(), picture.linesize(), 0, sourceHeight, planes, strides)
        // Read through an explicit NIO view rewound each time rather than through the
        // pointer's own `get`, whose starting offset is its position.
        view?.let { pixels ->
            pixels.rewind()
            pixels.get(into, 0, minOf(into.size, width * height * 4))
        }
        return true
    }

    /**
     * The next frame as its own luma and chroma planes, at full size, for the GPU to turn into
     * colour and scale as it draws — no conversion or scaling here at all. Written into [reuse]
     * when it is the same shape, so a stream of frames allocates nothing. Null at the end, or —
     * [planarFormat] false — for a picture in some other format (10-bit, say), which
     * [nextFrame] converts instead.
     */
    fun nextPlanes(reuse: VideoPlanes?, skipBeforeMs: Long = Long.MIN_VALUE): VideoPlanes? {
        val picture = nextPicture(skipBeforeMs) ?: return null
        val interleaved = when (picture.format()) {
            AV_PIX_FMT_NV12 -> true
            AV_PIX_FMT_YUV420P, AV_PIX_FMT_YUVJ420P -> false
            else -> {
                planarFormat = false
                return null
            }
        }
        val w = picture.width()
        val h = picture.height()
        val chromaWidth = (w + 1) / 2
        val chromaRows = (h + 1) / 2
        val yStride = picture.linesize(0)
        // Skia takes one channel to an image, so the hardware's interleaved U/V plane is split in
        // two here — a byte shuffle, nothing like the conversion it replaces.
        val cStride = if (interleaved) chromaWidth else picture.linesize(1)
        val out = reuse?.takeIf {
            it.width == w && it.height == h && it.yStride == yStride && it.cStride == cStride
        } ?: VideoPlanes(
            width = w,
            height = h,
            y = ByteArray(yStride * h),
            yStride = yStride,
            u = ByteArray(cStride * chromaRows),
            v = ByteArray(cStride * chromaRows),
            cStride = cStride,
        )
        copyPlane(picture, 0, out.y)
        if (interleaved) {
            val uvStride = picture.linesize(1)
            val uv = out.scratch?.takeIf { it.size == uvStride * chromaRows } ?: ByteArray(uvStride * chromaRows).also { out.scratch = it }
            copyPlane(picture, 1, uv)
            for (row in 0 until chromaRows) {
                var from = row * uvStride
                var to = row * cStride
                repeat(chromaWidth) {
                    out.u[to] = uv[from]
                    out.v[to] = uv[from + 1]
                    from += 2
                    to++
                }
            }
        } else {
            copyPlane(picture, 1, out.u)
            copyPlane(picture, 2, out.v)
        }
        out.ptsMs = framePtsMs
        // YouTube tags HD as BT.709; an untagged picture is guessed by its size, as players do.
        out.bt709 = when (picture.colorspace()) {
            AVCOL_SPC_BT709 -> true
            AVCOL_SPC_BT470BG, AVCOL_SPC_SMPTE170M -> false
            else -> h >= 720
        }
        out.fullRange = picture.color_range() == AVCOL_RANGE_JPEG || picture.format() == AV_PIX_FMT_YUVJ420P
        return out
    }

    /** Whether the stream's pictures come in a format [nextPlanes] hands over; false once one hasn't. */
    var planarFormat: Boolean = true
        private set

    private fun copyPlane(picture: AVFrame, index: Int, into: ByteArray) {
        val plane = picture.data(index)
        plane.capacity(into.size.toLong())
        plane.asByteBuffer().get(into, 0, into.size)
    }

    /**
     * Decodes on to the next picture at or past [skipBeforeMs], looping at the end, and brings a
     * hardware frame's finished picture back from the GPU. Valid until the next call.
     */
    private fun nextPicture(skipBeforeMs: Long): AVFrame? {
        val container = format ?: return null
        val context = codec ?: return null
        val pkt = packet ?: return null
        val decoded = frame ?: return null
        var looped = false
        while (true) {
            if (avcodec_receive_frame(context, decoded) == 0) {
                val pts = decoded.best_effort_timestamp()
                if (pts != AV_NOPTS_VALUE && timeBase > 0.0) framePtsMs = (pts * timeBase * 1_000).toLong()
                if (framePtsMs < skipBeforeMs) continue
                // A hardware frame lives in the GPU's memory: only the finished picture comes back.
                if (hwDevice == null || decoded.format() != hwPixelFormat) return decoded
                val copy = hwFrame ?: return null
                av_frame_unref(copy)
                if (av_hwframe_transfer_data(copy, decoded, 0) < 0) return null
                decodingInHardware = true
                return copy
            }
            if (av_read_frame(container, pkt) < 0) {
                if (looped || !loop) return null
                looped = true
                av_seek_frame(container, streamIndex, 0, AVSEEK_FLAG_BACKWARD)
                avcodec_flush_buffers(context)
                continue
            }
            if (pkt.stream_index() == streamIndex) avcodec_send_packet(context, pkt)
            av_packet_unref(pkt)
        }
    }

    /**
     * Jumps to the keyframe at or before [positionMs]; the frames after it decode on from there,
     * so a caller wanting an exact moment reads on until [framePtsMs] reaches it.
     */
    fun seek(positionMs: Long): Boolean {
        val container = format ?: return false
        val context = codec ?: return false
        if (timeBase <= 0.0) return false
        val target = (positionMs.coerceAtLeast(0L) / 1_000.0 / timeBase).toLong()
        if (av_seek_frame(container, streamIndex, target, AVSEEK_FLAG_BACKWARD) < 0) return false
        avcodec_flush_buffers(context)
        framePtsMs = -1L
        return true
    }

    /** The converter for the format frames are actually arriving in. */
    private fun scalerFor(pixelFormat: Int): SwsContext? {
        scaler?.let { if (pixelFormat == scalerFormat) return it }
        if (pixelFormat < 0) return null
        scaler?.let { sws_freeContext(it) }
        // BGRA because that is the order Skia's N32 bitmaps are laid out in, so the scaled frame
        // can be handed over without a second pass.
        val built = sws_getContext(
            sourceWidth, sourceHeight, pixelFormat,
            width, height, AV_PIX_FMT_BGRA,
            SWS_BILINEAR, null, null, null as DoublePointer?,
        )
        if (built == null) {
            DesktopTrackLog.log("canvas: no converter for pixel format $pixelFormat")
            scaler = null
            return null
        }
        scaler = built
        scalerFormat = pixelFormat
        return built
    }

    fun close() {
        frame?.let { av_frame_free(it) }
        hwFrame?.let { av_frame_free(it) }
        hwFrame = null
        hwDevice?.let { av_buffer_unref(it) }
        hwDevice = null
        hwPixelFormat = AV_PIX_FMT_NONE
        packet?.let { av_packet_free(it) }
        scaler?.let { sws_freeContext(it) }
        codec?.let { avcodec_free_context(it) }
        format?.let { avformat_close_input(it) }
        rangeIo?.close()
        rangeIo = null
        buffer?.deallocate()
        strides?.deallocate()
        frame = null
        packet = null
        scaler = null
        scalerFormat = -1
        codec = null
        format = null
        buffer = null
        view = null
        planes = null
        strides = null
        streamIndex = -1
        framePtsMs = -1L
        sourceWidth = 0
        sourceHeight = 0
    }

    private companion object {
        /** Plenty for a sleeve, and a quarter of the pixels of the source. */
        const val MAX_EDGE = 540

        private val http = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build()

        /**
         * One H.264 rendition out of an HLS master playlist, or null when [url] isn't one.
         *
         * Apple's artist clips are masters listing a dozen renditions, HEVC and I-frame-only ones
         * among them. FFmpeg opens such a master and then reads no frames at all, so the clip
         * stayed blank; a single rendition decodes. The smallest one at least [MAX_EDGE] square
         * is taken, as frames are scaled down to that anyway.
         */
        fun singleVariant(url: String, headers: Map<String, String>): String? {
            if (!isManifest(url)) return null
            val request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(15))
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .GET().build()
            val manifest = runCatching {
                http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                    .takeIf { it.statusCode() in 200..299 }?.body()
            }.getOrNull() ?: return null
            return pickVariant(manifest, url, MAX_EDGE)
        }

        /**
         * The windows a music video is read in: a 4K picture runs 20-40 Mbit/s, so the audio's
         * 512 KB windows were a round trip every ~0.1s of video, and a seek waited on dozens.
         */
        const val VIDEO_WINDOW_BYTES = 4 * 1024 * 1024

        /** Thirty frames a second is motion; sixty is just more bitmaps. */
        const val MIN_INTERVAL_MS = 33L
    }
}

private typealias DoublePointer = org.bytedeco.javacpp.DoublePointer

/** A picture's luma and chroma planes as decoded — see [DesktopCanvasDecoder.nextPlanes]. */
internal class VideoPlanes(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val yStride: Int,
    val u: ByteArray,
    val v: ByteArray,
    /** The row length of [u] and [v] alike. */
    val cStride: Int,
) {
    /** Where an interleaved U/V plane lands before it is split, reused frame to frame. */
    internal var scratch: ByteArray? = null
    var ptsMs: Long = -1L
    var bt709: Boolean = true
    var fullRange: Boolean = false
}

/** [singleVariant]'s choice out of a master playlist's text; null when it lists no H.264 rendition. */
internal fun pickVariant(manifest: String, manifestUrl: String, minEdge: Int = 540): String? {
    val lines = manifest.lines()
    val variants = lines.withIndex().mapNotNull { (i, line) ->
        if (!line.startsWith("#EXT-X-STREAM-INF:") || !line.contains("avc1")) return@mapNotNull null
        val uri = lines.getOrNull(i + 1)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("#") }
            ?: return@mapNotNull null
        val edge = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
            ?.destructured?.let { (w, h) -> minOf(w.toInt(), h.toInt()) } ?: 0
        edge to java.net.URI.create(manifestUrl).resolve(uri).toString()
    }
    return (variants.filter { it.first >= minEdge }.minByOrNull { it.first }
        ?: variants.maxByOrNull { it.first })?.second
}
