package com.manga.translator.capture

/**
 * 截图失败判定（纯逻辑，不依赖 Android 运行时，可 JVM 单测）。
 *
 * 这些规则直接决定用户看到的行为：
 * - 判为 [CaptureFailureType.TIMEOUT] → 走「硬重建管线 + 重试一次」的恢复路径；
 * - 判为 [CaptureFailureType.PROJECTION_EXPIRED] → 不重试，直接提示重新授权录屏。
 *
 * 所以分类错一次，用户就会在本该自动重试的时候被要求重新授权录屏；
 * 反过来，把「授权已失效」误判成超时，则会白白重试一次再失败。
 */
internal object CaptureFailurePolicy {

    /** 恢复重试的超时下限：请求值低于此值也会被抬到这里。 */
    const val MIN_RETRY_TIMEOUT_MS = 600L

    /** 恢复重试的超时上限：请求值高于此值也会被压到这里。 */
    const val MAX_RETRY_TIMEOUT_MS = 2200L

    /**
     * 各厂商/系统版本在录屏令牌失效时抛出的异常文案。
     * 全部按小写匹配（比对前会先把 message 转小写）。
     */
    private val PROJECTION_EXPIRED_PATTERNS = listOf(
        "don't re-use the resultdata",
        "projection token expired",
        "token is no longer valid",
        "media projection stopped",
        "projection has been stopped",
        "non-current mediaprojection"
    )

    /**
     * 把任意异常归类为 [CaptureFailureType]。
     *
     * - [CaptureException] 自带类型，原样返回；
     * - 「录屏失效」的判定优先于「超时」——同时命中时按录屏失效处理（不重试更安全）；
     * - 文案匹配不区分大小写。
     */
    fun classify(error: Throwable): CaptureFailureType {
        if (error is CaptureException) return error.type

        val message = error.message.orEmpty().lowercase()
        val projectionExpired =
            (error is SecurityException && message.contains("media projection")) ||
                PROJECTION_EXPIRED_PATTERNS.any { message.contains(it) }

        return when {
            projectionExpired -> CaptureFailureType.PROJECTION_EXPIRED
            message.contains("timeout") -> CaptureFailureType.TIMEOUT
            message.contains("not started") -> CaptureFailureType.NOT_STARTED
            else -> CaptureFailureType.UNKNOWN
        }
    }

    /**
     * 恢复重试时实际使用的超时。
     *
     * 注意夹取顺序有意义：先压上限、再抬下限。
     * 因此请求 100ms 会得到 [MIN_RETRY_TIMEOUT_MS]，请求 99999ms 会得到 [MAX_RETRY_TIMEOUT_MS]。
     */
    fun retryTimeoutMs(requestedMs: Long): Long =
        requestedMs.coerceAtMost(MAX_RETRY_TIMEOUT_MS).coerceAtLeast(MIN_RETRY_TIMEOUT_MS)
}
