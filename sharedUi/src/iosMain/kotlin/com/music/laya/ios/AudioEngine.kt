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

    /**
     * How the queued next track follows the playing one: a crossfade of [fadeMs] (0 = plain gapless), starting when the
     * playing track reaches [startMs] (-1 = so that it ends with the track; [fadeMs] is then kept to a third of it),
     * with the next track cued to [cueMs] and played at [rate] times the chosen speed (the playing one at [outRate] times, from the fade's start).
     * Sticks until play() or stop().
     */
    fun setTransition(startMs: Long, fadeMs: Long, cueMs: Long, rate: Float, outRate: Float)

    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun stop()

    /** Playback rate with pitch preserved. */
    fun setSpeed(speed: Float)

    /** The equaliser curve, one entry per EqLayout slot (the slot shapes and centres are fixed on both sides). */
    fun setEqualizer(gainsDb: List<Float>, qs: List<Float>, preampDb: Float, balance: Float)

    /** Which effects need the audio tap: reverb / karaoke / spatial widening ([IosDsp]), and skip silence (Swift measures and speeds through the gap). */
    fun setEffects(dsp: Boolean, skipSilence: Boolean)

    fun setNowPlaying(title: String, artist: String, album: String?, artworkUrl: String?)
    fun setQueueCapabilities(hasNext: Boolean, hasPrevious: Boolean)
    fun setListener(listener: AudioEngineListener?)

    /** The system output volume, 0..1. iOS only lets an app set it through a (hidden) MPVolumeView slider. */
    fun systemVolume(): Float
    fun setSystemVolume(level: Float)

    /** [portName, portType, sampleRateHz, outputChannels, ioBufferMs] of the audio session's current output route. */
    fun outputRoute(): List<String>
}

interface AudioEngineListener {
    /** Main thread, a few times a second while something is loaded. */
    fun onProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean, isBuffering: Boolean)
    fun onEnded()
    fun onAdvancedToNext()

    /** The current item's decoded format once it is ready (0 where unknown): [mimeType] in Media3's naming, e.g. audio/mp4a-latm. */
    fun onAudioFormat(mimeType: String?, sampleRateHz: Int, channels: Int, bitDepth: Int, bitrateKbps: Int)

    /** [httpStatus] is the googlevideo status that caused it, or 0 when it was not an HTTP refusal. */
    fun onError(message: String, httpStatus: Int)
    /** Hardware keys, Control Center or our own slider moved the system volume. */
    fun onVolumeChanged(level: Float)
    fun onRemoteNext()
    fun onRemotePrevious()
}
