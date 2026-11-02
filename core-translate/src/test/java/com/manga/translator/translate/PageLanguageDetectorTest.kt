package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页语言判定 + 自动源语言解析（2026-11-01）。
 *
 * 这里只测纯逻辑；ML Kit 的真实识别在 app 的 androidTest 探针里跑。
 */
class PageLanguageDetectorTest {

    private val japanese = "これは日本語の本文です。今日はいい天気ですね。".repeat(3)
    private val korean = "이것은 한국어로 작성된 본문입니다. 오늘은 날씨가 좋네요. ".repeat(3)
    private val chinese = "这是一个中文页面，包含足够多的汉字用于语言识别测试。".repeat(3)
    private val traditionalChinese = "這是一個繁體中文頁面，包含足夠多的漢字用於語言識別測試。".repeat(3)
    private val english = "This is an English page with enough words to identify the language reliably."

    @Test
    fun contentWinsOverDeclaredHint() {
        val detected = PageLanguageDetector.detect(
            sampleText = japanese,
            hints = listOf("en"),
            candidates = listOf(
                LanguageCandidate("en", 0.90f),
                LanguageCandidate("ja", 0.05f)
            )
        )
        assertEquals("ja", detected?.languageId)
        assertEquals(PageLanguageDetector.Source.CONTENT, detected?.source)
        assertTrue(detected?.reliable == true)
    }

    @Test
    fun koreanContentWinsEvenWhenMlKitSaysEnglish() {
        val detected = PageLanguageDetector.detect(
            sampleText = korean,
            hints = listOf("en-US"),
            candidates = listOf(
                LanguageCandidate("en", 0.80f),
                LanguageCandidate("ko", 0.10f)
            )
        )
        assertEquals("ko", detected?.languageId)
        assertEquals(PageLanguageDetector.Source.CONTENT, detected?.source)
        assertTrue(detected?.reliable == true)
    }

    @Test
    fun declaredHintIsOnlyFallbackAndNeverReliable() {
        val detected = PageLanguageDetector.detect(
            sampleText = "",
            hints = listOf("ja-JP"),
            candidates = emptyList()
        )
        assertEquals("ja", detected?.languageId)
        assertEquals(PageLanguageDetector.Source.DECLARED, detected?.source)
        assertFalse(detected?.reliable == true)
        assertEquals(0, detected?.sampleChars)
    }

    @Test
    fun shortScriptEvidenceIsDetectedButNotReliable() {
        val detected = PageLanguageDetector.detect(
            sampleText = "これは日本語",
            hints = emptyList(),
            candidates = emptyList()
        )
        assertEquals("ja", detected?.languageId)
        assertEquals(PageLanguageDetector.Source.CONTENT, detected?.source)
        assertFalse(detected?.reliable == true)
    }

    @Test
    fun mlKitChineseCandidateUsesTraditionalVariant() {
        val detected = PageLanguageDetector.detect(
            sampleText = traditionalChinese,
            hints = listOf("en"),
            candidates = listOf(LanguageCandidate("zh", 0.95f))
        )
        assertEquals("zh-tw", detected?.languageId)
        assertEquals(PageLanguageDetector.Source.CONTENT, detected?.source)
        assertTrue(detected?.reliable == true)
    }

    @Test
    fun chineseCandidateWithoutTraditionalCharsIsSimplified() {
        val detected = PageLanguageDetector.detect(
            sampleText = chinese,
            hints = emptyList(),
            candidates = listOf(LanguageCandidate("zh", 0.95f))
        )
        assertEquals("zh-cn", detected?.languageId)
    }

    @Test
    fun unsupportedTopCandidateDoesNotGuessEnglishFromLatinScript() {
        val french = "Ceci est une page en français avec assez de mots pour identifier la langue."
        val detected = PageLanguageDetector.detect(
            sampleText = french,
            hints = emptyList(),
            candidates = listOf(
                LanguageCandidate("fr", 0.92f),
                LanguageCandidate("en", 0.04f)
            )
        )
        assertNull(detected)
    }

    @Test
    fun reliableRequiresConfidenceMargin() {
        val ambiguous = PageLanguageDetector.detect(
            sampleText = english,
            hints = emptyList(),
            candidates = listOf(
                LanguageCandidate("en", 0.60f),
                LanguageCandidate("fr", 0.55f)
            )
        )
        assertEquals("en", ambiguous?.languageId)
        assertFalse(ambiguous?.reliable == true)

        val clear = PageLanguageDetector.detect(
            sampleText = english,
            hints = emptyList(),
            candidates = listOf(
                LanguageCandidate("en", 0.90f),
                LanguageCandidate("fr", 0.05f)
            )
        )
        assertEquals("en", clear?.languageId)
        assertTrue(clear?.reliable == true)
    }

