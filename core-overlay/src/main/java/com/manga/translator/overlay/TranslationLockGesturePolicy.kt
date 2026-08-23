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
        if (maxDistancePx > touchSlopPx.toFloat()) {
            return TranslationLockGestureDecision(
                outcome = TranslationLockGestureOutcome.BLOCKED,
                reason = "move"
            )
        }
        if (durationMs > tapTimeoutMs) {
            return TranslationLockGestureDecision(
                outcome = TranslationLockGestureOutcome.BLOCKED,
                reason = "hold"
            )
        }
        return TranslationLockGestureDecision(
            outcome = TranslationLockGestureOutcome.RELEASE,
            reason = "tap"
        )
    }
}
