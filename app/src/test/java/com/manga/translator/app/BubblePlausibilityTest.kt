package com.manga.translator.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 盖板宽高合理性钳制单测（2026-10-15，对齐参考项目 `stitch-mapping.js` 的窄高闸门）。
 *
 * 服务的历史问题：「之前超出盖板的原因应该是截页的处理没处理好、把宽高算错了」——
 * 换算链任何一环出错都会先表现为一块超高盖板；这条闸门把它钳回合规矩形。
 */
class BubblePlausibilityTest {

    @Test
    fun singleLineHeightCapIs35Percent() {
        assertEquals(35f, BubblePlausibility.maxCoverHeightPercent(1), 0.001f)
        // 34.9% —— 没超，原样放过（返回 null = 不加工）。
        assertNull(BubblePlausibility.clampCoverHeight(floatArrayOf(10f, 20f, 50f, 34.9f), 1))
        // 35.1% —— 超了，钳到 35%，上沿/宽度不动。
        val clamped = requireNotNull(BubblePlausibility.clampCoverHeight(floatArrayOf(10f, 20f, 50f, 35.1f), 1))
        assertArrayEquals(floatArrayOf(10f, 20f, 50f, 35f), clamped, 0.001f)
    }

    @Test
    fun multilineHeightCapIs60Percent() {
        assertEquals(60f, BubblePlausibility.maxCoverHeightPercent(2), 0.001f)
        assertNull(BubblePlausibility.clampCoverHeight(floatArrayOf(0f, 0f, 100f, 60f), 3))
        val clamped = requireNotNull(BubblePlausibility.clampCoverHeight(floatArrayOf(5f, 8f, 90f, 130f), 2))
        assertArrayEquals(floatArrayOf(5f, 8f, 90f, 60f), clamped, 0.001f)
    }

    @Test
    fun preservesNegativeTopForCrossPageBubbles() {
        // 跨图气泡的上沿可以是负数（框从上一张图伸过来）：钳高只改高度。
        val clamped = requireNotNull(BubblePlausibility.clampCoverHeight(floatArrayOf(12f, -18f, 70f, 95f), 1))
        assertArrayEquals(floatArrayOf(12f, -18f, 70f, 35f), clamped, 0.001f)
    }

    @Test
    fun invalidInputIsLeftAlone() {
        assertNull(BubblePlausibility.clampCoverHeight(floatArrayOf(1f, 2f, 3f), 1))
        assertNull(BubblePlausibility.clampCoverHeight(floatArrayOf(1f, 2f, 3f, Float.NaN), 1))
    }

    // ------------------------------------------------------------------
    // 2026-10-18 真机：竖排/3 行韩文盖板下面露原文
    // 现场 mt185：cover_box=35.0%（旧 35% 上限），text=39.79%（3 行韩文）
    // ------------------------------------------------------------------

    @Test
    fun tallVerticalColumnIsNotClampedByHorizontalLineCap() {
        // 竖排固定 sourceLineCount=1；旧逻辑会把 70% 高的竖列钳到 35%，下沿全露。
        assertNull(
            BubblePlausibility.clampCoverHeight(
                coverBoxPercent = floatArrayOf(10f, 5f, 20f, 70f),
                lineCount = 1,
                vertical = true
            )
        )
    }

    @Test
    fun normalGlyphDerivedCoverIsNotClampedEvenWhenLineCountIsUnderestimated() {
        // 现场：3 行文字被报成 1 行 → 旧上限 35%，但字形底+外扩在 40.2%，
        // 正常盖板（= 字形 + 外扩）不能被钳短。
        assertNull(
            BubblePlausibility.clampCoverHeight(
                coverBoxPercent = floatArrayOf(32.6f, 19.2f, 36.4f, 40.2f),
                lineCount = 1,
                vertical = false,
                glyphBottomPercent = 59.4f
            )
        )
    }

    @Test
    fun clampNeverCutsBelowDetectedGlyphBottom() {
        // 40% 高、字形底在 48%：即使超过 35% 上限，也只能钳到字形底（38%），不能钳到 35%。
        val clamped = requireNotNull(
            BubblePlausibility.clampCoverHeight(
                coverBoxPercent = floatArrayOf(10f, 10f, 50f, 50f),
                lineCount = 1,
                glyphBottomPercent = 48f
            )
        )
        assertArrayEquals(floatArrayOf(10f, 10f, 50f, 38f), clamped, 0.001f)
    }

    @Test
    fun abnormalCoverStillClampedWhenGlyphIsSmall() {
        // 换算错误：盖板 80% 高，但字形底只有 20% → 仍钳到 35%（安全网保留）。
        val clamped = requireNotNull(
            BubblePlausibility.clampCoverHeight(
                coverBoxPercent = floatArrayOf(5f, 10f, 90f, 80f),
                lineCount = 1,
                glyphBottomPercent = 20f
            )
        )
        assertArrayEquals(floatArrayOf(5f, 10f, 90f, 35f), clamped, 0.001f)
    }

    // ------------------------------------------------------------------
    // effectiveLineHeightPx（2026-10-15：混合字号页面上的扩框行高）
    // ------------------------------------------------------------------

    @Test
    fun effectiveLineHeightPrefersTheBubblesOwnHeight() {
        // 真机现场：中位 130、框 102、2 行 → 用 51（不是 130）。
        assertEquals(51f, BubblePlausibility.effectiveLineHeightPx(130f, 102f, 2), 0.01f)
    }

    @Test
    fun effectiveLineHeightIsCappedByMedian() {
        // 框内行均比中位大（大字号气泡）→ 不超过中位。
        assertEquals(43f, BubblePlausibility.effectiveLineHeightPx(43f, 200f, 2), 0.01f)
    }

    @Test
    fun effectiveLineHeightFallsBackToMedianForSlivers() {
        // 截缝细条（6px）量不出行高 → 中位。
        assertEquals(43f, BubblePlausibility.effectiveLineHeightPx(43f, 6f, 2), 0.01f)
        // 非法输入 → 中位；中位也非法 → 0。
        assertEquals(43f, BubblePlausibility.effectiveLineHeightPx(43f, Float.NaN, 2), 0.01f)
        assertEquals(0f, BubblePlausibility.effectiveLineHeightPx(0f, 100f, 2), 0.01f)
    }
}
