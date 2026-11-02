package com.manga.translator.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixivViewerSupportTest {

    @Test
    fun viewerUrlOnlyMatchesPixivComicViewer() {
        assertTrue(
            PixivViewerSupport.isViewerUrl(
                "https://comic-store-viewer.pixiv.net/static/viewer?cid=zyuresgpl&lin=0"
            )
        )
        assertFalse(PixivViewerSupport.isViewerUrl("https://www.pixiv.net/artworks/123"))
        assertFalse(
            PixivViewerSupport.isViewerUrl(
                "https://comic-store-viewer.pixiv.net.evil.com/static/viewer?cid=1"
            )
        )
        assertFalse(PixivViewerSupport.isViewerUrl("about:blank"))
        assertFalse(PixivViewerSupport.isViewerUrl(null))
    }

    @Test
    fun parsesProbeWithFullGeometry() {
        val probe = PixivViewerSupport.parseProbe(
            JSONObject(
                """
                {
                  "ok": true, "page": 6, "total": 184, "cid": "zyuresgpl",
                  "hasNext": true, "nextReady": true, "isDouble": false,
                  "cw": 1079, "ch": 1924, "vpid": "viewport0",
                  "dr": {"x": 0, "y": 195, "w": 1079, "h": 1535},
                  "cr": {"x": 0, "y": 0, "w": 411, "h": 733},
                  "vp": {"x": 0, "y": 0, "w": 411, "h": 733}
                }
                """.trimIndent()
            )
        )
        assertNotNull(probe)
        probe!!
        assertEquals(6, probe.page)
        assertEquals(184, probe.total)
        assertTrue(probe.hasNext)
        assertTrue(probe.nextReady)
        assertFalse(probe.isDoublePage)
        assertEquals("zyuresgpl", probe.contentKey)
        assertEquals(1079, probe.canvasWidth)
        assertEquals(1924, probe.canvasHeight)
        assertEquals(195f, probe.drawnRect.y, 0.01f)
        assertEquals(1535f, probe.drawnRect.height, 0.01f)
        assertEquals(411f, probe.canvasRect.width, 0.01f)
        assertEquals("viewport0", probe.viewportId)
        assertNotNull(probe.viewportRect)
    }

    @Test
    fun rejectsBadProbe() {
        assertNull(PixivViewerSupport.parseProbe(null))
        assertNull(PixivViewerSupport.parseProbe(JSONObject("""{"ok":false,"reason":"no_viewer"}""")))
        assertNull(
            PixivViewerSupport.parseProbe(
                JSONObject(
                    """{"ok":true,"page":-1,"total":184,"cw":1079,"ch":1924,
                       "dr":{"x":0,"y":0,"w":10,"h":10},"cr":{"x":0,"y":0,"w":10,"h":10}}"""
                )
            )
        )
        assertNull(
            PixivViewerSupport.parseProbe(
                JSONObject(
                    """{"ok":true,"page":6,"total":184,"cw":1079,"ch":1924,
                       "dr":{"x":0,"y":0,"w":0,"h":10},"cr":{"x":0,"y":0,"w":10,"h":10}}"""
                )
            )
        )
    }

    @Test
    fun probeWithoutViewportFallsBackToZeroOrigin() {
        val probe = PixivViewerSupport.parseProbe(
            JSONObject(
                """{"ok":true,"page":6,"total":184,"hasNext":true,"nextReady":false,
                   "cw":1079,"ch":1924,
                   "dr":{"x":0,"y":195,"w":1079,"h":1535},
                   "cr":{"x":0,"y":0,"w":411,"h":733}}"""
            )
        )
        assertNotNull(probe)
        assertNull(probe!!.viewportRect)
    }

    @Test
    fun anchorRectMapsDrawnRectIntoViewportCssCoordinates() {
        val probe = PixivViewerProbe(
            page = 6,
            total = 184,
            hasNext = true,
            nextReady = true,
            isDoublePage = false,
            contentKey = "zyuresgpl",
            canvasWidth = 1079,
            canvasHeight = 1924,
            drawnRect = PixivViewerRect(0f, 195f, 1079f, 1535f),
            canvasRect = PixivViewerRect(10f, 20f, 411f, 733f),
            viewportId = "viewport1",
            viewportRect = PixivViewerRect(10f, 20f, 411f, 733f)
        )
        val rect = PixivViewerSupport.anchorRect(probe)
        assertNotNull(rect)
        rect!!
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(195f * 733f / 1924f, rect.top, 0.01f)
        assertEquals(411f, rect.width, 0.01f)
        assertEquals(1535f * 733f / 1924f, rect.height, 0.01f)
    }

    @Test
    fun anchorRectRejectsInvalidGeometry() {
        val probe = PixivViewerProbe(
            page = 6,
            total = 184,
            hasNext = true,
            nextReady = true,
            isDoublePage = false,
            contentKey = "zyuresgpl",
            canvasWidth = 0,
            canvasHeight = 1924,
            drawnRect = PixivViewerRect(0f, 195f, 1079f, 1535f),
            canvasRect = PixivViewerRect(0f, 0f, 411f, 733f),
            viewportId = "viewport0",
            viewportRect = null
        )
        assertNull(PixivViewerSupport.anchorRect(probe))
    }

    @Test
    fun tokenOfOnlyAcceptsMtlocalOverlayUrls() {
        assertEquals("c123", PixivViewerSupport.tokenOf("https://mtlocal.overlay/c123.jpg"))
        assertEquals("s456", PixivViewerSupport.tokenOf("https://mtlocal.overlay/s456.png"))
        assertNull(PixivViewerSupport.tokenOf("https://example.com/c123.jpg"))
        assertNull(PixivViewerSupport.tokenOf(null))
    }

    @Test
    fun nextCaptureScriptUsesHiddenNextBufferWithoutTouchingVisiblePage() {
        val script = PixivViewerScript.captureStartJs("next")
        assertTrue(script.contains("renderNextLeft"))
        assertTrue(script.contains("R.o5x"))
        assertTrue(script.contains("leftDrawnRect"))
        assertTrue(script.contains("__mtPixivCaptureStore"))
    }

    @Test
    fun chunkScriptCarriesOffsetAndLength() {
        val script = PixivViewerScript.captureChunkJs(offset = 350_000, len = 123)
        assertTrue(script.contains("at = 350000"))
        assertTrue(script.contains("size = 123"))
    }

    @Test
    fun releaseSceneImagesDropsCleanedAndPatchTokens() {
        BrowserOverlayImageStore.clear()
        BrowserOverlayImageStore.put("c1", byteArrayOf(1), "image/jpeg")
        BrowserOverlayImageStore.put("c2", byteArrayOf(2), "image/jpeg")
        BrowserOverlayImageStore.put("s1", byteArrayOf(3), "image/png")
        val scene = BrowserSceneBuilder.Scene(
            cleanedUrl = "https://mtlocal.overlay/c1.jpg",
            pageCleanedUrl = "https://mtlocal.overlay/c2.jpg",
            bubbles = listOf(
                BrowserSceneBuilder.SceneBubble(
                    id = "b0",
                    x = 1f, y = 2f, w = 3f, h = 4f,
                    originalText = "原文",
                    translatedText = "译文",
                    sourceLineCount = 1,
                    vertical = false,
                    alignment = "center",
                    bgType = "solid",
                    bgColorHex = "#ffffff",
                    textColorHex = "#000000",
                    strokeColorHex = "#000000",
                    fontHeightPercent = 5f,
                    patchUrl = "https://mtlocal.overlay/s1.png"
                )
            )
        )

        PixivViewerSupport.releaseSceneImages(scene)

        assertNull(BrowserOverlayImageStore.get("c1"))
        assertNull(BrowserOverlayImageStore.get("c2"))
        assertNull(BrowserOverlayImageStore.get("s1"))
        BrowserOverlayImageStore.clear()
    }

    @Test
    fun filterTranslatedSceneDropsBubblesThatWereNotActuallyTranslated() {
        val scene = BrowserSceneBuilder.Scene(
            cleanedUrl = "https://mtlocal.overlay/c1.jpg",
            bubbles = listOf(
                bubble("ホストと社畜と目玉焼き", "Host and Corporate Slave and Fried Egg"),
                bubble("第1話", "第1話"),
                bubble("ホストと社畜とネコ", "ホストと社畜とネコ"),
                bubble("003", "003")
            )
        )

        val filtered = PixivViewerSupport.filterTranslatedScene(scene)

        assertEquals(1, filtered.bubbles.size)
        assertEquals("Host and Corporate Slave and Fried Egg", filtered.bubbles[0].translatedText)
    }

    @Test
    fun filterTranslatedSceneNormalizesWhitespace() {
        val scene = BrowserSceneBuilder.Scene(
            cleanedUrl = null,
            bubbles = listOf(bubble("A\nB", " A  B "))
        )
        assertEquals(0, PixivViewerSupport.filterTranslatedScene(scene).bubbles.size)
    }

    private fun bubble(original: String, translated: String): BrowserSceneBuilder.SceneBubble =
        BrowserSceneBuilder.SceneBubble(
            id = "b",
            x = 1f, y = 2f, w = 3f, h = 4f,
            originalText = original,
            translatedText = translated,
            sourceLineCount = 1,
            vertical = false,
            alignment = "center",
            bgType = "solid",
            bgColorHex = "#ffffff",
            textColorHex = "#000000",
            strokeColorHex = "#000000",
            fontHeightPercent = 5f
        )

    @Test
    fun anchorScriptTargetsViewportAndDedicatedAnchorId() {
        val probe = PixivViewerProbe(
            page = 6,
            total = 184,
            hasNext = true,
            nextReady = true,
            isDoublePage = false,
            contentKey = "zyuresgpl",
            canvasWidth = 1079,
            canvasHeight = 1924,
            drawnRect = PixivViewerRect(0f, 195f, 1079f, 1535f),
            canvasRect = PixivViewerRect(0f, 0f, 411f, 733f),
            viewportId = "viewport1",
            viewportRect = PixivViewerRect(0f, 0f, 411f, 733f)
        )
        val script = PixivViewerSupport.anchorJs("mt-pixiv-page", probe)
        assertNotNull(script)
        assertTrue(script!!.contains("viewport1"))
        assertTrue(script.contains("mt-pixiv-page"))
        assertTrue(script.contains("position:absolute"))
    }
}
