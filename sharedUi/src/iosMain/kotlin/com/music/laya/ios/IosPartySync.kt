package com.music.laya.ios

import com.music.laya.data.listentogether.ListenTogether
import com.music.laya.data.listentogether.partyQueueIndexOf
import com.music.laya.data.listentogether.partyUpcomingAfter
import com.music.laya.platform.nanoTime
import com.music.laya.playback.PartyDriftCorrector
import com.music.laya.playback.PartySyncLogic
import com.music.laya.playback.toPartyTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.TimeSource

/**
 * Keeps [IosPlayer] and the party in step, after Android's `PartySync` and the desktop's port of it.
 *
 * Outbound is [onLocalIntent], called by the player's own user-facing methods; inbound is
 * [reconcile], which writes to the engine through the player's `party*` methods, so nothing it does
 * can come back round as an intent and be republished.
 */
internal class IosPartySync(private val scope: CoroutineScope, private val player: IosPlayer) {

    private val jobs = mutableListOf<Job>()
    private var startJob: Job? = null
    private var publishJob: Job? = null

    private var loadingVideoId: String? = null
    private val drift = PartyDriftCorrector()
    private var awaitPlaybackSeq = Long.MAX_VALUE
    private var awaitQueueSeq = Long.MAX_VALUE
    private var locallyPaused = false

    /**
     * iOS paused the audio for something that was not the listener: a call, Siri, the headphones
     * coming out. Android's `focusLost`: one person's call does not pause the party, and this device
     * stops following it — or the next tick would put the music back over the call, or out of the
     * speaker — until its own listener presses play, which rejoins without telling anyone.
     */
    private var detached = false
    private var lastPartyCode: String? = null
    private var pendingIntentVideoId: String? = null
    private var quietUntilMs = 0L
    private val clock = TimeSource.Monotonic.markNow()

    private fun nowMs() = clock.elapsedNow().inWholeMilliseconds

    fun start() {
        jobs += scope.launch {
            ListenTogether.state
                .map { Triple(it.playback.seq, it.queue.seq, it.code) }
                .distinctUntilChanged()
                .collect { key ->
                    if (key.third != lastPartyCode) {
                        lastPartyCode = key.third
                        loadingVideoId = null
                        drift.reset()
                        locallyPaused = false
                        detached = false
                    }
                    if (pendingIntentVideoId == null && key.first >= awaitPlaybackSeq && key.second >= awaitQueueSeq) {
                        quietUntilMs = 0L
                    }
                    reconcile()
                }
        }
        // Drift accumulates between controls, and nothing arrives to announce it.
        jobs += scope.launch {
            while (true) {
                delay(PartySyncLogic.RECONCILE_INTERVAL_MS)
                reconcile()
            }
        }
    }

