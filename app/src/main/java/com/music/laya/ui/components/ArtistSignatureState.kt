package com.music.laya.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.music.laya.data.canvas.ArtistSignature
import com.music.laya.data.canvas.ArtistSignatureRepository
import com.music.laya.data.settings.AppSettings
import kotlinx.coroutines.delay

/**
 * The signature to draw for [name] now, or null — the artist header's lookup, for a surface that
 * has no Apple title logo to prefer it over.
 *
 * Waits for [ready] (the name is settled), re-runs when the toggle, the address or
 * [ArtistSignatureRepository.updates] change, keeps the last signature found through a miss, and
 * retries a miss with backoff: a sleeping self-hosted API needs ~30-60s to wake (Render's free
 * tier), and one miss would otherwise stand until the page is reopened. Genuine misses are not
 * cached, so retrying is cheap.
 */
@Composable
fun rememberArtistSignature(name: String, ready: Boolean): ArtistSignature? {
    val enabled by AppSettings.signatureApiEnabled.collectAsState()
    val baseUrl by AppSettings.signatureApiUrl.collectAsState()
    val updates by ArtistSignatureRepository.updates.collectAsState()
    var signature by remember(name) { mutableStateOf<ArtistSignature?>(null) }
    LaunchedEffect(name, ready, enabled, baseUrl, updates) {
        if (!ready) return@LaunchedEffect
        val base = ArtistSignatureRepository.effectiveBaseUrl(enabled, baseUrl)
        if (base == null) {
            signature = null
            return@LaunchedEffect
        }
        for (wait in listOf(0L, 5_000L, 15_000L, 30_000L)) {
            if (wait > 0) delay(wait)
            signature = ArtistSignatureRepository.cached(name, base)
                ?: ArtistSignatureRepository.signatureFor(name, base)
                ?: signature
            if (signature != null) return@LaunchedEffect
        }
    }
    return signature
}
