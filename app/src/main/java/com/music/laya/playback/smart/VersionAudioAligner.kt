package com.music.laya.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.music.laya.data.TrackLog
import com.music.laya.data.model.Song
import com.music.laya.playback.AudioCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis

/**
 * Analyzes audio waveforms/energy envelopes to find the exact time alignment
 * offset between two versions of the same track (e.g. YouTube music video with intro
 * skit vs studio catalogue audio release).
 */
@UnstableApi
object VersionAudioAligner {

    private const val TAG = "VersionAudioAligner"

    // Cache computed offsets so subsequent switches are instantaneous:
    // (sourceVideoId, targetVideoId) -> offsetMs
    private val offsetCache = ConcurrentHashMap<Pair<String, String>, Long>()

    /** How far a switch to the other cut has got; published for stats for nerds. */
    enum class CutPhase { FETCHING, MEASURING, ALIGNED, FAILED }

    /**
     * What the switch to the other cut currently knows — the three things a
     * listener actually wants confirmation of: that the other version has been
     * *fetched*, that it has been *measured*, and what shift the transition is
     * going to apply.
     *
     * Kept after the swap lands rather than cleared with it, because the shift
     * is only readable once the cut it describes is the one playing. Scoped to
     * the pair: [NowPlayingScreen] ignores a record naming a track the player
     * is nowhere near, so yesterday's measurement never describes today's song.
     */
    data class AlignmentStatus(
        /** The cut playing when the switch started. */
        val sourceId: String,
        /** The cut being switched to. */
        val targetId: String,
        val phase: CutPhase,
        /** The shift that will be — or was — applied, once it is known. */
        val offsetMs: Long? = null,
        /** True when the figure came out of the cache instead of a fresh pass. */
        val fromCache: Boolean = false,
    )

    val status = MutableStateFlow<AlignmentStatus?>(null)

    /**
     * Names one run of a switch, so the phases that follow can be told apart
     * from the next switch's.
     *
     * A switch is cancelled the moment a second one outranks it, but only at
     * its next suspension point — the statement a cancelled pass is standing on
     * when the new one takes over still runs. Without this, the pass that had
     * already lost would publish [CutPhase.MEASURING] on top of the
     * replacement's [CutPhase.FETCHING], and the status would describe two
     * switches at once.
     */
    private val attempt = AtomicLong()

    /** Opens a switch's status and reports where it starts: fetching. */
    fun beginAttempt(sourceId: String, targetId: String): Long {
        val token = attempt.incrementAndGet()
        status.value = AlignmentStatus(sourceId, targetId, CutPhase.FETCHING)
        return token
    }

    /**
     * Moves the attempt named by [token] on to [phase], and does nothing if a
     * later attempt has taken over since — see [attempt].
     *
     * Non-suspending for the same reason: this is called from a coroutine that
     * can already be cancelled, and a suspending publish would have to survive
     * that to record a phase.
     */
    fun publishPhase(
        token: Long,
        phase: CutPhase,
        offsetMs: Long? = null,
        fromCache: Boolean = false,
    ) {
        if (attempt.get() != token) return
        val current = status.value ?: return
        status.value = current.copy(phase = phase, offsetMs = offsetMs, fromCache = fromCache)
    }

    /**
     * Returns cached offset in milliseconds if already computed, or null if unknown.
     */
    fun getCachedOffsetMs(sourceVideoId: String, targetVideoId: String): Long? {
        if (sourceVideoId == targetVideoId) return 0L
        return offsetCache[sourceVideoId to targetVideoId]
    }

