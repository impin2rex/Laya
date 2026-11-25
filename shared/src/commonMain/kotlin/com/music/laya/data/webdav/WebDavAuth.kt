package com.music.laya.data.webdav

import kotlin.concurrent.Volatile

/**
 * In-memory credentials for [com.music.laya.data.Http]'s interceptor.
 *
 * Held outside SharedPreferences so the network layer never touches prefs:
 * [com.music.laya.data.settings.AppSettings] publishes here on init and
 * on every WebDAV edit.
 */
object WebDavAuth {
    /** The saved server's origin as [originOf] spells it, or null when none is configured. */
    @Volatile var origin: String? = null
    @Volatile var authHeader: String? = null

    /** [origin] and [header] as WebDavConfig derives them from the saved server. */
    fun update(origin: String?, header: String?) {
        this.origin = origin
        authHeader = header
    }

    /**
     * Whether a request to this scheme, host and port is the saved server's.
     *
     * The whole origin, not the host alone: an add-on on the same NAS under another port, or the
     * same host over plain http, is a different server, and the Basic credential must not reach it.
     */
    fun shouldAuthorize(scheme: String, host: String, port: Int): Boolean {
        val expected = origin ?: return false
        val header = authHeader ?: return false
        if (header.isBlank()) return false
        return originOf(scheme, host, port) == expected
    }

    /** `scheme://host:port`, lowercased, with a missing port (-1) filled in from the scheme. */
    fun originOf(scheme: String, host: String, port: Int): String {
        val s = scheme.lowercase()
        val p = if (port >= 0) port else if (s == "http") 80 else 443
        return "$s://${host.lowercase()}:$p"
    }
}
