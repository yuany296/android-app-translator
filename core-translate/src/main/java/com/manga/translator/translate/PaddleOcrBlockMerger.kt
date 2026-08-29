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
            val sameRow = overlapY >= minH * MIN_OVERLAP_RATIO &&
                centerYDist <= maxH * BASELINE_DIST_RATIO &&
                gapX <= maxH * INLINE_GAP_RATIO
            if (sameRow) return true
            // 倾斜行的楼梯式碎片：基线随行斜率逐级偏移，垂直重叠可能很小甚至为负，
            // 但只要高度一致、中心连线斜率落在合理倾斜角内、水平间隔合理，仍视为同一行。
            val dx = abs(a.centerX() - b.centerX())
            val dy = abs(a.centerY() - b.centerY())
            val slope = if (dx <= 1f) 0f else dy / dx
            // 2026-08-27 收紧：倾斜楼梯碎片必须高度一致，防止不同字号/不同行误合并。
            val heightConsistent = max(hA, hB) / min(hA, hB) <= SLANT_HEIGHT_RATIO_LIMIT
            val slanted = heightConsistent &&
                slope in SLANT_SLOPE_MIN..SLANT_SLOPE_MAX &&
                gapX <= maxH * SLANT_INLINE_GAP_RATIO &&
                overlapY >= -maxH * SLANT_NEG_OVERLAP_LIMIT
            slanted
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

        // 2026-08-27 收紧：段落合并首先要求主轴中心距不超过严格阈值，防止跨列/跨气泡误合并。
        // 横排看垂直中心距，竖排看水平中心距。
        val axisCenterDist = if (aVertical) abs(a.centerX() - b.centerX()) else abs(a.centerY() - b.centerY())
        if (axisCenterDist > avgH * PARAGRAPH_MAX_AXIS_DIST_RATIO) return false

        return if (aVertical) {
            // 竖排列之间
            hGap <= avgH * PARAGRAPH_INLINE_GAP_RATIO &&
                overlapX >= minW * PARAGRAPH_OVERLAP_RATIO &&
                centerDist <= max(wA, wB) * PARAGRAPH_CENTER_DIST_RATIO
        } else {
            // 横排行之间：要求同一列有实质重叠，或短行居中缩进对齐（两条都满足才算）
            val columnAligned = overlapX >= minW * PARAGRAPH_OVERLAP_RATIO
            val centeredIndent = hGap <= minW * PARAGRAPH_ALIGN_GAP_RATIO &&
                centerDist <= avgH * PARAGRAPH_CENTER_DIST_RATIO
            vGap <= avgH * PARAGRAPH_LINE_GAP_RATIO && (columnAligned || centeredIndent)
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
    // 2026-08-26 收紧：此前段落级过松（中心距可达 2 倍字高、行距 1.3 倍字高），
    // 会把上下相邻的不同气泡/旁白误合并成一个句子。
    // 2026-08-27 再次收紧：用户反馈仍太宽松，减少跨气泡/跨行误合并。
    private const val HEIGHT_RATIO_LIMIT = 1 / 0.72f // 高度差异不超过 ~1.39 倍
    private const val MIN_OVERLAP_RATIO = 0.55f
    private const val BASELINE_DIST_RATIO = 0.24f
    private const val CENTER_DIST_RATIO = 0.24f
    private const val INLINE_GAP_RATIO = 0.65f

    private const val PARAGRAPH_LINE_GAP_RATIO = 0.65f // 行间距不超过 0.65 倍字高
    private const val PARAGRAPH_INLINE_GAP_RATIO = 0.65f
    private const val PARAGRAPH_OVERLAP_RATIO = 0.45f
    private const val PARAGRAPH_ALIGN_GAP_RATIO = 0.22f
    private const val PARAGRAPH_CENTER_DIST_RATIO = 0.85f
    private const val PARAGRAPH_MAX_AXIS_DIST_RATIO = 1.5f // 段落合并前主轴中心距上限（横排看 Y、竖排看 X）
    private const val ROW_SPLIT_CENTER_RATIO = 0.30f

    // 倾斜行楼梯碎片合并：中心连线斜率范围（tan，约 2°~19°）
    private const val SLANT_SLOPE_MIN = 0.03f
    private const val SLANT_SLOPE_MAX = 0.35f
    private const val SLANT_INLINE_GAP_RATIO = 0.70f
    private const val SLANT_NEG_OVERLAP_LIMIT = 0.15f
    private const val SLANT_HEIGHT_RATIO_LIMIT = 1 / 0.80f // 倾斜碎片高度差异不超过 1.25 倍
}
