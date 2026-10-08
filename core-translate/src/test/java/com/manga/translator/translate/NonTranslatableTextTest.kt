package com.manga.translator.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语言中立文本过滤（跳过翻译）单测。
 * 仅覆盖「无论源语言/目标语言如何，原样展示即正确」的内容。
 */
class NonTranslatableTextTest {

    private fun skip(text: String) = NonTranslatableText.isNonTranslatable(text)

    @Test
    fun pureNumbers() {
        assertTrue(skip("123"))
        assertTrue(skip("0"))
        assertTrue(skip("12,345"))
        assertTrue(skip("1,000,000"))
        assertTrue(skip("1.5"))
        assertTrue(skip("-3"))
        assertTrue(skip("+7"))
        assertTrue(skip("100%"))
        assertTrue(skip("12.5%"))
        assertTrue(skip("3.14"))
        assertTrue(skip("１００"))
        assertTrue(skip("1 000"))
    }

    @Test
    fun clockTimes() {
        assertTrue(skip("3:30"))
        assertTrue(skip("12:00"))
        assertTrue(skip("09:45"))
        assertTrue(skip("24:00"))
        assertTrue(skip("9:30:15"))
        assertTrue(skip("3:30pm"))
        assertTrue(skip("3:30 PM"))
        assertTrue(skip("3：30"))
    }

    @Test
    fun digitDates() {
        assertTrue(skip("2024/1/1"))
        assertTrue(skip("2024-01-01"))
        assertTrue(skip("12.25"))
        assertTrue(skip("1/1"))
        assertTrue(skip("3-1-1"))
    }

    @Test
    fun punctuationAndEmoji() {
        assertTrue(skip("!!!"))
        assertTrue(skip("???"))
        assertTrue(skip("……"))
        assertTrue(skip("😊😊"))
        assertTrue(skip("(^_^)"))
        assertTrue(skip("☆★"))
    }

    @Test
    fun alnumCodes() {
        assertTrue(skip("No.5"))
        assertTrue(skip("b-12"))
        assertTrue(skip("vol.3"))
        assertTrue(skip("4k"))
        assertTrue(skip("a1"))
        assertTrue(skip("3d"))
        assertTrue(skip("ep7"))
        assertTrue(skip("B2"))
    }

    @Test
    fun urlsAndEmails() {
        assertTrue(skip("https://example.com/x"))
        assertTrue(skip("www.example.com"))
        assertTrue(skip("user@mail.com"))
        assertTrue(skip("abc.net"))
    }

    @Test
    fun repeatedCharNoise() {
        assertTrue(skip("!!!!"))
        assertTrue(skip("IIII"))
        assertTrue(skip("0000"))
        assertTrue(skip("ーーーー"))
    }

    @Test
    fun translatableTextsAreNotSkipped() {
        assertFalse(skip("午前8時"))
        assertFalse(skip("第3話"))
        assertFalse(skip("12時30分"))
        assertFalse(skip("100万円"))
        assertFalse(skip("50万"))
        assertFalse(skip("3人"))
        assertFalse(skip("ドン！"))
        assertFalse(skip("こんにちは"))
        assertFalse(skip("hello world"))
        assertFalse(skip("3:00〜"))
        assertFalse(skip("ニューゲーム！"))
        assertFalse(skip("ぎゃぎゃぎゃ"))
        assertFalse(skip("ドドド"))
        assertFalse(skip("ああああ"))
        assertFalse(skip("abab"))
        assertFalse(skip("1.5x"))
        assertFalse(skip("第1話 完結"))
        assertFalse(skip("おはよう"))
    }
}
