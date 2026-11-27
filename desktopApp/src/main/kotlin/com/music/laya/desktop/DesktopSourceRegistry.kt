package com.music.laya.desktop

import com.music.laya.data.model.SearchFilter
import com.music.laya.data.model.SearchResult
import com.music.laya.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** What playback got to start with, and what is still being looked for. */
internal class DesktopLiveResolution(
    val stream: DesktopStream,
    /**
     * The substitution search that had not finished when sound started, or null when nothing is
     * outstanding.
     */
    val pendingSubstitute: Deferred<DesktopStream?>? = null,
)

internal data class DesktopSourceDescriptor(
    val id: String,
    val name: String,
    val detail: String,
    val kind: DesktopSourceKind,
    val configured: Boolean,
    val enabled: Boolean,
    val supportsLossless: Boolean,
    /** Enabled, but the ceiling in force will not let it answer a stream. */
    val skippedByQuality: Boolean = false,
)

/**
 * Marks a lookup that a source *failed* rather than answered, so Direct Source Stream can tell
 * "nobody has this" from "somebody errored" — the desktop's half of the phone's
 * SourceResolver.SourceFailures. Rides in the coroutine context the lookup's searches inherit.
 */
internal class DesktopSourceFailures : kotlin.coroutines.AbstractCoroutineContextElement(DesktopSourceFailures) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<DesktopSourceFailures>

    @Volatile
    var any = false
}

/** Flags the lookup this runs inside, if it is a Direct Source Stream one, as having hit an error. */
internal suspend fun noteSourceFailure() {
    currentCoroutineContext()[DesktopSourceFailures]?.any = true
}

/** Desktop counterpart of Android's SourceRegistry. */
internal object DesktopSourceRegistry {
    private interface SourceAdapter {
        val config: DesktopSourceConfig
        val descriptor: DesktopSourceDescriptor
        suspend fun resolve(song: Song, quality: String?): Result<DesktopStream?>
        /** [resolve] for a row this source owns, played as itself rather than as a match. */
        suspend fun resolveOwned(song: Song, quality: String?): Result<DesktopStream?> = resolve(song, quality)
        /** Every copy this source holds of [song], most confident first. */
        suspend fun matches(song: Song): List<Song> = emptyList()

        suspend fun match(song: Song): Song? = matches(song).firstOrNull()
        fun owns(song: Song): Boolean = false
    }

    private class AddonAdapter(
        override val config: DesktopSourceConfig,
    ) : SourceAdapter {
        override val descriptor: DesktopSourceDescriptor
            get() = config.descriptor()

        override fun owns(song: Song): Boolean =
            DesktopAddonSource.parseTrack(song.videoId)?.sourceId == config.id

        override suspend fun resolve(song: Song, quality: String?): Result<DesktopStream?> =
            DesktopAddonSource.stream(config, song, quality)

        override suspend fun resolveOwned(song: Song, quality: String?): Result<DesktopStream?> =
            DesktopAddonSource.stream(config, song, quality, picked = true)

        override suspend fun matches(song: Song): List<Song> = DesktopAddonSource.matches(config, song)
    }

    private class ModuleAdapter(
        override val config: DesktopSourceConfig,
    ) : SourceAdapter {
        override val descriptor: DesktopSourceDescriptor
            get() = config.descriptor()

        override fun owns(song: Song): Boolean =
            DesktopModuleSource.parseTrack(song.videoId)?.let { ref ->
                ref.sourceId == null || ref.sourceId == config.id
            } == true

        override suspend fun resolve(song: Song, quality: String?): Result<DesktopStream?> =
            DesktopModuleSource.stream(config, song, quality)

        override suspend fun matches(song: Song): List<Song> = DesktopModuleSource.matches(config, song)
    }

    private class JioAdapter(
        override val config: DesktopSourceConfig,
    ) : SourceAdapter {
        override val descriptor: DesktopSourceDescriptor
            get() = config.descriptor()

        override fun owns(song: Song): Boolean = song.videoId.startsWith("jiosaavn:")

        override suspend fun resolve(song: Song, quality: String?): Result<DesktopStream?> =
            DesktopJioSaavn.stream(song.videoId)

        override suspend fun matches(song: Song): List<Song> = DesktopJioSaavn.matches(song)
    }

    private class LosslessAdapter(
        override val config: DesktopSourceConfig,
    ) : SourceAdapter {
        override val descriptor: DesktopSourceDescriptor
            get() = config.descriptor()

        override fun owns(song: Song): Boolean = DesktopLossless.owns(config.kind, song)

        override suspend fun resolve(song: Song, quality: String?): Result<DesktopStream?> =
            DesktopLossless.stream(config, song)

        override suspend fun matches(song: Song): List<Song> = DesktopLossless.matches(config.kind, song)
    }

