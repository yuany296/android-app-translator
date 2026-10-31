package com.manga.translator.translate

/**
 * ML Kit 段落块跨拼接图内部切缝时的**后处理拆分**（2026-11-01）。
 *
 * ML Kit 返回的是段落级块，一个块可能同时包含切缝上方和下方的两行；这种块交给
 * 气泡归属层后只能整体落到一侧，另一侧的同句又会再翻一遍（接缝重复/漏翻）。
 *
 * 这里不重新 OCR，只利用 ML Kit 自带的 `lineBoxes` 和 `text` 的行结构，把跨缝块
 * 按行中心拆成缝上/缝下两个 `TextBlock`。拆不了就原样保留（绝不猜文本）：
 * - 没有 `lineBoxes`；
 * - `text.lines()` 的行数与 `lineBoxes` 不一致；
 * - 所有行都在同一侧。
 *
 * 纯函数、无 Android 依赖，可 JVM 单测。
 */
internal object MlKitSeamSplitter {

    /**
     * @param blocks ML Kit 原始块（像素坐标，与 [seams] 同一坐标系）
     * @param seams 拼接图内部切缝 y（像素；0/越界由调用方先过滤）
     */
    fun split(blocks: List<TextBlock>, seams: List<Float>): List<TextBlock> {
        if (seams.isEmpty() || blocks.isEmpty()) return blocks
        val sortedSeams = seams.filter { it > 0f }.sorted()
        if (sortedSeams.isEmpty()) return blocks
        return blocks.flatMap { block -> splitBlock(block, sortedSeams) }
    }

    private fun splitBlock(block: TextBlock, seams: List<Float>): List<TextBlock> {
        val lineBoxes = block.lineBoxes ?: return listOf(block)
        if (lineBoxes.any { it.size < 4 }) return listOf(block)
        val textLines = block.text.lines()
        if (lineBoxes.size < 2 || textLines.size != lineBoxes.size) return listOf(block)
        // 按「行中心落在第几个缝区间」分组；行本身跨缝时归到下方（与 reading order 一致）。
        val groups = linkedMapOf<Int, MutableList<Int>>()
        lineBoxes.forEachIndexed { index, box ->
            val centerY = (box[1] + box[3]) / 2f
            val segment = seams.count { centerY >= it }
            groups.getOrPut(segment) { mutableListOf() }.add(index)
        }
        if (groups.size <= 1) return listOf(block)
        return groups.values.map { indices ->
            val boxes = indices.map { lineBoxes[it] }
            block.copy(
                text = indices.joinToString("\n") { textLines[it] },
                left = boxes.minOf { it[0] },
                top = boxes.minOf { it[1] },
                right = boxes.maxOf { it[2] },
                bottom = boxes.maxOf { it[3] },
                lineCount = boxes.size,
                lineBoxes = boxes
            )
        }
    }
}
