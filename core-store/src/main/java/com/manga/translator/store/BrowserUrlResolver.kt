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

    /**
     * 两个 URL 是否指向**同一份文档**（2026-10-15，Kakao「翻到一半停下来」修复）。
     *
     * 为什么不能用字符串相等：webtoon 阅读器（Kakao 实测）滚动时用 `history.pushState`
     * 把阅读位置写进地址（`?cut=N`、`#cut-4`），文档本身没换。翻译会话原先用
     * `webView.url != sessionPageUrl` 判「页面已跳转」—— 用户每次滚动都命中 → 状态行
     * 「页面已跳转」、整段会话终止（用户报「中间停下来了，不进行翻译了」）。
     *
     * 判据：**忽略查询串与片段，只比 scheme/host/path**。真跳转（换 path/host）仍会被
     * 识别为跳转；而且真正的整页导航本来就由 `onPageStarted` 负责停会话，这里的比较
     * 只是同文档内的位置漂移不该误杀。尾部 `/` 视为等价。
     */
    fun isSameDocument(one: String?, other: String?): Boolean {
        if (one.isNullOrBlank() || other.isNullOrBlank()) return false
        return documentKey(one) == documentKey(other)
    }

    private fun documentKey(url: String): String =
        url.substringBefore('#').substringBefore('?').trim().trimEnd('/')

    private fun looksLikeHost(value: String): Boolean =
        DOMAIN.matches(value) || IPV4.matches(value) || LOCALHOST.matches(value)
}
