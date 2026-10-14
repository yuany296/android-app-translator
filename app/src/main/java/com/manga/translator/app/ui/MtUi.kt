package com.manga.translator.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manga.translator.app.ui.theme.MtColors

/**
 * 改版通用组件与图标（规格来源：设计稿 `14-改法-配色与组件规范`）。
 *
 * 三条口径：
 * 1. 层次靠「白底 → 卡片 → 1px 描边」分层，阴影只做极轻的浮起提示。
 * 2. 圆角：卡片 18 / 按钮 12（小）·14（大）· 输入框 12 / 胶囊 40。
 * 3. 所有描边一律 1dp。
 */

// ---------------------------------------------------------------------------
// 图标：core icons 里没有的按设计稿自绘（24 视口，颜色交给 Icon 的 tint 覆盖）
// ---------------------------------------------------------------------------

/** 双向箭头（首页语言对的「交换键」）。 */
val MtSwapIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MtSwap",
        defaultWidth = 18.dp,
        defaultHeight = 18.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(7.5f, 8f)
            horizontalLineTo(17f)
            moveTo(14f, 4.8f)
            lineTo(17.2f, 8f)
            lineTo(14f, 11.2f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(16.5f, 16f)
            horizontalLineTo(7f)
            moveTo(10f, 12.8f)
            lineTo(6.8f, 16f)
            lineTo(10f, 19.2f)
        }
    }.build()
}

/** 时钟（底部导航「翻译历史」）。 */
val MtHistoryIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MtHistory",
        defaultWidth = 20.dp,
        defaultHeight = 20.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f
        ) {
            moveTo(3f, 12f)
            arcToRelative(9f, 9f, 0f, true, true, 18f, 0f)
            arcToRelative(9f, 9f, 0f, true, true, -18f, 0f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(12f, 11.2f)
            lineTo(12f, 16.8f)
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 6.6f)
            arcToRelative(1.3f, 1.3f, 0f, true, true, 0f, 2.6f)
            arcToRelative(1.3f, 1.3f, 0f, true, true, 0f, -2.6f)
            close()
        }
    }.build()
}

/** 列表（底部导航「术语表」）。 */
val MtListIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MtList",
        defaultWidth = 20.dp,
        defaultHeight = 20.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(8.5f, 6.5f)
            horizontalLineTo(20.5f)
            moveTo(8.5f, 12f)
            horizontalLineTo(20.5f)
            moveTo(8.5f, 17.5f)
            horizontalLineTo(20.5f)
        }
        listOf(6.5f, 12f, 17.5f).forEach { cy ->
            path(fill = SolidColor(Color.Black)) {
                moveTo(4.2f, cy - 1.4f)
                arcToRelative(1.4f, 1.4f, 0f, true, true, 0f, 2.8f)
                arcToRelative(1.4f, 1.4f, 0f, true, true, 0f, -2.8f)
                close()
            }
        }
    }.build()
}

/** 地球（底部导航「浏览器」：圆 + 赤道 + 两条经线弧）。 */
val MtBrowserIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MtBrowser",
        defaultWidth = 20.dp,
        defaultHeight = 20.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f
        ) {
            moveTo(3f, 12f)
            arcToRelative(9f, 9f, 0f, true, true, 18f, 0f)
            arcToRelative(9f, 9f, 0f, true, true, -18f, 0f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(3.6f, 9.4f)
            horizontalLineTo(20.4f)
            moveTo(3.6f, 14.6f)
            horizontalLineTo(20.4f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round
        ) {
            // 经线：两条对称椭圆弧（用 arcTo 近似，x 半径小、y 半径大）
            moveTo(12f, 3f)
            arcToRelative(5.2f, 9f, 0f, false, false, 0f, 18f)
            arcToRelative(5.2f, 9f, 0f, false, false, 0f, -18f)
            close()
        }
    }.build()
}

/**
 * 离开当前页（内嵌浏览器顶栏右侧的「回到浏览器界面」按钮，2026-10-12 用户指定形状）。
 *
 * 形状 =「圆角门框（右侧开口）+ 向右箭头」，两笔画一起说明动作：
 * 门框 = 现在所处的这一页，箭头朝外 = 从这一页出去。
 *
 * 演进过程（三次都因为「形状和动作对不上」被换掉）：
 * 地球 → 读作「网络 / 在系统浏览器打开」，又紧挨着齿轮，两者抢语义；
 * 圆角矩形 + 实心标题栏 → 只说明「这是一扇网页窗口」，和按钮要做的事（离开）无关；
 * 现版 → 直接在形状里画出「离开」（画布 `26:1` / `26:4` / `26:7` / `26:10` 四处统一）。
 */
val MtLeavePageIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MtLeavePage",
        defaultWidth = 20.dp,
        defaultHeight = 20.dp,
        viewportWidth = 20f,
        viewportHeight = 20f
    ).apply {
        val stroke = SolidColor(Color.Black)
        // 门框：右侧开口，只留左半边的圆角
        path(
            fill = null,
            stroke = stroke,
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(12.4f, 2.3f)
            horizontalLineTo(5.2f)
            arcToRelative(3f, 3f, 0f, false, false, -3f, 3f)
            verticalLineTo(14.7f)
            arcToRelative(3f, 3f, 0f, false, false, 3f, 3f)
            horizontalLineTo(12.4f)
        }
        // 箭头横线：从门框内侧穿出
        path(
            fill = null,
            stroke = stroke,
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(6.6f, 10f)
            horizontalLineTo(17.8f)
        }
        // 箭头头部
        path(
            fill = null,
            stroke = stroke,
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(13.4f, 5.6f)
            lineTo(17.8f, 10f)
            lineTo(13.4f, 14.4f)
        }
    }.build()
}

/**
 * 小锁（内嵌浏览器地址框左侧的「当前是 https 页面」提示，画布 `11:140`）。
 *
 * material-icons-core 里没有 Lock，且项目没引 extended 包，所以照设计稿画一个 14×14 的。
 */
val MtLockIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MtLock",
        defaultWidth = 14.dp,
        defaultHeight = 14.dp,
        viewportWidth = 14f,
        viewportHeight = 14f
    ).apply {
        // 锁梁（先画，被下面的锁体压住一段）
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.15f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(4.9f, 6.4f)
            verticalLineTo(4.5f)
            arcToRelative(2.1f, 2.1f, 0f, false, true, 4.2f, 0f)
            verticalLineTo(6.4f)
        }
        // 锁体
        path(fill = SolidColor(Color.Black)) {
            moveTo(4.1f, 6.4f)
            horizontalLineTo(9.9f)
            arcToRelative(1.2f, 1.2f, 0f, false, true, 1.2f, 1.2f)
            verticalLineTo(10.5f)
            arcToRelative(1.2f, 1.2f, 0f, false, true, -1.2f, 1.2f)
            horizontalLineTo(4.1f)
            arcToRelative(1.2f, 1.2f, 0f, false, true, -1.2f, -1.2f)
            verticalLineTo(7.6f)
            arcToRelative(1.2f, 1.2f, 0f, false, true, 1.2f, -1.2f)
            close()
        }
    }.build()
}

/**
 * 取景框（截图翻译，⋮ 菜单的那一行，画布 `23:267`）。
 *
 * 四个角括号 = 「框住一块区域」，是截图类动作最不容易被误读的形状
 * （相机图标会被读成「拍照」，裁切图标会被读成「裁剪图片」）。
 */
val MtScanIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MtScan",
        defaultWidth = 20.dp,
        defaultHeight = 20.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // 左上
            moveTo(3.5f, 8.5f)
            verticalLineTo(6f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, 2.5f, -2.5f)
            horizontalLineTo(8.5f)
            // 右上
            moveTo(15.5f, 3.5f)
            horizontalLineTo(18f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, 2.5f, 2.5f)
            verticalLineTo(8.5f)
            // 右下
            moveTo(20.5f, 15.5f)
            verticalLineTo(18f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, -2.5f, 2.5f)
            horizontalLineTo(15.5f)
            // 左下
            moveTo(8.5f, 20.5f)
            horizontalLineTo(6f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, -2.5f, -2.5f)
            verticalLineTo(15.5f)
        }
    }.build()
}

/**
 * 空心星标（顶栏收藏星的「未收藏」态，画布 `31:42` 左态）。
 *
 * 为什么是星而不是别的：浏览器里「收藏」的通用符号就是星（Chrome 地址栏右侧那颗），
 * 而项目里已经有的 `Icons.Filled.Star` 是实心 24dp 版，跟顶栏其它 20dp / 1.6 描边
 * 的图标规格不一致，所以照画布重画了一颗空心的。
 *
 * 2026-10-12 之前它长在设置弹层的「收藏当前页」行上，现在挂在浏览器顶栏。
 */
val MtStarIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MtStar",
        defaultWidth = 20.dp,
        defaultHeight = 20.dp,
        viewportWidth = 20f,
        viewportHeight = 20f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(10f, 2.6f)
            lineTo(11.94f, 7.33f)
            lineTo(17.04f, 7.71f)
            lineTo(13.14f, 11.02f)
            lineTo(14.35f, 15.99f)
            lineTo(10f, 13.3f)
            lineTo(5.65f, 15.99f)
            lineTo(6.86f, 11.02f)
            lineTo(2.96f, 7.71f)
            lineTo(8.06f, 7.33f)
            close()
        }
    }.build()
}

