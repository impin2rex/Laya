package com.music.laya.data.model

/** Identity of one occurrence in a live queue, independent of its timeline index. */
data class QueueEntryIdentity(
    val mediaId: String,
    val queueEntryId: String?,
    /** Zero-based occurrence among entries with the same media and queue-entry ids. */
    val occurrence: Int,
)

/** The identity of [index] in a queue of any item type, or null when it is out of range. */
inline fun <T> queueEntryIdentityAt(
    items: List<T>,
    index: Int,
    mediaId: (T) -> String,
    entryId: (T) -> String?,
): QueueEntryIdentity? {
    val item = items.getOrNull(index) ?: return null
    val id = mediaId(item)
    val entry = entryId(item)
    var ordinal = 0
    for (i in 0 until index) if (mediaId(items[i]) == id && entryId(items[i]) == entry) ordinal++
    return QueueEntryIdentity(id, entry, ordinal)
}

fun List<Song>.entryIdentityAt(index: Int): QueueEntryIdentity? =
    queueEntryIdentityAt(this, index, Song::videoId, Song::queueEntryId)
