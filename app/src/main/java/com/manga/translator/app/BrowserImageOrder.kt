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

    /**
     * 批处理中是否该因滚动**提前结束旧快照**并重新扫描（2026-10-23）。
     *
     * 旧实现只在整批 `images` 处理完后才重扫，而懒加载站点的新图是用户翻页时才挂出来的：
     * 用户在读第 N 张时往下翻，第 N+1 张已经加载，但它不在旧快照里，只能等前面所有待翻图
     * 处理完才进队列 —— 观感就是「眼前这页没提前翻，先停很久」。
     *
     * 判据只看「扫描时与当前滚动位置的距离」：上下翻都算（往回翻同样要重新排）；
     * 旧脚本没给滚动位置（[scannedScrollY] < 0）时一律不重排，保持旧行为。
     *
     * @param scannedScrollY 本轮扫描时的 `window.scrollY`，<0 表示未知。
     * @param currentScrollY 当前 `window.scrollY`，<0 表示读取失败。
     * @param thresholdPx 触发重扫的最小位移；冲突容差防止地址栏伸缩/回弹造成抖动。
     */
    fun shouldReanchor(scannedScrollY: Int, currentScrollY: Int, thresholdPx: Int): Boolean {
        if (scannedScrollY < 0 || currentScrollY < 0) return false
        return kotlin.math.abs(currentScrollY - scannedScrollY) >= thresholdPx.coerceAtLeast(1)
    }
}
