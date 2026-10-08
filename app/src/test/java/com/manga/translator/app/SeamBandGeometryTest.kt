package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接缝带几何单测（2026-09-30）。
 *
 * 覆盖的是跨图气泡「一块盖板 + 一份译文」改造里最容易出错的一步：坐标换算。
 * 三种坐标（源图百分比 / 拼接图像素 / 带内百分比）混一处就会让盖板与译文各自错位，
 * 而这类错误在真机上只表现为「接缝处偏了几十像素」，很难从截图反推是哪一个换算错了。
 */
class SeamBandGeometryTest {

    @Test
    fun bandOf_unionsBoxesAndClampsTopToSourceStart() {
        // 两个框：一个在本图内、一个伸进 topStrip（那段属于上一张图，必须夹掉）。
        val band = SeamBandGeometry.bandOf(
            boxes = listOf(
                SeamBandGeometry.Box(100f, 900f, 400f, 1100f),
                SeamBandGeometry.Box(120f, 520f, 380f, 980f)
            ),
            padPx = 3f,
            stitchedWidth = 1080f,
            limitTop = 600f,
            stitchedHeight = 3200f
        )
        requireNotNull(band)
        assertEquals(97f, band.left, 0.01f)
        // 上端被抬到 topStrip 之后（600），而不是 517
        assertEquals(600f, band.top, 0.01f)
        assertEquals(403f, band.right, 0.01f)
        assertEquals(1103f, band.bottom, 0.01f)
    }

    @Test
    fun bandOf_clampsToStitchedBoundsAndRejectsEmpty() {
        val band = SeamBandGeometry.bandOf(
            boxes = listOf(SeamBandGeometry.Box(-40f, 100f, 2000f, 4000f)),
            padPx = 5f,
            stitchedWidth = 1080f,
            limitTop = 0f,
            stitchedHeight = 3200f
        )
        requireNotNull(band)
        assertEquals(0f, band.left, 0.01f)
        assertEquals(1080f, band.right, 0.01f)
        assertEquals(3200f, band.bottom, 0.01f)

        assertNull(
            "没有合法框时不该产出带",
            SeamBandGeometry.bandOf(emptyList(), 3f, 1080f, 0f, 3200f)
        )
        assertNull(
            "退化框（零面积）不算",
            SeamBandGeometry.bandOf(
                listOf(SeamBandGeometry.Box(10f, 10f, 10f, 10f)), 3f, 1080f, 0f, 3200f
            )
        )
    }

    @Test
    fun percentBox_isIdentityWhenBandEqualsBox() {
        val box = SeamBandGeometry.Box(100f, 800f, 500f, 1200f)
        val percent = SeamBandGeometry.percentBox(box, box)
        assertEquals(0f, percent[0], 0.001f)
        assertEquals(0f, percent[1], 0.001f)
        assertEquals(100f, percent[2], 0.001f)
        assertEquals(100f, percent[3], 0.001f)
    }

    @Test
    fun percentBox_mapsStitchedPixelsIntoBandSpace() {
        val band = SeamBandGeometry.Box(0f, 800f, 1000f, 1200f)
        // 一半落在本图（拼接图 800 以下是本图）、一半落在下一张图
        val box = SeamBandGeometry.Box(100f, 1000f, 600f, 1300f)
        val percent = SeamBandGeometry.percentBox(box, band)
        assertEquals(10f, percent[0], 0.001f)
        assertEquals(50f, percent[1], 0.001f)
        assertEquals(50f, percent[2], 0.001f)
        assertEquals(75f, percent[3], 0.001f)
    }

    @Test
    fun sourcePercentToStitched_appliesTopStripOffset() {
        // 源图 1000x2000、topStrip 高 300：源图 y=40% 落在拼接图 300 + 800 = 1100
        val box = SeamBandGeometry.sourcePercentToStitched(
            percent = floatArrayOf(10f, 40f, 20f, 25f),
            srcWidth = 1000f,
            srcHeight = 2000f,
            topH = 300f
        )
        requireNotNull(box)
        assertEquals(100f, box.left, 0.01f)
        assertEquals(1100f, box.top, 0.01f)
        assertEquals(300f, box.right, 0.01f)
        assertEquals(1600f, box.bottom, 0.01f)

        assertNull(SeamBandGeometry.sourcePercentToStitched(floatArrayOf(1f, 2f, 3f), 1000f, 2000f, 0f))
    }

    @Test
    fun pointsRoundTrip_keepsStitchedPixelGeometry() {
        val band = SeamBandGeometry.Box(50f, 900f, 1050f, 1300f)
        val sourcePoints = listOf(10f, 40f, 90f, 40f, 90f, 55f, 10f, 55f)
        val stitched = SeamBandGeometry.sourcePercentPointsToStitched(
            points = sourcePoints,
            srcWidth = 1000f,
            srcHeight = 2000f,
            topH = 300f
        )
        requireNotNull(stitched)
        val percent = SeamBandGeometry.stitchedPointsToPercent(stitched, band)

        // 带内百分比 × 带尺寸 + 带原点 必须精确还原拼接图像素（跨缝两半与接缝整体同源）。
        for (index in stitched.indices step 2) {
            val px = percent[index] / 100f * band.width + band.left
            val py = percent[index + 1] / 100f * band.height + band.top
            assertEquals(stitched[index], px, 0.01f)
            assertEquals(stitched[index + 1], py, 0.01f)
        }
        assertTrue("点少于 4 个不算四边形", SeamBandGeometry.sourcePercentPointsToStitched(listOf(1f, 2f), 10f, 10f, 0f) == null)
    }

