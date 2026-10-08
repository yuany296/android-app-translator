package com.manga.translator.app

import com.manga.translator.translate.BubbleRegion
import com.manga.translator.translate.BubbleRegionType
import com.manga.translator.translate.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.abs

class BubbleFirstRegionPlannerTest {
    private val planner = BubbleFirstRegionPlanner()

    @Test
    fun route_assignsBlocksInsideBubbleAndSeparatesOutside() {
        val regions = listOf(
            BubbleRegion(
                id = 7,
                left = 100f,
                top = 100f,
                right = 220f,
                bottom = 280f,
                regionType = BubbleRegionType.BUBBLE,
                confidence = 0.9f,
                source = "test"
            )
        )
        val blocks = listOf(
            block(1, "bubbleA", 120f, 120f, 150f, 190f),
            block(2, "bubbleB", 160f, 130f, 190f, 250f),
            block(3, "outside", 320f, 130f, 360f, 190f)
        )

        val routing = planner.route(regions, blocks, 1080, 1920)

        assertEquals(1, routing.bubbleAssignments.size)
        assertEquals(2, routing.bubbleAssignments.first().blocks.size)
        assertEquals(1, routing.outsideAssignments.size)
        assertEquals(1, routing.outsideAssignments.first().blocks.size)
        assertEquals("outside", routing.outsideAssignments.first().blocks.first().text)
    }

    @Test
    fun buildBubbleFromBlocks_keepsOutsideAsTransparentIndependentBubble() {
        val region = BubbleRegion(
            id = 3,
            left = 300f,
            top = 120f,
            right = 390f,
            bottom = 220f,
            regionType = BubbleRegionType.OUTSIDE,
            confidence = 1f,
            source = "outside"
        )
        val bubble = planner.buildBubbleFromBlocks(
            region = region,
            blocks = listOf(
                block(1, "sideA", 320f, 130f, 340f, 180f),
                block(2, "sideB", 342f, 130f, 362f, 180f)
            ),
            imageWidth = 1080,
            imageHeight = 1920
        )

        assertTrue(bubble != null)
        assertEquals("none", bubble?.bgType)
        assertEquals("sideBsideA", bubble?.originalText)
        assertEquals("vertical", bubble?.layout)
        assertTrue(abs((bubble?.contentX ?: 0f) - (320f / 1080f * 100f)) < 0.2f)
        assertTrue(abs((bubble?.contentY ?: 0f) - (130f / 1920f * 100f)) < 0.2f)
        assertTrue(abs((bubble?.contentW ?: 0f) - (42f / 1080f * 100f)) < 0.2f)
        assertTrue(abs((bubble?.contentH ?: 0f) - (50f / 1920f * 100f)) < 0.2f)
    }

    @Test
    fun buildBubblesFromAssignment_splitsSeparatedGroupsInsideWideRegion() {
        val assignment = BubbleRegionAssignment(
            region = BubbleRegion(
                id = 11,
                left = 80f,
                top = 120f,
                right = 420f,
                bottom = 320f,
                regionType = BubbleRegionType.BUBBLE,
                confidence = 0.95f,
                source = "test"
            ),
            blocks = listOf(
                block(1, "left1", 110f, 140f, 140f, 250f),
                block(2, "left2", 150f, 145f, 180f, 255f),
                block(3, "right1", 320f, 150f, 350f, 255f),
                block(4, "right2", 360f, 152f, 390f, 258f)
            )
        )

        val bubbles = planner.buildBubblesFromAssignment(assignment, 1080, 1920)

        assertEquals(2, bubbles.size)
        assertTrue(bubbles.all { it.layout == "vertical" })
        assertFalse(kotlin.math.abs(bubbles[0].x - bubbles[1].x) < 1f)
        assertTrue(bubbles.all { it.contentW <= it.w })
        assertTrue(bubbles.all { it.contentH <= it.h })
    }

