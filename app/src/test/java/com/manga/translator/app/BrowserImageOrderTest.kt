package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「阅读位置优先」调度（2026-10-15，用户报 Kakao「我在中间看，它先从头开始翻」）。
 *
 * 12 张图、每张 900px、视口 800px、锚点 = 280（800 × 0.35）是基准场景：
 * 用户停在第 8 张（下标 7）时，处理顺序必须从它开始往下，已读过的 0..6 轮转到末尾。
 */
class BrowserImageOrderTest {

    private fun spans(count: Int, pageHeight: Int = 900, scrollY: Int): List<BrowserImageOrder.ViewportSpan> =
        (0 until count).map { i ->
            val top = i * pageHeight - scrollY
            BrowserImageOrder.ViewportSpan(top = top, bottom = top + pageHeight)
        }

    @Test
    fun atTop_startsFromFirstImage() {
        // 滚动位置 0：第 1 张底边（900）越过锚点（280）→ 原序。
        val order = BrowserImageOrder.rotateToReadingAnchor(
            spans = spans(count = 12, scrollY = 0),
            anchorY = 280
        )
        assertEquals((0 until 12).toList(), order)
    }

    @Test
    fun midChapter_startsFromVisibleImageThenWrapsReadPartToTheEnd() {
        // 用户停在第 8 张（scrollY = 7×900 + 400 = 6700，视口 800）：下标 7 的占位是
        // [-100, 800]，锚点线（280）落在它身上 → 从它开始：7..11，然后已读过的 0..6。
        val order = BrowserImageOrder.rotateToReadingAnchor(
            spans = spans(count = 12, scrollY = 6700),
            anchorY = 280
        )
        assertEquals((7 until 12).toList() + (0 until 7).toList(), order)
    }

    @Test
    fun partiallyVisibleCutCountsAsStart() {
        // 半张在屏上也算「正在读」：scrollY = 6300 时下标 7 占位 [0, 900]（正好铺满视口），
        // 底边 900 ≥ 280 → 起点 7；它的上一张占位 [-900, 0]，被轮转到末尾。
        val order = BrowserImageOrder.rotateToReadingAnchor(
            spans = spans(count = 12, scrollY = 6300),
            anchorY = 280
        )
        assertEquals((7 until 12).toList() + (0 until 7).toList(), order)
    }

    @Test
    fun scrolledPastEverything_keepsDocumentOrder() {
        // 全部底边都在锚点上方（视口下方大片空白）：找不到起点 → 保持文档序（不做激进重排）。
        val order = BrowserImageOrder.rotateToReadingAnchor(
            spans = spans(count = 4, scrollY = 100_000),
            anchorY = 280
        )
        assertEquals((0 until 4).toList(), order)
    }

    @Test
    fun emptyList_isSafe() {
        assertEquals(emptyList<Int>(), BrowserImageOrder.rotateToReadingAnchor(emptyList(), anchorY = 280))
    }

    @Test
    fun singleImage_identity() {
        val order = BrowserImageOrder.rotateToReadingAnchor(
            spans = listOf(BrowserImageOrder.ViewportSpan(top = -100, bottom = 800)),
            anchorY = 280
        )
        assertEquals(listOf(0), order)
    }
}
