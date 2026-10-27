package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 3：双页贴边几何回退（不依赖 ≥4 字文本匹配）。
 *
 * 上页取贴底块、下页取贴顶块；同角色、连通分量、上下合计 ≥3 块，
 * 上下并集水平重叠 ≥0.55 / 中心对齐 ≥0.76 才合并。
 */
class SeamEdgeFallbackTest {

    private fun candidate(
        id: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        text: String,
        translated: String = "",
        role: String = "speech_bubble",
        cover: FloatArray? = null
    ) = SeamRaceBand.Candidate(
        id = id,
        x = x,
        y = y,
        w = w,
        h = h,
        originalText = text,
        coverBox = cover,
        translatedText = translated,
        role = role
    )

    private fun match(
        previous: List<SeamRaceBand.Candidate>,
        current: List<SeamRaceBand.Candidate>
    ) = SeamRaceBand.findGeometricMatch(
        previous = previous,
        current = current,
        previousWidthPx = 1000,
        previousHeightPx = 2000,
        currentWidthPx = 1000,
        currentHeightPx = 1500
    )

    @Test
    fun threeEdgeFragmentsWithShortTextAreMergedGeometrically() {
        val previous = listOf(
            candidate("p0", x = 20f, y = 88f, w = 25f, h = 10f, text = "응?", translated = "네"),
            candidate("p1", x = 45f, y = 88f, w = 25f, h = 10f, text = "뭐?", translated = "?")
        )
        val current = listOf(
            candidate("c0", x = 20f, y = 0f, w = 50f, h = 20f, text = "?", translated = "뭐?")
        )

        val result = requireNotNull(match(previous, current))
        assertTrue(result.structuralFallback)
        assertEquals(listOf("p0", "p1"), result.previousIds)
        assertEquals(listOf("c0"), result.currentIds)
        assertEquals("응? 뭐? ?", result.originalText)
        assertEquals("네 ? 뭐?", result.translatedText)
        // 带必须跨两张图。
        assertTrue(result.bandTopPx < 2000)
        assertTrue(result.bandTopPx + result.bandHeightPx > 2000)
    }

    @Test
    fun twoEdgeFragmentsDoNotFormFallback() {
        val previous = listOf(
            candidate("p0", x = 20f, y = 88f, w = 50f, h = 10f, text = "응?")
        )
        val current = listOf(
            candidate("c0", x = 20f, y = 0f, w = 50f, h = 20f, text = "?")
        )
        assertNull(match(previous, current))
    }

    @Test
    fun horizontallyMisalignedComponentsDoNotFormFallback() {
        val previous = listOf(
            candidate("p0", x = 0f, y = 88f, w = 20f, h = 10f, text = "왼쪽"),
            candidate("p1", x = 20f, y = 88f, w = 20f, h = 10f, text = "조각")
        )
        val current = listOf(
            candidate("c0", x = 80f, y = 0f, w = 18f, h = 20f, text = "오른쪽")
        )
        assertNull(match(previous, current))
    }

    @Test
    fun differentRolesDoNotFormFallback() {
        val previous = listOf(
            candidate("p0", x = 20f, y = 88f, w = 25f, h = 10f, text = "대사", role = "speech_bubble"),
            candidate("p1", x = 45f, y = 88f, w = 25f, h = 10f, text = "이어짐", role = "speech_bubble")
        )
        val current = listOf(
            candidate("c0", x = 20f, y = 0f, w = 50f, h = 20f, text = "나레이션", role = "plain_text")
        )
        assertNull(match(previous, current))
    }

    @Test
    fun edgeReasonsRequireBothPagesAndThreeFragments() {
        val previous = listOf(
            candidate("p0", x = 10f, y = 88f, w = 30f, h = 10f, text = "위"),
            candidate("p1", x = 42f, y = 88f, w = 30f, h = 10f, text = "아래")
        )
        val current = listOf(
            candidate("c0", x = 10f, y = 0f, w = 62f, h = 20f, text = "?")
        )
        val reasons = SeamRaceBand.edgeReasons(previous, current)
        assertTrue(reasons.contains(SeamPairState.REASON_UPPER_EDGE))
        assertTrue(reasons.contains(SeamPairState.REASON_LOWER_EDGE))
        assertTrue(reasons.contains(SeamPairState.REASON_FRAGMENT_STRUCTURE))
    }

    @Test
    fun translationMergePrefersLongerContainedText() {
        val previous = listOf(
            candidate("p0", x = 20f, y = 88f, w = 25f, h = 10f, text = "원문", translated = "완전한 번역"),
            candidate("p1", x = 45f, y = 88f, w = 25f, h = 10f, text = "조각", translated = "")
        )
        val current = listOf(
            candidate("c0", x = 20f, y = 0f, w = 50f, h = 20f, text = "?", translated = "번역")
        )
        val result = requireNotNull(match(previous, current))
        assertEquals("완전한 번역", result.translatedText)
    }

    @Test
    fun textMatchStillWorksForLongText() {
        val previous = listOf(
            candidate("p0", x = 20f, y = 88f, w = 50f, h = 12f,
                text = "케이지라고요!! 접속종료를 하려면 죽거나")
        )
        val current = listOf(
            candidate("c0", x = 20f, y = 0f, w = 50f, h = 24f,
                text = "케이지라고요!! 접속종료를 하려면 죽거나 돌아가거나")
        )
        val text = SeamRaceBand.findMatch(previous, current, 1000, 2000, 1000, 1500)
        assertNotNull(text)
        assertEquals(false, text!!.structuralFallback)
    }
}