    @Test
    fun buildBubblesFromAssignment_doesNotMergeAcrossWhitespaceGutter() {
        val assignment = BubbleRegionAssignment(
            region = BubbleRegion(
                id = 15,
                left = 80f,
                top = 120f,
                right = 420f,
                bottom = 320f,
                regionType = BubbleRegionType.BUBBLE,
                confidence = 0.95f,
                source = "test"
            ),
            blocks = listOf(
                block(1, "left1", 110f, 140f, 140f, 250f),
                block(2, "left2", 150f, 145f, 180f, 255f),
                block(3, "right1", 215f, 150f, 245f, 255f),
                block(4, "right2", 255f, 152f, 285f, 258f)
            )
        )

        val bubbles = planner.buildBubblesFromAssignment(
            assignment = assignment,
            imageWidth = 1080,
            imageHeight = 1920,
            separatedByWhitespace = { a, b -> a.id <= 2 && b.id >= 3 || a.id >= 3 && b.id <= 2 }
        )

        assertEquals(2, bubbles.size)
    }

    @Test
    fun buildBubbleFromBlocks_regionHuggingTextStaysSolid() {
        // 真实气泡：区域 = 轮廓外扩 6%，总能包住文字（`logs/h2-tilt.logcat` 里的白色椭圆）。
        val region = BubbleRegion(
            id = 0,
            left = 100f,
            top = 185f,
            right = 700f,
            bottom = 363f,
            regionType = BubbleRegionType.BUBBLE,
            confidence = 0.9f,
            source = "opencv_contour"
        )
        val bubble = planner.buildBubbleFromBlocks(
            region = region,
            blocks = listOf(block(1, "한국어 대사가", 261f, 194f, 541f, 238f)),
            imageWidth = 900,
            imageHeight = 1400
        )
        assertEquals("solid", bubble?.bgType)
    }

    @Test
    fun buildBubbleFromBlocks_regionNotContainingTextFallsBackToNone() {
        // 2026-09-30 回归（`logs/h2-tilt.logcat`）：亮斑检测器把整页浅色渐变检成一个
        // `y 981..1399` 的大亮块，而斜排文字在 `y 929..1066` —— 区域根本不含文字。
        // 旧实现只要 regionType==BUBBLE 就给 "solid"，盖板于是用采样底色纯色平涂，
        // 在渐变背景上画出一块肉眼可见的平行四边形（用户：「倾斜文本的清理图也消失了」）。
        val region = BubbleRegion(
            id = 1,
            left = 150f,
            top = 981f,
            right = 750f,
            bottom = 1399f,
            regionType = BubbleRegionType.BUBBLE,
            confidence = 0.6f,
            source = "opencv_contour"
        )
        val bubble = planner.buildBubbleFromBlocks(
            region = region,
            blocks = listOf(block(1, "기울어진 텍스트", 206f, 929f, 489f, 1065f)),
            imageWidth = 900,
            imageHeight = 1400
        )
        assertEquals("none", bubble?.bgType)
    }

    @Test
    fun buildBubbleFromBlocks_toleratesFewPixelRegionShrink() {
        // 区域比文字框小几个像素（检测器 pad 与 OCR 边界口径差）仍算「包住」，
        // 避免把正常气泡误判成假阳性、退到清理图。
        val region = BubbleRegion(
            id = 2,
            left = 265f,
            top = 197f,
            right = 537f,
            bottom = 235f,
            regionType = BubbleRegionType.BUBBLE,
            confidence = 0.9f,
            source = "opencv_contour"
        )
        val bubble = planner.buildBubbleFromBlocks(
            region = region,
            blocks = listOf(block(1, "한국어 대사가", 261f, 194f, 541f, 238f)),
            imageWidth = 900,
            imageHeight = 1400
        )
        assertEquals("solid", bubble?.bgType)
    }

    // ------------------------------------------------------------------
    // 竖排判定的两个方向都要守住（2026-09-30 用户反馈）
    //
    // 症状：横排三行韩文被判成竖排 → 原文按列序（centerX 降序）+ 无分隔符拼接，
    // 译文成了「捕, 追术教导技将它NPC唯一的那」。
    //
    // 注意**不能用「按源语言关掉竖排」来修**：韩文里也有竖排（标题/招牌/竖写拟声词），
    // 那样会弄坏它。所以判据必须是**纯几何、与语言无关**的块排布关系。
    // ------------------------------------------------------------------

