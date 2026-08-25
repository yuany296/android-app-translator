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
        if (maxPointerCount > 1) {
            return TranslationLockGestureDecision(
                outcome = TranslationLockGestureOutcome.BLOCKED,
                reason = "multi_pointer"
            )
        }
        // 译文锁定层用于防止误触；单指任何手势（点击/滑动/长按）都应直接释放锁定，
        // 避免用户在模拟器/阅读器里翻页时感觉“译点不动”。
        return TranslationLockGestureDecision(
            outcome = TranslationLockGestureOutcome.RELEASE,
            reason = if (maxDistancePx <= touchSlopPx.toFloat() && durationMs <= tapTimeoutMs) "tap" else "touch"
        )
    }
}
