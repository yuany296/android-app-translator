package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Test

class GlossaryUniqueNameTest {

    @Test
    fun returnsBaseWhenEmpty() {
        assertEquals("默认", GlossaryRepository.uniqueCollectionName(emptyList(), "默认"))
    }

    @Test
    fun returnsBaseWhenNotExists() {
        assertEquals("作品A", GlossaryRepository.uniqueCollectionName(listOf("默认", "作品B"), "作品A"))
    }

    @Test
    fun appendsCounterWhenBaseExists() {
        val existing = listOf("作品A")
        assertEquals("作品A（2）", GlossaryRepository.uniqueCollectionName(existing, "作品A"))
    }

    @Test
    fun incrementsCounterUntilFree() {
        val existing = listOf("作品A", "作品A（2）", "作品A（3）")
        assertEquals("作品A（4）", GlossaryRepository.uniqueCollectionName(existing, "作品A"))
    }

    @Test
    fun trimsBaseAndCounter() {
        val existing = listOf("作品A")
        assertEquals("作品A（2）", GlossaryRepository.uniqueCollectionName(existing, "  作品A  "))
    }

    @Test
    fun returnsBlankForBlankBase() {
        assertEquals("", GlossaryRepository.uniqueCollectionName(emptyList(), "   "))
    }
}
