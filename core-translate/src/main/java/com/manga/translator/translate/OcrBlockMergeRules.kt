package com.manga.translator.translate

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 合并判决的原因（诊断用；拒绝原因也是稳定字符串，便于日志过滤）。 */
enum class OcrMergeReason {
    /** 合并：同一行（或竖排同一列组）的行内碎片。 */
    INLINE,

    /** 合并：同一段落里上下堆叠的相邻行。 */
    PARAGRAPH,

    /** 拒绝：一块像竖排列、一块不像，且不构成「并排的列」；或倾角差过大。 */
    ORIENTATION,

    /** 拒绝：两块的行高（字号）差异过大。 */
    FONT_SCALE,

    /** 拒绝：沿「行方向」的重叠不足（不是同一行，也不是同一列组）。 */
    LINE_OVERLAP,

    /** 拒绝：基线残差过大（同行/同列的基线不在一条线上）。 */
    BASELINE,

    /** 拒绝：两块之间的间隙超过以行高为单位的上限。 */
    GAP,

    /** 拒绝：宽度/边缘/中心对齐都不满足（不是一个段落的对齐形态）。 */
    ALIGNMENT
}

/**
 * 合并判决。`gapPx` / `lineHeightPx` 是**判据用到的原始量**，随结果带出，
 * 调用方据此打「接近但被拒」的诊断（见 `block_merge_reject` 日志）。
 */
data class OcrMergeDecision(
    val merge: Boolean,
    val reason: OcrMergeReason,
    val gapPx: Float,
    val lineHeightPx: Float,
    /** 这一对是否按「竖排阅读系」（行内方向 = Y、行高 = 列宽）评估。 */
    val verticalFrame: Boolean
)

/**
 * 本地 OCR 文本块的**收严合并判据**（2026-10-03），规则直接映射参考项目
 * `C:\homework\translator\extension\src\background\modules\ocr-lines.js` 的**线上路径**：
 *
 * - `shouldMergeLocalPaddleSameLine` → [shouldMerge] 的行内分支；
 * - `shouldMergeLocalPaddleParagraphLines` → [shouldMerge] 的堆叠（段落）分支。
 *
 * ## 与旧判据（`BubbleMergeHeuristics`）的关键差别
 *
 * | | 旧判据 | 本判据 |
 * |---|---|---|
 * | 阈值单位 | `max(块高)`（多行段落块会把上限放大好几倍） | **行高**（`框高 ÷ 行数`；竖排列用列宽） |
 * | 段落间距上限 | `1.35 × maxH`（真机现场 ≈ 188px，真实间隙只有 50px） | `1.1 × 平均行高`（现场 ≈ 37px） |
 * | 对齐 | 只有垂直重叠 ≥ 18% | 宽度比 + 边缘/中心对齐（见下） |
 * | 字号一致性 | 无 | `min/max 行高 ≥ 0.65` |
 *
 * ## 与参考项目的对齐口径（2026-10-14 修订）
 *
 * 1. 段落分支的对齐闸：参考项目是「`widthRatio ≥ 0.35` **或** 边缘/中心对齐」。
 *    2026-10-03 曾把宽度比升级成**硬闸**（不足直接拒绝）——结果「只有几个字的短行」
 *    （气泡末行、CAUTION 带）宽度比必然 < 0.35，即使居中/贴左也合并不上
 *    （用户 2026-10-14 报「只有两个字就合并不到」）。现在回到参考项目口径：
 *    宽度比只作证据，**边缘对齐或中心接近即可合并**；两者皆无仍拒绝
 *    （两气泡错位行的现场回归 `knownRegionRelaxesAlignmentButNotGap` 不受影响）。
 *    区域未知时保留「边缘对齐/中心接近」这道闸 —— 参考项目靠 `region_id` 兜底，
 *    我们没有，几何上必须自己守住「两个独立气泡不得并成一句」。
 * 2. 不实现参考项目的 `sharedSpeechBubbleContainer` 逃生门（同一个可信容器内可放宽字号闸）：
 *    我们的容器信息只在 planner 内部、不可靠，宁可拆开。
 *
 * 纯几何、无 Android 依赖，可 JVM 单测。
 */
object OcrBlockMergeRules {

    /** 字号闸：两块行高之比小于它就不合并（参考项目 `heightRatio < 0.65`）。 */
    const val FONT_SCALE_MIN_RATIO = 0.65f

