package com.manga.translator.app

import com.manga.translator.translate.Bubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分片接缝「半截字」配对单测（2026-09-28，对齐 C:\homework\translator 的 seam 判据）。
 *
 * 拼接图布局：上条带 200 + 本图 1000 + 下条带 200 = 1400 高，宽 800；
 * 分界 topLimit=200、bottomLimit=1200。
 */
class SeamBubbleMergerTest {

    private val canvasWidth = 800
    private val canvasHeight = 1400
    private val topLimit = 200f
    private val bottomLimit = 1200f

    private fun bubble(text: String, bgType: String = "solid"): Bubble {
        return Bubble(
            x = 0f,
            y = 0f,
            w = 10f,
            h = 10f,
            bgType = bgType,
            originalText = text,
            translatedText = ""
        )
    }

    private fun detection(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        bgType: String = "solid"
    ): SeamDetection {
        val box = SeamBox(left, top, right, bottom)
        return SeamDetection(bubble(text, bgType), box, box)
    }

    private fun config(
        topBand: Float = 200f,
        bottomBand: Float = 200f,
        edgeWindow: Float = 60f,
        maxCrossHeight: Float = 400f
    ): SeamConfig {
        // 分界跟着条带高度走（没有上条带时 topLimit=0），与真实调用方一致
        return SeamConfig(
            topLimitPx = topBand,
            bottomLimitPx = topBand + 1000f,
            topBandPx = topBand,
            bottomBandPx = bottomBand,
            edgeWindowPx = edgeWindow,
            maxCrossHeightPx = maxCrossHeight
        )
    }

    private fun plan(
        detections: List<SeamDetection>,
        config: SeamConfig = config(),
        incoming: List<SeamBox> = emptyList()
    ): SeamPlan {
        return planSeamOwnership(detections, config, incoming, canvasWidth, canvasHeight)
    }

    @Test
    fun wholeCrossingBubbleIsOwnedAndMarkedCross() {
        val plan = plan(listOf(detection("잘 가", 300f, 1100f, 700f, 1300f)))
        assertEquals(1, plan.owned.size)
        assertTrue(plan.owned.first().cross)
        assertEquals("잘 가", plan.owned.first().bubble.originalText)
    }

    @Test
    fun crossingTopLimitBubbleIsDroppedOnlyWhenPreviousImageHandedItOver() {
        // 上一张图**确实移交了**（incoming 覆盖本图可见部分）→ 本图丢弃，避免重复翻译
        val handed = plan(
            detections = listOf(detection("안녕", 300f, 100f, 700f, 300f)),
            incoming = listOf(SeamBox(280f, 180f, 720f, 300f))
        )
        assertTrue(handed.owned.isEmpty())
        assertEquals(1, handed.droppedNeighbor)
        assertEquals("dropped_top_handed", handed.verdicts.single())

        // 上一张图**没**移交（crossOut 为空 / 贴片不足）→ 本图必须认领，否则这句永远没译文
        // （2026-10-03 用户截图里的空洞就是这么来的）
        val orphan = plan(listOf(detection("안녕", 300f, 100f, 700f, 300f)))
        assertEquals(1, orphan.owned.size)
        assertEquals(1, orphan.orphanAdopted)
        assertEquals("orphan_adopted", orphan.verdicts.single())
        val owned = orphan.owned.single()
        assertFalse(owned.cross)
        // 裁进本图可见区（否则百分比框跑出画面，渲染层只能夹到页首）
        assertEquals(topLimit, owned.bubble.y / 100f * canvasHeight, 0.5f)
    }

