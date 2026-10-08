package com.manga.translator.app

/**
 * 接缝带（seam band）几何：把「跨两张图的气泡」换算到一条**独立坐标系**里。
 *
 * ## 为什么需要它
 *
 * 普通场景是「一张图一个覆盖层 root」，root 尺寸 = 那张图的可见矩形。跨缝的气泡于是被拆成
 * 两个独立渲染单元（上页半块 + 下页半块），各自用自己那张清理图 —— 接缝处必然出现
 * 「上下两层清理图」，译文也被排了两遍（用户 2026-09-30 反馈：「为什么不能只有一个覆盖层，
 * 而是必须分离在两页。字也是」）。
 *
 * 做法：把「接缝带」——从本图那张**清理图**里裁出来的、正好覆盖整个跨缝气泡的那一条
 * （上端落在本图内、下端落在下一张图内）——当成一张独立的虚拟图交给渲染层。整条带子来自
 * 同一张清理图，所以接缝处不可能有两种纹理；带内坐标就是普通场景的百分比坐标，遮罩/四边形/
 * 实底/字号/清理图贴图数学全链路复用。
 *
 * ## 坐标口径（三种，别混）
 *
 * 1. **源图百分比**（0-100，相对原图）——OCR 与场景契约的口径，[FinalBubble] 的
 *    `boxPercent` / `coverBoxPercent` / `*PolygonPercent` 都是它；
 * 2. **拼接图像素**（topStrip + 本图 + bottomStrip 的像素坐标）——`layoutRect` / `maskRect`
 *    以及 [TranslationTimer] 的诊断口径；接缝归属判定也在这一层；
 * 3. **带内百分比**（0-100，相对接缝带）——下发给渲染层的接缝场景用它。
 *
 * 换算链：2 → 3 用 [percentBox] / [stitchedPointsToPercent]；1 → 2 用
 * [sourcePercentToStitched] / [sourcePercentPointsToStitched]。
 *
 * 纯 float 运算、不依赖 android.graphics，可直接在 JVM 单测里跑。
 */
internal object SeamBandGeometry {