    /** 间距上限相对平均行高的比例（参考项目合并 1.2×、段内拆分 1.1×，取更严的 1.1）。 */
    const val LINE_GAP_MAX_RATIO = 1.1f

    /** 行内分支：沿行方向的重叠至少是较矮块的这个比例（参考项目 `lineOverlap ≥ 0.5 × min(h)`）。 */
    const val LINE_OVERLAP_MIN_RATIO = 0.5f

    /** 基线残差上限相对行高（参考项目 `|Δbottom| ≤ 0.35 × avgHeight`）。 */
    const val BASELINE_MAX_RATIO = 0.35f

    /**
     * 段落分支：两块在行内方向上的长度比（参考项目 `widthRatio ≥ 0.35`）。
     * 只作对齐证据、**不是闸**（2026-10-14 起）；保留常量用于与参考项目对表。
     */
    const val WIDTH_RATIO_MIN = 0.35f

    /** 段落分支：边缘对齐容差相对行高（参考项目 `|Δleft| / |Δright| ≤ 2.2 × avgHeight`）。 */
    const val EDGE_ALIGN_MAX_RATIO = 2.2f

    /** 段落分支：中心对齐容差相对较长块（参考项目 `centerOffset ≤ 0.35 × max(width)`）。 */
    const val CENTER_CLOSE_MAX_RATIO = 0.35f

    /** 段落分支：中心不近时，行内方向的重叠至少要达到这个比例（参考项目 `overlapRatio ≤ 0.35` → 不合并）。 */
    const val INLINE_OVERLAP_ACCEPT_RATIO = 0.35f

    /** 倾角差上限（参考项目 `rotationDistance > 18°` → 不合并）。 */
    const val ROTATION_MAX_DEG = 18f

