package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接缝收到侧的框几何单测（2026-10-03）。
 *
 * 现场（真机 `[5467219]`，用户截图）：本图上出现了三种东西 —— 上一张移交的译文、边缘露出的原文
 * （`밖에 …!!`）、以及本图自己读坏的一行乱码（`1드하데 거리기라`，框高不足 1%）。原因：
 * 移交框来自**上一张图**的字形轮廓，与本图实际字形对不齐；而本图那条碎片既不擦也不盖。
 * 这里钉住「并进同一个框」与「至少一行高」两条几何规则。
 */
class SeamIncomingGeometryTest {

    private fun box(left: Float, top: Float, right: Float, bottom: Float) =
        SeamIncomingGeometry.Box(left, top, right, bottom)

    // ---------------------------------------------------------------
    // 现场数字：移交框 + 同一行的本图碎片
    // ---------------------------------------------------------------

    @Test
    fun realWorldFragmentAboveHandoffIsAbsorbed() {
        // `cross_prep px=[103,124~357,145]`（移交框，本图坐标）
        val handoff = box(103f, 124f, 357f, 145f)
        // `erase_target idx=9 box=42x0@17,10`(百分比) → 本图坐标 ≈ x102~354, y110~123（乱码那行）
        val fragment = box(102f, 110f, 354f, 123f)
        assertTrue(
            "同一行的另一半必须能被吸收（现场：相距 1~14px、横向几乎完全重叠）",
            SeamIncomingGeometry.isSameLineFragment(handoff, fragment, lineHeightPx = 40f)
        )
        val grown = SeamIncomingGeometry.expandForAdjacentFragments(handoff, listOf(fragment), 40f)
        assertEquals("并框后上边要盖到碎片顶端", 110f, grown.top, 0.01f)
        assertEquals("并框后下边保持移交框底端", 145f, grown.bottom, 0.01f)
        assertEquals("左边取两框较小者（碎片比移交框略靠左）", 102f, grown.left, 0.01f)
        assertEquals(357f, grown.right, 0.01f)
    }

    @Test
    fun sideBySideBubbleIsNotAbsorbed() {
        // 并排的另一个气泡：纵向很近，但横向几乎不重叠（<50%）→ 不是同一行
        val handoff = box(100f, 120f, 300f, 145f)
        val neighbour = box(340f, 120f, 540f, 145f)
        assertFalse(SeamIncomingGeometry.isSameLineFragment(handoff, neighbour, 40f))
    }

    @Test
    fun multiLineBubbleNestedUnderTallHandoffIsNotAbsorbed() {
        // 2026-10-12 真机回归（gamesal 7/8 页 악질들泡）：移交框是上一张条带里看到的
        // 「下半段」，条带够深时它的框能罩住画面里**嵌在它下方的另一个多行气泡**
        // （现场：移交框 本图系 0~288，악질들 118~239，横向重叠 53%）。
        // 真正的切缝残片只有半行/一行高；两行的块是多行气泡 → 高度守卫必须挡住它，
        // 否则那个气泡被吸走：不擦、不翻、不渲染，原文整段露出。
        val handoff = box(60f, 0f, 380f, 288f)          // 条带深处的移交框（≈4 行高）
        val nestedBubble = box(69f, 118f, 341f, 239f)   // 121px ≈ 1.8 行（行高 67）
        assertFalse(
            "多行气泡不是切缝残片，不许被吸收",
            SeamIncomingGeometry.isSameLineFragment(handoff, nestedBubble, 67f)
        )
        // 对照：同样位置的单行残片（67px ≤ 1.6 行）仍然要能被吸收
        val thinFragment = box(69f, 118f, 341f, 185f)
        assertTrue(
            SeamIncomingGeometry.isSameLineFragment(handoff, thinFragment, 67f)
        )
    }

