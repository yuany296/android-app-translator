package com.manga.translator.store

/**
 * 翻译来源的 URL 工具（2026-10-31 随「句子级历史」一起引入）。
 *
 * 2026-11-02 起来源不再用进程内单例 `label` 承载：并发/切换页面时后一次翻译会
 * 覆盖前一次，历史里可能把网页漫画记成「截图翻译」。现在由调用方构造
 * [TranslationOriginContext] 显式传进翻译管线，这个对象只保留纯函数。
 */
object TranslationOrigin {

    /**
     * `https://www.lezhin.com/ko/comic/…` → `lezhin.com`。
     *
     * 故意不用 `android.net.Uri`：这里只是个字符串前缀，用纯 Kotlin 实现才能在
     * JVM 单元测试里跑（Uri 在本地测试里是空实现）。
     */
    fun hostOf(url: String?): String {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        return runCatching {
            val withoutScheme = raw.substringAfter("://", raw)
            val host = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            host.removePrefix("www.")
        }.getOrDefault("")
    }
}
