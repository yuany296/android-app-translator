package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 3：`SeamPairState` 的 revision 失效语义。
 *
 * 关键约定：几何回退只能消费 `completed` 且两页 revision 都对得上的状态；
 * 换图/blob 变化后旧状态必须 stale，不能拿旧证据合并新页。
 */
class SeamPairStateTest {

    private val token = "prev|cur"

    @Test
    fun keyIncludesBothRevisionsAndAdjacencyToken() {
        val key = SeamPairState.buildKey("fp-a", "fp-b", token)
        assertEquals("fp-a|fp-b|$token", key)
        assertEquals(
            key,
            SeamPairState.buildKey("fp-a", "fp-b", token)
        )
        assertFalse(key == SeamPairState.buildKey("fp-a2", "fp-b", token))
    }

    @Test
    fun completedStateIsUsableOnlyWhenBothRevisionsMatch() {
        val state = SeamPairState.complete(
            previousRevision = "fp-a",
            currentRevision = "fp-b",
            adjacencyToken = token,
            reasons = setOf(
                SeamPairState.REASON_UPPER_EDGE,
                SeamPairState.REASON_LOWER_EDGE,
                SeamPairState.REASON_FRAGMENT_STRUCTURE
            ),
            at = 100L
        )
        assertTrue(SeamPairState.isUsable(state, "fp-a", "fp-b"))
        assertFalse(SeamPairState.isUsable(state, "fp-a2", "fp-b"))
        assertFalse(SeamPairState.isUsable(state, "fp-a", "fp-b2"))
        assertTrue(SeamPairState.hasBothEdgeEvidence(state))
    }

    @Test
    fun runningFailedOrSkippedStateIsNotUsable() {
        val running = SeamPairState.start("fp-a", "fp-b", token, at = 1L)
        val failed = SeamPairState.fail("fp-a", "fp-b", token, emptySet(), at = 2L, error = "no_match")
        val skipped = SeamPairState.skip("fp-a", "fp-b", token, emptySet(), at = 3L)
        assertFalse(SeamPairState.isUsable(running, "fp-a", "fp-b"))
        assertFalse(SeamPairState.isUsable(failed, "fp-a", "fp-b"))
        assertFalse(SeamPairState.isUsable(skipped, "fp-a", "fp-b"))
        assertFalse(SeamPairState.isUsable(null, "fp-a", "fp-b"))
    }

    @Test
    fun revisionChangeMarksOldEvidenceStaleAndKeepsNewRevision() {
        val completed = SeamPairState.complete(
            "fp-a", "fp-b", token,
            setOf(SeamPairState.REASON_UPPER_EDGE, SeamPairState.REASON_LOWER_EDGE),
            at = 10L
        )
        val stale = SeamPairState.markStale(completed, "fp-a2", "fp-b", at = 20L)
        assertEquals(SeamPairState.Status.STALE, stale.status)
        assertEquals("fp-a2", stale.previousRevision)
        assertEquals("fp-b", stale.currentRevision)
        assertEquals(20L, stale.updatedAt)
        assertFalse(SeamPairState.isUsable(stale, "fp-a2", "fp-b"))
    }

    @Test
    fun sameRevisionDoesNotBecomeStale() {
        val completed = SeamPairState.complete("fp-a", "fp-b", token, emptySet(), at = 10L)
        val same = SeamPairState.markStale(completed, "fp-a", "fp-b", at = 20L)
        assertEquals(SeamPairState.Status.COMPLETED, same.status)
        assertEquals(10L, same.updatedAt)
    }
}