    private class YouTubeAdapter(
        override val config: DesktopSourceConfig,
    ) : SourceAdapter {
        override val descriptor: DesktopSourceDescriptor
            get() = config.descriptor()

        override suspend fun resolve(song: Song, quality: String?): Result<DesktopStream?> =
            DesktopStreamClient.resolve(song, youtubeCeiling(quality)).map { it.copy(sourceId = config.id) }

        /** Allows an unavailable module/Jio stream to fall back by identity. */
        override suspend fun matches(song: Song): List<Song> = youTubeMatches(song)
    }

    /** YouTube Music's copies of [song], most confident first, found by title and artist. */
    private suspend fun youTubeMatches(song: Song): List<Song> {
        if (song.title.isBlank() || song.isVideo) return emptyList()
        for (query in DesktopTrackMatcher.queries(song)) {
            val candidates = DesktopSearchClient.search(query, SearchFilter.SONGS)
                .getOrDefault(emptyList())
                .mapNotNull { (it as? SearchResult.Track)?.song }
            DesktopTrackMatcher.ranked(candidates, song).ifEmpty { null }?.let { return it }
        }
        return emptyList()
    }

    /**
     * The YouTube upload Automix measures [song] on: the row itself when it is one, else the best
     * YouTube Music match for an addon, module or JioSaavn row. Android's
     * `TrackAnalyzer.analysisUriFor` does the same.
     */
    suspend fun youTubeIdentity(song: Song): Song? = when {
        hasYouTubeOriginal(song) -> song
        // A row picked from the Addons search is that addon's or nothing — no YouTube search and
        // Opus fetch behind it just to measure it. Left unmeasured, as in a party.
        DesktopAddonSource.parseTrack(song.videoId) != null -> null
        else -> youTubeMatches(song).firstOrNull()
    }

    /**
     * Whether resolving [song] could ask an addon for it — an addon's own row, or a YouTube row
     * with an addon ranked above YouTube to substitute it.
     *
     * Such a track is not read ahead: an addon is only asked for a stream once the track is about
     * to play, as on Android, whose read-ahead only warms sources that are
     * `worthPrefetching`.
     */
    fun mayServeFromAddon(song: Song): Boolean {
        val addons = if (directActive()) directSources(null) else playbackAdapters(null).filterIsInstance<AddonAdapter>()
        if (addons.isEmpty()) return false
        if (DesktopAddonSource.parseTrack(song.videoId) != null) return true
        return hasYouTubeOriginal(song) && !song.isVideo && !DesktopOriginalVersion.isPinned(song.videoId)
    }

    private fun DesktopSourceConfig.descriptor() = DesktopSourceDescriptor(
        id = id,
        name = displayName,
        detail = if (kind.needsServer && baseUrl.isBlank()) "Setup required" else kind.detail,
        kind = kind,
        configured = isComplete,
        enabled = enabled,
        supportsLossless = kind.supportsLossless,
        skippedByQuality = enabled && !DesktopPersistence().audioQuality().permits(kind),
    )

    private fun configs(): List<DesktopSourceConfig> = DesktopPersistence().sourceConfigs()

    /**
     * The sources a *stream* may come from: enabled, complete, and permitted by the ceiling in
     * force.
     */
    private fun playbackAdapters(quality: String?, forDownload: Boolean = false): List<SourceAdapter> {
        val ceiling = ceiling(quality)
        return adapters(forDownload).filter { ceiling.permits(it.config.kind) }
    }

    /**
     * For a download, minus any addon whose manifest says `allowDownloads: 0`, so the next source
     * in line serves the file.
     */
    private fun adapters(forDownload: Boolean = false): List<SourceAdapter> =
        configs()
            .inSourceOrder()
            .filter { it.enabled && it.isComplete }
            .filterNot { forDownload && it.kind == DesktopSourceKind.ADDON && !DesktopAddonSource.allowsDownloads(it) }
            .map { config ->
                when (config.kind) {
                    DesktopSourceKind.ADDON -> AddonAdapter(config)
                    DesktopSourceKind.CUSTOM_MODULE,
                    DesktopSourceKind.MODULE,
                    -> ModuleAdapter(config)
                    DesktopSourceKind.JIOSAAVN -> JioAdapter(config)
                    DesktopSourceKind.TIDAL, DesktopSourceKind.QOBUZ -> LosslessAdapter(config)
                    DesktopSourceKind.YOUTUBE -> YouTubeAdapter(config)
                }
            }

    fun descriptors(): List<DesktopSourceDescriptor> = configs()
        .inSourceOrder()
        .map { it.descriptor() }

