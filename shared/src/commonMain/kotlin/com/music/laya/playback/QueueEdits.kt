package com.music.laya.playback

import com.music.laya.data.model.QueueEntryIdentity
import com.music.laya.data.model.QueueTier
import com.music.laya.data.model.Song
import com.music.laya.playback.QueueTimeline.asQueueEntry

/**
 * The queue screen's edits — removing rows (one or many) with an Undo, and a
 * row swiped into Next in queue — as plain list rules every player applies:
 * Android to its ExoPlayer, iOS and the desktop to their own lists.
 *
 * Rows are addressed by [QueueEntryIdentity] rather than by index, so a row
 * picked a moment ago still means that row once the track has moved on.
 */
object QueueEdits {

    /** (media id, queue-entry id) of a [Song], the key [QueueEntryIdentity] is made of. */
    val songKey: (Song) -> Pair<String, String?> = { it.videoId to it.queueEntryId }

    /** Song lists keep their rows' instances, or at least their entry ids, across edits. */
    val sameSong: (Song, Song) -> Boolean = { a, b ->
        a === b || (a.queueEntryId != null && a.queueEntryId == b.queueEntryId && a.videoId == b.videoId)
    }

    /** The identities of the rows at [indices] in [timeline]. */
    fun <T> identitiesAt(
        timeline: List<T>,
        indices: List<Int>,
        key: (T) -> Pair<String, String?>,
    ): List<QueueEntryIdentity> {
        val counts = HashMap<Pair<String, String?>, Int>()
        val occurrence = IntArray(timeline.size)
        timeline.forEachIndexed { index, item ->
            val k = key(item)
            occurrence[index] = counts[k] ?: 0
            counts[k] = occurrence[index] + 1
        }
        return indices.filter { it in timeline.indices }.map { index ->
            val (mediaId, entryId) = key(timeline[index])
            QueueEntryIdentity(mediaId, entryId, occurrence[index])
        }
    }

    /** Where [identities] sit in [timeline] now, ascending; rows no longer there are skipped. */
    fun <T> resolve(
        timeline: List<T>,
        identities: List<QueueEntryIdentity>,
        key: (T) -> Pair<String, String?>,
    ): List<Int> {
        val candidates = HashMap<Pair<String, String?>, MutableList<Int>>()
        timeline.forEachIndexed { index, item -> candidates.getOrPut(key(item)) { mutableListOf() } += index }
        return identities
            .mapNotNull { candidates[it.mediaId to it.queueEntryId]?.getOrNull(it.occurrence) }
            .distinct()
            .sorted()
    }

    /** Contiguous runs of the ascending [indices], last first, so removing one never shifts the next. */
    fun removalRanges(indices: List<Int>): List<IntRange> {
        val runs = ArrayList<IntRange>()
        var end = indices.size
        while (end > 0) {
            var start = end - 1
            while (start > 0 && indices[start - 1] == indices[start] - 1) start--
            runs += indices[start]..indices[end - 1]
            end = start
        }
        return runs
    }

    /** [items] without the rows at [indices], and where [currentIndex]'s row went. */
    fun <T> remove(items: List<T>, currentIndex: Int, indices: List<Int>): Pair<List<T>, Int> {
        val drop = indices.toHashSet()
        return items.filterIndexed { index, _ -> index !in drop } to
            currentIndex - indices.count { it < currentIndex }
    }

    /**
     * The insertions that put the rows at [removed] (ascending indices into
     * [before], the list as it was) back into [live], the list as it is now.
     *
     * Each run of removed rows goes back after the nearest row that preceded
     * it in [before] and is still there — or ahead of the nearest that followed
     * it, when none before it is left (history trimmed at the head, say) — so
     * rows appended or trimmed since the removal never misplace it. Apply them
     * in order: each index already counts the insertions before it. Null when
     * no row of [before] is left in [live]: the queue is no longer the one the
     * rows came out of.
     */
    fun <T> restoreInsertions(
        before: List<T>,
        removed: List<Int>,
        live: List<T>,
        same: (T, T) -> Boolean,
    ): List<Pair<Int, List<T>>>? {
        val working = live.toMutableList()
        val insertions = ArrayList<Pair<Int, List<T>>>()
        fun at(index: Int): Int? = working.indexOfFirst { same(it, before[index]) }.takeIf { it >= 0 }
        for (run in removalRanges(removed).asReversed()) {
            val position = (run.first - 1 downTo 0).firstNotNullOfOrNull(::at)?.plus(1)
                ?: (run.last + 1 until before.size).firstNotNullOfOrNull(::at)
                ?: if (working.isEmpty()) 0 else return null
            val rows = before.subList(run.first, run.last + 1).toList()
            working.addAll(position, rows)
            insertions += position to rows
        }
        return insertions
    }

    /** [live] with the [removed] rows of [before] put back, and where [liveIndex]'s row went. */
    fun <T> restore(
        before: List<T>,
        removed: List<Int>,
        live: List<T>,
        liveIndex: Int,
        same: (T, T) -> Boolean,
    ): Pair<List<T>, Int>? {
        val insertions = restoreInsertions(before, removed, live, same) ?: return null
        val restored = live.toMutableList()
        var index = liveIndex
        insertions.forEach { (position, rows) ->
            restored.addAll(position, rows)
            if (position <= index) index += rows.size
        }
        return restored to index
    }

    /**
     * Where a swipe moves the row at [from] to: the head of Next in queue when
     * [playNext], else its tail. Null for the playing row, or for one of the
     * listener's own rows already standing there.
     */
    fun userQueueTarget(timeline: List<Song>, currentIndex: Int, from: Int, playNext: Boolean): Int? {
        if (from !in timeline.indices || from == currentIndex) return null
        val insertion = QueueTimeline.findUserQueueInsertionIndex(timeline, currentIndex, playNext)
        val target = if (from < insertion) insertion - 1 else insertion
        return target.takeUnless { it == from && timeline[from].queueTier == QueueTier.USER_QUEUE }
    }

    /** [timeline] with the row at [from] made the listener's and moved per [userQueueTarget]. */
    fun moveToUserQueue(
        timeline: List<Song>,
        currentIndex: Int,
        from: Int,
        playNext: Boolean,
    ): Pair<List<Song>, Int>? {
        val target = userQueueTarget(timeline, currentIndex, from, playNext) ?: return null
        val moved = timeline.toMutableList()
        moved.add(target, moved.removeAt(from).asQueueEntry(QueueTier.USER_QUEUE))
        val index = when {
            from < currentIndex && target >= currentIndex -> currentIndex - 1
            else -> currentIndex
        }
        return moved to index
    }
}
