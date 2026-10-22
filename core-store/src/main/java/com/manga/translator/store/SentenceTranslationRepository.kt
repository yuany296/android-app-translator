package com.manga.translator.store

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class SentenceTranslation(
    val sourceLanguage: String,
    val targetLanguage: String,
    val originalText: String,
    val translatedText: String
)

enum class SentenceCacheMatchKind { EXACT, COMPACT, FUZZY }

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

data class SentenceCacheLookup(
    val translatedText: String?,
    val matchKind: SentenceCacheMatchKind?
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
    private val compactIndex = hashMapOf<String, MutableList<String>>()

    fun lookup(sourceLanguage: String, targetLanguage: String, originalText: String): SentenceCacheLookup {
        val normalized = normalize(originalText)
        if (normalized.isEmpty()) return SentenceCacheLookup.MISS
        val langKey = "${sourceLanguage.trim()}|${targetLanguage.trim()}"
        synchronized(lock) {
            ensureLoaded()
            val exactKey = "$langKey|$normalized"
            index[exactKey]?.let { entry ->
                if (isPoisoned(entry, normalized)) {
                    Log.d(TAG, "lookup poisoned_skip lang=$langKey kind=exact text=${truncate(originalText)}")
                    return SentenceCacheLookup.MISS
                }
                return SentenceCacheLookup(entry.translatedText, SentenceCacheMatchKind.EXACT)
            }

            val compact = compactKey(normalized)
            if (compact.isNotEmpty()) {
                ensureCompactIndex()
                val compactCandidates = compactIndex[compact].orEmpty()
                if (compactCandidates.isNotEmpty()) {
                    compactCandidates.firstOrNull()?.let { rawKey ->
                        index[rawKey]?.let { entry ->
                            if (isPoisoned(entry, normalized)) {
                                Log.d(TAG, "lookup poisoned_skip lang=$langKey kind=compact text=${truncate(originalText)}")
                                return SentenceCacheLookup.MISS
                            }
                            return SentenceCacheLookup(entry.translatedText, SentenceCacheMatchKind.COMPACT)
                        }
                    }
                }

                if (compact.length >= MIN_FUZZY_COMPACT_LEN) {
                    val tolerance = fuzzyTolerance(compact.length)
                    var bestRawKey: String? = null
                    var bestDist = Int.MAX_VALUE
                    for ((candidateCompact, rawKeys) in compactIndex) {
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
                            Log.d(
                                TAG,
                                "lookup fuzzy_hit lang=$langKey dist=$bestDist " +
                                    "src=${truncate(originalText)} matched=${truncate(entry.originalText)}"
                            )
                            return SentenceCacheLookup(entry.translatedText, SentenceCacheMatchKind.FUZZY)
                        }
                    }
                }
            }
            Log.d(TAG, "lookup miss lang=$langKey text=${truncate(originalText)}")
            return SentenceCacheLookup.MISS
        }
    }

    fun put(sourceLanguage: String, targetLanguage: String, originalText: String, translatedText: String) {
        val normalized = normalize(originalText)
        val value = normalize(translatedText)
        if (normalized.isEmpty() || value.isEmpty()) return
        if (value == normalized) {
            // 译文等于原文：视为翻译未生效（本地引擎失败回退、模型原样返回等），不入缓存，
            // 否则会形成"原文当译文"的毒化条目，后续全部命中显示原文。
            Log.d(TAG, "put skip_noop lang=$sourceLanguage|$targetLanguage text=${truncate(originalText)}")
            return
        }
        synchronized(lock) {
            ensureLoaded()
            val key = keyOf(sourceLanguage, targetLanguage, normalized)
            index.remove(key)
            index[key] = SentenceTranslation(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                originalText = normalized,
                translatedText = value
            )
            compactDirty = true
            trimIfNeeded()
            persist()
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
                if (index.remove(keyOf(key.sourceLanguage, key.targetLanguage, normalized)) != null) {
                    removed += 1
                }
            }
            if (removed > 0) {
                compactDirty = true
                persist()
            }
            return removed
        }
    }

    /** 单条作废的便捷入口；返回是否删到了（false = 本来就不在缓存里）。 */
    fun remove(sourceLanguage: String, targetLanguage: String, originalText: String): Boolean {
        return remove(listOf(SentenceCacheKey(sourceLanguage, targetLanguage, originalText))) > 0
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
                index[keyOf(sourceLanguage, targetLanguage, originalText)] = SentenceTranslation(
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    originalText = originalText,
                    translatedText = translatedText
                )
            }
            compactDirty = true
        }
    }

    private fun ensureCompactIndex() {
        if (!compactDirty) return
        compactDirty = false
        compactIndex.clear()
        index.forEach { (rawKey, entry) ->
            val compact = compactKey(entry.originalText)
            if (compact.isNotEmpty()) {
                compactIndex.getOrPut(compact) { mutableListOf() }.add(rawKey)
            }
        }
    }

    private fun trimIfNeeded() {
        while (index.size > MAX_ENTRIES) {
            val firstKey = index.keys.firstOrNull() ?: break
            index.remove(firstKey)
        }
        compactDirty = true
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
            )
        }
        file.writeText(
            JSONObject()
                .put("version", CACHE_VERSION)
                .put("entries", arr)
                .toString()
        )
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
         * 句子缓存语义版本（2026-10-18）。提示词、页面上下文策略或翻译规则发生
         * 影响译文的改变时 +1：旧条目在 [ensureLoaded] 时整份丢弃，避免旧硬译
         * 永远命中、新策略只对新句子生效。
         */
        const val CACHE_VERSION = 2
        private const val TAG = "SentenceCache"
        private const val MIN_FUZZY_COMPACT_LEN = 8
        private const val MIN_COMMON_PREFIX = 6
        private const val FUZZY_TOLERANCE_LONG_LEN = 14
    }
}