    /** 左/上/右/下（本文件里一律是这个顺序，避免 RectF 的 left/top/right/bottom 与 width/height 混淆）。 */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val isValid: Boolean get() = width > 0.5f && height > 0.5f
    }

    /**
     * 带 = 所有必须被复用的框的并集，四周外扩 [padPx]，再夹进拼接图边界与 [limitTop]。
     *
     * [limitTop] 是拼接图里「本图」的起点（topStrip 高度）：带的上端绝不能伸进 topStrip，
     * 那段像素属于**上一张图**，映射到屏幕会落到本图上方（盖住上一张图的内容）。
     */
    fun bandOf(
        boxes: List<Box>,
        padPx: Float,
        stitchedWidth: Float,
        limitTop: Float,
        stitchedHeight: Float
    ): Box? {
        val valid = boxes.filter { it.isValid }
        if (valid.isEmpty()) return null
        val box = Box(
            left = (valid.minOf { it.left } - padPx).coerceIn(0f, stitchedWidth),
            top = (valid.minOf { it.top } - padPx).coerceIn(limitTop, stitchedHeight),
            right = (valid.maxOf { it.right } + padPx).coerceIn(0f, stitchedWidth),
            bottom = (valid.maxOf { it.bottom } + padPx).coerceIn(0f, stitchedHeight)
        )
        return if (box.isValid) box else null
    }

    /** 拼接图像素框 → 带内百分比（x,y,w,h）。 */
    fun percentBox(box: Box, band: Box): FloatArray = floatArrayOf(
        (box.left - band.left) / band.width * 100f,
        (box.top - band.top) / band.height * 100f,
        box.width / band.width * 100f,
        box.height / band.height * 100f
    )

    /** 源图百分比框（相对原图）→ 拼接图像素框；缺省/非法时返回 null。 */
    fun sourcePercentToStitched(percent: FloatArray?, srcWidth: Float, srcHeight: Float, topH: Float): Box? {
        if (percent == null || percent.size != 4) return null
        val left = percent[0] / 100f * srcWidth
        val top = percent[1] / 100f * srcHeight + topH
        val width = percent[2] / 100f * srcWidth
        val height = percent[3] / 100f * srcHeight
        val box = Box(left, top, left + width, top + height)
        return if (box.isValid) box else null
    }

    /** 源图百分比点（x,y 交替，相对原图）→ 拼接图像素点。 */
    fun sourcePercentPointsToStitched(
        points: List<Float>?,
        srcWidth: Float,
        srcHeight: Float,
        topH: Float
    ): List<Float>? {
        if (points == null || points.size < 6 || points.size % 2 != 0) return null
        return List(points.size) { index ->
            if (index % 2 == 0) points[index] / 100f * srcWidth
            else points[index] / 100f * srcHeight + topH
        }
    }

    /** 拼接图像素点 → 带内百分比点。 */
    fun stitchedPointsToPercent(points: List<Float>, band: Box): List<Float> = List(points.size) { index ->
        if (index % 2 == 0) (points[index] - band.left) / band.width * 100f
        else (points[index] - band.top) / band.height * 100f
    }

    /** 点集的外接框（[points] 为 x,y 交替）；点数不足或含非有限值时返回 null。 */
    fun boundsOfPoints(points: List<Float>?): Box? {
        if (points == null || points.size < 6 || points.size % 2 != 0) return null
        val xs = points.filterIndexed { index, _ -> index % 2 == 0 }
        val ys = points.filterIndexed { index, _ -> index % 2 == 1 }
        if (xs.any { !it.isFinite() } || ys.any { !it.isFinite() }) return null
        val box = Box(xs.min(), ys.min(), xs.max(), ys.max())
        return if (box.isValid) box else null
    }

    /**
     * 盖板（遮原文的那一块）的外扩像素数 —— **随字号自适应**。
     *
     * 为什么不是固定值：`3px` 在小字上刚好，但韩文字形（尤其带收音的）会探出
     * OCR 字形框一点点，**字号越大探得越多**。用户 2026-09-30 反馈
     * 「译文下面有时有一点点原文残留，覆盖层需要扩大一点点」就是大字上的这一线。
     *
     * 为什么必须封顶：同一天早些时候用户投诉过「盖板框比文字大出许多」——
     * 那是拿排版框（字形框 ×1.3）当盖板造成的。[COVER_PAD_MAX_PX] 保证自适应
     * 不会把那个问题带回来：再大的字也只比字形多几个像素。
     *
     * 这三个常数集中放在这里（而不是散在调用点）是为了能被单测直接钉住 ——
     * 「多几个像素」与「大出 30%」之间只差这几个数，必须有测试守着上限。
     *
     * ⚠️ 这一档只给**有 OCR 四边形**的气泡（PaddleOCR，四边形紧贴字形）。没有四边形
     * （= ML Kit）的气泡走 [coverPadPxFor] → [mlKitCoverPadPx]，比这一档更大，原因见文件末尾。
     */
    fun coverPadPx(
        lineHeightPx: Float,
        ratio: Float = COVER_PAD_RATIO,
        minPx: Float = COVER_PAD_MIN_PX,
        maxPx: Float = COVER_PAD_MAX_PX
    ): Float {
        if (!lineHeightPx.isFinite() || lineHeightPx <= 0f) return minPx
        return (lineHeightPx * ratio).coerceIn(minPx, maxPx)
    }

    /** 盖板外扩的下限（= 原有固定行为，保证小字不退步）。 */
    const val COVER_PAD_MIN_PX = 3f

    /** 盖板外扩相对字高的比例。 */
    const val COVER_PAD_RATIO = 0.06f

    /** 盖板外扩的硬上限：再大的字也只比字形多这么多像素。 */
    const val COVER_PAD_MAX_PX = 8f

    // ---------------------------------------------------------------------------
    // ML Kit 专用盖板外扩（2026-10-01 用户：「译文下面有时候会有一点点原文残留，
    // 覆盖层需要扩大一点点。仅对于 ML Kit。」）
    //
    // 为什么只有 ML Kit 需要**再**大一档：PaddleOCR 返回的是**逐字紧贴字形的四边形**，
    // 盖板直接按四边形轮廓走，本来就不会切到笔画；ML Kit 的 Text Recognition 只给
    // **块级矩形**，项目只能靠 `TextInkBounds`（框内 OTSU 取「少数派=墨迹」）反推字形范围。
    // OTSU 会把笔画收笔、抗锯齿边缘这类浅像素判成背景 —— 于是墨迹包围框的下沿**落在字形里**，
    // 盖板（= 墨迹框 + 外扩）的下边正好切在原文最下面一行笔画上，译文下方就露出一线原文。
    //
    // 所以 ML Kit 这一档做两件事：
    // 1. **均匀外扩稍微放大**（[mlKitCoverPadPx]）：把四周的判定误差一起吃掉；
    // 2. **下沿额外多留**（[mlKitCoverPadBottomExtraPx]）：残留只在下方被观察到，
    //    而左右加宽恰好是用户早先投诉过的「盖板宽度超出文字许多」。把余量只加在下沿，
    //    既能盖掉残留，又不会把那个投诉带回来。
    // ---------------------------------------------------------------------------

    /**
     * **ML Kit 路径**（无 OCR 四边形）的盖板均匀外扩（像素）。
     *
     * 比通用档 [coverPadPx] 略大一档：通用档是给「有逐字四边形」的引擎用的，ML Kit 的
     * 字形框是 OTSU 反推出来的，四周都有判定误差。
     */
    fun mlKitCoverPadPx(
        lineHeightPx: Float,
        ratio: Float = ML_KIT_COVER_PAD_RATIO,
        minPx: Float = ML_KIT_COVER_PAD_MIN_PX,
        maxPx: Float = ML_KIT_COVER_PAD_MAX_PX
    ): Float {
        if (!lineHeightPx.isFinite() || lineHeightPx <= 0f) return minPx
        return (lineHeightPx * ratio).coerceIn(minPx, maxPx)
    }

    /**
     * **ML Kit 路径**在盖板**下沿**额外多留的像素（上沿与左右不加）。
     *
     * 只在下方加宽的理由见上面那段注释：残留是「盖板下边切到字形」造成的，且用户明确
     * 反对过盖板横向变宽。
     */
    fun mlKitCoverPadBottomExtraPx(
        lineHeightPx: Float,
        ratio: Float = ML_KIT_COVER_PAD_BOTTOM_RATIO,
        minPx: Float = ML_KIT_COVER_PAD_BOTTOM_MIN_PX,
        maxPx: Float = ML_KIT_COVER_PAD_BOTTOM_MAX_PX
    ): Float {
        if (!lineHeightPx.isFinite() || lineHeightPx <= 0f) return minPx
        return (lineHeightPx * ratio).coerceIn(minPx, maxPx)
    }

    /**
     * 按「OCR 有没有给文字四边形」选盖板均匀外扩：**有四边形 → 通用档**（PaddleOCR，
     * 紧贴字形，行为不变）；**没有 → ML Kit 档**（更大一档）。
     *
     * 判据就是 `bubble.polygon == null`，它在本项目里一直是「这条链路是 ML Kit」的同义词
     * （见 `WebtoonImageTranslator.resolveInkGeometry` 的注释）。
     */
    fun coverPadPxFor(lineHeightPx: Float, hasQuad: Boolean): Float =
        if (hasQuad) coverPadPx(lineHeightPx) else mlKitCoverPadPx(lineHeightPx)

    /** 按同样判据选「下沿额外外扩」：有四边形（PaddleOCR）= 0，不加。 */
    fun coverPadBottomExtraPx(lineHeightPx: Float, hasQuad: Boolean): Float =
        if (hasQuad) 0f else mlKitCoverPadBottomExtraPx(lineHeightPx)

    /** ML Kit 盖板均匀外扩的下限：比通用档的 3px 多 1px（小字也吃一点判定误差）。 */
    const val ML_KIT_COVER_PAD_MIN_PX = 4f

    /** ML Kit 盖板均匀外扩相对字高的比例：0.08（通用档是 0.06）。 */
    const val ML_KIT_COVER_PAD_RATIO = 0.08f

    /** ML Kit 盖板均匀外扩的硬上限：10px（通用档 8px），仍是「几个像素」量级。 */
    const val ML_KIT_COVER_PAD_MAX_PX = 10f

    /** ML Kit 下沿额外外扩的下限：无论字多大都多留 4px —— 就是用户说的「一点点」。 */
    const val ML_KIT_COVER_PAD_BOTTOM_MIN_PX = 4f

    /** ML Kit 下沿额外外扩相对字高的比例。 */
    const val ML_KIT_COVER_PAD_BOTTOM_RATIO = 0.08f

    /** ML Kit 下沿额外外扩的硬上限：6px（防「往下盖过头、糊掉气泡下描边」）。 */
    const val ML_KIT_COVER_PAD_BOTTOM_MAX_PX = 6f
}