    fun isEnabled(sourceId: String): Boolean = configs().firstOrNull { it.id == sourceId }?.enabled == true

    /** Searching is YouTube Music's job, and only YouTube Music's. */
    suspend fun search(query: String, filter: SearchFilter): Result<List<SearchResult>> =
        DesktopSearchClient.search(query, filter)

    suspend fun resolve(
        song: Song,
        quality: String?,
        excludedSourceId: String? = null,
        /** A download: addons that said `allowDownloads: 0` are left out, see [adapters]. */
        forDownload: Boolean = false,
    ): Result<DesktopStream> = runCatching {
        val available = playbackAdapters(quality, forDownload)
        val moduleReference = DesktopModuleSource.parseTrack(song.videoId)
        val addonReference = DesktopAddonSource.parseTrack(song.videoId)
        val owned = when {
            addonReference != null -> available.firstOrNull { it.config.id == addonReference.sourceId }
            moduleReference?.sourceId != null -> available.firstOrNull { it.config.id == moduleReference.sourceId }
            moduleReference != null -> available.firstOrNull {
                it.config.kind == DesktopSourceKind.CUSTOM_MODULE || it.config.kind == DesktopSourceKind.MODULE
            }
            song.videoId.startsWith("jiosaavn:") -> available.firstOrNull { it.config.kind == DesktopSourceKind.JIOSAAVN }
            // A Tidal/Qobuz row picked from the Addons search.
            else -> available.firstOrNull { it is LosslessAdapter && it.owns(song) }
        }
        val losslessRow = owned is LosslessAdapter

        // A row from an addon that is out of the walk only for policy — a download from one whose
        // manifest says `allowDownloads: 0` — is looked up elsewhere, like any other track, rather
        // than refused the way a disabled source's row is.
        val heldBack = addonReference != null && owned == null &&
            configs().any { it.id == addonReference.sourceId && it.enabled && it.isComplete }
        if (!heldBack && (addonReference != null || moduleReference != null || losslessRow || song.videoId.startsWith("jiosaavn:"))) {
            if (owned == null) error("The source for this track is disabled or no longer configured")
            // This path has nothing to race.
            DesktopTrackLog.log(
                "resolving '${song.title}' — this row belongs to ${owned.descriptor.name}" +
                    ", asking at the ${(quality ?: DesktopPersistence().audioQuality().name).lowercase()} rung",
            )
            if (owned.config.id != excludedSourceId) {
                owned.resolveOwned(song, quality).getOrNull()
                    ?.takeIf { playableHere(owned, it) }
                    ?.let {
                        DesktopTrackLog.log(
                            "  ${owned.descriptor.name} served it at " +
                                it.format.summary.ifBlank { "an unstated format" },
                        )
                        return@runCatching it
                    }
                DesktopTrackLog.log("  ${owned.descriptor.name} had no usable stream for it")
            }
            // Android walks on to other active sources when the pinned source cannot produce a
            // stream.
            // Never YouTube under an addon's own row: its Opus is not the copy that was picked.
            for (fallback in available.filter {
                it !== owned && it.config.id != excludedSourceId &&
                    !(addonReference != null && it.config.kind == DesktopSourceKind.YOUTUBE)
            }) {
                val matched = fallback.match(song) ?: continue
                fallback.resolve(matched, quality).getOrNull()
                    ?.takeIf { playableHere(fallback, it) }
                    ?.let {
                        DesktopTrackLog.log(
                            "  fell through to ${fallback.descriptor.name} at " +
                                it.format.summary.ifBlank { "an unstated format" },
                        )
                        return@runCatching it
                    }
            }
            error("${owned.descriptor.name} did not return an audio stream")
        }

        // A normal YouTube row is offered to every higher-priority source first so a configured
        // FLAC/module or JioSaavn copy is used when available.
        for (source in available.filter {
            it.config.kind != DesktopSourceKind.YOUTUBE &&
                it.config.id != excludedSourceId &&
                !DesktopOriginalVersion.isPinned(song.videoId)
        }) {
            val matched = source.match(song) ?: continue
            source.resolve(matched, quality).getOrNull()
                ?.takeIf { playableHere(source, it) }
                ?.let { return@runCatching it }
        }
        val youtube = available.firstOrNull {
            it.config.kind == DesktopSourceKind.YOUTUBE && it.config.id != excludedSourceId
        }
            // YouTube is the non-removable fallback and is force-enabled on read.
            ?: error(
                if (excludedSourceId != null) {
                    "No other source has a copy of this track"
                } else {
                    "No enabled music source is configured"
                },
            )
        // A held-back addon row carries that addon's id, which means nothing to YouTube.
        val target = if (heldBack) youtube.match(song) ?: error("No other source has a copy of this track") else song
        youtube.resolve(target, quality).fold(
            onSuccess = { it ?: error("${youtube.descriptor.name} did not return an audio stream") },
            onFailure = { throw it },
        )
    }

