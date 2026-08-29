package com.manga.translator.translate

import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 百度翻译开放平台 API 客户端（标准版：每月免费 500 万字符）。
 *
 * - 专用翻译接口：整页多句一次请求（q 以 \n 分隔多行），延迟低（通常 <1s），
 *   相比大模型 API（2~5s 生成延迟）明显更快。
 * - 标准版限制：QPS=1、单次请求 q ≤ 2000 字节；超长自动分批并间隔限流。
 * - 签名：MD5(appid + q + salt + secretKey)，q 为原始未编码文本。
 */
class BaiduTranslateClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    /**
     * 批量翻译 [texts]（保持顺序一一对应）。
     * 失败抛异常，由调用方提示原因（不静默回显原文）。
     */
    fun translate(
        appId: String,
        secretKey: String,
        texts: List<String>,
        sourceLanguageId: String,
        targetLanguageId: String
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        val from = toBaiduLanguage(sourceLanguageId) ?: "auto"
        val to = toBaiduLanguage(targetLanguageId)
            ?: throw IllegalStateException("百度翻译不支持目标语言：$targetLanguageId")
        val results = ArrayList<String>(texts.size)

        // 按单次 q 字节上限分批（多行以 \n 连接；原文的换行在上游已归一化为空格）。
        val batches = buildBatches(texts)
        batches.forEachIndexed { batchIndex, batch ->
            if (batchIndex > 0) {
                // 标准版 QPS=1：批间留间隔，避免 54003 限流错误。
                Thread.sleep(BATCH_INTERVAL_MS)
            }
            val q = batch.joinToString("\n")
            val salt = System.currentTimeMillis().toString()
            val sign = md5("$appId$q$salt$secretKey")
            val body = FormBody.Builder()
                .add("q", q)
                .add("from", from)
                .add("to", to)
                .add("appid", appId)
                .add("salt", salt)
                .add("sign", sign)
                .build()
            val request = Request.Builder().url(ENDPOINT).post(body).build()
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("百度翻译 HTTP ${response.code}：${bodyText.take(120)}")
                }
                val json = JSONObject(bodyText)
                if (json.has("error_code")) {
                    throw IllegalStateException(
                        "百度翻译错误 ${json.optString("error_code")}：${json.optString("error_msg")}"
                    )
                }
                val transResult = json.optJSONArray("trans_result")
                    ?: throw IllegalStateException("百度翻译响应缺少 trans_result")
                if (transResult.length() != batch.size) {
                    throw IllegalStateException("百度翻译返回数量不符 ${transResult.length()}/${batch.size}")
                }
                for (i in 0 until transResult.length()) {
                    results += transResult.getJSONObject(i).optString("dst", "").ifBlank { batch[i] }
                }
            }
        }
        return results
    }

    private fun buildBatches(texts: List<String>): List<List<String>> {
        val batches = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        var bytes = 0
        for (text in texts) {
            val textBytes = text.toByteArray(Charsets.UTF_8).size + 1 // +1 换行分隔
            if (current.isNotEmpty() && bytes + textBytes > MAX_Q_BYTES) {
                batches += current
                current = mutableListOf()
                bytes = 0
            }
            current += text
            bytes += textBytes
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    private fun toBaiduLanguage(languageId: String): String? {
        return when (languageId.trim().lowercase()) {
            "en", "eng", "english", "英文" -> "en"
            "ja", "jpn", "japanese", "日文" -> "jp"
            "ko", "kor", "korean", "韩文" -> "kor"
            "zh", "zh-cn", "zh-hans", "中文", "简体中文" -> "zh"
            "zh-tw", "zh-hant", "繁体中文" -> "cht"
            else -> null
        }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "BaiduTranslate"
        const val ENDPOINT = "https://fanyi-api.baidu.com/api/trans/vip/translate"
        // 标准版单次请求 q 长度上限（字节）。
        const val MAX_Q_BYTES = 2000
        // 标准版 QPS=1：分批请求间隔。
        const val BATCH_INTERVAL_MS = 1100L
    }
}
