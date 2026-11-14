package com.music.laya.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * How much of each edge of a video frame is the uploader's own black bars, as fractions of the
 * frame: a widescreen film letterboxed into 16:9, or a 4:3 clip pillarboxed into it. The player
 * shows what is inside them, so such a video fills the screen as one that was never padded does.
 */
data class VideoCrop(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val isNone: Boolean get() = left == 0f && top == 0f && right == 0f && bottom == 0f

    /** The picture's shape inside the bars, for a frame [aspect] (width / height) wide. */
    fun aspect(aspect: Float): Float = aspect * (1f - left - right) / (1f - top - bottom)

    companion object {
        val NONE = VideoCrop(0f, 0f, 0f, 0f)
    }
}

object BlackBars {
    /**
     * How long after one reading to take the next: a quarter second until the [VideoCropSettler]
     * has the readings it waits for, so a new video is cropped within a second rather than three,
     * then once a second.
     */
    fun readEveryMs(readsSoFar: Int): Long = if (readsSoFar < QUICK_READS) 250L else 1_000L

    private const val QUICK_READS = 3

    /** Brightest a bar's pixel gets, on 0–255: limited-range black is 16, and compression adds a little. */
    private const val BLACK = 32

    /** Less than this is noise, or a hairline of the picture itself, not a bar. */
    private const val SMALLEST = 0.015f

    /** More than this off one edge is no bar anyone uploads; something dark in the picture, rather. */
    private const val LARGEST = 0.35f

    /**
     * The bars of one frame, read off a [width]×[height] grid of its luma ([luma] at a grid point,
     * 0–255). Null when the frame is too dark to tell — a fade, a night scene — so it says nothing.
     */
    fun detect(width: Int, height: Int, luma: (x: Int, y: Int) -> Int): VideoCrop? {
        if (width < 4 || height < 4) return null
        fun rowDark(y: Int) = (0 until width).all { luma(it, y) <= BLACK }
        fun columnDark(x: Int) = (0 until height).all { luma(x, it) <= BLACK }
        val top = (0 until height).firstOrNull { !rowDark(it) } ?: return null
        val bottom = height - 1 - ((height - 1) downTo 0).first { !rowDark(it) }
        val left = (0 until width).first { !columnDark(it) }
        val right = width - 1 - ((width - 1) downTo 0).first { !columnDark(it) }
        fun edge(bars: Int, of: Int) = (bars.toFloat() / of).let { if (it < SMALLEST || it > LARGEST) 0f else it }
        return VideoCrop(edge(left, width), edge(top, height), edge(right, width), edge(bottom, height))
    }
}

/**
 * The crop a video settles on, from its frames' [BlackBars] read a few seconds apart: the least any
 * of the last [WINDOW] readings would take off each edge, so a dark stretch at the top of one shot
 * — a night sky — never cuts into the next. Nothing until [SETTLED] readings agree on a picture.
 */
class VideoCropSettler {
    private val readings = ArrayDeque<VideoCrop>()

    /** Whether enough frames were read for [offer]'s answer to stand: until then it is [VideoCrop.NONE]. */
    val settled: Boolean get() = readings.size >= SETTLED

    /** Takes one frame's reading (null for a frame too dark to read); returns the crop to show. */
    fun offer(reading: VideoCrop?): VideoCrop {
        if (reading != null) {
            readings.addLast(reading)
            while (readings.size > WINDOW) readings.removeFirst()
        }
        if (readings.size < SETTLED) return VideoCrop.NONE
        return VideoCrop(
            readings.minOf { it.left },
            readings.minOf { it.top },
            readings.minOf { it.right },
            readings.minOf { it.bottom },
        )
    }

    private companion object {
        const val WINDOW = 5
        const val SETTLED = 3
    }
}

/**
 * Each video's crop once settled, on every platform: a surface that takes the picture over (mini
 * player to full player, picture in picture), or the same video coming round again, shows it at
 * its final shape at once instead of waiting for the bars to be read again. The last [KEEP] videos;
 * safe from any thread.
 */
object VideoCropMemory {
    private val crops = MutableStateFlow<Map<String, VideoCrop>>(emptyMap())

    operator fun get(videoId: String): VideoCrop? = crops.value[videoId]

    fun put(videoId: String, crop: VideoCrop) = crops.update { held ->
        // Moved to the end, the newest; the oldest goes past [KEEP].
        val next = held - videoId + (videoId to crop)
        if (next.size > KEEP) next - next.keys.first() else next
    }

    /** The longest a new picture waits, hidden, for its crop: a dark opening gives no reading. */
    const val WAIT_MS = 1_500L

    private const val KEEP = 32
}
