package com.manga.translator.store

data class AppSettings( // data class：编译器自动生成 equals / hashCode / toString / copy
    val sourceLanguage: String = AppLanguage.ENGLISH.id,             // 源语言，默认英文
    val targetLanguage: String = AppLanguage.CHINESE_SIMPLIFIED.id,  // 目标语言，默认简体中文

    // ---- 以下 6 个是旧版远程 OCR 遗留，实际无人读，可忽略 ----
    val ocrMode: String = OcrMode.REMOTE_OCR.id,                     // 旧 OCR 模式，默认远程 OCR
    val ocrProvider: String = AiProvider.OPENAI.id,                  // 旧远程 OCR 用的 AI 厂商，只写不读
    val baiduOcrLanguage: String = BaiduOcrLanguage.JAP.id,          // 旧百度 OCR 语言，不持久化
    val ocrBaseUrl: String = "",                                     // 旧远程 OCR 的 API 地址(之前采用过用直接用多模态大模型进行识别文字)
    val ocrModel: String = "gpt-4o-mini",                            // 旧远程 OCR 的模型名
    val ocrApiKey: String = "",                                      // 旧远程 OCR 的 API Key

    // ---- 翻译：云端 API 路径 ----
    val translateProvider: String = AiProvider.OPENAI.id,            // 云端厂商，默认 OpenAI
    val translateBaseUrl: String = "",                               // 云端 API 的 Base URL，空则用厂商默认值
    val translateModel: String = "gpt-4o-mini",                      // 云端模型名
    val translateApiKey: String = "",                                // 云端 API Key

    // ---- 行为开关 ----
    val enabled: Boolean = true,                                     // 翻译总开关，逻辑活的但无 UI 能改，正常恒为 true
    val ignoreSimplifiedChinese: Boolean = false,                    // （死代码）旧路径遗留：只有已下线的云端 OCR 提示词会读，主链路无效
    val enableThinking: Boolean = false,                             // 是否开启“思考功能”（仅千问 / GLM 支持）
    val thinkingBudget: Int = 8192,                                  // 思考功能的 token 预算，UI 未暴露

    // ---- OCR：真正在用的两个字段 ----
    val ocrEngine: String = OcrEngine.ML_KIT.id,                     // OCR 引擎，默认 ML Kit
    val ocrSpeedMode: String = OcrSpeedMode.BALANCED.id,             // Paddle 的速度模式，默认均衡

    // ---- 翻译：引擎与百度凭据 ----
    val translateEngine: String = TranslateEngine.CLOUD.id,          // 翻译引擎，默认云端 API
    val baiduTranslateAppId: String = "",                            // 百度翻译 APP ID
    val baiduTranslateSecretKey: String = ""                         // 百度翻译 Secret Key
)
