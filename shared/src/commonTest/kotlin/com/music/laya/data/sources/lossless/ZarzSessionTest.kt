package com.music.laya.data.sources.lossless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZarzSessionTest {

    /** Expected values computed independently from Mornye's `protocol::signed_headers` recipe. */
    @Test
    fun signsLikeMornye() {
        val headers = ZarzSession.signedHeaders(
            sessionId = "sess_abc",
            sessionSecret = "s3cret",
            appVersion = "tidal-web@1.2.5",
            method = "POST",
            path = "/v2/dl/tid",
            body = """{"id":"1"}""",
            nowMs = 1763600000123,
            nonce = "0123456789abcdef01234567",
        )
        assertEquals("2025-11-20T00:53:20.123Z", headers["X-Zarz-Timestamp"])
        assertEquals("5811967f540d300d249ab30ae681359a7815fdb5d3dc71a94be1d491006a6b27", headers["X-Zarz-Body-SHA256"])
        assertEquals("-Sc29Qr3jhQm3NKVc0iNBUBdh8cTtk6NkgQw9GLRd6I", headers["X-Zarz-Signature"])
        assertEquals("extension", headers["X-Zarz-Platform"])
    }

    @Test
    fun readsGrantFromCallbackOnly() {
        assertEquals("gr_4/56", ZarzSession.grantFrom("mornye://session-grant?cb_version=v2grant&state=n&grant=gr_4%2F56"))
        assertNull(ZarzSession.grantFrom("mornye://session-grant?state=n"))
        assertNull(ZarzSession.grantFrom("https://evil.example/?grant=x"))
    }
}
