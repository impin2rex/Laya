package com.music.laya.data.sources.lossless

import com.music.laya.data.TrackLog
import com.music.laya.platform.HttpRequest
import com.music.laya.platform.HttpResponse
import com.music.laya.platform.NetHttp
import com.music.laya.platform.ioDispatcher
import io.ktor.http.URLBuilder
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Where session records are kept. Each platform points these at its own
 * (preferably encrypted) store at startup; until then nothing persists.
 */
object LosslessStore {
    var read: (key: String) -> String? = { null }
    var write: (key: String, value: String?) -> Unit = { _, _ -> }

    /**
     * Saves a DASH manifest the provider handed over as text and returns a URL the player can
     * open it from, or null where the player can't play DASH (iOS) — the stream is passed over.
     */
    var manifestFile: (name: String, mpd: String) -> String? = { _, _ -> null }
}

/** The broker wants its one-time browser check (a captcha) before it will answer. */
class VerificationRequired(val url: String = "") : Exception("Verification required")

class ZarzException(message: String) : Exception(message)

/**
 * A signed session with the `api.zarz.moe` broker the SpotiFLAC / Mornye Tidal and Qobuz
 * extensions stream through (LGPL-3.0, github.com/mornyeapp/Mornye — `signed_session/`).
 *
 * The handshake: `/bootstrap` either hands back a session or a challenge URL; the challenge is a
 * captcha page that redirects to [CALLBACK] carrying a `grant`, which `/session/exchange` trades
 * for a session id and secret. Every call after that is HMAC-signed with a key that rolls every
 * [WINDOW_SECONDS]. The broker scopes a session to [appVersion], so each provider holds its own.
 */
class ZarzSession(val appVersion: String, private val userAgent: () -> String) {

    @Serializable
    private data class Record(
        @SerialName("install_id") val installId: String = randomHex(16),
        @SerialName("session_id") val sessionId: String = "",
        @SerialName("session_secret") val sessionSecret: String = "",
        @SerialName("expires_at") val expiresAt: String = "",
    ) {
        fun expiry(): Instant? = expiresAt.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
        fun usable(now: Instant) = sessionId.isNotBlank() && sessionSecret.isNotBlank() && (expiry()?.let { now < it } ?: true)
        fun cleared() = copy(sessionId = "", sessionSecret = "", expiresAt = "")
    }

    private val key = "zarz_session_" + appVersion.substringBefore('@')
    private val mutex = Mutex()
    private var record: Record = LosslessStore.read(key)
        ?.let { runCatching { json.decodeFromString(Record.serializer(), it) }.getOrNull() }
        ?: Record()
    private val _verified = MutableStateFlow(record.usable(Clock.System.now()))

    /** Whether a usable session is held, for the sources screens. */
    val verified: StateFlow<Boolean> get() = _verified

    private val _expiresAt = MutableStateFlow(record.expiry()?.takeIf { _verified.value }?.toEpochMilliseconds())
    /**
     * When the session held ends, as the broker stated it (epoch ms), or null when none is held or it states no end.
     * Moves later when the session is renewed, which happens by itself during its last hour of use.
     */
    val expiresAtMs: StateFlow<Long?> get() = _expiresAt

    private val _expired = MutableStateFlow(false)
    /** The last session ran out or was revoked (not [forget]); cleared once verified again or switched by hand. */
    val expired: StateFlow<Boolean> get() = _expired

    private val _lost = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /**
     * Fires when a session the listener held is lost: past its end, or refused by the broker. Each platform turns
     * the source off and says so once. Not replayed (an old loss must not be reported twice); a listener that
     * starts after one, such as a session found expired at launch, reads [expired] — see
     * [com.music.laya.data.sources.LosslessSource.lossesOf].
     */
    val lost: SharedFlow<Unit> get() = _lost

    /**
     * Reports a session that has passed its end as lost without waiting for the next request: a capped quality never
     * makes one, and the sources screen would go on saying "Verified" for a session that no longer works.
     */
    fun check() {
        if (!record.usable(Clock.System.now())) lose(record)
    }

    /**
     * Renews a session in its last hour (no captcha), then [check]s it. Called by the expiry watcher, so a session
     * is extended even when nothing streams through it; requests alone would leave an idle one to run out.
     */
    suspend fun renewOrCheck() {
        refreshIfDue()
        check()
    }

    /** Drops [stale] (if still the one held) and reports it lost. */
    private fun lose(stale: Record) {
        if (record != stale || stale.sessionId.isBlank()) return
        save(stale.cleared())
        _expired.value = true
        _lost.tryEmit(Unit)
    }

    /** Re-reads the store; platforms call this once [LosslessStore] is wired, in case it was read before. */
    fun reload() {
        record = LosslessStore.read(key)
            ?.let { runCatching { json.decodeFromString(Record.serializer(), it) }.getOrNull() }
            ?: record
        _verified.value = record.usable(Clock.System.now())
        _expiresAt.value = record.expiry()?.takeIf { _verified.value }?.toEpochMilliseconds()
        if (!_verified.value) lose(record)
    }

