package com.manga.translator.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 色板（来源：设计稿 `14-改法-配色与组件规范`，2026-09-17 定稿）。
 *
 * 背景为纯白；层次靠「白底 → 卡片 → 1px 描边」分层，不靠阴影堆叠。
 *
 * 关于两个蓝：
 * - [Brand] `#3B82F6` 是**填充色**（填充按钮底、选中态），压白字。
 * - [BrandInk] `#2563EB` 是**Tonal 底上的文字/图标色**，比主色深一档，保证浅蓝底上的对比度。
 */
object MtColors {
    // 主色
    val Brand = Color(0xFF3B82F6)
    val BrandInk = Color(0xFF2563EB)
    val BrandDeep = Color(0xFF1D4ED8)
    val BrandTint = Color(0xFFE8F1FE)
    val BrandBorder = Color(0xFFBCD3FD)
    val BrandTextSoft = Color(0xFF5C7BC4)

    // 中性
    val PageBackground = Color(0xFFFFFFFF)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceMuted = Color(0xFFEEF1F7)
    val BubbleBg = Color(0xFFF4F7FB)
    val Border = Color(0xFFE4E8F0)
    val BorderStrong = Color(0xFFD9DCEA)
    val Divider = Color(0xFFEFF1F7)
    val StepperBg = Color(0xFFF3F4FA)
    val StepperDisabled = Color(0xFFB6BAC9)

    // 文字
    val TextPrimary = Color(0xFF10141C)
    val TextSecondary = Color(0xFF5A6273)
    val TextTertiary = Color(0xFF8B93A5)
    val TextOnBrandButton = Color(0xFF10141C)
    val TextOnMutedButton = Color(0xFF10141C)

    // 语义
    val Success = Color(0xFF0E9F6E)
    val Warning = Color(0xFFD97706)
    val Danger = Color(0xFFE0453F)
    val DangerText = Color(0xFFE0453F)
    val DangerTextSoft = Color(0xFFD93B34)
    val DangerTint = Color(0xFFFDECEA)
    val SwitchOff = Color(0xFFC7CDDA)

    // 空状态插画（用户指定 `空.svg`）
    val Illustration = Color(0xFFC4D0E4)

    // 蒙层（设计稿 #0F172A @ 45%）
    val Scrim = Color(0x730F172A)
}
