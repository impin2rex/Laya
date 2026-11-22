package com.music.laya.playback

import com.music.laya.data.model.Song
import com.music.laya.data.model.entryIdentityAt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The AutoMix pair the artwork follows, published by whichever engine is mixing: Android's
 * CrossfadeController, iOS's IosTransition and the desktop audio engine. Each platform has one
 * writer thread (Android and iOS main, the desktop's audio thread); the UI only reads [state].
 */
object AutoMixVisualFeed {
    private val mutableState = MutableStateFlow<AutoMixVisualState?>(null)
    val state: StateFlow<AutoMixVisualState?> = mutableState.asStateFlow()
    private var generation = 0L
    private var armedAtNanos = 0L

    fun arm(outgoing: AutoMixVisualTrack, incoming: AutoMixVisualTrack): Long {
        val id = ++generation
        armedAtNanos = autoMixClockNanos()
        mutableState.value = AutoMixVisualState(id, outgoing, incoming)
        return id
    }

    /** [arm] for an engine that holds its queue as songs. Null when either index is outside it. */
    fun arm(queue: List<Song>, outIndex: Int, inIndex: Int): Long? {
        val from = queue.entryIdentityAt(outIndex) ?: return null
        val to = queue.entryIdentityAt(inIndex) ?: return null
        return arm(
            AutoMixVisualTrack(from, outIndex, queue[outIndex].thumbnailUrl),
            AutoMixVisualTrack(to, inIndex, queue[inIndex].thumbnailUrl),
        )
    }

    fun pending(id: Long?): AutoMixVisualState? = mutableState.value?.takeIf {
        it.generation == id && (it.mixActive || it.phase == AutoMixVisualPhase.ARMED)
    }

    fun start(id: Long?, audioSeconds: Double) {
        val previous = pending(id) ?: return
        if (previous.mixActive) return
        mutableState.value = previous.copy(
            phase = AutoMixVisualPhase.MIXING,
            overlapStartSeconds = 0.0,
            audioSeconds = audioSeconds,
            sampledAtNanos = autoMixClockNanos(),
        )
    }

    fun swap(id: Long?, audioSeconds: Double, endSeconds: Double) {
        val previous = pending(id)?.takeIf { it.mixActive } ?: return
        if (previous.swapSeconds != null) return
        val at = maxOf(previous.audioSeconds, audioSeconds)
        mutableState.value = previous.copy(
            audioSeconds = at,
            swapSeconds = at,
            artworkWindow = windowAfter(at, endSeconds),
            sampledAtNanos = autoMixClockNanos(),
        )
    }

    private fun windowAfter(swap: Double, end: Double): AutoMixArtworkWindow? =
        AutoMixArtworkTuning.windowAfter(swap, end, (autoMixClockNanos() - armedAtNanos) / 1e9)

    fun advance(id: Long?, audioSeconds: Double, endSeconds: Double) {
        val previous = pending(id)?.takeIf { it.mixActive } ?: return
        val at = maxOf(previous.audioSeconds, audioSeconds)
        val window = previous.artworkWindow?.let {
            when {
                previous.artworkProgress >= 1f || it.endSeconds <= endSeconds -> it
                // A late decoder duration can shorten the effective fade. Before the window,
                // consume the lag first; once started, keep its start or drop the dissolve.
                previous.artworkProgress == 0f && at <= it.startSeconds -> windowAfter(at, endSeconds)
                endSeconds - it.startSeconds >= AutoMixArtworkTuning.MIN_WINDOW_SECONDS ->
                    it.copy(endSeconds = endSeconds)
                else -> null
            }
        }
        val progress = if (window == null) 0f else maxOf(
            previous.artworkProgress,
            ((at - window.startSeconds) / (window.endSeconds - window.startSeconds)).coerceIn(0.0, 1.0).toFloat(),
        )
        mutableState.value = previous.copy(
            audioSeconds = at,
            artworkWindow = window,
            artworkProgress = progress,
            sampledAtNanos = autoMixClockNanos(),
        )
    }

    fun complete(id: Long?) {
        val previous = pending(id) ?: return
        if (!previous.mixActive || previous.swapSeconds == null) {
            cancel(id)
            return
        }
        mutableState.value = previous.copy(phase = AutoMixVisualPhase.COMPLETED)
    }

    fun cancel(id: Long? = mutableState.value?.generation) {
        val previous = pending(id) ?: return
        mutableState.value = previous.copy(
            phase = AutoMixVisualPhase.CANCELLED,
            artworkWindow = null,
            artworkProgress = 0f,
        )
    }
}
