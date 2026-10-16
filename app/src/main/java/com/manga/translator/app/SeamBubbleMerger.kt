package com.manga.translator.app

import com.manga.translator.translate.Bubble
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 分片接缝（裁切线）文字归属与「半截字」配对（2026-09-28，对齐 C:\homework\translator）。
 *
 * 背景：长条漫画被站点按固定尺寸切成多张图，跨切线的文字会被劈成两半。
 * 现有链路只做了「复合图 OCR + 跨边界气泡把剩余部分交给下一张」，但 OCR 经常
 * **把两半识别成两个框**（字高不同、切在笔画中间留了缝），此时上块被判「归上一张」丢弃、
 * 下块只剩半句 → 用户看到的就是「半截字 / 缺字 / 断句」。
 *
 * 参考项目（`extension/src/background/modules/seam-handling.js`）的 seam 处理：
 * 1. 接缝复合图（本工程 = 当前图上下各拼一条邻居条带）后 OCR；
 * 2. 只保留确实跨边界的框；「只贴上/下边界」的框作为配对候选；
 * 3. **配对判据**（`buildSeamCrossPairCandidate`）：水平重叠 ≥0.25、行高比 ≥0.5、
 *    垂直间隙 ≤ 自适应上限、合并高度 ≤ 上限、两块旋转差 ≤20°；
 * 4. **句子连续性**（`seamPairTextCanContinue`）：上块以句末标点收尾且两块间有明显间隙 →
 *    判定为两句话，不合并。
 *
 * 本文件是该判据的纯几何实现（不依赖 android.graphics），便于 JVM 单测。
 */
internal data class SeamBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)

    fun union(other: SeamBox): SeamBox = SeamBox(
        left = min(left, other.left),
        top = min(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom)
    )

    /** 纵向裁到 [topLimit, bottomLimit]（横向不动）。 */
    fun clipVertical(topLimit: Float, bottomLimit: Float): SeamBox = SeamBox(
        left = left,
        top = max(top, topLimit),
        right = right,
        bottom = min(bottom, bottomLimit)
    )

    fun intersectionArea(other: SeamBox): Float {
        val width = min(right, other.right) - max(left, other.left)
        val height = min(bottom, other.bottom) - max(top, other.top)
        return if (width <= 0f || height <= 0f) 0f else width * height
    }

    /** 相对「较窄的那个」的水平重叠比例（对齐参考项目 horizontalOverlap 算法）。 */
    fun horizontalOverlapRatio(other: SeamBox): Float {
        val overlap = min(right, other.right) - max(left, other.left)
        if (overlap <= 0f) return 0f
        val narrower = min(width, other.width)
        return if (narrower <= 0f) 0f else overlap / narrower
    }
}

/** 一个检测框：气泡本体 + 它在**拼接图坐标系**里的外框与内容框。 */
internal data class SeamDetection(
    val bubble: Bubble,
    val outer: SeamBox,
    val content: SeamBox
)

/**
 * 归属结果。
 *
 * @param cross 跨下边界：需要把剩余部分交给下一张图继续画。
 * @param renderHere 是否**在本图**渲染这块。
 *
 * 为什么需要它（2026-10-03）：条带（上/下各 `clamp(width*0.20,144,384)` px）里常常不只
 * 装着「被切开的半行」，还装着下一张图正文的开头。若把它当普通 owned 气泡在本图渲染，
 * 它的百分比框会落到本图**画面之外**（y≥100%），渲染层只能夹到页脚 → 页脚出现本该属于
 * 下一张图的译文；所以这类块只**认领并移交**（`renderHere = false`），由下一张图渲染。
 */
internal data class SeamOwnership(
    val bubble: Bubble,
    val cross: Boolean,
    val renderHere: Boolean = true
)

/** 归属汇总（含诊断计数，供 `slice_classify` 日志）。 */
internal data class SeamPlan(
    val owned: List<SeamOwnership>,
    val detected: Int = 0,
    val pairedTopSeam: Int = 0,
    val pairedBottomSeam: Int = 0,
    val droppedNeighbor: Int = 0,
    val suppressedByIncoming: Int = 0,
    /**
     * 「谁都不管」而被本图收编的块数（本图上边界方向的orphan）。
     *
     * 为什么必须存在这个计数：规则 2/4 原先一律丢弃「跨上边界 / 落在邻居条带里」的检测，
     * 前提是「上一张图会移交」。**但上一张图并不总能移交**（`botH=0`、贴片不足 2px、
     * 配对失败都会导致 `crossOut` 为空）。这时这块文字既不属于本图、也不会被上一张图画，
     * 结果永远保持原文 —— 2026-10-03 用户截图里「跨页气泡下半段没译文」正是这一类。
     * 现在只要 `incomingCross` 没有覆盖它，本图就**认领**（裁到可见区后正常翻译渲染）。
     */
    val orphanAdopted: Int = 0,
    /** 完全落在邻居条带内、且已由本图移交下一页的块数（`renderHere = false`）。 */
    val handedTail: Int = 0,
    /** 跨下边界但深入条带超过「切开余量」而被裁短的块数（诊断用）。 */
    val cappedCross: Int = 0,
    /**
     * 与 [detections] 下标一一对应的判决文本（诊断用，`seam_verdict` 日志直接打它）。
     *
     * 取值：`cross` / `cross_capped` / `paired_bottom` / `paired_top` / `orphan_adopted` /
     * `handed_tail` / `dropped_top_handed` / `dropped_tail` / `dropped_fragment` /
     * `dropped_glyph_outside`（字形整体在可见区之外，本图没有可画的内容）/ `suppressed` /
     * `unknown`。
     */
    val verdicts: List<String> = emptyList()
)

