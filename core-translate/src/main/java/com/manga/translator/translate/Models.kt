package com.manga.translator.translate

/**
 * 文字四边形顶点。顺序按「文字基线方向」排列：0/2 边是宽、1/3 边是高
 * （0=左上、1=右上、2=右下、3=左下），与参考项目 C:\homework\translator 的
 * `polygon`（4 点任意四边形）一致。坐标系由持有者决定：
 * [TextBlock] 里是图片像素，[Bubble] 里是 0-100 百分比。
 */
data class QuadPoint(val x: Float, val y: Float)

data class Bubble(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val bgType: String,
    val originalText: String,
    val translatedText: String,
    val layout: String = "horizontal",
    /**
     * [layout] 的判定原因（诊断用，不参与渲染）。
     *
     * 例如 `columns`（块并排成列）、`narrow_block`（单块高瘦且区域竖长）、
     * `block_aspect`（块整体高瘦）；空串 = 横排。
     *
     * 为什么把它做成**数据**而不是直接打日志：判定发生在 `BubbleFirstRegionPlanner`，
     * 那是纯 Kotlin、可 JVM 单测的类，用 `android.util.Log` 会让单测抛
     * `Method d in android.util.Log not mocked`（2026-09-30 实测 7 个单测一起挂）。
     * 带出来之后由 `WebtoonImageTranslator` 记录，排障时能看到「为什么判成竖排」。
     */
    val layoutReason: String = "",
    val contentX: Float = x,
    val contentY: Float = y,
    val contentW: Float = w,
    val contentH: Float = h,
    /**
     * OCR 检测到的文字四边形（相对原图百分比，0=左上 / 1=右上 / 2=右下 / 3=左下）。
     *
     * 2026-09-29 渲染层对齐：斜排原文（拟声词、倾斜对白）的遮罩必须跟着倾角走，
     * 轴对齐外框会把遮罩撑成包围盒。拿不到四边形时为 null，渲染层退回
     * 「轴对齐框 + 可选 rotation_deg」，属于规定的降级路径。
     */
    val polygon: List<QuadPoint>? = null,
    /**
     * 文字倾角（度，折到 ±90°）。只有四边形缺失时才需要它；
     * 有四边形时渲染层直接从四边形反推，避免两套坐标角度不一致造成误判。
     */
    val rotationDeg: Float? = null,
    /**
     * 原文排版对齐（[TextAlignment.LEFT] / [TextAlignment.CENTER] / [TextAlignment.RIGHT]）。
     * 2026-09-28 用户要求「左对齐、右对齐和居中需要区分」：由 OCR 逐行文字框推断
     * （见 [TextAlignment.infer]），渲染译文时沿用，别一律居中。
     */
    val alignment: String = TextAlignment.CENTER,
    /**
     * 原文行数（逐行 OCR 框的个数）。渲染时用「内容框高 ÷ 行数」估计原文字高，
     * 让译文字号与原文一致（用户 2026-09-28 要求）；1 = 单行/未知。
     */
    val sourceLineCount: Int = 1,
    /**
     * 逐行文字框（**百分比**，每行 `[left, top, right, bottom]`，坐标系同 [x]/[y]）。
     *
     * 来源：[TextBlock.lineBoxes]（ML Kit 逐行 boundingBox）在构泡时换算。
     * 拿不到时为 null —— 字号/遮罩退回「块框 ÷ 行数 + 墨迹收紧」老路径
     * （跨缝合并泡等合成 Bubble 即如此，行为不变）。
     */
    val lineBoxes: List<FloatArray>? = null
)

data class TranslationResult(val bubbles: List<Bubble>)

enum class BubbleRegionType {
    BUBBLE,
    OUTSIDE
}

data class BubbleRegion(
    val id: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val regionType: BubbleRegionType,
    val confidence: Float = 0f,
    val source: String = ""
)

data class RegionOcrResult(
    val regionId: Int,
    val regionType: BubbleRegionType,
    val blocks: List<TextBlock>,
    val layout: String
)

data class TextBlock(
    val id: Int,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float = 0f,
    /**
     * 检测到的文字四边形（**图片像素**，0=左上 / 1=右上 / 2=右下 / 3=左下）。
     * 由检测器在透视裁剪时顺手带出；检测器没提供（远程 OCR / 退化框）时为 null。
     */
    val polygon: List<QuadPoint>? = null,
    /**
     * 这个块里有几行文字（**行高的唯一来源**，2026-10-03）。
     *
     * 为什么需要显式字段：ML Kit 返回的是**段落级多行块**（`text` 内含 `\n`，靠换行数能算出
     * 行数），而 Paddle 的合并块文本里**没有** `\n`（`PaddleOcrBlockMerger` 刻意用空格连接，
     * 避免换行符漏进译文）—— 不给行数，「框高 ÷ 行数」就会退回「整块高度当行高」，
     * 合并阈值被放大好几倍（两个独立气泡被并成一句的真机事故）。
     *
     * 取值：[TextLineGeometry.lineCount] 会与「文本换行数 + 1」取最大，所以这里给 1
     * 也不会把带换行的块算成单行。
     */
    val lineCount: Int = 1,
    /**
     * 逐行文字框（**图片像素**，每行 `[left, top, right, bottom]`）。
     *
     * ML Kit 的段落级块自带每行 boundingBox（除回检测缩放后带出）；Paddle / 远程 OCR
     * 拿不到时为 null。用途（2026-10-14「字号以原文为基准」）：行高中位数 = 原文行高、
     * 行框并集 = 真实字形区 —— 块级框偏松且会粘到气泡描边，「块框高 ÷ 行数」把字号
     * 系统性放大 1/3、盖板比原文大一圈（用户截图：译文戳出气泡下沿）。
     */
    val lineBoxes: List<FloatArray>? = null
)

data class TextBlockGroup(
    val bubbleId: Int,
    val blockIds: List<Int>
)