    /**
     * 两块要不要合成同一段/同一行。
     *
     * @param sameKnownRegion 两块已经被判定属于**同一个气泡区域**（planner 的区域内拆分路径）。
     *   区域即容器硬闸，此时段落分支不再额外要求边缘/中心对齐（对齐参考项目的容器口径）。
     */
    fun shouldMerge(
        a: TextBlock,
        b: TextBlock,
        sameKnownRegion: Boolean = false
    ): OcrMergeDecision {
        val verticalA = TextLineGeometry.isVerticalColumn(a)
        val verticalB = TextLineGeometry.isVerticalColumn(b)
        val columnPair = looksLikeColumnPair(a, b)
        // 「一块竖排、一块横排」按定义不是同一段 —— 除非它们构成「并排的列」
        // （横向几乎不重叠 + 合起来竖长）。这与 planner 现口径一致。
        if (verticalA != verticalB && !columnPair) {
            return reject(OcrMergeReason.ORIENTATION, a, b, vertical = false)
        }
        val vertical = (verticalA && verticalB) || columnPair
        if (rotationDelta(a, b) > ROTATION_MAX_DEG) {
            return reject(OcrMergeReason.ORIENTATION, a, b, vertical)
        }

        // 行高：横排 = 框高 ÷ 行数；竖排列 = 列宽。
        val lineHeightA = lineHeightPx(a, vertical)
        val lineHeightB = lineHeightPx(b, vertical)
        val avgLineHeight = ((lineHeightA + lineHeightB) / 2f).coerceAtLeast(1f)
        val minLineHeight = min(lineHeightA, lineHeightB)
        val maxLineHeight = max(lineHeightA, lineHeightB)
        val gapLimit = avgLineHeight * LINE_GAP_MAX_RATIO

        // 字号闸（参考项目 `heightRatio < 0.65` → 不合并）。
        if (minLineHeight / maxLineHeight < FONT_SCALE_MIN_RATIO) {
            return reject(OcrMergeReason.FONT_SCALE, a, b, vertical, gapLimit, avgLineHeight)
        }

        // 阅读系里的量（横排：inline = X、line = Y；竖排列：inline = Y、line = X）：
        // - inline = 文字流动方向；line = 垂直于文字流动的方向（行与行之间就沿它在排）。
        val inlineGap = if (vertical) verticalGap(a, b) else horizontalGap(a, b)
        val lineGap = if (vertical) horizontalGap(a, b) else verticalGap(a, b)
        val inlineOverlap = if (vertical) verticalOverlap(a, b) else horizontalOverlap(a, b)
        val lineOverlap = if (vertical) horizontalOverlap(a, b) else verticalOverlap(a, b)
        val inlineExtentA = inlineExtent(a, vertical)
        val inlineExtentB = inlineExtent(b, vertical)
        val minInlineExtent = min(inlineExtentA, inlineExtentB)
        val maxInlineExtent = max(inlineExtentA, inlineExtentB)
        val minLineExtent = if (vertical) {
            min(TextLineGeometry.widthPx(a), TextLineGeometry.widthPx(b))
        } else {
            min(TextLineGeometry.heightPx(a), TextLineGeometry.heightPx(b))
        }
        val baselineResidual = if (vertical) {
            abs(centerX(a) - centerX(b))
        } else {
            abs(a.bottom - b.bottom)
        }

        val decision = OcrMergeDecision(
            merge = false,
            reason = OcrMergeReason.GAP,
            gapPx = lineGap,
            lineHeightPx = avgLineHeight,
            verticalFrame = vertical
        )

        // ---- 分支 1：行内（同一行 / 并排的列）----
        // 参考项目 `shouldMergeLocalPaddleSameLine`：lineOverlap ≥ 0.5·min(h) 且 |Δbottom| ≤ 0.35·avgH
        // 且 inlineGap < 1.2·avgH（我们取 1.1）。
        val overlapEnough = lineOverlap >= minLineExtent * LINE_OVERLAP_MIN_RATIO
        val baselineOk = baselineResidual <= avgLineHeight * BASELINE_MAX_RATIO
        val inlineGapOk = inlineGap < gapLimit
        if (overlapEnough && baselineOk && inlineGapOk) {
            return decision.copy(merge = true, reason = OcrMergeReason.INLINE)
        }

        // ---- 分支 2：堆叠（段落里的上下行）----
        // 参考项目 `shouldMergeLocalPaddleParagraphLines`：**lineGap** < 1.2·avgH 是硬条件
        // （行距过大 = 空行分段），再要求宽度比 + 边缘/中心对齐。
        if (lineGap >= gapLimit) {
            // 行方向重叠很足、但基线对不上、行内间隙也不大 —— 这是「同一行被切成两块、
            // 基线歪了」的形态，单独给一个稳定的拒绝原因便于日志排查；其余按间距拒绝。
            return decision.copy(
                reason = if (overlapEnough && !baselineOk && inlineGapOk) {
                    OcrMergeReason.BASELINE
                } else {
                    OcrMergeReason.GAP
                }
            )
        }

        val edgeTolerance = avgLineHeight * EDGE_ALIGN_MAX_RATIO
        val centerOffset = if (vertical) {
            abs(centerY(a) - centerY(b))
        } else {
            abs(centerX(a) - centerX(b))
        }
        val centerClose = centerOffset <= maxInlineExtent * CENTER_CLOSE_MAX_RATIO
        // 参考项目的第 8 条拒绝：中心不近、行内重叠也不足 → 不是一个段落。
        val inlineOverlapRatio = inlineOverlap / minInlineExtent.coerceAtLeast(1f)
        if (!centerClose && inlineOverlapRatio <= INLINE_OVERLAP_ACCEPT_RATIO) {
            return decision.copy(reason = OcrMergeReason.ALIGNMENT)
        }
        if (!sameKnownRegion && !centerClose) {
            // 边缘对齐（行内方向两端任一端对齐即算）。
            val edgeAligned = if (vertical) {
                abs(a.top - b.top) <= edgeTolerance || abs(a.bottom - b.bottom) <= edgeTolerance
            } else {
                abs(a.left - b.left) <= edgeTolerance || abs(a.right - b.right) <= edgeTolerance
            }
            if (!edgeAligned) {
                return decision.copy(reason = OcrMergeReason.ALIGNMENT)
            }
        }
        // 宽度比（WIDTH_RATIO_MIN）只是「对齐形态」的证据之一，不再是硬闸（2026-10-14 修）：
        // 只有几个字的短行（气泡末行、CAUTION 带这类）宽度比必然 < 0.35，
        // 只要边缘对齐或中心接近就应并入所在段 —— 此前宽度比不足直接拒绝，
        // 短行永远合并不上（用户报「只有两个字就合并不到」）。
        return decision.copy(merge = true, reason = OcrMergeReason.PARAGRAPH)
    }

    /** 行内方向上的长度（横排 = 宽，竖排列 = 高）。 */
    private fun inlineExtent(block: TextBlock, vertical: Boolean): Float =
        if (vertical) TextLineGeometry.heightPx(block) else TextLineGeometry.widthPx(block)

