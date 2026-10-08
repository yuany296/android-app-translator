package com.manga.translator.app

/**
 * 扫描到的一张图在**宿主文档坐标**里的几何（[ScannedImage] 的几何子集）。
 *
 * 单独建一个类型是为了让「相邻性判定」能纯 JVM 单测 —— 它现在是跨切片链路的
 * 唯一准入闸，必须有回归测试钉住（见 [SeamNeighbourPolicy]）。
 */
data class StripBox(
    val src: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val hostId: String = "",
    val frame: Int = 0
) {
    val bottom: Int get() = top + height
    val right: Int get() = left + width
}

/** 相邻性判决。[reason] 是稳定字符串，直接进日志。 */
data class NeighbourDecision(
    val contiguous: Boolean,
    val reason: String
)

/**
 * 「上一张扫描图是不是本图**正上方紧邻**的那一片」—— 跨切片续排的唯一准入闸。
 *
 * ## 为什么需要它（2026-10-03 真机事故）
 *
 * 会话循环里 `prevBottomStrip`（上一张图的底部条带）与 `incomingCross`（上一张移交的气泡）
 * 是**跨 DOM 快照存活**的：懒加载阅读器里 `scan` 每轮只扫到 1~3 张图，而它们来自
 * 「上一次处理的图」而不是「本图上方那张图」。用户一滚动，配对的条带就可能属于很远的另一片：
 *
 * - 条带内容是别处的字 → OCR 把它和本图顶部的字并成一行（真机：
 *   `방해됐단양이o를 하려면 죽거나`）；
 * - 那块跨边界文本被 `SeamBubbleMerger` 当成「上一张已画过」整块丢弃 → 本图这半行
 *   **既不擦也不翻**；
 * - 同时上一张移交的气泡按整句重排到本图顶部 → 白框 + 上一页的中文糊在本图另一段上。
 *
 * 所以：**只有几何上紧邻（同一宿主/帧、正上方、间隙≤[MAX_GAP_PX]、左右基本对齐、
 * 宽度相当）的上一张图，才允许把它的条带/移交带进本图**；否则本图必须当独立图处理。
 *
 * 判定**不看扫描顺序**（`BrowserScanScript` 先收显式选择器命中、再收通用命中，
 * 顺序不保证是文档序）：用 [predecessor] 按几何找「正上方那一张」。
 *
 * 纯几何、无 Android 依赖，可 JVM 单测。
 */
object SeamNeighbourPolicy {

    /** 上下边「贴住」的容差（像素）：切片天然相邻，留几像素抖动。 */
    const val MAX_GAP_PX = 4

    /** 允许的左右错位（像素）：阅读器同一栏里的切片左右边界一致。 */
    const val MAX_LEFT_OFFSET_PX = 32

    /** 宽度比下限：宽度差太多说明两张图不在同一栏/同一缩放口径。 */
    const val MIN_WIDTH_RATIO = 0.9f

    /**
     * 在扫到的所有图里按几何找「本图**正上方**且最近」的那一张（不依赖扫描顺序）。
     *
     * 返回 null 表示页面上没有本图上方的那一片（章节第一片 / 懒加载还没出现）。
     * 这里**只做候选挑选，不做合格判定** —— 间隙是否可接受由 [decide] 判，
     * 这样日志里能区分「压根没有上一张」和「有但离得太远」。
     */
    fun predecessor(cur: StripBox, all: List<StripBox>): StripBox? {
        return all.asSequence()
            .filter { it.src != cur.src }
            .filter { it.hostId == cur.hostId && it.frame == cur.frame }
            .filter { it.bottom <= cur.top + MAX_GAP_PX }
            .minByOrNull { cur.top - it.bottom }
    }

    /**
     * 在扫到的所有图里按几何找「本图**正下方**且最近」的那一张（不依赖扫描顺序）。
     *
     * ## 为什么下一张也必须校验（2026-10-03 真机）
     *
     * 「下一张」被用来做三件事：裁**下条带**、建**接缝带**（`buildSeamScene` / `seam_dispatch`）、
     * 把跨下边界的气泡**移交**给它。扫描列表里常常混着离得很远的图（推广 banner、页脚图）：
     * 真机 `[5467219]` 里 `cur=<promotionBanner.webp>` 与上一片相距 **46000px**，结果
     * `seam_dispatch … ok=false reason=page_gap`（渲染层要求两张图屏幕间隙 ≤3px）→ 接缝带整块被拒、
     * 退回「两半各画一份」，用户看到的就是糊字/两层。
     *
     * 判定复用 [decide]（问「cur 是不是紧邻在 candidate 上方」）。
     */
    fun successor(cur: StripBox, all: List<StripBox>): StripBox? {
        return all.asSequence()
            .filter { it.src != cur.src }
            .filter { it.hostId == cur.hostId && it.frame == cur.frame }
            .filter { it.top >= cur.bottom - MAX_GAP_PX }
            .minByOrNull { it.top - cur.bottom }
    }

    /**
     * 上一张图能不能当本图的续排来源。
     *
     * 拒绝原因（稳定字符串，用于日志过滤）：
     * `no_prev` / `invalid_size` / `other_host` / `not_above` / `gap` / `overlap` / `x_offset` / `width_mismatch`。
     */
    fun decide(prev: StripBox?, cur: StripBox): NeighbourDecision {
        if (prev == null) return NeighbourDecision(false, "no_prev")
        if (prev.height <= 0 || cur.height <= 0 || prev.width <= 0 || cur.width <= 0) {
            return NeighbourDecision(false, "invalid_size")
        }
        if (prev.hostId != cur.hostId || prev.frame != cur.frame) {
            return NeighbourDecision(false, "other_host")
        }
        // 上一张必须在本图上方：它的下边不允许越过本图上边太多（越过=重叠，不是"上一片"）。
        if (prev.bottom > cur.top + MAX_GAP_PX) {
            // 允许极小重叠（扫描取整），超过一行的重叠按 overlap 拒绝：条带里的像素
            // 会与本图顶部重复，OCR 会把同一行读两遍并拼歪。
            val overlap = prev.bottom - cur.top
            return NeighbourDecision(false, if (overlap > MAX_GAP_PX) "overlap" else "not_above")
        }
        val gap = cur.top - prev.bottom
        if (gap > MAX_GAP_PX) return NeighbourDecision(false, "gap")
        if (kotlin.math.abs(prev.left - cur.left) > MAX_LEFT_OFFSET_PX) {
            return NeighbourDecision(false, "x_offset")
        }
        val ratio = minOf(prev.width, cur.width).toFloat() / maxOf(prev.width, cur.width).toFloat()
        if (ratio < MIN_WIDTH_RATIO) return NeighbourDecision(false, "width_mismatch")
        return NeighbourDecision(true, "adjacent")
    }
}
