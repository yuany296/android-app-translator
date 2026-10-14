package com.manga.translator.store

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译历史「手动修正译文」纯逻辑单测（2026-10-15）。
 *
 * 两条不能破的规矩：
 * 1. 改写正文时只动 `translatedText` / `edited`，坐标等渲染字段必须原样保留；
 * 2. 索引越界、JSON 损坏一律返回 null，不抛。
 */
class HistoryBubbleEditTest {

    /** 造一份与真实历史同形的 bubblesJson（含坐标、bgType、layout）。 */
    private fun bubblesJson(vararg bubbles: Triple<String, String, String>): String {
        val arr = JSONArray()
        bubbles.forEach { (original, translated, bgType) ->
            arr.put(
                JSONObject()
                    .put("x", 10)
                    .put("y", 20)
                    .put("w", 30)
                    .put("h", 40)
                    .put("contentX", 11)
                    .put("contentY", 21)
                    .put("contentW", 31)
                    .put("contentH", 41)
                    .put("bgType", bgType)
                    .put("originalText", original)
                    .put("translatedText", translated)
                    .put("layout", "h")
            )
        }
        return JSONObject().put("bubbles", arr).toString()
    }

    @Test
    fun rewriteBubble_keepsRenderFieldsAndMarksEdited() {
        val json = bubblesJson(
            Triple("첫 문장", "第一句", "solid"),
            Triple("둘째 문장", "第二句", "none")
        )
        val (rewritten, original) =
            TranslationHistoryRepository.rewriteBubble(json, 1, "第二句（改）")!!

        assertEquals("둘째 문장", original)

        val arr = JSONObject(rewritten).getJSONArray("bubbles")
        assertEquals(2, arr.length())

        val target = arr.getJSONObject(1)
        assertEquals("第二句（改）", target.getString("translatedText"))
        assertTrue(target.getBoolean("edited"))
        // 坐标等渲染字段不能丢
        assertEquals(10, target.getInt("x"))
        assertEquals(41, target.getInt("contentH"))
        assertEquals("none", target.getString("bgType"))
        assertEquals("h", target.getString("layout"))

        // 没被改的那条保持原样，也不该被误标 edited
        val other = arr.getJSONObject(0)
        assertEquals("第一句", other.getString("translatedText"))
        assertFalse(other.has("edited"))
    }

    @Test
    fun rewriteBubble_returnsNullWhenIndexOutOfRange() {
        val json = bubblesJson(Triple("문장", "句子", "solid"))
        assertNull(TranslationHistoryRepository.rewriteBubble(json, 3, "x"))
        assertNull(TranslationHistoryRepository.rewriteBubble(json, -1, "x"))
    }

    @Test
    fun rewriteBubble_returnsNullOnBrokenJson() {
        assertNull(TranslationHistoryRepository.rewriteBubble("{ not json", 0, "x"))
        assertNull(TranslationHistoryRepository.rewriteBubble("", 0, "x"))
        assertNull(TranslationHistoryRepository.rewriteBubble("{}", 0, "x"))
    }

    @Test
    fun parseHistoryBubbles_readsTextAndEditedFlag() {
        val json = bubblesJson(
            Triple("원문", "原文", "solid"),
            Triple("고칠 문장", "要改的句子", "none")
        )
        val marked = TranslationHistoryRepository.rewriteBubble(json, 1, "新译文")!!.first

        val bubbles = parseHistoryBubbles(marked)
        assertEquals(2, bubbles.size)
        assertEquals("원문", bubbles[0].originalText)
        assertEquals("原文", bubbles[0].translatedText)
        assertFalse(bubbles[0].edited)
        assertEquals("新译文", bubbles[1].translatedText)
        assertTrue(bubbles[1].edited)
    }

    @Test
    fun parseHistoryBubbles_toleratesEmptyAndBrokenJson() {
        assertTrue(parseHistoryBubbles("").isEmpty())
        assertTrue(parseHistoryBubbles("   ").isEmpty())
        assertTrue(parseHistoryBubbles("{ broken").isEmpty())
        assertTrue(parseHistoryBubbles("{}").isEmpty())
    }
}
