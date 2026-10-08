package com.manga.translator.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧可用性判定、最近帧快照新鲜度与 JPEG 编码参数的单测。
 *
 * 为什么值得测：
 * - 「可疑帧」判定错误会把好画面丢掉（或把全黑图当成好画面缓存下来），
 *   直接影响用户看到的识别结果；
 * - JPEG 缩放算错会把尺寸不对的图发给识别接口。
 */
class FramePolicyTest {

    private fun stats(
        samples: Int = 576,
        meanLuma: Float = 128f,
        stddevLuma: Float = 60f,
        darkRatio: Float = 0.05f,
        lightRatio: Float = 0.05f
    ) = FrameLumaStats(
        samples = samples,
        meanLuma = meanLuma,
        stddevLuma = stddevLuma,
        darkRatio = darkRatio,
        lightRatio = lightRatio
    )

    // ---------- isSuspicious ----------

    @Test
    fun suspicious_whenNoSamples() {
        assertTrue(FramePolicy.isSuspicious(stats(samples = 0)))
    }

    @Test
    fun suspicious_whenFrameIsAlmostAllBlack() {
        // 刚启动录屏时的典型中间态：整帧纯黑
        assertTrue(
            FramePolicy.isSuspicious(
                stats(meanLuma = 0f, stddevLuma = 0f, darkRatio = 1f, lightRatio = 0f)
            )
        )
    }

    @Test
    fun suspicious_whenFrameIsAlmostAllWhite() {
        assertTrue(
            FramePolicy.isSuspicious(
                stats(meanLuma = 255f, stddevLuma = 0f, darkRatio = 0f, lightRatio = 1f)
            )
        )
    }

    @Test
    fun notSuspicious_forHighContrastPageEvenWithManyDarkPixels() {
        // 关键回归点：漫画页有大量黑色线条时 darkRatio 会很高，
        // 但亮度并不均匀（stddev 大），不能被当成「可疑」丢掉。
        assertFalse(
            FramePolicy.isSuspicious(
                stats(stddevLuma = 60f, darkRatio = 0.99f, lightRatio = 0.01f)
            )
        )
    }

    @Test
    fun notSuspicious_forUniformButMixedFrame() {
        // 亮度均匀，但没有被单一亮度主导 → 不算可疑
        assertFalse(
            FramePolicy.isSuspicious(
                stats(stddevLuma = 2f, darkRatio = 0.5f, lightRatio = 0.1f)
            )
        )
    }

    @Test
    fun suspicious_boundaryStddevIsExclusive() {
        // stddev == 9 不算「过于均匀」（判定是严格小于）
        assertFalse(
            FramePolicy.isSuspicious(
                stats(stddevLuma = FramePolicy.MIN_STDDEV_LUMA, darkRatio = 1f, lightRatio = 0f)
            )
        )
        // 刚低于阈值就算
        assertTrue(
            FramePolicy.isSuspicious(
                stats(stddevLuma = 8.99f, darkRatio = 1f, lightRatio = 0f)
            )
        )
    }

    @Test
    fun suspicious_boundaryDominantRatioIsInclusive() {
        assertTrue(
            FramePolicy.isSuspicious(
                stats(stddevLuma = 0f, darkRatio = FramePolicy.DOMINANT_RATIO, lightRatio = 0f)
            )
        )
        assertFalse(
            FramePolicy.isSuspicious(stats(stddevLuma = 0f, darkRatio = 0.984f, lightRatio = 0f))
        )
    }

    @Test
    fun suspicious_lightRatioBoundary() {
        assertTrue(
            FramePolicy.isSuspicious(
                stats(stddevLuma = 0f, darkRatio = 0f, lightRatio = FramePolicy.DOMINANT_RATIO)
            )
        )
    }

    // ---------- snapshotUsable ----------

    @Test
    fun snapshot_freshFrameIsUsable() {
        assertTrue(FramePolicy.snapshotUsable(0L))
        assertTrue(FramePolicy.snapshotUsable(1199L))
    }

    @Test
    fun snapshot_boundaryIsInclusive() {
        assertTrue(FramePolicy.snapshotUsable(FramePolicy.RECENT_FRAME_MAX_AGE_MS))
        assertFalse(FramePolicy.snapshotUsable(FramePolicy.RECENT_FRAME_MAX_AGE_MS + 1))
    }

    @Test
    fun snapshot_supportsExplicitMaxAge() {
        assertTrue(FramePolicy.snapshotUsable(300L, maxAgeMs = 300L))
        assertFalse(FramePolicy.snapshotUsable(301L, maxAgeMs = 300L))
    }

    // ---------- shouldScale / scaledHeight ----------

    @Test
    fun shouldScale_onlyWhenWiderThanLimit() {
        assertTrue(FramePolicy.shouldScale(2048, 1024))
        assertFalse(FramePolicy.shouldScale(1024, 1024))
        assertFalse(FramePolicy.shouldScale(512, 1024))
    }

    @Test
    fun shouldScale_rejectsInvalidInputs() {
        assertFalse(FramePolicy.shouldScale(2048, 0))
        assertFalse(FramePolicy.shouldScale(2048, -1))
        assertFalse(FramePolicy.shouldScale(0, 1024))
    }

    @Test
    fun scaledHeight_keepsAspectRatio() {
        assertEquals(768, FramePolicy.scaledHeight(2048, 1536, 1024))
    }

    @Test
    fun scaledHeight_returnsOriginalHeightWhenNoScaleNeeded() {
        assertEquals(800, FramePolicy.scaledHeight(1024, 800, 1024))
        assertEquals(500, FramePolicy.scaledHeight(0, 500, 1024))
    }

    @Test
    fun scaledHeight_truncatesFractionalResult() {
        // (1024 / 3000) * 1000 = 341.33 → 341
        assertEquals(341, FramePolicy.scaledHeight(3000, 1000, 1024))
    }

    @Test
    fun scaledHeight_neverReturnsZeroForExtremeAspectRatio() {
        // 0 高度的 Bitmap 会直接抛异常，所以这里必须兜到 1
        assertEquals(1, FramePolicy.scaledHeight(4000, 1, 1))
        assertEquals(1, FramePolicy.scaledHeight(2048, 0, 1024))
    }

    // ---------- clampedQuality ----------

    @Test
    fun clampedQuality_keepsValuesInsideRange() {
        assertEquals(75, FramePolicy.clampedQuality(75))
        assertEquals(FramePolicy.MIN_JPEG_QUALITY, FramePolicy.clampedQuality(FramePolicy.MIN_JPEG_QUALITY))
        assertEquals(FramePolicy.MAX_JPEG_QUALITY, FramePolicy.clampedQuality(FramePolicy.MAX_JPEG_QUALITY))
    }

    @Test
    fun clampedQuality_clampsOutOfRangeValues() {
        assertEquals(FramePolicy.MIN_JPEG_QUALITY, FramePolicy.clampedQuality(10))
        assertEquals(FramePolicy.MIN_JPEG_QUALITY, FramePolicy.clampedQuality(0))
        assertEquals(FramePolicy.MAX_JPEG_QUALITY, FramePolicy.clampedQuality(100))
    }
}