    /**
     * Calculates or retrieves the time offset (in milliseconds) from [sourceSong] to [targetSong].
     * Target timestamp = (source timestamp + offsetMs).
     *
     * **null means it could not be measured** — no bytes on disk, a container
     * the decoder would not open, a correlation too weak to believe. Callers
     * must not fold that into `0`, which is a real answer meaning the two cuts
     * open on the same second; a silent zero is how a thirty-second difference
     * ends up as a swap that lands exactly where the other version left it,
     * with nothing on screen to say the measurement never happened.
     */
    suspend fun findOffsetMs(
        context: Context,
        sourceSong: Song,
        targetSong: Song,
        currentPosMs: Long = 0L,
    ): Long? = withContext(Dispatchers.IO) {
        if (sourceSong.videoId == targetSong.videoId) return@withContext 0L
        val cached = offsetCache[sourceSong.videoId to targetSong.videoId]
        if (cached != null) return@withContext cached

        val sourceDs = openDataSource(context, sourceSong)
        val targetDs = openDataSource(context, targetSong)

        if (sourceDs == null || targetDs == null) {
            safeClose(sourceDs)
            safeClose(targetDs)
            TrackLog.w(
                TAG,
                "Alignment for '${sourceSong.title}': no bytes on disk to measure against",
                sourceSong.videoId,
            )
            return@withContext null
        }

        try {
            // Both decodes at once rather than one after the other, and every
            // phase timed: the measurement runs against a deadline (see
            // [PlaybackService]'s ALIGNMENT_MEASURE_TIMEOUT_MS), and a result
            // arriving past it is discarded as if it had never happened — the
            // swap then goes out with no shift even though the offset was
            // computed. The field log caught exactly that race, with the
            // correlation succeeding 10 ms over a tighter budget.
            var decodes: Pair<Pair<AudioDecoder.Pcm, Double>?, Pair<AudioDecoder.Pcm, Double>?> =
                null to null
            val decodeMs = measureTimeMillis {
                decodes = coroutineScope {
                    val source = async(Dispatchers.IO) {
                        AudioDecoder.decodeRegion(sourceDs, 0.0, 45.0)
                    }
                    val target = async(Dispatchers.IO) {
                        AudioDecoder.decodeRegion(targetDs, 0.0, 45.0)
                    }
                    source.await() to target.await()
                }
            }
            val (sourceDecode, targetDecode) = decodes

            if (sourceDecode == null || targetDecode == null) {
                return@withContext null
            }

            val (pcmSource, startSourceSec) = sourceDecode
            val (pcmTarget, startTargetSec) = targetDecode

            if (pcmSource.samples.isEmpty() || pcmTarget.samples.isEmpty()) {
                return@withContext null
            }

            var result: VersionAlignment.Alignment? = null
            val measureMs = measureTimeMillis {
                result = VersionAlignment.align(
                    pcmSource.samples, pcmSource.sampleRate, startSourceSec,
                    pcmTarget.samples, pcmTarget.sampleRate, startTargetSec,
                )
            }
            val aligned = result!!
            val c = aligned.correlation
            TrackLog.d(
                TAG,
                "Alignment for '${sourceSong.title}' -> '${targetSong.title}': decode=${decodeMs}ms, measure=${measureMs}ms, " +
                    "lagFrames=${c.lagFrames}, conf=${c.confidence}, range=${c.floor}..${c.ceiling}",
                sourceSong.videoId,
            )
            val totalOffsetMs = aligned.offsetMs
            if (totalOffsetMs != null) {
                offsetCache[sourceSong.videoId to targetSong.videoId] = totalOffsetMs
                offsetCache[targetSong.videoId to sourceSong.videoId] = -totalOffsetMs
                return@withContext totalOffsetMs
            }
            TrackLog.w(
                TAG,
                "Alignment for '${sourceSong.title}' -> '${targetSong.title}': ${aligned.why} — no shift measured",
                sourceSong.videoId,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compute audio alignment for ${sourceSong.videoId} -> ${targetSong.videoId}", e)
        } finally {
            safeClose(sourceDs)
            safeClose(targetDs)
        }

        null
    }

    /**
     * Opens the copy of [song] worth measuring: the largest same-source
     * opening on disk, and only a substitute (`#alt`) when nothing else is
     * there at all.
     *
     * Ordered by bytes rather than by whichever URI answers first, because
     * neither URI names a particular copy — the live one resolves to whatever
     * rendition the player is streaming *now*, and the analysis copy under the
     * plain id can be a stale stub while a sibling key holds the whole
     * opening. A stub hands the decoder a second of audio, and a second of
     * audio can only correlate to garbage, which is how every switch measured
     * against one came out "failed". [AudioCache] asks the same question in
     * the same order when it declares the head ready, so what the wait calls
     * ready is what gets opened here.
     */
    private fun openDataSource(context: Context, song: Song): MediaDataSource? {
        song.localUri?.let { local ->
            val uri = Uri.parse(local)
            if (LocalAudioSource.isLocal(uri)) {
                return LocalAudioSource.open(context.contentResolver, uri)
            }
        }
        val watch = Uri.parse("laya://watch?v=${song.videoId}")
        val onDisk = AudioCache.renditionsOf(watch)
        val sameSource = onDisk
            .filter { !it.key.endsWith("#alt") }
            .maxByOrNull { it.cachedPrefix }
        val pick = if (sameSource != null &&
            sameSource.cachedPrefix >= AudioCache.MIN_ALIGNMENT_PREFIX_BYTES
        ) {
            sameSource
        } else {
            onDisk.maxByOrNull { it.cachedPrefix }
        } ?: return null
        return AudioCache.renditionDataSource(watch, pick)
    }

    private fun safeClose(ds: MediaDataSource?) {
        if (ds != null) {
            runCatching { ds.close() }
        }
    }
}
