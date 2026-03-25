package com.manga.translator.overlay.erase

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.manga.translator.translate.Bubble
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class EraseComplexity(
    val variance: Float,
    val edgeDensity: Float,
    val isComplex: Boolean
)

class EraseComplexityAnalyzer {
    fun analyze(bitmap: Bitmap, bubble: Bubble): EraseComplexity {
        val roi = bubbleToRoi(bitmap, bubble)
        if (roi.width() < 4 || roi.height() < 4) {
            return EraseComplexity(0f, 0f, isComplex = false)
        }

        var sum = 0f
        var sumSq = 0f
        var count = 0
        val strideX = max(1, roi.width() / 20)
        val strideY = max(1, roi.height() / 20)
        var edgeCount = 0
        var compareCount = 0
        val edgeThreshold = 42

        var y = roi.top
        while (y < roi.bottom) {
            var x = roi.left
            while (x < roi.right) {
                val p = bitmap.getPixel(x, y)
                val luma = 0.299f * p.red + 0.587f * p.green + 0.114f * p.blue
                sum += luma
                sumSq += luma * luma
                count += 1

                if (x + strideX < roi.right) {
                    val px = bitmap.getPixel(x + strideX, y)
                    val lx = 0.299f * px.red + 0.587f * px.green + 0.114f * px.blue
                    if (abs(luma - lx) >= edgeThreshold) edgeCount += 1
                    compareCount += 1
                }
                if (y + strideY < roi.bottom) {
                    val py = bitmap.getPixel(x, y + strideY)
                    val ly = 0.299f * py.red + 0.587f * py.green + 0.114f * py.blue
                    if (abs(luma - ly) >= edgeThreshold) edgeCount += 1
                    compareCount += 1
                }

                x += strideX
            }
            y += strideY
        }

        if (count == 0) {
            return EraseComplexity(0f, 0f, isComplex = false)
        }
        val mean = sum / count
        val variance = (sumSq / count) - mean * mean
        val edgeDensity = if (compareCount > 0) edgeCount.toFloat() / compareCount.toFloat() else 0f
        val transparentLike = bubble.bgType.equals("transparent", ignoreCase = true) ||
            bubble.bgType.equals("none", ignoreCase = true)
        val diagonalLike = roi.width() > 0 && roi.height() > 0 &&
            (max(roi.width(), roi.height()).toFloat() / min(roi.width(), roi.height()).toFloat() >= 2.3f)
        val isComplex = transparentLike || diagonalLike || variance >= 680f || edgeDensity >= 0.26f
        return EraseComplexity(
            variance = variance,
            edgeDensity = edgeDensity,
            isComplex = isComplex
        )
    }

    fun bubbleToRoi(bitmap: Bitmap, bubble: Bubble, extraPaddingPx: Int = 3): Rect {
        val left = ((bubble.x / 100f) * bitmap.width).toInt()
        val top = ((bubble.y / 100f) * bitmap.height).toInt()
        val width = ((bubble.w / 100f) * bitmap.width).toInt().coerceAtLeast(1)
        val height = ((bubble.h / 100f) * bitmap.height).toInt().coerceAtLeast(1)
        return Rect(
            (left - extraPaddingPx).coerceAtLeast(0),
            (top - extraPaddingPx).coerceAtLeast(0),
            (left + width + extraPaddingPx).coerceAtMost(bitmap.width),
            (top + height + extraPaddingPx).coerceAtMost(bitmap.height)
        )
    }
}

