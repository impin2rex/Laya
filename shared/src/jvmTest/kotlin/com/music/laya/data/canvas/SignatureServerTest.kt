package com.music.laya.data.canvas

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SignatureServerTest {
    private fun server(vararg up: String) = SignatureServer { it in up }

    @Test fun nothingConfiguredIsUnknown() = runBlocking {
        val server = server()
        assertEquals("", server.refresh(custom = "", builtIn = ""))
        assertEquals(null, server.online.value)
    }

    @Test fun builtInUsedWhenNothingSaved() = runBlocking {
        val server = server("https://built.in")
        assertEquals("https://built.in", server.refresh(custom = "", builtIn = "https://built.in"))
        assertEquals(true, server.online.value)
    }

    @Test fun savedServerWinsWhileUp() = runBlocking {
        val server = server("https://mine", "https://built.in")
        assertEquals("https://mine", server.refresh(custom = "https://mine", builtIn = "https://built.in"))
    }

    @Test fun fallsBackToBuiltInWhenSavedIsDown() = runBlocking {
        val server = server("https://built.in")
        assertEquals("https://built.in", server.refresh(custom = "https://mine", builtIn = "https://built.in"))
        assertEquals(true, server.online.value)
    }

    @Test fun offlineWhenEverythingIsDown() = runBlocking {
        val server = server()
        assertEquals("https://mine", server.refresh(custom = "https://mine", builtIn = "https://built.in"))
        assertEquals(false, server.online.value)
    }
}
