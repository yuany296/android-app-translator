package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 晚邻接竞态「接缝带补建」单测（2026-10-14）。
 *
 * 钉住用户截图那两种形态：
 * - **能匹配**：上一张贴底的泡（A）+ 当前贴顶的泡（B）、文本 A 是 B 的子串 → 给出带几何
 *   （跨两张图）与两个待摘 id；
 * - **不误伤**：贴边但不是同一句 / 不贴边 → 不匹配（宁可不补带，也不能乱摘泡）。
 */
class SeamRaceBandTest {

    private fun candidate(
        id: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        text: String,
        coverBox: FloatArray? = null
    ) = SeamRaceBand.Candidate(id = id, x = x, y = y, w = w, h = h, originalText = text, coverBox = coverBox)

    private fun match(
        previous: List<SeamRaceBand.Candidate>,
        current: List<SeamRaceBand.Candidate>,
        prevW: Int = 1000,
        prevH: Int = 2000,
        curW: Int = 1000,
        curH: Int = 1500,
        blockers: List<SeamRaceBand.Candidate> = emptyList()
    ) = SeamRaceBand.findMatch(previous, current, prevW, prevH, curW, curH, blockers)

    @Test
    fun findMatch_matchesBottomBubbleWithTopBubbleByText() {
        val prev = candidate("b0", x = 20f, y = 88f, w = 50f, h = 12f, text = "케이지라고요!! 접속종료를 하려면 죽거나")
        val cur = candidate(
            "b1", x = 20f, y = 0f, w = 50f, h = 24f,
            text = "케이지라고요!! 접속종료를 하려면 죽거나 돌아가거나 두 가지밖에 없어"
        )
        val result = requireNotNull(match(listOf(prev), listOf(cur)))
        assertEquals("b0", result.previousId)
        assertEquals("b1", result.currentId)
        // 带要跨两张图：上端在 A 的泡上方（< A 底边 = 2000），下端伸进 B（> 2000）。
        assertTrue("bandTop=${result.bandTopPx}", result.bandTopPx < 2000)
        assertTrue("bandTop+height=${result.bandTopPx + result.bandHeightPx}",
            result.bandTopPx + result.bandHeightPx > 2000)
        assertTrue(result.textScore >= HandoffTextMatch.SAME_SENTENCE_RATIO)
    }

    @Test
    fun findMatch_acceptsRealRaceFixtureGeometry() {
        // 真机/模拟器实测（Todolist §41 后续，seam-race.html）：A 泡泡底 96.7%（盖板框底
        // 不在 100% 上）、B 泡泡顶 0.0% —— 3% 阈值会拒掉它；8% 阈值收得下，且文本分 0.94。
        val prev = candidate("b0", x = 33f, y = 78.3f, w = 49.5f, h = 18.4f, text = "케이지리고요!! 접속종료를 하려면 죽거")
        val cur = candidate(
            "b1", x = 32.6f, y = 0f, w = 50f, h = 31.3f,
            text = "케이지라고요!! 접속종로를 하려면 죽거나 접속게이트로 돌아가거나 두 가지 밖에 없는거야!!"
        )
        val result = requireNotNull(match(listOf(prev), listOf(cur)))
        assertEquals("b0", result.previousId)
        assertEquals("b1", result.currentId)
    }

    @Test
    fun findMatch_ignoresBubblesThatAreNotOnTheSeam() {
        val awayPrev = candidate("b0", x = 20f, y = 40f, w = 50f, h = 12f, text = "케이지라고요!! 접속종료를")
        val onSeamCur = candidate("b1", x = 20f, y = 0f, w = 50f, h = 24f, text = "케이지라고요!! 접속종료를 하려면")
        assertNull(match(listOf(awayPrev), listOf(onSeamCur)))

        val onSeamPrev = candidate("b0", x = 20f, y = 88f, w = 50f, h = 12f, text = "케이지라고요!! 접속종료를")
        val awayCur = candidate("b1", x = 20f, y = 30f, w = 50f, h = 24f, text = "케이지라고요!! 접속종료를 하려면")
        assertNull(match(listOf(onSeamPrev), listOf(awayCur)))
    }

    @Test
    fun findMatch_ignoresDifferentSentences() {
        val prev = candidate("b0", x = 20f, y = 88f, w = 50f, h = 12f, text = "전혀 다른 문장입니다")
        val cur = candidate("b1", x = 20f, y = 0f, w = 50f, h = 24f, text = "케이지라고요!! 접속종료를 하려면 죽거나")
        assertNull(match(listOf(prev), listOf(cur)))
    }

