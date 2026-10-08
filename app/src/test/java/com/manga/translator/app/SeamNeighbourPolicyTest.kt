package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨切片相邻性单测（2026-10-03）。
 *
 * 背景：会话循环里 `prevBottomStrip`（上一张图底部条带）与 `incomingCross`（上一张移交的气泡）
 * 跨 DOM 快照存活，而懒加载阅读器每轮只扫到 1~3 张图 —— 「上一次处理的图」并不等于
 * 「本图正上方那张」。不校验就会出现「A 图的条带贴到 B 图上」：条带里的别处文字与本图顶部的
 * 字被并成一行，随后那块被当成「上一张已画过」整块丢弃（本图那半行既不擦也不翻），
 * 同时上一张的译文白框糊在本图另一段上（真机 `[32317270]`）。
 */
class SeamNeighbourPolicyTest {

    private fun box(
        src: String,
        top: Int,
        height: Int,
        left: Int = 0,
        width: Int = 720,
        hostId: String = "",
        frame: Int = 0
    ) = StripBox(src = src, left = left, top = top, width = width, height = height, hostId = hostId, frame = frame)

    // ---------------------------------------------------------------
    // predecessor：按几何找「正上方那一张」，不看扫描顺序
    // ---------------------------------------------------------------

    @Test
    fun predecessorFindsTheSliceRightAboveRegardlessOfScanOrder() {
        val cur = box("c", top = 2880, height = 2880)
        // 扫描顺序故意打乱（脚本先收显式选择器命中、再收通用命中，顺序不是文档序）
        val all = listOf(cur, box("d", top = 5760, height = 2880), box("b", top = 0, height = 2880))
        assertEquals("b", SeamNeighbourPolicy.predecessor(cur, all)?.src)
    }

    @Test
    fun predecessorIgnoresOtherHostsAndFrames() {
        val cur = box("c", top = 2880, height = 2880, hostId = "h1", frame = 0)
        val otherHost = box("b", top = 0, height = 2880, hostId = "h2")
        val otherFrame = box("b2", top = 0, height = 2880, frame = 3)
        assertNull(SeamNeighbourPolicy.predecessor(cur, listOf(otherHost, otherFrame)))
    }

    @Test
    fun predecessorPicksTheNearestAboveWhenThereIsAGap() {
        val cur = box("c", top = 6000, height = 2880)
        val far = box("a", top = 0, height = 2880)
        val near = box("b", top = 3000, height = 2880)
        val prev = SeamNeighbourPolicy.predecessor(cur, listOf(far, near))
        assertEquals("b", prev?.src)
        // 间隙 120px（6000 - 5880）→ 判决必须拒绝（不是「紧邻」）
        val decision = SeamNeighbourPolicy.decide(prev, cur)
        assertFalse(decision.contiguous)
        assertEquals("gap", decision.reason)
    }

    // ---------------------------------------------------------------
    // decide：准入判据
    // ---------------------------------------------------------------

    @Test
    fun adjacentSlicesAreContiguous() {
        val prev = box("b", top = 0, height = 2880)
        val cur = box("c", top = 2880, height = 2880)
        val decision = SeamNeighbourPolicy.decide(prev, cur)
        assertTrue(decision.contiguous)
        assertEquals("adjacent", decision.reason)
    }

    @Test
    fun smallRoundingGapIsStillContiguous() {
        // 切片间几像素抖动（取整/重采样）不算断裂
        val prev = box("b", top = 0, height = 2880)
        val cur = box("c", top = 2882, height = 2880)
        assertTrue(SeamNeighbourPolicy.decide(prev, cur).contiguous)
    }

    @Test
    fun pageGapIsRejected() {
        val prev = box("b", top = 0, height = 2880)
        val cur = box("c", top = 2940, height = 2880)
        val decision = SeamNeighbourPolicy.decide(prev, cur)
        assertFalse("翻页/栏间空隙不得续排（gap=${cur.top - prev.bottom}）", decision.contiguous)
        assertEquals("gap", decision.reason)
    }

