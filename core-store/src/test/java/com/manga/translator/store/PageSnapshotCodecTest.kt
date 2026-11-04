package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Test

/** 页面快照索引的纯逻辑（2026-11-02「翻译历史按页保留原图」）。 */
class PageSnapshotCodecTest {

    private fun snapshot(hash: String, ts: Long): PageSnapshot = PageSnapshot(
        pageHash = hash,
        originLabel = "lezhin.com",
        timestampMs = ts,
        bubblesJson = """{"bubbles":[{"x":10,"y":20,"w":30,"h":40}]}""",
        thumbnailDir = "dir-$hash",
        thumbnailTopPercent = 12.5f,
        thumbnailHeightPercent = 80f
    )

    @Test
    fun storeJson_roundTripsSnapshotFields() {
        val entries = listOf(snapshot("a", 100L), snapshot("b", 200L))
        val json = PageSnapshotRepository.toStoreJson(entries)
        val parsed = PageSnapshotRepository.parseEntriesText(json)

        assertEquals(2, parsed.size)
        assertEquals("a", parsed[0].pageHash)
        assertEquals("lezhin.com", parsed[0].originLabel)
        assertEquals(100L, parsed[0].timestampMs)
        assertEquals(12.5f, parsed[0].thumbnailTopPercent, 0.001f)
        assertEquals(80f, parsed[0].thumbnailHeightPercent, 0.001f)
    }

    @Test
    fun parseEntriesText_handlesBlankMalformedAndMissingHash() {
        assertEquals(0, PageSnapshotRepository.parseEntriesText("").size)
        assertEquals(0, PageSnapshotRepository.parseEntriesText("not json").size)
        assertEquals(0, PageSnapshotRepository.parseEntriesText("{}").size)
        val json = """{"entries":[{"pageHash":""},{"pageHash":"ok","timestampMs":3}]}"""
        val parsed = PageSnapshotRepository.parseEntriesText(json)
        assertEquals(1, parsed.size)
        assertEquals("ok", parsed[0].pageHash)
    }

    @Test
    fun pruneToLimit_keepsNewest() {
        val entries = listOf(
            snapshot("old", 100L),
            snapshot("mid", 200L),
            snapshot("new", 300L)
        )
        val pruned = PageSnapshotRepository.pruneToLimit(entries, max = 2)
        assertEquals(2, pruned.size)
        assertEquals("new", pruned[0].pageHash)
        assertEquals("mid", pruned[1].pageHash)
    }

    @Test
    fun parsePageBubbleRects_fallsBackToOuterBoxWhenContentMissing() {
        val rects = parsePageBubbleRects(
            """{"bubbles":[{"x":10,"y":20,"w":30,"h":40},{"x":1,"y":2,"w":0,"h":4}]}"""
        )
        assertEquals(1, rects.size)
        assertEquals(10f, rects[0].x, 0.001f)
        assertEquals(20f, rects[0].y, 0.001f)
        assertEquals(30f, rects[0].contentW, 0.001f)
        assertEquals(40f, rects[0].contentH, 0.001f)
    }

    @Test
    fun parsePageBubbleRects_keepsExplicitContentBox() {
        val rects = parsePageBubbleRects(
            """{"bubbles":[{"x":1,"y":2,"w":30,"h":40,"contentX":5,"contentY":6,"contentW":7,"contentH":8}]}"""
        )
        assertEquals(5f, rects[0].contentX, 0.001f)
        assertEquals(6f, rects[0].contentY, 0.001f)
        assertEquals(7f, rects[0].contentW, 0.001f)
        assertEquals(8f, rects[0].contentH, 0.001f)
    }

    @Test
    fun parsePageBubbleRects_handlesBlank() {
        assertEquals(0, parsePageBubbleRects("").size)
        assertEquals(0, parsePageBubbleRects("not json").size)
    }
}
