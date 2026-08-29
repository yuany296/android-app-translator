package com.manga.translator.translate

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于 ML Kit 端侧翻译（Google Play 服务）的本地翻译器。
 * - 覆盖漫画常见语言对：英文/日文/韩文 -> 简体中文，以及这些语言之间的互译（ML Kit 支持的语言）。
 * - 繁体中文目标（zh-tw）本版本 ML Kit 端侧不支持，调用方会回退云端。
 * - 首次使用某语言对时联网下载模型并缓存，之后完全离线翻译。
 * - 需要设备安装 Google Play 服务；模型下载失败时抛出异常由调用方回退云端。
 */
class MlKitLocalTranslator : LocalTranslator {

    // 语言对 -> Translator，避免每次重新创建 / 重复下载。
    private val translatorCache = HashMap<String, Translator>()

    // 已成功下载模型的语言对，用于 isModelReady 快速判定。
    private val readyPairs = HashSet<String>()
    private val readyLock = Any()

    // 语言对 -> 下载互斥锁，同一语言对只允许一个下载任务，避免并发重复下载。
    private val downloadLocks = HashMap<String, Any>()
    private val downloadLocksLock = Any()

    override fun isSupported(sourceLanguageId: String, targetLanguageId: String): Boolean {
        if (sourceLanguageId.equals(targetLanguageId, ignoreCase = true)) return false
        val src = toMlKitLanguage(sourceLanguageId) ?: return false
        val dst = toMlKitLanguage(targetLanguageId) ?: return false
        return src != dst
    }

    override fun isModelReady(sourceLanguageId: String, targetLanguageId: String): Boolean {
        val src = toMlKitLanguage(sourceLanguageId) ?: return false
        val dst = toMlKitLanguage(targetLanguageId) ?: return false
        val key = pairKey(src, dst)
        synchronized(readyLock) { return readyPairs.contains(key) }
    }

