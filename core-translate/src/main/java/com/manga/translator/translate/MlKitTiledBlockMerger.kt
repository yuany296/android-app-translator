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
        val lineBoxes: List<FloatArray>?
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
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
     * 合并各块的候选：与已有块 IoU ≥ [MERGE_IOU_THRESHOLD] 的丢弃（保留先到的，
     * 即更靠近块中心的那次检测）；其余追加。输出按给定顺序重新编号。
     */
    fun merge(candidates: List<Candidate>): List<Candidate> {
        val merged = mutableListOf<Candidate>()
        for (candidate in candidates) {
            if (merged.none { existing -> boxIoU(existing, candidate) >= MERGE_IOU_THRESHOLD }) {
                merged += candidate
            }
        }
        return merged
    }
}
