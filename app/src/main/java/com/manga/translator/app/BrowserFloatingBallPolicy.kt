package com.manga.translator.app

import androidx.compose.ui.geometry.Offset

/**
 * 内嵌浏览器翻译悬浮球的**纯几何策略**（2026-10-12 用户要求「翻译按钮变成悬浮球，好按一点」）。
 *
 * 2026-11-01 起悬浮球是**两颗一组的竖排组**（上：网页文字；下：漫画），整组拖动、
 * 整组吸附、只保存一份位置。位置仍以**下方漫画球的锚点**编码成比例：旧版本只存过
 * 漫画球位置，这样升级后不用迁移，漫画球还在原处，网页文字球出现在它上方。
 *
 * 抽成纯函数的原因与 [BrowserTranslateUiPolicy] 相同：悬浮球的位置规则
 * （夹紧、松手吸附左右边缘、菜单/胶囊怎么摆）是最容易在调参中漂移的部分，
 * 用单测钉住后，Compose 侧只负责把手势与状态画出来。
 *
 * 坐标系：悬浮球容器（网页内容区）左上角为原点的**像素**坐标；
 * 球的尺寸由调用方换算成 px 后传入，这里不做 dp/px 换算（保持纯 JVM 可测）。
 */
object BrowserFloatingBallPolicy {

    /** 球的直径（dp）。44 太小难点中，48 与底栏图标按钮的可点面积同档。 */
    const val SIZE_DP = 48

    /**
     * 同组两颗球的间距（dp）。参考项目 36px 球用 4px，按同比例缩到 48dp 取 6dp；
     * 整组竖排：上 = 网页文字球，下 = 漫画球。
     */
    const val GROUP_GAP_DP = 6

    /** 组高 = 48 + 6 + 48 = 102dp。 */
    const val GROUP_HEIGHT_DP = SIZE_DP * 2 + GROUP_GAP_DP

    /** 漫画球（组内下方那颗）相对组顶的偏移 = 球 + 间距 = 54dp。 */
    const val COMIC_TOP_OFFSET_DP = SIZE_DP + GROUP_GAP_DP

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
     *
     * 2026-11-01 合并两组后仍保持两行：网页文字翻译由组上方的「文」球直接触发，
     * 菜单里不再重复放一份（用户明确要求去掉）。
     */
    const val MENU_WIDTH_DP = 120

    /** 菜单内容高 = 内边距 8×2 + 行·截图翻译 44 + 行间距 8 + 分段·原文译文 34 = 102（dp）。 */
    const val MENU_HEIGHT_DP = 102

    /** 菜单与球的横向间距 / 球与容器边缘的停靠余量（dp）。 */
    const val MENU_GAP_DP = 10
    /**
     * 停靠余量 6dp（原 2dp）：状态角标压在球右上/右下并向外探出 3dp，
     * 贴边 2dp 时角标会被屏幕裁掉一半（2026-11-01 模拟机实测）。
     */
    const val DOCK_MARGIN_DP = 6

    /**
     * 初始停靠点与内容区**底边**的余量（dp）。画布 `11:3`／`11:220` 定稿：悬浮工具组贴在
     * 内容区底部，球（整组最下面那个元素）的底边距内容区底 16dp。
     *
     * 为什么不按屏幕高度取比例（早先用的是 30%）：球落在内容区**下半部**是本组几何的前提 ——
     * 菜单与胶囊都从组的上方展开（参考项目同款），贴底也是拇指最容易够到的位置。
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

    /**
     * 整组首次出现的默认位置（返回**组左上角**）。
     *
     * 先把「下方漫画球」按单球规则贴到内容区底，再把它上方那颗网页文字球加上去；
     * 组顶可能被顶到负值（容器矮 / 底距太大），统一交给 [clampGroup] 夹回可见区。
     */
    fun defaultGroupPosition(
        containerWidthPx: Float,
        containerHeightPx: Float,
        ballSizePx: Float,
        comicOffsetPx: Float,
        dockMarginPx: Float,
        bottomMarginPx: Float
    ): Offset {
        if (containerWidthPx <= 0f || containerHeightPx <= 0f || ballSizePx <= 0f) return Offset.Zero
        val anchor = defaultPosition(
            containerWidthPx = containerWidthPx,
            containerHeightPx = containerHeightPx,
            sizePx = ballSizePx,
            dockMarginPx = dockMarginPx,
            bottomMarginPx = bottomMarginPx
        )
        val groupHeightPx = comicOffsetPx + ballSizePx
        return clampGroup(
            raw = Offset(anchor.x, anchor.y - comicOffsetPx),
            ballSizePx = ballSizePx,
            groupHeightPx = groupHeightPx,
            containerWidthPx = containerWidthPx,
            containerHeightPx = containerHeightPx
        )
    }

    /** 拖动过程中的位置夹紧：球任何时刻都必须完整留在容器内（容器比球还小时退化到左上角）。 */
    fun clamp(raw: Offset, sizePx: Float, containerWidthPx: Float, containerHeightPx: Float): Offset {
        if (sizePx <= 0f) return raw
        val maxX = (containerWidthPx - sizePx).coerceAtLeast(0f)
        val maxY = (containerHeightPx - sizePx).coerceAtLeast(0f)
        return Offset(raw.x.coerceIn(0f, maxX), raw.y.coerceIn(0f, maxY))
    }

