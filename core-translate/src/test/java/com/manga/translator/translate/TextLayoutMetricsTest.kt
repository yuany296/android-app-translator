package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 视觉行数统计单测（2026-09-28）。
 *
 * 关键点：同一行被检测器切成多块时只能算 1 行 —— 否则「原文字高 = 框高 ÷ 行数」会把
 * 字高估小，译文跟着变小（用户反馈「字号感觉小了」）。
 */
class TextLayoutMetricsTest {

    private fun block(left: Float, top: Float, right: Float, bottom: Float): TextBlock =
        TextBlock(id = 0, text = "x", left = left, top = top, right = right, bottom = bottom)

    @Test
    fun singleBlockIsOneLine() {
        assertEquals(1, TextLayoutMetrics.countVisualLines(listOf(block(0f, 100f, 200f, 150f))))
    }

    @Test
    fun emptyInputIsOneLine() {
        assertEquals(1, TextLayoutMetrics.countVisualLines(emptyList()))
    }

    @Test
    fun threeStackedLinesCountThree() {
        val blocks = listOf(
            block(0f, 100f, 400f, 150f),
            block(0f, 170f, 300f, 220f),
            block(0f, 240f, 350f, 290f)
        )
        assertEquals(3, TextLayoutMetrics.countVisualLines(blocks))
    }

    @Test
    fun fragmentsOnSameLineCountOnce() {
        // 同一行被切成 3 块（空格/标点），纵向几乎完全重叠
        val blocks = listOf(
            block(0f, 100f, 120f, 150f),
            block(140f, 104f, 260f, 154f),
            block(280f, 98f, 400f, 148f)
        )
        assertEquals(1, TextLayoutMetrics.countVisualLines(blocks))
    }

    @Test
    fun twoLinesEachSplitInTwoCountTwo() {
        val blocks = listOf(
            block(0f, 100f, 120f, 150f),
            block(140f, 102f, 260f, 152f),
            block(0f, 180f, 120f, 230f),
            block(140f, 182f, 260f, 232f)
        )
        assertEquals(2, TextLayoutMetrics.countVisualLines(blocks))
    }

    @Test
    fun slightlyDriftedFragmentsStillSameLine() {
        // 检测框有轻微上下漂移，但中心距小于字高的一半
        val blocks = listOf(
            block(0f, 100f, 200f, 160f),
            block(210f, 112f, 400f, 172f)
        )
        assertEquals(1, TextLayoutMetrics.countVisualLines(blocks))
    }

    @Test
    fun clearVerticalGapCountsSeparateLines() {
        val blocks = listOf(
            block(0f, 100f, 400f, 160f),
            block(0f, 220f, 400f, 280f)
        )
        assertEquals(2, TextLayoutMetrics.countVisualLines(blocks))
    }

    @Test
    fun unsortedInputIsOrderedFirst() {
        val blocks = listOf(
            block(0f, 240f, 300f, 290f),
            block(0f, 100f, 400f, 150f)
        )
        assertEquals(2, TextLayoutMetrics.countVisualLines(blocks))
    }
}