/**
 * 接缝几何参数（全部为拼接图像素坐标）。
 *
 * @param topLimitPx 上条带（上一张图底部）与本图的分界 y
 * @param bottomLimitPx 本图与下条带（下一张图顶部）的分界 y
 * @param topBandPx 上条带高度（0 = 没有上一张）
 * @param bottomBandPx 下条带高度（0 = 没有下一张）
 * @param edgeWindowPx 「贴边」判定窗口：框边距分界小于它才算半块候选
 * @param maxCrossHeightPx 允许合并的最大高度（挡「整幅场景框」被误配）
 * @param cutAllowancePx 「切开余量」：跨下边界的气泡允许伸进下条带的深度。
 *
 *   下条带的作用是**补全被切开的半行**，但条带实际覆盖的画幅往往更深（实测
 *   `clamp(width*0.20,144,384)`：720px 宽的切片 = 144px ≈ 两行文字），于是下一张图正文的
 *   开头也会出现在条带里。若不设上限，跨缝气泡的框/盖板/接缝带纹理就会一路盖到
 *   邻居正文上（2026-10-03 用户截图：跨缝气泡下方那个气泡的第一行被擦掉、没译文）。
 *   0 = 不限制（保持历史行为，只有单测会用到）。
 */
internal data class SeamConfig(
    val topLimitPx: Float,
    val bottomLimitPx: Float,
    val topBandPx: Float,
    val bottomBandPx: Float,
    val edgeWindowPx: Float,
    val maxCrossHeightPx: Float,
    val cutAllowancePx: Float = 0f,
    /**
     * 「碎片」高度阈值（2026-10-03 真机加）。
     *
     * 条带边缘会把一行切成很薄的一条，OCR 于是吐出「高度只有二三十像素、文本是 `용살가`
     * 这种残片」的检测框。以前这类框会被当成气泡**认领并渲染**（真机实测 `d1=orphan_adopted`
     * 可见高度 25px、`d2=cross` 41px → 画出一个 43px 高的薄盖板 + 残片译文）。
     * 比它更薄的框一律按碎片丢弃（不渲染、不移交），留给真正能看到整行的那一侧处理。
     * 0 = 不启用（只有单测会用到）。
     */
    val fragmentHeightPx: Float = 0f,
    /**
     * 跨上边界的块「大半在本页」的下限。
     *
     * ⚠️ **2026-10-03 起不再参与丢弃判定**（保留字段只为不打断既有构造点与单测意图记录）。
     *
     * 原语义：跨上边界的块可见比例 < 0.5 时整块归上一张，理由是「上一张已经画过自己那部分，
     * 本图再画一次就会韩文+中文叠成一块」。
     *
     * 为什么撤掉：那个前提只在「上一张**确实**整块移交了它」时成立，而那正是规则 2 的
     * `handedOver` 分支（按整块覆盖率判定）。可见比例低 ≠ 上一张画过 —— 真机 `[32317270]`
     * 的跨边界块可见 44px / 整块 95px = 46% < 0.5，而它**没被整块移交**，于是被这条判据丢掉，
     * 结果本图这半行既不擦也不翻（用户截图里那行韩文）。现在这种块一律
     * **本图认领并裁进可见区**（`orphan_adopted`），只有「整块被移交」或「裁到只剩残片」才丢。
     */
    /** 见上方 KDoc：2026-10-03 起不再参与丢弃判定，保留字段只为记录既有意图与构造点。 */
    val minVisibleFraction: Float = 0.5f
) {
    val hasTopBand: Boolean get() = topBandPx > 0.5f
    val hasBottomBand: Boolean get() = bottomBandPx > 0.5f

    /** 跨下边界的框允许到达的最深 y（0 余量 = 不限制）。 */
    val crossCapPx: Float
        get() = if (hasBottomBand && cutAllowancePx > 0f) bottomLimitPx + cutAllowancePx else Float.MAX_VALUE
}

/** 阈值集中在这里，便于按真机数据调整。 */
internal object SeamTuning {
    const val MIN_EDGE_WINDOW_PX = 24f
    const val MAX_CROSS_BAND_COVERAGE = 1.5f
    const val MIN_HORIZONTAL_OVERLAP = 0.25f
    const val MIN_HEIGHT_RATIO = 0.5f
    const val BASE_PAIR_MAX_GAP_PX = 48f

    /** 与 incomingCross（上一张移交过来的下半句）重叠超过该比例即认为「同一句已经画过」。 */
    const val SUPPRESS_INCOMING_OVERLAP = 0.6f

    private val SENTENCE_END = Regex("[.!?。！？…]+[\"'”’」』）》】]*$")

    fun endsSentence(text: String): Boolean = SENTENCE_END.containsMatchIn(text.trim())

    /**
     * 配对允许的最大垂直间隙 —— **相对行高**，与 `maxCrossHeight` 彻底解耦。
     *
     * 2026-10-03 用户现场（「两段被合并了」）：旧写法用 `maxCrossHeight × 0.45` 当上限，
     * 而 `maxCrossHeight` 本身又跟着条带/行高放大（实测 1128px）→ 上限变成 507px，
     * 于是「缝下方条带里的半行残片」和**几十像素外另一个气泡**被配成一句，译文直接串味。
     * 判据本来应该问「这两块是不是同一条被切开的行」：同一条切开的行，间隙不会超过半行。
     */
    fun resolveMaxPairGap(upperHeight: Float, lowerHeight: Float): Float {
        val smaller = min(upperHeight, lowerHeight).coerceAtLeast(1f)
        return max(BASE_PAIR_MAX_GAP_PX, smaller * PAIR_MAX_GAP_LINE_RATIO)
    }

