package com.manga.translator.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条用户收藏（新标签页「收藏」格子）。 */
data class BrowserFavorite(
    val label: String,
    val url: String,
    val addedAtMs: Long
)

/** 收藏写入结果（供 UI 给提示）。 */
enum class FavoriteAddResult {
    ADDED,
    UPDATED,
    INVALID_URL,
    FULL
}

/**
 * 内嵌浏览器收藏（2026-09-28 用户要求：**初始为空、由用户自己添加**，格子显示站点 favicon）。
 *
 * JSON 落盘 `mt_browser_favorites.json`；同一 URL 重复添加视为「更新名称」；
 * 上限 [MAX_FAVORITES]（新标签页 4 列 × 6 行）。纯逻辑（归一化/合并/裁剪/序列化）
 * 放在 companion 里便于单测。
 */
class BrowserFavoriteRepository(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    /** 全部收藏，按用户添加顺序。 */
    fun all(): List<BrowserFavorite> {
        synchronized(lock) {
            return loadAll().sortedBy { it.addedAtMs }
        }
    }

    fun count(): Int = all().size

    /**
     * 添加收藏；同 URL 已存在时更新名称（返回 [FavoriteAddResult.UPDATED]），
     * URL 非法返回 [FavoriteAddResult.INVALID_URL]，已满返回 [FavoriteAddResult.FULL]。
     */
    fun add(
        label: String,
        url: String,
        nowMs: Long = System.currentTimeMillis()
    ): FavoriteAddResult {
        val normalized = BrowserHistoryRepository.normalizeUrl(url)
            ?: return FavoriteAddResult.INVALID_URL
        synchronized(lock) {
            val entries = loadAll()
            val existing = entries.firstOrNull { it.url == normalized }
            if (existing == null && entries.size >= MAX_FAVORITES) return FavoriteAddResult.FULL
            val next = mergeFavorite(entries, label, normalized, nowMs)
            write(next)
            return if (existing == null) FavoriteAddResult.ADDED else FavoriteAddResult.UPDATED
        }
    }

    /** 编辑收藏：把 [originalUrl] 那条改成新的名称/网址。 */
    fun update(originalUrl: String, label: String, url: String): Boolean {
        val normalized = BrowserHistoryRepository.normalizeUrl(url) ?: return false
        synchronized(lock) {
            val entries = loadAll()
            val target = entries.firstOrNull { it.url == originalUrl } ?: return false
            val renamed = entries.filterNot { it.url == originalUrl || it.url == normalized }
            val updated = target.copy(
                label = label.trim().ifBlank { defaultLabel(normalized) }.take(MAX_LABEL_LENGTH),
                url = normalized
            )
            write((renamed + updated).sortedBy { it.addedAtMs })
            return true
        }
    }

    fun remove(url: String): Boolean {
        synchronized(lock) {
            val entries = loadAll()
            val next = entries.filterNot { it.url == url }
            if (next.size == entries.size) return false
            write(next)
            return true
        }
    }

    fun clear() {
        synchronized(lock) { write(emptyList()) }
    }

    private fun loadAll(): List<BrowserFavorite> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return parseEntriesText(text)
    }

    private fun write(entries: List<BrowserFavorite>) {
        file.parentFile?.mkdirs()
        file.writeText(toStoreJson(entries))
    }

    companion object {
        const val FILE_NAME = "mt_browser_favorites.json"
        const val MAX_FAVORITES = 24
        const val MAX_LABEL_LENGTH = 24

        /** 名称留空时用域名兜底（去掉 www.）。 */
        fun defaultLabel(url: String): String {
            val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
            val cleaned = host.removePrefix("www.")
            return (cleaned.ifBlank { BrowserHistoryRepository.urlWithoutScheme(url) })
                .take(MAX_LABEL_LENGTH)
        }

        /**
         * 合并一条收藏：同 URL 覆盖名称（保留原添加时间），否则追加。
         * 超出上限时丢弃最后添加的（防止旧数据超限）。
         */
        internal fun mergeFavorite(
            existing: List<BrowserFavorite>,
            label: String,
            url: String,
            nowMs: Long
        ): List<BrowserFavorite> {
            val previous = existing.firstOrNull { it.url == url }
            val entry = BrowserFavorite(
                label = label.trim().ifBlank { previous?.label ?: defaultLabel(url) }
                    .take(MAX_LABEL_LENGTH),
                url = url,
                addedAtMs = previous?.addedAtMs ?: nowMs
            )
            val next = (existing.filterNot { it.url == url } + entry).sortedBy { it.addedAtMs }
            return if (next.size <= MAX_FAVORITES) next else next.take(MAX_FAVORITES)
        }

        /** favicon 请求用：站点根地址（scheme + host + 非默认端口）。 */
        fun originOf(url: String): String? {
            val normalized = BrowserHistoryRepository.normalizeUrl(url) ?: return null
            return runCatching {
                val uri = java.net.URI(normalized)
                val scheme = uri.scheme ?: return null
                val host = uri.host ?: return null
                val port = uri.port
                if (port > 0) "$scheme://$host:$port" else "$scheme://$host"
            }.getOrNull()
        }

        internal fun parseEntriesText(text: String): List<BrowserFavorite> {
            if (text.isBlank()) return emptyList()
            return runCatching {
                val arr = JSONObject(text).optJSONArray("entries") ?: JSONArray()
                val entries = mutableListOf<BrowserFavorite>()
                for (index in 0 until arr.length()) {
                    val item = arr.optJSONObject(index) ?: continue
                    val url = BrowserHistoryRepository.normalizeUrl(item.optString("url", ""))
                        ?: continue
                    entries += BrowserFavorite(
                        label = item.optString("label", "").take(MAX_LABEL_LENGTH),
                        url = url,
                        addedAtMs = item.optLong("addedAtMs", 0L)
                    )
                }
                entries
            }.getOrElse { emptyList() }
        }

        internal fun toStoreJson(entries: List<BrowserFavorite>): String {
            val arr = JSONArray()
            entries.forEach { entry ->
                arr.put(
                    JSONObject()
                        .put("label", entry.label)
                        .put("url", entry.url)
                        .put("addedAtMs", entry.addedAtMs)
                )
            }
            return JSONObject().put("entries", arr).toString()
        }
    }
}