    @Test
    fun wideThreeLineHorizontalRegionIsNotVertical() {
        // 用户那个气泡的形状：三行横排，横向跨度相近、纵向分离 → 宽扁组合框。
        // 旧的 `hasTallBlockMajority`（count*2 >= size）在只有 1~少数块时恒真，
        // 是这次误判的直接来源。
        val region = BubbleRegion(
            id = 21,
            left = 100f,
            top = 90f,
            right = 640f,
            bottom = 370f,
            regionType = BubbleRegionType.BUBBLE,
            confidence = 0.95f,
            source = "test"
        )
        val bubble = planner.buildBubbleFromBlocks(
            region = region,
            blocks = listOf(
                block(1, "그 용을 사냥하는 기술을", 120f, 100f, 620f, 170f),
                block(2, "가르칠 수 있는 유일한 NPC가", 120f, 190f, 620f, 260f),
                block(3, "눈 앞의 여자야이라고 했다", 120f, 280f, 620f, 350f)
            ),
            imageWidth = 1080,
            imageHeight = 2400
        )
        requireNotNull(bubble)
        assertEquals("横排三行不得判成竖排", "horizontal", bubble.layout)
        assertEquals(
            "原文必须保持「行内左→右、行间上→下」",
            "그 용을 사냥하는 기술을 가르칠 수 있는 유일한 NPC가 눈 앞의 여자야이라고 했다",
            bubble.originalText
        )
    }

    @Test
    fun singleWideBlockIsNotVerticalEvenIfTall() {
        // 单个块 + 宽扁区域：绝不能判竖排。
        // 旧逻辑里 `hasTallBlockMajority` 对单块恒真 —— 一个「高块」就能翻盘。
        val region = BubbleRegion(
            id = 22,
            left = 100f,
            top = 90f,
            right = 640f,
            bottom = 320f,
            regionType = BubbleRegionType.BUBBLE,
            confidence = 0.95f,
            source = "test"
        )
        val bubble = planner.buildBubbleFromBlocks(
            region = region,
            blocks = listOf(block(1, "한 줄짜리 긴 대사입니다", 120f, 100f, 620f, 300f)),
            imageWidth = 1080,
            imageHeight = 2400
        )
        requireNotNull(bubble)
        assertEquals("宽扁区域里的单块应判横排", "horizontal", bubble.layout)
    }

    @Test
    fun shortKoreanBlockInWideRegionIsNotVertical() {
        // 短文本（≤2 字）在横排版式里极其常见（韩文助词、拟声词、`?!`）。
        // 旧的 `isVerticalTextBlock` 含 `text.length <= 2` 分支，会把它误判成竖排 ——
        // 那条判据还是**语言无关**的。这里锁住「短 ≠ 竖排」。
        val region = BubbleRegion(
            id = 23,
            left = 100f,
            top = 90f,
            right = 700f,
            bottom = 200f,
            regionType = BubbleRegionType.BUBBLE,
            confidence = 0.95f,
            source = "test"
        )
        val bubble = planner.buildBubbleFromBlocks(
            region = region,
            blocks = listOf(block(1, "큭", 120f, 110f, 200f, 170f)),
            imageWidth = 1080,
            imageHeight = 2400
        )
        requireNotNull(bubble)
        assertEquals("短韩文词在宽扁区域里应判横排", "horizontal", bubble.layout)
    }

    @Test
    fun verticalColumnPairIsStillVertical() {
        // 守卫：**不许为了修横排而牺牲真竖排**（韩文/日文竖排都要继续正确）。
        // 两个窄列并排：合起来竖长（约 2.2 倍）→ 必须仍判竖排。
        val region = BubbleRegion(
            id = 24,
            left = 240f,
            top = 90f,
            right = 360f,
            bottom = 320f,
            regionType = BubbleRegionType.BUBBLE,
            confidence = 0.95f,
            source = "test"
        )
        val bubble = planner.buildBubbleFromBlocks(
            region = region,
            blocks = listOf(
                block(1, "右列", 300f, 100f, 340f, 300f),
                block(2, "左列", 250f, 100f, 290f, 300f)
            ),
            imageWidth = 1080,
            imageHeight = 2400
        )
        requireNotNull(bubble)
        assertEquals("并排的窄列必须仍判竖排", "vertical", bubble.layout)
        assertEquals("竖排按右→左读", "右列左列", bubble.originalText)
    }

