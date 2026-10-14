package com.manga.translator.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这块底是不是一条亮的平底色」判据单测（2026-10-13）。
 *
 * 现场（用户反馈「这个截页处的气泡框是纯色背景，但是它用的是清理图。希望处理为 solid」）：
 * 真机日志里 `bubble_region_stats detected=0` → 所有气泡都走 `bg_type=none` → 盖板从
 * inpaint 清理图裁切 → 白气泡边上留灰痕。判据必须**量出来**：外圈一致率 + 色调够亮，
 * 缺一不可（外圈是插画/网点时宁可继续用清理图，也不平涂一块色板）。
 */
class BubbleBackgroundPolicyTest {

    @Test
    fun pureWhiteRingIsFullyUniform() {
        val ring = IntArray(64) { 0xFFFFFFFF.toInt() }
        assertEquals(1f, BubbleBackgroundPolicy.ringCoverage(ring, ring.size, 0xFFFFFFFF.toInt()), 0.0001f)
    }

    @Test
    fun pollutedRingFallsBelowCoverage() {
        // 白气泡外圈混进三成灰（框比气泡轮廓大 → 采到气泡外的灰天空）
        val ring = IntArray(100) { index -> if (index < 70) 0xFFFFFFFF.toInt() else 0xFF8A9099.toInt() }
        val coverage = BubbleBackgroundPolicy.ringCoverage(ring, ring.size, 0xFFFFFFFF.toInt())
        assertEquals(0.7f, coverage, 0.0001f)
        assertFalse(
            "七成一致率不足以判平底（平涂一块色板比留灰痕更糟）",
            BubbleBackgroundPolicy.isFlatBrightBackground(0xFFFFFFFF.toInt(), coverage)
        )
    }

    @Test
    fun brightFlatBackgroundIsAccepted() {
        assertTrue(
            BubbleBackgroundPolicy.isFlatBrightBackground(
                0xFFFFFFFF.toInt(),
                BubbleBackgroundPolicy.SOLID_MIN_COVERAGE
            )
        )
        // 略偏暖的浅色底（漫画里常见的米白气泡）同样算亮底
        assertTrue(BubbleBackgroundPolicy.isFlatBrightBackground(0xFFF5F1E8.toInt(), 0.97f))
    }

    @Test
    fun darkOrMidToneBackgroundIsRejected() {
        // 中灰（相对亮度 ~0.23）不是「亮底」：深色大块多半是画面本身，不能平涂纯色
        assertFalse(BubbleBackgroundPolicy.isFlatBrightBackground(0xFF8A9099.toInt(), 1f))
        assertFalse(BubbleBackgroundPolicy.isFlatBrightBackground(0xFF111111.toInt(), 1f))
    }

    @Test
    fun coverageThresholdMatchesReferenceProject() {
        assertEquals(0.92f, BubbleBackgroundPolicy.SOLID_MIN_COVERAGE, 0.0001f)
        assertEquals(0.72f, BubbleBackgroundPolicy.SOLID_MIN_LUMINANCE, 0.0001f)
        assertEquals(24f, BubbleBackgroundPolicy.RING_UNIFORM_DISTANCE, 0.0001f)
    }

    @Test
    fun relativeLuminanceMatchesWcagAnchors() {
        assertEquals(0f, BubbleBackgroundPolicy.relativeLuminance(0xFF000000.toInt()), 0.001f)
        assertEquals(1f, BubbleBackgroundPolicy.relativeLuminance(0xFFFFFFFF.toInt()), 0.001f)
        assertTrue(BubbleBackgroundPolicy.relativeLuminance(0xFFF5F1E8.toInt()) >= 0.72f)
    }

    @Test
    fun rgbDistanceIsEuclidean() {
        assertEquals(0f, BubbleBackgroundPolicy.rgbDistance(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()), 0.0001f)
        assertEquals(255f, BubbleBackgroundPolicy.rgbDistance(0xFF000000.toInt(), 0xFF0000FF.toInt()), 0.0001f)
    }
}
