package com.manga.translator.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 一条内嵌浏览器访问记录（同一 URL 只保留最新一条，用 [visitCount] 记录次数）。 */
data class BrowserHistoryEntry(
    val url: String,
    val title: String,
    val lastVisitMs: Long,
    val visitCount: Int,
    /** 第一次访问时间（2026-11-02）；旧数据加载时回退为 lastVisitMs。 */
    val firstVisitMs: Long = 0L
)

/**
 * 内嵌浏览器访问历史（2026-09-28 用户要求「保存浏览历史」）。
 *
 * 只保存 http/https 页面，同一 URL 去重并置顶；JSON 文件落盘，容量上限 [MAX_ENTRIES]，
 * 超出后按最后访问时间淘汰最旧记录。纯逻辑（合并/裁剪/序列化）放在 companion 里便于单测。
 */
class BrowserHistoryRepository(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    /** 全部记录，按最后访问时间倒序。 */
    fun all(): List<BrowserHistoryEntry> {
        synchronized(lock) {
            return loadAll().sortedByDescending { it.lastVisitMs }
        }
    }

    /** 最近 [limit] 条（浏览器空状态/历史面板展示用）。 */
    fun recent(limit: Int = DEFAULT_RECENT_LIMIT): List<BrowserHistoryEntry> {
        return all().take(limit.coerceAtLeast(1))
    }

    fun lastVisited(): BrowserHistoryEntry? = all().firstOrNull()

    /** 记录一次访问；非 http/https 或空白 URL 直接忽略，返回是否写入。 */
    fun record(url: String, title: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val normalized = normalizeUrl(url) ?: return false
        synchronized(lock) {
            write(mergeVisit(loadAll(), normalized, title, nowMs, MAX_ENTRIES))
        }
        return true
    }

    /**
     * 更新某条记录的标题（2026-11-02）。
     *
     * WebView 的 `onReceivedTitle` 经常晚于 `onPageFinished`，甚至页面已经渲染完才由 JS
     * 改标题。旧实现只在 onPageFinished 读一次 title，空标题/懒加载标题会永久缺失；
     * 这里只改标题，不动访问次数与时间。
     */
    fun updateTitle(url: String, title: String): Boolean {
        val normalized = normalizeUrl(url) ?: return false
        val sanitized = title.trim().replace(Regex("\\s+"), " ").take(MAX_TITLE_LENGTH)
        if (sanitized.isBlank()) return false
        synchronized(lock) {
            val entries = loadAll()
            val existing = entries.firstOrNull { it.url == normalized } ?: return false
            if (existing.title == sanitized) return false
            write(entries.map { if (it.url == normalized) it.copy(title = sanitized) else it })
            return true
        }
    }

    fun delete(url: String): Boolean {
        synchronized(lock) {
            val entries = loadAll()
            val next = entries.filterNot { it.url == url }
            if (next.size == entries.size) return false
            write(next)
            return true
        }
    }

    fun clear() {
        synchronized(lock) {
            write(emptyList())
        }
    }

    fun count(): Int {
        synchronized(lock) {
            return loadAll().size
        }
    }

    private fun loadAll(): List<BrowserHistoryEntry> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return parseEntriesText(text)
    }

    private fun write(entries: List<BrowserHistoryEntry>) {
        file.parentFile?.mkdirs()
        val json = toStoreJson(entries)
        // 原子写（2026-11-02）：历史文件在每次访问后都会重写，直接覆盖时进程被杀/断电
        // 可能留下半截 JSON；下次启动 parse 失败会整份历史清空，表现就是「网站被吞了」。
        val tmp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            tmp.writeText(json)
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.onFailure {
            runCatching {
                file.writeText(json)
                tmp.delete()
            }
        }
    }

    /** 纯逻辑放这里：app 模块（浏览器 UI）与单测都要用，故为 public。 */
    companion object {
        const val FILE_NAME = "mt_browser_history.json"

        /**
         * 历史条数上限（2026-11-02 从 300 提到 2000）。
         *
         * 300 条对「每个章节一个 URL」的漫画站来说很容易满：用户翻几十话再回看，
         * 最早的站点已经被淘汰，看起来就是「网站被吞了」。单条约 200 字节，2000 条约 0.4MB。
         */
        const val MAX_ENTRIES = 2000
        const val DEFAULT_RECENT_LIMIT = 12
        /** 页面标题存储上限；展示行仍由 UI 决定省略，详情弹层可看完整文本。 */
        const val MAX_TITLE_LENGTH = 300
        /** 历史行/日志里的 URL 展示上限（完整 URL 在详情弹层里看）。 */
        const val MAX_URL_DISPLAY_LENGTH = 120

        /** 只接受 http/https 绝对地址（about:blank、data:、intent: 等不入库）。 */
        fun normalizeUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val lower = trimmed.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
            return trimmed
        }

        /**
         * 合并一次访问：同 URL 置顶 + 访问次数累加；标题为空时沿用旧标题（WebView 早期 title 常为空）。
         */
        internal fun mergeVisit(
            existing: List<BrowserHistoryEntry>,
            url: String,
            title: String,
            nowMs: Long,
            max: Int
        ): List<BrowserHistoryEntry> {
            val previous = existing.firstOrNull { it.url == url }
            val sanitizedTitle = title.trim().replace(Regex("\\s+"), " ").take(MAX_TITLE_LENGTH)
            val firstVisit = previous?.firstVisitMs?.takeIf { it > 0L }
                ?: previous?.lastVisitMs?.takeIf { it > 0L }
                ?: nowMs
            val updated = BrowserHistoryEntry(
                url = url,
                title = sanitizedTitle.ifBlank { previous?.title.orEmpty() },
                lastVisitMs = maxOf(previous?.lastVisitMs ?: 0L, nowMs),
                visitCount = (previous?.visitCount ?: 0) + 1,
                firstVisitMs = firstVisit
            )
            val rest = existing.filterNot { it.url == url }
            return (listOf(updated) + rest)
                .sortedByDescending { it.lastVisitMs }
                .take(max.coerceAtLeast(1))
        }

        /** 列表展示用标题：有网页标题用标题，否则退回「域名 + 路径」。 */
        fun displayTitle(entry: BrowserHistoryEntry): String {
            val title = entry.title.trim()
            if (title.isNotEmpty()) return title
            return urlWithoutScheme(entry.url)
        }

        /** 去掉 `https://` 前缀、去掉结尾 `/`、截断过长的查询串（只用于展示/日志）。 */
        fun urlWithoutScheme(url: String): String {
            val stripped = url.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
            val trimmed = stripped.trimEnd('/')
            return if (trimmed.length <= MAX_URL_DISPLAY_LENGTH) {
                trimmed
            } else {
                trimmed.take(MAX_URL_DISPLAY_LENGTH - 1) + "…"
            }
        }

        /** `https://www.a.com/x` → `a.com`；无效 URL 返回空串。 */
        fun hostOf(url: String): String {
            val raw = url.trim()
            if (raw.isEmpty()) return ""
            return runCatching {
                java.net.URI(raw).host.orEmpty().removePrefix("www.")
            }.getOrElse {
                // 兜底：URI 解析失败时仍按字符串前缀取主机，至少不让详情空着。
                raw.substringAfter("://", raw)
                    .substringBefore('/')
                    .substringBefore('?')
                    .substringBefore('#')
                    .substringBefore(':')
                    .removePrefix("www.")
            }
        }

        internal fun parseEntriesText(text: String): List<BrowserHistoryEntry> {
            if (text.isBlank()) return emptyList()
            return runCatching {
                val arr = JSONObject(text).optJSONArray("entries") ?: JSONArray()
                val entries = mutableListOf<BrowserHistoryEntry>()
                for (index in 0 until arr.length()) {
                    val item = arr.optJSONObject(index) ?: continue
                    val url = item.optString("url", "").trim()
                    if (url.isEmpty()) continue
                    val lastVisitMs = item.optLong("lastVisitMs", 0L)
                    entries += BrowserHistoryEntry(
                        url = url,
                        title = item.optString("title", ""),
                        lastVisitMs = lastVisitMs,
                        visitCount = item.optInt("visitCount", 1).coerceAtLeast(1),
                        // 旧文件没有 firstVisitMs：回退为 lastVisitMs，详情里至少不是 1970。
                        firstVisitMs = item.optLong("firstVisitMs", 0L)
                            .takeIf { it > 0L }
                            ?: lastVisitMs
                    )
                }
                entries
            }.getOrElse { emptyList() }
        }

        internal fun toStoreJson(entries: List<BrowserHistoryEntry>): String {
            val arr = JSONArray()
            entries.forEach { entry ->
                arr.put(
                    JSONObject()
                        .put("url", entry.url)
                        .put("title", entry.title)
                        .put("lastVisitMs", entry.lastVisitMs)
                        .put("visitCount", entry.visitCount)
                        .put("firstVisitMs", entry.firstVisitMs)
                )
            }
            return JSONObject().put("entries", arr).toString()
        }
    }
}
