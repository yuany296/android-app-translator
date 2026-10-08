package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原文对齐推断单测（2026-09-28，对齐 C:\homework\translator `inferTextAlignmentFromBoxes`）。
 *
 * 用户要求「左对齐、右对齐和居中需要区分」。
 */
class TextAlignmentTest {

    private fun line(left: Float, top: Float, right: Float, bottom: Float, text: String = "x"): TextBlock {
        return TextBlock(id = 0, text = text, left = left, top = top, right = right, bottom = bottom)
    }

    @Test
    fun shortSingleLineIsCentered() {
        assertEquals(TextAlignment.CENTER, TextAlignment.infer(listOf(line(100f, 100f, 300f, 160f)), 800f))
    }

    @Test
    fun equalLeftEdgesWithDifferentWidthsAreLeftAligned() {
        // 典型左对齐：左边缘齐，右边缘参差
        val blocks = listOf(
            line(80f, 100f, 500f, 160f),
            line(80f, 170f, 300f, 230f)
        )
        assertEquals(TextAlignment.LEFT, TextAlignment.infer(blocks, 800f))
    }

    @Test
    fun equalRightEdgesWithDifferentLeftsAreRightAligned() {
        val blocks = listOf(
            line(200f, 100f, 700f, 160f),
            line(400f, 170f, 700f, 230f)
        )
        assertEquals(TextAlignment.RIGHT, TextAlignment.infer(blocks, 800f))
    }

    @Test
    fun shortLineCenteredInsideLongLineIsCenterAligned() {
        // 参考项目特别强调的排版：长行 + 居中短行；左右边缘都不齐但中心线很稳
        val blocks = listOf(
            line(100f, 100f, 700f, 160f),
            line(320f, 170f, 480f, 230f)
        )
        assertEquals(TextAlignment.CENTER, TextAlignment.infer(blocks, 800f))
    }

    @Test
    fun chatRegionIsAlwaysLeftAligned() {
        val blocks = listOf(
            line(100f, 100f, 300f, 150f),
            line(140f, 160f, 340f, 210f)
        )
        assertEquals(TextAlignment.LEFT, TextAlignment.infer(blocks, 800f, regionType = "chat"))
    }

    @Test
    fun normalizeUnknownToCenter() {
        assertEquals(TextAlignment.CENTER, TextAlignment.normalize(null))
        assertEquals(TextAlignment.CENTER, TextAlignment.normalize(""))
        assertEquals(TextAlignment.CENTER, TextAlignment.normalize("justify"))
        assertEquals(TextAlignment.LEFT, TextAlignment.normalize("LEFT"))
        assertEquals(TextAlignment.RIGHT, TextAlignment.normalize(" Right "))
    }

    @Test
    fun referenceSpreadRuleIsMirrored() {
        // 参考项目同口径校验：左边缘齐、右边缘散 + 行宽差大 → 左对齐
        assertEquals(
            TextAlignment.LEFT,
            TextAlignment.infer(
                listOf(line(50f, 0f, 700f, 60f), line(50f, 70f, 200f, 130f)),
                800f
            )
        )
        // 左右都不齐、中心也不齐 → 落到「取分布最稳的那个」兜底规则（这里是左边缘最稳）
        assertEquals(
            TextAlignment.LEFT,
            TextAlignment.infer(
                listOf(line(50f, 0f, 200f, 60f), line(500f, 70f, 700f, 130f)),
                800f
            )
        )
    }

    @Test
    fun degenerateBoxesAreIgnored() {
        val blocks = listOf(
            line(80f, 100f, 500f, 160f),
            line(80f, 170f, 300f, 230f),
            line(10f, 10f, 10f, 10f) // 退化框
        )
        assertEquals(TextAlignment.LEFT, TextAlignment.infer(blocks, 800f))
    }

    @Test
    fun verticalTextProducesLeftOrRightNotCrash() {
        // 竖排（逐行框竖着排）不应抛异常，结果必须是三种之一
        val blocks = listOf(
            line(300f, 100f, 380f, 300f),
            line(300f, 320f, 380f, 520f)
        )
        val result = TextAlignment.infer(blocks, 800f)
        assertTrue(result in setOf(TextAlignment.LEFT, TextAlignment.CENTER, TextAlignment.RIGHT))
    }

    @Test
    fun bubbleDefaultAlignmentIsCenter() {
        val bubble = Bubble(
            x = 0f, y = 0f, w = 10f, h = 10f,
            bgType = "solid", originalText = "a", translatedText = "b"
        )
        assertEquals(TextAlignment.CENTER, bubble.alignment)
    }
}
