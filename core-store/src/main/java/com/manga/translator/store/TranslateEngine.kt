package com.manga.translator.store

/**
 * 翻译引擎：云端 API 或本地（离线）翻译。
 * 本地翻译（ML Kit）无网络往返，但需按（源语言,目标语言）语言对首次下载模型，且质量一般不如云端大模型。
 */
enum class TranslateEngine(
    val id: String,
    val label: String
) {
    CLOUD("cloud", "云端 API"),
    ML_KIT("mlkit", "ML Kit 本地翻译（离线）");

    companion object {
        fun fromId(value: String): TranslateEngine {
            return entries.firstOrNull { it.id == value.trim().lowercase() } ?: CLOUD
        }
    }
}
