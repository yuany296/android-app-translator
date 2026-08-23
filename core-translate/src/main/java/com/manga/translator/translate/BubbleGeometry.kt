package com.manga.translator.translate

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float
        get() = (right - left).coerceAtLeast(0f)

    val height: Float
        get() = (bottom - top).coerceAtLeast(0f)
}

enum class BubbleMergeScenario {
    GENERIC_TEXT,
    WITHIN_BUBBLE
}

data class BubbleMergeCandidate(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val verticalHint: Boolean
) {
    val width: Float
        get() = (right - left).coerceAtLeast(0.1f)

    val height: Float
        get() = (bottom - top).coerceAtLeast(0.1f)

    val centerX: Float
        get() = (left + right) / 2f

    val centerY: Float
        get() = (top + bottom) / 2f

    val area: Float
        get() = width * height
}

data class BubbleMergeDecision(
    val merge: Boolean,
    val reason: String
)

object BubbleGeometry {
    fun outerRect(bubble: Bubble): NormalizedRect {
        return normalizedRectOf(
            x = bubble.x,
            y = bubble.y,
            w = bubble.w,
            h = bubble.h
        )
    }

    fun contentRect(bubble: Bubble): NormalizedRect {
        return normalizedRectOf(
            x = bubble.contentX,
            y = bubble.contentY,
            w = bubble.contentW,
            h = bubble.contentH
        )
    }

    fun normalizedRectOf(x: Float, y: Float, w: Float, h: Float): NormalizedRect {
        val left = clamp(x, 0f, 100f)
        val top = clamp(y, 0f, 100f)
        val width = clamp(w, 0f, 100f)
        val height = clamp(h, 0f, 100f)
        val right = clamp(left + width, 0f, 100f).coerceAtLeast(left)
        val bottom = clamp(top + height, 0f, 100f).coerceAtLeast(top)
        if (right > left && bottom > top) {
            return NormalizedRect(left, top, right, bottom)
        }
        val safeRight = clamp(left + 0.1f, 0f, 100f).coerceAtLeast(left)
        val safeBottom = clamp(top + 0.1f, 0f, 100f).coerceAtLeast(top)
        return NormalizedRect(left, top, safeRight, safeBottom)
    }

    fun mergeCandidate(block: TextBlock): BubbleMergeCandidate {
        return BubbleMergeCandidate(
            left = block.left,
            top = block.top,
            right = block.right,
            bottom = block.bottom,
            verticalHint = isLikelyVertical(
                width = (block.right - block.left).coerceAtLeast(1f),
                height = (block.bottom - block.top).coerceAtLeast(1f),
                layout = null,
                text = block.text
            )
        )
    }

    fun mergeCandidate(bubble: Bubble): BubbleMergeCandidate {
        val rect = outerRect(bubble)
        return BubbleMergeCandidate(
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            verticalHint = isLikelyVertical(
                width = rect.width.coerceAtLeast(0.1f),
                height = rect.height.coerceAtLeast(0.1f),
                layout = bubble.layout,
                text = bubble.originalText
            )
        )
    }

    fun isLikelyVertical(width: Float, height: Float, layout: String?, text: String): Boolean {
        return layout.equals("vertical", ignoreCase = true) ||
            height >= width * 1.15f ||
            text.trim().length <= 2
    }
}

