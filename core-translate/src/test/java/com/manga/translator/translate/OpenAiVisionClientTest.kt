package com.manga.translator.translate

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
