package com.manga.translator.app

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮球几何策略（2026-10-12，2026-11-01 扩成两球一组）。
 *
 * 这些数字直接对应画布定稿（`23:153` 悬浮工具组）：球 48 · 同组间距 6 · 组高 102 ·
 * ⋮ 胶囊 44×36 贴在组上方留 10dp · 合并菜单 120×163（截图翻译 + 原文/译文 + 网页文字）。
 * 「差几个像素」是调参中最容易悄悄漂移的部分，用单测钉住，改数字必须先改这里。
 */
class BrowserFloatingBallPolicyTest {

    private val ball = BrowserFloatingBallPolicy.SIZE_DP.toFloat()
    private val gap = BrowserFloatingBallPolicy.GROUP_GAP_DP.toFloat()
    private val groupH = BrowserFloatingBallPolicy.GROUP_HEIGHT_DP.toFloat()
    private val comicOffset = BrowserFloatingBallPolicy.COMIC_TOP_OFFSET_DP.toFloat()
    private val capsuleW = BrowserFloatingBallPolicy.CAPSULE_WIDTH_DP.toFloat()
    private val capsuleH = BrowserFloatingBallPolicy.CAPSULE_HEIGHT_DP.toFloat()
    private val capsuleGap = BrowserFloatingBallPolicy.CAPSULE_GAP_DP.toFloat()

    @Test
    fun menuSize_matchesCanvasSpec() {
        assertEquals(120, BrowserFloatingBallPolicy.MENU_WIDTH_DP)
        // 上下内边距 8×2 + 行·截图翻译 44 + 行间距 8 + 分段·原文译文 34。
        assertEquals(8 * 2 + 44 + 8 + 34, BrowserFloatingBallPolicy.MENU_HEIGHT_DP)
        assertEquals(102, BrowserFloatingBallPolicy.MENU_HEIGHT_DP)
    }

    @Test
    fun groupSize_isTwoBallsPlusGap() {
        assertEquals(48, BrowserFloatingBallPolicy.SIZE_DP)
        assertEquals(6, BrowserFloatingBallPolicy.GROUP_GAP_DP)
        assertEquals(102, BrowserFloatingBallPolicy.GROUP_HEIGHT_DP)
        assertEquals(54, BrowserFloatingBallPolicy.COMIC_TOP_OFFSET_DP)
        // 角标向外探出 3dp；停靠余量必须大于它，否则贴边时只剩半个角标。
        assertEquals(6, BrowserFloatingBallPolicy.DOCK_MARGIN_DP)
        assertEquals(102f, BrowserFloatingBallPolicy.groupHeightPx(ball, gap), 0.01f)
        assertEquals(54f, BrowserFloatingBallPolicy.comicTopOffsetPx(ball, gap), 0.01f)
    }

    @Test
    fun capsule_widthLeavesTwoDpPerSideWithinBall() {
        // 球 48、胶囊 44 —— 横向居中后左右各 2dp，这是画布上的实际比例。
        assertEquals(2f, (ball - capsuleW) / 2f, 0.01f)
    }

    // ---- ⋮ 胶囊 ----

    @Test
    fun capsule_sitsAboveGroupLeavingGap() {
        val top = BrowserFloatingBallPolicy.capsuleTop(
            groupTopY = 300f,
            groupHeightPx = groupH,
            capsuleHeightPx = capsuleH,
            gapPx = capsuleGap
        )
        assertEquals(300f - capsuleGap - capsuleH, top, 0.01f)
    }

    @Test
    fun capsule_flipsBelowGroupWhenTopEdgeHasNoRoom() {
        val top = BrowserFloatingBallPolicy.capsuleTop(
            groupTopY = 0f,
            groupHeightPx = groupH,
            capsuleHeightPx = capsuleH,
            gapPx = capsuleGap
        )
        // 组贴顶边时必须翻到下方 —— 胶囊是菜单唯一的展开入口，不能跑出屏幕。
        assertEquals(groupH + capsuleGap, top, 0.01f)
        assertTrue(top > 0f)
    }

    @Test
    fun capsule_usesUpwardPlacementAsSoonAsItFits() {
        val exactFit = capsuleGap + capsuleH
        assertEquals(
            0f,
            BrowserFloatingBallPolicy.capsuleTop(
                groupTopY = exactFit,
                groupHeightPx = groupH,
                capsuleHeightPx = capsuleH,
                gapPx = capsuleGap
            ),
            0.01f
        )
    }

    @Test
    fun capsule_isCenteredOnGroup() {
        assertEquals(
            346f,
            BrowserFloatingBallPolicy.capsuleLeft(
                ballX = 344f,
                ballWidthPx = ball,
                capsuleWidthPx = capsuleW
            ),
            0.01f
        )
    }

    // ---- 展开菜单 ----