    /**
     * 「贴边」判定窗口：框边距分界多近才算「贴着缝」。
     *
     * 对齐参考项目的做法（`C:\homework\translator` 的 `areAnonymousSeamLinesCompatible`：
     * `-0.25×avgH ≤ gap ≤ 0.7×avgH`）——**以行高为单位**，而不是拿一个可能上百像素的
     * 「最大合并高度」去乘系数。否则窗口能有半屏大，缝下面随便哪个气泡都算「贴着缝」。
     */
    fun resolveEdgeWindow(medianLineHeight: Float): Float {
        if (medianLineHeight <= 0f || !medianLineHeight.isFinite()) return MIN_EDGE_WINDOW_PX
        return max(MIN_EDGE_WINDOW_PX, medianLineHeight * EDGE_WINDOW_LINE_RATIO)
    }

    /**
     * **行高**中位数（不是「框高中位数」）。
     *
     * 为什么必须除以行数：检测框经常是整个多行气泡（实测缝上一张只有 1 个检测框时，
     * 「行高」= 461px = 整个 4 行块），于是所有以它为单位的阈值（贴边窗口、配对间隙、
     * 切开余量、合并上限）一起放大好几倍 —— 2026-10-03 用户报「两段被合并」就是这条链。
     *
     * 行数取三者最大（对齐参考项目 `seam-grouping.js` 的 `candidateLineCount`）：
     * OCR 给的 `sourceLineCount`、**文本换行数 + 1**、1。
     * 只信 `sourceLineCount` 会踩坑：真机实测那条检测的文本里明明有换行
     * （`케이지라고요!!⏎접속종료를 하려면 죽거나`）而 `sourceLineCount` 还是 1，
     * 于是「行高」又变成整块高度、阈值重新放大。
     */
    fun medianLineHeight(detections: List<SeamDetection>): Float {
        if (detections.isEmpty()) return 0f
        val perLine = detections.map { detection ->
            val lines = lineCountOf(detection.bubble)
            detection.outer.height / lines
        }.filter { it.isFinite() && it > 0f }.sorted()
        if (perLine.isEmpty()) return 0f
        return perLine[perLine.size / 2]
    }

    /** 检测块的行数：显式行数与文本换行数取最大（最少 1）。 */
    fun lineCountOf(bubble: Bubble): Int {
        val explicit = bubble.sourceLineCount.coerceAtLeast(1)
        val textLines = bubble.originalText.split('\n', '\r').count { it.isNotBlank() }
        return max(explicit, max(textLines, 1))
    }

    /**
     * 允许合并的最大高度。
     *
     * 参考项目用 `min(bandA, bandB) * 1.5`（它的复合图两侧都是一条带宽）。本工程的拼接图
     * 只有「另一侧」有带宽（章节首尾甚至没有），用 min 会退化成 48px → 配对永远失败
     * （2026-09-28 实测：`edge_window=24 max_cross_h=48`，配对数为 0）。因此取**可用带宽**。
     *
     * 2026-10-03 两轮修正：
     * - 第一轮：`band × 1.5` 只有 216px，「上 2 行 + 下 1 行」会被拒 → 改成「条带 + 行数×行高」；
     * - 第二轮（用户报「两段被合并」）：那个「行高」当时取的是**框高**（= 整个多行块），
     *   上限被放大到 1128px，配合贴边窗口/配对间隙一起失守。现在行高是真正的单行高，
     *   上限只比条带多**一行**——被切开的那半行最多也就一行。
     */
    fun maxCrossHeight(topBandPx: Float, bottomBandPx: Float, medianLineHeight: Float = 0f): Float {
        val band = max(topBandPx, bottomBandPx)
        if (medianLineHeight <= 0f || !medianLineHeight.isFinite()) {
            return max(MIN_EDGE_WINDOW_PX * 2f, band * MAX_CROSS_BAND_COVERAGE)
        }
        return max(MIN_EDGE_WINDOW_PX * 2f, band + medianLineHeight)
    }

    /**
     * 「切开余量」：跨下边界的气泡允许伸进下条带的深度。
     *
     * ⚠️ 2026-10-03 第三次修正（被真机夹具证伪）：上一版把它定成「行高×1.2」，理由是
     * 「被切开的半行不会超过一行」。**这个前提是错的**：缝落在气泡第 2 行之后时，
     * 第 3、4 行本来就在下一张图上（也在条带里），气泡整体是**跨缝延续**的，不是只被切掉半行。
     * 实测（`.verify/24_seam_realpanel.logcat`）：A 只交出去 51px 而 B 的可见剩余是 80px
     * → 覆盖率 57% < 60% → B 把自己那块**又认领又翻了一遍**，缝上出现两层译文（截图里那行糊字）。
     *
     * 正确口径：**A 只能看到条带里的内容**，所以「能认领/能移交」的深度上限就是条带本身；
     * 邻居正文的保护靠的是别的东西 ——「两个独立气泡不合并」（配对间隙/贴边窗口按行高算）、
     * 以及接收侧擦字夹到移交过来的真实字形轮廓（`crossPrepareBubbles`）。
     */
    fun resolveCutAllowance(medianLineHeight: Float, stripPx: Float): Float {
        val strip = if (stripPx.isFinite() && stripPx > 0f) stripPx else 0f
        val byLine = if (medianLineHeight > 0f && medianLineHeight.isFinite()) {
            medianLineHeight * CUT_ALLOWANCE_MIN_LINES
        } else {
            MIN_CUT_ALLOWANCE_PX
        }
        return max(strip, max(MIN_CUT_ALLOWANCE_PX, byLine))
    }

    /** 配对允许的「行数跨度」上限（大块 / 小块 ≤ 该值）。 */
    const val MAX_PAIR_LINE_SPAN = 3f

    /** 配对间隙上限相对**较小那块**的比例（同一条被切开的行，间隙不会超过半行）。 */
    const val PAIR_MAX_GAP_LINE_RATIO = 0.6f

    /** 「贴边」窗口相对行高的比例（参考项目匿名行用的是 0.7×平均行高）。 */
    const val EDGE_WINDOW_LINE_RATIO = 0.6f

