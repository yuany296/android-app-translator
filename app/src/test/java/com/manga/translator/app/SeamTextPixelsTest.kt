package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接缝接收页「有没有文字像素」三态（参考项目 `hasTextPixels`）。
 *
 * 第 2 阶段的核心约定：只有显式 `false` 才让接收侧跳过擦字/mask/盖板/贴片/译文；
 * `null`（老数据、没有内容框）必须按 true 处理，不能把 null 塌成 false。
 */
class SeamTextPixelsTest {

    private val pageWidth = 800f
    private val pageHeight = 1400f

    private fun resolve(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Boolean? = SeamTextPixels.resolveOnPage(
        contentLeft = left,
        contentTop = top,
        contentRight = right,
        contentBottom = bottom,
        pageWidth = pageWidth,
        pageHeight = pageHeight
    )

    @Test
    fun contentCrossingFromPreviousPageHasTextPixels() {
        // 顶边在上一张（负），底边落在本页前 20px → 本页确实有文字。
        assertEquals(true, resolve(300f, -50f, 700f, 20f))
    }

    @Test
    fun contentEntirelyAbovePageHasNoTextPixels() {
        // 只有外框/容器跨缝，文字框整块在上一张。
        assertEquals(false, resolve(300f, -200f, 700f, -2f))
        assertEquals(false, resolve(300f, -200f, 700f, 0f))
    }

    @Test
    fun contentEntirelyBelowPageHasNoTextPixels() {
        assertEquals(false, resolve(300f, pageHeight + 1f, 700f, pageHeight + 100f))
    }

    @Test
    fun contentOutsidePageHorizontallyHasNoTextPixels() {
        assertEquals(false, resolve(-200f, 100f, 0f, 300f))
        assertEquals(false, resolve(pageWidth + 1f, 100f, pageWidth + 200f, 300f))
    }

    @Test
    fun missingOrDegenerateContentIsUnknownNotNullFalse() {
        assertNull("零宽内容框 = 没有内容框，不能猜成 false", resolve(300f, 100f, 300f, 300f))
        assertNull("零高内容框 = 没有内容框，不能猜成 false", resolve(300f, 100f, 700f, 100f))
        assertNull(
            "非有限值/非法页面尺寸 = 未知",
            SeamTextPixels.resolveOnPage(0f, 0f, 10f, 10f, 0f, pageHeight)
        )
    }

    @Test
    fun onlyExplicitFalseRemovesTextContribution() {
        assertTrue(SeamTextPixels.contributesText(true))
        assertTrue("null 必须按 true（老数据）", SeamTextPixels.contributesText(null))
        assertFalse(SeamTextPixels.contributesText(false))

        assertFalse(SeamTextPixels.isDeclaredNoText(true))
        assertFalse(SeamTextPixels.isDeclaredNoText(null))
        assertTrue(SeamTextPixels.isDeclaredNoText(false))
    }
}
