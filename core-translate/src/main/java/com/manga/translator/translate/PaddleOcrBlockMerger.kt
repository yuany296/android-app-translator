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
        // 竖排「同行」= 同一列里上下接续，所以只对**都像竖列**的块按竖排语义判定。
        //
        // ⚠️ 2026-09-30 修正：原来这里是 `if (aVertical != bVertical) return false`，
        // 只用一个**很弱的宽高比**（`height >= width * 1.15`）就给块定方向 ——
        // 横排三行文本的大框很容易被误判成「竖块」，后面 `mergeGroupToBlock` 便按
        // 列序（centerX 降序）+ 无分隔符拼接，**原文在翻译前就已经乱了**
        // （用户反馈：译文「捕, 追术教导技将它NPC唯一的那」）。
        // 现在用 [looksLikeColumnPair] 的排布判据兜底：一对块必须**横向几乎不重叠、
        // 纵向高度重叠**才算「并排的列」。
        val columnPair = looksLikeColumnPair(a, b)
        if (!columnPair && aVertical != bVertical) return false

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

        return if (columnPair) {
            // 竖排「同一行」= 同一横排里**并排的列**（右列在前、左列在后）。
            //
            // 2026-09-30 修正：这里原来（在 `aVertical` 语义下）要求
            // `overlapX >= minW * MIN_OVERLAP_RATIO`（水平重叠 ≥55%）——
            // 那是「同一列内上下接续」的判据，与 **columnPair 的前提（横向分开）
            // 自相矛盾**：并排的列按定义就没有水平重叠，所以真竖排的两列永远合不起来。
            // 实测：两个窄列并排时 `xOverlap=-10, minW=40` → 该条件恒假（回归测试抓到）。
            //
            // 并排成列的判据改为：纵向确实并排（垂直重叠）+ 水平相邻（间隔不超过一个字高量级）。
            val overlapY = min(a.bottom, b.bottom) - max(a.top, b.top)
            val gapX = max(0f, max(a.left, b.left) - min(a.right, b.right))
            overlapY >= minH * MIN_OVERLAP_RATIO &&
                gapX <= maxH * INLINE_GAP_RATIO
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
        // 同 [shouldJoinSameLine]：这一对到底走「竖排列之间」还是「横排行之间」，
        // 由**排布关系**决定，而不是由两个块各自的宽高比决定。
        val columnPair = looksLikeColumnPair(a, b)
        if (!columnPair && aVertical != bVertical) return false

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
        val axisCenterDist = if (columnPair) abs(a.centerX() - b.centerX()) else abs(a.centerY() - b.centerY())
        if (axisCenterDist > avgH * PARAGRAPH_MAX_AXIS_DIST_RATIO) return false

        return if (columnPair) {
            // 并排的列之间：水平相邻（间隔小）+ 纵向并排。
            // 同 [shouldJoinSameLine]：**不要求水平重叠** —— 并排的列按定义就没有重叠，
            // 旧条件 `overlapX >= minW * PARAGRAPH_OVERLAP_RATIO` 会让它们永不合并。
            val overlapY = min(a.bottom, b.bottom) - max(a.top, b.top)
            hGap <= avgH * PARAGRAPH_INLINE_GAP_RATIO &&
                overlapY >= min(hA, hB) * PARAGRAPH_OVERLAP_RATIO
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
        // ⚠️ 这里决定**原文的拼接顺序**，也是最容易静默搞坏输出的地方（2026-09-30）。
        //
        // 原来只看 `isVerticalBlock(sorted.first())` —— 一个**很弱的宽高比**。
        // 横排三行文本合成的大框满足 `height >= width * 1.15`，于是被判成竖排，
        // 接着按 **centerX 降序、无分隔符** 拼接：
        //     「그 용을 사냥하는 기술을 / 가르칠 수 있는 유일한 NPC가 / 눈 앞의 여자야이라고 했다」
        // 就变成了「...기술을...NPC가...여자야」按列错乱的形式，**翻译前原文就已经坏了**。
        //
        // 现在要求「组内确实构成并排的列」才按竖排拼接 —— 用排布关系（横向几乎不重叠、
        // 纵向高度重叠）判断，与语言无关：韩文竖排、日文竖排都会正确识别。
        val vertical = groupIsVerticalColumns(sorted)
        // 行数随块带出（2026-10-03）：合并后的文本用空格连接、**没有换行符**，
        // 下游（planner / orchestrator）要算「行高 = 框高 ÷ 行数」就只能靠这个字段，
        // 否则行高会退化成整块高度 → 合并阈值放大数倍（两个独立气泡并成一句的真机事故）。
        // 竖排按 1 行处理，与 `BubbleFirstRegionPlanner` 的 `sourceLineCount = 1` 同口径。
        val lineCount: Int
        val text = if (vertical) {
            lineCount = 1
            sorted.sortedWith(compareByDescending<TextBlock> { it.centerX() }.thenBy { it.top })
                .joinToString("") { it.text.trim() }
        } else {
            // 同行用空格连接，跨行（段内）也用空格连接成完整句子。
            // 之前用 \n 连接跨行会把换行符带进 originalText，导致译文也残留 \n
            // （如「超越……留下深刻印象\n以至于」），翻译与显示都很怪；
            // 译文排版交给渲染侧按气泡宽度自动换行。
            val lines = groupByRows(sorted)
            lineCount = lines.size
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
            confidence = sorted.maxOf { it.confidence },
            // 四边形只在「本组合并的就是单个检测框」时透传：合并后的段/行一定是轴对齐的
            // 多行块，硬把其中一个框的倾角当成整块的倾角会让遮罩歪掉（2026-09-29）。
            polygon = group.singleOrNull()?.polygon,
            lineCount = lineCount
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

    /**
     * 单个块是否「**高瘦的窄列**」。
     *
     * 仅按宽高比判断方向；短词（如韩文助词）在横排里也经常是 1~2 个字，
     * 若按字数误判为竖排会导致同行碎片无法合并。
     *
     * ⚠️ 阈值 `1.15 → 2.0`（2026-09-30）：1.15 太松，横排三行的大框就能满足，
     * 而**只有这个函数**在过去决定了原文拼接顺序（见 [mergeGroupToBlock]），
     * 误判的代价是整段原文错乱。真竖排一列通常 ≥ 2.0。
     *
     * 该函数现在只作为**辅助信号**；方向的主判据是 [looksLikeColumnPair] /
     * [groupIsVerticalColumns] 的排布关系。
     */
    private fun isVerticalBlock(block: TextBlock): Boolean {
        return block.heightPx() >= block.widthPx() * VERTICAL_BLOCK_MIN_ASPECT
    }

    /**
     * 这一对块是否构成「**并排的竖列**」—— 用于选择竖排语义（按列）还是横排语义（按行）。
     *
     * 两个条件同时成立：
     * 1. **横向分开**：`xOverlap < minW * COLUMN_X_OVERLAP_MAX`；
     * 2. **合起来竖长**：这一对的外接框 `height >= width * GROUP_VERTICAL_ASPECT`。
     *
     * 第 2 条是关键，它一次性把「同一行的左右碎片」排除掉：
     *
     * | 这一对 | 合起来的框 | 判断 |
     * |---|---|---|
     * | 同一行的两个碎片 | 200×60 | 宽 ≫ 高 → 横排 |
     * | 横排的第 1、2 行 | 500×160 | 宽 > 高 → 横排 |
     * | 真竖排相邻两列 | 90×200 | 高 ≫ 宽 → **竖排** |
     *
     * 只看「有没有横向间隔」不够：真竖排相邻两列可能只隔 10px，而横排同一行的
     * 碎片也可能隔 20px，按间隔分不开（实测会把一行拆成 4 块，回归测试守住这点）。
     *
     * 判据**纯几何、与语言无关** —— 韩文竖排、日文竖排都会被正确识别。
     */
    private fun looksLikeColumnPair(a: TextBlock, b: TextBlock): Boolean {
        val minW = min(a.widthPx(), b.widthPx()).coerceAtLeast(1f)
        val xOverlap = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
        if (xOverlap >= minW * COLUMN_X_OVERLAP_MAX) return false
        // 合起来必须竖长 —— 这一条一次性排除「同一行的左右碎片」（它们合起来是宽的）。
        val unionWidth = (max(a.right, b.right) - min(a.left, b.left)).coerceAtLeast(1f)
        val unionHeight = (max(a.bottom, b.bottom) - min(a.top, b.top)).coerceAtLeast(1f)
        return unionHeight >= unionWidth * GROUP_VERTICAL_ASPECT
    }

    /**
     * 这一组块整体是否构成「并排的竖列」。
     *
     * ## 判据：组的整体形状，而不是单个块或单对块
     *
     * 真竖排一列 = 一列字，整体**竖长**：`height >= width * GROUP_VERTICAL_ASPECT`。
     *
     * 为什么不能用「单对块之间有没有间隔」来判：**真竖排相邻两列可能只隔 10px**
     * （单字框之间本来就近），而横排同一行的左右碎片也可能隔 20px —— 两者按间隔
     * 根本分不开（实测：按间隔判会把一行拆成 4 块）。但**组的整体形状**分得很开：
     *
     * | 情形 | 组合框 | 判断 |
     * |---|---|---|
     * | 横排一行碎片 | 440×60 | 宽 ≫ 高 → 横排 |
     * | 横排三行（用户那个 bug） | 500×250 | 宽 > 高 → 横排 |
     * | 竖排两列 | 90×200 | 高 ≫ 宽 → 竖排 |
     *
     * 这个判据是**纯几何、与语言无关**的 —— 韩文竖排、日文竖排都会正确识别，
     * 不靠「按语言关掉竖排」那种会误伤真竖排的做法。
     */
    private fun groupIsVerticalColumns(group: List<TextBlock>): Boolean {
        if (group.size < 2) return false
        val width = (group.maxOf { it.right } - group.minOf { it.left }).coerceAtLeast(1f)
        val height = (group.maxOf { it.bottom } - group.minOf { it.top }).coerceAtLeast(1f)
        return height >= width * GROUP_VERTICAL_ASPECT
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

    /**
     * 单块判竖排的最小高宽比（真竖排一列通常 ≥ 2.0）。
     *
     * 2026-09-30：`1.15 → 2.0`。1.15 太松 —— 横排三行合成的大框就能满足，
     * 而本函数过去**独自决定**了原文拼接顺序，误判代价是整段原文错乱。
     */
    private const val VERTICAL_BLOCK_MIN_ASPECT = 2.0f

    /**
     * 一组块判为「竖排」的最小整体高宽比。
     *
     * 取 1.15：真竖排一列的高宽比通常很大（一列字），而横排多行即使行数多、
     * 组合框也偏宽（三行横排约 2:1 宽）。1.15 留出检测框抖动余量，
     * 同时不会把「两行且第二行很短」的横排误判成竖排。
     */
    private const val GROUP_VERTICAL_ASPECT = 1.15f

    /**
     * 「横向分开」判据：一对块的横向重叠不得超过较窄块的这个比例。
     *
     * 只用于**选择语义**（见 [looksLikeColumnPair]），不用于判定整组方向。
     */
    private const val COLUMN_X_OVERLAP_MAX = 0.25f

    /**
     * 「并排成列」判据：一对块的纵向重叠至少达到较矮块的这个比例。
     *
     * 用于 [shouldJoinSameLine] / [shouldJoinParagraph] 选择「按列」还是「按行」的语义。
     */
    private const val COLUMN_Y_OVERLAP_MIN = 0.5f

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

