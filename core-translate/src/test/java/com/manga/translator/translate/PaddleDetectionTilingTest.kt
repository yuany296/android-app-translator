package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长条图分块检测的纯策略单测（2026-10-16）。
 *
 * 现场背景：Lezhin 1280×5120 切图（页面取像素后 600×2400）整图检测只剩 ~19% 分辨率，
 * 单字 chip（`흠/윽/엎`）在日志里从未被检出；Kakao 760×1000 切图此前一直正常，不能被波及。
 */
class PaddleDetectionTilingTest {

    // ------------------------------------------------------------------
    // shouldTile：只有「长条 + 长边明显超出检测尺寸」才分块
    // ------------------------------------------------------------------

    @Test
    fun lezhinStripNeedsTiling() {
        // 600×2400：长边 2400 > 960×1.6，长宽比 4 → 分块
        assertTrue(PaddleDetectionTiling.shouldTile(600, 2400, 960f))
    }

    @Test
    fun kakaoCutDoesNotTile() {
        // 760×1000（Kakao 常规切图）：长宽比 1.32 < 2 → 保持单趟
        assertFalse(PaddleDetectionTiling.shouldTile(760, 1000, 960f))
    }

    @Test
    fun squareImagesDoNotTile() {
        assertFalse(PaddleDetectionTiling.shouldTile(1600, 1600, 960f))
        assertFalse(PaddleDetectionTiling.shouldTile(2400, 2000, 960f))
    }

    @Test
    fun mildStripsBelowLongRatioDoNotTile() {
        // 500×1400：长宽比 2.8 够长，但 1400 < 1536（960×1.6）→ 分块没有增益
        assertFalse(PaddleDetectionTiling.shouldTile(500, 1400, 960f))
    }

    @Test
    fun fastModeThresholdFollowsDetResizeLong() {
        // 快速模式 detResizeLong=768 → 阈值 1228.8：1200 长的条不分、1600 分
        assertFalse(PaddleDetectionTiling.shouldTile(500, 1200, 768f))
        assertTrue(PaddleDetectionTiling.shouldTile(500, 1600, 768f))
    }

    @Test
    fun invalidInputsDoNotTile() {
        assertFalse(PaddleDetectionTiling.shouldTile(0, 2400, 960f))
        assertFalse(PaddleDetectionTiling.shouldTile(600, 2400, 0f))
    }

    // ------------------------------------------------------------------
    // tiles：覆盖完整、相邻重叠、块数有上限、块长均匀
    // ------------------------------------------------------------------

    @Test
    fun shorterThanOneTileReturnsSingleRange() {
        assertEquals(listOf(PaddleDetectionTiling.Tile(0, 500)), PaddleDetectionTiling.tiles(500, 960f))
    }

    @Test
    fun lezhinStripSplitsIntoThreeEvenTiles() {
        val tiles = PaddleDetectionTiling.tiles(2400, 960f)
        assertEquals(3, tiles.size)
        assertEquals(0, tiles.first().start)
        assertEquals(2400, tiles.last().endExclusive)
        // 相邻重叠 = 前块末 - 后块首，必须为正（跨块边界的文字不会被切断丢失）
        for (i in 0 until tiles.size - 1) {
            val overlap = tiles[i].endExclusive - tiles[i + 1].start
            assertTrue("tile$i/$i+1 overlap=$overlap must be > 0", overlap > 0)
        }
        // 块长均匀：每块都在 [700, 1000] 之内（不是「两块大 + 一条细尾巴」）
        tiles.forEach { tile ->
            assertTrue("tile len=${tile.length}", tile.length in 700..1000)
        }
    }

    @Test
    fun tileCountIsCappedButCoverageStaysComplete() {
        val tiles = PaddleDetectionTiling.tiles(20000, 960f, maxTiles = 3)
        assertEquals(3, tiles.size)
        assertEquals(0, tiles.first().start)
        assertEquals(20000, tiles.last().endExclusive)
        // 上限之下不再细分，但每块仍然覆盖到下一块（无空洞）
        for (i in 0 until tiles.size - 1) {
            assertTrue(tiles[i + 1].start < tiles[i].endExclusive)
        }
        assertTrue(tiles.all { it.length > 0 })
    }

    @Test
    fun consecutiveTilesAlwaysOverlapForTypicalLengths() {
        val lengths = listOf(1536, 1600, 1920, 2000, 2400, 2544, 3000, 3600, 5120)
        for (length in lengths) {
            val tiles = PaddleDetectionTiling.tiles(length, 960f)
            assertTrue("length=$length must tile", tiles.size >= 2)
            assertEquals("length=$length first", 0, tiles.first().start)
            assertEquals("length=$length last", length, tiles.last().endExclusive)
            for (i in 0 until tiles.size - 1) {
                assertTrue(
                    "length=$length tile$i=${tiles[i]} next=${tiles[i + 1]} 必须重叠",
                    tiles[i + 1].start < tiles[i].endExclusive
                )
                assertTrue(
                    "length=$length 相邻块起点必须递增",
                    tiles[i + 1].start > tiles[i].start
                )
            }
        }
    }

    @Test
    fun invalidInputsReturnEmpty() {
        assertTrue(PaddleDetectionTiling.tiles(0, 960f).isEmpty())
        assertTrue(PaddleDetectionTiling.tiles(-5, 960f).isEmpty())
        assertTrue(PaddleDetectionTiling.tiles(2400, 0f).isEmpty())
        assertTrue(PaddleDetectionTiling.tiles(2400, 960f, maxTiles = 0).isEmpty())
    }
}
