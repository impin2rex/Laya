package com.music.laya.desktop

import com.music.laya.data.model.Song
import com.music.laya.data.sources.lossless.LosslessProvider
import com.music.laya.data.sources.lossless.LosslessStore
import com.music.laya.data.sources.lossless.ZarzSession
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.Base64

/**
 * Desktop side of the built-in Tidal/Qobuz sources: where their broker sessions are kept, the
 * browser hand-off that verifies them, and the stream/match calls the source registry makes.
 */
internal object DesktopLossless {

    private const val PREFIX = "lossless:"

    /** Sessions live beside the source list, sealed with DPAPI on Windows as that list is. */
    fun install(persistence: DesktopPersistence = DesktopPersistence()) {
        LosslessStore.read = { key ->
            persistence.string("lossless_$key").takeIf(String::isNotBlank)?.let { stored ->
                if (!DesktopPlatform.isWindows) stored
                else runCatching { Base64.getDecoder().decode(stored) }.getOrNull()
                    ?.let(DesktopWindowsCrypto::unprotect)?.toString(StandardCharsets.UTF_8)
            }
        }
        LosslessStore.write = { key, value ->
            val sealed = when {
                value == null -> ""
                !DesktopPlatform.isWindows -> value
                else -> DesktopWindowsCrypto.protect(value.toByteArray(StandardCharsets.UTF_8))
                    ?.let(Base64.getEncoder()::encodeToString).orEmpty()
            }
            persistence.saveString("lossless_$key", sealed)
        }
        LosslessStore.manifestFile = { name, mpd -> manifestFile(name, mpd) }
        LosslessProvider.all.forEach { it.session.reload() }
    }

