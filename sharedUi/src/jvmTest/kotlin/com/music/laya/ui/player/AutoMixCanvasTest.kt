package com.music.laya.ui.player

import com.music.laya.data.canvas.CanvasArtwork
import com.music.laya.data.canvas.CanvasSource
import com.music.laya.data.model.QueueEntryIdentity
import com.music.laya.playback.AutoMixArtworkGate
import com.music.laya.playback.AutoMixArtworkWindow
import com.music.laya.playback.AutoMixVisualPhase
import com.music.laya.playback.AutoMixVisualState
import com.music.laya.playback.AutoMixVisualTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AutoMixCanvasTest {
    private val outgoing = QueueEntryIdentity("a", null, 0)
    private val incoming = QueueEntryIdentity("b", null, 0)
    private val clipA = CanvasArtwork("https://c/a.mp4", source = CanvasSource.OTHER)
    private val clipB = CanvasArtwork("https://c/b.mp4", source = CanvasSource.OTHER)
    private val spotify = CanvasArtwork("https://c/s.mp4", source = CanvasSource.SPOTIFY)

    private fun state(
        phase: AutoMixVisualPhase = AutoMixVisualPhase.MIXING,
        audio: Double = 10.0,
        window: AutoMixArtworkWindow? = null,
        swap: Double? = null,
    ) = AutoMixVisualState(
        1L, AutoMixVisualTrack(outgoing, 0, null), AutoMixVisualTrack(incoming, 1, null),
        phase = phase, audioSeconds = audio, swapSeconds = swap, artworkWindow = window,
    )

    private fun tracks(sample: AutoMixVisualState?, pinned: CanvasArtwork? = clipA) =
        AutoMixCanvasTracks(1L, outgoing, incoming, pinned) { sample }

    @Test
    fun incomingMustBeReadyBeforeTheRevealWindowOpens() {
        val early = tracks(state(window = AutoMixArtworkWindow(12.0, 14.0), audio = 10.0))
        assertTrue(early.beforeIncomingDeadline(state(window = AutoMixArtworkWindow(12.0, 14.0), audio = 10.0)))
        assertFalse(early.beforeIncomingDeadline(state(window = AutoMixArtworkWindow(12.0, 14.0), audio = 12.0)))
        // Before the swap, with no window yet, the clip still has time; armed is always early.
        assertTrue(early.beforeIncomingDeadline(state(swap = null)))
        assertTrue(early.beforeIncomingDeadline(state(phase = AutoMixVisualPhase.ARMED)))
        assertFalse(early.beforeIncomingDeadline(state(swap = 10.0)))
    }

    @Test
    fun aReadyIncomingClipGoesLiveOnlyWhenSealedInTime() {
        val live = tracks(state())
        live.incomingClip = clipB
        live.started = true
        live.reportIncoming(true)
        assertTrue(live.holdsLive)
        assertTrue(live.sealIncoming())
        assertTrue(live.liveIncoming)
        assertTrue(live.holdsLive)
    }

    @Test
    fun aClipThatIsLateIsNotUsed() {
        val late = tracks(state(window = AutoMixArtworkWindow(12.0, 14.0), audio = 13.0))
        late.incomingClip = clipB
        late.started = true
        late.reportIncoming(true)
        assertFalse(late.incomingReady)
        assertFalse(late.sealIncoming())
        assertFalse(late.holdsLive)
    }

    @Test
    fun theChoiceIsSealedOnce() {
        val t = tracks(state())
        t.incomingClip = clipB
        t.started = true
        assertFalse(t.sealIncoming())
        t.reportIncoming(true)
        assertFalse(t.liveIncoming)
        assertFalse(t.sealIncoming())
    }

    @Test
    fun aLostIncomingSurfaceLeavesTheLiveBlend() {
        val t = tracks(state())
        t.incomingClip = clipB
        t.started = true
        t.reportIncoming(true)
        t.sealIncoming()
        t.reportIncoming(false)
        assertFalse(t.liveIncoming)
        assertNull(t.incomingClip)
    }

    @Test
    fun clipsOfAnotherShapeDoNotShareTheHeroSlot() {
        val t = tracks(state())
        t.outgoingAspect = 1f
        t.incomingAspect = 0.75f
        t.incomingClip = clipB
        t.started = true
        t.reportIncoming(true)
        assertFalse(t.sealIncoming())
        val ok = tracks(state())
        ok.outgoingAspect = 1.2f
        ok.incomingAspect = 1.6f
        ok.incomingClip = clipB
        ok.started = true
        ok.reportIncoming(true)
        assertTrue(ok.sealIncoming())
    }

    @Test
    fun aSpotifyCanvasNeverTakesPart() {
        assertTrue(spotify.retiresBeforeAutoMix)
        assertFalse(clipA.retiresBeforeAutoMix)
        val pair = AutoMixCanvasPair()
        pair.displayed(outgoing, spotify, true)
        val t = pair.start(state(), outgoing, AutoMixArtworkGate())
        assertNotNull(t)
        assertNull(t.pinnedClip)
        pair.beginMix(t)
        assertFalse(t.liveOutgoing)
    }

    @Test
    fun theOutgoingClipIsPinnedAndTheIncomingOneIsPromoted() {
        val pair = AutoMixCanvasPair()
        pair.displayed(outgoing, clipA, true)
        val t = pair.start(state(), outgoing, AutoMixArtworkGate())!!
        assertSame(clipA, t.pinnedClip)
        pair.beginMix(t)
        assertTrue(t.liveOutgoing)
        val live = tracks(state())
        live.incomingClip = clipB
        live.started = true
        live.reportIncoming(true)
        live.sealIncoming()
        pair.finish(live)
        assertNull(pair.visual)
        assertEquals(clipB, pair.promoted?.incomingClip)
        // Another selection drops the promotion.
        pair.displayed(outgoing, clipA, true)
        assertNull(pair.promoted)
    }

    @Test
    fun aGenerationIsStartedOnce() {
        val pair = AutoMixCanvasPair()
        pair.displayed(outgoing, clipA, true)
        assertNotNull(pair.start(state(), outgoing, AutoMixArtworkGate()))
        assertNull(pair.start(state(), outgoing, AutoMixArtworkGate()))
        assertNull(AutoMixCanvasPair().start(state(), incoming, AutoMixArtworkGate()))
        assertNull(AutoMixCanvasPair().start(state(), outgoing, AutoMixArtworkGate(enabled = false)))
    }
}
