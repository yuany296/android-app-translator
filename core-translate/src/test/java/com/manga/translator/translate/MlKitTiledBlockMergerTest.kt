package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ML Kit 长条图分块检测的合并策略单测（2026-10-16）。
 *
 * 背景：§54-B 只把分块检测接到了 Paddle；ML Kit 整图缩到 1280 长边，
 * 单字 chip / 句尾符号持续漏检（用户在 ML Kit 模式下复测仍在漏）。
 */
class MlKitTiledBlockMergerTest {

    private fun candidate(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        text: String = "텍스트",
        tileIndex: Int = 0,
        lineCount: Int = 1
    ) = MlKitTiledBlockMerger.Candidate(
        text = text,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        lineCount = lineCount,
        lineBoxes = listOf(floatArrayOf(left, top, right, bottom)),
        tileIndex = tileIndex
    )

    // ------------------------------------------------------------------
    // boxIoU：相交面积 / 并集面积
    // ------------------------------------------------------------------

    @Test
    fun identicalBoxesHaveIouOne() {
        val a = candidate(0f, 0f, 100f, 40f)
        assertEquals(1f, MlKitTiledBlockMerger.boxIoU(a, candidate(0f, 0f, 100f, 40f)), 1e-4f)
    }

    @Test
    fun disjointBoxesHaveIouZero() {
        val a = candidate(0f, 0f, 100f, 40f)
        assertEquals(0f, MlKitTiledBlockMerger.boxIoU(a, candidate(200f, 0f, 300f, 40f)), 1e-4f)
    }

    @Test
    fun touchingEdgesHaveIouZero() {
        // 只共享一条边（面积零相交）不能算同一块
        val a = candidate(0f, 0f, 100f, 40f)
        assertEquals(0f, MlKitTiledBlockMerger.boxIoU(a, candidate(100f, 0f, 200f, 40f)), 1e-4f)
    }

    // ------------------------------------------------------------------
    // merge：重叠区重复检测去重、独立块保留
    // ------------------------------------------------------------------

    @Test
    fun duplicateAcrossOverlapIsDropped() {
        // 相邻块 12% 重叠区里的同一句话（几乎同框）→ 保留先到的
        val merged = MlKitTiledBlockMerger.merge(
            listOf(
                candidate(1000f, 500f, 1150f, 540f),
                candidate(1005f, 502f, 1148f, 541f, text = "텍스트(邻块重复)")
            )
        )
        assertEquals(1, merged.size)
        assertEquals("텍스트", merged[0].text)
    }

    @Test
    fun crossTileSameTextLowIouIsDeduped() {
        // ML Kit 的块级框随上下文变化：同一句在相邻块重叠区里 IoU 可能 <0.45，
        // 但文本完全相同且框高度重叠 → 仍是同一块，不能保留两份。
        val a = candidate(0f, 0f, 100f, 40f, text = "케이지라고요!!", tileIndex = 0)
        val b = candidate(30f, 10f, 130f, 50f, text = "케이지라고요!!", tileIndex = 1)
        assertTrue(MlKitTiledBlockMerger.boxIoU(a, b) < MlKitTiledBlockMerger.MERGE_IOU_THRESHOLD)
        assertTrue(MlKitTiledBlockMerger.isCrossTileTextDuplicate(a, b))
        assertEquals(1, MlKitTiledBlockMerger.merge(listOf(a, b)).size)
    }

    @Test
    fun crossTileContainedTextKeepsTheMoreCompleteBlock() {
        val short = candidate(100f, 100f, 260f, 140f, text = "케이지", tileIndex = 0)
        val full = candidate(105f, 102f, 300f, 142f, text = "케이지라고요", tileIndex = 1, lineCount = 2)
        val merged = MlKitTiledBlockMerger.merge(listOf(short, full))
        assertEquals(1, merged.size)
        assertEquals("케이지라고요", merged[0].text)
        assertEquals(2, merged[0].lineCount)
    }

    @Test
    fun crossTileFuzzyOcrVariantIsDedupedKeepingStructuredBlock() {
        // 2026-11-01 真机现场（pageHash ca9603cd…）：同一句在两块重叠区被识别成两版，
        // 一版正确两行、一版错字单行；旧逻辑只认相同/包含，两版都留下 → 译文重复两遍。
        val correct = candidate(
            100f, 100f, 300f, 220f,
            text = "지금 물어야겠어?\n조용히 따라오기나 해.",
            tileIndex = 0,
            lineCount = 2
        )
        val garbled = candidate(
            100f, 100f, 300f, 150f,
            text = "지금 물어아겼어? 조용히 따리오기나 해.",
            tileIndex = 1,
            lineCount = 1
        )
        assertTrue(MlKitTiledBlockMerger.boxIoU(correct, garbled) < MlKitTiledBlockMerger.MERGE_IOU_THRESHOLD)
        assertTrue(MlKitTiledBlockMerger.isCrossTileTextDuplicate(correct, garbled))
        val merged = MlKitTiledBlockMerger.merge(listOf(correct, garbled))
        assertEquals(1, merged.size)
        assertEquals("지금 물어야겠어?\n조용히 따라오기나 해.", merged[0].text)
        assertEquals(2, merged[0].lineCount)
    }

