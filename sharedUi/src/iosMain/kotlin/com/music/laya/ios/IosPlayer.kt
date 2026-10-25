package com.music.laya.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.laya.data.NerdStats
import com.music.laya.data.TelemetryProvenance
import com.music.laya.data.YtMusicRepository
import com.music.laya.data.innertube.StreamResolver
import com.music.laya.data.model.QueueTier
import com.music.laya.data.model.Song
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.sources.SourceKind
import com.music.laya.data.sources.SourceRegistry
import com.music.laya.data.sources.SourceResolver
import com.music.laya.data.sources.SourceStream
import com.music.laya.data.sources.StreamFormat
import com.music.laya.data.sources.TrackMatcher
import com.music.laya.playback.youtubeSeedFor
import com.music.laya.platform.ioDispatcher
import com.music.laya.playback.Karaoke
import com.music.laya.playback.karaoke.IosKaraoke
import com.music.laya.playback.karaoke.IosPcmStream
import com.music.laya.playback.karaoke.KaraokeStems
import com.music.laya.playback.PlaybackPosition
import com.music.laya.playback.EqLayout
import com.music.laya.data.settings.EqualizerMode
import com.music.laya.playback.QueueBuilder
import com.music.laya.playback.manualCurve
import com.music.laya.playback.toneCurve
import com.music.laya.playback.QueueTimeline
import com.music.laya.playback.QueueTimeline.asQueueEntry
import com.music.laya.ui.player.RepeatModes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue

// 1 MiB, same as Android's STREAM_CHUNK_BYTES: googlevideo refuses/throttles larger ranges for some clients.
private const val CHUNK_BYTES = 1024L * 1024

/** Most AutoPlay tracks queued ahead at once, same as Android's MAX_QUEUED_AUTOPLAY. */
private const val MAX_AUTOPLAY = 10

/** How long a source ranked above YouTube gets before a track starts on YouTube regardless (Android's SUBSTITUTE_TIMEOUT_MS). */
private const val SUBSTITUTE_TIMEOUT_MS = 20_000L

/** What [IosPlayer.open] hands the engine, and what the source said about it. */
private class Opened(
    val url: String,
    val headers: Map<String, String>,
    val mime: String,
    val length: Long,
    val sourceName: String,
    val format: StreamFormat? = null,
    /** A better copy that may still arrive (a lossless search that outlasted the quick answer). */
    val pending: Deferred<SourceStream?>? = null,
)

