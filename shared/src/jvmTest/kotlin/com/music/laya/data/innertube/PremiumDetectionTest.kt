package com.music.laya.data.innertube

import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `account_menu` popups shaped as YouTube Music serves them (trimmed to what matters). They are
 * built from the known response structure, not captured from a live Premium account.
 */
class PremiumDetectionTest {

    private fun menu(vararg links: Pair<String, String>) = Json.parseToJsonElement(
        """
        {"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{
          "header":{"activeAccountHeaderRenderer":{
            "accountName":{"runs":[{"text":"Asha"}]},
            "email":{"runs":[{"text":"asha@example.com"}]},
            "accountPhoto":{"thumbnails":[{"url":"https://yt3.ggpht.com/a","width":48,"height":48}]}}},
          "sections":[{"multiPageMenuSectionRenderer":{"items":[
            {"compactLinkRenderer":{"title":{"runs":[{"text":"Your channel"}]},
              "navigationEndpoint":{"browseEndpoint":{"browseId":"UC123"}}}}
            ${links.joinToString("") { (title, url) ->
                """,{"compactLinkRenderer":{"title":{"runs":[{"text":"$title"}]},
                   "navigationEndpoint":{"urlEndpoint":{"url":"$url"}}}}"""
            }}
          ]}}]}}}}]}
        """,
    )

    @AfterTest
    fun reset() {
        PremiumAudio.read = { null }
        PremiumAudio.write = { _, _ -> }
        PremiumAudio.override.value = null
        PremiumAudio.detected.value = null
    }

    @Test
    fun `the upsell means no Premium`() {
        val response = menu("Get Music Premium" to "https://music.youtube.com/music_premium")
        assertEquals(false, InnertubeParser.parsePremium(response))
    }

    @Test
    fun `paid memberships without the upsell means Premium`() {
        val response = menu("Paid memberships" to "https://www.youtube.com/paid_memberships")
        assertEquals(true, InnertubeParser.parsePremium(response))
    }

    @Test
    fun `the upsell wins over a membership link`() {
        val response = menu(
            "Paid memberships" to "https://www.youtube.com/paid_memberships",
            "Get Music Premium" to "https://www.youtube.com/premium",
        )
        assertEquals(false, InnertubeParser.parsePremium(response))
    }

    @Test
    fun `a menu with neither signal, or an error body, is unknown`() {
        assertNull(InnertubeParser.parsePremium(menu()))
        assertNull(InnertubeParser.parsePremium(Json.parseToJsonElement("""{"error":{"code":401}}""")))
    }

    @Test
    fun `detection is remembered per account and follows the account`() {
        val store = mutableMapOf<String, String>()
        PremiumAudio.read = { store[it] }
        PremiumAudio.write = { k, v -> if (v == null) store.remove(k) else store[k] = v }
        PremiumAudio.observe(menu("Paid memberships" to "https://www.youtube.com/paid_memberships"))
        assertTrue(PremiumAudio.enabled())
        assertEquals("true", store["youtube_premium_detected:asha@example.com"])

        // A restart reads the last account's verdict before the menu is asked again.
        PremiumAudio.detected.value = null
        PremiumAudio.load()
        assertEquals(true, PremiumAudio.detected.value)
    }

    @Test
    fun `the listener's override wins either way`() {
        PremiumAudio.detected.value = true
        PremiumAudio.setOverride(false)
        assertFalse(PremiumAudio.enabled())
        PremiumAudio.detected.value = false
        PremiumAudio.setOverride(true)
        assertTrue(PremiumAudio.enabled())
        PremiumAudio.setOverride(null)
        assertFalse(PremiumAudio.enabled())
    }
}
