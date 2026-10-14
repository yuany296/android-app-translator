package com.manga.translator.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网页文字翻译（2026-10-14）：收集结果解析 + 切批。 */
class BrowserWebpageTextScriptTest {

    @Test
    fun parseCollectResultReadsItemsAndDropsBlank() {
        val payload = JSONObject()
            .put(
                "items",
                JSONArray()
                    .put(JSONObject().put("id", 0).put("text", "첫 번째 문장"))
                    .put(JSONObject().put("id", 1).put("text", "  "))
                    .put(JSONObject().put("id", 2).put("text", "둘째 문장"))
            )
        val items = BrowserWebpageTextScript.parseCollectResult(payload)
        assertEquals(listOf(0, 2), items.map { it.id })
        assertEquals("첫 번째 문장", items.first().text)
    }

    @Test
    fun parseCollectResultHandlesMissingOrNull() {
        assertTrue(BrowserWebpageTextScript.parseCollectResult(null).isEmpty())
        assertTrue(BrowserWebpageTextScript.parseCollectResult(JSONObject()).isEmpty())
    }

    @Test
    fun batchesSplitByCountThenChars() {
        val items = (0 until 50).map { BrowserWebpageTextScript.Item(it, "문장$it") }
        val batches = BrowserWebpageTextScript.buildBatches(items, maxItems = 10, maxChars = 1000)
        assertEquals(5, batches.size)
        assertEquals(items.size, batches.sumOf { it.size })
    }

    @Test
    fun batchesSplitByCharsBeforeCount() {
        // 每段 300 字符：第 6 段会爆字符预算（300×6=1800 > 1600），
        // 所以每批 5 段（1500 ≤ 1600）而不是 24 段。
        val items = (0 until 24).map { BrowserWebpageTextScript.Item(it, "가".repeat(300)) }
        val batches = BrowserWebpageTextScript.buildBatches(items, maxItems = 24, maxChars = 1600)
        assertEquals(5, batches.size)
        assertTrue(batches.dropLast(1).all { it.size == 5 })
        assertEquals(4, batches.last().size)
    }

    @Test
    fun oversizeItemGetsItsOwnBatch() {
        val big = BrowserWebpageTextScript.Item(0, "가".repeat(5000))
        val small = BrowserWebpageTextScript.Item(1, "짧은 문장")
        val batches = BrowserWebpageTextScript.buildBatches(listOf(big, small))
        assertEquals(2, batches.size)
        assertEquals(listOf(0), batches[0].map { it.id })
        assertEquals(listOf(1), batches[1].map { it.id })
    }

    @Test
    fun emptyInputYieldsNoBatches() {
        assertTrue(BrowserWebpageTextScript.buildBatches(emptyList()).isEmpty())
    }

    @Test
    fun applyPayloadCarriesIdAndTranslation() {
        val payload = BrowserWebpageTextScript.applyPayload(listOf(3 to "你好", 7 to "世界"))
        val array = JSONArray(payload)
        assertEquals(2, array.length())
        assertEquals(3, array.getJSONObject(0).getInt("id"))
        assertEquals("你好", array.getJSONObject(0).getString("t"))
    }

    @Test
    fun actionScriptsReferenceRegistryApi() {
        // JS 拼装里带上正确的入口名（防止改 API 名后只改了一处）。
        assertTrue(BrowserWebpageTextScript.collectJs().contains("__mtWebpageText.collect("))
        assertTrue(BrowserWebpageTextScript.applyJs("[]").contains("__mtWebpageText.apply("))
        assertTrue(BrowserWebpageTextScript.restoreJs().contains("__mtWebpageText.restore()"))
        assertTrue(BrowserWebpageTextScript.installJs().contains("CREATE-TREE-WALKER".lowercase()) ||
            BrowserWebpageTextScript.installJs().contains("createTreeWalker"))
    }
}
