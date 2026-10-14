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
