package com.manga.translator.app

import com.manga.translator.store.SentenceOrigin
import com.manga.translator.store.SentenceTranslation
import com.manga.translator.store.TranslationPageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 历史详情文案（2026-11-02）：不要把本地翻译显示成云端模型。 */
class TranslationHistoryFormattingTest {

    private fun page(
        engine: String,
        provider: String = "openai",
        model: String = "gpt-4o-mini"
    ) = TranslationPageInfo(
        pageHash = "page-a",
        translateEngine = engine,
        translateProvider = provider,
        translateModel = model
    )

    @Test
    fun localEngineDoesNotDisplayCloudProviderOrModel() {
        val text = historyTranslateDetail(page("mlkit"))
        assertTrue(text.contains("ML Kit"))
        assertFalse(text.contains("OpenAI"))
        assertFalse(text.contains("gpt-4o-mini"))
    }

    @Test
    fun baiduEngineDoesNotDisplayCloudProviderOrModel() {
        val text = historyTranslateDetail(page("baidu"))
        assertTrue(text.contains("百度"))
        assertFalse(text.contains("OpenAI"))
        assertFalse(text.contains("gpt-4o-mini"))
    }

    @Test
    fun cloudEngineDisplaysProviderAndModel() {
        val text = historyTranslateDetail(page("cloud"))
        assertTrue(text.contains("云端"))
        assertTrue(text.contains("OpenAI"))
        assertTrue(text.contains("gpt-4o-mini"))
    }

    @Test
    fun missingPageInfoShowsUnknown() {
        assertEquals("未知", historyTranslateDetail(null))
    }

    @Test
    fun pagePositionFormatsOneBasedIndex() {
        assertEquals("第 2/10 页", formatPagePosition(1, 10))
        assertEquals("第 3 页", formatPagePosition(2, -1))
        assertEquals("共 8 页", formatPagePosition(-1, 8))
        assertEquals("页码未知", formatPagePosition(-1, -1))
    }

    @Test
    fun sourceLabelUsesOriginKind() {
        assertEquals(
            "网页文字 · lezhin.com",
            historySourceLabel("lezhin.com", "webpage-text:123")
        )
        assertEquals("截图", historySourceLabel("截图翻译", "abc"))
        assertEquals("漫画/图片", historySourceLabel("", "abc"))
    }

    @Test
    fun originLinesIncludePageHashAndHitCount() {
        val entry = SentenceTranslation(
            sourceLanguage = "ko",
            targetLanguage = "zh-cn",
            originalText = "안녕",
            translatedText = "你好",
            originLabel = "lezhin.com",
            originPageHash = "page-a",
            origins = listOf(
                SentenceOrigin("page-a", "lezhin.com", 100L, 200L, 3, "EXACT")
            )
        )
        val line = sentenceOriginLines(entry).single()
        assertTrue(line.contains("漫画/图片 · lezhin.com"))
        assertTrue(line.contains("本页命中 3 次"))
        assertTrue(line.contains("页 page-a"))
    }

    @Test
    fun legacyEntryWithoutOriginsStillListsOneSource() {
        val entry = SentenceTranslation(
            sourceLanguage = "ko",
            targetLanguage = "zh-cn",
            originalText = "안녕",
            translatedText = "你好",
            originLabel = "lezhin.com",
            originPageHash = "legacy-page",
            createdAtMs = 100L
        )
        assertEquals(1, sentenceOriginLines(entry).size)
    }
}
