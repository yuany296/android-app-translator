package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏纯逻辑单测（2026-09-28 用户要求：收藏初始为空、由用户添加、显示 favicon）。
 */
class BrowserFavoriteRepositoryTest {

    private fun favorite(label: String, url: String, ts: Long): BrowserFavorite =
        BrowserFavorite(label = label, url = url, addedAtMs = ts)

    @Test
    fun mergeFavoriteAppendsInAddOrder() {
        val merged = BrowserFavoriteRepository.mergeFavorite(
            existing = listOf(favorite("A", "https://a.com", 100)),
            label = "B",
            url = "https://b.com",
            nowMs = 200
        )
        assertEquals(listOf("https://a.com", "https://b.com"), merged.map { it.url })
    }

    @Test
    fun mergeFavoriteUpdatesLabelButKeepsOriginalAddTime() {
        val merged = BrowserFavoriteRepository.mergeFavorite(
            existing = listOf(favorite("旧名", "https://a.com", 100)),
            label = "新名",
            url = "https://a.com",
            nowMs = 500
        )
        assertEquals(1, merged.size)
        assertEquals("新名", merged.single().label)
        assertEquals(100L, merged.single().addedAtMs)
    }

    @Test
    fun mergeFavoriteFallsBackToDomainWhenLabelBlank() {
        val merged = BrowserFavoriteRepository.mergeFavorite(
            existing = emptyList(),
            label = "   ",
            url = "https://page.kakao.com/view",
            nowMs = 1
        )
        assertEquals("page.kakao.com", merged.single().label)
    }

    @Test
    fun mergeFavoriteKeepsPreviousLabelWhenNewLabelBlank() {
        val merged = BrowserFavoriteRepository.mergeFavorite(
            existing = listOf(favorite("카카오페이지", "https://page.kakao.com", 1)),
            label = "",
            url = "https://page.kakao.com",
            nowMs = 9
        )
        assertEquals("카카오페이지", merged.single().label)
    }

    @Test
    fun mergeFavoriteTrimsToMax() {
        val many = (1..BrowserFavoriteRepository.MAX_FAVORITES).map {
            favorite("s$it", "https://s$it.com", it.toLong())
        }
        val merged = BrowserFavoriteRepository.mergeFavorite(
            existing = many,
            label = "extra",
            url = "https://extra.com",
            nowMs = 999
        )
        assertEquals(BrowserFavoriteRepository.MAX_FAVORITES, merged.size)
        // 超出上限时保留最早添加的，丢掉最新追加的
        assertTrue(merged.none { it.url == "https://extra.com" })
    }

    @Test
    fun defaultLabelStripsWwwAndScheme() {
        assertEquals("kakao.com", BrowserFavoriteRepository.defaultLabel("https://www.kakao.com/x"))
        assertEquals("page.kakao.com", BrowserFavoriteRepository.defaultLabel("http://page.kakao.com"))
        assertEquals(0, BrowserFavoriteRepository.defaultLabel("").length)
    }

    @Test
    fun originOfReturnsSchemeAndHost() {
        assertEquals("https://page.kakao.com", BrowserFavoriteRepository.originOf("https://page.kakao.com/view/1?x=2"))
        assertEquals("http://localhost:8765", BrowserFavoriteRepository.originOf("http://localhost:8765/a.html"))
        assertEquals(null, BrowserFavoriteRepository.originOf("about:blank"))
    }

    @Test
    fun jsonRoundTripKeepsFields() {
        val entries = listOf(
            favorite("카카오페이지", "https://page.kakao.com", 123),
            favorite("리디", "https://ridibooks.com", 456)
        )
        val parsed = BrowserFavoriteRepository.parseEntriesText(
            BrowserFavoriteRepository.toStoreJson(entries)
        )
        assertEquals(entries, parsed)
    }

    @Test
    fun parseEntriesTextSkipsInvalidUrls() {
        assertEquals(0, BrowserFavoriteRepository.parseEntriesText("").size)
        assertEquals(0, BrowserFavoriteRepository.parseEntriesText("not json").size)
        assertEquals(
            0,
            BrowserFavoriteRepository.parseEntriesText("""{"entries":[{"label":"x","url":"about:blank"}]}""").size
        )
    }

    @Test
    fun labelIsCappedToMaxLength() {
        val long = "가".repeat(BrowserFavoriteRepository.MAX_LABEL_LENGTH + 10)
        val merged = BrowserFavoriteRepository.mergeFavorite(emptyList(), long, "https://a.com", 1)
        assertEquals(BrowserFavoriteRepository.MAX_LABEL_LENGTH, merged.single().label.length)
    }
}
