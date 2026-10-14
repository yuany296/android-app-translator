package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截图翻译（2026-10-12）的 Kotlin↔JS 契约。
 *
 * 「免系统授权」的前提是：捕获只碰 WebView 自己（`webView.draw`），回贴只碰页面 DOM。
 * 这组单测钉住三件事 —— 视口几何脚本要探测滚动宿主、锚点/贴图要落在捕获时的坐标上、
 * 节点类名要能被「原文」视图的 REMOVE_JS 一并清掉。任何一行改动都意味着
 * 渲染层/清理脚本的契约变了，必须同步。
 */
class BrowserScreenshotTranslateTest {

    private fun geometry(
        anchoredToElement: Boolean = false,
        x: Int = 0,
        y: Int = 0,
        w: Int = 1080,
        h: Int = 2000
    ) = BrowserScreenshotTranslate.ViewportGeometry(anchoredToElement, x, y, w, h)

    @Test
    fun metricsScript_detectsScrollHostAndReadsDocumentScroll() {
        val js = BrowserScreenshotTranslate.metricsJs()
        // 可滚动判定：overflow + 实际内容超出（与渲染层 isScrollHost 同一口径）。
        assertTrue(js.contains("scrollHeight>el.clientHeight+4"))
        assertTrue(js.contains("overflowY"))
        // 只认占满视口的大容器（阅读器主容器），小滚动条不算。
        assertTrue(js.contains("window.innerHeight*0.6"))
        // 文档模式读文档滚动偏移。
        assertTrue(js.contains("pageXOffset"))
        assertTrue(js.contains("pageYOffset"))
        // 宿主打标记：「原文→译文」贴回时按它找回同一个容器。
        assertTrue(js.contains("setAttribute('${BrowserScreenshotTranslate.HOST_ATTR}'"))
    }

    @Test
    fun parseMetrics_decodesQuotedJsonAndRejectsInvalidSize() {
        // evaluateJavascript 的返回是「JSON 字符串再被引号包一层」的形态。
        val raw = "\"" +
            "{\\\"mode\\\":\\\"element\\\",\\\"x\\\":12,\\\"y\\\":34,\\\"vw\\\":1080,\\\"vh\\\":2000}" +
            "\""
        val parsed = BrowserScreenshotTranslate.parseMetrics(raw)
        assertTrue(parsed != null)
        assertTrue(parsed!!.anchoredToElement)
        assertEquals(12, parsed.docX)
        assertEquals(34, parsed.docY)
        assertEquals(1080, parsed.viewportWidth)
        assertEquals(2000, parsed.viewportHeight)

        // 视口尺寸非法（还没量出来）→ 拒绝，调用方报「页面还没准备好」。
        val tiny = "\"{\\\"mode\\\":\\\"document\\\",\\\"x\\\":0,\\\"y\\\":0,\\\"vw\\\":1,\\\"vh\\\":1}\""
        assertNull(BrowserScreenshotTranslate.parseMetrics(tiny))
        assertNull(BrowserScreenshotTranslate.parseMetrics(null))
        assertNull(BrowserScreenshotTranslate.parseMetrics("garbage"))
    }

    @Test
    fun anchorJs_embedsViewportGeometryAndMarkerClass() {
        val js = BrowserScreenshotTranslate.anchorJs(geometry(anchoredToElement = true, y = 120))
        assertTrue(js.contains("data-mt-id=\"${BrowserScreenshotTranslate.ANCHOR_ID}\""))
        assertTrue(js.contains("left:0px;top:120px"))
        assertTrue(js.contains("width:1080px;height:2000px"))
        // 只量几何不显示；类名挂 __mt_overlay 让 REMOVE_JS 能把它与场景根一起摘掉。
        assertTrue(js.contains("visibility:hidden"))
        assertTrue(js.contains("className='__mt_overlay'"))
        // 宿主存在时挂进宿主（跟内容一起滚），并把宿主补成定位元素。
        assertTrue(js.contains("host.style.position='relative'"))
    }

    @Test
    fun overlayJs_pointsAtMtlocalChannelAndReplacesInPlace() {
        val js = BrowserScreenshotTranslate.overlayJs(
            BrowserScreenshotTranslate.PNG_TOKEN, geometry(x = 5, y = 6)
        )
        assertTrue(js.contains("https://mtlocal.overlay/${BrowserScreenshotTranslate.PNG_TOKEN}.png"))
        assertTrue(js.contains("left:5px;top:6px"))
        // 与图链路整图贴片同一套替换规则（data-mt-ov-for 原地换旧）。
        assertTrue(js.contains("data-mt-ov-for=\"${BrowserScreenshotTranslate.ANCHOR_ID}\""))
        assertFalse(js.contains("visibility:hidden"))
    }

    @Test
    fun pngToken_isReusedSoRepeatedShotsDoNotAccumulateMemory() {
        // BrowserOverlayImageStore.put 同 key 会顶掉旧字节：token 必须是常量而不是每张新生。
        assertEquals("shotpng", BrowserScreenshotTranslate.PNG_TOKEN)
    }
}
