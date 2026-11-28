package com.music.laya.playback

import android.os.SystemClock
import com.music.laya.data.NerdStats
import com.music.laya.data.TrackLog
import com.music.laya.data.YtMusicRepository
import com.music.laya.data.listentogether.ListenTogether
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.sources.SourceResolver
import com.music.laya.data.sources.SourceStream
import com.music.laya.data.sources.StreamFormat
import com.music.laya.data.sources.StreamLengthProbe
import com.music.laya.data.sources.TrackMatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Direct Source Stream (ported from BitChord): a YouTube-queued track plays
 * from a source ranked above YouTube from its first note, and nothing is
 * fetched for a track until it is about to play.
 *
 * Without it a track starts on whichever of YouTube and the sources answers
 * first — nearly always YouTube — and [QualityUpgrade] swaps the better copy in
 * mid-song: two resolves, two streams and a seam per track. With it on:
 *
 *  1. **One resolve, one stream.** [lookup] asks the sources and the caller
 *     waits for the answer, which plays to the end. YouTube is the fallback only
 *     when nothing answered in time, and that track is then left to
 *     [QualityUpgrade] as usual — see [startedOnYouTube].
 *  2. **Nothing is fetched early.** A track neither playing nor next within
 *     [WINDOW_MS] of its transition is held at the resolver — see [awaitOpen].
 *     That covers ExoPlayer's own early preparation of the next item and
 *     read-ahead alike. A skip interrupts the loader thread, cancelling the wait.
 *  3. **The window opens on playback alone.** [PlaybackService] opens it from a
 *     tick, only while playing and once the playhead has run on its own past
 *     the last seek, so scrubbing or skipping never fetches the track after.
 *
 * Automix's `automix_opus` analysis reads skip all of this and keep measuring
 * YouTube's Opus.
 */
object DirectSourceGate {

    private const val TAG = "Laya"

    /** How far ahead of a transition the next track may be resolved and read ahead. */
    const val WINDOW_MS = 25_000L

    /**
     * How long playback waits for the sources before falling back to YouTube.
     * Shorter than [WINDOW_MS] so a window-opened lookup leaves time for the
     * fallback to buffer before the blend. A limit on the wait, not the lookup.
     */
    const val LOOKUP_TIMEOUT_MS = 15_000L

    /**
     * Whether Direct Source Stream is on *and* has a source to stream from.
     * Never in a Listen Together party: a party seeks every member to the host
     * and expects the track to be there already.
     */
    fun isActive(): Boolean =
        AppSettings.directSourceStream.value && !ListenTogether.state.value.inParty &&
            SourceResolver.directSources().isNotEmpty()

    private data class Open(val playing: Set<String>, val next: String?)

    private val open = MutableStateFlow(Open(emptySet(), null))

    /**
     * What is audible right now: the session's track and, mid-crossfade, the
     * other player's. A change closes the window on whatever was next.
     */
    fun onPlaying(mediaIds: Set<String>) {
        val now = open.value
        if (now.playing == mediaIds) return
        open.value = Open(mediaIds, now.next?.takeIf { it in mediaIds })
    }

    /** Opens the window on [mediaId]. Returns false when it was already open. */
    fun openNext(mediaId: String): Boolean {
        val now = open.value
        if (now.next == mediaId || mediaId in now.playing) return false
        open.value = now.copy(next = mediaId)
        return true
    }

    private fun Open.admits(mediaId: String) = mediaId in playing || mediaId == next

    /**
     * Suspends a resolve for [mediaId] until it is playing or next inside the
     * window. Returns at once when the gate is off, the track is admitted, or
     * the calling thread is inside [ungated].
     */
    suspend fun awaitOpen(mediaId: String) {
        if (bypass.get() == true || !isActive() || open.value.admits(mediaId)) return
        TrackLog.d(TAG, "holding $mediaId until it is within ${WINDOW_MS / 1000}s of playing", about = mediaId)
        combine(open, AppSettings.directSourceStream, ListenTogether.state) { state, on, party ->
            !on || party.inParty || state.admits(mediaId)
        }.first { it }
        TrackLog.d(TAG, "released $mediaId", about = mediaId)
    }

    private val bypass = ThreadLocal<Boolean>()

