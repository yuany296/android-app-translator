package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 收藏星预填（2026-11-03 用户要求：收藏网站时弹出网址/名称窗口）。 */
class FavoriteEditorPrefillTest {

    @Test
    fun prefillUsesFullPageUrlAndDomainLabel() {
        val editor = favoriteEditorForCurrentPage("https://page.kakao.com/content/123?tab=1")!!

        assertNull(editor.originalUrl)
        assertEquals("https://page.kakao.com/content/123?tab=1", editor.url)
        assertEquals("page.kakao.com", editor.label)
    }

    @Test
    fun prefillStripsWwwAndTrimsWhitespace() {
        val editor = favoriteEditorForCurrentPage("  http://www.example.com:8080/a/b  ")!!

        assertEquals("http://www.example.com:8080/a/b", editor.url)
        assertEquals("example.com", editor.label)
    }

    @Test
    fun nonHttpPageCannotBeFavorited() {
        assertNull(favoriteEditorForCurrentPage(""))
        assertNull(favoriteEditorForCurrentPage("about:blank"))
        assertNull(favoriteEditorForCurrentPage("data:text/html,hi"))
        assertNull(favoriteEditorForCurrentPage("intent://scan/#Intent;end"))
    }
}
