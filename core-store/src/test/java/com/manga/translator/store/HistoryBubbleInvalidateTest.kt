package com.manga.translator.store

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译历史「作废」纯逻辑单测（2026-10-15）。
 *
 * 用户反馈：「作废某个译文后，还是可以修改译文 —— 我以为它还能用。」
 * 根因是作废以前只删句子缓存、历史里没有任何可观测状态。这里钉住四条规矩：
 * 1. 作废**只加标记**，旧译文原样留档（用户还要靠它追溯「当时翻成什么」）；
 * 2. 标记可持久化解析（老条目没有该字段 → 默认 false，不能误报「已作废」）；
 * 3. 重复作废幂等（不会越写越多）；
 * 4. 手写译文 = 这句重新生效 → 清掉作废标记，并把「改之前是否作废」回报给调用方。
 */
class HistoryBubbleInvalidateTest {

    private fun bubblesJson(vararg bubbles: Triple<String, String, Boolean>): String {
        val arr = JSONArray()
        bubbles.forEach { (original, translated, invalidated) ->
            arr.put(
                JSONObject()
                    .put("x", 10)
                    .put("y", 20)
                    .put("w", 30)
                    .put("h", 40)
                    .put("bgType", "solid")
                    .put("originalText", original)
                    .put("translatedText", translated)
                    .put("invalidated", invalidated)
                    .put("layout", "h")
            )
        }
        return JSONObject().put("bubbles", arr).toString()
    }

    @Test
    fun markInvalidatedInJson_onlyMarksTargetIndexAndKeepsOldTranslation() {
        val json = bubblesJson(
            Triple("첫 문장", "第一句", false),
            Triple("둘째 문장", "第二句", false)
        )
        val (marked, count) = TranslationHistoryRepository.markInvalidatedInJson(json, 1)!!

        assertEquals(1, count)
        val arr = JSONObject(marked).getJSONArray("bubbles")
        assertFalse(arr.getJSONObject(0).optBoolean("invalidated", false))
        assertTrue(arr.getJSONObject(1).getBoolean("invalidated"))
        // 旧译文必须留着（作废 ≠ 删记录）
        assertEquals("第二句", arr.getJSONObject(1).getString("translatedText"))
        // 坐标等渲染字段不受影响
        assertEquals(10, arr.getJSONObject(1).getInt("x"))
        assertEquals("h", arr.getJSONObject(1).getString("layout"))
    }

    @Test
    fun markInvalidatedInJson_marksEveryBubbleWhenIndexIsNull() {
        val json = bubblesJson(
            Triple("가", "甲", false),
            Triple("나", "乙", true)
        )
        val (marked, count) = TranslationHistoryRepository.markInvalidatedInJson(json, null)!!

        // 已经标过的不重复计数（幂等）
        assertEquals(1, count)
        val arr = JSONObject(marked).getJSONArray("bubbles")
        assertTrue(arr.getJSONObject(0).getBoolean("invalidated"))
        assertTrue(arr.getJSONObject(1).getBoolean("invalidated"))
    }

    @Test
    fun markInvalidatedInJson_isIdempotentOnSameBubble() {
        val json = bubblesJson(Triple("문장", "句子", false))
        val once = TranslationHistoryRepository.markInvalidatedInJson(json, 0)!!
        val twice = TranslationHistoryRepository.markInvalidatedInJson(once.first, 0)!!

        assertEquals(1, once.second)
        assertEquals(0, twice.second)
        assertTrue(JSONObject(twice.first).getJSONArray("bubbles").getJSONObject(0).getBoolean("invalidated"))
    }

    @Test
    fun markInvalidatedInJson_returnsNullOnBrokenJsonOrBadIndex() {
        val json = bubblesJson(Triple("문장", "句子", false))
        assertNull(TranslationHistoryRepository.markInvalidatedInJson("{ not json", 0))
        assertNull(TranslationHistoryRepository.markInvalidatedInJson("", 0))
        assertNull(TranslationHistoryRepository.markInvalidatedInJson("{}", 0))
        assertNull(TranslationHistoryRepository.markInvalidatedInJson(json, 5))
        assertNull(TranslationHistoryRepository.markInvalidatedInJson(json, -1))
    }

    @Test
    fun parseHistoryBubbles_readsInvalidatedFlag() {
        val json = bubblesJson(
            Triple("원문", "原文", false),
            Triple("작폐 문장", "已作废的句子", true)
        )
        val bubbles = parseHistoryBubbles(json)

        assertEquals(2, bubbles.size)
        assertFalse(bubbles[0].invalidated)
        assertTrue(bubbles[1].invalidated)
        assertEquals("已作废的句子", bubbles[1].translatedText)
    }

    @Test
    fun parseHistoryBubbles_defaultsInvalidatedFalseForOldEntries() {
        // 老历史 JSON 里没有 invalidated 键（作废功能之前写的），不能当成「已作废」。
        val legacy = JSONObject()
            .put(
                "bubbles",
                JSONArray().put(
                    JSONObject()
                        .put("originalText", "문장")
                        .put("translatedText", "句子")
                        .put("edited", true)
                )
            )
            .toString()

        val bubbles = parseHistoryBubbles(legacy)
        assertEquals(1, bubbles.size)
        assertFalse(bubbles[0].invalidated)
        assertTrue(bubbles[0].edited)
    }

    @Test
    fun rewriteBubble_clearsInvalidatedAndReportsPreviousState() {
        val json = bubblesJson(Triple("문장", "旧译文", true))
        val (rewritten, original, wasInvalidated) =
            TranslationHistoryRepository.rewriteBubble(json, 0, "新译文")!!

        assertEquals("문장", original)
        assertTrue(wasInvalidated)

        val bubble = JSONObject(rewritten).getJSONArray("bubbles").getJSONObject(0)
        assertEquals("新译文", bubble.getString("translatedText"))
        assertTrue(bubble.getBoolean("edited"))
        // 手写译文 = 重新生效：不能再挂着「已作废」，否则界面与缓存各说各话。
        assertFalse(bubble.getBoolean("invalidated"))
    }

    @Test
    fun rewriteBubble_reportsNotInvalidatedForFreshBubble() {
        val json = bubblesJson(Triple("문장", "旧译文", false))
        val (_, _, wasInvalidated) = TranslationHistoryRepository.rewriteBubble(json, 0, "新译文")!!
        assertFalse(wasInvalidated)
    }
}