    /**
     * 「碎片」高度阈值相对行高的比例：可见高度不足它的框按碎片丢弃。
     *
     * 0.4 行：真机实测的残片是 25px / 41px（行高 ≈ 50-80px）。
     * 真正被切成三七开的一行（可见三成 = 0.3 行）也会被丢掉 —— 但那一侧 OCR 出来的
     * 本来就是残片（`용살가`），丢掉比画一块薄盖板 + 残片译文更好。
     */
    const val MIN_FRAGMENT_LINE_RATIO = 0.4f

    /** 碎片高度阈值（像素）。 */
    fun resolveFragmentHeight(medianLineHeight: Float): Float {
        if (medianLineHeight <= 0f || !medianLineHeight.isFinite()) return 0f
        return max(12f, medianLineHeight * MIN_FRAGMENT_LINE_RATIO)
    }

    /** 一行之外还允许合并的字高倍数（[maxCrossHeight] 的兜底口径里已改为「一行」）。 */
    const val MAX_EXTRA_PAIR_LINES = 1f

    /** 无行高信息时的切开余量下限（像素）。 */
    const val MIN_CUT_ALLOWANCE_PX = 32f

    /**
     * 切开余量的兜底倍数（条带极短时按行数给下限：至少能装下两行）。
     * 见 [resolveCutAllowance]：**正常情况以条带为准**。
     */
    const val CUT_ALLOWANCE_MIN_LINES = 2f
}

/**
 * 归属主流程：
 * 1. 整块跨下边界 → 本图（cross，最多伸进下条带 [SeamConfig.cutAllowancePx] 否则裁短）；
 * 2. 整块跨上边界 → 上一张（**只有 `incomingCross` 真的覆盖它时**才丢弃；否则本图认领并裁进可见区）；
 * 3. 半截字配对（先下边界，再上边界）；
 * 4. 其余按「顶边落在哪一段」归属：落在**上**条带里且没人认领 → 认领并裁进可见区；
 *    落在**下**条带深处（超过切开余量）→ 认领但只移交（`renderHere = false`）；
 * 5. 与 incomingCross 明显重叠的候选抑制掉（避免和上一张移交的下半句重复渲染）。
 *
 * ⚠️ 第 2/4 条的「丢弃」必须带条件（2026-10-03 修）：历史实现在这里无条件 drop，
 * 而上一张图**并不总能移交**（`botH=0` / 贴片不足 2px / 配对失败都会让 `crossOut` 为空），
 * 于是这块文字两边都不管、永远保持原文。现在只有「确实被 incomingCross 覆盖」才丢。
 */
