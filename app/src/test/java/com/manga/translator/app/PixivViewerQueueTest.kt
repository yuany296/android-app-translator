package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixivViewerQueueTest {

    private fun scene(text: String = "译文"): BrowserSceneBuilder.Scene =
        BrowserSceneBuilder.Scene(
            cleanedUrl = null,
            bubbles = listOf(
                BrowserSceneBuilder.SceneBubble(
                    id = "b0",
                    x = 1f, y = 2f, w = 3f, h = 4f,
                    originalText = "原文",
                    translatedText = text,
                    sourceLineCount = 1,
                    vertical = false,
                    alignment = "center",
                    bgType = "solid",
                    bgColorHex = "#ffffff",
                    textColorHex = "#000000",
                    strokeColorHex = "#000000",
                    fontHeightPercent = 5f
                )
            )
        )

    @Test
    fun currentPageHasPriorityThenNextPage() {
        val queue = PixivViewerQueue()
        queue.onPageChanged(6, hasNext = true)

        val first = queue.nextTask(inFlight = null)
        assertEquals(6, first?.page)
        assertEquals(PixivViewerTaskKind.CURRENT, first?.kind)

        queue.onTranslated(6, scene())

        val second = queue.nextTask(inFlight = null)
        assertEquals(7, second?.page)
        assertEquals(PixivViewerTaskKind.NEXT, second?.kind)

        queue.onTranslated(7, scene())
        assertNull(queue.nextTask(inFlight = null))
    }

    @Test
    fun pageTurnToCachedNextPageSkipsCurrentAndPrefetchesFollowing() {
        val queue = PixivViewerQueue()
        queue.onPageChanged(6, hasNext = true)
        queue.onTranslated(6, scene())
        queue.onTranslated(7, scene())

        queue.onPageChanged(7, hasNext = true)
        val task = queue.nextTask(inFlight = null)
        assertEquals(8, task?.page)
        assertEquals(PixivViewerTaskKind.NEXT, task?.kind)
    }

    @Test
    fun lastPageHasNoPrefetch() {
        val queue = PixivViewerQueue()
        queue.onPageChanged(183, hasNext = false)

        assertEquals(183, queue.nextTask(inFlight = null)?.page)
        queue.onTranslated(183, scene())
        assertNull(queue.prefetchPage)
        assertNull(queue.nextTask(inFlight = null))
    }

    @Test
    fun evictionAvoidsCurrentAndPrefetchPages() {
        val queue = PixivViewerQueue(cacheLimit = 2)
        queue.onPageChanged(6, hasNext = true)
        queue.onTranslated(6, scene())
        queue.onTranslated(7, scene())

        queue.onPageChanged(7, hasNext = true)
        val evicted = queue.onTranslated(8, scene())

        assertEquals(1, evicted.size)
        assertNull(queue.cached(6))
        assertTrue(queue.cached(7) != null)
        assertTrue(queue.cached(8) != null)
    }

    @Test
    fun markEmptyPreventsRetry() {
        val queue = PixivViewerQueue()
        queue.onPageChanged(6, hasNext = true)
        queue.markEmpty(6)

        val task = queue.nextTask(inFlight = null)
        assertEquals(7, task?.page)
        assertEquals(PixivViewerTaskKind.NEXT, task?.kind)
    }

    @Test
    fun clearCacheDropsCrossChapterScenesAndResetsPageState() {
        val queue = PixivViewerQueue()
        queue.onPageChanged(6, hasNext = true)
        queue.onTranslated(6, scene())
        queue.onTranslated(7, scene())

        val evicted = queue.clearCache()

        assertEquals(2, evicted.size)
        assertNull(queue.cached(6))
        assertNull(queue.cached(7))
        assertEquals(-1, queue.currentPage)
        assertNull(queue.prefetchPage)
        assertNull(queue.nextTask(inFlight = null))
    }
}