    @Test
    fun menuTop_alignsWithGroupTop() {
        assertEquals(
            300f,
            BrowserFloatingBallPolicy.menuTop(
                groupTopY = 300f,
                menuHeightPx = 120f,
                containerHeightPx = 800f
            ),
            0.01f
        )
    }

    @Test
    fun menuTop_clampsWhenGroupIsNearBottom() {
        // 组贴底时菜单仍要完整留在容器内：800 - 163 = 637，而不是从 698 开始长出屏幕。
        assertEquals(
            637f,
            BrowserFloatingBallPolicy.menuTop(
                groupTopY = 698f,
                menuHeightPx = 163f,
                containerHeightPx = 800f
            ),
            0.01f
        )
    }

    @Test
    fun menuTop_neverNegativeWhenContainerIsShorterThanMenu() {
        assertEquals(
            0f,
            BrowserFloatingBallPolicy.menuTop(
                groupTopY = 0f,
                menuHeightPx = 163f,
                containerHeightPx = 120f
            ),
            0.01f
        )
    }

    @Test
    fun menuLeft_opensOppositeToDock_andStaysInsideContainer() {
        val menuW = BrowserFloatingBallPolicy.MENU_WIDTH_DP.toFloat()
        val menuGap = BrowserFloatingBallPolicy.MENU_GAP_DP.toFloat()
        // 停靠右 → 菜单在组左侧。
        assertEquals(
            344f - menuGap - menuW,
            BrowserFloatingBallPolicy.menuLeft(344f, ball, menuW, 412f, dockedRight = true, menuGapPx = menuGap),
            0.01f
        )
        // 停靠左 → 菜单在组右侧。
        assertEquals(
            2f + ball + menuGap,
            BrowserFloatingBallPolicy.menuLeft(2f, ball, menuW, 412f, dockedRight = false, menuGapPx = menuGap),
            0.01f
        )
        // 容器比菜单还窄时退回 0，不出现负坐标。
        assertEquals(
            0f,
            BrowserFloatingBallPolicy.menuLeft(100f, ball, menuW, 80f, dockedRight = true, menuGapPx = menuGap),
            0.01f
        )
    }

    // ---- 球本身 / 整组：默认位置 / 夹紧 / 吸附 ----

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

    @Test
    fun defaultPosition_degeneratesOnZeroSizedContainer() {
        assertEquals(Offset.Zero, BrowserFloatingBallPolicy.defaultPosition(0f, 0f, ball, 2f, 16f))
    }

    @Test
    fun defaultGroupPosition_docksRight_andKeepsComicBallBottomMargin() {
        val pos = BrowserFloatingBallPolicy.defaultGroupPosition(
            containerWidthPx = 412f,
            containerHeightPx = 800f,
            ballSizePx = ball,
            comicOffsetPx = comicOffset,
            dockMarginPx = 2f,
            bottomMarginPx = 16f
        )
        assertEquals(412f - ball - 2f, pos.x, 0.01f)
        // 漫画球 = 组顶 + 54；它的底边距内容区底 16dp。
        assertEquals(800f - ball - 16f, pos.y + comicOffset, 0.01f)
        // 网页文字球（组顶）也必须在容器内。
        assertTrue(pos.y >= 0f)
    }

