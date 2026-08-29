package com.manga.translator.translate

import com.manga.translator.translate.PaddleQuadGeometry.OrientedResult
import com.manga.translator.translate.PaddleQuadGeometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 倾斜/变形文字几何处理单测（参考 C:\homework\translator crop_geometry.py / crop_recognition.py）。
 */
class PaddleQuadGeometryTest {

    private fun quad(tl: Point, tr: Point, br: Point, bl: Point): List<Point> =
        listOf(tl, tr, br, bl)

    @Test
    fun horizontalQuadRotationIsZero() {
        val q = quad(p(10f, 20f), p(200f, 20f), p(200f, 60f), p(10f, 60f))
        assertEquals(0f, PaddleQuadGeometry.rotationDeg(q), 0.5f)
    }

    @Test
    fun verticalQuadRotationIsMinus90() {
        // 竖排：长边竖直，方向角归一化到 -90
        val q = quad(p(100f, 10f), p(140f, 10f), p(140f, 300f), p(100f, 300f))
        assertEquals(-90f, PaddleQuadGeometry.rotationDeg(q), 0.5f)
    }

    @Test
    fun tiltedQuadRotationMatchesAngle() {
        // 基线向右下倾斜 30°（tan30°≈0.577）：TL(10,20) -> TR(183,120)，dy=dx*0.577
        val q = quad(p(10f, 20f), p(183f, 120f), p(163f, 165f), p(-10f, 65f))
        assertEquals(30f, PaddleQuadGeometry.rotationDeg(q), 1f)
    }

    @Test
    fun tiltedQuadUpwardsIsNegativeAngle() {
        // 基线向右上倾斜 30°：TR 高于 TL
        val q = quad(p(10f, 20f), p(183f, -80f), p(163f, -35f), p(-10f, 65f))
        assertEquals(-30f, PaddleQuadGeometry.rotationDeg(q), 1f)
    }

    @Test
    fun verticalCropDetection() {
        assertTrue(PaddleQuadGeometry.isConfidentVerticalCrop(100, 141))
        assertTrue(PaddleQuadGeometry.isConfidentVerticalCrop(60, 300))
        assertFalse(PaddleQuadGeometry.isConfidentVerticalCrop(141, 100))
        assertFalse(PaddleQuadGeometry.isConfidentVerticalCrop(100, 139))
    }

    @Test
    fun scriptCharCounting() {
        assertEquals(2, PaddleQuadGeometry.countScriptChars("안녕", "ko"))
        assertEquals(5, PaddleQuadGeometry.countScriptChars("こんにちは", "ja"))
        assertEquals(2, PaddleQuadGeometry.countScriptChars("世界", "ja"))
        assertEquals(5, PaddleQuadGeometry.countScriptChars("Hello!", "en"))
        assertEquals(4, PaddleQuadGeometry.countScriptChars("你好世界", "zh"))
    }

    @Test
    fun weakResultDetection() {
        assertTrue(PaddleQuadGeometry.isWeakResult("", 0.9f, "ko"))
        assertTrue(PaddleQuadGeometry.isWeakResult("abc", 0.6f, "ko"))
        assertTrue(PaddleQuadGeometry.isWeakResult("##", 0.99f, "ja"))
        assertFalse(PaddleQuadGeometry.isWeakResult("안녕", 0.9f, "ko"))
    }

    @Test
    fun selectionPrefersBestQualityWhenRotationSmall() {
        val results = listOf(
            OrientedResult(90, "テスト", 0.5f),
            OrientedResult(-90, "こんにちは", 0.9f)
        )
        val chosen = PaddleQuadGeometry.selectBestOrientation(10f, results, "ja")
        assertEquals(-90, chosen?.orientation)
    }

    @Test
    fun selectionPrefersRotationDirectionOnTie() {
        // 竖排 quad（rot=-90）：两个方向质量接近，应偏好 -90
        val results = listOf(
            OrientedResult(90, "テスト", 0.65f),
            OrientedResult(-90, "こんにちは", 0.66f)
        )
        val chosen = PaddleQuadGeometry.selectBestOrientation(-90f, results, "ja")
        assertEquals(-90, chosen?.orientation)
    }

    @Test
    fun selectionKeepsClearlyBetterResultDespiteRotationPreference() {
        // 竖排 quad（rot=-90）：+90 方向质量明显更好，不应被方向偏好覆盖
        val results = listOf(
            OrientedResult(90, "テスト", 0.9f),
            OrientedResult(-90, "こんにちは", 0.5f)
        )
        val chosen = PaddleQuadGeometry.selectBestOrientation(-90f, results, "ja")
        assertEquals(90, chosen?.orientation)
    }

    @Test
    fun selectionFallsBackWhenNoPreferredOrientationCandidate() {
        // 竖排 quad 但只有 +90 候选：退回质量最优
        val results = listOf(
            OrientedResult(90, "こんにちは", 0.8f)
        )
        val chosen = PaddleQuadGeometry.selectBestOrientation(-90f, results, "ja")
        assertEquals(90, chosen?.orientation)
    }

    @Test
    fun selectionHandlesEmptyResults() {
        assertNull(PaddleQuadGeometry.selectBestOrientation(-90f, emptyList(), "ja"))
    }

    private fun p(x: Float, y: Float): Point = Point(x, y)
}
