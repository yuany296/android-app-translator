package com.manga.translator.overlay

enum class EraseStrategy {
    FAST_FILL,
    EDGE_AWARE_FILL,
    OPENCV_INPAINT
}

enum class EraseStrategyPolicy {
    AUTO_TIERED,
    FAST_ONLY,
    OPENCV_WHEN_AVAILABLE
}

enum class RenderQualityProfile {
    SMOOTH_FIRST
}

