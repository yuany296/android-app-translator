package com.manga.translator.app

import com.manga.translator.translate.NonTranslatableText
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 接缝「多碎片成组」（2026-10-27，对齐参考项目 `seam-grouping.js`）。
 *
 * 背景：现有 [SeamBubbleMerger] 只做「上块 + 下块」1:1 配对。一个气泡被切成 3 块以上
 * （多行气泡跨缝、列拆分、OCR 碎片）时，多出来的块只能 orphan/drop，表现为漏字或重复翻译。
 * 这里在 1:1 配对**之前**先把同一容器的碎片合成一个候选：
 *
 * 1. **region groups**：同一 `region_id` 且 `region_type=speech_bubble` 的 ≥2 块；
 * 2. **anonymous body groups**：没有容器 id 的正文行 ≥3 块，按行高/水平重叠/间隙/旋转兼容性
 *    做连通分量（参考 `buildAnonymousSeamBodyGroups`）。
 *
 * 两组都要求：
 * - **anchored**：至少一块跨缝，或同一侧带上同时存在「贴缝上方」与「贴缝下方」的块；
 * - [groupFitsSeamWindow]：整组高度 ≤ `maxCrossHeight`，相邻块间隙不超过自适应上限。
 *
 * 合并后 `sourceLineCount` 用**相加**（不是 max）：多行气泡的每一块各占一行。
 *
 * 纯几何 / 纯数据，不依赖 android.graphics，可直接 JVM 单测。
 */
internal object SeamFragmentGroups {

    const val REGION_TYPE_SPEECH = "speech_bubble"
    const val REGION_TYPE_PLAIN = "plain_text"

    /** region 组至少 2 块（参考项目 `buildSeamRegionCandidateGroups`）。 */
    const val MIN_REGION_FRAGMENTS = 2

    /** 匿名正文组至少 3 块（参考项目 `buildAnonymousSeamBodyGroups` 的硬门槛）。 */
    const val MIN_BODY_FRAGMENTS = 3

    /** 匿名正文行的置信度下限。 */
    const val MIN_BODY_CONFIDENCE = 0.8f

    /** 匿名正文行高度比下限（较矮 / 较高）。 */
    const val BODY_MIN_HEIGHT_RATIO = 0.7f

    /** 匿名正文行水平重叠下限（占较窄块的比例）。 */
    const val BODY_MIN_HORIZONTAL_OVERLAP = 0.4f

    /** 匿名正文行纵向间隙窗口（相对平均行高）。 */
    const val BODY_GAP_MIN_RATIO = -0.25f
    const val BODY_GAP_MAX_RATIO = 0.7f

    /** 匿名正文行旋转差上限（度）。 */
    const val BODY_MAX_ROTATION_DELTA_DEG = 12f

    /** 高字/大字 SFX：单行框最多占画布高度的这个比例（参考 `SEAM_CROSS_MAX_LINE_COVERAGE`）。 */
    const val MAX_LINE_COVERAGE = 0.9f

    /** 容器轮廓贡献的宽/面积上限（参考项目 `getSeamCandidateVisualContributionBox`）。 */
    const val MAX_VISUAL_WIDTH_COVERAGE = 0.9f
    const val MAX_VISUAL_AREA_COVERAGE = 0.6f

    /** 聊天时间（参考项目 `CHAT_TIME_RE` 的基础时钟形态）。 */
    private val CHAT_TIME = Regex("""(?:(?:오전|오후|上午|下午)\s*)?\d{1,2}:\d{2}""")

    enum class Kind(val verdict: String) {
        REGION("grouped_region"),
        BODY("grouped_body")
    }

    /** 一个成组结果：成员下标（原 [SeamDetection] 列表）+ 合并后的候选。 */
    data class Group(
        val kind: Kind,
        val indices: List<Int>,
        val detection: SeamDetection
    )

