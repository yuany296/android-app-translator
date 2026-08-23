package com.manga.translator.store

enum class AppLanguage(
    val id: String,
    val label: String
) {
    ENGLISH("en", "英文"),
    JAPANESE("ja", "日文"),
    KOREAN("ko", "韩文"),
    CHINESE_SIMPLIFIED("zh-cn", "简体中文"),
    CHINESE_TRADITIONAL("zh-tw", "繁体中文");

    companion object {
        fun fromId(value: String): AppLanguage {
            return entries.firstOrNull { it.id.equals(value.trim(), ignoreCase = true) } ?: ENGLISH
        }
    }
}
