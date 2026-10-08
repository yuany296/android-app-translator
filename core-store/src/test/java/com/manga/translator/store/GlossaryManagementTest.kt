package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 多术语表管理：生效优先级排序、跨表冲突检测、合并后的生效顺序继承。 */
class GlossaryManagementTest {

    // ---------- 生效优先级排序 ----------

    @Test
    fun movedOrder_movesUpAndDown() {
        val actives = listOf("A", "B", "C")
        assertEquals(listOf("B", "A", "C"), GlossaryRepository.movedOrder(actives, "B", -1))
        assertEquals(listOf("A", "C", "B"), GlossaryRepository.movedOrder(actives, "B", 1))
    }

    @Test
    fun movedOrder_returnsNullAtBoundary() {
        val actives = listOf("A", "B")
        assertNull(GlossaryRepository.movedOrder(actives, "A", -1))
        assertNull(GlossaryRepository.movedOrder(actives, "B", 1))
    }

    @Test
    fun movedOrder_returnsNullForInactiveOrBlankName() {
        val actives = listOf("A", "B")
        assertNull(GlossaryRepository.movedOrder(actives, "C", -1))
        assertNull(GlossaryRepository.movedOrder(actives, "  ", -1))
        assertNull(GlossaryRepository.movedOrder(actives, "A", 0))
    }

    @Test
    fun movedOrder_ignoresSurroundingWhitespace() {
        assertEquals(
            listOf("B", "A"),
            GlossaryRepository.movedOrder(listOf("A", "B"), " B ", -1)
        )
    }

    // ---------- 跨表冲突检测 ----------

    @Test
    fun collectConflicts_flagsSameSourceWithDifferentTargets() {
        val conflicts = GlossaryRepository.collectConflicts(
            entriesByCollection = mapOf(
                "默认" to listOf("Eren" to "艾伦"),
                "进击的巨人" to listOf("Eren" to "艾连")
            ),
            activeOrder = listOf("默认", "进击的巨人")
        )
        assertEquals(1, conflicts.size)
        val conflict = conflicts[0]
        assertEquals("Eren", conflict.source)
        assertEquals("艾伦", conflict.effectiveTarget)
        assertEquals("默认", conflict.effectiveCollection)
        assertEquals(2, conflict.variants.size)
    }

    @Test
    fun collectConflicts_ignoresIdenticalTargets() {
        val conflicts = GlossaryRepository.collectConflicts(
            entriesByCollection = mapOf(
                "默认" to listOf("Eren" to "艾伦"),
                "进击的巨人" to listOf("Eren" to "艾伦")
            ),
            activeOrder = listOf("默认", "进击的巨人")
        )
        assertEquals(0, conflicts.size)
    }

    @Test
    fun collectConflicts_winnerFollowsActiveOrder() {
        val conflicts = GlossaryRepository.collectConflicts(
            entriesByCollection = mapOf(
                "默认" to listOf("Eren" to "艾伦"),
                "进击的巨人" to listOf("Eren" to "艾连")
            ),
            activeOrder = listOf("进击的巨人", "默认")
        )
        assertEquals(1, conflicts.size)
        assertEquals("艾连", conflicts[0].effectiveTarget)
        assertEquals("进击的巨人", conflicts[0].effectiveCollection)
    }

    @Test
    fun collectConflicts_skipsCollectionsOutsideActiveOrder() {
        val conflicts = GlossaryRepository.collectConflicts(
            entriesByCollection = mapOf(
                "默认" to listOf("Eren" to "艾伦"),
                "未启用" to listOf("Eren" to "艾连")
            ),
            activeOrder = listOf("默认")
        )
        assertEquals(0, conflicts.size)
    }

    @Test
    fun collectConflicts_collectsVariantsFromThreeCollections() {
        val conflicts = GlossaryRepository.collectConflicts(
            entriesByCollection = mapOf(
                "A" to listOf("Eren" to "艾伦"),
                "B" to listOf("Eren" to "艾连"),
                "C" to listOf("Eren" to "艾倫")
            ),
            activeOrder = listOf("A", "B", "C")
        )
        assertEquals(1, conflicts.size)
        assertEquals(3, conflicts[0].variants.size)
        assertEquals("艾伦", conflicts[0].effectiveTarget)
    }

    @Test
    fun collectConflicts_sortedBySource() {
        val conflicts = GlossaryRepository.collectConflicts(
            entriesByCollection = mapOf(
                "A" to listOf("Zed" to "泽1", "Eren" to "艾1"),
                "B" to listOf("Zed" to "泽2", "Eren" to "艾2")
            ),
            activeOrder = listOf("A", "B")
        )
        assertEquals(listOf("Eren", "Zed"), conflicts.map { it.source })
    }

    // ---------- 合并后的生效顺序 ----------

    @Test
    fun planMergedActiveOrder_sourceActive_targetTakesItsSeat() {
        // A 原本在第 2 位，合并进 C 后 C 应当顶到第 2 位
        assertEquals(
            listOf("X", "C", "B"),
            GlossaryRepository.planMergedActiveOrder(listOf("X", "A", "B"), "A", "C")
        )
    }

    @Test
    fun planMergedActiveOrder_targetAlreadyActiveKeepsPosition() {
        assertEquals(
            listOf("X", "B"),
            GlossaryRepository.planMergedActiveOrder(listOf("X", "A", "B"), "A", "B")
        )
    }

    @Test
    fun planMergedActiveOrder_inactiveSourceLeavesOrderUntouched() {
        assertEquals(
            listOf("X", "Y"),
            GlossaryRepository.planMergedActiveOrder(listOf("X", "Y"), "A", "B")
        )
    }

    @Test
    fun planMergedActiveOrder_handlesRemovingFirstItem() {
        // A 原本在第 1 位，合并进 C 后 C 接替第 1 位
        assertEquals(
            listOf("C", "B"),
            GlossaryRepository.planMergedActiveOrder(listOf("A", "B"), "A", "C")
        )
    }
}