private class KaraokeSource(val url: String, val headers: Map<String, String>, val mime: String, val length: Long)

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
    private var upgradeJob: Job? = null
    private var autoplayJob: Job? = null
    private var preloaded = -1
    /** What [resolveInto] last handed the engine for each track, so Karaoke can decode the same bytes. */
    private val streams = mutableMapOf<String, KaraokeSource>()
    /** Entry ids of the upcoming tracks as they were before Shuffle, so turning it off can restore them. */
    private var preShuffle = emptyList<String?>()
    /** Set while a restored track sits paused and unloaded; the first play loads it and seeks here. */
    private var pendingMs: Long? = null
    private var lastSavedAt = 0L

    init {
        engine.setListener(this)
        IosVolume.level.value = engine.systemVolume()
        IosVolume.setter = engine::setSystemVolume
        IosPlayerStore.load()?.let { s ->
            queue = s.songs; index = s.index; pendingMs = s.positionMs; durationMs = s.durationMs
            position.report(s.positionMs)
            announce() // mini player, lock screen card, Karaoke binding
        }
        // Same moments Android saves: leaving the foreground (the progress cadence covers the rest).
        listOf("UIApplicationWillResignActiveNotification", "UIApplicationDidEnterBackgroundNotification").forEach {
            NSNotificationCenter.defaultCenter.addObserverForName(it, null, NSOperationQueue.mainQueue) { _ -> saveState() }
        }
        scope.launch { AppSettings.spatialAudio.collect { engine.setSpatialAudio(it) } }
        scope.launch { AppSettings.playbackSpeed.collect { engine.setSpeed(it) } }
        scope.launch { combine(AppSettings.reverbEnabled, AppSettings.reverb) { on, r -> on to r }.collect { (on, r) -> IosDsp.setReverb(on, r) } }
        scope.launch { combine(Karaoke.enabled, Karaoke.level) { on, l -> on to l }.collect { (on, l) -> IosDsp.setKaraoke(on, l) } }
        scope.launch { combine(Karaoke.enabled, Karaoke.warm) { on, warm -> on || warm }.distinctUntilChanged().collect { bindKaraoke() } }
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
    fun togglePlayPause() {
        val at = pendingMs
        if (at != null) start(index, at) else if (isPlaying) engine.pause() else engine.resume()
    }

    private fun saveState() { if (song != null) IosPlayerStore.saveState(pendingMs ?: position.positionMs, durationMs) }

    fun seekTo(ms: Long) {
        if (pendingMs != null) { pendingMs = ms; position.seeks++; position.report(ms); return }
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
        IosPlayerStore.saveQueue(queue, index)
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
            val related = YtMusicRepository.radio(youtubeSeedFor(seed) ?: return@launch).getOrNull() ?: return@launch
            val extra = QueueBuilder.extend(queue, related, MAX_AUTOPLAY)
            if (extra.isEmpty() || song?.videoId != seed.videoId) return@launch
            queue = queue + extra.map { it.asQueueEntry(QueueTier.AUTOPLAY) }
            announce()
            preResolveNext()
        }
    }

    fun seekFraction(f: Float) { if (durationMs > 0) seekTo((f * durationMs).toLong()) }

    /** YouTube's own stream for [videoId], the only path before addons existed. */
    private suspend fun youtube(videoId: String): Opened {
        val url = StreamResolver.resolve(videoId)
        // googlevideo URLs carry the format as ?mime=audio%2Fmp4
        val mime = url.substringAfter("mime=", "audio%2Fmp4").substringBefore('&').replace("%2F", "/")
        return Opened(url, StreamResolver.mediaHeadersFor(url), mime, StreamResolver.contentLength(videoId) ?: -1L, "YouTube")
    }

    /** A source's answer as the engine wants it. A YouTube answer goes back through [youtube] for its headers and length. */
    private suspend fun fromSource(stream: SourceStream, pending: Deferred<SourceStream?>? = null): Opened {
        val config = stream.sourceConfigId?.let(SourceRegistry::config)
        if (config?.kind == SourceKind.YOUTUBE && stream.matchedId != null) return youtube(stream.matchedId!!)
        return Opened(
            url = stream.url,
            headers = stream.headers,
            mime = mimeOf(stream),
            length = -1L, // the loader learns the size from the first Content-Range
            sourceName = config?.displayName ?: "Source",
            format = stream.format,
            pending = pending,
        )
    }

    /** The container AVPlayer should expect, from the codec the source stated or else the file extension. */
    private fun mimeOf(stream: SourceStream): String {
        val ext = stream.url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when (stream.format.codec ?: ext) {
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "aiff" -> "audio/aiff"
            "mp3" -> "audio/mpeg"
            "aac" -> if (ext == "aac") "audio/aac" else "audio/mp4" // bare ADTS vs AAC in MP4
            else -> "audio/mp4" // alac, m4a, mp4
        }
    }

    /**
     * Where Android decides which source plays a track (PlaybackService's resolver): a row that came from
     * a source is resolved by it, falling back through the rest; a YouTube row is offered to every source
     * ranked above YouTube first, racing YouTube itself so a slow addon costs nothing.
     *
     * [patient] is for the track after the one playing, where nobody is waiting: the lookup may take its
     * time and the lossless answer is preferred over the quick one.
     */
    private suspend fun open(song: Song, patient: Boolean): Opened {
        val target = TrackMatcher.targetOf(song)
        SourceRegistry.parseTrackKey(song.videoId)?.let { (configId, trackId) ->
            // Addon rows play as the row the listener picked, never its Atmos sibling or YouTube's copy (MIX_PARAM).
            val mix = if (SourceRegistry.config(configId)?.kind == SourceKind.ADDON) {
                when (song.sourceQuality) {
                    null -> SourceResolver.MIX_ANY
                    "DOLBY" -> SourceResolver.MIX_ATMOS
                    else -> SourceResolver.MIX_STEREO
                }
            } else null
            val late = CompletableDeferred<SourceStream?>()
            val stream = withTimeoutOrNull(SUBSTITUTE_TIMEOUT_MS * 6) { SourceResolver.resolve(configId, trackId, target, mix, late) }
                ?: error("No enabled source could serve ${song.title}")
            return fromSource(stream, late.takeIf { !it.isCompleted })
        }
        if (!SourceResolver.canSubstituteForYouTube()) return youtube(song.videoId)
        if (patient) {
            val warmed = runCatching { SourceResolver.prefetchSubstitute(target, patient = true) }.getOrNull()
            return warmed?.let { fromSource(it) } ?: youtube(song.videoId)
        }
        val late = CompletableDeferred<SourceStream?>()
        val lookup = scope.async(ioDispatcher) { withTimeoutOrNull(SUBSTITUTE_TIMEOUT_MS) { SourceResolver.substituteForYouTube(target, late) } }
        val fallback = scope.async(ioDispatcher) { runCatching { youtube(song.videoId) } }
        // First past the post. A YouTube answer that failed has not won anything.
        val quick: SourceStream? = select {
            lookup.onAwait { it }
            fallback.onAwait { r -> if (r.isSuccess) null else lookup.await() }
        }
        if (quick != null) {
            fallback.cancel()
            return fromSource(quick, late.takeIf { !it.isCompleted })
        }
        val onYouTube = fallback.await().getOrThrow()
        // The lookup is still looking (or missed): what it lands on later swaps in mid-song.
        return if (lookup.isActive) Opened(onYouTube.url, onYouTube.headers, onYouTube.mime, onYouTube.length, onYouTube.sourceName, pending = lookup)
        else onYouTube.also { late.cancel() }
    }

    private suspend fun resolveInto(song: Song, next: Boolean) = withContext(ioDispatcher) {
        val o = open(song, patient = next)
        hand(song, o, next)
        if (!next && o.pending != null) watchUpgrade(song, o)
    }

    /** Gives the engine [o] and records what it is, for Karaoke and the nerd stats. */
    private fun hand(song: Song, o: Opened, next: Boolean) {
        streams[song.videoId] = KaraokeSource(o.url, o.headers, o.mime, o.length)
        NerdStats.recordSource(song.videoId, o.sourceName)
        o.format?.let { NerdStats.onSourceStream(song.videoId, it, o.sourceName) }
        if (next) engine.setNext(o.url, o.headers, o.mime, o.length, CHUNK_BYTES)
        else engine.play(o.url, o.headers, o.mime, o.length, CHUNK_BYTES)
    }

    /**
     * Swaps to the better copy a source lands on while [song] is playing, at the same position. Only when
     * it is the same recording (runtime within the resolver's drift) and worth the short break in the audio.
     */
    private fun watchUpgrade(song: Song, o: Opened) {
        upgradeJob?.cancel()
        upgradeJob = scope.launch {
            val better = runCatching { o.pending?.await() }.getOrNull() ?: return@launch
            if (this@IosPlayer.song?.videoId != song.videoId) return@launch
            if (!SourceResolver.worthSwapping(better.format, o.format)) return@launch
            if (!SourceResolver.sameRecordingAs(better.durationSec, durationMs.takeIf { it > 0 }?.let { (it / 1000).toInt() })) return@launch
            val at = position.positionMs
            val opened = withContext(ioDispatcher) { fromSource(better) }
            if (this@IosPlayer.song?.videoId != song.videoId) return@launch
            hand(song, opened, next = false)
            engine.seekTo(at)
            preResolveNext() // the swap replaced the engine's queued item
        }
    }

    private fun start(i: Int, fromMs: Long = 0) {
        val song = queue.getOrNull(i) ?: return
        index = i
        NerdStats.onTrackTransition(song.videoId)
        pendingMs = null
        isLoading = true
        durationMs = 0
        position.report(fromMs)
        IosPlayerStore.saveQueue(queue, i)
        IosPlayerStore.saveState(fromMs, 0)
        nextJob?.cancel()
        upgradeJob?.cancel()
        startJob?.cancel()
        startJob = scope.launch {
            runCatching { resolveInto(song, next = false) }
                .onFailure { isLoading = false; println("IosPlayer: ${it.message ?: it}") }
                .onSuccess { if (fromMs > 0) engine.seekTo(fromMs); announce(); preResolveNext(); topUpAutoplay() }
        }
    }

    private fun announce() {
        Karaoke.trackChanged(song?.videoId) // karaoke is per song, as on Android
        bindKaraoke()
        song?.let { engine.setNowPlaying(it.title, it.artist, it.albumName, it.thumbnailUrl) }
        engine.setQueueCapabilities(hasNext, hasPrevious)
    }

    /** Hands the tap the neural vocal of the track playing (Android's bindKaraoke), or nothing to stay on classic. */
    private fun bindKaraoke() {
        val id = song?.videoId
        val source = id?.let { streams[it] }
        val native = IosKaraoke.native
        IosDsp.bindStems(
            if (source == null || native == null || !(Karaoke.enabled.value || Karaoke.warm.value)) null
            else KaraokeStems.forTrack(id, id) {
                native.openPcm(source.url, source.headers, source.mime, source.length, CHUNK_BYTES)?.let(::IosPcmStream)
            },
        )
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
        // Position about every 5 s while playing, and straight away on pause.
        val now = NSDate().timeIntervalSinceReferenceDate.toLong()
        if (pendingMs == null && song != null && (!isPlaying || now - lastSavedAt >= 5)) { lastSavedAt = now; saveState() }
    }

    override fun onAudioFormat(mimeType: String?, sampleRateHz: Int, channels: Int, bitDepth: Int, bitrateKbps: Int) {
        val id = song?.videoId ?: return
        val lossless = NerdStats.isLosslessMime(mimeType)
        // The resolver's figure first (as Android); AVFoundation's estimate off a half-loaded stream only as a fallback.
        val (kbps, provenance) = NerdStats.sourceBitrate(id) { bitrateKbps.takeIf { it > 0 } }
            ?: (null to TelemetryProvenance.UNKNOWN)
        NerdStats.publish(
            NerdStats.Snapshot(
                mimeType = mimeType,
                bitrateKbps = kbps,
                sampleRateHz = sampleRateHz.takeIf { it > 0 },
                channels = channels.takeIf { it > 0 },
                bitDepth = bitDepth.takeIf { lossless && it > 0 },
                sourceName = NerdStats.exactSourceFor(id) ?: "YouTube",
                bitrateProvenance = provenance,
                mediaId = id,
            ),
            NerdStats.currentGeneration,
        )
    }

    override fun onEnded() { autoNext().let { if (it >= 0) start(it) else isPlaying = false } }

    override fun onAdvancedToNext() {
        index = preloaded
        NerdStats.onTrackTransition(song?.videoId)
        position.report(0)
        IosPlayerStore.saveQueue(queue, index)
        announce()
        preResolveNext()
        topUpAutoplay()
    }

    override fun onError(message: String, httpStatus: Int) {
        isLoading = false
        println("IosPlayer: $message ($httpStatus)")
    }

    override fun onVolumeChanged(level: Float) { IosVolume.level.value = level }
    override fun onRemoteNext() = next()
    override fun onRemotePrevious() = previous()
}
