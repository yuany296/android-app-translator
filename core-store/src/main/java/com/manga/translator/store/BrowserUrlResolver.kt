package com.manga.translator.store

import java.net.URLEncoder

/**
 * 地址栏 / 搜索框输入归一化（2026-09-28）：
 * - 带 `http://` / `https://` 的完整地址原样打开；
 * - 形如 `page.kakao.com`、`192.168.1.5:8080`、`localhost:3000/x` 的输入补 `https://` 当网址；
 * - 其余（含空格的自然语言）交给用户选定的搜索引擎（[BrowserSearchEngine]）。
 *
 * 纯 JVM 逻辑（用 [URLEncoder] 而非 `android.net.Uri`），便于单测。
 */
object BrowserUrlResolver {

    private val DOMAIN = Regex("^([A-Za-z0-9][A-Za-z0-9\\-]*\\.)+[A-Za-z]{2,}(:\\d{1,5})?(/.*)?$")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d{1,5})?(/.*)?$")
    private val LOCALHOST = Regex("^localhost(:\\d{1,5})?(/.*)?$", RegexOption.IGNORE_CASE)

    /** @return 可直接 `loadUrl` 的目标；输入为空返回 null。 */
    fun resolve(input: String, engine: BrowserSearchEngine = BrowserSearchEngine.DEFAULT): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        if (trimmed.none { it.isWhitespace() } && looksLikeHost(trimmed)) {
            return "https://$trimmed"
        }
        return engine.buildSearchUrl(URLEncoder.encode(trimmed, Charsets.UTF_8.name()))
    }

    /** 关键词搜索地址（不做「像不像网址」判断，供「搜索」按钮等使用）。 */
    fun searchUrl(keyword: String, engine: BrowserSearchEngine): String =
        engine.buildSearchUrl(URLEncoder.encode(keyword.trim(), Charsets.UTF_8.name()))

    private fun looksLikeHost(value: String): Boolean =
        DOMAIN.matches(value) || IPV4.matches(value) || LOCALHOST.matches(value)
}
