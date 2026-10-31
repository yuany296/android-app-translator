package com.manga.translator.translate

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenAiVisionClientTest {
    private val client = OpenAiVisionClient()

    @Test
    fun buildIndexedInputJson_carriesZeroBasedIndexes() {
        // 真机事故的输入侧修复：每条输入必须自带 i，模型不用自己数第几个。
        val array = JSONArray(client.buildIndexedInputJson(listOf("첫째", "둘째")))
        assertEquals(2, array.length())
        assertEquals(0, array.getJSONObject(0).getInt("i"))
        assertEquals("첫째", array.getJSONObject(0).getString("text"))
        assertEquals(1, array.getJSONObject(1).getInt("i"))
        assertEquals("둘째", array.getJSONObject(1).getString("text"))
    }

    @Test
    fun shouldMergeBaiduBubbles_allowsStrongVerticalWhenLayoutMismatch() {
        val strongVertical = bubble(
            x = 60f,
            y = 8f,
            w = 4f,
            h = 20f,
            layout = "vertical",
            text = "colA"
        )
        val ambiguousColumn = bubble(
            x = 53f,
            y = 10f,
            w = 7f,
            h = 8f,
            layout = "horizontal",
            text = "colB"
        )

        assertTrue(shouldMerge(strongVertical, ambiguousColumn))
    }

    @Test
    fun shouldMergeBaiduBubbles_relaxesVerticalColumnOverlapThreshold() {
        val firstColumn = bubble(
            x = 60f,
            y = 10f,
            w = 3f,
            h = 10f,
            layout = "vertical",
            text = "top"
        )
        val secondColumn = bubble(
            x = 53f,
            y = 17.4f,
            w = 3f,
            h = 10f,
            layout = "vertical",
            text = "tail"
        )

        assertTrue(shouldMerge(firstColumn, secondColumn))
    }

    @Test
    fun shouldMergeBaiduBubbles_relaxesVerticalStackOverlapThreshold() {
        val upper = bubble(
            x = 40f,
            y = 10f,
            w = 4f,
            h = 10f,
            layout = "vertical",
            text = "upper"
        )
        val lower = bubble(
            x = 42f,
            y = 23f,
            w = 4f,
            h = 10f,
            layout = "vertical",
            text = "lower"
        )

        assertTrue(shouldMerge(upper, lower))
    }

    @Test
    fun mergeBaiduBubbleGroup_keepsVerticalLayoutForParallelColumns() {
        val merged = mergeGroup(
            bubble(
                x = 60f,
                y = 10f,
                w = 4f,
                h = 18f,
                layout = "vertical",
                text = "right"
            ),
            bubble(
                x = 52f,
                y = 12f,
                w = 4f,
                h = 15f,
                layout = "vertical",
                text = "left"
            )
        )

        assertEquals("vertical", merged.layout)
        assertEquals("rightleft", merged.originalText)
    }

    @Test
    fun mergeBaiduBubbleGroup_preservesTighterContentBounds() {
        val merged = mergeGroup(
            bubble(
                x = 58f,
                y = 8f,
                w = 8f,
                h = 22f,
                layout = "vertical",
                text = "right",
                contentX = 60f,
                contentY = 10f,
                contentW = 4f,
                contentH = 18f
            ),
            bubble(
                x = 50f,
                y = 9f,
                w = 8f,
                h = 20f,
                layout = "vertical",
                text = "left",
                contentX = 52f,
                contentY = 12f,
                contentW = 4f,
                contentH = 15f
            )
        )

        assertEquals(52f, merged.contentX, 0.01f)
        assertEquals(10f, merged.contentY, 0.01f)
        assertEquals(12f, merged.contentW, 0.01f)
        assertEquals(18f, merged.contentH, 0.01f)
    }

    @Test
    fun heuristicMergeBaiduBubbles_prefersConservativeSplitForAmbiguousColumns() {
        val firstColumn = bubble(
            x = 60f,
            y = 10f,
            w = 4f,
            h = 12f,
            layout = "vertical",
            text = "right"
        )
        val secondColumn = bubble(
            x = 51.5f,
            y = 20.7f,
            w = 4f,
            h = 6f,
            layout = "vertical",
            text = "left"
        )

        val merged = mergeHeuristically(firstColumn, secondColumn)

        assertEquals(2, merged.size)
    }

    @Test
    fun shouldMergeBaiduBubbles_rejectsSeparatedAdjacentBubbles() {
        val leftBubble = bubble(
            x = 14f,
            y = 20f,
            w = 14f,
            h = 26f,
            layout = "horizontal",
            text = "left"
        )
        val rightBubble = bubble(
            x = 34f,
            y = 20f,
            w = 14f,
            h = 26f,
            layout = "horizontal",
            text = "right"
        )

        assertFalse(shouldMerge(leftBubble, rightBubble))
    }

    @Test
    fun isTransientHttpCode_classifiesNetworkAndServerErrors() {
        // 0 = 请求没走到响应（连接失败/超时）→ 值得重试。
        assertTrue(client.isTransientHttpCode(0))
        assertTrue(client.isTransientHttpCode(408))
        assertTrue(client.isTransientHttpCode(425))
        assertTrue(client.isTransientHttpCode(429))
        assertTrue(client.isTransientHttpCode(500))
        assertTrue(client.isTransientHttpCode(503))
        // 参数/鉴权类错误重试没有意义（会走降级阶梯或直接失败）。
        assertFalse(client.isTransientHttpCode(400))
        assertFalse(client.isTransientHttpCode(401))
        assertFalse(client.isTransientHttpCode(404))
    }

    @Test
    fun parseTranslations_parsesTranslationsArray() {
        val parsed = client.parseTranslations(
            completion("{\"translations\":[\"甲\",\"乙\"]}"),
            listOf("가", "나")
        )
        assertFalse(parsed.parseFailed)
        assertEquals(listOf("甲", "乙"), parsed.values)
    }

    @Test
    fun parseTranslations_handlesFencedJson() {
        val parsed = client.parseTranslations(
            completion("```json\n{\"translations\":[\"甲\",\"乙\"]}\n```"),
            listOf("가", "나")
        )
        assertFalse(parsed.parseFailed)
        assertEquals(listOf("甲", "乙"), parsed.values)
    }

    @Test
    fun parseTranslations_marksGarbageAsParseFailed() {
        // 模型回了一段解释而不是 JSON：旧实现静默返回原文，现在必须标记 parseFailed
        // （调用方据此拆批/换姿势重试，而不是直接上屏原文）。
        val parsed = client.parseTranslations(completion("抱歉，我无法完成这个请求。"), listOf("가", "나"))
        assertTrue(parsed.parseFailed)
        assertEquals(listOf("가", "나"), parsed.values)
    }

    @Test
    fun parseTranslations_marksMissingKeyAsParseFailed() {
        val parsed = client.parseTranslations(completion("{\"result\":[\"甲\",\"乙\"]}"), listOf("가", "나"))
        assertTrue(parsed.parseFailed)
    }

    @Test
    fun parseTranslations_marksTruncatedJsonAsParseFailed() {
        // 被 max_tokens 截断的半截 JSON：没有可配平的候选 → parseFailed。
        val parsed = client.parseTranslations(completion("{\"translations\":[\"甲\",\"乙"), listOf("가", "나"))
        assertTrue(parsed.parseFailed)
    }

    /**
     * 2026-10-31「译文贴错框」的回归测试：模型把序号带了回来，但数组顺序被打乱时，
     * 必须按 `i` 回填，不能按下标硬套。
     */
    @Test
    fun parseTranslations_mapsByIndexWhenModelReorders() {
        val parsed = client.parseTranslations(
            completion("{\"translations\":[{\"i\":2,\"t\":\"丙\"},{\"i\":0,\"t\":\"甲\"},{\"i\":1,\"t\":\"乙\"}]}"),
            listOf("가", "나", "다")
        )
        assertFalse("reason=" + parsed.reason, parsed.parseFailed)
        assertEquals(listOf("甲", "乙", "丙"), parsed.values)
    }

    @Test
    fun parseTranslations_mapsByIndexInBareArray() {
        val parsed = client.parseTranslations(
            completion("[{\"index\":1,\"text\":\"乙\"},{\"index\":0,\"text\":\"甲\"}]"),
            listOf("가", "나")
        )
        assertFalse(parsed.parseFailed)
        assertEquals(listOf("甲", "乙"), parsed.values)
    }

    /**
     * 旧格式（纯数组、无序号）长度比输入多时：以前会 minOf 静默截断，把整页第一句
     * 硬贴到本批气泡上。现在必须判 parseFailed，交给调用方拆批重试。
     */
    @Test
    fun parseTranslations_rejectsPositionalArrayWithWrongLength() {
        val parsed = client.parseTranslations(
            completion("{\"translations\":[\"整页一\",\"整页二\",\"整页三\"]}"),
            listOf("가", "나")
        )
        assertTrue(parsed.parseFailed)
        assertEquals(listOf("가", "나"), parsed.values)
    }

    /**
     * 2026-10-31 真机事故：整页 8 句、第 4 句命中缓存，本批只缺 7 句（不连续）。
     * 模型按整页顺序回了 7 条纯数组，长度刚好等于本批数量，旧实现按下标硬套，
     * 从缓存那一句之后整体错一位：原文还在原框，译文却来自上一句。
     *
     * 生产链路现在必须带 i；纯数组即使长度正确也判 parseFailed（宁可不翻，不能错贴）。
     */
    @Test
    fun parseTranslations_rejectsPositionalArrayWhenIndexIsRequired() {
        val parsed = client.parseTranslations(
            completion("{\"translations\":[\"甲\",\"乙\",\"丙\"]}"),
            listOf("가", "나", "다"),
            requireExplicitIndex = true
        )
        assertTrue("reason=" + parsed.reason, parsed.parseFailed)
        assertEquals(listOf("가", "나", "다"), parsed.values)
    }

    @Test
    fun parseTranslations_rejectsOneBasedPageIndices() {
        // 模型把页面上下文的 1-based 标号当成输入序号：0 号缺失、7 号越界。
        val parsed = client.parseTranslations(
            completion(
                "{\"translations\":[{\"i\":1,\"t\":\"甲\"},{\"i\":2,\"t\":\"乙\"},{\"i\":3,\"t\":\"丙\"}]}"
            ),
            listOf("가", "나", "다"),
            requireExplicitIndex = true
        )
        assertTrue("reason=" + parsed.reason, parsed.parseFailed)
    }

    @Test
    fun parseTranslations_rejectsPagePrefixArrayFromRealCachedLineCase() {
        // 真机事故的最小复现：整页 8 句，第 4 句命中缓存，本批只送第 1/2/3/5/6/7/8 句。
        // 模型返回整页前 7 句的译文（纯数组，长度恰好 7）。旧实现按下标回填，
        // 第 4 句之后整体错一位；现在必须拒绝。
        val missing = listOf("page1", "page2", "page3", "page5", "page6", "page7", "page8")
        val pagePrefixTranslations = listOf("t1", "t2", "t3", "t4(cached)", "t5", "t6", "t7")
        val parsed = client.parseTranslations(
            completion(JSONObject().put("translations", JSONArray(pagePrefixTranslations)).toString()),
            missing,
            requireExplicitIndex = true
        )
        assertTrue("reason=" + parsed.reason, parsed.parseFailed)
    }

    @Test
    fun parseTranslations_allowsSinglePositionalArrayWhenIndexIsRequired() {
        // 拆批到最后只剩一句时没有顺序歧义，模型偶尔仍回纯数组，不该因此丢掉译文。
        val parsed = client.parseTranslations(
            completion("{\"translations\":[\"甲\"]}"),
            listOf("가"),
            requireExplicitIndex = true
        )
        assertFalse("reason=" + parsed.reason, parsed.parseFailed)
        assertEquals(listOf("甲"), parsed.values)
    }

    @Test
    fun parseTranslations_acceptsValidIndexedArrayWhenRequired() {
        val parsed = client.parseTranslations(
            completion(
                "{\"translations\":[{\"i\":0,\"t\":\"甲\"},{\"i\":1,\"t\":\"乙\"},{\"i\":2,\"t\":\"丙\"}]}"
            ),
            listOf("가", "나", "다"),
            requireExplicitIndex = true
        )
        assertFalse("reason=" + parsed.reason, parsed.parseFailed)
        assertEquals(listOf("甲", "乙", "丙"), parsed.values)
    }

    @Test
    fun parseTranslations_toleratesTrailingBlankInPositionalArray() {
        val parsed = client.parseTranslations(
            completion("{\"translations\":[\"甲\",\"乙\",\"\"]}"),
            listOf("가", "나")
        )
        assertFalse(parsed.parseFailed)
        assertEquals(listOf("甲", "乙"), parsed.values)
    }

    private fun completion(content: String): String {
        return JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put("message", JSONObject().put("content", content))
                )
            )
            .toString()
    }

    private fun shouldMerge(a: Bubble, b: Bubble): Boolean {
        val method = OpenAiVisionClient::class.java.getDeclaredMethod(
            "shouldMergeBaiduBubbles",
            Bubble::class.java,
            Bubble::class.java
        )
        method.isAccessible = true
        return method.invoke(client, a, b) as Boolean
    }

    private fun mergeGroup(vararg bubbles: Bubble): Bubble {
        val method = OpenAiVisionClient::class.java.getDeclaredMethod(
            "mergeBaiduBubbleGroup",
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(client, bubbles.toList()) as Bubble
    }

    private fun mergeHeuristically(vararg bubbles: Bubble): List<Bubble> {
        val method = OpenAiVisionClient::class.java.getDeclaredMethod(
            "mergeNearbyBaiduBubblesHeuristically",
            List::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(client, bubbles.toList(), 1000, 1000) as List<Bubble>
    }

    private fun bubble(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        layout: String,
        text: String,
        contentX: Float = x,
        contentY: Float = y,
        contentW: Float = w,
        contentH: Float = h
    ): Bubble {
        return Bubble(
            x = x,
            y = y,
            w = w,
            h = h,
            bgType = "solid",
            originalText = text,
            translatedText = text,
            layout = layout,
            contentX = contentX,
            contentY = contentY,
            contentW = contentW,
            contentH = contentH
        )
    }
}
