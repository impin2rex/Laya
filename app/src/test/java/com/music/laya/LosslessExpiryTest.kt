package com.music.laya

import com.music.laya.data.sources.LosslessSource
import com.music.laya.data.sources.SourceConfig
import com.music.laya.data.sources.SourceKind
import com.music.laya.data.sources.SourceRegistry
import com.music.laya.data.sources.lossless.LosslessProvider
import com.music.laya.data.sources.lossless.LosslessStore
import com.music.laya.data.sources.lossless.VerificationRequired
import com.music.laya.data.sources.lossless.ZarzSession
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A Tidal/Qobuz session that runs out or is refused switches its source off and is reported once, so an "on" switch
 * always means a source that works. Switching off by hand is not a loss. No network: an expired session is refused
 * before any request is made.
 */
class LosslessExpiryTest {

    private val store = mutableMapOf<String, String?>()
    private val configsBefore = SourceRegistry.configs.value

    @Before
    fun setUp() {
        useAndroidSourcesHost()
        LosslessStore.read = { store[it] }
        LosslessStore.write = { key, value -> store[key] = value }
        LosslessProvider.Qobuz.session.forget() // the singleton's state outlives a test
        store.clear()
    }

    @After
    fun tearDown() {
        LosslessProvider.Qobuz.session.forget()
        SourceRegistry.configs.value = configsBefore
        LosslessStore.read = { null }
        LosslessStore.write = { _, _ -> }
    }

    private fun record(expiresAt: String) =
        """{"install_id":"i","session_id":"s","session_secret":"k","expires_at":"$expiresAt"}"""

    private val past = "2000-01-01T00:00:00Z"
    private val future = "2999-01-01T00:00:00Z"

    private fun qobuzOn(enabled: Boolean): SourceConfig {
        val qobuz = SourceConfig(kind = SourceKind.QOBUZ, enabled = enabled)
        SourceRegistry.configs.value = listOf(qobuz, SourceConfig(kind = SourceKind.YOUTUBE))
        return qobuz
    }

    private fun enabled(config: SourceConfig) = SourceRegistry.configs.value.first { it.id == config.id }.enabled

    // --- The session ---

    @Test
    fun `a session found expired at launch is lost once and cleared`() = runBlocking {
        store["zarz_session_launch-test"] = record(past)
        val session = ZarzSession("launch-test@1") { "test" }
        val losses = mutableListOf<Unit>()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { session.lost.collect { losses += it } }

        session.reload()
        session.reload() // nothing left to lose the second time
        yield()

        assertEquals(1, losses.size)
        assertFalse(session.verified.value)
        assertTrue(session.expired.value)
        assertTrue("stored secret cleared", store["zarz_session_launch-test"]!!.contains("\"session_id\":\"\""))
        watcher.cancel()
    }

    @Test
    fun `a session that ran out is lost on its next call, before any request`() = runBlocking {
        store["zarz_session_call-test"] = record(past)
        val session = ZarzSession("call-test@1") { "test" } // read, not yet reloaded: still held
        val losses = mutableListOf<Unit>()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { session.lost.collect { losses += it } }

        val refused = runCatching { session.get("/qbz/track/search?query=x") }.exceptionOrNull()
        assertTrue("refused as unverified, got $refused", refused is VerificationRequired)
        yield()

        assertEquals(1, losses.size)
        assertTrue(session.expired.value)
        watcher.cancel()
    }

    @Test
    fun `switching off by hand is not a loss`() = runBlocking {
        store["zarz_session_hand-test"] = record(future)
        val session = ZarzSession("hand-test@1") { "test" }
        assertTrue(session.verified.value)
        val losses = mutableListOf<Unit>()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { session.lost.collect { losses += it } }

        session.forget()
        yield()

        assertTrue(losses.isEmpty())
        assertFalse(session.verified.value)
        assertFalse(session.expired.value)
        watcher.cancel()
    }

    @Test
    fun `a session still valid at launch is kept`() {
        store["zarz_session_valid-test"] = record(future)
        val session = ZarzSession("valid-test@1") { "test" }
        session.reload()
        assertTrue(session.verified.value)
        assertFalse(session.expired.value)
    }

    // --- The switch ---

    @Test
    fun `a lost session switches its source off and is reported once`() = runBlocking {
        val qobuz = qobuzOn(enabled = true)
        val notified = mutableListOf<LosslessProvider>()
        LosslessSource.watchExpiry(this) { notified += it }
        yield()

        store["zarz_session_qobuz-web"] = record(past)
        LosslessProvider.Qobuz.session.reload()
        yield()

        assertFalse(enabled(qobuz))
        assertEquals(listOf<LosslessProvider>(LosslessProvider.Qobuz), notified)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `an expiry already standing when the app starts is handled`() = runBlocking {
        val qobuz = qobuzOn(enabled = true)
        store["zarz_session_qobuz-web"] = record(past)
        LosslessProvider.Qobuz.session.reload() // at launch, before anything listens

        val notified = mutableListOf<LosslessProvider>()
        LosslessSource.watchExpiry(this) { notified += it }
        yield()

        assertFalse(enabled(qobuz))
        assertEquals(listOf<LosslessProvider>(LosslessProvider.Qobuz), notified)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a source already off is not announced when its session is lost`() = runBlocking {
        val qobuz = qobuzOn(enabled = false)
        val notified = mutableListOf<LosslessProvider>()
        LosslessSource.watchExpiry(this) { notified += it }
        yield()

        store["zarz_session_qobuz-web"] = record(past)
        LosslessProvider.Qobuz.session.reload()
        yield()

        assertFalse(enabled(qobuz))
        assertTrue(notified.isEmpty())
        coroutineContext.cancelChildren()
    }

    @Test
    fun `turning a source on drops the old session so it verifies again`() {
        store["zarz_session_qobuz-web"] = record(future)
        LosslessProvider.Qobuz.session.reload()
        assertTrue(LosslessProvider.Qobuz.session.verified.value)

        val toVerify = LosslessSource.toggled(on = true, LosslessProvider.Qobuz)

        assertEquals(LosslessProvider.Qobuz, toVerify)
        assertFalse(LosslessProvider.Qobuz.session.verified.value)
        assertFalse(LosslessProvider.Qobuz.session.expired.value)
    }

    @Test
    fun `turning a source off drops its session and asks for nothing`() {
        store["zarz_session_qobuz-web"] = record(future)
        LosslessProvider.Qobuz.session.reload()

        assertEquals(null, LosslessSource.toggled(on = false, LosslessProvider.Qobuz))
        assertFalse(LosslessProvider.Qobuz.session.verified.value)
    }
}
