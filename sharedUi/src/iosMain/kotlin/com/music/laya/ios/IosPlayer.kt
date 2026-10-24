package com.music.laya.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.laya.data.innertube.StreamResolver
import com.music.laya.data.model.Song
import com.music.laya.platform.ioDispatcher
import com.music.laya.playback.PlaybackPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 1 MiB, same as Android's STREAM_CHUNK_BYTES: googlevideo refuses/throttles larger ranges for some clients.
private const val CHUNK_BYTES = 1024L * 1024

/**
 * Queue + transport state driving the Swift [AudioEngine]. Swift holds the listener weakly, so
 * whoever composes this must `remember` it.
 */
class IosPlayer(private val engine: AudioEngine, private val scope: CoroutineScope) : AudioEngineListener {
    var queue by mutableStateOf(emptyList<Song>()); private set
    var index by mutableStateOf(-1); private set
    var isPlaying by mutableStateOf(false); private set
    var isLoading by mutableStateOf(false); private set
    var durationMs by mutableStateOf(0L); private set
    val position = PlaybackPosition()

    val song: Song? get() = queue.getOrNull(index)
    val hasNext get() = index + 1 < queue.size
    val hasPrevious get() = index > 0

    private var startJob: Job? = null
    private var nextJob: Job? = null

    init { engine.setListener(this) }

    fun play(songs: List<Song>, startIndex: Int) {
        queue = songs
        start(startIndex)
    }

    fun next() { if (hasNext) start(index + 1) }
    fun previous() { if (hasPrevious) start(index - 1) else seekTo(0) }
    fun jumpTo(i: Int) { if (i in queue.indices) start(i) }
    fun togglePlayPause() { if (isPlaying) engine.pause() else engine.resume() }

    fun seekTo(ms: Long) {
        engine.seekTo(ms)
        position.seeks++
        position.report(ms)
    }

    fun seekFraction(f: Float) { if (durationMs > 0) seekTo((f * durationMs).toLong()) }

    private suspend fun resolveInto(song: Song, next: Boolean) = withContext(ioDispatcher) {
        val url = StreamResolver.resolve(song.videoId)
        val headers = StreamResolver.mediaHeadersFor(url)
        // googlevideo URLs carry the format as ?mime=audio%2Fmp4
        val mime = url.substringAfter("mime=", "audio%2Fmp4").substringBefore('&').replace("%2F", "/")
        val length = StreamResolver.contentLength(song.videoId) ?: -1L
        if (next) engine.setNext(url, headers, mime, length, CHUNK_BYTES)
        else engine.play(url, headers, mime, length, CHUNK_BYTES)
    }

    private fun start(i: Int) {
        val song = queue.getOrNull(i) ?: return
        index = i
        isLoading = true
        durationMs = 0
        position.report(0)
        nextJob?.cancel()
        startJob?.cancel()
        startJob = scope.launch {
            runCatching { resolveInto(song, next = false) }
                .onFailure { isLoading = false; println("IosPlayer: ${it.message ?: it}") }
                .onSuccess { announce(); preResolveNext() }
        }
    }

    private fun announce() {
        song?.let { engine.setNowPlaying(it.title, it.artist, it.albumName, it.thumbnailUrl) }
        engine.setQueueCapabilities(hasNext, hasPrevious)
    }

    /** Resolves the following track now so the engine can start it gaplessly. */
    private fun preResolveNext() {
        nextJob?.cancel()
        val upcoming = queue.getOrNull(index + 1) ?: return engine.clearNext()
        nextJob = scope.launch { runCatching { resolveInto(upcoming, next = true) } }
    }

    override fun onProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean, isBuffering: Boolean) {
        position.report(positionMs)
        this.durationMs = durationMs
        this.isPlaying = isPlaying
        isLoading = isBuffering
    }

    override fun onEnded() { if (hasNext) next() else isPlaying = false }

    override fun onAdvancedToNext() {
        index += 1
        position.report(0)
        announce()
        preResolveNext()
    }

    override fun onError(message: String, httpStatus: Int) {
        isLoading = false
        println("IosPlayer: $message ($httpStatus)")
    }

    override fun onRemoteNext() = next()
    override fun onRemotePrevious() = previous()
}
