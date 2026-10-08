package com.manga.translator.app

/**
 * 接缝**收到侧**的框几何（纯逻辑，可 JVM 单测）。
 *
 * ## 为什么单独抽出来（2026-10-03 真机第二次报障）
 *
 * 收到侧把上一张移交过来的半句铺到本图时，框来自**上一张图**的字形轮廓（`crossPrepareBubbles`
 * + `sourcePolygonBoundsPx`）。但本图的字形未必与上一张完全对齐，而且本图自己的 OCR 会把
 * 同一行读成**碎片/乱码**（真机 `[5467219]`：`1드하데 거리기라`，框高 <1% = 退化框）。
 * 结果：
 *
 * - 移交框比实际字形小 → 边缘**露字**（真机 `밖에 …!!`）；
 * - 本图那条碎片既不擦也不盖 → 乱码留着（截图里那行「1드하데 거리기라」）。
 *
 * 所以收到侧必须做两件事，都在这里实现（便于用真机数字钉住）：
 * 1. [expandForAdjacentFragments]：把与本块**同一行**（横向重叠、纵向一行以内）的本图碎片
 *    并进同一个框 —— 它们是同一行的两半，分开擦必然露字；
 * 2. [enforceMinimumHeight]：框至少要有**一行**高，退化框（`box=42x0`）不许原样进擦除。
 */
internal object SeamIncomingGeometry {

    /**
     * 纵向「同一行」容差：相距不超过行高的这个比例，视为被切开的同一行。
     *
     * ⚠️ 与参考项目的差别（刻意）：参考项目 `seam-residuals.js` 的
     * `isSeamAdjacentBubbleLine` 用 **`minHeight × 0.55`**（`minHeight` = 两块里更矮的那个
     * **框高**）—— 它的输入是**逐行**检测（行框高就是行高），而我们的输入是 ML Kit 的
     * **段落级**框，行高必须自己算（`SeamTuning.medianLineHeight`）。这里取
     * `lineHeight × 0.3`（≈ 行距的一半以内）：既覆盖「同一行的另一半」（真机现场相距 1px、
     * 旧算法量出来 14px），又不会把**相邻的另一行**吃进来 —— 参考项目对相邻行还有
     * 「把它的译文并进 winner」这一步（`seam-surface-utils.js`），我们暂时没有，
     * 所以窗口必须更紧（见类注释「未做」）。
     */
    const val ADJACENT_LINE_GAP_RATIO = 0.3f

    /** 横向重叠下限（占较窄块的比例）。与参考项目 `isSeamAdjacentBubbleLine` 的 `0.5` 同值。 */
    const val ADJACENT_H_OVERLAP_RATIO = 0.5f

    /**
     * 碎片面积上限（相对移交框）：比它还大的块更可能是**另一段内容**，不该被吸收。
     *
     * 参考项目第三条吸收路径用的就是 `residualArea ≤ winnerArea × 1.35`。
     */
    const val MAX_FRAGMENT_AREA_RATIO = 1.35f

    /** 框的最小高度（行数）。 */
    const val MIN_COVER_LINES = 1f

    /** 退化框的下限（像素）：比它还薄就干脆别擦别盖（`crossPrepareBubbles` 里的 4px 同源）。 */
    const val MIN_BOX_PX = 4f

    /** 轴对齐矩形（**本图坐标**，像素）。 */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = (right - left).coerceAtLeast(0f)
        val height: Float get() = (bottom - top).coerceAtLeast(0f)

