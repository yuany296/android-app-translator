package com.manga.translator.store

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 一句译文在某一页出现过（2026-11-02「翻译历史尽量详细」）。
 *
 * 旧模型只有一个 `originPageHash`：同一句在多页出现时，后一页会把前一页顶掉，
 * 「按页」历史因此会少句/错页。现在每句保留一份有上限的来源列表，
 * 每页各自的首次/最近出现时间和本页命中次数都能查。
 */
data class SentenceOrigin(
    val pageHash: String = "",
    /** 展示用来源（站点 host / 「截图翻译」）；页面详情缺失时兜底。 */
    val label: String = "",
    val firstSeenAtMs: Long = 0L,
    val lastSeenAtMs: Long = 0L,
    /** 在这页命中句子缓存的次数（不含首次翻译）。 */
    val hitCount: Int = 0,
    /** 最近一次命中方式：EXACT / COMPACT / FUZZY / NEW。 */
    val lastMatchKind: String = ""
)

/**
 * 一条句子译文（2026-10-31 起也是「句子历史」的一行）。
 *
 * [originLabel] / [originPageHash] / [createdAtMs] / [lastHitAtMs] / [hitCount] 是
 * 2026-10-31 新增的溯源元数据：以前只有原文和译文，界面上没法回答「这句是哪来的、
 * 什么时候翻的、被复用过几次」。旧文件缺这些字段时按默认值补齐，不做版本作废。
 *
 * [origins] 是 2026-11-02 追加的多页来源：旧文件没有这个字段时，由
 * [originsOrLegacy] 用旧的单页字段合成一条，迁移不需要改缓存版本。
 */
data class SentenceTranslation(
    val sourceLanguage: String,
    val targetLanguage: String,
    val originalText: String,
    val translatedText: String,
    /** 展示用来源（站点 host 或「悬浮球」），空 = 旧数据未知。 */
    val originLabel: String = "",
    /** 最近一次出现的页面标识，用于「按页」分组。 */
    val originPageHash: String = "",
    val createdAtMs: Long = 0L,
    val lastHitAtMs: Long = 0L,
    val hitCount: Int = 0,
    /**
     * 写入这条译文时，**这句原文实际命中的术语**的内容指纹（2026-11-02）。
     *
     * 空串 = 当时没有生效术语命中这句；`manual` = 用户在翻译历史里手工改过，
     * 永久跳过术语指纹校验（尊重用户最后的修改）。
     */
    val glossaryFingerprint: String = "",
    /** 多页来源列表，按最近出现时间倒序保留 [MAX_ORIGINS] 条。 */
    val origins: List<SentenceOrigin> = emptyList()
)

/**
 * 读取多页来源；旧数据没有 [SentenceTranslation.origins] 时用单页字段合成。
 *
 * 页面级详情缺失也不影响「按页」分组：至少还能按 pageHash 分到正确的页，
 * 只是少 URL/标题/引擎等信息。
 */
fun SentenceTranslation.originsOrLegacy(): List<SentenceOrigin> {
    if (origins.isNotEmpty()) return origins
    val hash = originPageHash.trim()
    if (hash.isEmpty()) return emptyList()
    val created = createdAtMs.takeIf { it > 0L } ?: 0L
    val last = maxOf(lastHitAtMs, createdAtMs).takeIf { it > 0L } ?: 0L
    return listOf(
        SentenceOrigin(
            pageHash = hash,
            label = originLabel,
            firstSeenAtMs = created,
            lastSeenAtMs = last,
            hitCount = hitCount
        )
    )
}

enum class SentenceCacheMatchKind { EXACT, COMPACT, FUZZY }

/**
 * 回填来源用的快照坐标（2026-10-31）：某一页的标识和它被翻译的时间。
 *
 * 见 [SentenceTranslationRepository.backfillOrigin]。
 */
data class OriginSnapshot(
    val pageHash: String,
    val timestampMs: Long
)

/**
 * 句子缓存的坐标（2026-10-15）。
 *
 * 在翻译历史里发现某句译错了时，拿它把对应的一条缓存作废，下次遇到这句会重新翻译。
 */
data class SentenceCacheKey(
    val sourceLanguage: String,
    val targetLanguage: String,
    val originalText: String
)

/**
 * 一条待写入的句子缓存（2026-11-02，P3）。
 *
 * [PageTranslationResolver] 一页会产出十几条新译文。旧实现逐条 `put()`，每条都把
 * 整个 JSON 全量落盘；`putAll` 让这批更新只在末尾写一次。
 */
