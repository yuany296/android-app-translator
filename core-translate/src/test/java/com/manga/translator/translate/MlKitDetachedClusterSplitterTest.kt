package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拟声词分散合框拆分（2026-11-03，真机 pageHash `4bee1031d4…`）。
 *
 * 现场：ML Kit 把 `앗` 和 `쪽` 放进同一个 block 的同一行；块级框高 288px，
 * 下游当成一块 35% 页高的文字，盖板/清理图遮住一大片画面。
 */
class MlKitDetachedClusterSplitterTest {

    @Test
    fun splitsWidelySpacedElementsInSameLine() {
        val block = block(
            id = 0,
            text = "앗 쪽",
            box = intArrayOf(208, 423, 306, 711),
            elements = listOf(
                element("앗", 209, 452, 306, 544, line = 0),
                element("쪽", 208, 625, 305, 711, line = 0)
            )
        )

        val result = MlKitDetachedClusterSplitter.split(listOf(block))

        assertEquals(2, result.size)
        assertEquals(setOf("앗", "쪽"), result.map { it.text }.toSet())
        assertTrue(result.all { it.splitGroupId == result.first().splitGroupId })
        // 两个碎片的框都只包自己，不再是 98x288 的并集。
        assertEquals(92f, result.first { it.text == "앗" }.bottom - 452f, 0.1f)
        assertEquals(86f, result.first { it.text == "쪽" }.bottom - 625f, 0.1f)
    }

    @Test
    fun keepsAdjacentWordsOnSameLineTogether() {
        val block = block(
            id = 0,
            text = "Hello world",
            box = intArrayOf(100, 100, 320, 150),
            elements = listOf(
                element("Hello", 100, 100, 200, 150, line = 0),
                element("world", 210, 100, 320, 150, line = 0)
            )
        )

        val result = MlKitDetachedClusterSplitter.split(listOf(block))

        assertEquals(1, result.size)
        assertEquals("Hello world", result.first().text)
    }

    @Test
    fun keepsNormalMultiLineParagraphTogether() {
        val block = block(
            id = 0,
            text = "안녕\n하세요",
            box = intArrayOf(100, 100, 260, 220),
            elements = listOf(
                element("안녕", 100, 100, 250, 150, line = 0),
                element("하세요", 100, 160, 260, 220, line = 1)
            )
        )

        val result = MlKitDetachedClusterSplitter.split(listOf(block))

        assertEquals(1, result.size)
        assertEquals("안녕\n하세요", result.first().text)
    }

    @Test
    fun dropsFragmentContainedInAnotherBlock() {
        val merged = block(
            id = 0,
            text = "앗 쪽",
            box = intArrayOf(208, 423, 306, 711),
            elements = listOf(
                element("앗", 209, 452, 306, 544, line = 0),
                element("쪽", 208, 625, 305, 711, line = 0)
            )
        )
        val full = block(
            id = 1,
            text = "하앗",
            box = intArrayOf(128, 452, 308, 544),
            elements = listOf(element("하앗", 128, 452, 308, 544, line = 0))
        )

        val result = MlKitDetachedClusterSplitter.split(listOf(merged, full))

        assertEquals(setOf("쪽", "하앗"), result.map { it.text }.toSet())
    }

    @Test
    fun doesNotSplitSingleElementBlock() {
        val block = block(
            id = 0,
            text = "하앗",
            box = intArrayOf(128, 452, 308, 544),
            elements = listOf(element("하앗", 128, 452, 308, 544, line = 0))
        )

        val result = MlKitDetachedClusterSplitter.split(listOf(block))

        assertEquals(1, result.size)
        assertEquals(block, result.first())
    }

    @Test
    fun splitFragmentsAreNeverMergedBackByRules() {
        val a = block(
            id = 0,
            text = "앗",
            box = intArrayOf(209, 452, 306, 544),
            elements = emptyList(),
            groupId = "g"
        )
        val b = block(
            id = 1,
            text = "쪽",
            box = intArrayOf(208, 625, 305, 711),
            elements = emptyList(),
            groupId = "g"
        )

        val decision = OcrBlockMergeRules.shouldMerge(a, b)

        assertFalse(decision.merge)
        assertEquals(OcrMergeReason.DETACHED_CLUSTER, decision.reason)
    }

    private fun element(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        line: Int
    ) = TextElement(
        text = text,
        left = left.toFloat(),
        top = top.toFloat(),
        right = right.toFloat(),
        bottom = bottom.toFloat(),
        lineIndex = line
    )

    private fun block(
        id: Int,
        text: String,
        box: IntArray,
        elements: List<TextElement>,
        groupId: String? = null
    ) = TextBlock(
        id = id,
        text = text,
        left = box[0].toFloat(),
        top = box[1].toFloat(),
        right = box[2].toFloat(),
        bottom = box[3].toFloat(),
        lineCount = elements.map { it.lineIndex }.distinct().size.coerceAtLeast(1),
        lineBoxes = elements.map { floatArrayOf(it.left, it.top, it.right, it.bottom) }
            .takeIf { it.isNotEmpty() },
        elements = elements.takeIf { it.isNotEmpty() },
        splitGroupId = groupId
    )
}
