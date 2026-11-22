package com.music.laya.data.bestpart

import com.music.laya.data.lyrics.LyricLine
import com.music.laya.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BestPartLocalTest {
    private val lines = listOf(
        10_000L to "first verse line one", 14_000L to "first verse line two",
        40_000L to "we sing this chorus loud", 44_000L to "we sing it all night long",
        70_000L to "second verse line one", 74_000L to "second verse line two",
        100_000L to "we sing this chorus loud", 104_000L to "we sing it all night long",
    ).map { (t, text) -> LyricLine(t, text) }

    private val file = Song("content://media/42", "Song", "Artist", null, "3:00", localUri = "content://media/42")

    @Test
    fun aLocalFileFallsBackToItsEmbeddedChorus() = runBlocking {
        assertNotNull(BestPart.startFor(file, lines))
        assertNull(BestPart.startFor(file, null))
    }
}