    /** Something the listener asked for here, which the party should be told about. */
    fun onLocalIntent() {
        val party = ListenTogether.state.value
        if (!party.inParty || party.controlsLocked) return
        // Coming back after a system pause: the gap is this device's to close, and published as a
        // seek it would haul everyone back to where the call came in. So nothing is sent.
        if (detached) {
            detached = false
            quietUntilMs = 0L
            reconcile()
            return
        }
        val waitFor = player.song?.videoId.takeIf { player.isLoading }
        pendingIntentVideoId = waitFor
        awaitPlaybackSeq = Long.MAX_VALUE
        awaitQueueSeq = Long.MAX_VALUE
        quietUntilMs = if (waitFor != null) Long.MAX_VALUE else nowMs() + PartySyncLogic.INTENT_QUIET_MS
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(PartySyncLogic.PUBLISH_DEBOUNCE_MS)
            if (waitFor != null) {
                repeat(PartySyncLogic.PUBLISH_WAIT_ATTEMPTS) {
                    if (player.song?.videoId == waitFor && !player.isLoading) {
                        pendingIntentVideoId = null
                        quietUntilMs = nowMs() + PartySyncLogic.INTENT_QUIET_MS
                        publish()
                        return@launch
                    }
                    delay(PartySyncLogic.PUBLISH_WAIT_STEP_MS)
                }
                pendingIntentVideoId = null
                quietUntilMs = nowMs()
                return@launch
            }
            publish()
        }
    }

    /** The system paused this device (a call, a route lost). See [detached]. */
    fun onSystemPause() {
        if (!ListenTogether.state.value.inParty) return
        detached = true
        startJob?.cancel()
    }

    /** In a host-locked party, play/pause moves only this device. */
    fun handleLockedPlayPause(play: Boolean): Boolean {
        if (!ListenTogether.state.value.controlsLocked) return false
        detached = false
        if (!play) {
            locallyPaused = true
            player.partyPause()
        } else {
            locallyPaused = false
            ListenTogether.partyPositionMs()?.let(player::partySeek)
            player.partyResume()
        }
        return true
    }

    fun reconcile() {
        val party = ListenTogether.state.value
        if (!party.inParty) {
            loadingVideoId = null
            locallyPaused = false
            detached = false
            return
        }
        if (detached) return
        if (!party.controlsLocked) locallyPaused = false
        if (nowMs() < quietUntilMs) return
        val target = party.playback
        val track = target.track
        if (track == null) {
            // A new party is empty; its host seeds it with the music already playing.
            if (party.you?.isHost == true && party.connection == ListenTogether.Connection.LIVE) publish()
            return
        }
        // Before the first round trip the playhead is a guess: apply track and play/pause only.
        if (target.isPlaying && !party.clockSynced) return

        if (player.song?.videoId != track.videoId) {
            if (loadingVideoId != track.videoId) {
                loadingVideoId = track.videoId
                // The queue is only usable if the track is in it; between a queue and a track
                // control the party holds a new order with the old song still current.
                val at = partyQueueIndexOf(party.queue, target, track.videoId)
                if (at >= 0) player.partyLoad(party.queue.items, at) else player.partyLoad(listOf(track), 0)
            }
            return
        }
        loadingVideoId = null
        if (player.isLoading) return
        reconcileQueue(party)

        val position = PartySyncLogic.livePositionMs(
            player.position.positionMs, player.position.sampledAtNanos, nanoTime(), player.isPlaying,
        )
        if (!target.isPlaying) {
            locallyPaused = false
            if (player.isPlaying) player.partyPause()
            if (abs(position - target.positionMs) > PartySyncLogic.PAUSED_TOLERANCE_MS) {
                player.partySeek(target.positionMs)
            }
            return
        }

        val want = ListenTogether.partyPositionMs()
        if (locallyPaused) return
        if (!player.isPlaying) {
            val wait = ListenTogether.msUntilStart()
            if (wait > 0) {
                startJob?.cancel()
                startJob = scope.launch {
                    delay(wait)
                    reconcile()
                }
                return
            }
            if (want != null) player.partySeek(drift.startAt(want, target, nowMs()))
            startJob?.cancel()
            player.partyResume()
            return
        }

        if (want == null) return
        drift.correct(position, want, target, nowMs())?.let { to ->
            println("IosPartySync: ${position - want}ms off the party, seeking with ${drift.seekLagMs}ms lead")
            player.partySeek(to)
        }
    }

    /** Puts what plays after this track into line with the party's, every tick, so a local edit the party never took is put right. */
    private fun reconcileQueue(party: ListenTogether.State) {
        if (party.queue.items.isEmpty()) return
        val currentId = player.song?.videoId ?: return
        if (partyQueueIndexOf(party.queue, party.playback, currentId) < 0) return
        player.partyUpcoming(
            partyUpcomingAfter(party.queue, party.playback, currentId).take(PartySyncLogic.MAX_PARTY_UPCOMING_QUEUE),
        )
    }

    /** Tells the party what this device just did. */
    private fun publish() {
        val party = ListenTogether.state.value
        if (!party.inParty || player.isLoading) return
        val song = player.song ?: return
        val track = song.toPartyTrack(player.durationMs)
        val position = player.position.positionMs.coerceAtLeast(0L)
        val basePlayback = party.playback.seq
        val baseQueue = party.queue.seq
        var playbackControls = 0
        var queueControls = 0

        val (shareable, shareIndex) = PartySyncLogic.queueForPartyPublish(
            songs = player.queue,
            currentIndex = player.index,
            currentVideoId = song.videoId,
            currentDurationMs = player.durationMs,
        )
        // Compared from the playing track on, by track and section, not by index or history.
        val partyAt = partyQueueIndexOf(party.queue, party.playback, song.videoId)
        if (partyAt < 0 || shareIndex < 0 ||
            shareable.drop(shareIndex).map { it.videoId to it.fromAutoplay } !=
            party.queue.items.drop(partyAt).map { it.videoId to it.fromAutoplay }
        ) {
            ListenTogether.setQueue(shareable, shareIndex)
            queueControls++
        }

        // After the queue, never before: a track change pointing into a running order nobody has yet plays the wrong song.
        when {
            party.playback.track?.videoId != track.videoId -> {
                ListenTogether.setTrack(track, position, player.isPlaying)
                playbackControls++
            }
            player.isPlaying && !party.playback.isPlaying -> {
                ListenTogether.play(position)
                playbackControls++
            }
            !player.isPlaying && party.playback.isPlaying -> {
                ListenTogether.pause(position)
                playbackControls++
            }
            abs(position - (ListenTogether.partyPositionMs() ?: position)) > PartySyncLogic.SEEK_REPORT_FLOOR_MS -> {
                ListenTogether.seek(position)
                playbackControls++
            }
        }
        awaitPlaybackSeq = basePlayback + playbackControls
        awaitQueueSeq = baseQueue + queueControls
        if (playbackControls == 0 && queueControls == 0) quietUntilMs = 0L
    }
}
