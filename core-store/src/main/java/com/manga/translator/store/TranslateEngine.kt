package com.manga.translator.store

/**
 * 翻译引擎：云端 API / 百度翻译（专用接口）/ 本地（离线）翻译。
 * - 云端 API：OpenAI 兼容大模型，质量好但单次生成延迟较高（2~5s）。
 * - 百度翻译：专用接口，整页多句一次请求（<1s），标准版每月免费 500 万字符，QPS=1。
 * - ML Kit 本地翻译：无网络往返最快，但需按（源语言,目标语言）语言对首次下载模型，质量一般。
 */
enum class TranslateEngine(
    val id: String,
    val label: String
) {
    CLOUD("cloud", "云端 API"),
    BAIDU("baidu", "百度翻译"),
    ML_KIT("mlkit", "ML Kit 本地翻译（离线，需 Google Play 服务，需要下载，翻译质量非常差）");

    companion object {
        fun fromId(value: String): TranslateEngine {
            return entries.firstOrNull { it.id == value.trim().lowercase() } ?: CLOUD
        }
    }
}
