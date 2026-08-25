package com.manga.translator.translate

import android.os.SystemClock
import android.util.Log
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.max

class OpenAiVisionClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    @Volatile
    private var baiduTokenCache: BaiduAccessTokenCache? = null

    fun detectBubbles(
        provider: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        baiduOcrLanguage: String = "JAP",
        dataUrl: String,
        imageWidth: Int,
        imageHeight: Int,
        ignoreSimplifiedChinese: Boolean,
        enableThinking: Boolean,
        thinkingBudget: Int
    ): TranslationResult {
        val providerId = provider.trim().lowercase()
        if (providerId == BAIDU_PROVIDER_ID) {
            return detectBubblesWithBaidu(
                baseUrl = baseUrl,
                credentials = apiKey,
                baiduOcrLanguage = baiduOcrLanguage,
                dataUrl = dataUrl,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                ignoreSimplifiedChinese = ignoreSimplifiedChinese
            )
        }

        val endpoint = normalizeBaseUrl(baseUrl) + "/chat/completions"
        val traceId = "ocr-" + SystemClock.elapsedRealtime().toString()
        val thinkingEnabled = enableThinking && (providerId == "qwen" || providerId == "glm")
        val normalizedThinkingBudget = thinkingBudget.coerceIn(1024, 131072)
        val prompt = buildOcrPrompt(ignoreSimplifiedChinese, imageWidth, imageHeight)

        val result = requestWithFallback(
            endpoint = endpoint,
            model = model,
            apiKey = apiKey,
            traceId = traceId,
            includeThinking = thinkingEnabled,
            thinkingBudget = normalizedThinkingBudget
        ) { useJsonFormat, useThinking ->
            buildVisionBody(
                model = model,
                prompt = prompt,
                dataUrl = dataUrl,
                useJsonResponseFormat = useJsonFormat,
                includeThinking = useThinking,
                thinkingBudget = normalizedThinkingBudget,
                systemPrompt = "You are a manga OCR engine. Return JSON only."
            )
        }

        val parsed = parseBubblesFromCompletion(
            raw = result,
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
        val normalized = parsed.bubbles.map {
            it.copy(translatedText = it.originalText.ifBlank { it.translatedText })
        }
        return TranslationResult(normalized)
    }

    fun detectTextBlocks(
        provider: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        baiduOcrLanguage: String = "JAP",
        dataUrl: String,
        imageWidth: Int,
        imageHeight: Int,
        ignoreSimplifiedChinese: Boolean,
        enableThinking: Boolean,
        thinkingBudget: Int
    ): List<TextBlock> {
        val providerId = provider.trim().lowercase()
        if (providerId == BAIDU_PROVIDER_ID) {
            return detectTextBlocksWithBaidu(
                baseUrl = baseUrl,
                credentials = apiKey,
                baiduOcrLanguage = baiduOcrLanguage,
                dataUrl = dataUrl,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                ignoreSimplifiedChinese = ignoreSimplifiedChinese
            )
        }
        val bubbles = detectBubbles(
            provider = provider,
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            baiduOcrLanguage = baiduOcrLanguage,
            dataUrl = dataUrl,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            ignoreSimplifiedChinese = ignoreSimplifiedChinese,
            enableThinking = enableThinking,
            thinkingBudget = thinkingBudget
        ).bubbles
        return bubbles.mapIndexedNotNull { index, bubble ->
            val text = bubble.originalText.trim()
            if (text.isBlank()) return@mapIndexedNotNull null
            val rect = BubbleGeometry.contentRect(bubble)
            val left = (rect.left / 100f) * imageWidth.toFloat()
            val top = (rect.top / 100f) * imageHeight.toFloat()
            val right = (rect.right / 100f) * imageWidth.toFloat()
            val bottom = (rect.bottom / 100f) * imageHeight.toFloat()
            TextBlock(
                id = index,
                text = text,
                left = left.coerceIn(0f, imageWidth.toFloat()),
                top = top.coerceIn(0f, imageHeight.toFloat()),
                right = right.coerceIn(0f, imageWidth.toFloat()),
                bottom = bottom.coerceIn(0f, imageHeight.toFloat()),
                confidence = 0f
            )
        }
    }

    fun translateTexts(
        provider: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        texts: List<String>,
        targetLanguageId: String,
        enableThinking: Boolean,
        thinkingBudget: Int,
        glossary: List<Pair<String, String>> = emptyList()
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (provider.trim().equals(BAIDU_PROVIDER_ID, ignoreCase = true)) {
            throw IOException("Baidu OCR only supports OCR recognition. Configure another translation provider.")
        }
        val endpoint = normalizeBaseUrl(baseUrl) + "/chat/completions"
        val traceId = "tr-" + SystemClock.elapsedRealtime().toString()
        val providerId = provider.trim().lowercase()
        val thinkingEnabled = enableThinking && (providerId == "qwen" || providerId == "glm")
        val normalizedThinkingBudget = thinkingBudget.coerceIn(1024, 131072)
        val prompt = buildTranslatePrompt(texts.size, targetLanguageId, glossary)

        val inputArray = JSONArray().apply {
            texts.forEach { put(it) }
        }

        val result = requestWithFallback(
            endpoint = endpoint,
            model = model,
            apiKey = apiKey,
            traceId = traceId,
            includeThinking = thinkingEnabled,
            thinkingBudget = normalizedThinkingBudget
        ) { useJsonFormat, useThinking ->
            buildTextTranslateBody(
                model = model,
                prompt = prompt,
                inputTextsJson = inputArray.toString(),
                useJsonResponseFormat = useJsonFormat,
                includeThinking = useThinking,
                thinkingBudget = normalizedThinkingBudget
            )
        }

        return parseTranslations(result, texts)
    }

    fun groupTextBlocks(
        provider: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        blocks: List<TextBlock>,
        enableThinking: Boolean,
        thinkingBudget: Int
    ): List<TextBlockGroup> {
        if (blocks.isEmpty()) return emptyList()
        if (provider.trim().equals(BAIDU_PROVIDER_ID, ignoreCase = true)) {
            throw IOException("Baidu OCR only supports OCR recognition and cannot be used for grouping.")
        }
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            throw IOException("Missing API config for block grouping")
        }
        val endpoint = normalizeBaseUrl(baseUrl) + "/chat/completions"
        val traceId = "grp-" + SystemClock.elapsedRealtime().toString()
        val providerId = provider.trim().lowercase()
        val thinkingEnabled = enableThinking && (providerId == "qwen" || providerId == "glm")
        val normalizedThinkingBudget = thinkingBudget.coerceIn(1024, 131072)
        val prompt = buildGroupPrompt(blocks)

        val result = requestWithFallback(
            endpoint = endpoint,
            model = model,
            apiKey = apiKey,
            traceId = traceId,
            includeThinking = thinkingEnabled,
            thinkingBudget = normalizedThinkingBudget
        ) { useJsonFormat, useThinking ->
            buildTextTranslateBody(
                model = model,
                prompt = prompt,
                inputTextsJson = "[]",
                useJsonResponseFormat = useJsonFormat,
                includeThinking = useThinking,
                thinkingBudget = normalizedThinkingBudget
            )
        }
return parseGroups(result, blocks)
    }

    fun fetchAvailableModels(baseUrl: String, apiKey: String): List<String> {
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            throw IOException("Missing API config for fetching models")
        }
        val endpoint = normalizeBaseUrl(baseUrl) + "/models"
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(extractError(raw).ifBlank { "HTTP ${response.code}" })
            }
            val root = runCatching { JSONObject(raw) }.getOrNull()
                ?: throw IOException("Invalid models response")
            val data = root.optJSONArray("data") ?: JSONArray()
            val ids = mutableListOf<String>()
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val id = item.optString("id", "").trim()
                if (id.isNotEmpty()) ids += id
            }
            return ids.distinct().sorted()
        }
    }

    private fun detectBubblesWithBaidu(
        baseUrl: String,
        credentials: String,
        baiduOcrLanguage: String,
        dataUrl: String,
        imageWidth: Int,
        imageHeight: Int,
        ignoreSimplifiedChinese: Boolean
    ): TranslationResult {
        val traceId = "ocr-baidu-" + SystemClock.elapsedRealtime().toString()
        val response = executeBaiduOcrRequest(
            baseUrl = baseUrl,
            credentials = credentials,
            baiduOcrLanguage = baiduOcrLanguage,
            dataUrl = dataUrl,
            traceId = traceId
        )
        val bubbles = parseBaiduWordsResult(
            raw = response.raw,
            imageWidth = imageWidth.coerceAtLeast(1),
            imageHeight = imageHeight.coerceAtLeast(1),
            baiduLanguage = response.language,
            ignoreSimplifiedChinese = ignoreSimplifiedChinese,
            traceId = traceId
        )
        val nearbyMerged = if (ENABLE_BAIDU_NEARBY_MERGE) {
            mergeNearbyBaiduBubbles(bubbles)
        } else {
            bubbles
        }
        val merged = if (ENABLE_BAIDU_HEURISTIC_MERGE) {
            mergeNearbyBaiduBubblesHeuristically(
                bubbles = nearbyMerged,
                imageWidth = imageWidth.coerceAtLeast(1),
                imageHeight = imageHeight.coerceAtLeast(1)
            )
        } else {
            nearbyMerged
        }
        val deduped = dedupeHighlyOverlappingBubbles(merged)
        val finalBubbles = if (bubbles.isNotEmpty() && deduped.isEmpty()) {
            Log.w(
                TAG,
                "[$traceId] baidu_merge_fallback reason=deduped_empty raw=${bubbles.size} nearby=${nearbyMerged.size} merged=${merged.size}"
            )
            bubbles
        } else {
            deduped
        }
        Log.d(
            TAG,
            "[$traceId] baidu_merge_stats raw=${bubbles.size} nearby=${nearbyMerged.size} merged=${merged.size} deduped=${deduped.size} final=${finalBubbles.size}"
        )
        Log.d(
            TAG,
            "[$traceId] baidu_response_ok cost_ms=${response.costMs} raw=${bubbles.size} nearby=${nearbyMerged.size} merged=${merged.size} deduped=${deduped.size} final=${finalBubbles.size}"
        )
        return TranslationResult(
            finalBubbles.map { bubble ->
                bubble.copy(translatedText = bubble.originalText.ifBlank { bubble.translatedText })
            }
        )
    }

    private fun detectTextBlocksWithBaidu(
        baseUrl: String,
        credentials: String,
        baiduOcrLanguage: String,
        dataUrl: String,
        imageWidth: Int,
        imageHeight: Int,
        ignoreSimplifiedChinese: Boolean
    ): List<TextBlock> {
        val traceId = "ocr-baidu-blocks-" + SystemClock.elapsedRealtime().toString()
        val response = executeBaiduOcrRequest(
            baseUrl = baseUrl,
            credentials = credentials,
            baiduOcrLanguage = baiduOcrLanguage,
            dataUrl = dataUrl,
            traceId = traceId
        )
        val blocks = parseBaiduTextBlocks(
            raw = response.raw,
            imageWidth = imageWidth.coerceAtLeast(1),
            imageHeight = imageHeight.coerceAtLeast(1),
            ignoreSimplifiedChinese = ignoreSimplifiedChinese,
            traceId = traceId,
            baiduLanguage = response.language
        )
        Log.d(TAG, "[$traceId] baidu_text_block_stats count=${blocks.size} cost_ms=${response.costMs}")
        return blocks
    }

    private fun executeBaiduOcrRequest(
        baseUrl: String,
        credentials: String,
        baiduOcrLanguage: String,
        dataUrl: String,
        traceId: String
    ): BaiduOcrRequestResult {
        val (ak, sk) = parseBaiduCredentials(credentials)
        val host = resolveBaiduHost(baseUrl)
        val accessToken = requestBaiduAccessToken(host, ak, sk, traceId)
        val normalizedLanguage = normalizeBaiduLanguage(baiduOcrLanguage)
        val endpoint = "$host/rest/2.0/ocr/v1/accurate?access_token=$accessToken"
        val imageBase64 = extractImageBase64(dataUrl)
        val requestBody = FormBody.Builder()
            .add("image", imageBase64)
            .add("language_type", normalizedLanguage)
            .add("detect_direction", "false")
            .add("vertexes_location", "false")
            .add("paragraph", "true")
            .add("probability", "false")
            .add("char_probability", "false")
            .add("multidirectional_recognize", "true")
            .build()
        val req = Request.Builder()
            .url(endpoint)
            .addHeader("Accept", "application/json")
            .post(requestBody)
            .build()

        val started = SystemClock.elapsedRealtime()
        Log.d(
            TAG,
            "[$traceId] baidu_request_send endpoint=$host/rest/2.0/ocr/v1/accurate " +
                "language=$normalizedLanguage image_chars=${imageBase64.length}"
        )
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val cost = SystemClock.elapsedRealtime() - started
            if (!resp.isSuccessful) {
                val err = extractBaiduError(raw).ifBlank { "${resp.code} ${resp.message}" }
                Log.w(TAG, "[$traceId] baidu_response_fail code=${resp.code} cost_ms=$cost err=${err.take(240)}")
                throw IOException("Baidu OCR request failed: $err")
            }
            return BaiduOcrRequestResult(
                raw = raw,
                language = normalizedLanguage,
                costMs = cost
            )
        }
    }

    private fun parseBaiduWordsResult(
        raw: String,
        imageWidth: Int,
        imageHeight: Int,
        baiduLanguage: String,
        ignoreSimplifiedChinese: Boolean,
        traceId: String
    ): List<Bubble> {
        val root = runCatching { JSONObject(raw) }.getOrElse { err ->
            throw IOException("Baidu OCR response parse failed: ${err.message}")
        }
        val errorCode = root.optInt("error_code", 0)
        if (errorCode != 0) {
            val msg = root.optString("error_msg", "unknown")
            throw IOException("Baidu OCR error[$errorCode]: $msg")
        }
        val words = root.optJSONArray("words_result") ?: JSONArray()
        val wordsNum = root.optInt("words_result_num", words.length())
        val lineBubbles = mutableListOf<Bubble>()
        var blankFiltered = 0
        var simplifiedFiltered = 0
        var invalidLocationFiltered = 0
        for (index in 0 until words.length()) {
            val item = words.optJSONObject(index) ?: continue
            val text = item.optString("words", "").trim()
            if (text.isBlank()) {
                blankFiltered += 1
                continue
            }
            // 百度 OCR 结果先不过滤简中，避免误杀导致整页“无文本”。
            if (false && ignoreSimplifiedChinese && isLikelySimplifiedOnlyText(text)) {
                simplifiedFiltered += 1
                continue
            }
            val location = item.optJSONObject("location") ?: continue
            val left = location.optDouble("left", -1.0).toFloat()
            val top = location.optDouble("top", -1.0).toFloat()
            val width = location.optDouble("width", -1.0).toFloat()
            val height = location.optDouble("height", -1.0).toFloat()
            if (left < 0f || top < 0f || width <= 0f || height <= 0f) {
                invalidLocationFiltered += 1
                continue
            }
            val layout = if (isLikelyVerticalText(text, width, height)) "vertical" else "horizontal"
            lineBubbles += Bubble(
                x = (left / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
                y = (top / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
                w = (width / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
                h = (height / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
                bgType = "solid",
                originalText = text,
                translatedText = text,
                layout = layout,
                contentX = (left / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
                contentY = (top / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
                contentW = (width / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
                contentH = (height / imageHeight.toFloat() * 100f).coerceIn(0f, 100f)
            )
        }
        val paragraphBubbles = buildBaiduParagraphBubbles(root, lineBubbles, traceId)
        val selectedBubbles = if (paragraphBubbles.isNotEmpty()) {
            paragraphBubbles
        } else {
            lineBubbles
        }
        Log.d(
            TAG,
            "[$traceId] baidu_parse_stats words_num=$wordsNum raw_items=${words.length()} accepted=${selectedBubbles.size} " +
                "blank_filtered=$blankFiltered simplified_filtered=$simplifiedFiltered invalid_location=$invalidLocationFiltered " +
                "ignore_simplified=$ignoreSimplifiedChinese language=$baiduLanguage paragraph_count=${paragraphBubbles.size}"
        )
        return selectedBubbles
    }

    private fun parseBaiduTextBlocks(
        raw: String,
        imageWidth: Int,
        imageHeight: Int,
        ignoreSimplifiedChinese: Boolean,
        traceId: String,
        baiduLanguage: String
    ): List<TextBlock> {
        val root = runCatching { JSONObject(raw) }.getOrElse { err ->
            throw IOException("Baidu OCR response parse failed: ${err.message}")
        }
        val errorCode = root.optInt("error_code", 0)
        if (errorCode != 0) {
            val msg = root.optString("error_msg", "unknown")
            throw IOException("Baidu OCR error[$errorCode]: $msg")
        }
        val words = root.optJSONArray("words_result") ?: JSONArray()
        val blocks = mutableListOf<TextBlock>()
        var blankFiltered = 0
        var simplifiedFiltered = 0
        var invalidLocationFiltered = 0
        for (index in 0 until words.length()) {
            val item = words.optJSONObject(index) ?: continue
            val text = item.optString("words", "").trim()
            if (text.isBlank()) {
                blankFiltered += 1
                continue
            }
            if (false && ignoreSimplifiedChinese && isLikelySimplifiedOnlyText(text)) {
                simplifiedFiltered += 1
                continue
            }
            val location = item.optJSONObject("location") ?: continue
            val left = location.optDouble("left", -1.0).toFloat()
            val top = location.optDouble("top", -1.0).toFloat()
            val width = location.optDouble("width", -1.0).toFloat()
            val height = location.optDouble("height", -1.0).toFloat()
            if (left < 0f || top < 0f || width <= 0f || height <= 0f) {
                invalidLocationFiltered += 1
                continue
            }
            blocks += TextBlock(
                id = blocks.size,
                text = text,
                left = left.coerceIn(0f, imageWidth.toFloat()),
                top = top.coerceIn(0f, imageHeight.toFloat()),
                right = (left + width).coerceIn(0f, imageWidth.toFloat()),
                bottom = (top + height).coerceIn(0f, imageHeight.toFloat()),
                confidence = 0f
            )
        }
        Log.d(
            TAG,
            "[$traceId] baidu_text_parse_stats words_num=${words.length()} blocks=${blocks.size} blank_filtered=$blankFiltered " +
                "simplified_filtered=$simplifiedFiltered invalid_location=$invalidLocationFiltered language=$baiduLanguage"
        )
        return blocks
    }

    private fun buildBaiduParagraphBubbles(root: JSONObject, lineBubbles: List<Bubble>, traceId: String): List<Bubble> {
        val paragraphs = root.optJSONArray("paragraphs_result") ?: return emptyList()
        if (paragraphs.length() == 0 || lineBubbles.isEmpty()) return emptyList()
        val merged = mutableListOf<Bubble>()
        val usedIndexes = linkedSetOf<Int>()
        val paragraphSizes = mutableListOf<Int>()
        for (i in 0 until paragraphs.length()) {
            val paragraph = paragraphs.optJSONObject(i) ?: continue
            val lineIndexes = paragraph.optJSONArray("words_result_idx") ?: continue
            val group = mutableListOf<Bubble>()
            for (j in 0 until lineIndexes.length()) {
                val index = lineIndexes.optInt(j, -1)
                if (index !in lineBubbles.indices) continue
                if (!usedIndexes.add(index)) continue
                group += lineBubbles[index]
            }
            if (group.isEmpty()) continue
            paragraphSizes += group.size
            merged += if (group.size == 1) group.first() else mergeBaiduBubbleGroup(group)
        }
        lineBubbles.forEachIndexed { index, bubble ->
            if (!usedIndexes.contains(index)) {
                merged += bubble
            }
        }
        Log.d(
            TAG,
            "[$traceId] baidu_paragraph_stats count=${paragraphs.length()} merged_groups=${paragraphSizes.size} " +
                "sample_sizes=${paragraphSizes.take(8).joinToString(separator = ",")}"
        )
        return merged
    }

    private fun mergeNearbyBaiduBubbles(bubbles: List<Bubble>): List<Bubble> {
        return mergeBaiduBubblesByPredicate(bubbles) { a, b ->
            shouldMergeBaiduBubbles(a, b)
        }
    }

    private fun mergeNearbyBaiduBubblesHeuristically(
        bubbles: List<Bubble>,
        imageWidth: Int,
        imageHeight: Int
    ): List<Bubble> {
        val width = imageWidth.coerceAtLeast(1)
        val height = imageHeight.coerceAtLeast(1)
        return mergeBaiduBubblesByPredicate(bubbles) { a, b ->
            shouldMergeBaiduBubblesHeuristically(a, b, width, height)
        }
    }

    private fun mergeBaiduBubblesByPredicate(
        bubbles: List<Bubble>,
        shouldMerge: (Bubble, Bubble) -> Boolean
    ): List<Bubble> {
        if (bubbles.size <= 1) return bubbles
        val parent = IntArray(bubbles.size) { it }

        fun find(x: Int): Int {
            var node = x
            while (parent[node] != node) {
                parent[node] = parent[parent[node]]
                node = parent[node]
            }
            return node
        }

        fun union(a: Int, b: Int) {
            val pa = find(a)
            val pb = find(b)
            if (pa != pb) {
                parent[pb] = pa
            }
        }

        for (i in bubbles.indices) {
            for (j in (i + 1) until bubbles.size) {
                if (shouldMerge(bubbles[i], bubbles[j])) {
                    union(i, j)
                }
            }
        }

        val grouped = linkedMapOf<Int, MutableList<Bubble>>()
        bubbles.forEachIndexed { index, bubble ->
            val root = find(index)
            grouped.getOrPut(root) { mutableListOf() }.add(bubble)
        }
        return grouped.values.map { group ->
            if (group.size == 1) {
                group.first()
            } else {
                mergeBaiduBubbleGroup(group)
            }
        }
    }

    private fun shouldMergeBaiduBubblesHeuristically(
        a: Bubble,
        b: Bubble,
        imageWidth: Int,
        imageHeight: Int
    ): Boolean {
        return BubbleMergeHeuristics.shouldMerge(
            a = BubbleGeometry.mergeCandidate(a),
            b = BubbleGeometry.mergeCandidate(b),
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            scenario = BubbleMergeScenario.GENERIC_TEXT
        ).merge
    }

    private fun shouldMergeBaiduBubbles(a: Bubble, b: Bubble): Boolean {
        val decision = BubbleMergeHeuristics.shouldMerge(
            a = BubbleGeometry.mergeCandidate(a),
            b = BubbleGeometry.mergeCandidate(b),
            imageWidth = BAIDU_NORMALIZED_SPACE,
            imageHeight = BAIDU_NORMALIZED_SPACE,
            scenario = BubbleMergeScenario.GENERIC_TEXT
        )
        if (!decision.merge) {
            logBaiduMergeReject(decision.reason, a, b)
        }
        return decision.merge
    }

    private fun logBaiduMergeReject(reason: String, a: Bubble, b: Bubble) {
        runCatching {
            Log.v(
                TAG,
                "baidu_merge_reject reason=$reason a=(${a.x},${a.y},${a.w},${a.h},${a.layout}) " +
                    "b=(${b.x},${b.y},${b.w},${b.h},${b.layout})"
            )
        }
    }

    private fun mergeBaiduBubbleGroup(group: List<Bubble>): Bubble {
        val left = group.minOf { it.x }
        val top = group.minOf { it.y }
        val right = group.maxOf { it.x + it.w }
        val bottom = group.maxOf { it.y + it.h }
        val contentLeft = group.minOf { it.contentX }
        val contentTop = group.minOf { it.contentY }
        val contentRight = group.maxOf { it.contentX + it.contentW }
        val contentBottom = group.maxOf { it.contentY + it.contentH }
        val width = (right - left).coerceAtLeast(0.1f)
        val height = (bottom - top).coerceAtLeast(0.1f)
        val contentWidth = (contentRight - contentLeft).coerceAtLeast(0.1f)
        val contentHeight = (contentBottom - contentTop).coerceAtLeast(0.1f)
        val verticalCount = group.count { it.layout == "vertical" || it.h >= it.w * 1.2f }
        val verticalLayout = verticalCount * 2 >= group.size ||
            (verticalCount > 0 && group.size >= 2 && width > height)
        val sorted = if (verticalLayout) {
            group.sortedWith(compareByDescending<Bubble> { it.x + it.w / 2f }.thenBy { it.y })
        } else {
            group.sortedWith(compareBy<Bubble> { it.y }.thenBy { it.x })
        }
        val mergedText = if (verticalLayout) {
            sorted.joinToString(separator = "") { it.originalText.trim().replace("\n", "") }
        } else {
            sorted.joinToString(separator = "\n") { it.originalText.trim() }
        }.trim()
        return Bubble(
            x = left.coerceIn(0f, 100f),
            y = top.coerceIn(0f, 100f),
            w = width.coerceIn(0f, 100f),
            h = height.coerceIn(0f, 100f),
            bgType = "solid",
            originalText = mergedText,
            translatedText = mergedText,
            layout = if (verticalLayout) "vertical" else "horizontal",
            contentX = contentLeft.coerceIn(0f, 100f),
            contentY = contentTop.coerceIn(0f, 100f),
            contentW = contentWidth.coerceIn(0f, 100f),
            contentH = contentHeight.coerceIn(0f, 100f)
        )
    }

    private fun requestBaiduAccessToken(host: String, ak: String, sk: String, traceId: String): String {
        val fingerprint = fingerprintCredentials(ak, sk)
        val now = SystemClock.elapsedRealtime()
        baiduTokenCache?.let { cache ->
            if (cache.credentialFingerprint == fingerprint && now < cache.expiresAtElapsedMs) {
                Log.d(TAG, "[$traceId] baidu_token_cache_hit ttl_ms=${cache.expiresAtElapsedMs - now}")
                return cache.token
            }
        }
        val url = "$host/oauth/2.0/token?grant_type=client_credentials&client_id=$ak&client_secret=$sk"
        val req = Request.Builder()
            .url(url)
            .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        val started = SystemClock.elapsedRealtime()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val cost = SystemClock.elapsedRealtime() - started
            if (!resp.isSuccessful) {
                val err = extractBaiduError(raw).ifBlank { "${resp.code} ${resp.message}" }
                Log.w(TAG, "[$traceId] baidu_token_fail code=${resp.code} cost_ms=$cost err=${err.take(200)}")
                throw IOException("Baidu token request failed: $err")
            }
            val root = runCatching { JSONObject(raw) }.getOrElse { err ->
                throw IOException("Baidu token parse failed: ${err.message}")
            }
            val token = root.optString("access_token", "").trim()
            if (token.isBlank()) {
                throw IOException("Baidu token response missing access_token")
            }
            val expiresInSeconds = root.optLong("expires_in", 2592000L).coerceAtLeast(120L)
            val expiresAt = now + ((expiresInSeconds - 60L).coerceAtLeast(60L) * 1000L)
            baiduTokenCache = BaiduAccessTokenCache(
                credentialFingerprint = fingerprint,
                token = token,
                expiresAtElapsedMs = expiresAt
            )
            Log.d(TAG, "[$traceId] baidu_token_ok cost_ms=$cost expires_in_s=$expiresInSeconds")
            return token
        }
    }

    private fun resolveBaiduHost(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        if (normalized.isBlank()) return BAIDU_DEFAULT_HOST
        return if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            normalized
        } else {
            "https://$normalized"
        }
    }

    private fun parseBaiduCredentials(raw: String): Pair<String, String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            throw IOException("Baidu OCR credentials are empty. Use format: AK|SK")
        }
        runCatching { JSONObject(trimmed) }.getOrNull()?.let { json ->
            val ak = json.optString("ak")
                .ifBlank { json.optString("apiKey") }
                .ifBlank { json.optString("client_id") }
                .trim()
            val sk = json.optString("sk")
                .ifBlank { json.optString("secretKey") }
                .ifBlank { json.optString("client_secret") }
                .trim()
            if (ak.isNotBlank() && sk.isNotBlank()) return ak to sk
        }
        if (trimmed.contains("&") && trimmed.contains("=")) {
            val kv = trimmed.split("&")
                .mapNotNull { part ->
                    val pair = part.split("=", limit = 2)
                    if (pair.size != 2) return@mapNotNull null
                    pair[0].trim().lowercase() to pair[1].trim()
                }.toMap()
            val ak = kv["ak"].orEmpty().ifBlank { kv["apikey"].orEmpty() }.ifBlank { kv["client_id"].orEmpty() }
            val sk = kv["sk"].orEmpty().ifBlank { kv["secretkey"].orEmpty() }.ifBlank { kv["client_secret"].orEmpty() }
            if (ak.isNotBlank() && sk.isNotBlank()) return ak to sk
        }
        val separator = BAIDU_CREDENTIAL_SEPARATORS.firstOrNull { trimmed.contains(it) }
        if (separator != null) {
            val parts = trimmed.split(separator, limit = 2).map { it.trim() }
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                return parts[0] to parts[1]
            }
        }
        throw IOException("Invalid Baidu OCR credentials. Use format: AK|SK")
    }

    private fun extractImageBase64(dataUrl: String): String {
        val trimmed = dataUrl.trim()
        if (trimmed.isBlank()) throw IOException("OCR image payload is empty")
        val marker = "base64,"
        val markerIndex = trimmed.indexOf(marker, ignoreCase = true)
        val base64 = if (markerIndex >= 0) {
            trimmed.substring(markerIndex + marker.length)
        } else {
            trimmed
        }
        return base64.trim()
    }

    private fun extractBaiduError(raw: String): String {
        return runCatching {
            val root = JSONObject(raw)
            val code = root.optInt("error_code", 0)
            val msg = root.optString("error_msg", "").trim()
            when {
                code > 0 && msg.isNotBlank() -> "[$code] $msg"
                code > 0 -> "[$code]"
                msg.isNotBlank() -> msg
                else -> ""
            }
        }.getOrDefault(raw)
    }

    private fun fingerprintCredentials(ak: String, sk: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$ak|$sk".toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { b -> "%02x".format(b) }
    }

    private fun isLikelyVerticalText(text: String, width: Float, height: Float): Boolean {
        if (height >= width * 1.25f) return true
        val compact = text.replace("\n", "").trim()
        return compact.length in 1..3 && height > width * 0.95f
    }

    private fun normalizeBaiduLanguage(raw: String): String {
        val normalized = raw.trim().uppercase()
        return if (normalized in BAIDU_LANGUAGE_TYPES) normalized else BAIDU_DEFAULT_LANGUAGE
    }

    private fun isLikelySimplifiedOnlyText(text: String): Boolean {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return false
        val hasJapaneseKana = compact.any { ch ->
            (ch in '\u3040'..'\u309F') || (ch in '\u30A0'..'\u30FF')
        }
        if (hasJapaneseKana) return false
        val markerCount = compact.count { SIMPLIFIED_MARKERS.contains(it) }
        if (markerCount < 2) return false
        val ratio = markerCount.toFloat() / compact.length.toFloat()
        return ratio >= 0.45f
    }

    private fun requestWithFallback(
        endpoint: String,
        model: String,
        apiKey: String,
        traceId: String,
        includeThinking: Boolean,
        thinkingBudget: Int,
        bodyBuilder: (useJsonFormat: Boolean, useThinking: Boolean) -> JSONObject
    ): String {
        Log.d(TAG, "[$traceId] request_prepare model=$model endpoint=$endpoint")

        val firstTry = executeRequest(
            endpoint = endpoint,
            apiKey = apiKey,
            body = bodyBuilder(true, includeThinking),
            traceId = traceId,
            jsonFormat = true,
            thinking = includeThinking
        )
        if (firstTry.isSuccess) return firstTry.raw

        val firstError = firstTry.error.orEmpty()
        if (includeThinking && isThinkingUnsupported(firstError)) {
            val noThinkingTry = executeRequest(
                endpoint = endpoint,
                apiKey = apiKey,
                body = bodyBuilder(true, false).put("thinking_budget", thinkingBudget),
                traceId = traceId,
                jsonFormat = true,
                thinking = false
            )
            if (noThinkingTry.isSuccess) return noThinkingTry.raw
            if (isResponseFormatUnsupported(noThinkingTry.error.orEmpty())) {
                val noThinkingNoFormat = executeRequest(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    body = bodyBuilder(false, false),
                    traceId = traceId,
                    jsonFormat = false,
                    thinking = false
                )
                if (noThinkingNoFormat.isSuccess) return noThinkingNoFormat.raw
                throw IOException("OpenAI-compatible API error: ${noThinkingNoFormat.error ?: noThinkingTry.error}")
            }
            throw IOException("OpenAI-compatible API error: ${noThinkingTry.error ?: firstError}")
        }

        if (isResponseFormatUnsupported(firstError)) {
            val secondTry = executeRequest(
                endpoint = endpoint,
                apiKey = apiKey,
                body = bodyBuilder(false, includeThinking),
                traceId = traceId,
                jsonFormat = false,
                thinking = includeThinking
            )
            if (secondTry.isSuccess) return secondTry.raw
            throw IOException("OpenAI-compatible API error: ${secondTry.error ?: firstError}")
        }

        throw IOException("OpenAI-compatible API error: ${firstError.ifBlank { "unknown error" }}")
    }

    private fun buildVisionBody(
        model: String,
        prompt: String,
        dataUrl: String,
        useJsonResponseFormat: Boolean,
        includeThinking: Boolean,
        thinkingBudget: Int,
        systemPrompt: String
    ): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray()
                                    .put(JSONObject().put("type", "text").put("text", prompt))
                                    .put(
                                        JSONObject()
                                            .put("type", "image_url")
                                            .put("image_url", JSONObject().put("url", dataUrl))
                                    )
                            )
                    )
            )
        if (includeThinking) {
            body.put("enable_thinking", true)
            body.put("thinking_budget", thinkingBudget)
        } else {
            // 非思考模式限制最大输出，避免模型输出失控导致响应时间暴涨（日志曾观测到 2 句译文返回 18KB）。
            body.put("max_tokens", MAX_OUTPUT_TOKENS)
        }
        if (useJsonResponseFormat) {
            body.put("response_format", JSONObject().put("type", "json_object"))
        }
        return body
    }

    private fun buildTextTranslateBody(
        model: String,
        prompt: String,
        inputTextsJson: String,
        useJsonResponseFormat: Boolean,
        includeThinking: Boolean,
        thinkingBudget: Int
    ): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", "You are a text translation engine. Return JSON only.")
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                "${prompt}\nINPUT_TEXTS_JSON=${inputTextsJson}"
                            )
                    )
            )
        if (includeThinking) {
            body.put("enable_thinking", true)
            body.put("thinking_budget", thinkingBudget)
        } else {
            body.put("max_tokens", MAX_OUTPUT_TOKENS)
        }
        if (useJsonResponseFormat) {
            body.put("response_format", JSONObject().put("type", "json_object"))
        }
        return body
    }

    private fun executeRequest(
        endpoint: String,
        apiKey: String,
        body: JSONObject,
        traceId: String,
        jsonFormat: Boolean,
        thinking: Boolean
    ): ApiResult {
        val requestBody = body.toString()
        val req = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val started = SystemClock.elapsedRealtime()
        Log.d(TAG, "[$traceId] request_send json_format=$jsonFormat thinking=$thinking bytes=${requestBody.length}")
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val cost = SystemClock.elapsedRealtime() - started
            return if (resp.isSuccessful) {
                Log.d(TAG, "[$traceId] response_ok code=${resp.code} cost_ms=$cost chars=${raw.length}")
                // 诊断：记录响应开头，用于确认模型是否输出注水/重复内容（影响耗时）。
                Log.d(TAG, "[$traceId] response_preview chars=${raw.length} preview=${raw.take(160).replace('\n', ' ')}")
                ApiResult(true, raw, null)
            } else {
                val err = extractError(raw).ifBlank { "${resp.code} ${resp.message}" }
                Log.w(TAG, "[$traceId] response_fail code=${resp.code} cost_ms=$cost err=${err.take(240)}")
                ApiResult(false, "", err)
            }
        }
    }

    private fun parseBubblesFromCompletion(
        raw: String,
        imageWidth: Int,
        imageHeight: Int
    ): TranslationResult {
        val text = extractCompletionText(raw)
        return parseModelJson(text, imageWidth, imageHeight)
    }

    private fun parseTranslations(raw: String, sourceTexts: List<String>): List<String> {
        val text = extractCompletionText(raw)
        val candidate = extractJsonCandidate(text)
        if (candidate.isBlank()) {
            Log.w(TAG, "translate_parse_fallback reason=no_json_candidate raw_chars=${raw.length} texts=${sourceTexts.size}")
            return sourceTexts
        }
        return runCatching {
            if (candidate.trimStart().startsWith("[")) {
                val arr = JSONArray(candidate)
                return@runCatching normalizeTranslationList(arr, sourceTexts)
            }
            val root = JSONObject(candidate)
            when {
                root.has("translations") -> normalizeTranslationList(root.optJSONArray("translations") ?: JSONArray(), sourceTexts)
                root.has("items") -> {
                    val items = root.optJSONArray("items") ?: JSONArray()
                    val out = MutableList(sourceTexts.size) { index -> sourceTexts[index] }
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val index = item.optInt("index", -1)
                        if (index in out.indices) {
                            out[index] = item.optString("text", out[index]).ifBlank { out[index] }
                        }
                    }
                    out
                }
                else -> {
                    Log.w(TAG, "translate_parse_fallback reason=missing_translations_key candidate=${candidate.take(120)}")
                    sourceTexts
                }
            }
        }.getOrElse { err ->
            Log.w(TAG, "translate_parse_fallback reason=parse_error err=${err.message?.take(120)} candidate=${candidate.take(120)}")
            sourceTexts
        }
    }

    private fun normalizeTranslationList(arr: JSONArray, sourceTexts: List<String>): List<String> {
        val out = MutableList(sourceTexts.size) { index -> sourceTexts[index] }
        for (i in 0 until minOf(arr.length(), out.size)) {
            val value = when (val item = arr.opt(i)) {
                is JSONObject -> item.optString("text", out[i])
                else -> item?.toString().orEmpty()
            }
            out[i] = value.ifBlank { out[i] }
        }
        return out
    }

    private fun parseGroups(raw: String, blocks: List<TextBlock>): List<TextBlockGroup> {
        val text = extractCompletionText(raw)
        val candidate = extractJsonCandidate(text)
        if (candidate.isBlank()) {
            return blocks.map { TextBlockGroup(bubbleId = it.id, blockIds = listOf(it.id)) }
        }
        val validBlockIds = blocks.map { it.id }.toSet()
        return runCatching {
            val root = if (candidate.trimStart().startsWith("[")) {
                JSONObject().put("groups", JSONArray(candidate))
            } else {
                JSONObject(candidate)
            }
            val groups = root.optJSONArray("groups")
                ?: root.optJSONArray("items")
                ?: JSONArray()
            val dedupUsedIds = hashSetOf<Int>()
            val out = mutableListOf<TextBlockGroup>()
            for (i in 0 until groups.length()) {
                val item = groups.optJSONObject(i) ?: continue
                val bubbleId = item.optInt("bubbleId", i + 1)
                val ids = item.optJSONArray("blockIds")
                    ?: item.optJSONArray("block_ids")
                    ?: JSONArray()
                val accepted = mutableListOf<Int>()
                for (j in 0 until ids.length()) {
                    val id = ids.optInt(j, -1)
                    if (id in validBlockIds && dedupUsedIds.add(id)) {
                        accepted += id
                    }
                }
                if (accepted.isNotEmpty()) {
                    out += TextBlockGroup(bubbleId = bubbleId, blockIds = accepted)
                }
            }
            if (out.isEmpty()) {
                blocks.map { TextBlockGroup(bubbleId = it.id, blockIds = listOf(it.id)) }
            } else {
                val uncovered = blocks
                    .map { it.id }
                    .filterNot { dedupUsedIds.contains(it) }
                val padded = out.toMutableList()
                uncovered.forEach { id ->
                    padded += TextBlockGroup(bubbleId = id, blockIds = listOf(id))
                }
                padded
            }
        }.getOrElse {
            blocks.map { TextBlockGroup(bubbleId = it.id, blockIds = listOf(it.id)) }
        }
    }

    private fun extractCompletionText(raw: String): String {
        val payload = if (raw.isNotBlank()) JSONObject(raw) else JSONObject()
        val content = payload
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.opt("content")
        val text = extractContentText(content).trim()
        if (text.isBlank()) throw IOException("OpenAI-compatible response is empty")
        return text
    }

    private fun extractJsonCandidate(rawText: String): String {
        val input = rawText.trim()
        if (input.isBlank()) return ""
        val fencedBlocks = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
            .findAll(input)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
            .filter { it.isNotBlank() }
            .toList()

        val sources = buildList {
            addAll(fencedBlocks)
            add(input)
        }
        for (source in sources) {
            val direct = source.trim()
            if (isJsonObjectOrArray(direct)) {
                return direct
            }
            val balanced = extractBalancedJsonCandidates(direct)
            val preferred = balanced.firstOrNull { candidate ->
                val lower = candidate.lowercase()
                lower.contains("\"bubbles\"") || lower.contains("\"translations\"") || lower.contains("\"items\"")
            }
            if (preferred != null) return preferred
            val firstValid = balanced.firstOrNull()
            if (firstValid != null) return firstValid
        }
        return ""
    }

    private fun isResponseFormatUnsupported(errorText: String): Boolean {
        val lower = errorText.lowercase()
        return lower.contains("response_format") &&
            (lower.contains("not supported") || lower.contains("not valid") || lower.contains("unsupported"))
    }

    private fun isThinkingUnsupported(errorText: String): Boolean {
        val lower = errorText.lowercase()
        if (!(lower.contains("enable_thinking") || lower.contains("thinking_budget"))) return false
        return lower.contains("not supported") ||
            lower.contains("not valid") ||
            lower.contains("unsupported") ||
            lower.contains("unknown")
    }

    private fun parseModelJson(
        rawText: String,
        imageWidth: Int,
        imageHeight: Int
    ): TranslationResult {
        val candidate = extractJsonCandidate(rawText)
        if (candidate.isBlank()) {
            throw IOException("Model output does not contain valid JSON")
        }

        val trimmedCandidate = candidate.trimStart()
        var rootObject: JSONObject? = null
        val source = runCatching {
            if (trimmedCandidate.startsWith("[")) {
                JSONArray(trimmedCandidate)
            } else {
                val root = JSONObject(trimmedCandidate)
                rootObject = root
                root.optJSONArray("bubbles")
                    ?: root.optJSONObject("data")?.optJSONArray("bubbles")
                    ?: root.optJSONArray("items")
                    ?: JSONArray()
            }
        }.getOrElse { parseErr ->
            throw IOException(
                "Model JSON parse failed: ${parseErr.message}. candidate=${candidate.take(180)}"
            )
        }

        val rawBubbles = mutableListOf<Bubble>()
        var maxCoordValue = 0f
        var maxRight = 0f
        var maxBottom = 0f
        var contractViolation = false
        var centerHintDetected = false
        var pixelHintDetected = false
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            val x = item.optDouble("x", item.optDouble("left", 0.0)).toFloat()
            val y = item.optDouble("y", item.optDouble("top", 0.0)).toFloat()
            val w = item.optDouble("w", item.optDouble("width", 0.0)).toFloat()
            val h = item.optDouble("h", item.optDouble("height", 0.0)).toFloat()
            val contentX = item.optDouble("content_x", item.optDouble("contentX", x.toDouble())).toFloat()
            val contentY = item.optDouble("content_y", item.optDouble("contentY", y.toDouble())).toFloat()
            val contentW = item.optDouble("content_w", item.optDouble("contentW", w.toDouble())).toFloat()
            val contentH = item.optDouble("content_h", item.optDouble("contentH", h.toDouble())).toFloat()
            maxCoordValue = max(maxCoordValue, max(max(x, y), max(w, h)))
            maxCoordValue = max(maxCoordValue, max(max(contentX, contentY), max(contentW, contentH)))
            maxRight = max(maxRight, x + w)
            maxBottom = max(maxBottom, y + h)
            if (x !in 0f..1000f || y !in 0f..1000f || w !in 0f..1000f || h !in 0f..1000f) {
                contractViolation = true
            }
            val origin = item.optString("coord_origin")
                .ifBlank { item.optString("coordinate_origin") }
                .ifBlank { item.optString("origin") }
                .trim()
                .lowercase()
            if (origin == "center" || item.optBoolean("is_center", false)) {
                centerHintDetected = true
                contractViolation = true
            }
            if (
                x > 1000f || y > 1000f || w > 1000f || h > 1000f ||
                max(maxRight, maxBottom) > 1000f
            ) {
                pixelHintDetected = true
            }
            rawBubbles += Bubble(
                x = x,
                y = y,
                w = w,
                h = h,
                bgType = normalizeBg(item.optString("bg_type", "solid")),
                originalText = item.optString("original_text", "").trim(),
                translatedText = item.optString("translated_text", "").trim(),
                layout = normalizeLayout(item.optString("layout", "horizontal")),
                contentX = contentX,
                contentY = contentY,
                contentW = contentW,
                contentH = contentH
            )
        }

        if (rawBubbles.isEmpty()) {
            Log.d(TAG, "parse_bubbles_count=0")
            return TranslationResult(emptyList())
        }

        val rootOrigin = rootObject?.optString("coord_origin")
            ?.ifBlank { rootObject?.optString("coordinate_origin") }
            ?.trim()
            ?.lowercase()
            .orEmpty()
        if (rootOrigin == "center") {
            centerHintDetected = true
            contractViolation = true
        }
        if (maxCoordValue > 1000f || max(maxRight, maxBottom) > 1000f) {
            pixelHintDetected = true
            contractViolation = true
        }

        val normalized = rawBubbles.mapNotNull { bubble ->
            val nx = clamp((bubble.x / 1000f) * 100f, 0f, 100f)
            val ny = clamp((bubble.y / 1000f) * 100f, 0f, 100f)
            val nw = clamp((bubble.w / 1000f) * 100f, 0f, 100f)
            val nh = clamp((bubble.h / 1000f) * 100f, 0f, 100f)
            val contentNx = clamp((bubble.contentX / 1000f) * 100f, 0f, 100f)
            val contentNy = clamp((bubble.contentY / 1000f) * 100f, 0f, 100f)
            val contentNw = clamp((bubble.contentW / 1000f) * 100f, 0f, 100f)
            val contentNh = clamp((bubble.contentH / 1000f) * 100f, 0f, 100f)
            if (nw <= 0f || nh <= 0f) return@mapNotNull null
            Bubble(
                x = nx,
                y = ny,
                w = nw,
                h = nh,
                bgType = bubble.bgType,
                originalText = bubble.originalText,
                translatedText = bubble.translatedText.ifBlank { bubble.originalText },
                layout = normalizeLayout(bubble.layout),
                contentX = contentNx,
                contentY = contentNy,
                contentW = contentNw.coerceAtLeast(0.1f),
                contentH = contentNh.coerceAtLeast(0.1f)
            )
        }
        val deduped = dedupeHighlyOverlappingBubbles(normalized)

        Log.i(TAG, "coord_strategy_selected mode=normalized_1000 origin=top_left")
        if (contractViolation) {
            Log.w(
                TAG,
                "coord_contract_violation max=${maxCoordValue.toInt()} right=${maxRight.toInt()} bottom=${maxBottom.toInt()} " +
                    "center_hint=$centerHintDetected pixel_hint=$pixelHintDetected"
            )
        }

        Log.d(
            TAG,
            "parse_bubbles_count=${deduped.size} raw_count=${normalized.size} coord_mode=normalized_1000 origin=top_left " +
                "raw_max=${maxCoordValue.toInt()} raw_right=${maxRight.toInt()} raw_bottom=${maxBottom.toInt()} " +
                "image_w=${imageWidth.coerceAtLeast(1)} image_h=${imageHeight.coerceAtLeast(1)}"
        )
        return TranslationResult(deduped)
    }

    private fun dedupeHighlyOverlappingBubbles(bubbles: List<Bubble>): List<Bubble> {
        if (bubbles.size <= 1) return bubbles
        val sorted = bubbles.sortedByDescending { it.w * it.h }
        val deduped = mutableListOf<Bubble>()
        sorted.forEach { current ->
            val duplicate = deduped.any { kept -> intersectionRatio(current, kept) >= 0.85f }
            if (!duplicate) deduped += current
        }
        return deduped
    }

    private fun intersectionRatio(a: Bubble, b: Bubble): Float {
        val ax1 = a.x
        val ay1 = a.y
        val ax2 = a.x + a.w
        val ay2 = a.y + a.h
        val bx1 = b.x
        val by1 = b.y
        val bx2 = b.x + b.w
        val by2 = b.y + b.h
        val interW = minOf(ax2, bx2) - maxOf(ax1, bx1)
        val interH = minOf(ay2, by2) - maxOf(ay1, by1)
        if (interW <= 0f || interH <= 0f) return 0f
        val interArea = interW * interH
        val minArea = minOf(a.w * a.h, b.w * b.h).coerceAtLeast(1e-3f)
        return interArea / minArea
    }

    private fun buildOcrPrompt(ignoreSimplifiedChinese: Boolean, imageWidth: Int, imageHeight: Int): String {
        val lines = mutableListOf(
            "You are a manga OCR detector.",
            "Detect all readable speech/narration text and localization boxes from the image.",
            "Return JSON only. No markdown, no explanation.",
            "The input image resolution is ${imageWidth}x${imageHeight}.",
            "Output schema:",
            "{\"bubbles\":[{\"x\":0-1000,\"y\":0-1000,\"w\":0-1000,\"h\":0-1000,\"bg_type\":\"solid|transparent|none\",\"layout\":\"vertical|horizontal\",\"original_text\":\"...\",\"translated_text\":\"\"}]}",
            "If there is no text, return exactly: {\"bubbles\":[]}",
            "x and y MUST be the top-left corner of the box. Never return center-point coordinates.",
            "x, y, w, h MUST use normalized 0-1000 coordinates relative to the full image.",
            "Do not return 0-100 percentages.",
            "Do not return absolute pixel coordinates.",
            "Do not return center-origin coordinates.",
            "bg_type must be one of: solid, transparent, none.",
            "layout must be one of: vertical, horizontal.",
            "Detect vertical Japanese text and set layout=vertical when detected."
        )
        if (ignoreSimplifiedChinese) lines += "Important: Ignore already Simplified Chinese text."
        return lines.joinToString("\n")
    }

    private fun buildTranslatePrompt(count: Int, targetLanguageId: String, glossary: List<Pair<String, String>> = emptyList()): String {
        val targetName = when (targetLanguageId.lowercase()) {
            "zh-cn", "zh" -> "Simplified Chinese"
            "zh-tw" -> "Traditional Chinese"
            "en" -> "English"
            "ko" -> "Korean"
            "ja" -> "Japanese"
            else -> "Simplified Chinese"
        }
        val lines = mutableListOf(
            "Translate each input text into $targetName.",
            "Keep order strictly the same as input index.",
            "Return JSON only with schema: {\"translations\":[\"t0\",\"t1\", ...]}",
            "The translations array length must equal input length = $count.",
            "Do not include explanations."
        )
        if (glossary.isNotEmpty()) {
            buildGlossaryLine(glossary)?.let { lines += it }
        }
        return lines.joinToString("\n")
    }

    internal fun buildGlossaryLine(glossary: List<Pair<String, String>>): String? {
        val normalized = glossary
            .map { (source, target) -> source.trim() to target.trim() }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
        if (normalized.isEmpty()) return null
        val mappings = normalized
            .map { (source, target) -> "$source=$target" }
            .joinToString("; ")
        return "Use the following glossary mappings strictly when the source terms appear: $mappings"
    }

    private fun buildGroupPrompt(blocks: List<TextBlock>): String {
        val payload = JSONArray().apply {
            blocks.forEach { block ->
                put(
                    JSONObject()
                        .put("id", block.id)
                        .put("text", block.text)
                        .put("bbox", JSONObject()
                            .put("left", block.left)
                            .put("top", block.top)
                            .put("right", block.right)
                            .put("bottom", block.bottom))
                )
            }
        }
        return listOf(
            "Group text blocks that belong to the same speech bubble.",
            "Return JSON only with schema: {\"groups\":[{\"bubbleId\":1,\"blockIds\":[0,1]}]}",
            "Every input block id must appear in exactly one group.",
            "Do not change block ids, do not add explanations.",
            "INPUT_BLOCKS_JSON=$payload"
        ).joinToString("\n")
    }

    private fun normalizeBaseUrl(value: String): String {
        var normalized = value.trim().trimEnd('/')
        normalized = normalized.replace(Regex("/chat/completions$", RegexOption.IGNORE_CASE), "")
        normalized = normalized.replace(Regex("/responses$", RegexOption.IGNORE_CASE), "")
        if (normalized.isBlank()) throw IOException("Base URL is empty")
        return normalized
    }

    private fun normalizeBg(value: String): String {
        return when (value.lowercase()) {
            "solid", "transparent", "none" -> value.lowercase()
            else -> "solid"
        }
    }

    private fun normalizeLayout(value: String): String {
        return when (value.lowercase()) {
            "vertical", "horizontal" -> value.lowercase()
            else -> "horizontal"
        }
    }

    private fun clamp(value: Float, min: Float, max: Float): Float = value.coerceIn(min, max)

    private fun extractContentText(content: Any?): String {
        return when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    val item = content.opt(i)
                    when (item) {
                        is String -> append(item)
                        is JSONObject -> append(item.optString("text", ""))
                    }
                    if (i < content.length() - 1) append('\n')
                }
            }
            else -> ""
        }
    }

    private fun extractError(raw: String): String {
        return try {
            val obj = JSONObject(raw)
            obj.optJSONObject("error")?.optString("message").orEmpty().ifBlank {
                obj.optString("message", "")
            }
        } catch (_: Throwable) {
            raw
        }
    }

    private fun isJsonObjectOrArray(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return runCatching {
            if (trimmed.startsWith("{")) {
                JSONObject(trimmed)
            } else if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                return false
            }
            true
        }.getOrDefault(false)
    }

    private fun extractBalancedJsonCandidates(text: String): List<String> {
        val candidates = mutableListOf<String>()
        val length = text.length
        var i = 0
        while (i < length) {
            val startChar = text[i]
            if (startChar != '{' && startChar != '[') {
                i++
                continue
            }
            val endIndex = findBalancedEnd(text, i, startChar)
            if (endIndex > i) {
                val candidate = text.substring(i, endIndex + 1).trim()
                if (isJsonObjectOrArray(candidate)) {
                    candidates += candidate
                }
                i = endIndex + 1
            } else {
                i++
            }
        }
        return candidates
    }

    private fun findBalancedEnd(text: String, startIndex: Int, startChar: Char): Int {
        val endChar = if (startChar == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in startIndex until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\') {
                    escaped = true
                    continue
                }
                if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                startChar -> depth += 1
                endChar -> {
                    depth -= 1
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private data class ApiResult(
        val isSuccess: Boolean,
        val raw: String,
        val error: String?
    )

    private data class BaiduAccessTokenCache(
        val credentialFingerprint: String,
        val token: String,
        val expiresAtElapsedMs: Long
    )

    private data class BaiduOcrRequestResult(
        val raw: String,
        val language: String,
        val costMs: Long
    )

private companion object {
        const val TAG = "VisionClient"
        const val READ_TIMEOUT_SECONDS = 120L
        const val MAX_OUTPUT_TOKENS = 2048
        const val BAIDU_PROVIDER_ID = "baidu"
        const val BAIDU_DEFAULT_HOST = "https://aip.baidubce.com"
        const val BAIDU_DEFAULT_LANGUAGE = "JAP"
        const val ENABLE_BAIDU_NEARBY_MERGE = true
        const val ENABLE_BAIDU_HEURISTIC_MERGE = true
        const val BAIDU_NORMALIZED_SPACE = 100
        val BAIDU_CREDENTIAL_SEPARATORS = listOf("|", ":", ",", ";", "\n")
        val BAIDU_LANGUAGE_TYPES = setOf("JAP", "CHN_ENG", "ENG", "KOR")
        val SIMPLIFIED_MARKERS = setOf(
            '\u8FD9', '\u4E2A', '\u4EEC', '\u7ECF', '\u4E0E', '\u4E3A', '\u53D1', '\u540E',
            '\u91CC', '\u8BF4', '\u8BED', '\u6765', '\u5F00', '\u65F6', '\u95F4', '\u56FD',
            '\u5173', '\u5B9E', '\u5F53', '\u7EBF', '\u70B9', '\u6837', '\u73B0', '\u5934',
            '\u5BF9', '\u6C14', '\u52A8', '\u4F1A'
        )
    }
}
