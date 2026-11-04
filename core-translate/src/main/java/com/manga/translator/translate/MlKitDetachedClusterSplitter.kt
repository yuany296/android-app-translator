package com.manga.translator.translate

import kotlin.math.max
import kotlin.math.min

/**
 * 把 ML Kit **同一个 block 里相距很远的元素**拆成多个文字块（2026-11-03）。
 *
 * ## 现场
 *
 * 韩漫 Lezhin 第 4 页（pageHash `4bee1031d4…`）右侧有两段分散的拟声词：
 * `하앗` 与 `쪽`。ML Kit 把 `앗` 和 `쪽` 放进了同一个 block 的同一个 line：
 *
 * ```
 * BLOCK 0 box=208,423,306,711 text=[앗 쪽]
 *   ELEM 0 box=209,423,306,611 text=[앗]
 *   ELEM 1 box=208,625,305,711 text=[쪽]
 * BLOCK 1 box=128,452,308,544 text=[하앗]
 * ```
 *
 * 块级框是两段的外接框（高 288px）。后续 `BubbleFirstRegionPlanner` 把它当成
 * 一块 35% 页高的文字，盖板/清理图遮住一大片画面；用户看到的就是
 * 「拟声词本来是分散的，合并后一大片被清理图遮住」。
 *
 * ## 做法
 *
 * 1. 用**元素级框**（不是行框）看间距；元素框先按墨迹收紧（回调由调用方提供，
 *    因为收紧需要 Bitmap，纯逻辑层不依赖 Android）。
 * 2. 同一行内：两个元素的横向/纵向间隙都超过 `0.45 × 较小边` 才断开；
 *    不同行：阈值放宽到 `0.85 × 较小边`（正常行距不能被拆）。
 * 3. 拆出的块带同一个 [TextBlock.splitGroupId]，`OcrBlockMergeRules` 不会再
 *    把它们合并回去；不同组仍按原规则走。
 * 4. 如果某个拆出的碎片被页面里另一个块**完全包含**（文本也是子串），直接丢掉，
 *    避免 ML Kit 把 `하앗` 拆成 `하` + `앗` 后重复翻译。
 *
 * 纯 Kotlin，可 JVM 单测。
 */
internal object MlKitDetachedClusterSplitter {

    /** 同一行内元素断开的阈值（× 较小边）。正常词间距约 0.2~0.3。 */
    const val SAME_LINE_GAP_RATIO = 0.45f

    /** 不同行元素断开的阈值（× 较小边）。正常行距约 0.2~0.6。 */
    const val CROSS_LINE_GAP_RATIO = 0.85f

    /** 元素太小/太近时不值得拆。 */
    const val MIN_GAP_PX = 6f

    /** 一个 block 最多拆成几段；超过说明判据可能误伤，放弃拆分。 */
    const val MAX_CLUSTERS = 4

    /** 块高超过元素中位高的这个倍数才值得做收紧 + 聚类。 */
    const val TRIGGER_HEIGHT_RATIO = 1.35f

    /** 同一行内最大元素间隙超过中位边的这个倍数也触发。 */
    const val TRIGGER_SAME_LINE_GAP_RATIO = 0.75f

    /** 「一个碎片被另一个块包含」的重叠比例。 */
    const val CONTAIN_OVERLAP_RATIO = 0.6f

    /**
     * @param blocks 同一张图（或同一个分块）的原始文字块。
     * @param tighten 元素框墨迹收紧回调；返回 null 表示保持原框。
     */
    fun split(
        blocks: List<TextBlock>,
        tighten: ((TextElement) -> TextElement?)? = null
    ): List<TextBlock> {
        if (blocks.isEmpty()) return blocks
        val splitResults = blocks.map { block -> splitBlock(block, tighten) }
        val didSplit = splitResults.any { it != null }
        if (!didSplit) return blocks

        // 展开成「原始块 / 拆出的碎片」序列，保持页面顺序。
        val flattened = mutableListOf<TextBlock>()
        splitResults.forEachIndexed { index, clusters ->
            if (clusters == null) {
                flattened += blocks[index]
            } else {
                flattened += clusters
            }
        }

        // 丢弃被其他块完全包含的碎片（同组除外）。典型：`앗` 是 `하앗` 的后缀。
        val kept = flattened.filter { candidate ->
            if (candidate.splitGroupId == null) return@filter true
            flattened.none { other ->
                other !== candidate &&
                    other.splitGroupId != candidate.splitGroupId &&
                    contains(other, candidate)
            }
        }
        return kept.mapIndexed { index, block -> block.copy(id = index) }
    }

