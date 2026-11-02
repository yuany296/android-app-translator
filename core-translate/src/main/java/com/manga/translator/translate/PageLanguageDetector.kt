package com.manga.translator.translate

/**
 * 网页页面语言判定 + 自动源语言解析（2026-11-01）。
 *
 * 交互口径（用户 2026-11-01 定稿）：
 * - 首页源语言只有具体语言，没有「自动检测」；
 * - 网页里默认自动检测，底部语言条显示识别结果；
 * - 非网页链路（悬浮球截图）使用首页源语言。
 *
 * 判定顺序：
 * 1. 正文脚本强证据（谚文 → ko、假名 → ja）优先于 ML Kit 候选；
 * 2. ML Kit 候选里取支持的语言，置信度 ≥ [MIN_CONFIDENCE] 才采用；
 * 3. 正文样本不足/候选不可用时，用 **[hints]（`<html lang>` 等）** 兜底，标记为 [Source.DECLARED]；
 * 4. 仍判不出返回 null，由调用方回退到首页源语言。
 *
 * 可靠性（[Detected.reliable]）只用于一个决定：**页面语言 == 目标语言时跳过翻译**。
 * 声明语言（declared）永远不算 reliable —— Pixiv 这类 `<html lang="en">` + 日文正文的页面
 * 不能因为声明语言就整页跳过。
 *
 * 纯 Kotlin、无 Android 依赖，可 JVM 单测。
 */
data class LanguageCandidate(
    val languageTag: String,
    val confidence: Float
)

object PageLanguageDetector {

    /** 浏览器源语言设置里的自动检测值；与首页具体语言 id 区分。 */
    const val AUTO = "auto"

    /** 页面语言样本最小字数（对齐参考项目 CLD 的 40 字门槛）。 */
    const val MIN_SAMPLE_CHARS = 40

    /** 页面语言样本最大字数（只取前 2000 字，避免长页拖慢识别）。 */
    const val MAX_SAMPLE_CHARS = 2000

    /** ML Kit 候选采用门槛。 */
    const val MIN_CONFIDENCE = 0.5f

    /** top1 与次优候选的最小差距；差距太小不标 reliable。 */
    const val MIN_CONFIDENCE_MARGIN = 0.1f

    /** 页面里至少要有的脚本字符数，否则不猜。 */
    const val MIN_SCRIPT_CHARS = 6

    /** 强脚本判据：谚文至少 2 个且 ≥5% 脚本字符。 */
    const val HANGUL_MIN = 2

    /** 强脚本判据：假名至少 3 个且 ≥5% 脚本字符。 */
    const val KANA_MIN = 3

    /** 汉字兜底至少 2 个且 ≥5% 脚本字符。 */
    const val HAN_MIN = 2

    /** 拉丁兜底至少 6 个且 ≥50% 脚本字符。 */
    const val LATIN_MIN = 6

    /** 标 reliable 至少需要命中的脚本字符数。 */
    const val RELIABLE_SCRIPT_CHARS = 10

    /** ML Kit 置信度低于该值的未支持语言不阻止脚本兜底。 */
    const val MIN_UNSUPPORTED_CONFIDENCE = 0.3f

    enum class Source {
        /** 来自正文（ML Kit 候选或脚本兜底）。 */
        CONTENT,

        /** 来自 `<html lang>` / `content-language` / `og:locale`。 */
        DECLARED
    }

    data class Detected(
        val languageId: String,
        val source: Source,
        val reliable: Boolean,
        val sampleChars: Int,
        val hint: String? = null,
        val candidates: List<LanguageCandidate> = emptyList()
    )

    enum class Origin {
        /** 浏览器源语言设置是具体语言，直接使用。 */
        CONFIGURED,

        /** 自动检测采用了正文结果。 */
        PAGE,

        /** 自动检测采用了网页声明语言。 */
        DECLARED,

        /** 自动检测失败，回退到首页源语言。 */
        FALLBACK
    }

    data class Resolved(
        val languageId: String,
        val origin: Origin
    )

    /**
     * 综合正文样本、声明语言与 ML Kit 候选，得到页面语言。
     *
     * @param sampleText 页面主内容采样（JS 返回；最多取 [MAX_SAMPLE_CHARS]）
     * @param hints `<html lang>` / `content-language` / `og:locale` 等原始值
     * @param candidates ML Kit `identifyPossibleLanguages` 的结果；空表示识别失败/超时
     */
    fun detect(
        sampleText: String,
        hints: List<String>,
        candidates: List<LanguageCandidate> = emptyList()
    ): Detected? {
        val sample = sampleText.take(MAX_SAMPLE_CHARS)
        val sampleChars = sample.trim().length
        val hinted = hints.asSequence().mapNotNull { normalizeHint(it) }.firstOrNull()
        val content = detectContent(sample, candidates)
        if (content != null) {
            return Detected(
                languageId = content.languageId,
                source = Source.CONTENT,
                reliable = content.reliable,
                sampleChars = sampleChars,
                hint = hinted,
                candidates = candidates
            )
        }
        if (hinted != null) {
            return Detected(
                languageId = hinted,
                source = Source.DECLARED,
                reliable = false,
                sampleChars = sampleChars,
                hint = hinted,
                candidates = candidates
            )
        }
        return null
    }

    private data class ContentDetection(val languageId: String, val reliable: Boolean)

