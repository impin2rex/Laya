package com.music.laya.data.aifilter

import kotlin.test.Test
import kotlin.test.assertEquals

class AiSongsTest {
    @Test
    fun anUploaderHandleIsReadOffTheOembedAuthorUrl() {
        assertEquals("fredinfocus", AiSongs.handleOf("https://www.youtube.com/@FredinFocus"))
        assertEquals("lo-fi-rock-metal", AiSongs.handleOf("https://www.youtube.com/@lo-fi-rock-metal/videos"))
        // A non-ASCII handle arrives percent-encoded.
        assertEquals("müzik", AiSongs.handleOf("https://www.youtube.com/@M%C3%BCzik"))
        // A Topic channel is a channel id, with no handle to match.
        assertEquals("", AiSongs.handleOf("https://www.youtube.com/channel/UCLeR-eGh2OFy9ydX7IUcOTw"))
        assertEquals("", AiSongs.handleOf(null))
    }

    @Test
    fun theListKeepsHandlesAndSkipsComments() {
        val list = AiSongs.parseBlocklist(
            """
            ! Title: AISList - Blocklist
            ! Format: @channelhandle
            @ChickofHonor-l
              @SuperCatLeague
            not a handle

            """.trimIndent(),
        )
        assertEquals(setOf("chickofhonor-l", "supercatleague"), list)
    }
}
