package com.manga.translator.app

import com.manga.translator.translate.Bubble
import com.manga.translator.translate.BubbleRegion
import com.manga.translator.translate.BubbleRegionType
import com.manga.translator.translate.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接缝「多碎片成组」+ 同容器句末例外 + 高字 SFX + 容器轮廓跨缝。
 *
 * 拼接图布局与 [SeamBubbleMergerTest] 一致：上条带 200 + 本图 1000 + 下条带 200 = 1400，
 * 宽 800；topLimit=200、bottomLimit=1200。
 */
class SeamFragmentGroupsTest {

    private val canvasWidth = 800
    private val canvasHeight = 1400
    private val topLimit = 200f
    private val bottomLimit = 1200f

    private fun bubble(
        text: String,
        bgType: String = "solid",
        regionId: String? = null,
        regionType: String? = null,
        confidence: Float = 1f,
        rotationDeg: Float? = null,
        sourceLineCount: Int = 1,
        lineBoxes: List<FloatArray>? = null
    ): Bubble = Bubble(
        x = 0f,
        y = 0f,
        w = 10f,
        h = 10f,
        bgType = bgType,
        originalText = text,
        translatedText = "",
        rotationDeg = rotationDeg,
        sourceLineCount = sourceLineCount,
        regionId = regionId,
        regionType = regionType,
        confidence = confidence,
        lineBoxes = lineBoxes
    )

    private fun detection(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        content: SeamBox? = null,
        container: SeamBox? = null,
        bgType: String = "solid",
        regionId: String? = null,
        regionType: String? = null,
        confidence: Float = 1f,
        rotationDeg: Float? = null,
        sourceLineCount: Int = 1,
        lineBoxes: List<FloatArray>? = null
    ): SeamDetection {
        val outer = SeamBox(left, top, right, bottom)
        return SeamDetection(
            bubble = bubble(
                text = text,
                bgType = bgType,
                regionId = regionId,
                regionType = regionType,
                confidence = confidence,
                rotationDeg = rotationDeg,
                sourceLineCount = sourceLineCount,
                lineBoxes = lineBoxes
            ),
            outer = outer,
            content = content ?: outer,
            container = container
        )
    }

    private fun config(maxCrossHeight: Float = 400f): SeamConfig = SeamConfig(
        topLimitPx = topLimit,
        bottomLimitPx = bottomLimit,
        topBandPx = 200f,
        bottomBandPx = 200f,
        edgeWindowPx = 60f,
        maxCrossHeightPx = maxCrossHeight
    )

    private fun plan(
        detections: List<SeamDetection>,
        config: SeamConfig = config()
    ): SeamPlan = planSeamOwnership(
        detections = detections,
        config = config,
        incomingCrossBoxes = emptyList(),
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight
    )

    // -----------------------------------------------------------------------
    // 1) 同容器多碎片成组（≥2，行数相加）
    // -----------------------------------------------------------------------

    @Test
    fun threeFragmentsOfSameRegionFormOneCandidateWithSummedLineCount() {
        val regionId = "bubble:7"
        val plan = plan(
            listOf(
                detection(
                    text = "첫 줄",
                    left = 300f, top = 1080f, right = 700f, bottom = 1195f,
                    regionId = regionId, regionType = SeamFragmentGroups.REGION_TYPE_SPEECH
                ),
                detection(
                    text = "둘째 줄",
                    left = 300f, top = 1205f, right = 700f, bottom = 1260f,
                    regionId = regionId, regionType = SeamFragmentGroups.REGION_TYPE_SPEECH
                ),
                detection(
                    text = "셋째 줄",
                    left = 300f, top = 1260f, right = 700f, bottom = 1315f,
                    regionId = regionId, regionType = SeamFragmentGroups.REGION_TYPE_SPEECH
                )
            )
        )

        assertEquals(1, plan.owned.size)
        assertEquals(1, plan.groupedRegion)
        assertEquals(0, plan.groupedBody)
        assertEquals(3, plan.groupedFragments)
        assertEquals(0, plan.pairedBottomSeam)
        assertTrue(plan.verdicts.all { it == "grouped_region" })

        val merged = plan.owned.single().bubble
        assertEquals("첫 줄\n둘째 줄\n셋째 줄", merged.originalText)
        assertEquals(3, merged.sourceLineCount)
        assertTrue(plan.owned.single().cross)
        // 外框百分比 = 三块并集
        assertEquals(300f / canvasWidth * 100f, merged.x, 0.01f)
        assertEquals(1080f / canvasHeight * 100f, merged.y, 0.01f)
        assertEquals(400f / canvasWidth * 100f, merged.w, 0.01f)
        assertEquals((1315f - 1080f) / canvasHeight * 100f, merged.h, 0.01f)
    }

