package com.manga.translator.overlay

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import kotlin.math.max
import kotlin.math.min

class WritableAreaRefiner {
    fun refine(
        source: Bitmap,
        srcRect: RectF,
        bubbleType: String
    ): RectF {
        val raw = clampRect(srcRect, source.width, source.height)
        if (raw.width() <= 2f || raw.height() <= 2f) {
            return raw
        }
        return when {
            bubbleType.equals("solid", ignoreCase = true) -> refineSolid(source, raw)
            bubbleType.equals("transparent", ignoreCase = true) ||
                bubbleType.equals("none", ignoreCase = true) -> shiftOpticalCenter(raw, upwardRatio = 0.045f)

            else -> raw
        }
    }

    private fun refineSolid(source: Bitmap, raw: RectF): RectF {
        val roi = Rect(
            raw.left.toInt().coerceIn(0, source.width - 1),
            raw.top.toInt().coerceIn(0, source.height - 1),
            raw.right.toInt().coerceIn(1, source.width),
            raw.bottom.toInt().coerceIn(1, source.height)
        )
        val maxInsetX = max(1, (roi.width() * MAX_INSET_RATIO_X).toInt())
        val maxInsetY = max(1, (roi.height() * MAX_INSET_RATIO_Y).toInt())

        val leftInset = scanInset(
            limit = maxInsetX,
            sampler = { inset -> sampleVerticalStrip(source, roi, inset, fromLeft = true) }
        )
        val rightInset = scanInset(
            limit = maxInsetX,
            sampler = { inset -> sampleVerticalStrip(source, roi, inset, fromLeft = false) }
        )
        val topInset = scanInset(
            limit = maxInsetY,
            sampler = { inset -> sampleHorizontalStrip(source, roi, inset, fromTop = true) }
        )
        val bottomInset = scanInset(
            limit = maxInsetY,
            sampler = { inset -> sampleHorizontalStrip(source, roi, inset, fromTop = false) }
        )

        val refined = RectF(
            roi.left + leftInset.toFloat(),
            roi.top + topInset.toFloat(),
            roi.right - rightInset.toFloat(),
            roi.bottom - bottomInset.toFloat()
        )
        return enforceMinimumSize(raw, refined)
    }

    private fun scanInset(
        limit: Int,
        sampler: (Int) -> StripSignal
    ): Int {
        var inset = 0
        while (inset < limit) {
            val signal = sampler(inset)
            val shouldShrink = signal.averageLuma < BRIGHT_LUMA_THRESHOLD ||
                signal.darkRatio > DARK_RATIO_THRESHOLD ||
                signal.contrast > CONTRAST_THRESHOLD
            if (!shouldShrink) {
                break
            }
            inset += STRIP_STEP_PX
        }
        return inset.coerceAtMost(limit)
    }

    private fun sampleVerticalStrip(
        source: Bitmap,
        roi: Rect,
        inset: Int,
        fromLeft: Boolean
    ): StripSignal {
        val xStart = if (fromLeft) roi.left + inset else roi.right - inset - STRIP_THICKNESS_PX
        val xEnd = (xStart + STRIP_THICKNESS_PX).coerceIn(roi.left + 1, roi.right)
        val yStart = (roi.top + roi.height() * SAMPLE_BAND_START_RATIO).toInt().coerceIn(roi.top, roi.bottom - 1)
        val yEnd = (roi.top + roi.height() * SAMPLE_BAND_END_RATIO).toInt().coerceIn(yStart + 1, roi.bottom)
        return sampleStrip(source, xStart.coerceIn(roi.left, roi.right - 1), xEnd, yStart, yEnd)
    }

    private fun sampleHorizontalStrip(
        source: Bitmap,
        roi: Rect,
        inset: Int,
        fromTop: Boolean
    ): StripSignal {
        val yStart = if (fromTop) roi.top + inset else roi.bottom - inset - STRIP_THICKNESS_PX
        val yEnd = (yStart + STRIP_THICKNESS_PX).coerceIn(roi.top + 1, roi.bottom)
        val xStart = (roi.left + roi.width() * SAMPLE_BAND_START_RATIO).toInt().coerceIn(roi.left, roi.right - 1)
        val xEnd = (roi.left + roi.width() * SAMPLE_BAND_END_RATIO).toInt().coerceIn(xStart + 1, roi.right)
        return sampleStrip(source, xStart, xEnd, yStart.coerceIn(roi.top, roi.bottom - 1), yEnd)
    }

