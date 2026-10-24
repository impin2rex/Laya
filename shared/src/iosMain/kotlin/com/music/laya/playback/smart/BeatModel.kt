package com.music.laya.playback.smart

// ponytail: iOS ONNX runtime lands in Phase 7
internal actual class BeatModel actual constructor(modelPath: () -> String, inferenceThreads: () -> Int) {
    actual fun infer(pcm: FloatArray): BeatLogits? = null
    actual fun release() = Unit
}
