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
    fun decide_blocksMultiPointer() {
        val decision = TranslationLockGesturePolicy.decide(
            maxDistancePx = 6f,
            durationMs = 80L,
            maxPointerCount = 2,
            touchSlopPx = 12,
            tapTimeoutMs = 100L
        )

        assertEquals(TranslationLockGestureOutcome.BLOCKED, decision.outcome)
        assertEquals("multi_pointer", decision.reason)
    }
}