    @Test
    fun legacyHeightMajorityHeuristicWouldMisjudgeShortWideRegion() {
        // 记录旧判据在什么形状下会翻车，防止有人把它改回去。
        //
        // 旧判据（git HEAD）：`textStack || (verticalHintCount*2 >= size) || (blockAspect && regionPortrait)`。
        // 实测：用户那个「三行横排 + 宽扁区域」用它算出来其实是横排 —— 所以那次错乱的主因
        // 在 **PaddleOcrBlockMerger**（原文按列序打乱），不在 planner（已由 merger 的测试守住）。
        //
        // 但旧判据确实在**矮而窄**的区域上会误判：`1*2 >= 1` 恒真 → 单块也判竖排。
        // 这里把那个形状钉住，避免旧逻辑回流。
        val shortNarrowRegion = BubbleRegion(
            id = 31,
            left = 300f,
            top = 100f,
            right = 360f,
            bottom = 220f,
            regionType = BubbleRegionType.BUBBLE,
            confidence = 0.95f,
            source = "test"
        )
        val oneWideBlock = listOf(block(1, "여자야이라고", 300f, 100f, 360f, 200f))
        assertTrue(
            "旧判据在这种形状下会误判成竖排（这正是要避免的）",
            legacyVerticalHeuristic(shortNarrowRegion, oneWideBlock)
        )

        // 新判据必须给出横排：单块高宽比 1.67 < 2.0（VERTICAL_BLOCK_MIN_ASPECT）。
        val bubble = planner.buildBubbleFromBlocks(
            region = shortNarrowRegion,
            blocks = oneWideBlock,
            imageWidth = 1080,
            imageHeight = 2400
        )
        requireNotNull(bubble)
        assertEquals("新判据应拒绝这种伪竖排", "horizontal", bubble.layout)
    }

    /**
     * 复刻 git HEAD 的竖排判据（仅用于测试对照，不参与生产逻辑）。
     *
     * 放在测试里而不是生产代码里：它是**被替换掉的错误逻辑**，留在生产代码里只会误导。
     */
    private fun legacyVerticalHeuristic(region: BubbleRegion, blocks: List<TextBlock>): Boolean {
        fun h(b: TextBlock) = (b.bottom - b.top).coerceAtLeast(1f)
        fun w(b: TextBlock) = (b.right - b.left).coerceAtLeast(1f)
        val rawWidth = (blocks.maxOf { it.right } - blocks.minOf { it.left }).coerceAtLeast(1f)
        val rawHeight = (blocks.maxOf { it.bottom } - blocks.minOf { it.top }).coerceAtLeast(1f)
        val regionWidth = (region.right - region.left).coerceAtLeast(1f)
        val regionHeight = (region.bottom - region.top).coerceAtLeast(1f)
        val hintCount = blocks.count { h(it) >= w(it) * 1.15f || it.text.trim().length <= 2 }
        val tallMajority = hintCount * 2 >= blocks.size
        val textStack = rawHeight >= rawWidth * 1.02f
        val blockAspect = blocks.map { h(it) }.average() >= blocks.map { w(it) }.average() * 1.15f
        val regionPortrait = regionHeight >= regionWidth * 1.05f
        return when (region.regionType) {
            BubbleRegionType.BUBBLE -> textStack || tallMajority || (blockAspect && regionPortrait)
            BubbleRegionType.OUTSIDE -> rawHeight >= rawWidth * 1.10f || tallMajority
        }
    }

    private fun block(id: Int, text: String, left: Float, top: Float, right: Float, bottom: Float): TextBlock {
        return TextBlock(
            id = id,
            text = text,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            confidence = 1f
        )
    }

    /** 多行块（ML Kit 段落块 / Paddle 合并块的形态）：行数显式带出。 */
    private fun blockWithLines(
        id: Int,
        lines: List<String>,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): TextBlock = block(id, lines.joinToString("\n"), left, top, right, bottom)
        .copy(lineCount = lines.size)

    // -----------------------------------------------------------------------
    // 缝感知分组（2026-10-03）：真机实测「两个气泡的台词被并成一句」
    // -----------------------------------------------------------------------

