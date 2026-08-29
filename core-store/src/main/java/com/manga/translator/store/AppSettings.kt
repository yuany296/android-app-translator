package com.manga.translator.store

data class AppSettings(
    val sourceLanguage: String = AppLanguage.ENGLISH.id,
    val targetLanguage: String = AppLanguage.CHINESE_SIMPLIFIED.id,
    val ocrMode: String = OcrMode.REMOTE_OCR.id,
    val ocrProvider: String = AiProvider.OPENAI.id,
    val baiduOcrLanguage: String = BaiduOcrLanguage.JAP.id,
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
    val thinkingBudget: Int = 8192,
    val ocrEngine: String = OcrEngine.ML_KIT.id,
    val ocrSpeedMode: String = OcrSpeedMode.BALANCED.id,
    val translateEngine: String = TranslateEngine.CLOUD.id,
    val baiduTranslateAppId: String = "",
    val baiduTranslateSecretKey: String = ""
)
