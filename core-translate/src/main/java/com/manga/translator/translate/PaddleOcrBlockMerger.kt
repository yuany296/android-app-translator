package com.manga.translator.translate

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 参考 C:\homework\translator 中 ocr-lines / ocr-cluster-geometry 的聚合思路，
 * 把 PaddleOCR 检测出的碎片按「同一行 → 同一段落/气泡」合并为完整句子。
 *
 * 与之前固定像素阈值相比，这里全部使用相对于字体高度的比例阈值，
 * 在大屏手机截图上也能把同一句话（包括跨行的对白）合并到一起。
 */
object PaddleOcrBlockMerger {

    fun mergeBlocks(blocks: List<TextBlock>, imageWidth: Int, imageHeight: Int): List<TextBlock> {
        if (blocks.size <= 1) return blocks
        val lines = mergeIntoLines(blocks)
        val paragraphs = mergeIntoParagraphs(lines, imageWidth, imageHeight)
        return paragraphs.mapIndexed { index, group ->
            mergeGroupToBlock(group, index, imageWidth, imageHeight)
        }.filter { it.text.isNotBlank() }
    }

    private fun mergeIntoLines(blocks: List<TextBlock>): List<List<TextBlock>> {
        val sorted = blocks.sortedWith(compareBy<TextBlock> { it.top }.thenBy { it.left })
        val groups = mutableListOf<MutableList<TextBlock>>()
        for (block in sorted) {
            var placed = false
            for (group in groups) {
                if (group.any { shouldJoinSameLine(it, block) }) {
                    group += block
                    placed = true
                    break
                }
            }
            if (!placed) {
                groups += mutableListOf(block)
            }
        }
        return groups
    }

