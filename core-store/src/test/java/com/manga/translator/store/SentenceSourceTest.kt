package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Test

/** 翻译历史「网页文字 / 漫画图片」筛选的分类规则（2026-10-31）。 */
class SentenceSourceTest {

    private fun entry(pageHash: String, label: String = "") = SentenceTranslation(
        sourceLanguage = "ko",
        targetLanguage = "zh-cn",
        originalText = "테스트",
        translatedText = "测试",
        originLabel = label,
        originPageHash = pageHash
    )

    @Test
    fun `webpage-text 归为网页文字`() {
        assertEquals(SentenceSource.WEBPAGE_TEXT, entry("webpage-text").sourceKind())
    }

    @Test
    fun `新网页会话前缀也归为网页文字`() {
        assertEquals(
            SentenceSource.WEBPAGE_TEXT,
            entry("webpage-text:1793450000000", "lezhin.com").sourceKind()
        )
    }

    @Test
    fun `真实页面哈希归为漫画图片`() {
        assertEquals(SentenceSource.IMAGE, entry("c1e51da30080", "lezhin.com").sourceKind())
    }

    @Test
    fun `截图翻译归为漫画图片`() {
        assertEquals(SentenceSource.IMAGE, entry("abc123", "截图翻译").sourceKind())
    }

    @Test
    fun `旧数据空哈希归为漫画图片`() {
        assertEquals(SentenceSource.IMAGE, entry("").sourceKind())
    }
}
