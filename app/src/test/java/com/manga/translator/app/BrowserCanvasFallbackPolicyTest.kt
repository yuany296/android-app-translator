package com.manga.translator.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCanvasFallbackPolicyTest {

    @Test
    fun pixivViewerFallsBackToScreenshot() {
        // comic-store-viewer.pixiv.net 实测：5 个 canvas + 5 个占位 img。
        assertTrue(shouldUseCanvasScreenshot(canvases = 5, images = 5))
        assertTrue(shouldUseCanvasScreenshot(canvases = 2, images = 0))
    }

    @Test
    fun normalImagePagesDoNotFallBack() {
        assertFalse(shouldUseCanvasScreenshot(canvases = 0, images = 20))
        assertFalse(shouldUseCanvasScreenshot(canvases = 1, images = 0))
        assertFalse(shouldUseCanvasScreenshot(canvases = 1, images = 100))
        assertFalse(shouldUseCanvasScreenshot(canvases = 2, images = 20))
    }
}