    @Test
    fun separateLineIsNotAbsorbed() {
        // 同一列的**下一行**（行距 45 > 容差 0.3×40=12）→ 不能被吃进来（它的译文我们还并不过去）
        val handoff = box(100f, 120f, 300f, 145f)
        val nextLine = box(100f, 190f, 300f, 215f)
        assertFalse(SeamIncomingGeometry.isSameLineFragment(handoff, nextLine, 40f))
    }

    @Test
    fun oversizedBlockIsNotAbsorbed() {
        // 比移交框大太多的块更可能是另一段内容（参考项目同样有 1.35 倍面积守卫）
        val handoff = box(100f, 120f, 200f, 140f) // 100x20
        val big = box(100f, 122f, 400f, 180f)      // 300x58 ≫ 1.35×2000
        assertFalse(SeamIncomingGeometry.isSameLineFragment(handoff, big, 40f))
    }

    @Test
    fun chainOfFragmentsGrowsToTheUnion() {
        // 链式碎片（同一行被横切成三截）→ 迭代到不动点，并成外接框
        val handoff = box(200f, 120f, 300f, 145f)
        val middle = box(200f, 100f, 300f, 118f)
        val top = box(200f, 80f, 300f, 98f)
        val grown = SeamIncomingGeometry.expandForAdjacentFragments(handoff, listOf(middle, top), 40f)
        assertEquals("顶端要吃掉最上面那一截", 80f, grown.top, 0.01f)
        assertEquals(145f, grown.bottom, 0.01f)
        assertEquals(200f, grown.left, 0.01f)
        assertEquals(300f, grown.right, 0.01f)
    }

    // ---------------------------------------------------------------
    // 最小高度（退化框）
    // ---------------------------------------------------------------

    @Test
    fun degenerateBoxIsGrownToOneLine() {
        // 真机 `box=42x0`：高不足 1% → 擦字擦不到、盖板只有薄片
        val grown = SeamIncomingGeometry.enforceMinimumHeight(
            box = box(100f, 250f, 400f, 255f),
            lineHeightPx = 40f,
            limitBottom = 2400f
        )
        assertEquals("至少一行高", 40f, grown.height, 0.01f)
        assertEquals("向下扩（缺的那半通常在下条带）", 250f, grown.top, 0.01f)
        assertEquals(290f, grown.bottom, 0.01f)
    }

    @Test
    fun tallEnoughBoxIsUntouched() {
        val original = box(100f, 250f, 400f, 300f)
        val grown = SeamIncomingGeometry.enforceMinimumHeight(original, 40f, 2400f)
        assertEquals(original, grown)
    }

    @Test
    fun growthIsClampedToTheImageBottom() {
        val grown = SeamIncomingGeometry.enforceMinimumHeight(
            box = box(100f, 590f, 400f, 595f),
            lineHeightPx = 40f,
            limitBottom = 600f
        )
        assertEquals("不能扩出画面", 600f, grown.bottom, 0.01f)
    }

    @Test
    fun degenerateDetectionIsReported() {
        assertTrue(SeamIncomingGeometry.isDegenerate(box(100f, 100f, 400f, 103f)))
        assertFalse(SeamIncomingGeometry.isDegenerate(box(100f, 100f, 400f, 140f)))
    }

    // ---------------------------------------------------------------
    // 相邻行（2026-10-11，对齐参考项目 seam-residuals.js 的 adjacent_line）
    //
    // 形态：缝落在行与行之间 —— 上一张移交了前几行，本图检出的是**同一气泡的下一行**。
    // 它要被并进移交框（一块盖板）+ 原文并进翻译请求（一份完整译文），而不是自己单独成块。
    // ---------------------------------------------------------------

    @Test
    fun nextLineBelowHandoffIsAdjacentFragment() {
        // 移交框 120~145（一行），下一行 165~190：间隙 20 = 0.5×行高40 ≤ 0.55×40
        val handoff = box(100f, 120f, 300f, 145f)
        val nextLine = box(100f, 165f, 300f, 190f)
        assertTrue(
            "同一气泡的下一行必须被吸收（缝落在行间）",
            SeamIncomingGeometry.isAdjacentLineFragment(handoff, nextLine, lineHeightPx = 40f)
        )
    }

