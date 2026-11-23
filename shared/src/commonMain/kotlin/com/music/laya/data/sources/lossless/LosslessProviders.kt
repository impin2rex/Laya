package com.music.laya.data.sources.lossless

import com.music.laya.data.TrackLog
import com.music.laya.data.sources.StreamFormat
import com.music.laya.platform.HttpRequest
import com.music.laya.platform.NetHttp
import com.music.laya.platform.ioDispatcher
import io.ktor.http.encodeURLParameter
import kotlin.io.encoding.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** One catalogue row from a lossless provider, before a platform turns it into its own Song. */
data class LosslessTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSec: Int?,
    val coverUrl: String?,
    val explicit: Boolean,
)

/** [transport] is `dash` when [url] is a manifest rather than the file itself. */
data class LosslessStream(val url: String, val format: StreamFormat, val transport: String? = null)

/**
 * The built-in Tidal and Qobuz sources: catalogue search against each service's own public web
 * API, streams through the broker the SpotiFLAC extensions use — see [ZarzSession]. Logic ported
 * from SpotiFLAC-Extension `tidal-web` and `qobuz-web` (LGPL-3.0).
 */
sealed class LosslessProvider(val id: String, val label: String, appVersion: String) {
    val session = ZarzSession(appVersion) { userAgent }

    abstract suspend fun search(query: String, limit: Int): List<LosslessTrack>

    /** Null when the provider has no lossless copy; throws [VerificationRequired] when unverified. */
    abstract suspend fun stream(trackId: String): LosslessStream?

    /** One-use ticket the broker wants before a stream call, bound to the track being asked for. */
    protected suspend fun ticket(provider: String, resource: String): String {
        val payload = session.post(
            "/tickets",
            buildJsonObject {
                put("capability", "download_ticket")
                put("provider", provider)
                put("resource_hash", ZarzSession.sha256Hex("$provider:track:${resource.lowercase()}"))
            },
        )
        return (payload.str("ticket_id").ifBlank { payload.str("ticket") }).ifBlank {
            throw ZarzException("Ticket response held no ticket")
        }
    }

    data object Tidal : LosslessProvider("tidal", "Tidal", "tidal-web@1.2.5") {
        private const val API = "https://tidal.com/v1"
        private const val CLIENT_ID = "txNoH4kkV41MfH25"
        private const val CLIENT_KEY = "dQjy0MinCEvxi1O4UmxvxWnDjt4cgHBPw8ll6nYBk98="
        private val tokenLock = Mutex()
        private var token = ""
        private var tokenExpires = Instant.DISTANT_PAST

        /** Tidal's signed-out web session: search answers nothing else since 2026-11-20. */
        private suspend fun accessToken(): String = tokenLock.withLock {
            if (token.isNotBlank() && Clock.System.now() < tokenExpires) return token
            val response = http(
                "https://auth.tidal.com/v1/oauth2/token",
                method = "POST",
                body = "client_id=$CLIENT_ID&client_secret=${CLIENT_KEY.encodeURLParameter()}&grant_type=client_credentials",
                contentType = "application/x-www-form-urlencoded",
            )
            val body = response.second?.jsonObject ?: throw ZarzException("Tidal token request failed (HTTP ${response.first})")
            token = body.str("access_token").ifBlank { throw ZarzException("Tidal gave no access token") }
            val lifetime = body["expires_in"]?.jsonPrimitive?.intOrNull ?: 3600
            tokenExpires = Clock.System.now() + (lifetime - 60).coerceAtLeast(60).seconds
            token
        }

