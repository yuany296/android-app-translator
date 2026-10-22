package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一次只有一个译文来源」的规则表（2026-10-16 重设计）。
 *
 * 为什么值得单独测一个这么小的函数：这条规则是这次重设计的根 ——
 * 改造前截图覆盖层与活翻译层各有一套显隐状态，谁都可能留在屏上，
 * 于是"点球"的行为取决于一堆布尔量的组合。现在切来源前必须先收旧层，
 * 这条判据一旦漂移，冲突就会以新的形式回来。
 */
class BrowserLayerPolicyTest {

    @Test
    fun nothingOnScreen_noRetireNeeded() {
        assertTrue(
            BrowserLayerPolicy.retireBefore(BrowserShownLayer.NONE, BrowserShownLayer.LIVE).isEmpty()
        )
        assertTrue(
            BrowserLayerPolicy.retireBefore(BrowserShownLayer.NONE, BrowserShownLayer.SNAPSHOT).isEmpty()
        )
        assertTrue(
            BrowserLayerPolicy.retireBefore(BrowserShownLayer.NONE, BrowserShownLayer.NONE).isEmpty()
        )
    }

    /** 幂等：目标层已经在屏上时不必先收再挂（否则每次点「译文」都要闪一下）。 */
    @Test
    fun sameLayer_noRetireNeeded() {
        assertTrue(
            BrowserLayerPolicy.retireBefore(BrowserShownLayer.LIVE, BrowserShownLayer.LIVE).isEmpty()
        )
        assertTrue(
            BrowserLayerPolicy.retireBefore(BrowserShownLayer.SNAPSHOT, BrowserShownLayer.SNAPSHOT).isEmpty()
        )
    }

    /** 换来源：必须先把当前层收掉（截图覆盖层 ↔ 活翻译层，双向都成立）。 */
    @Test
    fun switchingSources_retiresTheCurrentLayer() {
        assertEquals(
            setOf(BrowserShownLayer.LIVE),
            BrowserLayerPolicy.retireBefore(BrowserShownLayer.LIVE, BrowserShownLayer.SNAPSHOT)
        )
        assertEquals(
            setOf(BrowserShownLayer.SNAPSHOT),
            BrowserLayerPolicy.retireBefore(BrowserShownLayer.SNAPSHOT, BrowserShownLayer.LIVE)
        )
    }

    /** 切回原文：屏上不管挂着哪一层，都要收掉。 */
    @Test
    fun backToOriginal_retiresWhicheverLayerIsShown() {
        assertEquals(
            setOf(BrowserShownLayer.LIVE),
            BrowserLayerPolicy.retireBefore(BrowserShownLayer.LIVE, BrowserShownLayer.NONE)
        )
        assertEquals(
            setOf(BrowserShownLayer.SNAPSHOT),
            BrowserLayerPolicy.retireBefore(BrowserShownLayer.SNAPSHOT, BrowserShownLayer.NONE)
        )
    }
}
