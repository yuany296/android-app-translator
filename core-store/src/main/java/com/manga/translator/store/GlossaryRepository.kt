package com.manga.translator.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class GlossaryEntry(
    val source: String,
    val target: String
)

data class GlossaryImportResult(
    val added: Int,
    val skipped: Int,
    val total: Int,
    val truncated: Boolean
)

class GlossaryRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun listCollections(): List<String> {
        synchronized(lock) {
            val root = loadJson()
            val collections = root.optJSONObject(KEY_COLLECTIONS) ?: JSONObject()
            val names = mutableListOf<String>()
            val keys = collections.keys()
            while (keys.hasNext()) {
                val name = keys.next().trim()
                if (name.isNotEmpty()) names += name
            }
            return names.sorted()
        }
    }

    fun getActiveCollections(): List<String> {
        synchronized(lock) {
            val root = loadJson()
            val raw = root.optJSONArray(KEY_ACTIVE) ?: JSONArray()
            val all = listCollections()
            val active = mutableListOf<String>()
            for (index in 0 until raw.length()) {
                val name = raw.optString(index, "").trim()
                if (name.isNotEmpty() && all.contains(name) && !active.contains(name)) {
                    active += name
                }
            }
            return active
        }
    }

    fun setActiveCollections(names: List<String>) {
        synchronized(lock) {
            val root = loadJson()
            val all = listCollections()
            val arr = JSONArray()
            names.map { it.trim() }
                .filter { it.isNotEmpty() && all.contains(it) }
                .distinct()
                .forEach { arr.put(it) }
            root.put(KEY_ACTIVE, arr)
            save(root)
        }
    }

    fun createCollection(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        synchronized(lock) {
            val root = loadJson()
            val collections = root.optJSONObject(KEY_COLLECTIONS) ?: JSONObject()
            if (collections.has(trimmed)) return false
            collections.put(
                trimmed,
                JSONObject().put(KEY_REVISION, 0).put(KEY_ENTRIES, JSONArray())
            )
            root.put(KEY_COLLECTIONS, collections)
            save(root)
            return true
        }
    }

    fun deleteCollection(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        synchronized(lock) {
            val root = loadJson()
            val collections = root.optJSONObject(KEY_COLLECTIONS) ?: JSONObject()
            if (!collections.has(trimmed)) return false
            collections.remove(trimmed)
            root.put(KEY_COLLECTIONS, collections)
            val activeArr = root.optJSONArray(KEY_ACTIVE) ?: JSONArray()
            val nextActive = JSONArray()
            for (index in 0 until activeArr.length()) {
                val item = activeArr.optString(index, "").trim()
                if (item.isNotEmpty() && item != trimmed) nextActive.put(item)
            }
            root.put(KEY_ACTIVE, nextActive)
            save(root)
            return true
        }
    }

    fun renameCollection(oldName: String, newName: String): Boolean {
        val old = oldName.trim()
        val next = newName.trim()
        if (old.isEmpty() || next.isEmpty() || old == next) return false
        synchronized(lock) {
            val root = loadJson()
            val collections = root.optJSONObject(KEY_COLLECTIONS) ?: JSONObject()
            val payload = collections.optJSONObject(old) ?: return false
            if (collections.has(next)) return false
            collections.remove(old)
            collections.put(next, payload)
            root.put(KEY_COLLECTIONS, collections)
            val activeArr = root.optJSONArray(KEY_ACTIVE) ?: JSONArray()
            val nextActive = JSONArray()
            for (index in 0 until activeArr.length()) {
                val item = activeArr.optString(index, "").trim()
                if (item.isNotEmpty()) {
                    nextActive.put(if (item == old) next else item)
                }
            }
            root.put(KEY_ACTIVE, nextActive)
            save(root)
            return true
        }
    }

    fun getEntries(collection: String = DEFAULT_COLLECTION): List<GlossaryEntry> {
        synchronized(lock) {
            val arr = collectionEntries(collection)
            val entries = mutableListOf<GlossaryEntry>()
            for (index in 0 until arr.length()) {
                val item = arr.optJSONObject(index) ?: continue
                val source = item.optString("source", "").trim()
                val target = item.optString("target", "").trim()
                if (source.isNotEmpty()) {
                    entries += GlossaryEntry(source = source, target = target)
                }
            }
            return entries.sortedBy { it.source }
        }
    }

    fun get(collection: String, source: String): GlossaryEntry? {
        return getEntries(collection).firstOrNull { it.source == source.trim() }
    }

    fun add(collection: String, source: String, target: String): Boolean {
        val trimmedSource = source.trim()
        val trimmedTarget = target.trim()
        if (trimmedSource.isEmpty() || trimmedTarget.isEmpty()) return false
        synchronized(lock) {
            val root = loadJson()
            val collections = root.optJSONObject(KEY_COLLECTIONS) ?: JSONObject()
            val payload = collections.optJSONObject(collection) ?: freshCollection()
            val arr = payload.optJSONArray(KEY_ENTRIES) ?: JSONArray()
            for (index in 0 until arr.length()) {
                val item = arr.optJSONObject(index)
                if (item != null && item.optString("source", "").trim() == trimmedSource) {
                    return false
                }
            }
            if (arr.length() >= MAX_ENTRIES) return false
            arr.put(JSONObject().put("source", trimmedSource).put("target", trimmedTarget))
            payload.put(KEY_ENTRIES, arr)
            bumpRevision(payload)
            collections.put(collection, payload)
            root.put(KEY_COLLECTIONS, collections)
            save(root)
            return true
        }
    }

    fun update(collection: String, source: String, target: String): Boolean {
        val trimmedSource = source.trim()
        val trimmedTarget = target.trim()
        if (trimmedSource.isEmpty() || trimmedTarget.isEmpty()) return false
        synchronized(lock) {
            val root = loadJson()
            val collections = root.optJSONObject(KEY_COLLECTIONS) ?: JSONObject()
            val payload = collections.optJSONObject(collection) ?: return false
            val arr = payload.optJSONArray(KEY_ENTRIES) ?: JSONArray()
            var updated = false
            for (index in 0 until arr.length()) {
                val item = arr.optJSONObject(index) ?: continue
                if (item.optString("source", "").trim() == trimmedSource) {
                    item.put("target", trimmedTarget)
                    updated = true
                    break
                }
            }
            if (!updated) return false
            payload.put(KEY_ENTRIES, arr)
            bumpRevision(payload)
            collections.put(collection, payload)
            root.put(KEY_COLLECTIONS, collections)
            save(root)
            return true
        }
    }

    fun remove(collection: String, source: String): Boolean {
        val trimmedSource = source.trim()
        synchronized(lock) {
            val root = loadJson()
            val collections = root.optJSONObject(KEY_COLLECTIONS) ?: JSONObject()
            val payload = collections.optJSONObject(collection) ?: return false
            val arr = payload.optJSONArray(KEY_ENTRIES) ?: JSONArray()
            val next = JSONArray()
            var removed = false
            for (index in 0 until arr.length()) {
                val item = arr.optJSONObject(index)
                if (item != null && item.optString("source", "").trim() == trimmedSource) {
                    removed = true
                    continue
                }
                next.put(item)
            }
            if (!removed) return false
            payload.put(KEY_ENTRIES, next)
            bumpRevision(payload)
            collections.put(collection, payload)
            root.put(KEY_COLLECTIONS, collections)
            save(root)
            return true
        }
    }

    fun clear(collection: String) {
        synchronized(lock) {
            val root = loadJson()
            val collections = root.optJSONObject(KEY_COLLECTIONS) ?: JSONObject()
            val payload = collections.optJSONObject(collection) ?: return
            payload.put(KEY_ENTRIES, JSONArray())
            bumpRevision(payload)
            collections.put(collection, payload)
            root.put(KEY_COLLECTIONS, collections)
            save(root)
        }
    }

    fun revision(collection: String = DEFAULT_COLLECTION): Int {
        synchronized(lock) {
            return collectionPayload(collection).optInt(KEY_REVISION, 0)
        }
    }

    fun count(collection: String): Int {
        synchronized(lock) {
            return collectionEntries(collection).length()
        }
    }

    fun activeGlossaryEntries(): List<GlossaryEntry> {
        val actives = getActiveCollections()
        if (actives.isEmpty()) return emptyList()
        synchronized(lock) {
            val entriesByCollection = actives.associateWith { collection ->
                val arr = collectionEntries(collection)
                val pairs = mutableListOf<Pair<String, String>>()
                for (index in 0 until arr.length()) {
                    val item = arr.optJSONObject(index) ?: continue
                    pairs += item.optString("source", "").trim() to item.optString("target", "").trim()
                }
                pairs
            }
            return mergeActiveEntries(entriesByCollection, actives)
        }
    }

    fun activeRevision(): Int {
        return getActiveCollections().sumOf { revision(it) }
    }

    fun importJson(collection: String, raw: String): GlossaryImportResult {
        val payload = runCatching { JSONObject(raw) }.getOrElse {
            runCatching { JSONArray(raw) }.getOrNull() ?: return GlossaryImportResult(0, 0, 0, truncated = false)
        }
        val arr = when (payload) {
            is JSONObject -> payload.optJSONArray("glossary") ?: payload.optJSONArray("entries")
            is JSONArray -> payload
            else -> null
        } ?: return GlossaryImportResult(0, 0, 0, truncated = false)

        val incoming = mutableListOf<Pair<String, String>>()
        for (index in 0 until arr.length()) {
            val item = arr.optJSONObject(index) ?: continue
            val source = item.optString("source", "").trim()
            val target = item.optString("target", "").trim()
            if (source.isNotEmpty() && target.isNotEmpty()) {
                incoming += source to target
            }
        }
        if (incoming.isEmpty()) return GlossaryImportResult(0, 0, 0, truncated = false)

        synchronized(lock) {
            val root = loadJson()
            val collections = root.optJSONObject(KEY_COLLECTIONS) ?: JSONObject()
            val targetPayload = collections.optJSONObject(collection) ?: freshCollection().also {
                collections.put(collection, it)
            }
            val existing = mutableMapOf<String, String>()
            val entries = targetPayload.optJSONArray(KEY_ENTRIES) ?: JSONArray()
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val src = item.optString("source", "").trim()
                if (src.isNotEmpty()) existing[src] = item.optString("target", "").trim()
            }
            val merge = mergeIncoming(existing = existing, incoming = incoming, maxEntries = MAX_ENTRIES)
            val next = JSONArray()
            merge.merged.forEach { (source, target) ->
                next.put(JSONObject().put("source", source).put("target", target))
            }
            targetPayload.put(KEY_ENTRIES, next)
            if (merge.added > 0) {
                bumpRevision(targetPayload)
            }
            collections.put(collection, targetPayload)
            root.put(KEY_COLLECTIONS, collections)
            save(root)
            return GlossaryImportResult(
                added = merge.added,
                skipped = merge.skipped,
                total = incoming.size,
                truncated = merge.truncated
            )
        }
    }

    fun exportJson(collection: String = DEFAULT_COLLECTION): String {
        val root = JSONObject()
        val arr = JSONArray()
        getEntries(collection).forEach { entry ->
            arr.put(JSONObject().put("source", entry.source).put("target", entry.target))
        }
        root.put("glossary", arr)
        return root.toString()
    }

    fun importCsv(collection: String, raw: String): GlossaryImportResult {
        val incoming = GlossaryCsv.parse(raw)
        if (incoming.isEmpty()) return GlossaryImportResult(0, 0, 0, truncated = false)

        synchronized(lock) {
            val root = loadJson()
            val collections = root.optJSONObject(KEY_COLLECTIONS) ?: JSONObject()
            val targetPayload = collections.optJSONObject(collection) ?: freshCollection().also {
                collections.put(collection, it)
            }
            val existing = mutableMapOf<String, String>()
            val entries = targetPayload.optJSONArray(KEY_ENTRIES) ?: JSONArray()
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val src = item.optString("source", "").trim()
                if (src.isNotEmpty()) existing[src] = item.optString("target", "").trim()
            }
            val merge = mergeIncoming(existing = existing, incoming = incoming, maxEntries = MAX_ENTRIES)
            val next = JSONArray()
            merge.merged.forEach { (source, target) ->
                next.put(JSONObject().put("source", source).put("target", target))
            }
            targetPayload.put(KEY_ENTRIES, next)
            if (merge.added > 0) {
                bumpRevision(targetPayload)
            }
            collections.put(collection, targetPayload)
            root.put(KEY_COLLECTIONS, collections)
            save(root)
            return GlossaryImportResult(
                added = merge.added,
                skipped = merge.skipped,
                total = incoming.size,
                truncated = merge.truncated
            )
        }
    }

    fun exportCsv(collection: String = DEFAULT_COLLECTION): String {
        val sb = StringBuilder()
        sb.appendLine("source,target")
        getEntries(collection).forEach { entry ->
            sb.appendLine("${GlossaryCsv.escape(entry.source)},${GlossaryCsv.escape(entry.target)}")
        }
        return sb.toString()
    }

    private fun loadJson(): JSONObject {
        val raw = prefs.getString(KEY_STORE, "{}").orEmpty()
        val parsed = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        return migrateLegacy(parsed)
    }

    private fun migrateLegacy(root: JSONObject): JSONObject {
        if (root.has(KEY_COLLECTIONS)) return root
        val legacyEntries = root.optJSONArray(KEY_ENTRIES)
        val legacyRevision = root.optInt(KEY_REVISION, 0)
        val legacyGlossary = root.optJSONArray("glossary")
        if (legacyEntries == null && legacyGlossary == null) {
            root.put(KEY_COLLECTIONS, JSONObject())
            root.put(KEY_ACTIVE, JSONArray())
            return root
        }
        val entries = legacyEntries ?: legacyGlossary ?: JSONArray()
        val defaultCollection = JSONObject()
            .put(KEY_REVISION, legacyRevision)
            .put(KEY_ENTRIES, entries)
        root.put(KEY_COLLECTIONS, JSONObject().put(DEFAULT_COLLECTION, defaultCollection))
        root.put(KEY_ACTIVE, JSONArray().put(DEFAULT_COLLECTION))
        root.remove(KEY_ENTRIES)
        root.remove(KEY_REVISION)
        root.remove("glossary")
        save(root)
        return root
    }

    private fun collectionPayload(collection: String): JSONObject {
        val root = loadJson()
        val collections = root.optJSONObject(KEY_COLLECTIONS) ?: JSONObject()
        return collections.optJSONObject(collection) ?: freshCollection()
    }

    private fun collectionEntries(collection: String): JSONArray {
        return collectionPayload(collection).optJSONArray(KEY_ENTRIES) ?: JSONArray()
    }

    private fun freshCollection(): JSONObject {
        return JSONObject().put(KEY_REVISION, 0).put(KEY_ENTRIES, JSONArray())
    }

    private fun bumpRevision(payload: JSONObject) {
        payload.put(KEY_REVISION, payload.optInt(KEY_REVISION, 0) + 1)
    }

    private fun save(root: JSONObject) {
        prefs.edit().putString(KEY_STORE, root.toString()).apply()
    }

    internal object GlossaryCsv {
        fun parse(raw: String): List<Pair<String, String>> {
            val rows = parseRows(raw)
            val dataRows = if (rows.isNotEmpty() && looksLikeHeader(rows[0])) rows.drop(1) else rows
            val result = mutableListOf<Pair<String, String>>()
            for (row in dataRows) {
                if (row.size < 2) continue
                val source = row[0].trim()
                val target = row[1].trim()
                if (source.isNotEmpty() && target.isNotEmpty()) {
                    result += source to target
                }
            }
            return result
        }

        fun escape(value: String): String {
            return if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else value
        }

        private fun looksLikeHeader(row: List<String>): Boolean {
            if (row.size < 2) return false
            val a = row[0].trim().lowercase()
            val b = row[1].trim().lowercase()
            return (a == "source" || a == "原文" || a == "src" || a == "from") &&
                (b == "target" || b == "译文" || b == "tgt" || b == "to")
        }

        private fun parseRows(raw: String): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            var currentRow = mutableListOf<String>()
            val currentField = StringBuilder()
            var index = 0
            var insideQuotes = false
            val text = raw.replace("\r\n", "\n").replace("\r", "\n")
            while (index <= text.length) {
                val char = if (index < text.length) text[index] else '\n'
                when {
                    insideQuotes -> {
                        when (char) {
                            '"' -> {
                                val next = text.getOrNull(index + 1)
                                if (next == '"') {
                                    currentField.append('"')
                                    index++
                                } else {
                                    insideQuotes = false
                                }
                            }
                            '\n' -> currentField.append('\n')
                            else -> currentField.append(char)
                        }
                    }
                    char == '"' -> {
                        insideQuotes = true
                    }
                    char == ',' -> {
                        currentRow += currentField.toString()
                        currentField.clear()
                    }
                    char == '\n' || index == text.length -> {
                        currentRow += currentField.toString()
                        currentField.clear()
                        if (currentRow.size > 1 || currentRow.any { it.isNotBlank() }) {
                            rows += currentRow
                        }
                        currentRow = mutableListOf()
                    }
                    else -> currentField.append(char)
                }
                index++
            }
            return rows
        }
    }

    internal data class MergeResult(
        val merged: Map<String, String>,
        val added: Int,
        val skipped: Int,
        val truncated: Boolean
    )

    companion object {
        const val PREF_NAME = "mt_glossary"
        const val DEFAULT_COLLECTION = "默认"
        const val KEY_STORE = "store"
        const val KEY_COLLECTIONS = "collections"
        const val KEY_ACTIVE = "active"
        const val KEY_ENTRIES = "entries"
        const val KEY_REVISION = "revision"
        const val MAX_ENTRIES = 500

        internal fun mergeIncoming(
            existing: Map<String, String>,
            incoming: List<Pair<String, String>>,
            maxEntries: Int
        ): MergeResult {
            val merged = existing.toMutableMap()
            var added = 0
            var skipped = 0
            var truncated = false
            for ((source, target) in incoming) {
                val normalizedSource = source.trim()
                val normalizedTarget = target.trim()
                if (normalizedSource.isEmpty() || normalizedTarget.isEmpty()) {
                    skipped++
                    continue
                }
                if (merged.containsKey(normalizedSource)) {
                    skipped++
                    continue
                }
                if (merged.size >= maxEntries) {
                    truncated = true
                    break
                }
                merged[normalizedSource] = normalizedTarget
                added++
            }
            return MergeResult(merged = merged, added = added, skipped = skipped, truncated = truncated)
        }

        fun mergeActiveEntries(
            entriesByCollection: Map<String, List<Pair<String, String>>>,
            activeOrder: List<String>
        ): List<GlossaryEntry> {
            val seen = mutableSetOf<String>()
            val merged = mutableListOf<GlossaryEntry>()
            for (collection in activeOrder) {
                entriesByCollection[collection].orEmpty().forEach { (source, target) ->
                    val normalizedSource = source.trim()
                    val normalizedTarget = target.trim()
                    if (normalizedSource.isEmpty() || !seen.add(normalizedSource)) return@forEach
                    merged += GlossaryEntry(source = normalizedSource, target = normalizedTarget)
                }
            }
            return merged
        }

        fun uniqueCollectionName(existing: List<String>, base: String): String {
            val trimmed = base.trim()
            if (trimmed.isEmpty()) return trimmed
            if (!existing.contains(trimmed)) return trimmed
            var index = 2
            while (true) {
                val candidate = "$trimmed（$index）"
                if (!existing.contains(candidate)) return candidate
                index++
            }
        }
    }
}