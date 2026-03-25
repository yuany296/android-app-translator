package com.manga.translator.store

data class AppSettings(
    val ocrProvider: String = AiProvider.OPENAI.id,
    val ocrBaseUrl: String = "",
    val ocrModel: String = "gpt-4o-mini",
    val ocrApiKey: String = "",
    val translateProvider: String = AiProvider.OPENAI.id,
    val translateBaseUrl: String = "",
    val translateModel: String = "gpt-4o-mini",
    val translateApiKey: String = "",
    val enabled: Boolean = true,
    val ignoreSimplifiedChinese: Boolean = false,
    val enableThinking: Boolean = false,
    val thinkingBudget: Int = 8192
)
