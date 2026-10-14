package com.manga.translator.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.manga.translator.store.BrowserFavoriteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 站点 favicon 加载器（2026-09-28 用户要求：收藏格显示网站自己的图标，失败才用字母兜底）。
 *
 * 策略（2026-10-12 补 HTML 兜底）：先 `origin/favicon.ico`、`origin/favicon.png`；
 * 都没有再抓 origin 首页 HTML，解析 `<head>` 里的 `<link rel="… icon" href="…">`。
 * 为什么需要兜底：韩漫站点（Kakao Page / Lezhin / RIDI 实测）基本不把图标放在固定路径，
 * 而是用 HTML 声明 —— 只试固定路径时这三站全都落到字母兜底。
 * 成功结果进内存 LRU（一次会话内不重复请求），失败也缓存「无图标」避免反复打网络。
 */
object SiteFaviconLoader {
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_HTML_BYTES = 512 * 1024
    private const val TARGET_SIZE_PX = 96
    private const val CACHE_COUNT = 96
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** value = null 表示「已确认没有图标」，避免对同一站点反复重试。 */
    private val cache = object : LruCache<String, Optional>(CACHE_COUNT) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Optional, newValue: Optional?) {
            if (evicted) oldValue.bitmap?.recycle()
        }
    }

    private class Optional(val bitmap: Bitmap?)

    fun cached(url: String): Bitmap? = cache.get(cacheKey(url))?.bitmap

    fun isKnown(url: String): Boolean = cache.get(cacheKey(url)) != null

    suspend fun load(url: String): Bitmap? = withContext(Dispatchers.IO) {
        val key = cacheKey(url)
        cache.get(key)?.let { return@withContext it.bitmap }
        val origin = BrowserFavoriteRepository.originOf(url)
        if (origin == null) {
            cache.put(key, Optional(null))
            return@withContext null
        }
        val bitmap = fetch(origin)
        cache.put(key, Optional(bitmap))
        bitmap
    }

    private fun cacheKey(url: String): String = BrowserFavoriteRepository.originOf(url) ?: url

    private fun fetch(origin: String): Bitmap? {
        CANDIDATE_PATHS.forEach { path ->
            val bytes = download("$origin$path") ?: return@forEach
            decode(bytes)?.let { return it }
        }
        // 固定路径都没有 → 抓首页 HTML，按 <link rel="… icon"> 找真正的图标地址。
        val iconUrl = downloadHtml(origin)?.let { iconHrefFromHtml(it, origin) } ?: return null
        return download(iconUrl)?.let { decode(it) }
    }

    private fun download(url: String): ByteArray? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "image/*,*/*;q=0.8")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.isNotEmpty() && !contentType.startsWith("image")) return null
                val body = response.body ?: return null
                if (body.contentLength() > MAX_BYTES) return null
                val bytes = body.bytes()
                if (bytes.size > MAX_BYTES) return null
                bytes
            }
        }.getOrNull()
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_SIZE_PX ||
            bounds.outHeight / (sample * 2) >= TARGET_SIZE_PX
        ) {
            sample *= 2
        }
        return runCatching {
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        }.getOrNull()
    }

    /** 常见 favicon 路径（按命中率排序）。 */
    private val CANDIDATE_PATHS = listOf("/favicon.ico", "/favicon.png")

    /**
     * 抓 origin 首页 HTML（2026-10-12 补齐：上午的会话在 fetch() 里调用了本函数但没写完，
     * 导致整个 app 模块编译不过）。只取头部少量字节，够解析 <head> 里的 <link> 即可。
     */
    private fun downloadHtml(origin: String): String? {
        return runCatching {
            val request = Request.Builder()
                .url(origin)
                .header("Accept", "text/html,*/*;q=0.8")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val looksLikeHtml = contentType.isEmpty() ||
                    contentType.contains("text/html") ||
                    contentType.contains("xhtml") ||
                    contentType.contains("xml")
                if (!looksLikeHtml) return null
                val body = response.body ?: return null
                val bytes = body.bytes()
                if (bytes.size > MAX_HTML_BYTES) return null
                String(bytes, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    /**
     * 从 HTML 里解析图标地址：按声明顺序找第一个非 SVG 的
     * `<link rel="… icon…">`（rel=icon / shortcut icon / apple-touch-icon 都认），
     * 相对地址（含 `//host/x.png`）相对 origin 解析。
     * SVG 直接跳过：BitmapFactory 解不了，留着只会浪费一次下载。
     */
    private fun iconHrefFromHtml(html: String, origin: String): String? {
        val linkTags = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)
        linkTags.forEach { match ->
            val tag = match.value
            if (tag.contains("image/svg", ignoreCase = true)) return@forEach
            val rel = attributeValue(tag, "rel") ?: return@forEach
            val isIcon = rel.lowercase().split(Regex("\\s+")).any {
                it == "icon" || it.contains("touch-icon") || it == "apple-touch-icon"
            }
            if (!isIcon) return@forEach
            val href = attributeValue(tag, "href")?.trim().takeUnless { it.isNullOrEmpty() }
                ?: return@forEach
            val resolved = resolveUrl(origin, htmlUnescape(href)) ?: return@forEach
            return resolved
        }
        return null
    }

    /** 取标签里单个属性值（双引号 / 单引号 / 无引号三种写法都认）。 */
    private fun attributeValue(tag: String, name: String): String? {
        val pattern = Regex(
            "\\b${Regex.escape(name)}\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
            RegexOption.IGNORE_CASE
        )
        val result = pattern.find(tag) ?: return null
        return result.groupValues[1]
            .ifEmpty { result.groupValues[2].ifEmpty { result.groupValues[3] } }
    }

    /** 解析 href 相对 origin；只处理站点声明里最常见的两种相对形态，解不动就放弃。 */
    private fun resolveUrl(origin: String, href: String): String? {
        return runCatching {
            val base = java.net.URI(origin.trim())
            base.resolve(href.trim()).toString()
        }.getOrNull()
    }

    private fun htmlUnescape(value: String): String {
        return value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&apos;", "'")
    }
}
