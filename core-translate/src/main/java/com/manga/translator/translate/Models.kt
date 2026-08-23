package com.manga.translator.translate

data class Bubble(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val bgType: String,
    val originalText: String,
    val translatedText: String,
    val layout: String = "horizontal",
    val contentX: Float = x,
    val contentY: Float = y,
    val contentW: Float = w,
    val contentH: Float = h
)

data class TranslationResult(val bubbles: List<Bubble>)

enum class BubbleRegionType {
    BUBBLE,
    OUTSIDE
}

data class BubbleRegion(
    val id: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val regionType: BubbleRegionType,
    val confidence: Float = 0f,
    val source: String = ""
)

data class RegionOcrResult(
    val regionId: Int,
    val regionType: BubbleRegionType,
    val blocks: List<TextBlock>,
    val layout: String
)

data class TextBlock(
    val id: Int,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float = 0f
)

data class TextBlockGroup(
    val bubbleId: Int,
    val blockIds: List<Int>
)
