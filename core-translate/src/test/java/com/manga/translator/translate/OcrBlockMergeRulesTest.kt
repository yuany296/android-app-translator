package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收严后的块合并判据（2026-10-03）。
 *
 * 现场（`.verify/38_user.logcat` trace `[21930477]`，Lezhin 本地 OCR）：
 * 上气泡的 4 行块 `@192,83 300x139` 与下气泡的两块 `@77,272 272x65`、`@69,343 300x29`
 * 被并成**一句**，于是译文与盖板把两个独立气泡当成一段（用户截图）。
 *
 * 旧判据的阈值以**块高**为单位（`max(块高) × 1.35 ≈ 188px`），而真实间隙只有 50px；
 * 本判据以**行高**（框高 ÷ 行数）为单位，同样一对的阈值是 `1.1 × 33.6 ≈ 37px`。
 */
class OcrBlockMergeRulesTest {

    // ------------------------------------------------------------------
    // 现场回归
    // ------------------------------------------------------------------

    @Test
    fun realWorldTwoBubblesAreNotMerged() {
        val upperBubble = block(
            id = 0,
            text = lines("『케이지』라고요…!!", "접속종료를 하려면 죽거나", "접속게이트로 돌아가거나", "두 가지 밖에 없는데…!!"),
            left = 192f, top = 83f, right = 492f, bottom = 222f,
            lineCount = 4
        )
        val lowerBubbleLine1 = block(
            id = 1,
            text = lines("악질들한테 걸리기라도", "하면 죽지도"),
            left = 77f, top = 272f, right = 349f, bottom = 337f,
            lineCount = 2
        )
        val lowerBubbleLine2 = block(
            id = 2,
            text = "고통받을수도 있습니다!",
            left = 69f, top = 343f, right = 369f, bottom = 372f,
            lineCount = 1
        )

        val cross = OcrBlockMergeRules.shouldMerge(upperBubble, lowerBubbleLine1)
        assertFalse("两个独立气泡不得合并（实际 gap=${cross.gapPx} lh=${cross.lineHeightPx}）", cross.merge)
        assertEquals(OcrMergeReason.GAP, cross.reason)

        assertFalse(
            "上气泡与下气泡第二行也不得合并",
            OcrBlockMergeRules.shouldMerge(upperBubble, lowerBubbleLine2).merge
        )

        // 同一气泡内的两块仍必须合并（否则回归成逐行翻译）。
        val sameBubble = OcrBlockMergeRules.shouldMerge(lowerBubbleLine1, lowerBubbleLine2)
        assertTrue("同一气泡的相邻两块应合并（实际 reason=${sameBubble.reason}）", sameBubble.merge)
        assertEquals(OcrMergeReason.PARAGRAPH, sameBubble.reason)
    }

    @Test
    fun lineCountFallsBackToTextLineBreaks() {
        // 检测器把行数给成 1（真机踩过）时，必须靠文本换行数兜底 ——
        // 否则「行高 = 整块高度」，阈值又被放大数倍。
        val upperBubble = block(
            id = 0,
            text = lines("『케이지』라고요…!!", "접속종료를 하려면 죽거나", "접속게이트로 돌아가거나", "두 가지 밖에 없는데…!!"),
            left = 192f, top = 83f, right = 492f, bottom = 222f,
            lineCount = 1
        )
        val lowerBubble = block(
            id = 1,
            text = lines("악질들한테 걸리기라도", "하면 죽지도"),
            left = 77f, top = 272f, right = 349f, bottom = 337f,
            lineCount = 1
        )

        assertEquals(4, TextLineGeometry.lineCount(upperBubble))
        assertFalse(
            "文本换行数必须参与行数计算，否则又会合并",
            OcrBlockMergeRules.shouldMerge(upperBubble, lowerBubble).merge
        )
    }

    @Test
    fun sameBubbleNextLineStillMerges() {
        val first = block(0, lines(a, b, c, d), 192f, 83f, 492f, 222f, lineCount = 4)
        val next = block(1, "다음 줄입니다", 192f, 240f, 492f, 275f, lineCount = 1)
        val decision = OcrBlockMergeRules.shouldMerge(first, next)
        assertTrue("同一气泡的下一行必须仍能合并（reason=${decision.reason}）", decision.merge)
    }

    // ------------------------------------------------------------------
    // 行内（同一行 / 并排的列）
    // ------------------------------------------------------------------

