package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Test

/** 页面级翻译历史（2026-11-02「翻译历史尽量详细」）的纯合并/序列化逻辑。 */
class TranslationPageRepositoryTest {

    private val context = TranslationOriginContext(
        kind = TranslationPageKind.WEBTOON,
        label = "lezhin.com",
        url = "https://www.lezhin.com/ko/comic/x/1",
        title = "신의 결 1화",
        pageIndex = 2,
        pageTotal = 20,
        runId = "run-1"
    )

    private fun merge(
        existing: TranslationPageInfo? = null,
        ctx: TranslationOriginContext = context,
        stats: TranslationPageRunStats = TranslationPageRunStats(
            sentenceCount = 8,
            newCount = 5,
            cacheHitCount = 3,
            exactCount = 2,
            compactCount = 1,
            fuzzyCount = 0
        ),
        now: Long = 1000L
    ): TranslationPageInfo = TranslationPageRepository.mergePageInfo(
        existing = existing,
        pageHash = "page-a",
        context = ctx,
        stats = stats,
        sourceLanguage = "ko",
        targetLanguage = "zh-cn",
        ocrEngine = "ml_kit",
        translateEngine = "cloud",
        translateProvider = "openai",
        translateModel = "deepseek-v4.1-flash",
        now = now
    )

    @Test
    fun newPageStoresAllDetailFields() {
        val info = merge()
        assertEquals("page-a", info.pageHash)
        assertEquals(TranslationPageKind.WEBTOON, info.kind)
        assertEquals("lezhin.com", info.label)
        assertEquals("https://www.lezhin.com/ko/comic/x/1", info.url)
        assertEquals("신의 결 1화", info.title)
        assertEquals(2, info.pageIndex)
        assertEquals(20, info.pageTotal)
        assertEquals("ko", info.sourceLanguage)
        assertEquals("zh-cn", info.targetLanguage)
        assertEquals("ml_kit", info.ocrEngine)
        assertEquals("cloud", info.translateEngine)
        assertEquals("deepseek-v4.1-flash", info.translateModel)
        assertEquals(1000L, info.createdAtMs)
        assertEquals(1000L, info.lastTranslatedAtMs)
        assertEquals(1, info.translateCount)
        assertEquals(8, info.sentenceCount)
        assertEquals(5, info.newCount)
        assertEquals(3, info.cacheHitCount)
        assertEquals(2, info.exactCount)
        assertEquals(1, info.compactCount)
        assertEquals(0, info.fuzzyCount)
    }

    @Test
    fun sameRunAccumulatesBatchStatsWithoutBumpingTranslateCount() {
        val first = merge()
        val second = merge(
            existing = first,
            ctx = context.copy(accumulateStats = true),
            stats = TranslationPageRunStats(
                sentenceCount = 4,
                newCount = 2,
                cacheHitCount = 1,
                exactCount = 0,
                compactCount = 1,
                fuzzyCount = 0,
                skippedCount = 1,
                glossaryStaleCount = 1
            ),
            now = 2000L
        )
        assertEquals(12, second.sentenceCount)
        assertEquals(7, second.newCount)
        assertEquals(4, second.cacheHitCount)
        assertEquals(2, second.exactCount)
        assertEquals(2, second.compactCount)
        assertEquals(1, second.skippedCount)
        assertEquals(1, second.glossaryStaleCount)
        assertEquals(1, second.translateCount)
        assertEquals(1000L, second.createdAtMs)
        assertEquals(2000L, second.lastTranslatedAtMs)
    }

    @Test
    fun sameRunRetryReplacesStatsWithoutBumpingTranslateCount() {
        val first = merge()
        val retry = merge(
            existing = first,
            // 条带太短重跑：runId 相同，但不是页面的不同片段，必须覆盖而不是累加。
            ctx = context.copy(accumulateStats = false),
            stats = TranslationPageRunStats(
                sentenceCount = 9,
                newCount = 6,
                cacheHitCount = 3
            ),
            now = 2500L
        )
        assertEquals(9, retry.sentenceCount)
        assertEquals(6, retry.newCount)
        assertEquals(3, retry.cacheHitCount)
        assertEquals(1, retry.translateCount)
        assertEquals(1000L, retry.createdAtMs)
        assertEquals(2500L, retry.lastTranslatedAtMs)
    }

