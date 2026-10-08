package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 无四边形时的倾角兜底单测（2026-09-29）。
 *
 * 场景来源：ML Kit Text Recognition 只返回轴对齐矩形，`TextBlock.polygon` 恒为 null，
 * 斜排原文的遮罩于是退化成水平矩形。这里锁定「墨迹 PCA 主轴 → 倾角」的估计质量与三道闸。
 */
class TextTiltEstimatorTest {

    private val width = 200
    private val height = 120

    /** 白底 + 沿 [angleDeg] 方向的一条粗墨线（模拟一行倾斜文字）。 */
    private fun tiltedLineLuma(angleDeg: Double, thickness: Int = 7): IntArray {
        val luma = IntArray(width * height) { 255 }
        val rad = Math.toRadians(angleDeg)
        val cosA = cos(rad)
        val sinA = sin(rad)
        val centerX = width / 2.0
        val centerY = height / 2.0
        val half = width / 2.0 - 4
        var s = -half
        while (s <= half) {
            val px = centerX + s * cosA
            val py = centerY + s * sinA
            for (o in -thickness..thickness) {
                // 沿法线方向加粗
                val x = (px - o * sinA).roundToInt()
                val y = (py + o * cosA).roundToInt()
                if (x in 0 until width && y in 0 until height) luma[y * width + x] = 0
            }
            s += 0.5
        }
        return luma
    }

    @Test
    fun estimatesPositiveTilt() {
        val angle = TextTiltEstimator.estimateFromLuma(tiltedLineLuma(20.0), width, height)
        assertNotNull("20° 斜线应被估出倾角", angle)
        assertEquals(20f, angle!!, 2.5f)
    }

    @Test
    fun estimatesNegativeTilt() {
        val angle = TextTiltEstimator.estimateFromLuma(tiltedLineLuma(-30.0), width, height)
        assertNotNull("−30° 斜线应被估出倾角", angle)
        assertEquals(-30f, angle!!, 2.5f)
    }

    @Test
    fun estimatesShallowTilt() {
        val angle = TextTiltEstimator.estimateFromLuma(tiltedLineLuma(8.0), width, height)
        assertNotNull("8° 也应能估出（高于 3° 门槛）", angle)
        assertEquals(8f, angle!!, 2.5f)
    }

    @Test
    fun horizontalLineIsNotWorthTilting() {
        // 正常横排：低于 MIN_ABS_DEG → 返回 null，上游保持轴对齐（不能被抖成斜的）。
        assertNull(TextTiltEstimator.estimateFromLuma(tiltedLineLuma(0.0), width, height))
        assertNull(TextTiltEstimator.estimateFromLuma(tiltedLineLuma(1.0), width, height))
    }

    @Test
    fun verticalStrokeIsRejected() {
        // 竖排/单字：主轴接近竖直（≈90°）→ 不能当成横排倾角。
        assertNull(TextTiltEstimator.estimateFromLuma(tiltedLineLuma(90.0), width, height))
        assertNull(TextTiltEstimator.estimateFromLuma(tiltedLineLuma(-88.0), width, height))
    }

    @Test
    fun isotropicBlobIsRejected() {
        // 各向同性的一团墨：没有明确方向，不该给出角度。
        val luma = IntArray(width * height) { 255 }
        for (y in 40..80) for (x in 80..120) luma[y * width + x] = 0
        assertNull(TextTiltEstimator.estimateFromLuma(luma, width, height))
    }

    @Test
    fun tooFewInkPixelsIsRejected() {
        val luma = IntArray(width * height) { 255 }
        for (i in 0..9) luma[i] = 0 // 只有 10 个墨点 < MIN_INK_PIXELS
        assertNull(TextTiltEstimator.estimateFromLuma(luma, width, height))
    }

    @Test
    fun blankOrDegenerateInputIsRejected() {
        assertNull(TextTiltEstimator.estimateFromLuma(IntArray(0), 0, 0))
        assertNull(TextTiltEstimator.estimateFromLuma(IntArray(100) { 255 }, 10, 10))
        assertNull(TextTiltEstimator.estimateFromLuma(IntArray(100) { 0 }, 10, 10))
        // 数组长度不足 width*height
        assertNull(TextTiltEstimator.estimateFromLuma(IntArray(50) { 128 }, 10, 10))
    }

    @Test
    fun lightTextOnDarkBackgroundIsAlsoEstimated() {
        // 深底白字：墨迹是「少数派但更亮」的一侧，阈值方向必须能自动倒过来。
        val base = tiltedLineLuma(20.0)
        val inverted = IntArray(base.size) { if (base[it] == 0) 255 else 0 }
        val angle = TextTiltEstimator.estimateFromLuma(inverted, width, height)
        assertNotNull("深底白字也应估出倾角", angle)
        assertEquals(20f, angle!!, 2.5f)
    }

    @Test
    fun multiLineBlockUsesTheLineDirection() {
        // 多行文字：主轴仍是**行的方向**（而不是行堆叠的方向）。
        val luma = IntArray(width * height) { 255 }
        for (line in 0..2) {
            val centerY = 30.0 + line * 25.0
            var s = -70.0
            while (s <= 70.0) {
                val rad = Math.toRadians(20.0)
                val px = 100.0 + s * cos(rad)
                val py = centerY + s * sin(rad)
                for (o in -3..3) {
                    val x = (px - o * sin(rad)).roundToInt()
                    val y = (py + o * cos(rad)).roundToInt()
                    if (x in 0 until width && y in 0 until height) luma[y * width + x] = 0
                }
                s += 0.5
            }
        }
        val angle = TextTiltEstimator.estimateFromLuma(luma, width, height)
        assertNotNull(angle)
        // 最小面积外接矩形贴着文字块长边，多行也不会被「行间距的竖直堆叠」带偏
        // （旧的 PCA 实现在这组数据上是 25.7°，误差近 6°）。
        assertEquals(20f, angle!!, 1.5f)
    }

