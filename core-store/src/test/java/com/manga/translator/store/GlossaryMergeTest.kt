package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryMergeTest {
    @Test
    fun mergeIncoming_addsNewEntries() {
        val result = GlossaryRepository.mergeIncoming(
            existing = mapOf("A" to "甲"),
            incoming = listOf("B" to "乙", "C" to "丙"),
            maxEntries = 500
        )
        assertEquals(2, result.added)
        assertEquals(0, result.skipped)
        assertFalse(result.truncated)
        assertEquals(mapOf("A" to "甲", "B" to "乙", "C" to "丙"), result.merged)
    }

    @Test
    fun mergeIncoming_skipsDuplicates() {
        val result = GlossaryRepository.mergeIncoming(
            existing = mapOf("A" to "甲"),
            incoming = listOf("A" to "另译", "B" to "乙"),
            maxEntries = 500
        )
        assertEquals(1, result.added)
        assertEquals(1, result.skipped)
        assertEquals("甲", result.merged["A"])
    }

    @Test
    fun mergeIncoming_truncatesAtLimit() {
        val result = GlossaryRepository.mergeIncoming(
            existing = mapOf("A" to "甲"),
            incoming = listOf("B" to "乙", "C" to "丙", "D" to "丁"),
            maxEntries = 3
        )
        assertEquals(2, result.added)
        assertTrue(result.truncated)
        assertEquals(setOf("A", "B", "C"), result.merged.keys)
    }

    @Test
    fun mergeIncoming_skipsBlankEntries() {
        val result = GlossaryRepository.mergeIncoming(
            existing = emptyMap(),
            incoming = listOf("" to "乙", "B" to "  "),
            maxEntries = 500
        )
        assertEquals(0, result.added)
        assertEquals(2, result.skipped)
    }
}