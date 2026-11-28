package com.music.laya.data.aifilter

import com.music.laya.data.TrackLog
import com.music.laya.data.lyrics.LyricsTranslation
import com.music.laya.platform.HttpRequest
import com.music.laya.platform.NetHttp
import com.music.laya.platform.currentTimeMillis
import com.music.laya.platform.ioDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/**
 * AI-generated songs: which ones they are, and keeping them out of feeds, search and radio.
 *
 * The verdict comes from AiSList (aisloplist.com, CC BY-NC 4.0), a community list of YouTube
 * channels that publish AI-generated content, kept as `@handles`. A song is checked by its
 * uploader's handle, which YouTube's oEmbed answer carries for any public video in under a
 * kilobyte and without an account. Songs published through an auto-generated "Topic"
 * channel have no handle and so are never matched: the list cannot catch those.
 *
 * Both lookups are remembered on disk: the list, checked for changes once a day, and each
 * song's uploader, for good.
 */
object AiSongs {
    /** Whether AI songs are kept out of feeds, search and radio; [com.music.laya.data.settings.AppSettings] owns it. */
    val hide = MutableStateFlow(true)

    private val _flagged = MutableStateFlow<Set<String>>(emptySet())
    /** Video ids known to be AI-generated. Grows as songs are checked; drives the "AI" badges. */
    val flagged: StateFlow<Set<String>> = _flagged.asStateFlow()

    fun isAi(videoId: String?): Boolean = videoId != null && videoId in _flagged.value

    /**
     * [items] without the AI songs among them, when [hide] is on. Waits up to [waitMs] for
     * unchecked songs to be looked up; one still unknown after that is kept, and is caught next
     * time once its lookup lands. [waitMs] of 0 filters on what is already known.
     */
    suspend fun <T> keep(items: List<T>, waitMs: Long = KEEP_WAIT_MS, videoId: (T) -> String?): List<T> {
        if (!hide.value || items.isEmpty()) return items
        val ids = items.mapNotNull(videoId)
        if (waitMs > 0) withTimeoutOrNull(waitMs) { check(ids) } else request(ids)
        val flagged = _flagged.value
        return items.filter { videoId(it) !in flagged }
    }

    /** Fetches (or reads) the list ahead of the first feed or radio that needs it; call once at startup. */
    fun warmUp() {
        scope.launch { runCatching { load() } }
    }

    /** Looks [videoIds] up in the background, for badges on rows that were not filtered. */
    fun request(videoIds: Collection<String>) {
        if (videoIds.isEmpty()) return
        scope.launch { runCatching { check(videoIds) } }
    }

    /** Looks up every one of [videoIds] not yet checked. */
    suspend fun check(videoIds: Collection<String>) = withContext(ioDispatcher) {
        load()
        val unknown = videoIds.filter { it.isNotBlank() && it !in uploaders }.distinct()
        if (unknown.isEmpty()) return@withContext
        coroutineScope {
            unknown.map { id -> async { lookups.withPermit { uploaderOf(id) }?.let { id to it } } }.awaitAll()
        }.filterNotNull().takeIf { it.isNotEmpty() }?.let { found ->
            diskMutex.withLock {
                found.forEach { (id, handle) -> uploaders[id] = handle }
                append(found)
            }
            refreshFlagged()
        }
    }

    // ── The list and the remembered uploaders ─────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val loadMutex = Mutex()
    private val diskMutex = Mutex()
    private val lookups = Semaphore(LOOKUP_CONCURRENCY)
    private val json = Json { ignoreUnknownKeys = true }
    private val fs get() = FileSystem.SYSTEM
    private val directory: Path? get() = LyricsTranslation.cacheDir?.let { it / DIRECTORY }

    /** Lowercased handles, without the `@`. */
    @kotlin.concurrent.Volatile
    private var blocklist: Set<String> = emptySet()

    /** Video id → its uploader's lowercased handle, or "" when the uploader has none. */
    private val uploaders = com.music.laya.platform.ConcurrentMap<String, String>()

    @kotlin.concurrent.Volatile
    private var loadedAt = 0L

    /**
     * Reads the list, asking GitHub once a day whether it changed. The ask carries the saved
     * copy's ETag, so an unchanged list costs a few hundred bytes (304) rather than the whole
     * file. The ETag file's age is when the list was last checked; offline, the saved copy stands.
     */
    private suspend fun load() = loadMutex.withLock {
        val now = currentTimeMillis()
        if (loadedAt != 0L && now - loadedAt < LIST_CHECK_INTERVAL_MS) return@withLock
        if (loadedAt == 0L) readUploaders()
        val dir = directory
        val list = dir?.let { it / LIST_FILE }
        val etagFile = dir?.let { it / ETAG_FILE }
        val checkedAt = etagFile?.let { runCatching { fs.metadata(it).lastModifiedAtMillis }.getOrNull() }
        val saved = list?.let { runCatching { fs.read(it) { readUtf8() } }.getOrNull() }
        val text = if (saved != null && checkedAt != null && now - checkedAt < LIST_CHECK_INTERVAL_MS) {
            saved
        } else {
            val etag = saved?.let { etagFile?.let { runCatching { fs.read(it) { readUtf8() } }.getOrNull() } }
            val fetched = fetchList(etag)
            when {
                fetched == null -> saved // Offline or refused: the saved copy, and another try next time.
                fetched === NOT_MODIFIED -> saved.also { etagFile?.let { write(it, etag.orEmpty()) } }
                else -> fetched.first.also { fresh ->
                    list?.let { write(it, fresh) }
                    etagFile?.let { write(it, fetched.second.orEmpty()) }
                }
            }
        }
        if (text != null && text !== blocklistSource) {
            blocklist = parseBlocklist(text)
            blocklistSource = text
        }
        loadedAt = now
        refreshFlagged()
    }