    @Test
    fun twoHalvesAtBottomSeamAreMergedIntoSingleSentence() {
        val plan = plan(
            listOf(
                detection("안녕하", 300f, 1050f, 700f, 1195f),
                detection("세요", 300f, 1205f, 700f, 1340f)
            )
        )
        assertEquals(1, plan.owned.size)
        assertEquals(1, plan.pairedBottomSeam)
        val merged = plan.owned.first()
        assertEquals("안녕하 세요", merged.bubble.originalText)
        assertTrue(merged.cross)
        // 合并几何 = 两块并集（换算成百分比）
        assertEquals(300f / canvasWidth * 100f, merged.bubble.x, 0.01f)
        assertEquals(1050f / canvasHeight * 100f, merged.bubble.y, 0.01f)
        assertEquals(400f / canvasWidth * 100f, merged.bubble.w, 0.01f)
        assertEquals(290f / canvasHeight * 100f, merged.bubble.h, 0.01f)
    }

    @Test
    fun twoHalvesAtTopSeamAreMergedAndClippedIntoCurrentImage() {
        val plan = plan(
            listOf(
                detection("괜찮", 300f, 60f, 700f, 195f),
                detection("아요", 300f, 205f, 700f, 330f)
            )
        )
        assertEquals(1, plan.owned.size)
        assertEquals(1, plan.pairedTopSeam)
        val merged = plan.owned.first()
        assertEquals("괜찮 아요", merged.bubble.originalText)
        assertFalse(merged.cross)
        // 上半在上一张图里 → 几何裁进本图可见范围，保证整句都画得出来
        val topPx = merged.bubble.y / 100f * canvasHeight
        assertEquals(topLimit, topPx, 0.5f)
    }

    @Test
    fun halvesWithoutHorizontalOverlapAreNotMerged() {
        val plan = plan(
            listOf(
                detection("안녕하", 60f, 1050f, 300f, 1195f),
                detection("세요", 520f, 1205f, 760f, 1340f)
            )
        )
        assertEquals(0, plan.pairedBottomSeam)
        // 配不上对时两块都必须有归宿：
        //   · 上半 x[60,300] y[1050,1195] 完全在本图内 → 普通 owned（句子残缺，但至少不丢）；
        //   · 下半 x[520,760] y[1205,1340] 整块落在下条带里 → 丢弃（2026-10-12 起不再移交：
        //     移交框是拼接系百分比，下一张换算会整体错位一个坐标系 —— 它由下一张图
        //     自己完整看到，交给下一张的 owned 链路坐标才正确）。
        assertEquals(1, plan.owned.size)
        assertEquals(0, plan.handedTail)
        assertEquals(0, plan.orphanAdopted)
        assertEquals(1, plan.droppedNeighbor)
        val inside = plan.owned.single()
        assertEquals("안녕하", inside.bubble.originalText)
        assertFalse(inside.cross)
        assertTrue(inside.renderHere)
    }

    @Test
    fun tailBlockInBottomBandIsDroppedForNextImage() {
        // 整块落在下条带里（= 下一张图的正文）→ 丢弃（2026-10-12 起不再移交）：
        // 移交框是拼接系百分比，下一张换算整体错位（真机 gamesal 7/8 页：악질들泡移交框
        // 落第 8 页 -6.5%、真实字形 +4.9%）；下一张自己完整看到它，交给下一张的 owned 链路。
        val plan = plan(listOf(detection("다음 그림", 300f, 1205f, 700f, 1340f)))
        assertEquals(0, plan.owned.size)
        assertEquals(0, plan.handedTail)
        assertEquals(1, plan.droppedNeighbor)
    }

    @Test
    fun halvesWithVeryDifferentHeightsAreNotMerged() {
        val plan = plan(
            listOf(
                detection("안녕하", 300f, 1000f, 700f, 1195f),
                detection("세요", 300f, 1205f, 700f, 1250f)
            )
        )
        assertEquals(0, plan.pairedBottomSeam)
    }

    @Test
    fun sentenceEndingUpperHalfWithGapIsNotMerged() {
        val plan = plan(
            listOf(
                detection("안녕하세요.", 300f, 1050f, 700f, 1195f),
                detection("그래서 말인데", 300f, 1240f, 700f, 1360f)
            )
        )
        assertEquals(0, plan.pairedBottomSeam)
    }