        override suspend fun search(query: String, limit: Int): List<LosslessTrack> {
            val url = "$API/search/tracks?query=${query.encodeURLParameter()}&limit=$limit&offset=0" +
                "&countryCode=US&locale=en_US&deviceType=BROWSER"
            val (code, body) = http(url, headers = mapOf("Authorization" to "Bearer ${accessToken()}"))
            if (code == 401) token = ""
            val items = body?.jsonObject?.get("items")?.jsonArray ?: throw ZarzException("Tidal search failed (HTTP $code)")
            return items.mapNotNull { element ->
                val track = element.jsonObject
                if (track["streamReady"]?.jsonPrimitive?.contentOrNull == "false") return@mapNotNull null
                val version = track.str("version")
                LosslessTrack(
                    id = track.str("id").ifBlank { return@mapNotNull null },
                    title = track.str("title") + if (version.isNotBlank()) " ($version)" else "",
                    artist = track["artists"]?.jsonArray?.joinToString(", ") { it.jsonObject.str("name") }
                        ?.ifBlank { null } ?: track["artist"]?.jsonObject?.str("name").orEmpty(),
                    album = track["album"]?.jsonObject?.str("title")?.ifBlank { null },
                    durationSec = track["duration"]?.jsonPrimitive?.intOrNull,
                    coverUrl = track["album"]?.jsonObject?.str("cover")?.takeIf { it.isNotBlank() }
                        ?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/640x640.jpg" },
                    explicit = track["explicit"]?.jsonPrimitive?.contentOrNull == "true",
                )
            }
        }

        // LOSSLESS (16/44.1). The broker answers with a plain FLAC URL or, more often now, a DASH
        // manifest of FLAC segments, which is saved where the player can open it.
        override suspend fun stream(trackId: String): LosslessStream? {
            val ticket = ticket("tid", trackId)
            val payload = session.post(
                "/dl/tid",
                buildJsonObject {
                    put("id", trackId)
                    put("quality", "LOSSLESS")
                },
                mapOf("X-Zarz-Ticket" to ticket),
            )
            val data = payload["data"] as? JsonObject ?: return null
            if (data.str("assetPresentation").equals("PREVIEW", ignoreCase = true)) return null
            val manifest = Base64.decode(data.str("manifest").ifBlank { return null }).decodeToString()
            val format = StreamFormat(
                codec = "flac",
                sampleRateHz = data["sampleRate"]?.jsonPrimitive?.intOrNull,
                bitDepth = data["bitDepth"]?.jsonPrimitive?.intOrNull,
            )
            if (!manifest.trimStart().startsWith("{")) {
                val file = LosslessStore.manifestFile("tidal-$trackId.mpd", manifest) ?: run {
                    TrackLog.w(TAG, "Tidal: $trackId is a DASH manifest, which this player can't open; skipping")
                    return null
                }
                // iOS's manifestFile rewrites the DASH manifest into an HLS playlist of the same
                // segments and hands back a local .m3u8 it can already open directly — no transport
                // flag to ask the host about. Android/Desktop still save the raw .mpd for their own
                // DASH engines, which do need acceptTransport asked before it is opened.
                val transport = if (file.endsWith(".m3u8", ignoreCase = true)) null else "dash"
                return LosslessStream(url = file, format = format, transport = transport)
            }
            val bts = json.parseToJsonElement(manifest).jsonObject
            val url = bts["urls"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: return null
            val mime = bts.str("mimeType").lowercase()
            return LosslessStream(
                url = url,
                format = if ("flac" in mime || mime.isBlank()) format else format.copy(codec = mime.substringAfter('/')),
            )
        }
    }

    data object Qobuz : LosslessProvider("qobuz", "Qobuz", "qobuz-web@1.2.21") {
        private const val WIDGET_APP_ID = "735532640"
        private const val APP_ID = "798273057"

