package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryCodecTest {
    private fun entry(id: String, ts: Long, fingerprint: String = "fp-$id"): TranslationHistoryEntry {
        return TranslationHistoryEntry(
            id = id,
            timestampMs = ts,
            pageHash = "page-$id",
            sourceLanguage = "ko",
            targetLanguage = "zh-cn",
            translateFingerprint = fingerprint,
            bubblesJson = """{"bubbles":[{"originalText":"A","translatedText":"甲"}]}"""
        )
    }

    @Test
    fun storeJson_roundTripsEntries() {
        val entries = listOf(entry("1", 100L), entry("2", 200L))
        val json = TranslationHistoryRepository.toStoreJson(entries)
        val parsed = TranslationHistoryRepository.parseEntriesText(json)

        assertEquals(2, parsed.size)
        assertEquals("1", parsed[0].id)
        assertEquals(100L, parsed[0].timestampMs)
        assertEquals("fp-1", parsed[0].translateFingerprint)
        assertEquals("甲", parsed[0].bubblesJson.substringAfter("甲").let { "甲" })
    }

    @Test
    fun parseEntriesText_handlesBlankAndMalformed() {
        assertEquals(0, TranslationHistoryRepository.parseEntriesText("").size)
        assertEquals(0, TranslationHistoryRepository.parseEntriesText("not json").size)
        assertEquals(0, TranslationHistoryRepository.parseEntriesText("{}").size)
    }

    @Test
    fun parseEntriesText_skipsEntriesWithoutId() {
        val json = """{"entries":[{"timestampMs":1},{"id":"","timestampMs":2},{"id":"ok","timestampMs":3}]}"""
        val parsed = TranslationHistoryRepository.parseEntriesText(json)
        assertEquals(1, parsed.size)
        assertEquals("ok", parsed[0].id)
    }

    @Test
    fun pruneToLimit_keepsNewest() {
        val entries = listOf(entry("old", 100L), entry("mid", 200L), entry("new", 300L))
        val pruned = TranslationHistoryRepository.pruneToLimit(entries, max = 2)

        assertEquals(2, pruned.size)
        assertEquals("new", pruned[0].id)
        assertEquals("mid", pruned[1].id)
    }

    @Test
    fun pruneToLimit_keepsAllWhenUnderLimit() {
        val entries = listOf(entry("1", 100L))
        assertEquals(1, TranslationHistoryRepository.pruneToLimit(entries, max = 500).size)
    }

    @Test
    fun storeJson_thenPrune_thenParseKeepsFingerprint() {
        val entries = (1..10).map { entry("e$it", it * 10L, fingerprint = "shared-fp") }
        val json = TranslationHistoryRepository.toStoreJson(entries)
        val pruned = TranslationHistoryRepository.pruneToLimit(
            TranslationHistoryRepository.parseEntriesText(json),
            max = 3
        )
        assertEquals(3, pruned.size)
        assertEquals("e10", pruned.first().id)
        assertEquals(100L, pruned.first().timestampMs)
        assertEquals("shared-fp", pruned.first().translateFingerprint)
        assertNull(
            TranslationHistoryRepository.parseEntriesText(
                TranslationHistoryRepository.toStoreJson(pruned)
            ).firstOrNull { it.id == "e1" }
        )
    }
}