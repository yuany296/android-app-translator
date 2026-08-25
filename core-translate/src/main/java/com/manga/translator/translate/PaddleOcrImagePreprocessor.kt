package com.manga.translator.translate

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 参考 C:\homework\translator 的本地 OCR 预处理策略，
 * 为彩色/艺术字/低对比度文本生成更利于检测/识别的图像变体。
 *
 * 主要变体：
 * 1. 增强灰度：grayscale + autocontrast(cutoff=1%) + contrast×2.2
 * 2. 反色增强灰度：同上并反色（用于浅色字/霓虹字）
 * 3. 二值文本掩码：把深色中性字与红褐字保留为黑，其余变白
 */
object PaddleOcrImagePreprocessor {

    /**
     * 增强灰度：把原图转成灰度后做 1% cutoff 的自动对比度拉伸，再增强对比度。
     * 对彩色大字、描边字、渐变填充字有较好的归一效果。
     */
    fun buildEnhancedGrayscale(original: Bitmap, invert: Boolean = false): Bitmap {
        val w = original.width
        val h = original.height
        val total = w * h
        val pixels = IntArray(total)
        original.getPixels(pixels, 0, w, 0, 0, w, h)

        val hist = IntArray(256)
        val lumas = FloatArray(total)
        for (i in pixels.indices) {
            val px = pixels[i]
            val luma = luminanceOf(px)
            lumas[i] = luma
            hist[luma.toInt().coerceIn(0, 255)]++
        }

        val cutoffLow = max(1, (total * AUTOCONTRAST_CUTOFF).toInt())
        val cutoffHigh = max(1, (total * AUTOCONTRAST_CUTOFF).toInt())
        var low = 0
        var cum = 0
        while (low < 255 && cum < cutoffLow) {
            cum += hist[low]
            low++
        }
        var high = 255
        cum = 0
        while (high > 0 && cum < cutoffHigh) {
            cum += hist[high]
            high--
        }
        if (low >= high) {
            low = 0
            high = 255
        }

        val range = (high - low).toFloat().coerceAtLeast(1f)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(total)
        for (i in pixels.indices) {
            var v = (lumas[i] - low) / range
            if (invert) v = 1f - v
            v = ((v - 0.5f) * CONTRAST_FACTOR + 0.5f).coerceIn(0f, 1f)
            val iv = (v * 255).roundToInt()
            outPixels[i] = 0xFF000000.toInt() or (iv shl 16) or (iv shl 8) or iv
        }
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * OTSU 二值化：对灰度图自动计算最佳阈值，适合有明显前景/背景对比的彩色大字。
     */
    fun buildOtsuBinaryMask(original: Bitmap): Bitmap {
        val w = original.width
        val h = original.height
        val total = w * h
        val pixels = IntArray(total)
        original.getPixels(pixels, 0, w, 0, 0, w, h)

        val hist = IntArray(256)
        var sum = 0L
        for (px in pixels) {
            val l = luminanceOf(px).toInt().coerceIn(0, 255)
            hist[l]++
            sum += l
        }

        var sumB = 0L
        var wB = 0
        var maxVar = 0f
        var threshold = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB / wB.toFloat()
            val mF = (sum - sumB) / wF.toFloat()
            val varBetween = wB * wF * (mB - mF) * (mB - mF)
            if (varBetween > maxVar) {
                maxVar = varBetween
                threshold = t
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(total)
        for (i in pixels.indices) {
            val l = luminanceOf(pixels[i]).toInt().coerceIn(0, 255)
            val c = if (l <= threshold) 0 else 255
            outPixels[i] = 0xFF000000.toInt() or (c shl 16) or (c shl 8) or c
        }
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 二值文本掩码：参考 translator 的 build_binary_text_image。
     * 保留深色中性字与红褐字，其余置白；对彩色艺术字的黑色/深色描边与填充有效。
     */
    fun buildBinaryTextMask(original: Bitmap): Bitmap {
        val w = original.width
        val h = original.height
        val total = w * h
        val pixels = IntArray(total)
        original.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(total)
        for (i in pixels.indices) {
            val px = pixels[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val brightness = (r + g + b) / 3f
            val minCh = min(min(r, g), b)
            val maxCh = max(max(r, g), b)
            val saturation = maxCh - minCh

            val darkNeutral = brightness < DARK_NEUTRAL_BRIGHTNESS && saturation < DARK_NEUTRAL_SATURATION
            val redBrownText = r > RED_BROWN_MIN_R &&
                r >= g + RED_BROWN_RG_GAP &&
                r >= b + RED_BROWN_RB_GAP &&
                brightness < RED_BROWN_MAX_BRIGHTNESS

            val isText = darkNeutral || redBrownText
            val c = if (isText) 0 else 255
            outPixels[i] = 0xFF000000.toInt() or (c shl 16) or (c shl 8) or c
        }
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun luminanceOf(px: Int): Float {
        val r = (px shr 16) and 0xFF
        val g = (px shr 8) and 0xFF
        val b = px and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private const val AUTOCONTRAST_CUTOFF = 0.01f
    private const val CONTRAST_FACTOR = 2.2f

    private const val DARK_NEUTRAL_BRIGHTNESS = 205f
    private const val DARK_NEUTRAL_SATURATION = 86f
    private const val RED_BROWN_MIN_R = 70
    private const val RED_BROWN_RG_GAP = 16
    private const val RED_BROWN_RB_GAP = 16
    private const val RED_BROWN_MAX_BRIGHTNESS = 214f
}