    /**
     * 这一对块是否构成「**并排的竖列**」（与 `PaddleOcrBlockMerger.looksLikeColumnPair`
     * / `BubbleFirstRegionPlanner.looksLikeColumnPair` 同口径）：
     * 横向几乎不重叠，且两块的**外接框竖长**。
     *
     * 第 2 条是关键：同一行的左右碎片合起来是宽的，不会被误判成列。
     */
    fun looksLikeColumnPair(a: TextBlock, b: TextBlock): Boolean {
        val minW = min(TextLineGeometry.widthPx(a), TextLineGeometry.widthPx(b))
        val xOverlap = horizontalOverlap(a, b)
        if (xOverlap >= minW * COLUMN_X_OVERLAP_MAX) return false
        val unionWidth = (max(a.right, b.right) - min(a.left, b.left)).coerceAtLeast(1f)
        val unionHeight = (max(a.bottom, b.bottom) - min(a.top, b.top)).coerceAtLeast(1f)
        return unionHeight >= unionWidth * COLUMN_PAIR_ASPECT
    }

    private fun lineHeightPx(block: TextBlock, vertical: Boolean): Float {
        val lineHeight = if (vertical) {
            TextLineGeometry.widthPx(block)
        } else {
            TextLineGeometry.heightPx(block) / TextLineGeometry.lineCount(block).toFloat()
        }
        return lineHeight.coerceAtLeast(1f)
    }

    /**
     * 倾角差（度）。只有**双方都带文字四边形**时才有意义（ML Kit 不给四边形）——
     * 拿不到就返回 0（跳过这条闸），不会因为缺数据而误拒。
     */
    private fun rotationDelta(a: TextBlock, b: TextBlock): Float {
        val rotationA = rotationDegOf(a) ?: return 0f
        val rotationB = rotationDegOf(b) ?: return 0f
        var delta = abs(rotationA - rotationB) % 180f
        if (delta > 90f) delta = 180f - delta
        return delta
    }

    private fun rotationDegOf(block: TextBlock): Float? {
        val polygon = block.polygon ?: return null
        if (polygon.size != QUAD_POINT_COUNT) return null
        return PaddleQuadGeometry.rotationDeg(polygon.map { PaddleQuadGeometry.Point(it.x, it.y) })
    }

    private fun reject(
        reason: OcrMergeReason,
        a: TextBlock,
        b: TextBlock,
        vertical: Boolean,
        gapPx: Float = 0f,
        lineHeightPx: Float = 0f
    ): OcrMergeDecision {
        val resolvedLineHeight = if (lineHeightPx > 0f) {
            lineHeightPx
        } else {
            averageLineHeight(a, b, vertical)
        }
        val resolvedGap = if (gapPx > 0f) {
            gapPx
        } else if (vertical) {
            verticalGap(a, b)
        } else {
            horizontalGap(a, b)
        }
        return OcrMergeDecision(
            merge = false,
            reason = reason,
            gapPx = resolvedGap,
            lineHeightPx = resolvedLineHeight,
            verticalFrame = vertical
        )
    }

    private fun averageLineHeight(a: TextBlock, b: TextBlock, vertical: Boolean): Float {
        return ((lineHeightPx(a, vertical) + lineHeightPx(b, vertical)) / 2f).coerceAtLeast(1f)
    }

    private fun horizontalGap(a: TextBlock, b: TextBlock): Float =
        max(0f, max(a.left, b.left) - min(a.right, b.right))

    private fun verticalGap(a: TextBlock, b: TextBlock): Float =
        max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))

    private fun horizontalOverlap(a: TextBlock, b: TextBlock): Float =
        (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)

    private fun verticalOverlap(a: TextBlock, b: TextBlock): Float =
        (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)

    private fun centerX(block: TextBlock): Float = (block.left + block.right) / 2f

    private fun centerY(block: TextBlock): Float = (block.top + block.bottom) / 2f

    private const val QUAD_POINT_COUNT = 4

    /** 「并排成列」的横向重叠上限（与 `PaddleOcrBlockMerger.COLUMN_X_OVERLAP_MAX` 同值同义）。 */
    private const val COLUMN_X_OVERLAP_MAX = 0.25f

    /** 一对块判为「并排的列」的最小整体高宽比（同 `GROUP_VERTICAL_ASPECT`）。 */
    private const val COLUMN_PAIR_ASPECT = 1.15f
}
