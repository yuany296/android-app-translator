package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 站点适配表纯逻辑单测（2026-09-30）。
 *
 * 覆盖的是**匹配与解析**这类「错了就会静默失效」的逻辑：
 * 主机模式命中、特化压过泛化、URL 抠主机、通用兜底。
 *
 * 不覆盖（需要真实浏览器）：选择器在页面里能不能命中 —— 那由
 * `scripts/scan-script-verify.mjs`（无头 Chrome + 夹具页）负责。
 */
class BrowserSiteAdapterTest {

    // ------------------------------------------------------------------
    // 主机模式匹配
    // ------------------------------------------------------------------

    @Test
    fun exactHostMatchesItselfOnly() {
        val adapter = BrowserSiteAdapter(
            id = "t",
            label = "T",
            hostPatterns = listOf("page.kakao.com")
        )
        assertTrue(adapter.matchScore("page.kakao.com") >= 0)
        // 精确模式**不**匹配子域：写精确就是想只匹配它。
        assertEquals(-1, adapter.matchScore("m.page.kakao.com"))
        assertEquals(-1, adapter.matchScore("kakao.com"))
        assertEquals(-1, adapter.matchScore("evil-kakao.com"))
    }

    @Test
    fun wildcardSubdomainMatchesSubdomainsButNotBareDomain() {
        val adapter = BrowserSiteAdapter(
            id = "t",
            label = "T",
            hostPatterns = listOf("*.kakao.com")
        )
        assertTrue(adapter.matchScore("page.kakao.com") >= 0)
        assertTrue(adapter.matchScore("a.b.kakao.com") >= 0)
        // `*.kakao.com` 语义上不含裸域，要覆盖裸域得再写一条 `*kakao.com`。
        assertEquals(-1, adapter.matchScore("kakao.com"))
        assertEquals(-1, adapter.matchScore("notkakao.com"))
    }

    @Test
    fun starPrefixMatchesBareDomainAndSubdomains() {
        val adapter = BrowserSiteAdapter(
            id = "t",
            label = "T",
            hostPatterns = listOf("*naver.com")
        )
        assertTrue(adapter.matchScore("naver.com") >= 0)
        assertTrue(adapter.matchScore("comic.naver.com") >= 0)
        assertEquals(-1, adapter.matchScore("notnaver.com"))
        assertEquals(-1, adapter.matchScore("naver.com.evil.test"))
    }

    @Test
    fun matchIgnoresCaseAndPort() {
        val adapter = BrowserSiteAdapter(
            id = "t",
            label = "T",
            hostPatterns = listOf("page.kakao.com")
        )
        assertTrue(adapter.matchScore("PAGE.Kakao.COM") >= 0)
        // `host:port` 形态：端口不算主机的一部分。
        assertTrue(adapter.matchScore("page.kakao.com:8443") >= 0)
    }

    @Test
    fun longerPatternWinsSoSpecializationBeatsGeneralization() {
        // 同一个适配器里同时写精确与泛化：取**更长**的那个模式的分。
        val adapter = BrowserSiteAdapter(
            id = "t",
            label = "T",
            hostPatterns = listOf("*.kakao.com", "page.kakao.com")
        )
        val exact = adapter.matchScore("page.kakao.com")
        val sub = adapter.matchScore("other.kakao.com")
        assertTrue("精确模式得分应更高", exact > sub)
    }

    // ------------------------------------------------------------------
    // 注册表解析
    // ------------------------------------------------------------------

    @Test
    fun resolvePicksMostSpecificAdapter() {
        // page.kakao.com 同时被 kakao_page 的精确模式与其他泛化模式覆盖，
        // 必须稳定解析到 kakao_page。
        assertEquals("kakao_page", BrowserSiteAdapterRegistry.resolve("page.kakao.com").id)
        assertEquals("kakao_page", BrowserSiteAdapterRegistry.resolve("webtoon.kakao.com").id)
    }

    @Test
    fun resolveHandlesUserSites() {
        assertEquals("lezhin", BrowserSiteAdapterRegistry.resolve("www.lezhin.com").id)
        assertEquals("lezhin", BrowserSiteAdapterRegistry.resolve("www.lezhin.jp").id)
        // 英文站是用户实际在用的域名（2026-09-30 从设备浏览历史确认）。
        assertEquals("lezhin", BrowserSiteAdapterRegistry.resolve("www.lezhinus.com").id)
        assertEquals("lezhin", BrowserSiteAdapterRegistry.resolve("lezhinus.com").id)
        assertEquals("ridibooks", BrowserSiteAdapterRegistry.resolve("ridibooks.com").id)
        assertEquals("ridibooks", BrowserSiteAdapterRegistry.resolve("m.ridibooks.com").id)
    }

    @Test
    fun noSelectorReliesOnHashedCssModuleNames() {
        // Lezhin 的旧 CSS-module 类名形如 `topBtn__8FP1p`（名字 + 哈希），发版即变。
        // 适配器里一旦写死这种类名，过几天就会静默失效 —— 这条用例把它钉住。
        val hashed = Regex("""[A-Za-z]+__[A-Za-z0-9_]{5,}""")
        BrowserSiteAdapterRegistry.ALL.forEach { adapter ->
            (adapter.imageSelectors + adapter.excludeSelectors).forEach { selector ->
                assertFalse(
                    "${adapter.id} 的选择器疑似写死了哈希类名：$selector",
                    hashed.containsMatchIn(selector)
                )
            }
        }
    }

