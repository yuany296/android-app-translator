package com.manga.translator.store

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一次翻译发生在什么页面（2026-11-02「翻译历史尽量详细」）。
 *
 * 句子表只记「这句原文 → 译文」，同一句在多页出现时只能保留一份来源；
 * 这里把**每一页的翻译现场**单独记下来，历史页的按页视图就有 URL、标题、页码、
 * 引擎、耗时统计等完整信息，句子表本身不用膨胀。
 */
enum class TranslationPageKind(
    val id: String,
    val label: String
) {
    WEBTOON("webtoon", "网页漫画"),
    WEB_SCREENSHOT("web_screenshot", "网页截图"),
    PIXIV("pixiv", "Pixiv 阅读器"),
    WEBPAGE_TEXT("webpage_text", "网页文字"),
    SCREENSHOT("screenshot", "截图翻译"),
    UNKNOWN("unknown", "未知来源");

    companion object {
        fun fromId(value: String?): TranslationPageKind {
            val id = value?.trim().orEmpty()
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

/**
 * 调用方在翻译前提供的页面现场。
 *
 * [runId] 用来区分「同一页的一次运行」：网页文字一次会话会分多批，批与批之间
 * 用同一个 runId，统计会累加；网页漫画每张图/每次重试是独立 run，统计会覆盖。
 */
data class TranslationOriginContext(
    val kind: TranslationPageKind = TranslationPageKind.UNKNOWN,
    /** 展示用来源：站点 host 或「截图翻译」。 */
    val label: String = "",
    /** 完整 URL（网页链路）；截图链路为空。 */
    val url: String = "",
    /** 页面标题；拿不到时为空。 */
    val title: String = "",
    /** 0-based 图片序号；-1 = 未知。 */
    val pageIndex: Int = -1,
    /** 本次页面里的图片总数；-1 = 未知。 */
    val pageTotal: Int = -1,
    /** 一次运行的标识；同一页分多批时由调用方传同一个值。 */
    val runId: String = "",
    /**
     * 同一 runId 的多次调用是否累加句子统计。
     *
     * - 网页文字：一次会话分多批，每批是页面的不同片段 → true（累加）；
     * - 网页漫画/截图：一次页面可能因条带太短重跑，重跑是同一页的替代结果 → false（覆盖）。
     */
    val accumulateStats: Boolean = false
)

/** 一次页面翻译的句子统计（只记最近一次 run；同一 run 的多批会累加）。 */
data class TranslationPageRunStats(
    val sentenceCount: Int = 0,
    val newCount: Int = 0,
    val cacheHitCount: Int = 0,
    /** 缓存命中的三种匹配方式（2026-11-02：历史详情里分开显示）。 */
    val exactCount: Int = 0,
    val compactCount: Int = 0,
    val fuzzyCount: Int = 0,
    val skippedCount: Int = 0,
    val glossaryStaleCount: Int = 0
)

/**
 * 按 `pageHash` 保存的页面级历史。
 *
 * 与 [PageSnapshot] 的区别：快照只在用户打开「保存页面缩略图」时才有图；
 * 这里是**始终记录**的轻量文本元数据，用来回答「这一页是什么、什么时候翻的、
 * 用了什么引擎、多少句是新翻/复用」。
 */
data class TranslationPageInfo(
    val pageHash: String,
    val kind: TranslationPageKind = TranslationPageKind.UNKNOWN,
    val label: String = "",
    val url: String = "",
    val title: String = "",
    val pageIndex: Int = -1,
    val pageTotal: Int = -1,
    val sourceLanguage: String = "",
    val targetLanguage: String = "",
    val ocrEngine: String = "",
    val translateEngine: String = "",
    val translateProvider: String = "",
    val translateModel: String = "",
    val createdAtMs: Long = 0L,
    val lastTranslatedAtMs: Long = 0L,
    val translateCount: Int = 0,
    val lastRunId: String = "",
    val sentenceCount: Int = 0,
    val newCount: Int = 0,
    val cacheHitCount: Int = 0,
    val exactCount: Int = 0,
    val compactCount: Int = 0,
    val fuzzyCount: Int = 0,
    val skippedCount: Int = 0,
    val glossaryStaleCount: Int = 0
) {
    /** 详情弹层/卡片头用的标题：页面标题 → URL → 来源标签 → 页标识。 */
    fun displayTitle(): String {
        return title.trim().ifBlank {
            url.trim().ifBlank {
                label.trim().ifBlank { "页面 ${pageHash.take(8)}" }
            }
        }
    }
}

class TranslationPageRepository(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()
    private var cache: List<TranslationPageInfo>? = null

    fun all(): List<TranslationPageInfo> = synchronized(lock) {
        loadAll().sortedByDescending { it.lastTranslatedAtMs }
    }

    fun find(pageHash: String): TranslationPageInfo? = synchronized(lock) {
        val key = pageHash.trim()
        if (key.isEmpty()) return null
        loadAll().firstOrNull { it.pageHash == key }
    }

    /**
     * 记录一次翻译现场。返回落库后的条目；`pageHash` 为空时返回 null。
     *
     * 写入在小文件上同步完成（通常 <2ms），调用方在 IO 线程执行；
     * 这样翻译返回时历史详情已经可读，不会出现「先看到句子、过一会儿才有页面详情」。
     */
    fun record(
        pageHash: String,
        context: TranslationOriginContext,
        stats: TranslationPageRunStats,
        sourceLanguage: String,
        targetLanguage: String,
        ocrEngine: String,
        translateEngine: String,
        translateProvider: String,
        translateModel: String,
        now: Long = System.currentTimeMillis()
    ): TranslationPageInfo? {
        val key = pageHash.trim()
        if (key.isEmpty()) return null
        synchronized(lock) {
            val entries = loadAll()
            val existing = entries.firstOrNull { it.pageHash == key }
            val merged = mergePageInfo(
                existing = existing,
                pageHash = key,
                context = context,
                stats = stats,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                ocrEngine = ocrEngine,
                translateEngine = translateEngine,
                translateProvider = translateProvider,
                translateModel = translateModel,
                now = now
            )
            val next = entries.filterNot { it.pageHash == key } + merged
            write(pruneToLimit(next, MAX_ENTRIES))
            Log.d(
                TAG,
                "page_record page=$key kind=${merged.kind.id} " +
                    "count=${merged.sentenceCount} new=${merged.newCount} " +
                    "cache=${merged.cacheHitCount} skipped=${merged.skippedCount} " +
                    "run=${context.runId.take(32)}"
            )
            return merged
        }
    }

    fun count(): Int = synchronized(lock) { loadAll().size }

    fun clear() {
        synchronized(lock) {
            cache = emptyList()
            file.delete()
        }
    }

    private fun loadAll(): List<TranslationPageInfo> {
        cache?.let { return it }
        val parsed = if (!file.exists()) {
            emptyList()
        } else {
            runCatching { parseEntriesText(file.readText()) }.getOrElse {
                Log.w(TAG, "page_load_fail reason=${it.message?.take(80)}")
                emptyList()
            }
        }
        cache = parsed
        return parsed
    }

    private fun write(entries: List<TranslationPageInfo>) {
        cache = entries
        file.parentFile?.mkdirs()
        file.writeText(toStoreJson(entries))
    }

    companion object {
        private const val TAG = "TranslationHistory"
        const val FILE_NAME = "mt_translation_pages.json"

        /** 页面级元数据上限：远大于句子表 5000 句对应的页数，文件通常 <1MB。 */
        const val MAX_ENTRIES = 1000

        /**
         * 纯合并逻辑（便于单测）：
         * - `runId` 相同 → 同一页的多批翻译，句子统计累加；
         * - `runId` 不同/为空 → 新的一次翻译，统计覆盖，`translateCount` +1；
         * - 首次时间、上下文里的非空字段永远保留最新可用值。
         */
        internal fun mergePageInfo(
            existing: TranslationPageInfo?,
            pageHash: String,
            context: TranslationOriginContext,
            stats: TranslationPageRunStats,
            sourceLanguage: String,
            targetLanguage: String,
            ocrEngine: String,
            translateEngine: String,
            translateProvider: String,
            translateModel: String,
            now: Long
        ): TranslationPageInfo {
            // 同一次逻辑运行：重跑（条带加高）或网页文字多批。
            val sameLogicalRun = existing != null &&
                context.runId.isNotBlank() &&
                existing.lastRunId == context.runId
            // 只有明确声明「这批是页面的不同片段」时才累加；重跑必须覆盖，否则
            // 一张图重跑两次会把句数/新翻数记成两倍。
            val accumulate = sameLogicalRun && context.accumulateStats
            val sentenceCount = if (accumulate) {
                existing!!.sentenceCount + stats.sentenceCount
            } else {
                stats.sentenceCount
            }
            val newCount = if (accumulate) {
                existing!!.newCount + stats.newCount
            } else {
                stats.newCount
            }
            val cacheHitCount = if (accumulate) {
                existing!!.cacheHitCount + stats.cacheHitCount
            } else {
                stats.cacheHitCount
            }
            val exactCount = if (accumulate) {
                existing!!.exactCount + stats.exactCount
            } else {
                stats.exactCount
            }
            val compactCount = if (accumulate) {
                existing!!.compactCount + stats.compactCount
            } else {
                stats.compactCount
            }
            val fuzzyCount = if (accumulate) {
                existing!!.fuzzyCount + stats.fuzzyCount
            } else {
                stats.fuzzyCount
            }
            val skippedCount = if (accumulate) {
                existing!!.skippedCount + stats.skippedCount
            } else {
                stats.skippedCount
            }
            val glossaryStaleCount = if (accumulate) {
                existing!!.glossaryStaleCount + stats.glossaryStaleCount
            } else {
                stats.glossaryStaleCount
            }
            return TranslationPageInfo(
                pageHash = pageHash,
                kind = if (context.kind != TranslationPageKind.UNKNOWN) {
                    context.kind
                } else {
                    existing?.kind ?: TranslationPageKind.UNKNOWN
                },
                label = context.label.trim().ifBlank { existing?.label.orEmpty() },
                url = context.url.trim().ifBlank { existing?.url.orEmpty() },
                title = context.title.trim().ifBlank { existing?.title.orEmpty() },
                pageIndex = if (context.pageIndex >= 0) context.pageIndex else existing?.pageIndex ?: -1,
                pageTotal = if (context.pageTotal > 0) context.pageTotal else existing?.pageTotal ?: -1,
                sourceLanguage = sourceLanguage.trim().ifBlank { existing?.sourceLanguage.orEmpty() },
                targetLanguage = targetLanguage.trim().ifBlank { existing?.targetLanguage.orEmpty() },
                ocrEngine = ocrEngine.trim().ifBlank { existing?.ocrEngine.orEmpty() },
                translateEngine = translateEngine.trim().ifBlank { existing?.translateEngine.orEmpty() },
                translateProvider = translateProvider.trim().ifBlank { existing?.translateProvider.orEmpty() },
                translateModel = translateModel.trim().ifBlank { existing?.translateModel.orEmpty() },
                createdAtMs = existing?.createdAtMs?.takeIf { it > 0L } ?: now,
                lastTranslatedAtMs = now,
                translateCount = if (sameLogicalRun) {
                    existing!!.translateCount
                } else {
                    (existing?.translateCount ?: 0) + 1
                },
                lastRunId = context.runId.trim().ifBlank { existing?.lastRunId.orEmpty() },
                sentenceCount = sentenceCount,
                newCount = newCount,
                cacheHitCount = cacheHitCount,
                exactCount = exactCount,
                compactCount = compactCount,
                fuzzyCount = fuzzyCount,
                skippedCount = skippedCount,
                glossaryStaleCount = glossaryStaleCount
            )
        }

        internal fun parseEntriesText(text: String): List<TranslationPageInfo> {
            if (text.isBlank()) return emptyList()
            return runCatching {
                val arr = JSONObject(text).optJSONArray("entries") ?: JSONArray()
                val entries = mutableListOf<TranslationPageInfo>()
                for (index in 0 until arr.length()) {
                    val item = arr.optJSONObject(index) ?: continue
                    val hash = item.optString("pageHash", "").trim()
                    if (hash.isEmpty()) continue
                    entries += TranslationPageInfo(
                        pageHash = hash,
                        kind = TranslationPageKind.fromId(item.optString("kind", "")),
                        label = item.optString("label", ""),
                        url = item.optString("url", ""),
                        title = item.optString("title", ""),
                        pageIndex = item.optInt("pageIndex", -1),
                        pageTotal = item.optInt("pageTotal", -1),
                        sourceLanguage = item.optString("sourceLanguage", ""),
                        targetLanguage = item.optString("targetLanguage", ""),
                        ocrEngine = item.optString("ocrEngine", ""),
                        translateEngine = item.optString("translateEngine", ""),
                        translateProvider = item.optString("translateProvider", ""),
                        translateModel = item.optString("translateModel", ""),
                        createdAtMs = item.optLong("createdAtMs", 0L),
                        lastTranslatedAtMs = item.optLong("lastTranslatedAtMs", 0L),
                        translateCount = item.optInt("translateCount", 0),
                        lastRunId = item.optString("lastRunId", ""),
                        sentenceCount = item.optInt("sentenceCount", 0),
                        newCount = item.optInt("newCount", 0),
                        cacheHitCount = item.optInt("cacheHitCount", 0),
                        exactCount = item.optInt("exactCount", 0),
                        compactCount = item.optInt("compactCount", 0),
                        fuzzyCount = item.optInt("fuzzyCount", 0),
                        skippedCount = item.optInt("skippedCount", 0),
                        glossaryStaleCount = item.optInt("glossaryStaleCount", 0)
                    )
                }
                entries
            }.getOrElse { emptyList() }
        }

        internal fun toStoreJson(entries: List<TranslationPageInfo>): String {
            val arr = JSONArray()
            entries.forEach { entry ->
                arr.put(
                    JSONObject()
                        .put("pageHash", entry.pageHash)
                        .put("kind", entry.kind.id)
                        .put("label", entry.label)
                        .put("url", entry.url)
                        .put("title", entry.title)
                        .put("pageIndex", entry.pageIndex)
                        .put("pageTotal", entry.pageTotal)
                        .put("sourceLanguage", entry.sourceLanguage)
                        .put("targetLanguage", entry.targetLanguage)
                        .put("ocrEngine", entry.ocrEngine)
                        .put("translateEngine", entry.translateEngine)
                        .put("translateProvider", entry.translateProvider)
                        .put("translateModel", entry.translateModel)
                        .put("createdAtMs", entry.createdAtMs)
                        .put("lastTranslatedAtMs", entry.lastTranslatedAtMs)
                        .put("translateCount", entry.translateCount)
                        .put("lastRunId", entry.lastRunId)
                        .put("sentenceCount", entry.sentenceCount)
                        .put("newCount", entry.newCount)
                        .put("cacheHitCount", entry.cacheHitCount)
                        .put("exactCount", entry.exactCount)
                        .put("compactCount", entry.compactCount)
                        .put("fuzzyCount", entry.fuzzyCount)
                        .put("skippedCount", entry.skippedCount)
                        .put("glossaryStaleCount", entry.glossaryStaleCount)
                )
            }
            return JSONObject().put("entries", arr).toString()
        }

        internal fun pruneToLimit(
            entries: List<TranslationPageInfo>,
            max: Int
        ): List<TranslationPageInfo> {
            if (entries.size <= max) return entries
            return entries.sortedByDescending { it.lastTranslatedAtMs }.take(max)
        }
    }
}
