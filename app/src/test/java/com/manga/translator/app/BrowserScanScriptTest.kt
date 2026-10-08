package com.manga.translator.app

import com.manga.translator.store.BrowserSiteAdapter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取图扫描脚本的**注入配置**单测（2026-09-30）。
 *
 * 这里只测「Kotlin 交给页面什么」，不测「页面里扫到了什么」——后者需要真实浏览器，
 * 由 `scripts/scan-script-verify.mjs`（无头 Chrome + 夹具页）负责。两侧分工明确：
 * 本文件挡住配置拼装/转义这类错误，那边挡住 DOM 行为这类错误。
 */
class BrowserScanScriptTest {

    private fun adapter(
        id: String = "t",
        label: String = "T",
        imageSelectors: List<String> = emptyList(),
        excludeSelectors: List<String> = emptyList(),
        minWidth: Int? = null,
        minHeight: Int? = null,
        minNaturalWidth: Int? = null,
        minNaturalHeight: Int? = null,
        fixedOverlayPages: Boolean = false
    ) = BrowserSiteAdapter(
        id = id,
        label = label,
        hostPatterns = listOf("example.com"),
        imageSelectors = imageSelectors,
        excludeSelectors = excludeSelectors,
        minWidth = minWidth,
        minHeight = minHeight,
        minNaturalWidth = minNaturalWidth,
        minNaturalHeight = minNaturalHeight,
        fixedOverlayPages = fixedOverlayPages
    )

    // ------------------------------------------------------------------
    // 配置内容
    // ------------------------------------------------------------------

    @Test
    fun configCarriesSelectorsAndThresholdsWhenRulesEnabled() {
        val json = JSONObject(
            BrowserScanScript.scriptConfigJson(
                adapter(
                    id = "kakao_page",
                    label = "Kakao Page",
                    imageSelectors = listOf("[data-scroll-index] img", ".viewer img"),
                    excludeSelectors = listOf("header", "[class*='Banner']"),
                    minWidth = 120,
                    minHeight = 130,
                    minNaturalWidth = 200,
                    minNaturalHeight = 210
                )
            )
        )
        assertEquals("kakao_page", json.getString("id"))
        assertEquals("Kakao Page", json.getString("label"))
        assertEquals(2, json.getJSONArray("sel").length())
        assertEquals("[data-scroll-index] img", json.getJSONArray("sel").getString(0))
        assertEquals(2, json.getJSONArray("excl").length())
        assertEquals(120, json.getInt("minW"))
        assertEquals(130, json.getInt("minH"))
        assertEquals(200, json.getInt("minNW"))
        assertEquals(210, json.getInt("minNH"))
    }

    @Test
    fun siteRulesOffKeepsOnlyIdentity() {
        // 总开关关掉 = 只跑通用层。此时**不能**把选择器/阈值带进页面，
        // 否则「关掉站点适配」这个开关实际上没生效。
        val json = JSONObject(
            BrowserScanScript.scriptConfigJson(
                adapter(
                    id = "lezhin",
                    label = "Lezhin",
                    imageSelectors = listOf(".viewer img"),
                    excludeSelectors = listOf("header"),
                    minWidth = 100,
                    minNaturalWidth = 200
                ),
                siteRulesEnabled = false
            )
        )
        assertEquals("lezhin", json.getString("id"))
        assertEquals("Lezhin", json.getString("label"))
        assertFalse("关了规则不该带 sel", json.has("sel"))
        assertFalse("关了规则不该带 excl", json.has("excl"))
        assertFalse(json.has("minW"))
        assertFalse(json.has("minNW"))
    }

    @Test
    fun nullThresholdsAreOmittedSoScriptUsesGenericDefaults() {
        // null 的语义是「沿用通用默认」；如果写成 0 或省略成 undefined 让脚本 `|| 180`
        // 生效，两者都行 —— 但不能出现 "minW":0（会被 `Number(0) || 180` 兜住，
        // 看着对但语义不明）。这里锁死「干脆不出现」这一种写法。
        val json = JSONObject(BrowserScanScript.scriptConfigJson(adapter()))
        assertFalse(json.has("minW"))
        assertFalse(json.has("minH"))
        assertFalse(json.has("minNW"))
        assertFalse(json.has("minNH"))
        assertFalse(json.has("fixedPages"))
        assertFalse(json.has("sel"))
        assertFalse(json.has("excl"))
    }

    @Test
    fun fixedOverlayPagesOnlyEmittedWhenTrue() {
        val off = JSONObject(BrowserScanScript.scriptConfigJson(adapter(fixedOverlayPages = false)))
        assertFalse(off.has("fixedPages"))
        val on = JSONObject(BrowserScanScript.scriptConfigJson(adapter(fixedOverlayPages = true)))
        assertTrue(on.getBoolean("fixedPages"))
    }

    @Test
    fun configIsValidJsonForRealBuiltInAdapters() {
        // 用真实内置表跑一遍：选择器里含引号/括号/属性选择器，最容易把 JSON 拼坏。
        val ids = listOf("kakao_page", "lezhin", "ridibooks")
        ids.forEach { id ->
            val builtIn = requireNotNull(
                com.manga.translator.store.BrowserSiteAdapterRegistry.byId(id)
            ) { "缺少内置适配器 $id" }
            val raw = BrowserScanScript.scriptConfigJson(builtIn)
            val json = JSONObject(raw)
            assertEquals(id, json.getString("id"))
            assertEquals(builtIn.imageSelectors.size, json.getJSONArray("sel").length())
            assertEquals(builtIn.excludeSelectors.size, json.getJSONArray("excl").length())
        }
    }

