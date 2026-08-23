package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveMergeTest {
    @Test
    fun mergeActiveEntries_mergesInActiveOrder() {
        val entries = mapOf(
            "默认" to listOf("A" to "甲"),
            "作品B" to listOf("C" to "丙")
        )
        val merged = GlossaryRepository.mergeActiveEntries(entries, listOf("默认", "作品B"))

        assertEquals(2, merged.size)
        assertEquals("A", merged[0].source)
        assertEquals("甲", merged[0].target)
        assertEquals("C", merged[1].source)
    }

    @Test
    fun mergeActiveEntries_earlierCollectionWinsOnConflict() {
        val entries = mapOf(
            "默认" to listOf("Eren" to "艾伦", "Levi" to "利威尔"),
            "作品B" to listOf("Eren" to "艾连")
        )
        val merged = GlossaryRepository.mergeActiveEntries(entries, listOf("默认", "作品B"))

        assertEquals(2, merged.size)
        assertEquals("艾伦", merged.first { it.source == "Eren" }.target)
    }

    @Test
    fun mergeActiveEntries_activeOrderDeterminesPriority() {
        val entries = mapOf(
            "默认" to listOf("Eren" to "艾伦"),
            "作品B" to listOf("Eren" to "艾连")
        )
        val merged = GlossaryRepository.mergeActiveEntries(entries, listOf("作品B", "默认"))

        assertEquals(1, merged.size)
        assertEquals("艾连", merged[0].target)
    }

    @Test
    fun mergeActiveEntries_skipsBlankSources() {
        val entries = mapOf(
            "默认" to listOf("" to "空", "B" to "乙")
        )
        val merged = GlossaryRepository.mergeActiveEntries(entries, listOf("默认"))

        assertEquals(1, merged.size)
        assertEquals("B", merged[0].source)
    }

    @Test
    fun mergeActiveEntries_emptyActiveOrderReturnsEmpty() {
        val entries = mapOf("默认" to listOf("A" to "甲"))
        assertEquals(0, GlossaryRepository.mergeActiveEntries(entries, emptyList()).size)
    }
}