package com.music.laya.playback

import com.music.laya.data.model.QueueEntryIdentity
import com.music.laya.data.model.QueueTier
import com.music.laya.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueEditsTest {

    private fun song(id: String, tier: QueueTier = QueueTier.CONTEXT, entry: String? = "e-$id") =
        Song(videoId = id, title = id, artist = "", thumbnailUrl = null, queueTier = tier, queueEntryId = entry)

    private fun ids(list: List<Song>) = list.map { it.videoId }

    @Test
    fun bulkRemoveThenUndoRestoresOriginalOrderAndCurrent() {
        val before = listOf("a", "b", "c", "d", "e", "f", "g").map { song(it) }
        val current = 3 // "d"
        val removed = listOf(1, 2, 5) // b, c, f
        val (after, afterIndex) = QueueEdits.remove(before, current, removed)
        assertEquals(listOf("a", "d", "e", "g"), ids(after))
        assertEquals(1, afterIndex)

        val (restored, restoredIndex) = QueueEdits.restore(before, removed, after, afterIndex, QueueEdits.sameSong)!!
        assertEquals(ids(before), ids(restored))
        assertEquals(current, restoredIndex)
    }

    @Test
    fun undoSurvivesHistoryTrimAndAppendedAutoplay() {
        val before = listOf("a", "b", "c", "d", "e").map { song(it) }
        val removed = listOf(0, 3) // a (history head), d
        val (after, _) = QueueEdits.remove(before, 2, removed)
        // Meanwhile: the history's head "b" was trimmed and AutoPlay appended "z".
        val live = after.drop(1) + song("z", QueueTier.AUTOPLAY)
        val (restored, index) = QueueEdits.restore(before, removed, live, 0, QueueEdits.sameSong)!!
        // "a" goes back ahead of its nearest survivor "c"; "d" after "c".
        assertEquals(listOf("a", "c", "d", "e", "z"), ids(restored))
        assertEquals(1, index)
    }

    @Test
    fun undoRefusesAReplacedQueue() {
        val before = listOf("a", "b", "c").map { song(it) }
        assertNull(QueueEdits.restoreInsertions(before, listOf(1), listOf(song("x"), song("y")), QueueEdits.sameSong))
    }

    @Test
    fun insertionsApplyInOrderForPlayers() {
        val before = (0..9).map { song("s$it") }
        val removed = listOf(0, 1, 4, 8, 9)
        val (after, _) = QueueEdits.remove(before, 5, removed)
        val working = after.toMutableList()
        QueueEdits.restoreInsertions(before, removed, after, QueueEdits.sameSong)!!
            .forEach { (at, rows) -> working.addAll(at, rows) }
        assertEquals(ids(before), ids(working))
    }

    @Test
    fun removalRangesAreDescendingContiguousRuns() {
        assertEquals(listOf(8..9, 4..4, 0..1), QueueEdits.removalRanges(listOf(0, 1, 4, 8, 9)))
    }

    @Test
    fun identitiesResolveDuplicatesByOccurrence() {
        val timeline = listOf(song("a", entry = null), song("b"), song("a", entry = null))
        val identities = QueueEdits.identitiesAt(timeline, listOf(2), QueueEdits.songKey)
        assertEquals(listOf(QueueEntryIdentity("a", null, 1)), identities)
        assertEquals(listOf(2), QueueEdits.resolve(timeline, identities, QueueEdits.songKey))
        // Gone rows are skipped rather than mapped elsewhere.
        assertEquals(emptyList(), QueueEdits.resolve(timeline.take(2), identities, QueueEdits.songKey))
    }

    @Test
    fun swipeMovesIntoNextInQueue() {
        val timeline = listOf(
            song("h"), song("now"), song("u1", QueueTier.USER_QUEUE), song("c1"), song("c2"),
        )
        val (next, nextIndex) = QueueEdits.moveToUserQueue(timeline, 1, from = 4, playNext = true)!!
        assertEquals(listOf("h", "now", "c2", "u1", "c1"), ids(next))
        assertEquals(QueueTier.USER_QUEUE, next[2].queueTier)
        assertEquals(1, nextIndex)

        val (tail, _) = QueueEdits.moveToUserQueue(timeline, 1, from = 4, playNext = false)!!
        assertEquals(listOf("h", "now", "u1", "c2", "c1"), ids(tail))

        // A history row leaves history; the playing row keeps playing.
        val (fromHistory, historyIndex) = QueueEdits.moveToUserQueue(timeline, 1, from = 0, playNext = true)!!
        assertEquals(listOf("now", "h", "u1", "c1", "c2"), ids(fromHistory))
        assertEquals(0, historyIndex)

        // Already the tail of Next in queue: nothing to do.
        assertNull(QueueEdits.moveToUserQueue(timeline, 1, from = 2, playNext = false))
        assertNull(QueueEdits.moveToUserQueue(timeline, 1, from = 1, playNext = true))
    }
}
