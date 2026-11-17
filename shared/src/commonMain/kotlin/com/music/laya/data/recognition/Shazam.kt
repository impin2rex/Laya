/*
 * Shazam's tag endpoint, as ArchiveTune (© Rukamori, GPL-3.0,
 * github.com/rukamori/ArchiveTune) and SongRec call it. This is Shazam's own
 * private API, not a published one: it can change or start refusing requests
 * without notice, which callers surface as a service error.
 */
package com.music.laya.data.recognition

import com.music.laya.platform.HttpRequest
import com.music.laya.platform.NetHttp
import com.music.laya.platform.currentTimeMillis
import com.music.laya.platform.ioDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random
import kotlin.uuid.Uuid

internal class ShazamException(val rateLimited: Boolean, message: String) : Exception(message)

internal object Shazam {
    private val json = Json { ignoreUnknownKeys = true }

    private val userAgents = listOf(
        "Dalvik/2.1.0 (Linux; U; Android 5.0.2; VS980 4G Build/LRX22G)",
        "Dalvik/1.6.0 (Linux; U; Android 4.4.2; SM-T210 Build/KOT49H)",
        "Dalvik/2.1.0 (Linux; U; Android 5.1.1; SM-P905V Build/LMY47X)",
        "Dalvik/2.1.0 (Linux; U; Android 6.0.1; SM-G920F Build/MMB29K)",
        "Dalvik/2.1.0 (Linux; U; Android 5.0; SM-G900F Build/LRX21T)",
    )
    private val timezones = listOf(
        "Europe/Paris", "Europe/London", "America/New_York", "America/Los_Angeles", "Asia/Tokyo", "Asia/Dubai",
    )

    /** The track [signature] is, or null when Shazam has no match. Throws on network/service failure. */
    suspend fun recognize(signature: ShazamSignature): RecognizedTrack? = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        val body = buildJsonObject {
            putJsonObject("geolocation") {
                put("altitude", Random.nextDouble() * 400 + 100)
                put("latitude", Random.nextDouble() * 180 - 90)
                put("longitude", Random.nextDouble() * 360 - 180)
            }
            putJsonObject("signature") {
                put("samplems", signature.sampleDurationMs)
                put("timestamp", now / 1000)
                put("uri", signature.uri)
            }
            put("timestamp", now / 1000)
            put("timezone", timezones.random())
        }
        val url = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/" +
            "${Uuid.random().toString().uppercase()}/${Uuid.random()}" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"
        val response = NetHttp.execute(
            HttpRequest(
                url = url,
                method = "POST",
                headers = mapOf("User-Agent" to userAgents.random(), "Content-Language" to "en_US"),
                body = body.toString().encodeToByteArray(),
                contentType = "application/json",
                callTimeoutMs = 20_000,
            ),
        )
        when {
            response.code == 404 -> null
            response.code == 429 -> throw ShazamException(rateLimited = true, "Shazam rate limit")
            !response.isSuccessful -> throw ShazamException(rateLimited = false, "Shazam HTTP ${response.code}")
            else -> parse(response.text(), now)
        }
    }

    internal fun parse(text: String, recognizedAt: Long): RecognizedTrack? {
        val track = json.parseToJsonElement(text).jsonObject["track"] as? JsonObject ?: return null
        val title = track.str("title") ?: return null
        val metadata = (track["sections"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("type") == "SONG" }
            ?.get("metadata")?.jsonArray.orEmpty()
            .mapNotNull { it as? JsonObject }
        fun meta(name: String) = metadata.firstOrNull { it.str("title") == name }?.str("text")
        val images = track["images"] as? JsonObject
        return RecognizedTrack(
            id = track.str("key") ?: "$title|${track.str("subtitle")}",
            title = title,
            artist = track.str("subtitle").orEmpty(),
            album = meta("Album"),
            artworkUrl = images?.str("coverarthq") ?: images?.str("coverart"),
            releaseYear = meta("Released"),
            genre = (track["genres"] as? JsonObject)?.str("primary"),
            isrc = track.str("isrc"),
            shazamUrl = track.str("url"),
            recognizedAt = recognizedAt,
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
}
