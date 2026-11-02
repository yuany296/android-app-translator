package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮球状态呈现（方案 B：图标常驻，状态只走外圈/角标）。
 *
 * 这组断言钉住的是「哪颗球在什么状态下显示什么」，避免以后加状态时又退回
 * 「把图标换成方块/对勾」—— 那会让两颗球在最需要辨认的时候反而认不出来。
 */
class BrowserFloatingBallPresentationTest {

    @Test
    fun comicIdle_hasNoBadgeOrRing() {
        val p = BrowserFloatingBallPresentation.comic(BrowserPrimaryAction.START, busy = false)
        assertNull(p.badge)
        assertFalse(p.spinner)
        assertFalse(p.runningRing)
        assertFalse(p.dimmed)
    }

    @Test
    fun comicBusy_showsSpinner() {
        val p = BrowserFloatingBallPresentation.comic(
            action = BrowserPrimaryAction.START,
            busy = true
        )
        assertTrue(p.spinner)
        assertNull(p.badge)
        assertFalse(p.runningRing)
        assertFalse(p.dimmed)
    }

    @Test
    fun comicRunning_showsRingAndStopBadge() {
        val p = BrowserFloatingBallPresentation.comic(
            action = BrowserPrimaryAction.STOP,
            busy = false
        )
        assertEquals(BrowserBallBadge.STOP, p.badge)
        assertTrue(p.runningRing)
        assertFalse(p.spinner)
        assertFalse(p.dimmed)
    }

    @Test
    fun comicRestore_showsCloseBadge() {
        val p = BrowserFloatingBallPresentation.comic(
            action = BrowserPrimaryAction.RESTORE,
            busy = false
        )
        assertEquals(BrowserBallBadge.RESTORE, p.badge)
        assertFalse(p.spinner)
        assertFalse(p.runningRing)
        assertFalse(p.dimmed)
    }

    @Test
    fun comicWithoutPage_isDimmed() {
        val p = BrowserFloatingBallPresentation.comic(action = null, busy = false)
        assertTrue(p.dimmed)
        assertNull(p.badge)
        assertFalse(p.spinner)
        assertFalse(p.runningRing)
    }

    @Test
    fun webpageIdle_hasNoBadge() {
        val p = BrowserFloatingBallPresentation.webpage(busy = false, applied = false)
        assertNull(p.badge)
        assertFalse(p.spinner)
    }

    @Test
    fun webpageBusy_showsSpinnerOnly() {
        val p = BrowserFloatingBallPresentation.webpage(busy = true, applied = true)
        assertTrue(p.spinner)
        assertNull(p.badge)
    }

    @Test
    fun webpageApplied_showsCheckBadge() {
        val p = BrowserFloatingBallPresentation.webpage(busy = false, applied = true)
        assertEquals(BrowserBallBadge.CHECK, p.badge)
        assertFalse(p.spinner)
    }
}