    @Test
    fun defaultGroupPosition_degeneratesOnZeroSizedContainer() {
        assertEquals(
            Offset.Zero,
            BrowserFloatingBallPolicy.defaultGroupPosition(0f, 0f, ball, comicOffset, 2f, 16f)
        )
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
    fun clampGroup_keepsWholeGroupInsideUsingGroupHeight() {
        assertEquals(
            Offset(412f - ball, 800f - groupH),
            BrowserFloatingBallPolicy.clampGroup(Offset(9999f, 9999f), ball, groupH, 412f, 800f)
        )
        assertEquals(
            Offset(0f, 0f),
            BrowserFloatingBallPolicy.clampGroup(Offset(-50f, -50f), ball, groupH, 412f, 800f)
        )
        // 容器很矮时：上限按组高算（200 - 102 = 98），不能按单球算成 152。
        assertEquals(
            98f,
            BrowserFloatingBallPolicy.clampGroup(Offset(0f, 9999f), ball, groupH, 412f, 200f).y,
            0.01f
        )
    }

    @Test
    fun comicAnchorConversions_roundTrip() {
        val groupTop = 322f
        val comicTop = BrowserFloatingBallPolicy.comicTopForGroup(groupTop, comicOffset)
        assertEquals(376f, comicTop, 0.01f)
        assertEquals(
            groupTop,
            BrowserFloatingBallPolicy.groupTopForComic(comicTop, comicOffset),
            0.01f
        )
    }

    @Test
    fun dockX_snapsToNearestEdgeByWhichHalfHoldsTheCentre() {
        assertEquals(2f, BrowserFloatingBallPolicy.dockX(2f, ball, 412f, 2f), 0.01f)
        assertEquals(412f - ball - 2f, BrowserFloatingBallPolicy.dockX(360f, ball, 412f, 2f), 0.01f)
        // 球心正好在正中偏右一格 → 视为停靠右侧。
        assertTrue(BrowserFloatingBallPolicy.isDockedRight(183f, ball, 412f))
        assertFalse(BrowserFloatingBallPolicy.isDockedRight(1f, ball, 412f))
    }

    // ---- 位置持久化：比例编解码（仍以漫画球为锚点） ----

    @Test
    fun encodePosition_roundTripsInsideMovableRange() {
        val encoded = BrowserFloatingBallPolicy.encodePosition(
            raw = Offset(100f, 200f),
            sizePx = ball,
            containerWidthPx = 412f,
            containerHeightPx = 800f
        )
        requireNotNull(encoded)
        assertEquals(100f / (412f - ball), encoded.x, 0.0001f)
        assertEquals(200f / (800f - ball), encoded.y, 0.0001f)

        val decoded = BrowserFloatingBallPolicy.decodePosition(encoded, ball, 412f, 800f)
        assertEquals(100f, decoded.x, 0.01f)
        assertEquals(200f, decoded.y, 0.01f)
    }

    @Test
    fun encodePosition_mapsEdgesToZeroAndOne() {
        val topLeft = BrowserFloatingBallPolicy.encodePosition(Offset(0f, 0f), ball, 412f, 800f)
        requireNotNull(topLeft)
        assertEquals(0f, topLeft.x, 0.0001f)
        assertEquals(0f, topLeft.y, 0.0001f)

        val bottomRight = BrowserFloatingBallPolicy.encodePosition(
            Offset(412f - ball, 800f - ball), ball, 412f, 800f
        )
        requireNotNull(bottomRight)
        assertEquals(1f, bottomRight.x, 0.0001f)
        assertEquals(1f, bottomRight.y, 0.0001f)
    }

    @Test
    fun encodePosition_clampsOutOfBoundsAndRejectsInvalidContainer() {
        val encoded = BrowserFloatingBallPolicy.encodePosition(
            Offset(-100f, 9999f), ball, 412f, 800f
        )
        requireNotNull(encoded)
        assertEquals(0f, encoded.x, 0.0001f)
        assertEquals(1f, encoded.y, 0.0001f)

        assertNull(BrowserFloatingBallPolicy.encodePosition(Offset(1f, 1f), ball, 0f, 800f))
        assertNull(BrowserFloatingBallPolicy.encodePosition(Offset(1f, 1f), 0f, 412f, 800f))
    }

    @Test
    fun decodePosition_scalesToNewContainerAndClampsBadRatios() {
        // 同一比例在更窄/更矮的容器里换算成新的像素位置，而不是沿用旧像素。
        val decoded = BrowserFloatingBallPolicy.decodePosition(Offset(1f, 1f), ball, 300f, 600f)
        assertEquals(300f - ball, decoded.x, 0.01f)
        assertEquals(600f - ball, decoded.y, 0.01f)

        // 坏数据（负数/大于 1）夹回合法范围，不能把球画到屏幕外。
        assertEquals(
            Offset(0f, 0f),
            BrowserFloatingBallPolicy.decodePosition(Offset(-1f, -1f), ball, 412f, 800f)
        )
        assertEquals(
            Offset(412f - ball, 800f - ball),
            BrowserFloatingBallPolicy.decodePosition(Offset(2f, 2f), ball, 412f, 800f)
        )
    }

    /**
     * 旧数据的迁移口径：位置比例仍按**单球（漫画球）**解码，再减 54dp 得到组顶。
     * 这样升级后漫画球还在用户上次放的地方，网页文字球出现在它上方。
     */
    @Test
    fun legacySingleBallRatio_restoresSameComicBallTop() {
        val ratio = Offset(1f, 0.5f)
        val comicAnchor = BrowserFloatingBallPolicy.decodePosition(ratio, ball, 412f, 800f)
        assertEquals((800f - ball) / 2f, comicAnchor.y, 0.01f)
        val groupTop = BrowserFloatingBallPolicy.clampGroup(
            raw = Offset(
                comicAnchor.x,
                BrowserFloatingBallPolicy.groupTopForComic(comicAnchor.y, comicOffset)
            ),
            ballSizePx = ball,
            groupHeightPx = groupH,
            containerWidthPx = 412f,
            containerHeightPx = 800f
        )
        // 组顶 = 漫画球锚点 - 54；结果未被夹紧，说明漫画球保持原位。
        assertEquals(comicAnchor.y - comicOffset, groupTop.y, 0.01f)
    }
}
