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
 * 旧算法量出来 14px），又不会把**相邻的另一行**吃进来 —— 相邻行由
 * [isAdjacentLineFragment] 单独判（吸收后原文会并进移交气泡的翻译请求，不会丢那行的译文，
 * 参考项目 `seam-surface-utils.js` 的 `buildSeamExtendedBubbles` 同一件事）。
 */
    const val ADJACENT_LINE_GAP_RATIO = 0.3f

    /** 横向重叠下限（占较窄块的比例）。与参考项目 `isSeamAdjacentBubbleLine` 的 `0.5` 同值。 */
    const val ADJACENT_H_OVERLAP_RATIO = 0.5f

    // ---------------------------------------------------------------------------
    // 相邻行（参考项目 `kind:"adjacent_line"`，2026-10-11 移植）
    //
    // 同一行的两半（[isSameLineFragment]）之外，跨缝气泡还有一种形态：**缝落在行与行之间，
    // 上一张移交了前几行，本图检出的是同一气泡的下一行**。它不该自己单独成块（两块盖板、
    // 两份译文、断行对不上），该并进移交框、原文并进移交气泡的翻译请求
    // （参考项目 `seam-surface-utils.js:buildSeamExtendedBubbles` 同一件事）。
    //
    // 判据对齐参考项目 `seam-residuals.js:isSeamAdjacentBubbleLine`，两处刻意偏离：
    // 1. 纵向窗口的单位用 **行高**（参考用 minHeight = 两框里更矮的那个框高）—— 它的输入是
    //    逐行检测（框高即行高），我们的检出可能是段落级框，minHeight 不稳定；
    // 2. 比**参考项目多两道守卫**（单行高上限、面积上限）：参考项目有 `translationRole`
    //    （旁白/对白）可以核对身份，我们没有 —— 多行块、超大块更可能是另一段内容，
    //    误吸收的代价（整句串味）比漏吸收（两块各翻各的，现状）高。
    // ---------------------------------------------------------------------------

    /** 相邻行纵向间隙上限（相对行高）。参考项目 `maxGap = minHeight × 0.55`。 */
    const val ADJACENT_LINE_GAP_MAX_RATIO = 0.55f

    /**
     * 相邻行允许的纵向重叠上限（相对行高）。
     * 参考项目注释：「OCR 相邻行的框常有几像素重叠，允许最多重叠较矮行的四成」
     * （`minGap = -minHeight × 0.4`）。
     */
    const val ADJACENT_LINE_OVERLAP_MAX_RATIO = 0.4f

    /**
     * 两框重叠占**较小框**面积的比例上限：≥ 它说明两框基本叠在一起 —— 那是「同一行的两半」
     * （[isSameLineFragment] 管），不是相邻行。参考项目同值（`overlapOverSmaller >= 0.35`）。
     */
    const val ADJACENT_MAX_BOX_OVERLAP_RATIO = 0.35f

    /** 相邻行高度上限（相对行高）：更高的是多行块 = 另一段内容（见上「多两道守卫」）。 */
    const val ADJACENT_MAX_HEIGHT_LINES = 1.6f

    /** 相邻行面积上限（相对移交框）：一行文字可以比移交的那半句长，但不能长出几倍。 */
    const val MAX_ADJACENT_AREA_RATIO = 2.5f

    /**
     * 这块检出是不是移交框**同一气泡的相邻行**（缝落在行与行之间的续排）。
     *
     * 判据（全部成立）：
     * 1. 高度 ≤ [ADJACENT_MAX_HEIGHT_LINES] × 行高（单行）；
     * 2. 面积 ≤ 移交框 × [MAX_ADJACENT_AREA_RATIO]；
     * 3. 与移交框的重叠 < 较小框面积的 [ADJACENT_MAX_BOX_OVERLAP_RATIO]（几乎叠在一起的是同行两半）；
     * 4. 纵向间隙落在 `[-0.4, +0.55] × 行高`（允许几像素重叠，参考项目同窗口）；
     * 5. 横向重叠 ≥ 较窄块的 [ADJACENT_H_OVERLAP_RATIO]。
     */
    fun isAdjacentLineFragment(part: Box, fragment: Box, lineHeightPx: Float): Boolean {
        if (lineHeightPx <= 0f || !lineHeightPx.isFinite()) return false
        if (fragment.width <= 0f || fragment.height <= 0f) return false
        if (fragment.height > lineHeightPx * ADJACENT_MAX_HEIGHT_LINES) return false
        val partArea = (part.width * part.height).coerceAtLeast(1f)
        if (fragment.width * fragment.height > partArea * MAX_ADJACENT_AREA_RATIO) return false
        val ix = minOf(part.right, fragment.right) - maxOf(part.left, fragment.left)
        val iy = minOf(part.bottom, fragment.bottom) - maxOf(part.top, fragment.top)
        if (ix > 0f && iy > 0f) {
            val smaller = minOf(partArea, fragment.width * fragment.height).coerceAtLeast(1f)
            if (ix * iy / smaller >= ADJACENT_MAX_BOX_OVERLAP_RATIO) return false
        }
        val aboveGap = part.top - fragment.bottom
        val belowGap = fragment.top - part.bottom
        val maxGap = lineHeightPx * ADJACENT_LINE_GAP_MAX_RATIO
        val minGap = -lineHeightPx * ADJACENT_LINE_OVERLAP_MAX_RATIO
        val adjacent = (aboveGap >= minGap && aboveGap <= maxGap) ||
            (belowGap >= minGap && belowGap <= maxGap)
        if (!adjacent) return false
        val hOverlap = horizontalOverlap(part, fragment)
        val minWidth = minOf(part.width, fragment.width).coerceAtLeast(1f)
        return hOverlap >= minWidth * ADJACENT_H_OVERLAP_RATIO
    }

    /** 被「相邻行」路径吸收的本图检出：框（本图坐标）+ 原文（要并进移交气泡的翻译请求）。 */
    data class AbsorbedFragment(
        val ownedIndex: Int,
        val box: Box,
        val originalText: String
    )

    /**
     * 相邻行原文并进移交气泡的原文，**按纵向位置排序**（移交框的顶边可为负 = 起点在上一张图里，
     * 直接比大小即可）。
     *
     * 对齐参考项目 `buildSeamExtendedBubbles` 的排序（页序 → y → 文本）；它的注释同样适用这里：
     * 「框和译文都盖住两行了，原文却还停在 winner 的半句上」—— 原文不并进去，整句重翻就少了那行。
     */
    fun mergeAdjacentLineText(partText: String, partTop: Float, fragments: List<AbsorbedFragment>): String {
        val lines = buildList {
            fragments.forEach { fragment ->
                if (fragment.originalText.isNotBlank()) add(fragment.box.top to fragment.originalText.trim())
            }
            if (partText.isNotBlank()) add(partTop to partText.trim())
        }
        return lines.sortedBy { it.first }.joinToString("\n") { it.second }
    }

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
     * 判据（四条同时成立）：
     * 1. 纵向相距（含重叠）≤ `lineHeightPx × [ADJACENT_LINE_GAP_RATIO]`；
     * 2. 横向重叠 ≥ `min(两块宽) × [ADJACENT_H_OVERLAP_RATIO]`；
     * 3. 面积不超过移交框的 [MAX_FRAGMENT_AREA_RATIO] 倍（比它还大的块更可能是另一段内容）；
     * 4. **碎片本身 ≤ [ADJACENT_MAX_HEIGHT_LINES] 行**（2026-10-12 真机加）。
     *
     * 为什么需要第 4 条：移交框是「上一张条带里看到的下半段」，条带常常足够深（2~4 行），
     * 它的框在纵向上能**罩住画面里嵌在它下方的另一个气泡**（真机 gamesal 7/8 页：
     * 케이지移交框 本图系 0~288，악질들泡 118~239 —— 两泡在原画里本来就是上下嵌着的，
     * 横向重叠 53% 全部满足 1~3 条）→ 「另一个气泡」被当成切缝残片吸走：不擦、不翻、
     * 不渲染，原文整段露出。真正的切缝残片是**半行/一行**（真机 `1드하데 거리기라` 高
     * 25px），两行的块是多行气泡，不是残片。
     *
     * 阈值来源与偏差见各常量的 KDoc（参考项目 `seam-residuals.js` / `seam-surface-utils.js`）。
     */
    fun isSameLineFragment(part: Box, fragment: Box, lineHeightPx: Float): Boolean {
        if (fragment.width <= 0f || fragment.height <= 0f) return false
        if (lineHeightPx > 0f && lineHeightPx.isFinite() &&
            fragment.height > lineHeightPx * ADJACENT_MAX_HEIGHT_LINES
        ) {
            return false
        }
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
