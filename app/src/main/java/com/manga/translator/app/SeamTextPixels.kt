package com.manga.translator.app

import kotlin.math.max
import kotlin.math.min

/**
 * 接缝「这一页到底有没有文字像素」三态（2026-10-27，对齐参考项目 `hasTextPixels`）。
 *
 * 只有 [Boolean.FALSE] 是**明确声明没有文字**；`null` 表示没有内容框 / 老数据 / 拿不准，
 * 必须按 true 处理（不能把 null 塌成 false）。这个区别决定接收侧是否要：
 * - 生成空壳盖板/贴片；
 * - 擦字、进清理图 mask、翻译。
 *
 * 纯几何，不依赖 android.graphics，可直接 JVM 单测。
 */
internal object SeamTextPixels {

    /** 判定「有可见面积」的最小像素（小于它视为 0，避免浮点噪声制造假文字）。 */
    const val MIN_VISIBLE_PX = 0.5f

    /**
     * 内容框（**接收页坐标系**，顶边可为负）在接收页 `0..pageWidth × 0..pageHeight` 内
     * 是否有可见文字面积。
     *
     * @return `true` 有可见面积；`false` 内容框有效但完全在页外；`null` 内容框缺失/退化
     *         （老数据，按 true 处理）。
     */
    fun resolveOnPage(
        contentLeft: Float,
        contentTop: Float,
        contentRight: Float,
        contentBottom: Float,
        pageWidth: Float,
        pageHeight: Float
    ): Boolean? {
        if (!contentLeft.isFinite() || !contentTop.isFinite() ||
            !contentRight.isFinite() || !contentBottom.isFinite() ||
            !pageWidth.isFinite() || !pageHeight.isFinite() ||
            pageWidth <= 0f || pageHeight <= 0f
        ) {
            return null
        }
        val width = contentRight - contentLeft
        val height = contentBottom - contentTop
        if (width <= MIN_VISIBLE_PX || height <= MIN_VISIBLE_PX) return null
        val visibleWidth = min(contentRight, pageWidth) - max(contentLeft, 0f)
        val visibleHeight = min(contentBottom, pageHeight) - max(contentTop, 0f)
        return visibleWidth > MIN_VISIBLE_PX && visibleHeight > MIN_VISIBLE_PX
    }

    /** `false` 才排除；`true` / `null` 都按「有文字」处理。 */
    fun contributesText(value: Boolean?): Boolean = value != false

    /** 明确声明「这一页没有文字像素」时才返回 true。 */
    fun isDeclaredNoText(value: Boolean?): Boolean = value == false
}
