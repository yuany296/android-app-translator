package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 盖板轮廓多边形单测（2026-10-14）。
 *
 * 覆盖用户真机截图反馈的两个症状的防线：
 * - **盖不住原文**：轮廓窄于文字行带时必须被墨迹守卫回扩（否则裁切切进字形区）；
 * - **锯齿**：单行噪声尖刺被邻行外抹平、整条轮廓外扩。
 *
 * 另外钉住既有行为：行退化 / 可用行不足时整体放弃（返回 null，落回矩形轮廓）。
 */
class CoverOutlinePolygonTest {

    /** 拼接图 1000 宽、2000 高、无上条带：百分比 = px / 10（x）、px / 20（y）。 */
    private fun build(
        profileRowsPx: FloatArray,
        inkBands: List<FloatArray>,
        coverTopPx: Float = 100f,
        coverBottomPx: Float = 300f,
        dilatePx: Float = 0f
    ) = CoverOutlinePolygon.build(
        profileRowsPx = profileRowsPx,
        coverTopPx = coverTopPx,
        coverBottomPx = coverBottomPx,
        inkBands = inkBands,
        dilatePx = dilatePx,
        stitchedWidthPx = 1000,
        srcHeightPx = 2000,
        topStripHeightPx = 0
    )

    /** 取多边形对应某 y 的左右端点（左侧自上而下，右侧自下而上，两个列表顺序相反）。 */
    private fun rowIntervalAtY(result: CoverOutlinePolygon.Result, yPercent: Float): Pair<Float, Float>? {
        val points = result.pointsPercent
        val rows = points.size / 4
        var left: Float? = null
        for (i in 0 until rows) {
            if (kotlin.math.abs(points[i * 2 + 1] - yPercent) < 0.01f) {
                left = points[i * 2]
                break
            }
        }
        var right: Float? = null
        for (i in 0 until rows) {
            val index = rows + i
            if (kotlin.math.abs(points[index * 2 + 1] - yPercent) < 0.01f) {
                right = points[index * 2]
                break
            }
        }
        return if (left != null && right != null) left to right else null
    }

    @Test
    fun build_expandsNoisyNarrowRowToCoverInkBand() {
        // 中间一行被笔画卡住（量到 500px 弦，仍高于噪声阈值），墨迹行带要求 200~800：
        // 守卫必须把它扩回去，否则盖板在这一行被裁出缺口 → 原文从缺口露出来。
        val profile = floatArrayOf(
            120f, 200f, 800f,
            150f, 250f, 750f,
            180f, 200f, 800f
        )
        val result = requireNotNull(
            build(profile, inkBands = listOf(floatArrayOf(200f, 110f, 800f, 290f)))
        )
        val middle = requireNotNull(rowIntervalAtY(result, 150f / 20f))
        assertEquals(20f, middle.first, 0.01f)
        assertEquals(80f, middle.second, 0.01f)
        assertEquals(1, result.diag.rowsInkClamped)
        assertTrue(result.diag.maxInkDeficitPx >= 49f)
    }

    @Test
    fun build_leavesNoisyRowOutsideInkBandsUntouched() {
        // 行带覆盖 110~140；y=180 的窄行不在任何行带里（多行之间的空白带）：
        // 那里没有墨迹要保，轮廓该多窄就多窄（保住轮廓贴合度）。
        val profile = floatArrayOf(
            120f, 200f, 800f,
            150f, 200f, 800f,
            180f, 300f, 700f
        )
        val result = requireNotNull(
            build(profile, inkBands = listOf(floatArrayOf(200f, 110f, 800f, 140f)))
        )
        val outside = requireNotNull(rowIntervalAtY(result, 180f / 20f))
        assertEquals(30f, outside.first, 0.01f)
        assertEquals(70f, outside.second, 0.01f)
    }

    @Test
    fun build_dilatesAllRowsOutward() {
        val profile = floatArrayOf(
            120f, 200f, 800f,
            150f, 200f, 800f,
            180f, 200f, 800f
        )
        val result = requireNotNull(build(profile, inkBands = emptyList(), dilatePx = 5f))
        val first = requireNotNull(rowIntervalAtY(result, 120f / 20f))
        assertEquals(19.5f, first.first, 0.01f)
        assertEquals(80.5f, first.second, 0.01f)
        assertEquals(5f, result.diag.dilatePx, 0.01f)
    }

    @Test
    fun build_extendsPolygonEndsToCoverBox() {
        // profile 只覆盖 120~180；盖板框 100~300 → 两端各延一行（用最近行边界）。
        val profile = floatArrayOf(
            120f, 200f, 800f,
            150f, 200f, 800f,
            180f, 200f, 800f
        )
        val result = requireNotNull(build(profile, inkBands = emptyList()))
        assertTrue(rowIntervalAtY(result, 100f / 20f) != null)
        assertTrue(rowIntervalAtY(result, 300f / 20f) != null)
    }

    @Test
    fun build_smallChordsAreDroppedAndFewUsableRowsFallBack() {
        // 只有一行可用（其余弦宽 < 最大弦 45%）→ 可用行不足 3 行 → 放弃（落回矩形盖板）。
        val profile = floatArrayOf(
            120f, 450f, 550f,
            140f, 480f, 520f,
            160f, 460f, 540f,
            180f, 495f, 505f
        )
        assertNull(build(profile, inkBands = emptyList()))
    }

    @Test
    fun build_degenerateRowFallsBack() {
        // 左右交错（right - left < 4px）的行 = 点位非法 → 整体放弃。
        val profile = floatArrayOf(
            120f, 200f, 800f,
            150f, 600f, 598f,
            180f, 200f, 800f
        )
        assertNull(build(profile, inkBands = emptyList()))
    }

    @Test
    fun build_keepsMeasuredWideRowsUntouchedWithoutInkDeficit() {
        // 轮廓本来就比行带宽（气泡比文字宽）→ 不允许被收窄，只做外扩散量。
        val profile = floatArrayOf(
            120f, 100f, 900f,
            150f, 100f, 900f,
            180f, 100f, 900f
        )
        val result = requireNotNull(
            build(profile, inkBands = listOf(floatArrayOf(200f, 110f, 800f, 290f)), dilatePx = 2f)
        )
        val middle = requireNotNull(rowIntervalAtY(result, 150f / 20f))
        assertEquals(9.8f, middle.first, 0.01f)
        assertEquals(90.2f, middle.second, 0.01f)
        assertEquals(0, result.diag.rowsInkClamped)
    }

    @Test
    fun build_usesPerLineInkBandsNotTheWholeUnion() {
        // 两行文字：第一行窄（200~600）、第二行宽（200~800）。
        // 第一行带内的行只受第一行带约束（回扩到 200~600），不会被第二行带撑到 800。
        val profile = floatArrayOf(
            120f, 220f, 580f,
            150f, 220f, 700f,
            180f, 220f, 780f
        )
        val result = requireNotNull(
            build(
                profile,
                inkBands = listOf(
                    floatArrayOf(200f, 110f, 600f, 140f),
                    floatArrayOf(200f, 150f, 800f, 290f)
                )
            )
        )
        val firstRow = requireNotNull(rowIntervalAtY(result, 120f / 20f))
        assertEquals(20f, firstRow.first, 0.01f)
        assertEquals(60f, firstRow.second, 0.01f)
    }
}
