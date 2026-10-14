package com.manga.translator.translate

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 文字块的**视觉行数**统计（2026-09-28）。
 *
 * 背景：译文字号要「和原文一样大」，而字号原文字高 = 内容框高 ÷ 行数。
 * 直接用「OCR 文字块个数」当行数是错的 —— 检测器经常把**同一行**切成两块
 * （空格/标点/气泡分配切分），行数被数大 → 字高估小 → 译文明显偏小。
 * 这里按纵向重叠把块合并成视觉行：同一行的碎片算一行。
 *
 * 纯逻辑（只用 [TextBlock] 几何），便于 JVM 单测。
 */
object TextLayoutMetrics {

    /** 同一行的判定：纵向重叠比例 ≥ [SAME_LINE_OVERLAP] 或中心距 < 平均字高 ×[SAME_LINE_CENTER_RATIO]。 */
    private const val SAME_LINE_OVERLAP = 0.45f
    private const val SAME_LINE_CENTER_RATIO = 0.55f

    /** 排版框外扩：默认每侧各扩 15%（内容框 → 至多 1.3 倍），但不得侵入气泡描边 [OUTER_INSET_RATIO]。 */
    const val LAYOUT_GROW_RATIO = 0.30f
    const val LAYOUT_OUTER_INSET_RATIO = 0.04f

    /**
     * 把气泡的**内容框**朝外框方向扩一圈（不侵入外框 4% 描边），返回扩后的气泡副本。
     *
     * 为什么要扩：OCR 内容框贴着字，译文比原文长时很快就放不下 → 字号被压小（用户反馈
     * 「字号感觉小了」）。真实排版（参考项目）是把文字铺满整个气泡框，必要时还会
     * `expandBubbleForTextOverflow` 撑大框；这里做等价的事：在气泡内部给译文更多空间。
     * 扩不动（旁白这种外框≈内容框）时原样返回。
     */
    fun expandContentRect(
        bubble: Bubble,
        growRatio: Float = LAYOUT_GROW_RATIO,
        outerInsetRatio: Float = LAYOUT_OUTER_INSET_RATIO
    ): Bubble {
        val content = BubbleGeometry.contentRect(bubble)
        val outer = BubbleGeometry.outerRect(bubble)
        if (content.width <= 0f || content.height <= 0f) return bubble
        val growPerSideX = content.width * growRatio / 2f
        val growPerSideY = content.height * growRatio / 2f
        val insetX = outer.width * outerInsetRatio
        val insetY = outer.height * outerInsetRatio
        val left = maxOf(outer.left + insetX, content.left - growPerSideX)
        val top = maxOf(outer.top + insetY, content.top - growPerSideY)
        val right = minOf(outer.right - insetX, content.right + growPerSideX)
        val bottom = minOf(outer.bottom - insetY, content.bottom + growPerSideY)
        val width = right - left
        val height = bottom - top
        if (width <= content.width || height <= content.height) return bubble
        return bubble.copy(contentX = left, contentY = top, contentW = width, contentH = height)
    }

    /**
     * 逐行框的**行高中位数**（像素）：横排取每行框的高、竖排取每行框的宽（列宽）。
     *
     * 为什么不用「块框高 ÷ 行数」：块级框偏松（含行距/内边距；粘到气泡描边时 OTSU
     * 墨迹收紧也收不回来——描边被当成墨迹），行高被系统性放大（2026-10-14 真机实测：
     * 同一张图上 4 行泡 lineH=45.3、5 行泡 lineH=34.9，前者译文比原文大 1/3、
     * 白盖板戳出气泡）。ML Kit 自带的逐行框紧贴字形，中位数抗单行离群。
     *
     * 返回 0f = 输入不可用（空 / 维度非法），调用方退回老口径。
     */
    fun medianLineBoxDimPx(lineBoxes: List<FloatArray>, vertical: Boolean): Float {
        val dims = lineBoxes.mapNotNull { lb ->
            when {
                lb.size < 4 -> null
                vertical -> (lb[2] - lb[0]).takeIf { it > 1f }
                else -> (lb[3] - lb[1]).takeIf { it > 1f }
            }
        }.sorted()
        if (dims.isEmpty()) return 0f
        return dims[dims.size / 2]
    }

    /**
     * 统计视觉行数；空输入返回 1。
     *
     * ⚠️ 2026-10-14 修：**同一个块里的多行**以前被算成 1 行 —— 而 ML Kit 返回的是块级框，
     * 「两行并成一个块」是常态（真机夹具：4 行韩文只给 3 个块）。行数少算一行，
     * 「框高 ÷ 行数」就翻倍 → `font_height_percent` 翻倍 → 译文比原文大一大截、整块戳出气泡
     * （用户 2026-10-14：「超出太多了吧」）。
     *
     * 现在的口径：块按行分组（同一行被切成多块时只算一行），**每组的行数取组内块 `lineCount`
     * 的最大值**再求和 —— `lineCount` 来自检测器本身（ML Kit = `block.lines`；Paddle 单框 = 1，
     * 合并块 = 组内行数）。检测器没给行数的老数据 `lineCount` 默认 1，行为与改动前一致。
     */
    fun countVisualLines(blocks: List<TextBlock>): Int {
        val usable = blocks.filter { it.bottom > it.top }
        if (usable.isEmpty()) return 1
        val sorted = usable.sortedBy { it.top }
        var lines = 0
        var currentTop = 0f
        var currentBottom = 0f
        var currentCount = 0
        var currentLineSpan = 1
        val heights = mutableListOf<Float>()
        sorted.forEach { block ->
            val blockLineSpan = block.lineCount.coerceAtLeast(1)
            if (currentCount == 0) {
                currentTop = block.top
                currentBottom = block.bottom
                currentCount = 1
                currentLineSpan = blockLineSpan
                heights += block.bottom - block.top
                return@forEach
            }
            val averageHeight = (heights.sum() / heights.size).coerceAtLeast(1f)
            val overlap = min(currentBottom, block.bottom) - max(currentTop, block.top)
            val shorter = min(currentBottom - currentTop, block.bottom - block.top).coerceAtLeast(1f)
            val overlapRatio = overlap / shorter
            val centerDistance = abs(((currentTop + currentBottom) / 2f) - ((block.top + block.bottom) / 2f))
            val sameLine = overlapRatio >= SAME_LINE_OVERLAP ||
                centerDistance < averageHeight * SAME_LINE_CENTER_RATIO
            if (sameLine) {
                currentTop = min(currentTop, block.top)
                currentBottom = max(currentBottom, block.bottom)
                currentCount += 1
                currentLineSpan = maxOf(currentLineSpan, blockLineSpan)
                heights += block.bottom - block.top
            } else {
                lines += currentLineSpan
                currentTop = block.top
                currentBottom = block.bottom
                currentCount = 1
                currentLineSpan = blockLineSpan
                heights += block.bottom - block.top
            }
        }
        if (currentCount > 0) lines += currentLineSpan
        return lines.coerceAtLeast(1)
    }
}