    /**
     * Resolves a track for immediate playback, racing the sources ranked above YouTube against
     * YouTube itself.
     */
    suspend fun resolveLive(
        song: Song,
        quality: String?,
        excludedSourceId: String? = null,
    ): Result<DesktopLiveResolution> = runCatching {
        val available = playbackAdapters(quality)
        val owned = available.any { it.owns(song) } ||
            DesktopModuleSource.parseTrack(song.videoId) != null ||
            song.videoId.startsWith("jiosaavn:")
        if (owned) {
            val stream = resolve(song, quality, excludedSourceId).getOrThrow()
            // A row a source owns (JioSaavn, a module) starts on that source at once, and the
            // sources ranked above it look for a better copy behind it — as on Android, where this
            // used to be asked *before* the first note and held the track back for every search.
            // An addon's own row is played as picked, never substituted.
            val upgrade = if (DesktopAddonSource.parseTrack(song.videoId) == null && !song.isVideo) {
                betterThan(
                    stream,
                    available.filter {
                        it.config.kind != DesktopSourceKind.YOUTUBE && it.config.id != excludedSourceId
                    },
                    song,
                    quality,
                )
            } else {
                null
            }
            return@runCatching DesktopLiveResolution(stream, upgrade)
        }

        val youtube = available.firstOrNull {
            it.config.kind == DesktopSourceKind.YOUTUBE && it.config.id != excludedSourceId
        }
        val higher = if (DesktopOriginalVersion.isPinned(song.videoId)) {
            // Sent back to YouTube's own upload by hand.
            DesktopTrackLog.log("'${song.title}' is pinned to YouTube's original by the listener")
            emptyList()
        } else {
            available.filter {
                it.config.kind != DesktopSourceKind.YOUTUBE && it.config.id != excludedSourceId
            }
        }
        // Nothing outranks YouTube, so there is no race to run — this is the plain resolve.
        if (higher.isEmpty() || youtube == null) {
            return@runCatching DesktopLiveResolution(resolve(song, quality, excludedSourceId).getOrThrow())
        }
        if (directActive() && !song.isVideo) {
            return@runCatching resolveDirect(song, quality, higher, youtube)
        }

        // Both legs are parented to the registry's own scope rather than the caller's.
        DesktopTrackLog.log(
            "resolving '${song.title}' by '${song.artist}' — racing " +
                higher.joinToString { it.descriptor.name } + " against YouTube",
        )
        if (song.isVideo) {
            // A video upload is another recording, and can be another song altogether — the sources
            // are never asked to stand in for one.
            DesktopTrackLog.log("  this row is a video upload, so no source will be asked to match it")
        } else {
            DesktopTrackLog.log(
                "  asking for: " + DesktopTrackMatcher.queries(song).joinToString(" | ").ifBlank { "(nothing)" },
            )
        }
        val lookup = scope.async {
            withTimeoutOrNull(SUBSTITUTE_TIMEOUT_MS) { firstSubstitute(higher, song, quality) }
        }
        val fallback = scope.async { youtube.resolve(song, quality).getOrNull() }

        val quick: DesktopStream? = select {
            lookup.onAwait { it }
            // A YouTube leg that finished without a URL has not won anything.
            fallback.onAwait { stream -> if (stream != null) null else lookup.await() }
        }

        if (quick != null) {
            fallback.cancel()
            DesktopTrackLog.log(
                "substituted: '${song.title}' served by ${sourceNameFor(quick)} over YouTube" +
                    " at ${quick.format.summary.ifBlank { "an unstated format" }}",
            )
            // Winning the race is not the same as being the best copy.
            return@runCatching DesktopLiveResolution(quick, betterThan(quick, higher, song, quality))
        }

        val youtubeStream = fallback.await()
            ?: lookup.await()
            ?: error("No enabled music source could play this track")
        // Still running means it lost the race rather than ran out of answers.
        val pending = lookup.takeIf { it.isActive }
        if (pending != null) {
            DesktopTrackLog.log("'${song.title}' started on YouTube while the other sources keep looking")
        }
        // Whichever of [higher] answers first here is the fastest, not the best — JioSaavn's lossy
        // 320 routinely beats a lossless Tidal/Qobuz call that still has a license to negotiate. As
        // in the race above, winning is not the same as being the best copy: chase a better one
        // behind it instead of settling for the first.
        val chased = pending?.let { raw ->
            scope.async { raw.await()?.let { betterThan(it, higher, song, quality)?.await() ?: it } }
        }
        DesktopLiveResolution(youtubeStream, chased)
    }