internal fun planSeamOwnership(
    detections: List<SeamDetection>,
    config: SeamConfig,
    incomingCrossBoxes: List<SeamBox>,
    canvasWidth: Int,
    canvasHeight: Int,
    suppressOverlap: Float = SeamTuning.SUPPRESS_INCOMING_OVERLAP
): SeamPlan {
    if (detections.isEmpty()) return SeamPlan(owned = emptyList())
    val used = BooleanArray(detections.size)
    val owned = mutableListOf<SeamOwnership>()
    var pairedTop = 0
    var pairedBottom = 0
    var dropped = 0
    var suppressed = 0
    var orphanAdopted = 0
    var handedTail = 0
    var cappedCross = 0
    val verdicts = MutableList(detections.size) { "unknown" }

    val top = config.topLimitPx
    val bottom = config.bottomLimitPx
    val window = config.edgeWindowPx
    val cap = config.crossCapPx

    val suppress: (SeamBox) -> Boolean = { box ->
        isSuppressedByIncoming(box, incomingCrossBoxes, top, bottom, suppressOverlap)
    }
    // 「上一张图确实把这块移交过来了」：见 [isHandedOverByIncoming]（比旧的「只看可见条」
    // 多两道约束：整块覆盖率，以及移交框必须**横向罩得住**这块）。
    val handedOver: (SeamBox) -> Boolean = { box ->
        isHandedOverByIncoming(box, incomingCrossBoxes, top, bottom, suppressOverlap)
    }
    // 条带边缘的残片（可见高度不足碎片阈值）不当气泡处理，见 [SeamConfig.fragmentHeightPx]。
    val fragmentHeight = config.fragmentHeightPx
    fun isFragment(height: Float): Boolean = fragmentHeight > 0f && height < fragmentHeight

    // 1) 整块跨下边界：拼接后 OCR 看到完整一行 → 归本图，剩余部分交给下一张
    if (config.hasBottomBand) {
        detections.forEachIndexed { index, detection ->
            if (!used[index] && detection.outer.top < bottom && detection.outer.bottom > bottom) {
                used[index] = true
                // 伸进下条带太深 = 把下一张图正文也圈进来了：裁到「切开余量」为止，
                // 剩下的部分留给下一张图自己识别（它会作为 orphan 被下一张认领）。
                val clipped = if (detection.outer.bottom > cap) {
                    cappedCross += 1
                    verdicts[index] = "cross_capped"
                    clipDetection(detection, -Float.MAX_VALUE, cap, canvasWidth, canvasHeight)
                } else {
                    verdicts[index] = "cross"
                    detection
                }
                // 太薄的框是条带边缘的残片：不认领也不移交（留给下一张图看到整行的那侧处理）。
                if (clipped == null) {
                    // 字形整体深在条带里（内容框裁完退化）—— 它是下一张图的正文，
                    // 不认领不移交（历史上这里会退化成「区域大框当内容」，错位画一行）。
                    dropped += 1
                    verdicts[index] = "dropped_glyph_outside"
                } else if (isFragment(clipped.outer.height)) {
                    dropped += 1
                    verdicts[index] = "dropped_fragment"
                } else {
                    owned += SeamOwnership(clipped.bubble, cross = true)
                }
            }
        }
    }
    // 2) 整块跨上边界：上半在上一张图里 → 归上一张（它已经渲染并移交给 incomingCross）。
    //    但**上一张没移交**时本图必须认领，否则这块永远没有译文。
    if (config.hasTopBand) {
        detections.forEachIndexed { index, detection ->
            if (!used[index] && detection.outer.top < top && detection.outer.bottom > top) {
                used[index] = true
                if (handedOver(detection.outer)) {
                    dropped += 1
                    verdicts[index] = "dropped_top_handed"
                } else {
                    val clipped = clipDetection(detection, top, Float.MAX_VALUE, canvasWidth, canvasHeight)
                    if (clipped == null) {
                        // 字形整块在上条带里（= 上一张图的正文）：本图没有可画的内容。
                        // 绝不退化成「区域大框当内容」（见 [clipDetection] 的注释）。
                        dropped += 1
                        verdicts[index] = "dropped_glyph_outside"
                    } else if (isFragment(clipped.outer.height)) {
                        // 跨上边界但只剩一条残片 → 本图确实画不出东西。
                        dropped += 1
                        verdicts[index] = "dropped_fragment"
                    } else {
                        // 上一张**没有**整块移交它 → 本图认领并裁进可见区。
                        //
                        // 2026-10-03 改：不再看 `visibleFraction < minVisibleFraction` 就丢。
                        // 那个判据的前提是「上一张已经画过自己那部分」，但「没被整块移交」恰恰
                        // 说明上一张没画（`crossOut` 为空或没覆盖到它）——此时丢掉的结果是可见的
                        // 半行永远保持原文（真机 `[32317270]`：可见 44px / 整块 95px = 46%）。
                        // 交界处的混行（条带尾行 + 本图首行被检测器并成一行）由调用方
                        // `translateStitched` 的 incoming 交叉判定再兜一次（本图另有检出时本图优先）。
                        owned += SeamOwnership(clipped.bubble, cross = false)
                        orphanAdopted += 1
                        verdicts[index] = "orphan_adopted"
                    }
                }
            }
        }
    }

    // 3a) 下边界配对：本图内贴上边界的「上半」+ 下条带内贴下边界的「下半」
    if (config.hasBottomBand) {
        val uppers = halfCandidates(detections, used) { box ->
            box.bottom <= bottom + 0.5f && box.bottom >= bottom - window
        }
        val lowers = halfCandidates(detections, used) { box ->
            box.top >= bottom - 0.5f && box.top <= bottom + window
        }
        buildHalfPairs(uppers, lowers, used, config).forEach { pair ->
            used[pair.upper.index] = true
            used[pair.lower.index] = true
            // 合并框同样受「切开余量」约束：配对出来的框曾经可以一路盖到下一张图正文上。
            val clipTo = if (config.cutAllowancePx > 0f) -Float.MAX_VALUE to config.crossCapPx else null
            val merged = mergeDetections(pair.upper.detection, pair.lower.detection, clipTo, canvasWidth, canvasHeight)
            if (clipTo != null &&
                maxOf(pair.upper.detection.outer.bottom, pair.lower.detection.outer.bottom) > config.crossCapPx
            ) {
                cappedCross += 1
            }
            val mergedBox = boxOf(merged, canvasWidth, canvasHeight)
            if (suppress(mergedBox)) {
                suppressed += 1
                verdicts[pair.upper.index] = "suppressed"
                verdicts[pair.lower.index] = "suppressed"
            } else {
                owned += SeamOwnership(merged, cross = true)
                pairedBottom += 1
                verdicts[pair.upper.index] = "paired_bottom"
                verdicts[pair.lower.index] = "paired_bottom"
            }
        }
    }
    // 3b) 上边界配对：上条带内贴上边界的「上半」+ 本图内贴上边界的「下半」
    if (config.hasTopBand) {
        val uppers = halfCandidates(detections, used) { box ->
            box.bottom <= top + 0.5f && box.bottom >= top - window
        }
        val lowers = halfCandidates(detections, used) { box ->
            box.top >= top - 0.5f && box.top <= top + window
        }
        buildHalfPairs(uppers, lowers, used, config).forEach { pair ->
            used[pair.upper.index] = true
            used[pair.lower.index] = true
            // 上半在上一张图里，本图只负责把整句译文放进本图可见区域（裁到分界内），
            // 否则文字会有一半被画到画外看不见 → 用户仍然觉得「缺字」。
            val merged = mergeDetections(pair.upper.detection, pair.lower.detection, top to bottom, canvasWidth, canvasHeight)
            val mergedBox = boxOf(merged, canvasWidth, canvasHeight)
            if (suppress(mergedBox)) {
                suppressed += 1
                verdicts[pair.upper.index] = "suppressed"
                verdicts[pair.lower.index] = "suppressed"
            } else {
                owned += SeamOwnership(merged, cross = false)
                pairedTop += 1
                verdicts[pair.upper.index] = "paired_top"
                verdicts[pair.lower.index] = "paired_top"
            }
        }
    }

    // 4) 其余按归属分类
    detections.forEachIndexed { index, detection ->
        if (used[index]) return@forEachIndexed
        val box = detection.outer
        if (suppress(box)) {
            suppressed += 1
            verdicts[index] = "suppressed"
            return@forEachIndexed
        }
        if (box.top < top - 0.5f) {
            // 整块落在**上**条带里 = 上一张图的正文。上一张没移交时本图认领，
            // 裁到本图可见区（否则它的框在本图画面之外，渲染层只能夹到页首）。
            val visible = clipDetection(detection, top, Float.MAX_VALUE, canvasWidth, canvasHeight)
            if (visible == null) {
                // 字形整块在可见区之外（内容框裁完退化）→ 不认领。
                // 绝不退化成「区域大框当内容」（见 [clipDetection] 的注释）。
                dropped += 1
                verdicts[index] = "dropped_glyph_outside"
            } else if (isFragment(visible.outer.height)) {
                dropped += 1
                verdicts[index] = "dropped_fragment"
            } else {
                orphanAdopted += 1
                verdicts[index] = "orphan_adopted"
                owned += SeamOwnership(visible.bubble, cross = false)
            }
            return@forEachIndexed
        }
        if (box.top >= bottom - 0.5f) {
            // 整块落在**下**条带里 = 下一张图的正文（2026-10-12 起不再移交，直接丢弃）。
            //
            // 历史行为是「认领 + 移交下一张」（handed_tail，renderHere=false）。但移交框是
            // **拼接图坐标系**的百分比（分母 = 拼接高），下一张换算时却按「上一张本图高」
            // 解（[CrossBubblePart.sourceHeightPx] = source.height）—— 整块在条带里的泡
            // 换算后整体错位：真机 15:04 trace（gamesal 7/8 页）악질들泡移交框落在第 8 页
            // -6.5%，真实字形在 +4.9% → 中文叠在错位处、韩文原样露出，且第 8 页自己的
            // 检出被 [isHandedOverByIncoming] 抑制，两边都没人擦。
            // 而下一张图自己**必然完整看到**这个泡（它不受条带切割），由下一张的 owned
            // 链路渲染/擦除/翻译，坐标天然正确 —— 所以这里直接丢弃（dropped_tail），
            // 把泡还给下一张。
            // 真跨缝泡（规则 1 的 cross：主体在本图内、只伸进条带一点）不受影响 ——
            // 它的主体坐标在第 7 页本图内，移交/接缝带语义成立。
            dropped += 1
            verdicts[index] = "dropped_tail"
            return@forEachIndexed
        }
        val clipped = if (config.hasBottomBand && box.bottom > cap) {
            cappedCross += 1
            verdicts[index] = "cross_capped"
            clipDetection(detection, top, cap, canvasWidth, canvasHeight)
        } else {
            verdicts[index] = "owned"
            detection
        }
        if (clipped == null) {
            dropped += 1
            // 字形整体深在条带里（内容框裁完退化）或外框退化 —— 都不认领
            // （见 [clipDetection]：这里回退成区域大框会画出一行错位文字）。
            verdicts[index] = "dropped_glyph_outside"
            return@forEachIndexed
        }
        owned += SeamOwnership(
            clipped.bubble,
            cross = config.hasBottomBand && clipped.outer.bottom > bottom + 0.5f
        )
    }

    return SeamPlan(
        owned = owned,
        detected = detections.size,
        pairedTopSeam = pairedTop,
        pairedBottomSeam = pairedBottom,
        droppedNeighbor = dropped,
        suppressedByIncoming = suppressed,
        orphanAdopted = orphanAdopted,
        handedTail = handedTail,
        cappedCross = cappedCross,
        verdicts = verdicts
    )
}

