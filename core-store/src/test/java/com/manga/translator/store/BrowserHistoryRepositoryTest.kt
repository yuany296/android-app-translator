package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内嵌浏览器访问历史纯逻辑单测（2026-09-28）：
 * URL 归一化、同 URL 去重置顶 + 次数累加、容量裁剪、JSON 往返、标题回退。
 */
class BrowserHistoryRepositoryTest {

    private fun entry(url: String, ts: Long, title: String = "", visits: Int = 1): BrowserHistoryEntry {
        return BrowserHistoryEntry(url = url, title = title, lastVisitMs = ts, visitCount = visits)
    }

    @Test
    fun normalizeUrlAcceptsOnlyHttpSchemes() {
        assertEquals("https://page.kakao.com/x", BrowserHistoryRepository.normalizeUrl("  https://page.kakao.com/x "))
        assertEquals("http://a.com", BrowserHistoryRepository.normalizeUrl("http://a.com"))
        assertNull(BrowserHistoryRepository.normalizeUrl("about:blank"))
        assertNull(BrowserHistoryRepository.normalizeUrl("data:text/html,hi"))
        assertNull(BrowserHistoryRepository.normalizeUrl("intent://scan"))
        assertNull(BrowserHistoryRepository.normalizeUrl(""))
        assertNull(BrowserHistoryRepository.normalizeUrl("   "))
    }

    @Test
    fun mergeVisitPutsLatestFirstAndCountsVisits() {
        val existing = listOf(
            entry("https://a.com", ts = 200),
            entry("https://b.com", ts = 100)
        )
        val merged = BrowserHistoryRepository.mergeVisit(
            existing = existing,
            url = "https://b.com",
            title = "B 站",
            nowMs = 300,
            max = 10
        )
        assertEquals(listOf("https://b.com", "https://a.com"), merged.map { it.url })
        assertEquals(2, merged.first().visitCount)
        assertEquals("B 站", merged.first().title)
        // 未命中的记录保持原样
        assertEquals(1, merged[1].visitCount)
    }

    @Test
    fun mergeVisitKeepsPreviousTitleWhenNewTitleBlank() {
        val existing = listOf(entry("https://a.com", ts = 100, title = "旧标题"))
        val merged = BrowserHistoryRepository.mergeVisit(existing, "https://a.com", "   ", 200, 10)
        assertEquals("旧标题", merged.first().title)
    }

    @Test
    fun mergeVisitTrimsTitleAndCapsLength() {
        val longTitle = "标".repeat(BrowserHistoryRepository.MAX_TITLE_LENGTH + 50)
        val merged = BrowserHistoryRepository.mergeVisit(emptyList(), "https://a.com", "  $longTitle  ", 1, 10)
        assertEquals(BrowserHistoryRepository.MAX_TITLE_LENGTH, merged.first().title.length)
    }

    @Test
    fun mergeVisitPrunesToMaxByRecency() {
        val existing = listOf(
            entry("https://a.com", ts = 300),
            entry("https://b.com", ts = 200),
            entry("https://c.com", ts = 100)
        )
        val merged = BrowserHistoryRepository.mergeVisit(existing, "https://d.com", "D", 400, max = 2)
        assertEquals(listOf("https://d.com", "https://a.com"), merged.map { it.url })
    }

    @Test
    fun mergeVisitUpdatesExistingWithoutGrowingList() {
        val existing = listOf(entry("https://a.com", ts = 100), entry("https://b.com", ts = 90))
        val merged = BrowserHistoryRepository.mergeVisit(existing, "https://a.com", "A", 150, 10)
        assertEquals(2, merged.size)
    }

    @Test
    fun jsonRoundTripKeepsAllFields() {
        val entries = listOf(
            entry("https://a.com", ts = 123, title = "标题", visits = 4),
            entry("https://b.com/page?q=1", ts = 99)
        )
        val parsed = BrowserHistoryRepository.parseEntriesText(
            BrowserHistoryRepository.toStoreJson(entries)
        )
        assertEquals(entries, parsed)
    }

    @Test
    fun parseEntriesTextToleratesBadInput() {
        assertEquals(0, BrowserHistoryRepository.parseEntriesText("").size)
        assertEquals(0, BrowserHistoryRepository.parseEntriesText("not json").size)
        assertEquals(0, BrowserHistoryRepository.parseEntriesText("{}").size)
        // 缺 url 的条目被丢弃
        assertEquals(
            0,
            BrowserHistoryRepository.parseEntriesText("""{"entries":[{"title":"x"}]}""").size
        )
    }

    @Test
    fun parseEntriesTextDefaultsVisitCountToAtLeastOne() {
        val parsed = BrowserHistoryRepository.parseEntriesText(
            """{"entries":[{"url":"https://a.com","title":"","lastVisitMs":5,"visitCount":0}]}"""
        )
        assertEquals(1, parsed.single().visitCount)
    }

    @Test
    fun displayTitleFallsBackToUrlWithoutScheme() {
        assertEquals("标题", BrowserHistoryRepository.displayTitle(entry("https://a.com", 1, "标题")))
        assertEquals("a.com/x", BrowserHistoryRepository.displayTitle(entry("https://www.a.com/x/", 1)))
    }

    @Test
    fun urlWithoutSchemeTruncatesLongUrls() {
        val long = "https://a.com/" + "p".repeat(200)
        val rendered = BrowserHistoryRepository.urlWithoutScheme(long)
        assertTrue(rendered.length <= BrowserHistoryRepository.MAX_TITLE_LENGTH)
        assertTrue(rendered.endsWith("…"))
    }
}
