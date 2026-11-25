package com.music.laya.ios

import com.music.laya.data.lyrics.LyricLine
import com.music.laya.data.lyrics.LyricsPicks
import com.music.laya.data.lyrics.LyricsRepository
import com.music.laya.data.lyrics.LyricsSource
import com.music.laya.data.model.Song
import com.music.laya.data.settings.AppSettings
import com.music.laya.ui.player.LyricsOnShow
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Swift's half of the lock-screen lyrics (LyricsLiveActivityImpl): ActivityKit has no Objective-C face, so the
 * app's Swift starts, updates and ends the Live Activity. [show] starts one only while Laya is in front (iOS
 * refuses a start from the background) and otherwise updates the one that is up.
 */
interface LyricsLiveActivityHost {
    /** False when there is no activity to update and none could be started (Laya in the background). */
    fun show(title: String, artist: String, line: String, next: String): Boolean
    fun end()
}

/**
 * The playing line on the lock screen and in the Dynamic Island, as a Live Activity beside the system's own
 * Now Playing card, which keeps the transport and the scrubber. iOS's take on Android's lock-screen player,
 * behind the same setting ([AppSettings.lockScreenPlayer]).
 *
 * Fed by the player's own progress ticks rather than the UI, and with a lookup of its own, as Android's
 * PlaybackService has: with the phone locked Compose draws no frames, so nothing keyed on the UI would move
 * on to the next song's lyrics until the app was opened again.
 *
 * Stays up while paused, since a Live Activity can only be started with the app on screen: one ended on a
 * pause could not come back until Laya was opened. Ends when there is nothing left to play or the setting
 * goes off. A song with no synced lyrics shows its title and artist.
 */
object IosLyricsActivity {
    var host: LyricsLiveActivityHost? = null

    /**
     * What the app's player settled on for a track once its lookup finished (null lines: none), set by
     * LayaIosApp. Shown in place of this object's own lookup, so the card and the app never disagree.
     */
    var shown: Pair<String, List<LyricLine>?>? = null

    private var lyricsFor: String? = null
    private var lines: List<LyricLine>? = null
    private var job: Job? = null
    private var sent: List<String>? = null
    private var picksSeen = -1

    /** Called with every progress tick of the player (four a second while it plays). */
    internal fun onTick(song: Song?, positionMs: Long, durationMs: Long) {
        val host = host ?: return
        if (song == null || !AppSettings.lockScreenPlayer.value) {
            if (sent != null) host.end()
            sent = null
            return
        }
        // A provider picked by hand in the player (now or earlier) counts here too: redo the lookup.
        val picks = LyricsPicks.version.value
        if ((song.videoId != lyricsFor || picks != picksSeen) && durationMs > 0L) {
            picksSeen = picks
            load(song, durationMs)
        }

        val chosen = shown?.takeIf { it.first == song.videoId }
        val synced = if (chosen != null) chosen.second?.takeIf { l -> l.any { it.timeMs > 0 } } else lines.takeIf { lyricsFor == song.videoId }
        // The app's translation or romanization, line for line with its own lyrics: under the sung line
        // in place of the next one, as the app's player draws it.
        val sub = LyricsOnShow.subLines.value?.takeIf { chosen != null && it.first == song.videoId }?.second
        val current = synced
            ?.let { currentAndNext(it, positionMs - AppSettings.lyricsOffsetMs.value, sub) }
        val (line, next) = current ?: (song.title to song.artist)
        val update = listOf(song.title, song.artist, line, next)
        if (update == sent) return
        // Only remembered once it is on screen, so a start refused in the background is tried again.
        if (host.show(song.title, song.artist, line, next)) sent = update
    }

    private fun load(song: Song, durationMs: Long) {
        lyricsFor = song.videoId
        lines = null
        job?.cancel()
        val sources = if (AppSettings.syncedLyrics.value) AppSettings.lyricsSources.value else emptySet()
        if (sources.isEmpty()) return
        job = MainScope().launch {
            suspend fun ask(from: Set<LyricsSource>, order: List<LyricsSource>, syllables: Boolean) = runCatching {
                LyricsRepository.lyrics(song.videoId, song.title, song.artist, durationMs, song.albumName, from, order, syllables)
            }.getOrNull()
            // The provider picked by hand in the player, so the lock screen shows the same lyrics.
            val found = LyricsPicks[song.videoId]?.takeIf { it in sources }?.let { ask(setOf(it), listOf(it), false) }
                ?: ask(sources, AppSettings.lyricsSourceOrder.value, AppSettings.prioritizeSyllableSync.value)
            // Only timed lyrics: plain text can't follow the song, so the card shows title and artist instead.
            if (lyricsFor == song.videoId) lines = found?.lines?.takeIf { l -> l.any { it.timeMs > 0 } }
        }
    }

    /** The sung line and the one after it; "♪" over an instrumental gap. Null before the first line. */
    internal fun currentAndNext(lines: List<LyricLine>, positionMs: Long, sub: List<LyricLine>? = null): Pair<String, String>? {
        val i = lines.indexOfLast { it.timeMs <= positionMs }
        val line = lines.getOrNull(i) ?: return null
        val gap = line.isGap || line.text.isBlank()
        val under = sub?.getOrNull(i)?.text?.takeIf { !gap && it.isNotBlank() && it != line.text }
        val next = under ?: lines.drop(i + 1).firstOrNull { !it.isGap && it.text.isNotBlank() }?.text.orEmpty()
        return (if (gap) "♪" else line.text) to next
    }
}