    @Test
    fun sentenceEndingUpperHalfWithoutGapIsStillMerged() {
        // 紧贴（笔画被切开时常见 gap≈0）→ 仍然认为是被劈开的同一行
        val plan = plan(
            listOf(
                detection("무슨 일이야?", 300f, 1050f, 700f, 1199f),
                detection("라고", 300f, 1200f, 700f, 1330f)
            )
        )
        assertEquals(1, plan.pairedBottomSeam)
    }

    @Test
    fun tooTallMergedPairIsNotMerged() {
        val plan = plan(
            listOf(
                detection("위", 300f, 700f, 700f, 1195f),
                detection("아래", 300f, 1205f, 700f, 1340f)
            ),
            config = config(maxCrossHeight = 400f)
        )
        assertEquals(0, plan.pairedBottomSeam)
    }

    @Test
    fun identicalTextHalvesAreNotMerged() {
        val plan = plan(
            listOf(
                detection("같은말", 300f, 1050f, 700f, 1195f),
                detection("같은말", 300f, 1205f, 700f, 1340f)
            )
        )
        assertEquals(0, plan.pairedBottomSeam)
    }

    @Test
    fun bubbleDeepInsideNeighborBandIsDropped() {
        val plan = plan(
            listOf(
                detection("이전 그림 대사", 300f, 20f, 700f, 150f),
                detection("다음 그림 대사", 300f, 1250f, 700f, 1380f)
            )
        )
        // 上条带里那块 = 上一张图正文，且上一张没移交；它整块在本图画面之外（裁完高度为 0）
        // → 仍然丢弃（画不出来的东西不该硬画）。
        assertEquals(2, plan.droppedNeighbor)
        assertEquals(0, plan.orphanAdopted)
        // 下条带那块 = 下一张图正文 → 丢弃（2026-10-12 起不再移交，由下一张自己的 owned 链路处理）。
        assertEquals(0, plan.handedTail)
        assertEquals(0, plan.owned.size)
    }

    @Test
    fun bubbleInsideCurrentImageIsOwnedWithoutCross() {
        val plan = plan(listOf(detection("본문 대사", 300f, 500f, 700f, 650f)))
        assertEquals(1, plan.owned.size)
        assertFalse(plan.owned.first().cross)
    }

    @Test
    fun noBandsMeansEverythingInsideIsOwned() {
        val plan = plan(
            detections = listOf(detection("본문 대사", 300f, 100f, 700f, 250f)),
            config = config(topBand = 0f, bottomBand = 0f)
        )
        assertEquals(1, plan.owned.size)
        assertFalse(plan.owned.first().cross)
    }

    @Test
    fun mergedPairIsSuppressedWhenIncomingCrossAlreadyCoversIt() {
        // 上一张图已经把这句的下半部分移交过来（contentRect 顶边为负、覆盖本图顶部区域）
        val incoming = listOf(SeamBox(280f, 120f, 720f, 420f))
        val plan = plan(
            detections = listOf(
                detection("안녕하", 300f, 60f, 700f, 195f),
                detection("세요", 300f, 205f, 700f, 330f)
            ),
            incoming = incoming
        )
        assertTrue(plan.owned.isEmpty())
        assertEquals(1, plan.suppressedByIncoming)
    }

    @Test
    fun incomingCrossDoesNotSuppressUnrelatedBubble() {
        val incoming = listOf(SeamBox(60f, 120f, 300f, 420f))
        val plan = plan(
            detections = listOf(detection("다른 대사", 400f, 600f, 760f, 760f)),
            incoming = incoming
        )
        assertEquals(1, plan.owned.size)
        assertEquals(0, plan.suppressedByIncoming)
    }

    @Test
    fun pairKeepsNarrationStyleWhenOnlyOneHalfIsSolid() {
        val plan = plan(
            listOf(
                detection("나레이션", 300f, 1050f, 700f, 1195f, bgType = "none"),
                detection("이어짐", 300f, 1205f, 700f, 1340f, bgType = "solid")
            )
        )
        assertEquals(1, plan.pairedBottomSeam)
        assertEquals("none", plan.owned.first().bubble.bgType)
    }

