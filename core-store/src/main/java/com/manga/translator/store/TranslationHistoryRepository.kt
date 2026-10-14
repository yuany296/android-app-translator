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

/** 历史里的一条气泡（只读展示用；2026-10-15 起支持手动修正译文）。 */
data class HistoryBubble(
    val originalText: String,
    val translatedText: String,
    val edited: Boolean = false
)

/**
 * 历史条目里某一句话的坐标：拿它去动句子缓存。
 *
 * 历史只是快照，改它**不影响**翻译复用；真正决定「下次遇到这句用什么译文」的是
 * `SentenceTranslationRepository`，所以改 / 作废时都要带上这组坐标。
 */
data class HistoryBubbleRef(
    val sourceLanguage: String,
    val targetLanguage: String,
    val originalText: String
)

/**
 * 解析历史条目里的气泡列表（只读）。
 *
 * `bubblesJson` 里除原文 / 译文外还有坐标等渲染字段，这里只取展示需要的三项；
 * 改写要走 [TranslationHistoryRepository.updateBubbleTranslation]，它会保留其余字段。
 */
fun parseHistoryBubbles(bubblesJson: String): List<HistoryBubble> {
    if (bubblesJson.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONObject(bubblesJson).optJSONArray("bubbles") ?: JSONArray()
        val list = mutableListOf<HistoryBubble>()
        for (index in 0 until arr.length()) {
            val item = arr.optJSONObject(index) ?: continue
            list += HistoryBubble(
                originalText = item.optString("originalText", ""),
                translatedText = item.optString("translatedText", ""),
                edited = item.optBoolean("edited", false)
            )
        }
        list
    }.getOrElse { emptyList() }
}

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

    fun findByPageHash(pageHash: String): TranslationHistoryEntry? {
        if (pageHash.isBlank()) return null
        synchronized(lock) {
            return loadAll()
                .filter { it.pageHash == pageHash }
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

    /**
     * 取某条历史里第 [index] 个气泡的坐标（不改动任何数据）。
     *
     * 用于「作废这句缓存」：先拿到源 / 目标语言与原文，再去句子缓存里删。
     */
    fun bubbleRefAt(id: String, index: Int): HistoryBubbleRef? {
        synchronized(lock) {
            val entry = loadAll().firstOrNull { it.id == id } ?: return null
            val bubbles = parseHistoryBubbles(entry.bubblesJson)
            if (index !in bubbles.indices) return null
            return HistoryBubbleRef(entry.sourceLanguage, entry.targetLanguage, bubbles[index].originalText)
        }
    }

    /**
     * 取某条历史里全部气泡的坐标（用于「作废本页缓存」）。
     *
     * 返回 null 表示记录已不存在；返回空列表表示这条记录里没有可作废的气泡。
     */
    fun pageBubbleRefs(id: String): List<HistoryBubbleRef>? {
        synchronized(lock) {
            val entry = loadAll().firstOrNull { it.id == id } ?: return null
            return parseHistoryBubbles(entry.bubblesJson).map {
                HistoryBubbleRef(entry.sourceLanguage, entry.targetLanguage, it.originalText)
            }
        }
    }

    /**
     * 改写某条历史里第 [index] 个气泡的译文，并标记该气泡「已修正」（2026-10-15 用户要求）。
     *
     * 只动 `translatedText` 与 `edited` 两个字段，坐标等渲染字段原样保留 ——
     * 若整体重新序列化 bubble，会把 `x/y/w/h/bgType` 一起丢掉。
     *
     * 注意：历史是快照，改它**不会影响翻译复用**；调用方必须同时把新译文写进句子缓存
     * （`SentenceTranslationRepository.put`），否则下次命中缓存仍是旧译文。
     *
     * @return 被改气泡的坐标（拿它去同步缓存）；记录不存在 / 索引越界时为 null。
     */
    fun updateBubbleTranslation(id: String, index: Int, translatedText: String): HistoryBubbleRef? {
        synchronized(lock) {
            val entries = loadAll()
            val target = entries.firstOrNull { it.id == id } ?: return null
            val rewritten = rewriteBubble(target.bubblesJson, index, translatedText) ?: return null
            val ( rewrittenJson, originalText ) = rewritten
            val updated = target.copy(bubblesJson = rewrittenJson)
            write(entries.map { if (it.id == id) updated else it })
            return HistoryBubbleRef(target.sourceLanguage, target.targetLanguage, originalText)
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

        /**
         * 改写 `bubblesJson` 里第 [index] 个气泡的译文（2026-10-15 手动修正）。
         *
         * 只动 `translatedText` 与 `edited`，`x/y/w/h/bgType/layout` 等渲染字段原样保留 ——
         * 整体重新序列化会把坐标一起丢掉。
         *
         * @return 新 JSON 与这句的原文；JSON 损坏或索引越界时为 null。
         */
        internal fun rewriteBubble(
            bubblesJson: String,
            index: Int,
            translatedText: String
        ): Pair<String, String>? {
            val root = runCatching { JSONObject(bubblesJson) }.getOrNull() ?: return null
            val bubbles = root.optJSONArray("bubbles") ?: return null
            val bubble = bubbles.optJSONObject(index) ?: return null
            val originalText = bubble.optString("originalText", "")
            bubble.put("translatedText", translatedText)
            bubble.put("edited", true)
            return root.toString() to originalText
        }
    }
}