object BubbleMergeHeuristics {
    fun shouldMerge(
        a: BubbleMergeCandidate,
        b: BubbleMergeCandidate,
        imageWidth: Int,
        imageHeight: Int,
        scenario: BubbleMergeScenario
    ): BubbleMergeDecision {
        val verticalHint = a.verticalHint || b.verticalHint
        val overlapW = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
        val overlapH = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
        val hGap = max(0f, max(a.left, b.left) - min(a.right, b.right))
        val vGap = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))

        val minW = min(a.width, b.width).coerceAtLeast(0.1f)
        val minH = min(a.height, b.height).coerceAtLeast(0.1f)
        val maxW = max(a.width, b.width).coerceAtLeast(0.1f)
        val maxH = max(a.height, b.height).coerceAtLeast(0.1f)
        val overlapXRatio = overlapW / minW
        val overlapYRatio = overlapH / minH
        val normalizedX = isNormalizedAxis(max(a.right, b.right), imageWidth)
        val normalizedY = isNormalizedAxis(max(a.bottom, b.bottom), imageHeight)
        val baseGapX = baseGapForAxis(MERGE_BASE_GAP_PX, imageWidth, normalizedX)
        val baseGapY = baseGapForAxis(MERGE_BASE_GAP_PX, imageHeight, normalizedY)

        val overlapMin = if (verticalHint) 0.16f else 0.18f
        val nearVertical = overlapXRatio >= overlapMin &&
            hGap <= max(
                maxW * if (scenario == BubbleMergeScenario.WITHIN_BUBBLE) 1.15f else 1.28f,
                baseGapX
            ) &&
            vGap <= max(
                maxH * if (scenario == BubbleMergeScenario.WITHIN_BUBBLE) 1.05f else 1.35f,
                baseGapY
            )
        val nearHorizontal = overlapYRatio >= overlapMin &&
            vGap <= max(
                maxH * if (scenario == BubbleMergeScenario.WITHIN_BUBBLE) 0.70f else 0.85f,
                baseGapY
            ) &&
            hGap <= max(
                maxW * if (scenario == BubbleMergeScenario.WITHIN_BUBBLE) 0.92f else 1.06f,
                baseGapX
            )
        if (!nearVertical && !nearHorizontal) {
            return BubbleMergeDecision(merge = false, reason = "gap_or_overlap")
        }

        val unionLeft = min(a.left, b.left)
        val unionTop = min(a.top, b.top)
        val unionRight = max(a.right, b.right)
        val unionBottom = max(a.bottom, b.bottom)
        val unionArea = (unionRight - unionLeft).coerceAtLeast(0.1f) *
            (unionBottom - unionTop).coerceAtLeast(0.1f)
        val expansion = unionArea / (a.area + b.area).coerceAtLeast(0.1f)
        val expansionLimit = when {
            scenario == BubbleMergeScenario.WITHIN_BUBBLE && verticalHint -> 6.0f
            scenario == BubbleMergeScenario.WITHIN_BUBBLE -> 4.2f
            verticalHint -> 7.0f
            else -> 5.0f
        }
        if (expansion > expansionLimit && hGap > 0f && vGap > 0f) {
            return BubbleMergeDecision(merge = false, reason = "expansion")
        }

        val centerDistance = hypot(
            (a.centerX - b.centerX).toDouble(),
            (a.centerY - b.centerY).toDouble()
        ).toFloat()
        val diagonal = hypot(
            axisSpan(imageWidth, normalizedX).toDouble(),
            axisSpan(imageHeight, normalizedY).toDouble()
        ).toFloat().coerceAtLeast(1f)
        val distanceLimit = if (scenario == BubbleMergeScenario.WITHIN_BUBBLE) 0.085f else 0.11f
        if (centerDistance > diagonal * distanceLimit) {
            return BubbleMergeDecision(merge = false, reason = "distance")
        }

        if (verticalHint && hGap > maxW * 1.5f) {
            return BubbleMergeDecision(merge = false, reason = "vertical_gap")
        }
        if (!verticalHint && vGap > maxH * 1.1f) {
            return BubbleMergeDecision(merge = false, reason = "horizontal_gap")
        }

        return BubbleMergeDecision(merge = true, reason = if (nearVertical) "vertical" else "horizontal")
    }

    private const val MERGE_BASE_GAP_PX = 8f

    private fun isNormalizedAxis(maxCoord: Float, imageSize: Int): Boolean {
        return imageSize > 100 && maxCoord <= 100.5f
    }

    private fun baseGapForAxis(baseGapPx: Float, imageSize: Int, normalizedAxis: Boolean): Float {
        return if (normalizedAxis) {
            (baseGapPx / imageSize.coerceAtLeast(1).toFloat()) * 100f
        } else {
            baseGapPx
        }
    }

    private fun axisSpan(imageSize: Int, normalizedAxis: Boolean): Float {
        return if (normalizedAxis) 100f else imageSize.coerceAtLeast(1).toFloat()
    }
}

private fun clamp(value: Float, minValue: Float, maxValue: Float): Float {
    return value.coerceIn(minValue, maxValue)
}