    @Test
    fun boundsOfPoints_rejectsDegenerateInput() {
        assertNull(SeamBandGeometry.boundsOfPoints(null))
        assertNull(SeamBandGeometry.boundsOfPoints(listOf(1f, 2f, 3f, 4f)))
        assertNull(SeamBandGeometry.boundsOfPoints(listOf(1f, 2f, 3f, 4f, 5f)))
        val bounds = SeamBandGeometry.boundsOfPoints(listOf(10f, 20f, 90f, 20f, 90f, 60f, 10f, 60f))
        requireNotNull(bounds)
        assertEquals(10f, bounds.left, 0.01f)
        assertEquals(20f, bounds.top, 0.01f)
        assertEquals(90f, bounds.right, 0.01f)
        assertEquals(60f, bounds.bottom, 0.01f)
    }

    // ------------------------------------------------------------------
    // 盖板外扩（2026-09-30 用户：「译文下面有时有一点点原文残留，覆盖层需要扩大一点点」）
    //
    // 这一组测试守的是**两个方向的边界**：既要盖住那一线残留（下限不退化），
    // 又不能回到「盖板比文字大出许多」那个被投诉过的状态（上限必须硬）。
    // 两者只差几个常量，没有测试守着很容易在某次调参里悄悄越界。
    // ------------------------------------------------------------------

    @Test
    fun coverPadPx_neverShrinksBelowOriginalThreePixels() {
        // 小字（或字高拿不到 = 0）：必须退回原有行为，不能比 3px 小。
        assertEquals(3f, SeamBandGeometry.coverPadPx(0f), 0.001f)
        assertEquals(3f, SeamBandGeometry.coverPadPx(10f), 0.001f)
        assertEquals(3f, SeamBandGeometry.coverPadPx(49f), 0.001f)
        assertEquals(3f, SeamBandGeometry.coverPadPx(-5f), 0.001f)
        assertEquals(3f, SeamBandGeometry.coverPadPx(Float.NaN), 0.001f)
    }

    @Test
    fun coverPadPx_growsWithLineHeightButStaysCapped() {
        // 大字：随字高增长……
        val mid = SeamBandGeometry.coverPadPx(108f)
        assertEquals(6.48f, mid, 0.01f)
        assertTrue("大字要比小字多盖一点（用户 2026-09-30 的诉求）", mid > 3f)

        // ……但必须封顶：不能回到「盖板比文字大出 30%」。
        val huge = SeamBandGeometry.coverPadPx(400f)
        assertTrue("再大的字也不能超过上限", huge <= SeamBandGeometry.COVER_PAD_MAX_PX)
        assertEquals(SeamBandGeometry.COVER_PAD_MAX_PX, huge, 0.001f)

        // 上限本身要「只有几个像素」——这是用户当初的明确要求。
        assertTrue(
            "上限必须仍在「几个像素」量级（实际 ${SeamBandGeometry.COVER_PAD_MAX_PX}）",
            SeamBandGeometry.COVER_PAD_MAX_PX <= 10f
        )
    }

    @Test
    fun coverPadPx_isMonotonicAndNeverExceedsCap() {
        // 单调不减 + 全程不越界：避免出现「字变大反而盖得更少」这种反直觉行为。
        var previous = 0f
        var lineHeight = 1f
        while (lineHeight <= 500f) {
            val pad = SeamBandGeometry.coverPadPx(lineHeight)
            assertTrue("字高 $lineHeight 时外扩应单调不减", pad >= previous - 0.001f)
            assertTrue("字高 $lineHeight 时外扩越界：$pad", pad <= SeamBandGeometry.COVER_PAD_MAX_PX + 0.001f)
            previous = pad
            lineHeight += 1f
        }
    }

    // ------------------------------------------------------------------
    // ML Kit 专用档（2026-10-01 用户：「译文下面有时候会有一点点原文残留。所以感觉覆盖层需要
    // 扩大一点点。**仅对于 ML Kit**」）
    //
    // ML Kit 只给块级框，字形范围靠 OTSU 反推，下沿会落在字形里（见 SeamBandGeometry 注释）；
    // 这一组测试守三件事：① 只有没有四边形的气泡吃这一档；② 下沿一定比通用档多；③ 上下限仍然硬。
    // ------------------------------------------------------------------

