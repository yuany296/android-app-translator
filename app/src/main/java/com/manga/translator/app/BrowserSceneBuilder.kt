package com.manga.translator.app

/**
 * 内嵌浏览器「译文覆盖层」场景序列化（2026-09-29）。
 *
 * 把翻译管线产出的气泡（颜色 / 字高 / 百分比几何）序列化成 WebView 端
 * `MtOverlay.applySceneJson` 消费的场景 JSON。渲染本身在 WebView 的 DOM 覆盖层
 * 完成（assets/mt-overlay-renderer.js，移植自 C:\homework\translator 扩展），
 * 本类只做纯数据转换 + JSON 拼装，不依赖 android.graphics，便于 JVM 单测。
 *
 * 坐标约定：一律用相对**原图**的百分比 0-100，禁止混用像素；像素只在渲染那一刻
 * 由覆盖层按元素可见矩形换算（对齐扩展的数据契约）。
 */
object BrowserSceneBuilder {

    /** 单个气泡的场景字段（全部相对原图百分比）。 */
    data class SceneBubble(
        val id: String,
        /** 最终渲染框（擦字/文字共用的框，不是 OCR 原始框）。 */
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val originalText: String,
        val translatedText: String,
        /** 原文行数（断行 DP 的目标行数）。 */
        val sourceLineCount: Int,
        /** OCR 判定的排版方向（竖排漫画为 true）。 */
        val vertical: Boolean,
        val alignment: String,
        /** "solid"（实底气泡）| "none"（旁白/复杂背景）。 */
        val bgType: String,
        val bgColorHex: String,
        val textColorHex: String,
        val strokeColorHex: String,
        /**
         * 要不要给译文加描边（2026-09-30 用户要求「白边黑边只在背景有多种颜色的时候有」）。
         *
         * 由宿主按**背景（清理图）的亮度离散度**算好下发；缺省 true = 保留描边，
         * 与改动前行为一致（老版本宿主的场景 JSON 里没有这个字段，也走 true）。
         */
        val needsStroke: Boolean = true,
        /** 原文字高 / 图高 ×100 —— 字号拟合的核心输入。 */
        val fontHeightPercent: Float,
        /**
         * 文字四边形（8 个数 = 4 点 x,y 交替，相对原图百分比，0=左上/1=右上/2=右下/3=左下）。
         * 有值时渲染层直接拿它裁剪遮罩，斜排原文不会残留水平补丁。
         */
        val polygon: List<Float>? = null,
        /** 文字倾角（度）。只有四边形缺失时渲染层才需要它。 */
        val rotationDeg: Float? = null,
        /** 实底容器范围（百分比 x,y,w,h）；solid 时填充范围 = x/y/w/h ∪ fill_box。 */
        val fillBox: FloatArray? = null,
        /** 实底容器轮廓（百分比顶点，x,y 交替）；solid 时按它 clip-path 裁剪填充。 */
        val regionPolygon: List<Float>? = null,
        /**
         * **盖板外框**（百分比 x,y,w,h）= 字形范围 + 几像素，**不含**排版外扩。
         *
         * 2026-09-30 用户反馈「盖板框的宽度/高度超出文字许多，不进行扩展，要扩也只是几个像素」：
         * 渲染层此前把 x/y/w/h（= 排版框，字形框每侧再扩 15%）直接当盖板框用。
         * 有值时渲染层**必须**用它定位盖板；x/y/w/h 只继续用于译文排版。
         */
        val coverBox: FloatArray? = null,
        /** 清理图补丁要映射的源区域（百分比 x,y,w,h）；缺省用 x/y/w/h。 */
        val cleanedSourceBox: FloatArray? = null,
        /** 字重（100-900）；缺省渲染层用 600。 */
        val fontWeight: Int? = null,
        /**
         * 跨页/分片遮罩段（§11）。同一气泡跨多张图（或被扫描线切成多个分片）时，
         * 每段给出在当前图坐标系下的子框 + 可选四边形；渲染层按页 union 后 clip。
         * 当前实现：单图场景通常只有 1 段；多图 union 需要上层先把相邻图的气泡拼起来。
         */
        val coverSegments: List<CoverSegment>? = null,
        /** 跨图续排气泡（y 可为负/超界，渲染层不做 0-100 收拢）。 */
        val stitchOverflow: Boolean = false,
        /** 跨图续排的固定字号（图高百分比），接缝两侧字号一致。 */
        val fixedFontPercent: Float? = null,
        /** 跨图续排的擦字贴片 URL（mtlocal 拦截通道）。 */
        val patchUrl: String? = null,
        /** 贴片目标框（百分比 x/y/w/h），为 null 时贴片语义不生效。 */
        val patchBox: FloatArray? = null,
        /**
         * **不透明实底盖板**（2026-10-11 用户要求「不要残影，用实体的背景遮挡」）。
         *
         * 只对 `bg_type = "none"`（旁白/压在画面上的文字）有意义：宿主拿不到清理图纹理时
         * （速度优先模式关闭「精细擦字」，或清理图构建失败），盖板不再留空/贴残影贴片，
         * 而是直接用 `bg_color`（气泡底色采样）铺一块**100% 不透明**的底把原文盖掉。
         * 缺省 false = 老行为（透明 + 清理图/贴片）。
         */
        val flatCover: Boolean = false
    )

