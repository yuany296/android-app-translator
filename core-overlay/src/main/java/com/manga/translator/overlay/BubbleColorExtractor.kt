package com.manga.translator.overlay

import android.graphics.Bitmap
import android.graphics.Color
import com.manga.translator.translate.Bubble
import com.manga.translator.translate.BubbleGeometry
import kotlin.math.sqrt

data class BubbleColors(
    val textColor: Int,
    val strokeColor: Int,
    val bgColor: Int = Color.WHITE,
    /**
     * 原文本身有没有描边（白边/黑边）。
     *
     * 2026-09-30 用户要求：「除了背景复杂，还有就是原文有的时候，也需要黑边白边」——
     * 原作者给文字加了描边就是一种排版意图，译文的风格要跟原文一致，
     * 不能因为「这次背景恰好是单色」就把描边吞掉。
     */
    val originalHadOutline: Boolean = false
)

/**
 * 墨色量化桶总数：每通道 6 bit（256 / 4 = 64 级）→ 64³。
 * 桶数组**必须**开这么大；开小了就会静默丢掉大半色彩空间（见 [BubbleColorExtractor.sampleInkColor] 注释）。
 */
internal const val INK_BUCKET_COUNT = 64 * 64 * 64

/**
 * 参考 C:\homework\translator 的 kakaopage 设计（`extension/src/rendering/bubble-colors.js`）：
 * 1. 背景色 = 文字框边框区域各通道中值；
 * 2. 墨色 = 与背景 RGB 欧氏距离 >= 42 的像素，量化到 24 级桶取主导桶中心（支持彩色文字）；
 * 3. 可读性：墨色与背景对比度 >= 4.5 **或彩色（色度 >= 40）** 时保留原墨色
 *    （彩色字的可读性由必渲染的描边保证），否则取对比度更高的纯黑/纯白；
 * 4. 描边 = 文字亮度 < 0.45 用白，否则用黑（与背景无关，保证文字轮廓清晰）；
 * 5. 白描边守卫：主导桶若「无彩且近白」，说明采到的是白描边而不是笔画，
 *    在其余桶里找「有色度 + 离背景够远 + 像素数够」的彩色候选让位（2026-09-28 对齐 v4）。
 */