    /**
     * 整组夹紧：横向按球宽（组宽 = 球宽），纵向按组高。
     *
     * 不能直接用 [clamp]：组的宽高不相等，用球的高（48）去夹会把下方漫画球挤出内容区，
     * 用组高（102）去夹又会多让出 54dp 的横向空间。
     */
    fun clampGroup(
        raw: Offset,
        ballSizePx: Float,
        groupHeightPx: Float,
        containerWidthPx: Float,
        containerHeightPx: Float
    ): Offset {
        if (ballSizePx <= 0f || groupHeightPx <= 0f) return raw
        val maxX = (containerWidthPx - ballSizePx).coerceAtLeast(0f)
        val maxY = (containerHeightPx - groupHeightPx).coerceAtLeast(0f)
        return Offset(raw.x.coerceIn(0f, maxX), raw.y.coerceIn(0f, maxY))
    }

    /** 组顶 → 下方漫画球的顶（加 [comicOffsetPx]）。 */
    fun comicTopForGroup(groupTopY: Float, comicOffsetPx: Float): Float =
        groupTopY + comicOffsetPx

    /** 下方漫画球的顶 → 组顶（减 [comicOffsetPx]）。 */
    fun groupTopForComic(comicTopY: Float, comicOffsetPx: Float): Float =
        comicTopY - comicOffsetPx

    /** 由球径和间距算组高（运行时 dp→px 后使用，避免调用方自己乘错）。 */
    fun groupHeightPx(ballSizePx: Float, gapPx: Float): Float =
        ballSizePx * 2f + gapPx

    /** 由球径和间距算漫画球相对组顶的偏移。 */
    fun comicTopOffsetPx(ballSizePx: Float, gapPx: Float): Float =
        ballSizePx + gapPx

    /**
     * 把像素位置编码成**可移动范围内的比例**：0 = 左/上边缘，1 = 右/下边缘。
     *
     * 持久化用比例而不是像素：旋转屏幕、分屏、换设备后仍落在相同的相对位置；
     * 容器无效（还没量出尺寸）时返回 null，调用方不要覆盖已存的位置。
     */
    fun encodePosition(
        raw: Offset,
        sizePx: Float,
        containerWidthPx: Float,
        containerHeightPx: Float
    ): Offset? {
        if (sizePx <= 0f || containerWidthPx <= 0f || containerHeightPx <= 0f) return null
        val clamped = clamp(raw, sizePx, containerWidthPx, containerHeightPx)
        val maxX = (containerWidthPx - sizePx).coerceAtLeast(0f)
        val maxY = (containerHeightPx - sizePx).coerceAtLeast(0f)
        return Offset(
            if (maxX <= 0f) 0f else (clamped.x / maxX).coerceIn(0f, 1f),
            if (maxY <= 0f) 0f else (clamped.y / maxY).coerceIn(0f, 1f)
        )
    }

    /** [encodePosition] 的逆运算：按当前容器尺寸还原，并夹紧到球完整可见。 */
    fun decodePosition(
        ratio: Offset,
        sizePx: Float,
        containerWidthPx: Float,
        containerHeightPx: Float
    ): Offset {
        if (sizePx <= 0f || containerWidthPx <= 0f || containerHeightPx <= 0f) return Offset.Zero
        val maxX = (containerWidthPx - sizePx).coerceAtLeast(0f)
        val maxY = (containerHeightPx - sizePx).coerceAtLeast(0f)
        return clamp(
            Offset(
                ratio.x.coerceIn(0f, 1f) * maxX,
                ratio.y.coerceIn(0f, 1f) * maxY
            ),
            sizePx,
            containerWidthPx,
            containerHeightPx
        )
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

    /**
     * 展开菜单的纵向位置：与整组**顶边对齐**（参考项目做法），再按容器高度夹紧。
     * 菜单比组高时不强行往上/下长，只保证整张卡完整可见。
     */
    fun menuTop(groupTopY: Float, menuHeightPx: Float, containerHeightPx: Float): Float {
        val maxTop = (containerHeightPx - menuHeightPx).coerceAtLeast(0f)
        return groupTopY.coerceIn(0f, maxTop)
    }

    /**
     * ⋮ 胶囊的纵向位置：默认贴在组**上方**（留 [gapPx]）；组被拖到顶边、上方放不下时
     * 改到组下方 —— 保证胶囊任何时候都完整可见（它是菜单唯一的展开入口）。
     */
    fun capsuleTop(
        groupTopY: Float,
        groupHeightPx: Float,
        capsuleHeightPx: Float,
        gapPx: Float
    ): Float {
        val above = groupTopY - gapPx - capsuleHeightPx
        return if (above >= 0f) above else groupTopY + groupHeightPx + gapPx
    }

    /** ⋮ 胶囊的横向位置：与组同心（组宽 = 球 48、胶囊 44，左右各让 2dp）。 */
    fun capsuleLeft(ballX: Float, ballWidthPx: Float, capsuleWidthPx: Float): Float =
        ballX + (ballWidthPx - capsuleWidthPx) / 2f

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
