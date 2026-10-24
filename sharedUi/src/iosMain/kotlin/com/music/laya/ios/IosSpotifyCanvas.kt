package com.music.laya.ios

import com.music.laya.data.canvas.CanvasArtwork
import com.music.laya.data.canvas.CanvasSource
import com.music.laya.data.canvas.SpotifyCanvasQuery
import com.music.laya.data.canvas.normalizeForMatch
import com.music.laya.data.canvas.splitArtists
import com.music.laya.data.model.Song
import com.music.laya.data.settings.AppSettings
import com.music.laya.platform.HttpRequest
import com.music.laya.platform.NetHttp
import com.music.laya.platform.ioDispatcher
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Spotify Canvas on iOS: Android's SpotifyCanvas + CanvasRepository, Spotify only (Apple / Tidal / community sources
 * stay Android-side). Same route: Pathfinder search (REST fallback) for the track uri, then the `canvas` GraphQL query.
 * ponytail: no canvaz-cache protobuf fallback for a video canvas that Pathfinder returns in an unplayable shape.
 */
internal object IosSpotifyCanvas {
    private const val PATHFINDER_V1 = "https://api-partner.spotify.com/pathfinder/v1/query"
    private const val SEARCH_URL = "https://api.spotify.com/v1/search"
    private const val SEARCH_HASH = "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val hashes = SpotifyCanvasQuery.QueryHashes(fetch = { get(it, mapOf("User-Agent" to SPOTIFY_UA)).second })

    private class Hit(val uri: String, val title: String, val artist: String, val album: String?)

    private fun get(url: String, headers: Map<String, String>): Pair<Int, String?> = runCatching {
        NetHttp.execute(HttpRequest(url, headers = headers)).let { it.code to if (it.isSuccessful) it.text() else null }
    }.getOrDefault(-1 to null)

    /** Null on any failure or when signed out: canvas is decoration. */
    suspend fun search(title: String, artist: String, album: String?): CanvasArtwork? {
        val token = IosSpotify.accessToken() ?: return null
        val clientToken = IosSpotify.clientToken()
        return withContext(ioDispatcher) {
            val headers = buildMap {
                put("Authorization", "Bearer $token")
                put("User-Agent", SPOTIFY_UA)
                clientToken?.let { put("Client-Token", it) }
            }
            val hit = searchViaPathfinder(title, artist, album, headers, clientToken != null) ?: searchViaRest(title, artist, album, headers)
                ?: return@withContext null
            val url = fetchCanvasUrl(hit.uri, headers) ?: return@withContext null
            CanvasArtwork(url = url, title = hit.title, artist = hit.artist, album = hit.album, source = CanvasSource.SPOTIFY)
        }
    }

    private fun searchViaPathfinder(title: String, artist: String, album: String?, headers: Map<String, String>, hasClientToken: Boolean): Hit? {
        if (!hasClientToken) return null
        val variables = buildJsonObject {
            put("searchTerm", listOfNotNull(title, artist, album).joinToString(" "))
            put("offset", 0); put("limit", 10); put("numberOfTopResults", 5)
            put("includeAudiobooks", false); put("includePreReleases", false)
        }.toString()
        val extensions = buildJsonObject { putJsonObject("persistedQuery") { put("version", 1); put("sha256Hash", SEARCH_HASH) } }.toString()
        val url = "$PATHFINDER_V1?operationName=searchTracks&variables=${variables.encodeURLParameter()}&extensions=${extensions.encodeURLParameter()}"
        val body = get(url, headers + mapOf("App-platform" to "WebPlayer", "Accept" to "application/json")).second ?: return null
        val item = runCatching {
            json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.get("searchV2")?.jsonObject?.get("tracksV2")?.jsonObject
                ?.get("items")?.jsonArray?.firstOrNull()?.jsonObject?.get("item")?.jsonObject?.get("data")?.jsonObject
        }.getOrNull() ?: return null
        val uri = item["uri"]?.jsonPrimitive?.contentOrNull ?: item["id"]?.jsonPrimitive?.contentOrNull?.let { "spotify:track:$it" } ?: return null
        return Hit(uri, title, artist, album)
    }

