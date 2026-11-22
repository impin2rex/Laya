package com.music.laya.playback

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.music.laya.data.model.QueueEntryIdentity
import com.music.laya.data.model.QueueTier
import kotlinx.coroutines.guava.await

/** Removes queue rows by [QueueEntryIdentity]; answers with an [EXTRA_QUEUE_UNDO_TOKEN]. */
const val ACTION_QUEUE_REMOVE_ENTRIES = "com.music.laya.action.QUEUE_REMOVE_ENTRIES"

/** Puts back the removal [EXTRA_QUEUE_UNDO_TOKEN] names, if nothing has replaced it since. */
const val ACTION_QUEUE_UNDO_REMOVAL = "com.music.laya.action.QUEUE_UNDO_REMOVAL"

/** Moves one row into Next in queue — its head or tail, per the swipe setting. */
const val ACTION_QUEUE_MOVE_TO_USER = "com.music.laya.action.QUEUE_MOVE_TO_USER"

const val EXTRA_QUEUE_UNDO_TOKEN = "laya.queueUndo.token"
private const val EXTRA_QUEUE_MEDIA_IDS = "laya.queueEntries.mediaIds"
private const val EXTRA_QUEUE_ENTRY_IDS = "laya.queueEntries.entryIds"
private const val EXTRA_QUEUE_OCCURRENCES = "laya.queueEntries.occurrences"

private val MediaItem.entryKey: Pair<String, String?> get() = mediaId to queueEntryId

/** A row survives a player hand-off (a crossfade's new deck) by its entry id, if not as the same item. */
private fun sameEntry(a: MediaItem, b: MediaItem): Boolean =
    a === b || (a.queueEntryId != null && a.queueEntryId == b.queueEntryId && a.mediaId == b.mediaId)

private val Player.items: List<MediaItem> get() = (0 until mediaItemCount).map(::getMediaItemAt)

/**
 * The service's half of the queue screen's edits, applied to the session's
 * player — the same forwarding player a controller's own removeMediaItem
 * reaches, so a party's lock and its publish apply exactly as they did.
 *
 * Holds the one removal Undo can take back: a newer removal replaces it, and
 * a queue that has moved on too far to put the rows back refuses it.
 */
internal class QueueScreenEdits {
    private class Removal(val token: Long, val before: List<MediaItem>, val removed: List<Int>)

    private var last: Removal? = null
    private var tokens = 0L

    fun remove(player: Player, args: Bundle): SessionResult {
        val identities = args.queueEntryIdentities() ?: return failed()
        val before = player.items
        val indices = QueueEdits.resolve(before, identities) { it.entryKey }
            .filter { it != player.currentMediaItemIndex }
        if (indices.isEmpty()) return failed()
        QueueEdits.removalRanges(indices).forEach { player.removeMediaItems(it.first, it.last + 1) }
        // Refused (a party's host holds the controls): nothing to undo.
        if (player.mediaItemCount == before.size) return failed()
        val token = ++tokens
        last = Removal(token, before, indices)
        return SessionResult(SessionResult.RESULT_SUCCESS, bundleOf(EXTRA_QUEUE_UNDO_TOKEN to token))
    }

    fun undo(player: Player, args: Bundle): SessionResult {
        val removal = last?.takeIf { it.token == args.getLong(EXTRA_QUEUE_UNDO_TOKEN, -1L) } ?: return failed()
        last = null
        val insertions = QueueEdits.restoreInsertions(removal.before, removal.removed, player.items, ::sameEntry)
            ?: return failed()
        insertions.forEach { (at, items) -> player.addMediaItems(at, items) }
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    fun moveToUser(player: Player, args: Bundle, playNext: Boolean): SessionResult {
        val identity = args.queueEntryIdentities()?.singleOrNull() ?: return failed()
        val items = player.items
        val from = QueueEdits.resolve(items, listOf(identity)) { it.entryKey }.singleOrNull() ?: return failed()
        val target = QueueEdits.userQueueTarget(items.map { it.toSong() }, player.currentMediaItemIndex, from, playNext)
            ?: return failed()
        // The session's own player rather than a controller round trip, so the
        // row keeps its URI, canonical metadata and entry id; only its tier changes.
        player.replaceMediaItem(from, QueueShuffle.withQueueMetadata(items[from], newTier = QueueTier.USER_QUEUE))
        if (from != target) player.moveMediaItem(from, target)
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    private fun failed() = SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
}

private fun List<QueueEntryIdentity>.toBundle(): Bundle = bundleOf(
    EXTRA_QUEUE_MEDIA_IDS to ArrayList(map { it.mediaId }),
    // ponytail: "" stands for no entry id; real ids are UUIDs, never empty.
    EXTRA_QUEUE_ENTRY_IDS to ArrayList(map { it.queueEntryId.orEmpty() }),
    EXTRA_QUEUE_OCCURRENCES to map { it.occurrence }.toIntArray(),
)

private fun Bundle.queueEntryIdentities(): List<QueueEntryIdentity>? {
    val mediaIds = getStringArrayList(EXTRA_QUEUE_MEDIA_IDS) ?: return null
    val entryIds = getStringArrayList(EXTRA_QUEUE_ENTRY_IDS) ?: return null
    val occurrences = getIntArray(EXTRA_QUEUE_OCCURRENCES) ?: return null
    if (mediaIds.size != entryIds.size || mediaIds.size != occurrences.size) return null
    return mediaIds.indices.map { QueueEntryIdentity(mediaIds[it], entryIds[it].ifEmpty { null }, occurrences[it]) }
}

/** Removes [entries] from the queue; the token undoes it with [undoQueueRemoval]. Null when nothing went. */
suspend fun MediaController.removeQueueEntries(entries: List<QueueEntryIdentity>): Long? {
    val result = sendCustomCommand(SessionCommand(ACTION_QUEUE_REMOVE_ENTRIES, Bundle.EMPTY), entries.toBundle()).await()
    return result.extras.getLong(EXTRA_QUEUE_UNDO_TOKEN, -1L)
        .takeIf { result.resultCode == SessionResult.RESULT_SUCCESS && it > 0 }
}

suspend fun MediaController.undoQueueRemoval(token: Long): Boolean =
    sendCustomCommand(
        SessionCommand(ACTION_QUEUE_UNDO_REMOVAL, Bundle.EMPTY),
        bundleOf(EXTRA_QUEUE_UNDO_TOKEN to token),
    ).await().resultCode == SessionResult.RESULT_SUCCESS

suspend fun MediaController.moveQueueEntryToUser(entry: QueueEntryIdentity): Boolean =
    sendCustomCommand(
        SessionCommand(ACTION_QUEUE_MOVE_TO_USER, Bundle.EMPTY),
        listOf(entry).toBundle(),
    ).await().resultCode == SessionResult.RESULT_SUCCESS
