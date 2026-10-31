package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ML Kit 跨拼接图内部切缝的段落块拆分（2026-11-01）。
 *
 * 真机背景：一个 ML Kit 段落块可能同时含缝上/缝下两行，交给气泡归属层后只能整体
 * 落到一侧，另一侧再翻一遍 → 接缝重复/漏翻。这里用 `lineBoxes` 按行中心拆开。
 */
class MlKitSeamSplitterTest {

    @Test
    fun splitsParagraphBlockAtInternalSeam() {
        val block = block(
            text = "上半句\n下半句\n第三行",
            lineBoxes = listOf(
                floatArrayOf(100f, 100f, 500f, 140f),
                floatArrayOf(100f, 160f, 500f, 200f),
                floatArrayOf(100f, 220f, 500f, 260f)
            )
        )

        val split = MlKitSeamSplitter.split(listOf(block), listOf(150f))

        assertEquals(2, split.size)
        assertEquals("上半句", split[0].text)
        assertEquals("下半句\n第三行", split[1].text)
        assertEquals(1, split[0].lineCount)
        assertEquals(2, split[1].lineCount)
        assertEquals(100f, split[0].top, 0.01f)
        assertEquals(140f, split[0].bottom, 0.01f)
        assertEquals(160f, split[1].top, 0.01f)
        assertEquals(260f, split[1].bottom, 0.01f)
    }

    @Test
    fun keepsBlockWhenAllLinesOnSameSideOfSeam() {
        val block = block(
            text = "第一行\n第二行",
            lineBoxes = listOf(
                floatArrayOf(100f, 100f, 500f, 140f),
                floatArrayOf(100f, 160f, 500f, 200f)
            )
        )
        val split = MlKitSeamSplitter.split(listOf(block), listOf(300f))
        assertEquals(1, split.size)
        assertEquals(block.text, split[0].text)
    }

    @Test
    fun keepsBlockWhenLineBoxesDoNotMatchTextLines() {
        // 拆不了就原样保留，绝不猜文本（避免把一句拆坏）。
        val block = block(
            text = "只有一行",
            lineBoxes = listOf(
                floatArrayOf(100f, 100f, 500f, 140f),
                floatArrayOf(100f, 160f, 500f, 200f)
            )
        )
        val split = MlKitSeamSplitter.split(listOf(block), listOf(150f))
        assertEquals(1, split.size)
        assertEquals("只有一行", split[0].text)
    }

    @Test
    fun noSeamOrNoLineBoxesReturnsInput() {
        val block = block(text = "第一行\n第二行", lineBoxes = null)
        assertTrue(MlKitSeamSplitter.split(listOf(block), emptyList()).single() === block)
        assertTrue(MlKitSeamSplitter.split(listOf(block), listOf(150f)).single() === block)
    }

    private fun block(text: String, lineBoxes: List<FloatArray>?): TextBlock {
        val boxes = lineBoxes.orEmpty()
        return TextBlock(
            id = 1,
            text = text,
            left = boxes.minOfOrNull { it[0] } ?: 100f,
            top = boxes.minOfOrNull { it[1] } ?: 100f,
            right = boxes.maxOfOrNull { it[2] } ?: 500f,
            bottom = boxes.maxOfOrNull { it[3] } ?: 200f,
            confidence = 0f,
            lineCount = boxes.size.coerceAtLeast(1),
            lineBoxes = lineBoxes
        )
    }
}
