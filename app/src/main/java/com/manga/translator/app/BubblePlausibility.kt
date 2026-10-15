package com.manga.translator.app

/**
 * 盖板框的**合理性钳制**（2026-10-15，对齐参考项目 `stitch-mapping.js` 的宽高钳制）。
 *
 * ## 为什么要有它
 *
 * 用户复述历史问题：「之前超出盖板的原因应该是截页的处理没处理好、把宽高算错了」。
 * 参考项目对拼接图映射回来的气泡有一条宽高闸门：**高度超过 owner 高的 60%（多行）/
 * 35%（单行）** 就钳高（并丢掉多边形/填充证据，退回纯矩形）。那不是排版规则，
 * 而是「这个尺寸只可能是换算错了」的安全网 —— 换算链（拼接 px → 源图 %、跨图移交、
 * 条带裁剪）任何一环出错，最先可见的结果就是一块超高的盖板。
 *
 * ## 口径
 *
 * - 只钳**盖板框**高度（上沿不动，宽度不动）：排版/字号仍按「以原文为基准」原样走，
 *   不碰用户的排版规则；
 * - 超上限时调用方还会把 `regionPolygonPercent` 置空 → 盖板退回纯矩形（与参考项目
 *   `clampAdjusted ? null : …` 同口径），避免用被污染的轮廓再裁出怪形状；
 * - 未超上限时**原样返回 null**（不做任何加工）。
 *
 * 纯逻辑、可 JVM 单测。
 */
internal object BubblePlausibility {

    /** 单行气泡的盖板高度上限（占原图高比例）：参考项目 `maxH = lineCount > 1 ? 60 : 35`。 */
    const val MAX_COVER_HEIGHT_RATIO_SINGLE_LINE = 0.35f

    /** 多行气泡的盖板高度上限（占原图高比例）。 */
    const val MAX_COVER_HEIGHT_RATIO_MULTILINE = 0.60f

    /** 该行数下盖板高度的上限（百分比 0..100）。 */
    fun maxCoverHeightPercent(lineCount: Int): Float =
        if (lineCount > 1) MAX_COVER_HEIGHT_RATIO_MULTILINE * 100f
        else MAX_COVER_HEIGHT_RATIO_SINGLE_LINE * 100f

    /**
     * 盖板框高度超过合理性上限时，返回**钳高后的框**（`x, y, w, h`，保持上沿）；
     * 未超上限（或输入非法）返回 null —— 调用方原样使用，不做任何加工。
     */
    fun clampCoverHeight(coverBoxPercent: FloatArray, lineCount: Int): FloatArray? {
        if (coverBoxPercent.size != 4) return null
        val height = coverBoxPercent[3]
        if (!height.isFinite()) return null
        val maxHeight = maxCoverHeightPercent(lineCount)
        if (height <= maxHeight) return null
        return floatArrayOf(coverBoxPercent[0], coverBoxPercent[1], coverBoxPercent[2], maxHeight)
    }

    /**
     * 「按行数估文字高度」时该用的行高（2026-10-15 真机现场：跨缝圆泡盖板 = 文字两倍）。
     *
     * 背景：`growBubbleBottomPx` 用**全页中位行高**估「N 行文字要占多高」。混合字号页面上
     * 这会系统性高估小字气泡（现场：中位 130px、气泡自己的行高 ~51px，2 行小字被估成
     * 312px → 扩出来的区域包含圆圈下弧线的墨迹 → 后续墨迹收紧也收不回来，盖板 = 文字两倍）。
     *
     * 修法：**优先用气泡自己的行高**（框高 ÷ 行数，上限=中位行高）；只有框本身是「细条」
     * （[MIN_LOCAL_BOX_PX] 以下，压根量不出行高 —— 2026-10-11 那种 `box=60x6` 的截缝碎片）
     * 才退回中位行高。小字气泡不再按大字页面的中位被放大；「框真的只装了一半文字」的
     * 老现场（框内行高接近中位）行为基本不变。
     */
    fun effectiveLineHeightPx(
        medianLineHeightPx: Float,
        boxHeightPx: Float,
        lineCount: Int
    ): Float {
        if (!medianLineHeightPx.isFinite() || medianLineHeightPx <= 0f) return 0f
        if (!boxHeightPx.isFinite() || boxHeightPx <= 0f) return medianLineHeightPx
        if (boxHeightPx < MIN_LOCAL_BOX_PX) return medianLineHeightPx
        val lines = lineCount.coerceAtLeast(1)
        return minOf(medianLineHeightPx, boxHeightPx / lines)
    }

    /** 框矮到这个程度就量不出「自己的行高」了（截缝细条），退回中位行高。 */
    const val MIN_LOCAL_BOX_PX = 20f
}
