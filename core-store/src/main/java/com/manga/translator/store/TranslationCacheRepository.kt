package com.manga.translator.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class TranslationCacheRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun get(cacheKey: String): String? {
        val raw = prefs.getString(cacheKey, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val ts = obj.optLong("ts", 0L)
            if (System.currentTimeMillis() - ts > TTL_MS) {
                prefs.edit().remove(cacheKey).apply()
                null
            } else {
                if (obj.has("value") && !obj.isNull("value")) obj.getString("value") else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun put(cacheKey: String, value: String) {
        val payload = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("value", value)
            .toString()
        prefs.edit().putString(cacheKey, payload).apply()
        trimIfNeeded()
    }

    private fun trimIfNeeded() {
        val all = prefs.all
        if (all.size <= MAX_ENTRIES) return

        val sortable = all.entries.mapNotNull { entry ->
            val raw = entry.value as? String ?: return@mapNotNull null
            try {
                val ts = JSONObject(raw).optLong("ts", 0L)
                entry.key to ts
            } catch (_: Throwable) {
                null
            }
        }.sortedBy { it.second }

        val removeCount = (sortable.size - MAX_ENTRIES).coerceAtLeast(0)
        if (removeCount == 0) return

        val editor = prefs.edit()
        sortable.take(removeCount).forEach { editor.remove(it.first) }
        editor.apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREF_NAME = "mt_translate_cache"
        const val TTL_MS = 2 * 60 * 60 * 1000L
        const val MAX_ENTRIES = 120
    }
}
