package com.music.laya.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess

/**
 * Keeps a captured Google sign-in alive the way a browser does.
 *
 * Google rotates the short-lived halves of a session (`__Secure-1PSIDTS`, `__Secure-3PSIDTS` and their
 * kin): an open browser tab posts to `RotateCookies` every few minutes and stores what comes back. A jar
 * captured once and never rotated stops being accepted after a while, and music.youtube.com then serves
 * the signed-out shell, which is how the desktop app kept dropping to "Sign in" with the account's photo
 * still on the toolbar. Rotating at launch and on a timer, and saving the result, keeps the jar current.
 */
internal object DesktopCookieRotation {
    private const val ENDPOINT = "https://accounts.youtube.com/RotateCookies"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    /** How often a running app rotates; browsers do it about this often. */
    const val INTERVAL_MS = 10 * 60_000L

    private val client = HttpClient(CIO) { followRedirects = false }

    /**
     * [cookie] with whatever Google rotated merged in, or null when the rotation was refused or couldn't be
     * made (the caller then keeps what it has and lets the sign-in check decide). Never throws.
     */
    suspend fun rotate(cookie: String): String? = runCatching {
        val response = client.post(ENDPOINT) {
            header("Cookie", cookie)
            header("Origin", "https://accounts.youtube.com")
            header("Content-Type", "application/json")
            header("User-Agent", USER_AGENT)
            setBody("""[000,"-0000000000000000000"]""")
        }
        if (!response.status.isSuccess()) {
            DesktopTrackLog.log("youtube: cookie rotation refused (HTTP ${response.status.value})")
            return null
        }
        val updates = response.headers.getAll("Set-Cookie").orEmpty()
        merge(cookie, updates).also { merged ->
            if (merged != cookie) DesktopTrackLog.log("youtube: rotated ${changedNames(cookie, merged).joinToString()}")
        }
    }.getOrElse {
        DesktopTrackLog.log("youtube: cookie rotation failed: ${it.message}")
        null
    }

    /**
     * [jar] (a `Cookie` header) with [setCookies] applied: a cookie that reaches music.youtube.com (a
     * `Domain` of youtube.com) takes its new value, and one being deleted (`Max-Age=0`, an empty value) goes.
     * A host-only cookie of accounts.youtube.com is left out: music.youtube.com would never be sent it.
     */
    fun merge(jar: String, setCookies: List<String>): String {
        val cookies = LinkedHashMap<String, String>()
        jar.split(';').map { it.trim() }.filter { '=' in it }.forEach { cookies[it.substringBefore('=')] = it.substringAfter('=') }
        for (line in setCookies) {
            val parts = line.split(';').map { it.trim() }
            val pair = parts.firstOrNull()?.takeIf { '=' in it } ?: continue
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=').trim()
            val attributes = parts.drop(1).associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=', "").trim() }
            val domain = attributes["domain"]?.trimStart('.')?.lowercase() ?: continue
            if (domain != "youtube.com") continue
            if (value.isEmpty() || attributes["max-age"]?.toLongOrNull()?.let { it <= 0 } == true) cookies.remove(name) else cookies[name] = value
        }
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** Names only: values are credentials and stay out of the log. */
    private fun changedNames(before: String, after: String): List<String> {
        fun parse(jar: String) = jar.split(';').map { it.trim() }.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val old = parse(before)
        val new = parse(after)
        return (new.keys + old.keys).distinct().filter { old[it] != new[it] }
    }
}
