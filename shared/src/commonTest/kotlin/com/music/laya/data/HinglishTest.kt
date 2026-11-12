package com.music.laya.data

import kotlin.test.Test
import kotlin.test.assertEquals

class HinglishTest {
    @Test
    fun songTitlesComeOutAsTyped() {
        assertEquals("tu mil jaye", Hinglish.of("तू मिल जाए"))
        assertEquals("tum hi ho", Hinglish.of("तुम ही हो"))
        assertEquals("kal ho na ho", Hinglish.of("कल हो ना हो"))
        assertEquals("channa mereya", Hinglish.of("चन्ना मेरेया"))
        assertEquals("tera ban jaunga", Hinglish.of("तेरा बन जाऊंगा"))
        assertEquals("dhadkan", Hinglish.of("धड़कन"))
        assertEquals("samajhna", Hinglish.of("समझना"))
        assertEquals("hindi", Hinglish.of("हिंदी"))
        assertEquals("chand", Hinglish.of("चाँद"))
        assertEquals("zindagi", Hinglish.of("ज़िंदगी"))
        assertEquals("kambakht", Hinglish.of("कंबख्त"))
    }

    @Test
    fun otherTextIsLeftAlone() {
        assertEquals("A Thousand Years", Hinglish.of("A Thousand Years"))
        assertEquals("arijit ke gane", Hinglish.of("arijit के गाने"))
        assertEquals("", Hinglish.of(""))
    }
}
