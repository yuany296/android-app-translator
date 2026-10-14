package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 均衡断行单测（2026-10-14）。
 *
 * 钉住用户真机反馈的两个毛病：
 * - 「讨｜厌它」：断点落在词中间 → 分词单元的边界永不断开；
 * - 「行只有一两个字」：贪心换行的末行孤字 → DP 均衡 + 单单元成行/末行过短罚。
 *
 * 另外钉住标点禁则（行首不出现收尾标点）与「行数不足/已含换行返回 null」。
 */
class BalancedLineBreakerTest {

    /** 注入式分词/测宽（与 Android/ICU 无关）：`|` 分隔词，每字符宽 10。 */
    private val measure: (String) -> Float = { text -> text.length.toFloat() * 10f }
    private val tokenize: (String) -> List<BalancedLineBreaker.Unit> = { text ->
        text.split("|")
            .filter { it.isNotEmpty() }
            .map { BalancedLineBreaker.Unit(it) }
    }

    private fun rows(text: String, lines: Int, maxWidth: Float = 1000f): List<String>? =
        BalancedLineBreaker.breakLines(text, lines, maxWidth, measure) { tokenize(it) }

    @Test
    fun breakLines_neverSplitsAUnit() {
        val text = "一二三|四五|六七|八九|十零一"
        val rows = requireNotNull(rows(text, 2))
        assertEquals(2, rows.size)
        assertEquals("一二三四五六七八九十零一", rows.joinToString(""))
        assertTrue(rows.all { it.isNotEmpty() })
    }

    @Test
    fun breakLines_forbidsClosingPunctuationAtLineStart() {
        val rows = requireNotNull(rows("一二三四五|！|六七八九十", 2))
        assertTrue("行首不该是收尾标点：$rows", !rows[1].startsWith("！"))
    }

    @Test
    fun breakLines_prefersBreakingAfterPunctuation() {
        val rows = requireNotNull(rows("今天天气很好|，|我们一起出去玩吧", 2))
        assertTrue("断点应落在逗号之后：$rows", rows[0].endsWith("，"))
    }

    @Test
    fun breakLines_balancesInsteadOfLeavingAShortLastLine() {
        val text = (0 until 11).joinToString("|") { "字$it" }
        val rows = requireNotNull(rows(text, 2))
        assertTrue("末行不该只剩一两个字：$rows", rows[1].length >= 4)
    }

    @Test
    fun breakLines_rejectsSingleLineAndHardBrokenText() {
        assertNull(BalancedLineBreaker.breakLines("一二三", 1, 500f, measure) { tokenize(it) })
        assertNull(BalancedLineBreaker.breakLines("已有\n换行", 2, 500f, measure) { tokenize(it) })
    }

    @Test
    fun breakLines_rejectsWhenThereAreFewerUnitsThanLines() {
        assertNull(BalancedLineBreaker.breakLines("一二|三四", 3, 500f, measure) { tokenize(it) })
    }

    @Test
    fun countGreedyLines_countsHardLinesAndWraps() {
        val layout = BalancedLineBreaker.countGreedyLines("一二三|四五六|七八九十", 50f, measure) { tokenize(it) }
        assertEquals(3, layout)
    }

    @Test
    fun fallbackSegment_splitsCjkPerCharAndLatinBySpace() {
        val units = BalancedLineBreaker.fallbackSegment("你好 world")
        assertEquals(listOf("你", "好", "world"), units.map { it.text })
        assertTrue(units[1].sepAfter == " ")
        assertTrue(units.all { it.wordLike })
    }

    @Test
    fun splitOverwideUnits_splitsLongCjkWordButKeepsLatinWord() {
        val units = listOf(
            BalancedLineBreaker.Unit("这是一个很长的词"),
            BalancedLineBreaker.Unit("unbreakablelongword")
        )
        val split = BalancedLineBreaker.splitOverwideUnits(units, 50f, measure)
        // CJK 拆成单字（8 个字）+ 拉丁词原样 = 9 个单元
        assertEquals(9, split.size)
        assertEquals("unbreakablelongword", split.last().text)
    }
}