    @Test
    fun resolveFallsBackToGenericForUnknownAndBrokenInput() {
        assertEquals(BrowserSiteAdapter.GENERIC.id, BrowserSiteAdapterRegistry.resolve("example.com").id)
        assertEquals(BrowserSiteAdapter.GENERIC.id, BrowserSiteAdapterRegistry.resolve(null).id)
        assertEquals(BrowserSiteAdapter.GENERIC.id, BrowserSiteAdapterRegistry.resolve("").id)
        assertEquals(BrowserSiteAdapter.GENERIC.id, BrowserSiteAdapterRegistry.resolve("   ").id)
    }

    @Test
    fun genericAdapterHasNoPatternsSoItNeverWinsByAccident() {
        assertTrue(BrowserSiteAdapter.GENERIC.hostPatterns.isEmpty())
        assertEquals(-1, BrowserSiteAdapter.GENERIC.matchScore("anything.com"))
    }

    // ------------------------------------------------------------------
    // URL → 主机
    // ------------------------------------------------------------------

    @Test
    fun hostOfStripsSchemePathQueryAndPort() {
        assertEquals("page.kakao.com", BrowserSiteAdapterRegistry.hostOf("https://page.kakao.com/content/1/viewer/2"))
        assertEquals("page.kakao.com", BrowserSiteAdapterRegistry.hostOf("https://page.kakao.com:443/x?y=1#z"))
        assertEquals("page.kakao.com", BrowserSiteAdapterRegistry.hostOf("  HTTPS://Page.Kakao.Com/x  "))
        assertEquals("localhost", BrowserSiteAdapterRegistry.hostOf("http://localhost:8899/big.html"))
        assertEquals("127.0.0.1", BrowserSiteAdapterRegistry.hostOf("http://127.0.0.1:8899/a"))
    }

    @Test
    fun hostOfHandlesUserInfoAndIpv6() {
        assertEquals("example.com", BrowserSiteAdapterRegistry.hostOf("https://user:pw@example.com/x"))
        assertEquals("[::1]", BrowserSiteAdapterRegistry.hostOf("http://[::1]:8899/x"))
    }

    @Test
    fun hostOfReturnsEmptyForGarbage() {
        assertEquals("", BrowserSiteAdapterRegistry.hostOf(""))
        assertEquals("", BrowserSiteAdapterRegistry.hostOf("   "))
        // 没有 scheme 也不是域名的裸词：抠不出主机，交由调用方退回通用。
        assertEquals("about", BrowserSiteAdapterRegistry.hostOf("about:blank"))
    }

    @Test
    fun resolveUrlUsesHostExtraction() {
        assertEquals("kakao_page", BrowserSiteAdapterRegistry.resolveUrl("https://page.kakao.com/x").id)
        assertEquals(BrowserSiteAdapter.GENERIC.id, BrowserSiteAdapterRegistry.resolveUrl("about:blank").id)
        assertEquals(BrowserSiteAdapter.GENERIC.id, BrowserSiteAdapterRegistry.resolveUrl(null).id)
    }

    // ------------------------------------------------------------------
    // 内置表自身的约束
    // ------------------------------------------------------------------

    @Test
    fun builtInAdaptersAreWellFormed() {
        assertTrue("内置适配器至少要有 10 个", BrowserSiteAdapterRegistry.ALL.size >= 10)
        val ids = BrowserSiteAdapterRegistry.ALL.map { it.id }
        assertEquals("适配器 id 必须唯一", ids.size, ids.toSet().size)
        assertFalse("id 不能占用 generic", ids.contains(BrowserSiteAdapter.GENERIC.id))
        BrowserSiteAdapterRegistry.ALL.forEach { adapter ->
            assertTrue("${adapter.id} 缺少主机模式", adapter.hostPatterns.isNotEmpty())
            assertTrue("${adapter.id} 缺少展示名", adapter.label.isNotBlank())
            assertTrue("${adapter.id} 的说明不该为空（方便下次改版回溯）", adapter.note.isNotBlank())
            adapter.hostPatterns.forEach { pattern ->
                assertFalse("${adapter.id} 的模式不该带 scheme：$pattern", pattern.contains("://"))
                assertFalse("${adapter.id} 的模式不该带路径：$pattern", pattern.contains("/"))
            }
        }
    }

    @Test
    fun userSitesHaveTakeImageRules() {
        // 用户主力站必须有显式取图规则（只靠通用层就等于没适配）。
        listOf("kakao_page", "lezhin", "ridibooks").forEach { id ->
            val adapter = BrowserSiteAdapterRegistry.byId(id)
            requireNotNull(adapter) { "缺少内置适配器 $id" }
            assertTrue("$id 应有取图选择器", adapter.imageSelectors.isNotEmpty())
            assertTrue("$id 应有排除区", adapter.excludeSelectors.isNotEmpty())
        }
    }

    @Test
    fun byIdIsCaseInsensitiveAndNullSafe() {
        assertEquals("lezhin", BrowserSiteAdapterRegistry.byId("LEZHIN")?.id)
        assertEquals("lezhin", BrowserSiteAdapterRegistry.byId("  lezhin ")?.id)
        assertNull(BrowserSiteAdapterRegistry.byId(null))
        assertNull(BrowserSiteAdapterRegistry.byId("nope"))
        // 通用适配器不在表里（它不参与匹配打分）。
        assertNull(BrowserSiteAdapterRegistry.byId(BrowserSiteAdapter.GENERIC.id))
    }
}