    @Test
    fun planReportsDetectedCountForDiagnostics() {
        val plan = plan(listOf(detection("본문", 300f, 500f, 700f, 650f)))
        assertEquals(1, plan.detected)
    }

    @Test
    fun edgeWindowIsAdaptiveToLineHeight() {
        // 行高 100 → 窗口 = max(24, 100×0.6) = 60
        assertEquals(60f, SeamTuning.resolveEdgeWindow(100f), 0.01f)
        // 行高很小 → 不会小于基准窗口
        assertEquals(24f, SeamTuning.resolveEdgeWindow(5f), 0.01f)
        // 行高未知 → 基准窗口（不再跟着「最大合并高度」放大）
        assertEquals(24f, SeamTuning.resolveEdgeWindow(0f), 0.01f)
    }

    @Test
    fun maxPairGapIsRelativeToTheSmallerHalf() {
        // 150px 的两半 → max(48, 150×0.6) = 90
        assertEquals(90f, SeamTuning.resolveMaxPairGap(150f, 150f), 0.01f)
        // 小块很矮 → 保底 48
        assertEquals(48f, SeamTuning.resolveMaxPairGap(10f, 10f), 0.01f)
        // 与「最大合并高度」无关：这正是 2026-10-03「两段被合并」的失守点
        assertEquals(90f, SeamTuning.resolveMaxPairGap(150f, 150f), 0.01f)
    }

    @Test
    fun maxCrossHeightUsesAvailableBandWhenOneSideIsMissing() {
        // 章节第一张没有上条带、最后一张没有下条带：仍然要给出可用的合并上限
        assertEquals(228f, SeamTuning.maxCrossHeight(0f, 152f), 0.01f)
        assertEquals(228f, SeamTuning.maxCrossHeight(152f, 0f), 0.01f)
        assertEquals(48f, SeamTuning.maxCrossHeight(0f, 0f), 0.01f)
    }

    @Test
    fun endsSentenceDetectsCjkAndLatinPunctuation() {
        assertTrue(SeamTuning.endsSentence("안녕하세요."))
        assertTrue(SeamTuning.endsSentence("真的吗？"))
        assertTrue(SeamTuning.endsSentence("そうか！"))
        assertTrue(SeamTuning.endsSentence("정말…\""))
        assertFalse(SeamTuning.endsSentence("안녕하"))
        assertFalse(SeamTuning.endsSentence(""))
    }

    // -----------------------------------------------------------------------
    // 2026-10-03：用户现场「缝落在气泡第 2 行之后一点」
    // -----------------------------------------------------------------------

    @Test
    fun twoLinesAboveOneLineBelowAreStillMerged() {
        // 上 2 行（110px）/ 下 1 行（50px）：高度比 0.45 < 0.5，**旧的死判据会拒绝配对**，
        // 于是上半被当完整气泡翻译、下半没人管。改用「行数跨度」后应当配对成功。
        val plan = plan(
            listOf(
                detection("접속종료를 하려면 죽거나", 300f, 1080f, 700f, 1190f),
                detection("접속게이트로 돌아가거나", 300f, 1200f, 700f, 1250f)
            )
        )
        assertEquals(1, plan.pairedBottomSeam)
        assertEquals(1, plan.owned.size)
        assertTrue(plan.owned.single().cross)
        assertEquals("접속종료를 하려면 죽거나 접속게이트로 돌아가거나", plan.owned.single().bubble.originalText)
    }

    @Test
    fun deepTailInsideBottomBandIsCappedAndNotCovered() {
        // 缝下方第 3 行开始就是**下一张图正文**：切开余量 60px → 合并/跨缝框最多到 1260。
        val cfg = config().copy(cutAllowancePx = 60f)
        val plan = plan(
            detections = listOf(detection("위 두 줄", 300f, 1100f, 700f, 1350f)),
            config = cfg
        )
        assertEquals(1, plan.cappedCross)
        assertEquals("cross_capped", plan.verdicts.single())
        val owned = plan.owned.single()
        assertTrue(owned.cross)
        // 框底被裁到 1200 + 60 = 1260（否则盖板/接缝带纹理会一路盖到邻居正文上）
        val bottomPx = (owned.bubble.y + owned.bubble.h) / 100f * canvasHeight
        assertEquals(1260f, bottomPx, 0.5f)
    }

