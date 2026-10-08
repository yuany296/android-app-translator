package com.manga.translator.capture

/**
 * 一次帧质量采样得到的统计量。
 *
 * @param samples 实际采样到的像素数
 * @param meanLuma 平均亮度（0-255）
 * @param stddevLuma 亮度标准差
 * @param darkRatio 亮度 <= [FramePolicy.DARK_LUMA_THRESHOLD] 的像素占比
 * @param lightRatio 亮度 >= [FramePolicy.LIGHT_LUMA_THRESHOLD] 的像素占比
 */
internal data class FrameLumaStats(
    val samples: Int,
    val meanLuma: Float,
    val stddevLuma: Float,
    val darkRatio: Float,
    val lightRatio: Float
)

/**
 * 截图链路上与「这一帧能不能用」和「按什么参数编码」相关的纯规则
 * （纯逻辑，不依赖 Android 运行时，可 JVM 单测）。
 *
 * 「可疑帧」的含义：整帧几乎只有一种亮度（接近纯黑或纯白），
 * 通常是刚启动录屏、屏幕切换动画中、或投屏尚未出图的中间态。
 * 这类帧会被直接丢弃，也不会进入最近帧快照缓存——否则下一次翻译会拿到一张全黑图。
 */
internal object FramePolicy {

    /** 帧质量采样的网格边数：在长宽上各取这么多个采样点。 */
    const val SAMPLE_GRID = 24

    /** 亮度低于此值算「暗」像素。 */
    const val DARK_LUMA_THRESHOLD = 24f

    /** 亮度高于此值算「亮」像素。 */
    const val LIGHT_LUMA_THRESHOLD = 245f

    /** 亮度标准差低于此值视为「整帧亮度过于均匀」。 */
    const val MIN_STDDEV_LUMA = 9f

    /** 暗像素或亮像素占比达到此值视为「被单一亮度主导」。 */
    const val DOMINANT_RATIO = 0.985f

    /** 最近帧快照的最大可复用年龄。 */
    const val RECENT_FRAME_MAX_AGE_MS = 1200L

    /** JPEG 编码质量下限。 */
    const val MIN_JPEG_QUALITY = 40

    /** JPEG 编码质量上限。 */
    const val MAX_JPEG_QUALITY = 95

    /**
     * 判定采样结果是否为「可疑帧」。
     *
     * 判定为可疑需要**同时**满足：亮度过于均匀，且被单一亮度主导。
     * 只看其中一个条件会把正常的高对比度画面（如大段文字的漫画页）误判掉。
     * 采样数为 0（取不到像素）一律视为可疑。
     */
    fun isSuspicious(stats: FrameLumaStats): Boolean {
        if (stats.samples <= 0) return true
        val uniform = stats.stddevLuma < MIN_STDDEV_LUMA
        val dominatedByOneEnd =
            stats.darkRatio >= DOMINANT_RATIO || stats.lightRatio >= DOMINANT_RATIO
        return uniform && dominatedByOneEnd
    }

    /**
     * 最近帧快照是否还新鲜到可以复用。
     * 边界含等号：ageMs == maxAgeMs 仍可用，超过才失效。
     */
    fun snapshotUsable(ageMs: Long, maxAgeMs: Long = RECENT_FRAME_MAX_AGE_MS): Boolean =
        ageMs <= maxAgeMs

    /**
     * 是否需要按 [maxWidth] 等比缩小。
     * 宽度无效（<= 0）或上限无效（<= 0）时不缩放。
     */
    fun shouldScale(width: Int, maxWidth: Int): Boolean =
        width > 0 && maxWidth > 0 && width > maxWidth

    /**
     * 等比缩放后的目标高度。不需要缩放时返回原高度。
     *
     * 结果至少为 1：极端长宽比（如 4000x1 缩到宽 1）向下取整会得到 0，
     * 而 0 高度的 Bitmap 会直接抛异常。
     */
    fun scaledHeight(width: Int, height: Int, maxWidth: Int): Int {
        if (!shouldScale(width, maxWidth)) return height.coerceAtLeast(1)
        return ((maxWidth.toFloat() / width.toFloat()) * height).toInt().coerceAtLeast(1)
    }

    /** 把 JPEG 质量夹到 [MIN_JPEG_QUALITY, MAX_JPEG_QUALITY]。 */
    fun clampedQuality(quality: Int): Int = quality.coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY)
}
