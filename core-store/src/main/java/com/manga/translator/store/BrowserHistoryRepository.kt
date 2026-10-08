package com.manga.translator.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条内嵌浏览器访问记录（同一 URL 只保留最新一条，用 [visitCount] 记录次数）。 */
data class BrowserHistoryEntry(
    val url: String,
    val title: String,
    val lastVisitMs: Long,
    val visitCount: Int
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
        file.writeText(toStoreJson(entries))
    }

    /** 纯逻辑放这里：app 模块（浏览器 UI）与单测都要用，故为 public。 */
    companion object {
        const val FILE_NAME = "mt_browser_history.json"
        const val MAX_ENTRIES = 300
        const val DEFAULT_RECENT_LIMIT = 12
        const val MAX_TITLE_LENGTH = 80

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
            val sanitizedTitle = title.trim().take(MAX_TITLE_LENGTH)
            val updated = BrowserHistoryEntry(
                url = url,
                title = sanitizedTitle.ifBlank { previous?.title.orEmpty() },
                lastVisitMs = nowMs,
                visitCount = (previous?.visitCount ?: 0) + 1
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

        /** 去掉 `https://` 前缀、去掉结尾 `/`、截断过长的查询串。 */
        fun urlWithoutScheme(url: String): String {
            val stripped = url.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
            val trimmed = stripped.trimEnd('/')
            return if (trimmed.length <= MAX_TITLE_LENGTH) trimmed else trimmed.take(MAX_TITLE_LENGTH - 1) + "…"
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
                    entries += BrowserHistoryEntry(
                        url = url,
                        title = item.optString("title", ""),
                        lastVisitMs = item.optLong("lastVisitMs", 0L),
                        visitCount = item.optInt("visitCount", 1).coerceAtLeast(1)
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
                )
            }
            return JSONObject().put("entries", arr).toString()
        }
    }
}
