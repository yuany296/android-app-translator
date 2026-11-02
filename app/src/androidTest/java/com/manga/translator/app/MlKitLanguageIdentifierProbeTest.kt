package com.manga.translator.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.manga.translator.translate.MlKitLanguageIdentifier
import com.manga.translator.translate.PageLanguageDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ML Kit 语言识别探针（2026-11-01 网页自动检测源语言）。
 *
 * 验证 bundled `language-id` 在设备上真的可用，并确认四种源语言的 top candidate。
 * 过滤：`adb logcat -s MlKitLangId TestRunner`。
 */
@RunWith(AndroidJUnit4::class)
class MlKitLanguageIdentifierProbeTest {

    @Test
    fun identifiesJapanese() = assertTop("ja", "これは日本語の本文です。今日はいい天気ですね。".repeat(4))

    @Test
    fun identifiesKorean() = assertTop("ko", "이것은 한국어로 작성된 본문입니다. 오늘은 날씨가 좋네요. ".repeat(4))

    @Test
    fun identifiesEnglish() = assertTop(
        "en",
        "This is an English page with enough words to identify the language reliably. ".repeat(3)
    )

    @Test
    fun identifiesChinese() = assertTop("zh", "这是一个中文页面，包含足够多的汉字用于语言识别测试。".repeat(4))

    private fun assertTop(expected: String, text: String) = runBlocking {
        val candidates = MlKitLanguageIdentifier.identify(text)
        assertTrue("ML Kit 没有返回候选：$candidates", candidates.isNotEmpty())
        val top = PageLanguageDetector.normalizeDetectedTag(candidates.first().languageTag, text)
        assertEquals("候选=${candidates.take(3)}", normalizeForAssert(expected, text), top)
    }

    private fun normalizeForAssert(expected: String, text: String): String =
        if (expected == "zh") {
            PageLanguageDetector.normalizeDetectedTag("zh", text) ?: "zh-cn"
        } else {
            expected
        }
}