    // ------------------------------------------------------------------
    // 注入脚本
    // ------------------------------------------------------------------

    @Test
    fun installScriptAssignsConfigVariable() {
        val script = BrowserScanScript.installScript("""{"id":"x"}""")
        assertTrue(
            "必须写到约定的全局变量上：$script",
            script.contains("window.${BrowserScanScript.CONFIG_VAR} = ")
        )
        assertTrue("结尾要给个表达式值，避免 evaluateJavascript 拿到 undefined", script.trim().endsWith("1"))
    }

    @Test
    fun configVariableNameIsStable() {
        // 脚本（JS 侧）与 Kotlin 侧靠这个字符串对接；改名会让配置读不到而静默走通用层。
        assertEquals("__mtSiteCfg", BrowserScanScript.CONFIG_VAR)
    }

    // ------------------------------------------------------------------
    // 换行符转义（这个坑一旦踩上就是「注入失败、翻译完全不动」）
    // ------------------------------------------------------------------

    @Test
    fun lineSeparatorsAreEscapedForJsInlining() {
        val dirty = "a\u2028b\u2029c"
        val escaped = BrowserScanScript.escapeLineSeparators(dirty)
        assertFalse("不能残留 U+2028", escaped.contains('\u2028'))
        assertFalse("不能残留 U+2029", escaped.contains('\u2029'))
        assertTrue(escaped.contains("\\u2028"))
        assertTrue(escaped.contains("\\u2029"))
    }

    @Test
    fun escapedConfigCanBeInlinedIntoJsObjectLiteral() {
        // 模拟「适配器 label 里带了行分隔符」：内联后必须仍是**一行合法 JS**。
        val json = JSONObject().apply {
            put("id", "x")
            put("label", "a\u2028b")
        }.toString()
        val script = BrowserScanScript.installScript(json)
        assertFalse("内联后的脚本不能含真实行分隔符", script.contains('\u2028'))
        assertFalse(script.contains('\u2029'))
        assertEquals("脚本里不该出现换行", 1, script.lines().size)
    }

    @Test
    fun escapeLineSeparatorsLeavesCleanInputUntouched() {
        val clean = """{"id":"x","sel":["a[b='c']"]}"""
        assertEquals(clean, BrowserScanScript.escapeLineSeparators(clean))
    }

    @Test
    fun jsLiteralEscapesQuotesBackslashesNewlinesAndLineSeparators() {
        val literal = BrowserScanScript.jsLiteral("a'b\\c\nd\re\u2028f\u2029g")
        assertTrue(literal.startsWith("'") && literal.endsWith("'"))
        assertTrue(literal.contains("\\'"))
        assertTrue(literal.contains("\\\\"))
        assertTrue(literal.contains("\\n"))
        assertTrue(literal.contains("\\r"))
        assertTrue(literal.contains("\\u2028"))
        assertTrue(literal.contains("\\u2029"))
        // 结果必须是单行：多行会把 evaluateJavascript 的源码弄坏。
        assertEquals(1, literal.lines().size)
    }

    @Test
    fun jsLiteralEscapesControlCharacters() {
        val literal = BrowserScanScript.jsLiteral("a\u0001b")
        assertTrue("控制字符要转成 \\uXXXX", literal.contains("\\u0001"))
    }

    // ------------------------------------------------------------------
    // 脚本本体（不跑浏览器，只做结构性检查）
    // ------------------------------------------------------------------

    @Test
    fun scanScriptIsSelfContainedAndReadsConfigVariable() {
        val js = BrowserScanScript.SCAN_JS
        assertTrue("要读注入的配置", js.contains("window.${BrowserScanScript.CONFIG_VAR}"))
        // 关键能力逐项存在：懒加载属性、srcset、可滚动宿主、iframe、canvas、blob。
        listOf("isLazyName", "parseSrcset", "hostOf", "frames", "canvas", "blob:").forEach { token ->
            assertTrue("扫描脚本缺能力：$token", js.contains(token))
        }
        // 不能残留 Kotlin 的占位符（它会在 JS 里变成语法错或读到 undefined）。
        assertFalse("残留 \$CONFIG_VAR 占位符", js.contains("\$CONFIG_VAR"))
    }

    @Test
    fun extractorScriptIsIdempotentAndReportsTaint() {
        val js = BrowserScanScript.EXTRACTOR_JS
        assertTrue(js.contains("if (window.__mtExtract) return 1;"))
        // 画布被跨域污染时必须报 tainted，而不是抛异常把整轮扫描打断。
        assertTrue(js.contains("tainted"))
        assertTrue(js.contains("SecurityError").not() || js.contains("catch"))
    }

    @Test
    fun extractDataUrlJsEmbedsRequestAsEscapedLiteral() {
        val js = BrowserScanScript.extractDataUrlJs("mt7", 0, 1000)
        assertTrue(js.contains("window.__mtExtract"))
        // 请求负载是**单个** JS 字符串字面量：它自己必须在一行里，
        // 不能被换行/引号/行分隔符截断（脚本整体是多行 IIFE，那是正常的）。
        val payloadLine = js.lines().firstOrNull { it.contains("var p = ") }
        requireNotNull(payloadLine) { "脚本里应当有 var p = <payload> 这一行：\n$js" }
        assertTrue("负载行必须含 mt7：$payloadLine", payloadLine.contains("mt7"))
        assertTrue("负载行必须以 ; 收尾（字面量闭合）：$payloadLine", payloadLine.trim().endsWith(";"))
        assertEquals("负载必须落在一行里", 1, payloadLine.lines().size)
    }
}
