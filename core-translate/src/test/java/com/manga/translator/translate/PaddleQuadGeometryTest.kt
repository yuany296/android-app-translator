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
        // 阈值 2026-09-30 由 1.4 收紧到 1.8：这个判定决定**要不要把整块转 90° 去识别**，
        // 判错的代价是识别结果整体错乱（用户反馈：横排三行韩文被当成竖排）。
        // 真竖排一列的高宽比通常 ≥ 2，1.8 仍留足余量。
        assertTrue("明显竖长（5 倍）应判竖排", PaddleQuadGeometry.isConfidentVerticalCrop(60, 300))
        assertTrue("刚好 1.8 倍应判竖排", PaddleQuadGeometry.isConfidentVerticalCrop(100, 180))
        // 旧阈值 1.4 会把这些判成竖排 —— 正是要挡掉的「多行横排偏高」形态。
        assertFalse("1.41 倍：旧阈值会误判，现在必须拒绝", PaddleQuadGeometry.isConfidentVerticalCrop(100, 141))
        assertFalse("1.7 倍：仍不够竖", PaddleQuadGeometry.isConfidentVerticalCrop(100, 170))
        assertFalse("宽扁不该判竖排", PaddleQuadGeometry.isConfidentVerticalCrop(141, 100))
        assertFalse("略低于阈值", PaddleQuadGeometry.isConfidentVerticalCrop(100, 179))
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

    // ------------------------------------------------------------------
    // 质量闸门 isAcceptableResult（2026-09-30 用户反馈 `cYa` 噪声）
    //
    // 用例形状直接来自真实截图：空白/纹理区域被误检成**零散拉丁字母**。
    // 守两件事：噪声要挡住、正常短文本不能误杀。
    // ------------------------------------------------------------------

    @Test
    fun acceptableResult_rejectsLatinNoiseOnKoreanPage() {
        // 用户截图里的三种形态：都是纯拉丁、一个韩文字都没有。
        assertFalse(PaddleQuadGeometry.isAcceptableResult("cYa", 0.91f, "ko"))
        assertFalse(PaddleQuadGeometry.isAcceptableResult("a c", 0.85f, "ko"))
        assertFalse(PaddleQuadGeometry.isAcceptableResult("c Y", 0.80f, "ko"))
        // 高分数也不行：「韩文页面上没有一个韩文字」本身就是判据，不看分数。
        assertFalse(PaddleQuadGeometry.isAcceptableResult("XYZ", 0.99f, "ko"))
    }

    @Test
    fun acceptableResult_acceptsRealKoreanEvenWhenShort() {
        // 一个韩文字也要放过：韩漫里 `얼음`、`큭` 这类短台词很常见。
        assertTrue(PaddleQuadGeometry.isAcceptableResult("얼음", 0.7f, "ko"))
        assertTrue(PaddleQuadGeometry.isAcceptableResult("큭", 0.6f, "ko"))
        assertTrue(PaddleQuadGeometry.isAcceptableResult("햇빛을 받으면", 0.95f, "ko"))
        // 韩文 + 拉丁混排（外来语/拟声词）也要过。
        assertTrue(PaddleQuadGeometry.isAcceptableResult("OK 좋아", 0.9f, "ko"))
    }

    @Test
    fun acceptableResult_rejectsLowScoreRegardlessOfLanguage() {
        // 分数下限对所有语言生效：正常文字普遍 > 0.85。
        assertFalse(PaddleQuadGeometry.isAcceptableResult("안녕", 0.4f, "ko"))
        assertFalse(PaddleQuadGeometry.isAcceptableResult("오늘", 0.0f, "ko"))
        assertFalse(PaddleQuadGeometry.isAcceptableResult("Hello", 0.3f, "en"))
        assertFalse(PaddleQuadGeometry.isAcceptableResult("こんにちは", Float.NaN, "ja"))
    }

    @Test
    fun acceptableResult_neverKillsJaLatinOnomatopoeia() {
        // ⚠️ 刻意不对日语/英语要求「目标文字」：日漫拟声词常是拉丁/片假名混排，
        // 按脚本过滤会误杀。分数够就放行 —— 宁可漏过滤，不可误杀。
        assertTrue(PaddleQuadGeometry.isAcceptableResult("BANG", 0.9f, "ja"))
        assertTrue(PaddleQuadGeometry.isAcceptableResult("ドドド", 0.88f, "ja"))
        assertTrue(PaddleQuadGeometry.isAcceptableResult("cYa", 0.9f, "en"))
    }

    @Test
    fun acceptableResult_rejectsPunctuationOnlyAndBlank() {
        // 纯标点/符号翻不出东西，且不属于任何目标文字。
        assertFalse(PaddleQuadGeometry.isAcceptableResult("…", 0.99f, "ko"))
        assertFalse(PaddleQuadGeometry.isAcceptableResult("?!", 0.99f, "ko"))
        assertFalse(PaddleQuadGeometry.isAcceptableResult("   ", 0.99f, "ko"))
        assertFalse(PaddleQuadGeometry.isAcceptableResult("", 0.99f, "ko"))
    }

    @Test
    fun weakResultAndAcceptableResultAreDifferentGates() {
        // 两个闸门职责不同，不能互相替代：
        // `isWeakResult` 决定「要不要再试一次」，`isAcceptableResult` 决定「要不要采纳」。
        // 一条**高分噪声**只被后者挡住 —— 这正是原来漏掉 `cYa` 的原因：
        // 只靠 isWeakResult 时，它重试完照样被原样返回。
        assertTrue(PaddleQuadGeometry.isWeakResult("cYa", 0.91f, "ko"))
        assertFalse(PaddleQuadGeometry.isAcceptableResult("cYa", 0.91f, "ko"))
        // 真实长句：不弱、也可采纳。
        assertFalse(PaddleQuadGeometry.isWeakResult("햇빛을 받으면", 0.95f, "ko"))
        assertTrue(PaddleQuadGeometry.isAcceptableResult("햇빛을 받으면", 0.95f, "ko"))
    }

    private fun p(x: Float, y: Float): Point = Point(x, y)
}