    /** 跨页/分片遮罩段：page_id 用于 union/去重，为空时按 index 兜底。 */
    data class CoverSegment(
        val pageId: String,
        /** 该段在当前图坐标系下的百分比外接框。 */
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        /** 该段在当前图坐标系下的百分比四边形（可选，用于倾斜文字）。 */
        val polygon: List<Float>? = null,
        /** 该段对应的清理图源区域（百分比 x,y,w,h）。 */
        val cleanedSourceBox: FloatArray? = null,
        /** 该段是否落在当前扫描带内（带内用复合清理图，带外用整页清理图）。 */
        val inBand: Boolean = true
    )

    /** 场景的可复用部分（imageId 无关：「原文→翻译」贴回时换 id 直接重发）。 */
    data class Scene(
        /** 捕获带内的复合清理图（第 1 级背景）。 */
        val cleanedUrl: String?,
        val bubbles: List<SceneBubble>,
        /**
         * 整页清理图（§11 三级背景降级第 2/3 级）：分段跨出捕获带时，带内用 [cleanedUrl]、
         * 带外用这张整页图。为 null 时渲染层退回单层复合图（绝不出现「有遮罩但背景是空的」）。
         */
        val pageCleanedUrl: String? = null
    ) {
        fun isEmpty(): Boolean = bubbles.isEmpty()
    }

    /** 拼装场景 JSON（bubbles 用预序列化片段，贴回时只换 imageId）。 */
    fun buildSceneJson(imageId: String, scene: Scene): String {
        return buildString {
            append("{\"v\":1")
            append(",\"image_id\":").append(quote(imageId))
            append(",\"cleaned_url\":").append(scene.cleanedUrl?.let { quote(it) } ?: "null")
            append(",\"page_cleaned_url\":").append(scene.pageCleanedUrl?.let { quote(it) } ?: "null")
            append(",\"bubbles\":[").append(scene.bubbles.joinToString(",") { bubbleJson(it) }).append("]}")
        }
    }