    /**
     * 构建本图所有可用的多碎片组。返回顺序确定（region 按 id 升序，body 按视觉起点）。
     */
    fun buildGroups(
        detections: List<SeamDetection>,
        config: SeamConfig,
        canvasWidth: Int,
        canvasHeight: Int
    ): List<Group> {
        if (detections.size < MIN_REGION_FRAGMENTS) return emptyList()
        val used = BooleanArray(detections.size)
        val groups = mutableListOf<Group>()

        // 1) 同容器（region_id）成组。
        detections.indices
            .filter { index -> !used[index] && isSpeechRegionCandidate(detections[index]) }
            .groupBy { index -> detections[index].bubble.regionId.orEmpty() }
            .toSortedMap()
            .forEach { (_, members) ->
                val indices = members.sorted()
                if (indices.size < MIN_REGION_FRAGMENTS) return@forEach
                val items = indices.map { detections[it] }
                if (!groupFitsSeamWindow(items, config)) return@forEach
                if (!isAnchored(items, config, canvasWidth, canvasHeight)) return@forEach
                groups += Group(
                    kind = Kind.REGION,
                    indices = indices,
                    detection = merge(items, canvasWidth, canvasHeight)
                )
                indices.forEach { used[it] = true }
            }

        // 2) 无 region_id 的正文行成组。
        val available = detections.indices
            .filter { index ->
                !used[index] &&
                    !isSpeechRegionCandidate(detections[index]) &&
                    isAnonymousBodyCandidate(detections[index])
            }
            .sortedWith(
                compareBy<Int> { index -> detections[index].outer.top }
                    .thenBy { index -> detections[index].outer.left }
                    .thenBy { it }
            )
        val pending = available.toMutableSet()
        while (pending.isNotEmpty()) {
            val start = pending.minOrNull() ?: break
            pending.remove(start)
            val component = mutableListOf(start)
            var cursor = 0
            while (cursor < component.size) {
                val current = detections[component[cursor]]
                pending.toList().forEach { index ->
                    if (areAnonymousLinesCompatible(current, detections[index])) {
                        pending.remove(index)
                        component += index
                    }
                }
                cursor += 1
            }
            if (component.size < MIN_BODY_FRAGMENTS) continue
            val indices = component.sorted()
            val items = indices.map { detections[it] }
            if (!groupFitsSeamWindow(items, config)) continue
            if (!isAnchored(items, config, canvasWidth, canvasHeight)) continue
            groups += Group(
                kind = Kind.BODY,
                indices = indices,
                detection = merge(items, canvasWidth, canvasHeight)
            )
            indices.forEach { used[it] = true }
        }
        return groups
    }

    /** 参考项目 `isSeamSpeechRegionCandidate`：有 region_id 且类型是气泡。 */
    fun isSpeechRegionCandidate(detection: SeamDetection): Boolean {
        val id = detection.bubble.regionId?.trim().orEmpty()
        if (id.isEmpty()) return false
        return detection.bubble.regionType?.trim()?.equals(REGION_TYPE_SPEECH, ignoreCase = true) == true
    }

    /** 参考项目 `isAnonymousSeamBodyCandidate`。 */
    fun isAnonymousBodyCandidate(detection: SeamDetection): Boolean {
        val bubble = detection.bubble
        val text = bubble.originalText.trim()
        if (text.isEmpty()) return false
        // 本阶段只把「没有容器 id 的正文行」当作匿名组；有 id 的走 region 组或 1:1。
        if (!bubble.regionId.isNullOrBlank()) return false
        if (isSpeechRegionCandidate(detection)) return false
        if (!bubble.confidence.isFinite() || bubble.confidence < MIN_BODY_CONFIDENCE) return false
        // Android 没有独立的 non_translate 字段，用现有「不值得翻」判据等价替代。
        if (NonTranslatableText.isNonTranslatable(text)) return false
        if (CHAT_TIME.containsMatchIn(text)) return false
        return true
    }

    /** 参考项目 `areAnonymousSeamLinesCompatible`。 */
    fun areAnonymousLinesCompatible(left: SeamDetection, right: SeamDetection): Boolean {
        if (!isAnonymousBodyCandidate(left) || !isAnonymousBodyCandidate(right)) return false
        val ordered = listOf(left, right).sortedWith(
            compareBy<SeamDetection> { it.outer.top }.thenBy { it.outer.left }
        )
        val upperBox = ordered[0].outer
        val lowerBox = ordered[1].outer
        if (upperBox.height <= 0f || lowerBox.height <= 0f) return false
        val averageHeight = max(1f, (upperBox.height + lowerBox.height) / 2f)
        val heightRatio = min(upperBox.height, lowerBox.height) /
            max(max(upperBox.height, lowerBox.height), 1f)
        if (heightRatio < BODY_MIN_HEIGHT_RATIO) return false
        val overlap = upperBox.horizontalOverlapRatio(lowerBox)
        if (overlap < BODY_MIN_HORIZONTAL_OVERLAP) return false
        val gap = lowerBox.top - upperBox.bottom
        val minGap = averageHeight * BODY_GAP_MIN_RATIO
        if (gap < minGap) return false
        if (gap > averageHeight * BODY_GAP_MAX_RATIO) return false
        val upperRotation = ordered[0].bubble.rotationDeg ?: 0f
        val lowerRotation = ordered[1].bubble.rotationDeg ?: 0f
        return abs(upperRotation - lowerRotation) <= BODY_MAX_ROTATION_DELTA_DEG
    }