    @Test
    fun duplicateTextFragmentsDoNotInflateMergedTextOrLineCount() {
        // ML Kit 分块检测可能在重叠区把同一句报两次（框偏移、IoU 不高）。
        // 成组时不能把同一句重复拼进去，也不能把行数相加成 2。
        val regionId = "bubble:dup"
        val plan = plan(
            listOf(
                detection(
                    "같은 말", 300f, 1120f, 700f, 1210f,
                    regionId = regionId, regionType = SeamFragmentGroups.REGION_TYPE_SPEECH
                ),
                detection(
                    "같은 말", 300f, 1180f, 700f, 1270f,
                    regionId = regionId, regionType = SeamFragmentGroups.REGION_TYPE_SPEECH
                )
            )
        )

        assertEquals(1, plan.groupedRegion)
        val merged = plan.owned.single().bubble
        assertEquals("같은 말", merged.originalText)
        assertEquals(1, merged.sourceLineCount)
    }

    @Test
    fun overlappingLineBoxesDoNotInflateMergedLineCount() {
        // 同一行被 ML Kit 报成两个略偏移的碎片：逐行框重叠 → 视觉行数仍是 1。
        val regionId = "bubble:line-dup"
        val plan = plan(
            listOf(
                detection(
                    "첫 조각", 300f, 1120f, 700f, 1210f,
                    regionId = regionId, regionType = SeamFragmentGroups.REGION_TYPE_SPEECH,
                    lineBoxes = listOf(floatArrayOf(300f, 1120f, 700f, 1170f))
                ),
                detection(
                    "둘째 조각", 300f, 1160f, 700f, 1250f,
                    regionId = regionId, regionType = SeamFragmentGroups.REGION_TYPE_SPEECH,
                    lineBoxes = listOf(floatArrayOf(300f, 1140f, 700f, 1190f))
                )
            )
        )

        assertEquals(1, plan.groupedRegion)
        assertEquals(1, plan.owned.single().bubble.sourceLineCount)
    }

    @Test
    fun sideBySideFragmentsInSameRegionAreNotGrouped() {
        // 一个大亮区可能装多个独立气泡：同 region_id 但横向并排 → 不能成组。
        val regionId = "bubble:wide"
        val plan = plan(
            listOf(
                detection(
                    "왼쪽 대사", 200f, 1120f, 350f, 1195f,
                    regionId = regionId, regionType = SeamFragmentGroups.REGION_TYPE_SPEECH
                ),
                detection(
                    "오른쪽 대사", 500f, 1205f, 650f, 1280f,
                    regionId = regionId, regionType = SeamFragmentGroups.REGION_TYPE_SPEECH
                )
            )
        )

        assertEquals(0, plan.groupedRegion)
        assertFalse(plan.verdicts.any { it == "grouped_region" })
    }

