package com.music.laya.data.sources

import com.music.laya.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Direct Source Stream's two needs from [SourceResolver.bestAcross]: errors told apart from misses, and a rejected answer not ending the search. */
class DirectAnswerTest {
    private val target = TrackMatcher.Target(title = "Song", artist = "Artist", durationSec = 200)

    private class Fake(override val configId: String, val url: String?, val fails: Boolean = false) : MusicSource {
        override val kind = SourceKind.ADDON
        override val displayName = configId
        override suspend fun health(): SourceHealth = SourceHealth.Ok()
        override suspend fun search(query: String, limit: Int, waitForAll: Boolean, request: StreamRequest?): List<Song> {
            if (fails) error("server down")
            return if (url == null) emptyList() else listOf(Song("$configId-1", "Song", "Artist", null, durationText = "3:20"))
        }
        override suspend fun stream(trackId: String, request: StreamRequest) = url?.let { SourceStream(it) }
    }

    private fun run(vararg sources: MusicSource, accept: suspend (MusicSource, SourceStream) -> Boolean = { _, _ -> true }) = runBlocking {
        val failures = SourceResolver.SourceFailures()
        val found = withContext(failures) {
            SourceResolver.bestAcross(sources.toList(), target, StreamRequest.Best, strictLength = true, accept = accept)
        }
        found?.second to failures.any
    }

    @Test
    fun cleanMissIsNotAnError() {
        val (stream, errored) = run(Fake("a", url = null))
        assertNull(stream)
        assertFalse(errored)
    }

    @Test
    fun throwingSourceIsAnError() {
        val (stream, errored) = run(Fake("a", url = null, fails = true), Fake("b", url = null))
        assertNull(stream)
        assertTrue(errored)
    }

    @Test
    fun rejectedAnswerLeavesTheOthers() {
        val (stream, _) = run(Fake("preview", "https://p/x"), Fake("full", "https://f/x")) { _, s -> s.url != "https://p/x" }
        assertEquals("https://f/x", stream?.url)
    }
}
