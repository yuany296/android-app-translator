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
 * ## 2026-10-18 竖排/字形底修正（用户真机：竖排和 3 行韩文盖板下面露原文）
 *
 * 35%/60% 是参考项目给**横排**气泡的口径（`lineCount` = 横排行数）；直接套到竖排上
 * 会误伤：竖排天然很高，而且本项目竖排固定 `sourceLineCount = 1` → 35% 上限，
 * 一列稍长就被从下面截断。另一个真机现场（`mt185`）是 3 行韩文被检测器报成 1 行，
 * 35% 钳制把最后一行切掉 —— 盖板 = 35%，文字 = 39.79%。
 *
 * 现在的口径：
 * - **竖排不做高度钳制**（高度上限 100%），竖排的「宽度」才是异常尺寸的判据；
 * - 横排仍然钳，但**不得低于字形底 + 下沿外扩**：正常盖板 = 字形框 + 外扩，钳到
 *   这个下限以下一定是误伤；只有「盖板明显高于字形底」时才允许钳。
 *
 * 纯逻辑、可 JVM 单测。
 */
internal object BubblePlausibility {

    /** 单行气泡的盖板高度上限（占原图高比例）：参考项目 `maxH = lineCount > 1 ? 60 : 35`。 */
    const val MAX_COVER_HEIGHT_RATIO_SINGLE_LINE = 0.35f

    /** 多行气泡的盖板高度上限（占原图高比例）。 */
    const val MAX_COVER_HEIGHT_RATIO_MULTILINE = 0.60f

    /** 竖排的「高度」不按横排行数钳制：上限就是整张图（100%）。 */
    const val MAX_COVER_HEIGHT_PERCENT_VERTICAL = 100f

    /** 该行数下盖板高度的上限（百分比 0..100）。 */
    fun maxCoverHeightPercent(lineCount: Int): Float =
        if (lineCount > 1) MAX_COVER_HEIGHT_RATIO_MULTILINE * 100f
        else MAX_COVER_HEIGHT_RATIO_SINGLE_LINE * 100f

    /**
     * 盖板框高度超过合理性上限时，返回**钳高后的框**（`x, y, w, h`，保持上沿）；
     * 未超上限（或输入非法）返回 null —— 调用方原样使用，不做任何加工。
     *
     * @param vertical 竖排（OCR `layout=vertical`）：不做横排的 35%/60% 高度钳制。
     * @param glyphBottomPercent 字形框下沿 + 下沿外扩（原图百分比 0..100）。传入时
     *   作为钳制下限，保证任何情况下都不会把已经识别到的字形切掉；缺省（NaN）时
     *   退回旧行为（只有行数比例上限）。
     */
    fun clampCoverHeight(
        coverBoxPercent: FloatArray,
        lineCount: Int,
        vertical: Boolean = false,
        glyphBottomPercent: Float = Float.NaN
    ): FloatArray? {
        if (coverBoxPercent.size != 4) return null
        val height = coverBoxPercent[3]
        if (!height.isFinite()) return null
        val ratioCap = if (vertical) MAX_COVER_HEIGHT_PERCENT_VERTICAL else maxCoverHeightPercent(lineCount)
        val glyphFloor = if (glyphBottomPercent.isFinite()) {
            // 盖板上沿可能为负（跨图气泡），floor 仍是「到字形底需要的高度」。
            (glyphBottomPercent - coverBoxPercent[1]).coerceIn(0f, 100f)
        } else {
            0f
        }
        val maxHeight = maxOf(ratioCap, glyphFloor)
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
