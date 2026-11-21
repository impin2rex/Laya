package com.music.laya.ios

import com.music.laya.data.model.Song
import com.music.laya.data.scrobbling.LastFM
import com.music.laya.data.scrobbling.ListenBrainzManager
import com.music.laya.data.scrobbling.ScrobbleManager
import com.music.laya.data.settings.AppSettings
import com.music.laya.platform.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Last.fm and ListenBrainz on iOS: PlaybackService's scrobbling, driven by the engine's progress
 * ticks instead of Media3 callbacks. [IosPlayer] feeds every tick through [onTick], and the
 * track-change / pause / resume events Android gets from the player are recovered by diffing.
 */
internal object IosScrobbler {
    private var scope: CoroutineScope? = null
    private var manager: ScrobbleManager? = null

    private var song: Song? = null
    private var playing = false
    private var everPlayed = false
    private var startMs = 0L
    private var positionMs = 0L
    private var durationMs = 0L

    fun attach(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        scope.launch { observeSettings(scope) }
    }

    fun onTick(song: Song?, isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        val prev = this.song
        // Repeat-one and a restart replay the same id from the top — a new listen, as Android's
        // onMediaItemTransition treats it. ponytail: a 5 s backwards jump stands in for a transition
        // event; a long manual seek back re-arms the scrobble timer, which Last.fm forgives.
        val changed = prev?.videoId != song?.videoId ||
            (song != null && positionMs + 5_000 < this.positionMs)
        if (changed) {
            finish(prev)
            this.song = song
            startMs = currentTimeMillis()
            playing = false
            everPlayed = false
            this.durationMs = 0
            manager?.onSongStop()
        }
        this.positionMs = positionMs
        if (durationMs > 0) this.durationMs = durationMs
        val s = this.song ?: return
        if (isPlaying != playing) {
            playing = isPlaying
            everPlayed = everPlayed || isPlaying
            manager?.onPlayerStateChanged(isPlaying, s, durationMs.takeIf { it > 0 })
            // ListenBrainz "now playing" on play and resume too, not just on track change (as Android).
            if (isPlaying) playingNow(s, positionMs, durationMs.takeIf { it > 0 })
        }
    }

    /** The queue stopped or was cleared: the last chance to close the ListenBrainz listen out. */
    fun onStopped() {
        finish(song)
        song = null
        playing = false
        everPlayed = false
        manager?.onSongStop()
    }

    private fun finish(song: Song?) {
        if (song == null || !everPlayed) return
        if (!AppSettings.scrobblingAvailable || !AppSettings.listenBrainzEnabled.value) return
        val token = AppSettings.listenBrainzToken.value
        if (token.isBlank()) return
        val start = startMs
        val duration = durationMs.takeIf { it > 0 }
        scope?.launch {
            ListenBrainzManager.submitFinished(
                token, song, start, currentTimeMillis(), duration,
                AppSettings.listenBrainzPrimaryArtistOnly.value,
            )
        }
    }

    private fun playingNow(song: Song, positionMs: Long, durationMs: Long?) {
        if (!AppSettings.scrobblingAvailable || !AppSettings.listenBrainzEnabled.value) return
        val token = AppSettings.listenBrainzToken.value
        if (token.isBlank()) return
        scope?.launch {
            ListenBrainzManager.submitPlayingNow(
                token, song, positionMs, durationMs,
                AppSettings.listenBrainzPrimaryArtistOnly.value,
            )
        }
    }

    /** PlaybackService.observeScrobbling: the manager outlives settings changes so a tweak doesn't cancel a running timer. */
    private suspend fun observeSettings(scope: CoroutineScope) {
        combine<Any, Boolean>(
            AppSettings.lastfmEnabled,
            AppSettings.lastfmScrobbleEnabled,
            AppSettings.lastfmNowPlaying,
            AppSettings.lastfmPrimaryArtistOnly,
            AppSettings.lastfmSessionKey,
            AppSettings.lastfmApiKey,
            AppSettings.lastfmSecret,
            AppSettings.lastfmEndpoint,
            AppSettings.scrobbleMinDuration,
            AppSettings.scrobbleDelayPercent,
            AppSettings.scrobbleDelaySeconds,
        ) { true }.collectLatest {
            val shouldEnable = AppSettings.scrobblingAvailable &&
                AppSettings.lastfmEnabled.value &&
                AppSettings.lastfmScrobbleEnabled.value &&
                AppSettings.lastfmSessionKey.value.isNotBlank() &&
                AppSettings.lastfmApiKey.value.isNotBlank() &&
                AppSettings.lastfmSecret.value.isNotBlank()

            if (!shouldEnable) {
                manager?.destroy()
                manager = null
                return@collectLatest
            }

            LastFM.configure(
                endpoint = AppSettings.lastfmEndpoint.value.ifBlank { LastFM.DEFAULT_API_ENDPOINT },
                apiKey = AppSettings.lastfmApiKey.value,
                secret = AppSettings.lastfmSecret.value,
                sessionKey = AppSettings.lastfmSessionKey.value,
            )
            val m = manager ?: ScrobbleManager(scope).also { manager = it }
            m.minSongDuration = AppSettings.scrobbleMinDuration.value
            m.scrobbleDelayPercent = AppSettings.scrobbleDelayPercent.value
            m.scrobbleDelaySeconds = AppSettings.scrobbleDelaySeconds.value
            m.useNowPlaying = AppSettings.lastfmNowPlaying.value
            m.usePrimaryArtistOnly = AppSettings.lastfmPrimaryArtistOnly.value

            // A track already underway when scrobbling turns on starts its timer now, as Android.
            song?.takeIf { playing }?.let { m.onPlayerStateChanged(true, it, durationMs.takeIf { d -> d > 0 }) }
        }
    }
}
