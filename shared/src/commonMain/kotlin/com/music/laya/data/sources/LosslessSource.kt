package com.music.laya.data.sources

import com.music.laya.data.TrackLog
import com.music.laya.data.model.Song
import com.music.laya.data.sources.lossless.LosslessProvider
import com.music.laya.data.sources.lossless.VerificationRequired
import kotlinx.coroutines.CoroutineScope
import com.music.laya.data.settings.AppSettings
import com.music.laya.data.settings.AudioQuality
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch

/**
 * The built-in Tidal or Qobuz source. Silent — no search, no stream — until the listener has
 * passed the broker's one-time check from the sources screen, so an unverified source costs the
 * resolver nothing.
 */
class LosslessSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    val provider: LosslessProvider = providerFor(config.kind)

    override val configId: String get() = config.id
    override val kind: SourceKind get() = config.kind
    override val displayName: String get() = config.label.ifBlank { kind.label }

    override suspend fun health(): SourceHealth =
        if (provider.session.verified.value) SourceHealth.Ok("Verified") else SourceHealth.Rejected("Verifies when turned on")

    override suspend fun search(query: String, limit: Int, waitForAll: Boolean, request: StreamRequest?): List<Song> {
        if (!provider.session.verified.value) return emptyList()
        return provider.search(query, limit).map { track ->
            Song(
                videoId = SourceRegistry.trackKey(config.id, track.id),
                title = track.title,
                artist = track.artist.ifBlank { "Unknown Artist" },
                albumName = track.album,
                thumbnailUrl = track.coverUrl,
                durationText = track.durationSec?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
                sourceQuality = "LOSSLESS",
                isExplicit = track.explicit,
            )
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        if (!provider.session.verified.value) return null
        val stream = try {
            provider.stream(trackId)
        } catch (e: VerificationRequired) {
            TrackLog.w(TAG, "${provider.label}: verification expired; verify again on the Sources screen")
            return null
        } ?: return null
        if (stream.transport != null && !SourceRegistry.host.acceptTransport(stream.url, stream.transport)) return null
        TrackLog.d(TAG, "  ✓ ${provider.label} ${stream.format.summary} ${stream.url.take(96)}")
        return SourceStream(url = stream.url, format = stream.format)
    }

    companion object {
        private const val TAG = "Laya"

        fun providerFor(kind: SourceKind): LosslessProvider = when (kind) {
            SourceKind.QOBUZ -> LosslessProvider.Qobuz
            else -> LosslessProvider.Tidal
        }

        /**
         * The switch is the only control, on every platform: turning it on always verifies afresh (the old session
         * is dropped first), turning it off drops the session. Returns the provider to verify, or null.
         */
        fun toggled(on: Boolean, provider: LosslessProvider): LosslessProvider? {
            provider.session.forget()
            return provider.takeIf { on }
        }

        /** [provider]'s session losses, starting with one already standing when collection begins. */
        fun lossesOf(provider: LosslessProvider): Flow<Unit> =
            provider.session.lost.onSubscription { if (provider.session.expired.value) emit(Unit) }

        fun kindOf(provider: LosslessProvider): SourceKind =
            if (provider == LosslessProvider.Qobuz) SourceKind.QOBUZ else SourceKind.TIDAL

        /** Whether lossless is in use on the phones: the quality for the connection in hand asks for it. */
        val losslessInUse: Flow<Boolean> = combine(
            AppSettings.audioQualityWifi, AppSettings.audioQualityCellular,
            AppSettings.meteredConnection, AppSettings.streamCeilingOverride,
        ) { _, _, _, _ -> AppSettings.effectiveAudioQuality == AudioQuality.LOSSLESS }

        /** Switches every [provider] row off in [SourceRegistry]; true when one was on. */
        private fun switchOffInRegistry(provider: LosslessProvider): Boolean {
            val on = SourceRegistry.configs.value.filter { it.kind == kindOf(provider) && it.enabled }
            on.forEach { SourceRegistry.setEnabled(it.id, false) }
            return on.isNotEmpty()
        }

        /** How often a held session's end date is compared with the clock. */
        const val CHECK_EVERY_MS = 5 * 60_000L

        /**
         * A lost session ([com.music.laya.data.sources.lossless.ZarzSession.lost]) switches its source off at once,
         * so the switch and its status are never wrong, whatever the quality. The notice ([notify], once) waits
         * until lossless is in use ([inUse]): on a capped quality the user is told when they come back to it, not
         * about a source they are not listening to; dropped if they verify again first. A session is also
         * compared with the clock every [CHECK_EVERY_MS], since a capped quality makes no request that would find
         * it expired. Call once at startup, after the sources are loaded; desktop passes its own switch and quality.
         */
        fun watchExpiry(
            scope: CoroutineScope,
            inUse: Flow<Boolean> = losslessInUse,
            switchOff: (LosslessProvider) -> Boolean = ::switchOffInRegistry,
            notify: suspend (LosslessProvider) -> Unit,
        ) {
            LosslessProvider.all.forEach { provider ->
                val owed = MutableStateFlow(false)
                scope.launch { lossesOf(provider).collect { if (switchOff(provider)) owed.value = true } }
                // Belt and braces for the report above, which fires once: a session already gone
                // before this run (cleared in an earlier one, the switch left on) is never reported
                // again, and the row sat "on" with nothing behind it. No session, no switch — at
                // startup and whenever it drops. Silent: the notice stays with a real loss. Turning
                // the switch on by hand doesn't change [verified], so it doesn't fight the verify flow.
                scope.launch { provider.session.verified.collect { if (!it) switchOff(provider) } }
                scope.launch {
                    while (true) {
                        provider.session.renewOrCheck()
                        delay(CHECK_EVERY_MS)
                    }
                }
                scope.launch {
                    combine(owed, inUse) { due, wanted -> due && wanted }.distinctUntilChanged().collect { due ->
                        if (!due) return@collect
                        owed.value = false
                        if (provider.session.expired.value) notify(provider)
                    }
                }
            }
        }
    }
}
