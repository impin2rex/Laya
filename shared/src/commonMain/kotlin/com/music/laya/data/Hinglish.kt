package com.music.laya.data

/**
 * Hindi heard by Apple's recognizer (macOS, iOS), written the way people
 * type it into a search box (Android's recognizer does this itself): "तू मिल जाए" → "tu mil jaye". Song titles are mostly listed in this
 * Hinglish, and that's what voice search should put in the field. Text that
 * isn't Devanagari passes through untouched.
 *
 * Spelling follows common usage (ी → i, ू → u, ा → a) rather than a strict
 * scheme, and the inherent "a" is dropped where Hindi speech drops it: at the
 * end of a word, and between two sounded syllables (धड़कन → dhadkan).
 * ponytail: rule-based schwa deletion, misses a few compounds (हमसफ़र → hamsfar); search tolerates it.
 */
object Hinglish {
    private val consonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh",
        'ष' to "sh", 'स' to "s", 'ह' to "h",
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "gh", '\u095B' to "z",
        '\u095C' to "d", '\u095D' to "dh", '\u095E' to "f", '\u095F' to "y", // क़ ख़ ग़ ज़ ड़ ढ़ फ़ य़
    )

    /** With a nukta (़) after them, as most keyboards and recognizers write these. */
    private val nukta = mapOf('क' to "q", 'ख' to "kh", 'ग' to "gh", 'ज' to "z", 'ड' to "d", 'ढ' to "dh", 'फ' to "f")

    private val vowels = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u",
        'ऋ' to "ri", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o",
    )

    private val signs = mapOf(
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॅ' to "e", 'ॉ' to "o",
    )

    private const val VIRAMA = '्'
    private const val NUKTA = '\u093C'

    /** One syllable: its consonants, its vowel, whatever nasal or h closes it. */
    private class Syllable(val onset: String, var vowel: String, val inherent: Boolean, var coda: String = "")

    fun of(text: String): String {
        if (text.none { it in 'ऀ'..'ॿ' }) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] !in 'ऀ'..'ॿ') {
                out.append(text[i++])
                continue
            }
            val start = i
            while (i < text.length && text[i] in 'ऀ'..'ॿ') i++
            out.append(word(text.substring(start, i)))
        }
        return out.toString()
    }

    private fun word(word: String): String {
        val syllables = mutableListOf<Syllable>()
        val passthrough = StringBuilder()
        var i = 0
        fun consonant(): String? {
            val c = word.getOrNull(i) ?: return null
            val base = consonants[c] ?: return null
            i++
            if (word.getOrNull(i) == NUKTA) {
                i++
                return nukta[c] ?: base
            }
            return base
        }
        while (i < word.length) {
            val c = word[i]
            when {
                c in consonants -> {
                    var onset = consonant()!!
                    // A conjunct: consonants joined by a virama share one vowel.
                    while (word.getOrNull(i) == VIRAMA && word.getOrNull(i + 1)?.let { it in consonants } == true) {
                        i++
                        onset += consonant()!!
                    }
                    val sign = word.getOrNull(i)
                    when {
                        sign == VIRAMA -> { i++; syllables += Syllable(onset, "", inherent = false) }
                        sign != null && sign in signs -> { i++; syllables += Syllable(onset, signs.getValue(sign), inherent = false) }
                        else -> syllables += Syllable(onset, "a", inherent = true)
                    }
                }
                c in vowels -> {
                    i++
                    // ए after a vowel is sung, and typed, as "ye": जाए → jaye.
                    val glide = c == 'ए' && syllables.lastOrNull()?.vowel?.isNotEmpty() == true
                    syllables += Syllable(if (glide) "y" else "", vowels.getValue(c).let { if (glide) "e" else it }, inherent = false)
                }
                c == 'ं' || c == 'ँ' -> { i++; syllables.lastOrNull()?.let { it.coda += "\u0000" } }
                c == 'ः' -> { i++; syllables.lastOrNull()?.let { it.coda += "h" } }
                c in '०'..'९' -> { i++; syllables += Syllable("", "", inherent = false, coda = ('0' + (c - '०')).toString()) }
                else -> { i++; passthrough.append(if (c == '।' || c == '॥') "." else "") }
            }
        }

        // The inherent "a" goes where speech drops it, scanning from the end
        // so two neighbours never both lose theirs.
        val last = syllables.lastIndex
        var k = last
        while (k >= 0) {
            val s = syllables[k]
            val drop = s.inherent && s.coda.isEmpty() && when (k) {
                last -> k > 0
                0 -> false
                // Not after a nasal: ज़िंदगी is zindagi, not zindgi.
                else -> syllables[k + 1].vowel.isNotEmpty() && syllables[k - 1].coda.isEmpty()
            }
            if (drop) {
                s.vowel = ""
                k -= 2
            } else {
                k--
            }
        }

        return buildString {
            syllables.forEachIndexed { index, s ->
                append(s.onset).append(s.vowel)
                // A nasal sign is "m" before p, b or m, "n" anywhere else.
                val next = syllables.getOrNull(index + 1)?.onset?.firstOrNull()
                append(s.coda.replace("\u0000", if (next == 'p' || next == 'b' || next == 'm') "m" else "n"))
            }
            append(passthrough)
        }
    }
}
