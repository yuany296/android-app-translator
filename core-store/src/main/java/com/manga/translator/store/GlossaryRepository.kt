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

/** 术语表概览信息（用于管理列表一次性渲染，避免每行单独查库）。 */
data class GlossaryCollectionInfo(
    val name: String,
    val entryCount: Int,
    val active: Boolean,
    /** 生效优先级，从 1 开始；未生效时为 0。 */
    val priority: Int
)

/** 同一原文在某一层面术语表中的译文。 */
data class GlossaryConflictVariant(
    val collection: String,
    val target: String
)

/**
 * 跨术语表冲突：多张生效术语表对同一原文给出了不同译文。
 * 翻译时只会采用 [effectiveTarget]，其余译文会被静默丢弃，因此需要显式暴露出来。
 */
data class GlossaryConflict(
    val source: String,
    val effectiveTarget: String,
    val effectiveCollection: String,
    val variants: List<GlossaryConflictVariant>
)

/** 合并术语表的结果。 */
data class GlossaryMergeReport(
    val from: String,
    val into: String,
    val added: Int,
    val skipped: Int,
    /** 合并后源表是否已被删除。 */
    val sourceRemoved: Boolean
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
            if (collections.length() >= MAX_COLLECTIONS) return false
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

    // ------------------------------------------------------------------
    // 多术语表管理
    // ------------------------------------------------------------------

    /**
     * 列表页的一次性快照：名称 / 条数 / 是否生效 / 生效优先级。
     * 生效的排在前面并按优先级升序，未生效的按名称排序。
     */
    fun listInfos(): List<GlossaryCollectionInfo> {
        synchronized(lock) {
            val actives = getActiveCollections()
            val priorityByName = actives.withIndex().associate { (index, name) -> name to (index + 1) }
            return listCollections()
                .map { name ->
                    GlossaryCollectionInfo(
                        name = name,
                        entryCount = collectionEntries(name).length(),
                        active = priorityByName.containsKey(name),
                        priority = priorityByName[name] ?: 0
                    )
                }
                .sortedWith(compareBy({ if (it.active) 0 else 1 }, { it.priority }, { it.name }))
        }
    }

    /** 所有术语表的条目总数。 */
    fun totalEntryCount(): Int {
        synchronized(lock) {
            return listCollections().sumOf { collectionEntries(it).length() }
        }
    }

    /**
     * 调整生效术语表的优先级顺序：冲突时排在前面的表获胜。
     *
     * @param delta -1 上移（优先级提高），+1 下移。
     * @return 顺序是否真的发生了变化。
     */
    fun moveActiveCollection(name: String, delta: Int): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || delta == 0) return false
        synchronized(lock) {
            val next = movedOrder(getActiveCollections(), trimmed, delta) ?: return false
            setActiveCollections(next)
            return true
        }
    }

    /** 复制一张术语表（条目一并复制，副本 revision 归零，不自动生效）。 */
    fun duplicateCollection(source: String, newName: String): Boolean {
        val from = source.trim()
        val to = newName.trim()
        if (from.isEmpty() || to.isEmpty() || from == to) return false
        synchronized(lock) {
            val root = loadJson()
            val collections = root.optJSONObject(KEY_COLLECTIONS) ?: JSONObject()
            val payload = collections.optJSONObject(from) ?: return false
            if (collections.has(to)) return false
            if (collections.length() >= MAX_COLLECTIONS) return false
            val sourceArr = payload.optJSONArray(KEY_ENTRIES) ?: JSONArray()
            val copy = JSONArray()
            for (index in 0 until sourceArr.length()) {
                val item = sourceArr.optJSONObject(index) ?: continue
                copy.put(
                    JSONObject()
                        .put("source", item.optString("source", ""))
                        .put("target", item.optString("target", ""))
                )
            }
            collections.put(to, JSONObject().put(KEY_REVISION, 0).put(KEY_ENTRIES, copy))
            root.put(KEY_COLLECTIONS, collections)
            save(root)
            return true
        }
    }

    /**
     * 把 [from] 表的条目并入 [into] 表（原文已存在的跳过），随后删除 [from] 表。
     * 若 [from] 原本生效，则让 [into] 顶替它的生效位次，保持"这些术语应当生效"的意图。
     */
    fun mergeCollections(from: String, into: String): GlossaryMergeReport {
        val src = from.trim()
        val dst = into.trim()
        val noop = GlossaryMergeReport(from = src, into = dst, added = 0, skipped = 0, sourceRemoved = false)
        if (src.isEmpty() || dst.isEmpty() || src == dst) return noop
        synchronized(lock) {
            val root = loadJson()
            val collections = root.optJSONObject(KEY_COLLECTIONS) ?: JSONObject()
            val srcPayload = collections.optJSONObject(src) ?: return noop
            val dstPayload = collections.optJSONObject(dst) ?: return noop

            val existing = linkedMapOf<String, String>()
            val dstArr = dstPayload.optJSONArray(KEY_ENTRIES) ?: JSONArray()
            for (index in 0 until dstArr.length()) {
                val item = dstArr.optJSONObject(index) ?: continue
                val key = item.optString("source", "").trim()
                if (key.isNotEmpty()) existing[key] = item.optString("target", "").trim()
            }

            val incoming = mutableListOf<Pair<String, String>>()
            val srcArr = srcPayload.optJSONArray(KEY_ENTRIES) ?: JSONArray()
            for (index in 0 until srcArr.length()) {
                val item = srcArr.optJSONObject(index) ?: continue
                val key = item.optString("source", "").trim()
                if (key.isEmpty()) continue
                incoming += key to item.optString("target", "").trim()
            }

            val merge = mergeIncoming(existing = existing, incoming = incoming, maxEntries = MAX_ENTRIES)
            val merged = JSONArray()
            merge.merged.forEach { (source, target) ->
                merged.put(JSONObject().put("source", source).put("target", target))
            }
            dstPayload.put(KEY_ENTRIES, merged)
            if (merge.added > 0) bumpRevision(dstPayload)
            collections.put(dst, dstPayload)

            val nextActive = planMergedActiveOrder(getActiveCollections(), src, dst)
            collections.remove(src)
            root.put(KEY_COLLECTIONS, collections)
            root.put(KEY_ACTIVE, JSONArray().apply { nextActive.forEach { put(it) } })
            save(root)

            return GlossaryMergeReport(
                from = src,
                into = dst,
                added = merge.added,
                skipped = merge.skipped,
                sourceRemoved = true
            )
        }
    }

    /**
     * 找出多张生效术语表里"同一原文、不同译文"的条目。
     * 这些条目在翻译时只有优先级最高的那张表会生效，其余被静默丢弃。
     */
    fun findConflicts(): List<GlossaryConflict> {
        val actives = getActiveCollections()
        if (actives.size < 2) return emptyList()
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
            return collectConflicts(entriesByCollection, actives)
        }
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
        const val MAX_COLLECTIONS = 50

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

        /**
         * 计算调整生效顺序后的新顺序。
         * @return null 表示无变化（名字不在生效列表里，或已经顶到边界）。
         */
        internal fun movedOrder(activeOrder: List<String>, name: String, delta: Int): List<String>? {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || delta == 0) return null
            val index = activeOrder.indexOf(trimmed)
            if (index < 0) return null
            val target = (index + delta).coerceIn(0, activeOrder.size - 1)
            if (target == index) return null
            val next = activeOrder.toMutableList()
            next.removeAt(index)
            next.add(target, trimmed)
            return next
        }

        /**
         * 合并术语表后计算新的生效顺序：源表 [from] 被移除；
         * 若它原本生效，则让 [into] 顶替它的位次，避免"合并完反而不生效了"。
         */
        internal fun planMergedActiveOrder(activeOrder: List<String>, from: String, into: String): List<String> {
            val srcIndex = activeOrder.indexOf(from)
            val next = activeOrder.filterNot { it == from }.toMutableList()
            if (srcIndex >= 0 && !next.contains(into)) {
                next.add(srcIndex.coerceIn(0, next.size), into)
            }
            return next
        }

        /**
         * 收集跨表冲突：同一原文出现在多张生效表中且译文不同。
         * 生效译文取生效顺序中最靠前的那张表提供的译文。
         */
        internal fun collectConflicts(
            entriesByCollection: Map<String, List<Pair<String, String>>>,
            activeOrder: List<String>
        ): List<GlossaryConflict> {
            val bySource = linkedMapOf<String, MutableList<GlossaryConflictVariant>>()
            for (collection in activeOrder) {
                entriesByCollection[collection].orEmpty().forEach { (source, target) ->
                    val normalized = source.trim()
                    if (normalized.isEmpty()) return@forEach
                    bySource.getOrPut(normalized) { mutableListOf() } += GlossaryConflictVariant(
                        collection = collection,
                        target = target.trim()
                    )
                }
            }
            return bySource.mapNotNull { (source, variants) ->
                if (variants.map { it.target }.distinct().size < 2) return@mapNotNull null
                val winner = variants.first()
                GlossaryConflict(
                    source = source,
                    effectiveTarget = winner.target,
                    effectiveCollection = winner.collection,
                    variants = variants.toList()
                )
            }.sortedBy { it.source }
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