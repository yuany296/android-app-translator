package com.manga.translator.store

enum class OcrEngine(
    val id: String,
    val label: String
) {
    ML_KIT("ml_kit", "ML Kit"),
    PADDLE("paddle", "PaddleOCR (PP-OCRv5)");

    companion object {
        fun fromId(value: String): OcrEngine {
            return entries.firstOrNull { it.id == value.trim().lowercase() } ?: ML_KIT
        }
    }
}