    // ------------------------------------------------------------------
    // 旋转后的四边形顶点
    // ------------------------------------------------------------------

    @Test
    fun rotateBoxCornersKeepsOrderAndSize() {
        val corners = TextTiltEstimator.rotateBoxCorners(0f, 0f, 100f, 40f, 0f)
        assertEquals(4, corners.size)
        assertEquals(0f, corners[0].x, 0.01f)
        assertEquals(0f, corners[0].y, 0.01f)
        assertEquals(100f, corners[1].x, 0.01f)
        assertEquals(40f, corners[2].y, 0.01f)
    }

    @Test
    fun rotateBoxCornersRotatesAroundCenter() {
        // 绕中心旋转：中心不变，角点沿旋转方向移动。
        val corners = TextTiltEstimator.rotateBoxCorners(0f, 0f, 100f, 40f, 90f)
        val centerX = corners.map { it.x }.average()
        val centerY = corners.map { it.y }.average()
        assertEquals(50.0, centerX, 0.01)
        assertEquals(20.0, centerY, 0.01)
        // TL(0,0) 绕中心 (50,20) 转 90°：dx=-50,dy=-20 →
        // x' = 50 + (-50·0 − (−20)·1) = 70，y' = 20 + (−50·1 + (−20)·0) = −30
        assertEquals(70f, corners[0].x, 0.6f)
        assertEquals(-30f, corners[0].y, 0.6f)
    }

    @Test
    fun rotateBoxCornersPreservesEdgeLengths() {
        val width1 = 120f
        val height1 = 30f
        val corners = TextTiltEstimator.rotateBoxCorners(0f, 0f, width1, height1, 17f)
        fun dist(a: QuadPoint, b: QuadPoint) =
            kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
        assertEquals(width1.toDouble(), dist(corners[0], corners[1]), 0.05)
        assertEquals(width1.toDouble(), dist(corners[3], corners[2]), 0.05)
        assertEquals(height1.toDouble(), dist(corners[1], corners[2]), 0.05)
        assertTrue("旋转后仍是凸四边形（对角线交叉）",
            dist(corners[0], corners[2]) > dist(corners[0], corners[1]))
    }

    // ------------------------------------------------------------------
    // 最小面积外接矩形的顶点（2026-09-29 用户反馈「倾斜文本角度和宽度都不对」）
    //
    // 只给角度、让调用方去旋转 OCR 的轴对齐框是错的：斜排文字的 AABB 宽度
    // = 字宽·cosθ + 字高·sinθ，转出来必然又宽又偏。这里锁定「顶点贴着字形」。
    // ------------------------------------------------------------------

    @Test
    fun rectCornersAreTighterThanTheAxisAlignedBox() {
        val estimate = TextTiltEstimator.estimateRectFromLuma(tiltedLineLuma(20.0), width, height)
        assertNotNull(estimate)
        assertEquals(4, estimate!!.corners.size)

        fun dist(a: QuadPoint, b: QuadPoint) =
            kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
        // 旋转矩形的真实面积 = 长边 × 短边（顶点按环序给出）
        val trueArea = dist(estimate.corners[0], estimate.corners[1]) *
            dist(estimate.corners[1], estimate.corners[2])
        val xs = estimate.corners.map { it.x }
        val ys = estimate.corners.map { it.y }
        val aabbArea = (xs.max() - xs.min()) * (ys.max() - ys.min())
        assertTrue(
            "斜排文字的外接矩形面积($trueArea) 必须明显小于它的轴对齐框($aabbArea)",
            trueArea < aabbArea * 0.85
        )
    }

    @Test
    fun rectCornersStayInsideTheSearchRegion() {
        val estimate = TextTiltEstimator.estimateRectFromLuma(tiltedLineLuma(20.0), width, height)
        assertNotNull(estimate)
        estimate!!.corners.forEach { point ->
            assertTrue("顶点 x 越界: ${point.x}", point.x >= -2f && point.x <= width + 2f)
            assertTrue("顶点 y 越界: ${point.y}", point.y >= -2f && point.y <= height + 2f)
        }
    }

    @Test
    fun rectCornersKeepTheEstimatedAngle() {
        val estimate = TextTiltEstimator.estimateRectFromLuma(tiltedLineLuma(-25.0), width, height)
        assertNotNull(estimate)
        assertEquals(-25f, estimate!!.angleDeg, 2.5f)
        // 顶点的长边方向必须和 angleDeg 一致（否则渲染层从顶点反推的角度会打架）
        val dx = estimate.corners[1].x - estimate.corners[0].x
        val dy = estimate.corners[1].y - estimate.corners[0].y
        val edgeAngle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val delta = kotlin.math.abs(kotlin.math.abs(edgeAngle) - kotlin.math.abs(estimate.angleDeg))
        assertTrue("顶点长边角度($edgeAngle) 应与 angleDeg(${estimate.angleDeg}) 一致", delta < 1f)
    }
}