    private fun bubbleJson(bubble: SceneBubble): String = buildString {
        append("{\"id\":").append(quote(bubble.id))
        append(",\"x\":").append(num(bubble.x))
        append(",\"y\":").append(num(bubble.y))
        append(",\"w\":").append(num(bubble.w))
        append(",\"h\":").append(num(bubble.h))
        append(",\"original_text\":").append(quote(bubble.originalText))
        append(",\"translated_text\":").append(quote(bubble.translatedText))
        append(",\"source_line_count\":").append(bubble.sourceLineCount.coerceAtLeast(1))
        append(",\"vertical\":").append(bubble.vertical)
        append(",\"alignment\":").append(quote(bubble.alignment))
        append(",\"bg_type\":").append(quote(bubble.bgType))
        append(",\"bg_color\":").append(quote(bubble.bgColorHex))
        append(",\"text_color\":").append(quote(bubble.textColorHex))
        append(",\"stroke_color\":").append(quote(bubble.strokeColorHex))
        append(",\"needs_stroke\":").append(bubble.needsStroke)
        append(",\"font_height_percent\":").append(num(bubble.fontHeightPercent))
        append(",\"polygon\":").append(pointArray(bubble.polygon?.takeIf { it.size == 8 }))
        append(",\"rotation_deg\":").append(bubble.rotationDeg?.let { num(it) } ?: "null")
        append(",\"fill_box\":").append(boxObject(bubble.fillBox))
        append(",\"region_polygon\":").append(pointArray(bubble.regionPolygon))
        append(",\"cover_box\":").append(boxObject(bubble.coverBox))
        append(",\"cleaned_source_box\":").append(boxObject(bubble.cleanedSourceBox))
        append(",\"font_weight\":").append(bubble.fontWeight?.toString() ?: "null")
        append(",\"cover_segments\":").append(coverSegmentsArray(bubble.coverSegments))
        append(",\"stitch_overflow\":").append(bubble.stitchOverflow)
        append(",\"fixed_font_percent\":").append(bubble.fixedFontPercent?.let { num(it) } ?: "null")
        append(",\"patch_url\":").append(bubble.patchUrl?.let { quote(it) } ?: "null")
        append(",\"patch_box\":").append(boxObject(bubble.patchBox))
        // 只在 true 时下发（默认透明盖板，老渲染层没有这个字段 → 行为与改动前一致）。
        if (bubble.flatCover) append(",\"flat_cover\":true")
        append("}")
    }

    private fun coverSegmentsArray(segments: List<CoverSegment>?): String {
        if (segments.isNullOrEmpty()) return "null"
        return segments.joinToString(prefix = "[", postfix = "]", separator = ",") { seg ->
            buildString {
                append("{\"page_id\":").append(quote(seg.pageId))
                append(",\"x\":").append(num(seg.x))
                append(",\"y\":").append(num(seg.y))
                append(",\"w\":").append(num(seg.w))
                append(",\"h\":").append(num(seg.h))
                append(",\"polygon\":").append(pointArray(seg.polygon?.takeIf { it.size == 8 }))
                append(",\"cleaned_source_box\":").append(boxObject(seg.cleanedSourceBox))
                append(",\"in_band\":").append(seg.inBand)
                append("}")
            }
        }
    }

    /** 扁平点数组（x,y 交替）→ `[{"x":..,"y":..}, ...]`；点数为奇数或缺省时输出 null。 */
    private fun pointArray(flat: List<Float>?): String {
        if (flat == null || flat.size < 6 || flat.size % 2 != 0) return "null"
        val sb = StringBuilder(flat.size * 12 + 4)
        sb.append('[')
        var index = 0
        while (index < flat.size) {
            if (index > 0) sb.append(',')
            sb.append("{\"x\":").append(num(flat[index]))
                .append(",\"y\":").append(num(flat[index + 1])).append('}')
            index += 2
        }
        sb.append(']')
        return sb.toString()
    }

    /** `[x,y,w,h]` → `{"x":..,"y":..,"w":..,"h":..}`；尺寸非法时输出 null。 */
    private fun boxObject(box: FloatArray?): String {
        if (box == null || box.size != 4) return "null"
        return "{\"x\":" + num(box[0]) + ",\"y\":" + num(box[1]) +
            ",\"w\":" + num(box[2]) + ",\"h\":" + num(box[3]) + "}"
    }

