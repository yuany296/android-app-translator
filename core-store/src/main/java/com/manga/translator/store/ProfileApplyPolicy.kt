package com.manga.translator.store

/**
 * 「套用配置档案」的字段归属策略（2026-10-15）。
 *
 * ## 为什么要单独抽一层
 *
 * `AppSettings` 是「全局阅读偏好 + 档案凭据」混装的 data class，而配置档案是把**整份
 * AppSettings** 序列化存下来的（[SettingsRepository.settingsToJson]）。于是「选一个档案」
 * 以前就是整包替换全局设置 —— 档案里那份**建档案那一刻的语言快照**会顶掉用户现在选的
 * 源语言。用户反馈：「换翻译配置的时候，会换掉源语言。希望不要换掉。」
 *
 * 档案表单里根本没有语言项（`ProfileFormScreen` 只有厂商 / Base URL / Key / 模型 / 思考），
 * 也就是说档案里的语言从来不是用户的意思表示，只是整包替换的副作用。
 *
 * ## 归属划分
 *
 * - **档案自己的**（把档案的凭据搬过去）：厂商 / Base URL / 模型 / Key / 多模态模型 /
 *   思考开关与预算 / 翻译引擎（档案自带 engine，浏览器弹层靠「选档案 = 切云端」）；
 * - **不属于档案、必须保留用户当前值**：语言对（源 / 目标）、OCR 引擎与速度模式、
 *   百度凭据、服务开关 —— 档案表单里都没有这些项，弹回去只会让用户莫名其妙。
 *
 * 纯函数、无 Android 依赖，可 JVM 单测（见 `ProfileApplyPolicyTest`）。
 */
fun AppSettings.applyProfile(profile: AppSettings): AppSettings = copy(
    translateProvider = profile.translateProvider,
    translateBaseUrl = profile.translateBaseUrl,
    translateModel = profile.translateModel,
    translateApiKey = profile.translateApiKey,
    visionModel = profile.visionModel,
    enableThinking = profile.enableThinking,
    thinkingBudget = profile.thinkingBudget,
    translateEngine = profile.translateEngine
    // 其余字段（sourceLanguage / targetLanguage / ocrEngine / ocrSpeedMode /
    // baiduTranslateAppId / baiduTranslateSecretKey / enabled）保持调用方当前值。
)