/**
 * 把一个检测框纵向裁到 [topLimit]..[bottomLimit]，并同步重算百分比框与行数。
 *
 * 为什么要裁而不是丢：裁完之后这块几何就完全落在本图画面内，译文/擦字都能正常做；
 * 不裁的话百分比框会跑出 0-100（渲染层只能夹到页面边缘，画出一行错位的假译文）。
 *
 * 裁完**没有剩余高度**（框整个在区间之外）时返回 null —— 调用方据此判定「画不出来」。
 * ⚠️ 这里绝不能返回原框：调用方是靠「裁完还剩不剩」来区分「画面内」与「画面外」的，
 * 返回原框会让画面外的框被当成画面内（2026-10-03 单测当场抓到）。
 *
 * ## 字形不在可见区 → 一律不认领（2026-10-16 修）
 *
 * 历史实现里「裁完内容框退化 → 回退用 outer」有一条致命形态（真机 Lezhin 实测）：
 * 单字 chip 的文字在上条带里（content = 75~96），而 outer 是**大区域框**（0~731）——
 * 裁完 content 退化，于是 content 被换成 597×587px 的 outer。下游（`resolveFinalBubbles`
 * / `refine_boxes` / `ink_tilt`）把这个大框当文字框：遮罩 597×587、译文被画到框中心
 * （错位几百像素）、盖板只剩贴着 chip 的一小块 —— 用户看到「气泡没被处理 + 画面别处
 * 多出一个字」（原文回退时就是那句韩文飘在错误位置）。
 *
 * 现在的口径：**外层框可见 ≠ 可认领** —— 只有当「字形（content）在可见区里还剩下一部分」
 * 才裁入认领；字形整体在可见区之外（裁完退化）时返回 null，由调用方丢弃。
 * 这块字的完整像素区在**邻居图**的可视范围里（它不在本图的画面内），邻居图的 OCR 会
 * 看到整行，交给它自己的 owned 链路处理，坐标天然正确。
 */
