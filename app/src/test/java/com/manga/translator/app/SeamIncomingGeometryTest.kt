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
}