    /**
     * Whether Direct Source Stream is on and has a source above YouTube to play from — the
     * desktop's half of Android's DirectSourceGate. Its Listen Together party check is the phone's
     * alone: the desktop doesn't join parties.
     */
    fun directActive(): Boolean =
        com.music.laya.data.settings.AppSettings.directSourceStream.value && directSources(null).isNotEmpty()

    /** The sources Direct Source Stream plays from: enabled, inside the ceiling, ranked above YouTube. */
    private fun directSources(quality: String?): List<SourceAdapter> =
        playbackAdapters(quality).takeWhile { it.config.kind != DesktopSourceKind.YOUTUBE }

    /**
     * Direct Source Stream's resolve: the sources above YouTube first and alone, YouTube only when
     * none answers within [DIRECT_LOOKUP_TIMEOUT_MS]. Nothing races, and a source's answer plays to
     * the end with no upgrade behind it. A YouTube start keeps the lookup still running (and, if it
     * finds something, the usual better-copy chase) as its upgrade.
     */
    private suspend fun resolveDirect(
        song: Song,
        quality: String?,
        higher: List<SourceAdapter>,
        youtube: SourceAdapter,
    ): DesktopLiveResolution {
        // [higher] already leaves out an excluded source; only those ranked above YouTube are direct.
        val above = directSources(quality).map { it.config.id }.toSet()
        val direct = higher.filter { it.config.id in above }.ifEmpty { higher }
        val missedAt = synchronized(directMisses) { directMisses[song.videoId] }
        if (missedAt != null && System.currentTimeMillis() - missedAt < DIRECT_MISS_KEPT_MS) {
            DesktopTrackLog.log("direct: no source had '${song.title}' recently; playing it from YouTube")
            return DesktopLiveResolution(
                youtube.resolve(song, quality).getOrNull() ?: error("No enabled music source could play this track"),
            )
        }
        DesktopTrackLog.log(
            "direct: asking ${direct.joinToString { it.descriptor.name }} for '${song.title}' before YouTube",
        )
        val failures = DesktopSourceFailures()
        // Every source asked at once, but answered in the listener's rank order: a quick JioSaavn
        // 320 must not end the search while a Tidal or Qobuz ranked above it is still looking —
        // taking the first answer did exactly that, cancelling the lossless search mid-way.
        val running = direct.map { source ->
            scope.async(failures) { openBest(source, song, quality)?.takeUnless { cutShort(it, song) } }
        }
        val lookup = scope.async(failures) {
            try {
                running.firstNotNullOfOrNull { it.await() }.also { found ->
                    // Only a clean "nobody has it" is remembered: a source that errored may have it
                    // once it recovers, so the next play asks again.
                    if (found == null && !failures.any) {
                        synchronized(directMisses) { directMisses[song.videoId] = System.currentTimeMillis() }
                    }
                }
            } finally {
                running.forEach { it.cancel() }
            }
        }
        val found = withTimeoutOrNull(DIRECT_LOOKUP_TIMEOUT_MS) { lookup.await() }
        if (found != null) {
            DesktopTrackLog.log("direct: '${song.title}' from ${sourceNameFor(found)} at ${found.format.summary.ifBlank { "an unstated format" }}")
            return DesktopLiveResolution(found)
        }
        val still = lookup.takeIf { it.isActive }
        // Out of time with a higher-ranked source still looking: the best-ranked answer already in
        // hand plays rather than YouTube, and the higher one swaps in if it lands.
        val partial = if (still != null) {
            running.firstNotNullOfOrNull { if (it.isCompleted && !it.isCancelled) it.await() else null }
        } else {
            null
        }
        if (partial != null) {
            DesktopTrackLog.log(
                "direct: '${song.title}' from ${sourceNameFor(partial)} for now; a higher-ranked source is still looking",
            )
            return DesktopLiveResolution(partial, scope.async { still?.await()?.takeIf { it.url != partial.url } })
        }
        DesktopTrackLog.log(
            if (still != null) {
                "no source answered for '${song.title}' within ${DIRECT_LOOKUP_TIMEOUT_MS / 1000}s; starting it on YouTube and still looking"
            } else {
                "no source served '${song.title}' directly; playing it from YouTube"
            },
        )
        val stream = youtube.resolve(song, quality).getOrNull()
            ?: still?.await()
            ?: error("No enabled music source could play this track")
        return DesktopLiveResolution(stream, still)
    }