    /**
     * 接缝覆盖层场景 JSON（渲染层 `MtOverlay.applySeamSceneJson`）。
     *
     * 与 [buildSceneJson] 的区别：这个场景的坐标系不是「某一张图」，而是**接缝带**——
     * 从本图清理图里裁出来的、正好覆盖整个跨缝气泡的那一条。渲染层把 root 摆在这条带的
     * 屏幕矩形上，于是跨缝气泡只有一块盖板 + 一份译文（用户 2026-09-30 的要求），
     * 接缝处也不可能出现两种背景纹理。
     *
     * [imageId] / [nextImageId] 是两个页面元素 id（`data-mt-id`），渲染层用它们算带的位置；
     * **两张图显示宽度必须相同且首尾相接**，否则渲染层直接拒绝（返回 `ok:false`），
     * 调用方保留原来的两半渲染。
     */
    fun buildSeamSceneJson(
        imageId: String,
        nextImageId: String,
        seamKey: String,
        cleanedUrl: String?,
        bandLeftPx: Int,
        bandTopPx: Int,
        bandWidthPx: Int,
        bandHeightPx: Int,
        topStripHeightPx: Int,
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        bubbles: List<SceneBubble>
    ): String = buildString {
        append("{\"v\":1")
        append(",\"kind\":\"seam\"")
        append(",\"seam_key\":").append(quote(seamKey))
        append(",\"image_id\":").append(quote(imageId))
        append(",\"next_image_id\":").append(quote(nextImageId))
        append(",\"cleaned_url\":").append(cleanedUrl?.let { quote(it) } ?: "null")
        append(",\"band_left_px\":").append(bandLeftPx)
        append(",\"band_top_px\":").append(bandTopPx)
        append(",\"band_width_px\":").append(bandWidthPx)
        append(",\"band_height_px\":").append(bandHeightPx)
        append(",\"top_h_px\":").append(topStripHeightPx)
        append(",\"source_width_px\":").append(sourceWidthPx)
        append(",\"source_height_px\":").append(sourceHeightPx)
        append(",\"bubbles\":[").append(bubbles.joinToString(",") { bubbleJson(it) }).append("]}")
    }

    /**
     * 实底填充范围限制：`fillBox` 相对 `textBox` 的外扩，每边最多 [MAX_FILL_EXPAND_RATIO] × 对应边长。
     *
     * 为什么需要（2026-09-29 用户反馈「覆盖层太大了，有时候都超出气泡框了，且只存在于 ML Kit 下」）：
     * `fill_box` 来自气泡**外框**（区域检测的结果）。ML Kit 的文字框本身偏松，再叠上偏松的气泡外框，
     * 填充矩形会明显大于气泡本体（实测 `--mt-fill-width` 到 185%，即填充宽是文字框的 1.85 倍、
     * 占整幅图 80% 宽），糊掉大片画面。
     *
     * 这里只做**限幅**：真实气泡比文字框大是正常的，但大到 1.4 倍以上基本就是误检了。
     */
    fun clampFillBox(textBox: FloatArray, fillBox: FloatArray?): FloatArray {
        if (textBox.size != 4) return floatArrayOf(0f, 0f, 100f, 100f)
        if (fillBox == null || fillBox.size != 4) return textBox
        val tx = textBox[0]
        val ty = textBox[1]
        val tw = textBox[2].coerceAtLeast(0f)
        val th = textBox[3].coerceAtLeast(0f)
        val maxLeft = tx - tw * MAX_FILL_EXPAND_RATIO
        val maxTop = ty - th * MAX_FILL_EXPAND_RATIO
        val maxRight = tx + tw + tw * MAX_FILL_EXPAND_RATIO
        val maxBottom = ty + th + th * MAX_FILL_EXPAND_RATIO
        val left = maxOf(fillBox[0], maxLeft)
        val top = maxOf(fillBox[1], maxTop)
        val right = minOf(fillBox[0] + fillBox[2], maxRight)
        val bottom = minOf(fillBox[1] + fillBox[3], maxBottom)
        if (right <= left || bottom <= top) return textBox
        return floatArrayOf(left, top, right - left, bottom - top)
    }

    /** `fill_box` 相对文字框的最大外扩比例（每边）。0.2 → 填充最多是文字框的 1.4 倍。 */
    const val MAX_FILL_EXPAND_RATIO = 0.2f