    /** Runs [block] with the gate open, for a resolve made for something other than this player (Cast, the watch). */
    fun <T> ungated(block: () -> T): T {
        val was = bypass.get()
        bypass.set(true)
        try {
            return block()
        } finally {
            bypass.set(was)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private class Lookup(val work: Deferred<SourceStream?>, val startedAtMs: Long)

    private val lookups = ConcurrentHashMap<String, Lookup>()

    /**
     * Tracks no source had (a clean miss, not an error), so read-ahead's empty answer is not searched for
     * again (another [LOOKUP_TIMEOUT_MS] of silence) when the track starts.
     * ponytail: process-lifetime, bounded; a source enabled later is only
     * asked once the entry ages out.
     */
    private val missed = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > KEPT
    }

    /**
     * The source stream for [videoId], shared between whoever asks at once —
     * read-ahead and the player's own resolve arrive together and should cost
     * one search. Null means nothing had it in time; the caller falls back to
     * YouTube and says so with [startedOnYouTube].
     */
    suspend fun lookup(videoId: String, target: TrackMatcher.Target): SourceStream? {
        synchronized(missed) {
            missed[videoId]?.let { at -> if (SystemClock.elapsedRealtime() - at < MISS_KEPT_MS) return null }
        }
        val fresh = Lookup(
            work = scope.async(TrackLog.about(videoId), start = CoroutineStart.LAZY) { find(videoId, target) },
            startedAtMs = SystemClock.elapsedRealtime(),
        )
        val running = lookups.putIfAbsent(videoId, fresh)
        if (running != null) {
            fresh.work.cancel()
        } else {
            fresh.work.invokeOnCompletion { lookups.remove(videoId, fresh) }
            fresh.work.start()
        }
        val lookup = running ?: fresh
        val waited = SystemClock.elapsedRealtime() - lookup.startedAtMs
        return withTimeoutOrNull(LOOKUP_TIMEOUT_MS - waited) { lookup.work.await() }
    }

    /** The lookup for [videoId] if it is still going, past the point anyone waits for it. */
    fun stillLooking(videoId: String): Deferred<SourceStream?>? = lookups[videoId]?.work

    private suspend fun find(videoId: String, target: TrackMatcher.Target): SourceStream? {
        // A row queued without a runtime can't be held to one; YouTube's is one
        // metadata call away. ponytail: asked before the search rather than
        // beside it, so such rows (rare: nearly every row carries a duration)
        // pay up to LENGTH_TIMEOUT_MS more.
        val withLength = if (target.durationSec != null) target else target.copy(durationSec = youTubeLength(videoId))
        val answer = SourceResolver.directStream(withLength, rejects = { cutShort(it, withLength) })
        // Only a clean "nobody has it" is remembered. A source that errored may
        // have it once it recovers, so the next play asks again.
        if (answer.stream == null && !answer.errored) {
            synchronized(missed) { missed[videoId] = SystemClock.elapsedRealtime() }
        }
        return answer.stream
    }

    private suspend fun youTubeLength(videoId: String): Int? = withTimeoutOrNull(LENGTH_TIMEOUT_MS) {
        YtMusicRepository.trackLinks(videoId).getOrNull()?.durationText?.let(TrackMatcher::secondsOf)
    }

    /**
     * Whether [stream]'s file is too small to hold the recording — a source's
     * 30-second preview under a full-length row. Nothing auditions this answer,
     * so its size is checked with one ranged request. Answers no whenever it
     * can't measure (a manifest, a server that won't say).
     */
    private suspend fun cutShort(stream: SourceStream, target: TrackMatcher.Target): Boolean {
        val expectedSec = target.durationSec ?: return false
        if (StreamContainer.isManifest(stream.url)) return false
        val bytes = StreamLengthProbe.bytesOf(stream.url, stream.headers) ?: return false
        if (!StreamLengthProbe.tooShort(bytes, stream.format.isLossless == true, stream.format.kbps, expectedSec)) return false
        TrackLog.w(TAG, "'${target.title}': ${bytes / 1024}kB can't hold ${expectedSec}s — a preview; looking further")
        return true
    }

    /**
     * [videoId] is about to play from YouTube because nothing answered in time,
     * so it gets the second look it would get with the setting off. A lookup
     * still running is handed over and swaps in when it lands.
     */
    fun startedOnYouTube(videoId: String, target: TrackMatcher.Target) {
        val pending = QualityUpgrade.settledForLess(
            mediaId = videoId,
            target = target,
            inFlight = stillLooking(videoId),
            playing = NerdStats.pickedBitrateKbps(videoId)?.let { StreamFormat(kbps = it) },
        )
        if (!pending) NerdStats.onLosslessRaceEnd(videoId)
    }

    private const val KEPT = 256
    private const val MISS_KEPT_MS = 30 * 60_000L
    private const val LENGTH_TIMEOUT_MS = 5_000L
}