    @Test
    fun cutAllowanceFollowsTheStripNotTheLineHeight() {
        // 条带 117px、行高 42 → 余量 = 条带（117）：缝落在第 2 行之后时，第 3/4 行都在条带里，
        // 截短它会让两边各翻一遍（真机夹具实测的「缝上两层译文」）。
        assertEquals(117f, SeamTuning.resolveCutAllowance(42f, 117f), 0.01f)
        // 条带极短 → 按行数给下限（至少两行）
        assertEquals(100f, SeamTuning.resolveCutAllowance(50f, 40f), 0.01f)
        // 行高未知且条带极短 → 保底 32
        assertEquals(32f, SeamTuning.resolveCutAllowance(0f, 10f), 0.01f)
    }

    @Test
    fun maxCrossHeightGrowsWithKnownLineHeight() {
        // 条带 144 + 行高 60 = 204（**只多一行**：被切开的那半行最多也就一行）
        assertEquals(204f, SeamTuning.maxCrossHeight(0f, 144f, 60f), 0.01f)
    }

    @Test
    fun medianLineHeightDividesByLineCount() {
        // 整个 4 行块（高 400）→ 行高 100，而不是 400
        val block = detection("네 줄 짜리 대사", 300f, 500f, 700f, 900f).let {
            SeamDetection(it.bubble.copy(sourceLineCount = 4), it.outer, it.content)
        }
        assertEquals(100f, SeamTuning.medianLineHeight(listOf(block)), 0.01f)
    }

    @Test
    fun lineCountFallsBackToTextLineBreaks() {
        // 只信 sourceLineCount 会把「行高」算成整块高度 → 阈值一起放大 → 两个气泡被合并。
        val bubble = Bubble(
            x = 0f, y = 0f, w = 10f, h = 10f, bgType = "solid",
            originalText = "케이지라고요!!\n접속종료를 하려면 죽거나",
            translatedText = ""
        )
        assertEquals(2, SeamTuning.lineCountOf(bubble))
    }

    // -----------------------------------------------------------------------
    // 2026-10-03 第二轮：用户报「那两段合并了」（合并规则太宽泛）
    // -----------------------------------------------------------------------

    @Test
    fun separateBubblesAcrossTheSeamAreNotMerged() {
        // 现场形态：缝上方条带里是上气泡的残片（bottom 贴分界），缝下方是**另一个气泡**，
        // 两者之间有接近一行的间隙（真站点里是气泡描边 + 间距）。
        // 贴边窗口按行高算（行高 ≈ 100 → 窗口 60），下块 top=1265 距分界 65px → 不算贴边，
        // 于是**不配对**；若按旧口径（窗口 507px）它就会被合并成一句。
        val upper = detection("위 조각", 300f, 1100f, 700f, 1195f)
        val lower = detection("아래 다른 대사", 300f, 1265f, 700f, 1380f)
        val cfg = config(edgeWindow = SeamTuning.resolveEdgeWindow(100f))
        val plan = plan(listOf(upper, lower), config = cfg)
        assertEquals(0, plan.pairedBottomSeam)
        // 两块不合并；下块整块落在下条带里 → 丢弃（2026-10-12 起不再移交，由下一张自己的
        // owned 链路处理），本图只保留上块。
        assertEquals(1, plan.owned.size)
        assertEquals("위 조각", plan.owned.single().bubble.originalText)
        assertFalse(plan.owned.any { it.bubble.originalText.contains("위 조각 아래") })
    }

    @Test
    fun genuineMidLineCutIsStillMerged() {
        // 同一条被切开的行：间隙几像素 → 仍然合并（不能被上面的收紧误伤）
        val upper = detection("잘려진 반", 300f, 1100f, 700f, 1196f)
        val lower = detection("줄", 300f, 1200f, 700f, 1250f)
        val cfg = config(edgeWindow = SeamTuning.resolveEdgeWindow(100f))
        val plan = plan(listOf(upper, lower), config = cfg)
        assertEquals(1, plan.pairedBottomSeam)
        assertEquals("잘려진 반 줄", plan.owned.single().bubble.originalText)
    }

