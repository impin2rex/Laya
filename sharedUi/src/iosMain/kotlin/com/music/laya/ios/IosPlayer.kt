package com.music.laya.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.laya.data.YtMusicRepository
import com.music.laya.data.innertube.StreamResolver
import com.music.laya.data.model.QueueTier
import com.music.laya.data.model.Song
import com.music.laya.data.settings.AppSettings
import com.music.laya.platform.ioDispatcher
import com.music.laya.playback.Karaoke
import com.music.laya.playback.PlaybackPosition
import com.music.laya.playback.EqLayout
import com.music.laya.data.settings.EqualizerMode
import com.music.laya.playback.QueueBuilder
import com.music.laya.playback.manualCurve
import com.music.laya.playback.toneCurve
import com.music.laya.playback.QueueTimeline
import com.music.laya.playback.QueueTimeline.asQueueEntry
import com.music.laya.ui.player.RepeatModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 1 MiB, same as Android's STREAM_CHUNK_BYTES: googlevideo refuses/throttles larger ranges for some clients.
private const val CHUNK_BYTES = 1024L * 1024

/** Most AutoPlay tracks queued ahead at once, same as Android's MAX_QUEUED_AUTOPLAY. */
private const val MAX_AUTOPLAY = 10

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
    val hasNext get() = manualNext() >= 0
    val hasPrevious get() = index > 0

    private var startJob: Job? = null
    private var nextJob: Job? = null
    private var autoplayJob: Job? = null
    private var preloaded = -1
    /** Entry ids of the upcoming tracks as they were before Shuffle, so turning it off can restore them. */
    private var preShuffle = emptyList<String?>()

    init {
        engine.setListener(this)
        scope.launch { AppSettings.playbackSpeed.collect { engine.setSpeed(it) } }
        scope.launch { combine(AppSettings.reverbEnabled, AppSettings.reverb) { on, r -> on to r }.collect { (on, r) -> IosDsp.setReverb(on, r) } }
        scope.launch { combine(Karaoke.enabled, Karaoke.level) { on, l -> on to l }.collect { (on, l) -> IosDsp.setKaraoke(on, l) } }
        scope.launch {
            combine(AppSettings.reverbEnabled, Karaoke.enabled, AppSettings.skipSilence) { r, k, s -> (r || k) to s }
                .distinctUntilChanged().collect { (dsp, silence) -> engine.setEffects(dsp, silence) }
        }
        scope.launch {
            combine(
                AppSettings.equalizerEnabled, AppSettings.equalizerMode, AppSettings.equalizerBands,
                AppSettings.equalizerToneX, AppSettings.equalizerToneY, AppSettings.equalizerFocused,
                AppSettings.equalizerBalance,
            ) { it }.collect { v ->
                val c = when (v[1] as EqualizerMode) {
                    EqualizerMode.DYNAMIC -> toneCurve(v[3] as Int, v[4] as Int, v[5] as Boolean)
                    EqualizerMode.MANUAL -> manualCurve(@Suppress("UNCHECKED_CAST") (v[2] as List<Float>))
                }
                val on = v[0] as Boolean
                // Off = a flat curve, so the filters glide out instead of clicking.
                engine.setEqualizer(
                    if (on) c.gainsDb.toList() else List(EqLayout.SLOTS) { 0f }, c.qs.toList(),
                    if (on) c.preampDb else 0f, if (on) v[6] as Float else 0f,
                )
            }
        }
    }

    fun play(songs: List<Song>, startIndex: Int) {
        val entries = songs.map { it.asQueueEntry(QueueTier.CONTEXT) }
        if (AppSettings.shuffleEnabled.value) {
            queue = QueueTimeline.shuffledStartingOrder(entries, startIndex)
            start(0)
        } else {
            queue = entries
            start(startIndex)
        }
    }

    /** Where Next goes: the following track, wrapping to the top under Repeat all. */
    private fun manualNext() = when {
        index + 1 < queue.size -> index + 1
        AppSettings.repeatMode.value == RepeatModes.ALL && queue.isNotEmpty() -> 0
        else -> -1
    }

    /** Where playback goes by itself: the same track under Repeat one. */
    private fun autoNext() = if (AppSettings.repeatMode.value == RepeatModes.ONE) index else manualNext()

    fun next() { manualNext().let { if (it >= 0) start(it) } }
    fun previous() { if (hasPrevious) start(index - 1) else seekTo(0) }
    fun jumpTo(i: Int) { if (i in queue.indices) start(i) }
    fun togglePlayPause() { if (isPlaying) engine.pause() else engine.resume() }

    fun seekTo(ms: Long) {
        engine.seekTo(ms)
        position.seeks++
        position.report(ms)
    }

    fun playNext(song: Song) = enqueue(song, next = true)
    fun addToQueue(song: Song) = enqueue(song, next = false)

    private fun enqueue(song: Song, next: Boolean) {
        if (queue.isEmpty()) return play(listOf(song), 0)
        val at = QueueTimeline.findUserQueueInsertionIndex(queue, index, next)
        queue = queue.toMutableList().apply { add(at, song.asQueueEntry(QueueTier.USER_QUEUE)) }
        queueChanged()
    }

    fun removeAt(i: Int) {
        if (i !in queue.indices || i == index) return
        queue = queue.toMutableList().apply { removeAt(i) }
        if (i < index) index--
        queueChanged()
    }

    fun move(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices || from == to) return
        queue = queue.toMutableList().apply { add(to, removeAt(from)) }
        index = when {
            from == index -> to
            from < index && to >= index -> index - 1
            from > index && to <= index -> index + 1
            else -> index
        }
        queueChanged()
    }

    /** Clear drops the listener's own queued tracks; the album / playlist and AutoPlay stay. */
    fun clearUserQueue() {
        val drop = QueueTimeline.upcomingUserQueueIndices(queue.map { it.queueTier }, index).toSet()
        if (drop.isEmpty()) return
        queue = queue.filterIndexed { i, _ -> i !in drop }
        queueChanged()
    }

    /** Shuffle as an edit to the upcoming queue (same rules as Android's QueueShuffle): your queue stays pinned first. */
    fun setShuffle(on: Boolean) {
        AppSettings.setShuffleEnabled(on)
        if (index !in queue.indices) return
        val head = queue.take(index + 1)
        val upcoming = queue.drop(index + 1)
        val tiers = upcoming.map { it.queueTier }
        val order = if (on) {
            preShuffle = upcoming.map { it.queueEntryId }
            QueueTimeline.shuffledUpcomingOrder(tiers)
        } else {
            val at = preShuffle.withIndex().associate { (i, id) -> id to i }
            QueueTimeline.sectionedOrder(upcoming.indices.sortedBy { at[upcoming[it].queueEntryId] ?: Int.MAX_VALUE }, tiers)
        }
        queue = head + order.map { upcoming[it] }
        queueChanged()
    }

    fun cycleRepeat() = AppSettings.setRepeatMode(
        when (AppSettings.repeatMode.value) {
            RepeatModes.OFF -> RepeatModes.ALL
            RepeatModes.ALL -> RepeatModes.ONE
            else -> RepeatModes.OFF
        },
    )

    fun setAutoplay(on: Boolean) {
        AppSettings.setAutoplay(on)
        if (on) topUpAutoplay()
    }

    private fun queueChanged() {
        announce()
        preResolveNext()
        topUpAutoplay()
    }

    /** AutoPlay: on the last track, append the radio for it (Android's loadAutoplayTracks, minus party and module sources). */
    private fun topUpAutoplay() {
        val seed = song ?: return
        if (!AppSettings.autoplay.value || AppSettings.repeatMode.value == RepeatModes.ALL ||
            index != queue.lastIndex || autoplayJob?.isActive == true
        ) return
        autoplayJob = scope.launch {
            val related = YtMusicRepository.radio(seed.videoId).getOrNull() ?: return@launch
            val extra = QueueBuilder.extend(queue, related, MAX_AUTOPLAY)
            if (extra.isEmpty() || song?.videoId != seed.videoId) return@launch
            queue = queue + extra.map { it.asQueueEntry(QueueTier.AUTOPLAY) }
            announce()
            preResolveNext()
        }
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
                .onSuccess { announce(); preResolveNext(); topUpAutoplay() }
        }
    }

    private fun announce() {
        Karaoke.trackChanged(song?.videoId) // karaoke is per song, as on Android
        song?.let { engine.setNowPlaying(it.title, it.artist, it.albumName, it.thumbnailUrl) }
        engine.setQueueCapabilities(hasNext, hasPrevious)
    }

    /** Resolves the following track now so the engine can start it gaplessly. */
    private fun preResolveNext() {
        nextJob?.cancel()
        preloaded = autoNext()
        val upcoming = queue.getOrNull(preloaded) ?: return engine.clearNext()
        nextJob = scope.launch { runCatching { resolveInto(upcoming, next = true) } }
    }

    override fun onProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean, isBuffering: Boolean) {
        position.report(positionMs)
        this.durationMs = durationMs
        this.isPlaying = isPlaying
        isLoading = isBuffering
    }

    override fun onEnded() { autoNext().let { if (it >= 0) start(it) else isPlaying = false } }

    override fun onAdvancedToNext() {
        index = preloaded
        position.report(0)
        announce()
        preResolveNext()
        topUpAutoplay()
    }

    override fun onError(message: String, httpStatus: Int) {
        isLoading = false
        println("IosPlayer: $message ($httpStatus)")
    }

    override fun onRemoteNext() = next()
    override fun onRemotePrevious() = previous()
}
