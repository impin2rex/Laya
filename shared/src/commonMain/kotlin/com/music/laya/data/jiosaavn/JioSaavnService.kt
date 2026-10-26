package com.music.laya.data.jiosaavn

import com.music.laya.data.TrackLog
import com.music.laya.platform.HttpRequest
import com.music.laya.platform.NetHttp
import com.music.laya.platform.desEcbDecrypt
import com.music.laya.platform.ioDispatcher
import io.ktor.http.URLBuilder
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
data class RawArtistMapItem(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class RawArtistMap(
    @SerialName("primary_artists") val primaryArtists: List<RawArtistMapItem> = emptyList(),
)

@Serializable
data class RawMoreInfo(
    val album_id: String = "",
    val album: String = "",
    @SerialName("encrypted_media_url") val encryptedMediaUrl: String = "",
    val duration: String = "",
    /**
     * Whether a 320kbps rendition exists, as `"true"`/`"false"`.
     *
     * The catalogue states this per track and it is frequently false. Asking
     * the CDN for `_320` anyway does not produce one.
     */
    @SerialName("320kbps") val has320: String = "",
    val artistMap: RawArtistMap = RawArtistMap(),
) {
    val supports320: Boolean get() = has320.equals("true", ignoreCase = true)
}

/** A decoded CDN URL and the bitrate it will really deliver. */
data class SaavnStream(val url: String, val kbps: Int?)

/**
 * Select the highest JioSaavn CDN rendition without losing URL parameters.
 *
 * The encrypted URL normally names the 96 kbps file and the catalogue states
 * whether the parallel 320 kbps object exists. CDN URLs are not guaranteed to
 * end at `.mp4`: cache-busting or access query parameters may follow it. The
 * old end-anchored match missed those URLs, left `_96.mp4` in place, and still
 * reported 320 upstream. Playback and Downloads consequently displayed 320
 * while both fetched the 96 kbps object.
 */
fun selectBestSaavnStream(decryptedUrl: String, supports320: Boolean): SaavnStream? {
    if (decryptedUrl.isBlank()) return null
    val rendition = Regex(
        "_(48|96|160|320)\\.(mp4|aac|mp3)(?=[?#]|$)",
        RegexOption.IGNORE_CASE,
    ).find(decryptedUrl)
        // An unrecognised URL must not be labelled 320 merely because the
        // catalogue says that rendition exists; no rewrite was actually made.
        ?: return SaavnStream(decryptedUrl, null)

    val offered = rendition.groupValues[1].toInt()
    if (!supports320) return SaavnStream(decryptedUrl, offered)

    val extension = rendition.groupValues[2]
    return SaavnStream(
        decryptedUrl.replaceRange(rendition.range, "_320.$extension"),
        320,
    )
}

@Serializable
data class RawSongItem(
    val id: String = "",
    val title: String = "",
    val image: String = "",
    /** JioSaavn sends this as the string `"1"` or `"0"`. */
    @SerialName("explicit_content") val explicitContent: String = "",
    @SerialName("more_info") val moreInfo: RawMoreInfo = RawMoreInfo()
) {
    val isExplicit: Boolean
        get() = explicitContent == "1" || explicitContent.equals("true", ignoreCase = true)
}

@Serializable
data class RawSearchResponse(
    val results: List<RawSongItem> = emptyList()
)

@Serializable
data class RawSongsResponse(
    val songs: List<RawSongItem> = emptyList()
)

/**
 * Put uncensored catalogue rows ahead of their clean duplicates.
 *
 * Top-level so the pure ordering rule remains unit-testable without
 * initialising Android's Base64-backed service singleton on the JVM.
 */
fun prioritizeExplicit(songs: List<RawSongItem>): List<RawSongItem> =
    songs.sortedByDescending { it.isExplicit }

@OptIn(ExperimentalEncodingApi::class)
object JioSaavnService {
    private const val TAG = "Laya"

    // https://www.jiosaavn.com/api.php
    private val BASE_URL = Base64.decode("aHR0cHM6Ly93d3cuamlvc2Fhdm4uY29tL2FwaS5waHA=").decodeToString()

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val HEADERS = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "X-Forwarded-For" to "49.36.0.1",
        "X-Real-IP" to "49.36.0.1",
        "Accept-Language" to "en-IN,en;q=0.9",
        "Cookie" to "explicit_content=1",
    )

    /** One call of the api.php endpoint for [method]; the body, or null on a non-200. */
    private suspend fun call(method: String, vararg params: Pair<String, String>): String? {
        val url = URLBuilder(BASE_URL).apply {
            (listOf("__call" to method, "_format" to "json", "_marker" to "0", "api_version" to "4", "ctx" to "android") + params)
                .forEach { (k, v) -> parameters.append(k, v) }
        }.buildString()
        val response = withContext(ioDispatcher) {
            NetHttp.execute(HttpRequest(url, headers = HEADERS, callTimeoutMs = 6_000, connectTimeoutMs = 4_000))
        }
        if (response.code != 200) {
            TrackLog.w(TAG, "Saavn $method failed: HTTP ${response.code}")
            return null
        }
        return response.text()
    }

    private fun decryptUrl(encryptedUrl: String): String {
        if (encryptedUrl.isBlank()) return ""
        return try {
            // DES/ECB/PKCS5 with an 8-byte key
            desEcbDecrypt("38346591".encodeToByteArray(), Base64.Mime.decode(encryptedUrl)).decodeToString().trim()
        } catch (e: Exception) {
            TrackLog.e(TAG, "JioSaavn URL decryption failed", e)
            ""
        }
    }

    /**
     * The best CDN URL this track really has, and the bitrate it will deliver.
     *
     * The rewrite to `_320` is conditional on [RawMoreInfo.supports320] rather
     * than applied to everything: the CDN has no 320 rendition for a track that
     * hasn't got one, and a 96kbps stream advertised as 320 would clear
     * [SourceResolver.worthSwapping][com.music.laya.data.sources.SourceResolver]
     * against YouTube's 160kbps Opus.
     */
    private fun bestStream(encryptedUrl: String, supports320: Boolean): SaavnStream? =
        selectBestSaavnStream(decryptUrl(encryptedUrl), supports320)

    suspend fun searchSongs(query: String): List<RawSongItem> = runCatching {
        val body = call("search.getResults","q" to query, "p" to "1", "n" to "10")
            ?: return@runCatching emptyList()
        json.decodeFromString<RawSearchResponse>(body).results
    }.getOrElse {
        TrackLog.w(TAG, "Saavn search error: ${it.message}")
        emptyList()
    }

    suspend fun getStreamUrl(saavnSongId: String): SaavnStream? {
        val result = runCatching {
            val body = call("song.getDetails","pids" to saavnSongId) ?: return@runCatching null

            // `song.getDetails` answers with a map keyed by the id that was asked for
            // (`{"5xceLLNe":{…}}`), not the `{"songs":[…]}` envelope `search.getResults`
            // uses; both shapes are read so neither endpoint changing breaks the other.
            val root = json.parseToJsonElement(body) as? JsonObject
                ?: return@runCatching null
            val songElement = (root["songs"] as? JsonArray)?.firstOrNull()
                ?: root.values.firstOrNull { it is JsonObject }
                ?: run {
                    TrackLog.w(TAG, "Saavn getDetails held no song for $saavnSongId")
                    return@runCatching null
                }
            val rawSong = json.decodeFromJsonElement(RawSongItem.serializer(), songElement)

            bestStream(rawSong.moreInfo.encryptedMediaUrl, rawSong.moreInfo.supports320)
        }
        return result.onFailure { TrackLog.w(TAG, "Saavn getDetails error: ${it.message}") }.getOrNull()
    }
}