    private fun shouldJoinSameLine(a: TextBlock, b: TextBlock): Boolean {
        val aVertical = isVerticalBlock(a)
        val bVertical = isVerticalBlock(b)
        if (aVertical != bVertical) return false

        val hA = a.heightPx()
        val hB = b.heightPx()
        val wA = a.widthPx()
        val wB = b.widthPx()
        val maxH = max(hA, hB)
        val minH = min(hA, hB)
        val maxW = max(wA, wB)
        val minW = min(wA, wB)
        if (minH <= 0f || minW <= 0f) return false
        if (maxH / minH > HEIGHT_RATIO_LIMIT) return false

        return if (aVertical) {
            // 竖排：按列上下相邻，要求水平重叠、水平中心接近
            val overlapX = min(a.right, b.right) - max(a.left, b.left)
            val centerXDist = abs(a.centerX() - b.centerX())
            val gapY = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))
            overlapX >= minW * MIN_OVERLAP_RATIO &&
                centerXDist <= maxW * CENTER_DIST_RATIO &&
                gapY <= maxW * INLINE_GAP_RATIO
        } else {
            // 横排：按行左右相邻，要求垂直重叠、垂直中心（基线）接近
            val overlapY = min(a.bottom, b.bottom) - max(a.top, b.top)
            val centerYDist = abs(a.centerY() - b.centerY())
            val gapX = max(0f, max(a.left, b.left) - min(a.right, b.right))
            overlapY >= minH * MIN_OVERLAP_RATIO &&
                centerYDist <= maxH * BASELINE_DIST_RATIO &&
                gapX <= maxH * INLINE_GAP_RATIO
        }
    }

    private fun mergeIntoParagraphs(
        lines: List<List<TextBlock>>,
        imageWidth: Int,
        imageHeight: Int
    ): List<List<TextBlock>> {
        if (lines.size <= 1) return lines
        val lineBlocks = lines.map { mergeGroupToBlock(it, -1, imageWidth, imageHeight) }
        val groups = mutableListOf<MutableList<TextBlock>>()
        for (block in lineBlocks) {
            var placed = false
            for (group in groups) {
                if (group.any { shouldJoinParagraph(it, block) }) {
                    group += block
                    placed = true
                    break
                }
            }
            if (!placed) {
                groups += mutableListOf(block)
            }
        }
        // 把代表行的小块再展开回原始碎片，避免行边界信息丢失
        val lineToOriginals = lines.mapIndexed { index, members -> lineBlocks[index] to members }.toMap()
        return groups.map { group ->
            group.flatMap { lineToOriginals[it] ?: listOf(it) }
        }
    }

    private fun shouldJoinParagraph(a: TextBlock, b: TextBlock): Boolean {
        val aVertical = isVerticalBlock(a)
        val bVertical = isVerticalBlock(b)
        if (aVertical != bVertical) return false

        val hA = a.heightPx()
        val hB = b.heightPx()
        val wA = a.widthPx()
        val wB = b.widthPx()
        val avgH = (hA + hB) / 2f
        val minW = min(wA, wB)
        val overlapX = min(a.right, b.right) - max(a.left, b.left)
        val hGap = max(0f, max(a.left, b.left) - min(a.right, b.right))
        val vGap = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))
        val centerDist = hypot(
            (a.centerX() - b.centerX()).toDouble(),
            (a.centerY() - b.centerY()).toDouble()
        ).toFloat()

        return if (aVertical) {
            // 竖排列之间
            hGap <= avgH * PARAGRAPH_INLINE_GAP_RATIO &&
                overlapX >= minW * PARAGRAPH_OVERLAP_RATIO &&
                centerDist <= max(wA, wB) * PARAGRAPH_CENTER_DIST_RATIO
        } else {
            // 横排行之间
            vGap <= avgH * PARAGRAPH_LINE_GAP_RATIO &&
                (overlapX >= minW * PARAGRAPH_OVERLAP_RATIO ||
                    hGap <= minW * PARAGRAPH_ALIGN_GAP_RATIO ||
                    centerDist <= avgH * PARAGRAPH_CENTER_DIST_RATIO)
        }
    }

    private fun mergeGroupToBlock(group: List<TextBlock>, id: Int, imageWidth: Int, imageHeight: Int): TextBlock {
        val sorted = group.sortedWith(compareBy<TextBlock> { it.top }.thenBy { it.left })
        val left = sorted.minOf { it.left }.coerceIn(0f, imageWidth.toFloat())
        val top = sorted.minOf { it.top }.coerceIn(0f, imageHeight.toFloat())
        val right = sorted.maxOf { it.right }.coerceIn(left + 1f, imageWidth.toFloat())
        val bottom = sorted.maxOf { it.bottom }.coerceIn(top + 1f, imageHeight.toFloat())
        val vertical = isVerticalBlock(sorted.first())
        val text = if (vertical) {
            sorted.sortedWith(compareByDescending<TextBlock> { it.centerX() }.thenBy { it.top })
                .joinToString("") { it.text.trim() }
        } else {
            // 同行用空格连接，跨行（段内）也用空格连接成完整句子。
            // 之前用 \n 连接跨行会把换行符带进 originalText，导致译文也残留 \n
            // （如「超越……留下深刻印象\n以至于」），翻译与显示都很怪；
            // 译文排版交给渲染侧按气泡宽度自动换行。
            val lines = groupByRows(sorted)
            lines.joinToString(" ") { row ->
                row.sortedBy { it.left }.joinToString(" ") { it.text.trim() }
            }
        }.trim()
        return TextBlock(
            id = id,
            text = text,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            confidence = sorted.maxOf { it.confidence }
        )
    }

    /**
     * 把已经按行分好的块再按垂直位置分成行，避免把不同行硬用空格连起来。
     */
    private fun groupByRows(blocks: List<TextBlock>): List<List<TextBlock>> {
        if (blocks.isEmpty()) return emptyList()
        val sorted = blocks.sortedBy { it.top }
        val rows = mutableListOf<MutableList<TextBlock>>()
        for (block in sorted) {
            val placedRow = rows.firstOrNull { row ->
                row.any { abs(it.centerY() - block.centerY()) <= max(it.heightPx(), block.heightPx()) * ROW_SPLIT_CENTER_RATIO }
            }
            if (placedRow != null) {
                placedRow += block
            } else {
                rows += mutableListOf(block)
            }
        }
        return rows
    }

    private fun isVerticalBlock(block: TextBlock): Boolean {
        // 仅按宽高比判断方向；短词（如韩文助词）在横排里也经常是 1~2 个字，
        // 若按字数误判为竖排会导致同行碎片无法合并。
        return block.heightPx() >= block.widthPx() * 1.15f
    }

    private fun TextBlock.widthPx(): Float = (right - left).coerceAtLeast(1f)
    private fun TextBlock.heightPx(): Float = (bottom - top).coerceAtLeast(1f)
    private fun TextBlock.centerX(): Float = (left + right) / 2f
    private fun TextBlock.centerY(): Float = (top + bottom) / 2f

    // 阈值全部基于字体高度/宽度比例，参考 translator 的 ocr-lines.js
    private const val HEIGHT_RATIO_LIMIT = 1 / 0.65f // 高度差异不超过 ~1.54 倍
    private const val MIN_OVERLAP_RATIO = 0.45f
    private const val BASELINE_DIST_RATIO = 0.35f
    private const val CENTER_DIST_RATIO = 0.35f
    private const val INLINE_GAP_RATIO = 1.2f

    private const val PARAGRAPH_LINE_GAP_RATIO = 1.3f // 行间距不超过 1.3 倍字高
    private const val PARAGRAPH_INLINE_GAP_RATIO = 1.2f
    private const val PARAGRAPH_OVERLAP_RATIO = 0.20f
    private const val PARAGRAPH_ALIGN_GAP_RATIO = 0.50f
    private const val PARAGRAPH_CENTER_DIST_RATIO = 2.0f
    private const val ROW_SPLIT_CENTER_RATIO = 0.35f
}
