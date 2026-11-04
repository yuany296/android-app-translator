package com.manga.translator.translate

/**
 * ML Kit 长条图**分块检测**的合并策略（2026-10-16）。
 *
 * ## 背景（对齐 Paddle 的 [PaddleDetectionTiling]，§54-B 只接了 Paddle）
 *
 * `MlKitLocalTextDetector` 此前把整图缩到长边 1280 再检测：600×2544 的长条缩到
 * 300×1272，单字 chip 和句尾符号（`!!!`、收音）在检测输入里只剩几个像素，
 * 用户在 ML Kit 模式下持续看到「有的字没被处理」——§54-B 修了 Paddle、ML Kit 没接。
 *
 * ## 这份逻辑负责什么
 *
 * 分块本身沿长轴切（复用 [PaddleDetectionTiling.tiles]，闸门同款）；每个块独立过
 * ML Kit 后，把**块坐标系的框**换算回整图坐标，再按外接框 IoU 去重合并
 * （阈值与 Paddle 的 `MERGE_IOU_THRESHOLD` 同口径）。
 *
 * 纯数据运算，无 Android 依赖，JVM 可直接单测。
 */
internal object MlKitTiledBlockMerger {

    /** 相邻块重叠区里同一句话会被检两次：IoU ≥ 它视为同一块，保留先到的。 */
    const val MERGE_IOU_THRESHOLD = 0.45f

    /** 一个待合并的候选块：整图像素坐标的外接框 + 逐行框（同坐标系）。 */
    data class Candidate(
        val text: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val lineCount: Int,
        val lineBoxes: List<FloatArray>?,
        /** 来源块序号（同一张图分块检测时用；0 = 单块/老调用）。 */
        val tileIndex: Int = 0,
        /** 从同一 block 按元素间距拆出的组标识；合并/去重时原样保留。 */
        val splitGroupId: String? = null
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val area: Float get() = width * height
    }

