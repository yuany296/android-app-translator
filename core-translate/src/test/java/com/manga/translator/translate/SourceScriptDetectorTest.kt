package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 源语言脚本检测（2026-10-14「网站语言自动识别」）。 */
class SourceScriptDetectorTest {

    @Test
    fun detectsKorean() {
        val detected = SourceScriptDetector.detect(listOf("케이지라고요!!", "접속종료를 하려면 죽거나"))
        assertEquals("ko", detected?.languageId)
        assert((detected?.scriptChars ?: 0) >= 2)
    }

    @Test
    fun detectsJapaneseByKana() {
        // 日文页的汉字不抢判：假名是强特征，优先级 ko > ja > zh。
        val detected = SourceScriptDetector.detect(listOf("なんだこれは", "田村か?"))
        assertEquals("ja", detected?.languageId)
    }

    @Test
    fun detectsSimplifiedChinese() {
        val detected = SourceScriptDetector.detect(listOf("这是简体中文的台词", "我们回家吧"))
        assertEquals("zh-cn", detected?.languageId)
    }

    @Test
    fun detectsTraditionalChinese() {
        val detected = SourceScriptDetector.detect(listOf("這是繁體中文的台詞", "我們回家吧"))
        assertEquals("zh-tw", detected?.languageId)
    }

    @Test
    fun koreanBeatsHanOnKoreanPage() {
        // 韩文页里偶尔的汉字（漢字）不能把语言带偏。
        val detected = SourceScriptDetector.detect(listOf("この言葉는 韓國語입니다 아니다", "케이지라고요"))
        assertEquals("ko", detected?.languageId)
    }

    @Test
    fun latinNeedsSixLetters() {
        assertEquals("en", SourceScriptDetector.detect(listOf("Hello world this is fine"))?.languageId)
        // OCR 噪声（cYa / S / O 这类）不该判成英文。
        assertNull(SourceScriptDetector.detect(listOf("cYa", "S", "O")))
    }

    @Test
    fun insufficientEvidenceReturnsNull() {
        assertNull(SourceScriptDetector.detect(emptyList()))
        assertNull(SourceScriptDetector.detect(listOf("", "  ")))
        assertNull(SourceScriptDetector.detect(listOf("12345", "...!?")))
        // 单个谚文字符不够（噪声线）。
        assertNull(SourceScriptDetector.detect(listOf("큭")))
    }
}