    override suspend fun isDownloaded(sourceLanguageId: String, targetLanguageId: String): Boolean {
        val src = toMlKitLanguage(sourceLanguageId) ?: return false
        val dst = toMlKitLanguage(targetLanguageId) ?: return false
        if (isModelReady(src, dst)) return true
        return withContext(Dispatchers.IO) {
            try {
                // 已缓存的模型会立即返回，无需真实下载；短超时即可区分"已缓存"与"需要下载"。
                val translator = getOrCreateTranslator(src, dst)
                val conditions = DownloadConditions.Builder().build()
                Tasks.await(translator.downloadModelIfNeeded(conditions), PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                markReady(src, dst)
                Log.d(TAG, "mlkit_translate_probe_cached pair=${pairKey(src, dst)}")
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun downloadModel(sourceLanguageId: String, targetLanguageId: String): String? {
        val src = toMlKitLanguage(sourceLanguageId)
            ?: return "本地翻译不支持源语言：$sourceLanguageId"
        val dst = toMlKitLanguage(targetLanguageId)
            ?: return "本地翻译不支持目标语言：$targetLanguageId（繁体中文请改用云端）"
        if (src == dst) return "源语言与目标语言相同，无需翻译"
        return withContext(Dispatchers.IO) {
            val translator = getOrCreateTranslator(src, dst)
            try {
                ensureModelDownloaded(translator, src, dst, source = "manual")
                null
            } catch (e: Exception) {
                Log.w(TAG, "mlkit_translate_download_failed pair=${pairKey(src, dst)} reason=${e.message?.take(160)}")
                "模型下载失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    override suspend fun translate(
        texts: List<String>,
        sourceLanguageId: String,
        targetLanguageId: String
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        val src = toMlKitLanguage(sourceLanguageId)
            ?: throw IllegalStateException("Unsupported ML Kit source language: $sourceLanguageId")
        val dst = toMlKitLanguage(targetLanguageId)
            ?: throw IllegalStateException("Unsupported ML Kit target language: $targetLanguageId")
        if (src == dst) throw IllegalStateException("Source and target language are the same")

        return withContext(Dispatchers.IO) {
            val translator = getOrCreateTranslator(src, dst)
            // 2026-08-27 优化：模型未就绪时不阻塞全量下载（旧逻辑 120s×3 会让点击后
            // 卡住 6 分钟）。先做 3s 快速探测磁盘缓存：已缓存立即用；未缓存则触发后台
            // 下载并抛异常提示，本次显示原文，下次点击时下载完成即可翻译。
            ensureModelReadyOrThrow(translator, src, dst)
            // 2026-08-27 逐句容错：单句失败（noop/可疑/超时）只保留该句原文，不拖垮整批。
            // 全部失败时抛异常，由调用方提示"本地翻译不可用"；部分失败返回混合结果，
            // 失败句为原文（缓存层有"译文==原文"保护不会毒化缓存）。
            val results = ArrayList<String>(texts.size)
            var failedCount = 0
            for (text in texts) {
                if (text.isBlank()) {
                    results += text
                    continue
                }
                val normalizedIn = text.trim().replace(Regex("\\s+"), " ")
                // 纯符号/emoji/数字/空白等不含可翻译语言字符的文本：原样返回，不做质量检查。
                if (!containsTranslatableScript(normalizedIn, sourceLanguageId)) {
                    results += normalizedIn
                    continue
                }
                try {
                    val out = Tasks.await(translator.translate(text), TRANSLATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        ?.trim()
                        ?.replace(Regex("\\s+"), " ")
                        .orEmpty()
                    if (out.isEmpty() || out == normalizedIn) {
                        // 端侧模型对部分语言对（尤其汉字/假名混合文本）会原样返回输入，
                        // 视为"翻译未生效"：保留原文，不写入缓存（缓存层有保护）。
                        Log.w(TAG, "mlkit_translate_noop text=${normalizedIn.take(60)}")
                        results += normalizedIn
                        failedCount++
                        continue
                    }
                    if (looksLikeUntranslated(normalizedIn, out, sourceLanguageId, targetLanguageId)) {
                        Log.w(TAG, "mlkit_translate_suspicious text=${normalizedIn.take(60)} out=${out.take(60)}")
                        results += normalizedIn
                        failedCount++
                        continue
                    }
                    results += out
                } catch (e: Exception) {
                    Log.w(TAG, "mlkit_translate_item_failed text=${normalizedIn.take(60)} reason=${e.message?.take(120)}")
                    results += normalizedIn
                    failedCount++
                }
            }
            if (failedCount == texts.size) {
                throw IllegalStateException("ML Kit 翻译未生效（全部 $failedCount/${texts.size} 句失败），已显示原文")
            }
            if (failedCount > 0) {
                Log.w(TAG, "mlkit_translate_partial failed=$failedCount/${texts.size}")
            }
            results
        }
    }

    /**
     * 判断文本是否包含"可翻译"的语言字符（字母/汉字/假名/韩文等）。
     * 纯 emoji、符号、数字、URL 片段等无需翻译，原样返回即可，不做 noop/可疑检查。
     */
    private fun containsTranslatableScript(text: String, sourceLanguageId: String): Boolean {
        var count = 0
        for (ch in text) {
            when (sourceLanguageId.trim().lowercase()) {
                "en", "eng", "english", "英文" -> {
                    if (ch.isLetter()) count++
                }
                "ja", "jpn", "japanese", "日文" -> {
                    if (ch in '\u3040'..'\u30ff' || ch in '\u4e00'..'\u9fff') count++
                }
                "ko", "kor", "korean", "韩文" -> {
                    if (ch in '\uac00'..'\ud7af') count++
                }
                else -> {
                    if (ch.isLetterOrDigit() || ch in '\u3040'..'\u30ff' ||
                        ch in '\u4e00'..'\u9fff' || ch in '\uac00'..'\ud7af'
                    ) count++
                }
            }
            if (count >= 2) return true
        }
        return count >= 2
    }

    override fun prewarm(sourceLanguageId: String, targetLanguageId: String) {
        if (!isSupported(sourceLanguageId, targetLanguageId)) return
        val src = toMlKitLanguage(sourceLanguageId) ?: return
        val dst = toMlKitLanguage(targetLanguageId) ?: return
        val key = pairKey(src, dst)
        if (isModelReady(src, dst)) return
        val translator = getOrCreateTranslator(src, dst)
        // 仅触发下载，不阻塞翻译流程；成功标记就绪，失败记录原因（真正翻译时仍会再尝试并回退云端）。
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                markReady(src, dst)
                Log.d(TAG, "mlkit_translate_prewarm_done pair=$key")
            }
            .addOnFailureListener { err ->
                Log.w(TAG, "mlkit_translate_prewarm_failed pair=$key reason=${err.message?.take(160)}")
            }
    }

    private fun getOrCreateTranslator(src: String, dst: String): Translator {
        val key = pairKey(src, dst)
        return translatorCache.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(src)
                .setTargetLanguage(dst)
                .build()
            Translation.getClient(options).also {
                Log.d(TAG, "mlkit_translator_created pair=$key")
            }
        }
    }

    private fun ensureModelDownloaded(translator: Translator, src: String, dst: String, source: String) {
        val key = pairKey(src, dst)
        // 同一语言对串行下载：等待前一个下载任务完成或失败，避免并发重复触发。
        val lock = pairDownloadLock(key)
        synchronized(lock) {
            if (isModelReady(src, dst)) return
            // 不强制 WiFi，允许移动数据下载；给足超时与重试，避免慢网络/VPN 下误判失败回退云端。
            val conditions = DownloadConditions.Builder().build()
            var lastError: Exception? = null
            var attempt = 0
            while (attempt < DOWNLOAD_ATTEMPTS) {
                attempt++
                try {
                    Tasks.await(
                        translator.downloadModelIfNeeded(conditions),
                        DOWNLOAD_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                    )
                    markReady(src, dst)
                    Log.d(TAG, "mlkit_translate_model_ready pair=$key source=$source attempt=$attempt")
                    return
                } catch (e: Exception) {
                    lastError = e
                    Log.w(
                        TAG,
                        "mlkit_translate_download_retry pair=$key source=$source attempt=$attempt reason=${e.message?.take(160)}"
                    )
                }
            }
            throw lastError ?: IllegalStateException("Model download failed: $key")
        }
    }

    /**
     * 翻译前的模型就绪检查（不阻塞长下载）：
     * - 进程内已就绪 → 直接返回；
     * - 磁盘已缓存（3s 探测）→ 标记就绪并返回；
     * - 未缓存 → 触发后台下载（prewarm 只触发不等待），并抛异常提示"模型下载中"，
     *   本次翻译显示原文；下载完成后下次点击即可翻译。
     */
    private fun ensureModelReadyOrThrow(translator: Translator, src: String, dst: String) {
        val key = pairKey(src, dst)
        val lock = pairDownloadLock(key)
        synchronized(lock) {
            if (isModelReady(src, dst)) return
            val conditions = DownloadConditions.Builder().build()
            val cached = runCatching {
                Tasks.await(translator.downloadModelIfNeeded(conditions), PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                true
            }.getOrDefault(false)
            if (cached) {
                markReady(src, dst)
                Log.d(TAG, "mlkit_translate_probe_cached pair=$key")
                return
            }
            // 磁盘无缓存：触发后台下载（不阻塞），提示用户稍后重试。
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    markReady(src, dst)
                    Log.d(TAG, "mlkit_translate_bg_download_done pair=$key")
                }
                .addOnFailureListener { err ->
                    Log.w(TAG, "mlkit_translate_bg_download_failed pair=$key reason=${err.message?.take(160)}")
                }
            Log.w(TAG, "mlkit_translate_model_not_ready pair=$key")
            throw IllegalStateException("翻译模型下载中（$key），请稍后重试")
        }
    }

    private fun pairDownloadLock(key: String): Any {
        return synchronized(downloadLocksLock) { downloadLocks.getOrPut(key) { Any() } }
    }

    private fun pairKey(src: String, dst: String): String = "$src->$dst"

    /**
     * 启发式检测 ML Kit 译文是否"几乎没翻译"：
     * 当目标语言为中文时，若译文中 CJK 表意文字占比过低，且源文仍保留大量源文字脚本，
     * 视为模型对该句翻译失败，保留原文（不写入缓存）。
     * 注意：调用方（translate）已先经 containsTranslatableScript 过滤纯符号/emoji 文本。
     */
    private fun looksLikeUntranslated(
        source: String,
        target: String,
        sourceLanguageId: String,
        targetLanguageId: String
    ): Boolean {
        if (target.isBlank()) return true
        // 仅对中目标语言做启发式检查；其他目标语言（如英/日/韩互译）质量由调用方评估。
        val targetIsChinese = targetLanguageId.trim().lowercase() in setOf("zh", "zh-cn", "zh-hans", "中文", "简体中文")
        if (!targetIsChinese) return false
        val sourceChars = source.filterNot { it.isWhitespace() }
        val targetChars = target.filterNot { it.isWhitespace() }
        if (sourceChars.isEmpty() || targetChars.isEmpty()) return false
        val cjkIdeographs = targetChars.count { it in '\u4e00'..'\u9fff' }
        val cjkRatio = cjkIdeographs.toFloat() / targetChars.length
        val sourceScriptCount = when (sourceLanguageId.trim().lowercase()) {
            "ja", "jpn", "japanese", "日文" -> sourceChars.count { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' }
            "ko", "kor", "korean", "韩文" -> sourceChars.count { it in '\uac00'..'\ud7af' }
            "en", "eng", "english", "英文" -> sourceChars.count { it.isLetter() }
            else -> sourceChars.count { it.isLetterOrDigit() || it in '\u3040'..'\u9fff' || it in '\uac00'..'\ud7af' }
        }
        val sourceScriptRatio = sourceScriptCount.toFloat() / sourceChars.length
        // 源文主要是目标脚本，且译文中表意汉字极少 → 疑似未翻译。
        if (sourceScriptRatio >= 0.5f && cjkRatio < 0.25f) {
            return true
        }
        // 译文与源文过短（<4 字）时，直接比较是否完全相同或仅做了极小编辑。
        if (sourceChars.length <= 4 && targetChars.length <= 4 &&
            normalizedEditDistance(sourceChars, targetChars) < 0.35f
        ) {
            return true
        }
        return false
    }

    private fun normalizedEditDistance(a: String, b: String): Float {
        val dist = levenshtein(a, b)
        return if (a.length + b.length == 0) 0f else dist.toFloat() / ((a.length + b.length) / 2f).coerceAtLeast(1f)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val costs = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = costs[0]
            costs[0] = i
            for (j in 1..b.length) {
                val temp = costs[j]
                costs[j] = if (a[i - 1] == b[j - 1]) {
                    prev
                } else {
                    minOf(costs[j - 1], costs[j], prev) + 1
                }
                prev = temp
            }
        }
        return costs[b.length]
    }

    private fun markReady(src: String, dst: String) {
        synchronized(readyLock) { readyPairs.add(pairKey(src, dst)) }
    }

    private fun toMlKitLanguage(languageId: String): String? {
        return when (languageId.trim().lowercase()) {
            "en" -> TranslateLanguage.ENGLISH
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "zh-cn", "zh", "zh-hans" -> TranslateLanguage.CHINESE
            // ML Kit 端侧翻译（本版本）仅区分 CHINESE（简体）；繁体中文需回退云端。
            "zh-tw", "zh-hant" -> null
            else -> null
        }
    }

    companion object {
        private const val TAG = "MlKitLocalTranslator"
        // 首次联网下载模型较慢（约 10~40MB），给足超时避免慢网络/VPN 下误判失败；上层 UI 会显示"正在下载翻译模型"。
        private const val DOWNLOAD_TIMEOUT_SECONDS = 120L
        private const val DOWNLOAD_ATTEMPTS = 3
        private const val TRANSLATE_TIMEOUT_SECONDS = 30L
        // 磁盘探测：模型已缓存时 downloadModelIfNeeded 立即完成，短超时足够。
        private const val PROBE_TIMEOUT_SECONDS = 3L
    }
}