    /** FFmpeg's DASH demuxer opens a local manifest; its segment URLs are absolute. */
    private fun manifestFile(name: String, mpd: String): String {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "laya-manifests").apply { mkdirs() }
        return java.io.File(dir, name).apply { writeText(mpd); deleteOnExit() }.absolutePath
    }

    fun provider(kind: DesktopSourceKind): LosslessProvider =
        if (kind == DesktopSourceKind.QOBUZ) LosslessProvider.Qobuz else LosslessProvider.Tidal

    fun verified(kind: DesktopSourceKind): Boolean = provider(kind).session.verified.value

    /** [song]'s copies in [kind]'s catalogue, most confident first; none while unverified. */
    suspend fun matches(kind: DesktopSourceKind, song: Song): List<Song> {
        val provider = provider(kind)
        if (!provider.session.verified.value || song.title.isBlank() || song.isVideo) return emptyList()
        for (query in DesktopTrackMatcher.queries(song)) {
            val candidates = runCatching { provider.search(query, 10) }
                .onFailure { DesktopTrackLog.log("${provider.label}: search '$query' failed: ${it.message}") }
                .getOrDefault(emptyList()).map { track ->
                Song(
                    videoId = "$PREFIX${provider.id}:${track.id}",
                    title = track.title,
                    artist = track.artist,
                    albumName = track.album,
                    thumbnailUrl = track.coverUrl,
                    durationText = track.durationSec?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
                    sourceQuality = "LOSSLESS",
                    isExplicit = track.explicit,
                )
            }
            DesktopTrackMatcher.ranked(candidates, song).ifEmpty { null }?.let { return it }
        }
        return emptyList()
    }

    suspend fun stream(config: DesktopSourceConfig, song: Song): Result<DesktopStream?> = runCatching {
        val provider = provider(config.kind)
        val id = song.videoId.removePrefix("$PREFIX${provider.id}:").takeIf { it != song.videoId } ?: return@runCatching null
        val stream = provider.stream(id) ?: run {
            DesktopTrackLog.log("${provider.label}: no lossless stream for track $id")
            return@runCatching null
        }
        DesktopTrackLog.log("${provider.label}: streaming ${stream.format.summary} for '${song.title}'")
        DesktopStream(
            url = stream.url,
            format = DesktopStreamFormat(
                codec = stream.format.codec,
                sampleRateHz = stream.format.sampleRateHz,
                bitDepth = stream.format.bitDepth,
            ),
            sourceId = config.id,
            transport = stream.transport,
        )
    }.onFailure {
        // Losing the race to another source cancels this; that is not a failure.
        if (it !is kotlinx.coroutines.CancellationException) DesktopTrackLog.log("${provider(config.kind).label}: stream failed: ${it.message}")
    }

    /**
     * Runs the captcha in the listener's own browser — Cloudflare's check never passes in JavaFX's
     * WebKit, nor in a browser Laya drives over DevTools. The zarz page only redirects to app
     * schemes, but a page it was opened from gets the grant by `postMessage` (`zarz_grant`). So
     * Laya serves a one-shot page on loopback that opens the captcha as a popup, receives that
     * message and hands the grant back here. Throws on timeout.
     */
    suspend fun verify(kind: DesktopSourceKind) {
        val provider = provider(kind)
        val grant = CompletableDeferred<String>()
        // Only the page this call served knows it; any other request to the port is ignored.
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val origin = "http://127.0.0.1:${server.address.port}"
        var challenge = ""
        server.createContext("/") { exchange ->
            val params = Url("$origin${exchange.requestURI.rawPath}?${exchange.requestURI.rawQuery.orEmpty()}").parameters
            val (status, html) = when {
                params["t"] != token -> 404 to "Not found"
                exchange.requestURI.path == "/grant" -> {
                    params["grant"]?.takeIf { it.startsWith("gr_") }?.let(grant::complete)
                    200 to "ok"
                }
                else -> 200 to verifyPage(provider.label, challenge, token)
            }
            val bytes = html.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            // A callback the gateway won't accept leaves the page on its postMessage path.
            challenge = provider.session.verificationUrl("$origin/session-grant")
            if (challenge.isEmpty()) return
            DesktopExternalLinks.open("$origin/?t=$token").getOrThrow()
            DesktopTrackLog.log("${provider.label}: verification opened in the browser; waiting for it to finish")
            val received = withTimeout(VERIFY_TIMEOUT_MS) { grant.await() }
            provider.session.completeGrant(received)
            DesktopTrackLog.log("${provider.label}: verified")
        } finally {
            server.stop(1)
        }
    }

    private fun verifyPage(label: String, challenge: String, token: String): String {
        val target = kotlinx.serialization.json.JsonPrimitive(challenge).toString()
        return """<!doctype html><html><head><meta charset="utf-8"><title>Verify $label · Laya</title>
<style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#111;color:#eee;font:16px system-ui,sans-serif}
main{text-align:center;max-width:420px;padding:24px}button{font:inherit;padding:12px 22px;border:0;border-radius:999px;background:#7ee08a;color:#000;cursor:pointer}
p{color:#aaa}</style></head><body><main><h1>Verify $label</h1>
<p id="s">A Cloudflare check opens in a small window. Complete it there and Laya finishes by itself.</p>
<button id="b">Open the check</button></main><script>
var s=document.getElementById('s'),b=document.getElementById('b'),done=false;
function openCheck(){ if(!window.open($target,'zarz','width=520,height=760')){ s.textContent='Your browser blocked the window. Press the button to open it.'; } }
b.onclick=openCheck; openCheck();
window.addEventListener('message',function(e){
  if(done||e.origin!=='https://api.zarz.moe'||!e.data||e.data.type!=='zarz_grant')return;
  done=true; b.hidden=true;
  fetch('/grant?t=$token&grant='+encodeURIComponent(e.data.grant)).then(function(){
    s.textContent='$label is verified. You can close this tab and return to Laya.';
  });
});
</script></body></html>"""
    }

    private const val VERIFY_TIMEOUT_MS = 10 * 60_000L
}
