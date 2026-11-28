package com.music.laya.data.innertube

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryRemovalTokensTest {
    private fun row(videoId: String, token: String, icon: String = "REMOVE_FROM_HISTORY") = """
        {"musicResponsiveListItemRenderer": {
          "playlistItemData": {"videoId": "$videoId"},
          "menu": {"menuRenderer": {"items": [
            {"menuNavigationItemRenderer": {"icon": {"iconType": "MIX"}}},
            {"menuServiceItemRenderer": {"icon": {"iconType": "$icon"},
              "serviceEndpoint": {"feedbackEndpoint": {"feedbackToken": "$token"}}}}
          ]}}
        }}
    """

    @Test
    fun everyPlayOfATrackGivesItsTokenEvenUnderAnotherIcon() {
        val page = Json.parseToJsonElement(
            """{"contents": [${row("a", "t1")}, ${row("b", "t2")}, ${row("a", "t3")}, ${row("c", "t4", icon = "DELETE")}]}""",
        )
        assertEquals(mapOf("a" to listOf("t1", "t3"), "b" to listOf("t2"), "c" to listOf("t4")), InnertubeParser.historyRemovalTokens(page))
    }
}
