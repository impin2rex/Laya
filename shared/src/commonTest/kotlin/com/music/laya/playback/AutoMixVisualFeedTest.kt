package com.music.laya.playback

import com.music.laya.data.model.QueueEntryIdentity
import com.music.laya.data.model.Song
import com.music.laya.data.model.entryIdentityAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoMixVisualFeedTest {
    private val outgoing = QueueEntryIdentity("a", null, 0)
    private val incoming = QueueEntryIdentity("b", null, 0)
    private val visual = AutoMixVisualState(
        1L,
        AutoMixVisualTrack(outgoing, 0, null),
        AutoMixVisualTrack(incoming, 1, null),
    )

    @Test
    fun revealProgressMovesBetweenControllerSamplesButIsCappedAhead() {
        val sampled = visual.copy(
            phase = AutoMixVisualPhase.MIXING,
            audioSeconds = 1.0,
            artworkWindow = AutoMixArtworkWindow(0.0, 2.0),
            artworkProgress = 0.5f,
            sampledAtNanos = 1_000_000_000L,
        )
        assertEquals(0.5f, sampled.artworkProgressAt(1_000_000_000L))
        // 10ms after the sample: 0.01s of a 2s window further on.
        assertEquals(0.505f, sampled.artworkProgressAt(1_010_000_000L), 1e-4f)
        // A stalled controller cannot carry it more than the cap ahead.
        val capped = 0.5f + (AutoMixArtworkTuning.MAX_PROGRESS_EXTRAPOLATION_SECONDS / 2.0).toFloat()
        assertEquals(capped, sampled.artworkProgressAt(5_000_000_000L), 1e-4f)
        // Never behind the sample, and untouched without a window or once cancelled.
        assertEquals(0.5f, sampled.artworkProgressAt(500_000_000L))
        assertEquals(0.5f, sampled.copy(phase = AutoMixVisualPhase.CANCELLED).artworkProgressAt(1_010_000_000L))
    }

    @Test
    fun skippingOntoTheMixShortensThePrepareLeadInsteadOfDroppingTheDissolve() {
        val t = AutoMixArtworkTuning
        val settled = requireNotNull(t.windowAfter(10.0, 20.0, preparedForSeconds = 5.0))
        assertEquals(10.0 + t.START_LAG_AFTER_SWAP, settled.startSeconds, 1e-9)
        assertEquals(t.WINDOW_SECONDS, settled.endSeconds - settled.startSeconds, 1e-9)
        val lead = requireNotNull(t.windowAfter(10.0, 20.0, preparedForSeconds = 0.0))
        assertEquals(10.0 + t.MIN_PREPARE_LEAD_SECONDS, lead.startSeconds, 1e-9)
        val skipped = requireNotNull(t.windowAfter(10.0, 11.5, preparedForSeconds = 0.0))
        assertEquals(11.5, skipped.endSeconds, 1e-9)
        assertEquals(t.MIN_WINDOW_SECONDS, skipped.endSeconds - skipped.startSeconds, 1e-9)
        assertTrue(skipped.startSeconds >= 10.0)
        assertNull(t.windowAfter(10.0, 10.0 + t.MIN_WINDOW_SECONDS - 0.01, preparedForSeconds = 0.0))
    }

    @Test
    fun feedRunsArmMixSwapAdvanceComplete() {
        val feed = AutoMixVisualFeed
        val id = feed.arm(visual.outgoing, visual.incoming)
        assertEquals(AutoMixVisualPhase.ARMED, feed.state.value?.phase)
        // Completion before a swap is not a dissolve: it cancels.
        feed.start(id, 0.0)
        assertTrue(feed.state.value!!.mixActive)
        feed.swap(id, 4.0, 8.0)
        val window = assertNotNull(feed.state.value?.artworkWindow)
        assertTrue(window.startSeconds >= 4.0 && window.endSeconds <= 8.0)
        feed.advance(id, (window.startSeconds + window.endSeconds) / 2, 8.0)
        assertEquals(0.5f, feed.state.value!!.artworkProgress, 1e-3f)
        // Progress never steps back on a late sample.
        feed.advance(id, window.startSeconds, 8.0)
        assertEquals(0.5f, feed.state.value!!.artworkProgress, 1e-3f)
        feed.advance(id, 8.0, 8.0)
        feed.complete(id)
        assertEquals(AutoMixVisualPhase.COMPLETED, feed.state.value?.phase)
        // A terminal generation cannot be cancelled or restarted.
        feed.cancel(id)
        assertEquals(AutoMixVisualPhase.COMPLETED, feed.state.value?.phase)
    }

    @Test
    fun completingBeforeTheSwapCancelsAndStaleIdsAreIgnored() {
        val feed = AutoMixVisualFeed
        val first = feed.arm(visual.outgoing, visual.incoming)
        val second = feed.arm(visual.incoming, visual.outgoing)
        feed.start(first, 0.0)
        assertEquals(AutoMixVisualPhase.ARMED, feed.state.value?.phase)
        feed.start(second, 0.0)
        feed.complete(second)
        assertEquals(AutoMixVisualPhase.CANCELLED, feed.state.value?.phase)
        assertNull(feed.state.value?.artworkWindow)
    }

    @Test
    fun artworkPreferencesAreIndependentUnderTheAutoMixParent() {
        val defaults = AutoMixArtworkPreferences()
        // Both artwork transitions are always on now, under Automix.
        assertTrue(defaults.gate(automix = true).allows(1L))
        assertTrue(defaults.gate(automix = true, mini = true).allows(1L))
        for (autoMix in listOf(false, true)) for (art in listOf(false, true)) for (mini in listOf(false, true)) {
            val preferences = defaults.copy(artTransition = art, miniPlayerTransition = mini)
            assertEquals(autoMix && art, preferences.gate(automix = autoMix).allows(1L))
            assertEquals(autoMix && mini, preferences.gate(automix = autoMix, mini = true).allows(1L))
        }
    }

    @Test
    fun turningATransitionOffFencesTheMixInFlightOnly() {
        val on = AutoMixArtworkPreferences(artTransition = true, miniPlayerTransition = true)
        val off = on.withArtTransition(false, liveGeneration = 7L)
        val back = off.withArtTransition(true, liveGeneration = 7L)
        assertFalse(back.allows(automix = true, generation = 7L))
        assertTrue(back.allows(automix = true, generation = 8L))
        // The mini player's own switch is untouched.
        assertTrue(back.allows(automix = true, generation = 7L, mini = true))
    }

    @Test
    fun repeatedEntriesAreTellApartByOccurrence() {
        fun song(id: String) = Song(videoId = id, title = id, artist = "", thumbnailUrl = null)
        val queue = listOf(song("a"), song("b"), song("a"))
        assertEquals(QueueEntryIdentity("a", null, 1), queue.entryIdentityAt(2))
        assertEquals(QueueEntryIdentity("b", null, 0), queue.entryIdentityAt(1))
        assertNull(queue.entryIdentityAt(3))
    }
}
