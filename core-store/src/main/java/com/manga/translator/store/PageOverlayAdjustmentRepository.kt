package com.manga.translator.store

import android.content.Context
import org.json.JSONObject

data class PageOverlayAdjustment(
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val fontScale: Float = 1f,
    val strokeScale: Float = 1f
)

class PageOverlayAdjustmentRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun get(pageHash: String, bubbleKey: String): PageOverlayAdjustment? {
        val raw = prefs.getString(buildKey(pageHash, bubbleKey), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            PageOverlayAdjustment(
                offsetX = json.optInt("offsetX", 0),
                offsetY = json.optInt("offsetY", 0),
                fontScale = json.optDouble("fontScale", 1.0).toFloat().coerceIn(0.7f, 1.5f),
                strokeScale = json.optDouble("strokeScale", 1.0).toFloat().coerceIn(0.6f, 1.6f)
            )
        }.getOrNull()
    }

    fun save(pageHash: String, bubbleKey: String, adjustment: PageOverlayAdjustment) {
        val payload = JSONObject()
            .put("offsetX", adjustment.offsetX)
            .put("offsetY", adjustment.offsetY)
            .put("fontScale", adjustment.fontScale.toDouble())
            .put("strokeScale", adjustment.strokeScale.toDouble())
            .toString()
        prefs.edit().putString(buildKey(pageHash, bubbleKey), payload).apply()
    }

    private fun buildKey(pageHash: String, bubbleKey: String): String {
        return "$PREFIX:${pageHash.trim()}:${bubbleKey.trim()}"
    }

    private companion object {
        private const val PREF_NAME = "mt_overlay_adjustment"
        private const val PREFIX = "page"
    }
}