    private fun searchViaRest(title: String, artist: String, album: String?, headers: Map<String, String>): Hit? {
        val q = listOfNotNull(title, artist, album).joinToString(" ").encodeURLParameter()
        val body = get("$SEARCH_URL?q=$q&type=track&limit=10", headers).second ?: return null
        val items = runCatching { json.parseToJsonElement(body).jsonObject["tracks"]?.jsonObject?.get("items")?.jsonArray }.getOrNull() ?: return null
        for (item in items) {
            val track = item as? JsonObject ?: continue
            val name = track["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val artists = track["artists"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.orEmpty()
            if (!isMatch(name, artists, title, artist)) continue
            val uri = track["uri"]?.jsonPrimitive?.contentOrNull ?: continue
            return Hit(uri, name, artists.joinToString(", ").ifBlank { artist }, track["album"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull)
        }
        return null
    }

    private fun isMatch(gotName: String, gotArtists: List<String>, wantName: String, wantArtist: String): Boolean {
        if (gotName.normalizeForMatch() != wantName.normalizeForMatch()) return false
        val wanted = splitArtists(wantArtist)
        val credited = gotArtists.map { it.normalizeForMatch() }.filter { it.isNotBlank() }
        return wanted.isNotEmpty() && credited.isNotEmpty() && wanted.all { w -> credited.any { it == w } }
    }

    private fun fetchCanvasUrl(trackUri: String, headers: Map<String, String>, retry: Boolean = false): String? {
        val response = runCatching {
            NetHttp.execute(
                HttpRequest(
                    SpotifyCanvasQuery.ENDPOINT, "POST",
                    headers + mapOf("App-platform" to "WebPlayer", "Accept" to "application/json", "Accept-Language" to "en"),
                    SpotifyCanvasQuery.requestBody(trackUri, hashes.canvasHash(forceRefresh = retry)).encodeToByteArray(),
                    "application/json",
                ),
            )
        }.getOrNull()?.takeIf { it.isSuccessful } ?: return null
        return when (val answer = SpotifyCanvasQuery.parse(response.text())) {
            is SpotifyCanvasQuery.Answer.Found -> answer.url
            // A rebuilt web player retires the old hash: look it up again once.
            is SpotifyCanvasQuery.Answer.Failed -> if (answer.staleHash && !retry) fetchCanvasUrl(trackUri, headers, retry = true) else null
            is SpotifyCanvasQuery.Answer.NoCanvas -> null
        }
    }
}

/** Android's CanvasRepository for a song, Spotify only: cleaned title/artist, re-checked against what's playing, misses cached. */
internal object IosCanvas {
    private const val CACHE_SIZE = 64
    private class Entry(val artwork: CanvasArtwork?, val withAlbum: Boolean)
    private val cache = LinkedHashMap<String, Entry>()
    private val lock = Mutex()

    private val NOISE = Regex(
        """\((?:from|official|lyrical|video|audio)[^)]*\)|\[[^]]*]|\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
        RegexOption.IGNORE_CASE,
    )
    private fun String.cleaned() = replace(NOISE, " ").substringBefore(" | ").replace(Regex("\\s+"), " ").trim().ifBlank { this }
    private fun key(song: Song) = "song|${song.videoId}"

    fun cached(song: Song): CanvasArtwork? = cache[key(song)]?.artwork

    suspend fun canvasFor(song: Song): CanvasArtwork? {
        if (AppSettings.spotifySpdcToken.value.isBlank()) return null
        val title = song.title.cleaned()
        val artist = song.artist.cleaned()
        if (title.isBlank() || artist.isBlank()) return null
        val album = song.albumName
        return lock.withLock {
            // A miss reached without the album is provisional: the album is what makes the search land.
            cache[key(song)]?.let { if (it.artwork != null || it.withAlbum || album == null) return@withLock it.artwork }
            val found = runCatching { IosSpotifyCanvas.search(title, artist, album) }.getOrNull()?.takeIf { it.matches(title, artist, album) }
            if (cache.size >= CACHE_SIZE) cache.remove(cache.keys.first())
            cache[key(song)] = Entry(found, album != null)
            found
        }
    }
}
