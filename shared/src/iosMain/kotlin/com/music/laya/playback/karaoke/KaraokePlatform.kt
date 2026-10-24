package com.music.laya.playback.karaoke

import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow

internal actual class WakeSignal actual constructor() {
    private val condition = NSCondition()

    actual fun await(millis: Long) {
        condition.lock()
        condition.waitUntilDate(NSDate.dateWithTimeIntervalSinceNow(millis / 1000.0))
        condition.unlock()
    }

    actual fun signal() {
        condition.lock()
        condition.broadcast()
        condition.unlock()
    }
}

internal actual fun startKaraokeThread(name: String, lowPriority: Boolean, body: () -> Unit) {
    NSThread(block = body).apply {
        this.name = name
        if (lowPriority) threadPriority = 0.4
        start()
    }
}

internal actual fun sleepMillis(millis: Long) = NSThread.sleepForTimeInterval(millis / 1000.0)

internal actual fun cpuCount(): Int = NSProcessInfo.processInfo.activeProcessorCount.toInt()

// ponytail: the native analyzer isn't built for iOS yet, so no resampling and karaoke segments fail like a missing model
internal actual fun resampleAudio(samples: FloatArray, from: Int, to: Int): FloatArray? = null