    private fun save(next: Record) {
        record = next
        LosslessStore.write(key, json.encodeToString(Record.serializer(), next))
        _verified.value = next.usable(Clock.System.now())
        _expiresAt.value = next.expiry()?.takeIf { _verified.value }?.toEpochMilliseconds()
        if (_verified.value) _expired.value = false
    }

    /** Drops the session by hand (the switch): no [lost] report, and no longer [expired]. */
    fun forget() {
        _expired.value = false
        save(record.cleared())
    }

    /**
     * The captcha page to show, or "" when the broker granted a session outright. Bootstrap is
     * unsigned; the `state` it carries is echoed back on the callback and is not checked here — the
     * grant is only ever read from a page this app opened itself.
     */
    /**
     * [callback] is where the page sends its grant: the default is caught inside an in-app WebView;
     * desktop passes a loopback URL so the check can run in the real browser, which the captcha
     * accepts where an embedded old WebKit does not.
     */
    suspend fun verificationUrl(callback: String = CALLBACK): String = mutex.withLock {
        val response = send(
            "GET",
            "$BASE/bootstrap?app_version=${appVersion.encodeURLParameter()}&install_id=${record.installId}",
            null,
            emptyMap(),
        )
        if (response.code !in 200..299) throw ZarzException("Verification service returned HTTP ${response.code}")
        val body = parse(response)
        val session = body.str("session_id")
        if (session.isNotBlank() && body.str("session_secret").isNotBlank()) {
            save(record.copy(sessionId = session, sessionSecret = body.str("session_secret"), expiresAt = body.str("expires_at")))
            return ""
        }
        val url = body.str("auth_url").ifBlank { body.str("challenge_url") }.ifBlank {
            body.str("challenge_id").takeIf { it.isNotBlank() }?.let {
                "$BASE/challenge?id=${it.encodeURLParameter()}&cb=${"$CALLBACK?cb_version=v2grant".encodeURLParameter()}"
            }
        } ?: throw ZarzException("Verification service returned neither a session nor a challenge")
        if (callback == CALLBACK) return url
        // As Mornye-Python's desktop flow: the challenge page with `cb` replaced outright.
        val builder = URLBuilder(body.str("challenge_url").ifBlank { url })
        builder.parameters["cb"] = callback
        builder.buildString()
    }

    /** Trades the grant from the captcha callback for a session. */
    suspend fun completeGrant(grant: String) = mutex.withLock {
        val body = buildJsonObject {
            put("grant", grant)
            put("install_id", record.installId)
            put("app_version", appVersion)
            put("platform", PLATFORM)
        }.toString()
        val response = send("POST", "$BASE/session/exchange", body, mapOf("Content-Type" to "application/json"))
        if (response.code !in 200..299) throw ZarzException("Verification was not accepted (HTTP ${response.code})")
        val fields = parse(response)
        if (fields.str("session_id").isBlank() || fields.str("session_secret").isBlank()) {
            throw ZarzException("Verification response held no session")
        }
        save(record.copy(sessionId = fields.str("session_id"), sessionSecret = fields.str("session_secret"), expiresAt = fields.str("expires_at")))
        TrackLog.d(TAG, "$appVersion: verified")
    }

    /**
     * A signed POST to [path] under [BASE]. Throws [VerificationRequired] when the broker wants
     * the captcha again — the session is dropped then, so the sources screen shows "Verify".
     */
    suspend fun post(path: String, body: JsonObject, headers: Map<String, String> = emptyMap()): JsonObject =
        call("POST", path, body.toString(), headers)

    /** A signed GET; [path] may carry a query, which (as in Mornye) is not part of the signature. */
    suspend fun get(path: String): JsonObject = call("GET", path, null, emptyMap())

    private suspend fun call(method: String, path: String, text: String?, headers: Map<String, String>): JsonObject {
        refreshIfDue()
        var providerRetries = 0
        while (true) {
            val current = record
            if (!current.usable(Clock.System.now())) {
                mutex.withLock { lose(current) }
                throw VerificationRequired()
            }
            val signed = signedHeaders(
                current, method, "/v2" + path.substringBefore('?'), text.orEmpty(),
                Clock.System.now().toEpochMilliseconds(), randomHex(12),
            )
            val json = if (text != null) mapOf("Content-Type" to "application/json") else emptyMap()
            val response = send(method, BASE + path, text, signed + json + headers)
            val error = runCatching { parse(response) }.getOrNull()
            val code = error?.str("code")?.uppercase().orEmpty()
            val origin = error?.str("origin")?.lowercase().orEmpty()
            when {
                response.code in 200..299 -> return parse(response)
                response.code == 503 && origin == "provider" && code == "PROVIDER_UNAVAILABLE" && providerRetries < 2 -> {
                    providerRetries++
                    delay(PROVIDER_RETRY_MS)
                }
                (response.code == 401 && code == "SESSION_INVALID") || (response.code == 428 && code == "VERIFY_REQUIRED") -> {
                    mutex.withLock { lose(current) }
                    throw VerificationRequired()
                }
                else -> throw ZarzException(
                    "HTTP ${response.code} for $path" + (error?.str("error")?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()),
                )
            }
        }
    }

