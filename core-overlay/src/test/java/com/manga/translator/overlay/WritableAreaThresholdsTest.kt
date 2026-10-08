package com.manga.translator.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「可写区域精修」判据阈值的回归单测（2026-09-30）。
 *
 * 回归背景：`refineSolid` 原来把判据写死成 `averageLuma < 208`，隐含假设「气泡底是白的」。
 * 漫画里彩色/渐变背景很常见，实测某页背景是蓝灰渐变、亮度只有 170~193 —— 于是**每一条**
 * 边缘条带都被判成「有内容」，四个方向一路缩到 `MAX_INSET_RATIO` 上限：
 * `283x136@206,929` 被砍成 `182x95@257,950`（宽 -36%、高 -30%）。
 * 而斜排文字的倾角就是在这个被砍小的框里估计的 → 最小外接矩形只包得住中段 →
 * 页面表现为「倾斜的文本两边都有字没被遮住」。
 *
 * 修法：阈值改成 `min(背景亮度 - 跌落量, 旧上限)`。这里把两条性质钉死：
 * ① 背景变暗时阈值必须跟着下调（否则就是原 bug）；
 * ② 阈值**永远不高于**旧上限（保证只会比修复前更保守，不会新引入「缩过头」）。
 */
class WritableAreaThresholdsTest {

    /** 旧实现的写死值——现在只当天花板。 */
    private val legacyBrightCap = 208f
    private val legacyDarkCap = 164f

    @Test
    fun midtoneBackdropLowersTheBrightCutBelowLegacyValue() {
        // 实测值：某页蓝灰渐变背景亮度 ≈ 190。
        val backdrop = 190f
        val cut = WritableAreaThresholds.brightCut(backdrop)

        assertTrue(
            "背景亮度 $backdrop 必须让阈值掉到旧值 $legacyBrightCap 以下，否则渐变背景又会被判成「有内容」",
            cut < legacyBrightCap
        )
        assertTrue(
            "条带亮度 $backdrop（纯背景）必须判成「亮背景」才不会再缩框，实得阈值 $cut",
            backdrop >= cut
        )
    }

    @Test
    fun cutNeverExceedsLegacyCapSoBehaviourIsNeverMoreAggressive() {
        // 从纯黑到纯白扫一遍：任何背景亮度下都不能比修复前**更激进**。
        var background = 0f
        while (background <= 255f) {
            assertTrue(
                "背景 $background 的亮判据 ${WritableAreaThresholds.brightCut(background)} 超过了旧上限",
                WritableAreaThresholds.brightCut(background) <= legacyBrightCap
            )
            assertTrue(
                "背景 $background 的深墨判据 ${WritableAreaThresholds.darkCut(background)} 超过了旧上限",
                WritableAreaThresholds.darkCut(background) <= legacyDarkCap
            )
            background += 1f
        }
    }

    @Test
    fun whiteBackdropKeepsTheExactLegacyThresholds() {
        // 白底气泡是旧实现的「主场」，算出来必须与旧值**完全相等**，行为逐像素不变。
        assertEquals(legacyBrightCap, WritableAreaThresholds.brightCut(255f), 0f)
        assertEquals(legacyDarkCap, WritableAreaThresholds.darkCut(255f), 0f)
        // 稍微带点灰的白底也一样（255 - 40 = 215 > 208）。
        assertEquals(legacyBrightCap, WritableAreaThresholds.brightCut(250f), 0f)
    }

    @Test
    fun darkerBackdropYieldsStrictlyLowerThresholds() {
        // 单调性：背景越暗，阈值越低（越不容易判成「有内容」）。
        val ramps = listOf(255f, 230f, 200f, 160f, 120f, 60f)
        ramps.zipWithNext { lighter, darker ->
            assertTrue(
                "背景 $darker 的亮判据应不高于背景 $lighter",
                WritableAreaThresholds.brightCut(darker) <= WritableAreaThresholds.brightCut(lighter)
            )
            assertTrue(
                "背景 $darker 的深墨判据应不高于背景 $lighter",
                WritableAreaThresholds.darkCut(darker) <= WritableAreaThresholds.darkCut(lighter)
            )
        }
    }
}