/**
 * 实心星标（顶栏收藏星的「已收藏」态，画布 `31:42` 右态）。
 *
 * 跟 [MtStarIcon] 是同一颗星的两个状态：形状路径完全一致，只是这里 `fill` 取代 `stroke`。
 * 路径必须逐点对齐 [MtStarIcon]，否则两态切换时星会「跳」一下。
 */
val MtStarFilledIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MtStarFilled",
        defaultWidth = 20.dp,
        defaultHeight = 20.dp,
        viewportWidth = 20f,
        viewportHeight = 20f
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            stroke = null,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(10f, 2.6f)
            lineTo(11.94f, 7.33f)
            lineTo(17.04f, 7.71f)
            lineTo(13.14f, 11.02f)
            lineTo(14.35f, 15.99f)
            lineTo(10f, 13.3f)
            lineTo(5.65f, 15.99f)
            lineTo(6.86f, 11.02f)
            lineTo(2.96f, 7.71f)
            lineTo(8.06f, 7.33f)
            close()
        }
    }.build()
}

/** 房子（底部导航「首页」）。 */
val MtHomeIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MtHome",
        defaultWidth = 20.dp,
        defaultHeight = 20.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3.5f, 10.5f)
            lineTo(12f, 3.5f)
            lineTo(20.5f, 10.5f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(5.8f, 9.6f)
            verticalLineTo(20.4f)
            horizontalLineTo(18.2f)
            verticalLineTo(9.6f)
        }
    }.build()
}

/** 行尾右箭头（细 chevron，stroke 1.5 ≈ 24 视口的 1.8）。 */
val MtChevronRightIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MtChevronRight",
        defaultWidth = 20.dp,
        defaultHeight = 20.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9f, 6f)
            lineTo(15f, 12f)
            lineTo(9f, 18f)
        }
    }.build()
}

/** 下拉箭头。 */
val MtChevronDownIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MtChevronDown",
        defaultWidth = 18.dp,
        defaultHeight = 18.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(7f, 10f)
            lineTo(12f, 15f)
            lineTo(17f, 10f)
        }
    }.build()
}

/** 播放三角（主按钮里的图标）。 */
val MtPlayIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MtPlay",
        defaultWidth = 18.dp,
        defaultHeight = 18.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7.5f, 5.2f)
            verticalLineTo(18.8f)
            lineTo(19f, 12f)
            close()
        }
    }.build()
}

/** 返回箭头（细 chevron，历史页顶栏用，与 MtChevronRightIcon 成对）。 */
val MtChevronLeftIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MtChevronLeft",
        defaultWidth = 20.dp,
        defaultHeight = 20.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(15f, 6f)
            lineTo(9f, 12f)
            lineTo(15f, 18f)
        }
    }.build()
}

/** 跳转箭头（右指长箭头，新标签页搜索框右侧的圆形跳转钮用）。 */
val MtArrowRightIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MtArrowRight",
        defaultWidth = 20.dp,
        defaultHeight = 20.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4.5f, 12f)
            lineTo(19f, 12f)
            moveTo(13.5f, 6.5f)
            lineTo(19f, 12f)
            lineTo(13.5f, 17.5f)
        }
    }.build()
}

/** 放大镜（细线，新标签页搜索框内用）。 */
val MtSearchIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MtSearch",
        defaultWidth = 18.dp,
        defaultHeight = 18.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(11f, 4.5f)
            curveTo(7.4f, 4.5f, 4.5f, 7.4f, 4.5f, 11f)
            curveTo(4.5f, 14.6f, 7.4f, 17.5f, 11f, 17.5f)
            curveTo(14.6f, 17.5f, 17.5f, 14.6f, 17.5f, 11f)
            curveTo(17.5f, 7.4f, 14.6f, 4.5f, 11f, 4.5f)
            close()
            moveTo(15.8f, 15.8f)
            lineTo(20f, 20f)
        }
    }.build()
}

// ---------------------------------------------------------------------------
// 基础组件
// ---------------------------------------------------------------------------

private val CardShape = RoundedCornerShape(18.dp)

/** 1dp 分隔线（卡片内用，不贴边）。 */
@Composable
fun MtDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MtColors.Border)
    )
}

/** 白卡：圆角 18 + 1px 描边 + 极轻阴影。 */
@Composable
fun MtCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .shadow(2.dp, shape, clip = false, ambientColor = MtColors.Border, spotColor = MtColors.Border)
            .clip(shape)
            .background(MtColors.Surface)
            .border(1.dp, MtColors.Border, shape),
        content = content
    )
}

