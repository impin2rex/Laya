package com.music.laya.ios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.laya.data.YtMusicRepository
import com.music.laya.data.innertube.StreamResolver
import com.music.laya.data.model.SearchFilter
import com.music.laya.data.model.SearchResult
import com.music.laya.data.model.Song
import com.music.laya.platform.ioDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSProcessInfo

// ponytail: throwaway until Phase 4 brings the real UI.

// 1 MiB, same as Android's STREAM_CHUNK_BYTES: googlevideo refuses/throttles larger ranges for some clients.
private const val CHUNK_BYTES = 1024L * 1024

private suspend fun play(engine: AudioEngine, song: Song, next: Boolean) = withContext(ioDispatcher) {
    val url = StreamResolver.resolve(song.videoId)
    val headers = StreamResolver.mediaHeadersFor(url)
    // googlevideo URLs carry the format as ?mime=audio%2Fmp4
    val mime = url.substringAfter("mime=", "audio%2Fmp4").substringBefore('&').replace("%2F", "/")
    val length = StreamResolver.contentLength(song.videoId) ?: -1L
    if (next) engine.setNext(url, headers, mime, length, CHUNK_BYTES)
    else engine.play(url, headers, mime, length, CHUNK_BYTES)
}

@Composable
fun DebugPlayerScreen(engine: AudioEngine) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var index by remember { mutableStateOf(-1) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }

    // Resolves songs[i] as the next track so playback continues gaplessly.
    fun queueNext(i: Int) {
        val song = songs.getOrNull(i + 1) ?: return engine.clearNext()
        scope.launch { runCatching { play(engine, song, next = true) } }
    }

    fun start(i: Int) {
        val song = songs.getOrNull(i) ?: return
        index = i
        error = ""
        status = "Resolving ${song.title}..."
        scope.launch {
            runCatching { play(engine, song, next = false) }
                .onFailure { error = it.message ?: it.toString() }
                .onSuccess {
                    status = ""
                    engine.setNowPlaying(song.title, song.artist, null, song.thumbnailUrl)
                    engine.setQueueCapabilities(hasNext = i + 1 < songs.size, hasPrevious = i > 0)
                    queueNext(i)
                }
        }
    }

    suspend fun search() {
        status = "Searching..."
        YtMusicRepository.search(query, SearchFilter.SONGS)
            .onSuccess { rows ->
                songs = rows.mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
                status = ""
            }
            .onFailure { status = ""; error = it.message ?: it.toString() }
    }

    // Test hook: `simctl launch` with SIMCTL_CHILD_LAYA_AUTOPLAY=<query> searches and plays the first hit.
    LaunchedEffect(Unit) {
        val q = NSProcessInfo.processInfo.environment["LAYA_AUTOPLAY"] as? String ?: return@LaunchedEffect
        query = q
        search()
        start(0)
    }

    // Swift holds the listener weakly: the composition's remembered slot is what keeps it alive.
    remember {
        object : AudioEngineListener {
            override fun onProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean, isBuffering: Boolean) {
                pos = positionMs
                dur = durationMs
                playing = isPlaying || isBuffering
            }
            override fun onEnded() { playing = false }
            override fun onAdvancedToNext() {
                index += 1
                songs.getOrNull(index)?.let { engine.setNowPlaying(it.title, it.artist, null, it.thumbnailUrl) }
                queueNext(index)
            }
            override fun onError(message: String, httpStatus: Int) { error = "$message ($httpStatus)" }
            override fun onRemoteNext() = start(index + 1)
            override fun onRemotePrevious() = start(index - 1)
        }.also(engine::setListener)
    }

    Column(Modifier.fillMaxSize().safeContentPadding().padding(8.dp)) {
        Row {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, label = { Text("Search songs") })
            Button({ scope.launch { search() } }) { Text("Go") }
        }
        Text("${songs.getOrNull(index)?.title ?: "-"}  ${pos / 1000}s / ${dur / 1000}s  $status")
        if (error.isNotEmpty()) Text("Error: $error")
        Row {
            Button({ if (playing) engine.pause() else engine.resume() }) { Text(if (playing) "Pause" else "Play") }
            Button({ engine.seekTo(pos + 10_000) }) { Text("+10s") }
        }
        LazyColumn {
            itemsIndexed(songs) { i, song ->
                Text("${song.title} - ${song.artist}", Modifier.fillMaxWidth().clickable { start(i) }.padding(8.dp))
            }
        }
    }
}
