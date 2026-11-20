package com.music.laya.data.sources

import com.music.laya.data.TrackLog
import com.music.laya.data.model.Song
import com.music.laya.data.sources.lossless.LosslessProvider
import com.music.laya.data.sources.lossless.VerificationRequired
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
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

        /**
         * A lost session ([com.music.laya.data.sources.lossless.ZarzSession.lost]) switches its source off, so a
         * switch that is on always means a source that works; [notify] is told once, for a short notice. Call once,
         * after [SourceRegistry.init].
         */
        fun watchExpiry(scope: CoroutineScope, notify: suspend (LosslessProvider) -> Unit) {
            LosslessProvider.all.forEach { provider ->
                scope.launch {
                    lossesOf(provider).collect {
                        val on = SourceRegistry.configs.value.filter { it.kind == kindOf(provider) && it.enabled }
                        on.forEach { SourceRegistry.setEnabled(it.id, false) }
                        if (on.isNotEmpty()) notify(provider)
                    }
                }
            }
        }
    }
}
