package com.music.laya.playback

// Kotlin/Native has no unmodifiable wrapper; callers already receive a fresh copy.
internal actual fun <T> unmodifiableList(list: List<T>): List<T> = list
