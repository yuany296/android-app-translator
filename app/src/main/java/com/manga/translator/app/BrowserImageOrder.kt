package com.manga.translator.app

/**
 * 长条漫翻译的「阅读位置优先」调度（2026-10-15，用户报 Kakao「我在中间看，它先从头开始翻」）。
 *
 * 扫描列表是文档序，会话原先从第 1 张翻起 —— 用户停在第 8 页时，眼前那几页等最久，
 * 观感就是「从开头翻」。参考项目 `scheduler.js#selectPendingAheadCandidates` 的口径：
 * 找文档序中第一张「底边越过阅读锚点」（视口高度 35%）的图，从它开始按文档序往下翻；
 * 视口上方已读过的部分轮转到列表末尾。
 *
 * 只**旋转**顺序、不重排：接缝/条带链路（incoming 交接、prevBottomStrip 复用）依赖
 * 文档序内的相邻关系，旋转保证每个连续段内部仍是文档序。唯一的顺序断点在
 * 「视口上方末尾 ↔ 锚点首张」——那正是用户已经读过、最不在意的边界。
 */
internal object BrowserImageOrder {

    /** 元素在顶层视口里的纵向占位（CSS px）。 */
    data class ViewportSpan(val top: Int, val bottom: Int)

    /**
     * @param spans 每张图的视口占位（扫描顺序）。
     * @param anchorY 阅读锚点（视口高度 × 35%）。
     * @return 旋转后的处理下标顺序。锚点前没有图（全在锚点上方）或锚点就是首张时保持原序。
     */
    fun rotateToReadingAnchor(spans: List<ViewportSpan>, anchorY: Int): List<Int> {
        if (spans.isEmpty()) return emptyList()
        val start = spans.indexOfFirst { it.bottom >= anchorY }
        if (start <= 0) return spans.indices.toList()
        return (start until spans.size).toList() + (0 until start).toList()
    }
}