    /**
     * Tracks no source had, so a replay goes straight to YouTube rather than waiting another
     * [DIRECT_LOOKUP_TIMEOUT_MS]. ponytail: process-lifetime and bounded; a source enabled later is
     * only asked once the entry ages out.
     */
    private val directMisses = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > DIRECT_MISSES_KEPT
    }

    /**
     * Whether [stream]'s file is too small to hold [song] — a source's 30-second preview under a
     * full-length row. Nothing auditions a direct answer, so its size is checked with one ranged
     * request; a manifest, a local file or a server that won't say passes.
     */
    private suspend fun cutShort(stream: DesktopStream, song: Song): Boolean {
        val expectedSec = song.durationText.durationSeconds().toInt().takeIf { it > 0 } ?: return false
        if (stream.transport != null || !stream.url.startsWith("http") ||
            stream.url.substringBefore('?').let { it.endsWith(".mpd") || it.endsWith(".m3u8") }
        ) {
            return false
        }
        val bytes = com.music.laya.data.sources.StreamLengthProbe.bytesOf(stream.url, stream.headers) ?: return false
        if (!com.music.laya.data.sources.StreamLengthProbe.tooShort(bytes, stream.format.isLossless, stream.format.kbps, expectedSec)) {
            return false
        }
        DesktopTrackLog.log("'${song.title}': ${bytes / 1024}kB can't hold ${expectedSec}s — a preview; looking further")
        return true
    }

    /**
     * Every enabled addon's and verified Tidal/Qobuz catalogue's rows for [query], round-robin so
     * no catalogue buries the others — the Search page's Addons tab. A slow or failing source costs
     * only its own rows.
     */
    suspend fun searchAddons(query: String): List<Song> = coroutineScope {
        val sources = playbackAdapters(null).filter { it is AddonAdapter || it is LosslessAdapter }
        val perSource = sources.map { source ->
            async {
                withTimeoutOrNull(ADDON_SEARCH_TIMEOUT_MS) {
                    runCatching {
                        when (source) {
                            is AddonAdapter -> DesktopAddonSource.search(source.config, query, ADDON_SEARCH_LIMIT)
                                .getOrDefault(emptyList()).mapNotNull { (it as? SearchResult.Track)?.song }
                            else -> DesktopLossless.search(source.config.kind, query, ADDON_SEARCH_LIMIT)
                        }
                    }.getOrDefault(emptyList())
                } ?: emptyList()
            }
        }.map { it.await() }
        buildList {
            for (i in 0 until (perSource.maxOfOrNull { it.size } ?: 0)) perSource.forEach { rows -> rows.getOrNull(i)?.let(::add) }
        }
    }

    /** Whether the Addons tab has anything to ask. */
    fun hasAddonSearch(): Boolean = playbackAdapters(null).any { it is AddonAdapter || it is LosslessAdapter }

    /**
     * A patient second look for a copy that beats the one now playing, or null when nothing could.
     */
    private fun betterThan(
        playing: DesktopStream,
        candidates: List<SourceAdapter>,
        song: Song,
        quality: String?,
    ): Deferred<DesktopStream?>? {
        if (playing.format.isLossless || playing.format.isDolbyAtmos) return null
        val winnerRank = configs().firstOrNull { it.id == playing.sourceId }?.kind?.rank
            ?: DesktopSourceKind.YOUTUBE.rank
        val better = candidates.filter { it.config.kind.rank < winnerRank }
        if (better.isEmpty()) return null
        DesktopTrackLog.log(
            "still looking for a better copy of '${song.title}' from " +
                better.joinToString { it.descriptor.name },
        )
        // The registry's own scope, like both legs of the race above: this outlives the call that
        // started it by design.
        // Only an answer worth the swap ends the search. Taking the first usable one let a module's
        // MP3 settle-for beat a slower FLAC, which then waited for a follow-up search.
        return scope.async {
            withTimeoutOrNull(UPGRADE_TIMEOUT_MS) {
                firstSubstitute(better, song, quality) { worthSwapping(it.format, playing.format) }
            }
        }
    }

    /**
     * The upcoming song's catalogue searches, started early by [warmMatches]: its id, and each
     * source's search by config id. One song at a time.
     */
    @Volatile
    private var warmed: Pair<String, Map<String, Deferred<List<Song>>>>? = null

    /**
     * Starts every source's search for [song] now, while the track before it plays.
     *
     * An addon's track is armed only shortly before its transition, so its stream URL is fresh
     * when it plays; the search was armed with it, and a slow addon's search ran past that lead,
     * starting the track on a quick copy and upgrading mid-song. Only the search moves earlier —
     * [openBest] takes these results, and the stream itself is still asked for at arm time.
     */
    fun warmMatches(song: Song, quality: String?) {
        // Direct Source Stream asks for nothing before the track's window opens.
        if (song.isVideo || song.title.isBlank() || directActive()) return
        val sources = playbackAdapters(quality).filter { it.config.kind != DesktopSourceKind.YOUTUBE }
        if (sources.any { it.owns(song) }) return // played as itself, never matched
        warmed?.second?.values?.forEach { it.cancel() }
        warmed = song.videoId to sources.associate { source ->
            source.config.id to scope.async { runCatching { source.matches(song) }.getOrDefault(emptyList()) }
        }
        DesktopTrackLog.log("searching ahead for '${song.title}' on ${sources.joinToString { it.descriptor.name }}")
    }

    /** [source]'s matches for [song]: the early search from [warmMatches] when there is one. */
    private suspend fun matchesFor(source: SourceAdapter, song: Song): List<Song> =
        warmed?.takeIf { it.first == song.videoId }?.second?.get(source.config.id)
            ?.takeUnless { it.isCancelled }
            ?.await()
            ?: source.matches(song)

    /** The first usable stream from any of [sources], each asked in parallel. */
    private suspend fun firstSubstitute(
        sources: List<SourceAdapter>,
        song: Song,
        quality: String?,
        accept: suspend (DesktopStream) -> Boolean = { true },
    ): DesktopStream? = coroutineScope {
        val running = sources
            .map { source ->
                async { openBest(source, song, quality) }
            }
            .toMutableList()
        try {
            while (running.isNotEmpty()) {
                val done = select<Deferred<DesktopStream?>> {
                    running.forEach { candidate -> candidate.onAwait { candidate } }
                }
                running -= done
                done.await()?.let { if (accept(it)) return@coroutineScope it }
            }
            null
        } finally {
            running.forEach { it.cancel() }
        }
    }

    /** Whether a copy found after the fact is worth interrupting playback for. */
    internal fun worthSwapping(candidate: DesktopStreamFormat, playing: DesktopStreamFormat?): Boolean {
        if (candidate.isLossless) return true
        val gain = (candidate.kbps ?: return false) - (playing?.kbps ?: return false)
        return gain >= UPGRADE_MIN_GAIN_KBPS
    }

    /**
     * A second look, asked from scratch once the first one has settled for nothing better.
     *
     * The live race deliberately takes the first copy that beats YouTube, so a slower lossless
     * source can still be searching when a lossy one wins — and a source that answered HTTP 502
     * that minute may well answer properly now. Mirrors Android's `SourceResolver.upgradeFor`.
     */
    suspend fun upgradeFor(
        song: Song,
        playing: DesktopStream?,
        quality: String? = null,
    ): DesktopStream? {
        // This is an explicit second look. Empty catalogue answers and failed/expired stream URLs
        // from the first pass must not make the retry a cache hit.
        DesktopAddonSource.clearCompletedTrackCalls()
        if (warmed?.first == song.videoId) warmed = null
        val playingRank = configs().firstOrNull { it.id == playing?.sourceId }?.kind?.rank
            ?: DesktopSourceKind.YOUTUBE.rank
        val better = playbackAdapters(quality).filter {
            it.config.kind != DesktopSourceKind.YOUTUBE && it.config.kind.rank < playingRank
        }
        if (better.isEmpty()) return null
        DesktopTrackLog.log(
            "asking ${better.joinToString { it.descriptor.name }} again for a better copy of " +
                "'${song.title}'",
        )
        return withTimeoutOrNull(UPGRADE_TIMEOUT_MS) {
            firstSubstitute(better, song, quality) { worthSwapping(it.format, playing?.format) }
        }
    }

    /** Whether a stream a source handed back can actually be played here. */
    /**
     * The best copy [source] can actually serve for [song], or null when it has none.
     *
     * More than one row is opened, because a catalogue that holds a track can still fail on the
     * particular row matched first — and rows advertising the tier being asked for go first.
     */
    private suspend fun openBest(
        source: SourceAdapter,
        song: Song,
        quality: String?,
    ): DesktopStream? {
        val matches = matchesFor(source, song)
        if (matches.isEmpty()) {
            // A leg cancelled by a sibling winning the race has not missed anything.
            if (currentCoroutineContext().isActive) {
                DesktopTrackLog.log("${source.descriptor.name} has no copy of '${song.title}'")
            }
            return null
        }
        val wantsLossless = ceiling(quality) == DesktopAudioQuality.LOSSLESS
        var settleFor: DesktopStream? = null
        for (match in preferred(matches, song, wantsLossless).take(STREAM_ATTEMPTS)) {
            val stream = source.resolve(match, quality)
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) noteSourceFailure() }
                .getOrNull() ?: continue
            if (!playableHere(source, stream)) continue
            val served = stream.format
            if (!wantsLossless || served.isLossless || served.isDolbyAtmos) {
                DesktopTrackLog.log(
                    "${source.descriptor.name} matched '${match.title}' by '${match.artist}'" +
                        " → ${served.summary.ifBlank { "an unstated format" }}",
                )
                return stream
            }
            DesktopTrackLog.log(
                "${source.descriptor.name} offered ${served.summary.ifBlank { "an unstated format" }}" +
                    " for '${match.title}'; looking further",
            )
            // The floor is the best of what was refused, not the first of it.
            settleFor = betterOf(settleFor, stream)
        }
        if (settleFor == null && currentCoroutineContext().isActive) {
            DesktopTrackLog.log("${source.descriptor.name} matched '${song.title}' but returned no stream")
        }
        return settleFor
    }

    /** The matching rows in the order they are worth opening. */
    internal fun preferred(matches: List<Song>, target: Song, wantsLossless: Boolean): List<Song> {
        val sameLength = matches.filter { DesktopTrackMatcher.withinSeconds(it, target, SAME_RECORDING_SEC) }
        val eligible = sameLength.ifEmpty { matches }
        if (!wantsLossless) return eligible
        // Stable, so the confidence order the matcher produced survives inside each tier.
        return eligible.sortedByDescending { it.sourceQuality == DesktopModuleSource.LOSSLESS }
    }

    /** Which of two renditions of the same recording is the better one. */
    private fun betterOf(current: DesktopStream?, candidate: DesktopStream): DesktopStream {
        val held = current ?: return candidate
        val a = candidate.format
        val b = held.format
        if (a.isLossless != b.isLossless) return if (a.isLossless) candidate else held
        if (a.isDolbyAtmos != b.isDolbyAtmos) return if (a.isDolbyAtmos) candidate else held
        return if ((a.kbps ?: 0) > (b.kbps ?: 0)) candidate else held
    }

    private fun playableHere(source: SourceAdapter, stream: DesktopStream): Boolean {
        if (stream.isDolbyAtmos && !DesktopCodecs.supportsDolbyAtmos) {
            DesktopTrackLog.log(
                "${source.descriptor.name} offered a Dolby Atmos rendition, " +
                    "which this build has no E-AC-3 decoder for",
            )
            return false
        }
        if (stream.isDolbyAtmos && !DesktopAddonSettings.dolbyAtmosEnabled) {
            DesktopTrackLog.log(
                "${source.descriptor.name} offered a Dolby Atmos rendition, which is switched off",
            )
            return false
        }
        return true
    }

    /** Whether this row has a YouTube upload behind it to go back to. */
    fun hasYouTubeOriginal(song: Song): Boolean =
        DesktopAddonSource.parseTrack(song.videoId) == null &&
            DesktopModuleSource.parseTrack(song.videoId) == null &&
            !song.videoId.startsWith("jiosaavn:") &&
            song.videoId.isNotBlank()

    /** The configured source a stream came from, for a log line. */
    internal fun sourceNameFor(stream: DesktopStream): String =
        configs().firstOrNull { it.id == stream.sourceId }?.displayName ?: "a configured source"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How long the live path waits on the sources before starting on whatever YouTube has. */
    private const val SUBSTITUTE_TIMEOUT_MS = 20_000L
    /** How long a Direct Source Stream start waits for the sources before falling back to YouTube. */
    private const val DIRECT_LOOKUP_TIMEOUT_MS = 15_000L
    private const val DIRECT_MISS_KEPT_MS = 30 * 60_000L
    private const val DIRECT_MISSES_KEPT = 256
    private const val ADDON_SEARCH_TIMEOUT_MS = 12_000L
    private const val ADDON_SEARCH_LIMIT = 20

    /**
     * The patient second look's budget. Longer than the race's, because nobody is waiting on the
     * first note by then and a cold addon routinely needs more than one call to settle.
     */
    private const val UPGRADE_TIMEOUT_MS = 90_000L

    /** How many matched rows are opened before a source is written off. */
    private const val STREAM_ATTEMPTS = 3

    /** The runtime agreement that says two rows are the same recording. */
    private const val SAME_RECORDING_SEC = 2

    /** The smallest bitrate gain worth a break in the audio for. */
    private const val UPGRADE_MIN_GAIN_KBPS = 96

    /** The ceiling a call is made under: an explicit override, else the stored rung. */
    internal fun ceiling(qualityOverride: String?): DesktopAudioQuality =
        qualityOverride?.let { override ->
            DesktopAudioQuality.entries.firstOrNull { it.name.equals(override, ignoreCase = true) }
        } ?: DesktopPersistence().audioQuality()

    private fun youtubeCeiling(qualityOverride: String?): Int = ceiling(qualityOverride).maxKbps
}
