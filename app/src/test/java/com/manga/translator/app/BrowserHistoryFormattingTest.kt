package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** 浏览历史详情时间格式（2026-11-02）。 */
class BrowserHistoryFormattingTest {

    @Test
    fun unknownTimestampShowsUnknownInsteadOf1970() {
        assertEquals("未知", formatHistoryDetailTime(0L))
        assertEquals("未知", formatHistoryDetailTime(-1L))
    }
}
