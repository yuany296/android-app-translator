package com.manga.translator.app

import com.manga.translator.store.SentenceSource
import com.manga.translator.store.SentenceTranslation
import com.manga.translator.store.originsOrLegacy
import com.manga.translator.store.sourceKind
import com.manga.translator.store.sourceKindOfPageHash

/**
 * 翻译历史「按页」的聚合（2026-11-02 抽出，纯逻辑可单测）。
 *
 * 旧实现直接按 `originLabel to originPageHash` 分组 —— 一句原文只有一份来源，
 * 同一句在多页出现时后一页会把前一页顶掉。现在按每句的 `origins` 展开：
 * 一句在多页出现就会同时出现在多张页卡片里，每张卡片保留**本页**的首次/最近时间
 * 和命中次数。
 */
internal fun sentenceHistoryKey(entry: SentenceTranslation): String =
    "${entry.sourceLanguage}|${entry.targetLanguage}|${entry.originalText}"

internal fun groupKey(label: String, pageHash: String): String = "$label\u0001$pageHash"

/**
 * 这句是否在某一类来源里出现过（2026-11-02）。
 *
 * 不能只看主来源：同一句可能先在网页文字里翻过、后来又在漫画页命中缓存；
 * 只看 `originPageHash` 会让「网页文字」筛选漏掉它，但按页视图里却能找到，
 * 两个视图自相矛盾。
 */
internal fun SentenceTranslation.matchesSourceKind(kind: SentenceSource): Boolean {
    val origins = originsOrLegacy()
    if (origins.isEmpty()) return sourceKind() == kind
    return origins.any { sourceKindOfPageHash(it.pageHash) == kind }
}

internal data class SentencePageOccurrence(
    val label: String,
    val pageHash: String,
    val entry: SentenceTranslation,
    val firstSeenAtMs: Long,
    val lastSeenAtMs: Long,
    val hitCount: Int,
    val lastMatchKind: String = ""
)

internal data class SentencePageGroup(
    val label: String,
    val pageHash: String,
    val occurrences: List<SentencePageOccurrence>
) {
    val sentenceKeys: Set<String> get() = occurrences.map { sentenceHistoryKey(it.entry) }.toSet()
    val sentenceCount: Int get() = sentenceKeys.size
    val hitCount: Int get() = occurrences.sumOf { it.hitCount }
    val firstSeenAtMs: Long get() = occurrences.minOfOrNull { it.firstSeenAtMs } ?: 0L
    val lastSeenAtMs: Long get() = occurrences.maxOfOrNull { it.lastSeenAtMs } ?: 0L
    val key: String get() = groupKey(label, pageHash)
}

internal fun buildSentencePageGroups(
    entries: List<SentenceTranslation>
): List<SentencePageGroup> {
    val occurrences = ArrayList<SentencePageOccurrence>(entries.size)
    entries.forEach { entry ->
        val origins = entry.originsOrLegacy()
        if (origins.isEmpty()) {
            occurrences += SentencePageOccurrence(
                label = entry.originLabel.trim(),
                pageHash = entry.originPageHash.trim(),
                entry = entry,
                firstSeenAtMs = entry.createdAtMs,
                lastSeenAtMs = maxOf(entry.lastHitAtMs, entry.createdAtMs),
                hitCount = entry.hitCount
            )
        } else {
            origins.forEach { origin ->
                occurrences += SentencePageOccurrence(
                    label = origin.label.trim().ifBlank { entry.originLabel.trim() },
                    pageHash = origin.pageHash.trim(),
                    entry = entry,
                    firstSeenAtMs = origin.firstSeenAtMs,
                    lastSeenAtMs = origin.lastSeenAtMs,
                    hitCount = origin.hitCount,
                    lastMatchKind = origin.lastMatchKind
                )
            }
        }
    }
    return occurrences
        .groupBy { it.label to it.pageHash }
        .map { (key, list) ->
            SentencePageGroup(
                label = key.first,
                pageHash = key.second,
                // 同一句在同一页只应有一条来源；旧数据异常重复时按句子去重，避免重复行。
                occurrences = list.distinctBy { sentenceHistoryKey(it.entry) }
            )
        }
        .sortedWith(
            compareByDescending<SentencePageGroup> { it.lastSeenAtMs }
                .thenByDescending { it.sentenceCount }
        )
}
