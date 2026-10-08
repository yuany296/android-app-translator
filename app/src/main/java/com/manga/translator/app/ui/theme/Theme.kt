package com.manga.translator.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MtColorScheme = lightColorScheme(
    primary = MtColors.Brand,
    onPrimary = Color.White,
    primaryContainer = MtColors.BrandTint,
    onPrimaryContainer = MtColors.BrandDeep,
    inversePrimary = MtColors.BrandDeep,
    secondary = MtColors.Brand,
    onSecondary = Color.White,
    secondaryContainer = MtColors.BrandTint,
    onSecondaryContainer = MtColors.BrandDeep,
    tertiary = MtColors.BrandDeep,
    onTertiary = Color.White,
    tertiaryContainer = MtColors.BrandTint,
    onTertiaryContainer = MtColors.BrandDeep,
    background = MtColors.PageBackground,
    onBackground = MtColors.TextPrimary,
    surface = MtColors.Surface,
    onSurface = MtColors.TextPrimary,
    surfaceVariant = MtColors.SurfaceMuted,
    onSurfaceVariant = MtColors.TextSecondary,
    // 设计稿里的弹窗 / 菜单都是纯白，关掉 M3 的高程染色
    surfaceTint = MtColors.Surface,
    inverseSurface = MtColors.TextPrimary,
    inverseOnSurface = MtColors.Surface,
    error = MtColors.Danger,
    onError = Color.White,
    errorContainer = MtColors.DangerTint,
    onErrorContainer = MtColors.DangerText,
    outline = MtColors.Border,
    outlineVariant = MtColors.Border,
    scrim = MtColors.Scrim
)

/** 字号阶梯：22 页面标题 / 18 弹窗标题 / 15 区块标题 / 13 正文 / 12 辅助 / 11 标签。 */
private val BaseTypography = Typography()

private val MtTypography = BaseTypography.copy(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )
)

/** 圆角：小标签 8 · 按钮与输入 14 · 卡片 18 · 弹窗 22。 */
private val MtShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(22.dp)
)

@Composable
fun MangaTranslatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MtColorScheme,
        typography = MtTypography,
        shapes = MtShapes,
        content = content
    )
}
