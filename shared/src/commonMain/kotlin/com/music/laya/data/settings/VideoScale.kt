package com.music.laya.data.settings

/**
 * How a music video fills the screen it plays on, as VLC's aspect button: the whole picture with
 * black bars ([BEST_FIT], the default), the screen filled and the picture's overflow cropped
 * ([FIT_SCREEN]), or the picture stretched to the screen's shape ([FILL]).
 */
enum class VideoScale {
    BEST_FIT,
    FIT_SCREEN,
    FILL;

    /** The mode the aspect button goes to from this one. */
    val next: VideoScale get() = entries[(ordinal + 1) % entries.size]

    /**
     * The picture's drawn size, to centre in a [width]×[height] box, for a picture [aspect]
     * (width / height) wide. Larger than the box under [FIT_SCREEN]: the caller clips.
     */
    fun frame(aspect: Float, width: Float, height: Float): Pair<Float, Float> {
        if (this == FILL || aspect <= 0f || width <= 0f || height <= 0f) return width to height
        val widthLimited = (aspect > width / height) == (this == BEST_FIT)
        return if (widthLimited) width to width / aspect else height * aspect to height
    }

    /**
     * Where the whole frame goes in a [width]×[height] box — left, top, width, height — so the part
     * of it inside [crop] (the video's own black bars cut off) sits there as [frame] places a
     * picture. The rest falls outside that, into the box's own black or off its edges: clip to it.
     */
    fun place(frameAspect: Float, crop: VideoCrop, width: Float, height: Float): FramePlacement {
        val (w, h) = frame(crop.aspect(frameAspect), width, height)
        val fullW = w / (1f - crop.left - crop.right)
        val fullH = h / (1f - crop.top - crop.bottom)
        return FramePlacement(
            left = (width - w) / 2f - crop.left * fullW,
            top = (height - h) / 2f - crop.top * fullH,
            width = fullW,
            height = fullH,
        )
    }

    companion object {
        /** A stored name, or the default for none or one this version doesn't know. */
        fun named(name: String?): VideoScale = entries.firstOrNull { it.name == name } ?: BEST_FIT
    }
}

/** A video frame's place in its box: see [VideoScale.place]. */
data class FramePlacement(val left: Float, val top: Float, val width: Float, val height: Float)
