package com.music.laya.data

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceLanguagesTest {
    @Test
    fun autoPicksTheDevicesOwnLanguage() {
        assertEquals("es-MX", VoiceLanguages.auto("es-MX"))
        assertEquals("es-ES", VoiceLanguages.auto("es-AR")) // a Spanish not listed: still Spanish
        assertEquals("en-US", VoiceLanguages.auto("en_US")) // Apple's underscore form
        assertEquals("bn-BD", VoiceLanguages.auto("bn-IN"))
        assertEquals("en-IN", VoiceLanguages.auto("sw-KE")) // not offered: the first
    }

    @Test
    fun autoWorksOnLinuxsShorterList() {
        val vosk = listOf("en-IN", "hi-IN", "en-US", "es", "uk")
        assertEquals("es", VoiceLanguages.auto("es-ES", vosk))
        assertEquals("en-US", VoiceLanguages.auto("en-US", vosk))
        assertEquals("en-IN", VoiceLanguages.auto("en-AU", vosk))
        assertEquals("en-IN", VoiceLanguages.auto("bn-BD", vosk))
    }
}
