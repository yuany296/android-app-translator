package com.manga.translator.translate

import kotlin.math.ceil

/**
 * 长条图**分块检测**的纯几何策略（2026-10-16）。
 *
 * ## 为什么需要它（用户报「有些单个字的气泡没有 DOM」的根因之一）
 *
 * 检测前会把整图等比缩到配置的「检测长边」（快速 768 / 均衡与质量 960）以内。
 * Lezhin/RIDI 这类站点一张切图能到 1280×5120（页面取像素后也还有 600×2400），
 * 整图检测的实际分辨率于是只剩原图的 **18%~19%**：单字 chip（十几像素高）在检测
 * 输入里只有几个像素，直接落在检测器阈下 —— 日志里 `흠/윽/엎` 这些字**从头到尾
 * 没有出现过**，而稍大一点的字都正常（所以用户看到的是「有的单字泡没被处理」
 * 而不是「都不行」）。
 *
 * ## 做法
 *
 * 对「长宽比 ≥ [MIN_ASPECT] 且长边 > 检测尺寸 × [MIN_LONG_RATIO]」的图：
 * 沿长边切成至多 [MAX_TILES] 块（块间重叠 [TILE_OVERLAP_RATIO]），逐块跑检测
 * （每块的长边都在检测尺寸附近 → 块内文字按**接近原分辨率**检测），再把四边形
 * 映射回原图坐标、按 IoU 去重合并。
 *
 * 普通页面（Kakao 的 760×1000 切图、方形插页）不满足闸门 → 保持单趟检测，
 * 行为与成本完全不变。
 *
 * 纯 Int/Float 运算，无 Android 依赖，JVM 可直接单测。
 */
internal object PaddleDetectionTiling {

    /** 只有「长条」才分块：长宽比低于它（方形插页、常规切图）不分。 */
    const val MIN_ASPECT = 2.0f

    /** 且长边要明显超过检测尺寸 —— 没超多少时分块没有增益，只多花检测时间。 */
    const val MIN_LONG_RATIO = 1.6f

    /** 块间重叠占块长的比例（取 0.12：跨块边界的单行文字大概率整行落在邻块里）。 */
    const val TILE_OVERLAP_RATIO = 0.12f

    /** 单图最多切几块 —— 检测耗时随块数线性上涨，3 块已能把 2400px 长条拉回 ~1:1。 */
    const val MAX_TILES = 3

    /** 一块检测区域（沿长轴的 [start], endExclusive）。 */
    data class Tile(val start: Int, val endExclusive: Int) {
        val length: Int get() = endExclusive - start
    }

    /**
     * 这张图要不要走分块检测。
     *
     * @param detResizeLong 检测的缩放长边（[PaddleOcrLocalTextDetector.SpeedModeConfig.detResizeLong]）。
     */
    fun shouldTile(width: Int, height: Int, detResizeLong: Float): Boolean {
        if (width <= 0 || height <= 0 || detResizeLong <= 0f) return false
        val longSide = maxOf(width, height)
        val shortSide = minOf(width, height)
        if (longSide / shortSide.toFloat() < MIN_ASPECT) return false
        return longSide > detResizeLong * MIN_LONG_RATIO
    }

    /**
     * 沿长轴切块（覆盖 [0, length) 的完整区间）。
     *
     * 块数 = `min(maxTiles, ceil(length / detResizeLong))`（块数 ≤ 上限）；
     * 步长按「块数 + 重叠比例」解出来（`step = length / ((count-1) + 1/(1-ratio))`），
     * 块长 = 步长 + 重叠（重叠 = 步长 × [overlapRatio]）——保证相邻块**必有重叠**、
     * 各块长度均匀（不会切出一条几百像素的细尾巴）、最后一块的末端始终 = length。
     *
     * 长度不足一块时返回单块 `[0, length)`（调用方按单趟处理）。
     */
    fun tiles(
        length: Int,
        detResizeLong: Float,
        maxTiles: Int = MAX_TILES,
        overlapRatio: Float = TILE_OVERLAP_RATIO
    ): List<Tile> {
        if (length <= 0 || maxTiles <= 0 || detResizeLong <= 0f) return emptyList()
        val count = ceil(length / detResizeLong.toDouble()).toInt().coerceIn(1, maxTiles)
        if (count <= 1) return listOf(Tile(0, length))
        val ratio = overlapRatio.coerceIn(0f, 0.45f)
        // 覆盖条件：(count-1)·step + (step + step·ratio) ≥ length
        //        →  step ≥ length / ((count-1) + 1/(1-ratio))
        val denom = (count - 1) + 1.0 / (1.0 - ratio)
        val step = ceil(length / denom).toInt().coerceAtLeast(1)
        val tileLen = (step + ceil(step * ratio).toInt()).coerceAtMost(length)
        val out = mutableListOf<Tile>()
        var start = 0
        for (index in 0 until count) {
            val end = if (index == count - 1) length else (start + tileLen).coerceAtMost(length)
            if (end <= start) return listOf(Tile(0, length))
            out += Tile(start, end)
            start += step
        }
        return out
    }
}
