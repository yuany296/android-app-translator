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
 * 策略：先 `origin/favicon.ico`，再 `origin/favicon.png`；只接受图片、限制体积与解码尺寸；
 * 成功结果进内存 LRU（一次会话内不重复请求），失败也缓存「无图标」避免反复打网络。
 */
object SiteFaviconLoader {
    private const val MAX_BYTES = 256 * 1024
    private const val TARGET_SIZE_PX = 96
    private const val CACHE_COUNT = 96

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
        return null
    }

    private fun download(url: String): ByteArray? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "image/*,*/*;q=0.8")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
                )
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
}
