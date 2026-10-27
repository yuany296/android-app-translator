package com.manga.translator.app

/**
 * 接缝配对状态机（2026-10-27，阶段 3，参考 `reconciler-seam-ocr.js` 的 seam state）。
 *
 * 为什么需要：几何回退不能凭「两张图都贴边」就合并一把 —— 必须先确认这一对页面的
 * **当前 revision** 上确实产生过接缝证据。状态按「邻接 token + 两页内容指纹」建 key，
 * 换图 / 懒加载 blob 变化后旧状态必须标 `stale`，不能继续用于回退。
 *
 * 纯数据 + 纯函数，不依赖 android/graphics，可直接 JVM 单测。
 */
internal object SeamPairState {

    enum class Status {
        /** 没有足够证据，不运行接缝处理。 */
        SKIPPED,
        /** 正在处理（用于防止同一对页面重复进入）。 */
        RUNNING,
        /** 处理完成，回退可以使用这一对页面的证据。 */
        COMPLETED,
        /** 处理失败；不回退，保留两半各画。 */
        FAILED,
        /** 页面内容 revision 已变化，旧证据作废。 */
        STALE
    }

    data class Snapshot(
        val key: String,
        val adjacencyToken: String,
        val previousRevision: String,
        val currentRevision: String,
        val status: Status,
        val reasons: Set<String> = emptySet(),
        val updatedAt: Long = 0L,
        val error: String? = null
    )

    fun buildKey(previousRevision: String, currentRevision: String, adjacencyToken: String): String =
        "$previousRevision|$currentRevision|$adjacencyToken"

    fun start(
        previousRevision: String,
        currentRevision: String,
        adjacencyToken: String,
        at: Long
    ): Snapshot = Snapshot(
        key = buildKey(previousRevision, currentRevision, adjacencyToken),
        adjacencyToken = adjacencyToken,
        previousRevision = previousRevision,
        currentRevision = currentRevision,
        status = Status.RUNNING,
        updatedAt = at
    )

    fun complete(
        previousRevision: String,
        currentRevision: String,
        adjacencyToken: String,
        reasons: Set<String>,
        at: Long
    ): Snapshot = Snapshot(
        key = buildKey(previousRevision, currentRevision, adjacencyToken),
        adjacencyToken = adjacencyToken,
        previousRevision = previousRevision,
        currentRevision = currentRevision,
        status = Status.COMPLETED,
        reasons = reasons,
        updatedAt = at
    )

    fun fail(
        previousRevision: String,
        currentRevision: String,
        adjacencyToken: String,
        reasons: Set<String>,
        at: Long,
        error: String? = null
    ): Snapshot = Snapshot(
        key = buildKey(previousRevision, currentRevision, adjacencyToken),
        adjacencyToken = adjacencyToken,
        previousRevision = previousRevision,
        currentRevision = currentRevision,
        status = Status.FAILED,
        reasons = reasons,
        updatedAt = at,
        error = error
    )

    fun skip(
        previousRevision: String,
        currentRevision: String,
        adjacencyToken: String,
        reasons: Set<String>,
        at: Long
    ): Snapshot = Snapshot(
        key = buildKey(previousRevision, currentRevision, adjacencyToken),
        adjacencyToken = adjacencyToken,
        previousRevision = previousRevision,
        currentRevision = currentRevision,
        status = Status.SKIPPED,
        reasons = reasons,
        updatedAt = at
    )

    /**
     * 同一对页面的 revision 变了 → 标 stale（保留原因/时间，便于日志）。
     * revision 未变则原样返回。
     */
    fun markStale(
        snapshot: Snapshot,
        previousRevision: String,
        currentRevision: String,
        at: Long
    ): Snapshot {
        if (snapshot.previousRevision == previousRevision &&
            snapshot.currentRevision == currentRevision
        ) {
            return snapshot
        }
        return snapshot.copy(
            previousRevision = previousRevision,
            currentRevision = currentRevision,
            status = Status.STALE,
            updatedAt = at
        )
    }

    /** 只有 completed 且两页 revision 都对得上，才允许几何回退消费。 */
    fun isUsable(
        snapshot: Snapshot?,
        previousRevision: String,
        currentRevision: String
    ): Boolean {
        if (snapshot == null || snapshot.status != Status.COMPLETED) return false
        return snapshot.previousRevision == previousRevision &&
            snapshot.currentRevision == currentRevision
    }

    /** 接缝证据是否包含两侧贴边（几何回退的必要条件之一）。 */
    fun hasBothEdgeEvidence(snapshot: Snapshot?): Boolean {
        val reasons = snapshot?.reasons.orEmpty()
        return reasons.contains(REASON_UPPER_EDGE) && reasons.contains(REASON_LOWER_EDGE)
    }

    const val REASON_UPPER_EDGE = "upper_ocr_edge"
    const val REASON_LOWER_EDGE = "lower_ocr_edge"
    const val REASON_FRAGMENT_STRUCTURE = "fragment_structure"
    const val REASON_PIXEL_OVERLAP = "pixel_overlap"
    const val REASON_STRUCTURAL_FALLBACK = "structural_fallback"
}
