package com.manga.translator.translate

import android.os.SystemClock
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max

class OpenAiVisionClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    fun detectBubbles(
        provider: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        dataUrl: String,
        imageWidth: Int,
        imageHeight: Int,
        ignoreSimplifiedChinese: Boolean,
        enableThinking: Boolean,
        thinkingBudget: Int
    ): TranslationResult {
        val endpoint = normalizeBaseUrl(baseUrl) + "/chat/completions"
        val traceId = "ocr-" + SystemClock.elapsedRealtime().toString()
        val providerId = provider.trim().lowercase()
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

    fun translateTexts(
        provider: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        texts: List<String>,
        enableThinking: Boolean,
        thinkingBudget: Int
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        val endpoint = normalizeBaseUrl(baseUrl) + "/chat/completions"
        val traceId = "tr-" + SystemClock.elapsedRealtime().toString()
        val providerId = provider.trim().lowercase()
        val thinkingEnabled = enableThinking && (providerId == "qwen" || providerId == "glm")
        val normalizedThinkingBudget = thinkingBudget.coerceIn(1024, 131072)
        val prompt = buildTranslatePrompt(texts.size)

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
        if (candidate.isBlank()) return sourceTexts
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
                else -> sourceTexts
            }
        }.getOrElse { sourceTexts }
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
            maxCoordValue = max(maxCoordValue, max(max(x, y), max(w, h)))
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
                layout = normalizeLayout(item.optString("layout", "horizontal"))
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
            if (nw <= 0f || nh <= 0f) return@mapNotNull null
            Bubble(
                x = nx,
                y = ny,
                w = nw,
                h = nh,
                bgType = bubble.bgType,
                originalText = bubble.originalText,
                translatedText = bubble.translatedText.ifBlank { bubble.originalText },
                layout = normalizeLayout(bubble.layout)
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

    private fun buildTranslatePrompt(count: Int): String {
        return listOf(
            "Translate each input text into Simplified Chinese.",
            "Keep order strictly the same as input index.",
            "Return JSON only with schema: {\"translations\":[\"t0\",\"t1\", ...]}",
            "The translations array length must equal input length = $count.",
            "Do not include explanations."
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

    private companion object {
        const val TAG = "VisionClient"
    }
}