private fun clipDetection(
    detection: SeamDetection,
    topLimit: Float,
    bottomLimit: Float,
    canvasWidth: Int,
    canvasHeight: Int
): SeamDetection? {
    val outer = detection.outer.clipVertical(topLimit, bottomLimit)
    if (outer.width <= 0.5f || outer.height <= 0.5f) return null
    val sourceContent = detection.content
    val clippedContent = sourceContent.clipVertical(topLimit, bottomLimit)
    val content = when {
        // 原内容框本身就退化（老数据的少数形态）→ 保持旧行为（退到 outer）。
        sourceContent.width <= 0.5f || sourceContent.height <= 0.5f -> outer
        // 字形整体落在可见区之外 → 本图没有可画的内容，交给邻居图（见上方注释）。
        clippedContent.width <= 0.5f || clippedContent.height <= 0.5f -> return null
        else -> clippedContent
    }
    val visibleRatio = (outer.height / max(detection.outer.height, 1f)).coerceIn(0f, 1f)
    val bubble = detection.bubble.copy(
        x = percent(outer.left, canvasWidth),
        y = percent(outer.top, canvasHeight),
        w = percent(outer.width, canvasWidth),
        h = percent(outer.height, canvasHeight),
        contentX = percent(content.left, canvasWidth),
        contentY = percent(content.top, canvasHeight),
        contentW = percent(content.width, canvasWidth),
        contentH = percent(content.height, canvasHeight),
        // 行数按可见比例折算：裁掉一半还留 3 行 → 「原文字高 = 框高 ÷ 3」会把字高估小一半。
        sourceLineCount = maxOf(1, (detection.bubble.sourceLineCount * visibleRatio).roundToInt())
    )
    return SeamDetection(bubble, outer, content)
}

private data class HalfCandidate(val index: Int, val detection: SeamDetection)

private data class HalfPair(val upper: HalfCandidate, val lower: HalfCandidate, val score: Float)

private fun halfCandidates(
    detections: List<SeamDetection>,
    used: BooleanArray,
    predicate: (SeamBox) -> Boolean
): List<HalfCandidate> {
    return detections.indices
        .filter { index -> !used[index] && predicate(detections[index].outer) }
        .map { index -> HalfCandidate(index, detections[index]) }
}

/**
 * 贪心配对（对齐参考项目 pairCandidates.sort(...score...) + used 去重）：
 * 分数 = 水平重叠 ×0.6 + 行高比 ×0.3 + 间隙得分 ×0.1。
 */
private fun buildHalfPairs(
    uppers: List<HalfCandidate>,
    lowers: List<HalfCandidate>,
    used: BooleanArray,
    config: SeamConfig
): List<HalfPair> {
    val candidates = mutableListOf<HalfPair>()
    uppers.forEach { upper ->
        lowers.forEach { lower ->
            if (upper.index == lower.index) return@forEach
            val score = pairScore(upper.detection, lower.detection, config) ?: return@forEach
            candidates += HalfPair(upper, lower, score)
        }
    }
    candidates.sortWith(
        compareByDescending<HalfPair> { it.score }
            .thenBy { it.upper.index }
            .thenBy { it.lower.index }
    )
    val taken = mutableSetOf<Int>()
    val result = mutableListOf<HalfPair>()
    candidates.forEach { pair ->
        if (taken.contains(pair.upper.index) || taken.contains(pair.lower.index)) return@forEach
        if (used[pair.upper.index] || used[pair.lower.index]) return@forEach
        taken += pair.upper.index
        taken += pair.lower.index
        result += pair
    }
    return result
}

/** 返回配对分数；null = 不符合参考项目的配对判据。 */
private fun pairScore(upper: SeamDetection, lower: SeamDetection, config: SeamConfig): Float? {
    val upperBox = upper.outer
    val lowerBox = lower.outer
    val upperText = upper.bubble.originalText.trim()
    val lowerText = lower.bubble.originalText.trim()
    if (upperText.isEmpty() || lowerText.isEmpty()) return null
    if (upperText.replace(Regex("\\s+"), "") == lowerText.replace(Regex("\\s+"), "")) return null

    val overlap = upperBox.horizontalOverlapRatio(lowerBox)
    if (overlap < SeamTuning.MIN_HORIZONTAL_OVERLAP) return null

    val heightRatio = min(upperBox.height, lowerBox.height) /
        max(max(upperBox.height, lowerBox.height), 1f)
    // 2026-10-03 修：原来直接卡 `heightRatio >= 0.5`，而「上 2 行 / 下 1 行」这类真实切法
    // 正好落在 0.5 上（上半 2 行高 ≈ 下半 1 行高的 2 倍）→ 晃动一点就拒绝配对，
    // 于是上半被当完整气泡翻译、下半没人管（用户截图里的形态）。
    // 改成按**行数跨度**判断：以较小那块为「一行」，两块跨度不超过 MAX_PAIR_LINE_SPAN。
    val lineSpan = if (heightRatio < SeamTuning.MIN_HEIGHT_RATIO) {
        val unit = max(min(upperBox.height, lowerBox.height), 1f)
        max(upperBox.height, lowerBox.height) / unit
    } else {
        1f
    }
    if (lineSpan > SeamTuning.MAX_PAIR_LINE_SPAN) return null

    val gap = lowerBox.top - upperBox.bottom
    val maxGap = SeamTuning.resolveMaxPairGap(upperBox.height, lowerBox.height)
    if (gap > maxGap) return null

    val mergedHeight = max(upperBox.bottom, lowerBox.bottom) - min(upperBox.top, lowerBox.top)
    if (mergedHeight > config.maxCrossHeightPx) return null

    // 上块已经是完整句子收尾、两块之间又有明显间隙 → 是两句话，不是被切开的同一句。
    val gapThreshold = max(2f, min(upperBox.height, lowerBox.height) * 0.25f)
    if (SeamTuning.endsSentence(upperText) && gap > gapThreshold) return null

    val gapScore = 1f - (max(0f, gap) / max(1f, maxGap))
    return overlap * 0.6f + heightRatio * 0.3f + gapScore * 0.1f
}

/**
 * 合并两块半截字：外框/内容框取并集，文本用**空格**拼接
 * （渲染与翻译请求都会把连续空白归一化成单空格，用空格比换行更不容易被当成两句），
 * 只有两块都是 solid 才保持 solid，否则退回旁白样式（避免把艺术画面当纯色底擦）。
 */