    /** The list text and its ETag; [NOT_MODIFIED] when [etag] is still current; null on failure. */
    private fun fetchList(etag: String?): Pair<String, String?>? = runCatching {
        val response = NetHttp.execute(
            HttpRequest(
                LIST_URL,
                headers = if (etag.isNullOrBlank()) emptyMap() else mapOf("If-None-Match" to etag),
                // The whole 1.2 MB list took over two minutes on a slow link; an unchanged one is a 304.
                callTimeoutMs = 3 * 60_000,
            ),
        )
        when {
            response.code == 304 -> NOT_MODIFIED
            response.isSuccessful -> response.text() to response.header("ETag")
            else -> null
        }
    }.onFailure { TrackLog.w(TAG, "AiSList check failed: ${it.message}") }.getOrNull()

    private val NOT_MODIFIED = "" to null

    /** The text [blocklist] was parsed from, so an unchanged list is not parsed again. */
    @kotlin.concurrent.Volatile
    private var blocklistSource: String? = null

    /** Writes [text] to [file] whole, through a temporary file, so a crash never leaves half a list. */
    private fun write(file: Path, text: String) {
        runCatching {
            fs.createDirectories(file.parent!!)
            val temporary = file.parent!! / "${file.name}.tmp"
            fs.write(temporary) { writeUtf8(text) }
            fs.atomicMove(temporary, file)
        }
    }

    private fun readUploaders() {
        val file = directory?.let { it / UPLOADERS_FILE } ?: return
        runCatching {
            fs.read(file) {
                while (true) {
                    val line = readUtf8Line() ?: break
                    val tab = line.indexOf('\t')
                    if (tab > 0) uploaders[line.substring(0, tab)] = line.substring(tab + 1)
                }
            }
        }
    }

    private fun append(found: List<Pair<String, String>>) {
        val dir = directory ?: return
        runCatching {
            fs.createDirectories(dir)
            fs.appendingSink(dir / UPLOADERS_FILE).buffer().use { sink ->
                found.forEach { (id, handle) -> sink.writeUtf8("$id\t$handle\n") }
            }
        }
    }

    private fun refreshFlagged() {
        val list = blocklist
        if (list.isEmpty()) return
        _flagged.value = uploaders.entries.filter { it.value.isNotEmpty() && it.value in list }.map { it.key }.toSet()
    }

    /** [videoId]'s uploader handle, "" when it has none, or null when the lookup should be retried later. */
    private fun uploaderOf(videoId: String): String? = runCatching {
        val response = NetHttp.execute(
            HttpRequest(
                "https://www.youtube.com/oembed?format=json&url=https://www.youtube.com/watch?v=$videoId",
                callTimeoutMs = 10_000,
            ),
        )
        when {
            response.isSuccessful -> handleOf(
                json.parseToJsonElement(response.text()).jsonObject["author_url"]?.jsonPrimitive?.contentOrNull,
            )
            // Private, removed or not embeddable: no uploader to read, and none will appear.
            response.code in setOf(400, 401, 403, 404) -> ""
            else -> null
        }
    }.getOrNull()

    // ── Parsing (internal for tests) ──────────────────────────────────────

    /** The lowercased handle in an oEmbed `author_url`, or "" for a channel-id URL (a Topic channel). */
    internal fun handleOf(authorUrl: String?): String =
        authorUrl?.substringAfter("/@", "")?.substringBefore('/')?.let(::percentDecode)?.lowercase().orEmpty()

    /** AiSList's text: one `@handle` (or channel id) per line, `!` starting a comment. */
    internal fun parseBlocklist(text: String): Set<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("@") }
            .map { it.removePrefix("@").lowercase() }
            .toHashSet()

    private fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val bytes = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 <= text.lastIndex) {
                val value = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (value != null) {
                    bytes += value.toByte()
                    i += 3
                    continue
                }
            }
            c.toString().encodeToByteArray().forEach { bytes += it }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    private const val TAG = "AiSongs"
    private const val LIST_URL = "https://raw.githubusercontent.com/Override92/AiSList/main/AiSList/aislist_blocklist.txt"
    private const val DIRECTORY = "ai_filter"
    private const val LIST_FILE = "aislist_blocklist.txt"
    private const val UPLOADERS_FILE = "uploaders.tsv"
    private const val ETAG_FILE = "aislist_blocklist.etag"
    private const val LIST_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val LOOKUP_CONCURRENCY = 8
    private const val KEEP_WAIT_MS = 4_000L
}
