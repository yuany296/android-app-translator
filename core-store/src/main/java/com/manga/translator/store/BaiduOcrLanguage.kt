package com.manga.translator.store

enum class BaiduOcrLanguage(
    val id: String,
    val label: String
) {
    JAP("JAP", "日文"),
    CHN_ENG("CHN_ENG", "中英混合"),
    ENG("ENG", "英文"),
    KOR("KOR", "韩文");

    companion object {
        fun fromId(value: String): BaiduOcrLanguage {
            val normalized = value.trim().uppercase()
            return entries.firstOrNull { it.id == normalized } ?: JAP
        }
    }
}
