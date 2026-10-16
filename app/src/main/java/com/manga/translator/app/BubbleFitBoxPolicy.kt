package com.manga.translator.app

/**
 * 排版可用框（fit box）的纯几何策略（2026-10-16 用户要求）。
 *
 * ## 背景
 *
 * 用户原话：「维持原文字号，但字体加粗一点。然后看有需要自适应缩小的情况，
 * 只要不超过漫画里的对话框，就可以不进行自适应缩小。」
 *
 * 之前的规则是「译文装不下**原文字形框**就一路缩字号」（2026-10-12 定的）——
 * 于是本地引擎译文一啰嗦（如「好吧，你一直在睡了很长时间。」），小气泡里就被压到 8px。
 * 现在把判定框换成**对话框内部**（[com.manga.translator.overlay.BubbleInteriorScanner]
 * 量出来的可信容器）：装不进字形框、但装得进对话框 → **不缩**，按原字号铺开，
 * 先吃气泡里的空白；真正超出对话框才缩。
 *
 * ## 为什么是「对称扩」（而不是铺满容器）
 *
 * 渲染层以**文字节点盒的中心**为锚点（`translate(-50%,-50%)`）。若按容器四边直接铺满，
 * 文字在气泡内的相对位置会跟着容器偏心漂移（原文居中、容器偏心时译文就偏了），
 * 与「译文位置以原文为基准」的历史规则冲突。所以只做**绕字形框中点的对称扩张**：
 * 位置不动、盒子变大；某一侧容器更近时，按近侧限制（可能吃不满另一侧的空间，
 * 这是有意的保守取舍）。量不到可信容器（旁白/拟声词压在插画上）→ 原样返回。
 *
 * 纯 float 运算、不依赖 android.graphics —— 可在 JVM 单测直接跑（`RectF` 在单测里
 * 是 mockable jar，字段恒为 0，不能当数据容器用）。
 */
internal object BubbleFitBoxPolicy {

    /** 左/上/右/下（顺序与 [SeamBandGeometry.Box] 一致，避免与 RectF 的 width/height 混淆）。 */
    data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val isValid: Boolean get() = width > 0.5f && height > 0.5f
    }

    /**
     * 区域框（宿主 `bubble.x/y/w/h`）能不能当「对话框」用。
     *
     * 为什么需要第二级证据：白气泡画在白面板/白页面上时，内壁测量会跨过 3px 描边
     * 一路量到整页（轮廓与背景同色），[com.manga.translator.overlay.BubbleInteriorScanner] 的
     * 可信度闸会拒绝它 —— 而**区域检测器**给的框是按「亮斑轮廓」找的，对这类场景更稳
     * （真机 Lezhin 大量气泡正是白底白泡）。所以内壁量不到时退到区域框，但必须过两道闸：
     *
     * 1. **包住字形框**（区域只是在文字附近的一小块，而不是偏到别处的假阳性）；
     * 2. 不超字形框 [maxWidthRatio] / [maxHeightRatio] 倍 —— 「整片亮区」型假阳性
     *    （真机：24×21px 的单字 chip 配到 597×587px 的白色带，约 25 倍）在这里被拒绝。
     */
    fun credibleContainer(
        glyph: Box,
        candidate: Box?,
        maxWidthRatio: Float,
        maxHeightRatio: Float
    ): Box? {
        if (candidate == null || !candidate.isValid || !glyph.isValid) return null
        if (candidate.left > glyph.left || candidate.top > glyph.top ||
            candidate.right < glyph.right || candidate.bottom < glyph.bottom
        ) {
            return null
        }
        if (candidate.width > glyph.width * maxWidthRatio) return null
        if (candidate.height > glyph.height * maxHeightRatio) return null
        return candidate
    }

    /**
     * 绕 [glyph] 中点向 [container] 内部对称扩张，每侧留 [insetPx] 不贴描边。
     *
     * - [container] 为 null / 非法 / 内缩后已无空间 → 返回 [glyph]（不扩）；
     * - 扩张量受**近侧**限制：`half = min(中心到左, 右到中心)`，保证框不越出容器；
     * - 只扩不缩：容器比字形框还窄/矮时返回 [glyph]。
     */
    fun expandSymmetrically(glyph: Box, container: Box?, insetPx: Float): Box {
        if (container == null || !container.isValid || !glyph.isValid) return glyph
        val inset = insetPx.coerceAtLeast(0f)
        val left = container.left + inset
        val top = container.top + inset
        val right = container.right - inset
        val bottom = container.bottom - inset
        if (right - left <= 0.5f || bottom - top <= 0.5f) return glyph

        val centerX = (glyph.left + glyph.right) / 2f
        val centerY = (glyph.top + glyph.bottom) / 2f
        val halfWidth = minOf(centerX - left, right - centerX)
        val halfHeight = minOf(centerY - top, bottom - centerY)

        val newHalfWidth = maxOf(glyph.width / 2f, halfWidth)
        val newHalfHeight = maxOf(glyph.height / 2f, halfHeight)
        val out = Box(
            left = centerX - newHalfWidth,
            top = centerY - newHalfHeight,
            right = centerX + newHalfWidth,
            bottom = centerY + newHalfHeight
        )
        return if (out.isValid) out else glyph
    }
}
