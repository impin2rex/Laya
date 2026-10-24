package com.music.laya.platform

import kotlin.time.Clock
import kotlin.time.TimeSource

private val start = TimeSource.Monotonic.markNow()

fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

fun nanoTime(): Long = start.elapsedNow().inWholeNanoseconds
