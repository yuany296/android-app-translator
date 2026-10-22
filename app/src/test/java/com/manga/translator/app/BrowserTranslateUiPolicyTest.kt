package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮球 / 「原文｜译文」开关的状态表（2026-10-16 重设计）。
 *
 * 这张表就是设计定稿：任何一行改动都必须先在这里体现，
 * 否则「运行时按钮还能点但点了没反应」这类问题会再次溜进 UI。
 *
 * 本轮的核心回归闸是 [idle_SNAPSHOT_ballRestoresOriginal] 与
 * [idle_withoutLayer_ballStartsSession] ——
 * 前者钉住"球在覆盖层上 = 恢复原文"（首页同款），
 * 后者钉住"球不再重放旧截图结果"（用户报的「点球无法翻译」的直接根因）。
 */
class BrowserTranslateUiPolicyTest {

    private fun controls(
        pageLoaded: Boolean = true,
        running: Boolean = false,
        reShowBusy: Boolean = false,
        screenshotBusy: Boolean = false,
        shown: BrowserShownLayer = BrowserShownLayer.NONE
    ) = BrowserTranslateUiPolicy.resolve(
        pageLoaded = pageLoaded,
        running = running,
        reShowBusy = reShowBusy,
        screenshotBusy = screenshotBusy,
        shown = shown
    )

    @Test
    fun freshPage_toggleDisabledWithReasonAndBallStarts() {
        val c = controls()
        assertFalse(c.modeToggleEnabled)
        assertEquals(BrowserTranslateUiPolicy.HINT_NO_TRANSLATION, c.modeToggleDisabledReason)
        assertEquals(BrowserTranslateUiPolicy.LABEL_START, c.primaryLabel)
        assertEquals(BrowserPrimaryAction.START, c.primaryAction)
        assertTrue(c.primaryEnabled)
        assertFalse(c.primaryBusy)
    }

    @Test
    fun noPage_ballHasNoActionAndReasonSaysOpenPageFirst() {
        val c = controls(pageLoaded = false)
        assertNull(c.primaryAction)
        assertFalse(c.primaryEnabled)
        assertFalse(c.modeToggleEnabled)
        assertEquals("先打开网页", c.modeToggleDisabledReason)
    }

    @Test
    fun running_ballStopsAndToggleUsable() {
        val c = controls(running = true, shown = BrowserShownLayer.LIVE)
        assertEquals(BrowserTranslateUiPolicy.LABEL_STOP, c.primaryLabel)
        assertEquals(BrowserPrimaryAction.STOP, c.primaryAction)
        assertTrue(c.primaryEnabled)
        assertTrue(c.modeToggleEnabled)
        assertNull(c.modeToggleDisabledReason)
    }

    /** 运行中即使屏上还没有任何译文层，也要能切视图（会话马上会产出）。 */
    @Test
    fun runningWithoutLayer_toggleStillUsable() {
        assertTrue(controls(running = true, shown = BrowserShownLayer.NONE).modeToggleEnabled)
    }

    /**
     * 截图覆盖层在屏（未在翻译）：球 = **恢复原文**（对齐首页那条链路），
     * 而不是"再看一次译文"，也不是"开始翻译"。
     */
    @Test
    fun idle_SNAPSHOT_ballRestoresOriginal() {
        val c = controls(shown = BrowserShownLayer.SNAPSHOT)
        assertEquals(BrowserTranslateUiPolicy.LABEL_RESTORE, c.primaryLabel)
        assertEquals(BrowserPrimaryAction.RESTORE, c.primaryAction)
        assertTrue(c.primaryEnabled)
        // 有覆盖层就说明"有东西可切"，分段开关必须可用（否则用户点它只会得到 toast）。
        assertTrue(c.modeToggleEnabled)
    }

    /**
     * 核心回归（用户 2026-10-16 报的现场）：截图译文被点掉之后（shown=NONE、没有会话在跑），
     * 点球必须**开始连续翻译**，不许再偷偷重放上一次的截图结果。
     *
     * 改造前这里是 `canReshow` 驱动的前置拦截（`onPrimaryAction` 里重发旧快照），
     * 用户看到的是"点球没翻译"。现在这个入口在策略层就不存在了 —— 只有 START。
     */
    @Test
    fun idle_withoutLayer_ballStartsSession() {
        val c = controls(shown = BrowserShownLayer.NONE)
        assertEquals(BrowserPrimaryAction.START, c.primaryAction)
        assertTrue(c.primaryEnabled)
    }

    /** 截图翻译进行中：球与开关都禁用，原因写在状态行/无障碍描述里。 */
    @Test
    fun screenshotBusy_disablesBothWithReason() {
        val c = controls(screenshotBusy = true)
        assertFalse(c.primaryEnabled)
        assertTrue(c.primaryBusy)
        assertFalse(c.modeToggleEnabled)
        assertEquals(BrowserTranslateUiPolicy.HINT_BUSY_SCREENSHOT, c.modeToggleDisabledReason)
        // 忙碌期间球的文案仍说明"它接下来会做什么"，不要闪成空白。
        assertEquals(BrowserTranslateUiPolicy.LABEL_START, c.primaryLabel)
    }

    @Test
    fun reShowBusy_disablesBothWithReason() {
        val c = controls(reShowBusy = true, shown = BrowserShownLayer.LIVE)
        assertFalse(c.primaryEnabled)
        assertTrue(c.primaryBusy)
        assertFalse(c.modeToggleEnabled)
        assertEquals(BrowserTranslateUiPolicy.HINT_BUSY_RESHOW, c.modeToggleDisabledReason)
    }

    /** 忙碌与运行态正交：运行中同时贴回（理论边界）也要以"忙"为准禁用。 */
    @Test
    fun busyWhileRunning_stillDisabled() {
        val c = controls(running = true, reShowBusy = true, shown = BrowserShownLayer.LIVE)
        assertFalse(c.primaryEnabled)
        assertEquals(BrowserPrimaryAction.STOP, c.primaryAction)
        assertTrue(c.primaryBusy)
    }

    /**
     * 状态行兜底文案。
     *
     * 2026-10-16 改口径：快照被点掉后不再承诺「点球重看」——
     * 球只负责整页翻译，想再来一次当前画面走 ⋮ 菜单的「截图翻译」。
     */
    @Test
    fun idleHint_coversAllQuadrants() {
        fun hint(
            mode: BrowserViewMode = BrowserViewMode.TRANSLATED,
            running: Boolean = false,
            shown: BrowserShownLayer = BrowserShownLayer.NONE,
            pageLoaded: Boolean = true
        ) = BrowserTranslateUiPolicy.idleHint(mode, running, shown, pageLoaded)

        assertEquals("打开漫画网页后点球翻译", hint(pageLoaded = false))
        assertEquals("点球自动翻完整页", hint())
        assertEquals("译文显示中", hint(shown = BrowserShownLayer.LIVE))
        assertEquals("译文显示中 · 点球可停止", hint(running = true, shown = BrowserShownLayer.LIVE))
        assertEquals(
            "原文显示中 · 球上菜单切译文",
            hint(mode = BrowserViewMode.ORIGINAL, running = true)
        )
        assertEquals("原文显示中 · 点球翻译", hint(mode = BrowserViewMode.ORIGINAL))
        assertEquals(
            "截图译文显示中 · 点屏幕恢复原文",
            hint(shown = BrowserShownLayer.SNAPSHOT)
        )
    }
}
