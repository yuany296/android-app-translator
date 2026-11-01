package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageLanguageHintTest {

    @Test
    fun normalizesHtmlLanguageTagsToAppLanguageIds() {
        assertEquals("ja", normalizePageLanguageHint("ja-JP"))
        assertEquals("ko", normalizePageLanguageHint("ko_KR"))
        assertEquals("en", normalizePageLanguageHint("en-US"))
        assertEquals("zh-cn", normalizePageLanguageHint("zh-Hans"))
        assertEquals("zh-cn", normalizePageLanguageHint("zh-CN"))
        assertEquals("zh-tw", normalizePageLanguageHint("zh-Hant"))
        assertEquals("zh-tw", normalizePageLanguageHint("zh-TW"))
    }

    @Test
    fun ignoresEmptyUnknownAndMalformedHints() {
        assertNull(normalizePageLanguageHint(""))
        assertNull(normalizePageLanguageHint("und"))
        assertNull(normalizePageLanguageHint("fr-FR"))
        assertEquals("ja", normalizePageLanguageHint("ja-JP, en;q=0.8"))
    }
}