    /** Mornye refreshes within the last hour of a session's life; a failure leaves the old one in use. */
    private suspend fun refreshIfDue() = mutex.withLock {
        val now = Clock.System.now()
        val expiry = record.expiry() ?: return@withLock
        if (!record.usable(now) || expiry - now > 1.hours) return@withLock
        val text = buildJsonObject { put("install_id", record.installId) }.toString()
        runCatching {
            val signed = signedHeaders(record, "POST", "/v2/session/refresh", text, now.toEpochMilliseconds(), randomHex(12))
            val response = send("POST", "$BASE/session/refresh", text, signed + ("Content-Type" to "application/json"))
            if (response.code in 200..299) {
                val fields = parse(response)
                save(
                    record.copy(
                        sessionId = fields.str("session_id").ifBlank { record.sessionId },
                        sessionSecret = fields.str("session_secret").ifBlank { record.sessionSecret },
                        expiresAt = fields.str("expires_at").ifBlank { record.expiresAt },
                    ),
                )
                TrackLog.d(TAG, "$appVersion: session renewed until ${record.expiresAt}")
            } else {
                TrackLog.w(TAG, "$appVersion: session refresh returned HTTP ${response.code}")
            }
        }.onFailure { TrackLog.w(TAG, "$appVersion: session refresh failed: ${it.message}") }
    }

    private suspend fun send(method: String, url: String, body: String?, headers: Map<String, String>): HttpResponse =
        withContext(ioDispatcher) {
            NetHttp.execute(
                HttpRequest(
                    url = url,
                    method = method,
                    headers = mapOf("Accept" to "application/json", "User-Agent" to userAgent()) + headers,
                    body = body?.encodeToByteArray(),
                    contentType = if (body != null) "application/json" else null,
                    callTimeoutMs = CALL_TIMEOUT_MS,
                ),
            )
        }

    private fun parse(response: HttpResponse): JsonObject = json.parseToJsonElement(response.text()).jsonObject

    private fun JsonObject.str(name: String): String = this[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()

    private fun signedHeaders(record: Record, method: String, path: String, body: String, nowMs: Long, nonce: String): Map<String, String> =
        signedHeaders(record.sessionId, record.sessionSecret, appVersion, method, path, body, nowMs, nonce)

    companion object {
        const val BASE = "https://api.zarz.moe/v2"
        /** Where the captcha page sends its grant. Never opened by the OS: the WebView catches it. */
        const val CALLBACK = "mornye://session-grant"
        private const val SCHEME = "ZARZ-HMAC-V1"
        private const val PREFIX = "X-Zarz-"
        private const val PLATFORM = "extension"
        private const val WINDOW_SECONDS = 300L
        private const val CALL_TIMEOUT_MS = 30_000L
        private const val PROVIDER_RETRY_MS = 1_500L
        private const val TAG = "Laya"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** The `grant` on a captcha callback, or null when [url] is not one. */
        fun grantFrom(url: String, callback: String = CALLBACK): String? {
            if (!url.startsWith(callback)) return null
            return url.substringAfter('?', "").split('&')
                .firstOrNull { it.startsWith("grant=") }
                ?.removePrefix("grant=")
                ?.decodeURLQueryComponent()
                ?.takeIf { it.isNotBlank() }
        }

        /** Hex SHA-256, as the broker hashes ticket resources and bodies. */
        fun sha256Hex(text: String): String = text.encodeUtf8().sha256().hex()

        /** Ported from Mornye's `protocol::signed_headers`; pure so it can be checked against fixed inputs. */
        fun signedHeaders(
            sessionId: String,
            sessionSecret: String,
            appVersion: String,
            method: String,
            path: String,
            body: String,
            nowMs: Long,
            nonce: String,
        ): Map<String, String> {
            val iso = Instant.fromEpochMilliseconds(nowMs).toString()
            val timestamp = iso.substringBefore('.').removeSuffix("Z") + "." + (nowMs % 1000).toString().padStart(3, '0') + "Z"
            val hash = sha256Hex(body)
            val window = (nowMs / 1000) / WINDOW_SECONDS
            val rolling = hmac(sessionSecret, "$window:$sessionId")
            val signing = listOf(SCHEME, method, path, "", hash, timestamp, nonce, sessionId, appVersion, PLATFORM).joinToString("\n")
            return mapOf(
                "Session" to sessionId,
                "Timestamp" to timestamp,
                "Nonce" to nonce,
                "Body-SHA256" to hash,
                "Signature" to hmac(rolling, signing),
                "App-Version" to appVersion,
                "Platform" to PLATFORM,
            ).mapKeys { PREFIX + it.key }
        }

        private fun hmac(key: String, message: String): String =
            message.encodeUtf8().hmacSha256(key.encodeUtf8()).base64Url().trimEnd('=')

        private fun randomHex(bytes: Int): String =
            (Uuid.random().toHexString() + Uuid.random().toHexString()).take(bytes * 2)
    }
}
