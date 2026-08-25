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

    override fun isSupported(sourceLanguageId: String, targetLanguageId: String): Boolean {
        if (sourceLanguageId.equals(targetLanguageId, ignoreCase = true)) return false
        val src = toMlKitLanguage(sourceLanguageId) ?: return false
        val dst = toMlKitLanguage(targetLanguageId) ?: return false
        return src != dst
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
            ensureModelDownloaded(translator, src, dst)
            texts.map { text ->
                if (text.isBlank()) {
                    text
                } else {
                    val out = Tasks.await(translator.translate(text), TRANSLATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    out?.trim().orEmpty().ifBlank { text }
                }
            }
        }
    }

    override fun prewarm(sourceLanguageId: String, targetLanguageId: String) {
        if (!isSupported(sourceLanguageId, targetLanguageId)) return
        val src = toMlKitLanguage(sourceLanguageId) ?: return
        val dst = toMlKitLanguage(targetLanguageId) ?: return
        val translator = getOrCreateTranslator(src, dst)
        // 仅触发下载，不阻塞；失败静默，等真正翻译时再处理。
        val conditions = DownloadConditions.Builder().requireWifi().build()
        translator.downloadModelIfNeeded(conditions).addOnFailureListener { err ->
            Log.w(TAG, "mlkit_translate_prewarm_failed reason=${err.message?.take(120)}")
        }
    }

    private fun getOrCreateTranslator(src: String, dst: String): Translator {
        val key = "$src->$dst"
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

    private fun ensureModelDownloaded(translator: Translator, src: String, dst: String) {
        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()
        // 若无网络或模型不可用会抛 TranslateException，由上层回退云端。
        Tasks.await(translator.downloadModelIfNeeded(conditions), DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        Log.d(TAG, "mlkit_translate_model_ready pair=$src->$dst")
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
        private const val DOWNLOAD_TIMEOUT_SECONDS = 180L
        private const val TRANSLATE_TIMEOUT_SECONDS = 30L
    }
}