    @Test
    fun sliceBelowIsNotAPredecessor() {
        // 本图上方什么都没有（章节第一片），只有下方那一张 → 不能拿它当上一张
        val cur = box("c", top = 0, height = 2880)
        val below = box("d", top = 2880, height = 2880)
        assertNull(SeamNeighbourPolicy.predecessor(cur, listOf(below)))
        assertEquals("no_prev", SeamNeighbourPolicy.decide(null, cur).reason)
    }

    @Test
    fun overlappingSlicesAreRejected() {
        // 重叠（>4px）说明两图内容重复：条带会把同一行读两遍并拼歪
        val prev = box("b", top = 0, height = 2880)
        val cur = box("c", top = 2700, height = 2880)
        val decision = SeamNeighbourPolicy.decide(prev, cur)
        assertFalse(decision.contiguous)
        assertEquals("overlap", decision.reason)
    }

    @Test
    fun horizontalOffsetIsRejected() {
        val prev = box("b", top = 0, height = 2880, left = 0)
        val cur = box("c", top = 2880, height = 2880, left = 120)
        val decision = SeamNeighbourPolicy.decide(prev, cur)
        assertFalse(decision.contiguous)
        assertEquals("x_offset", decision.reason)
    }

    @Test
    fun widthMismatchIsRejected() {
        // 两张图缩放口径不同（例如一张是缩略图）：条带贴上去比例就对不上
        val prev = box("b", top = 0, height = 2880, width = 320)
        val cur = box("c", top = 2880, height = 2880, width = 720)
        val decision = SeamNeighbourPolicy.decide(prev, cur)
        assertFalse(decision.contiguous)
        assertEquals("width_mismatch", decision.reason)
    }

    @Test
    fun degenerateSizeIsRejected() {
        val prev = box("b", top = 0, height = 0)
        val cur = box("c", top = 0, height = 2880)
        assertEquals("invalid_size", SeamNeighbourPolicy.decide(prev, cur).reason)
    }

    // ---------------------------------------------------------------
    // successor：「下一张」也必须校验（2026-10-03 `page_gap` 事故）
    // ---------------------------------------------------------------

    @Test
    fun successorFindsTheSliceRightBelow() {
        val cur = box("b", top = 0, height = 2880)
        val all = listOf(cur, box("d", top = 8640, height = 2880), box("c", top = 2880, height = 2880))
        assertEquals("c", SeamNeighbourPolicy.successor(cur, all)?.src)
    }

    @Test
    fun farBannerIsFoundButRejectedByDecide() {
        // 真机 `[5467219]`：扫描列表里的「下一张」是推广 banner，相距 46000px。
        // successor 会挑到它（几何上确实在下方最近），但 decide(本图, 候选) 必须判 gap → 不可用：
        // 否则下条带/接缝带会对着 banner，渲染层以 `page_gap` 拒掉接缝带（退回两半各画一份）。
        val cur = box("b", top = 0, height = 2880)
        val banner = box("promo", top = 48880, height = 400)
        val successor = SeamNeighbourPolicy.successor(cur, listOf(cur, banner))
        assertEquals("promo", successor?.src)
        val decision = SeamNeighbourPolicy.decide(cur, successor!!)
        assertFalse(decision.contiguous)
        assertEquals("gap", decision.reason)
    }

    @Test
    fun noSuccessorWhenNothingBelow() {
        val cur = box("c", top = 2880, height = 2880)
        val above = box("b", top = 0, height = 2880)
        assertNull(SeamNeighbourPolicy.successor(cur, listOf(cur, above)))
    }

    @Test
    fun successorIgnoresOtherHosts() {
        val cur = box("b", top = 0, height = 2880, hostId = "h1")
        val otherHost = box("c", top = 2880, height = 2880, hostId = "h2")
        assertNull(SeamNeighbourPolicy.successor(cur, listOf(cur, otherHost)))
    }
}
