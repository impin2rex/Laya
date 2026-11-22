package com.music.laya.data.bestpart

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeatmapStoreTest {
    private val heat = Heatmap(1_000, List(20) { it / 20f })
    private fun dir(): Path = Files.createTempDirectory("heat").toString().toPath()

    @Test
    fun survivesARestartAndExpires() {
        val root = dir()
        var t = 1_000L
        HeatmapStore({ root }, now = { t }).put("aaaaaaaaaaa", heat)
        HeatmapStore({ root }, now = { t }).put("bbbbbbbbbbb", null)
        val reopened = HeatmapStore({ root }, now = { t })
        assertEquals(heat, reopened.get("aaaaaaaaaaa")?.heat)
        assertNotNull(reopened.get("bbbbbbbbbbb"))
        assertNull(reopened.get("bbbbbbbbbbb")?.heat)
        t += HeatmapStore.WEEK_MS + 1
        assertNull(reopened.get("bbbbbbbbbbb")) // "no curve" lapsed after a week
        assertNotNull(reopened.get("bbbbbbbbbbb", allowStale = true))
        assertNotNull(reopened.get("aaaaaaaaaaa")) // a curve lasts a month
        t += HeatmapStore.MONTH_MS
        assertNull(reopened.get("aaaaaaaaaaa"))
    }

    @Test
    fun keepsTheNewestFewHundred() {
        val root = dir()
        var t = 0L
        val store = HeatmapStore({ root }, now = { t++ })
        repeat(HeatmapStore.MAX_KEPT + 10) { store.put("id$it", heat) }
        val reopened = HeatmapStore({ root }, now = { t })
        assertNull(reopened.get("id0"))
        assertNotNull(reopened.get("id${HeatmapStore.MAX_KEPT + 9}"))
    }

    @Test
    fun unreadableFileIsDiscardedAndNullRootIsMemoryOnly() {
        val root = dir()
        FileSystem.SYSTEM.createDirectories(root / "bestpart")
        FileSystem.SYSTEM.write(root / "bestpart" / "heatmaps.json") { writeUtf8("{nope") }
        val store = HeatmapStore({ root })
        assertNull(store.get("aaaaaaaaaaa"))
        assertTrue(!FileSystem.SYSTEM.exists(root / "bestpart" / "heatmaps.json"))
        HeatmapStore({ null }).put("aaaaaaaaaaa", heat) // no crash, nothing kept
    }
}