    /**
     * 参考项目 `groupFitsSeamWindow`：整组高度 ≤ 上限，且按纵向排序后相邻块间隙
     * 不超过自适应上限（[resolveGroupMaxGap]）。
     */
    fun groupFitsSeamWindow(items: List<SeamDetection>, config: SeamConfig): Boolean {
        if (items.isEmpty()) return false
        val boxes = items.map { it.outer }
        val top = boxes.minOf { it.top }
        val bottom = boxes.maxOf { it.bottom }
        if (bottom - top > config.maxCrossHeightPx) return false
        val ordered = boxes.sortedWith(compareBy<SeamBox> { it.top }.thenBy { it.left })
        return ordered.indices.drop(1).all { index ->
            val previous = ordered[index - 1]
            val box = ordered[index]
            val gap = box.top - previous.bottom
            gap <= resolveGroupMaxGap(previous.height, box.height, config.maxCrossHeightPx)
        }
    }

    /** 成组窗口的自适应间隙（参考项目 `resolveSeamCrossPairMaxGap`）。 */
    fun resolveGroupMaxGap(upperHeight: Float, lowerHeight: Float, maxCrossHeight: Float): Float {
        val lineHeightSum = max(2f, upperHeight + lowerHeight)
        val adaptive = lineHeightSum * 0.8f
        val cap = max(SeamTuning.BASE_PAIR_MAX_GAP_PX, maxCrossHeight * 0.45f)
        return max(SeamTuning.BASE_PAIR_MAX_GAP_PX, min(cap, adaptive))
    }

    /**
     * 成组锚定：任意一块跨缝，或同一侧带的「贴上缝 + 贴下缝」同时存在。
     *
     * 容器轮廓（solid + speech_bubble）只贡献「跨缝」证据，不贡献贴边证据 ——
     * 与参考项目 `describeSeamOcrCandidate` 的 `visualCrossesBoundary` 一致。
     */
    fun isAnchored(
        items: List<SeamDetection>,
        config: SeamConfig,
        canvasWidth: Int,
        canvasHeight: Int
    ): Boolean {
        val upperSeams = mutableSetOf<Float>()
        val lowerSeams = mutableSetOf<Float>()
        items.forEach { detection ->
            val box = detection.outer
            seams(config).forEach { seam ->
                if (crosses(box, seam)) return true
                if (touchesFromAbove(box, seam, config.edgeWindowPx)) upperSeams += seam
                if (touchesFromBelow(box, seam, config.edgeWindowPx)) lowerSeams += seam
                val visual = containerContribution(detection, config, canvasWidth, canvasHeight)
                if (visual != null && crosses(visual, seam)) return true
            }
        }
        return upperSeams.any { it in lowerSeams }
    }

    /**
     * 参考项目 `getSeamCandidateVisualContributionBox` 的边界条件：
     * solid + speech_bubble、与文字框有交集、宽度 ≤ 图宽×0.9、高度 ≤ maxCrossHeight、
     * 面积 ≤ 图面积×0.6。`caption_panel` 明确不参与。
     *
     * 容器框优先用 [SeamDetection.container]（子区域仍指回原容器）；没有时退回外框
     * （现有气泡链路里 `Bubble.x/y/w/h` 就是区域框）。
     */
    fun containerContribution(
        detection: SeamDetection,
        config: SeamConfig,
        canvasWidth: Int,
        canvasHeight: Int
    ): SeamBox? {
        val bubble = detection.bubble
        if (!bubble.bgType.equals("solid", ignoreCase = true)) return null
        if (bubble.regionType?.trim()?.equals(REGION_TYPE_SPEECH, ignoreCase = true) != true) return null
        if (canvasWidth <= 0 || canvasHeight <= 0) return null
        val box = detection.container ?: detection.outer
        if (box.width <= 0.5f || box.height <= 0.5f) return null
        if (box.intersectionArea(detection.content) <= 0f) return null
        if (box.width > canvasWidth * MAX_VISUAL_WIDTH_COVERAGE) return null
        if (box.height > config.maxCrossHeightPx) return null
        if (box.area > canvasWidth.toFloat() * canvasHeight * MAX_VISUAL_AREA_COVERAGE) return null
        return box
    }