    @Test
    fun inlineFragmentsOnTheSameLineMerge() {
        val left = block(0, "뭔가에", 100f, 100f, 200f, 140f)
        val right = block(1, "확", 230f, 104f, 330f, 144f)
        val decision = OcrBlockMergeRules.shouldMerge(left, right)
        assertTrue("同一行的两个碎片应合并（reason=${decision.reason}）", decision.merge)
        assertEquals(OcrMergeReason.INLINE, decision.reason)
    }

    @Test
    fun baselineMismatchIsRejected() {
        // 行内方向相邻，但基线差了大半行 → 不是同一行（更像上下两个块的错位）。
        val left = block(0, "왼쪽", 100f, 100f, 200f, 140f)
        val right = block(1, "오른쪽", 230f, 150f, 330f, 190f)
        val decision = OcrBlockMergeRules.shouldMerge(left, right)
        assertFalse("基线差过大不应按同一行合并", decision.merge)
    }

    @Test
    fun verticalColumnsMergeRightNextToEachOther() {
        val rightColumn = block(0, "右列", 300f, 100f, 340f, 300f)
        val leftColumn = block(1, "左列", 250f, 100f, 290f, 300f)
        val decision = OcrBlockMergeRules.shouldMerge(rightColumn, leftColumn)
        assertTrue("并排的窄列必须仍合并（reason=${decision.reason}）", decision.merge)
    }

    @Test
    fun distantColumnsAreNotMerged() {
        // 列间距远大于一个字宽 → 不是同一条竖排文字（很可能是另一个气泡）。
        val rightColumn = block(0, "右列", 300f, 100f, 340f, 300f)
        val farColumn = block(1, "左列", 150f, 100f, 190f, 300f)
        val decision = OcrBlockMergeRules.shouldMerge(rightColumn, farColumn)
        assertFalse("列间距过大不应合并（gap=${decision.gapPx}）", decision.merge)
    }

    @Test
    fun verticalMixedWithHorizontalIsRejected() {
        // 一块像竖排列、一块是横排，且不构成「并排的列」→ 方向不一致，不合并。
        val column = block(0, "세로", 300f, 100f, 340f, 300f)
        val horizontal = block(1, "가로로 긴 문장입니다", 350f, 120f, 650f, 160f)
        val decision = OcrBlockMergeRules.shouldMerge(column, horizontal)
        assertFalse("竖排与横排不得合并", decision.merge)
        assertEquals(OcrMergeReason.ORIENTATION, decision.reason)
    }

    @Test
    fun rotationMismatchIsRejected() {
        // 两块都有四边形且倾角差 30°（> 18°）→ 不合并（参考项目 rotationDistance > 18°）。
        val flat = block(0, "가로", 100f, 100f, 400f, 140f, polygon = quad(degrees = 0f))
        val tilted = block(1, "기울", 100f, 160f, 400f, 200f, polygon = quad(degrees = 30f))
        val decision = OcrBlockMergeRules.shouldMerge(flat, tilted)
        assertFalse("倾角差过大不应合并", decision.merge)
        assertEquals(OcrMergeReason.ORIENTATION, decision.reason)
    }

    // ------------------------------------------------------------------
    // 字号 / 对齐
    // ------------------------------------------------------------------

    @Test
    fun fontScaleMismatchIsRejected() {
        // 行高 20 vs 40（比值 0.5 < 0.65）：标题级字号与正文不应并成一段。
        val small = block(0, "제목", 0f, 100f, 60f, 120f)
        val large = block(1, "본문", 0f, 130f, 60f, 170f)
        val decision = OcrBlockMergeRules.shouldMerge(small, large)
        assertFalse("字号差过大不应合并", decision.merge)
        assertEquals(OcrMergeReason.FONT_SCALE, decision.reason)
    }

    @Test
    fun alignmentMismatchIsRejected() {
        // 宽度相同、行距很小，但横向错开整整一个块宽：既没有边缘对齐、中心也远，
        // 行内重叠不足 35% → 不是一个段落的对齐形态。
        val upper = block(0, "위 문장입니다", 100f, 100f, 400f, 140f)
        val lower = block(1, "아래 문장입니다", 300f, 150f, 600f, 190f)
        val decision = OcrBlockMergeRules.shouldMerge(upper, lower)
        assertFalse("对齐形态不符不应合并", decision.merge)
        assertEquals(OcrMergeReason.ALIGNMENT, decision.reason)
    }

