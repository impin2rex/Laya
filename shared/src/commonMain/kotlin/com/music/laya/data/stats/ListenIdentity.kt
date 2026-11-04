package com.music.laya.data.stats

import com.music.laya.data.YtMusicRepository
import com.music.laya.data.model.Song
import com.music.laya.platform.ConcurrentMap
import com.music.laya.platform.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Which track a listen is of: the audio release, whenever there is one.
 *
 * A track can play as its music video or as its audio-only release, and the audio/video switch
 * puts the other one in the player mid-listen. History — the account's on YouTube, the device's
 * own stats — and what is announced elsewhere (scrobbles, Discord) must count and name the track
 * being listened to, not whichever cut of it is coming out. So a listen is of the audio release
 * when the track has one: a video played on its own is credited to its audio release from the
 * start, switching to the video and back is the same listen, and only a video with no audio
 * release is a listen of the video.
 */
object ListenIdentity {

    /** The listen each version switched between belongs to, by its media id. */
    private val listenOf = ConcurrentMap<String, Song>()

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** [from] became [to] mid-listen. Returns the track the listen is of from here on. */
    fun onVersionSwapped(from: Song, to: Song): Song {
        val listen = if (to.isVideo) of(from) else to
        // A handful of pairs is all a session switches between; nothing needs keeping longer.
        if (listenOf.size > MAX_PAIRS) listenOf.clear()
        listenOf[from.videoId] = listen
        listenOf[to.videoId] = listen
        return listen
    }

    /** A switch to another version that didn't go through: [from] is a listen of [listen] again. */
    fun revert(from: Song, listen: Song) {
        listenOf[from.videoId] = listen
    }

    /**
     * The track [song] is a listen of, when that is settled: null for a video whose audio release
     * hasn't been looked for yet — [resolve] answers it.
     */
    fun known(song: Song): Song? {
        listenOf[song.videoId]?.let { return it }
        if (!song.isVideo) return song
        // The lookup's own answer: the audio release, or the video itself when it has none.
        return YtMusicRepository.cachedAudioVersion(song.videoId)
    }

    /** The track [song] is a listen of, as far as is known now — [song] itself until settled. */
    fun of(song: Song): Song = known(song) ?: song

    /** As [of], for a bare media id. */
    fun idOf(videoId: String): String = listenOf[videoId]?.videoId
        ?: YtMusicRepository.cachedAudioVersion(videoId)?.videoId
        ?: videoId

    /** The track [song] is a listen of, looking for a video's audio release if it must. */
    suspend fun resolve(song: Song): Song {
        known(song)?.let { return it }
        try {
            YtMusicRepository.resolveAudio(song)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Unanswered, not answered "none": credited to the video this time.
            return song
        }
        return of(song)
    }

    /** Starts [resolve] for a track about to play, so it is settled by the time it does. */
    fun prefetch(song: Song) {
        if (known(song) == null) scope.launch { resolve(song) }
    }

    private const val MAX_PAIRS = 64
}
