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
        text: String = "텍스트"
    ) = MlKitTiledBlockMerger.Candidate(
        text = text,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        lineCount = 1,
        lineBoxes = listOf(floatArrayOf(left, top, right, bottom))
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
