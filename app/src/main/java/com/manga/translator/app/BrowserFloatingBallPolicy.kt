package com.manga.translator.app

import androidx.compose.ui.geometry.Offset

/**
 * 内嵌浏览器翻译悬浮球的**纯几何策略**（2026-10-12 用户要求「翻译按钮变成悬浮球，好按一点」）。
 *
 * 抽成纯函数的原因与 [BrowserTranslateUiPolicy] 相同：悬浮球的位置规则
 * （夹紧、松手吸附左右边缘、展开菜单朝哪边长）是最容易在调参中漂移的部分，
 * 用单测钉住后，Compose 侧只负责把手势与状态画出来。
 *
 * 坐标系：悬浮球容器（网页内容区）左上角为原点的**像素**坐标；
 * 球的尺寸由调用方换算成 px 后传入，这里不做 dp/px 换算（保持纯 JVM 可测）。
 */
object BrowserFloatingBallPolicy {

    /** 球的直径（dp）。44 太小难点中，48 与底栏图标按钮的可点面积同档。 */
    const val SIZE_DP = 48

    /**
     * ⋮ 胶囊：贴在球上方（放不下时到下方）的小圆角胶囊，点它开/关翻译菜单。
     * 44 宽保住最小触控边；36 高是画布定稿值（`23:167`）。
     */
    const val CAPSULE_WIDTH_DP = 44
    const val CAPSULE_HEIGHT_DP = 36

    /** ⋮ 胶囊与球的间距（dp）。 */
    const val CAPSULE_GAP_DP = 10

    /**
     * 展开菜单（白卡）宽度（dp）。120 是画布定稿值：早先的 176 宽占屏宽 43%，
     * 用户反馈「非常遮挡原图」；120 只占 29%，刚好装下「截图翻译」行与「原文｜译文」分段。
     */
    const val MENU_WIDTH_DP = 120

    /** 菜单内容高 = 内边距 8×2 + 行·截图翻译 44 + 行间距 8 + 分段·原文译文 34 = 102（dp）。 */
    const val MENU_HEIGHT_DP = 102

    /** 菜单与球的横向间距 / 球与容器边缘的停靠余量（dp）。 */
    const val MENU_GAP_DP = 10
    const val DOCK_MARGIN_DP = 2

    /**
     * 初始停靠点与内容区**底边**的余量（dp）。画布 `11:3`／`11:220` 定稿：悬浮工具组贴在
     * 内容区底部，球（整组最下面那个元素）的底边距内容区底 16dp。
     *
     * 为什么不按屏幕高度取比例（早先用的是 30%）：球落在内容区**下半部**是本组几何的前提 ——
     * [expandsUpward] 只有在球位于下半部时才让菜单朝上长，画布的构图正是「胶囊在上、菜单在上」。
     * 30% 时菜单会朝下长，和设计稿正好相反；而且贴底也是拇指最容易够到的位置。
     */
    const val DEFAULT_BOTTOM_MARGIN_DP = 16

    /** 首次出现时的默认位置：右边缘（留停靠余量）、纵向贴内容区底留 [DEFAULT_BOTTOM_MARGIN_DP]。 */
    fun defaultPosition(
        containerWidthPx: Float,
        containerHeightPx: Float,
        sizePx: Float,
        dockMarginPx: Float,
        bottomMarginPx: Float
    ): Offset {
        if (containerWidthPx <= 0f || containerHeightPx <= 0f || sizePx <= 0f) return Offset.Zero
        val x = (containerWidthPx - sizePx - dockMarginPx).coerceAtLeast(0f)
        val y = (containerHeightPx - sizePx - bottomMarginPx)
            .coerceIn(0f, (containerHeightPx - sizePx).coerceAtLeast(0f))
        return Offset(x, y)
    }

