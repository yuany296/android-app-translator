package com.manga.translator.overlay

data class OverlayRenderConfig(
    val denseMode: Boolean = false,
    val autoFitText: Boolean = true,
    val allowExpand: Boolean = true,
    val touchEnabled: Boolean = false,
    val qualityProfile: RenderQualityProfile = RenderQualityProfile.SMOOTH_FIRST,
    val erasePolicy: EraseStrategyPolicy = EraseStrategyPolicy.AUTO_TIERED
)