    // -----------------------------------------------------------------------
    // 2026-10-03 第三轮：真机 Lezhin 实测「条带边缘的残片被当气泡」（碎片守卫）
    // -----------------------------------------------------------------------

    @Test
    fun sliverAtTopEdgeIsDroppedInsteadOfAdopted() {
        // 真机实测：条带把一行切成残片，可见高度只有 25px（OCR 文本 `용살가`），
        // 以前会被认领渲染成一个 25px 高的假气泡。
        val plan = plan(
            detections = listOf(detection("용살가", 300f, 150f, 700f, 215f)),
            config = config().copy(fragmentHeightPx = 40f)
        )
        assertTrue(plan.owned.isEmpty())
        assertEquals(1, plan.droppedNeighbor)
        assertEquals("dropped_fragment", plan.verdicts.single())
    }

    @Test
    fun sliverCrossingBottomEdgeIsNotHandedOver() {
        // 跨下边界但只剩一条 21px 的薄片（真机实测 41px 的那块）→ 不移交、不渲染，
        // 留给下一张图（它能在自己那张里看到整行）。
        val plan = plan(
            detections = listOf(detection("잘린 조각", 300f, 1185f, 700f, 1215f)),
            config = config().copy(fragmentHeightPx = 40f)
        )
        assertTrue(plan.owned.isEmpty())
        assertEquals("dropped_fragment", plan.verdicts.single())
    }

    @Test
    fun normalHeightDetectionIsStillOwnedWhenFragmentGuardIsOn() {
        // 守卫不能误伤正常高度：行高阈值 40px 下，80px 高的块照常认领
        val plan = plan(
            detections = listOf(detection("정상 대사", 300f, 500f, 700f, 580f)),
            config = config().copy(fragmentHeightPx = 40f)
        )
        assertEquals(1, plan.owned.size)
        assertEquals("owned", plan.verdicts.single())
    }

    @Test
    fun mostlyAboveTopSeamIsAdoptedInsteadOfDropped() {
        // 2026-10-03 改口径：可见比例低 ≠ 上一张画过它。
        // 真机 `[32317270]`：跨边界块可见 44px / 整块 95px = 46%（< 旧的 50% 下限），
        // 而它**没被整块移交** → 旧口径整块丢弃，本图这半行既不擦也不翻（用户截图那行韩文）。
        // 现在这种块一律本图认领并裁进可见区；「上一张确实画过」由 `handedOver` 判定。
        val plan = plan(listOf(detection("아니하지만 그게 방해됐단말이야", 300f, 100f, 700f, 260f)))
        assertEquals(1, plan.owned.size)
        assertEquals(1, plan.orphanAdopted)
        assertEquals("orphan_adopted", plan.verdicts.single())
        // 裁进可见区（顶边 = 上边界），否则百分比框跑到条带里、渲染层只能夹到页首
        assertEquals(topLimit, plan.owned.single().bubble.y / 100f * canvasHeight, 0.5f)
    }

