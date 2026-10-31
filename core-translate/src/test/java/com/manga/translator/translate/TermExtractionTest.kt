package com.manga.translator.translate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 句子级 AI 术语提取（2026-10-31）。
 *
 * 这里钉住两件事，因为它们在真机上都很难用眼睛验证：
 * 1. 提示词必须包含「原文逐字照抄」——否则提取出来的 term 和原文差一个字符，
 *    注入翻译时匹配不上，用户看到的现象是「明明加进术语表了却不生效」。
 * 2. 解析必须容忍模型的几种常见变形（`terms`/`items`/`translations` 键、`s`/`source` 别名、
 *    外面套一层 ```json 围栏），并在遇到垃圾输出时返回空列表而不是崩溃。
 */
class TermExtractionTest {

    private val client = OpenAiVisionClient()

    private fun completion(content: String): String = buildString {
        append("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":")
        append(JSONObject.quote(content))
        append("}}]}")
    }

    @Test
    fun buildTermPrompt_pinsHardConstraints() {
        val prompt = client.buildTermPrompt(
            sourceLanguageId = "ko",
            targetLanguageId = "zh-cn",
            sentenceCount = 7
        )
        // 输入规模要说清楚，否则模型会自己编句子。
        assertTrue(prompt.contains("7 comic dialogue line"))
        assertTrue(prompt.contains("Korean"))
        assertTrue(prompt.contains("Simplified Chinese"))
        // 三条硬约束
        assertTrue(prompt.contains("appear literally in the input"))
        assertTrue(prompt.contains("Copy the source spelling exactly"))
        assertTrue(prompt.contains("Give the best Simplified Chinese rendering"))
        assertTrue(prompt.contains("Ignore ordinary words"))
        // 只要 JSON，只给一个 schema
        assertTrue(prompt.contains("\"terms\""))
        assertTrue(prompt.startsWith("The input is"))
    }

    @Test
    fun parseTermSuggestions_readsTermsSchema() {
        val raw = completion(
            """{"terms":[
                {"s":"강백호","t":"姜白虎","k":"person"},
                {"s":"북산고","t":"北山高中","k":"org"}
            ]}"""
        )
        val terms = client.parseTermSuggestions(raw)
        assertEquals(2, terms.size)
        assertEquals("강백호", terms[0].source)
        assertEquals("姜白虎", terms[0].target)
        assertEquals("person", terms[0].kind)
        assertEquals("org", terms[1].kind)
    }

    @Test
    fun parseTermSuggestions_toleratesFenceAndAliasKeys() {
        val raw = completion(
            "```json\n" +
                """{"items":[{"source":"서태웅","translation":"徐太雄"}]}""" +
                "\n```"
        )
        val terms = client.parseTermSuggestions(raw)
        assertEquals(1, terms.size)
        assertEquals("서태웅", terms[0].source)
        assertEquals("徐太雄", terms[0].target)
        // 没给 k 时回落 other，界面按「其他」显示。
        assertEquals("other", terms[0].kind)
    }

    @Test
    fun parseTermSuggestions_acceptsBareArray() {
        val raw = completion("""[{"s":"정대만","t":"郑大万","type":"person"}]""")
        val terms = client.parseTermSuggestions(raw)
        assertEquals(1, terms.size)
        assertEquals("person", terms[0].kind)
    }

    @Test
    fun parseTermSuggestions_dropsSelfTranslationAndDuplicateSource() {
        val raw = completion(
            """{"terms":[
                {"s":"한나","t":"한나"},
                {"s":"한나","t":"韩娜"},
                {"s":"한나","t":"汉娜"},
                {"s":"","t":"空原文"}
            ]}"""
        )
        val terms = client.parseTermSuggestions(raw)
        // 译文==原文 丢弃；同一 source 只留第一条；空原文丢弃。
        assertEquals(1, terms.size)
        assertEquals("韩娜", terms[0].target)
    }

    @Test
    fun parseTermSuggestions_returnsEmptyOnGarbage() {
        assertTrue(client.parseTermSuggestions("").isEmpty())
        assertTrue(client.parseTermSuggestions(completion("这不是 JSON")).isEmpty())
        assertTrue(client.parseTermSuggestions("{\"choices\":[]}").isEmpty())
    }

    @Test
    fun parseTermSuggestions_capsResultCount() {
        val items = (1..40).joinToString(",") { """{"s":"name$it","t":"名字$it"}""" }
        val terms = client.parseTermSuggestions(completion("""{"terms":[$items]}"""))
        // 上限存在的意义：候选太多时弹层滚不完，用户也不会逐个确认。
        assertEquals(20, terms.size)
    }

    @Test
    fun parseTermSuggestions_dropsTruncatedFragmentOfSameTerm() {
        // 实测样本（2026-10-31 模拟器，lezhin 韩漫）：OCR 把一句长台词切碎，
        // 模型把整句和尾巴当成两个术语，译名完全相同。
        val raw = completion(
            """{"terms":[
                {"s":"데뷔못하면 죽는병걸림","t":"不能出道就会死的病"},
                {"s":"죽는병걸림","t":"会死的病"},
                {"s":"박문대","t":"朴文大"}
            ]}"""
        )
        val terms = client.parseTermSuggestions(raw)
        assertEquals(2, terms.size)
        // 保留最长的那条，且顺序维持模型给出的顺序。
        assertEquals("데뷔못하면 죽는병걸림", terms[0].source)
        assertEquals("박문대", terms[1].source)
    }

    @Test
    fun parseTermSuggestions_keepsDifferentNamesSharingSameRendering() {
        // 两个不同角色恰好译名相同（同音字）时不能当成碎片删掉。
        val raw = completion(
            """{"terms":[
                {"s":"한소희","t":"小美"},
                {"s":"한소미","t":"小美"}
            ]}"""
        )
        assertEquals(2, client.parseTermSuggestions(raw).size)
    }
}
