package com.manga.translator.app

import com.manga.translator.store.SentenceOrigin
import com.manga.translator.store.SentenceSource
import com.manga.translator.store.SentenceTranslation
import org.junit.Assert.assertEquals
import org.junit.Test

/** 翻译历史「按页」展开逻辑（2026-11-02）：同一句在多页出现时不能丢来源。 */
class HistoryPageGroupingTest {

    private fun entry(
        text: String,
        hash: String = "page-a",
        label: String = "lezhin.com",
        created: Long = 100L,
        lastHit: Long = 0L,
        hitCount: Int = 0,
        origins: List<SentenceOrigin> = emptyList()
    ) = SentenceTranslation(
        sourceLanguage = "ko",
        targetLanguage = "zh-cn",
        originalText = text,
        translatedText = "译文-$text",
        originLabel = label,
        originPageHash = hash,
        createdAtMs = created,
        lastHitAtMs = lastHit,
        hitCount = hitCount,
        origins = origins
    )

    @Test
    fun legacyEntryBecomesOnePageGroup() {
        val groups = buildSentencePageGroups(
            listOf(entry("안녕", hash = "legacy", hitCount = 2, lastHit = 300L))
        )
        assertEquals(1, groups.size)
        assertEquals("legacy", groups[0].pageHash)
        assertEquals(1, groups[0].sentenceCount)
        assertEquals(2, groups[0].hitCount)
        assertEquals(300L, groups[0].lastSeenAtMs)
    }

    @Test
    fun sentenceWithTwoOriginsAppearsInBothPageGroups() {
        val shared = entry(
            "같은 대사",
            origins = listOf(
                SentenceOrigin("page-b", "lezhin.com", 200L, 400L, 3, "EXACT"),
                SentenceOrigin("page-a", "lezhin.com", 100L, 300L, 1, "NEW")
            )
        )
        val groups = buildSentencePageGroups(listOf(shared))
        assertEquals(2, groups.size)
        assertEquals("page-b", groups[0].pageHash)
        assertEquals("page-a", groups[1].pageHash)
        assertEquals(1, groups[0].sentenceCount)
        assertEquals(3, groups[0].hitCount)
        assertEquals(1, groups[1].hitCount)
    }

    @Test
    fun duplicateSameSentenceInOnePageIsDeduplicated() {
        val duplicate = entry(
            "중복",
            origins = listOf(
                SentenceOrigin("page-a", "lezhin.com", 100L, 200L, 1, "NEW"),
                SentenceOrigin("page-a", "lezhin.com", 100L, 200L, 1, "NEW")
            )
        )
        val groups = buildSentencePageGroups(listOf(duplicate))
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].sentenceCount)
        assertEquals(1, groups[0].occurrences.size)
    }

    @Test
    fun groupsAreSortedByMostRecentLastSeen() {
        val old = entry(
            "old",
            origins = listOf(SentenceOrigin("old-page", "site", 10L, 10L, 0, "NEW"))
        )
        val new = entry(
            "new",
            origins = listOf(SentenceOrigin("new-page", "site", 20L, 20L, 0, "NEW"))
        )
        val groups = buildSentencePageGroups(listOf(old, new))
        assertEquals("new-page", groups[0].pageHash)
        assertEquals("old-page", groups[1].pageHash)
    }

    @Test
    fun sourceFilterMatchesAnyOriginNotJustPrimary() {
        val shared = entry(
            "mixed",
            hash = "image-hash",
            origins = listOf(
                SentenceOrigin("image-hash", "site", 100L, 100L, 0, "NEW"),
                SentenceOrigin("webpage-text:123", "site", 200L, 200L, 0, "NEW")
            )
        )
        assertEquals(true, shared.matchesSourceKind(SentenceSource.IMAGE))
        assertEquals(true, shared.matchesSourceKind(SentenceSource.WEBPAGE_TEXT))
    }

    @Test
    fun sourceKindUsesEachOriginPageHash() {
        val shared = entry(
            "mixed",
            origins = listOf(
                SentenceOrigin("webpage-text:123", "site", 200L, 200L, 0, "NEW"),
                SentenceOrigin("image-hash", "site", 100L, 100L, 0, "NEW")
            )
        )
        val groups = buildSentencePageGroups(listOf(shared))
        assertEquals(2, groups.size)
        assertEquals("webpage-text:123", groups[0].pageHash)
        assertEquals("image-hash", groups[1].pageHash)
    }
}
