package com.manga.translator.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TranslationHistoryEntry(
    val id: String,
    val timestampMs: Long,
    val pageHash: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val translateFingerprint: String,
    val bubblesJson: String
)

class TranslationHistoryRepository(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    fun all(): List<TranslationHistoryEntry> {
        synchronized(lock) {
            return loadAll().sortedByDescending { it.timestampMs }
        }
    }

    fun findByFingerprint(fingerprint: String): TranslationHistoryEntry? {
        if (fingerprint.isBlank()) return null
        synchronized(lock) {
            return loadAll()
                .filter { it.translateFingerprint == fingerprint }
                .maxByOrNull { it.timestampMs }
        }
    }

    fun add(entry: TranslationHistoryEntry) {
        synchronized(lock) {
            val entries = loadAll()
            if (entries.any { it.id == entry.id }) {
                return
            }
            write(pruneToLimit(entries + entry, MAX_ENTRIES))
        }
    }

    fun delete(id: String): Boolean {
        synchronized(lock) {
            val entries = loadAll()
            val next = entries.filterNot { it.id == id }
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

    private fun loadAll(): List<TranslationHistoryEntry> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return parseEntriesText(text)
    }

    private fun write(entries: List<TranslationHistoryEntry>) {
        file.parentFile?.mkdirs()
        file.writeText(toStoreJson(entries))
    }

    internal companion object {
        const val FILE_NAME = "mt_translation_history.json"
        const val MAX_ENTRIES = 500

        internal fun parseEntriesText(text: String): List<TranslationHistoryEntry> {
            if (text.isBlank()) return emptyList()
            return runCatching {
                val arr = JSONObject(text).optJSONArray("entries") ?: JSONArray()
                val entries = mutableListOf<TranslationHistoryEntry>()
                for (index in 0 until arr.length()) {
                    val item = arr.optJSONObject(index) ?: continue
                    val id = item.optString("id", "").trim()
                    if (id.isEmpty()) continue
                    entries += TranslationHistoryEntry(
                        id = id,
                        timestampMs = item.optLong("timestampMs", 0L),
                        pageHash = item.optString("pageHash", ""),
                        sourceLanguage = item.optString("sourceLanguage", ""),
                        targetLanguage = item.optString("targetLanguage", ""),
                        translateFingerprint = item.optString("translateFingerprint", ""),
                        bubblesJson = item.optString("bubblesJson", "{}")
                    )
                }
                entries
            }.getOrElse { emptyList() }
        }

        internal fun toStoreJson(entries: List<TranslationHistoryEntry>): String {
            val arr = JSONArray()
            entries.forEach { entry ->
                arr.put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("timestampMs", entry.timestampMs)
                        .put("pageHash", entry.pageHash)
                        .put("sourceLanguage", entry.sourceLanguage)
                        .put("targetLanguage", entry.targetLanguage)
                        .put("translateFingerprint", entry.translateFingerprint)
                        .put("bubblesJson", entry.bubblesJson)
                )
            }
            return JSONObject().put("entries", arr).toString()
        }

        internal fun pruneToLimit(
            entries: List<TranslationHistoryEntry>,
            max: Int
        ): List<TranslationHistoryEntry> {
            if (entries.size <= max) return entries
            return entries.sortedByDescending { it.timestampMs }.take(max)
        }
    }
}