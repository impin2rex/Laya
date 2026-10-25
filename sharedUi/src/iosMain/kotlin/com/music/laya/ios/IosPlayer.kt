package com.music.laya.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.laya.data.NerdStats
import com.music.laya.data.TelemetryProvenance
import com.music.laya.data.TrackLog
import com.music.laya.data.stats.ListeningRecorder
import com.music.laya.platform.currentTimeMillis
import io.ktor.http.Url
import com.music.laya.data.listentogether.PartyTrack
import com.music.laya.data.listentogether.ListenTogether
import com.music.laya.playback.toQueueSong
import com.music.laya.data.YtMusicRepository
import com.music.laya.data.innertube.PlaybackTracker
import com.music.laya.data.innertube.PlayerClient
import com.music.laya.data.innertube.StreamResolver
import com.music.laya.data.model.QueueTier
import com.music.laya.data.model.Song
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.settings.SmartAnalysis
import com.music.laya.data.settings.TrackAnalysisState
import com.music.laya.data.settings.TransitionWindow
import com.music.laya.playback.smart.AnalysisSource
import com.music.laya.playback.smart.AutomixAnalyzer
import com.music.laya.playback.smart.CrossfadeMode
import com.music.laya.playback.smart.TrackAnalysis
import com.music.laya.playback.smart.TransitionTrackInfo
import com.music.laya.playback.smart.planTransition
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue

/** Android's rule: googlevideo in bounded ranges (it throttles one long read), any other host in one open request. */
private fun chunkBytesFor(url: String) = PlayerClient.rangeBytesFor(url)
/** Retries of one broken track before it is left paused for the listener; reset by a different track or a quiet minute. */
private const val MAX_RECOVERIES = 3

/** Most AutoPlay tracks queued ahead at once, same as Android's MAX_QUEUED_AUTOPLAY. */
private const val MAX_AUTOPLAY = 10

/** How long a source ranked above YouTube gets before a track starts on YouTube regardless (Android's SUBSTITUTE_TIMEOUT_MS). */
private const val SUBSTITUTE_TIMEOUT_MS = 20_000L

/** Android's DEFAULT_SMART_FALLBACK_SECONDS: the fade a pair gets before it is analysed, when the slider says Off. */
private const val DEFAULT_SMART_FALLBACK_SECONDS = 6.0