    fun boxIoU(a: Candidate, b: Candidate): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val interW = right - left
        val interH = bottom - top
        if (interW <= 0f || interH <= 0f) return 0f
        val inter = interW * interH
        val union = a.width * a.height + b.width * b.height - inter
        if (union <= 0f) return 0f
        return inter / union
    }

    /**
     * 合并各块的候选：
     * - 同一块内 IoU ≥ [MERGE_IOU_THRESHOLD] → 去重；
     * - **不同块**之间文本相同/包含、且几何高度重叠 → 也视为同一块（2026-10-30）。
     *
     * 为什么需要第二条：ML Kit 的块级框会随上下文变化，同一句在相邻块重叠区里
     * 可能 IoU 只有 0.3~0.4（低于阈值）但文本相同。旧逻辑保留两份 → 同一句原文
     * 被重复拼进一个气泡，表现为「文字越合越长」「接缝处文字重复」。
     *
     * 保留更完整的那一份：文本更长 > 行数更多 > 面积更大。
     */
    fun merge(candidates: List<Candidate>): List<Candidate> {
        val merged = mutableListOf<Candidate>()
        for (candidate in candidates) {
            val sameBoxIndex = merged.indexOfFirst { existing ->
                boxIoU(existing, candidate) >= MERGE_IOU_THRESHOLD
            }
            if (sameBoxIndex >= 0) {
                // 既有行为：同框重复保留先到的（更靠近块中心的那次检测）。
                // 例外：近似 OCR 变体，或一方文本是另一方的**真子串**时，保留更可信的那份。
                val existing = merged[sameBoxIndex]
                val contained = existing.tileIndex != candidate.tileIndex &&
                    isContainedTextDuplicate(existing, candidate)
                if (isBetter(candidate, existing) &&
                    (contained || isFuzzyTextDuplicate(existing, candidate))
                ) {
                    merged[sameBoxIndex] = candidate
                }
                continue
            }
            val textDuplicateIndex = merged.indexOfFirst { existing ->
                isCrossTileTextDuplicate(existing, candidate) || isFuzzyTextDuplicate(existing, candidate)
            }
            if (textDuplicateIndex < 0) {
                merged += candidate
            } else if (isBetter(candidate, merged[textDuplicateIndex])) {
                merged[textDuplicateIndex] = candidate
            }
        }
        return merged
    }

    /**
     * 不同分块之间的「同文重复」：文本相同、一方包含另一方，或**OCR 近似变体**
     * （同一句被识别成两版，如「물어야겠어」→「물어아겼어」），且框明显重叠。
     * 只对**不同 tileIndex** 生效 —— 同一块里的两个同文本气泡仍按 IoU 判决
     * （既有 `partiallyOverlappingDistinctBlocksSurvive` 行为不变）。
     */
    fun isCrossTileTextDuplicate(a: Candidate, b: Candidate): Boolean {
        if (a.tileIndex == b.tileIndex) return false
        val aText = normalizeText(a.text)
        val bText = normalizeText(b.text)
        if (aText.isEmpty() || bText.isEmpty()) return false
        val verticalOverlap = intersectionHeight(a, b) / maxOf(1f, minOf(a.height, b.height))
        val horizontalOverlap = intersectionWidth(a, b) / maxOf(1f, minOf(a.width, b.width))
        if (aText == bText || isContainedTextDuplicate(a, b)) {
            return verticalOverlap >= 0.35f && horizontalOverlap >= 0.30f
        }
        // 近似变体要求更严的几何重叠：同一句被两个块各读一遍时，框几乎重合；
        // 两个不同的气泡即使文本相似，也不会在横纵两个方向都压到 50% 以上。
        if (verticalOverlap < 0.55f || horizontalOverlap < 0.50f) return false
        return isNearDuplicateText(aText, bText)
    }

    /**
     * 同一/不同分块里的「近似 OCR 变体」：文本不同但高度相似，且框在两个方向都强重叠。
     *
     * 不要求 tileIndex 不同 —— 短图只有单块时，同一句也可能被 ML Kit 拆成两个
     * 近似块；只要文本不是完全相同（完全相同的同块重复仍走 IoU 判决），就按近似变体处理。
     */
    fun isFuzzyTextDuplicate(a: Candidate, b: Candidate): Boolean {
        val aText = normalizeText(a.text)
        val bText = normalizeText(b.text)
        if (aText.isEmpty() || bText.isEmpty() || aText == bText) return false
        val verticalOverlap = intersectionHeight(a, b) / maxOf(1f, minOf(a.height, b.height))
        val horizontalOverlap = intersectionWidth(a, b) / maxOf(1f, minOf(a.width, b.width))
        if (verticalOverlap < 0.55f || horizontalOverlap < 0.50f) return false
        return isNearDuplicateText(aText, bText)
    }

    /**
     * OCR 近似变体：归一化文本的 Levenshtein 相似度 ≥ [OcrTextSimilarity.NEAR_DUPLICATE_SIMILARITY]。
     *
     * 真机现场（2026-11-01，`pageHash ca9603cd…`）同一页同一句被识别成：
     * - `지금 물어야겠어?\n조용히 따라오기나 해.`（正确，两行）
     * - `지금 물어아겼어? 조용히 따리오기나 해.`（近似但错字，单行）
     * 旧逻辑只认「相同/包含」，两版都留下，随后被并进同一段 → 译文重复两遍。
     * 归一化后相似度 0.8125，长度相同（16），框几乎重合。
     */
    private fun isNearDuplicateText(aText: String, bText: String): Boolean =
        OcrTextSimilarity.isNearDuplicate(aText, bText)

    /** 文本真包含（较短者至少 2 字）：同一句的残缺/完整两版。 */
    private fun isContainedTextDuplicate(a: Candidate, b: Candidate): Boolean {
        val aText = normalizeText(a.text)
        val bText = normalizeText(b.text)
        if (aText == bText) return false
        val shorter = if (aText.length <= bText.length) aText else bText
        return shorter.length >= 2 && (aText.contains(bText) || bText.contains(aText))
    }

    private fun isBetter(candidate: Candidate, existing: Candidate): Boolean {
        val candidateText = normalizeText(candidate.text).length
        val existingText = normalizeText(existing.text).length
        if (candidateText != existingText) return candidateText > existingText
        if (candidate.lineCount != existing.lineCount) return candidate.lineCount > existing.lineCount
        // 同长度/同行数时，优先「文本换行结构与 lineCount 一致」的那份：
        // 近似变体里常有一份是单行合并块（文本无 \n 但 lineCount=2），
        // 正确识别的多行块会带 \n，结构更可信。
        val candidateConsistency = kotlin.math.abs(
            (candidate.text.count { it == '\n' } + 1) - candidate.lineCount
        )
        val existingConsistency = kotlin.math.abs(
            (existing.text.count { it == '\n' } + 1) - existing.lineCount
        )
        if (candidateConsistency != existingConsistency) {
            return candidateConsistency < existingConsistency
        }
        return candidate.area > existing.area
    }

    private fun normalizeText(text: String): String =
        text.filter { it.isLetterOrDigit() }.lowercase()

    private fun intersectionWidth(a: Candidate, b: Candidate): Float =
        maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left))

    private fun intersectionHeight(a: Candidate, b: Candidate): Float =
        maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
}