        /**
         * The broker's signed Qobuz catalogue first, as qobuz-web does; Qobuz's own public API, which
         * answers this app id with nothing for most queries, only when that fails.
         */
        override suspend fun search(query: String, limit: Int): List<LosslessTrack> {
            val params = "query=${query.encodeURLParameter()}&limit=$limit&offset=0"
            val body = runCatching { session.get("/qbz/track/search?$params&app_id=$APP_ID") }
                .onFailure { if (it is VerificationRequired) throw it; TrackLog.w(TAG, "Qobuz: signed search failed: ${it.message}") }
                .getOrNull()
                ?: http("https://www.qobuz.com/api.json/0.2/track/search?$params", headers = mapOf("X-App-Id" to WIDGET_APP_ID)).second?.jsonObject
            val items = body?.get("tracks")?.jsonObject?.get("items")?.jsonArray
                ?: throw ZarzException("Qobuz search returned no track list")
            return items.mapNotNull { element ->
                val track = element.jsonObject
                if (track["streamable"]?.jsonPrimitive?.contentOrNull == "false") return@mapNotNull null
                val version = track.str("version")
                val album = track["album"] as? JsonObject
                LosslessTrack(
                    id = track.str("id").ifBlank { return@mapNotNull null },
                    title = track.str("title") + if (version.isNotBlank()) " ($version)" else "",
                    artist = (track["performer"] as? JsonObject)?.str("name")?.ifBlank { null }
                        ?: (album?.get("artist") as? JsonObject)?.str("name").orEmpty(),
                    album = album?.str("title")?.ifBlank { null },
                    durationSec = track["duration"]?.jsonPrimitive?.intOrNull,
                    coverUrl = (album?.get("image") as? JsonObject)?.str("large")?.ifBlank { null },
                    explicit = track["parental_warning"]?.jsonPrimitive?.contentOrNull == "true",
                )
            }
        }

        override suspend fun stream(trackId: String): LosslessStream? {
            val trackUrl = "https://open.qobuz.com/track/$trackId"
            // The broker hashes the ticket on `url`, not the id — see qobuz-web's fetchProviderDownloadInfo.
            val ticket = ticket("qbz", trackUrl)
            val payload = session.post(
                "/dl/qbz",
                buildJsonObject {
                    put("quality", "hi-res-max")
                    put("upload_to_r2", false)
                    put("id", trackId)
                    put("type", "track")
                    put("url", trackUrl)
                },
                mapOf("X-Zarz-Ticket" to ticket),
            )
            val nested = payload["data"] as? JsonObject
            val url = listOf(payload, nested).firstNotNullOfOrNull { obj ->
                obj?.let { o -> listOf("download_url", "url", "link").map { o.str(it) }.firstOrNull(String::isNotBlank) }
            } ?: return null
            val rate = (payload["sampling_rate"] ?: nested?.get("sampling_rate"))?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            return LosslessStream(
                url = url,
                format = StreamFormat(
                    codec = "flac",
                    bitDepth = (payload["bit_depth"] ?: nested?.get("bit_depth"))?.jsonPrimitive?.intOrNull,
                    sampleRateHz = rate?.let { if (it < 1000) (it * 1000).toInt() else it.toInt() },
                ),
            )
        }
    }

    companion object {
        private const val TAG = "Laya"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Sent on every broker and catalogue call. Matches what Mornye sends to api.zarz.moe
         * (`Mornye/{AppInfo.version}`, no `v`, no platform); bump with Mornye's releases.
         * Set at build time from LOSSLESS_USER_AGENT (local.properties or the environment).
         */
        const val userAgent: String = LOSSLESS_USER_AGENT

        val all: List<LosslessProvider> get() = listOf(Tidal, Qobuz)

        fun byId(id: String): LosslessProvider? = all.firstOrNull { it.id == id }

        private fun JsonObject.str(name: String): String =
            (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()

        private suspend fun http(
            url: String,
            method: String = "GET",
            headers: Map<String, String> = emptyMap(),
            body: String? = null,
            contentType: String? = null,
        ): Pair<Int, JsonElement?> = withContext(ioDispatcher) {
            val response = NetHttp.execute(
                HttpRequest(
                    url = url,
                    method = method,
                    headers = mapOf("Accept" to "application/json", "User-Agent" to userAgent) + headers,
                    body = body?.encodeToByteArray(),
                    contentType = contentType,
                    callTimeoutMs = 15_000L,
                ),
            )
            response.code to if (response.isSuccessful) runCatching { json.parseToJsonElement(response.text()) }.getOrNull() else null
        }
    }
}
