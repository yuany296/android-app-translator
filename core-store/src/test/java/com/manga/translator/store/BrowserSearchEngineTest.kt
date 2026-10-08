package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内嵌浏览器搜索引擎纯逻辑单测（2026-09-28 用户反馈「搜索写死 Google，国内打不开」）：
 * 默认必应可直连、关键词填入模板、未知/空 id 回退默认。
 */
class BrowserSearchEngineTest {

    @Test
    fun defaultEngineIsBingBecauseGoogleIsUnreachableInMainlandChina() {
        assertEquals(BrowserSearchEngine.BING, BrowserSearchEngine.DEFAULT)
        assertEquals(BrowserSearchEngine.BING, BrowserSearchEngine.fromId(null))
        assertEquals(BrowserSearchEngine.BING, BrowserSearchEngine.fromId(""))
        assertEquals(BrowserSearchEngine.BING, BrowserSearchEngine.fromId("duckduckgo"))
    }

    @Test
    fun fromIdIsCaseInsensitiveAndTrimmed() {
        assertEquals(BrowserSearchEngine.BAIDU, BrowserSearchEngine.fromId(" Baidu "))
        assertEquals(BrowserSearchEngine.GOOGLE, BrowserSearchEngine.fromId("GOOGLE"))
    }

    @Test
    fun buildSearchUrlFillsEncodedQuery() {
        assertEquals(
            "https://cn.bing.com/search?q=%E7%81%AB%E5%BD%B1",
            BrowserSearchEngine.BING.buildSearchUrl("%E7%81%AB%E5%BD%B1")
        )
        assertEquals(
            "https://www.baidu.com/s?wd=hello",
            BrowserSearchEngine.BAIDU.buildSearchUrl("hello")
        )
        assertEquals(
            "https://www.google.com/search?q=hello",
            BrowserSearchEngine.GOOGLE.buildSearchUrl("hello")
        )
    }

    @Test
    fun everyEngineHasDistinctIdAndHttpsTemplate() {
        val ids = BrowserSearchEngine.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        BrowserSearchEngine.entries.forEach { engine ->
            assertEquals(true, engine.urlTemplate.startsWith("https://"))
            assertEquals(1, engine.urlTemplate.split("%s").size - 1)
        }
    }
}