    @Test
    fun mixedLineStraddlingTopSeamIsNotDiscardedAsHandedOver() {
        // 真机 `[32317270]` 的现场几何（拼接图 600x2544、上条带 144）复刻：
        //   本图检出 outer 93~188 / content 101~180，x 83~492（= 条带尾行 + 本图首行被并成一行）
        //   移交框（CrossBubblePart.contentRect）x 187~499, y 144~226
        // 旧口径：可见段 144~188 有 76% 落在移交框里 ≥ 0.6 → 整块 dropped_top_handed
        //        → 本图那半行既不擦也不翻（用户截图的韩文）。
        // 新口径：整块覆盖率只有 34%，且移交框横向只罩住 74%（< 90%）→ 移交框不可信 →
        //        本图认领可见段。
        val broken = SeamBox(83f, 93f, 492f, 188f)
        val bubble = Bubble(
            x = 0f, y = 0f, w = 10f, h = 10f, bgType = "solid",
            originalText = "방해됐단양이o를 하려면 죽거나", translatedText = ""
        )
        val plan = planSeamOwnership(
            detections = listOf(SeamDetection(bubble, broken, SeamBox(83f, 101f, 492f, 180f))),
            config = SeamConfig(
                topLimitPx = 144f,
                bottomLimitPx = 2544f,
                topBandPx = 144f,
                bottomBandPx = 0f,
                edgeWindowPx = 41f,
                maxCrossHeightPx = 213f
            ),
            incomingCrossBoxes = listOf(SeamBox(187f, 144f, 499f, 226f)),
            canvasWidth = 600,
            canvasHeight = 2544
        )
        assertEquals("跨边界混行不得整块丢弃（实际 ${plan.verdicts.single()}）", 1, plan.owned.size)
        assertEquals("orphan_adopted", plan.verdicts.single())
        assertEquals(1, plan.orphanAdopted)
        assertEquals(0, plan.droppedNeighbor)
    }

    @Test
    fun mostlyVisibleTopSeamBlockIsStillAdopted() {
        // 大半在本页（整块 100~400 = 300px，可见 200~400 = 200px = 67%）→ 认领并裁进可见区
        val plan = plan(listOf(detection("이 쪽이 대부분", 300f, 100f, 700f, 400f)))
        assertEquals(1, plan.owned.size)
        assertEquals("orphan_adopted", plan.verdicts.single())
        val owned = plan.owned.single()
        assertEquals(topLimit, owned.bubble.y / 100f * canvasHeight, 0.5f)
    }

    @Test
    fun sameBubbleHandedOverAcrossTopSeamIsStillDropped() {
        // 反向守卫：合法的续排（移交框横向罩得住本图检出、且可见段被整段画过）**必须仍然丢弃**，
        // 否则上一张已经画好的内容会被本图再翻一遍（「韩文 + 中文叠成一块」的旧病）。
        val plan = plan(
            detections = listOf(detection("잘려진 문장", 300f, 150f, 700f, 300f)),
            incoming = listOf(SeamBox(280f, 240f, 720f, 330f))
        )
        assertTrue(plan.owned.isEmpty())
        assertEquals(1, plan.droppedNeighbor)
        assertEquals("dropped_top_handed", plan.verdicts.single())
    }

    // -----------------------------------------------------------------------
    // 2026-10-16：字形不在可见区 → 不认领（真机 Lezhin 单字 chip 的 597×587 事故）
    // -----------------------------------------------------------------------

    @Test
    fun glyphEntirelyInsideTopBandIsNotAdoptedEvenIfRegionCrossesBoundary() {
        // 真机复刻（trace 31224250）：拼接图 600×2544、上条带 144。
        //   单字 `억!` 的**区域框** outer = 0~731（大块亮区，跨过上边界），
        //   而字形 content = 75~96（整块在上条带里 = 上一张图的正文）。
        // 旧实现：content 裁完退化 → 回退用 outer → 597×587 的「文字框」→ 遮罩/译文全错位
        //         （译文被画到框中心、盖板只剩贴着 chip 的一小块）。
        // 新口径：字形不在可见区 → 不认领（上一张图自己完整看到它，交给它的 owned 链路）。
        val bubble = Bubble(
            x = 0f, y = 0f, w = 10f, h = 10f, bgType = "solid",
            originalText = "억!", translatedText = ""
        )
        val plan = planSeamOwnership(
            detections = listOf(
                SeamDetection(bubble, SeamBox(0f, 0f, 597f, 731f), SeamBox(324f, 75f, 348f, 96f))
            ),
            config = SeamConfig(
                topLimitPx = 144f,
                bottomLimitPx = 2544f,
                topBandPx = 144f,
                bottomBandPx = 0f,
                edgeWindowPx = 32f,
                maxCrossHeightPx = 197f
            ),
            incomingCrossBoxes = emptyList(),
            canvasWidth = 600,
            canvasHeight = 2544
        )
        assertTrue("字形在条带里不该被认领（实际 ${plan.verdicts.single()}）", plan.owned.isEmpty())
        assertEquals(0, plan.orphanAdopted)
        assertEquals(1, plan.droppedNeighbor)
        assertEquals("dropped_glyph_outside", plan.verdicts.single())
    }