    @Test
    fun findMatch_unionsCoverBoxes() {
        val prev = candidate(
            "b0", x = 20f, y = 88f, w = 50f, h = 12f,
            text = "케이지라고요!! 접속종료를", coverBox = floatArrayOf(20f, 88f, 50f, 12f)
        )
        val cur = candidate(
            "b1", x = 22f, y = 0f, w = 46f, h = 24f,
            text = "케이지라고요!! 접속종료를 하려면 죽거나", coverBox = floatArrayOf(22f, 0f, 46f, 20f)
        )
        val result = requireNotNull(match(listOf(prev), listOf(cur)))
        // 盖板并集在带内：左不早于排版框左、右不晚于排版框右（两盖板都更紧）。
        assertTrue(result.coverBoxPercent[0] >= result.layoutBoxPercent[0] - 0.5f)
        assertTrue(result.coverBoxPercent[1] >= result.layoutBoxPercent[1] - 0.5f)
        assertEquals(4, result.coverBoxPercent.size)
    }

    @Test
    fun findMatch_clampsCoverAwayFromOtherBubbles() {
        // B 的认领框（0..31%）比真实气泡高，压到下一个气泡（24%..46%）的文字上：
        // 盖板必须收边到下一个气泡的顶部，不能把它盖掉一行。
        val prev = candidate("b0", x = 33f, y = 75f, w = 49f, h = 22f, text = "케이지라고요!! 접속종로를 하려면 죽거")
        val cur = candidate(
            "b1", x = 32.6f, y = 0f, w = 50f, h = 31.3f,
            text = "케이지라고요!! 접속종로를 하려면 죽거나 접속게이트로",
            coverBox = floatArrayOf(32.6f, 0f, 50f, 31.3f)
        )
        val next = candidate("b2", x = 12f, y = 24.3f, w = 49.7f, h = 22.2f, text = "即使你得到一个徽章")
        val result = requireNotNull(
            match(listOf(prev), listOf(cur, next), blockers = listOf(cur, next))
        )
        // blocker 顶在 A 像素空间 = 2000 + 24.3% × 1500 = 2364.5；带 = 1496..2473.5（高 977.5）
        // → 收边后盖板底端 ≈ 88.7%（不收边会是 99.6% = 压住 blocker 那一行）。
        val coverBottomPercent = result.coverBoxPercent[1] + result.coverBoxPercent[3]
        assertTrue("coverBottom=$coverBottomPercent", coverBottomPercent <= 90f)
        assertTrue(
            "cover must stay inside the layout box",
            result.coverBoxPercent[1] + result.coverBoxPercent[3] <=
                result.layoutBoxPercent[1] + result.layoutBoxPercent[3] + 0.01f
        )
    }

    @Test
    fun findMatch_convertsBetweenDifferentImagePixelSizes() {
        // B 图只有 A 的一半像素宽（显示宽度相同 → 像素比例 2×）。
        val prev = candidate("b0", x = 20f, y = 88f, w = 50f, h = 12f, text = "케이지라고요!! 접속종료를")
        val cur = candidate("b1", x = 20f, y = 0f, w = 50f, h = 24f, text = "케이지라고요!! 접속종료를 하려면")
        val result = requireNotNull(
            match(listOf(prev), listOf(cur), prevW = 1000, prevH = 2000, curW = 500, curH = 800)
        )
        // B 的 24% 高在 A 的像素空间里 = 800 * 0.24 * 2 = 384px（不是 192px）。
        val expectedBottom = 2000 + 800 * 0.24f * 2f
        val bandBottom = result.bandTopPx + result.bandHeightPx
        assertTrue("bandBottom=$bandBottom expected≈${expectedBottom + SeamRaceBand.PAD_PX}",
            kotlin.math.abs(bandBottom - (expectedBottom + SeamRaceBand.PAD_PX)) <= 2f)
    }

    @Test
    fun findMatch_picksTheBestTextScore() {
        val prev = candidate("b0", x = 20f, y = 90f, w = 50f, h = 10f, text = "악질들한테 걸리기라도 하면")
        val other = candidate("b2", x = 20f, y = 92f, w = 40f, h = 8f, text = "악질들한테 걸리기라도 하면")
        val cur = candidate(
            "b1", x = 20f, y = 0f, w = 50f, h = 20f,
            text = "악질들한테 걸리기라도 하면 죽지도 못하면서"
        )
        val result = requireNotNull(match(listOf(prev, other), listOf(cur)))
        assertNotNull(result)
        assertTrue(result.previousId == "b0" || result.previousId == "b2")
    }
}
