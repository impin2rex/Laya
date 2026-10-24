package com.music.laya.data.innertube

import com.music.laya.platform.HttpRequest
import com.music.laya.platform.NetHttp

// ponytail: no NewPipe on iOS; InnerTubeX is the only resolver there
internal actual object StreamPlatform {
    actual fun init() {}

    actual val newPipeAvailable = false

    actual fun isLinkageError(e: Throwable) = false

    actual suspend fun signatureTimestamp(videoId: String): Int? = null

    actual fun permanentReason(e: Throwable): String? = null

    actual suspend fun extractStream(
        videoId: String,
        select: (List<Pair<Int, NewPipeAudio>>) -> NewPipeAudio?,
    ): StreamResolver.Stream = error("NewPipe is not available on iOS")

    // ponytail: NetHttp reads the whole ranged body (<= 1 MiB on googlevideo) instead of stopping at readBytes.
    actual fun <T> probeFetch(url: String, headers: Map<String, String>, readBytes: Long, onReply: (Int, String?, () -> Boolean) -> T): T {
        val r = NetHttp.execute(HttpRequest(url, headers = headers, callTimeoutMs = 6_000L))
        return onReply(r.code, r.header("Content-Type")) { r.body.size >= readBytes }
    }
}