    @Test
    fun glyphPartlyVisibleAboveTopSeamIsStillAdoptedWithClippedContent() {
        // 与上一条互补：字形**跨**上边界（部分是上一张的、部分在本图）→ 仍然认领，
        // 且 content 跟着裁进可见区（不是退化成区域大框）。
        val bubble = Bubble(
            x = 0f, y = 0f, w = 10f, h = 10f, bgType = "solid",
            originalText = "이어지는 문장", translatedText = ""
        )
        val plan = planSeamOwnership(
            detections = listOf(
                SeamDetection(bubble, SeamBox(100f, 120f, 500f, 400f), SeamBox(120f, 150f, 480f, 260f))
            ),
            config = SeamConfig(
                topLimitPx = 200f,
                bottomLimitPx = 1200f,
                topBandPx = 200f,
                bottomBandPx = 0f,
                edgeWindowPx = 60f,
                maxCrossHeightPx = 400f
            ),
            incomingCrossBoxes = emptyList(),
            canvasWidth = 800,
            canvasHeight = 1400
        )
        assertEquals(1, plan.owned.size)
        assertEquals("orphan_adopted", plan.verdicts.single())
        val owned = plan.owned.single()
        // 内容框 = 裁到可见区后的字形框（200~260px），不是 outer（120~400px）
        assertEquals(200f, owned.bubble.contentY / 100f * canvasHeight, 0.5f)
        assertEquals(
            60f, owned.bubble.contentH / 100f * canvasHeight, 0.5f
        )
    }

    @Test
    fun glyphEntirelyBelowCutAllowanceIsNotClaimedAsCross() {
        // 跨下边界但字形整块深在条带里（超过切开余量）：下一张图会在自己的画面里
        // 看到整行 —— 这里既不认领也不移交，更不允许「退化成区域大框」。
        val bubble = Bubble(
            x = 0f, y = 0f, w = 10f, h = 10f, bgType = "solid",
            originalText = "다음 페이지 본문", translatedText = ""
        )
        val plan = planSeamOwnership(
            detections = listOf(
                SeamDetection(bubble, SeamBox(300f, 1100f, 700f, 1350f), SeamBox(320f, 1275f, 680f, 1340f))
            ),
            config = config().copy(cutAllowancePx = 60f),
            incomingCrossBoxes = emptyList(),
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight
        )
        assertTrue("字形在切开余量之外不该认领（实际 ${plan.verdicts.single()}）", plan.owned.isEmpty())
        assertEquals("dropped_glyph_outside", plan.verdicts.single())
    }

    @Test
    fun glyphStraddlingCutAllowanceIsStillClaimedAsCross() {
        // 互补：字形跨切开余量（后半截在余量内可见）→ 照常认领，content 裁到 cap。
        val bubble = Bubble(
            x = 0f, y = 0f, w = 10f, h = 10f, bgType = "solid",
            originalText = "잘린 문장", translatedText = ""
        )
        val plan = planSeamOwnership(
            detections = listOf(
                SeamDetection(bubble, SeamBox(300f, 1100f, 700f, 1350f), SeamBox(320f, 1240f, 680f, 1300f))
            ),
            config = config().copy(cutAllowancePx = 60f),
            incomingCrossBoxes = emptyList(),
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight
        )
        assertEquals(1, plan.owned.size)
        val owned = plan.owned.single()
        assertTrue(owned.cross)
        // content 底边裁到 cap = 1200 + 60 = 1260
        val contentBottomPx = (owned.bubble.contentY + owned.bubble.contentH) / 100f * canvasHeight
        assertEquals(1260f, contentBottomPx, 0.5f)
    }
}
