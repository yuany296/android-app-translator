package com.manga.translator.overlay

import android.graphics.Bitmap
import android.graphics.Color
import com.manga.translator.translate.Bubble
import com.manga.translator.translate.BubbleGeometry

data class BubbleColors(
    val textColor: Int,
    val strokeColor: Int
)

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

        val ringThicknessX = (boxW * RING_RATIO).toInt().coerceAtLeast(1)
        val ringThicknessY = (boxH * RING_RATIO).toInt().coerceAtLeast(1)
        val bgChannels = IntArray(3)
        var bgCount = 0
        fun accumulateChannel(array: IntArray, color: Int) {
            array[0] += (color shr 16) and 0xFF
            array[1] += (color shr 8) and 0xFF
            array[2] += color and 0xFF
        }
        for (y in top until bottom) {
            for (x in left until right) {
                val inTopRing = y < top + ringThicknessY
                val inBottomRing = y >= bottom - ringThicknessY
                val inLeftRing = x < left + ringThicknessX
                val inRightRing = x >= right - ringThicknessX
                if (!inTopRing && !inBottomRing && !inLeftRing && !inRightRing) continue
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) < ALPHA_THRESHOLD) continue
                accumulateChannel(bgChannels, pixel)
                bgCount++
            }
        }
        if (bgCount == 0) {
            return fallbackColors()
        }
        val bgColor = Color.rgb(
            (bgChannels[0] / bgCount).coerceIn(0, 255),
            (bgChannels[1] / bgCount).coerceIn(0, 255),
            (bgChannels[2] / bgCount).coerceIn(0, 255)
        )
        val bgLuma = luma(bgColor)

        val sampleStep = (boxW * boxH / MAX_SAMPLES).coerceAtLeast(1)
        val textChannels = IntArray(3)
        var textCount = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) >= ALPHA_THRESHOLD) {
                    val diff = kotlin.math.abs(luma(pixel) - bgLuma)
                    if (diff >= MIN_CONTRAST) {
                        accumulateChannel(textChannels, pixel)
                        textCount++
                    }
                }
                x += sampleStep
            }
            y += sampleStep
        }

        val textColor = if (textCount >= MIN_TEXT_SAMPLES) {
            Color.rgb(
                (textChannels[0] / textCount).coerceIn(0, 255),
                (textChannels[1] / textCount).coerceIn(0, 255),
                (textChannels[2] / textCount).coerceIn(0, 255)
            )
        } else {
            if (bgLuma >= LUMA_SPLIT) DARK_TEXT else LIGHT_TEXT
        }
        return BubbleColors(textColor = textColor, strokeColor = bgColor)
    }

    private fun luma(color: Int): Int {
        return ((299 * ((color shr 16) and 0xFF)) +
            (587 * ((color shr 8) and 0xFF)) +
            (114 * (color and 0xFF))) / 1000
    }

    private fun fallbackColors(): BubbleColors {
        return BubbleColors(textColor = DEFAULT_TEXT, strokeColor = DEFAULT_STROKE)
    }

    private companion object {
        const val RING_RATIO = 0.06f
        const val MAX_SAMPLES = 4096
        const val MIN_CONTRAST = 40
        const val MIN_TEXT_SAMPLES = 8
        const val ALPHA_THRESHOLD = 200
        const val LUMA_SPLIT = 128
        const val DEFAULT_TEXT = 0xFF181818.toInt()
        const val DEFAULT_STROKE = 0xFFE6E2DB.toInt()
        val DARK_TEXT = 0xFF181818.toInt()
        val LIGHT_TEXT = 0xFFE6E2DB.toInt()
    }
}