    @Test
    fun coverPadPxFor_onlyEnlargesTheMlKitPath() {
        // 有四边形（PaddleOCR，紧贴字形）→ 必须与通用档逐值相同，用户没让动它。
        for (lineHeight in listOf(0f, 20f, 60f, 108f, 400f, Float.NaN)) {
            assertEquals(
                "字高 $lineHeight 时 Paddle 路径不该被改",
                SeamBandGeometry.coverPadPx(lineHeight),
                SeamBandGeometry.coverPadPxFor(lineHeight, hasQuad = true),
                0.001f
            )
            assertEquals(
                "字高 $lineHeight 时 Paddle 路径不该有下沿额外外扩",
                0f,
                SeamBandGeometry.coverPadBottomExtraPx(lineHeight, hasQuad = true),
                0.001f
            )
        }

        // 无四边形（= ML Kit）→ 均匀外扩本身就大一档（每个字高都严格更大或持平于下限差）。
        assertTrue(
            "ML Kit 档必须比通用档大",
            SeamBandGeometry.coverPadPxFor(108f, hasQuad = false) >
                SeamBandGeometry.coverPadPxFor(108f, hasQuad = true)
        )
        assertTrue(
            "小字上 ML Kit 档也要比通用档的 3px 多",
            SeamBandGeometry.coverPadPxFor(10f, hasQuad = false) >
                SeamBandGeometry.coverPadPxFor(10f, hasQuad = true)
        )
    }

    @Test
    fun mlKitCoverPad_growsWithLineHeightButStaysCapped() {
        // 小字/字高未知 → 退到 ML Kit 自己的下限（4px），不许低于它。
        assertEquals(4f, SeamBandGeometry.mlKitCoverPadPx(0f), 0.001f)
        assertEquals(4f, SeamBandGeometry.mlKitCoverPadPx(-5f), 0.001f)
        assertEquals(4f, SeamBandGeometry.mlKitCoverPadPx(Float.NaN), 0.001f)
        assertEquals(4f, SeamBandGeometry.mlKitCoverPadPx(40f), 0.001f)

        // 大字跟着涨，但封顶。
        assertEquals(8.64f, SeamBandGeometry.mlKitCoverPadPx(108f), 0.01f)
        assertEquals(
            SeamBandGeometry.ML_KIT_COVER_PAD_MAX_PX,
            SeamBandGeometry.mlKitCoverPadPx(400f),
            0.001f
        )
        // 上限仍是「几个像素」——用户当初的明确要求（不许回到「盖板比文字大出许多」）。
        assertTrue(
            "ML Kit 档上限也必须仍在「几个像素」量级（实际 ${SeamBandGeometry.ML_KIT_COVER_PAD_MAX_PX}）",
            SeamBandGeometry.ML_KIT_COVER_PAD_MAX_PX <= 12f
        )

        var previous = 0f
        var lineHeight = 1f
        while (lineHeight <= 500f) {
            val pad = SeamBandGeometry.mlKitCoverPadPx(lineHeight)
            assertTrue("字高 $lineHeight 时 ML Kit 档应单调不减", pad >= previous - 0.001f)
            assertTrue("字高 $lineHeight 时 ML Kit 档越界：$pad", pad <= SeamBandGeometry.ML_KIT_COVER_PAD_MAX_PX + 0.001f)
            previous = pad
            lineHeight += 1f
        }
    }

    @Test
    fun mlKitCoverPadBottomExtra_isBottomOnlyAndStaysSmall() {
        // 下沿额外外扩：字高未知/小字也至少 4px（用户要的「一点点」），大字封顶 6px。
        assertEquals(4f, SeamBandGeometry.mlKitCoverPadBottomExtraPx(0f), 0.001f)
        assertEquals(4f, SeamBandGeometry.mlKitCoverPadBottomExtraPx(Float.NaN), 0.001f)
        assertEquals(6f, SeamBandGeometry.mlKitCoverPadBottomExtraPx(108f), 0.001f)
        assertEquals(6f, SeamBandGeometry.mlKitCoverPadBottomExtraPx(1000f), 0.001f)
        assertTrue(
            "下沿额外外扩必须封顶在几个像素（防往下盖过头糊掉气泡下描边）",
            SeamBandGeometry.ML_KIT_COVER_PAD_BOTTOM_MAX_PX <= 8f
        )

        // 下沿合计 = 均匀档 + 额外档；按字高单调不减，且总量仍有硬上限。
        var previous = 0f
        var lineHeight = 1f
        while (lineHeight <= 500f) {
            val total = SeamBandGeometry.mlKitCoverPadPx(lineHeight) +
                SeamBandGeometry.mlKitCoverPadBottomExtraPx(lineHeight)
            assertTrue("字高 $lineHeight 时下沿合计应单调不减", total >= previous - 0.001f)
            assertTrue(
                "字高 $lineHeight 时下沿合计越界：$total",
                total <= SeamBandGeometry.ML_KIT_COVER_PAD_MAX_PX +
                    SeamBandGeometry.ML_KIT_COVER_PAD_BOTTOM_MAX_PX + 0.001f
            )
            // 左右（= 均匀档）永远不该吃到下沿那份额外外扩：这是「别横向变宽」的硬约束。
            assertTrue(
                "字高 $lineHeight 时横向外扩不该跟着下沿一起变大",
                SeamBandGeometry.mlKitCoverPadPx(lineHeight) < total
            )
            previous = total
            lineHeight += 1f
        }
    }
}
