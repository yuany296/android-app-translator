package com.manga.translator.app

import com.manga.translator.translate.Bubble
import com.manga.translator.translate.BubbleGeometry
import com.manga.translator.translate.BubbleRegion
import com.manga.translator.translate.BubbleRegionType
import com.manga.translator.translate.OcrBlockMergeRules
import com.manga.translator.translate.OcrMergeDecision
import com.manga.translator.translate.TextBlock
import com.manga.translator.translate.TextLineGeometry
import kotlin.math.max
import kotlin.math.min

/** 「接近但被拒」的合并不了对：`gap ≤ 阈值 × [NEAR_MISS_GAP_FACTOR]` 但最终没合并。 */
data class BlockMergeReject(
    val a: TextBlock,
    val b: TextBlock,
    val decision: OcrMergeDecision
)

class BubbleFirstRegionPlanner {

    /**
     * 「接近但被拒」的合并出对汇（诊断用）。
     *
     * 为什么要有：用户报「两个气泡被并成一段」时，光看 `final=N` 分不清是
     * 「判据拒绝了」还是「压根没判定」；这些出对带着判决原因与几何，
     * 由调用方（能打 Android 日志的地方）汇总成**一行**日志。
     */
    private var mergeRejectSink: MutableList<BlockMergeReject>? = null

    /** 收集合并没有被采纳、但间隙接近上限的出对；传 null 关闭（默认）。 */
    fun setMergeDiagnostics(sink: MutableList<BlockMergeReject>?) {
        mergeRejectSink = sink
    }

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
        // 竖排判定的**原因**作为数据随 Bubble 带出（`layoutReason` 字段），而不是打 Android 日志：
        //
        // 这个类是纯 Kotlin / 可 JVM 单测的（`app/src/test` 直接调它），一旦使用
        // `android.util.Log` 就会让所有单测抛 `Method d in android.util.Log not mocked`
        // —— 实测踩到（7 个单测一起挂）。所以把原因带出去，由 `WebtoonImageTranslator`
        // （可以打日志的地方）记录，排障时能看到「为什么判成竖排」。
        val verticalReason = verticalLayoutReason(region, blocks)
        val verticalLayout = verticalReason != VERTICAL_REASON_NONE
        val orderedText = if (verticalLayout) {
            blocks.sortedWith(compareByDescending<TextBlock> { it.centerX() }.thenBy { it.top })
                .joinToString(separator = "") { it.text.trim().replace("\n", "") }
        } else {
            // 横排多行用空格连接成完整句子（之前用 \n 会把换行带进原文/译文，
            // ML Kit 逐行翻译时表现很怪；排版交给渲染侧按气泡宽度自动换行）。
            blocks.sortedWith(compareBy<TextBlock> { it.top }.thenBy { it.left })
                .joinToString(separator = " ") { it.text.trim() }
        }.trim()
        if (orderedText.isBlank()) return null

