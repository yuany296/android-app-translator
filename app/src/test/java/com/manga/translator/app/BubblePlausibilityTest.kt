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
}
