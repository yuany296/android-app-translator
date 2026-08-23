package com.manga.translator.app

import com.manga.translator.translate.Bubble
import com.manga.translator.translate.BubbleGeometry
import com.manga.translator.translate.BubbleMergeHeuristics
import com.manga.translator.translate.BubbleMergeScenario
import com.manga.translator.translate.BubbleRegion
import com.manga.translator.translate.BubbleRegionType
import com.manga.translator.translate.TextBlock
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class BubbleFirstRegionPlanner {
    fun buildBubblesFromAssignment(
        assignment: BubbleRegionAssignment,
        imageWidth: Int,
        imageHeight: Int,
        separatedByWhitespace: ((TextBlock, TextBlock) -> Boolean)? = null
    ): List<Bubble> {
        if (assignment.blocks.isEmpty()) return emptyList()
        val groups = splitRegionBlocks(
            region = assignment.region,
            blocks = assignment.blocks,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            separatedByWhitespace = separatedByWhitespace
        )
        return groups.mapIndexedNotNull { index, group ->
            val targetRegion = when {
                assignment.region.regionType == BubbleRegionType.OUTSIDE -> assignment.region
                groups.size <= 1 -> assignment.region
                else -> buildBubbleSubRegion(
                    parentRegion = assignment.region,
                    blocks = group,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    suffix = index
                )
            }
            buildBubbleFromBlocks(
                region = targetRegion,
                blocks = group,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )
        }
    }

    fun route(
        bubbleRegions: List<BubbleRegion>,
        textBlocks: List<TextBlock>,
        imageWidth: Int,
        imageHeight: Int,
        separatedByWhitespace: ((TextBlock, TextBlock) -> Boolean)? = null
    ): BubbleFirstRouting {
        if (textBlocks.isEmpty()) {
            return BubbleFirstRouting(
                bubbleAssignments = bubbleRegions.map { BubbleRegionAssignment(it, emptyList()) },
                outsideAssignments = emptyList(),
                outsideBlocks = emptyList()
            )
        }

        val indexedAssignments = bubbleRegions.associateBy { it.id }.mapValues { mutableListOf<TextBlock>() }.toMutableMap()
        val outsideBlocks = mutableListOf<TextBlock>()
        textBlocks.forEach { block ->
            val matched = bubbleRegions
                .mapNotNull { region ->
                    val score = matchScore(region, block)
                    if (score <= 0f) null else region to score
                }
                .maxByOrNull { it.second }
                ?.first
            if (matched != null) {
                indexedAssignments.getOrPut(matched.id) { mutableListOf() } += block
            } else {
                outsideBlocks += block
            }
        }

        val bubbleAssignments = bubbleRegions.map { region ->
            val members = indexedAssignments[region.id].orEmpty()
                .sortedWith(compareBy<TextBlock> { it.top }.thenBy { it.left })
            BubbleRegionAssignment(region = region, blocks = members)
        }

        val outsideAssignments = buildOutsideAssignments(
            outsideBlocks = outsideBlocks,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            separatedByWhitespace = separatedByWhitespace
        )
        return BubbleFirstRouting(
            bubbleAssignments = bubbleAssignments,
            outsideAssignments = outsideAssignments,
            outsideBlocks = outsideBlocks
        )
    }

    fun buildBubbleFromBlocks(
        region: BubbleRegion,
        blocks: List<TextBlock>,
        imageWidth: Int,
        imageHeight: Int
    ): Bubble? {
        if (blocks.isEmpty()) return null
        val rawLeft = blocks.minOf { it.left }.coerceIn(0f, imageWidth.toFloat())
        val rawTop = blocks.minOf { it.top }.coerceIn(0f, imageHeight.toFloat())
        val rawRight = blocks.maxOf { it.right }.coerceIn(0f, imageWidth.toFloat())
        val rawBottom = blocks.maxOf { it.bottom }.coerceIn(0f, imageHeight.toFloat())
        val verticalLayout = shouldUseVerticalLayout(region, blocks)
        val orderedText = if (verticalLayout) {
            blocks.sortedWith(compareByDescending<TextBlock> { it.centerX() }.thenBy { it.top })
                .joinToString(separator = "") { it.text.trim().replace("\n", "") }
        } else {
            blocks.sortedWith(compareBy<TextBlock> { it.top }.thenBy { it.left })
                .joinToString(separator = "\n") { it.text.trim() }
        }.trim()
        if (orderedText.isBlank()) return null

        val rect = if (region.regionType == BubbleRegionType.BUBBLE) {
            region
        } else {
            buildOutsideRegionRect(blocks, imageWidth, imageHeight, region.id)
        }
        return Bubble(
            x = (rect.left / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
            y = (rect.top / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
            w = ((rect.right - rect.left) / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
            h = ((rect.bottom - rect.top) / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
            bgType = if (region.regionType == BubbleRegionType.BUBBLE) "solid" else "none",
            originalText = orderedText,
            translatedText = "",
            layout = if (verticalLayout) "vertical" else "horizontal",
            contentX = (rawLeft / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
            contentY = (rawTop / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
            contentW = ((rawRight - rawLeft) / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
            contentH = ((rawBottom - rawTop) / imageHeight.toFloat() * 100f).coerceIn(0f, 100f)
        )
    }

    fun buildRemoteBlocksFromBubbles(
        region: BubbleRegion,
        bubbles: List<Bubble>,
        cropWidth: Int,
        cropHeight: Int
    ): List<TextBlock> {
        if (cropWidth <= 0 || cropHeight <= 0) return emptyList()
        return bubbles.mapIndexedNotNull { index, bubble ->
            val text = bubble.originalText.trim()
            if (text.isBlank()) return@mapIndexedNotNull null
            val contentRect = BubbleGeometry.contentRect(bubble)
            val left = region.left + (contentRect.left / 100f) * cropWidth.toFloat()
            val top = region.top + (contentRect.top / 100f) * cropHeight.toFloat()
            val right = region.left + (contentRect.right / 100f) * cropWidth.toFloat()
            val bottom = region.top + (contentRect.bottom / 100f) * cropHeight.toFloat()
            TextBlock(
                id = index,
                text = text,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                confidence = 0f
            )
        }
    }

    private fun shouldUseVerticalLayout(region: BubbleRegion, blocks: List<TextBlock>): Boolean {
        val verticalHintCount = blocks.count { isVerticalTextBlock(it) }
        val rawLeft = blocks.minOf { it.left }
        val rawTop = blocks.minOf { it.top }
        val rawRight = blocks.maxOf { it.right }
        val rawBottom = blocks.maxOf { it.bottom }
        val rawWidth = (rawRight - rawLeft).coerceAtLeast(1f)
        val rawHeight = (rawBottom - rawTop).coerceAtLeast(1f)
        val regionWidth = (region.right - region.left).coerceAtLeast(1f)
        val regionHeight = (region.bottom - region.top).coerceAtLeast(1f)
        val avgBlockWidth = blocks.map { it.widthPx() }.average().toFloat().coerceAtLeast(1f)
        val avgBlockHeight = blocks.map { it.heightPx() }.average().toFloat().coerceAtLeast(1f)
        val hasTallBlockMajority = verticalHintCount * 2 >= blocks.size
        val textStackLooksVertical = rawHeight >= rawWidth * 1.02f
        val blockAspectLooksVertical = avgBlockHeight >= avgBlockWidth * 1.15f
        val regionSupportsVertical = regionHeight >= regionWidth * 1.05f
        return when (region.regionType) {
            BubbleRegionType.BUBBLE -> {
                textStackLooksVertical || hasTallBlockMajority || (blockAspectLooksVertical && regionSupportsVertical)
            }

            BubbleRegionType.OUTSIDE -> {
                rawHeight >= rawWidth * 1.10f || hasTallBlockMajority
            }
        }
    }

    private fun matchScore(region: BubbleRegion, block: TextBlock): Float {
        val centerInside = block.centerX() in region.left..region.right && block.centerY() in region.top..region.bottom
        val overlapLeft = max(region.left, block.left)
        val overlapTop = max(region.top, block.top)
        val overlapRight = min(region.right, block.right)
        val overlapBottom = min(region.bottom, block.bottom)
        val overlapArea = if (overlapRight > overlapLeft && overlapBottom > overlapTop) {
            (overlapRight - overlapLeft) * (overlapBottom - overlapTop)
        } else {
            0f
        }
        val blockArea = block.areaPx().coerceAtLeast(1f)
        val overlapRatio = overlapArea / blockArea
        return when {
            centerInside -> 1f + overlapRatio
            overlapRatio >= MIN_REGION_OVERLAP_RATIO -> overlapRatio
            else -> 0f
        }
    }

    private fun buildOutsideAssignments(
        outsideBlocks: List<TextBlock>,
        imageWidth: Int,
        imageHeight: Int,
        separatedByWhitespace: ((TextBlock, TextBlock) -> Boolean)?
    ): List<BubbleRegionAssignment> {
        val groups = buildHeuristicTextBlockGroups(
            blocks = outsideBlocks,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            separatedByWhitespace = separatedByWhitespace
        )
        return groups.mapIndexed { index, group ->
            BubbleRegionAssignment(
                region = buildOutsideRegionRect(group, imageWidth, imageHeight, index),
                blocks = group
            )
        }
    }

    private fun buildOutsideRegionRect(
        blocks: List<TextBlock>,
        imageWidth: Int,
        imageHeight: Int,
        regionId: Int
    ): BubbleRegion {
        val rawLeft = blocks.minOf { it.left }.coerceIn(0f, imageWidth.toFloat())
        val rawTop = blocks.minOf { it.top }.coerceIn(0f, imageHeight.toFloat())
        val rawRight = blocks.maxOf { it.right }.coerceIn(0f, imageWidth.toFloat())
        val rawBottom = blocks.maxOf { it.bottom }.coerceIn(0f, imageHeight.toFloat())
        val rawWidth = max(1f, rawRight - rawLeft)
        val rawHeight = max(1f, rawBottom - rawTop)
        val padX = max(OUTSIDE_BASE_GAP_PX, rawWidth * OUTSIDE_PADDING_RATIO)
        val padY = max(OUTSIDE_BASE_GAP_PX, rawHeight * OUTSIDE_PADDING_RATIO)
        return BubbleRegion(
            id = regionId,
            left = (rawLeft - padX).coerceIn(0f, imageWidth.toFloat()),
            top = (rawTop - padY).coerceIn(0f, imageHeight.toFloat()),
            right = (rawRight + padX).coerceIn(1f, imageWidth.toFloat()),
            bottom = (rawBottom + padY).coerceIn(1f, imageHeight.toFloat()),
            regionType = BubbleRegionType.OUTSIDE,
            confidence = 1f,
            source = "outside_group"
        )
    }

    private fun buildBubbleSubRegion(
        parentRegion: BubbleRegion,
        blocks: List<TextBlock>,
        imageWidth: Int,
        imageHeight: Int,
        suffix: Int
    ): BubbleRegion {
        val rawLeft = blocks.minOf { it.left }.coerceIn(0f, imageWidth.toFloat())
        val rawTop = blocks.minOf { it.top }.coerceIn(0f, imageHeight.toFloat())
        val rawRight = blocks.maxOf { it.right }.coerceIn(0f, imageWidth.toFloat())
        val rawBottom = blocks.maxOf { it.bottom }.coerceIn(0f, imageHeight.toFloat())
        val rawWidth = max(1f, rawRight - rawLeft)
        val rawHeight = max(1f, rawBottom - rawTop)
        val padX = max(BUBBLE_SPLIT_BASE_GAP_PX, rawWidth * BUBBLE_SPLIT_PADDING_X)
        val padY = max(BUBBLE_SPLIT_BASE_GAP_PX, rawHeight * BUBBLE_SPLIT_PADDING_Y)
        return BubbleRegion(
            id = parentRegion.id * 10 + suffix,
            left = (rawLeft - padX).coerceIn(parentRegion.left, parentRegion.right),
            top = (rawTop - padY).coerceIn(parentRegion.top, parentRegion.bottom),
            right = (rawRight + padX).coerceIn(parentRegion.left, parentRegion.right),
            bottom = (rawBottom + padY).coerceIn(parentRegion.top, parentRegion.bottom),
            regionType = BubbleRegionType.BUBBLE,
            confidence = parentRegion.confidence,
            source = "${parentRegion.source}_split"
        )
    }

    private fun buildHeuristicTextBlockGroups(
        blocks: List<TextBlock>,
        imageWidth: Int,
        imageHeight: Int,
        separatedByWhitespace: ((TextBlock, TextBlock) -> Boolean)?
    ): List<List<TextBlock>> {
        if (blocks.isEmpty()) return emptyList()
        val sortedBlocks = blocks.sortedWith(compareBy<TextBlock> { it.left }.thenBy { it.top })
        val parent = IntArray(sortedBlocks.size) { it }

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
            if (pa != pb) {
                parent[pb] = pa
            }
        }

        for (i in sortedBlocks.indices) {
            for (j in (i + 1) until sortedBlocks.size) {
                if (shouldMergeLocalBlocks(sortedBlocks[i], sortedBlocks[j], imageWidth, imageHeight, separatedByWhitespace)) {
                    union(i, j)
                }
            }
        }

        val grouped = linkedMapOf<Int, MutableList<TextBlock>>()
        sortedBlocks.forEachIndexed { index, block ->
            val root = find(index)
            grouped.getOrPut(root) { mutableListOf() }.add(block)
        }
        return grouped.values.map { members ->
            members.sortedWith(compareBy<TextBlock> { it.top }.thenBy { it.left })
        }
    }

    private fun splitRegionBlocks(
        region: BubbleRegion,
        blocks: List<TextBlock>,
        imageWidth: Int,
        imageHeight: Int,
        separatedByWhitespace: ((TextBlock, TextBlock) -> Boolean)?
    ): List<List<TextBlock>> {
        if (blocks.size <= 1 || region.regionType != BubbleRegionType.BUBBLE) {
            return listOf(blocks.sortedWith(compareBy<TextBlock> { it.top }.thenBy { it.left }))
        }
        val sortedBlocks = blocks.sortedWith(compareBy<TextBlock> { it.left }.thenBy { it.top })
        val parent = IntArray(sortedBlocks.size) { it }

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
            if (pa != pb) {
                parent[pb] = pa
            }
        }

        for (i in sortedBlocks.indices) {
            for (j in (i + 1) until sortedBlocks.size) {
                if (shouldMergeBlocksInBubbleRegion(
                        sortedBlocks[i],
                        sortedBlocks[j],
                        region,
                        imageWidth,
                        imageHeight,
                        separatedByWhitespace
                    )
                ) {
                    union(i, j)
                }
            }
        }

        val grouped = linkedMapOf<Int, MutableList<TextBlock>>()
        sortedBlocks.forEachIndexed { index, block ->
            grouped.getOrPut(find(index)) { mutableListOf() }.add(block)
        }
        return grouped.values.map { members ->
            members.sortedWith(compareBy<TextBlock> { it.top }.thenBy { it.left })
        }
    }

    private fun shouldMergeLocalBlocks(
        a: TextBlock,
        b: TextBlock,
        imageWidth: Int,
        imageHeight: Int,
        separatedByWhitespace: ((TextBlock, TextBlock) -> Boolean)?
    ): Boolean {
        if (separatedByWhitespace?.invoke(a, b) == true) return false
        return BubbleMergeHeuristics.shouldMerge(
            a = BubbleGeometry.mergeCandidate(a),
            b = BubbleGeometry.mergeCandidate(b),
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            scenario = BubbleMergeScenario.GENERIC_TEXT
        ).merge
    }

    private fun shouldMergeBlocksInBubbleRegion(
        a: TextBlock,
        b: TextBlock,
        region: BubbleRegion,
        imageWidth: Int,
        imageHeight: Int,
        separatedByWhitespace: ((TextBlock, TextBlock) -> Boolean)?
    ): Boolean {
        if (separatedByWhitespace?.invoke(a, b) == true) return false
        val decision = BubbleMergeHeuristics.shouldMerge(
            a = BubbleGeometry.mergeCandidate(a),
            b = BubbleGeometry.mergeCandidate(b),
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            scenario = BubbleMergeScenario.WITHIN_BUBBLE
        )
        if (!decision.merge) return false
        val regionWidth = (region.right - region.left).coerceAtLeast(1f)
        val regionHeight = (region.bottom - region.top).coerceAtLeast(1f)
        val horizontalSeparation = max(0f, max(a.left, b.left) - min(a.right, b.right))
        val verticalSeparation = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))
        if (horizontalSeparation > regionWidth * 0.16f) return false
        if (verticalSeparation > regionHeight * 0.12f && !(isVerticalTextBlock(a) || isVerticalTextBlock(b))) {
            return false
        }
        return true
    }

    private fun isVerticalTextBlock(block: TextBlock): Boolean {
        return block.heightPx() >= block.widthPx() * 1.15f || block.text.trim().length <= 2
    }

    private fun TextBlock.widthPx(): Float = (right - left).coerceAtLeast(1f)

    private fun TextBlock.heightPx(): Float = (bottom - top).coerceAtLeast(1f)

    private fun TextBlock.areaPx(): Float = widthPx() * heightPx()

    private fun TextBlock.centerX(): Float = (left + right) / 2f

    private fun TextBlock.centerY(): Float = (top + bottom) / 2f

    private companion object {
        private const val MIN_REGION_OVERLAP_RATIO = 0.18f
        private const val OUTSIDE_BASE_GAP_PX = 8f
        private const val OUTSIDE_PADDING_RATIO = 0.10f
        private const val BUBBLE_SPLIT_BASE_GAP_PX = 8f
        private const val BUBBLE_SPLIT_PADDING_X = 0.26f
        private const val BUBBLE_SPLIT_PADDING_Y = 0.20f
    }
}

data class BubbleFirstRouting(
    val bubbleAssignments: List<BubbleRegionAssignment>,
    val outsideAssignments: List<BubbleRegionAssignment>,
    val outsideBlocks: List<TextBlock>
)

data class BubbleRegionAssignment(
    val region: BubbleRegion,
    val blocks: List<TextBlock>
)