    @Test
    fun veryDifferentLineHeightsInSameRegionAreNotGrouped() {
        val regionId = "bubble:mixed"
        val plan = plan(
            listOf(
                detection(
                    "큰 글자", 300f, 1080f, 700f, 1180f,
                    regionId = regionId, regionType = SeamFragmentGroups.REGION_TYPE_SPEECH
                ),
                detection(
                    "작은 글자", 300f, 1205f, 700f, 1235f,
                    regionId = regionId, regionType = SeamFragmentGroups.REGION_TYPE_SPEECH
                )
            )
        )

        assertEquals(0, plan.groupedRegion)
        assertFalse(plan.verdicts.any { it == "grouped_region" })
    }

    // -----------------------------------------------------------------------
    // 2) 匿名正文成组：≥3 才成组，2 条仍走 1:1
    // -----------------------------------------------------------------------

    @Test
    fun threeAnonymousBodyLinesAcrossSeamAreGrouped() {
        val plan = plan(
            listOf(
                detection("본문 첫 줄", 300f, 1090f, 700f, 1180f, confidence = 0.9f),
                detection("본문 둘째 줄", 300f, 1190f, 700f, 1280f, confidence = 0.9f),
                detection("본문 셋째 줄", 300f, 1290f, 700f, 1380f, confidence = 0.9f)
            )
        )

        assertEquals(1, plan.owned.size)
        assertEquals(1, plan.groupedBody)
        assertEquals(0, plan.groupedRegion)
        assertEquals(3, plan.groupedFragments)
        assertTrue(plan.verdicts.all { it == "grouped_body" })
        assertEquals("본문 첫 줄\n본문 둘째 줄\n본문 셋째 줄", plan.owned.single().bubble.originalText)
        assertEquals(3, plan.owned.single().bubble.sourceLineCount)
    }

    @Test
    fun twoAnonymousBodyLinesDoNotFormAGroup() {
        val plan = plan(
            listOf(
                detection("본문 첫 줄", 300f, 1090f, 700f, 1180f, confidence = 0.9f),
                detection("본문 둘째 줄", 300f, 1205f, 700f, 1295f, confidence = 0.9f)
            )
        )

        assertEquals(0, plan.groupedBody)
        assertFalse(plan.verdicts.any { it == "grouped_body" })
        // 剩余两块仍走原 1:1 配对（门槛不变）
        assertEquals(1, plan.pairedBottomSeam)
        assertEquals("본문 첫 줄 본문 둘째 줄", plan.owned.single().bubble.originalText)
    }

    // -----------------------------------------------------------------------
    // 3) 同容器句末 + 明显间隙例外
    // -----------------------------------------------------------------------

    @Test
    fun sentenceEndingUpperHalfStillMergesWhenRegionIdsMatch() {
        // regionType=plain_text 刻意绕开 region 成组，直接验证 pairScore 的句末例外。
        val plan = plan(
            listOf(
                detection(
                    "안녕하세요.", 300f, 1060f, 700f, 1170f,
                    regionId = "same", regionType = SeamFragmentGroups.REGION_TYPE_PLAIN
                ),
                detection(
                    "그래서 말인데", 300f, 1200f, 700f, 1310f,
                    regionId = "same", regionType = SeamFragmentGroups.REGION_TYPE_PLAIN
                )
            )
        )

        assertEquals(1, plan.pairedBottomSeam)
        assertEquals(0, plan.groupedRegion)
        assertEquals("안녕하세요. 그래서 말인데", plan.owned.single().bubble.originalText)
    }

    @Test
    fun sentenceEndingUpperHalfDoesNotMergeAcrossDifferentRegions() {
        val plan = plan(
            listOf(
                detection(
                    "안녕하세요.", 300f, 1060f, 700f, 1170f,
                    regionId = "left", regionType = SeamFragmentGroups.REGION_TYPE_PLAIN
                ),
                detection(
                    "그래서 말인데", 300f, 1200f, 700f, 1310f,
                    regionId = "right", regionType = SeamFragmentGroups.REGION_TYPE_PLAIN
                )
            )
        )

        assertEquals(0, plan.pairedBottomSeam)
        assertEquals(1, plan.owned.size)
        assertEquals("안녕하세요.", plan.owned.single().bubble.originalText)
        assertEquals("dropped_tail", plan.verdicts[1])
    }

