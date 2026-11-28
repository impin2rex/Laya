package com.music.laya.ui.screens

import com.music.laya.data.model.KeyedMix
import com.music.laya.data.model.ShelfItem
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupMixesTest {

    private fun mix(id: String, shown: String, english: String = shown) =
        KeyedMix(ShelfItem(shown, "", null, null, id), english)

    @Test
    fun sortsIntoStationsYoursAndMoods() {
        val sections = groupMixes(
            listOf(
                mix("a", "Discover Mix"),
                mix("b", "Chill Mix 2"),
                mix("c", "My Supermix"),
                mix("d", "Modern Punjabi Beats"),
                mix("e", "Chill Supermix"),
                mix("f", "Chill Mix 1"),
                mix("g", "My Mix 1"),
                mix("a", "Discover Mix"),
            ),
        )
        assertEquals(listOf("My Supermix", "Discover Mix"), sections.stations.map { it.title })
        assertEquals(listOf("Modern Punjabi Beats", "My Mix 1"), sections.yours.map { it.title })
        assertEquals(1, sections.moods.size)
        assertEquals("Chill", sections.moods[0].name)
        assertEquals(
            listOf("Chill Supermix", "Chill Mix 1", "Chill Mix 2"),
            sections.moods[0].items.map { it.title },
        )
    }

    @Test
    fun translatedMoodHeadingIsTheTitlesCommonPrefix() {
        val sections = groupMixes(
            listOf(
                mix("x", "Mix Détente 1", "Chill Mix 1"),
                mix("y", "Mix Détente 2", "Chill Mix 2"),
            ),
        )
        assertEquals("Mix Détente", sections.moods.single().name)
    }
}
