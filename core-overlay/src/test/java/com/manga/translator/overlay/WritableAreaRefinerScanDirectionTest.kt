package com.manga.translator.overlay

import com.manga.translator.overlay.WritableAreaRefiner.StripSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「可写区域精修」收边方向（终止条件）的回归单测（2026-09-30）。
 *
 * 回归背景：`scanInset` 的判据原本是**反的** —— 「条带还有墨 → 继续往里缩，遇到空白就停」。
 * 于是**文字内部**的字间空隙被当成了边界。
 *
 * 实测接缝气泡（`.verify/web/seamBubbleA.png` + `seamBubbleB.png`，离线复刻
 * `logs/refine_probe.py` 与真机 `refine_boxes` 逐像素一致）：
 * - OCR 框 `174..551`，文字真实墨迹范围 `174..543`；
 * - 旧逻辑左缩 43（停在首字与第二字之间的空隙 217）、右缩 67（撞上 18% 上限），
 *   结果 `217..484` —— 首字被切掉一半、尾字只剩前半，页面表现为「上下两端露字」。
 *
 * 下面把新语义钉死：
 * ① 只要**最外那条**已经压在字上，就必须一个字都不缩（框永远不缩进文字）；
 * ② 外侧确实是空白时，才允许把空白收掉；
 * ③ 无论怎样都不超过 limit。
 */
class WritableAreaRefinerScanDirectionTest {

    private val refiner = WritableAreaRefiner()
    private val brightCut = 208f

    /** 接缝气泡左边缘实测值（`logs/refine_probe.py` 的 trace）。 */
    private val seamLeftInk = listOf(
        137.5f, 99.6f, 76.5f, 63.7f, 54.7f, 46.9f, 40.9f, 35.2f, 30.1f, 47.4f, // 0..9
        96.7f, 140.2f, 163.4f, 179.7f, 190.5f, 196.0f, 199.4f, 201.7f, 203.6f, 204.5f, // 10..19
        204.6f, 204.5f, 204.4f, 203.7f, 201.9f, 199.6f, 196.4f, 191.4f, 180.6f, 163.5f, // 20..29
        141.1f, 99.2f, 48.5f, 29.1f, 34.2f, 40.1f, 46.2f, 53.9f, 63.0f, 76.8f, // 30..39
        101.3f, 141.3f, 207.6f // 40..42（全部有墨）
    )

    private fun inkSignal(avg: Float) = StripSignal(averageLuma = avg, darkRatio = 0.5f, contrast = 25f)

    private fun backgroundSignal() = StripSignal(averageLuma = 252f, darkRatio = 0f, contrast = 4f)

    @Test
    fun outermostStripAlreadyHasInkMeansNothingIsTrimmed() {
        // ① 最关键的一条：框的左边界已经压在字上 → 一个像素都不能缩。
        val inset = refiner.scanInset(limit = 67, brightCut = brightCut) { index ->
            if (index < seamLeftInk.size) {
                inkSignal(seamLeftInk[index])
            } else {
                backgroundSignal() // 217..219 处确实是空白（字间空隙）
            }
        }

        assertEquals(
            "外缘就是字形的框必须原样保留；缩成 ${inset}px 会把首字切掉一半（旧 bug）",
            0,
            inset
        )
    }

    @Test
    fun blankOuterPaddingIsTrimmedDownToTheInk() {
        // ② 外侧确实是空白（OCR 框留了白）→ 应该把它收掉，收到第一列有墨为止。
        val inkStartsAt = 20
        val inset = refiner.scanInset(limit = 67, brightCut = brightCut) { index ->
            if (index < inkStartsAt) backgroundSignal() else inkSignal(120f)
        }

        assertEquals("外侧 20px 空白应当收掉", inkStartsAt, inset)
    }

    @Test
    fun allBlankNeverExceedsLimit() {
        // ③ 整条都是空白（理论上不该出现）时不得越过 limit，避免把框缩成 0。
        val inset = refiner.scanInset(limit = 13, brightCut = brightCut) { backgroundSignal() }

        assertEquals(13, inset)
    }

    @Test
    fun darkRatioOrContrastAloneAlsoStopsTheScan() {
        // 亮度还没掉下去、但条带里已经有深墨/高对比 → 同样算「压到字上了」，必须停。
        val byDarkRatio = refiner.scanInset(limit = 67, brightCut = brightCut) {
            StripSignal(averageLuma = 250f, darkRatio = 0.30f, contrast = 3f)
        }
        assertEquals("darkRatio 超阈值也要停", 0, byDarkRatio)

        val byContrast = refiner.scanInset(limit = 67, brightCut = brightCut) {
            StripSignal(averageLuma = 250f, darkRatio = 0f, contrast = 40f)
        }
        assertEquals("contrast 超阈值也要停", 0, byContrast)
    }

    @Test
    fun aFewStrayInkPixelsOnTheEdgeStripAlsoStopTheScan() {
        // 接缝气泡实测：文字首行（751~752）深墨占比 0.057、末行（831~833）0.088。
        // 这两个值都远低于旧的 0.18 → 被判成「空白」收掉 → 盖板只比墨迹多 1.5px，
        // 抗锯齿灰边露在外面。判据收紧到 0 之后必须判成「不是背景」。
        listOf(0.057f, 0.088f, 0.01f).forEach { ratio ->
            assertTrue(
                "深墨占比 $ratio 的边缘条带已经沾到字形，不能判成背景（否则会收掉字的边缘）",
                !WritableAreaThresholds.looksLikeBackground(
                    averageLuma = 240f, darkRatio = ratio, contrast = 4f, brightCut = brightCut
                )
            )
        }
    }

    @Test
    fun pureBackgroundPredicateMatchesTheScanStopCondition() {
        // 判据本身：三个条件全满足才算背景（= 可以继续收边）。
        assertTrue(
            "纯背景条带应判成背景",
            WritableAreaThresholds.looksLikeBackground(
                averageLuma = 252f, darkRatio = 0f, contrast = 4f, brightCut = brightCut
            )
        )
        assertTrue(
            "亮度刚好等于阈值算背景（>=）",
            WritableAreaThresholds.looksLikeBackground(
                averageLuma = brightCut, darkRatio = 0f, contrast = 0f, brightCut = brightCut
            )
        )
        assertTrue(
            "任何一项越界就不是背景",
            listOf(
                WritableAreaThresholds.looksLikeBackground(200f, 0f, 0f, brightCut),
                WritableAreaThresholds.looksLikeBackground(252f, 0.30f, 4f, brightCut),
                WritableAreaThresholds.looksLikeBackground(252f, 0f, 40f, brightCut)
            ).none { it }
        )
    }
}
