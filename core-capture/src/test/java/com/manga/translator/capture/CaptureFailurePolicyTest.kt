package com.manga.translator.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * 截图失败分类与恢复重试超时的单测。
 *
 * 为什么值得测：`classify` 决定 App 是「硬重建管线后自动重试一次」还是
 * 「直接弹窗让用户重新授权录屏」。分类错一次，用户就会在本该自动恢复的时候
 * 被要求重新授权，或者反过来白白重试一次再失败。
 */
class CaptureFailurePolicyTest {

    // ---------- classify：已有类型的异常原样透传 ----------

    @Test
    fun classify_keepsTypeOfExistingCaptureException() {
        assertEquals(
            CaptureFailureType.NOT_STARTED,
            CaptureFailurePolicy.classify(
                CaptureException(CaptureFailureType.NOT_STARTED, "Capture is not started")
            )
        )
    }

    @Test
    fun classify_keepsTimeoutTypeOfExistingCaptureException() {
        assertEquals(
            CaptureFailureType.TIMEOUT,
            CaptureFailurePolicy.classify(
                CaptureException(CaptureFailureType.TIMEOUT, "Screen capture timeout")
            )
        )
    }

    // ---------- classify：录屏令牌失效的各类系统文案 ----------

    @Test
    fun classify_securityExceptionMentioningMediaProjection() {
        assertEquals(
            CaptureFailureType.PROJECTION_EXPIRED,
            CaptureFailurePolicy.classify(SecurityException("media projection permission denied"))
        )
    }

    @Test
    fun classify_matchesKnownProjectionExpiredMessages() {
        val messages = listOf(
            "Don't re-use the resultData after starting a new projection",
            "projection token expired",
            "Token is no longer valid",
            "Media Projection has been stopped",
            "projection has been stopped",
            "non-current MediaProjection"
        )
        messages.forEach { raw ->
            assertEquals(
                "应判为录屏失效：$raw",
                CaptureFailureType.PROJECTION_EXPIRED,
                CaptureFailurePolicy.classify(IOException(raw))
            )
        }
    }

    @Test
    fun classify_ignoresMessageCase() {
        assertEquals(
            CaptureFailureType.PROJECTION_EXPIRED,
            CaptureFailurePolicy.classify(IOException("DON'T RE-USE THE RESULTDATA"))
        )
    }

    // ---------- classify：普通失败 ----------

    @Test
    fun classify_timeoutMessage() {
        assertEquals(
            CaptureFailureType.TIMEOUT,
            CaptureFailurePolicy.classify(IOException("Screen capture timeout"))
        )
    }

    @Test
    fun classify_notStartedMessage() {
        assertEquals(
            CaptureFailureType.NOT_STARTED,
            CaptureFailurePolicy.classify(IllegalStateException("Capture is not started"))
        )
    }

    @Test
    fun classify_unrecognisedMessageFallsBackToUnknown() {
        assertEquals(
            CaptureFailureType.UNKNOWN,
            CaptureFailurePolicy.classify(IllegalStateException("something exploded"))
        )
    }

    @Test
    fun classify_nullMessageFallsBackToUnknown() {
        assertEquals(
            CaptureFailureType.UNKNOWN,
            CaptureFailurePolicy.classify(IOException())
        )
    }

    // ---------- classify：优先级与已知脆点 ----------

    @Test
    fun classify_projectionExpiredWinsOverTimeout() {
        // 同时命中两类文案时按「录屏失效」处理：不重试比白重试一次更安全。
        assertEquals(
            CaptureFailureType.PROJECTION_EXPIRED,
            CaptureFailurePolicy.classify(
                IOException("timeout while reading: projection has been stopped")
            )
        )
    }

    @Test
    fun classify_securityExceptionWithoutMediaProjectionIsNotProjectionExpired() {
        assertEquals(
            CaptureFailureType.UNKNOWN,
            CaptureFailurePolicy.classify(SecurityException("permission denied"))
        )
    }

    @Test
    fun classify_characterisation_mediaProjectionWithoutSpaceIsNotMatched() {
        // 特征化测试（记录当前行为，不是期望行为）：
        // SecurityException 分支匹配的是带空格的 "media projection"，
        // 而模式表里也只有带空格的 "non-current mediaprojection"。
        // 因此无空格的 "MediaProjection ..." 文案会落到 UNKNOWN。
        // 这是当前实现的真实脆点，是否补齐由人工决定，见交付说明。
        assertEquals(
            CaptureFailureType.UNKNOWN,
            CaptureFailurePolicy.classify(SecurityException("MediaProjection permission denied"))
        )
    }

    // ---------- retryTimeoutMs：先压上限、再抬下限 ----------

    @Test
    fun retryTimeout_capsAtMax() {
        assertEquals(CaptureFailurePolicy.MAX_RETRY_TIMEOUT_MS, CaptureFailurePolicy.retryTimeoutMs(5000L))
        assertEquals(CaptureFailurePolicy.MAX_RETRY_TIMEOUT_MS, CaptureFailurePolicy.retryTimeoutMs(99999L))
    }

    @Test
    fun retryTimeout_liftsShortRequestsToMin() {
        assertEquals(CaptureFailurePolicy.MIN_RETRY_TIMEOUT_MS, CaptureFailurePolicy.retryTimeoutMs(100L))
        assertEquals(CaptureFailurePolicy.MIN_RETRY_TIMEOUT_MS, CaptureFailurePolicy.retryTimeoutMs(0L))
        assertEquals(CaptureFailurePolicy.MIN_RETRY_TIMEOUT_MS, CaptureFailurePolicy.retryTimeoutMs(-5L))
    }

    @Test
    fun retryTimeout_keepsRequestsInsideRange() {
        assertEquals(1000L, CaptureFailurePolicy.retryTimeoutMs(1000L))
    }

    @Test
    fun retryTimeout_boundariesAreInclusive() {
        assertEquals(
            CaptureFailurePolicy.MAX_RETRY_TIMEOUT_MS,
            CaptureFailurePolicy.retryTimeoutMs(CaptureFailurePolicy.MAX_RETRY_TIMEOUT_MS)
        )
        assertEquals(
            CaptureFailurePolicy.MIN_RETRY_TIMEOUT_MS,
            CaptureFailurePolicy.retryTimeoutMs(CaptureFailurePolicy.MIN_RETRY_TIMEOUT_MS)
        )
    }
}
