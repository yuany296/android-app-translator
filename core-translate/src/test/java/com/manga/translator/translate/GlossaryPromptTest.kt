package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryPromptTest {
    private val client = OpenAiVisionClient()

    @Test
    fun buildGlossaryLine_returnsNullWhenEmpty() {
        assertNull(client.buildGlossaryLine(emptyList()))
    }

    @Test
    fun buildGlossaryLine_returnsNullWhenAllBlank() {
        assertNull(client.buildGlossaryLine(listOf("  " to "  ", "" to "x")))
    }

    @Test
    fun buildGlossaryLine_formatsMappings() {
        val line = client.buildGlossaryLine(listOf("Eren" to "艾伦", "Levi" to "利威尔"))
        assertTrue(line!!.contains("Eren=艾伦"))
        assertTrue(line.contains("Levi=利威尔"))
        assertTrue(line.startsWith("Use the following glossary mappings strictly"))
    }

    @Test
    fun buildGlossaryLine_skipsBlankPairsButKeepsValidOnes() {
        val line = client.buildGlossaryLine(listOf("A" to "  ", "B" to "乙"))
        assertTrue(line!!.contains("B=乙"))
        assertTrue(!line.contains("A="))
    }
}