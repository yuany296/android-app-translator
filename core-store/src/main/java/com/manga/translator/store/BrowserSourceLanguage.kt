package com.manga.translator.store

/**
 * 浏览器源语言设置（2026-11-01）。
 *
 * 与首页 `AppSettings.sourceLanguage` **分离**：首页/悬浮球始终是具体语言；
 * 网页里额外允许 `auto`，按页面语言自动识别，识别失败回退首页源语言。
 *
 * 纯 Kotlin、无 Android 依赖，可 JVM 单测。
 */
object BrowserSourceLanguage {

    /** 网页自动检测；不是 [AppLanguage] 的成员，避免影响首页/目标语言下拉。 */
    const val AUTO = "auto"

    /** 只接受 auto 或 AppLanguage 的具体 id；未知值回退 auto（默认行为）。 */
    fun normalize(value: String): String {
        val raw = value.trim()
        if (raw.equals(AUTO, ignoreCase = true)) return AUTO
        val language = AppLanguage.entries.firstOrNull {
            it.id.equals(raw, ignoreCase = true)
        } ?: return AUTO
        return language.id
    }
}