    /**
     * 百分比框外扩固定像素，返回 `[x, y, w, h]`。
     *
     * [bottomPadPx] 缺省等于 [padPx]（四边一样）。**ML Kit 路径**会传一个比 [padPx] 大的值：
     * 原文残留只出现在盖板**下沿**（原因见 `SeamBandGeometry` 末尾那段），而横向加宽是用户
     * 明确反对过的（「盖板框的宽度超出文字许多」），所以额外那几像素只加在下沿。
     *
     * 放在这里（而不是留在 `WebtoonImageTranslator` 私有）是为了能直接单测：这条「只加下沿、
     * 不动左右」的口径是用户可见行为的分界线，而它只差一个参数。
     */
    fun expandPercentBoxByPx(
        box: FloatArray,
        padPx: Float,
        imageWidthPx: Int,
        imageHeightPx: Int,
        bottomPadPx: Float = padPx
    ): FloatArray {
        if (box.size != 4 || padPx <= 0f || imageWidthPx <= 0 || imageHeightPx <= 0) return box
        val padX = padPx / imageWidthPx * 100f
        val padY = padPx / imageHeightPx * 100f
        val padBottomY = bottomPadPx / imageHeightPx * 100f
        return floatArrayOf(
            box[0] - padX,
            box[1] - padY,
            box[2] + padX * 2f,
            box[3] + padY + padBottomY
        )
    }

    /** 点列表 → 扁平 `[x0,y0,x1,y1,...]`；空列表返回 null。 */
    fun flattenQuad(points: List<com.manga.translator.translate.QuadPoint>?): List<Float>? {
        if (points.isNullOrEmpty()) return null
        val flat = ArrayList<Float>(points.size * 2)
        points.forEach { point ->
            flat += point.x
            flat += point.y
        }
        return flat.ifEmpty { null }
    }

    /**
     * 拼接图坐标 → 原图坐标：y 先减去顶部条带像素，再折算成原图百分比。
     * 拼接图与原图同宽，所以 x/w 只做归一化基准切换。
     */
    fun stitchedYPercentToSource(
        stitchedYPercent: Float,
        stitchedHeightPx: Int,
        topStripHeightPx: Int,
        sourceHeightPx: Int
    ): Float {
        if (stitchedHeightPx <= 0 || sourceHeightPx <= 0) return 0f
        val sourceY = stitchedYPercent / 100f * stitchedHeightPx - topStripHeightPx
        return (sourceY / sourceHeightPx * 100f).coerceIn(-100f, 100f)
    }

    /** 拼接图坐标 → 原图坐标（高度尺寸，不需要位移）。 */
    fun stitchedHPercentToSource(
        stitchedHPercent: Float,
        stitchedHeightPx: Int,
        sourceHeightPx: Int
    ): Float {
        if (stitchedHeightPx <= 0 || sourceHeightPx <= 0) return 0f
        return (stitchedHPercent / 100f * stitchedHeightPx / sourceHeightPx * 100f).coerceIn(0f, 100f)
    }

    /** 拼接图像素框 → 原图百分比框（x/w 直接按宽度归一化）。 */
    fun stitchedRectPxToSource(
        leftPx: Float,
        topPx: Float,
        widthPx: Float,
        heightPx: Float,
        stitchedWidthPx: Int,
        stitchedHeightPx: Int,
        topStripHeightPx: Int,
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        allowOverflowY: Boolean = false
    ): FloatArray {
        if (stitchedWidthPx <= 0 || stitchedHeightPx <= 0 || sourceWidthPx <= 0 || sourceHeightPx <= 0) {
            return floatArrayOf(0f, 0f, 0f, 0f)
        }
        val x = (leftPx / stitchedWidthPx * 100f).coerceIn(0f, 100f)
        val yRaw = (topPx - topStripHeightPx) / sourceHeightPx * 100f
        val y = if (allowOverflowY) yRaw.coerceIn(-100f, 100f) else yRaw.coerceIn(0f, 100f)
        val w = (widthPx / stitchedWidthPx * 100f).coerceIn(0f, 100f)
        val h = (heightPx / sourceHeightPx * 100f).coerceIn(0f, 100f)
        return floatArrayOf(x, y, w, h)
    }

    /** ARGB Int → "#rrggbb"（覆盖层语义里颜色都是不透明的）。 */
    fun colorToHex(color: Int): String {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return "#%02x%02x%02x".format(r, g, b)
    }

    private fun num(value: Float): String {
        val rounded = (value * 100).let { (it.toLong()) / 100.0 }
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    private fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (ch < ' ') {
                        sb.append("\\u").append(String.format("%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