    /** 拖动过程中的位置夹紧：球任何时刻都必须完整留在容器内（容器比球还小时退化到左上角）。 */
    fun clamp(raw: Offset, sizePx: Float, containerWidthPx: Float, containerHeightPx: Float): Offset {
        if (sizePx <= 0f) return raw
        val maxX = (containerWidthPx - sizePx).coerceAtLeast(0f)
        val maxY = (containerHeightPx - sizePx).coerceAtLeast(0f)
        return Offset(raw.x.coerceIn(0f, maxX), raw.y.coerceIn(0f, maxY))
    }

    /**
     * 松手吸附：球心落在容器右半 → 靠右，否则靠左（与 [isDockedRight] 同一判据）。
     * 吸附只改横向位置，纵向保持用户拖到的高度。
     */
    fun dockX(x: Float, sizePx: Float, containerWidthPx: Float, dockMarginPx: Float): Float {
        if (containerWidthPx <= 0f || sizePx <= 0f) return 0f
        val maxX = (containerWidthPx - sizePx - dockMarginPx).coerceAtLeast(0f)
        val docked = if (isDockedRight(x, sizePx, containerWidthPx)) {
            containerWidthPx - sizePx - dockMarginPx
        } else {
            dockMarginPx
        }
        return docked.coerceIn(0f, maxX)
    }

    /** 球心在容器右半 = 停靠右侧（决定菜单朝左展开；[dockX] 与默认位置也用它）。 */
    fun isDockedRight(x: Float, sizePx: Float, containerWidthPx: Float): Boolean {
        if (containerWidthPx <= 0f) return true
        val center = x + sizePx / 2f
        return center > containerWidthPx / 2f
    }

    /** 展开方向：球心在容器下半部 → 菜单朝**上**长（避免伸到屏幕外），否则朝下。 */
    fun expandsUpward(ballY: Float, sizePx: Float, containerHeightPx: Float): Boolean =
        ballY + sizePx / 2f > containerHeightPx / 2f

    /**
     * 展开菜单的纵向位置：由 [expandsUpward] 决定长在球上方还是下方。返回菜单左上角 y。
     */
    fun menuTop(
        ballY: Float,
        sizePx: Float,
        menuHeightPx: Float,
        containerHeightPx: Float
    ): Float {
        return if (expandsUpward(ballY, sizePx, containerHeightPx)) {
            (ballY - menuHeightPx).coerceAtLeast(0f)
        } else {
            (ballY + sizePx).coerceAtMost((containerHeightPx - menuHeightPx).coerceAtLeast(0f))
        }
    }

    /**
     * ⋮ 胶囊的纵向位置：默认贴在球**上方**（留 [gapPx]）；球被拖到顶边、上方放不下时
     * 改到球下方 —— 保证胶囊任何时候都完整可见（它是菜单唯一的展开入口）。
     */
    fun capsuleTop(ballY: Float, sizePx: Float, capsuleHeightPx: Float, gapPx: Float): Float {
        val above = ballY - gapPx - capsuleHeightPx
        return if (above >= 0f) above else ballY + sizePx + gapPx
    }

    /** ⋮ 胶囊的横向位置：与球同心（球 48、胶囊 44，左右各让 2dp）。 */
    fun capsuleLeft(ballX: Float, sizePx: Float, capsuleWidthPx: Float): Float =
        ballX + (sizePx - capsuleWidthPx) / 2f

    /**
     * 展开菜单的横向位置：停靠右 → 菜单在球左侧，停靠左 → 在球右侧；
     * 菜单自身也夹在容器内（球贴边时菜单不会伸出去）。
     */
    fun menuLeft(
        ballX: Float,
        sizePx: Float,
        menuWidthPx: Float,
        containerWidthPx: Float,
        dockedRight: Boolean,
        menuGapPx: Float
    ): Float {
        val raw = if (dockedRight) ballX - menuGapPx - menuWidthPx else ballX + sizePx + menuGapPx
        val maxX = (containerWidthPx - menuWidthPx).coerceAtLeast(0f)
        return raw.coerceIn(0f, maxX)
    }
}
