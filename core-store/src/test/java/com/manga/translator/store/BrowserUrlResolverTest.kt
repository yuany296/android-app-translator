package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 地址栏输入归一化单测（2026-09-28）：完整地址 / 裸域名 / IP / 自然语言关键词分流，
 * 关键词走用户选定的搜索引擎（默认必应，不再写死 Google）。
 */
class BrowserUrlResolverTest {

    @Test
    fun keepsExplicitSchemes() {
        assertEquals(
            "https://page.kakao.com/content/1",
            BrowserUrlResolver.resolve("https://page.kakao.com/content/1")
        )
        assertEquals("http://a.com", BrowserUrlResolver.resolve("  http://a.com "))
        assertEquals("HTTPS://A.com", BrowserUrlResolver.resolve("HTTPS://A.com"))
    }

    @Test
    fun bareHostGetsHttpsScheme() {
        assertEquals("https://page.kakao.com", BrowserUrlResolver.resolve("page.kakao.com"))
        assertEquals("https://m.webtoons.com/ep1", BrowserUrlResolver.resolve("m.webtoons.com/ep1"))
        assertEquals("https://192.168.1.5:8080/x", BrowserUrlResolver.resolve("192.168.1.5:8080/x"))
        assertEquals("https://localhost:3000", BrowserUrlResolver.resolve("localhost:3000"))
    }

    @Test
    fun plainKeywordsGoToSelectedSearchEngine() {
        assertEquals(
            "https://cn.bing.com/search?q=%E7%81%AB%E5%BD%B1",
            BrowserUrlResolver.resolve("火影")
        )
        assertEquals(
            "https://www.baidu.com/s?wd=%E7%81%AB%E5%BD%B1",
            BrowserUrlResolver.resolve("火影", BrowserSearchEngine.BAIDU)
        )
        assertEquals(
            "https://www.google.com/search?q=one+piece",
            BrowserUrlResolver.resolve("one piece", BrowserSearchEngine.GOOGLE)
        )
    }

    @Test
    fun emptyInputResolvesToNull() {
        assertNull(BrowserUrlResolver.resolve(""))
        assertNull(BrowserUrlResolver.resolve("   "))
    }

    @Test
    fun searchUrlAlwaysEncodesKeyword() {
        assertEquals(
            "https://cn.bing.com/search?q=a%26b",
            BrowserUrlResolver.searchUrl(" a&b ", BrowserSearchEngine.BING)
        )
    }
}