    @Test
    fun blocksOnOppositeSidesOfTheSeamAreNotMerged() {
        // 现场形态：上一气泡的尾行（bottom 贴缝）与下一气泡的首行（在条带里）几何上挨得很近。
        // 缝 = 2400，余量 = 18px（行高 ~50 的 0.35 倍）；两块的间隙 60px > 18 → 不合并。
        planner.setSeamBoundary(2400f, 18f)
        try {
            val upper = block(1, "두 가지 밖에 없는데.", 60f, 2340f, 500f, 2392f)
            val lower = block(2, "악질들한테 걸리기라도", 100f, 2452f, 520f, 2500f)
            val routing = planner.route(emptyList(), listOf(upper, lower), 600, 2688, null)
            val groups = routing.outsideAssignments.map { it.blocks.size } + routing.bubbleAssignments.map { it.blocks.size }
            assertFalse("跨缝两块不能被并成一组（实际 $groups）", groups.any { it == 2 })
        } finally {
            planner.setSeamBoundary(0f, 0f)
        }
    }

    @Test
    fun aLineCutByTheSeamIsStillMerged() {
        // 真被切开的一行：间隙只有几像素（≤ 余量）→ 仍然合并（交给归属/配对层处理）
        planner.setSeamBoundary(2400f, 18f)
        try {
            val upper = block(1, "잘려진 반", 60f, 2378f, 500f, 2397f)
            val lower = block(2, "줄", 100f, 2400f, 520f, 2420f)
            val routing = planner.route(emptyList(), listOf(upper, lower), 600, 2688, null)
            val groups = routing.outsideAssignments.map { it.blocks.size } + routing.bubbleAssignments.map { it.blocks.size }
            assertTrue("贴着缝的两块应当合并（实际 $groups）", groups.any { it == 2 })
        } finally {
            planner.setSeamBoundary(0f, 0f)
        }
    }

    @Test
    fun blocksAcrossTheTopBandBoundaryAreNeverMerged() {
        // 2026-10-03 真机 `[32317270]`：拼接图上方 144px 是**上一张图**的条带，它里面的字与本图的字
        // 被并成一行（`방해됐단양이o를 하려면 죽거나`），随后整块被当成「上一张已画过」丢掉。
        // 上边界因此也必须是不合并屏障 —— 且**不设余量**（不存在「被切开的一行」这种情形）。
        planner.setSeamBoundaries(topBoundaryPx = 144f, bottomBoundaryPx = 2544f, cutGracePx = 18f)
        try {
            val inBand = block(1, "…를 하려면 죽거나", 83f, 101f, 492f, 140f)
            val below = block(2, "방해됐단말이야…", 83f, 146f, 492f, 180f)
            val routing = planner.route(emptyList(), listOf(inBand, below), 600, 2544, null)
            val groups = routing.outsideAssignments.map { it.blocks.size } +
                routing.bubbleAssignments.map { it.blocks.size }
            assertFalse("条带里的字与本图的字不得合并（实际 $groups）", groups.any { it == 2 })
        } finally {
            planner.setSeamBoundaries(0f, 0f, 0f)
        }
    }

    @Test
    fun blocksInsideTheSameSideOfTheTopBoundaryStillMerge() {
        // 守卫：上边界屏障不能误伤「都在本图内」的正常多行合并
        planner.setSeamBoundaries(topBoundaryPx = 144f, bottomBoundaryPx = 2544f, cutGracePx = 18f)
        try {
            val first = block(1, "첫 줄입니다", 83f, 160f, 492f, 200f)
            val second = block(2, "둘째 줄입니다", 83f, 208f, 492f, 248f)
            val routing = planner.route(emptyList(), listOf(first, second), 600, 2544, null)
            val groups = routing.outsideAssignments.map { it.blocks.size } +
                routing.bubbleAssignments.map { it.blocks.size }
            assertTrue("本图内的相邻两行仍要合并（实际 $groups）", groups.any { it == 2 })
        } finally {
            planner.setSeamBoundaries(0f, 0f, 0f)
        }
    }

