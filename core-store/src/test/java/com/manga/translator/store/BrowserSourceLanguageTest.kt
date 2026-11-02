package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserSourceLanguageTest {

    @Test
    fun acceptsAutoAndConcreteLanguages() {
        assertEquals(BrowserSourceLanguage.AUTO, BrowserSourceLanguage.normalize("auto"))
        assertEquals(BrowserSourceLanguage.AUTO, BrowserSourceLanguage.normalize("AUTO"))
        assertEquals("ja", BrowserSourceLanguage.normalize("ja"))
        assertEquals("ko", BrowserSourceLanguage.normalize("KO"))
        assertEquals("zh-cn", BrowserSourceLanguage.normalize("zh-CN"))
    }

    @Test
    fun unknownValueFallsBackToAuto() {
        assertEquals(BrowserSourceLanguage.AUTO, BrowserSourceLanguage.normalize(""))
        assertEquals(BrowserSourceLanguage.AUTO, BrowserSourceLanguage.normalize("fr"))
        assertEquals(BrowserSourceLanguage.AUTO, BrowserSourceLanguage.normalize("中文"))
    }
}