    // -----------------------------------------------------------------------
    // 4) 高字 / 大字 SFX 例外（只放宽单行跨缝）
    // -----------------------------------------------------------------------

    @Test
    fun tallSingleLineSfxIsStillPairedWhenStrictlyInset() {
        // maxCrossHeight=200，合并高度 290 > 200；但整框严格内缩（top>0 / bottom<画布高）。
        val plan = plan(
            listOf(
                detection("쾅", 300f, 1040f, 700f, 1170f, sourceLineCount = 1),
                detection("!", 300f, 1200f, 700f, 1330f, sourceLineCount = 1)
            ),
            config = config(maxCrossHeight = 200f)
        )

        assertEquals(1, plan.pairedBottomSeam)
        assertTrue(plan.owned.single().bubble.originalText.contains("쾅"))
    }

    @Test
    fun fullHeightSceneBoxIsNotAValidSingleLineCross() {
        assertFalse(
            "占满整幅高度必须拒绝",
            SeamFragmentGroups.fitsSingleLineComposite(SeamBox(100f, 0f, 700f, 1400f), canvasHeight)
        )
        assertFalse(
            "贴住画布上边也必须拒绝",
            SeamFragmentGroups.fitsSingleLineComposite(SeamBox(100f, 0f, 700f, 1000f), canvasHeight)
        )
        assertFalse(
            "超过画布高度 90% 必须拒绝",
            SeamFragmentGroups.fitsSingleLineComposite(SeamBox(100f, 100f, 700f, 1399f), canvasHeight)
        )
        assertTrue(
            "严格内缩的高字仍可保留",
            SeamFragmentGroups.fitsSingleLineComposite(SeamBox(100f, 200f, 700f, 1200f), canvasHeight)
        )
    }

    @Test
    fun fullHeightSceneLikePairIsNotMerged() {
        // 画布 800x1400、缝在 700：两半高度相同但并集顶到 0、底到 1400（占满整高）。
        // 它不像被切开的单行 SFX，必须拒绝配对；上半仍按普通 owned 处理，下半留给下一张。
        val cfg = SeamConfig(
            topLimitPx = 0f,
            bottomLimitPx = 700f,
            topBandPx = 0f,
            bottomBandPx = 700f,
            edgeWindowPx = 60f,
            maxCrossHeightPx = 400f
        )
        val plan = planSeamOwnership(
            detections = listOf(
                detection("위", 0f, 0f, 700f, 695f),
                detection("아래", 0f, 705f, 700f, 1400f)
            ),
            config = cfg,
            incomingCrossBoxes = emptyList(),
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight
        )

        assertEquals(0, plan.pairedBottomSeam)
        assertEquals(1, plan.owned.size)
        assertEquals("dropped_tail", plan.verdicts[1])
    }

    @Test
    fun tallMultiLineBubbleStillRespectsMaxCrossHeight() {
        val plan = plan(
            listOf(
                detection("여러 줄", 300f, 1040f, 700f, 1170f, sourceLineCount = 3),
                detection("이어짐", 300f, 1200f, 700f, 1330f, sourceLineCount = 3)
            ),
            config = config(maxCrossHeight = 200f)
        )

        assertEquals(0, plan.pairedBottomSeam)
    }

    // -----------------------------------------------------------------------
    // 5) 容器轮廓跨缝（solid speech_bubble，文字只在一侧）
    // -----------------------------------------------------------------------

