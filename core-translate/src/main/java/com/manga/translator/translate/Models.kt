package com.manga.translator.translate

data class Bubble(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val bgType: String,
    val originalText: String,
    val translatedText: String,
    val layout: String = "horizontal"
)

data class TranslationResult(val bubbles: List<Bubble>)