        fun union(other: Box): Box = Box(
            left = minOf(left, other.left),
            top = minOf(top, other.top),
            right = maxOf(right, other.right),
            bottom = maxOf(bottom, other.bottom)
        )
    }

    /**
     * 这两块是不是「**同一行被横切成的上下两半**」（其中一块是收到侧的移交框）。
     *
     * 接缝切的是**行**（水平切线），所以两半的形态是：**横向对齐、纵向紧邻**。
     * 反过来「同一行左右被切开」的两块在纵向上完全重叠 —— 它们早已被普通分组合成一块，
     * 不归这里管（这里横向重叠要求正是为了把「并排的另一个气泡」挡在外面）。
     *
     * 判据（三条同时成立）：
     * 1. 纵向相距（含重叠）≤ `lineHeightPx × [ADJACENT_LINE_GAP_RATIO]`；
     * 2. 横向重叠 ≥ `min(两块宽) × [ADJACENT_H_OVERLAP_RATIO]`；
     * 3. 面积不超过移交框的 [MAX_FRAGMENT_AREA_RATIO] 倍（比它还大的块更可能是另一段内容）。
     *
     * 阈值来源与偏差见各常量的 KDoc（参考项目 `seam-residuals.js` / `seam-surface-utils.js`）。
     */
    fun isSameLineFragment(part: Box, fragment: Box, lineHeightPx: Float): Boolean {
        if (fragment.width <= 0f || fragment.height <= 0f) return false
        val verticalTolerance = (lineHeightPx * ADJACENT_LINE_GAP_RATIO).coerceAtLeast(MIN_BOX_PX)
        if (verticalGap(part, fragment) > verticalTolerance) return false
        val hOverlap = horizontalOverlap(part, fragment)
        val minWidth = minOf(part.width, fragment.width).coerceAtLeast(1f)
        if (hOverlap < minWidth * ADJACENT_H_OVERLAP_RATIO) return false
        val partArea = (part.width * part.height).coerceAtLeast(1f)
        return fragment.width * fragment.height <= partArea * MAX_FRAGMENT_AREA_RATIO
    }

    /**
     * 把与本块**同一行**的碎片并进同一个框。
     *
     * 真机 `[5467219]`：移交框 `x 103~357, y 124~145` 与本图碎片 `x 102~354, y ~110~120`
     * 相距 14px（行高 ≈40 → 容差 24）且横向近乎完全重叠 → 并成一块，那行乱码就被同一个框擦掉。
     */
    fun expandForAdjacentFragments(box: Box, fragments: List<Box>, lineHeightPx: Float): Box {
        if (fragments.isEmpty()) return box
        var expanded = box
        // 迭代到不动点：并进来一块后，它的位置可能又让下一块满足条件（链式碎片）。
        var changed = true
        while (changed) {
            changed = false
            fragments.forEach { fragment ->
                if (!isSameLineFragment(expanded, fragment, lineHeightPx)) return@forEach
                val union = expanded.union(fragment)
                if (union != expanded) {
                    expanded = union
                    changed = true
                }
            }
        }
        return expanded
    }

    /**
     * 框至少一行高（不足则**向下**扩，扩到 `limitBottom` 为止；`limitBottom` = 本图底边）。
     *
     * 向下扩而不是居中扩：接缝处被切开的行，缺的那半通常在下一条带上（本图顶部的框只拿到上半）。
     */
    fun enforceMinimumHeight(box: Box, lineHeightPx: Float, limitBottom: Float): Box {
        val minHeight = (lineHeightPx * MIN_COVER_LINES).coerceAtLeast(MIN_BOX_PX)
        if (box.height >= minHeight) return box
        val bottom = minOf(limitBottom, box.top + minHeight)
        return Box(box.left, box.top, box.right, maxOf(box.bottom, bottom))
    }

    /** 这个框是否薄到「不值得擦/盖」（会产出薄盖板 + 残片译文）。 */
    fun isDegenerate(box: Box): Boolean = box.width < MIN_BOX_PX || box.height < MIN_BOX_PX

    private fun verticalGap(a: Box, b: Box): Float =
        maxOf(0f, maxOf(a.top, b.top) - minOf(a.bottom, b.bottom))

    private fun horizontalOverlap(a: Box, b: Box): Float =
        (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
}