    private fun splitBlock(
        block: TextBlock,
        tighten: ((TextElement) -> TextElement?)?
    ): List<TextBlock>? {
        val rawElements = block.elements ?: return null
        if (rawElements.size < 2 || rawElements.size > 80) return null
        // 先用**原始元素框**做便宜的触发判断；只有可疑块才跑墨迹收紧（逐元素 getPixels
        // 有成本，正常单词/短句不该为这个功能付钱）。原始框很松时主要靠块高触发；
        // 原始框已经紧时靠同行大间距触发。
        val rawMedianW = rawElements.map { it.width() }.sorted()[rawElements.size / 2]
        val rawMedianH = rawElements.map { it.height() }.sorted()[rawElements.size / 2]
        if (rawMedianW <= 1f || rawMedianH <= 1f) return null
        val trigger = (block.bottom - block.top) > rawMedianH * TRIGGER_HEIGHT_RATIO ||
            maxSameLineGap(rawElements) > max(rawMedianW, rawMedianH) * TRIGGER_SAME_LINE_GAP_RATIO
        if (!trigger) return null

        val elements = rawElements.mapNotNull { element ->
            val tightened = tighten?.invoke(element) ?: element
            if (tightened.width() <= 1f || tightened.height() <= 1f) null else tightened
        }
        if (elements.size < 2) return null

        val parent = IntArray(elements.size) { it }

        fun find(x: Int): Int {
            var node = x
            while (parent[node] != node) {
                parent[node] = parent[parent[node]]
                node = parent[node]
            }
            return node
        }

        fun union(a: Int, b: Int) {
            val pa = find(a)
            val pb = find(b)
            if (pa != pb) parent[pb] = pa
        }

        for (i in elements.indices) {
            for (j in (i + 1) until elements.size) {
                if (shouldConnect(elements[i], elements[j])) union(i, j)
            }
        }

        val grouped = linkedMapOf<Int, MutableList<TextElement>>()
        elements.forEachIndexed { index, element ->
            grouped.getOrPut(find(index)) { mutableListOf() }.add(element)
        }
        if (grouped.size <= 1 || grouped.size > MAX_CLUSTERS) return null

        val groupId = "mlkit-split-${block.id}-${block.left.toInt()}-${block.top.toInt()}"
        return grouped.values.mapIndexed { index, group ->
            buildClusterBlock(block, group, groupId, index)
        }
    }

    private fun shouldConnect(a: TextElement, b: TextElement): Boolean {
        val hGap = max(0f, max(a.left, b.left) - min(a.right, b.right))
        val vGap = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))
        if (hGap <= 0f && vGap <= 0f) return true
        val scale = min(min(a.width(), a.height()), min(b.width(), b.height()))
            .coerceAtLeast(1f)
        val ratio = if (a.lineIndex == b.lineIndex) SAME_LINE_GAP_RATIO else CROSS_LINE_GAP_RATIO
        val limit = max(MIN_GAP_PX, scale * ratio)
        return hGap <= limit && vGap <= limit
    }

    private fun maxSameLineGap(elements: List<TextElement>): Float {
        var result = 0f
        for (i in elements.indices) {
            for (j in (i + 1) until elements.size) {
                if (elements[i].lineIndex != elements[j].lineIndex) continue
                val hGap = max(0f, max(elements[i].left, elements[j].left) -
                    min(elements[i].right, elements[j].right))
                val vGap = max(0f, max(elements[i].top, elements[j].top) -
                    min(elements[i].bottom, elements[j].bottom))
                result = max(result, max(hGap, vGap))
            }
        }
        return result
    }

    private fun buildClusterBlock(
        source: TextBlock,
        group: List<TextElement>,
        groupId: String,
        index: Int
    ): TextBlock {
        val vertical = boundsOf(group).let { box -> box.height() > box.width() * 1.2f }
        val byLine = group.groupBy { it.lineIndex }
            .toSortedMap()
            .values
            .map { line -> line.sortedBy { if (vertical) it.top else it.left } }
        val text = byLine.joinToString(separator = "\n") { line ->
            joinLine(line, vertical)
        }
        val lineBoxes = byLine.map { line -> unionBox(line) }
        val box = unionBox(group)
        return source.copy(
            id = source.id * 10 + index,
            text = text.ifBlank { source.text },
            left = box[0],
            top = box[1],
            right = box[2],
            bottom = box[3],
            lineCount = lineBoxes.size.coerceAtLeast(1),
            lineBoxes = lineBoxes,
            elements = group,
            splitGroupId = groupId
        )
    }

    private fun joinLine(line: List<TextElement>, vertical: Boolean): String {
        if (line.isEmpty()) return ""
        val builder = StringBuilder(line.first().text.trim())
        var previous = line.first()
        for (index in 1 until line.size) {
            val current = line[index]
            val gap = if (vertical) {
                current.top - previous.bottom
            } else {
                current.left - previous.right
            }
            val limit = max(2f, 0.15f * min(previous.height(), current.height()))
            if (gap > limit) builder.append(' ')
            builder.append(current.text.trim())
            previous = current
        }
        return builder.toString().trim()
    }

    private fun unionBox(elements: List<TextElement>): FloatArray {
        return floatArrayOf(
            elements.minOf { it.left },
            elements.minOf { it.top },
            elements.maxOf { it.right },
            elements.maxOf { it.bottom }
        )
    }

    private fun boundsOf(elements: List<TextElement>): Box {
        val box = unionBox(elements)
        return Box(box[0], box[1], box[2], box[3])
    }

    /** `outer` 是否包含 `inner`（文本子串 + 面积重叠 ≥ [CONTAIN_OVERLAP_RATIO]）。 */
    private fun contains(outer: TextBlock, inner: TextBlock): Boolean {
        val outerText = normalize(outer.text)
        val innerText = normalize(inner.text)
        if (outerText.length <= innerText.length || !outerText.contains(innerText)) return false
        val innerArea = (inner.right - inner.left) * (inner.bottom - inner.top)
        if (innerArea <= 0f) return false
        val overlapW = min(outer.right, inner.right) - max(outer.left, inner.left)
        val overlapH = min(outer.bottom, inner.bottom) - max(outer.top, inner.top)
        if (overlapW <= 0f || overlapH <= 0f) return false
        return overlapW * overlapH / innerArea >= CONTAIN_OVERLAP_RATIO
    }

    private fun normalize(text: String): String =
        text.filter { it.isLetterOrDigit() }.lowercase()

    private fun TextElement.width(): Float = right - left

    private fun TextElement.height(): Float = bottom - top

    private data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        fun width(): Float = right - left
        fun height(): Float = bottom - top
    }
}