private fun mergeDetections(
    upper: SeamDetection,
    lower: SeamDetection,
    clipVerticalTo: Pair<Float, Float>?,
    canvasWidth: Int,
    canvasHeight: Int
): Bubble {
    var outer = upper.outer.union(lower.outer)
    var content = upper.content.union(lower.content)
    if (clipVerticalTo != null) {
        outer = outer.clipVertical(clipVerticalTo.first, clipVerticalTo.second)
        content = content.clipVertical(clipVerticalTo.first, clipVerticalTo.second)
    }
    val bothSolid = upper.bubble.bgType.equals("solid", ignoreCase = true) &&
        lower.bubble.bgType.equals("solid", ignoreCase = true)
    val text = listOf(upper.bubble.originalText.trim(), lower.bubble.originalText.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    return upper.bubble.copy(
        x = percent(outer.left, canvasWidth),
        y = percent(outer.top, canvasHeight),
        w = percent(outer.width, canvasWidth),
        h = percent(outer.height, canvasHeight),
        bgType = if (bothSolid) "solid" else "none",
        originalText = text,
        translatedText = "",
        contentX = percent(content.left, canvasWidth),
        contentY = percent(content.top, canvasHeight),
        contentW = percent(content.width, canvasWidth),
        contentH = percent(content.height, canvasHeight),
        // 被切开的通常是同一行：两半行数相同 → 取较大者，别相加（相加会把原字高估成一半）
        sourceLineCount = maxOf(upper.bubble.sourceLineCount, lower.bubble.sourceLineCount)
    )
}

private fun boxOf(bubble: Bubble, canvasWidth: Int, canvasHeight: Int): SeamBox {
    return SeamBox(
        left = bubble.x / 100f * canvasWidth,
        top = bubble.y / 100f * canvasHeight,
        right = (bubble.x + bubble.w) / 100f * canvasWidth,
        bottom = (bubble.y + bubble.h) / 100f * canvasHeight
    )
}

private fun percent(value: Float, total: Int): Float {
    if (total <= 0) return 0f
    return (value / total.toFloat() * 100f).coerceIn(0f, 100f)
}

/**
 * 是否已被上一张图移交的「下半句」覆盖：
 * 用本图可见区域（裁到分界内）与 incomingCross 内容框求交，重叠超过阈值即抑制，
 * 否则同一句会被画两遍（上一张移交的那份 + 本图自己配出来的那份）。
 *
 * ⚠️ 只用在下边界配对（规则 3a）等「按可见区抑制」的场景。**跨上边界的丢弃**
 * （规则 2）必须用 [isCoveredByIncoming]（整块覆盖），原因见那个函数的注释
 * （真机 `[32317270]`：可见区 76% 被覆盖 → 整块丢弃，结果本图自己的半行既不擦也不翻）。
 */
private fun isSuppressedByIncoming(
    box: SeamBox,
    incomingCrossBoxes: List<SeamBox>,
    topLimit: Float,
    bottomLimit: Float,
    threshold: Float
): Boolean {
    if (incomingCrossBoxes.isEmpty()) return false
    val visible = box.clipVertical(topLimit, bottomLimit)
    if (visible.area <= 0f) return false
    return incomingCrossBoxes.any { incoming ->
        incoming.intersectionArea(visible) / visible.area >= threshold
    }
}

/**
 * 这块检测框是否**真的**被上一张图移交的气泡覆盖（= 本图可以不画它）。
 *
 * ## 为什么不能沿用「只看边界以下那点可见区域」（2026-10-03 真机修）
 *
 * 旧口径只把块裁到本图可见区再与 incoming 框比。真机 `[32317270]` 现场：跨上边界的块
 * outer `93~188`（可见部分 `144~188`，44px），incoming 框 `144~226`（x 187~499）→
 * 可见区里 76% 落在 incoming 内 ≥ 60% → **整块**被判成「上一张已画过」丢弃。可那块
 * 同时含**本图自己的**半行（`방해됐단말이야…`，检出框 x 83~492 向左伸出了移交框），
 * 于是那半行既不擦也不翻 —— 用户截图里「韩文留着、旁边一个白框中文」正是这一条。
 *
 * ## 现行判据（两条任一成立才算「已移交」）
 *
 * 1. **整块被覆盖**：`incoming ∩ box / box.area ≥ 阈值`（上一张的移交框本就是这个气泡的
 *    布局框，合法的续排天然满足）；
 * 2. **可见段被覆盖 **且** 移交框横向罩得住这块**：`hOverlap ≥ 块宽 × [HORIZONTAL_CONTAIN_RATIO]`。
 *
 * 第 2 条里的横向约束是关键区分器：合法的续排里，移交框（气泡布局框）在横向上**包含**本图
 * 检出的那一段（实测 100%），而「侵占本图另一个气泡」的情形只有 74%（本图检出向左伸出）。
 * 只看可见段的旧口径两种都判「已移交」，于是把本图内容一起丢了。
 */
private fun isHandedOverByIncoming(
    box: SeamBox,
    incomingCrossBoxes: List<SeamBox>,
    topLimit: Float,
    bottomLimit: Float,
    threshold: Float
): Boolean {
    if (incomingCrossBoxes.isEmpty() || box.area <= 0f) return false
    if (incomingCrossBoxes.any { incoming -> incoming.intersectionArea(box) / box.area >= threshold }) {
        return true
    }
    val visible = box.clipVertical(topLimit, bottomLimit)
    if (visible.area <= 0f) return false
    return incomingCrossBoxes.any { incoming ->
        val visibleCovered = incoming.intersectionArea(visible) / visible.area >= threshold
        val overlap = (min(incoming.right, box.right) - max(incoming.left, box.left)).coerceAtLeast(0f)
        visibleCovered && overlap >= box.width * HORIZONTAL_CONTAIN_RATIO
    }
}

/** 「移交框横向罩得住本图检出」的比例下限（见 [isHandedOverByIncoming]）。 */
private const val HORIZONTAL_CONTAIN_RATIO = 0.9f
