package com.music.laya.data

/**
 * Voice search languages for the platforms whose own recognizer does the
 * listening — Android (Google), iOS and macOS (Apple) — so all three offer
 * the same list and pick the same way. Linux, limited to Vosk's models, keeps
 * its own list (DesktopVoiceSearch.kt) but picks with [auto] too.
 *
 * Tags are BCP-47; labels are endonyms, so each reads right whatever language
 * the app is in. The South Asian three lead; the rest follow by tag. A
 * recognizer that lacks one (Apple and Bangla, say) reports it unavailable.
 */
object VoiceLanguages {
    /** Listened for alongside the chosen language where the recognizer can (Android). */
    val southAsian = listOf("en-IN", "hi-IN", "bn-BD")

    val all: List<Pair<String, String>> = listOf(
        "en-IN" to "English (India)",
        "hi-IN" to "हिन्दी",
        "bn-BD" to "বাংলা",
        "ar-SA" to "العربية",
        "ca-ES" to "Català",
        "cs-CZ" to "Čeština",
        "da-DK" to "Dansk",
        "de-DE" to "Deutsch",
        "el-GR" to "Ελληνικά",
        "en-GB" to "English (UK)",
        "en-US" to "English (US)",
        "es-ES" to "Español (España)",
        "es-MX" to "Español (México)",
        "fi-FI" to "Suomi",
        "fr-FR" to "Français",
        "gu-IN" to "ગુજરાતી",
        "he-IL" to "עברית",
        "hr-HR" to "Hrvatski",
        "hu-HU" to "Magyar",
        "id-ID" to "Bahasa Indonesia",
        "it-IT" to "Italiano",
        "ja-JP" to "日本語",
        "ko-KR" to "한국어",
        "mr-IN" to "मराठी",
        "ms-MY" to "Bahasa Melayu",
        "nb-NO" to "Norsk bokmål",
        "nl-NL" to "Nederlands",
        "pl-PL" to "Polski",
        "pt-BR" to "Português (Brasil)",
        "pt-PT" to "Português (Portugal)",
        "ro-RO" to "Română",
        "ru-RU" to "Русский",
        "sk-SK" to "Slovenčina",
        "sv-SE" to "Svenska",
        "ta-IN" to "தமிழ்",
        "te-IN" to "తెలుగు",
        "th-TH" to "ไทย",
        "tr-TR" to "Türkçe",
        "uk-UA" to "Українська",
        "ur-PK" to "اردو",
        "vi-VN" to "Tiếng Việt",
        "zh-CN" to "中文（简体）",
        "zh-TW" to "中文（繁體）",
        "zh-HK" to "粵語",
    )

    /**
     * What Auto listens in: the device's own language — the exact variant
     * offered (en-US) before any variant of the language (en), and the first
     * offered when the device's language isn't there at all.
     */
    fun auto(deviceTag: String, offered: List<String> = all.map { it.first }): String {
        val tag = deviceTag.replace('_', '-')
        val language = tag.substringBefore('-')
        return offered.firstOrNull { it.equals(tag, ignoreCase = true) }
            ?: offered.firstOrNull { it.substringBefore('-').equals(language, ignoreCase = true) }
            ?: offered.first()
    }
}
