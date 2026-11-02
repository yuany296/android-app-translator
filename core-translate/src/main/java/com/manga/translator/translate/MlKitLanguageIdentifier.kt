package com.manga.translator.translate

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * ML Kit 页面语言识别（2026-11-01）。
 *
 * 用 bundled `com.google.mlkit:language-id`（模型随 APK 打包，约 +900KB，不需要联网、
 * 不依赖 Google Play 服务）。ML Kit 负责给候选和 confidence，真正的页面语言选择
 * 交给 [PageLanguageDetector]（脚本强证据 > ML Kit 候选 > 声明语言 > 首页回退）。
 *
 * 超时/失败一律返回空列表：页面语言判定不能把翻译流程搞挂。
 */
object MlKitLanguageIdentifier {

    private const val TAG = "MlKitLangId"

    /** ML Kit 建议的识别上限；页面正文 2000 字已经足够，留一点余量。 */
    private const val MAX_CHARS = 4000

    /** 单次识别等待上限；bundled 模型首帧通常 <100ms，1s 只防异常卡死。 */
    private const val TIMEOUT_MS = 1_000L

    private val client: LanguageIdentifier? by lazy {
        runCatching {
            LanguageIdentification.getClient(
                LanguageIdentificationOptions.Builder()
                    .setConfidenceThreshold(0.3f)
                    .build()
            )
        }.onFailure { err ->
            Log.w(TAG, "lang_id_init_fail ${err.javaClass.simpleName}: ${err.message}")
        }.getOrNull()
    }

    suspend fun identify(text: String): List<LanguageCandidate> = withContext(Dispatchers.IO) {
        val sample = text.take(MAX_CHARS).trim()
        if (sample.isEmpty()) return@withContext emptyList()
        val identifier = client ?: return@withContext emptyList()
        val startedAt = System.currentTimeMillis()
        val result = runCatching {
            Tasks.await(
                identifier.identifyPossibleLanguages(sample),
                TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
        }.onFailure { err ->
            Log.w(TAG, "lang_id_fail reason=${err.javaClass.simpleName}: ${err.message}")
        }.getOrDefault(emptyList())
            .map { LanguageCandidate(it.languageTag, it.confidence) }
            .sortedByDescending { it.confidence }
        Log.d(
            TAG,
            "lang_id chars=${sample.length} ms=${System.currentTimeMillis() - startedAt} " +
                "candidates=" + result.take(3).joinToString(separator = ",") { candidate ->
                    String.format(Locale.US, "%s:%.2f", candidate.languageTag, candidate.confidence)
                }
        )
        result
    }
}