    @Test
    fun alignedParagraphMerges() {
        val upper = block(0, "위 문장입니다", 100f, 100f, 400f, 140f)
        val lower = block(1, "아래 문장입니다", 100f, 150f, 400f, 190f)
        val decision = OcrBlockMergeRules.shouldMerge(upper, lower)
        assertTrue("左边缘对齐、行距正常应合并（reason=${decision.reason}）", decision.merge)
        assertEquals(OcrMergeReason.PARAGRAPH, decision.reason)
    }

    @Test
    fun blankLineBreakSplitsParagraph() {
        // 行距 ≥ 1.1 倍行高（空行分段）→ 不合并，交由调用方产出两段。
        val upper = block(0, "첫 문단입니다", 100f, 100f, 400f, 140f)
        val lower = block(1, "둘째 문단입니다", 100f, 200f, 400f, 240f)
        val decision = OcrBlockMergeRules.shouldMerge(upper, lower)
        assertFalse("空行分段不应合并（gap=${decision.gapPx}）", decision.merge)
        assertEquals(OcrMergeReason.GAP, decision.reason)
    }

    @Test
    fun knownRegionRelaxesAlignmentButNotGap() {
        // 区域已知时（同一个气泡容器）放宽对齐要求，但**行距闸不放宽** ——
        // 用户要的正是「同一气泡里的两段也分开展示」。
        //
        // 这一对：行内重叠 50%（过参考项目的 35% 下限），但中心偏离 150px > 35%×300，
        // 左右边缘也不对齐 → 无区域信息时拒绝、区域已知时接受。
        val upper = block(0, "위 문장입니다", 100f, 100f, 400f, 140f)
        val shifted = block(1, "아래 문장입니다", 250f, 150f, 550f, 190f)
        assertFalse(
            "没有区域信息时，错位行不按同一段处理",
            OcrBlockMergeRules.shouldMerge(upper, shifted).merge
        )
        assertTrue(
            "同一区域内的错位行仍算同一段",
            OcrBlockMergeRules.shouldMerge(upper, shifted, sameKnownRegion = true).merge
        )
        val farBelow = block(2, "아래 문장입니다", 250f, 200f, 550f, 240f)
        assertFalse(
            "同一区域内空行分段仍要拆开",
            OcrBlockMergeRules.shouldMerge(upper, farBelow, sameKnownRegion = true).merge
        )
    }

    // ------------------------------------------------------------------
    // 行高口径
    // ------------------------------------------------------------------

    @Test
    fun lineHeightDividesByLineCount() {
        val paragraph = block(0, lines("a", "b", "c", "d"), 0f, 0f, 300f, 140f, lineCount = 4)
        assertEquals(4, TextLineGeometry.lineCount(paragraph))
        assertEquals(35f, TextLineGeometry.lineHeightPx(paragraph), 0.01f)
    }

    @Test
    fun verticalColumnLineHeightIsColumnWidth() {
        val column = block(0, "세로쓰기", 100f, 100f, 140f, 300f)
        assertTrue(TextLineGeometry.isVerticalColumn(column))
        assertEquals(40f, TextLineGeometry.lineHeightPx(column), 0.01f)
    }

    @Test
    fun multiLineBlockIsNeverVertical() {
        // 横排三行的大框高宽比可以到 1.5~2.2：文本带换行时**永不**判竖排。
        val paragraph = block(0, lines("가", "나", "다"), 100f, 100f, 200f, 300f, lineCount = 3)
        assertFalse(TextLineGeometry.isVerticalColumn(paragraph))
    }

    private fun lines(vararg values: String): String = values.joinToString("\n")

    /** 按 TL/TR/BR/BL 顺序生成一个带倾角的四边形。 */
    private fun quad(degrees: Float): List<QuadPoint> {
        val rad = Math.toRadians(degrees.toDouble())
        val width = 300f
        val height = 40f
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()
        fun point(dx: Float, dy: Float): QuadPoint {
            val x = dx * cos - dy * sin
            val y = dx * sin + dy * cos
            return QuadPoint(x, y)
        }
        return listOf(
            point(0f, 0f),
            point(width, 0f),
            point(width, height),
            point(0f, height)
        )
    }

    private fun block(
        id: Int,
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        lineCount: Int = 1,
        polygon: List<QuadPoint>? = null
    ): TextBlock = TextBlock(
        id = id,
        text = text,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        confidence = 0.9f,
        polygon = polygon,
        lineCount = lineCount
    )

    private val a = "『케이지』라고요…!!"
    private val b = "접속종료를 하려면 죽거나"
    private val c = "접속게이트로 돌아가거나"
    private val d = "두 가지 밖에 없는데…!!"
}