/** Tracks kept queued on the receiver after the playing one (Android CastPlayback.AHEAD), so it moves on alone with the phone asleep. */
private const val CAST_AHEAD = 3

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

    /** Sleep timer, as Android's SleepTimer: a minutes preset (null = off) or "pause when this track ends". */
    var sleepMinutes by mutableStateOf<Int?>(null); private set
    var sleepAfterTrack by mutableStateOf(false); private set
    private var sleepJob: Job? = null

    fun startSleep(minutes: Int) {
        cancelSleep()
        sleepMinutes = minutes
        sleepJob = scope.launch { delay(minutes * 60_000L); cancelSleep(); pauseOut() }
    }
    fun startSleepAfterTrack() { cancelSleep(); sleepAfterTrack = true; syncTransition() }
    fun cancelSleep() { sleepJob?.cancel(); sleepJob = null; sleepMinutes = null; sleepAfterTrack = false; syncTransition() }

    private var startJob: Job? = null
    private var nextJob: Job? = null
    private var upgradeJob: Job? = null
    private var autoplayJob: Job? = null
    private var preloaded = -1
    /** Tracks whose current stream came from a source above YouTube, so a failure can fall back once. */
    private val offSite = mutableSetOf<String>()
    /** What [resolveInto] last handed the engine for each track, so Karaoke can decode the same bytes. */
    private val streams = mutableMapOf<String, KaraokeSource>()
    /** Entry ids of the upcoming tracks as they were before Shuffle, so turning it off can restore them. */
    private var preShuffle = emptyList<String?>()
    /** Set while a restored track sits paused and unloaded; the first play loads it and seeks here. */
    private var pendingMs: Long? = null
    private var lastSavedAt = 0L
    private val party = IosPartySync(scope, this)

    init {
        engine.setListener(this)
        IosCast.player = this
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
        party.start()
        scope.launch { AppSettings.spatialAudio.collect { IosDsp.setSpatial(it) } }
        scope.launch { AppSettings.playbackSpeed.collect { engine.setSpeed(it) } }
        scope.launch {
            combine(AppSettings.crossfadeSeconds, AppSettings.smartFadeEnabled, AppSettings.repeatMode, ListenTogether.state.map { it.inParty }) { _, _, _, _ -> }
                .collect { syncTransition() }
        }
        scope.launch { combine(AppSettings.reverbEnabled, AppSettings.reverb) { on, r -> on to r }.collect { (on, r) -> IosDsp.setReverb(on, r) } }
        scope.launch { combine(Karaoke.enabled, Karaoke.level) { on, l -> on to l }.collect { (on, l) -> IosDsp.setKaraoke(on, l) } }
        scope.launch { combine(Karaoke.enabled, Karaoke.warm) { on, warm -> on || warm }.distinctUntilChanged().collect { bindKaraoke() } }
        scope.launch {
            combine(AppSettings.reverbEnabled, Karaoke.enabled, AppSettings.spatialAudio, AppSettings.skipSilence) { r, k, sp, s -> (r || k || sp) to s }
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

    /**
     * Tells the engine whether the queued track is joined by a crossfade, under Android's CrossfadeController rules:
     * never in a party (each member would start the next song at its own moment), on Cast, on repeat-one (a track
     * fading into itself), or with the sleep timer due at this track's end (the pause would land mid-fade).
     * A skip or a seek is never a fade: they restart or cancel the engine's.
     */
    private fun syncTransition() {
        val off = IosCast.active || ListenTogether.state.value.inParty || sleepAfterTrack || autoNext() == index
        when {
            off -> noTransition()
            AppSettings.smartFadeEnabled.value -> automix(position.positionMs)
            else -> {
                AppSettings.smartTransitionWindow.value = null // Automix's seek-bar marker goes with it (CrossfadeController)
                // Android's classic crossfade: the shared renderer at its defaults (equal power, nothing filtered).
                val fadeMs = AppSettings.crossfadeSeconds.value * 1000L
                val next = queue.getOrNull(autoNext())
                IosTransition.setPlan(
                    if (fadeMs <= 0) null else IosTransitionPlan(
                        source = null, cueSec = 0.0, inRate = 1.0, outRate = 1.0, beatMs = 0, startMs = -1, fadeMs = fadeMs,
                        outAnalysis = TrackAnalysis(), inAnalysis = TrackAnalysis(), inDurationSec = next?.let(::secondsOf) ?: 0.0,
                    ),
                )
                engine.setTransition(-1, fadeMs, 0, 1f, 1f)
            }
        }
    }

    private fun noTransition() {
        AppSettings.smartTransitionWindow.value = null
        IosTransition.setPlan(null)
        engine.setTransition(-1, 0, 0, 1f, 1f)
    }

    // ---- Automix (Android's CrossfadeController.considerSmartTransition; the blend itself is rendered by IosTransition) ----

    private val analyzer = AutomixAnalyzer(scope, ::chunkBytesFor)
    private var lastVerdict = ""

    private fun source(s: Song) = streams[s.videoId]?.let { AnalysisSource(it.url, it.headers, it.mime, it.length) }

    /** "3:45" / "1:02:03" as seconds, or 0 when the row states none. */
    private fun secondsOf(s: Song) = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
        ?.fold(0) { acc, part -> acc * 60 + part }?.toDouble() ?: 0.0

    /**
     * Asks for the analysis of the playing and the next track (a no-op once measured), publishes the stats line's
     * state, and hands the engine the planner's window for this pair. The decode, the DSP and the beat model all run
     * on the analyzer's worker; this only reads what they have finished.
     */
    private fun automix(positionMs: Long) {
        val cur = song ?: return
        val next = queue.getOrNull(autoNext())
        val dur = durationMs.takeIf { it > 0 } ?: return
        if (next == null || cur.isVideoOrigin || next.isVideoOrigin) {
            AppSettings.smartTransitionWindow.value = null
            noTransition()
            return
        }
        source(cur)?.let { analyzer.request(cur.videoId, it, dur / 1000.0) }
        source(next)?.let { analyzer.request(next.videoId, it, secondsOf(next)) }
        val state = SmartAnalysis(analyzer.stateOf(cur.videoId), analyzer.stateOf(next.videoId))
        if (AppSettings.smartAnalysis.value != state) AppSettings.smartAnalysis.value = state

        val a = analyzer.analysisFor(cur.videoId)
        val b = analyzer.analysisFor(next.videoId)
        val plan = planTransition(
            analysis = a,
            nextAnalysis = b,
            currentTrack = TransitionTrackInfo(cur.videoId, dur, cur.title, cur.artist),
            nextTrack = TransitionTrackInfo(next.videoId, (secondsOf(next) * 1000).toLong(), next.title, next.artist),
            currentTime = positionMs / 1000.0,
            duration = dur / 1000.0,
            fadeSeconds = AppSettings.crossfadeSeconds.value.takeIf { it > 0 }?.toDouble() ?: DEFAULT_SMART_FALLBACK_SECONDS,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        val verdict = "${plan.reason}|${plan.transitionStyle}|start=${plan.transitionStart}|fade=${plan.fadeMs}|cue=${plan.incomingCueTime}" +
            "|rate=${plan.incomingPlaybackRate}/${plan.outgoingPlaybackRate}|blocked=${plan.blocked}|bpm=${a.bpm}/${b.bpm}"
        if (verdict != lastVerdict) { lastVerdict = verdict; println("Automix plan ${cur.videoId}->${next.videoId}: $verdict") }

        val measured = setOf(TrackAnalysisState.ANALYSED, TrackAnalysisState.REFINING)
        AppSettings.smartTransitionWindow.value = if (!plan.blocked && plan.markerVisible && state.current in measured && state.next in measured) {
            TransitionWindow((plan.transitionStart * 1000.0 / dur).toFloat().coerceIn(0f, 1f), (plan.transitionEnd * 1000.0 / dur).toFloat().coerceIn(0f, 1f))
        } else null
        if (plan.blocked || plan.fadeMs <= 0) return noTransition()
        IosTransition.setPlan(
            IosTransitionPlan(
                source = plan, cueSec = plan.incomingCueTime, inRate = plan.incomingPlaybackRate, outRate = plan.outgoingPlaybackRate,
                beatMs = (plan.beatSeconds * 1000).toLong(), startMs = (plan.transitionStart * 1000).toLong(), fadeMs = plan.fadeMs,
                outAnalysis = a, inAnalysis = b, inDurationSec = secondsOf(next),
            ),
        )
        engine.setTransition(
            (plan.transitionStart * 1000).toLong(), plan.fadeMs, (plan.incomingCueTime * 1000).toLong(),
            plan.incomingPlaybackRate.toFloat(), plan.outgoingPlaybackRate.toFloat(),
        )
    }

    /** A host that holds the controls takes them from every listener: the call does nothing. */
    private fun locked() = ListenTogether.state.value.controlsLocked

    /** A user action: refused while locked, and told to the party once done. */
    private inline fun intent(block: () -> Unit) {
        if (locked()) return
        block()
        party.onLocalIntent()
    }

    fun play(songs: List<Song>, startIndex: Int) = intent {
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

    fun next() = intent { manualNext().let { if (it >= 0) start(it) } }
    fun previous() = intent { if (hasPrevious) start(index - 1) else rawSeek(0) }
    fun jumpTo(i: Int) = intent { if (i in queue.indices) start(i) }
    fun togglePlayPause() {
        if (party.handleLockedPlayPause()) return
        val at = pendingMs
        if (at != null) start(index, at) else if (isPlaying) pauseOut() else resumeOut()
        party.onLocalIntent()
    }

    private fun saveState() { if (song != null) IosPlayerStore.saveState(pendingMs ?: position.positionMs, durationMs) }

    fun seekTo(ms: Long) = intent { rawSeek(ms) }

    private fun rawSeek(ms: Long) {
        if (pendingMs != null) { pendingMs = ms; position.seeks++; position.report(ms); return }
        if (IosCast.active) { castSeekAt = NSDate().timeIntervalSinceReferenceDate; IosCast.native?.seekTo(ms) } else
        engine.seekTo(ms)
        position.seeks++
        position.report(ms)
    }

    fun playNext(song: Song) = enqueue(song, next = true)
    fun addToQueue(song: Song) = enqueue(song, next = false)

    private fun enqueue(song: Song, next: Boolean) = intent {
        if (queue.isEmpty()) return@intent play(listOf(song), 0)
        val at = QueueTimeline.findUserQueueInsertionIndex(queue, index, next)
        queue = queue.toMutableList().apply { add(at, song.asQueueEntry(QueueTier.USER_QUEUE)) }
        queueChanged()
    }

    fun removeAt(i: Int) = intent {
        if (i !in queue.indices || i == index) return@intent
        queue = queue.toMutableList().apply { removeAt(i) }
        if (i < index) index--
        queueChanged()
    }

    fun move(from: Int, to: Int) = intent {
        if (from !in queue.indices || to !in queue.indices || from == to) return@intent
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
    fun clearUserQueue() = intent {
        val drop = QueueTimeline.upcomingUserQueueIndices(queue.map { it.queueTier }, index).toSet()
        if (drop.isEmpty()) return@intent
        queue = queue.filterIndexed { i, _ -> i !in drop }
        queueChanged()
    }

    /** Shuffle as an edit to the upcoming queue (same rules as Android's QueueShuffle): your queue stays pinned first. */
    fun setShuffle(on: Boolean) = intent {
        AppSettings.setShuffleEnabled(on)
        if (index !in queue.indices) return@intent
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
        if (ListenTogether.state.value.inParty) return ListenTogether.setAutoplay(on)
        AppSettings.setAutoplay(on)
        if (on) topUpAutoplay()
    }

    // ---- Listen Together: the party's writes to the engine, which are never intents ----

    internal fun partyPause() = pauseOut()
    internal fun partyResume() { if (pendingMs != null) start(index, pendingMs ?: 0) else resumeOut() }
    internal fun partySeek(ms: Long) = rawSeek(ms)

    /** Puts the party's running order on this device and starts the track at [at]. */
    internal fun partyLoad(tracks: List<PartyTrack>, at: Int) {
        queue = tracks.map { it.toQueueSong().let { s -> s.asQueueEntry(s.queueTier) } }
        start(at)
    }

    /** Replaces what follows the playing track with the party's, keeping the entries already here. */
    internal fun partyUpcoming(tracks: List<PartyTrack>) {
        val head = queue.take(index + 1)
        val have = queue.drop(index + 1)
        if (have.map { it.videoId to (it.queueTier == QueueTier.AUTOPLAY) } == tracks.map { it.videoId to it.fromAutoplay }) return
        val reuse = have.groupBy { it.videoId }.mapValues { it.value.toMutableList() }
        queue = head + tracks.map { t ->
            reuse[t.videoId]?.takeIf { it.isNotEmpty() }?.removeAt(0)
                ?.let { it.copy(queueTier = if (t.fromAutoplay) QueueTier.AUTOPLAY else QueueTier.USER_QUEUE) }
                ?: t.toQueueSong().let { s -> s.asQueueEntry(s.queueTier) }
        }
        IosPlayerStore.saveQueue(queue, index)
        announce()
        preResolveNext()
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
        val inParty = ListenTogether.state.value
        // A party has one AutoPlay supplier, elected the way Android does; the others wait for its queue.
        if (inParty.inParty && (!inParty.playback.autoplayEnabled || inParty.you?.memberId != partyAutoplaySupplier(inParty))) return
        if (!inParty.inParty && !AppSettings.autoplay.value || AppSettings.repeatMode.value == RepeatModes.ALL ||
            index != queue.lastIndex || autoplayJob?.isActive == true
        ) return
        autoplayJob = scope.launch {
            val related = YtMusicRepository.radio(youtubeSeedFor(seed) ?: return@launch).getOrNull() ?: return@launch
            val extra = QueueBuilder.extend(queue, related, MAX_AUTOPLAY)
            if (extra.isEmpty() || song?.videoId != seed.videoId) return@launch
            queue = queue + extra.map { it.asQueueEntry(QueueTier.AUTOPLAY) }
            announce()
            preResolveNext()
            party.onLocalIntent()
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
        // A downloaded copy always wins: no lookup, no network (a bad file falls back to the stream like any failed source).
        IosDownloads.fileUrl(song.videoId)?.let { url ->
            val mime = when (url.substringAfterLast('.')) {
                "flac" -> "audio/flac"; "wav" -> "audio/wav"; "aiff" -> "audio/aiff"; "mp3" -> "audio/mpeg"; "aac" -> "audio/aac"
                else -> "audio/mp4"
            }
            return Opened(url, emptyMap(), mime, -1L, "Downloaded")
        }
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
        if (o.sourceName != "YouTube") offSite += song.videoId else offSite -= song.videoId
        // Host only: addon URLs carry the owner's token in the path or query.
        TrackLog.d(
            "Laya",
            "iOS player ${if (next) "queued" else "opened"} via ${o.sourceName} (${runCatching { Url(o.url).host }.getOrDefault("?")}) " +
                "mime=${o.mime} declared=${o.format?.summary ?: "nothing"} ua=${if (o.headers.containsKey("User-Agent")) "custom" else "default"}",
            about = song.videoId,
        )
        NerdStats.recordSource(song.videoId, o.sourceName)
        o.format?.let { NerdStats.onSourceStream(song.videoId, it, o.sourceName) }
        if (next) { syncTransition(); engine.setNext(o.url, o.headers, o.mime, o.length, chunkBytesFor(o.url)) } // plan first: the engine parks the track by it
        else engine.play(o.url, o.headers, o.mime, o.length, chunkBytesFor(o.url))
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
        ListeningRecorder.onStopped() // as PlaybackService on a transition: repeat-one's second pass must not continue the first
        // YouTube history: close the outgoing play. A retry of the same track mid-way (recover) is not a new play.
        if (fromMs == 0L || song.videoId != this.song?.videoId) { PlaybackTracker.onTrackChanged(trackedSec); trackedSec = 0 }
        index = i
        NerdStats.onTrackTransition(song.videoId)
        TrackLog.onTrackStarted(song.videoId)
        pendingMs = null
        isLoading = true
        durationMs = 0
        position.report(fromMs)
        IosPlayerStore.saveQueue(queue, i)
        IosPlayerStore.saveState(fromMs, 0)
        nextJob?.cancel()
        upgradeJob?.cancel()
        startJob?.cancel()
        if (IosCast.active) return castLoad(song, fromMs, play = true)
        startJob = scope.launch {
            runCatching { resolveInto(song, next = false) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) return@onFailure
                    println("IosPlayer: ${it.message ?: it}")
                    if (it is StreamResolver.PermanentlyUnplayableException) {
                        isLoading = false
                        IosPlayerHost.showMessage("Can't play ${song.title}")
                    } else recover(song, it.message ?: "resolve failed", 0)
                }
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
                native.openPcm(source.url, source.headers, source.mime, source.length, chunkBytesFor(source.url))?.let(::IosPcmStream)
            },
        )
    }

    /** Resolves the following track now so the engine can start it gaplessly. */
    private fun preResolveNext() {
        if (IosCast.active) return castReconcile()
        nextJob?.cancel()
        preloaded = autoNext()
        val upcoming = queue.getOrNull(preloaded) ?: return engine.clearNext()
        nextJob = scope.launch { runCatching { resolveInto(upcoming, next = true) } }
    }

    // ---- Cast (Android's CastPlayback): this queue stays, the receiver owns the clock ----

    private fun pauseOut() = if (IosCast.active) IosCast.native?.pause() else engine.pause()
    private fun resumeOut() = if (IosCast.active) IosCast.native?.play() else engine.resume()

    /** The track whose load the receiver has not answered yet; its status, not the old one's, is believed meanwhile. */
    private var castLoading: String? = null
    private var castSeekAt = 0.0
    private var castReconcileJob: Job? = null

    /** What the receiver can open by itself, or null (with a notice) for a local file or a source that needs headers. */
    private suspend fun castItem(s: Song, patient: Boolean): CastItem? {
        val o = withContext(ioDispatcher) { runCatching { open(s, patient) }.getOrNull() }
        if (o == null || o.sourceName == "Downloaded" || (o.sourceName != "YouTube" && o.headers.isNotEmpty())) return null
        return CastItem(o.url, o.mime, s.videoId, s.title, s.artist, s.albumName, s.thumbnailUrl?.takeIf { it.startsWith("http") })
    }

    private fun castLoad(s: Song, at: Long, play: Boolean) {
        val native = IosCast.native ?: return
        castLoading = s.videoId
        isLoading = true
        position.report(at)
        pendingMs = null
        if (play && at == 0L && native.jumpTo(s.videoId)) { announce(); castReconcile(); return } // already queued there: instant
        startJob = scope.launch {
            val item = castItem(s, patient = false)
            if (item == null || !IosCast.active || song?.videoId != s.videoId) {
                if (item == null) { IosPlayerHost.showMessage("This track can't be cast"); castLoading = null; isLoading = false }
                return@launch
            }
            native.load(item, at, play)
            announce()
        }
    }

    internal fun castBegan(resumed: Boolean) {
        val s = song ?: return
        val playing = isPlaying
        val at = pendingMs ?: position.positionMs
        engine.pause()
        syncTransition()
        IosVolume.setter = { IosCast.native?.setVolume(it) }
        // A session that outlived the app and is already on this track keeps going instead of restarting.
        if (resumed && IosCast.native?.currentMediaId() == s.videoId) { pendingMs = null; castReconcile() } else castLoad(s, at, play = playing)
    }

    /** The receiver let go: its place comes back to the phone, paused unless the listener chose "Stop casting" while it played. */
    internal fun castEnded(resumeHere: Boolean) {
        castLoading = null
        IosVolume.setter = engine::setSystemVolume
        IosVolume.level.value = engine.systemVolume()
        val at = position.positionMs
        val was = isPlaying
        isPlaying = false
        isLoading = false
        if (song == null) return
        if (resumeHere && was) start(index, at) else pendingMs = at
    }

    internal fun castStatus(mediaId: String?, positionMs: Long, durationMs: Long, state: Int) {
        val loading = castLoading
        if (loading != null) {
            if (state == 5) { castLoading = null; isLoading = false; IosPlayerHost.showMessage("This track can't be cast"); return }
            if (mediaId != loading || state !in 1..2) return
            castLoading = null
            castReconcile()
        } else if (mediaId != null && mediaId != song?.videoId) { // the receiver moved on by itself, or its own remote did
            val to = queue.indices.filter { queue[it].videoId == mediaId }.minByOrNull { kotlin.math.abs(it - index) } ?: return
            index = to
            NerdStats.onTrackTransition(mediaId)
            IosPlayerStore.saveQueue(queue, index)
            announce()
            castReconcile()
            topUpAutoplay()
            party.onLocalIntent()
        }
        isPlaying = state == 1
        isLoading = state == 3
        if (durationMs > 0) this.durationMs = durationMs
        // A seek is asked now and answered a moment later; until then the receiver's clock still reads the old place.
        if (NSDate().timeIntervalSinceReferenceDate - castSeekAt > 1.5) position.report(positionMs)
        if (state == 4) autoNext().let { if (it >= 0) start(it) else isPlaying = false } // ran out of the few tracks it holds
        if (!isPlaying && song != null) saveState()
    }

    /** Makes the receiver's queue after the playing track match the next few of ours (Android's reconcileOnce). */
    private fun castReconcile() {
        castReconcileJob?.cancel()
        castReconcileJob = scope.launch {
            delay(300) // let the receiver's own status catch up with whatever just changed it
            val native = IosCast.native ?: return@launch
            if (castLoading != null || !IosCast.active) return@launch
            val want = if (AppSettings.repeatMode.value == RepeatModes.ONE) emptyList() else queue.drop(index + 1).take(CAST_AHEAD)
            val have = native.aheadIds()
            var keep = 0
            while (keep < have.size && keep < want.size && have[keep] == want[keep].videoId) keep++
            if (keep == have.size && keep == want.size) return@launch
            if (keep < have.size) native.trimAhead(keep)
            val playing = song?.videoId
            for (next in want.drop(keep)) {
                val item = castItem(next, patient = true) ?: break
                if (!IosCast.active || song?.videoId != playing) return@launch
                native.append(item)
            }
        }
    }

    override fun onProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean, isBuffering: Boolean) {
        if (IosCast.active) { // the receiver owns the clock; a lock-screen Play that woke this engine goes to it instead
            if (isPlaying) { engine.pause(); IosCast.native?.play() }
            return
        }
        position.report(positionMs)
        this.durationMs = durationMs
        this.isPlaying = isPlaying
        isLoading = isBuffering
        recordListening(isPlaying, durationMs)
        if (isPlaying && AppSettings.smartFadeEnabled.value && pendingMs == null) syncTransition()
        // Position about every 5 s while playing, and straight away on pause.
        val now = NSDate().timeIntervalSinceReferenceDate.toLong()
        if (pendingMs == null && song != null && (!isPlaying || now - lastSavedAt >= 5)) { lastSavedAt = now; saveState() }
    }

    private var lastListenTickAt = 0L
    /** Last position reported to PlaybackTracker, which closes a play out on it. */
    private var trackedSec = 0L

    /** PlaybackService's sampler: a tick every PROGRESS_SAMPLE_MS while audio plays, and a stop the moment it does not. */
    private fun recordListening(playing: Boolean, durationMs: Long) {
        val now = currentTimeMillis()
        if (!playing) {
            if (lastListenTickAt != 0L) { lastListenTickAt = 0L; ListeningRecorder.onStopped() }
            return
        }
        if (now - lastListenTickAt < LISTEN_SAMPLE_MS) return
        lastListenTickAt = now
        song?.let { ListeningRecorder.onSample(it, durationMs) }
        // The account's history (PlaybackService's sampler): registers the play once audible, then reports watch time.
        song?.videoId?.let { id ->
            trackedSec = position.positionMs / 1000
            PlaybackTracker.onPlaying(id)
            PlaybackTracker.onProgress(id, trackedSec)
        }
    }

    override fun onAudioFormat(mimeType: String?, sampleRateHz: Int, channels: Int, bitDepth: Int, bitrateKbps: Int) {
        val id = song?.videoId ?: return
        val lossless = NerdStats.isLosslessMime(mimeType)
        // The resolver's figure first (as Android); AVFoundation's estimate off a half-loaded stream only as a fallback.
        // A lossless source states no bitrate worth trusting; the engine's figure (bytes over runtime) is the measured one.
        val (kbps, provenance) = NerdStats.sourceBitrate(id) { bitrateKbps.takeIf { it > 0 } }
            ?: bitrateKbps.takeIf { it > 0 && lossless }?.let { it to TelemetryProvenance.MEASURED }
            ?: (null to TelemetryProvenance.UNKNOWN)
        println("IosPlayer: decoder reports $mimeType ${sampleRateHz}Hz ${channels}ch ${bitDepth}-bit ${bitrateKbps}kbps; source said ${NerdStats.declaredFormat(id)?.summary ?: "nothing"}")
        NerdStats.publish(
            NerdStats.Snapshot(
                mimeType = mimeType,
                bitrateKbps = kbps,
                sampleRateHz = sampleRateHz.takeIf { it > 0 },
                channels = channels.takeIf { it > 0 },
                bitDepth = bitDepth.takeIf { lossless && it > 0 },
                sourceName = NerdStats.exactSourceFor(id) ?: "YouTube",
                bitrateProvenance = provenance,
                claimed = NerdStats.declaredFormat(id),
                mediaId = id,
            ),
            NerdStats.currentGeneration,
        )
        IosPlayerHost.outputFormat.value = com.music.laya.ui.player.OutputFormatUi(outputSummary(), (outputRateHz() ?: 0) > 48_000)
    }

    override fun onEnded() {
        if (IosCast.active) return
        if (sleepAfterTrack) { cancelSleep(); isPlaying = false; PlaybackTracker.onPlaybackFinished(trackedSec); return }
        autoNext().let { if (it >= 0) start(it) else { isPlaying = false; PlaybackTracker.onPlaybackFinished(trackedSec) } }
        party.onLocalIntent()
    }

    override fun onAdvancedToNext() {
        if (IosCast.active) return
        ListeningRecorder.onStopped()
        PlaybackTracker.onTrackChanged(trackedSec)
        trackedSec = 0
        index = preloaded
        if (sleepAfterTrack) { cancelSleep(); engine.pause() }
        NerdStats.onTrackTransition(song?.videoId)
        position.report(0)
        IosPlayerStore.saveQueue(queue, index)
        announce()
        preResolveNext()
        topUpAutoplay()
        party.onLocalIntent()
    }

    override fun onError(message: String, httpStatus: Int) {
        println("IosPlayer: $message ($httpStatus)")
        val failed = song ?: run { isLoading = false; return }
        // Android's PlaybackFallback: a source that failed after being picked is not asked again, YouTube plays instead.
        if (!offSite.remove(failed.videoId)) return recover(failed, "$message ($httpStatus)", httpStatus)
        val via = NerdStats.exactSourceFor(failed.videoId) ?: "source"
        TrackLog.w("Laya", "$via failed to play: $message ($httpStatus), falling back to YouTube", about = failed.videoId)
        NerdStats.clearDeclared(failed.videoId)
        val at = position.positionMs
        upgradeJob?.cancel()
        startJob = scope.launch {
            runCatching {
                val id = withContext(ioDispatcher) { youtubeSeedFor(failed) } ?: error("no YouTube match for ${failed.title}")
                val o = withContext(ioDispatcher) { youtube(id) }
                if (song?.videoId != failed.videoId) return@launch
                hand(failed, o, next = false)
                if (at > 0) engine.seekTo(at)
                preResolveNext()
            }.onFailure { isLoading = false; println("IosPlayer: YouTube fallback failed: ${it.message ?: it}") }
        }
    }

    private var recoverFor: String? = null
    private var recoverCount = 0
    private var recoverAt = 0.0

    /**
     * A track that broke (a dropped connection, a refused URL) starts again where it stopped, with a fresh
     * URL when the old one was refused. It is never skipped: after [MAX_RECOVERIES] it waits, paused, for Play.
     */
    private fun recover(failed: Song, reason: String, httpStatus: Int) {
        if (httpStatus > 0) streams[failed.videoId]?.url?.let { StreamResolver.onPlaybackRefused(it, httpStatus) }
        val now = NSDate().timeIntervalSinceReferenceDate
        if (recoverFor != failed.videoId || now - recoverAt > 60) { recoverFor = failed.videoId; recoverCount = 0 }
        recoverAt = now
        val at = pendingMs ?: position.positionMs
        // A break in the last seconds is the end of the track (the engine says so too): retrying there replays the tail.
        if (durationMs > 0 && at >= durationMs - 3_000) { TrackLog.d("Laya", "broke at the end ($reason); moving on", about = failed.videoId); return onEnded() }
        if (++recoverCount > MAX_RECOVERIES) {
            TrackLog.w("Laya", "gave up after $MAX_RECOVERIES retries: $reason", about = failed.videoId)
            isLoading = false
            isPlaying = false
            pendingMs = at // Play tries again from here
            IosPlayerHost.showMessage("Couldn't play ${failed.title}")
            return
        }
        TrackLog.w("Laya", "playback broke ($reason); retry $recoverCount at ${at} ms", about = failed.videoId)
        isLoading = true
        scope.launch {
            delay(recoverCount * 1000L)
            if (song?.videoId == failed.videoId) start(index, at)
        }
    }

    override fun onVolumeChanged(level: Float) { if (!IosCast.active) IosVolume.level.value = level }
    override fun onRemoteNext() = next()
    override fun onRemotePrevious() = previous()
}

/** The member a party's AutoPlay queue comes from: the host, else the lowest id among those connected. */
/** PlaybackService.PROGRESS_SAMPLE_MS: the listening record is fed on this beat. */
private const val LISTEN_SAMPLE_MS = 5_000L

private fun partyAutoplaySupplier(party: ListenTogether.State): String? {
    val host = party.members.firstOrNull { it.isHost && it.connected }?.memberId
    if (party.hostOnlyControl) return host
    return host ?: party.members.asSequence().filter { it.connected }.minByOrNull { it.memberId }?.memberId
}
