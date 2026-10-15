package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底栏控件状态表（2026-10-02「原文/译文」与「翻译/停止」语义拆分）。
 *
 * 这张表就是设计定稿：任何一行改动都必须先在这里体现，
 * 否则「运行时按钮还能点但点了没反应」这类问题会再次溜进 UI。
 */
class BrowserTranslateUiPolicyTest {

    private fun controls(
        pageLoaded: Boolean = true,
        running: Boolean = false,
        reShowBusy: Boolean = false,
        hasRender: Boolean = false,
        mode: BrowserViewMode = BrowserViewMode.TRANSLATED,
        canReshow: Boolean = false
    ) = BrowserTranslateUiPolicy.resolve(pageLoaded, running, reShowBusy, hasRender, mode, canReshow)

    @Test
    fun freshPage_toggleDisabledWithReasonAndPrimaryStart() {
        val c = controls()
        assertFalse(c.modeToggleEnabled)
        assertEquals(BrowserTranslateUiPolicy.HINT_NO_TRANSLATION, c.modeToggleDisabledReason)
        assertEquals("翻译", c.primaryLabel)
        assertEquals(BrowserPrimaryAction.START, c.primaryAction)
        assertTrue(c.primaryEnabled)
    }

    @Test
    fun noPage_primaryDisabledAndReasonSaysOpenPageFirst() {
        val c = controls(pageLoaded = false)
        assertFalse(c.primaryEnabled)
        assertFalse(c.modeToggleEnabled)
        assertEquals("先打开网页", c.modeToggleDisabledReason)
    }

    @Test
    fun running_toggleEnabledAndPrimaryStops() {
        val c = controls(running = true, hasRender = true)
        assertTrue(c.modeToggleEnabled)
        assertNull(c.modeToggleDisabledReason)
        assertEquals("停止", c.primaryLabel)
        assertEquals(BrowserPrimaryAction.STOP, c.primaryAction)
        assertTrue(c.primaryEnabled)
    }

    /** 运行中即使还没有任何译文上屏，也要能切视图（会话马上会产出）。 */
    @Test
    fun runningWithoutRenderYet_toggleStillEnabled() {
        assertTrue(controls(running = true, hasRender = false).modeToggleEnabled)
    }

    @Test
    fun hiddenWithCache_toggleEnabledAndPrimaryCanResume() {
        val c = controls(hasRender = true, mode = BrowserViewMode.ORIGINAL)
        assertTrue(c.modeToggleEnabled)
        assertEquals("翻译", c.primaryLabel)
        assertTrue(c.primaryEnabled)
    }

    @Test
    fun reShowBusy_disablesBothControls() {
        val c = controls(reShowBusy = true, hasRender = true, mode = BrowserViewMode.TRANSLATED)
        assertFalse(c.modeToggleEnabled)
        assertEquals("正在显示译文…", c.modeToggleDisabledReason)
        assertFalse(c.primaryEnabled)
    }

    /** 停止后：译文留在屏上，所以开关仍可用，主按钮回到「翻译」。 */
    @Test
    fun stoppedWithTranslationsStillOnScreen_toggleUsable() {
        val c = controls(running = false, hasRender = true)
        assertTrue(c.modeToggleEnabled)
        assertEquals("翻译", c.primaryLabel)
        assertTrue(c.primaryEnabled)
    }

    /**
     * 截图译文被「点一下恢复原文」摘掉后（hasRender 归 false，但结果还能重看）：
     * 开关必须仍可用 —— 2026-10-15 用户反馈「翻译层点不回来」的另一半根因是这里禁了开关。
     */
    @Test
    fun dismissedScreenshot_canReshowKeepsToggleUsable() {
        val c = controls(hasRender = false, mode = BrowserViewMode.ORIGINAL, canReshow = true)
        assertTrue(c.modeToggleEnabled)
        assertNull(c.modeToggleDisabledReason)
        assertEquals("翻译", c.primaryLabel)
    }

    /**
     * 状态行兜底文案（2026-10-12 第三轮改口径）。
     *
     * 底栏不再有「翻译/停止」与「原文｜译文」两个控件，文案一律指向**悬浮球**与
     * **球上的 ⋮ 菜单**；指向已消失的按钮就是死文案，这一条单测专门防它回潮。
     */
    @Test
    fun idleHint_coversFourQuadrants() {
        assertEquals(
            "点球自动翻完整页",
            BrowserTranslateUiPolicy.idleHint(BrowserViewMode.TRANSLATED, hasRender = false, pageLoaded = true)
        )
        assertEquals(
            "译文已盖上 · 点球可停止",
            BrowserTranslateUiPolicy.idleHint(BrowserViewMode.TRANSLATED, hasRender = true, pageLoaded = true)
        )
        assertEquals(
            "原文显示中 · 点球上菜单切译文",
            BrowserTranslateUiPolicy.idleHint(BrowserViewMode.ORIGINAL, hasRender = true, pageLoaded = true)
        )
        assertEquals(
            "打开漫画网页后点球翻译",
            BrowserTranslateUiPolicy.idleHint(BrowserViewMode.TRANSLATED, hasRender = false, pageLoaded = false)
        )
    }
}
