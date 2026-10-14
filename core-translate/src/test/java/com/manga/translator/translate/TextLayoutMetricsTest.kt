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
    fun blockSpanningTwoLinesCountsBoth() {
        // 2026-10-14 用户现场（「超出太多了吧」）：ML Kit 把两行并成一个块 ——
        // 块自身带行数（`TextBlock.lineCount`），数行数时必须用它，否则行高翻倍、字号跟着翻倍。
        val merged = block(0f, 100f, 400f, 220f).copy(lineCount = 2)
        val next = block(0f, 240f, 350f, 290f)
        assertEquals(3, TextLayoutMetrics.countVisualLines(listOf(merged, next)))
    }

    @Test
    fun lineCountOneKeepsLegacyBehaviour() {
        // 老数据 / 检测器没给行数（lineCount 默认 1）→ 与改动前一致。
        val blocks = listOf(
            block(0f, 100f, 400f, 150f),
            block(0f, 170f, 300f, 220f)
        )
        assertEquals(2, TextLayoutMetrics.countVisualLines(blocks))
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

    // ------------------------------------------------------------------
    // 行高中位数（2026-10-14）：「块框高 ÷ 行数」会被块框松弛放大
    // （真机实测 45.3 vs 相邻泡 34.9），逐行框中位数紧贴字形。
    // ------------------------------------------------------------------

    private fun lineBox(left: Float, top: Float, right: Float, bottom: Float) =
        floatArrayOf(left, top, right, bottom)

    @Test
    fun medianLineHeightPicksMiddleValue() {
        val boxes = listOf(
            lineBox(0f, 0f, 100f, 34f),
            lineBox(0f, 40f, 100f, 75f),   // 35 —— 中位数
            lineBox(0f, 80f, 100f, 126f)   // 46 —— 离群（松框）
        )
        assertEquals(35f, TextLayoutMetrics.medianLineBoxDimPx(boxes, vertical = false), 0.01f)
    }

    @Test
    fun medianLineHeightEvenCountTakesUpperMiddle() {
        val boxes = listOf(
            lineBox(0f, 0f, 100f, 30f),
            lineBox(0f, 40f, 100f, 74f),   // 34
            lineBox(0f, 80f, 100f, 116f),  // 36
            lineBox(0f, 120f, 100f, 160f)  // 40 —— 离群
        )
        assertEquals(36f, TextLayoutMetrics.medianLineBoxDimPx(boxes, vertical = false), 0.01f)
    }

    @Test
    fun verticalMedianUsesColumnWidth() {
        // 竖排的「行」是一列，行高 = 列宽；第三列 46 是离群（松框）
        val boxes = listOf(
            lineBox(0f, 0f, 30f, 200f),
            lineBox(50f, 0f, 84f, 200f),   // 宽 34 —— 中位数
            lineBox(100f, 0f, 146f, 200f)  // 宽 46 —— 离群
        )
        assertEquals(34f, TextLayoutMetrics.medianLineBoxDimPx(boxes, vertical = true), 0.01f)
    }

    @Test
    fun degenerateBoxesAreSkippedAndEmptyReturnsZero() {
        val boxes = listOf(
            floatArrayOf(0f, 0f, 100f),                 // 维度不足
            lineBox(0f, 0f, 100f, 100f),                // 高 100
            lineBox(0f, 0f, 100f, 0.5f)                 // 高 <1 —— 不可用
        )
        assertEquals(100f, TextLayoutMetrics.medianLineBoxDimPx(boxes, vertical = false), 0.01f)
        assertEquals(0f, TextLayoutMetrics.medianLineBoxDimPx(emptyList(), vertical = false), 0.01f)
    }
}