        val rect = if (region.regionType == BubbleRegionType.BUBBLE) {
            region
        } else {
            buildOutsideRegionRect(blocks, imageWidth, imageHeight, region.id)
        }
        val quad = com.manga.translator.translate.TextQuadGeometry.quadOf(blocks, imageWidth, imageHeight)
        return Bubble(
            x = (rect.left / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
            y = (rect.top / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
            w = ((rect.right - rect.left) / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
            h = ((rect.bottom - rect.top) / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
            bgType = if (isSolidBubbleRegion(region, rawLeft, rawTop, rawRight, rawBottom)) "solid" else "none",
            originalText = orderedText,
            translatedText = "",
            layout = if (verticalLayout) "vertical" else "horizontal",
            // 判定原因（columns / narrow_block / block_aspect / 空=横排）：
            // 这个类不能打 `android.util.Log`（会让单测抛 not-mocked），
            // 所以把原因作为数据带出，由 WebtoonImageTranslator 在日志里记录。
            layoutReason = verticalReason,
            contentX = (rawLeft / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
            contentY = (rawTop / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
            contentW = ((rawRight - rawLeft) / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
            contentH = ((rawBottom - rawTop) / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
            // 原文对齐（左/居中/右）由逐行文字框推断，渲染译文时沿用（2026-09-28 用户要求）
            alignment = com.manga.translator.translate.TextAlignment.infer(blocks, imageWidth.toFloat()),
            // 原文行数：**视觉行数**（同一行被检测器切成多块时只算一行 —— 用块数会把
            // 原字高估小，译文跟着变小；2026-09-28 用户反馈「字号感觉小了」）。
            // 竖排按 1 行处理，竖排字高跟框宽走。
            sourceLineCount = if (verticalLayout) 1
            else com.manga.translator.translate.TextLayoutMetrics.countVisualLines(blocks),
            // 文字四边形 + 倾角（2026-09-29）：像素 → 百分比在此完成，渲染层只吃百分比。
            // 多框合并出的块一定是轴对齐的，[TextQuadGeometry] 只在单框时透传。
            polygon = quad.first,
            rotationDeg = quad.second
        )
    }

    /**
     * 这个区域能不能当「实底气泡」用（= 盖板允许用采样底色**纯色平涂**）。
     *
     * ⚠️ 判据不能只看 `regionType == BUBBLE`（2026-09-30 修）。
     *
     * 背景：区域检测器（[com.manga.translator.overlay.BubbleRegionDetector]）是「亮斑轮廓」法
     * —— 阈值 208 找亮块。漫画里**整片浅色/渐变**的页面（天空、白背景、浅色云）本身就会
     * 被检出一个巨大的亮块，于是「区域」和真正需要翻译的文字毫无关系。
     *
     * 实测（`logs/h2-tilt.logcat`，900x1400 的渐变页 + 20° 斜排韩文）：
     * 斜排文字在 `y 929..1066`，却被分到一个 `y 981..1399` 的区域（**区域根本不含文字**）。
     * 旧判据一律给 `solid` → 盖板用采样底色 `#ACBED6` 纯色平涂，
     * 而那一带的背景其实是平滑渐变（`#A8BCD5` → `#B2C3DA`）→ 画面上出现一块肉眼可见的
     * 平行四边形色块。用户原话：「倾斜文本的清理图也消失了」。
     *
     * 正确语义（与参考项目 C:\homework\translator 一致）：拿不到**可信容器**时一律退到
     * `none`（走清理图 / inpaint 背景），绝不画纯色补丁 —— 参考项目里
     * `bgType = adaptiveBackground ? … : "none"`，而 `adaptiveBackground` 只在文字真的落在
     * 一个可信容器里时才有值。
     *
     * 判据：区域框必须**真的把文字框包住**（允许 [REGION_CONTAIN_SLACK_PX] 像素的容差，
     * 因为检测器的 pad 与 OCR 边界口径不完全一致）。真实气泡的区域 = 轮廓外扩 6%，
     * 天然满足；上面那种「整页亮块」必然不满足。
     *
     * 这条判据同时修掉了「斜排译文旁边多一块色板」和「旁白/拟声词底下有方块」两类现象，
     * 因为两者都源自「假阳性区域 + 无脑纯色平涂」。
     */
    private fun isSolidBubbleRegion(
        region: BubbleRegion,
        rawLeft: Float,
        rawTop: Float,
        rawRight: Float,
        rawBottom: Float
    ): Boolean {
        if (region.regionType != BubbleRegionType.BUBBLE) return false
        val slack = REGION_CONTAIN_SLACK_PX
        return region.left <= rawLeft + slack &&
            region.top <= rawTop + slack &&
            region.right >= rawRight - slack &&
            region.bottom >= rawBottom - slack
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

    /**
     * 这个区域要不要按**竖排**排版。
     *
     * ## 为什么改判据（2026-09-30 用户反馈）
     *
     * 用户截图：横排三行韩文（`그 용을 사냥하는 기술을 / 가르칠 수 있는 유일한 NPC가 /
     * 눈 앞의 여자야이라고 했다`）被排成了竖排，译文变成一列一列从上往下念。
     *
     * 关键线索是**「Paddle 有、ML Kit 没有」**：Paddle 倾向把整段合成**少数几个大块**，
     * 于是旧的 `hasTallBlockMajority`（`verticalHintCount * 2 >= blocks.size`）
     * 在**只有 1 个块**时恒真 —— 一个「高块」就能把整段判成竖排；ML Kit 逐行返回小框，
     * 反倒不会触发。**这就是 provider 差异的来源。**
     *
     * ## 新判据：看**块的排布关系**，不看单个块的胖瘦
     *
     * - 竖排的块是「**X 方向并排、Y 方向重叠**」：相邻两列横向几乎不重叠，纵向跨度高度重叠；
     * - 横排多行是「**Y 方向堆叠、X 方向重叠**」：相邻两行横向跨度高度重叠，纵向分离。
     *
     * 这是**纯几何、与语言无关**的判据 —— 对韩文竖排（标题/招牌/竖写拟声词）和
     * 日文竖排一视同仁，不会为了修横排而牺牲真竖排。
     *
     * ⚠️ 曾考虑按源语言关掉竖排判定，**已否决**：韩文里也有竖排，一刀切会弄坏它；
     * 而且渲染层 `mt-overlay-renderer.js` 早有「韩文→不竖排」的判断却没拦住本问题，
     * 说明按语言砍是重复且无效的思路。
     */
    private fun verticalLayoutReason(region: BubbleRegion, blocks: List<TextBlock>): String {
        val regionWidth = (region.right - region.left).coerceAtLeast(1f)
        val regionHeight = (region.bottom - region.top).coerceAtLeast(1f)
        val regionSupportsVertical = regionHeight >= regionWidth * 1.05f

        // 主判据：块构成「并排的列」。
        if (hasBlockColumns(blocks)) return "columns"

        // 单块：必须是「高瘦的窄列」**且**区域本身竖长（双条件）。
        // 旧的「1 个高块即竖排」正是误判来源（`1*2 >= 1` 恒真），这里要求窄列的
        // 宽高比足够强（真竖排一列通常 ≥ 2.0；三行横排的宽框远达不到）。
        if (blocks.size == 1) {
            return if (isVerticalTextBlock(blocks.first()) && regionSupportsVertical) "narrow_block"
            else VERTICAL_REASON_NONE
        }

        // 多块但不成列：只有「块整体高瘦」且「区域竖长」时才退让为竖排。
        val avgBlockWidth = blocks.map { it.widthPx() }.average().toFloat().coerceAtLeast(1f)
        val avgBlockHeight = blocks.map { it.heightPx() }.average().toFloat().coerceAtLeast(1f)
        return if (avgBlockHeight >= avgBlockWidth * 1.5f && regionSupportsVertical) "block_aspect"
        else VERTICAL_REASON_NONE
    }

    /**
     * 这些块是否构成「**并排的竖列**」—— 竖排的真正形态。
     *
     * 判据（对块对，任一满足即算）：
     * - 横向几乎不重叠：`xOverlap <= min(wA, wB) * COLUMN_X_OVERLAP_MAX`；
     * - 纵向高度重叠：`yOverlap >= min(hA, hB) * COLUMN_Y_OVERLAP_MIN`。
     *
     * 反例（用户那个 bug）：三行横排的三个块横向跨度几乎相同（xOverlap≈minW）、
     * 纵向分离（yOverlap≈0）→ **不满足**，正确地判为横排。
     *
     * 正例（真竖排）：两个窄列并排，`xOverlap≈0`、`yOverlap≈minH` → 满足。
     */
    private fun hasBlockColumns(blocks: List<TextBlock>): Boolean {
        if (blocks.size < 2) return false
        for (i in blocks.indices) {
            for (j in (i + 1) until blocks.size) {
                if (looksLikeColumnPair(blocks[i], blocks[j])) return true
            }
        }
        return false
    }

    /**
     * 一对块是否构成「**并排的竖列**」。
     *
     * 两个条件同时成立（与 `PaddleOcrBlockMerger.looksLikeColumnPair` 同口径）：
     * 1. **横向分开**：`xOverlap < minW * COLUMN_X_OVERLAP_MAX`；
     * 2. **合起来竖长**：这一对的外接框 `height >= width * COLUMN_PAIR_ASPECT`。
     *
     * 第 2 条是关键，它一次性排除「同一行的左右碎片」（合起来是宽的）。
     * 只看横向间隔不够：真竖排相邻两列可能只隔 10px，横排同行碎片也可能隔 20px。
     */
    private fun looksLikeColumnPair(a: TextBlock, b: TextBlock): Boolean {
        val minW = min(a.widthPx(), b.widthPx()).coerceAtLeast(1f)
        val xOverlap = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
        if (xOverlap >= minW * COLUMN_X_OVERLAP_MAX) return false
        val unionWidth = (max(a.right, b.right) - min(a.left, b.left)).coerceAtLeast(1f)
        val unionHeight = (max(a.bottom, b.bottom) - min(a.top, b.top)).coerceAtLeast(1f)
        return unionHeight >= unionWidth * COLUMN_PAIR_ASPECT
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
        if (crossesSeamBoundary(a, b)) return false
        if (isGutterSeparated(a, b, separatedByWhitespace)) return false
        return decideMerge(a, b, sameKnownRegion = false)
    }

    private fun shouldMergeBlocksInBubbleRegion(
        a: TextBlock,
        b: TextBlock,
        region: BubbleRegion,
        imageWidth: Int,
        imageHeight: Int,
        separatedByWhitespace: ((TextBlock, TextBlock) -> Boolean)?
    ): Boolean {
        if (crossesSeamBoundary(a, b)) return false
        if (isGutterSeparated(a, b, separatedByWhitespace)) return false
        if (!decideMerge(a, b, sameKnownRegion = true)) return false
        val regionWidth = (region.right - region.left).coerceAtLeast(1f)
        val horizontalSeparation = max(0f, max(a.left, b.left) - min(a.right, b.right))
        val verticalSeparation = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))
        if (horizontalSeparation > regionWidth * 0.16f) return false
        // 行距超过 1.1 倍**字高**视为不同段（此前按 `max(块高) × 1.2` 判定：
        // 多行段落块的块高会把上限放大数倍，同一气泡里的两段就会被并成一段）。
        // 行距阈值与 `OcrBlockMergeRules.LINE_GAP_MAX_RATIO` 同口径。
        val avgLineHeight = TextLineGeometry.averageLineHeight(a, b)
        if (verticalSeparation > avgLineHeight * OcrBlockMergeRules.LINE_GAP_MAX_RATIO &&
            !(isVerticalTextBlock(a) || isVerticalTextBlock(b))
        ) {
            return false
        }
        return true
    }

    /**
     * 收严后的合并判决（2026-10-03）：统一走 [OcrBlockMergeRules]，并把
     * 「接近但被拒」的出对交给诊断汇（若有）。
     *
     * 旧实现（`BubbleMergeHeuristics`）的阈值以**块高**为单位，多行段落块会把上限
     * 放大数倍 —— 真机现场两个独立气泡的间隙只有 50px，却被算成远小于上限，
     * 于是并成一句、一块盖板。详见 [OcrBlockMergeRules] 的类注释。
     */
    private fun decideMerge(a: TextBlock, b: TextBlock, sameKnownRegion: Boolean): Boolean {
        val decision = OcrBlockMergeRules.shouldMerge(a, b, sameKnownRegion = sameKnownRegion)
        if (!decision.merge) {
            mergeRejectSink?.let { sink ->
                if (decision.gapPx <= decision.lineHeightPx * OcrBlockMergeRules.LINE_GAP_MAX_RATIO * NEAR_MISS_GAP_FACTOR) {
                    sink += BlockMergeReject(a, b, decision)
                }
            }
        }
        return decision.merge
    }

    /**
     * **缝感知**：两块分处切缝两侧时，默认**不合并**（2026-10-03）。
     *
     * 现场（Lezhin，`.verify/38_user.logcat`）：加高下条带之后，条带里同时能看到**上一气泡**的
     * 尾行和**下一气泡**的首行，而这两块在几何上确实挨得很近（两个气泡轮廓几乎相接）→ 纯邻近
     * 合并把它们并成一个气泡，`erase_target` 里就出现
     * `…두 가지 밖에 없는데.! 악질들한테 걸리기라도 …`（两个气泡的台词拼成一句），
     * 用户看到的就是「那两段合并成一块了」。
     *
     * 参考项目靠**气泡区域**（`region_id` + `sharedSpeechBubble`）区分；我们这一页
     * `regions=0`（气泡区域没命中）→ 只能靠邻近度，于是并错。而我们知道一个参考项目没有的
     * 确定事实：**页面切缝**。所以：缝 = 段落边界；只有「几乎贴着缝」（间隙 ≤ [seamCutGracePx]）
     * 才算同一行被切开 —— 真被切开的那半行交给归属/配对层处理（那边有自己的判据），
     * 这里不合并不会丢东西。
     *
     * 2026-10-03 补**上边界**（条带与本图之间，[seamTopBoundaryPx]）：拼接图上方那条
     * `topH` 条带属于**上一张图**，它里面的字与本图的字**永远不是同一段** —— 真机
     * `[32317270]` 就是这么并出一行 `방해됐단양이o를 하려면 죽거나` 的（前半在条带里、
     * 后半在本图顶部），随后整块被当成「上一张已画过」丢弃，本图那半行既不擦也不翻。
     * 上边界**不设余量**：下边界的余量是为「被切开的一行」准备的，上边界没有这种情形。
     */
    private fun crossesSeamBoundary(a: TextBlock, b: TextBlock): Boolean {
        if (crossesTopBandBoundary(a, b)) return true
        val seam = seamBoundaryPx
        if (seam <= 0f) return false
        val aAbove = a.bottom <= seam
        val bAbove = b.bottom <= seam
        if (aAbove == bAbove) return false
        val upper = if (aAbove) a else b
        val lower = if (aAbove) b else a
        val gap = lower.top - upper.bottom
        return gap > seamCutGracePx
    }

    /** 两块分处「上一张图的条带」与本图之间 → 永不合并（上边界没有「被切开的一行」这种情形）。 */
    private fun crossesTopBandBoundary(a: TextBlock, b: TextBlock): Boolean {
        val top = seamTopBoundaryPx
        if (top <= 0f) return false
        val aInBand = a.bottom <= top
        val bInBand = b.bottom <= top
        return aInBand != bInBand
    }

    /**
     * 设置**页面切缝**（拼接图像素）与本图允许的「切开余量」；两块分处两侧且间隙大于余量时
     * 不再合并。`0` = 关闭（悬浮球链路不拼接邻居图，保持原行为）。
     *
     * 调用方必须在用完复位（[setSeamBoundaries]`(0f, 0f, 0f)`），否则会把上一次的缝带给别的图。
     */
    fun setSeamBoundary(boundaryPx: Float, cutGracePx: Float) {
        setSeamBoundaries(topBoundaryPx = 0f, bottomBoundaryPx = boundaryPx, cutGracePx = cutGracePx)
    }

    /**
     * 同时设置**上边界**（拼接图里本图的起点 = `topH`，条带与本图之间）与**下边界**。
     *
     * 上边界两侧的文字属于不同的图，永不合并（[crossesTopBandBoundary]）；下边界保留
     * 「贴着缝仍可合并」的余量，供被切开的一行接回。
     */
    fun setSeamBoundaries(topBoundaryPx: Float, bottomBoundaryPx: Float, cutGracePx: Float) {
        seamTopBoundaryPx = topBoundaryPx
        seamBoundaryPx = bottomBoundaryPx
        seamCutGracePx = cutGracePx
    }

    private var seamTopBoundaryPx: Float = 0f
    private var seamBoundaryPx: Float = 0f
    private var seamCutGracePx: Float = 0f

    /**
     * 白色空隙分隔判定：仅当空隙足够大（≥1.5 倍字高，真实页距/面板间距）时才视为分隔。
     * 同一段落/气泡的行间空隙通常只有 0.3~0.8 倍字高，白色空隙检测会把相邻行误判为
     * 分隔，导致 ML Kit 逐行翻译（译文很怪）。
     */
    private fun isGutterSeparated(
        a: TextBlock,
        b: TextBlock,
        separatedByWhitespace: ((TextBlock, TextBlock) -> Boolean)?
    ): Boolean {
        if (separatedByWhitespace == null) return false
        val hGap = max(0f, max(a.left, b.left) - min(a.right, b.right))
        val vGap = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))
        val minH = min(a.heightPx(), b.heightPx()).coerceAtLeast(1f)
        if (vGap >= hGap && vGap < minH * GUTTER_MIN_GAP_RATIO) return false
        return separatedByWhitespace(a, b)
    }

    /**
     * 单个块是否「**高瘦的窄列**」—— 竖排的形态。
     *
     * 两处收紧（2026-09-30）：
     * 1. **删掉 `text.trim().length <= 2`**：短词在横排版式里极其常见（韩文助词、
     *    拟声词、`?!`），按字数判竖排会把它们误判 —— 而这个判据还是**语言无关**的，
     *    对日文同样危险。字数是「内容」属性，不该拿来推「排版方向」。
     * 2. 宽高比 `1.15 → 2.0`：真竖排一列通常 ≥ 2.0；三行横排的宽框（约 1.5~2.0）
     *    在旧阈值下会被误判。
     *
     * ⚠️ 这里**不看源语言**：韩文里也有竖排（标题/招牌/竖写拟声词），
     * 按语言一刀切会把它弄坏。方向只由几何与排布决定。
     */
    private fun isVerticalTextBlock(block: TextBlock): Boolean {
        return block.heightPx() >= block.widthPx() * VERTICAL_BLOCK_MIN_ASPECT
    }

    private fun TextBlock.widthPx(): Float = (right - left).coerceAtLeast(1f)

    private fun TextBlock.heightPx(): Float = (bottom - top).coerceAtLeast(1f)

    private fun TextBlock.areaPx(): Float = widthPx() * heightPx()

    private fun TextBlock.centerX(): Float = (left + right) / 2f

    private fun TextBlock.centerY(): Float = (top + bottom) / 2f

    companion object {
        /** 单块判竖排的最小高宽比（真竖排一列通常 ≥ 2.0）。 */
        private const val VERTICAL_BLOCK_MIN_ASPECT = 2.0f

        /**
         * 「并排成列」判据：一对块的横向重叠不得超过较窄块的这个比例。
         *
         * 0.25 的取值依据：同属一列的相邻两块（上下接续）横向几乎完全重合，
         * 但检测框常有几像素抖动；而横排不同行之间的横向重叠接近 1.0。
         */
        private const val COLUMN_X_OVERLAP_MAX = 0.25f

        /** 竖排判定「不是竖排」的原因值（= 横排）。 */
        private const val VERTICAL_REASON_NONE = ""

        /**
         * 「并排成列」判据：一对块合起来判为竖排的最小高宽比。
         *
         * 与 `PaddleOcrBlockMerger.GROUP_VERTICAL_ASPECT` 同值同义：
         * 真竖排两列合起来竖长（约 2.2），横排同行碎片合起来扁宽（约 0.3）。
         */
        private const val COLUMN_PAIR_ASPECT = 1.15f

        private const val MIN_REGION_OVERLAP_RATIO = 0.18f
        private const val OUTSIDE_BASE_GAP_PX = 8f
        private const val OUTSIDE_PADDING_RATIO = 0.10f
        private const val BUBBLE_SPLIT_BASE_GAP_PX = 8f
        private const val BUBBLE_SPLIT_PADDING_X = 0.26f
        private const val BUBBLE_SPLIT_PADDING_Y = 0.20f
        // 白色空隙只有在 ≥1.5 倍字高（真实页距）时才视为分隔
        private const val GUTTER_MIN_GAP_RATIO = 1.5f

        /**
         * 「区域包住文字」判定的容差（像素）。检测器给区域外扩的是 `max(6px, 6%)`，
         * OCR 文字框与轮廓口径略有出入，留几像素避免把真气泡误判成假阳性。
         */
        private const val REGION_CONTAIN_SLACK_PX = 4f

        /**
         * 「接近但被拒」的判定倍数：间隙不超过上限的这么多倍时才记进诊断。
         *
         * 取 2：真机现场那一对是 `gap=50 > 37`（1.35 倍）—— 正是最需要看见的形态；
         * 而间隙动辄十几倍行高的「显然不同气泡」不进日志，避免每页刷噪声。
         */
        private const val NEAR_MISS_GAP_FACTOR = 2.0f

        /**
         * 把「接近但被拒」的合并出对压成**一行**诊断（最多 [MAX_REPORTED] 条）。
         *
         * 放在这里而不是直接打日志：planner 是纯 Kotlin（单测直接调它，
         * 用 `android.util.Log` 会让单测抛 `Method d in android.util.Log not mocked`），
         * 由 `WebtoonImageTranslator` / `TranslateOrchestrator` 记录。
         */
        fun mergeRejectDiag(rejects: List<BlockMergeReject>, maxReported: Int = MAX_REPORTED): String? {
            if (rejects.isEmpty()) return null
            val sample = rejects.take(maxReported).joinToString(" | ") { reject ->
                "reason=${reject.decision.reason} gap=${reject.decision.gapPx.toInt()}" +
                    ">${(reject.decision.lineHeightPx * OcrBlockMergeRules.LINE_GAP_MAX_RATIO).toInt()}" +
                    " lh=${reject.decision.lineHeightPx.toInt()}" +
                    " vertical=${reject.decision.verticalFrame}" +
                    " a=${boxOf(reject.a)} b=${boxOf(reject.b)}"
            }
            return "pairs=${rejects.size} sample=[$sample]"
        }

        private fun boxOf(block: TextBlock): String =
            "${block.left.toInt()},${block.top.toInt()}," +
                "${(block.right - block.left).toInt()}x${(block.bottom - block.top).toInt()}"

        private const val MAX_REPORTED = 3
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
