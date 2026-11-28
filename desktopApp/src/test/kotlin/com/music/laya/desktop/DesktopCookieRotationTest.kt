package com.music.laya.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopCookieRotationTest {
    private val jar = "SID=a; __Secure-1PSIDTS=old1; __Secure-3PSIDTS=old3; PREF=x"

    @Test
    fun rotatedYoutubeCookiesReplaceTheirOldValues() {
        val merged = DesktopCookieRotation.merge(
            jar,
            listOf(
                "__Secure-1PSIDTS=new1; Domain=.youtube.com; Path=/; Secure; HttpOnly; Expires=Sat, 27-Nov-2027 00:00:00 GMT",
                "__Secure-3PSIDTS=new3; Domain=youtube.com; Path=/; Secure; HttpOnly; SameSite=none",
            ),
        )
        assertEquals("SID=a; __Secure-1PSIDTS=new1; __Secure-3PSIDTS=new3; PREF=x", merged)
    }

    @Test
    fun hostOnlyAndOtherDomainsAreLeftOut() {
        val merged = DesktopCookieRotation.merge(
            jar,
            listOf("ACCOUNT_CHOOSER=z; Path=/; Secure", "SID=g; Domain=.google.com; Path=/"),
        )
        assertEquals(jar, merged)
    }

    @Test
    fun deletedCookiesGo() {
        val merged = DesktopCookieRotation.merge(jar, listOf("PREF=; Domain=.youtube.com; Max-Age=0"))
        assertEquals("SID=a; __Secure-1PSIDTS=old1; __Secure-3PSIDTS=old3", merged)
    }

    @Test
    fun newCookiesAreAdded() {
        val merged = DesktopCookieRotation.merge("SID=a", listOf("SIDCC=c; Domain=.youtube.com; Path=/"))
        assertEquals("SID=a; SIDCC=c", merged)
    }
}
