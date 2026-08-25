package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleOcrBlockMergerTest {

    @Test
    fun mergesHorizontalWordFragmentsIntoLine() {
        // 同一行被拆成多个短块（模拟 PaddleOCR 对韩文空格的断裂）
        val blocks = listOf(
            tb(0, "뭔가에", 100f, 100f, 220f, 160f),
            tb(1, "확", 240f, 100f, 300f, 160f),
            tb(2, "꽂혀본", 320f, 100f, 460f, 160f),
            tb(3, "적", 480f, 100f, 540f, 160f)
        )
        val merged = PaddleOcrBlockMerger.mergeBlocks(blocks, 1080, 2400)
        assertEquals(1, merged.size)
        assertTrue(merged[0].text.contains("뭔가에"))
        assertTrue(merged[0].text.contains("확"))
        assertTrue(merged[0].text.contains("꽂혀본"))
        assertTrue(merged[0].text.contains("적"))
    }

    @Test
    fun mergesTwoLinesIntoParagraph() {
        // 两行属于同一句对白，应该合并为一个带换行的块
        val blocks = listOf(
            tb(0, "뭔가에 확 꽂혀본 적", 100f, 100f, 600f, 160f),
            tb(1, "있는가?", 300f, 180f, 520f, 240f)
        )
        val merged = PaddleOcrBlockMerger.mergeBlocks(blocks, 1080, 2400)
        assertEquals(1, merged.size)
        assertTrue(merged[0].text.contains("뭔가에"))
        assertTrue(merged[0].text.contains("있는가"))
    }

    @Test
    fun keepsFarApartBlocksSeparate() {
        // 三个不相关的文本块不应被合并
        val blocks = listOf(
            tb(0, "뭔가에", 100f, 100f, 220f, 160f),
            tb(1, "단순히", 400f, 800f, 560f, 860f),
            tb(2, "재밌네", 300f, 1800f, 500f, 1900f)
        )
        val merged = PaddleOcrBlockMerger.mergeBlocks(blocks, 1080, 2400)
        assertEquals(3, merged.size)
    }

    private fun tb(id: Int, text: String, left: Float, top: Float, right: Float, bottom: Float): TextBlock {
        return TextBlock(
            id = id,
            text = text,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            confidence = 0.9f
        )
    }
}
