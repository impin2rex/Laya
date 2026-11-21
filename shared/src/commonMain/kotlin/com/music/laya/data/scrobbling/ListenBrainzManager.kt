package com.music.laya.data.scrobbling

import com.music.laya.data.DebugLog as Log
import com.music.laya.data.model.Song
import com.music.laya.platform.currentTimeMillis
import com.music.laya.platform.ioDispatcher
import com.music.laya.platform.ktorClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object ListenBrainzManager {
    private const val TAG = "ListenBrainzManager"
    private const val API_URL = "https://api.listenbrainz.org/1/submit-listens"

    private val client by lazy { ktorClient { expectSuccess = false } }

    suspend fun submitPlayingNow(
        token: String,
        song: Song?,
        positionMs: Long,
        durationMsOverride: Long? = null,
        primaryArtistOnly: Boolean = false,
    ): Boolean {
        if (token.isBlank() || song == null) return false
        val durationMs = durationMsOverride ?: parseDurationMs(song.durationText)
        val body = buildJsonObject {
            put("listen_type", "playing_now")
            putJsonArray("payload") {
                addJsonObject {
                    putJsonObject("track_metadata") {
                        metadata(song, primaryArtistOnly) {
                            // The API rejects a zero/negative duration_ms, and it is
                            // optional — so only send it when it is actually known.
                            if (durationMs > 0) put("duration_ms", durationMs)
                            put("position_ms", positionMs)
                            put("submission_client", "Laya")
                        }
                    }
                }
            }
        }
        return submit("playing_now", song, body.toString(), token)
    }

    suspend fun submitFinished(
        token: String,
        song: Song?,
        startMs: Long,
        endMs: Long,
        durationMsOverride: Long? = null,
        primaryArtistOnly: Boolean = false,
    ): Boolean {
        if (token.isBlank() || song == null) return false
        val durationMs = durationMsOverride ?: parseDurationMs(song.durationText)
        var listenedAtStart = startMs / 1000L
        val minListenTs = 1033430400L
        if (listenedAtStart < minListenTs) {
            listenedAtStart = currentTimeMillis() / 1000L
        }
        val body = buildJsonObject {
            put("listen_type", "single")
            putJsonArray("payload") {
                addJsonObject {
                    put("listened_at", listenedAtStart)
                    putJsonObject("track_metadata") {
                        metadata(song, primaryArtistOnly) {
                            if (durationMs > 0) put("duration_ms", durationMs)
                            put("start_ms", startMs)
                            put("end_ms", endMs)
                            put("submission_client", "Laya")
                        }
                    }
                }
            }
        }
        return submit("finished listen", song, body.toString(), token)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.metadata(
        song: Song,
        primaryArtistOnly: Boolean,
        additionalInfo: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) {
        put("artist_name", if (primaryArtistOnly) song.artist.primaryArtist() else song.artist)
        put("track_name", song.title)
        song.albumName?.takeIf { it.isNotBlank() }?.let { put("release_name", it) }
        putJsonObject("additional_info", additionalInfo)
    }

    private suspend fun submit(what: String, song: Song, bodyJson: String, token: String): Boolean =
        withContext(ioDispatcher) {
            try {
                Log.d(TAG, "$what: $bodyJson")
                val response = client.post(API_URL) {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Token $token")
                    setBody(bodyJson)
                }
                if (response.status.isSuccess()) {
                    Log.d(TAG, "$what submitted for ${song.title}")
                    true
                } else {
                    val bodyText = try { response.bodyAsText() } catch (_: Exception) { "" }
                    Log.w(TAG, "$what submit failed: ${response.status.value} - $bodyText")
                    false
                }
            } catch (ex: Exception) {
                Log.e(TAG, "$what submit failed", ex)
                false
            }
        }

    private fun parseDurationMs(text: String?): Long {
        if (text == null) return 0L
        val parts = text.split(":")
        if (parts.size != 2) return 0L
        val minutes = parts[0].toLongOrNull() ?: return 0L
        val seconds = parts[1].toLongOrNull() ?: return 0L
        return (minutes * 60 + seconds) * 1000
    }
}
