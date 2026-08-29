package com.manga.translator.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationLockGesturePolicyTest {
    @Test
    fun decide_releasesForTapWithinSlopAndTimeout() {
        val decision = TranslationLockGesturePolicy.decide(
            maxDistancePx = 6f,
            durationMs = 80L,
            maxPointerCount = 1,
            touchSlopPx = 12,
            tapTimeoutMs = 100L
        )

        assertEquals(TranslationLockGestureOutcome.RELEASE, decision.outcome)
        assertEquals("tap", decision.reason)
    }

    @Test
    fun decide_releasesSwipeBeyondSlop() {
        val decision = TranslationLockGesturePolicy.decide(
            maxDistancePx = 24f,
            durationMs = 80L,
            maxPointerCount = 1,
            touchSlopPx = 12,
            tapTimeoutMs = 100L
        )

        assertEquals(TranslationLockGestureOutcome.RELEASE, decision.outcome)
        assertEquals("touch", decision.reason)
    }

    @Test
    fun decide_releasesMultiPointer() {
        // 2026-08-27：多指（双指缩放/翻页）同样释放锁定，避免阅读器"屏幕动不了"。
        val decision = TranslationLockGesturePolicy.decide(
            maxDistancePx = 6f,
            durationMs = 80L,
            maxPointerCount = 2,
            touchSlopPx = 12,
            tapTimeoutMs = 100L
        )

        assertEquals(TranslationLockGestureOutcome.RELEASE, decision.outcome)
        assertEquals("multi_pointer", decision.reason)
    }
}