data class SentenceTranslationUpdate(
    val sourceLanguage: String,
    val targetLanguage: String,
    val originalText: String,
    val translatedText: String,
    val originLabel: String = "",
    val originPageHash: String = "",
    /** 这句原文实际命中的术语指纹；由 [PageTranslationResolver] 在写缓存前算好。 */
    val glossaryFingerprint: String = ""
)

data class SentenceCacheLookup(
    val translatedText: String?,
    val matchKind: SentenceCacheMatchKind?,
    /** 命中了条目但术语指纹已过期：调用方应把它当 miss，重新翻译。 */
    val staleGlossary: Boolean = false
) {
    val hit: Boolean get() = translatedText != null

    companion object {
        val MISS = SentenceCacheLookup(null, null)
    }
}

/**
 * 句子级翻译缓存：原文 -> 译文 一一对应，按（源语言, 目标语言）隔离。
 * 不依赖截图/页码，同一句原文只要源语言与目标语言一致即可命中。
 *
 * 本地 OCR 对同一句视觉文本的识别结果会存在字符级抖动（例如 COUNTILESS / COUNTLESS），
 * 因此除逐字精确匹配外，还提供：
 * 1. compact 匹配：忽略大小写/空白/标点后精确相等；
 * 2. fuzzy 匹配：仅限拉丁字符文本、编辑距离 <= 容差且共享足够公共前缀（降低语义漂移风险）。
 * 命中方式通过 [SentenceCacheLookup.matchKind] 返回，便于调用方记录日志。
 */