    @Test
    fun sameTileFuzzyVariantIsDeduped() {
        // 短图不触发分块时所有候选 tileIndex 都是 0；近似变体仍必须去重。
        val correct = candidate(
            100f, 100f, 300f, 220f,
            text = "지금 물어야겠어?\n조용히 따라오기나 해.",
            tileIndex = 0,
            lineCount = 2
        )
        val garbled = candidate(
            100f, 100f, 300f, 150f,
            text = "지금 물어아겼어? 조용히 따리오기나 해.",
            tileIndex = 0,
            lineCount = 1
        )
        assertTrue(MlKitTiledBlockMerger.isFuzzyTextDuplicate(correct, garbled))
        val merged = MlKitTiledBlockMerger.merge(listOf(correct, garbled))
        assertEquals(1, merged.size)
        assertEquals(2, merged[0].lineCount)
        assertTrue(merged[0].text.contains("\n"))
    }

    @Test
    fun sameBoxFuzzyVariantKeepsBetterRegardlessOfOrder() {
        // 两版框几乎重合（IoU≥0.45）时旧逻辑保留先到的；若先到的是错字版就会留下错字。
        // 近似变体应按“行数/结构”选更可信的一份，和顺序无关。
        val garbled = candidate(
            100f, 100f, 300f, 150f,
            text = "지금 물어아겼어? 조용히 따리오기나 해.",
            tileIndex = 0,
            lineCount = 1
        )
        val correct = candidate(
            100f, 100f, 300f, 150f,
            text = "지금 물어야겠어?\n조용히 따라오기나 해.",
            tileIndex = 0,
            lineCount = 2
        )
        val merged = MlKitTiledBlockMerger.merge(listOf(garbled, correct))
        assertEquals(1, merged.size)
        assertEquals(2, merged[0].lineCount)
        assertTrue(merged[0].text.contains("\n"))
    }

    @Test
    fun crossTileDifferentTextHighOverlapIsKept() {
        // 文本相似度不足时，即使框重叠也不能去重（两个不同气泡紧挨着）。
        val a = candidate(0f, 0f, 200f, 60f, text = "오늘은 날씨가 좋네요", tileIndex = 0, lineCount = 1)
        val b = candidate(0f, 0f, 200f, 200f, text = "내일은 비가 온대요", tileIndex = 1, lineCount = 1)
        assertFalse(MlKitTiledBlockMerger.isCrossTileTextDuplicate(a, b))
        assertEquals(2, MlKitTiledBlockMerger.merge(listOf(a, b)).size)
    }

    @Test
    fun sameTileSameTextLowIouStillKept() {
        // 同一块里两个同文本气泡（或同一块的相邻检测）不能被跨块文本去重误吞。
        val a = candidate(0f, 0f, 100f, 40f, text = "텍스트", tileIndex = 0)
        val b = candidate(30f, 10f, 130f, 50f, text = "텍스트", tileIndex = 0)
        assertFalse(MlKitTiledBlockMerger.isCrossTileTextDuplicate(a, b))
        assertEquals(2, MlKitTiledBlockMerger.merge(listOf(a, b)).size)
    }

    @Test
    fun crossTileSameTextButLittleGeometryOverlapIsKept() {
        val a = candidate(0f, 0f, 100f, 40f, text = "텍스트", tileIndex = 0)
        val b = candidate(120f, 100f, 220f, 140f, text = "텍스트", tileIndex = 1)
        assertFalse(MlKitTiledBlockMerger.isCrossTileTextDuplicate(a, b))
        assertEquals(2, MlKitTiledBlockMerger.merge(listOf(a, b)).size)
    }

    @Test
    fun distinctNearbyBlocksAreBothKept() {
        // 同一气泡里上下两行块：位置不同（IoU 低）不能误吞
        val merged = MlKitTiledBlockMerger.merge(
            listOf(
                candidate(100f, 100f, 300f, 140f),
                candidate(100f, 150f, 300f, 190f)
            )
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun partiallyOverlappingDistinctBlocksSurvive() {
        // 阈值以下的重叠（相邻气泡紧挨着）必须都保留 —— 这正是「合并过宽」事故的反面
        val a = candidate(0f, 0f, 100f, 40f)
        val b = candidate(60f, 0f, 160f, 40f)
        val iou = MlKitTiledBlockMerger.boxIoU(a, b)
        assertTrue("用例本身要落在阈值下", iou < MlKitTiledBlockMerger.MERGE_IOU_THRESHOLD)
        assertEquals(2, MlKitTiledBlockMerger.merge(listOf(a, b)).size)
    }

    @Test
    fun emptyInputMergesToEmpty() {
        assertTrue(MlKitTiledBlockMerger.merge(emptyList()).isEmpty())
    }

    // ------------------------------------------------------------------
    // 闸门联动：ML Kit 的检测长边是 1280，闸门按它判定
    // ------------------------------------------------------------------

    @Test
    fun lezhinStripTilesWithMlKitEdge() {
        // 600×2544（页内拼接图）：长边 2544 > 1280×1.6 → 分块
        assertTrue(PaddleDetectionTiling.shouldTile(600, 2544, 1280f))
    }

    @Test
    fun kakaoCutStaysSinglePassWithMlKitEdge() {
        assertFalse(PaddleDetectionTiling.shouldTile(760, 1000, 1280f))
    }
}
