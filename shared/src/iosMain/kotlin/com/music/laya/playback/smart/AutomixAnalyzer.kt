package com.music.laya.playback.smart

import com.music.laya.data.settings.AppSettings
import com.music.laya.data.settings.TrackAnalysisState
import com.music.laya.platform.ConcurrentMap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/** Where a track's bytes can be read from, as the player handed them to the engine. */
class AnalysisSource(val url: String, val headers: Map<String, String>, val mime: String, val length: Long)

/**
 * Automix's analysis on iOS: the counterpart of Android's TrackAnalyzer, minus the cache-rendition and
 * vocal-model machinery. One worker at a time, off every audio and UI thread. Decode, the DSP features and the
 * Beat This! model run in Swift ([IosAutomix.native]); stitching them into a [TrackAnalysis] is the same as Android's
 * whole-track pass (features over the whole track, beat grid over its head and tail windows).
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)
class AutomixAnalyzer(private val scope: CoroutineScope, private val chunkBytes: Long) {
    private val store = AnalysisStore {
        (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String).toPath()
    }
    private val tracker = BeatTracker({ IosAutomix.native?.modelPath().orEmpty() }, { AppSettings.automixPerformanceMode.value.inferenceThreads })
    private val results = ConcurrentMap<String, TrackAnalysis>()
    private val running = ConcurrentMap<String, Boolean>()
    private val worker = Dispatchers.Default.limitedParallelism(1)

    fun analysisFor(trackId: String): TrackAnalysis =
        results[trackId] ?: store.load(trackId)?.also { results[trackId] = it } ?: TrackAnalysis(trackId = trackId)

    fun isRunning(trackId: String) = running[trackId] == true

    fun stateOf(trackId: String): TrackAnalysisState {
        val a = analysisFor(trackId)
        return when {
            a.isUsable -> TrackAnalysisState.ANALYSED
            isRunning(trackId) -> TrackAnalysisState.ANALYSING
            a.status == TrackAnalysis.STATUS_READY -> TrackAnalysisState.FAILED
            else -> TrackAnalysisState.WAITING
        }
    }

    /** Cheap to call every tick: a track measured, failed or already in flight is a no-op. */
    fun request(trackId: String, source: AnalysisSource, durationSeconds: Double) {
        if (results[trackId] != null || running[trackId] == true || IosAutomix.native == null) return
        if (store.load(trackId) != null) return
        running[trackId] = true
        scope.launch(worker) {
            try {
                results[trackId] = analyze(trackId, source, durationSeconds)
            } catch (e: Throwable) {
                AnalysisLog.warn("Analysis of $trackId failed", e)
                results[trackId] = failed(trackId, durationSeconds)
            } finally {
                running.remove(trackId)
            }
        }
    }

    private fun failed(trackId: String, duration: Double) =
        TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = trackId, duration = duration)

    private fun analyze(trackId: String, s: AnalysisSource, knownDuration: Double): TrackAnalysis {
        val native = IosAutomix.native ?: return failed(trackId, knownDuration)
        val pcm = native.decode(s.url, s.headers, s.mime, s.length, chunkBytes) ?: return failed(trackId, knownDuration)
        try {
            val rate = AutomixNative.DECODE_RATE
            val seconds = pcm.frames / rate
            // A decode that stopped early reads as a track that goes quiet, and the mix-out would land where the bytes ran out.
            if (knownDuration > 0 && seconds < knownDuration * MIN_DECODED_FRACTION) return failed(trackId, knownDuration)
            val features = pcm.features(seconds)?.let { runCatching { TrackFeatureJson.parse(it) }.getOrNull() }
                ?: return failed(trackId, seconds)

            val window = BeatTracker.WINDOW_SECONDS
            val head = grid(pcm, 0.0, minOf(window, seconds))
            val tailStart = maxOf(0.0, seconds - window)
            val tail = if (tailStart > window / 2) grid(pcm, tailStart, seconds) else null
            val leading = tail ?: head
            println("Automix: analysed $trackId bpm=${leading?.bpm ?: features.bpm} conf=${leading?.beatConfidence ?: features.beatConfidence} contentEnd=${features.contentEndTime}")
            return TrackAnalysis(
                status = TrackAnalysis.STATUS_READY,
                trackId = trackId,
                duration = seconds,
                contentEndTime = features.contentEndTime.takeIf { it > 0 } ?: seconds,
                bpm = leading?.bpm ?: features.bpm,
                beatInterval = leading?.beatInterval ?: features.beatInterval,
                beatConfidence = leading?.beatConfidence ?: features.beatConfidence,
                downbeats = (head?.downbeats.orEmpty() + tail?.downbeats.orEmpty()).ifEmpty { features.downbeats }.sorted(),
                firstBeat = head?.firstBeat ?: features.firstBeat,
                phraseBoundaries = features.phraseBoundaries,
                key = features.key,
                keyConfidence = features.keyConfidence,
                audibleStartTime = features.audibleStartTime,
                pickupTime = features.pickupTime,
                introEndTime = features.introEndTime,
                outroStartTime = features.outroStartTime,
                mixInTime = features.mixInTime,
                mixOutTime = features.mixOutTime,
                mixInCandidates = features.mixInCandidates,
                mixOutCandidates = features.mixOutCandidates,
                energyCurve = features.energyCurve,
                lowEnergyCurve = features.lowEnergyCurve,
                // ponytail: the DSP vocal heuristic only; the open-unmix vocal model is not ported, so vocal-clash avoidance is coarser than Android's.
                vocalActivityMask = features.vocalActivityMask,
                vocalProbability = features.vocalProbability,
            ).also { store.save(trackId, it) }
        } finally {
            pcm.close()
        }
    }

    private fun grid(pcm: AutomixPcm, fromSeconds: Double, toSeconds: Double): BeatTracker.Grid? {
        val rate = AutomixNative.DECODE_RATE
        val from = (fromSeconds * rate).toInt()
        val count = ((toSeconds * rate).toInt() - from).coerceAtLeast(0)
        if (count < rate) return null
        val samples = FloatArray(count)
        samples.usePinned { pcm.copy(it.addressOf(0), from, count) }
        return tracker.track(samples, offsetSeconds = fromSeconds)
    }

    private companion object {
        const val MIN_DECODED_FRACTION = 0.95
    }
}