    @Test
    fun previousLineAboveHandoffIsAdjacentFragment() {
        // 上一行在移交框上方（移交框顶边贴着缝、上一行还在本图内）
        val handoff = box(100f, 120f, 300f, 145f)
        val prevLine = box(100f, 75f, 300f, 100f)
        assertTrue(SeamIncomingGeometry.isAdjacentLineFragment(handoff, prevLine, 40f))
    }

    @Test
    fun slightlyOverlappingLineIsStillAdjacent() {
        // OCR 相邻行的框常有几像素重叠（参考项目允许重叠较矮行高的四成）
        val handoff = box(100f, 120f, 300f, 145f)
        val nextLine = box(100f, 140f, 300f, 165f) // 重叠 5px = 0.125×行高
        assertTrue(SeamIncomingGeometry.isAdjacentLineFragment(handoff, nextLine, 40f))
    }

    @Test
    fun farLineIsNotAdjacentFragment() {
        // 隔了一整行（间隙 75 > 0.55×40=22）→ 是更下面的另一段，不吸收
        val handoff = box(100f, 120f, 300f, 145f)
        val farLine = box(100f, 220f, 300f, 245f)
        assertFalse(SeamIncomingGeometry.isAdjacentLineFragment(handoff, farLine, 40f))
    }

    @Test
    fun sameLineHalfIsNotAdjacentFragment() {
        // 与移交框几乎叠在一起的块 = 同一行的两半（isSameLineFragment 管），不是相邻行
        val handoff = box(100f, 120f, 300f, 145f)
        val sameLine = box(102f, 124f, 300f, 145f)
        assertFalse(SeamIncomingGeometry.isAdjacentLineFragment(handoff, sameLine, 40f))
    }

    @Test
    fun multiLineBlockIsNotAdjacentFragment() {
        // 高度 90 > 1.6×行高40 = 多行块 = 另一段内容（我们没有 translationRole，宁紧勿松）
        val handoff = box(100f, 120f, 300f, 145f)
        val paragraph = box(100f, 165f, 300f, 255f)
        assertFalse(SeamIncomingGeometry.isAdjacentLineFragment(handoff, paragraph, 40f))
    }

    @Test
    fun horizontallyShiftedLineIsNotAdjacentFragment() {
        // 横向重叠 80 < 200×0.5 → 更可能是并排气泡的行
        val handoff = box(100f, 120f, 300f, 145f)
        val shifted = box(220f, 165f, 420f, 190f)
        assertFalse(SeamIncomingGeometry.isAdjacentLineFragment(handoff, shifted, 40f))
    }

    // ---------------------------------------------------------------
    // 相邻行原文并进移交气泡（译文并回 winner 的原料）
    // ---------------------------------------------------------------

    @Test
    fun mergedOriginalTextIsSortedByVerticalPosition() {
        // 移交框顶边可为负（起点在上一张图里），排序直接比 y
        val fragments = listOf(
            SeamIncomingGeometry.AbsorbedFragment(0, box(100f, 165f, 300f, 190f), "아랫줄"),
            SeamIncomingGeometry.AbsorbedFragment(1, box(100f, 75f, 300f, 100f), "윗줄")
        )
        val merged = SeamIncomingGeometry.mergeAdjacentLineText(
            partText = "가운데",
            partTop = 120f,
            fragments = fragments
        )
        assertEquals("윗줄\n가운데\n아랫줄", merged)
    }

    @Test
    fun mergedOriginalTextKeepsPartTextOnlyWithoutFragments() {
        val merged = SeamIncomingGeometry.mergeAdjacentLineText(
            partText = "반점만",
            partTop = 120f,
            fragments = emptyList()
        )
        assertEquals("반점만", merged)
    }
}