// ---------------------------------------------------------------------------
// 下拉菜单
// ---------------------------------------------------------------------------

/**
 * 下拉菜单容器。
 *
 * [anchorWidthPx] 传锚点宽度（px）时，菜单宽度与锚点对齐 ——
 * 菜单比所在行窄会显得不协调（2026-09-18 用户反馈）。
 */
@Composable
fun MtDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchorWidthPx: Int = 0,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = if (anchorWidthPx > 0) {
            Modifier.width(with(density) { anchorWidthPx.toDp() })
        } else {
            Modifier
        },
        content = content
    )
}

/** 下拉项：文字规格与「设置行」的值一致（14 / Regular / #10141C）。 */
@Composable
fun MtDropdownItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = MtColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        onClick = onClick
    )
}

/**
 * 开关：选中 = 品牌蓝轨 + 白滑块，未选中 = 灰轨 + 白滑块。
 *
 * 为什么要有这个组件（2026-10-14）：浏览器弹层直接用 M3 `Switch` 默认配色
 * （紫色轨），和全 App 的品牌蓝不符 —— 统一收口到这里，别处不再裸用 `Switch`。
 */
@Composable
fun MtSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MtColors.Brand,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = MtColors.SwitchOff,
            uncheckedBorderColor = Color.Transparent
        )
    )
}

/** 设置行：名称（13 次级色）+ 值（14 主色）+ 右箭头，行高 56。 */
@Composable
fun MtListRow(
    name: String,
    value: String,
    onClick: () -> Unit,
    showDivider: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = name,
                fontSize = 13.sp,
                color = MtColors.TextSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                fontSize = 14.sp,
                color = MtColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )
            Icon(
                imageVector = MtChevronRightIcon,
                contentDescription = null,
                tint = MtColors.TextTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
        if (showDivider) MtDivider()
    }
}

/**
 * M3 Filled 字段：浅灰底、无边框、圆角 12、高 60；11px 小标签压在 15px 值上方。
 *
 * [labelOutside] = true 时标签移到框外上方、框高压到 52（2026-09-18 用户要求，配置表单用）。
 */
@Composable
fun MtFilledField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    height: Dp = 60.dp,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    labelOutside: Boolean = false
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (labelOutside) {
            Text(text = label, fontSize = 13.sp, color = MtColors.TextSecondary, maxLines = 1)
            Box(modifier = Modifier.height(6.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (labelOutside) 52.dp else height)
                .clip(RoundedCornerShape(12.dp))
                .background(MtColors.SurfaceMuted)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (!labelOutside) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        color = MtColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = value,
                    fontSize = 15.sp,
                    color = MtColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            trailing?.invoke()
        }
    }
}

/**
 * 主按钮：填充蓝、胶囊、白字 16（首页「启动服务」）。
 *
 * [height] 默认 52（首页全宽主按钮）；内嵌浏览器底栏这种「和别的控件同排」的场合传更矮的值。
 */
@Composable
fun MtPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = MtColors.Brand,
    contentColor: Color = Color.White,
    enabled: Boolean = true,
    height: Dp = 52.dp
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(40.dp))
            .background(if (enabled) container else MtColors.SwitchOff)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Box(modifier = Modifier.size(8.dp))
        }
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}

/** 次按钮：浅蓝底 + 蓝字（Tonal）。禁用时灰底弱字（2026-10-14「网页文字翻译」按钮要禁用态）。 */
@Composable
fun MtTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 42.dp,
    fontSize: Int = 13,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) MtColors.BrandTint else MtColors.SurfaceMuted)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MtColors.BrandInk,
                modifier = Modifier.size(16.dp)
            )
            Box(modifier = Modifier.size(6.dp))
        }
        Text(
            text = text,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MtColors.BrandInk else MtColors.TextTertiary
        )
    }
}

/** 次按钮：白底 + 描边。 */
@Composable
fun MtOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 42.dp,
    fontSize: Int = 13
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(MtColors.Surface)
            .border(1.dp, MtColors.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.SemiBold,
            color = MtColors.TextPrimary
        )
    }
}

/** 危险按钮：浅红底 + 红字。 */
@Composable
fun MtDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 36.dp
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(MtColors.DangerTint)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MtColors.Danger else MtColors.TextTertiary
        )
    }
}

/** 纯文字弱按钮。 */
@Composable
fun MtTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MtColors.TextSecondary,
    fontSize: Int = 13,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) color else MtColors.TextTertiary
        )
    }
}