    @Test
    fun containerCrossingWithTextOnCurrentSideIsCoveredNotDropped() {
        val container = SeamBox(300f, 1080f, 700f, 1300f)
        val content = SeamBox(320f, 1100f, 680f, 1180f)
        val plan = plan(
            listOf(
                detection(
                    "용사여",
                    300f, 1080f, 700f, 1300f,
                    content = content,
                    container = container,
                    regionId = "bubble:9",
                    regionType = SeamFragmentGroups.REGION_TYPE_SPEECH
                )
            )
        )

        assertEquals(1, plan.owned.size)
        assertEquals(1, plan.containerCross)
        assertEquals(0, plan.droppedNeighbor)
        assertEquals("cross_container", plan.verdicts.single())
        val owned = plan.owned.single()
        assertEquals("용사여", owned.bubble.originalText)
        assertFalse("cover-only 不移交下一张，避免重复", owned.cross)
        // 外框裁到当前图可见区（底边 = 下分界），不伸进下一张
        assertEquals(
            bottomLimit,
            (owned.bubble.y + owned.bubble.h) / 100f * canvasHeight,
            0.5f
        )
    }

    @Test
    fun containerCrossingWithTextOnlyOnNeighborSideIsLeftToNextImage() {
        val container = SeamBox(300f, 1150f, 700f, 1350f)
        val content = SeamBox(320f, 1210f, 680f, 1290f)
        val plan = plan(
            listOf(
                detection(
                    "다음 장 대사",
                    300f, 1150f, 700f, 1350f,
                    content = content,
                    container = container,
                    regionId = "bubble:10",
                    regionType = SeamFragmentGroups.REGION_TYPE_SPEECH
                )
            )
        )

        assertTrue(plan.owned.isEmpty())
        assertEquals(0, plan.containerCross)
        assertEquals("dropped_tail", plan.verdicts.single())
    }

    @Test
    fun captionPanelAndOversizedContainersDoNotContribute() {
        val config = config()
        val panel = detection(
            "패널",
            100f, 1000f, 700f, 1300f,
            content = SeamBox(120f, 1100f, 680f, 1180f),
            regionId = "panel:1",
            regionType = "caption_panel"
        )
        assertNull(
            SeamFragmentGroups.containerContribution(panel, config, canvasWidth, canvasHeight)
        )

        val tooWide = detection(
            "너무 넓음",
            0f, 1000f, 800f, 1300f,
            content = SeamBox(120f, 1100f, 680f, 1180f),
            regionId = "bubble:11",
            regionType = SeamFragmentGroups.REGION_TYPE_SPEECH
        )
        assertNull(
            SeamFragmentGroups.containerContribution(tooWide, config, canvasWidth, canvasHeight)
        )

        val tooTall = detection(
            "너무 높음",
            300f, 700f, 700f, 1400f,
            content = SeamBox(320f, 1100f, 680f, 1180f),
            regionId = "bubble:12",
            regionType = SeamFragmentGroups.REGION_TYPE_SPEECH
        )
        assertNull(
            SeamFragmentGroups.containerContribution(tooTall, config, canvasWidth, canvasHeight)
        )
    }

    @Test
    fun plannerCarriesContainerIdentityAndConfidenceIntoBubble() {
        val region = BubbleRegion(
            id = 71,
            left = 300f,
            top = 1080f,
            right = 700f,
            bottom = 1200f,
            regionType = BubbleRegionType.BUBBLE,
            confidence = 0.95f,
            parentId = 7
        )
        val block = TextBlock(
            id = 0,
            text = "본문",
            left = 320f,
            top = 1100f,
            right = 680f,
            bottom = 1180f,
            confidence = 0.85f
        )
        val bubble = BubbleFirstRegionPlanner()
            .buildBubbleFromBlocks(region, listOf(block), canvasWidth, canvasHeight)!!

        // 拆分出的子区域必须指回原容器 id，否则多碎片成组 / 句末例外都用不上。
        assertEquals("7", bubble.regionId)
        assertEquals(SeamFragmentGroups.REGION_TYPE_SPEECH, bubble.regionType)
        assertEquals(0.85f, bubble.confidence, 0.001f)
    }
}
