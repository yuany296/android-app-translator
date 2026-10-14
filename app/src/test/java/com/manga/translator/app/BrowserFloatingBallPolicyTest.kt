package com.manga.translator.app

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮球几何策略（2026-10-12）。
 *
 * 这些数字直接对应画布定稿（`23:153` 悬浮工具组）：球 48 · ⋮ 胶囊 44×36 贴在球上方留
 * 10dp 间距 · 展开菜单 120×102（早先 176 宽被用户判为「非常遮挡原图」）。
 * 「差几个像素」是调参中最容易悄悄漂移的部分，用单测钉住，改数字必须先改这里。
 */
class BrowserFloatingBallPolicyTest {

    private val ball = BrowserFloatingBallPolicy.SIZE_DP.toFloat()
    private val capsuleW = BrowserFloatingBallPolicy.CAPSULE_WIDTH_DP.toFloat()
    private val capsuleH = BrowserFloatingBallPolicy.CAPSULE_HEIGHT_DP.toFloat()
    private val capsuleGap = BrowserFloatingBallPolicy.CAPSULE_GAP_DP.toFloat()

    @Test
    fun menuSize_matchesCanvasSpec() {
        assertEquals(120, BrowserFloatingBallPolicy.MENU_WIDTH_DP)
        // 上下内边距 8×2 + 行·截图翻译 44 + 行间距 8 + 分段·原文译文 34。
        assertEquals(8 * 2 + 44 + 8 + 34, BrowserFloatingBallPolicy.MENU_HEIGHT_DP)
    }

    @Test
    fun capsule_widthLeavesTwoDpPerSideWithinBall() {
        // 球 48、胶囊 44 —— 横向居中后左右各 2dp，这是画布上的实际比例。
        assertEquals(2f, (ball - capsuleW) / 2f, 0.01f)
    }

    // ---- ⋮ 胶囊 ----

    @Test
    fun capsule_sitsAboveBallLeavingGap() {
        val top = BrowserFloatingBallPolicy.capsuleTop(
            ballY = 300f, sizePx = ball, capsuleHeightPx = capsuleH, gapPx = capsuleGap
        )
        assertEquals(300f - capsuleGap - capsuleH, top, 0.01f)
    }

    @Test
    fun capsule_flipsBelowBallWhenTopEdgeHasNoRoom() {
        val top = BrowserFloatingBallPolicy.capsuleTop(
            ballY = 0f, sizePx = ball, capsuleHeightPx = capsuleH, gapPx = capsuleGap
        )
        // 球贴顶边时必须翻到下方 —— 胶囊是菜单唯一的展开入口，不能跑出屏幕。
        assertEquals(ball + capsuleGap, top, 0.01f)
        assertTrue(top > 0f)
    }

    @Test
    fun capsule_usesUpwardPlacementAsSoonAsItFits() {
        val exactFit = capsuleGap + capsuleH
        assertEquals(
            0f,
            BrowserFloatingBallPolicy.capsuleTop(
                ballY = exactFit, sizePx = ball, capsuleHeightPx = capsuleH, gapPx = capsuleGap
            ),
            0.01f
        )
    }

    @Test
    fun capsule_isCenteredOnBall() {
        assertEquals(
            346f,
            BrowserFloatingBallPolicy.capsuleLeft(ballX = 344f, sizePx = ball, capsuleWidthPx = capsuleW),
            0.01f
        )
    }

    // ---- 展开菜单 ----

    @Test
    fun expandsUpward_onlyInLowerHalf() {
        assertTrue(BrowserFloatingBallPolicy.expandsUpward(ballY = 600f, sizePx = ball, containerHeightPx = 800f))
        assertFalse(BrowserFloatingBallPolicy.expandsUpward(ballY = 100f, sizePx = ball, containerHeightPx = 800f))
    }

    @Test
    fun menuTop_aboveBallWhenLowOnScreen_andNeverNegative() {
        val height = 120f // == MENU_HEIGHT_DP
        assertEquals(
            600f - height,
            BrowserFloatingBallPolicy.menuTop(600f, ball, height, containerHeightPx = 800f),
            0.01f
        )
        // 球刚好在下半部边界、上方放不下整张菜单时，夹到 0（宁可压住页面也不出屏）。
        assertEquals(
            0f,
            BrowserFloatingBallPolicy.menuTop(80f, ball, height, containerHeightPx = 200f),
            0.01f
        )
    }

