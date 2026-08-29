package com.manga.translator.overlay

internal enum class TranslationLockGestureOutcome {
    RELEASE,
    BLOCKED
}

internal data class TranslationLockGestureDecision(
    val outcome: TranslationLockGestureOutcome,
    val reason: String
)

internal object TranslationLockGesturePolicy {
    fun decide(
        maxDistancePx: Float,
        durationMs: Long,
        maxPointerCount: Int,
        touchSlopPx: Int,
        tapTimeoutMs: Long
    ): TranslationLockGestureDecision {
        // 2026-08-27 放宽：任何手势（点击/滑动/长按/双指）都释放锁定并清除译文，
        // 避免用户在阅读器里双指缩放/滑动翻页时感觉"整个屏幕动不了"。
        // 译文锁定层只用于防止误触下层应用，不应拦截阅读手势。
        return TranslationLockGestureDecision(
            outcome = TranslationLockGestureOutcome.RELEASE,
            reason = if (maxPointerCount > 1) {
                "multi_pointer"
            } else if (maxDistancePx <= touchSlopPx.toFloat() && durationMs <= tapTimeoutMs) {
                "tap"
            } else {
                "touch"
            }
        )
    }
}
