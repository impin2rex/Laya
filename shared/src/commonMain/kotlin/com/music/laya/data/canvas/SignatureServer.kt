package com.music.laya.data.canvas

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which address artist-signature lookups should go to: the listener's own
 * server when one is saved and up, otherwise the one this build ships with —
 * the same order Listen Together uses. [online] is what the setting shows.
 *
 * The built-in address is passed in, probed and handed back, but never
 * published: the app says only *that* it is down, never where it is.
 *
 * [check] is the platform's `GET /v1/health`.
 */
class SignatureServer(private val check: suspend (baseUrl: String) -> Boolean) {

    private val _online = MutableStateFlow<Boolean?>(null)

    /** Whether a server answered the last check; null before one, or with nothing configured. */
    val online: StateFlow<Boolean?> = _online.asStateFlow()

    private val lock = Mutex()

    /**
     * Probes [custom] (or [builtIn] when nothing is saved), falling back to
     * [builtIn] when [custom] is down. Returns the address lookups should use;
     * "" when neither is configured.
     */
    suspend fun refresh(custom: String, builtIn: String): String = lock.withLock {
        val primary = custom.ifBlank { builtIn }
        val active = when {
            primary.isBlank() -> null
            check(primary) -> primary
            custom.isNotBlank() && builtIn.isNotBlank() && builtIn != custom && check(builtIn) -> builtIn
            else -> null
        }
        _online.value = if (primary.isBlank()) null else active != null
        active ?: primary
    }
}
