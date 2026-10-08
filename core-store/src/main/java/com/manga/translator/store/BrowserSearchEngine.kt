package com.manga.translator.store

/**
 * 内嵌浏览器的默认搜索引擎（2026-09-28 用户反馈：搜索写死 Google，国内网络打不开）。
 *
 * 默认 [BING]：`cn.bing.com` 在国内网络可直连，结果质量接近 Google；
 * 需要 Google 的用户可以自己切到 [GOOGLE]（需可访问的网络），或换成 [BAIDU]。
 *
 * [urlTemplate] 里的 `%s` 是 URL 编码后的关键词（由 [buildSearchUrl] 填充）。
 */
enum class BrowserSearchEngine(
    val id: String,
    /** 菜单/芯片用的短名。 */
    val shortLabel: String,
    /** 完整说明（含「需可访问的网络」这类提示）。 */
    val label: String,
    val urlTemplate: String
) {
    BING("bing", "必应", "必应 Bing", "https://cn.bing.com/search?q=%s"),
    BAIDU("baidu", "百度", "百度", "https://www.baidu.com/s?wd=%s"),
    GOOGLE("google", "Google", "Google（需可访问的网络）", "https://www.google.com/search?q=%s");

    /** 关键词 → 搜索地址（[encodedQuery] 必须是已 URL 编码的关键词）。 */
    fun buildSearchUrl(encodedQuery: String): String = urlTemplate.replace("%s", encodedQuery)

    companion object {
        val DEFAULT: BrowserSearchEngine = BING

        fun fromId(value: String?): BrowserSearchEngine {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == normalized } ?: DEFAULT
        }
    }
}