    @Test
    fun menuTop_belowBallWhenHighOnScreen() {
        val height = 120f
        assertEquals(
            100f + ball,
            BrowserFloatingBallPolicy.menuTop(100f, ball, height, containerHeightPx = 800f),
            0.01f
        )
    }

    @Test
    fun menuLeft_opensOppositeToDock_andStaysInsideContainer() {
        val menuW = BrowserFloatingBallPolicy.MENU_WIDTH_DP.toFloat()
        val gap = BrowserFloatingBallPolicy.MENU_GAP_DP.toFloat()
        // 停靠右 → 菜单在球左侧。
        assertEquals(
            344f - gap - menuW,
            BrowserFloatingBallPolicy.menuLeft(344f, ball, menuW, 412f, dockedRight = true, menuGapPx = gap),
            0.01f
        )
        // 停靠左 → 菜单在球右侧。
        assertEquals(
            2f + ball + gap,
            BrowserFloatingBallPolicy.menuLeft(2f, ball, menuW, 412f, dockedRight = false, menuGapPx = gap),
            0.01f
        )
        // 容器比菜单还窄时退回 0，不出现负坐标。
        assertEquals(
            0f,
            BrowserFloatingBallPolicy.menuLeft(100f, ball, menuW, 80f, dockedRight = true, menuGapPx = gap),
            0.01f
        )
    }

    // ---- 球本身：默认位置 / 夹紧 / 吸附 ----

    @Test
    fun defaultPosition_docksRightAndLeavesBottomMargin() {
        val pos = BrowserFloatingBallPolicy.defaultPosition(
            containerWidthPx = 412f,
            containerHeightPx = 800f,
            sizePx = ball,
            dockMarginPx = 2f,
            bottomMarginPx = BrowserFloatingBallPolicy.DEFAULT_BOTTOM_MARGIN_DP.toFloat()
        )
        assertEquals(412f - ball - 2f, pos.x, 0.01f)
        assertEquals(800f - ball - 16f, pos.y, 0.01f)
    }

    /**
     * 默认点必须落在内容区**下半部** —— 否则 [BrowserFloatingBallPolicy.expandsUpward] 为 false，
     * 展开菜单会朝下长，和画布 `11:3` 的「胶囊在上、菜单在上」构图正好相反。
     */
    @Test
    fun defaultPosition_landsInLowerHalfSoMenuOpensUpward() {
        val pos = BrowserFloatingBallPolicy.defaultPosition(412f, 800f, ball, 2f, 16f)
        assertTrue(BrowserFloatingBallPolicy.expandsUpward(pos.y, ball, 800f))
    }

    @Test
    fun defaultPosition_degeneratesOnZeroSizedContainer() {
        assertEquals(Offset.Zero, BrowserFloatingBallPolicy.defaultPosition(0f, 0f, ball, 2f, 16f))
    }

    @Test
    fun clamp_keepsBallFullyInside() {
        assertEquals(
            Offset(412f - ball, 800f - ball),
            BrowserFloatingBallPolicy.clamp(Offset(9999f, 9999f), ball, 412f, 800f)
        )
        assertEquals(Offset(0f, 0f), BrowserFloatingBallPolicy.clamp(Offset(-50f, -50f), ball, 412f, 800f))
    }

    @Test
    fun dockX_snapsToNearestEdgeByWhichHalfHoldsTheCentre() {
        assertEquals(2f, BrowserFloatingBallPolicy.dockX(2f, ball, 412f, 2f), 0.01f)
        assertEquals(412f - ball - 2f, BrowserFloatingBallPolicy.dockX(360f, ball, 412f, 2f), 0.01f)
        // 球心正好在正中偏右一格 → 视为停靠右侧。
        assertTrue(BrowserFloatingBallPolicy.isDockedRight(183f, ball, 412f))
        assertFalse(BrowserFloatingBallPolicy.isDockedRight(1f, ball, 412f))
    }
}