    @Test
    fun differentRunReplacesStatsAndIncrementsTranslateCount() {
        val first = merge()
        val second = merge(
            existing = first,
            ctx = context.copy(runId = "run-2"),
            stats = TranslationPageRunStats(sentenceCount = 6, newCount = 1, cacheHitCount = 5),
            now = 3000L
        )
        assertEquals(6, second.sentenceCount)
        assertEquals(1, second.newCount)
        assertEquals(5, second.cacheHitCount)
        assertEquals(2, second.translateCount)
        assertEquals(1000L, second.createdAtMs)
        assertEquals(3000L, second.lastTranslatedAtMs)
        assertEquals("run-2", second.lastRunId)
    }

    @Test
    fun blankContextFieldsKeepPreviousValues() {
        val first = merge()
        val second = merge(
            existing = first,
            ctx = TranslationOriginContext(kind = TranslationPageKind.UNKNOWN, runId = "run-2"),
            now = 2000L
        )
        assertEquals(TranslationPageKind.WEBTOON, second.kind)
        assertEquals("lezhin.com", second.label)
        assertEquals("https://www.lezhin.com/ko/comic/x/1", second.url)
        assertEquals("신의 결 1화", second.title)
        assertEquals(2, second.pageIndex)
        assertEquals(20, second.pageTotal)
        assertEquals("ko", second.sourceLanguage)
        assertEquals("ml_kit", second.ocrEngine)
    }

    @Test
    fun displayTitleFallsBackInOrder() {
        val base = merge()
        assertEquals("신의 결 1화", base.displayTitle())
        assertEquals(
            "https://www.lezhin.com/ko/comic/x/1",
            base.copy(title = "").displayTitle()
        )
        assertEquals("lezhin.com", base.copy(title = "", url = "").displayTitle())
        assertEquals("页面 page-a", base.copy(title = "", url = "", label = "").displayTitle())
    }

    @Test
    fun storeJsonRoundTripsAllFields() {
        val info = merge()
        val json = TranslationPageRepository.toStoreJson(listOf(info))
        val parsed = TranslationPageRepository.parseEntriesText(json)
        assertEquals(1, parsed.size)
        assertEquals(info, parsed[0])
    }

    @Test
    fun parseEntriesTextIgnoresMalformedAndMissingHash() {
        assertEquals(0, TranslationPageRepository.parseEntriesText("").size)
        assertEquals(0, TranslationPageRepository.parseEntriesText("not json").size)
        val parsed = TranslationPageRepository.parseEntriesText(
            """{"entries":[{"pageHash":""},{"pageHash":"ok","kind":"pixiv","pageIndex":4}]}"""
        )
        assertEquals(1, parsed.size)
        assertEquals(TranslationPageKind.PIXIV, parsed[0].kind)
        assertEquals(4, parsed[0].pageIndex)
    }

    @Test
    fun pruneToLimitKeepsNewestPages() {
        val entries = listOf(
            merge(now = 100L),
            merge(ctx = context.copy(runId = "b"), now = 200L).copy(pageHash = "b"),
            merge(ctx = context.copy(runId = "c"), now = 300L).copy(pageHash = "c")
        )
        val pruned = TranslationPageRepository.pruneToLimit(entries, max = 2)
        assertEquals(2, pruned.size)
        assertEquals("c", pruned[0].pageHash)
        assertEquals("b", pruned[1].pageHash)
    }

    @Test
    fun unknownKindIsParsedAsUnknown() {
        assertEquals(TranslationPageKind.UNKNOWN, TranslationPageKind.fromId(null))
        assertEquals(TranslationPageKind.UNKNOWN, TranslationPageKind.fromId("garbage"))
        assertEquals(TranslationPageKind.WEBPAGE_TEXT, TranslationPageKind.fromId("webpage_text"))
    }

}
