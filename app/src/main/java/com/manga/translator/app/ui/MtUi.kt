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

/** 次按钮：浅蓝底 + 蓝字（Tonal）。 */
@Composable
fun MtTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 42.dp,
    fontSize: Int = 13
) {
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(MtColors.BrandTint)
            .clickable(onClick = onClick)
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
            color = MtColors.BrandInk
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
