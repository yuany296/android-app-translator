package com.manga.translator.app

import com.manga.translator.translate.BubbleRegion
import com.manga.translator.translate.BubbleRegionType
import com.manga.translator.translate.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.abs

class BubbleFirstRegionPlannerTest {
    private val planner = BubbleFirstRegionPlanner()

    @Test
    fun route_assignsBlocksInsideBubbleAndSeparatesOutside() {
        val regions = listOf(
            BubbleRegion(
                id = 7,
                left = 100f,
                top = 100f,
                right = 220f,
                bottom = 280f,
                regionType = BubbleRegionType.BUBBLE,
                confidence = 0.9f,
                source = "test"
            )
        )
        val blocks = listOf(
            block(1, "bubbleA", 120f, 120f, 150f, 190f),
            block(2, "bubbleB", 160f, 130f, 190f, 250f),
            block(3, "outside", 320f, 130f, 360f, 190f)
        )

        val routing = planner.route(regions, blocks, 1080, 1920)

        assertEquals(1, routing.bubbleAssignments.size)
        assertEquals(2, routing.bubbleAssignments.first().blocks.size)
        assertEquals(1, routing.outsideAssignments.size)
        assertEquals(1, routing.outsideAssignments.first().blocks.size)
        assertEquals("outside", routing.outsideAssignments.first().blocks.first().text)
    }

    @Test
    fun buildBubbleFromBlocks_keepsOutsideAsTransparentIndependentBubble() {
        val region = BubbleRegion(
            id = 3,
            left = 300f,
            top = 120f,
            right = 390f,
            bottom = 220f,
            regionType = BubbleRegionType.OUTSIDE,
            confidence = 1f,
            source = "outside"
        )
        val bubble = planner.buildBubbleFromBlocks(
            region = region,
            blocks = listOf(
                block(1, "sideA", 320f, 130f, 340f, 180f),
                block(2, "sideB", 342f, 130f, 362f, 180f)
            ),
            imageWidth = 1080,
            imageHeight = 1920
        )

        assertTrue(bubble != null)
        assertEquals("none", bubble?.bgType)
        assertEquals("sideBsideA", bubble?.originalText)
        assertEquals("vertical", bubble?.layout)
        assertTrue(abs((bubble?.contentX ?: 0f) - (320f / 1080f * 100f)) < 0.2f)
        assertTrue(abs((bubble?.contentY ?: 0f) - (130f / 1920f * 100f)) < 0.2f)
        assertTrue(abs((bubble?.contentW ?: 0f) - (42f / 1080f * 100f)) < 0.2f)
        assertTrue(abs((bubble?.contentH ?: 0f) - (50f / 1920f * 100f)) < 0.2f)
    }

    @Test
    fun buildBubblesFromAssignment_splitsSeparatedGroupsInsideWideRegion() {
        val assignment = BubbleRegionAssignment(
            region = BubbleRegion(
                id = 11,
                left = 80f,
                top = 120f,
                right = 420f,
                bottom = 320f,
                regionType = BubbleRegionType.BUBBLE,
                confidence = 0.95f,
                source = "test"
            ),
            blocks = listOf(
                block(1, "left1", 110f, 140f, 140f, 250f),
                block(2, "left2", 150f, 145f, 180f, 255f),
                block(3, "right1", 320f, 150f, 350f, 255f),
                block(4, "right2", 360f, 152f, 390f, 258f)
            )
        )

        val bubbles = planner.buildBubblesFromAssignment(assignment, 1080, 1920)

        assertEquals(2, bubbles.size)
        assertTrue(bubbles.all { it.layout == "vertical" })
        assertFalse(kotlin.math.abs(bubbles[0].x - bubbles[1].x) < 1f)
        assertTrue(bubbles.all { it.contentW <= it.w })
        assertTrue(bubbles.all { it.contentH <= it.h })
    }

    @Test
    fun buildBubblesFromAssignment_doesNotMergeAcrossWhitespaceGutter() {
        val assignment = BubbleRegionAssignment(
            region = BubbleRegion(
                id = 15,
                left = 80f,
                top = 120f,
                right = 420f,
                bottom = 320f,
                regionType = BubbleRegionType.BUBBLE,
                confidence = 0.95f,
                source = "test"
            ),
            blocks = listOf(
                block(1, "left1", 110f, 140f, 140f, 250f),
                block(2, "left2", 150f, 145f, 180f, 255f),
                block(3, "right1", 215f, 150f, 245f, 255f),
                block(4, "right2", 255f, 152f, 285f, 258f)
            )
        )

        val bubbles = planner.buildBubblesFromAssignment(
            assignment = assignment,
            imageWidth = 1080,
            imageHeight = 1920,
            separatedByWhitespace = { a, b -> a.id <= 2 && b.id >= 3 || a.id >= 3 && b.id <= 2 }
        )

        assertEquals(2, bubbles.size)
    }

    private fun block(id: Int, text: String, left: Float, top: Float, right: Float, bottom: Float): TextBlock {
        return TextBlock(
            id = id,
            text = text,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            confidence = 1f
        )
    }
}