class BubbleColorExtractor {
    fun extract(bitmap: Bitmap, bubble: Bubble): BubbleColors {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return fallbackColors()
        }
        val rect = BubbleGeometry.contentRect(bubble)
        val left = ((rect.left / 100f) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = ((rect.top / 100f) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = ((rect.right / 100f) * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ((rect.bottom / 100f) * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        val boxW = right - left
        val boxH = bottom - top
        if (boxW < 3 || boxH < 3) {
            return fallbackColors()
        }

        val bgColor = sampleBackgroundMedian(bitmap, left, top, right, bottom, boxW, boxH)
            ?: return fallbackColors()
        val inkColor = sampleInkColor(bitmap, left, top, right, bottom, bgColor)
        val textColor = chooseReadableTextColor(inkColor.color, bgColor)
        val strokeColor = if (relativeLuminance(textColor) < LUMINANCE_SPLIT) {
            Color.WHITE
        } else {
            Color.BLACK
        }
        return BubbleColors(
            textColor = textColor,
            strokeColor = strokeColor,
            bgColor = bgColor,
            originalHadOutline = originalHadOutline(inkColor, textColor)
        )
    }

    private fun sampleBackgroundMedian(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        boxW: Int,
        boxH: Int
    ): Int? {
        val thicknessX = (boxW * RING_RATIO).toInt().coerceAtLeast(1)
        val thicknessY = (boxH * RING_RATIO).toInt().coerceAtLeast(1)
        val rs = IntArray((boxW * 2 * thicknessY + boxH * 2 * thicknessX).coerceAtMost(MAX_BG_SAMPLES))
        val gs = IntArray(rs.size)
        val bs = IntArray(rs.size)
        var count = 0
        for (y in top until bottom) {
            val inTopRing = y < top + thicknessY
            val inBottomRing = y >= bottom - thicknessY
            if (!inTopRing && !inBottomRing) continue
            for (x in left until right) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) < ALPHA_THRESHOLD) continue
                if (count < rs.size) {
                    rs[count] = (pixel shr 16) and 0xFF
                    gs[count] = (pixel shr 8) and 0xFF
                    bs[count] = pixel and 0xFF
                    count++
                }
            }
        }
        for (x in left until right) {
            val inLeftRing = x < left + thicknessX
            val inRightRing = x >= right - thicknessX
            if (!inLeftRing && !inRightRing) continue
            for (y in top until bottom) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) < ALPHA_THRESHOLD) continue
                if (count < rs.size) {
                    rs[count] = (pixel shr 16) and 0xFF
                    gs[count] = (pixel shr 8) and 0xFF
                    bs[count] = pixel and 0xFF
                    count++
                }
            }
        }
        if (count < MIN_BG_SAMPLES) return null
        val sliceR = rs.copyOf(count)
        val sliceG = gs.copyOf(count)
        val sliceB = bs.copyOf(count)
        sliceR.sort()
        sliceG.sort()
        sliceB.sort()
        val mid = count / 2
        return Color.rgb(sliceR[mid], sliceG[mid], sliceB[mid])
    }

    /**
     * 「离背景足够远」的像素（= 笔画 + 描边）统计。
     *
     * 为什么要把近白/近黑单独数出来：描边在**墨迹像素**里的占比是它唯一的可靠特征
     * —— 白边环 + 深色笔画时，近白像素会占到墨迹的两三成，而纯深色字一个近白像素都没有。
     * 见 [originalHadOutline]。
     */
    internal data class InkSampling(
        val color: Int,
        val inkPixels: Int,
        val nearWhitePixels: Int,
        val nearBlackPixels: Int
    )

    private fun sampleInkColor(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        bgColor: Int
    ): InkSampling {
        val bgR = (bgColor shr 16) and 0xFF
        val bgG = (bgColor shr 8) and 0xFF
        val bgB = bgColor and 0xFF
        // 量化桶空间必须是完整的 64³ = 262144。
        // ⚠️ 历史 bug（2026-09-29 用户反馈「粉红色的字没有变红」）：这里曾写成
        // `IntArray(bucketCount.coerceAtMost(MAX_INK_SAMPLES * 2))` → 数组只有 16384 个格子，
        // 而 bucket 索引最大到 262143，配合下面的 `if (bucket < buckets.size)` 守卫，
        // 等于**把所有红通道 >= 16 的像素全部丢掉** —— 只有接近纯黑（r<16）的墨色能被统计到。
        // 后果：彩色文字（粉字 #e0447c、深蓝 #143c6e…）永远凑不够 MIN_INK_SAMPLES，
        // 一律退化到 DARK_TEXT #181818，看起来就是「彩色字被压成黑色」。
        val buckets = IntArray(INK_BUCKET_COUNT)
        var total = 0
        // 描边证据：在「离背景足够远」的像素里，近白/近黑的各有多少（见 [originalHadOutline]）。
        var nearWhite = 0
        var nearBlack = 0
        val step = ((right - left) * (bottom - top) / MAX_INK_SAMPLES).coerceAtLeast(1)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) >= ALPHA_THRESHOLD) {
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val dr = r - bgR
                    val dg = g - bgG
                    val db = b - bgB
                    val distance = sqrt((dr * dr + dg * dg + db * db).toFloat())
                    if (distance >= INK_DISTANCE_THRESHOLD) {
                        val bucket = inkBucketIndex(r, g, b)
                        buckets[bucket]++
                        total++
                        if (isNearWhitePixel(r, g, b)) nearWhite++
                        if (isNearBlackPixel(r, g, b)) nearBlack++
                    }
                }
                x += step
            }
            y += step
        }
        if (total < MIN_INK_SAMPLES) {
            val bgLuma = luma(bgColor)
            return InkSampling(
                color = if (bgLuma >= LUMA_SPLIT) DARK_TEXT else LIGHT_TEXT,
                inkPixels = total,
                nearWhitePixels = 0,
                nearBlackPixels = 0
            )
        }
        // 先按像素数降序，跳过「与背景对比度 < 2」的桶（浅底上的白描边必然被跳过），
        // 再套白描边守卫；两者都不满足时回退像素数最多者。
        val ranked = buckets.indices
            .filter { buckets[it] > 0 }
            .sortedByDescending { buckets[it] }
        val dominant = ranked.firstOrNull { index ->
            contrastRatio(bucketColor(index), bgColor) >= INK_BACKGROUND_MIN_CONTRAST
        } ?: ranked.firstOrNull() ?: return InkSampling(
            color = fallbackInk(bgColor),
            inkPixels = total,
            nearWhitePixels = nearWhite,
            nearBlackPixels = nearBlack
        )
        val best = resolveWhiteOutlineSuspicion(ranked, dominant, buckets, bgColor) ?: dominant
        return InkSampling(
            color = bucketColor(best),
            inkPixels = total,
            nearWhitePixels = nearWhite,
            nearBlackPixels = nearBlack
        )
    }

    /**
     * 原文本身有没有描边（2026-09-30 用户要求「原文有的时候，也需要黑边白边」）。
     *
     * 判据：在「离背景足够远」的像素里，近白/近黑占多大比例。
     * - 纯深色字：一个近白像素都没有 → 判无描边；
     * - 深色字 + 白描边：白边环本身就是一大批近白像素 → 判有描边；
     * - **白字** / **黑字**：近白/近黑占比也很高，但那是字身不是描边 ——
     *   用「文字色本身是不是近白/近黑」把它们排除掉（否则纯白字会被当成「有白边」）。
     * - 黑字 + 白描边、白字 + 黑描边：另一侧的占比照样能命中，所以两种组合都能识别。
     */
    internal fun originalHadOutline(sampling: InkSampling, textColor: Int): Boolean {
        if (sampling.inkPixels < MIN_INK_SAMPLES) return false
        val ratio = { count: Int -> count.toFloat() / sampling.inkPixels.toFloat() }
        val textIsNearWhite = isNearWhitePixel(
            (textColor shr 16) and 0xFF,
            (textColor shr 8) and 0xFF,
            textColor and 0xFF
        )
        val textIsNearBlack = isNearBlackPixel(
            (textColor shr 16) and 0xFF,
            (textColor shr 8) and 0xFF,
            textColor and 0xFF
        )
        val whiteOutline = !textIsNearWhite &&
            ratio(sampling.nearWhitePixels) >= OUTLINE_MIN_RATIO
        val blackOutline = !textIsNearBlack &&
            ratio(sampling.nearBlackPixels) >= OUTLINE_MIN_RATIO
        return whiteOutline || blackOutline
    }

    /** 近白：无彩且每个通道都够亮（与 [WHITE_OUTLINE_MAX_CHROMA]/[NEAR_WHITE_MIN_CHANNEL] 同口径）。 */
    internal fun isNearWhitePixel(r: Int, g: Int, b: Int): Boolean {
        return minOf(r, g, b) >= NEAR_WHITE_MIN_CHANNEL && maxOf(r, g, b) - minOf(r, g, b) < WHITE_OUTLINE_MAX_CHROMA
    }

    /**
     * 近黑：够暗**且无彩**。用「无彩」把深蓝/深红这类**彩色深字**排除掉
     * ——否则深蓝字（#163E6E，max 通道 110）会被误认成黑描边。
     */
    internal fun isNearBlackPixel(r: Int, g: Int, b: Int): Boolean {
        return maxOf(r, g, b) <= NEAR_BLACK_MAX_CHANNEL &&
            maxOf(r, g, b) - minOf(r, g, b) < BLACK_OUTLINE_MAX_CHROMA
    }

    private fun bucketColor(bucket: Int): Int {
        val cr = ((bucket shr 12) and 63) * QUANT_STEP + QUANT_STEP / 2
        val cg = ((bucket shr 6) and 63) * QUANT_STEP + QUANT_STEP / 2
        val cb = (bucket and 63) * QUANT_STEP + QUANT_STEP / 2
        return Color.rgb(cr.coerceIn(0, 255), cg.coerceIn(0, 255), cb.coerceIn(0, 255))
    }

    /** `(r,g,b)` → 量化桶下标；值域恒在 `0 until INK_BUCKET_COUNT` 内（由单测锁定）。 */
    internal fun inkBucketIndex(r: Int, g: Int, b: Int): Int {
        val qr = (r / QUANT_STEP).coerceIn(0, 63)
        val qg = (g / QUANT_STEP).coerceIn(0, 63)
        val qb = (b / QUANT_STEP).coerceIn(0, 63)
        return (qr shl 12) or (qg shl 6) or qb
    }

    private fun fallbackInk(bgColor: Int): Int {
        return if (luma(bgColor) >= LUMA_SPLIT) DARK_TEXT else LIGHT_TEXT
    }

    /**
     * 白描边守卫（对齐参考项目 `resolveWhiteOutlineSuspicion`）：
     * 主导桶「无彩（chroma < 20）且近白（min 通道 >= 200）」时，找彩色笔画候选
     * —— 色度 >= 40、与背景 RGB 距离 >= 60、像素数 >= max(64, 全部墨色像素的 0.5%)
     * 且 >= 主导桶的 4% —— 找到就让位给色度最高者。没有就维持主导桶。
     */
    private fun resolveWhiteOutlineSuspicion(
        ranked: List<Int>,
        dominant: Int,
        buckets: IntArray,
        bgColor: Int
    ): Int? {
        val dominantColor = bucketColor(dominant)
        if (minChannel(dominantColor) < NEAR_WHITE_MIN_CHANNEL) return null
        if (colorChroma(dominantColor) >= WHITE_OUTLINE_MAX_CHROMA) return null
        val totalInk = ranked.sumOf { buckets[it] }
        val minimumByInk = maxOf(STROKE_GUARD_MIN_BUCKET_COUNT, (totalInk * STROKE_GUARD_TOTAL_INK_RATIO).toInt())
        val minimumByTop = (buckets[dominant] * STROKE_GUARD_TOP_COUNT_RATIO).toInt()
        var best: Int? = null
        ranked.forEach { bucket ->
            if (bucket == dominant) return@forEach
            if (buckets[bucket] < minimumByInk || buckets[bucket] < minimumByTop) return@forEach
            val color = bucketColor(bucket)
            if (colorChroma(color) < STROKE_GUARD_COLOURED_CHROMA) return@forEach
            if (rgbDistance(color, bgColor) < STROKE_GUARD_MIN_BG_DISTANCE) return@forEach
            val current = best?.let { bucketColor(it) }
            val better = current == null ||
                colorChroma(color) > colorChroma(current) ||
                (colorChroma(color) == colorChroma(current) && buckets[bucket] > buckets[best!!])
            if (better) best = bucket
        }
        return best
    }

    /**
     * 保留原墨色的判据（对齐参考项目 `chooseReadableTextColors`）：
     * 对比度 >= 4.5 **或** 彩色（色度 >= 40）→ 直接用原墨色；
     * 否则按对比度在黑/白里选（彩色字即便与背景亮度接近也保留原色，可读性交给描边）。
     */
    internal fun chooseReadableTextColor(ink: Int, background: Int): Int {
        if (contrastRatio(ink, background) >= MIN_CONTRAST_RATIO || colorChroma(ink) >= COLOURED_CHROMA) {
            return ink
        }
        val blackContrast = contrastRatio(Color.BLACK, background)
        val whiteContrast = contrastRatio(Color.WHITE, background)
        return if (blackContrast >= whiteContrast) Color.BLACK else Color.WHITE
    }

    /** 色度 = max 通道 - min 通道（参考项目 `colorChroma`）。 */
    internal fun colorChroma(color: Int): Int = maxChannel(color) - minChannel(color)

    private fun maxChannel(color: Int): Int {
        return maxOf((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
    }

    private fun minChannel(color: Int): Int {
        return minOf((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
    }

    private fun rgbDistance(first: Int, second: Int): Float {
        val dr = ((first shr 16) and 0xFF) - ((second shr 16) and 0xFF)
        val dg = ((first shr 8) and 0xFF) - ((second shr 8) and 0xFF)
        val db = (first and 0xFF) - (second and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    private fun relativeLuminance(color: Int): Float {
        val r = channelLinear((color shr 16) and 0xFF)
        val g = channelLinear((color shr 8) and 0xFF)
        val b = channelLinear(color and 0xFF)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun channelLinear(channel: Int): Float {
        val value = channel / 255f
        return if (value <= 0.04045f) {
            value / 12.92f
        } else {
            java.lang.Math.pow((value + 0.055f) / 1.055f.toDouble(), 2.4).toFloat()
        }
    }

    private fun contrastRatio(first: Int, second: Int): Float {
        val high = maxOf(relativeLuminance(first), relativeLuminance(second))
        val low = minOf(relativeLuminance(first), relativeLuminance(second))
        return (high + 0.05f) / (low + 0.05f)
    }

    private fun luma(color: Int): Int {
        return ((299 * ((color shr 16) and 0xFF)) +
            (587 * ((color shr 8) and 0xFF)) +
            (114 * (color and 0xFF))) / 1000
    }

    private fun fallbackColors(): BubbleColors {
        return BubbleColors(textColor = DEFAULT_TEXT, strokeColor = DEFAULT_STROKE, bgColor = Color.WHITE)
    }

    private companion object {
        const val RING_RATIO = 0.08f
        const val MAX_BG_SAMPLES = 8192
        const val MIN_BG_SAMPLES = 16
        const val MAX_INK_SAMPLES = 8192
        const val MIN_INK_SAMPLES = 8
        const val INK_DISTANCE_THRESHOLD = 42f
        const val QUANT_STEP = 4
        const val MIN_CONTRAST_RATIO = 4.5f
        const val LUMINANCE_SPLIT = 0.45f
        const val ALPHA_THRESHOLD = 200
        const val LUMA_SPLIT = 128
        const val DEFAULT_TEXT = 0xFF181818.toInt()
        const val DEFAULT_STROKE = 0xFFFFFFFF.toInt()

        // 彩色字保留线 / 白描边守卫（与参考项目同名常量同值）
        const val COLOURED_CHROMA = 40
        const val INK_BACKGROUND_MIN_CONTRAST = 2f
        const val WHITE_OUTLINE_MAX_CHROMA = 20
        const val NEAR_WHITE_MIN_CHANNEL = 200
        const val STROKE_GUARD_MIN_BUCKET_COUNT = 64
        const val STROKE_GUARD_COLOURED_CHROMA = 40
        const val STROKE_GUARD_MIN_BG_DISTANCE = 60f
        const val STROKE_GUARD_TOTAL_INK_RATIO = 0.005
        const val STROKE_GUARD_TOP_COUNT_RATIO = 0.04

        /**
         * 判定「原文自带描边」所需的近白/近黑像素占墨迹的最小比例。
         *
         * 取值依据：白描边环的面积 ≈ 字形周长 × 边宽 ≈ 4×字高×边宽，字身面积 ≈ 0.5×字高²
         * —— 边宽取字高的 8%（项目渲染描边比例）时环/字身 ≈ 0.6，即描边像素占墨迹两成以上；
         * 而无描边时该比例是 0。0.15 落在这个巨大空档里，留了抗锯齿/压缩噪声的余量。
         */
        const val OUTLINE_MIN_RATIO = 0.15f

        /** 近黑判据：三通道都不超过它。 */
        const val NEAR_BLACK_MAX_CHANNEL = 64

        /** 近黑判据：三通道极差不超过它（保证是中性黑，不是深彩色）。 */
        const val BLACK_OUTLINE_MAX_CHROMA = 40
        val DARK_TEXT = 0xFF181818.toInt()
        val LIGHT_TEXT = 0xFFE6E2DB.toInt()
    }
}