class SentenceTranslationRepository(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()
    private var loaded = false
    private val index = linkedMapOf<String, SentenceTranslation>()
    private var compactDirty = true
    // langKey -> compactKey -> raw keys。按语言对分桶：模糊匹配只扫当前语言对，
    // 同时避免同一原文在不同语言对之间串译文（2026-11-02）。
    private val compactIndex = hashMapOf<String, HashMap<String, MutableList<String>>>()
    private var lastStatsPersistMs = 0L
    private var statsDirty = false

    fun lookup(
        sourceLanguage: String,
        targetLanguage: String,
        originalText: String,
        glossaryFingerprint: String? = null,
        /**
         * 当前翻译发生在哪一页（2026-11-02）：命中缓存时把「本页也出现过」
         * 记进来源列表；空串 = 调用方不关心来源（老入口/测试）。
         */
        originPageHash: String = "",
        originLabel: String = ""
    ): SentenceCacheLookup {
        val normalized = normalize(originalText)
        if (normalized.isEmpty()) return SentenceCacheLookup.MISS
        val langKey = "${sourceLanguage.trim()}|${targetLanguage.trim()}"
        synchronized(lock) {
            ensureLoaded()
            fun staleHit(entry: SentenceTranslation, kind: String): SentenceCacheLookup {
                Log.d(
                    TAG,
                    "lookup stale_glossary lang=$langKey kind=$kind " +
                        "stored=${entry.glossaryFingerprint.take(12)} " +
                        "current=${glossaryFingerprint.orEmpty().take(12)} " +
                        "text=${truncate(originalText)}"
                )
                return SentenceCacheLookup(null, null, staleGlossary = true)
            }

            val exactKey = "$langKey|$normalized"
            index[exactKey]?.let { entry ->
                if (isPoisoned(entry, normalized)) {
                    Log.d(TAG, "lookup poisoned_skip lang=$langKey kind=exact text=${truncate(originalText)}")
                    return SentenceCacheLookup.MISS
                }
                if (!isGlossaryFingerprintFresh(entry.glossaryFingerprint, glossaryFingerprint)) {
                    return staleHit(entry, "exact")
                }
                return recordHit(entry, SentenceCacheMatchKind.EXACT, originPageHash, originLabel)
            }

            val compact = compactKey(normalized)
            if (compact.isNotEmpty()) {
                ensureCompactIndex()
                val langIndex = compactIndex[langKey]
                val compactCandidates = langIndex?.get(compact).orEmpty()
                if (compactCandidates.isNotEmpty()) {
                    compactCandidates.firstOrNull()?.let { rawKey ->
                        index[rawKey]?.let { entry ->
                            if (isPoisoned(entry, normalized)) {
                                Log.d(TAG, "lookup poisoned_skip lang=$langKey kind=compact text=${truncate(originalText)}")
                                return SentenceCacheLookup.MISS
                            }
                            if (!isGlossaryFingerprintFresh(entry.glossaryFingerprint, glossaryFingerprint)) {
                                return staleHit(entry, "compact")
                            }
                            return recordHit(entry, SentenceCacheMatchKind.COMPACT, originPageHash, originLabel)
                        }
                    }
                }

                if (compact.length >= MIN_FUZZY_COMPACT_LEN && langIndex != null) {
                    val tolerance = fuzzyTolerance(compact.length)
                    var bestRawKey: String? = null
                    var bestDist = Int.MAX_VALUE
                    for ((candidateCompact, rawKeys) in langIndex) {
                        if (!fuzzyMatchAllowed(compact, candidateCompact)) continue
                        if (abs(candidateCompact.length - compact.length) > tolerance) continue
                        val dist = boundedEditDistance(compact, candidateCompact, tolerance)
                        if (dist in 1..tolerance && dist < bestDist) {
                            bestDist = dist
                            bestRawKey = rawKeys.firstOrNull()
                        }
                    }
                    if (bestRawKey != null) {
                        index[bestRawKey]?.let { entry ->
                            if (isPoisoned(entry, normalized)) {
                                Log.d(TAG, "lookup poisoned_skip lang=$langKey kind=fuzzy text=${truncate(originalText)}")
                                return SentenceCacheLookup.MISS
                            }
                            if (!isGlossaryFingerprintFresh(entry.glossaryFingerprint, glossaryFingerprint)) {
                                return staleHit(entry, "fuzzy")
                            }
                            Log.d(
                                TAG,
                                "lookup fuzzy_hit lang=$langKey dist=$bestDist " +
                                    "src=${truncate(originalText)} matched=${truncate(entry.originalText)}"
                            )
                            return recordHit(entry, SentenceCacheMatchKind.FUZZY, originPageHash, originLabel)
                        }
                    }
                }
            }
            Log.d(TAG, "lookup miss lang=$langKey text=${truncate(originalText)}")
            return SentenceCacheLookup.MISS
        }
    }

    fun put(
        sourceLanguage: String,
        targetLanguage: String,
        originalText: String,
        translatedText: String,
        originLabel: String = "",
        originPageHash: String = ""
    ) {
        putAll(
            listOf(
                SentenceTranslationUpdate(
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    originalText = originalText,
                    translatedText = translatedText,
                    originLabel = originLabel,
                    originPageHash = originPageHash
                )
            )
        )
    }

    /**
     * 批量写入（2026-11-02，P3）：一页十几条新译文只落盘一次。
     *
     * 旧实现每条 `put()` 都 `persist()` 整个 JSON；真机一张图 10+ 句时会连续写
     * 10+ 次 400KB 文件，直接体现在 `post_ms` 里。
     */
    fun putAll(updates: List<SentenceTranslationUpdate>) {
        if (updates.isEmpty()) return
        synchronized(lock) {
            ensureLoaded()
            // 先建好索引，再增量更新；旧实现每次 put 都把 compactDirty 置真，下一次 lookup
            // 重建 5000 条全量索引（真机每张图多花 200–350ms，2026-11-02）。
            ensureCompactIndex()
            var changed = false
            updates.forEach { update ->
                val normalized = normalize(update.originalText)
                val value = normalize(update.translatedText)
                if (normalized.isEmpty() || value.isEmpty()) return@forEach
                if (value == normalized) {
                    // 译文等于原文：视为翻译未生效（本地引擎失败回退、模型原样返回等），不入缓存，
                    // 否则会形成"原文当译文"的毒化条目，后续全部命中显示原文。
                    Log.d(
                        TAG,
                        "put skip_noop lang=${update.sourceLanguage}|${update.targetLanguage} " +
                            "text=${truncate(update.originalText)}"
                    )
                    return@forEach
                }
                applyPutLocked(update, normalized, value)
                changed = true
            }
            if (!changed) return
            trimIfNeeded()
            persist()
        }
    }

    private fun applyPutLocked(
        update: SentenceTranslationUpdate,
        normalized: String,
        value: String
    ) {
        val key = keyOf(update.sourceLanguage, update.targetLanguage, normalized)
        val previous = index.remove(key)
        if (previous != null) {
            removeCompactEntry(
                langKey = compactLangKey(previous.sourceLanguage, previous.targetLanguage),
                originalText = previous.originalText,
                rawKey = key
            )
        }
        val now = System.currentTimeMillis()
        val entry = SentenceTranslation(
            sourceLanguage = update.sourceLanguage,
            targetLanguage = update.targetLanguage,
            originalText = normalized,
            translatedText = value,
            originLabel = update.originLabel.trim().ifBlank { previous?.originLabel.orEmpty() },
            originPageHash = update.originPageHash.trim().ifBlank { previous?.originPageHash.orEmpty() },
            // 首次写入时间沿用旧值，避免改一次译文就把「什么时候开始的」冲掉。
            createdAtMs = previous?.createdAtMs?.takeIf { it > 0L } ?: now,
            lastHitAtMs = previous?.lastHitAtMs ?: 0L,
            hitCount = previous?.hitCount ?: 0,
            glossaryFingerprint = update.glossaryFingerprint.trim(),
            // 同一句在新的一页被重新翻译：来源列表里加一条，旧页不会丢。
            origins = mergeOrigin(
                origins = previous?.originsOrLegacy().orEmpty(),
                pageHash = update.originPageHash,
                label = update.originLabel,
                now = now,
                matchKind = MATCH_KIND_NEW,
                hit = false
            )
        )
        index[key] = entry
        addCompactEntry(entry, key)
    }

    /** 全量快照，按「最近用过」倒序；句子历史页的数据源。 */
    fun all(): List<SentenceTranslation> {
        synchronized(lock) {
            ensureLoaded()
            return index.values.sortedWith(
                compareByDescending<SentenceTranslation> {
                    maxOf(
                        it.lastHitAtMs,
                        it.createdAtMs,
                        it.origins.maxOfOrNull { origin -> origin.lastSeenAtMs } ?: 0L
                    )
                }.thenByDescending { it.createdAtMs }
            )
        }
    }

    /**
     * 用历史快照回填旧条目的「来自哪一页 / 什么时候翻的」（2026-10-31）。
     *
     * 为什么需要：`originPageHash` / `createdAtMs` 是 2026-10-31 才加的字段，
     * 之前的句子一律是空值。于是「翻译历史 → 按页」会把这批句子全部归成一组
     * 「来源未知」，界面上跟「按句」看不出区别 —— 用户反馈的正是这个。
     * 而页面级的历史快照里正好留着每页的 pageHash、时间和当时的原文，可以补回去。
     *
     * **只填空字段，不覆盖已有值**；重复调用幂等。返回真正被补上的条数
     * （0 = 没有可补的，调用方不必提示）。
     *
     * 注意 [mapping] 的键必须用 [originKey] 构造，否则一条也匹配不上（且不会报错）。
     */
    fun backfillOrigin(mapping: Map<String, OriginSnapshot>): Int {
        if (mapping.isEmpty()) return 0
        synchronized(lock) {
            ensureLoaded()
            var filled = 0
            for ((key, entry) in index.toList()) {
                val patched = backfilledEntry(entry, mapping[key]) ?: continue
                index[key] = patched
                filled += 1
            }
            if (filled > 0) persist()
            return filled
        }
    }

    /** 改译文：只动这一条，命中统计与来源保持不变。返回是否命中到条目。 */
    fun updateTranslation(
        sourceLanguage: String,
        targetLanguage: String,
        originalText: String,
        translatedText: String
    ): Boolean {
        val normalized = normalize(originalText)
        val value = normalize(translatedText)
        if (normalized.isEmpty() || value.isEmpty()) return false
        synchronized(lock) {
            ensureLoaded()
            val key = keyOf(sourceLanguage, targetLanguage, normalized)
            val entry = index[key] ?: return false
            // 用户手工改过的译文是「最终答案」：以后术语再变也不覆盖它。
            index[key] = entry.copy(
                translatedText = value,
                glossaryFingerprint = MANUAL_FINGERPRINT
            )
            persist()
            return true
        }
    }

    fun count(): Int {
        synchronized(lock) {
            ensureLoaded()
            return index.size
        }
    }

    fun clear() {
        synchronized(lock) {
            index.clear()
            compactIndex.clear()
            compactDirty = true
            statsDirty = false
            lastStatsPersistMs = 0L
            file.delete()
        }
    }

    /**
     * 作废若干条缓存（2026-10-15，翻译历史页的「作废」入口用）；返回实际删掉的条数。
     *
     * 只删不写：删掉后这句变成缓存未命中，下次遇到会重新调翻译引擎并写回新结果。
     * 批量一次落盘，避免一页几十句时反复重写整个 JSON。
     */
    fun remove(keys: Collection<SentenceCacheKey>): Int {
        if (keys.isEmpty()) return 0
        synchronized(lock) {
            ensureLoaded()
            var removed = 0
            keys.forEach { key ->
                val normalized = normalize(key.originalText)
                if (normalized.isEmpty()) return@forEach
                val rawKey = keyOf(key.sourceLanguage, key.targetLanguage, normalized)
                val removedEntry = index.remove(rawKey)
                if (removedEntry != null) {
                    removeCompactEntry(
                        langKey = compactLangKey(removedEntry.sourceLanguage, removedEntry.targetLanguage),
                        originalText = removedEntry.originalText,
                        rawKey = rawKey
                    )
                    removed += 1
                }
            }
            if (removed > 0) {
                persist()
            }
            return removed
        }
    }

    /** 单条作废的便捷入口；返回是否删到了（false = 本来就不在缓存里）。 */
    fun remove(sourceLanguage: String, targetLanguage: String, originalText: String): Boolean {
        return remove(listOf(SentenceCacheKey(sourceLanguage, targetLanguage, originalText))) > 0
    }

    /**
     * 命中计数（2026-10-31）：给句子历史页显示「命中 N 次 / 最近使用」。
     *
     * 只更新内存并**限频落盘**（最多 30 秒一次）：lookup 在翻译热路径上，一页几十句，
     * 每命中一次就重写 400KB 的 JSON 会把 I/O 打满。
     */
    private fun recordHit(
        entry: SentenceTranslation,
        kind: SentenceCacheMatchKind,
        originPageHash: String,
        originLabel: String
    ): SentenceCacheLookup {
        val now = System.currentTimeMillis()
        val updated = entry.copy(
            hitCount = entry.hitCount + 1,
            lastHitAtMs = now,
            // 最近一次出现在哪一页：按句视图显示「最近来源」用；按页分组读 origins。
            originPageHash = originPageHash.trim().ifBlank { entry.originPageHash },
            originLabel = originLabel.trim().ifBlank { entry.originLabel },
            origins = mergeOrigin(
                origins = entry.originsOrLegacy(),
                pageHash = originPageHash,
                label = originLabel,
                now = now,
                matchKind = kind.name,
                hit = true
            )
        )
        index[keyOf(entry.sourceLanguage, entry.targetLanguage, entry.originalText)] = updated
        statsDirty = true
        if (now - lastStatsPersistMs >= STATS_PERSIST_INTERVAL_MS) {
            lastStatsPersistMs = now
            statsDirty = false
            runCatching { persist() }
        }
        return SentenceCacheLookup(updated.translatedText, kind)
    }

    /**
     * 把本次页面命中的来源统计立即落盘（2026-11-02）。
     *
     * 热路径上的 [recordHit] 只做限频落盘；一页翻完调用一次这里，
     * 避免 App 被杀进程时丢掉「本页命中次数 / 最近出现时间」。无改动时是空操作。
     */
    fun flushStats() {
        synchronized(lock) {
            if (!statsDirty) return
            statsDirty = false
            lastStatsPersistMs = System.currentTimeMillis()
            runCatching { persist() }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        runCatching {
            val root = JSONObject(text)
            // 2026-10-18：缓存语义版本。提示词/上下文策略变化后，旧译文不能继续命中，
            // 否则「页面上下文」永远只对新句子生效。版本不符时整份丢弃，一次性重翻。
            val version = root.optInt("version", 0)
            if (version != CACHE_VERSION) {
                Log.d(TAG, "cache_reset old_version=$version current=$CACHE_VERSION")
                file.delete()
                return
            }
            val arr = root.optJSONArray("entries") ?: JSONArray()
            for (itemIndex in 0 until arr.length()) {
                val item = arr.optJSONObject(itemIndex) ?: continue
                val sourceLanguage = item.optString("sourceLanguage", "").trim()
                val targetLanguage = item.optString("targetLanguage", "").trim()
                val originalText = normalize(item.optString("originalText", ""))
                val translatedText = normalize(item.optString("translatedText", ""))
                if (sourceLanguage.isEmpty() || targetLanguage.isEmpty() || originalText.isEmpty() || translatedText.isEmpty()) {
                    continue
                }
                val parsed = SentenceTranslation(
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    originalText = originalText,
                    translatedText = translatedText,
                    // 2026-10-31 新增字段：旧文件没有 → 默认值（来源未知、时间未知）。
                    originLabel = item.optString("originLabel", ""),
                    originPageHash = item.optString("originPageHash", ""),
                    createdAtMs = item.optLong("createdAtMs", 0L),
                    lastHitAtMs = item.optLong("lastHitAtMs", 0L),
                    hitCount = item.optInt("hitCount", 0),
                    // 2026-11-02 新增：旧文件没有这个字段 → 空串。
                    // 含生效术语的句子会在下一次 lookup 时被判 stale 并重译；
                    // 不含术语的句子继续命中，不需要整库清空。
                    glossaryFingerprint = item.optString("glossaryFingerprint", ""),
                    origins = parseOrigins(item.optJSONArray("origins"))
                )
                index[keyOf(sourceLanguage, targetLanguage, originalText)] =
                    if (parsed.origins.isEmpty()) parsed.copy(origins = parsed.originsOrLegacy()) else parsed
            }
            compactDirty = true
        }
    }

    private fun ensureCompactIndex() {
        if (!compactDirty) return
        compactDirty = false
        compactIndex.clear()
        index.forEach { (rawKey, entry) ->
            addCompactEntry(entry, rawKey)
        }
    }

    /** 增量往 compactIndex 加一条；调用方必须持有 lock。 */
    private fun addCompactEntry(entry: SentenceTranslation, rawKey: String) {
        val compact = compactKey(entry.originalText)
        if (compact.isEmpty()) return
        val langKey = compactLangKey(entry.sourceLanguage, entry.targetLanguage)
        compactIndex
            .getOrPut(langKey) { hashMapOf() }
            .getOrPut(compact) { mutableListOf() }
            .add(rawKey)
    }

    /** 增量从 compactIndex 删一条；列表空时删桶，避免长期累积空键。 */
    private fun removeCompactEntry(langKey: String, originalText: String, rawKey: String) {
        val compact = compactKey(originalText)
        if (compact.isEmpty()) return
        val langIndex = compactIndex[langKey] ?: return
        val keys = langIndex[compact] ?: return
        keys.remove(rawKey)
        if (keys.isEmpty()) langIndex.remove(compact)
        if (langIndex.isEmpty()) compactIndex.remove(langKey)
    }

    private fun compactLangKey(sourceLanguage: String, targetLanguage: String): String {
        return "${sourceLanguage.trim()}|${targetLanguage.trim()}"
    }

    private fun trimIfNeeded() {
        while (index.size > MAX_ENTRIES) {
            val firstKey = index.keys.firstOrNull() ?: break
            val removed = index.remove(firstKey) ?: break
            removeCompactEntry(
                langKey = compactLangKey(removed.sourceLanguage, removed.targetLanguage),
                originalText = removed.originalText,
                rawKey = firstKey
            )
        }
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        index.values.forEach { entry ->
            arr.put(
                JSONObject()
                    .put("sourceLanguage", entry.sourceLanguage)
                    .put("targetLanguage", entry.targetLanguage)
                    .put("originalText", entry.originalText)
                    .put("translatedText", entry.translatedText)
                    .put("originLabel", entry.originLabel)
                    .put("originPageHash", entry.originPageHash)
                    .put("createdAtMs", entry.createdAtMs)
                    .put("lastHitAtMs", entry.lastHitAtMs)
                    .put("hitCount", entry.hitCount)
                    .put("glossaryFingerprint", entry.glossaryFingerprint)
                    .put("origins", originsToJson(entry.origins))
            )
        }
        file.writeText(
            JSONObject()
                .put("version", CACHE_VERSION)
                .put("entries", arr)
                .toString()
        )
        statsDirty = false
        lastStatsPersistMs = System.currentTimeMillis()
    }

    private fun keyOf(sourceLanguage: String, targetLanguage: String, originalText: String): String {
        return "${sourceLanguage.trim()}|${targetLanguage.trim()}|$originalText"
    }

    private fun normalize(text: String): String {
        return text.trim().replace(Regex("\\s+"), " ")
    }

    /** 忽略大小写/空白/标点，仅保留字母与数字，用于抗 OCR 抖动匹配。 */
    private fun compactKey(text: String): String {
        return text.lowercase().filter { it.isLetterOrDigit() }
    }

    private fun isLatinCompact(text: String): Boolean {
        return text.all { it in 'a'..'z' || it in '0'..'9' }
    }

    /** 仅拉丁字符文本参与模糊匹配，避免日语/中文等单字符差异导致语义漂移。 */
    private fun fuzzyMatchAllowed(a: String, b: String): Boolean {
        if (a.length < MIN_FUZZY_COMPACT_LEN || b.length < MIN_FUZZY_COMPACT_LEN) return false
        if (!isLatinCompact(a) || !isLatinCompact(b)) return false
        val minLen = min(a.length, b.length)
        var prefix = 0
        while (prefix < minLen && a[prefix] == b[prefix]) prefix++
        // 只需共享一个较短的前缀；长句子的防误匹配由编辑距离容差保证
        // （同一视觉文本的 OCR 抖动通常只发生在句子中段，例如 COUNTUESS / COUNTLESS）。
        return prefix >= MIN_COMMON_PREFIX
    }

    private fun fuzzyTolerance(len: Int): Int {
        return if (len >= FUZZY_TOLERANCE_LONG_LEN) 2 else 1
    }

    private fun boundedEditDistance(a: String, b: String, maxDist: Int): Int {
        if (abs(a.length - b.length) > maxDist) return maxDist + 1
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                rowMin = min(rowMin, curr[j])
            }
            if (rowMin > maxDist) return maxDist + 1
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    private fun isPoisoned(entry: SentenceTranslation, normalizedQuery: String): Boolean {
        // 历史遗留：早期"本地引擎失败/未配置云端 API"时会把原文当译文写入缓存。
        // 命中此类条目视为未命中，允许重新翻译并覆盖。
        return entry.translatedText == normalizedQuery
    }

    private fun truncate(text: String): String {
        val compact = text.replace("\n", "\\n")
        return if (compact.length <= 40) compact else compact.take(40) + "..."
    }

    companion object {
        const val FILE_NAME = "mt_sentence_translation.json"
        const val MAX_ENTRIES = 5000

        /**
         * 回填规则（纯函数，便于单测）：返回补好的条目，或 null 表示这条不用动。
         *
         * 三条规矩：
         * 1. 没有快照 → 不动；
         * 2. 只填空字段，已有值一律保留（旧数据可能比历史快照更准）；
         * 3. 两个字段都已填 → 不动（幂等的来源：重复跑返回 0）。
         */
        fun backfilledEntry(
            entry: SentenceTranslation,
            snapshot: OriginSnapshot?
        ): SentenceTranslation? {
            if (snapshot == null) return null
            val needHash = entry.originPageHash.isEmpty()
            val needTime = entry.createdAtMs <= 0L
            // 两个旧字段都已填 → 不动（幂等）。旧文件缺 origins 时由 ensureLoaded
            // 用旧字段合成一条，不靠回填触发，避免每次启动都重写整份 JSON。
            if (!needHash && !needTime) return null
            val patched = entry.copy(
                originPageHash = if (needHash) snapshot.pageHash else entry.originPageHash,
                createdAtMs = if (needTime) snapshot.timestampMs else entry.createdAtMs
            )
            if (patched.originPageHash.isBlank() && patched.origins.isEmpty()) return patched
            // 旧数据没有多页来源：先用旧字段合成一条，再把历史快照补成第二条。
            // 两者 pageHash 相同（常见情况）时 mergeOrigin 会合并，不会重复。
            return patched.copy(
                origins = mergeOrigin(
                    origins = patched.originsOrLegacy(),
                    pageHash = snapshot.pageHash,
                    label = patched.originLabel,
                    now = snapshot.timestampMs,
                    matchKind = MATCH_KIND_NEW,
                    hit = false,
                    firstSeenFallback = snapshot.timestampMs
                )
            )
        }

        /**
         * 合并一条页面来源（纯函数，便于单测）。
         *
         * - 同一 `pageHash` 只保留一条：更新最近出现时间，[hit] 时命中次数 +1；
         * - 首次出现时间优先沿用旧值；[firstSeenFallback] 用于回填旧快照；
         * - 来源列表按最近出现时间倒序，最多 [MAX_ORIGINS] 条，避免单句无限膨胀。
         */
        internal fun mergeOrigin(
            origins: List<SentenceOrigin>,
            pageHash: String,
            label: String,
            now: Long,
            matchKind: String? = null,
            hit: Boolean = false,
            firstSeenFallback: Long = 0L
        ): List<SentenceOrigin> {
            val key = pageHash.trim()
            if (key.isEmpty()) return origins.take(MAX_ORIGINS)
            val existing = origins.firstOrNull { it.pageHash == key }
            val firstSeen = existing?.firstSeenAtMs?.takeIf { it > 0L }
                ?: firstSeenFallback.takeIf { it > 0L }
                ?: now
            val merged = SentenceOrigin(
                pageHash = key,
                label = label.trim().ifBlank { existing?.label.orEmpty() },
                firstSeenAtMs = firstSeen,
                lastSeenAtMs = maxOf(existing?.lastSeenAtMs ?: 0L, now),
                hitCount = (existing?.hitCount ?: 0) + if (hit) 1 else 0,
                lastMatchKind = matchKind?.trim().orEmpty().ifBlank {
                    existing?.lastMatchKind.orEmpty()
                }
            )
            return (origins.filterNot { it.pageHash == key } + merged)
                .sortedByDescending { it.lastSeenAtMs }
                .take(MAX_ORIGINS)
        }

        internal fun originsToJson(origins: List<SentenceOrigin>): JSONArray {
            val arr = JSONArray()
            origins.forEach { origin ->
                arr.put(
                    JSONObject()
                        .put("pageHash", origin.pageHash)
                        .put("label", origin.label)
                        .put("firstSeenAtMs", origin.firstSeenAtMs)
                        .put("lastSeenAtMs", origin.lastSeenAtMs)
                        .put("hitCount", origin.hitCount)
                        .put("lastMatchKind", origin.lastMatchKind)
                )
            }
            return arr
        }

        internal fun parseOrigins(array: JSONArray?): List<SentenceOrigin> {
            if (array == null || array.length() == 0) return emptyList()
            val list = mutableListOf<SentenceOrigin>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val pageHash = item.optString("pageHash", "").trim()
                if (pageHash.isEmpty()) continue
                list += SentenceOrigin(
                    pageHash = pageHash,
                    label = item.optString("label", ""),
                    firstSeenAtMs = item.optLong("firstSeenAtMs", 0L),
                    lastSeenAtMs = item.optLong("lastSeenAtMs", 0L),
                    hitCount = item.optInt("hitCount", 0),
                    lastMatchKind = item.optString("lastMatchKind", "")
                )
            }
            return list.sortedByDescending { it.lastSeenAtMs }.take(MAX_ORIGINS)
        }

        /**
         * 句子表的键：`源语言|目标语言|原文`（原文按入库规则归一化：去首尾空白 + 压缩空白）。
         *
         * 暴露出来是给「用历史快照回填来源」用的 —— 回填方必须和内部用同一套键，
         * 否则一条也匹配不上，而且不会报错（只会静默回填 0 条）。
         */
        fun originKey(sourceLanguage: String, targetLanguage: String, originalText: String): String {
            val text = originalText.trim().replace(Regex("\\s+"), " ")
            return "${sourceLanguage.trim()}|${targetLanguage.trim()}|$text"
        }

        /**
         * 句子缓存语义版本（2026-10-18）。提示词、页面上下文策略或翻译规则发生
         * 影响译文的改变时 +1：旧条目在 [ensureLoaded] 时整份丢弃，避免旧硬译
         * 永远命中、新策略只对新句子生效。
         */
        const val CACHE_VERSION = 2
        /** 用户手工改过的译文：永久跳过术语指纹校验。 */
        const val MANUAL_FINGERPRINT = "manual"
        /** 单句最多保留多少页来源（超出丢最久以前的，防止句子表无限膨胀）。 */
        const val MAX_ORIGINS = 12
        /** 来源列表里「这一页首次翻译」的标记。 */
        const val MATCH_KIND_NEW = "NEW"

        /**
         * 术语指纹是否还新鲜（纯函数，便于单测）。
         *
         * - [current] 为 null：调用方不关心术语（老入口/测试）→ 永远新鲜；
         * - 存的是 `manual`：用户手工改过 → 永远新鲜；
         * - 其他：必须与当前指纹完全一致。
         */
        internal fun isGlossaryFingerprintFresh(stored: String, current: String?): Boolean {
            if (current == null) return true
            if (stored == MANUAL_FINGERPRINT) return true
            return stored == current
        }

        private const val TAG = "SentenceCache"
        private const val MIN_FUZZY_COMPACT_LEN = 8
        private const val MIN_COMMON_PREFIX = 6
        private const val FUZZY_TOLERANCE_LONG_LEN = 14

        /** 命中统计的落盘间隔（毫秒）：翻译热路径上不能每次命中都重写整份 JSON。 */
        private const val STATS_PERSIST_INTERVAL_MS = 30_000L
    }
}
