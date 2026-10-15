package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // ---- 同一文档判定（2026-10-15，Kakao「翻到一半停下来」）----

    @Test
    fun sameDocument_ignoresViewerPositionQueryAndFragment() {
        val session = "https://page.kakao.com/content/123/viewer/456"
        // 阅读器滚动时写进地址的位置参数：不算跳转。
        assertTrue(BrowserUrlResolver.isSameDocument(session, "$session?cut=4"))
        assertTrue(BrowserUrlResolver.isSameDocument(session, "$session#cut-4"))
        assertTrue(BrowserUrlResolver.isSameDocument(session, "$session?cut=4#cut-4"))
        assertTrue(BrowserUrlResolver.isSameDocument(session, "$session/"))
    }

    @Test
    fun sameDocument_stillDetectsRealNavigation() {
        val session = "https://page.kakao.com/content/123/viewer/456"
        // 换话 / 换站：路径或主机变了 → 必须仍然判为跳转。
        assertFalse(BrowserUrlResolver.isSameDocument(session, "https://page.kakao.com/content/123/viewer/789"))
        assertFalse(BrowserUrlResolver.isSameDocument(session, "https://page.kakao.com/home"))
        assertFalse(BrowserUrlResolver.isSameDocument(session, "https://www.lezhin.com/ko/comic/x/1"))
    }

    @Test
    fun sameDocument_blankOrNullIsNeverSame() {
        assertFalse(BrowserUrlResolver.isSameDocument(null, "https://a.com/x"))
        assertFalse(BrowserUrlResolver.isSameDocument("https://a.com/x", ""))
        assertFalse(BrowserUrlResolver.isSameDocument("about:blank", "https://a.com/x"))
    }
}
