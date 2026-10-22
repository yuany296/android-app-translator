package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本翻译提示词（2026-10-15 对齐参考插件的重写）。
 *
 * 用户反馈「插件的翻译效果比手机上的好」—— 对齐后的三条主干在这里钉住：
 * system 角色声明（漫画台词 + 源/目标语言）、质量规则、术语表按批过滤。
 */
class TranslatePromptTest {

    private val client = OpenAiVisionClient()

    @Test
    fun systemPrompt_declaresMangaDialogueRoleAndLanguagePair() {
        val prompt = client.buildTranslateSystemPrompt("ja", "zh-cn")
        assertTrue(prompt.contains("manga dialogue translator"))
        assertTrue(prompt.contains("Translate from Japanese to Simplified Chinese"))
        assertTrue(prompt.contains("JSON"))
    }

    @Test
    fun userPrompt_containsQualityRulesAndBatchCount() {
        val prompt = client.buildTranslatePrompt(
            texts = listOf("케이지라고요!!", "접속종료를 하려면 죽거나"),
            sourceLanguageId = "ko",
            targetLanguageId = "zh-cn"
        )
        // 不增删信息 / 语气口语化 / 保留引号 / 专有名词不留原文 / 拟声词不丢
        assertTrue(prompt.contains("do not add information"))
        assertTrue(prompt.contains("natural spoken Simplified Chinese"))
        assertTrue(prompt.contains("quoted"))
        assertTrue(prompt.contains("never left in the source script"))
        assertTrue(prompt.contains("Onomatopoeia"))
        // 批内数量（拆批重试时每批的 prompt 各自带自己的数量）
        assertTrue(prompt.contains("input length = 2"))
        // 旧版的「全量术语表照抄」不应在无词条时出现
        assertFalse(prompt.contains("glossary"))
    }

    @Test
    fun userPrompt_injectsOnlyGlossaryTermsPresentInThisBatch() {
        val glossary = listOf(
            "케이지" to "笼子",
            "레온" to "莱昂",
            "미수" to "未遂"
        )
        val prompt = client.buildTranslatePrompt(
            texts = listOf("케이지라고요!!"),
            sourceLanguageId = "ko",
            targetLanguageId = "zh-cn",
            glossary = glossary
        )
        // 这批真正出现的词条要注入……
        assertTrue(prompt.contains("케이지=笼子"))
        // ……没出现的词条不能整表照抄（稀释指令 = 插件对照出的第二条差距）
        assertFalse(prompt.contains("레온=莱昂"))
        assertFalse(prompt.contains("미수=未遂"))
    }

    @Test
    fun userPrompt_injectsFullPageContextWhenBatchIsOnlyMissingLines() {
        // 2026-10-18 用户选 A：整页台词是上下文，本次只翻未命中的那一句。
        val page = listOf("첫 줄", "둘째 줄", "셋째 줄")
        val prompt = client.buildTranslatePrompt(
            texts = listOf("둘째 줄"),
            sourceLanguageId = "ko",
            targetLanguageId = "zh-cn",
            pageContext = page
        )
        assertTrue(prompt.contains("context only"))
        assertTrue(prompt.contains("[1] 첫 줄"))
        assertTrue(prompt.contains("[3] 셋째 줄"))
        assertTrue(prompt.contains("do not translate the context lines"))
        // 本批仍然只要求 1 条输出，不能把上下文也翻出来。
        assertTrue(prompt.contains("input length = 1"))
    }

    @Test
    fun userPrompt_doesNotDuplicateContextWhenBatchIsWholePage() {
        val texts = listOf("첫 줄", "둘째 줄")
        val prompt = client.buildTranslatePrompt(
            texts = texts,
            sourceLanguageId = "ko",
            targetLanguageId = "zh-cn",
            pageContext = texts
        )
        assertFalse(prompt.contains("context only"))
    }

    @Test
    fun pageContextBlock_capsLinesAndMarksTruncation() {
        val page = (1..120).map { "line$it" }
        val block = requireNotNull(client.buildPageContextBlock(page, listOf("line1")))
        assertTrue(block.contains("context truncated"))
        assertEquals(80, Regex("\\[\\d+\\]").findAll(block).count())
    }

    @Test
    fun languageDisplayName_coversSupportedIds() {
        assertEquals("Simplified Chinese", client.languageDisplayName("zh-cn"))
        assertEquals("Traditional Chinese", client.languageDisplayName("zh-tw"))
        assertEquals("English", client.languageDisplayName("en"))
        assertEquals("Korean", client.languageDisplayName("ko"))
        assertEquals("Japanese", client.languageDisplayName("ja"))
        // 未知 id 兜底到默认目标语言，不能抛异常
        assertEquals("Simplified Chinese", client.languageDisplayName("fr"))
    }
}
