package com.manga.translator.overlay

import android.graphics.RectF

data class BubbleRenderState(
    val key: String,
    var isTruncated: Boolean = false,
    var expanded: Boolean = false,
    var offsetX: Int = 0,
    var offsetY: Int = 0,
    var fontScale: Float = 1f,
    var strokeScale: Float = 1f,
    var baseTextSp: Float = 12f,
    var fittedTextSp: Float = 0f,
    var baseStrokeDp: Float = 1f,
    var bubbleId: Int = -1,
    var solidPatched: Boolean = false,
    var verticalLayout: Boolean = false,
    var displayText: String = "",
    var lineSpacingMultiplier: Float = 1f,
    var baseRect: RectF = RectF()
)
