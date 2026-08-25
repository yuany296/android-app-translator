package com.manga.translator.overlay

import android.graphics.Bitmap
import android.graphics.Color
import com.manga.translator.translate.Bubble
import com.manga.translator.translate.BubbleGeometry
import kotlin.math.sqrt

data class BubbleColors(
    val textColor: Int,
    val strokeColor: Int,
    val bgColor: Int = Color.WHITE
)

/**
 * 参考 C:\homework\translator 的 kakaopage 设计：
 * 1. 背景色 = 文字框边框区域各通道中值；
 * 2. 墨色 = 与背景 RGB 欧氏距离 >= 42 的像素，量化到 24 级桶取主导桶中心（支持彩色文字）；
 * 3. 可读性：墨色与背景对比度 >= 4.5 时保留原墨色，否则取对比度更高的纯黑/纯白；
 * 4. 描边 = 文字亮度 < 0.45 用白，否则用黑（与背景无关，保证文字轮廓清晰）。
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
        val textColor = chooseReadableTextColor(inkColor, bgColor)
        val strokeColor = if (relativeLuminance(textColor) < LUMINANCE_SPLIT) {
            Color.WHITE
        } else {
            Color.BLACK
        }
        return BubbleColors(textColor = textColor, strokeColor = strokeColor, bgColor = bgColor)
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

    private fun sampleInkColor(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        bgColor: Int
    ): Int {
        val bgR = (bgColor shr 16) and 0xFF
        val bgG = (bgColor shr 8) and 0xFF
        val bgB = bgColor and 0xFF
        val bucketCount = 64 * 64 * 64
        val buckets = IntArray(bucketCount.coerceAtMost(MAX_INK_SAMPLES * 2))
        var total = 0
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
                        val qr = (r / QUANT_STEP).coerceAtMost(63)
                        val qg = (g / QUANT_STEP).coerceAtMost(63)
                        val qb = (b / QUANT_STEP).coerceAtMost(63)
                        val bucket = (qr shl 12) or (qg shl 6) or qb
                        if (bucket < buckets.size) {
                            buckets[bucket]++
                            total++
                        }
                    }
                }
                x += step
            }
            y += step
        }
        if (total < MIN_INK_SAMPLES) {
            val bgLuma = luma(bgColor)
            return if (bgLuma >= LUMA_SPLIT) DARK_TEXT else LIGHT_TEXT
        }
        var bestBucket = 0
        var bestCount = 0
        for (index in buckets.indices) {
            if (buckets[index] > bestCount) {
                bestCount = buckets[index]
                bestBucket = index
            }
        }
        val cr = ((bestBucket shr 12) and 63) * QUANT_STEP + QUANT_STEP / 2
        val cg = ((bestBucket shr 6) and 63) * QUANT_STEP + QUANT_STEP / 2
        val cb = (bestBucket and 63) * QUANT_STEP + QUANT_STEP / 2
        return Color.rgb(cr.coerceIn(0, 255), cg.coerceIn(0, 255), cb.coerceIn(0, 255))
    }

    private fun chooseReadableTextColor(ink: Int, background: Int): Int {
        if (contrastRatio(ink, background) >= MIN_CONTRAST_RATIO) {
            return ink
        }
        val blackContrast = contrastRatio(Color.BLACK, background)
        val whiteContrast = contrastRatio(Color.WHITE, background)
        return if (blackContrast >= whiteContrast) Color.BLACK else Color.WHITE
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
        val DARK_TEXT = 0xFF181818.toInt()
        val LIGHT_TEXT = 0xFFE6E2DB.toInt()
    }
}