    // -----------------------------------------------------------------------
    // 收严合并判据（2026-10-03）：真机现场「两个独立气泡被并成一段」
    //
    // 现场（`.verify/38_user.logcat` trace `[21930477]`）：区域检测 0 命中，块走 outside
    // 分组；上气泡 4 行块 `@192,83 300x139` 与下气泡 `@77,272 272x65` / `@69,343 300x29`
    // 被旧判据（阈值以**块高**为单位，上限 ≈188px）并成一句、一块盖板。
    // 新判据以**行高**为单位，同一对的上限是 `1.1 × 33.6 ≈ 37px`。
    // -----------------------------------------------------------------------

    @Test
    fun separateBubblesWithoutAnyRegionAreNotMerged() {
        val upperBubble = blockWithLines(
            0,
            listOf("『케이지』라고요…!!", "접속종료를 하려면 죽거나", "접속게이트로 돌아가거나", "두 가지 밖에 없는데…!!"),
            192f, 83f, 492f, 222f
        )
        val lowerBubbleLine1 = blockWithLines(
            1, listOf("악질들한테 걸리기라도", "하면 죽지도"), 77f, 272f, 349f, 337f
        )
        val lowerBubbleLine2 = block(2, "고통받을수도 있습니다!", 69f, 343f, 369f, 372f)

        val routing = planner.route(
            bubbleRegions = emptyList(),
            textBlocks = listOf(upperBubble, lowerBubbleLine1, lowerBubbleLine2),
            imageWidth = 720,
            imageHeight = 1400
        )

        val groups = routing.outsideAssignments.map { assignment -> assignment.blocks.map { it.id }.toSet() }
        assertFalse(
            "上气泡与下气泡不得落在同一组（实际 $groups）",
            groups.any { it.containsAll(setOf(0, 1)) || it.containsAll(setOf(0, 2)) }
        )
        assertTrue(
            "下气泡的两块应合成一组（实际 $groups）",
            groups.any { it == setOf(1, 2) }
        )
    }

    @Test
    fun twoParagraphsInsideOneRegionSplitIntoTwoBubbles() {
        // 「一个气泡框里的两段文字」也要保持两段：行距 ≥1.1 倍字高（空行分段）时拆开。
        val assignment = BubbleRegionAssignment(
            region = BubbleRegion(
                id = 41,
                left = 80f,
                top = 90f,
                right = 560f,
                bottom = 400f,
                regionType = BubbleRegionType.BUBBLE,
                confidence = 0.95f,
                source = "test"
            ),
            blocks = listOf(
                block(1, "첫 번째 문단입니다", 100f, 100f, 500f, 140f),
                block(2, "둘째 문단입니다", 100f, 220f, 500f, 260f)
            )
        )

        val bubbles = planner.buildBubblesFromAssignment(assignment, 720, 1400)

        assertEquals("空行分段必须产出两个气泡", 2, bubbles.size)
        assertEquals(
            "两段原文都要保留",
            setOf("첫 번째 문단입니다", "둘째 문단입니다"),
            bubbles.map { it.originalText }.toSet()
        )
    }

    @Test
    fun sameBubbleLinesStillProduceSingleBubble() {
        // 守卫：收严不得把同一气泡的多行拆成逐行（那会让译文变成一行一句）。
        val assignment = BubbleRegionAssignment(
            region = BubbleRegion(
                id = 42,
                left = 80f,
                top = 90f,
                right = 560f,
                bottom = 400f,
                regionType = BubbleRegionType.BUBBLE,
                confidence = 0.95f,
                source = "test"
            ),
            blocks = listOf(
                blockWithLines(
                    1, listOf("접속종료를 하려면 죽거나", "접속게이트로 돌아가거나"), 100f, 100f, 500f, 170f
                ),
                block(2, "두 가지 밖에 없는데…!!", 100f, 185f, 500f, 215f)
            )
        )

        val bubbles = planner.buildBubblesFromAssignment(assignment, 720, 1400)

        assertEquals("同一气泡的多行必须仍是一个气泡", 1, bubbles.size)
        assertTrue(
            "两段原文都要在同一个气泡里",
            bubbles.first().originalText.contains("접속종료를") &&
                bubbles.first().originalText.contains("두 가지")
        )
    }
}