    /** 高字/大字 SFX 是否仍可当作「有效单行跨缝行」（参考 `seamLineFitsComposite`）。 */
    fun fitsSingleLineComposite(box: SeamBox, canvasHeight: Int): Boolean {
        if (canvasHeight <= 0 || box.height <= 0f) return false
        if (box.top <= 0f) return false
        if (box.bottom >= canvasHeight.toFloat()) return false
        return box.height <= canvasHeight * MAX_LINE_COVERAGE
    }

    /** 合并一组碎片：外框/内容框并集，原文按纵向（top→left）顺序拼接，行数相加。 */
    fun merge(
        items: List<SeamDetection>,
        canvasWidth: Int,
        canvasHeight: Int
    ): SeamDetection {
        val ordered = items.sortedWith(
            compareBy<SeamDetection> { it.outer.top }
                .thenBy { it.outer.left }
                .thenBy { it.content.top }
        )
        val base = ordered.first()
        val outer = ordered.drop(1).fold(base.outer) { acc, item -> acc.union(item.outer) }
        val contentUnion = ordered.drop(1).fold(base.content) { acc, item -> acc.union(item.content) }
        val content = if (contentUnion.width <= 0.5f || contentUnion.height <= 0.5f) {
            outer
        } else {
            contentUnion
        }
        val bothSolid = ordered.all { it.bubble.bgType.equals("solid", ignoreCase = true) }
        val regionIds = ordered.mapNotNull { it.bubble.regionId?.takeIf(String::isNotBlank) }.distinct()
        val regionTypes = ordered.mapNotNull { it.bubble.regionType?.takeIf(String::isNotBlank) }.distinct()
        val text = ordered.map { it.bubble.originalText.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        val lineCount = ordered.sumOf { SeamTuning.lineCountOf(it.bubble) }
        val lineBoxes = ordered.flatMap { it.bubble.lineBoxes.orEmpty() }
            .takeIf { it.isNotEmpty() }
        val rotations = ordered.mapNotNull { it.bubble.rotationDeg }
        val rotation = if (rotations.isEmpty()) null else rotations.average().toFloat()
        val bubble = base.bubble.copy(
            x = percent(outer.left, canvasWidth),
            y = percent(outer.top, canvasHeight),
            w = percent(outer.width, canvasWidth),
            h = percent(outer.height, canvasHeight),
            bgType = if (bothSolid) "solid" else "none",
            originalText = text,
            translatedText = "",
            contentX = percent(content.left, canvasWidth),
            contentY = percent(content.top, canvasHeight),
            contentW = percent(content.width, canvasWidth),
            contentH = percent(content.height, canvasHeight),
            sourceLineCount = lineCount,
            // 合并后的框不是原文字四边形：宁可退回轴对齐，避免拿第一块的倾角套整组。
            polygon = null,
            rotationDeg = rotation,
            lineBoxes = lineBoxes,
            regionId = regionIds.singleOrNull(),
            regionType = regionTypes.singleOrNull(),
            confidence = ordered.minOf { it.bubble.confidence }
        )
        val container = ordered.mapNotNull { it.container }
            .reduceOrNull { acc, box -> acc.union(box) }
        return SeamDetection(bubble = bubble, outer = outer, content = content, container = container)
    }

    private fun seams(config: SeamConfig): List<Float> = buildList {
        if (config.hasTopBand) add(config.topLimitPx)
        if (config.hasBottomBand) add(config.bottomLimitPx)
    }

    private fun crosses(box: SeamBox, seam: Float): Boolean =
        box.top < seam && box.bottom > seam

    private fun touchesFromAbove(box: SeamBox, seam: Float, window: Float): Boolean =
        box.bottom <= seam + 0.5f && box.bottom >= seam - window

    private fun touchesFromBelow(box: SeamBox, seam: Float, window: Float): Boolean =
        box.top >= seam - 0.5f && box.top <= seam + window

    private fun percent(value: Float, total: Int): Float {
        if (total <= 0) return 0f
        return (value / total.toFloat() * 100f).coerceIn(0f, 100f)
    }
}
