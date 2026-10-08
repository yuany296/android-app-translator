package com.manga.translator.overlay

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「背景是否多色」判据单测（2026-09-30 用户要求
 * 「那个字体的白边黑边，要不只在背景有多种颜色的时候有吧」）。
 *
 * 这里只测**纯函数**那一段（[BackgroundOutlinePolicy.isBusy]）：
 * 取样的入口 [BackgroundOutlinePolicy.brightnessSamples] 要真 Bitmap，
 * JVM 单测跑不了（本模块没有 Robolectric），由真机验证覆盖。
 */
class BackgroundOutlinePolicyTest {

    private val threshold = BackgroundOutlinePolicy.BUSY_LUMA_RANGE

    @Test
    fun flatBackgroundIsNotBusy() {
        // 纯色背景（几像素的压缩噪声）
        val flat = IntArray(200) { 180 - (it % 3) }
        assertTrue("纯色背景不该被判为多色", !BackgroundOutlinePolicy.isBusy(flat))
    }

    @Test
    fun smoothGradientIsNotBusy() {
        // 平滑渐变：真机素材里斜排旁白的背景（浅蓝渐变）在气泡尺度上的跨度只有个位数
        val gradient = IntArray(240) { 150 + it / 20 }   // 150..161
        assertTrue("平滑渐变不该被判为多色（这正是用户要关掉白边的那种背景）",
            !BackgroundOutlinePolicy.isBusy(gradient))
    }

    @Test
    fun busyBackgroundIsBusy() {
        // 照片 / 网点 / 花纹：亮度铺满大半个量程
        val busy = IntArray(240) { if (it % 2 == 0) 40 else 210 }
        assertTrue("乱七八糟的背景必须判为多色（要保留描边）",
            BackgroundOutlinePolicy.isBusy(busy))
    }

    @Test
    fun thresholdIsExclusive() {
        // 10% 的低位 + 90% 的高位（10% > 5%，两端都落在分位点之内），跨度刚好 = 阈值 → 不算多色
        val exact = IntArray(100) { if (it < 10) 50 else 50 + threshold }
        assertTrue("跨度恰好等于阈值 → 不算多色（判据是严格大于）",
            !BackgroundOutlinePolicy.isBusy(exact))
        // 再高 2 个亮度就跨过去
        val over = IntArray(100) { if (it < 10) 50 else 52 + threshold }
        assertTrue(BackgroundOutlinePolicy.isBusy(over))
    }

    @Test
    fun robustQuantileIgnoresIsolatedOutliers() {
        // 只有一个孤立极亮像素（JPEG 噪点）时不能把整块背景判成多色：
        // 用 5%~95% 分位就是为了这个。
        val withOutlier = IntArray(200) { if (it == 0) 255 else 120 }
        assertTrue("孤立噪点不该翻转结论", !BackgroundOutlinePolicy.isBusy(withOutlier))
    }

    @Test
    fun tooFewSamplesNeverCountsAsBusy() {
        // 样本不足时 isBusy 给 false（保守）；needsOutline 对外会把「样本不足」当 true
        // （宁可留着描边），两条口径分别由本用例与真机验证覆盖。
        assertTrue(!BackgroundOutlinePolicy.isBusy(IntArray(3) { 0 }))
        assertTrue(!BackgroundOutlinePolicy.isBusy(IntArray(0)))
    }
}
