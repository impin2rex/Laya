package com.music.laya.ios

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import platform.Foundation.NSString
import platform.Foundation.stringByRemovingPercentEncoding

/**
 * Swift's half of the home-screen widgets (WidgetHostImpl): writes what they draw to the App Group the WidgetKit
 * extension reads, fetching the covers first, and reloads them. Android's MediaWidgetSnapshot + LikedWidgetSongs.
 */
interface WidgetHost {
    fun publish(
        mediaId: String?,
        title: String,
        artist: String,
        artworkUrl: String?,
        isPlaying: Boolean,
        hasPrevious: Boolean,
        hasNext: Boolean,
        isLiked: Boolean,
        canLike: Boolean,
        shuffleEnabled: Boolean,
    )

    /** The first few Liked Music tracks (empty when signed out), as parallel lists. */
    fun publishLiked(ids: List<String>, titles: List<String>, artworkUrls: List<String>)
}

/** What the widgets show of the playing track: the fields of Android's MediaWidgetSnapshot. */
internal data class WidgetState(
    val mediaId: String?,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val isPlaying: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val isLiked: Boolean,
    val canLike: Boolean,
    val shuffleEnabled: Boolean,
)

/**
 * The widgets' way into the app. Their buttons (an App Intent run in this process) and links (laya://widget/…) arrive
 * here as commands — toggle, next, previous, like, shuffle, open, play:<videoId> — and wait until the player screen,
 * which runs them, exists: an intent can start the app before any UI does.
 */
object IosWidgets {
    var host: WidgetHost? = null

    internal fun publish(s: WidgetState) = host?.publish(
        s.mediaId, s.title, s.artist, s.artworkUrl, s.isPlaying, s.hasPrevious, s.hasNext, s.isLiked, s.canLike, s.shuffleEnabled,
    )

    private val queue = Channel<String>(Channel.UNLIMITED)
    internal val commands: Flow<String> = queue.receiveAsFlow()

    /** Whether something is collecting [commands]: the app's player is up and will act now. */
    var ready = false
        internal set

    /** Queues [command]; true when it runs now, false when it waits for the app's UI to start. */
    fun perform(command: String): Boolean {
        queue.trySend(command)
        return ready
    }

    /** laya://widget/open and laya://widget/play?v=<id>; false for any other link. */
    fun handleUrl(url: String): Boolean {
        if (!url.startsWith("laya://widget/")) return false
        val rest = url.removePrefix("laya://widget/")
        when {
            rest.startsWith("play") -> rest.substringAfter("v=", "").substringBefore('&').takeIf { it.isNotEmpty() }
                ?.let { perform("play:" + it.percentDecoded()) }
            else -> perform("open")
        }
        return true
    }

    @Suppress("CAST_NEVER_SUCCEEDS") // a Kotlin String is an NSString on Apple targets
    private fun String.percentDecoded(): String = (this as NSString).stringByRemovingPercentEncoding ?: this
}