    private fun detectContent(
        sample: String,
        candidates: List<LanguageCandidate>
    ): ContentDetection? {
        val counts = SourceScriptDetector.countScripts(listOf(sample))
        val total = counts.total
        if (total < MIN_SCRIPT_CHARS) return null
        val enoughSample = sample.trim().length >= MIN_SAMPLE_CHARS

        // 谚文/假名是比 CLD 更硬的证据：韩漫夹汉字、日文页夹汉字时不会被抢判。
        if (counts.hangul >= HANGUL_MIN && counts.hangul * 20 >= total) {
            return ContentDetection(
                languageId = "ko",
                reliable = enoughSample && counts.hangul >= RELIABLE_SCRIPT_CHARS
            )
        }
        if (counts.kana >= KANA_MIN && counts.kana * 20 >= total) {
            return ContentDetection(
                languageId = "ja",
                reliable = enoughSample && counts.kana >= RELIABLE_SCRIPT_CHARS
            )
        }

        val sorted = candidates.sortedByDescending { it.confidence }
        val top = sorted.firstOrNull()
        val topSupported = sorted.firstOrNull { normalizeDetectedTag(it.languageTag, sample) != null }
        if (topSupported != null && topSupported.confidence >= MIN_CONFIDENCE) {
            val topIsSupported = top != null && top.languageTag == topSupported.languageTag
            val closeToTop = top == null || top.confidence - topSupported.confidence <= 0.05f
            if (topIsSupported || closeToTop) {
                val second = sorted.firstOrNull { it !== topSupported }?.confidence ?: 0f
                val reliable = enoughSample &&
                    topSupported.confidence - second >= MIN_CONFIDENCE_MARGIN
                val languageId = normalizeDetectedTag(topSupported.languageTag, sample)
                if (languageId != null) {
                    return ContentDetection(languageId, reliable)
                }
            }
        }

        // ML Kit 明确给出的是我们不支持的语言（法语/德语等）：不要用脚本硬猜成英文。
        if (top != null && top.confidence >= MIN_UNSUPPORTED_CONFIDENCE &&
            normalizeDetectedTag(top.languageTag, sample) == null
        ) {
            return null
        }

        // ML Kit 不可用/低置信时的脚本兜底。
        if (counts.han >= HAN_MIN && counts.han * 20 >= total) {
            return ContentDetection(
                languageId = SourceScriptDetector.chineseVariant(sample),
                reliable = enoughSample && counts.han >= RELIABLE_SCRIPT_CHARS
            )
        }
        if (counts.latin >= LATIN_MIN && counts.latin * 2 >= total) {
            return ContentDetection(
                languageId = "en",
                reliable = enoughSample && counts.latin >= RELIABLE_SCRIPT_CHARS
            )
        }
        return null
    }

    /** HTML 语言标签 → AppLanguage.id；`zh-Hans/zh-CN/zh-Hant/zh-TW` 归到简/繁。 */
    fun normalizeHint(raw: String): String? {
        val value = raw.trim().lowercase().replace('_', '-')
            .substringBefore(',').substringBefore(';').trim()
        if (value.isEmpty() || value == "und") return null
        return when {
            value.startsWith("zh") -> {
                if (value.contains("tw") || value.contains("hk") || value.contains("hant")) {
                    "zh-tw"
                } else {
                    "zh-cn"
                }
            }
            value.startsWith("ja") -> "ja"
            value.startsWith("ko") -> "ko"
            value.startsWith("en") -> "en"
            else -> null
        }
    }

    /**
     * ML Kit 语言标签 → AppLanguage.id。
     *
     * `*-Latn` 是「用拉丁字母写的 xx」，OCR 仍应按拉丁文字处理，统一落到 en；
     * `zh` 用页面文本里的繁体专用字区分简/繁。
     */
    fun normalizeDetectedTag(raw: String, sample: String = ""): String? {
        val value = raw.trim().lowercase().replace('_', '-')
        if (value.isEmpty() || value == "und") return null
        if (value.endsWith("-latn")) return "en"
        return when {
            value.startsWith("zh") -> SourceScriptDetector.chineseVariant(sample)
            value.startsWith("ja") -> "ja"
            value.startsWith("ko") -> "ko"
            value.startsWith("en") -> "en"
            else -> null
        }
    }

    /** 浏览器源语言解析：具体语言直接用；auto 按页面语言，失败回退首页语言。 */
    fun resolveSource(
        configuredLanguageId: String,
        pageLanguage: Detected?,
        fallbackLanguageId: String
    ): Resolved {
        if (!configuredLanguageId.equals(AUTO, ignoreCase = true)) {
            return Resolved(configuredLanguageId, Origin.CONFIGURED)
        }
        if (pageLanguage != null && pageLanguage.languageId.isNotBlank()) {
            return Resolved(
                languageId = pageLanguage.languageId,
                origin = if (pageLanguage.source == Source.CONTENT) Origin.PAGE else Origin.DECLARED
            )
        }
        val fallback = fallbackLanguageId.ifBlank { "en" }
        return Resolved(fallback, Origin.FALLBACK)
    }

    /** 简/繁、`zh-Hans` 等标签的同一语言判定。 */
    fun sameLanguage(first: String, second: String): Boolean =
        normalizeLanguageId(first) == normalizeLanguageId(second)

    fun normalizeLanguageId(value: String): String {
        val normalized = value.trim().lowercase().replace('_', '-')
        return when {
            normalized.startsWith("zh") -> {
                if (normalized.contains("tw") || normalized.contains("hk") ||
                    normalized.contains("hant")
                ) {
                    "zh-tw"
                } else {
                    "zh-cn"
                }
            }
            normalized.startsWith("ja") -> "ja"
            normalized.startsWith("ko") -> "ko"
            normalized.startsWith("en") -> "en"
            else -> normalized
        }
    }
}
