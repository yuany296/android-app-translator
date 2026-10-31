package com.manga.translator.store

/**
 * 当前这次翻译的来源标签（2026-10-31 随「句子级历史」一起引入）。
 *
 * 句子表要回答「这句是哪来的」。但来源只有调用方知道：内嵌浏览器手里有 URL，
 * 悬浮球链路只知道是截图翻译。把这个信息穿到仓库要经过 4 层参数，所以用一个
 * 极小的进程内单例承载。
 *
 * 约束：**只在写缓存时读一次，纯展示字段，不参与任何判断**；也不持久化
 * （App 重启后由下一次翻译重新设置，空值显示为「—」）。
 */
object TranslationOrigin {

    @Volatile
    var label: String = ""

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
