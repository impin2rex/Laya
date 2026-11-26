package com.music.laya.data.home

import com.music.laya.data.model.HomeShelf
import com.music.laya.data.model.ShelfItem
import com.music.laya.data.settings.HomeDialSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeDialTest {
    private fun track(id: String) = ShelfItem(id, "", null, id, null)
    private fun card(browse: String, title: String = browse) = ShelfItem(title, "", null, null, browse)

    @Test
    fun kindsFromIds() {
        assertEquals(DialKind.TRACK, track("v").dialKind)
        assertEquals(DialKind.ALBUM, card("MPREb_x").dialKind)
        assertEquals(DialKind.PLAYLIST, card("VLRDTMAK1").dialKind)
        assertEquals(DialKind.ARTIST, card("UCabc").dialKind)
        assertEquals(null, card("FEmusic_x").dialKind)
    }

    @Test
    fun youTubePoolFollowsShelfRanks() {
        val shelves = listOf(
            HomeShelf("Quick picks", listOf(track("q"))),
            HomeShelf("Listen again", listOf(track("l"), card("UCart"))),
            HomeShelf("Speed dial", listOf(track("s"))),
            HomeShelf("Other", listOf(track("o"), track("hidden"))),
        )
        val pool = SpeedDial.pool(HomeDialSource.YOUTUBE, shelves, listOf(track("r")), emptyList(), setOf("hidden"), withArtists = true)
        // Speed dial, Listen again (artist left to the Artists page), recents, Quick picks, the rest.
        assertEquals(listOf("s", "l", "r", "q", "o"), pool.map { it.dialId })
    }

    @Test
    fun pinsLeadAndShortDealsAreToppedUp() {
        val pin = card("VLPLpin")
        val entries = SpeedDial.entries(listOf(pin), listOf(track("a"), pin), listOf(track("b")), pageSize = 3, newReleases = setOf("a"))
        assertEquals(listOf("VLPLpin", "a", "b"), entries.map { it.item.dialId })
        assertTrue(entries[0].pinned && entries[1].isNewRelease)
    }

    @Test
    fun mixesDealtOneOfAKindAndSleepOnlyAtNight() {
        val home = listOf(HomeShelf("Mixed", listOf(card("VLRDTMAKa", "Chill Mix 1"), card("VLRDTMAKb", "Chill Mix 2"), card("VLRDTMAKc", "Sleep Mix"))))
        val day = MixesDial.deal(home, emptyList(), emptyList(), emptySet(), sleepTime = false, random = Random(1))
        assertEquals(1, day.size)
        val night = MixesDial.deal(home, emptyList(), emptyList(), emptySet(), sleepTime = true, random = Random(1))
        assertEquals(2, night.size)
        assertTrue(MixesDial.isSleepTime(23 * 60) && !MixesDial.isSleepTime(12 * 60))
    }
}
