package com.manga.translator.translate

import kotlin.math.max
import kotlin.math.min

/**
 * 原文排版对齐推断与归一（2026-09-28，对齐 C:\homework\translator `ocr-regions.js`
 * 的 `inferTextAlignmentFromBoxes` / `normalizeOcrTextAlignment`）。
 *
 * 用户要求「左对齐、右对齐和居中需要区分」：气泡里多行文字的左右边缘/中心线分布
 * 能稳定反映原文排版，译文应沿用同一对齐方式，而不是一律居中。
 *
 * 判据（与参考项目同口径）：
 * 1. chat/comment/ui 区域一律左对齐；
 * 2. 行数 < 2 时没有可比较的分布 → 居中（这些区域类型除外）；
 * 3. 「短行嵌在长行中间」的排版：中心线分布明显比左右边缘更稳 → 居中（必须先于宽松的边缘规则判断）；
 * 4. 左边缘分布够稳且右边缘明显更散（或有明显行宽差）→ 左对齐；右边缘同理 → 右对齐；
 * 5. 其余取三者中分布最稳的那个，无法判断时居中。
 *
 * 纯函数（只用 [TextBlock] 的几何），便于 JVM 单测。
 */
object TextAlignment {
    const val LEFT = "left"
    const val CENTER = "center"
    const val RIGHT = "right"

    /** 区域类型里天然左对齐的那些（聊天/评论/UI 文本）。 */
    private val LEFT_ALIGNED_REGION_TYPES = setOf("chat", "comment", "ui")

    /** 归一非法值到居中（参考项目 `normalizeOcrTextAlignment`）。 */
    fun normalize(value: String?): String {
        return when (value?.trim()?.lowercase()) {
            LEFT -> LEFT
            RIGHT -> RIGHT
            CENTER -> CENTER
            else -> CENTER
        }
    }

    fun isLeft(value: String?): Boolean = normalize(value) == LEFT
    fun isRight(value: String?): Boolean = normalize(value) == RIGHT

    /**
     * 从逐行文字框推断对齐方式。
     *
     * @param blocks 同一气泡/文字块内的逐行 OCR 框（像素坐标）
     * @param imageWidth 图片宽度（像素），用于按图宽给出容差下限
     * @param regionType 区域类型（chat/comment/ui 等），可空
     */
    fun infer(
        blocks: List<TextBlock>,
        imageWidth: Float,
        regionType: String = ""
    ): String {
        if (regionType.trim().lowercase() in LEFT_ALIGNED_REGION_TYPES) return LEFT
        val usable = blocks.filter { it.right > it.left && it.bottom > it.top }
        if (usable.size < 2) return CENTER

        val averageHeight = max(
            1f,
            usable.sumOf { (it.bottom - it.top).toDouble() }.toFloat() / usable.size
        )
        val safeWidth = max(1f, imageWidth)
        val lefts = usable.map { it.left }
        val rights = usable.map { it.right }
        val centers = usable.map { (it.left + it.right) / 2f }
        val widths = usable.map { it.right - it.left }

        val leftSpread = spread(lefts)
        val rightSpread = spread(rights)
        val centerSpread = spread(centers)
        val widthSpread = spread(widths)

        val edgeTolerance = max(averageHeight * 1.15f, safeWidth * 0.012f)
        val centerTolerance = max(averageHeight * 1.2f, safeWidth * 0.018f)
        val closestEdgeSpread = min(leftSpread, rightSpread)

        // 「短行嵌在长行中间」：中心线比左右边缘稳得多 → 居中
        if (centerSpread <= centerTolerance && centerSpread <= closestEdgeSpread * 0.6f) {
            return CENTER
        }
        if (leftSpread <= edgeTolerance &&
            (rightSpread > leftSpread * 1.35f || widthSpread > averageHeight * 0.75f)
        ) {
            return LEFT
        }
        if (rightSpread <= edgeTolerance &&
            (leftSpread > rightSpread * 1.35f || widthSpread > averageHeight * 0.75f)
        ) {
            return RIGHT
        }
        if (centerSpread <= centerTolerance) return CENTER
        if (leftSpread <= rightSpread && leftSpread <= centerSpread) return LEFT
        if (rightSpread <= leftSpread && rightSpread <= centerSpread) return RIGHT
        return CENTER
    }

    private fun spread(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        return values.max() - values.min()
    }
}
