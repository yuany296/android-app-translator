package com.manga.translator.app

import android.util.Base64
import com.manga.translator.store.AppSettings
import com.manga.translator.store.GlossaryEntry
import com.manga.translator.store.TranslationHistoryEntry
import com.manga.translator.translate.Bubble
import com.manga.translator.translate.NonTranslatableText
import com.manga.translator.translate.TranslationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 句子级翻译解析器（2026-09-28 自 [TranslateOrchestrator] 抽出，逻辑不变）。
 *
 * 职责：缓存查找（精确/压缩/模糊）→ 引擎分发（ML Kit 本地 / 百度 / 云端 OpenAI 兼容）→
 * 缓存回写 → 翻译历史异步写入。悬浮球链路与内嵌浏览器链路共用，保证两种入口
 * 的译文、缓存、术语表行为完全一致。
 */
class PageTranslationResolver(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun resolveTranslations(
        bubbles: List<Bubble>,
        settings: AppSettings,
        translateKey: String,
        pageHash: String,
        glossaryEntries: List<GlossaryEntry>,
        timer: TranslationTimer,
        onTranslatorDownloading: ((Boolean) -> Unit)? = null
    ): TranslationOutcome {
        val sentenceRepo = ServiceLocator.sentenceTranslationRepository
        val (translatedByOriginal, missing, cacheStats) = withContext(ioDispatcher) {
            val map = hashMapOf<String, String>()
            val miss = mutableListOf<Bubble>()
            var exactHits = 0
            var compactHits = 0
            var fuzzyHits = 0
            var skipped = 0
            bubbles.forEach { bubble ->
                val original = bubble.originalText
                if (NonTranslatableText.isNonTranslatable(original)) {
                    // 语言中立内容（纯数字/时间/URL/符号等）不调用翻译引擎，原样展示且不入缓存。
                    map[original] = original
                    skipped += 1
                    return@forEach
                }
                val lookup = sentenceRepo.lookup(settings.sourceLanguage, settings.targetLanguage, original)
                when (lookup.matchKind) {
                    com.manga.translator.store.SentenceCacheMatchKind.EXACT -> {
                        map[original] = lookup.translatedText!!
                        exactHits += 1
                    }

                    com.manga.translator.store.SentenceCacheMatchKind.COMPACT -> {
                        map[original] = lookup.translatedText!!
                        compactHits += 1
                    }

                    com.manga.translator.store.SentenceCacheMatchKind.FUZZY -> {
                        map[original] = lookup.translatedText!!
                        fuzzyHits += 1
                    }

                    null -> miss += bubble
                }
            }
            Triple(
                map,
                miss,
                TranslationCacheStats(exactHits, compactHits, fuzzyHits, skipped, bubbles.size)
            )
        }
        timer.mark(
            "sentence_cache",
            "exact=${cacheStats.exact} compact=${cacheStats.compact} fuzzy=${cacheStats.fuzzy} " +
                "skipped=${cacheStats.skipped} missing=${missing.size} total=${cacheStats.total}"
        )
        if (missing.isEmpty()) {
            timer.mark("translate_response", "sentence_cache_hit=true cached=${translatedByOriginal.size}")
            return TranslationOutcome(
                result = TranslationResult(
                    bubbles.map { bubble ->
                        bubble.copy(translatedText = translatedByOriginal[bubble.originalText] ?: bubble.originalText)
                    }
                ),
                warningMessage = null,
                cacheStats = cacheStats
            )
        }

        val fresh = runCatching {
            timer.mark("request_send", "stage=translate missing=${missing.size} total=${bubbles.size}")
            val (translated, fallbackHint) = withContext(ioDispatcher) {
                translateMissing(
                    settings = settings,
                    texts = missing.map { it.originalText.replace(Regex("\\s+"), " ").trim() },
                    glossaryEntries = glossaryEntries,
                    timer = timer,
                    onTranslatorDownloading = onTranslatorDownloading
                )
            }
            timer.mark("response_ok", "stage=translate count=${translated.size}")
            timer.mark("translate_response", "count=${translated.size}")
            val result = withContext(ioDispatcher) {
                missing.forEachIndexed { index, bubble ->
                    // 译文归一化空白（\n/多余空格→单空格），避免 LLM 返回的换行
                    // 残留到渲染显示（如「超越……留下深刻印象\n以至于」）。
                    val value = translated.getOrElse(index) { bubble.originalText }
                        .ifBlank { bubble.originalText }
                        .replace(Regex("\\s+"), " ").trim()
                    translatedByOriginal[bubble.originalText] = value
                    sentenceRepo.put(settings.sourceLanguage, settings.targetLanguage, bubble.originalText, value)
                }
                TranslationResult(
                    bubbles.map { bubble ->
                        bubble.copy(translatedText = translatedByOriginal[bubble.originalText] ?: bubble.originalText)
                    }
                )
            }
            // 历史记录仅用于展示：异步写入并隔离失败，绝不能影响翻译结果或缓存读取。
            val historySnapshot = result
            ServiceLocator.appScope.launch {
                runCatching {
                    val now = System.currentTimeMillis()
                    ServiceLocator.translationHistoryRepository.add(
                        TranslationHistoryEntry(
                            id = "h${now}_${translateKey.take(8)}",
                            timestampMs = now,
                            pageHash = pageHash,
                            sourceLanguage = settings.sourceLanguage,
                            targetLanguage = settings.targetLanguage,
                            translateFingerprint = translateKey,
                            bubblesJson = serializeResult(historySnapshot)
                        )
                    )
                }
            }
            TranslationOutcome(result = result, warningMessage = fallbackHint, cacheStats = cacheStats)
        }.getOrElse { err ->
            timer.mark("response_fail", "stage=translate reason=${err.message?.take(80)}")
            timer.mark("translate_response", "failed=${err.message?.take(80)}")
            TranslationOutcome(
                result = TranslationResult(
                    bubbles.map { bubble ->
                        bubble.copy(translatedText = bubble.originalText)
                    }
                ),
                warningMessage = "翻译失败，已显示原文",
                cacheStats = cacheStats
            )
        }
        return fresh
    }

    private suspend fun translateMissing(
        settings: AppSettings,
        texts: List<String>,
        glossaryEntries: List<GlossaryEntry>,
        timer: TranslationTimer,
        onTranslatorDownloading: ((Boolean) -> Unit)? = null
    ): Pair<List<String>, String?> {
        // 本地引擎优先：离线、无网络往返，显著提速。
        // 2026-08-27 用户要求：选择 ML Kit 引擎时失败**不回退云端**，直接显示原文并提示原因。
        val engine = com.manga.translator.store.TranslateEngine.fromId(settings.translateEngine)
        if (engine == com.manga.translator.store.TranslateEngine.ML_KIT) {
            val local = ServiceLocator.localTranslator
            if (local.isSupported(settings.sourceLanguage, settings.targetLanguage)) {
                // 通知 UI：正在下载/加载本地翻译模型（悬浮球显示"正在下载翻译模型"）。
                // 回调会触发悬浮球动画（View.animate 等），必须回到主线程，否则抛
                // "Animators may only be run on Looper threads" 导致整个翻译失败显示原文。
                withContext(Dispatchers.Main) { onTranslatorDownloading?.invoke(true) }
                val outcome = runCatching {
                    local.translate(texts, settings.sourceLanguage, settings.targetLanguage)
                }
                withContext(Dispatchers.Main) { onTranslatorDownloading?.invoke(false) }
                outcome.onSuccess { result ->
                    if (result.size == texts.size) {
                        timer.mark("translate_local", "ok=mlkit count=${result.size} engine=${engine.label}")
                        // 部分失败时失败句为原文，直接返回（缓存层有"译文==原文"保护）。
                        val failedCount = result.zip(texts).count { (t, s) -> t == s && s.isNotBlank() }
                        return result to if (failedCount > 0) {
                            "本地翻译部分句子未生效（$failedCount/${texts.size} 句已显示原文）"
                        } else {
                            null
                        }
                    }
                    timer.mark("translate_local", "ok=mlkit size_mismatch count=${result.size}")
                    return result to "本地翻译返回数量不符，已显示原文"
                }.onFailure { err ->
                    timer.mark("translate_local_fallback", "reason=${err.message?.take(80)}")
                    return texts to "本地翻译不可用（${err.message?.takeIf { it.isNotBlank() } ?: "无 GMS 或模型下载失败"}），已显示原文"
                }
            } else {
                timer.mark("translate_local_skip", "reason=unsupported_pair ${settings.sourceLanguage}->${settings.targetLanguage}")
                return texts to "本地翻译不支持该语言对（${settings.sourceLanguage}→${settings.targetLanguage}），已显示原文"
            }
        }

        // 百度翻译引擎：专用接口，整页多句一次请求（<1s），标准版每月免费 500 万字符。
        // 2026-08-27 新增：满足"免费 + 快"诉求；失败直接显示原文并提示，不回退云端。
        if (engine == com.manga.translator.store.TranslateEngine.BAIDU) {
            val appId = settings.baiduTranslateAppId.trim()
            val secretKey = settings.baiduTranslateSecretKey.trim()
            if (appId.isBlank() || secretKey.isBlank()) {
                timer.mark("translate_response", "skipped=missing_baidu_credentials")
                return texts to "百度翻译未配置 APP ID/密钥，请在翻译设置页填写"
            }
            timer.mark("request_send", "stage=translate mode=baidu count=${texts.size}")
            val translated = runCatching {
                ServiceLocator.baiduTranslateClient.translate(
                    appId = appId,
                    secretKey = secretKey,
                    texts = texts,
                    sourceLanguageId = settings.sourceLanguage,
                    targetLanguageId = settings.targetLanguage
                )
            }.getOrElse { err ->
                timer.mark("response_fail", "stage=translate mode=baidu reason=${err.message?.take(80)}")
                return texts to "百度翻译失败（${err.message?.takeIf { it.isNotBlank() } ?: "网络错误"}），已显示原文"
            }
            timer.mark("response_ok", "stage=translate mode=baidu count=${translated.size}")
            return translated to null
        }

        // 云端引擎：走 OpenAI 兼容 API；未配置云端 API 时直接回显原文。
        if (settings.translateBaseUrl.isBlank() || settings.translateApiKey.isBlank()) {
            timer.mark("translate_response", "skipped=missing_translate_api")
            return texts to null
        }
        timer.mark(
            "request_send",
            "stage=translate mode=cloud count=${texts.size} " +
                "provider=${settings.translateProvider} thinking=${settings.enableThinking}"
        )
        val cloud = ServiceLocator.visionClient.translateTexts(
            provider = settings.translateProvider,
            baseUrl = settings.translateBaseUrl,
            model = settings.translateModel,
            apiKey = settings.translateApiKey,
            texts = texts,
            targetLanguageId = settings.targetLanguage,
            enableThinking = settings.enableThinking,
            thinkingBudget = settings.thinkingBudget,
            glossary = glossaryEntries.map { it.source to it.target }
        )
        return cloud to null
    }

    fun buildTranslateKey(
        texts: List<String>,
        settings: AppSettings,
        glossaryRevision: Int
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val normalizedTexts = texts
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .sorted()
        val source = listOf(
            "translate_v2",
            normalizedTexts.joinToString("\u0001"),
            settings.targetLanguage,
            settings.translateProvider,
            settings.translateBaseUrl,
            settings.translateModel,
            settings.translateApiKey,
            settings.enableThinking.toString(),
            settings.thinkingBudget.toString(),
            glossaryRevision.toString()
        ).joinToString("|")
        val bytes = digest.digest(source.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun serializeResult(result: TranslationResult): String {
        val array = JSONArray()
        result.bubbles.forEach { bubble ->
            array.put(
                JSONObject()
                    .put("x", bubble.x)
                    .put("y", bubble.y)
                    .put("w", bubble.w)
                    .put("h", bubble.h)
                    .put("contentX", bubble.contentX)
                    .put("contentY", bubble.contentY)
                    .put("contentW", bubble.contentW)
                    .put("contentH", bubble.contentH)
                    .put("bgType", bubble.bgType)
                    .put("originalText", bubble.originalText)
                    .put("translatedText", bubble.translatedText)
                    .put("layout", bubble.layout)
            )
        }
        return JSONObject().put("bubbles", array).toString()
    }
}

data class TranslationOutcome(
    val result: TranslationResult,
    val warningMessage: String?,
    val cacheStats: TranslationCacheStats = TranslationCacheStats(0, 0, 0, 0, 0)
)

data class TranslationCacheStats(
    val exact: Int,
    val compact: Int,
    val fuzzy: Int,
    val skipped: Int,
    val total: Int
) {
    val hitCount: Int get() = exact + compact + fuzzy
}
