package com.music.laya.ios

/**
 * The audio output, implemented in Swift on AVFoundation (iosApp/iosApp/AudioEngine.swift)
 * and handed to Kotlin through `MainViewController(engine)`. Kotlin decides what plays,
 * Swift plays URLs and owns the audio session, interruptions and the lock screen.
 */
interface AudioEngine {
    /** Start [url] from the beginning, dropping anything queued. Each HTTP request carries [headers] and asks for at most [chunkBytes]. */
    fun play(url: String, headers: Map<String, String>, mimeType: String, contentLength: Long, chunkBytes: Long)

    /** Queue the track after the current one so it starts gaplessly, even with the screen locked. */
    fun setNext(url: String, headers: Map<String, String>, mimeType: String, contentLength: Long, chunkBytes: Long)
    fun clearNext()

    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun stop()

    fun setNowPlaying(title: String, artist: String, album: String?, artworkUrl: String?)
    fun setQueueCapabilities(hasNext: Boolean, hasPrevious: Boolean)
    fun setListener(listener: AudioEngineListener?)
}

interface AudioEngineListener {
    /** Main thread, a few times a second while something is loaded. */
    fun onProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean, isBuffering: Boolean)
    fun onEnded()
    fun onAdvancedToNext()

    /** [httpStatus] is the googlevideo status that caused it, or 0 when it was not an HTTP refusal. */
    fun onError(message: String, httpStatus: Int)
    fun onRemoteNext()
    fun onRemotePrevious()
}
