package com.music.laya.playback.karaoke

// ponytail: iOS ONNX runtime lands in Phase 7
// Construction throws, which KaraokeStems already treats as "model failed" and retries later.
actual class MdxSeparator actual constructor(modelPath: String, threads: Int, actual val model: MdxModel) : ChunkSeparator {
    init {
        throw UnsupportedOperationException("ONNX not wired on iOS yet")
    }

    actual class Aborted actual constructor() : Exception()

    actual fun abort() = Unit

    actual fun close() = Unit

    override fun separate(left: FloatArray, right: FloatArray, vocalLeft: FloatArray, vocalRight: FloatArray) = Unit

    actual companion object {
        actual val SAMPLE_RATE = 44_100
        actual val HOP = 1_024
        actual val DIM_T = 256
        actual val CHUNK = HOP * (DIM_T - 1)
    }
}