    @Test
    fun scriptFallbackWorksWithoutMlKitCandidates() {
        val detected = PageLanguageDetector.detect(
            sampleText = korean,
            hints = emptyList(),
            candidates = emptyList()
        )
        assertEquals("ko", detected?.languageId)
        assertTrue(detected?.reliable == true)
    }

    @Test
    fun latinScriptFallbackIsEnglishWhenMlKitUnavailable() {
        val detected = PageLanguageDetector.detect(
            sampleText = english,
            hints = emptyList(),
            candidates = emptyList()
        )
        assertEquals("en", detected?.languageId)
        assertTrue(detected?.reliable == true)
    }

    @Test
    fun normalizeHintHandlesBcp47AndRejectsUnsupported() {
        assertEquals("ja", PageLanguageDetector.normalizeHint("ja-JP"))
        assertEquals("ko", PageLanguageDetector.normalizeHint("ko_KR"))
        assertEquals("en", PageLanguageDetector.normalizeHint("en-US"))
        assertEquals("zh-cn", PageLanguageDetector.normalizeHint("zh-Hans"))
        assertEquals("zh-cn", PageLanguageDetector.normalizeHint("zh-CN"))
        assertEquals("zh-tw", PageLanguageDetector.normalizeHint("zh-Hant"))
        assertEquals("zh-tw", PageLanguageDetector.normalizeHint("zh-TW"))
        assertEquals("ja", PageLanguageDetector.normalizeHint("ja-JP, en;q=0.8"))
        assertNull(PageLanguageDetector.normalizeHint(""))
        assertNull(PageLanguageDetector.normalizeHint("und"))
        assertNull(PageLanguageDetector.normalizeHint("fr-FR"))
    }

    @Test
    fun normalizeDetectedTagHandlesLatnAndUnd() {
        assertEquals("en", PageLanguageDetector.normalizeDetectedTag("ja-Latn", japanese))
        assertEquals("zh-tw", PageLanguageDetector.normalizeDetectedTag("zh", traditionalChinese))
        assertEquals("zh-cn", PageLanguageDetector.normalizeDetectedTag("zh", chinese))
        assertNull(PageLanguageDetector.normalizeDetectedTag("und"))
        assertNull(PageLanguageDetector.normalizeDetectedTag("fr"))
    }

    @Test
    fun resolveSourcePrefersConfiguredThenPageThenFallback() {
        val page = PageLanguageDetector.Detected(
            languageId = "ja",
            source = PageLanguageDetector.Source.CONTENT,
            reliable = true,
            sampleChars = 120
        )
        assertEquals(
            PageLanguageDetector.Resolved("ko", PageLanguageDetector.Origin.CONFIGURED),
            PageLanguageDetector.resolveSource("ko", page, "en")
        )
        assertEquals(
            PageLanguageDetector.Resolved("ja", PageLanguageDetector.Origin.PAGE),
            PageLanguageDetector.resolveSource(PageLanguageDetector.AUTO, page, "en")
        )
        assertEquals(
            PageLanguageDetector.Resolved("en", PageLanguageDetector.Origin.FALLBACK),
            PageLanguageDetector.resolveSource(PageLanguageDetector.AUTO, null, "en")
        )
    }

    @Test
    fun declaredPageLanguageIsReportedAsDeclaredOrigin() {
        val page = PageLanguageDetector.Detected(
            languageId = "ja",
            source = PageLanguageDetector.Source.DECLARED,
            reliable = false,
            sampleChars = 0
        )
        assertEquals(
            PageLanguageDetector.Resolved("ja", PageLanguageDetector.Origin.DECLARED),
            PageLanguageDetector.resolveSource(PageLanguageDetector.AUTO, page, "en")
        )
    }

    @Test
    fun sameLanguageNormalizesChineseVariants() {
        assertTrue(PageLanguageDetector.sameLanguage("zh-cn", "zh-Hans"))
        assertTrue(PageLanguageDetector.sameLanguage("zh-tw", "zh-Hant"))
        assertTrue(PageLanguageDetector.sameLanguage("ja", "ja-JP"))
        assertFalse(PageLanguageDetector.sameLanguage("zh-cn", "zh-tw"))
        assertFalse(PageLanguageDetector.sameLanguage("ja", "ko"))
    }
}
