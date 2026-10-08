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

    @Test
    fun singleBlockKeepsPolygon() {
        // 单块：四边形直接透传，渲染层可以用它把斜排遮罩一起倾斜。
        val quad = listOf(QuadPoint(0f, 0f), QuadPoint(100f, 10f), QuadPoint(100f, 60f), QuadPoint(0f, 50f))
        val blocks = listOf(
            tb(0, " tilted ", 0f, 0f, 100f, 60f).copy(polygon = quad)
        )
        val merged = PaddleOcrBlockMerger.mergeBlocks(blocks, 1080, 2400)
        assertEquals(1, merged.size)
        assertEquals(quad, merged[0].polygon)
    }

    @Test
    fun mergedGroupDropsPolygonToAvoidWrongTilt() {
        // 多框合并出来的段落一定是轴对齐的，硬带其中一个框的倾角会让遮罩歪掉。
        val quad = listOf(QuadPoint(0f, 0f), QuadPoint(100f, 10f), QuadPoint(100f, 60f), QuadPoint(0f, 50f))
        val blocks = listOf(
            tb(0, " first ", 0f, 0f, 100f, 60f).copy(polygon = quad),
            tb(1, " second ", 0f, 80f, 100f, 140f).copy(polygon = quad)
        )
        val merged = PaddleOcrBlockMerger.mergeBlocks(blocks, 1080, 2400)
        assertEquals(1, merged.size)
        assertEquals(null, merged[0].polygon)
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

    // ------------------------------------------------------------------
    // 方向判定的两个方向都要守住（2026-09-30 用户反馈）
    //
    // 症状：横排三行韩文被当成竖排，原文在翻译前就被按**列序**打乱 ——
    // 译文成了「捕, 追术教导技将它NPC唯一的那」。
    //
    // 根因：`mergeGroupToBlock` 过去只用 `isVerticalBlock(sorted.first())`（宽高比 ≥1.15）
    // 决定拼接顺序；横排三行合成的大框就能满足这个松阈值。
    //
    // 所以这组测试必须**同时**覆盖：
    //   · 横排多行 → 按行从上到下、不得按列打乱；
    //   · 真竖排（并排的窄列）→ 仍按列、仍右→左。**不能为了修前者而牺牲后者**。
    // ------------------------------------------------------------------

    @Test
    fun threeHorizontalLinesKeepRowMajorReadingOrder() {
        // 复现用户那个气泡：三行横排，行内左→右，行间上→下。
        // 三行横向跨度相近 → 是「Y 方向堆叠」而非「X 方向并排的列」。
        val blocks = listOf(
            tb(0, "그 용을 사냥하는 기술을", 120f, 100f, 620f, 170f),
            tb(1, "가르칠 수 있는 유일한 NPC가", 120f, 190f, 620f, 260f),
            tb(2, "눈 앞의 여자야이라고 했다", 120f, 280f, 620f, 350f)
        )
        val merged = PaddleOcrBlockMerger.mergeBlocks(blocks, 1080, 2400)

        assertEquals("三行横排应合并成一个段落", 1, merged.size)
        assertEquals(
            "必须保持「行内左→右、行间上→下」，不得按列打乱",
            "그 용을 사냥하는 기술을 가르칠 수 있는 유일한 NPC가 눈 앞의 여자야이라고 했다",
            merged[0].text
        )
    }

    @Test
    fun singleWideBlockIsNotTreatedAsVertical() {
        // Paddle 常把整段识别成**一个大块**：宽 500 × 高 210（宽高比 0.42）。
        // 旧阈值 1.15 下这种块不会判竖排，但 1.15 太松 —— 只要块稍高一点就会翻车，
        // 所以这里锁住「宽扁的单块一定走横排」这个语义。
        val blocks = listOf(
            tb(0, "그 용을 사냥하는 기술을 가르칠 수 있는 유일한 NPC가", 120f, 100f, 620f, 310f)
        )
        val merged = PaddleOcrBlockMerger.mergeBlocks(blocks, 1080, 2400)
        assertEquals(1, merged.size)
        // 单块本来就原样返回；这里断言的是**没有发生列序重排**（原文首尾仍在原位）。
        assertTrue(merged[0].text.startsWith("그 용을"))
        assertTrue(merged[0].text.endsWith("NPC가"))
    }

    @Test
    fun verticalColumnBlocksStillMergeRightToLeft() {
        // 真竖排：两个窄列并排（横向几乎不重叠、纵向高度重叠）→ 仍须合并、仍按右→左。
        // 这条是「不许为了修横排而牺牲真竖排」的守卫。
        val blocks = listOf(
            tb(0, "右列", 300f, 100f, 340f, 300f),
            tb(1, "左列", 250f, 100f, 290f, 300f)
        )
        val merged = PaddleOcrBlockMerger.mergeBlocks(blocks, 1080, 2400)
        assertEquals("并排的窄列应合并", 1, merged.size)
        assertEquals("竖排按右→左读", "右列左列", merged[0].text)
    }

    // ------------------------------------------------------------------
    // 行数随块带出（2026-10-03）
    //
    // 合并后的文本用空格连接、**没有换行符**，下游「行高 = 框高 ÷ 行数」只能靠
    // `TextBlock.lineCount`；缺了它行高就退化成整块高度 → 合并阈值放大数倍，
    // 两个独立气泡会被并成一句（真机 `.verify/38_user.logcat` 现场）。
    // ------------------------------------------------------------------

    @Test
    fun mergedParagraphCarriesItsLineCount() {
        val blocks = listOf(
            tb(0, "뭔가에 확 꽂혀본 적", 100f, 100f, 600f, 160f),
            tb(1, "있는가?", 300f, 180f, 520f, 240f)
        )
        val merged = PaddleOcrBlockMerger.mergeBlocks(blocks, 1080, 2400)
        assertEquals(1, merged.size)
        assertEquals("两行段落块必须带出 2 行", 2, merged[0].lineCount)
        assertEquals("行高 = 框高 ÷ 行数", 70f, TextLineGeometry.lineHeightPx(merged[0]), 0.01f)
    }

    @Test
    fun mergedVerticalColumnsReportSingleLine() {
        val blocks = listOf(
            tb(0, "右列", 300f, 100f, 340f, 300f),
            tb(1, "左列", 250f, 100f, 290f, 300f)
        )
        val merged = PaddleOcrBlockMerger.mergeBlocks(blocks, 1080, 2400)
        assertEquals(1, merged.size)
        // 竖排按 1 行处理，与 planner 的 `sourceLineCount = 1` 同口径；
        // 行高走「列宽」分支 —— 合并后拿到的是**两列的并集框**（宽 90），
        // 所以这里量到的是并集宽度而不是单列宽度（40）。
        // 多列竖排块的这点高估由字号闸（min/max 行高 ≥ 0.65）兜住：邻居块的
        // 行高若真是 40，比值 0.44 < 0.65 → 不会合并。
        assertEquals(1, merged[0].lineCount)
        assertEquals(90f, TextLineGeometry.lineHeightPx(merged[0]), 0.01f)
    }
}
