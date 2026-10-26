package com.music.laya.playback

import com.music.laya.data.listentogether.PartyTrack
import com.music.laya.data.model.QueueTier
import com.music.laya.data.model.Song
import kotlin.math.abs

/** What reconciling decided about the playhead, and the counters it leaves behind. */
data class SeekDecision(
    val seek: Boolean,
    val alignedSeq: Long,
    val strikes: Int,
    val cooldownUntilMs: Long = 0,
    val reason: String = "",
)

/** The rules a device follows to stay in time with a party, free of any player. */
object PartySyncLogic {
    /** Well above two decoders' jitter, and at the level of an actual desync. */
    const val DRIFT_LIMIT_MS = 1_200L

    /** Readings in a row before a correction fires, so one stall does not cause a seek. */
    const val DRIFT_STRIKES = 2
    const val DRIFT_COOLDOWN_MS = 6_000L
    const val PAUSED_TOLERANCE_MS = 400L
    const val ALIGN_TOLERANCE_MS = 120L
    const val SEEK_REPORT_FLOOR_MS = 1_000L
    const val INTENT_QUIET_MS = 4_500L
    const val PUBLISH_DEBOUNCE_MS = 120L
    const val PUBLISH_WAIT_STEP_MS = 100L
    const val PUBLISH_WAIT_ATTEMPTS = 100
    const val RECONCILE_INTERVAL_MS = 700L
    const val MAX_PARTY_UPCOMING_QUEUE = 25

    /**
     * Whether the playhead should be moved, given how far out it is: a new control aligns at once
     * and tightly, ordinary drift has to be both large and persistent, and a correction that has
     * just fired will not fire again until the cooldown is out.
     */
    fun decideSeek(
        drift: Long,
        controlSeq: Long,
        alignedSeq: Long,
        strikes: Int,
        nowMs: Long,
        cooldownUntilMs: Long,
    ): SeekDecision {
        if (controlSeq != alignedSeq) {
            return SeekDecision(
                seek = abs(drift) > ALIGN_TOLERANCE_MS,
                alignedSeq = controlSeq,
                strikes = 0,
                reason = "aligning onto control $controlSeq",
            )
        }
        if (abs(drift) <= DRIFT_LIMIT_MS) {
            return SeekDecision(seek = false, alignedSeq = alignedSeq, strikes = 0)
        }
        if (nowMs < cooldownUntilMs) {
            return SeekDecision(seek = false, alignedSeq = alignedSeq, strikes = strikes)
        }
        val next = strikes + 1
        if (next < DRIFT_STRIKES) {
            return SeekDecision(seek = false, alignedSeq = alignedSeq, strikes = next)
        }
        return SeekDecision(
            seek = true,
            alignedSeq = alignedSeq,
            strikes = 0,
            cooldownUntilMs = nowMs + DRIFT_COOLDOWN_MS,
            reason = "correcting",
        )
    }

    /** The queue a device shares with a party: the playing track onwards, the listener's own and AutoPlay's. */
    fun queueForPartyPublish(
        songs: List<Song>,
        currentIndex: Int,
        currentVideoId: String,
        currentDurationMs: Long,
    ): Pair<List<PartyTrack>, Int> {
        val raw = songs.withIndex().filter { (_, song) -> song.localPath == null && song.localUri == null }
        val rawCurrent = raw.indexOfFirst { (originalIndex, song) ->
            originalIndex == currentIndex && song.videoId == currentVideoId
        }.takeIf { it >= 0 } ?: raw.indexOfFirst { it.value.videoId == currentVideoId }

        val withoutContextTail = if (rawCurrent >= 0) {
            raw.take(rawCurrent + 1) + raw.drop(rawCurrent + 1).filter { it.value.queueTier != QueueTier.CONTEXT }
        } else {
            raw.filter { it.value.queueTier != QueueTier.CONTEXT }
        }
        val projectedCurrent = withoutContextTail.indexOfFirst { (originalIndex, song) ->
            originalIndex == currentIndex && song.videoId == currentVideoId
        }.takeIf { it >= 0 } ?: withoutContextTail.indexOfFirst { it.value.videoId == currentVideoId }
        val endExclusive = if (projectedCurrent >= 0) {
            (projectedCurrent + 1 + MAX_PARTY_UPCOMING_QUEUE).coerceAtMost(withoutContextTail.size)
        } else {
            (1 + MAX_PARTY_UPCOMING_QUEUE).coerceAtMost(withoutContextTail.size)
        }
        val tracks = withoutContextTail.take(endExclusive).map { (_, queued) ->
            queued.toPartyTrack(if (queued.videoId == currentVideoId) currentDurationMs else 0L)
        }
        return tracks to tracks.indexOfFirst { it.videoId == currentVideoId }
    }
}

/** This device's row as the party knows it: identity and artwork, not how the audio was got. */
fun Song.toPartyTrack(durationMs: Long): PartyTrack = PartyTrack(
    videoId = videoId,
    title = title,
    artist = artist,
    thumbnailUrl = thumbnailUrl,
    durationMs = durationMs.takeIf { it > 0 },
    fromAutoplay = queueTier == QueueTier.AUTOPLAY,
)

/** A shared queue item as a catalogue song, in the section the phone puts it in. */
fun PartyTrack.toQueueSong(): Song = Song(
    videoId = videoId,
    title = title,
    artist = artist,
    thumbnailUrl = thumbnailUrl,
    // Not cosmetic: a cross-source match is made on it.
    durationText = durationMs?.let { ms ->
        val total = ms / 1000
        "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    },
    queueTier = if (fromAutoplay) QueueTier.AUTOPLAY else QueueTier.USER_QUEUE,
)
