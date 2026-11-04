package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 旧句子「来源回填」纯逻辑单测（2026-10-31）。
 *
 * 背景：`originPageHash` / `createdAtMs` 是后加的字段，老数据全是空值，
 * 于是「翻译历史 → 按页」把它们糊成一组「来源未知」，跟「按句」看不出区别。
 * 启动时拿页面级历史快照往回补一次，这里钉的是补的规则本身。
 *
 * 三条不能破的规矩：
 * 1. 键必须和仓库内部同口径 —— 否则一条也匹配不上，而且不报错（静默 0 条）；
 * 2. 只填空字段，绝不覆盖已有值；
 * 3. 已填满的条目再跑一次必须返回 null（幂等，避免每次启动无谓重写整个 JSON）。
 */
class SentenceOriginBackfillTest {

    private fun entry(
        original: String = "저희 무대로 이동하시겠습니다.",
        hash: String = "",
        created: Long = 0L
    ) = SentenceTranslation(
        sourceLanguage = "ko",
        targetLanguage = "zh-cn",
        originalText = original,
        translatedText = "请前往我们的舞台。",
        originPageHash = hash,
        createdAtMs = created
    )

    private val snapshot = OriginSnapshot(pageHash = "abc123", timestampMs = 1_700_000_000_000L)

    // --- 键的口径 ---------------------------------------------------------

    @Test
    fun `originKey 压缩连续空白并去掉首尾空白`() {
        val a = SentenceTranslationRepository.originKey("ko", "zh-cn", "  저희  무대로\n이동  ")
        val b = SentenceTranslationRepository.originKey("ko", "zh-cn", "저희 무대로 이동")
        assertEquals(b, a)
    }

    @Test
    fun `originKey 区分语言对`() {
        val ko = SentenceTranslationRepository.originKey("ko", "zh-cn", "안녕")
        val ja = SentenceTranslationRepository.originKey("ja", "zh-cn", "안녕")
        assertEquals(false, ko == ja)
    }

    @Test
    fun `originKey 不忽略大小写也不去标点`() {
        // 回填只认严格同口径：宽松匹配属于查询侧的 EXACT/COMPACT/FUZZY，不能拿来当键，
        // 否则会把句子挂到别的页上（错页比没页码更糟）。
        val upper = SentenceTranslationRepository.originKey("en", "zh-cn", "Hello!")
        val lower = SentenceTranslationRepository.originKey("en", "zh-cn", "hello")
        assertEquals(false, upper == lower)
    }

    // --- 回填规则 ---------------------------------------------------------

    @Test
    fun `两个字段都空时一起补`() {
        val patched = SentenceTranslationRepository.backfilledEntry(entry(), snapshot)
        assertNotNull(patched)
        assertEquals("abc123", patched!!.originPageHash)
        assertEquals(1_700_000_000_000L, patched.createdAtMs)
    }

    @Test
    fun `只有时间空时只补时间 保留原有 hash`() {
        val patched = SentenceTranslationRepository.backfilledEntry(
            entry(hash = "keep-me", created = 0L),
            snapshot
        )
        assertNotNull(patched)
        assertEquals("keep-me", patched!!.originPageHash)
        assertEquals(1_700_000_000_000L, patched.createdAtMs)
    }

    @Test
    fun `只有 hash 空时只补 hash 保留原有时间`() {
        val patched = SentenceTranslationRepository.backfilledEntry(
            entry(hash = "", created = 123L),
            snapshot
        )
        assertNotNull(patched)
        assertEquals("abc123", patched!!.originPageHash)
        assertEquals(123L, patched.createdAtMs)
    }

    @Test
    fun `已填满的条目不再动 保证幂等`() {
        // 2026-11-02 起「填满」还包括多页来源列表：只补 hash/时间的旧规则会让
        // 每次启动都重建 origins，破坏幂等。这里用已经有一条来源的条目钉住。
        val filled = entry(hash = "already", created = 999L).copy(
            origins = listOf(
                SentenceOrigin(
                    pageHash = "already",
                    firstSeenAtMs = 999L,
                    lastSeenAtMs = 999L,
                    lastMatchKind = "NEW"
                )
            )
        )
        val patched = SentenceTranslationRepository.backfilledEntry(filled, snapshot)
        assertNull(patched)
    }

    @Test
    fun `没有快照时不动`() {
        assertNull(SentenceTranslationRepository.backfilledEntry(entry(), null))
    }

    @Test
    fun `回填不改原文译文本与命中统计`() {
        val source = entry().copy(hitCount = 7, lastHitAtMs = 555L, originLabel = "kakaopage")
        val patched = SentenceTranslationRepository.backfilledEntry(source, snapshot)!!
        assertEquals(source.originalText, patched.originalText)
        assertEquals(source.translatedText, patched.translatedText)
        assertEquals(7, patched.hitCount)
        assertEquals(555L, patched.lastHitAtMs)
        assertEquals("kakaopage", patched.originLabel)
    }
}
