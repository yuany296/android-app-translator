package com.manga.translator.store

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 句子多页来源（2026-11-02）的合并、迁移与序列化。 */
class SentenceOriginTest {

    private fun entry(
        hash: String = "page-a",
        label: String = "lezhin.com",
        created: Long = 100L,
        lastHit: Long = 0L,
        hitCount: Int = 0,
        origins: List<SentenceOrigin> = emptyList()
    ) = SentenceTranslation(
        sourceLanguage = "ko",
        targetLanguage = "zh-cn",
        originalText = "안녕",
        translatedText = "你好",
        originLabel = label,
        originPageHash = hash,
        createdAtMs = created,
        lastHitAtMs = lastHit,
        hitCount = hitCount,
        origins = origins
    )

    @Test
    fun legacyOriginIsSynthesizedWhenListIsEmpty() {
        val origins = entry(hitCount = 3, lastHit = 500L).originsOrLegacy()
        assertEquals(1, origins.size)
        assertEquals("page-a", origins[0].pageHash)
        assertEquals("lezhin.com", origins[0].label)
        assertEquals(100L, origins[0].firstSeenAtMs)
        assertEquals(500L, origins[0].lastSeenAtMs)
        assertEquals(3, origins[0].hitCount)
    }

    @Test
    fun emptyLegacyOriginReturnsEmptyList() {
        assertTrue(entry(hash = "", created = 0L).originsOrLegacy().isEmpty())
    }

    @Test
    fun mergeAddsNewPageAndKeepsOldPage() {
        val first = SentenceOrigin("page-a", "lezhin.com", 100L, 100L, 0, "NEW")
        val merged = SentenceTranslationRepository.mergeOrigin(
            origins = listOf(first),
            pageHash = "page-b",
            label = "kakaopage",
            now = 200L,
            matchKind = "NEW"
        )
        assertEquals(2, merged.size)
        assertEquals("page-b", merged[0].pageHash)
        assertEquals("page-a", merged[1].pageHash)
        assertEquals(100L, merged[1].firstSeenAtMs)
    }

    @Test
    fun mergeHitUpdatesCountAndLastSeenWithoutChangingFirstSeen() {
        val first = SentenceOrigin("page-a", "lezhin.com", 100L, 150L, 2, "EXACT")
        val merged = SentenceTranslationRepository.mergeOrigin(
            origins = listOf(first),
            pageHash = "page-a",
            label = "",
            now = 300L,
            matchKind = "COMPACT",
            hit = true
        )
        assertEquals(1, merged.size)
        assertEquals(100L, merged[0].firstSeenAtMs)
        assertEquals(300L, merged[0].lastSeenAtMs)
        assertEquals(3, merged[0].hitCount)
        assertEquals("lezhin.com", merged[0].label)
        assertEquals("COMPACT", merged[0].lastMatchKind)
    }

    @Test
    fun mergeUsesFallbackFirstSeenForBackfilledData() {
        val merged = SentenceTranslationRepository.mergeOrigin(
            origins = emptyList(),
            pageHash = "old-page",
            label = "",
            now = 50L,
            firstSeenFallback = 10L
        )
        assertEquals(10L, merged[0].firstSeenAtMs)
        assertEquals(50L, merged[0].lastSeenAtMs)
    }

    @Test
    fun mergeCapsOriginsToMostRecent() {
        var origins = emptyList<SentenceOrigin>()
        for (i in 1..(SentenceTranslationRepository.MAX_ORIGINS + 3)) {
            origins = SentenceTranslationRepository.mergeOrigin(
                origins = origins,
                pageHash = "p$i",
                label = "site",
                now = i.toLong()
            )
        }
        assertEquals(SentenceTranslationRepository.MAX_ORIGINS, origins.size)
        assertEquals("p${SentenceTranslationRepository.MAX_ORIGINS + 3}", origins.first().pageHash)
        assertTrue(origins.none { it.pageHash == "p1" })
    }

    @Test
    fun mergeBlankPageHashIsNoOp() {
        val first = SentenceOrigin("page-a", "lezhin.com", 100L, 100L, 0, "NEW")
        val merged = SentenceTranslationRepository.mergeOrigin(
            origins = listOf(first),
            pageHash = "  ",
            label = "ignored",
            now = 200L
        )
        assertEquals(listOf(first), merged)
    }

    @Test
    fun backfillAddsOriginForLegacyRow() {
        val patched = SentenceTranslationRepository.backfilledEntry(
            entry(hash = "", created = 0L),
            OriginSnapshot(pageHash = "snap-page", timestampMs = 900L)
        )
        assertEquals("snap-page", patched!!.originPageHash)
        assertEquals(900L, patched.createdAtMs)
        assertEquals(1, patched.origins.size)
        assertEquals("snap-page", patched.origins[0].pageHash)
        assertEquals(900L, patched.origins[0].firstSeenAtMs)
    }

    @Test
    fun backfillAddsSnapshotAsExtraOriginWhenTimeWasMissing() {
        val patched = SentenceTranslationRepository.backfilledEntry(
            entry(created = 0L),
            OriginSnapshot(pageHash = "another", timestampMs = 900L)
        )
        // 已有旧字段合成的一条来源；回填再把快照那一页补成第二条。
        assertEquals(2, patched!!.origins.size)
        val again = SentenceTranslationRepository.backfilledEntry(
            patched,
            OriginSnapshot(pageHash = "another", timestampMs = 900L)
        )
        assertEquals(null, again)
    }

    @Test
    fun backfillDoesNotTouchFullyFilledLegacyRow() {
        // 幂等：旧字段都填了就不动；origins 由 ensureLoaded 用旧字段合成。
        val patched = SentenceTranslationRepository.backfilledEntry(
            entry(hash = "keep", created = 100L),
            OriginSnapshot(pageHash = "another", timestampMs = 900L)
        )
        assertEquals(null, patched)
    }

    @Test
    fun originsJsonRoundTrip() {
        val origins = listOf(
            SentenceOrigin("page-b", "site", 10L, 30L, 4, "FUZZY"),
            SentenceOrigin("page-a", "site", 5L, 20L, 1, "NEW")
        )
        val array = SentenceTranslationRepository.originsToJson(origins)
        val parsed = SentenceTranslationRepository.parseOrigins(array)
        assertEquals(origins, parsed)
    }

    @Test
    fun parseOriginsSkipsBlankHashAndSortsByLastSeen() {
        val array = org.json.JSONArray()
            .put(JSONObject().put("pageHash", "").put("lastSeenAtMs", 999))
            .put(JSONObject().put("pageHash", "old").put("lastSeenAtMs", 10))
            .put(JSONObject().put("pageHash", "new").put("lastSeenAtMs", 20))
        val parsed = SentenceTranslationRepository.parseOrigins(array)
        assertEquals(2, parsed.size)
        assertEquals("new", parsed[0].pageHash)
        assertEquals("old", parsed[1].pageHash)
    }
}