    private fun sampleStrip(
        source: Bitmap,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int
    ): StripSignal {
        var lumaSum = 0f
        var darkCount = 0
        var contrastSum = 0f
        var samples = 0
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                val color = source.getPixel(x, y)
                val luma = luminance(color)
                lumaSum += luma
                if (luma < DARK_LUMA_THRESHOLD) {
                    darkCount += 1
                }
                if (x + 1 < xEnd) {
                    contrastSum += kotlin.math.abs(luma - luminance(source.getPixel(x + 1, y)))
                }
                if (y + 1 < yEnd) {
                    contrastSum += kotlin.math.abs(luma - luminance(source.getPixel(x, y + 1)))
                }
                samples += 1
            }
        }
        if (samples == 0) {
            return StripSignal(BRIGHT_LUMA_THRESHOLD, 0f, 0f)
        }
        return StripSignal(
            averageLuma = lumaSum / samples.toFloat(),
            darkRatio = darkCount.toFloat() / samples.toFloat(),
            contrast = contrastSum / samples.toFloat()
        )
    }

    private fun shiftOpticalCenter(rect: RectF, upwardRatio: Float): RectF {
        val shift = rect.height() * upwardRatio
        val newTop = max(0f, rect.top - shift)
        val newBottom = newTop + rect.height()
        return if (newBottom <= rect.bottom) {
            RectF(rect.left, newTop, rect.right, newBottom)
        } else {
            RectF(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    private fun enforceMinimumSize(raw: RectF, refined: RectF): RectF {
        val minWidth = raw.width() * MIN_REFINED_WIDTH_RATIO
        val minHeight = raw.height() * MIN_REFINED_HEIGHT_RATIO
        val width = refined.width().coerceAtLeast(minWidth)
        val height = refined.height().coerceAtLeast(minHeight)
        val centerX = refined.centerX()
        val centerY = refined.centerY()
        val safe = RectF(
            centerX - width / 2f,
            centerY - height / 2f,
            centerX + width / 2f,
            centerY + height / 2f
        )
        return RectF(
            safe.left.coerceAtLeast(raw.left),
            safe.top.coerceAtLeast(raw.top),
            safe.right.coerceAtMost(raw.right),
            safe.bottom.coerceAtMost(raw.bottom)
        )
    }

    private fun clampRect(rect: RectF, sourceWidth: Int, sourceHeight: Int): RectF {
        val left = rect.left.coerceIn(0f, sourceWidth.toFloat())
        val top = rect.top.coerceIn(0f, sourceHeight.toFloat())
        val right = rect.right.coerceIn((left + 1f).coerceAtMost(sourceWidth.toFloat()), sourceWidth.toFloat())
        val bottom = rect.bottom.coerceIn((top + 1f).coerceAtMost(sourceHeight.toFloat()), sourceHeight.toFloat())
        return RectF(left, top, right, bottom)
    }

    private fun luminance(color: Int): Float {
        return (color.red * 0.299f) + (color.green * 0.587f) + (color.blue * 0.114f)
    }

    private data class StripSignal(
        val averageLuma: Float,
        val darkRatio: Float,
        val contrast: Float
    )

    private companion object {
        private const val STRIP_THICKNESS_PX = 2
        private const val STRIP_STEP_PX = 1
        private const val SAMPLE_BAND_START_RATIO = 0.22f
        private const val SAMPLE_BAND_END_RATIO = 0.78f
        private const val MAX_INSET_RATIO_X = 0.18f
        private const val MAX_INSET_RATIO_Y = 0.16f
        private const val BRIGHT_LUMA_THRESHOLD = 208f
        private const val DARK_LUMA_THRESHOLD = 164f
        private const val DARK_RATIO_THRESHOLD = 0.18f
        private const val CONTRAST_THRESHOLD = 22f
        private const val MIN_REFINED_WIDTH_RATIO = 0.58f
        private const val MIN_REFINED_HEIGHT_RATIO = 0.54f
    }
}
