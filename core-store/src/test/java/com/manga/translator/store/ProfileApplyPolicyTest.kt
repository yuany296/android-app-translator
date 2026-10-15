package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「套用配置档案」字段归属策略（2026-10-15）。
 *
 * 用户反馈：「换翻译配置的时候，会换掉源语言。希望不要换掉。」
 * 根因是档案 = 整份 AppSettings 快照，选档案 = 整包替换。这里钉住归属划分：
 * 档案只搬「档案表单里存在的字段」（凭据/模型/思考/引擎），语言对与 OCR 选择保留用户当前值。
 */
class ProfileApplyPolicyTest {

    private fun settings(
        source: String = "ja",
        target: String = "zh-cn",
        ocrEngine: String = "paddle"
    ): AppSettings = AppSettings(
        sourceLanguage = source,
        targetLanguage = target,
        ocrEngine = ocrEngine,
        translateEngine = "mlkit",
        translateProvider = "openai",
        translateBaseUrl = "",
        translateModel = "",
        translateApiKey = "",
        baiduTranslateAppId = "baidu-id",
        baiduTranslateSecretKey = "baidu-secret"
    )

    private fun profile(): AppSettings = AppSettings(
        sourceLanguage = "ko",
        targetLanguage = "en",
        ocrEngine = "mlkit",
        translateEngine = "cloud",
        translateProvider = "deepseek",
        translateBaseUrl = "https://api.deepseek.example",
        translateModel = "deepseek-chat",
        translateApiKey = "sk-profile-key",
        visionModel = "deepseek-vision",
        enableThinking = true,
        thinkingBudget = 8192
    )

    @Test
    fun applyProfile_keepsUserLanguageAndOcrChoices() {
        val next = settings().applyProfile(profile())

        // 用户反馈的核心：换档案不动语言对
        assertEquals("ja", next.sourceLanguage)
        assertEquals("zh-cn", next.targetLanguage)
        // OCR 引擎与速度模式同理（档案表单里没有这些项）
        assertEquals("paddle", next.ocrEngine)
    }

    @Test
    fun applyProfile_carriesProfileCredentialsModelAndEngine() {
        val next = settings().applyProfile(profile())

        assertEquals("deepseek", next.translateProvider)
        assertEquals("https://api.deepseek.example", next.translateBaseUrl)
        assertEquals("deepseek-chat", next.translateModel)
        assertEquals("sk-profile-key", next.translateApiKey)
        assertEquals("cloud", next.translateEngine)
        assertEquals("deepseek-vision", next.visionModel)
        assertEquals(true, next.enableThinking)
        assertEquals(8192, next.thinkingBudget)
    }

    @Test
    fun applyProfile_keepsBaiduCredentialsAndServiceState() {
        val next = settings().applyProfile(profile())

        // 百度凭据不属于任何云端档案，不该被档案清掉
        assertEquals("baidu-id", next.baiduTranslateAppId)
        assertEquals("baidu-secret", next.baiduTranslateSecretKey)
    }

    @Test
    fun applyProfile_isPureFunction_originalUntouched() {
        val current = settings()
        current.applyProfile(profile())

        assertEquals("ja", current.sourceLanguage)
        assertEquals("mlkit", current.translateEngine)
        assertEquals("", current.translateApiKey)
    }
}
