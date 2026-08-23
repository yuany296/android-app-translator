package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Test

class GlossaryCsvTest {

    @Test
    fun parseWithHeader() {
        val csv = "source,target\nEren,艾伦\nLevi,利威尔"
        val result = GlossaryRepository.GlossaryCsv.parse(csv)
        assertEquals(listOf("Eren" to "艾伦", "Levi" to "利威尔"), result)
    }

    @Test
    fun parseWithoutHeader() {
        val csv = "Eren,艾伦\nLevi,利威尔"
        val result = GlossaryRepository.GlossaryCsv.parse(csv)
        assertEquals(listOf("Eren" to "艾伦", "Levi" to "利威尔"), result)
    }

    @Test
    fun parseWithChineseHeader() {
        val csv = "原文,译文\nEren,艾伦\nLevi,利威尔"
        val result = GlossaryRepository.GlossaryCsv.parse(csv)
        assertEquals(listOf("Eren" to "艾伦", "Levi" to "利威尔"), result)
    }

    @Test
    fun parseSkipsBlankRows() {
        val csv = "source,target\n\nEren,艾伦\n\n"
        val result = GlossaryRepository.GlossaryCsv.parse(csv)
        assertEquals(listOf("Eren" to "艾伦"), result)
    }

    @Test
    fun parseQuotedComma() {
        val csv = "\"Eren, Yeager\",\"艾伦·耶格尔\"\nLevi,利威尔"
        val result = GlossaryRepository.GlossaryCsv.parse(csv)
        assertEquals(listOf("Eren, Yeager" to "艾伦·耶格尔", "Levi" to "利威尔"), result)
    }

    @Test
    fun parseQuotedQuote() {
        val csv = "\"Say \"\"Hi\"\"\",打招呼"
        val result = GlossaryRepository.GlossaryCsv.parse(csv)
        assertEquals(listOf("Say \"Hi\"" to "打招呼"), result)
    }

    @Test
    fun parseQuotedNewline() {
        val csv = "\"Line1\nLine2\",多行"
        val result = GlossaryRepository.GlossaryCsv.parse(csv)
        assertEquals(listOf("Line1\nLine2" to "多行"), result)
    }

    @Test
    fun escapePlainValueUnchanged() {
        assertEquals("Eren", GlossaryRepository.GlossaryCsv.escape("Eren"))
    }

    @Test
    fun escapeComma() {
        assertEquals("\"Eren, Yeager\"", GlossaryRepository.GlossaryCsv.escape("Eren, Yeager"))
    }

    @Test
    fun escapeQuote() {
        assertEquals("\"Say \"\"Hi\"\"\"", GlossaryRepository.GlossaryCsv.escape("Say \"Hi\""))
    }

    @Test
    fun escapeNewline() {
        assertEquals("\"Line1\nLine2\"", GlossaryRepository.GlossaryCsv.escape("Line1\nLine2"))
    }
}
