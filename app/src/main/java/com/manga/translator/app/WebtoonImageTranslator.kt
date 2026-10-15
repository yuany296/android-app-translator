package com.manga.translator.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.graphics.Typeface
import com.manga.translator.overlay.BubbleColors
import com.manga.translator.overlay.BubbleInteriorScanner
import com.manga.translator.overlay.WritableAreaRefiner
import com.manga.translator.overlay.erase.ErasePatch
import com.manga.translator.store.AppSettings
import com.manga.translator.translate.BalancedLineBreaker
import com.manga.translator.translate.Bubble
import com.manga.translator.translate.BubbleGeometry
import com.manga.translator.translate.NormalizedRect
import com.manga.translator.translate.OcrBlockMergeRules
import com.manga.translator.translate.TextAlignment
import com.manga.translator.translate.TextBlock
import com.manga.translator.translate.TextLayoutMetrics
import com.manga.translator.translate.TextQuadGeometry
import com.manga.translator.translate.TranslationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * 跨图气泡的「剩余部分」：当前图里检测到、但下半截落在下一张图里的气泡，
 * 由下一张图渲染时补画（几何/字号/颜色都已换算到下一张图坐标系）。
 * 与 C:\homework\translator 扩展的 stitch_overflow（allowVerticalOverflow）同思路。
 */
data class CrossBubblePart(
    val translatedText: String,
    /**
     * 这个气泡在**上一张图**里识别到的原文（2026-10-03 加）。
     *
     * 为什么带上：下一张图第一次有了**文本证据** —— 「本图这一带检出的文字是不是这半句的延续」
     * 可以判了（`incoming_decision` 日志、以及 `filterContestedIncoming` 的冲突诊断）。
     * 之前只有译文，几何一错就无从对证（真机 `[32317270]`：白框中文糊在本图另一个气泡上）。
     */
    val originalText: String = "",
    val narration: Boolean,
    val colors: BubbleColors,
    /** 擦字贴片（只裁出属于下一张图的部分，独立位图，用后由消费方回收）。 */
    val patchBitmap: Bitmap,
    /** 贴片在下一张图坐标系里的目标矩形。 */
    val patchDest: RectF,
    /** 整个气泡的完整内容框（顶边可能为负——起点在上一张图里），供续排同一套文字布局。 */
    val contentRect: RectF,
    /** 与上一张图一致的字号（px），保证两段渲染接缝处字号/断行完全相同。 */
    val fontSizePx: Float,
    /** 与上一张图一致的原文对齐方式（左/居中/右），续排时沿用。 */
    val alignment: String = TextAlignment.CENTER,
    /**
     * 覆盖层渲染模式下的擦字贴片通道（mtlocal 拦截 URL）：
     * 会话循环在把贴片交给下一张图**之前**编码落仓并回填到这里；
     * 整图替换模式不需要（贴片直接画进成图）。
     */
    val patchUrl: String? = null,
    /**
     * **上一张图**里这个气泡最终用的百分比框（x,y,w,h，相对上一张原图）。
     *
     * 为什么必须带上它：贴片/续排用的 [contentRect] 是**上一张图的像素坐标**，如果下一张图
     * 直接拿它按自己的宽高折算百分比，两张图尺寸不一致时 x/宽 会被缩放错 —— 同一个气泡
     * 在接缝两侧就被画成两个错位的框，用户看到的「重影」正是这么来的（实测
     * seamBubbleA/B：同一句「这是什么？」在两图里各画一次，框高 137px vs 347px）。
     * 带上百分比框 + 上一张图的像素尺寸，下一张图就能原样换算，不要求两图同尺寸。
     */
    val sourceBoxPercent: FloatArray? = null,
    /** 上一张原图尺寸（配合 [sourceBoxPercent] 做跨尺寸换算）。 */
    val sourceWidthPx: Int = 0,
    val sourceHeightPx: Int = 0,
    /**
     * 上一张图里这个气泡最终用的**盖板轮廓**（8 个数 = 4 个顶点，相对上一张原图的百分比）。
     *
     * 两个用处，都要求「接缝两侧完全同源」：
     * 1. 续排的那一半必须用**同一个**四边形，否则两边各自估计倾角会得到不同角度
     *    （实测接缝两半 -4.07° vs 0.00°）—— 一个气泡在接缝处一半斜一半平，比重影还显眼；
     * 2. 下一张图那块盖板的外框直接由它取外接框，尺寸/位置因此与本图那块严格一致
     *    （传 null 时渲染层会退回 `x/y/w/h` = 排版框，比字形大 30% → 「上下两个层」）。
     *
     * 来源是 [FinalBubble.regionPolygonPercent]：有倾角时是外扩几像素的四边形，
     * 轴对齐时是外扩几像素的矩形四角 —— 两种情形都有值。
     * 换算到下一张图坐标系只需 y 减 100（两图紧邻），x 不变。
     */
    val sourcePolygon: List<Float>? = null
)

/**
 * 接缝带场景（2026-09-30）：「一条图缝」上的所有跨图气泡打包成**一个**接缝覆盖层。
 *
 * 交给渲染层的是一张「虚拟图」——[cleanedUrl] 这条从本图清理图里裁出来的带子，
 * 上端落在本图内、下端落在下一张图内，整条来自同一张清理图。渲染层把 root 摆在这条带的
 * 屏幕矩形上，于是跨缝气泡只有**一块盖板 + 一份译文**，接缝处不可能出现两层纹理。
 *
 * 气泡坐标一律是**带内百分比**（0-100，允许溢出：`stitchOverflow = true`）。
 */
data class SeamBandScene(
    val bubbles: List<BrowserSceneBuilder.SceneBubble>,
    /**
     * 带在拼接图里的位置与尺寸（像素）→ 渲染层用 [sourceWidthPx]/[sourceHeightPx] 换算屏幕矩形。
     *
     * ⚠️ [bandLeftPx] 不能省：带是拼接图上横向裁出来的**一小块**（宽度通常远小于图宽），
     * 只给宽度不给左边距，渲染层会把带当成「从图左边开始」，整块盖板与译文横向平移
     * 一个边距（实测 496px 宽的带在 760px 宽的图上偏出 114px，盖板左边缘糊到气泡外）。
     */
    val bandLeftPx: Int,
    val bandTopPx: Int,
    val bandWidthPx: Int,
    val bandHeightPx: Int,
    /** 拼接图里「本图」的起点（= topStrip 高度）。 */
    val topHPx: Int,
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    /** 接缝带清理图（同一张纹理覆盖上下两半）；纯实底气泡时为 null。 */
    val cleanedUrl: String?,
    /** 本图场景里这些气泡的层 id（渲染层据此摘掉「上半块」）。 */
    val ownBubbleIds: List<String>,
    /** 下一张图场景里这些气泡的层 id（摘掉「下半块」）。 */
    val nextBubbleIds: List<String>
)

/** 内嵌浏览器渲染模式：DOM 覆盖层（场景下发）或整图替换（回退/兼容）。 */
enum class BrowserRenderMode {
    /** 场景下发给 WebView 的 DOM 覆盖层渲染（默认）。 */
    DOM_SCENE,

    /** 旧的整图替换：Kotlin 侧渲染成图 PNG，WebView 拦截 URL 原位贴上。 */
    FULL_PNG
}

/**
 * 单张图的处理阶段文案（2026-10-02），供内嵌浏览器的 loading 框显示。
 *
 * 为什么文案集中在渲染管线这一层：只有它知道「现在到底在干什么」。
 * UI 侧（`EmbeddedBrowserScreen`）只负责把字符串挂到卡片上，
 * 参考项目 `C:\homework\translator` 也是同一分工（`scope.loading(target, key, "识别当前页...")`）。
 *
 * 文案要短：卡片是单行胶囊，长了就被省略号吃掉（webtoon 图上 12px 也就能放 8–10 个字）。
 */
object WebtoonPhase {
    /** 下载/解码由调用方负责（会话侧另有「读取图片…」），这里从 OCR 开始。 */
    const val RECOGNIZING = "识别文字…"
    const val TRANSLATING = "翻译文字…"
    const val RENDERING = "擦字渲染…"
}

/**
 * 内嵌浏览器链路（2026-09-28 新增）：对**单张漫画图片**执行
 * 「气泡检测 → OCR → 翻译 → 擦字 → 渲染译文图」，输出与原图同尺寸的成图 PNG。
 *
 * 效果对齐 C:\homework\translator 扩展的「图片原位翻译」：网页里的漫画图直接被替换成
 * 译文图，页面滚动/排版完全不受影响。不依赖录屏、悬浮窗权限，也不需要启动前台服务；
 * 复用悬浮球链路的全部底层能力（OCR 引擎、翻译引擎、句子缓存、术语表、擦字贴片、
 * 气泡配色采样），两种入口的译文与缓存行为完全一致。
 *
 * **截页（中缝截字）处理（2026-09-28）**：长条漫画被网站切成多张图，跨图断点处的
 * 文字会变成半截字导致 OCR 失败。处理方式：
 * 1. 识别当前图时，把相邻图（上一张的底部条 / 下一张的顶部条）拼接到上下，
 *    跨图断点的气泡在拼接图里是完整的，OCR 正常识别；
 * 2. 跨越下边界的气泡归当前图「所有」：译文 + 擦字贴片按真实坐标渲染，
 *    落在当前图内的部分直接画，落在下一张图内的部分打包成 [CrossBubblePart]；
 * 3. 上一张图移交的 [CrossBubblePart] 在渲染当前图时补画（含负坐标内容框续排同一套布局）；
 * 4. 完全落在邻居图条内的气泡跳过（归邻居所有，避免重复翻译）。
 *
 * 所有权约定：[translateImage] 接管传入 bitmap 与两条 strip 的生命周期，返回（含失败）后
 * 全部回收；[incomingCross] 里的贴片位图在渲染后由本类回收。
 */
class WebtoonImageTranslator(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val writableAreaRefiner = WritableAreaRefiner()
    private val bubbleFirstRegionPlanner = BubbleFirstRegionPlanner()
    private val translationResolver = PageTranslationResolver(ioDispatcher)
    private val renderer = BubbleImageRenderer()
    private val patchPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * 最近一次 [buildScene] 的气泡计数（owned / cross / incoming / final）。
     *
     * 只用于诊断日志：跨页气泡的下半那半一旦在这里丢掉，画面就是「下半段没译文」，
     * 而 [Outcome.bubbleCount] 是**和数**，看不出丢在哪一步。串行调用，普通字段即可。
     */
    private var sceneDiag: String = ""

    data class Outcome(
        val bubbleCount: Int,
        val cacheHitCount: Int,
        val cacheTotalCount: Int,
        val warningMessage: String?,
        /** 整图替换模式的成图 PNG；覆盖层模式为 null。 */
        val renderedPng: ByteArray?,
        /** 场景（百分比坐标 + 颜色 + 字高 + 清理图 URL）；整图模式为 null。 */
        val scene: BrowserSceneBuilder.Scene? = null,
        /** 移交给下一张图的跨图气泡剩余部分（按 scan 顺序串行处理时正好衔接）。 */
        val crossOut: List<CrossBubblePart> = emptyList(),
        /**
         * 本图产生的**接缝覆盖层**（跨下边界的气泡：一块盖板 + 一份译文，绑在同一条清理图上）。
         *
         * 与 [crossOut] 是「同一批气泡的两种画法」：接缝场景成功上屏后，调用方用
         * [SeamBandScene.ownBubbleIds] / [SeamBandScene.nextBubbleIds] 把两半各自那套摘掉；
         * 接缝场景不成立（几何不允许 / 拿不到清理图）时什么都不用做，[crossOut] 的两半就是兜底。
         */
        val seam: SeamBandScene? = null,
        /** 场景气泡计数诊断（owned/cross/incoming/final），只用于日志。 */
        val sceneDiag: String = "",
        /**
         * 下条带太短：跨缝气泡的检测框顶到了拼接图底边（2026-10-03）。
         *
         * 为什么必须让调用方知道：条带（`clamp(width*0.20,144,384)`）只够装「被切开的半行」，
         * 而缝落在气泡第 2 行之后时，第 3/4 行也在条带里 —— 不够高就会把句子**截断着**读出来
         * （真机实测 `케이시 접속종료를 죽거나 접속게이트로 돌`，译文因此串味），
         * 移交出去的深度也比下一张图看到的浅 → 下一张图又认领一次 → 缝上两层译文。
         * 调用方拿到 true 时应当**加高条带重跑一次**。
         */
        val stripTruncated: Boolean = false
    )

    suspend fun translateImage(
        source: Bitmap,
        topStrip: Bitmap? = null,
        bottomStrip: Bitmap? = null,
        incomingCross: List<CrossBubblePart> = emptyList(),
        renderMode: BrowserRenderMode = BrowserRenderMode.DOM_SCENE,
        /**
         * 允许「相邻行并回」（2026-10-11，见 [absorbIncomingFragments]）。
         *
         * 会话侧在**有接缝带瞄准本图**时必须传 false：接缝带的译文是上一张图按截断文本
         * 翻的（它在接缝上下连成一句，正是「一块盖板 + 一份译文」的价值所在），而并回会把
         * 更深的行并进移交气泡、整句重翻 —— 接缝带派发时会摘掉移交气泡那一层，并回的
         * 译文就丢了。所以带在时保持旧行为（相邻行作为独立气泡自己渲染），带不在
         * （整图模式 / 移交失败 / 章节首尾）时才并回。
         */
        allowIncomingMerge: Boolean = true,
        /**
         * 阶段回调（2026-10-02）：给内嵌浏览器的 loading 框用。
         *
         * 为什么需要它：单张图要走「OCR → 翻译 → 擦字渲染」，RIDI/Lezhin 这种大图上实测
         * 2–5 秒；只有一个静态「翻译中」看不出卡在哪一步（参考项目同样按阶段换文案）。
         *
         * ⚠️ 回调可能在 **IO 线程**被调用（`translateStitched` 里并发跑擦字与翻译请求），
         * 调用方若要碰 WebView（线程受限）必须自己切回主线程。
         */
        onPhase: (String) -> Unit = {}
    ): Outcome {
        val timer = TranslationTimer()
        try {
            val topH = topStrip?.height ?: 0
            val botH = bottomStrip?.height ?: 0
            val stitched = buildStitched(source, topStrip, bottomStrip)
            if (stitched !== source) {
                topStrip?.recycle()
                bottomStrip?.recycle()
            }
            timer.mark("start", "src=${source.width}x${source.height} topH=$topH botH=$botH mode=$renderMode")
            try {
                return translateStitched(
                    stitched,
                    source,
                    topH,
                    botH,
                    incomingCross,
                    timer,
                    renderMode,
                    allowIncomingMerge,
                    onPhase
                )
            } finally {
                if (stitched !== source && !stitched.isRecycled) stitched.recycle()
                if (!source.isRecycled) source.recycle()
            }
        } catch (err: Throwable) {
            timer.mark("render_fail", "reason=${err.message?.take(80)}")
            Log.w(TAG, "translateImage failed: ${err.javaClass.simpleName}: ${err.message}")
            topStrip?.recycle()
            bottomStrip?.recycle()
            incomingCross.forEach { part -> runCatching { part.patchBitmap.recycle() } }
            if (!source.isRecycled) source.recycle()
            return Outcome(0, 0, 0, "处理失败：${err.message?.take(60) ?: "未知错误"}", null)
        }
    }

    private suspend fun translateStitched(
        stitched: Bitmap,
        source: Bitmap,
        topH: Int,
        botH: Int,
        incomingRaw: List<CrossBubblePart>,
        timer: TranslationTimer,
        renderMode: BrowserRenderMode,
        allowIncomingMerge: Boolean,
        onPhase: (String) -> Unit = {}
    ): Outcome {
        val settings = ServiceLocator.settingsRepository.getSettings()
        // 「inpaint 清理图」选项（浏览器设置，默认开）：整页 inpaint 出的背景连续一致，
        // 观感比逐气泡贴片好；代价是多一次整页 inpaint，故做成可关的选项。
        val inpaintClean = ServiceLocator.settingsRepository.isBrowserInpaintCleanEnabled()
        // loading 框的阶段文案（单一来源在这里，见 WebtoonPhase）：UI 只负责显示。
        onPhase(WebtoonPhase.RECOGNIZING)
        val ocrResult = try {
            detectBubbles(
                stitched,
                settings,
                timer,
                bottomLimit = (topH + source.height).toFloat(),
                topLimit = topH.toFloat()
            )
        } catch (err: Throwable) {
            timer.mark("response_fail", "stage=ocr reason=${err.message?.take(80)}")
            incomingRaw.forEach { part -> runCatching { part.patchBitmap.recycle() } }
            return Outcome(0, 0, 0, "识别失败：${err.message?.take(60) ?: "未知错误"}", null)
        }
        val srcHeightF = source.height.toFloat()
        val topLimit = topH.toFloat()
        val bottomLimit = topH + srcHeightF
        // 下条带是否不够装跨缝内容（检测到就在返回时告诉调用方，由它加高条带重跑一次）。
        var stripTruncated = false

        // 分片接缝（裁切线）文字归属 + 「半截字」配对，对齐 C:\homework\translator 的 seam 处理：
        // - 整块跨下边界 → 本图（cross，剩余部分交给下一张继续画）；
        // - 整块跨上边界 → 上一张图（本图丢弃，避免重复翻译）；
        // - OCR 把被劈开的一行识别成上下两块时，按参考项目判据（水平重叠/行高比/间隙/合并高度
        //   + 句子连续性）配成一句完整台词 → 解决「半截字 / 缺字 / 跨页断句」。
        // 行高先算（收到侧交叉判据也要用，见 filterContestedIncoming）。
        val detections = ocrResult.bubbles.mapNotNull { bubble ->
            val outer = percentRectToPx(BubbleGeometry.outerRect(bubble), stitched.width, stitched.height)
            if (outer.width() <= 1f || outer.height() <= 1f) return@mapNotNull null
            val content = percentRectToPx(BubbleGeometry.contentRect(bubble), stitched.width, stitched.height)
            SeamDetection(bubble, outer.toSeamBox(), content.toSeamBox())
        }
        val topBand = topH.toFloat()
        val bottomBand = botH.toFloat()
        val medianLineHeight = SeamTuning.medianLineHeight(detections)

        // 收到侧交叉判定（2026-10-03）：本图在移交气泡的目标区域**另有检出、且那检出伸出了
        // 移交框之外** → 说明这一带不只是「上一张那半句」，移交框不该盖上来。
        // 真机 `[32317270]`：移交框只覆盖本图那份检出的 34%，白框中文于是糊在本图另一个气泡上，
        // 同时本图那半行又被当成「上一张已画过」丢掉（既不擦也不翻）。
        // 必须在 `planSeamOwnership` **之前**判：归属判定正是用这批框做「上一张画过没有」的依据。
        val incomingCross = filterContestedIncoming(
            parts = incomingRaw,
            localBubbles = ocrResult.bubbles,
            stitched = stitched,
            topH = topH,
            sourceHeight = source.height,
            lineHeightPx = medianLineHeight,
            timer = timer
        )

        val maxCrossHeight = SeamTuning.maxCrossHeight(topBand, bottomBand, medianLineHeight)
        val edgeWindow = SeamTuning.resolveEdgeWindow(medianLineHeight)
        // 「切开余量」= A 能看到的范围（条带），不是「半行」—— 缝落在第 2 行之后时，
        // 气泡的第 3/4 行本来就在下一张图上，截短它会两边各翻一遍（见 resolveCutAllowance 注释）。
        val cutAllowance = if (bottomBand > 0.5f) {
            SeamTuning.resolveCutAllowance(medianLineHeight, bottomBand)
        } else {
            0f
        }
        val seamConfig = SeamConfig(
            topLimitPx = topLimit,
            bottomLimitPx = bottomLimit,
            topBandPx = topBand,
            bottomBandPx = bottomBand,
            edgeWindowPx = edgeWindow,
            maxCrossHeightPx = maxCrossHeight,
            cutAllowancePx = cutAllowance,
            // 条带边缘的残片（可见高度不足 0.4 行）不认领、不移交 —— 真机实测它们只产出
            // 「25px 高的假气泡 / 43px 的薄盖板 + 残片译文」（`용살가`）。
            fragmentHeightPx = SeamTuning.resolveFragmentHeight(medianLineHeight)
        )
        val seamPlan = planSeamOwnership(
            detections = detections,
            config = seamConfig,
            // ⚠️ 坐标系必须统一（2026-10-03 真机抓到）：
            // `detections` 的框是**拼接图**像素（0 = 上条带顶端），而 `CrossBubblePart.contentRect`
            // 是**本图**像素（0 = 本图顶端，顶边可为负）。以前直接把 contentRect 当拼接图坐标传进去，
            // 整块就少了 topH 的偏移 —— 于是「上一张图已经移交」的判定偏了一个条带高度：
            // 重叠率被算小 → 本该被抑制的下半块被判成「没人管」→ 本图又翻一遍（重影）。
            // 实测：`topH=129` 时可见部分重叠 96% 被算成 56%（阈值 60%），`d1=orphan_adopted`。
            incomingCrossBoxes = incomingCross.map { part ->
                SeamBox(
                    left = part.contentRect.left,
                    top = part.contentRect.top + topH,
                    right = part.contentRect.right,
                    bottom = part.contentRect.bottom + topH
                )
            },
            canvasWidth = stitched.width,
            canvasHeight = stitched.height
        )
        // ⚠️ 这里**不再**对 owned 气泡做整体外扩（2026-09-30）。
        //
        // 历史实现把「排版框外扩」（内容框朝外框方向扩 30%）提前到视觉预处理之前，于是
        // **擦字贴片、清理图 mask、实底填充范围、倾角估计窗口**全都跟着一起外扩：
        //   1. 实底填充变成「外扩框 ∪ 气泡外框」的大矩形 → 用户反馈「扩张太大、超出气泡框」；
        //   2. ML Kit 下倾角估计窗口扩到气泡描边上 → 描边被当成墨迹，估计出乱七八糟的角度、
        //      最小外接矩形把整个气泡圈进去 → 遮罩位置偏、看着像「清理图没了」。
        // 现在只保留「文字排版」需要的那点余量，而且在 [resolveFinalBubbles] 里用像素空间
        // 单独算（从**字形紧包围框**朝气泡方向扩），遮罩/擦字/倾角一律贴着原字形。
        val ownedRaw = seamPlan.owned
        // 「读不出就别动」（2026-09-30 用户要求）：OCR 读不出可覆盖内容的区域（空 /
        // 极短纯符号 / 极短混合文字系统碎片，判据见 EraseTargets.kt）在这里**整块丢弃** ——
        // 不擦（白块/乱码）、不翻译、不渲染、不移交下一张（原文原样保留，读者无损）。
        // 必须在 owned 源头丢：下游清理图/贴片/场景/接缝全部以 owned 为准，
        // 源头一致才能保证「要么擦掉并盖上译文，要么原样保留」，没有中间态。
        val ownedWorth = ownedRaw.filter { isWorthErasing(it.bubble) }
        if (ownedWorth.size < ownedRaw.size) {
            timer.mark(
                "erase_skip",
                "dropped=${ownedRaw.size - ownedWorth.size} kept=${ownedWorth.size} skipped_text=" +
                    ownedRaw.filterNot { isWorthErasing(it.bubble) }
                        .joinToString(separator = " ") { "[${it.bubble.originalText}]" }
            )
        }
        // 收到侧吸收（2026-10-03 同行碎片 / 2026-10-11 相邻行）：把「与移交框同属一个气泡的
        // 本图检出」并进移交框 —— 同行碎片只扩框（译文上一张已有），相邻行还要把原文并进
        // 本次的翻译请求（见 [absorbIncomingFragments]）。
        //
        // 现场（真机 `[5467219]`）：移交框来自**上一张图**的字形轮廓，本图实际字形比它大/更靠外，
        // 而且本图自己的 OCR 会把同一行读成碎片甚至乱码（`1드하데 거리기라`，框高 <1% = 退化框）：
        //   · 移交框只盖住一部分 → 边缘**露字**（截图里的 `밖에 …!!`）；
        //   · 那条碎片既不擦也不盖 → 乱码留在画面上（截图里那行）。
        // 并进同一个框之后：一块盖板 + 一份译文，把整行盖住；碎片不再单独翻译（避免重复）。
        // 翻译能力判定提前到这里：没有翻译引擎时相邻行不并回（并回只会得到原文回退，
        // 不如保持旧行为让那行自己走一遍「翻译=原文」）。
        val engine = com.manga.translator.store.TranslateEngine.fromId(settings.translateEngine)
        val useLocalEngine = engine == com.manga.translator.store.TranslateEngine.ML_KIT &&
            ServiceLocator.localTranslator.isSupported(settings.sourceLanguage, settings.targetLanguage)
        val missingCloudConfig = settings.translateBaseUrl.isBlank() || settings.translateApiKey.isBlank()
        val absorption = absorbIncomingFragments(
            parts = incomingCross,
            owned = ownedWorth,
            stitchedWidth = stitched.width,
            stitchedHeight = stitched.height,
            topH = topH,
            sourceHeight = source.height,
            lineHeightPx = medianLineHeight,
            // ① 没有翻译引擎时并回只会得到原文回退；② 有接缝带瞄准本图时并回的译文会被
            // 接缝带派发摘掉（见 [translateImage.allowIncomingMerge]）—— 两种情况都不并。
            mergeAdjacentLines = allowIncomingMerge && !(missingCloudConfig && !useLocalEngine)
        )
        val incomingFragments = absorption.eraseFragmentBoxes
        val absorbedOwned = absorption.absorbedOwnedIndices
        // 框按文本行数向下扩（2026-10-11 真机 [Lezhin]）：OCR 的框经常比它装的文本**矮一半**
        // （现场 4 行台词框高只够 2 行：`box=60x6` 装 `케이지라고요!!⏎…⏎두 가지 밖에 없는데.!`）
        // → 擦除/清理图只擦上半，下半原文留在画面上（用户截图「清理图没了、原文露着」），
        // 跨缝块的移交框/接缝带也同样缺一截。扩框放在 prepareVisuals 之前：擦除、贴片、
        // 移交、接缝带全部继承同一个框；扩多的部分由墨迹精修收回（没字形就缩回），
        // 邻居框保护防止压进下方其它气泡。
        val ownedSource = if (absorbedOwned.isEmpty()) {
            ownedWorth
        } else {
            ownedWorth.filterIndexed { index, _ -> index !in absorbedOwned }
        }
        val owned = ownedSource.map { ownership ->
            ownership.copy(
                bubble = growBubbleToTextHeight(
                    bubble = ownership.bubble,
                    others = ownedSource.map { it.bubble },
                    stitchedWidth = stitched.width,
                    stitchedHeight = stitched.height,
                    lineHeightPx = medianLineHeight
                )
            )
        }
        if (absorbedOwned.isNotEmpty()) {
            timer.mark(
                "incoming_absorbed",
                "absorbed=${absorbedOwned.size} same_line=" +
                    (absorbedOwned.size - absorption.adjacentByPart.values.sumOf { it.size }) +
                    " adjacent=" + absorption.adjacentByPart.values.sumOf { it.size } +
                    " parts=${incomingFragments.size} " +
                    "texts=" + absorbedOwned.sorted().joinToString(" ") { index ->
                        "[${ownedWorth[index].bubble.originalText.take(10)}]"
                    }
            )
        }
        // 相邻行并回（Todolist §30「未做 #1」，对齐参考项目 `buildSeamExtendedBubbles`）：
        // 被吸收行的**原文**并进移交气泡的原文（按纵向排序），整句重新翻译一次；译出后回填
        // 移交气泡（`translatedText`），框扩到并集（`contentRect` / `sourceBoxPercent`）。
        // 这样「缝落在行间」的气泡在收到侧也是**一块盖板 + 一份完整译文**，而不是上下两块
        // 各翻各的（断行对不上、盖板接缝处叠一层 —— 用户说的「多余的残影」就是这类）。
        // 翻译请求在下面统一发出：`owned` 的原文在前、合并原文追加在后，结果按下标切回。
        data class MergedIncomingRequest(val partIndex: Int, val mergedOriginal: String, val union: RectF)
        val mergedRequests = incomingCross.mapIndexed { partIndex, part ->
            val fragments = absorption.adjacentByPart[partIndex].orEmpty()
            if (fragments.isEmpty()) return@mapIndexed null
            val union = RectF(part.contentRect)
            fragments.forEach { fragment ->
                if (fragment.box.left < union.left) union.left = fragment.box.left
                if (fragment.box.top < union.top) union.top = fragment.box.top
                if (fragment.box.right > union.right) union.right = fragment.box.right
                if (fragment.box.bottom > union.bottom) union.bottom = fragment.box.bottom
            }
            MergedIncomingRequest(
                partIndex = partIndex,
                mergedOriginal = SeamIncomingGeometry.mergeAdjacentLineText(
                    partText = part.originalText,
                    partTop = part.contentRect.top,
                    fragments = fragments
                ),
                union = union
            )
        }.filterNotNull()
        if (owned.isEmpty() && incomingCross.isEmpty()) {
            timer.mark("render_done", "bubbles=0 detected=${seamPlan.detected}")
            return Outcome(0, 0, 0, null, null)
        }
        timer.mark(
            "slice_classify",
            "detected=${seamPlan.detected} owned=${owned.size} cross=${owned.count { it.cross }} " +
                "paired_top=${seamPlan.pairedTopSeam} paired_bottom=${seamPlan.pairedBottomSeam} " +
                "dropped_neighbor=${seamPlan.droppedNeighbor} suppressed_incoming=${seamPlan.suppressedByIncoming} " +
                "orphan_adopted=${seamPlan.orphanAdopted} handed_tail=${seamPlan.handedTail} " +
                "capped_cross=${seamPlan.cappedCross} " +
                "incoming=${incomingCross.size} edge_window=${edgeWindow.toInt()} " +
                "max_cross_h=${maxCrossHeight.toInt()} cut_allow=${cutAllowance.toInt()}"
        )
        // 缝归属诊断（2026-09-30）：每条 `outerT~outerB/contentT~contentB`，用来核对
        // 「跨下边界判定」用的是哪一个框 —— 误判会让普通气泡被当成跨图气泡。
        timer.mark(
            "seam_boxes",
            "top=$topLimit bottom=$bottomLimit " + detections.mapIndexed { index, detection ->
                val b = detection.bubble
                "d$index=${detection.outer.top.toInt()}~${detection.outer.bottom.toInt()}" +
                    "/${detection.content.top.toInt()}~${detection.content.bottom.toInt()}" +
                    "/ocr=(${b.contentX.toInt()},${b.contentY.toInt()},${b.contentW.toInt()},${b.contentH.toInt()})"
            }.joinToString(" ")
        )
        // 归属判决逐条可见（2026-10-03）：出问题时必须能一眼回答「这个框到底被算成了什么」——
        // 历史实现只有汇总计数（`owned=N dropped=M`），分不清是哪个框被丢。
        // 下标与 `seam_boxes` 的 dN 一一对应。
        timer.mark("seam_verdict", seamPlan.verdicts.mapIndexed { index, v -> "d$index=$v" }.joinToString(" "))
        // 条带是否够长（2026-10-03）：跨缝框的底边一旦**顶到拼接图底边**，说明下条带里的
        // 内容被切在条带边界上 —— OCR 拿到的是被截断的半句（真机实测 `…접속게이트로 돌`，
        // 译文因此串味），而且移交深度不够 → 下一张图又会认领一次（缝上两层译文）。
        // 这里只负责**报告**；加高条带重跑由调用方（会话）决定，见 [Outcome.stripTruncated]。
        run {
            val stitchedBottom = stitched.height.toFloat()
            val truncated = detections.withIndex()
                .filter { (_, d) -> d.outer.bottom >= stitchedBottom - 1.5f }
            if (truncated.isNotEmpty()) {
                timer.mark(
                    "seam_strip_short",
                    "count=${truncated.size} band=${botH}px stitched_h=${stitched.height} :: " +
                        truncated.take(4).joinToString(" ") { (_, d) ->
                            "[${d.bubble.originalText.replace('\n', '⏎').take(14)}]"
                        }
                )
                // 只有「跨下边界、由本图认领」的那些需要加高条带：条带里邻居自己的正文
                // （handed_tail）本来就该留在下一张图上，不触发重跑。
                val crossTruncated = truncated.any { (index, _) ->
                    seamPlan.verdicts.getOrNull(index)?.startsWith("cross") == true
                }
                if (crossTruncated) stripTruncated = true
            }
        }
        // 句中截断的第二形态（2026-10-11 真机 [Lezhin]）：框**没**顶到拼接图底边，但框底
        // 停在条带深处（现场：`…돌아가거나⏎두 가`，框底距拼接图底边 54px ≈ 1 行半）——下一行
        // 只进了半截就被条带边缘切掉。移交过去就只有半句，缝下剩下的原文（本图 OCR 又没
        // 检出来）没人擦没人翻。
        // 判据（纯几何，2026-10-11 第二版）：跨缝气泡的框伸进了下条带、但框底距拼接图底边
        // 不足 2 行 —— 条带明明还有内容而框停住了，大概率是 OCR 把切在句中的那行截掉了
        // （末行不完整时框不会包到条带底边）。第一版曾要求「句尾无标点」，但真机日志的
        // `detected_texts` 是 take(22) 截断显示，尾部有没有标点根本看不出来，条件形同虚设。
        // 误报的代价只是一次重跑（结果只会更完整），漏报的代价是缝下留原文。
        run {
            val stitchedBottom = stitched.height.toFloat()
            val lineHeight = medianLineHeight.coerceAtLeast(24f)
            val nearEdgeFloor = stitchedBottom - 2f * lineHeight
            val candidates = detections.withIndex().filter { (index, d) ->
                d.outer.bottom >= nearEdgeFloor && d.outer.bottom < stitchedBottom - 1.5f &&
                    seamPlan.verdicts.getOrNull(index)?.startsWith("cross") == true
            }
            if (candidates.isNotEmpty()) {
                timer.mark(
                    "seam_strip_sentence_cut",
                    "band=${botH}px floor=${nearEdgeFloor.toInt()} :: " +
                        candidates.take(3).joinToString(" ") { (_, d) ->
                            "[${d.bubble.originalText.replace('\n', '⏎').take(16)}]" +
                                "@${d.outer.bottom.toInt()}"
                        }
                )
                stripTruncated = true
            }
        }
        // 每张图**认出了哪些文字**（去换行、截断到 22 字）：用户报「某句没翻译」时，
        // 第一步必须能回答「OCR 到底看没看到它」。之前只能从 erase_target 反推，
        // 而那条只覆盖「参与擦字」的气泡 —— 分组阶段被丢掉、或被并入别的块的那些句子
        // 在那里根本不可见（2026-09-30 用户给的 `햇빛을 받으면 반짝반짝 빛나고…` 就是这么丢的）。
        timer.mark(
            "detected_texts",
            detections.take(10).joinToString(" ˌ ") { detection ->
                detection.bubble.originalText.replace('\n', '⏎').take(22)
            }
        )

        val pageHash = withContext(ioDispatcher) { ServiceLocator.eraseProcessor.buildPageHash(stitched) }
        // OCR 已经出结果，接下来是「翻译 + 擦字/颜色预处理」（两者并行）。
        onPhase(WebtoonPhase.TRANSLATING)

        var warning: String? = null
        var cacheStats = TranslationCacheStats(0, 0, 0, 0, 0)
        var merged: TranslationResult? = null
        var prep: ImageVisualPrep? = null
        // 上一张图移交的跨图气泡：**先让本图的清理图/擦字 mask 把它擦掉**（2026-09-30）。
        // 这样接缝下半截的背景来自本图自己的清理图（与整页同一张纹理），不再需要
        // 「从上一张图裁一块贴片贴过来」—— 那是「上下各一层清理图」和贴片错位的来源。
        val crossPrepBubbles = crossPrepareBubbles(
            incomingCross = incomingCross,
            stitched = stitched,
            topH = topH,
            sourceHeight = source.height,
            fragmentBoxes = incomingFragments,
            adjacentFragmentBoxes = absorption.adjacentFragmentBoxes,
            lineHeightPx = medianLineHeight
        )
        if (crossPrepBubbles.isNotEmpty()) {
            // 明细（2026-10-03）：把「本图到底要求擦哪一块」打出来 —— 用户截图里
            // 「跨缝气泡下面那个气泡被擦掉一半」时，这一行是判断误擦的唯一直证据。
            val detail = crossPrepBubbles.joinToString(" ") { b ->
                val left = b.contentX / 100f * stitched.width
                val top = (b.contentY / 100f * stitched.height) - topH
                val right = (b.contentX + b.contentW) / 100f * stitched.width
                val bottom = ((b.contentY + b.contentH) / 100f * stitched.height) - topH
                "[${left.toInt()},${top.toInt()}~${right.toInt()},${bottom.toInt()}]"
            }
            timer.mark("cross_prep", "incoming=${incomingCross.size} added=${crossPrepBubbles.size} px=$detail")
        }
        coroutineScope {
            // 擦字/颜色/内容框与翻译请求并行（与悬浮球链路同策略，减少总等待）。
            val prepDeferred = async(ioDispatcher) {
                prepareVisuals(
                    result = TranslationResult(owned.map { it.bubble } + crossPrepBubbles),
                    source = stitched,
                    pageHash = pageHash,
                    timer = timer,
                    includeSolidInpaint = inpaintClean
                )
            }
            val glossaryEntries = ServiceLocator.glossaryRepository.activeGlossaryEntries()
            // 翻译请求 = owned 的原文 + 相邻行并回的合并原文（追加在尾部，结果按下标切回；
            // `resolveTranslations` 按列表顺序逐条返回，顺序/条数都与入参一致）。
            val requestBubbles = owned.map { it.bubble } + mergedRequests.map { request ->
                val union = request.union
                val stitchedW = stitched.width.toFloat()
                val stitchedH = stitched.height.toFloat()
                val xPct = (union.left / stitchedW * 100f).coerceIn(0f, 100f)
                val wPct = (union.width() / stitchedW * 100f).coerceIn(0f, 100f)
                val yPct = ((union.top + topH) / stitchedH * 100f).coerceIn(0f, 100f)
                val hPct = (union.height() / stitchedH * 100f).coerceIn(0f, 100f)
                Bubble(
                    x = xPct,
                    y = yPct,
                    w = wPct,
                    h = hPct,
                    bgType = if (incomingCross[request.partIndex].narration) "none" else "solid",
                    // 合并原文只在翻译请求里用；几何是陪伴字段（缓存/日志可读）。
                    originalText = request.mergedOriginal,
                    translatedText = "",
                    contentX = xPct,
                    contentY = yPct,
                    contentW = wPct,
                    contentH = hPct
                )
            }
            if (mergedRequests.isNotEmpty()) {
                timer.mark(
                    "incoming_merge_request",
                    "count=${mergedRequests.size} texts=" + mergedRequests.joinToString(" ") { request ->
                        "[${request.mergedOriginal.replace('\n', '⏎').take(24)}]"
                    }
                )
            }
            val translateKey = translationResolver.buildTranslateKey(
                texts = requestBubbles.map { it.originalText },
                settings = settings,
                glossaryRevision = ServiceLocator.glossaryRepository.activeRevision()
            )
            if (missingCloudConfig && !useLocalEngine) {
                warning = "仅OCR（未配置翻译API）"
                timer.mark("translate_response", "skipped=missing_translate_api")
                merged = TranslationResult(
                    requestBubbles.map { it.copy(translatedText = it.originalText) }
                )
            } else {
                translationResolver.resolveTranslations(
                    bubbles = requestBubbles,
                    settings = settings,
                    translateKey = translateKey,
                    pageHash = pageHash,
                    glossaryEntries = glossaryEntries,
                    timer = timer,
                    onTranslatorDownloading = null
                ).let { outcome ->
                    warning = outcome.warningMessage
                    cacheStats = outcome.cacheStats
                    merged = outcome.result
                }
            }
            prep = prepDeferred.await()
        }

        val finalPrep = prep ?: throw IllegalStateException("visual prep missing")
        val cleanupIssue = describeCleanupIssue(inpaintClean, finalPrep)
        if (cleanupIssue != null) {
            warning = listOfNotNull(warning, cleanupIssue).joinToString(" ｜ ")
        }
        // 结果切回：前 owned.size 条是本图自己的气泡，尾部是相邻行并回的合并译文。
        // ⚠️ finalBubbles 必须与 owned 一一对应（geometries/crossFlags 都按下标对齐）。
        val mergedAll = merged?.bubbles.orEmpty()
        val finalBubbles = mergedAll.take(owned.size)
        // 相邻行并回（2026-10-11）：合并译文回填移交气泡，框扩到「移交框 ∪ 被吸收行」的并集。
        //
        // 为什么连框一起扩：移交框只来自上一张图看得到的那段，被吸收行在它下面 —— 框不扩，
        // 译文按旧框排版，并进来的那行会落在框外（渲染层裁掉 = 等于没翻）。
        // contentRect 是本图像素（顶边可为负），sourceBoxPercent 是「上一张图百分比」：
        // y% 的口径是 100 = 上一张图底边（= 本图顶边），渲染层换算时上一张图的高度会消掉，
        // 所以这里直接用**本图**的宽高折算即可（推导见 buildScene 的 percentBox 换算）。
        // 字号刻意不动：接缝两侧必须同字号（上一张那半已按 fontSizePx 渲染），合并文本
        // 多出的行由并集框装下；万一溢出，落点也是被清理图擦过的背景，不会压到原文。
        val incomingFinal = if (mergedRequests.isEmpty()) {
            incomingCross
        } else {
            val updated = incomingCross.toMutableList()
            var applied = 0
            mergedRequests.forEachIndexed { k, request ->
                val translated = mergedAll.getOrNull(owned.size + k)?.translatedText?.trim().orEmpty()
                if (translated.isBlank()) return@forEachIndexed
                val srcWf = source.width.toFloat().coerceAtLeast(1f)
                val srcHf = source.height.toFloat().coerceAtLeast(1f)
                updated[request.partIndex] = updated[request.partIndex].copy(
                    translatedText = translated,
                    contentRect = RectF(request.union),
                    sourceBoxPercent = floatArrayOf(
                        request.union.left / srcWf * 100f,
                        100f + request.union.top / srcHf * 100f,
                        request.union.width() / srcWf * 100f,
                        request.union.height() / srcHf * 100f
                    )
                )
                applied += 1
            }
            timer.mark(
                "incoming_merge",
                "requests=${mergedRequests.size} applied=$applied texts=" +
                    mergedRequests.joinToString(" ") { request ->
                        val index = owned.size + mergedRequests.indexOf(request)
                        "[${mergedAll.getOrNull(index)?.translatedText?.take(16).orEmpty()}]"
                    }
            )
            updated
        }
        val crossFlags = owned.map { it.cross }
        // 译文的「擦字 + 画字」准备与场景/成图渲染（loading 框的最后一段）。
        onPhase(WebtoonPhase.RENDERING)

        // 最终几何**只算一次**：场景渲染与「移交下一张图」共用同一份（2026-09-30）。
        // 历史实现里两处各算一遍：本图渲染用「墨迹收紧后的框」，而移交给下一张图的却是
        // 「未收紧的内容框」→ 接缝两侧的框/字号/倾角都对不上（用户反馈「seam 图定位不准」）。
        val geometries = withContext(ioDispatcher) {
            resolveFinalBubbles(
                stitched = stitched,
                prep = finalPrep,
                bubbles = finalBubbles,
                crossFlags = crossFlags,
                srcW = source.width,
                srcH = source.height,
                topH = topH
            )
        }
        pendingTiltProbe?.let { probe ->
            timer.mark("ink_tilt", probe)
            pendingTiltProbe = null
        }

        // 覆盖层模式：把「擦掉原文 → 画上译文」下发给 WebView 的 DOM 渲染层
        // （场景 = 百分比几何 + 颜色 + 原字高 + 清理图 URL）；整图模式：照旧渲染成图 PNG。
        // 场景构建失败（序列化/清理图导出异常）→ 立即回退整图渲染，这张图不能没有译文。
        var scene: BrowserSceneBuilder.Scene? = null
        var png: ByteArray? = null
        if (renderMode == BrowserRenderMode.DOM_SCENE) {
            scene = runCatching {
                withContext(ioDispatcher) {
                    buildScene(source, topH, finalBubbles, geometries, finalPrep, incomingFinal, crossFlags)
                }
            }.getOrElse { err ->
                timer.mark("render_scene_fail", "reason=${err.javaClass.simpleName}:${err.message?.take(60)}")
                null
            }
            timer.mark(
                "render_scene",
                if (scene != null) {
                    // cleaned = 场景带整页清理图；patches = 跨图续排的贴片盖板数；
                    // flat = 用**不透明实底**盖住原文的气泡数（关闭「精细擦字」时旁白走这条）。
                    "owned=${finalBubbles.size} incoming=${incomingFinal.size} " +
                        "cleaned=${scene.cleanedUrl != null} " +
                        "patches=${scene.bubbles.count { it.patchUrl != null }} " +
                        "flat=${scene.bubbles.count { it.flatCover }}"
                } else {
                    "fallback=full_png"
                }
            )
        }
        if (scene == null) {
            val renderResult = withContext(ioDispatcher) {
                renderSlice(source, topH, finalBubbles, finalPrep, incomingFinal, geometries)
            }
            val output = renderResult.first
            // 字号诊断：`字号/原文行高`，核对「译文字号是否和原文一致」（用户 2026-09-28 要求）。
            timer.mark("render_font", renderResult.second)
            png = withContext(ioDispatcher) {
                val bos = ByteArrayOutputStream()
                output.compress(Bitmap.CompressFormat.PNG, 100, bos)
                bos.toByteArray()
            }
            output.recycle()
        }

        // 跨图气泡的剩余部分：换算到下一张图坐标系（y -= bottomLimit），裁出属于下一张图的贴片。
        //
        // ⚠️ 顺序关键：必须先做 crossOut、再回收贴片。历史实现把贴片回收放在这之前，
        // 于是 crossOut 拿到的 patch.bitmap 已经 isRecycled → 直接跳过、crossOut 永远为空，
        // 「跨页/裁切线切断的台词下半句交给下一张图继续画」这条链路**从未真正生效**
        // （2026-09-28 用 cross_out_probe 定位：probe=i0:recycled）。
        val crossOut = mutableListOf<CrossBubblePart>()
        val crossProbe = StringBuilder()
        // 接缝覆盖层（2026-09-30）：同一条缝上的跨图气泡打包成一个场景（一块盖板 + 一份译文）。
        // 只收「本图场景与下一张图场景都真的有这个气泡」的那些（两半都能被摘掉），
        // 所以收集点就在 crossOut 真正追加成功的那一刻。
        val seamEntries = mutableListOf<SeamBubbleGeometry>()
        if (botH > 0) {
            finalBubbles.forEachIndexed { index, bubble ->
                if (crossFlags.getOrNull(index) != true) return@forEachIndexed
                val geometry = geometries.getOrNull(index)
                val patch = finalPrep.patchFor(index)
                if (geometry == null) {
                    crossProbe.append("i$index:no_geom ")
                    return@forEachIndexed
                }
                val colors = finalPrep.colors[index] ?: BubbleColors(Color.BLACK, Color.WHITE)
                val narration = !bubble.bgType.equals("solid", ignoreCase = true)
                // 字号：用几何里已经算好的那一份（与场景下发的 fixed_font_percent 同源）。
                val fontSize = geometry.fixedFontPx
                if (fontSize == null || patch == null || patch.bitmap.isRecycled) {
                    crossProbe.append("i$index:no_patch ")
                    return@forEachIndexed
                }
                // 译文回退成原文（翻译失败/乱码原文）的跨缝块不移交、不进接缝带（2026-10-11）：
                // 接缝带会把它当「译文」整块画上去 —— 用户看到的就是乱码原文印在面板上
                // （现场 `1드하데 거리기라→1드하데 거리기라`）。跳过后该区域由下一张图自己的
                // 检出兜底，比盖一层乱码好。OCR-only 模式（本来就拿原文当译文）不适用。
                val untranslated = !(missingCloudConfig && !useLocalEngine) &&
                    (bubble.translatedText.isBlank() ||
                        bubble.translatedText.trim() == bubble.originalText.trim())
                if (untranslated) {
                    crossProbe.append("i$index:untranslated ")
                    return@forEachIndexed
                }
                val subTop = maxOf(patch.srcRect.top, bottomLimit)
                val subBottom = minOf(patch.srcRect.bottom, stitched.height.toFloat())
                val patchPart = if (subBottom - subTop >= 2f) {
                    val offsetY = (subTop - patch.srcRect.top).roundToInt().coerceIn(0, patch.bitmap.height - 1)
                    val partHeight = (subBottom - subTop).roundToInt()
                        .coerceAtMost(patch.bitmap.height - offsetY)
                    if (partHeight < 1) {
                        null
                    } else {
                        runCatching {
                            Bitmap.createBitmap(patch.bitmap, 0, offsetY, patch.bitmap.width, partHeight)
                        }.getOrNull()
                    }
                } else {
                    null
                }
                if (patchPart == null) {
                    crossProbe.append(
                        "i$index:sub=${subTop.toInt()}~${subBottom.toInt()} src=${patch.srcRect.top.toInt()}~${patch.srcRect.bottom.toInt()} "
                    )
                    return@forEachIndexed
                }
                crossProbe.append("i$index:ok ")
                // 框 / 四边形 / 字号全部取**最终几何**（本图场景用的就是它，两图因此严格一致）。
                val contentFull = geometry.layoutRect
                // 接缝覆盖层的 id 对应关系（必须在追加之前取 crossOut.size —— 它就是本项
                // 在下一张图场景里的 incoming 下标）。
                seamEntries += SeamBubbleGeometry(
                    seamBubbleId = "s${seamEntries.size}",
                    ownBubbleId = "b$index",
                    nextBubbleId = "c${crossOut.size}",
                    bubble = bubble,
                    colors = colors,
                    layoutRect = geometry.layoutRect,
                    coverBox = SeamBandGeometry.sourcePercentToStitched(
                        geometry.coverBoxPercent,
                        source.width.toFloat(),
                        source.height.toFloat(),
                        topH.toFloat()
                    ) ?: SeamBandGeometry.Box(
                        geometry.maskRect.left,
                        geometry.maskRect.top,
                        geometry.maskRect.right,
                        geometry.maskRect.bottom
                    ),
                    maskPolygon = SeamBandGeometry.sourcePercentPointsToStitched(
                        geometry.maskPolygonPercent,
                        source.width.toFloat(),
                        source.height.toFloat(),
                        topH.toFloat()
                    ),
                    regionPolygon = SeamBandGeometry.sourcePercentPointsToStitched(
                        geometry.regionPolygonPercent,
                        source.width.toFloat(),
                        source.height.toFloat(),
                        topH.toFloat()
                    ),
                    lineHeightPx = geometry.lineHeightPx,
                    fixedFontPx = geometry.fixedFontPx
                )
                crossOut += CrossBubblePart(
                    translatedText = bubble.translatedText.ifBlank { bubble.originalText },
                    originalText = bubble.originalText,
                    narration = narration,
                    colors = colors,
                    patchBitmap = patchPart,
                    patchDest = RectF(
                        patch.srcRect.left,
                        subTop - bottomLimit,
                        patch.srcRect.right,
                        subBottom - bottomLimit
                    ),
                    contentRect = RectF(
                        contentFull.left,
                        contentFull.top - bottomLimit,
                        contentFull.right,
                        contentFull.bottom - bottomLimit
                    ),
                    fontSizePx = fontSize,
                    alignment = bubble.alignment,
                    // ⚠️ boxPercent 是**拼接图**坐标系的百分比（分母 = 拼接高，含上/下条带），
                    // 而下一张图换算时按「上一张**本图**高」解（见 crossBubbles 的 topPx 公式，
                    // sourceHeightPx = source.height）。两者对「完全落在下条带里的框」会差出
                    // 一个坐标系：真机 gamesal 7/8 页，跨缝泡 c0 被换到页面外（-13.7%），下一张
                    // 「已被移交」的抑制判据（isHandedOverByIncoming，用 contentRect，是对的）
                    // 因此失配 → 缝上残行被下一张又认领又翻一遍，叠在接缝带译文上。
                    // 这里统一换算成**本图系**百分比（y 减 topH、y/h 分母换成 source 高），
                    // 与 contentRect / patchDest 同一口径。
                    sourceBoxPercent = geometry.boxPercent.let { pct ->
                        if (pct.size == 4 && stitched.height > 0 && source.height > 0) {
                            val topPx = pct[1] / 100f * stitched.height - topH
                            floatArrayOf(
                                pct[0],
                                topPx / source.height * 100f,
                                pct[2],
                                pct[3] * stitched.height / source.height
                            )
                        } else {
                            pct
                        }
                    },
                    sourceWidthPx = source.width,
                    sourceHeightPx = source.height,
                    // 移交下一张图的轮廓必须和**本图盖板**同源，否则接缝两侧会各画一块大小
                    // 不同的盖板 —— 用户看到的就是「上下都有一个层」。
                    //
                    // 2026-09-30 修：这里此前传 [FinalBubble.maskPolygonPercent]，而它只在
                    // **有倾角**（OCR 给四边形 / 墨迹估出倾角）时才有值，轴对齐气泡一律为 null。
                    // 于是轴对齐的跨图气泡在下一张图里退回拿 `x/y/w/h`（= 排版框 = 字形框每侧
                    // 再扩 15%）当盖板 → 比本图那块大 30%，肉眼就是「上面/下面两个盖板不一样宽」。
                    // [FinalBubble.regionPolygonPercent] 两种情形都有值且语义就是「字形 + 几像素」：
                    // 有倾角时是外扩后的四边形，轴对齐时是外扩后的矩形四角。
                    //
                    // y 同 sourceBoxPercent：从拼接系换算成上一张**本图系**（y 减 topH、分母换
                    // source 高）—— 下一张的 `flat[i] - 100f` 假设输入是本图系百分比。
                    sourcePolygon = geometry.regionPolygonPercent?.let { flat ->
                        if (flat.size == 8 && stitched.height > 0 && source.height > 0) {
                            List(flat.size) { i ->
                                if (i % 2 == 1) {
                                    (flat[i] / 100f * stitched.height - topH) / source.height * 100f
                                } else {
                                    flat[i]
                                }
                            }
                        } else {
                            flat
                        }
                    }
                )
            }
        }
        timer.mark(
            "cross_out_probe",
            "owned=${owned.size} bubbles=${finalBubbles.size} botH=$botH " +
                "flags=${owned.map { if (it.cross) 1 else 0 }} probe=${crossProbe.toString().trim()}"
        )
        // 接缝覆盖层：必须在清理图回收**之前**裁带导出（同一张纹理覆盖接缝上下两半）。
        val seam = if (renderMode == BrowserRenderMode.DOM_SCENE && seamEntries.isNotEmpty()) {
            withContext(ioDispatcher) {
                buildSeamScene(
                    entries = seamEntries,
                    stitchedWidth = stitched.width,
                    stitchedHeight = stitched.height,
                    cleanedImage = finalPrep.cleanedImage,
                    topH = topH,
                    srcW = source.width,
                    srcH = source.height
                )
            }
        } else {
            null
        }
        timer.mark(
            "seam_scene",
            if (seam == null) {
                "mode=none entries=${seamEntries.size}"
            } else {
                "mode=band bubbles=${seam.bubbles.size} " +
                    "band=${seam.bandWidthPx}x${seam.bandHeightPx}@${seam.bandLeftPx},${seam.bandTopPx} " +
                    "bg=${if (seam.cleanedUrl != null) "cleaned" else "solid"} " +
                    "own=${seam.ownBubbleIds} next=${seam.nextBubbleIds}"
            }
        )
        // 贴片只用于渲染与 crossOut 裁切，两者都完成后才能回收。
        finalPrep.patchesByBubbleId.values.forEach { patch ->
            if (!patch.bitmap.isRecycled) patch.bitmap.recycle()
        }
        incomingFinal.forEach { part -> runCatching { part.patchBitmap.recycle() } }
        // 清理图：整图模式在贴片里用完即弃；覆盖层模式 buildScene 已导出为 JPEG，
        // 这里统一回收（导出用的是裁剪副本，原图位图必须由本方法负责释放）。
        finalPrep.cleanedImage?.let { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        timer.mark(
            "render_done",
            "bubbles=${finalBubbles.size} incoming=${incomingFinal.size} crossOut=${crossOut.size} " +
                "seam=${seam?.bubbles?.size ?: 0} mode=$renderMode png=${png?.size ?: 0}B"
        )
        return Outcome(
            bubbleCount = finalBubbles.size + incomingFinal.size,
            cacheHitCount = cacheStats.hitCount,
            cacheTotalCount = cacheStats.total,
            warningMessage = warning,
            renderedPng = png,
            scene = scene,
            crossOut = crossOut,
            seam = seam,
            sceneDiag = sceneDiag,
            stripTruncated = stripTruncated
        )
    }

    /**
     * 一条接缝带上、单个气泡的**拼接图像素**几何。
     *
     * 之所以要单独存一份：带的位置要等所有跨缝气泡都收齐、并集算完才知道，
     * 而带内百分比必须在那之后才能换算（见 [buildSeamScene]）。
     */
    private class SeamBubbleGeometry(
        val seamBubbleId: String,
        val ownBubbleId: String,
        val nextBubbleId: String,
        val bubble: Bubble,
        val colors: BubbleColors,
        val layoutRect: RectF,
        val coverBox: SeamBandGeometry.Box,
        val maskPolygon: List<Float>?,
        val regionPolygon: List<Float>?,
        val lineHeightPx: Float,
        val fixedFontPx: Float?
    ) {
        /** 带必须覆盖的全部框（遮罩 + 轮廓 + 排版框：译文排版可能比字形框大一圈）。 */
        val boxes: List<SeamBandGeometry.Box> = listOfNotNull(
            coverBox,
            SeamBandGeometry.boundsOfPoints(maskPolygon),
            SeamBandGeometry.boundsOfPoints(regionPolygon),
            SeamBandGeometry.Box(layoutRect.left, layoutRect.top, layoutRect.right, layoutRect.bottom)
        )
    }

    /**
     * 把本图所有跨下边界的气泡打包成一条接缝带场景。
     *
     * 带 = 所有气泡「遮罩 ∪ 轮廓 ∪ 排版框」的并集，外扩几像素，上端夹在本图起点（topStrip 之后）
     * ——带的上端一旦伸进 topStrip，那些像素属于**上一张图**，摆到屏幕上会盖住上一张图的内容。
     *
     * 任一条不成立就返回 null，让调用方保留原来的两半渲染：
     * - 并集算不出合法矩形；
     * - 有复杂背景气泡（`bg_type=none`）但拿不到清理图 —— 没有纹理的盖板会让原文直接露出来，
     *   比两层纹理更糟。
     */
    private fun buildSeamScene(
        entries: List<SeamBubbleGeometry>,
        stitchedWidth: Int,
        stitchedHeight: Int,
        cleanedImage: Bitmap?,
        topH: Int,
        srcW: Int,
        srcH: Int
    ): SeamBandScene? {
        if (entries.isEmpty() || stitchedWidth <= 0 || stitchedHeight <= 0 || srcW <= 0 || srcH <= 0) return null
        val band = SeamBandGeometry.bandOf(
            boxes = entries.flatMap { it.boxes },
            padPx = SEAM_BAND_PAD_PX,
            stitchedWidth = stitchedWidth.toFloat(),
            limitTop = topH.toFloat(),
            stitchedHeight = stitchedHeight.toFloat()
        ) ?: return null
        val needsBackground = entries.any { !it.bubble.bgType.equals("solid", ignoreCase = true) }
        if (needsBackground && (cleanedImage == null || cleanedImage.isRecycled)) return null
        val cleanedUrl = if (needsBackground) {
            exportSeamBandImage(cleanedImage, band) ?: return null
        } else {
            null
        }
        val bubbles = entries.mapNotNull { entry -> buildSeamBubble(entry, band, cleanedImage) }
        if (bubbles.size != entries.size) return null
        return SeamBandScene(
            bubbles = bubbles,
            bandLeftPx = band.left.roundToInt(),
            bandTopPx = band.top.roundToInt(),
            bandWidthPx = band.width.roundToInt(),
            bandHeightPx = band.height.roundToInt(),
            topHPx = topH,
            sourceWidthPx = srcW,
            sourceHeightPx = srcH,
            cleanedUrl = cleanedUrl,
            ownBubbleIds = entries.map { it.ownBubbleId },
            nextBubbleIds = entries.map { it.nextBubbleId }
        )
    }

    /**
     * 单个跨缝气泡 → 带内百分比的气泡字段。
     *
     * 换算要点：**百分比之外的一切都按像素走**。带是把拼接图整块裁下来的一刀，带内百分比 ×
     * 带尺寸就精确还原拼接图像素，所以遮罩四边形、字号（`fixedFontPercent`）在带里与在本图
     * 场景里是同一个屏幕像素值 —— 两半摘掉之后位置/字号不会有任何跳动。
     */
    private fun buildSeamBubble(
        entry: SeamBubbleGeometry,
        band: SeamBandGeometry.Box,
        cleanedImage: Bitmap?
    ): BrowserSceneBuilder.SceneBubble? {
        val bandHeight = band.height
        if (bandHeight <= 0.5f || band.width <= 0.5f) return null
        val layout = SeamBandGeometry.percentBox(
            SeamBandGeometry.Box(
                entry.layoutRect.left,
                entry.layoutRect.top,
                entry.layoutRect.right,
                entry.layoutRect.bottom
            ),
            band
        )
        val solid = entry.bubble.bgType.equals("solid", ignoreCase = true)
        return BrowserSceneBuilder.SceneBubble(
            id = entry.seamBubbleId,
            x = layout[0], y = layout[1], w = layout[2], h = layout[3],
            originalText = entry.bubble.originalText,
            translatedText = entry.bubble.translatedText.ifBlank { entry.bubble.originalText },
            sourceLineCount = renderer.resolveSourceLineCount(entry.bubble.sourceLineCount, entry.bubble.originalText),
            vertical = entry.bubble.layout.equals("vertical", ignoreCase = true),
            alignment = TextAlignment.normalize(entry.bubble.alignment),
            bgType = if (solid) "solid" else "none",
            bgColorHex = BrowserSceneBuilder.colorToHex(entry.colors.bgColor),
            textColorHex = BrowserSceneBuilder.colorToHex(entry.colors.textColor),
            strokeColorHex = BrowserSceneBuilder.colorToHex(entry.colors.strokeColor),
            // 描边只看**这一块背景**是否多色（与页面上其它气泡同一条判据）。
            needsStroke = com.manga.translator.overlay.BackgroundOutlinePolicy.needsOutline(
                background = cleanedImage,
                rect = entry.layoutRect,
                bgType = if (solid) "solid" else "none",
                originalHadOutline = entry.colors.originalHadOutline
            ),
            fontHeightPercent = entry.lineHeightPx / bandHeight * 100f,
            polygon = entry.maskPolygon?.let { SeamBandGeometry.stitchedPointsToPercent(it, band) },
            rotationDeg = entry.bubble.rotationDeg,
            fillBox = null,
            regionPolygon = entry.regionPolygon?.let { SeamBandGeometry.stitchedPointsToPercent(it, band) },
            coverBox = SeamBandGeometry.percentBox(entry.coverBox, band),
            // 接缝带是**一个**渲染单元（一块盖板 + 一份译文），不存在「上下两页字号必须一致」
            // 的问题 → 不锁字号，交给渲染层用真实盒子 + 真实字体自己拟合（与页面上其它气泡
            // 完全是同一条路径，字号也就与它们同量级）。
            //
            // 反过来下发 `fixed_font_percent` 会引入两套口径：宿主按**图像像素**估可用宽度、
            // 渲染层按 **CSS 盒子**排版。差一点点 CJK 就折行，折行后文字层高度翻倍、译文溢出
            // 气泡（2026-09-30 用户反馈「虽然已经是一块了，但是字超出了原来的字体位置了」，
            // 修复前实测：盒子 266×59、内容 233×113、2 行）。
            //
            // 两半渲染（[CrossBubblePart]，接缝带不可用时的兜底）仍然锁字号 —— 那才是真的
            // 分居两张图、必须一致的场景。
            fixedFontPercent = null,
            // 跨缝：译文排版框允许越出带上边界（下一半本来就落在下一张图里）。
            stitchOverflow = true
        )
    }

    /**
     * 接缝带清理图：从**整页清理图**上裁出带那一条导出 JPEG。
     *
     * 与 [exportCleanedImage] 同一条 mtlocal 短 URL 通道（拦截后直接返回字节，避开 Chromium
     * 行内样式约 2MB 的静默上限）。
     */
    private fun exportSeamBandImage(cleanedImage: Bitmap?, band: SeamBandGeometry.Box): String? {
        val bitmap = cleanedImage ?: return null
        if (bitmap.isRecycled) return null
        return runCatching {
            val left = band.left.roundToInt().coerceIn(0, bitmap.width - 1)
            val top = band.top.roundToInt().coerceIn(0, bitmap.height - 1)
            val width = band.width.roundToInt().coerceIn(1, bitmap.width - left)
            val height = band.height.roundToInt().coerceIn(1, bitmap.height - top)
            val region = Bitmap.createBitmap(bitmap, left, top, width, height)
            val bos = ByteArrayOutputStream()
            region.compress(Bitmap.CompressFormat.JPEG, CLEANED_JPEG_QUALITY, bos)
            if (region !== bitmap) region.recycle()
            val token = "s${System.nanoTime()}"
            BrowserOverlayImageStore.put(token, bos.toByteArray(), "image/jpeg")
            "https://mtlocal.overlay/$token.jpg"
        }.getOrNull()
    }

    /**
     * 覆盖层场景构建：把「擦掉原文 → 画上译文」所需的全部信息序列化成百分比坐标场景。
     *
     * - owned 气泡：几何来自 [resolveFinalBubbles] —— **排版框** = 字形框朝气泡方向扩一圈、
     *   **遮盖区域** = 字形紧包围框 / 最小外接四边形（斜排），所以擦字与实底都不会超出气泡；
     * - incoming 跨图气泡：几何已换算到本图坐标系（y 可为负）；遮盖优先用**本图自己的清理图**
     *   （见 [crossPrepareBubbles]），贴片只在清理图缺失时兜底；
     * - 清理图：裁掉拼接条带后整页导出 JPEG（短 URL 通道，天然避开 Chromium 行内样式
     *   约 2MB 的静默上限；`background-size/position` 百分比数学由渲染层完成）。
     */
    private fun buildScene(
        source: Bitmap,
        topH: Int,
        bubbles: List<Bubble>,
        geometries: List<FinalBubble?>,
        prep: ImageVisualPrep,
        incomingCross: List<CrossBubblePart>,
        crossFlags: List<Boolean> = emptyList()
    ): BrowserSceneBuilder.Scene {
        val cleanedUrl = exportCleanedImage(prep.cleanedImage, topH, source.height)
        // 没有纹理来源 → 旁白/透明块（bg=none）改用**不透明实底盖板**：直接铺采样出来的气泡
        // 底色把原文盖死。触发条件有两类 —— ①速度优先模式（关闭「精细擦字」，本来就不建清理图）；
        // ②清理图构建/导出失败。用户 2026-10-11 原话：「现在会有残影。不要这样的，而是用实体的
        // 背景遮挡」—— 贴片（局部边缘填充）边缘必然留残影，纯色块虽丢纹理但绝不会露原文。
        // 纯色气泡不需要：渲染层给它们画的就是采样底色平涂（.mt-bg-solid::before）。
        val useFlatCover = cleanedUrl == null
        val srcH = source.height
        val ownedBubbles = bubbles.mapIndexedNotNull { index, bubble ->
            val geometry = geometries.getOrNull(index) ?: return@mapIndexedNotNull null
            val colors = prep.colors[index] ?: BubbleColors(Color.BLACK, Color.WHITE)
            val box = geometry.boxPercent
            val flatCover = useFlatCover && !bubble.bgType.equals("solid", ignoreCase = true)
            BrowserSceneBuilder.SceneBubble(
                id = "b$index",
                x = box[0], y = box[1], w = box[2], h = box[3],
                originalText = bubble.originalText,
                translatedText = bubble.translatedText.ifBlank { bubble.originalText },
                sourceLineCount = renderer.resolveSourceLineCount(bubble.sourceLineCount, bubble.originalText),
                vertical = bubble.layout.equals("vertical", ignoreCase = true),
                alignment = TextAlignment.normalize(bubble.alignment),
                bgType = if (bubble.bgType.equals("solid", ignoreCase = true)) "solid" else "none",
                bgColorHex = BrowserSceneBuilder.colorToHex(colors.bgColor),
                textColorHex = BrowserSceneBuilder.colorToHex(colors.textColor),
                strokeColorHex = BrowserSceneBuilder.colorToHex(colors.strokeColor),
                fontHeightPercent = if (srcH > 0) geometry.lineHeightPx / srcH * 100f else 0f,
                // 描边只在背景多色时才要（背景 = 清理图里这一块）；缺项时保留描边。
                needsStroke = prep.strokeFlags[index] ?: true,
                polygon = geometry.maskPolygonPercent,
                rotationDeg = bubble.rotationDeg,
                // 实底填充范围：只用**字形遮盖轮廓**（不再 ∪ 气泡外框）。
                // 历史实现是「文字框 ∪ fill_box(气泡外框，限幅 1.4 倍)」，矩形四角会伸到
                // 圆形气泡轮廓之外 → 用户反馈「扩张太大、超出气泡框」（2026-09-30）。
                fillBox = null,
                regionPolygon = geometry.regionPolygonPercent,
                // 盖板框（= 字形范围 + 几像素）单独下发，渲染层不再拿排版框当盖板
                // （2026-09-30 用户反馈「盖板框宽度/高度超出文字许多」）。
                coverBox = geometry.coverBoxPercent,
                fixedFontPercent = geometry.fixedFontPx?.let { if (srcH > 0) it / srcH * 100f else null },
                // 跨缝气泡（owned 的那半）：**允许越出本图排版**（2026-10-03）。
                //
                // 为什么必须带上它：渲染层在 `stitch_overflow` 为假时会把文字锚点 `centerY`
                // 夹到 0..100（mt-overlay-renderer.js 的 `normalizeFillBox`）—— 当气泡大半落在
                // 缝以下时，译文会被整体**上提**到页面内，而盖板留在原位 → 用户看到「字跑了、
                // 盖板还在下面」。更糟的是「整块落在下条带里」的移交块：它的框在本图之外
                // （y 夹到 100%），锚点被夹回来之后会在页脚画出一行假译文。
                // 打开开关后：锚点按真实框走，超出画面的部分由 root 的 `overflow:hidden` 裁掉。
                stitchOverflow = crossFlags.getOrElse(index) { false },
                flatCover = flatCover
            )
        }
        val crossBubbles = incomingCross.mapIndexed { index, part ->
            // 续排框：优先用上一张图**算好的百分比框**原样换算。
            // 上一张图的百分比框 (x%,y%,w%,h%) × 它的像素尺寸 = 上一张图里的像素框；
            // 再按本图尺寸折成百分比 —— 两图尺寸不一致也不会错位。
            // 只有拿不到（旧数据/整图模式）时才退回「拿上一张图的像素框按本图尺寸重算」。
            val percentBox = part.sourceBoxPercent?.takeIf { it.size == 4 }
            val box = if (percentBox != null) {
                // 上一张图的百分比框 → 本图：本图紧接在它下方，所以 y 下移一整张；
                // 两图同宽（拼接条带就是按 current.width 裁的）→ x/宽 原样；
                // 高度不同时按显示比例折 y/高，避免上下半句被拉伸。
                val prevH = part.sourceHeightPx.toFloat().coerceAtLeast(1f)
                val scaleY = srcH / prevH
                val topPx = (percentBox[1] / 100f * prevH - prevH) * scaleY
                val heightPx = percentBox[3] / 100f * prevH * scaleY
                floatArrayOf(
                    percentBox[0],
                    topPx / srcH * 100f,
                    percentBox[2],
                    heightPx / srcH * 100f
                )
            } else {
                BrowserSceneBuilder.stitchedRectPxToSource(
                    leftPx = part.contentRect.left,
                    topPx = part.contentRect.top,
                    widthPx = part.contentRect.width(),
                    heightPx = part.contentRect.height(),
                    stitchedWidthPx = source.width,
                    stitchedHeightPx = srcH,
                    topStripHeightPx = 0,
                    sourceWidthPx = source.width,
                    sourceHeightPx = srcH,
                    allowOverflowY = true
                )
            }
            // 续排的倾斜：直接用上一张图那一半的四边形，只把 y 下移一整张图（两图紧邻）。
            // 不这样做的话两半各自估计倾角，会得到不同角度 → 接缝处一半斜一半平。
            val crossPolygon = part.sourcePolygon
                ?.takeIf { it.size == 8 }
                ?.let { flat -> List(flat.size) { i -> if (i % 2 == 1) flat[i] - 100f else flat[i] } }
            // 遮挡背景：本图自己的清理图已经把这一块（crossPrepareBubbles 合成气泡）擦掉了，
            // 直接按百分比映射即可 —— 与页面其余部分同一张纹理，接缝处不会出现「上下两层」。
            // 清理图不可用时才退回上一张图裁过来的贴片（兜底，保持「有遮罩但背景是空的」不再出现）。
            val usePatch = cleanedUrl == null && part.patchUrl != null
            val patchBox = if (usePatch) {
                BrowserSceneBuilder.stitchedRectPxToSource(
                    leftPx = part.patchDest.left,
                    topPx = part.patchDest.top,
                    widthPx = part.patchDest.width(),
                    heightPx = part.patchDest.height(),
                    stitchedWidthPx = source.width,
                    stitchedHeightPx = srcH,
                    topStripHeightPx = 0,
                    sourceWidthPx = source.width,
                    sourceHeightPx = srcH,
                    allowOverflowY = true
                )
            } else {
                null
            }
            BrowserSceneBuilder.SceneBubble(
                id = "c$index",
                x = box[0], y = box[1], w = box[2], h = box[3],
                originalText = "",
                translatedText = part.translatedText,
                sourceLineCount = 1,
                vertical = false,
                alignment = TextAlignment.normalize(part.alignment),
                bgType = if (part.narration) "none" else "solid",
                bgColorHex = BrowserSceneBuilder.colorToHex(part.colors.bgColor),
                textColorHex = BrowserSceneBuilder.colorToHex(part.colors.textColor),
                strokeColorHex = BrowserSceneBuilder.colorToHex(part.colors.strokeColor),
                needsStroke = com.manga.translator.overlay.BackgroundOutlinePolicy.needsOutline(
                    background = prep.cleanedImage,
                    rect = part.contentRect,
                    bgType = if (part.narration) "none" else "solid",
                    originalHadOutline = part.colors.originalHadOutline
                ),
                fontHeightPercent = if (srcH > 0) part.fontSizePx / srcH * 100f else 0f,
                polygon = crossPolygon,
                stitchOverflow = true,
                fixedFontPercent = if (srcH > 0) part.fontSizePx / srcH * 100f else 0f,
                patchUrl = if (usePatch) part.patchUrl else null,
                patchBox = patchBox
            )
        }
        // 跨页气泡的**下半那半**最容易在这里静默消失（用户 2026-09-30 反馈「一张跨页气泡，
        // 下半那段没有翻译」）。render_done 的 bubbles 是 owned+cross 的和，分不清
        // 「没进来」还是「进来了没画」，所以把四个数一起带出去，在会话日志里对照。
        sceneDiag = "owned=${ownedBubbles.size} cross=${crossBubbles.size} " +
            "incoming=${incomingCross.size} final=${ownedBubbles.size + crossBubbles.size}"
        return BrowserSceneBuilder.Scene(cleanedUrl, ownedBubbles + crossBubbles)
    }

    /**
     * 一个 owned 气泡的最终几何（**本图渲染与跨图移交共用同一份**）：
     * 两处各算一遍时，本图用「墨迹收紧后的框」、移交却用「未收紧的内容框」，接缝两侧
     * 的框/字号/倾角于是各不相同（用户 2026-09-30 反馈「seam 图定位不准」）。
     *
     * 关键区分（2026-09-30「扩张太大超出气泡框」的修法）：
     * - [maskRect] / [maskPolygonPercent]：**字形**范围（墨迹紧包围框 / 最小外接四边形）——
     *   遮罩、实底填充裁剪、擦字都用它，绝不超出气泡；
     * - [layoutRect] / [boxPercent]：**排版框** = 字形框原样（2026-10-14 用户规则：译文
     *   位置/字号以原文为基准，装不下才由渲染层缩字号），只用于译文排版与字号拟合。
     */
    private data class FinalBubble(
        val layoutRect: RectF,
        val maskRect: RectF,
        val boxPercent: FloatArray,
        /**
         * **盖板外框**（原图百分比 x,y,w,h），只用于遮罩/实底，不含排版外扩。
         *
         * 2026-09-30 用户反馈「盖板框的宽度/高度超出文字许多，不进行扩展，要扩也只是几个像素」：
         * 渲染层此前直接拿 [boxPercent]（= 排版框，字形框每侧再扩 15%）当盖板框，
         * 于是盖板比文字大出 30%+。现在盖板框单独给：字形范围 + 自适应外扩
         * （见 `SeamBandGeometry.coverPadPx` / `coverPadPxFor`）。
         */
        val coverBoxPercent: FloatArray?,
        /** 字形四边形（原图百分比 8 个数）；无倾角时为 null。 */
        val maskPolygonPercent: List<Float>?,
        /** 实底填充的裁剪轮廓（略外扩的字形四边形，轴对齐时即为紧包围框）。 */
        val regionPolygonPercent: List<Float>?,
        val lineHeightPx: Float,
        val fixedFontPx: Float?
    )

    /**
     * 计算每个 owned 气泡的最终几何（纯几何 + 一次墨迹分析，跑在 IO 线程）。
     *
     * 顺序：内容框（可写区域精修后）→ 墨迹收紧（ML Kit 的块级框偏松）→ 倾角四边形
     * （OCR 四边形优先，否则用墨迹最小外接矩形兜底）→ 排版框（朝气泡方向扩一圈）。
     */
    private fun resolveFinalBubbles(
        stitched: Bitmap,
        prep: ImageVisualPrep,
        bubbles: List<Bubble>,
        crossFlags: List<Boolean>,
        srcW: Int,
        srcH: Int,
        topH: Int
    ): List<FinalBubble?> {
        resetTiltProbe()
        val result = bubbles.mapIndexed { index, bubble ->
            val refinedRect = prep.contentRects[index]
            if (refinedRect == null || refinedRect.width() <= 2f || refinedRect.height() <= 2f) {
                return@mapIndexed null
            }
            // 遮罩/实底/排版都用**精修后**的框（它才刮掉了气泡描边那一条）。
            val rawRect = refinedRect
            // 但**倾角估计的窗口**不能跟着精修一起变小（2026-09-30）：
            // 精修一旦砍掉两端，斜排墨迹的最小外接矩形就只包得住中段 → 两端露字。
            // 这里取「精修框 ∪ 未精修 OCR 框」，保证窗口不小于 OCR 原始框。
            val tiltWindow = unionRect(refinedRect, prep.ocrRects[index])
            val vertical = bubble.layout.equals("vertical", ignoreCase = true)
            // -----------------------------------------------------------------
            // 逐行框（2026-10-14「字号以原文为基准」）：ML Kit 自带每行 boundingBox，
            // 并集 = 真实字形区。块级框偏松（含行距/内边距），粘到气泡描边时 OTSU
            // 墨迹收紧也收不动（描边被当成墨迹）→ 遮罩/盖板/字号基线全被撑大
            // （真机实测：4 行泡 lineH=45.3、相邻 5 行泡 34.9，译文比原文大 1/3、
            // 白盖板戳出气泡下沿 —— 用户截图）。有逐行框时：
            // - 字形区 = 行框并集（横排）；斜排仍用墨迹最小外接矩形（行框在斜排下
            //   同样是 AABB，并集不比块框紧）；
            // - 字号基线 = 行高中位数（见下方 lineHeightPx）。
            // Paddle / 跨缝合并泡拿不到逐行框 → null，行为与改动前一致。
            // -----------------------------------------------------------------
            val lineBoxesPx = bubble.lineBoxes
                ?.filter { it.size == 4 && it.all { v -> v.isFinite() } }
                ?.map { lb ->
                    RectF(
                        lb[0] / 100f * stitched.width,
                        lb[1] / 100f * stitched.height,
                        lb[2] / 100f * stitched.width,
                        lb[3] / 100f * stitched.height
                    )
                }
                ?.filter { it.width() > 1f && it.height() > 1f }
                ?.takeIf { it.isNotEmpty() }
            val lineUnion = lineBoxesPx?.let { boxes ->
                RectF(boxes[0]).apply {
                    for (i in 1 until boxes.size) union(boxes[i])
                }
            }
            // ML Kit 的文字框是「块级」的、偏松（含行距/内边距），PaddleOCR 给的是紧贴字形的四边形。
            // 只对**没有四边形**的气泡走墨迹几何。有逐行框时收紧窗口换成行框并集：
            // 窗口不再含气泡描边，收紧只服务于斜排四边形，失败也不伤框本身。
            val inkGeometry = if (bubble.polygon == null) {
                resolveInkGeometry(stitched, lineUnion ?: rawRect, tiltWindow, vertical)
            } else {
                tiltProbe.append("has_quad(${bubble.polygon?.size ?: 0}) ")
                null
            }
            val maskRect = when {
                lineUnion == null -> inkGeometry?.rect ?: rawRect
                inkGeometry?.quad != null -> inkGeometry.rect
                else -> lineUnion
            }
            // 原文行数：**检测器给出的行数**（ML Kit = `block.lines`，`countVisualLines` 现在会把
            // 块内行数按组求和）。此前「两行并成一个块」会被算成 1 行 → 「框高 ÷ 行数」翻倍 →
            // `font_height_percent` 翻倍 → 译文比原文大一大截、整块戳出气泡
            // （用户 2026-10-14：「超出太多了吧」）。详见 `TextLayoutMetrics.countVisualLines`。
            val lineCount = renderer.resolveSourceLineCount(bubble.sourceLineCount, bubble.originalText)
            val ocrPolygon = TextQuadGeometry.stitchedPolygonToSource(
                polygon = bubble.polygon,
                stitchedHeightPx = stitched.height,
                topStripHeightPx = topH,
                sourceHeightPx = srcH
            )
            val maskQuadSource = ocrPolygon ?: TextQuadGeometry.stitchedPolygonToSource(
                polygon = inkGeometry?.quad ?: estimateInkTiltQuadStitched(stitched, maskRect, vertical).second,
                stitchedHeightPx = stitched.height,
                topStripHeightPx = topH,
                sourceHeightPx = srcH
            )
            // 原文单行字高取**字形框**（不含排版外扩），否则字号会跟着外扩一起被放大。
            //
            // ⚠️ 有四边形（斜排）时字高必须取四边形的**厚度**（垂直于基线的那条边），
            // 不能取外接框的高：外接框高 = 行长·sinθ + 字高·cosθ，19° 时 38px 的字会被
            // 读成 125px（3.3 倍）→ 译文字号跟着放大 → 「倾斜文字比原文高一大截」
            // （用户 2026-09-30 反馈）。厚度取不出来时才退回外接框口径。
            //
            // ⚠️⚠️ **单位**（2026-09-30 修，同一用户当天第二次反馈）：`maskQuadSource` 是
            // **原图百分比**四边形（0-100），不是像素！直接 `quadLineThicknessPx(maskQuadSource)`
            // 量到的是「百分比厚度」却被当成像素用 —— 真机实测 `lineH=10.7`，而逐像素量出的
            // 真值是 **37.7px**（差 3.5 倍）；`font_height_percent` 因此从 11.09% 掉到 3.13%，
            // 字号被渲染层 FONT_MIN 兜在 10px，再叠上 1.8px 白描边 → 用户看到的是
            // 「倾斜的文本看不清（像空心白字）」。所以量厚度前必须先按原图尺寸换算成像素。
            val quadThicknessPx = percentQuadThicknessPx(maskQuadSource, srcW, srcH)
            val fallbackLineHeightPx = renderer.originalLineHeightPx(
                contentHeightPx = maskRect.height(),
                sourceLineCount = lineCount,
                contentWidthPx = maskRect.width(),
                vertical = vertical
            )
            // 字号基线：斜排四边形厚度 → 逐行框中位数（横排行高 / 竖排列宽）→
            // 块框 ÷ 行数（老口径兜底）。前两档都紧贴字形，「块框 ÷ 行数」会被
            // 块框松弛放大（见上方 lineBoxesPx 注释）。
            val lineHeightPx = when {
                quadThicknessPx > 2f -> quadThicknessPx / lineCount.coerceAtLeast(1)
                lineBoxesPx != null -> {
                    val median = TextLayoutMetrics.medianLineBoxDimPx(
                        lineBoxesPx.map { floatArrayOf(it.left, it.top, it.right, it.bottom) },
                        vertical
                    )
                    if (median > 2f) median else fallbackLineHeightPx
                }
                else -> fallbackLineHeightPx
            }
            // -----------------------------------------------------------------
            // 排版框（2026-10-14 用户规则）：**就是字形框原样**，一个像素都不动。
            //
            // 译文的位置和字号都以原文为基准——原文在哪个位置、字号多大，译文就在哪个
            // 位置、字号多大。对 layoutRect 的任何加工（外扩/收窄/削高）都是在挪译文，
            // 一律不做。唯一允许的调整是「译文按原文字号装不下时，渲染层自适应缩小字号」
            // （JS fitBubbleFontSize：原字高起步、装不下二分收小）；字号基线 [lineHeightPx]
            // 来自四边形厚度/字形框，本就不受排版框影响。
            //
            // 历史（勿回加）：
            // - 字形框 ∓0.2 行高（descender 余量）→ 已删：外扩等于给译文多让地；
            // - interiorCap 横向扩到 1.3×/容器宽 → 已删：译文行铺到原文宽度之外，
            //   白底同色页面上直接戳出气泡（「文字超出气泡框」的来源之一）；
            // - interiorCap 纵向削到容器高 → 已删：这就是纵向收窄；
            // - 2026-10-12 固定横向收窄 ∓0.2 行高 → 用户真机对比判定负优化（악질들泡
            //   译文第一行被接缝盖板裁掉一截），当天已回退。
            // 「按容器内壁轮廓裁剪盖板」那套机制已删（2026-10-15 用户裁定）：它逐行走像素
            // 找气泡边界，量到的其实是**文字噪声**的轮廓 —— 面板型气泡里切出台阶
            // （用户真机「锯齿状的背景」）。盖板只按「**合并后的文字区域 + 少量外扩**」走
            // （见下方 regionPolygonPercent），与参考项目 `cover-geometry.js` 的
            // `resolveCoverQuad`（文字几何 × coverScale）同口径 —— 那边的 region_polygon
            // 也只是 OCR 的文字区域，从不扫描气泡内壁。
            // -----------------------------------------------------------------
            val layoutRect = RectF(maskRect)
            // 字号输入诊断（2026-09-30 用户反馈「倾斜的文本看不清」）：
            // 真机上 `font_height_percent` 只有 3.13%（真值 11.09%），字号被 FONT_MIN 兜住 = 10px。
            // 只打最终字号是查不出「四边形厚度算错还是 srcH 口径不对」的，这里把三个输入全打出来。
            tiltProbe.append(
                "|b$index lineH=${"%.1f".format(lineHeightPx)}" +
                    " quad=${"%.1f".format(quadThicknessPx)}" +
                    " lines=$lineCount" +
                    " lb=${lineBoxesPx?.size ?: 0}" +
                    " mask=${"%.0f".format(maskRect.width())}x${"%.0f".format(maskRect.height())}" +
                    " src=${srcW}x$srcH stitch=${stitched.width}x${stitched.height}" +
                    " topH=$topH poly=${bubble.polygon?.size ?: 0}" +
                    " maskQuad=${maskQuadSource?.size ?: 0} vertical=$vertical "
            )
            val boxPercent = BrowserSceneBuilder.stitchedRectPxToSource(
                leftPx = layoutRect.left,
                topPx = layoutRect.top,
                widthPx = layoutRect.width(),
                heightPx = layoutRect.height(),
                stitchedWidthPx = stitched.width,
                stitchedHeightPx = stitched.height,
                topStripHeightPx = topH,
                sourceWidthPx = srcW,
                sourceHeightPx = srcH
            )
            // -----------------------------------------------------------------
            // 盖板区域（= 遮罩，与**排版框**彻底分开）
            //
            // 2026-09-30 用户反馈「盖板框的宽度/高度超出文字许多，我的希望是不进行扩展，
            // 要扩张也只是几个像素」。原因：渲染层此前拿 x/y/w/h（= 排版框 = 字形框每侧
            // 再外扩 15%，共 1.3 倍）当盖板框，于是盖板比文字大出 30%+。
            // 现在单独下发一个 [FinalBubble.coverBoxPercent]：只取字形范围 + 自适应外扩。
            // 排版框只继续用于译文排版（用户看不见）。
            // -----------------------------------------------------------------
            // ⚠️ 外扩的像素→百分比换算必须用**原图**尺寸（srcW/srcH），不能用拼接图：
            // maskRectPercent / maskQuadSource 都是原图百分比，配上拼接图高度（800+152）
            // 算出来的 padY 只有 3×800/952 = 2.52px，垂直方向会悄悄少遮 0.5px。
            // 盖板外扩：**随字号自适应**（2026-09-30 用户反馈「译文下面有时有一点点原文残留，
            // 覆盖层需要扩大一点点」）。
            //
            // 为什么固定 3px 不够：同一天早些时候用户要求「不扩展、最多几个像素」，
            // 于是定了 COVER_PAD_PX = 3。但韩文字形（尤其带收音的）会探出 OCR 字形框一点点，
            // 字号越大探得越多 —— 固定 3px 在小字上刚好、在大字上就露出一线原文。
            // 现在取 `max(3px, 字高 × 比例)` 并**封顶**：比 3px 大一点，
            // 又不会回到「盖板比文字大 30%」那个被投诉过的状态（封顶就是防它的）。
            //
            // ⚠️ 2026-10-01 用户再次反馈「译文下面有时候会有一点点原文残留。所以感觉覆盖层
            // 需要扩大一点点。**仅对于 ML Kit**」：通用档解决不了 ML Kit —— PaddleOCR 给的是
            // 逐字紧贴字形的四边形，盖板按它走本来就不会切字；ML Kit 只给块级框，字形范围靠
            // OTSU 反推，而 OTSU 会把收笔/抗锯齿这类浅像素判成背景 → 墨迹框下沿落在字形里 →
            // 盖板下边正好切在原文最后一行笔画上（用户截图：译文下方露出一线原文字脚）。
            // 所以按「有没有四边形」分档：无四边形（= ML Kit）走更大的一档，并在**下沿**再
            // 多留几个像素；左右刻意不加宽，避免把早先「盖板宽度超出文字许多」的投诉带回来。
            val hasQuad = bubble.polygon != null
            val coverPadPx = SeamBandGeometry.coverPadPxFor(lineHeightPx, hasQuad)
            val coverPadBottomPx =
                coverPadPx + SeamBandGeometry.coverPadBottomExtraPx(lineHeightPx, hasQuad)
            val paddedQuadPercent = BrowserSceneBuilder.flattenQuad(maskQuadSource)
                ?.let { inflatePercentQuadByPx(it, coverPadPx, srcW, srcH) }
            val maskRectPercent = BrowserSceneBuilder.stitchedRectPxToSource(
                leftPx = maskRect.left,
                topPx = maskRect.top,
                widthPx = maskRect.width(),
                heightPx = maskRect.height(),
                stitchedWidthPx = stitched.width,
                stitchedHeightPx = stitched.height,
                topStripHeightPx = topH,
                sourceWidthPx = srcW,
                sourceHeightPx = srcH
            )
            // 斜排：轮廓就是外扩后的四边形；轴对齐：轮廓就是外扩后的字形框四角。
            // 这里与盖板框共用同一组外扩值：轮廓（region_polygon）用来裁剪实底填充，
            // 它若比盖板小，填充就会被裁掉一圈 —— 两处口径必须一致（含下沿的那份额外外扩）。
            //
            // 斜排（≥3°）时下沿口径失效：世界坐标的「下沿」不等于文字的「下沿」（文字是斜的），
            // 强行只加下沿会把四边形拉歪。斜排气泡因此只吃均匀的那一档（ML Kit 档本就比
            // 有四边形时大一档），见 `coverPadPxFor`。
            val defaultRegionPolygon = paddedQuadPercent ?: BrowserSceneBuilder.expandPercentBoxByPx(
                maskRectPercent,
                coverPadPx,
                srcW,
                srcH,
                bottomPadPx = coverPadBottomPx
            ).let { rect ->
                listOf(
                    rect[0], rect[1],
                    rect[0] + rect[2], rect[1],
                    rect[0] + rect[2], rect[1] + rect[3],
                    rect[0], rect[1] + rect[3]
                )
            }
            // 盖板/实底填充轮廓 = **合并后的文字区域 + 少量外扩**（见 [defaultRegionPolygon]）。
            // 2026-10-15 用户裁定：删掉「按容器内壁轮廓裁剪」那套机制 —— 它量到的其实是
            // 文字/背景噪声的轮廓，会把盖板切出台阶（「锯齿状的背景」）；参考项目
            // `cover-geometry.js` 同样只用文字几何（`resolveCoverQuad` = 文字四边形/框
            // × coverScale 外扩），从不扫描气泡内壁。
            val regionPolygonPercent = defaultRegionPolygon
            // 盖板外框 = 轮廓的外接框（四边形的外接框已经含了那几像素外扩）。
            val coverBoxPercent = paddedQuadPercent?.let { percentQuadBounds(it) }
                ?: BrowserSceneBuilder.expandPercentBoxByPx(
                    maskRectPercent,
                    coverPadPx,
                    srcW,
                    srcH,
                    bottomPadPx = coverPadBottomPx
                )
            val isCross = crossFlags.getOrElse(index) { false }
            // 跨图气泡：两半必须用**同一个字号**（否则本图 DOM 自适应、下一张图固定值 → 错位重影）。
            val fixedFontPx = if (isCross && srcH > 0) {
                renderer.fitFontSize(
                    text = bubble.translatedText.ifBlank { bubble.originalText },
                    rect = layoutRect,
                    originalLineHeightPx = lineHeightPx,
                    // 这个字号最终由 DOM 渲染层按 inline font-size 使用，必须扣掉
                    // `.mt-bubble` 的 em 内边距才是真实可用宽度（见 [DOM_BUBBLE_PADDING_EM]）。
                    horizontalPaddingEmRatio = DOM_BUBBLE_PADDING_EM
                )
            } else {
                null
            }
            FinalBubble(
                layoutRect = layoutRect,
                maskRect = maskRect,
                boxPercent = boxPercent,
                coverBoxPercent = coverBoxPercent,
                maskPolygonPercent = paddedQuadPercent,
                regionPolygonPercent = regionPolygonPercent,
                lineHeightPx = lineHeightPx,
                fixedFontPx = fixedFontPx
            )
        }
        flushTiltProbe()
        return result
    }

    /**
     * 上一张图移交的字形轮廓（**上一张图百分比**，8 个数 = 4 点 x/y 交替）→ 本图像素外接框。
     *
     * 坐标口径与 [buildScene] 里 `c0` 的 `polygon` 完全一致：x 原样、y 下移一整张图
     * （`y% - 100`）。两图高度不同时按本图高度折算 —— 与 `contentRect` 的换算口径相同
     * （`contentRect` 就是 `layoutRect` 减掉一整张图的高度），所以两者可以互相夹。
     */
    private fun sourcePolygonBoundsPx(points: List<Float>?, srcW: Float, srcH: Float): RectF? {
        if (points == null || points.size != 8 || srcW <= 0f || srcH <= 0f) return null
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val x = points[i * 2] / 100f * srcW
            val y = (points[i * 2 + 1] - 100f) / 100f * srcH
            if (!x.isFinite() || !y.isFinite()) return null
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
        val box = RectF(left, top, right, bottom)
        return if (box.width() > 1f && box.height() > 1f) box else null
    }

    /**
     * 上一张图移交的跨图气泡 → **本图清理预处理用的合成气泡**（2026-09-30）。
     *
     * 目的：让本图的清理图/擦字 mask 把接缝下半截也擦掉，于是本图渲染这一半时直接用
     * **自己那张清理图**——整页同一张纹理，接缝两侧不会「上下各一层清理图」，也不再需要
     * 从上一张图裁贴片（贴片只在清理图不可用时兜底）。
     *
     * 坐标：跨图气泡的 [CrossBubblePart.contentRect] 是**本图像素**坐标（顶边可为负），
     * 这里换算成**拼接图百分比**（本图区域整体下移 topH），只取落在本图内的可见部分。
     * 这些合成气泡只参与 mask/擦字/取色，不参与翻译，也不会作为场景文字下发。
     */
    /**
     * 收到侧交叉判定：**本图另有检出、且那检出伸出了移交框之外**时，丢弃该移交气泡。
     *
     * ## 判据（三条同时成立才丢）
     *
     * 1. 本图某条检出的内容框与移交框（[CrossBubblePart.contentRect]，换算到本图坐标）重叠
     *    ≥ [INCOMING_CONTEST_MIN_OVERLAP]；
     * 2. **该重叠不足 [SeamTuning.SUPPRESS_INCOMING_OVERLAP]** —— 即这条检出并非「整块都在
     *    移交框里」（那种情况是它本来就被上一张画过，归属判定会正常丢弃它）；
     * 3. **本图的文本解释不了移交的原文**（[textExplainedByHandoff] 为假）。
     *
     * 第 3 条是必需的，否则会误伤合法续排：真机夹具 `seam-after-line2.html` 里下一张图
     * 把同一行的下半截读成 `케이지라고요 !`（上一张移交的原文是 `케이지리고요!!`），
     * 几何上同样「伸出移交框」——只有文本相似度能区分「同一句的残缺读法」（保留移交）
     * 与「本图另一段文字」（丢弃移交）。
     *
     * 真机 `[32317270]`：本图检出 `방해됐단양이o를 하려면 죽거나`（把条带尾行与本图另一段
     * 并成了一行）与移交原文只有 54% 字符重合 → 移交框被丢弃，本图那半行改由归属判定认领。
     *
     * 合法续排（本图检出就是上一张那半句的残缺读法）时重叠率 ≥ 阈值或文本重合度高 → 不冲突
     * → 移交照常生效。
     */
    private fun filterContestedIncoming(
        parts: List<CrossBubblePart>,
        localBubbles: List<Bubble>,
        stitched: Bitmap,
        topH: Int,
        sourceHeight: Int,
        lineHeightPx: Float,
        timer: TranslationTimer
    ): List<CrossBubblePart> {
        if (parts.isEmpty() || localBubbles.isEmpty()) return parts
        val widthF = stitched.width.toFloat()
        val stitchedH = stitched.height.toFloat()
        val heightF = sourceHeight.toFloat()
        val kept = mutableListOf<CrossBubblePart>()
        val rejected = mutableListOf<String>()
        parts.forEachIndexed { index, part ->
            val left = part.contentRect.left
            val right = part.contentRect.right
            val top = maxOf(0f, part.contentRect.top)
            val bottom = minOf(heightF, part.contentRect.bottom)
            val partArea = ((right - left).coerceAtLeast(0f)) * ((bottom - top).coerceAtLeast(0f))
            var contested: Bubble? = null
            if (partArea > 0f) {
                contested = localBubbles.firstOrNull { bubble ->
                    val rect = BubbleGeometry.contentRect(bubble)
                    val bLeft = rect.left / 100f * widthF
                    val bRight = rect.right / 100f * widthF
                    val bTop = rect.top / 100f * stitchedH - topH
                    val bBottom = rect.bottom / 100f * stitchedH - topH
                    val bArea = ((bRight - bLeft).coerceAtLeast(0f)) *
                        ((bBottom - bTop).coerceAtLeast(0f))
                    if (bArea <= 0f) return@firstOrNull false
                    val ix = (minOf(right, bRight) - maxOf(left, bLeft)).coerceAtLeast(0f)
                    val iy = (minOf(bottom, bBottom) - maxOf(top, bTop)).coerceAtLeast(0f)
                    val coverage = (ix * iy) / bArea
                    if (coverage < INCOMING_CONTEST_MIN_OVERLAP ||
                        coverage >= SeamTuning.SUPPRESS_INCOMING_OVERLAP
                    ) return@firstOrNull false
                    if (textExplainedByHandoff(bubble.originalText, part.originalText)) {
                        return@firstOrNull false
                    }
                    // 同句重读豁免（2026-10-11 真机 [Lezhin]）：本图把「条带里那几行 + 缝下
                    // 本图的行」读成一个框，它与移交原文只有部分字符重合（移交框只装了上半，
                    // 实测 sim=0.67 < 0.7 被旧判据丢掉）—— 但几何上它就是同一句：
                    // ①框伸进了上条带（bTop < 0，含上一张的内容）；
                    // ②横向与移交框同列（±15%）；
                    // ③缝以下的部分几乎完全被移交框覆盖（≥90%）。
                    // 这种形态丢了移交，移交带来的那几行（本图 OCR 往往根本没检出来）就
                    // 没人擦没人翻 —— 截图里「缝下整段原文还留着」正是这条。
                    // 反例守卫（[32317270]）：本图检出横向伸出移交框（x 83 vs 187，超 15%），
                    // 不是同列 → 不豁免 → 照旧丢弃。
                    !isSameBubbleReRead(
                        localLeft = bLeft, localRight = bRight, localTop = bTop,
                        localBottom = bBottom,
                        incomingLeft = left, incomingRight = right,
                        incomingTop = top, incomingBottom = bottom,
                        heightF = heightF
                    )
                }
            }
            if (contested == null) {
                kept += part
            } else {
                rejected += "i$index:[${part.originalText.take(8)}]~[${contested.originalText.take(8)}]" +
                    " sim=${"%.2f".format(handoffTextOverlap(contested.originalText, part.originalText))}"
                runCatching { part.patchBitmap.recycle() }
            }
        }
        if (rejected.isNotEmpty()) {
            timer.mark(
                "incoming_decision",
                "kept=${kept.size} skipped=${rejected.size} detail=${rejected.joinToString(" ")}"
            )
        }
        return kept
    }

    /**
     * 本图检出是不是「同一句被条带 + 本图上下两半合并后的重读」：
     * ①框伸进上条带（localTop < 0）；②横向与移交框同列（左右各 ±15% 以内）；
     * ③缝以下的部分被移交框覆盖 ≥90%。三条同时成立才豁免（判据与现场数字见调用点注释）。
     */
    private fun isSameBubbleReRead(
        localLeft: Float,
        localRight: Float,
        localTop: Float,
        localBottom: Float,
        incomingLeft: Float,
        incomingRight: Float,
        incomingTop: Float,
        incomingBottom: Float,
        heightF: Float
    ): Boolean {
        if (localTop >= 0f) return false
        val incomingWidth = (incomingRight - incomingLeft).coerceAtLeast(1f)
        val tolerance = incomingWidth * 0.15f
        if (kotlin.math.abs(localLeft - incomingLeft) > tolerance ||
            kotlin.math.abs(localRight - incomingRight) > tolerance
        ) return false
        val visibleTop = maxOf(localTop, 0f)
        val visibleBottom = minOf(localBottom, heightF)
        val visibleArea = (localRight - localLeft).coerceAtLeast(0f) *
            (visibleBottom - visibleTop).coerceAtLeast(0f)
        if (visibleArea <= 0f) return false
        val ix = (minOf(incomingRight, localRight) - maxOf(incomingLeft, localLeft)).coerceAtLeast(0f)
        val iy = (minOf(incomingBottom, visibleBottom) - maxOf(incomingTop, visibleTop)).coerceAtLeast(0f)
        return (ix * iy) / visibleArea >= 0.9f
    }

    /**
     * 本图的这段文字能否用**上一张移交的原文**解释（= 同一句话的残缺/变体读法）。
     *
     * 判据与取值依据见 [HandoffTextMatch]（纯逻辑，可 JVM 单测）：
     * - 合法续排：`케이지라고요 !` vs `케이지리고요!!` → 0.83 ≥ 0.7 → 保留移交；
     * - 侵占本图：`방해됐단양이…를 하려면 죽거나` vs 上一张那半句 → 0.54 < 0.7 → 丢弃移交。
     */
    private fun textExplainedByHandoff(localText: String, handoffOriginalText: String): Boolean =
        HandoffTextMatch.explainedByHandoff(localText, handoffOriginalText)

    /** 见 [HandoffTextMatch.overlap]（诊断日志里直接打出来的相似度）。 */
    private fun handoffTextOverlap(localText: String, handoffOriginalText: String): Float =
        HandoffTextMatch.overlap(localText, handoffOriginalText)

    /**
     * 收到侧吸收：找出「与某个移交框同属一个气泡的本图检出」，返回
     * ① 每个移交框要并进来的碎片框（本图坐标，擦字/盖板扩到并集用）；
     * ② 这些检出在 `owned` 里的下标（要从本图场景里摘掉）；
     * ③ 其中「相邻行」碎片按移交框分组的明细（带原文，译文并回 winner 用）。
     *
     * 两种形态（判据都在 [SeamIncomingGeometry]）：
     * - **同一行的两半**（[SeamIncomingGeometry.isSameLineFragment]）：移交气泡的译文来自
     *   上一张的拼接 OCR（它看到过整行），本图碎片只是同一行的另一种读法 —— 摘掉即可，
     *   文字不用并回；
     * - **同一气泡的相邻行**（[SeamIncomingGeometry.isAdjacentLineFragment]，2026-10-11 补齐
     *   Todolist §30「未做 #1」）：缝落在行与行之间时，移交气泡只盖住前几行，本图检出的
     *   是同一气泡的下一行 —— 摘掉之外**原文必须并进翻译请求**，否则那行没人翻。
     *
     * [mergeAdjacentLines] 为假（OCR-only 模式：没有翻译能力，并回只会得到原文回退）时
     * 相邻行不吸收，保持旧行为（那行作为独立气泡自己走一遍「翻译=原文」）。
     *
     * 纯几何 + 纯计算，与 `crossPrepareBubbles`（要 Bitmap）分开，便于单测钉住真机数字。
     */
    private class IncomingAbsorption(
        /** 同行碎片的框（本图坐标）——交给 `expandForAdjacentFragments` 固定点并框。 */
        val eraseFragmentBoxes: Map<Int, List<SeamIncomingGeometry.Box>>,
        /**
         * 相邻行的框（本图坐标）——**普通并集、不参与固定点链式扩框**。
         *
         * 为什么不能链式：链式并掉一行后框下移，下一行又变得「相邻」—— 整段会被逐行吞进
         * 擦除框，而这些行没有并进翻译请求（吸收判据只认移交框紧邻的那一两行）→
         * 擦掉却没人翻。并回判据（[SeamIncomingGeometry.isAdjacentLineFragment] 对**移交框**
         * 判）与扩框集合必须严格同集。
         */
        val adjacentFragmentBoxes: Map<Int, List<SeamIncomingGeometry.Box>>,
        /** 被吸收的 owned 下标（ownedWorth 坐标系）——从本图场景摘掉。 */
        val absorbedOwnedIndices: Set<Int>,
        /** 每个移交框吸收的「相邻行」碎片（带原文）——译文并回 winner 用。 */
        val adjacentByPart: Map<Int, List<SeamIncomingGeometry.AbsorbedFragment>>
    )

    private fun absorbIncomingFragments(
        parts: List<CrossBubblePart>,
        owned: List<SeamOwnership>,
        stitchedWidth: Int,
        stitchedHeight: Int,
        topH: Int,
        sourceHeight: Int,
        lineHeightPx: Float,
        mergeAdjacentLines: Boolean
    ): IncomingAbsorption {
        if (parts.isEmpty() || owned.isEmpty()) {
            return IncomingAbsorption(emptyMap(), emptyMap(), emptySet(), emptyMap())
        }
        val widthF = stitchedWidth.toFloat()
        val stitchedH = stitchedHeight.toFloat()
        val heightF = sourceHeight.toFloat()
        fun bubbleBox(bubble: Bubble): SeamIncomingGeometry.Box {
            val rect = BubbleGeometry.contentRect(bubble)
            return SeamIncomingGeometry.Box(
                left = rect.left / 100f * widthF,
                top = rect.top / 100f * stitchedH - topH,
                right = rect.right / 100f * widthF,
                bottom = rect.bottom / 100f * stitchedH - topH
            )
        }
        val partBoxes = parts.map { part ->
            SeamIncomingGeometry.Box(
                left = part.contentRect.left,
                top = maxOf(0f, part.contentRect.top),
                right = part.contentRect.right,
                bottom = minOf(heightF, part.contentRect.bottom)
            )
        }
        val fragments = HashMap<Int, MutableList<SeamIncomingGeometry.Box>>()
        val adjacentBoxes = HashMap<Int, MutableList<SeamIncomingGeometry.Box>>()
        val adjacentLines = HashMap<Int, MutableList<SeamIncomingGeometry.AbsorbedFragment>>()
        val absorbed = HashSet<Int>()
        owned.forEachIndexed { index, detection ->
            val box = bubbleBox(detection.bubble)
            if (box.width <= 0f || box.height <= 0f) return@forEachIndexed
            // 同行优先：两半的框基本叠在一起，相邻行判据会把它挡在外面，但显式先判更稳。
            val partIndex = partBoxes.indexOfFirst { partBox ->
                SeamIncomingGeometry.isSameLineFragment(partBox, box, lineHeightPx)
            }
            if (partIndex >= 0) {
                fragments.getOrPut(partIndex) { mutableListOf() } += box
                absorbed += index
                return@forEachIndexed
            }
            if (!mergeAdjacentLines) return@forEachIndexed
            val adjacentIndex = partBoxes.indexOfFirst { partBox ->
                SeamIncomingGeometry.isAdjacentLineFragment(partBox, box, lineHeightPx)
            }
            if (adjacentIndex >= 0) {
                adjacentBoxes.getOrPut(adjacentIndex) { mutableListOf() } += box
                adjacentLines.getOrPut(adjacentIndex) { mutableListOf() } +=
                    SeamIncomingGeometry.AbsorbedFragment(
                        ownedIndex = index,
                        box = box,
                        originalText = detection.bubble.originalText
                    )
                absorbed += index
            }
        }
        return IncomingAbsorption(
            eraseFragmentBoxes = fragments.toMap(),
            adjacentFragmentBoxes = adjacentBoxes.toMap(),
            absorbedOwnedIndices = absorbed,
            adjacentByPart = adjacentLines.toMap()
        )
    }

    /**
     * 把「框比文本矮」的气泡框向下扩到装得下文本（2026-10-11，见调用点注释）。
     *
     * 行数口径（2026-10-12 收紧）：**只按原文硬换行数**，行高用全页中位行高。
     *
     * 为什么撤掉「每行按宽度可容纳字数」的折行估算（2026-10-12 用户截图
     * 게임은 살인이다 第 1 话 + 渲染层 CDP 实测 `origH_pct=4.51`）：
     * 单行小字气泡（OCR 框 316×25px，全页中位行高 36px）被按中位行高估字宽
     * （36×0.72≈26px/字），14 个字折成 2 行 → 框撑到 ~108px 高。扩出来的区域
     * 撞上气泡描边，墨迹精修把描边当墨迹收不回 → `lineH=108` →
     * `font_height_percent=4.5%`（真值 1.04%）→ 译文渲染大 4 倍、白盖板伸出气泡外。
     * 折行本来就是给「原文行宽超过 OCR 框宽」兜底的 —— 而原文行宽 ≤ 框宽是 OCR 的
     * 量测结果，这个兜底只会在小字气泡上误伤。需要补的只有「框高 < 硬行数 × 行高」
     * （2026-10-11 的原始现场：4 行台词框高只够 2 行），硬行数本身就够 —— 而且用中位
     * 行高算 2312+4×43×1.2=2518，比当年折行版（2570 被邻居夹到 2520）更贴真实底边。
     */
    private fun growBubbleToTextHeight(
        bubble: Bubble,
        others: List<Bubble>,
        stitchedWidth: Int,
        stitchedHeight: Int,
        lineHeightPx: Float
    ): Bubble {
        val rect = percentRectToPx(BubbleGeometry.outerRect(bubble), stitchedWidth, stitchedHeight)
        val hardLineCount = bubble.originalText.split('\n', '\r').count { it.isNotBlank() }
        val otherRects = others.mapNotNull {
            if (it === bubble) null else percentRectToPx(BubbleGeometry.outerRect(it), stitchedWidth, stitchedHeight)
        }
        val newBottom = growBubbleBottomPx(
            rectLeft = rect.left,
            rectTop = rect.top,
            rectRight = rect.right,
            rectBottom = rect.bottom,
            hardLineCount = hardLineCount,
            medianLineHeightPx = lineHeightPx,
            stitchedHeightPx = stitchedHeight.toFloat(),
            others = otherRects.map { it.toSeamBox() }
        ) ?: return bubble
        val deltaPct = (newBottom - rect.bottom) / stitchedHeight * 100f
        return bubble.copy(h = bubble.h + deltaPct, contentH = bubble.contentH + deltaPct)
    }

    private fun crossPrepareBubbles(
        incomingCross: List<CrossBubblePart>,
        stitched: Bitmap,
        topH: Int,
        sourceHeight: Int,
        fragmentBoxes: Map<Int, List<SeamIncomingGeometry.Box>> = emptyMap(),
        /** 相邻行的框（2026-10-11）：普通并集（**不**参与同行碎片的固定点链式扩框），见 [IncomingAbsorption]。 */
        adjacentFragmentBoxes: Map<Int, List<SeamIncomingGeometry.Box>> = emptyMap(),
        lineHeightPx: Float = 0f
    ): List<Bubble> {
        if (incomingCross.isEmpty() || stitched.width <= 0 || stitched.height <= 0) return emptyList()
        val stitchedH = stitched.height.toFloat()
        val stitchedW = stitched.width.toFloat()
        val heightF = sourceHeight.toFloat()
        return incomingCross.mapIndexedNotNull { index, part ->
            var topPx = maxOf(0f, part.contentRect.top).coerceAtMost(heightF)
            var bottomPx = minOf(heightF, part.contentRect.bottom)
            var leftPx = part.contentRect.left.coerceIn(0f, stitchedW)
            var rightPx = part.contentRect.right.coerceIn(0f, stitchedW)
            // 2026-10-03：把擦字区域再夹到「移交过来的**真实字形轮廓**」内。
            //
            // 为什么必须夹：`contentRect` 是整块的**排版框**（字形框每侧再扩 15%），一旦它被
            // 配对合并/条带截断撑大，擦字区域就会伸到本图正文上 —— 用户 2026-10-03 截图里
            // 「跨缝气泡下面那个气泡的第一行被擦掉、又没有译文」就是这种形态。
            // 轮廓（sourcePolygon）是上一张图算好的「字形 + 几像素」，用它夹最保险；
            // 夹完若小于 4px（说明坐标口径对不上）就不夹，宁可保留原框也别把该擦的留一半。
            val glyph = sourcePolygonBoundsPx(part.sourcePolygon, stitchedW, heightF)
            if (glyph != null) {
                val gl = glyph.left - CROSS_GLYPH_PAD_PX
                val gr = glyph.right + CROSS_GLYPH_PAD_PX
                val gt = glyph.top - CROSS_GLYPH_PAD_PX
                val gb = glyph.bottom + CROSS_GLYPH_PAD_PX
                val nl = maxOf(leftPx, gl)
                val nr = minOf(rightPx, gr)
                val nt = maxOf(topPx, gt)
                val nb = minOf(bottomPx, gb)
                if (nr - nl >= 4f && nb - nt >= 4f) {
                    leftPx = nl
                    rightPx = nr
                    topPx = nt
                    bottomPx = nb
                }
            }
            // 收到侧吸收（2026-10-03）：把与本图同一行的**本图碎片**并进同一个框。
            //
            // 为什么必须在「夹到字形轮廓」**之后**并：上面那次夹紧是为了不擦到邻居正文
            // （2026-10-03 修「跨缝气泡下面那个气泡被擦掉一行」），但它会让框小于本图实际字形
            // —— 真机 `[5467219]` 的 `밖에 …!!` 就是框外露出来的字。本图自己检出的碎片是
            // **本图确实有字**的证据，把它并回来既补齐盖板、又不越过本图正文。
            val fragments = fragmentBoxes[index].orEmpty()
            if (fragments.isNotEmpty()) {
                val grown = SeamIncomingGeometry.expandForAdjacentFragments(
                    box = SeamIncomingGeometry.Box(leftPx, topPx, rightPx, bottomPx),
                    fragments = fragments,
                    lineHeightPx = lineHeightPx
                )
                leftPx = grown.left.coerceIn(0f, stitchedW)
                rightPx = grown.right.coerceIn(0f, stitchedW)
                topPx = grown.top.coerceIn(0f, heightF)
                bottomPx = grown.bottom.coerceIn(0f, heightF)
            }
            // 相邻行（2026-10-11）：普通并集（见 [IncomingAbsorption.adjacentFragmentBoxes] ——
            // 链式扩框会把没并进翻译请求的行也擦掉）。
            adjacentFragmentBoxes[index].orEmpty().forEach { adjacent ->
                leftPx = minOf(leftPx, adjacent.left).coerceIn(0f, stitchedW)
                rightPx = maxOf(rightPx, adjacent.right).coerceIn(0f, stitchedW)
                topPx = minOf(topPx, adjacent.top).coerceIn(0f, heightF)
                bottomPx = maxOf(bottomPx, adjacent.bottom).coerceIn(0f, heightF)
            }
            // 退化框（真机 `box=42x0`：高不足 1%）必须至少撑到一行高：
            // 否则擦字擦不到、盖板也只有一条薄片 → 原文留着（截图里那行乱码韩文）。
            val sized = SeamIncomingGeometry.enforceMinimumHeight(
                box = SeamIncomingGeometry.Box(leftPx, topPx, rightPx, bottomPx),
                lineHeightPx = lineHeightPx,
                limitBottom = heightF
            )
            topPx = sized.top
            bottomPx = sized.bottom
            leftPx = sized.left
            rightPx = sized.right
            if (SeamIncomingGeometry.isDegenerate(sized)) return@mapIndexedNotNull null
            // 本图可见区域 → 拼接图坐标（+topH）→ 百分比。
            val topPercent = (topPx + topH) / stitchedH * 100f
            val heightPercent = (bottomPx - topPx) / stitchedH * 100f
            val leftPercent = leftPx / stitchedW * 100f
            val widthPercent = (rightPx - leftPx) / stitchedW * 100f
            Bubble(
                x = leftPercent,
                y = topPercent,
                w = widthPercent,
                h = heightPercent,
                bgType = if (part.narration) "none" else "solid",
                // 原文文本非空：擦字/清理图按「有文字要擦」处理（用译文占位即可，只用于触发擦除）。
                originalText = part.translatedText.ifBlank { " " },
                translatedText = "",
                contentX = leftPercent,
                contentY = topPercent,
                contentW = widthPercent,
                contentH = heightPercent
            )
        }
    }

    /**
     * 两个框的并集（[extra] 为 null 时原样返回 [base]）。
     * 用途：精修只允许**缩小**框，但倾角估计窗口不允许被这种缩小带到字形以内。
     */
    private fun unionRect(base: RectF, extra: RectF?): RectF {
        if (extra == null || extra.width() <= 0f || extra.height() <= 0f) return base
        return RectF(
            minOf(base.left, extra.left),
            minOf(base.top, extra.top),
            maxOf(base.right, extra.right),
            maxOf(base.bottom, extra.bottom)
        )
    }

    /**
     * 绕外接框中心外扩**固定像素数**，返回百分比四边形（8 个数）。
     *
     * 外接框在两个方向分别放大 `2·padPx`，再把四边形按同样的 scaleX/scaleY 绕中心缩放 ——
     * 于是外接框每侧正好多出 `padPx` 像素，斜排的两端各多出约 `padPx`。
     */
    private fun inflatePercentQuadByPx(
        flat: List<Float>,
        padPx: Float,
        imageWidthPx: Int,
        imageHeightPx: Int
    ): List<Float> {
        if (flat.size != 8 || padPx <= 0f || imageWidthPx <= 0 || imageHeightPx <= 0) return flat
        val box = percentQuadBounds(flat) ?: return flat
        val widthPx = box[2] / 100f * imageWidthPx
        val heightPx = box[3] / 100f * imageHeightPx
        if (widthPx <= 0f || heightPx <= 0f) return flat
        val scaleX = (widthPx + padPx * 2f) / widthPx
        val scaleY = (heightPx + padPx * 2f) / heightPx
        val cx = box[0] + box[2] / 2f
        val cy = box[1] + box[3] / 2f
        return List(8) { i ->
            if (i % 2 == 0) cx + (flat[i] - cx) * scaleX else cy + (flat[i] - cy) * scaleY
        }
    }

    /** 百分比四边形（8 个数）的外接框 `[x, y, w, h]`；非法输入返回 null。 */
    private fun percentQuadBounds(flat: List<Float>): FloatArray? {
        if (flat.size != 8) return null
        val xs = listOf(flat[0], flat[2], flat[4], flat[6])
        val ys = listOf(flat[1], flat[3], flat[5], flat[7])
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        if (maxX - minX <= 0f || maxY - minY <= 0f) return null
        return floatArrayOf(minX, minY, maxX - minX, maxY - minY)
    }

    /**
     * 四边形的**厚度**（两条「短边」的平均长度，像素）—— 一行字的真实字高就是这个。
     *
     * ⚠️ 不要拿四边形**外接框的高**当字高：斜排文字的包络高 = 行长·sinθ + 字高·cosθ。
     * 实测 19° 的斜排：真实字高 38px，外接框高 125px（3.3 倍）→ 译文字号被算成大 3 倍
     * （用户 2026-09-30 反馈「倾斜文字的高度比起原文太高」）。
     */
    // 四边形厚度换算搬到 [percentQuadThicknessPx]（app/QuadMetrics.kt 的顶层函数）——
    // 留在类里单测就得起一个 WebtoonImageTranslator 实例，单位口径这么容易错的东西必须能直接测。

    /** 无四边形气泡的墨迹几何：收紧后的框 + （斜排时）最小面积外接矩形顶点。 */
    private data class InkGeometry(val rect: RectF, val quad: List<com.manga.translator.translate.QuadPoint>?)

    /** 倾角估计的诊断累积串（每张图一条日志，便于核对「这次为什么没转」）。 */
    private val tiltProbe = StringBuilder()

    /** 每张图开始时清空倾角诊断（在 [resolveFinalBubbles] 入口调用）。 */
    private fun resetTiltProbe() {
        tiltProbe.setLength(0)
    }

    /** 倾角诊断：累积到 [pendingTiltProbe]，由 [translateStitched] 统一打日志。 */
    private fun flushTiltProbe() {
        pendingTiltProbe = tiltProbe.toString().trim().ifEmpty { null }
        tiltProbe.setLength(0)
    }

    /** 本轮倾角诊断（[flushTiltProbe] 写入，[translateStitched] 读取）。 */
    private var pendingTiltProbe: String? = null

    /**
     * 没有 OCR 四边形（= ML Kit）时的几何重建。三步：
     * 1. 把偏松的块级框**收紧到墨迹范围**（`TextInkBounds`），遮罩/底色不再「超出气泡框」；
     * 2. 求墨迹的**最小面积外接矩形**（`TextTiltEstimator`）——斜排文字的 AABB 比字形宽得多，
     *    必须用外接矩形本身，而不是「把 AABB 转一下」；
     * 3. 斜排时返回外接矩形的顶点（当四边形用）+ 它的外接框（当排版/遮罩框用，比原 AABB 紧）。
     *
     * 横排（倾角不足 3°）时只做第 1 步，保持原有排版行为。
     *
     * @param rect 精修后的内容框（像素）—— 墨迹收紧与兜底都用它。
     * @param tiltWindow **倾角估计**专用窗口（像素，≥ [rect]）：精修会刮掉气泡描边那一条，
     *   一旦砍到字形，最小外接矩形就只剩中段 → 斜排两端露字。所以窗口取「精修框 ∪ OCR 原框」。
     */
    private fun resolveInkGeometry(
        stitched: Bitmap,
        rect: RectF,
        tiltWindow: RectF,
        vertical: Boolean
    ): InkGeometry {
        val tight = tightenRectToInk(stitched, rect) ?: rect
        if (vertical) return InkGeometry(tight, null)
        // ⚠️ 倾角必须在**足够大的**框上估计，不能用收紧后的框：
        // 收紧框只是墨迹的 AABB，斜排文字的笔画端点常常落在它的外扩上限之外，
        // 一裁就把长条裁短、纵横比掉到闸门以下 → 估计失败 → 整个遮罩退回轴对齐。
        // 实测同一页两次识别，用收紧框时一次成功、一次失败（倾角时有时无）。
        val (angle, corners) = estimateInkTiltQuadStitched(stitched, tiltWindow, vertical = false)
        if (angle == null || corners == null) {
            tiltProbe.append("none(${tiltWindow.width().toInt()}x${tiltWindow.height().toInt()}) ")
            return InkGeometry(tight, null)
        }
        tiltProbe.append("ok(${angle.toInt()}deg,${tiltWindow.width().toInt()}x${tiltWindow.height().toInt()}) ")
        // ⚠️ 单位换算（2026-09-30 修）：`corners` 是**百分比**（[estimateInkTiltQuadStitched]
        // 的返回口径，要直接喂给 [TextQuadGeometry.stitchedPolygonToSource]），而这里要返回的
        // [InkGeometry.rect] 必须是**像素**（后面的原文行高、排版框、清理图 mask 全在像素空间）。
        // 忘了换算时：百分比数值被当成像素 → 排版框变成「原图左上角一个约 20×6 px 的小点」
        // → 倾斜文字被塞进只有 3.6px 字号的盒子、5 个字各占一行、位置飘到左上角
        // （用户 2026-09-30 反馈「倾斜文字的位置完全错了、倾斜角度也和原图不一样」——其实角度
        //  19.85° 是对的，是被这个畸形的框毁掉的观感）。
        val xs = corners.map { it.x / 100f * stitched.width }
        val ys = corners.map { it.y / 100f * stitched.height }
        val quadLeft = xs.min()
        val quadTop = ys.min()
        val quadRight = xs.max()
        val quadBottom = ys.max()
        if (quadRight - quadLeft < 2f || quadBottom - quadTop < 2f) {
            return InkGeometry(tight, null)
        }
        // 排版/遮罩框用外接矩形的 AABB：它比「斜排的 OCR 块框」紧得多，
        // 字号按它拟合才对（原来框偏高/偏宽，字号和位置都会偏）。
        return InkGeometry(RectF(quadLeft, quadTop, quadRight, quadBottom), corners)
    }

    /**
     * 把 OCR 给的偏松文字框收紧到**墨迹实际范围**（拼接图像素坐标）。
     *
     * 只用于没有四边形（ML Kit）的气泡：ML Kit 返回的是块级框，含行距/内边距，
     * 直接用会让遮罩和实底范围明显大于字形。收紧后：
     * - 遮罩不再「超出气泡框」；
     * - 字号按真实字高拟合（原来的框偏高，字号会被算大）。
     * 拿不到墨迹（框太小/纯色）时返回 null，调用方退回原框。
     */
    private fun tightenRectToInk(stitched: Bitmap, rect: RectF): RectF? {
        val left = rect.left.toInt().coerceIn(0, stitched.width - 1)
        val top = rect.top.toInt().coerceIn(0, stitched.height - 1)
        val right = rect.right.toInt().coerceIn(left + 1, stitched.width)
        val bottom = rect.bottom.toInt().coerceIn(top + 1, stitched.height)
        if (right - left < 8 || bottom - top < 8) return null
        val bounds = runCatching {
            com.manga.translator.translate.TextInkBounds.findInBitmap(
                stitched, left, top, right, bottom
            )
        }.getOrNull() ?: return null
        val tightLeft = bounds[0].toFloat()
        val tightTop = bounds[1].toFloat()
        val tightRight = bounds[2].toFloat()
        val tightBottom = bounds[3].toFloat()
        // 收紧有效才采用：明显变小（面积 < 原框 92%）时才替换，避免为了几像素抖动改布局。
        val originalArea = (right - left).toFloat() * (bottom - top)
        val tightArea = (tightRight - tightLeft) * (tightBottom - tightTop)
        if (tightArea <= 0f || tightArea >= originalArea * 0.92f) return null
        return RectF(tightLeft, tightTop, tightRight, tightBottom)
    }

    /**
     * 没有 OCR 四边形时的倾角兜底：按内容框内墨迹的**最小面积外接矩形**给出文字方向与顶点。
     *
     * 返回 `Pair(倾角?, 顶点?)`，顶点是**拼接图百分比**（`stitchedPolygonToSource` 的输入口径，
     * 不是像素！2026-09-29 踩过：换成外接矩形顶点后误把像素坐标直接喂给它，percent↔px 混用
     * 让四边形被渲染层 clamp 成退化形状，表现为「角度时有时无、宽度不对」）。
     *
     * 顶点用外接矩形本身，而不是「把 OCR 轴对齐框转一下」—— 斜排文字的 AABB 本来就比字形宽，
     * 旋转 AABB 会又宽又偏。竖排气泡直接跳过（主轴本来就接近竖直）。
     */
    private fun estimateInkTiltQuadStitched(
        stitched: Bitmap,
        rect: RectF,
        vertical: Boolean
    ): Pair<Float?, List<com.manga.translator.translate.QuadPoint>?> {
        if (vertical) return null to null
        val left = rect.left.toInt().coerceIn(0, stitched.width - 1)
        val top = rect.top.toInt().coerceIn(0, stitched.height - 1)
        val right = rect.right.toInt().coerceIn(left + 1, stitched.width)
        val bottom = rect.bottom.toInt().coerceIn(top + 1, stitched.height)
        if (right - left < 12 || bottom - top < 12) return null to null
        val estimate = runCatching {
            com.manga.translator.translate.TextTiltEstimator.estimateRectFromBitmap(
                stitched, left, top, right, bottom
            )
        }.getOrNull() ?: return null to null
        // 外接矩形是相对裁剪区域左上角 → 加回 left/top 得到拼接图像素 → 再折成拼接图百分比。
        val corners = estimate.corners.map { point ->
            com.manga.translator.translate.QuadPoint(
                x = (point.x + left) / stitched.width * 100f,
                y = (point.y + top) / stitched.height * 100f
            )
        }
        return estimate.angleDeg to corners
    }

    /** 整页清理图 → 原图区域 JPEG → mtlocal 短 URL；拿不到/失败返回 null（覆盖层保持透明）。 */
    private fun exportCleanedImage(cleanedImage: Bitmap?, topH: Int, sourceHeight: Int): String? {
        val bitmap = cleanedImage ?: return null
        if (bitmap.isRecycled || sourceHeight <= 0) return null
        return runCatching {
            val cropHeight = minOf(sourceHeight, bitmap.height - topH)
            if (cropHeight <= 0) return@runCatching null
            val region = if (topH > 0 || cropHeight < bitmap.height) {
                Bitmap.createBitmap(bitmap, 0, topH.coerceAtMost(bitmap.height - 1), bitmap.width, cropHeight)
            } else {
                bitmap
            }
            val bos = ByteArrayOutputStream()
            region.compress(Bitmap.CompressFormat.JPEG, CLEANED_JPEG_QUALITY, bos)
            if (region !== bitmap) region.recycle()
            val token = "c${System.nanoTime()}"
            BrowserOverlayImageStore.put(token, bos.toByteArray(), "image/jpeg")
            "https://mtlocal.overlay/$token.jpg"
        }.getOrNull()
    }

    /**
     * 渲染当前图：原图 + 贴片/译文（含跨图气泡的本图部分）+ 上一张图移交的剩余部分。
     *
     * @return 成图 + 字号诊断串（`字号/原文行高`，用于核对「译文字号是否与原文一致」）。
     */
    private fun renderSlice(
        source: Bitmap,
        topH: Int,
        bubbles: List<Bubble>,
        prep: ImageVisualPrep,
        incomingCross: List<CrossBubblePart>,
        geometries: List<FinalBubble?>
    ): Pair<Bitmap, String> {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(source, 0f, 0f, null)
        val fontProbe = StringBuilder()

        // 上一张图移交的跨图气泡：贴片 + 续排文字（contentRect 顶边可能为负）。
        incomingCross.forEach { part ->
            if (!part.patchBitmap.isRecycled) {
                canvas.drawBitmap(part.patchBitmap, null, part.patchDest, patchPaint)
            }
            val size = renderer.drawText(
                canvas = canvas,
                text = part.translatedText,
                rect = part.contentRect,
                colors = part.colors,
                narration = part.narration,
                fixedFontSizePx = part.fontSizePx,
                alignment = part.alignment,
                // 斜排原文的续排也要斜（2026-09-30）：整图兜底链路历史上完全没有旋转支持，
                // 于是「DOM 里斜、回退整图就不斜」。
                rotationDeg = pixelTiltDegFromPercentQuad(part.sourcePolygon, source.width, source.height),
                needsStroke = com.manga.translator.overlay.BackgroundOutlinePolicy.needsOutline(
                    background = prep.cleanedImage,
                    rect = part.contentRect,
                    bgType = if (part.narration) "none" else "solid",
                    originalHadOutline = part.colors.originalHadOutline
                )
            )
            fontProbe.append("in:${size.toInt()}${alignTag(part.alignment)} ")
        }

        // 本图拥有的气泡：坐标整体上移 topH（拼接图 → 原图坐标），画布边界自动裁掉越界部分。
        bubbles.forEachIndexed { index, bubble ->
            val geometry = geometries.getOrNull(index) ?: return@forEachIndexed
            val patch = prep.patchFor(index)
            if (patch != null && !patch.bitmap.isRecycled) {
                val dest = RectF(
                    patch.srcRect.left,
                    patch.srcRect.top - topH,
                    patch.srcRect.right,
                    patch.srcRect.bottom - topH
                )
                canvas.drawBitmap(patch.bitmap, null, dest, patchPaint)
            }
            val colors = prep.colors[index] ?: BubbleColors(Color.BLACK, Color.WHITE)
            val narration = !bubble.bgType.equals("solid", ignoreCase = true)
            val layout = geometry.layoutRect
            val rect = RectF(layout.left, layout.top - topH, layout.right, layout.bottom - topH)
            val size = renderer.drawText(
                canvas = canvas,
                text = bubble.translatedText.ifBlank { bubble.originalText },
                rect = rect,
                colors = colors,
                narration = narration,
                // 跨图气泡用**与下一张图完全相同的固定字号**（geometry.fixedFontPx）：
                // 整图兜底链路以前在这里自行自适应，于是「上一张图自适应、下一张图固定值」
                // 两半字号不同 → 接缝处错位重影。
                fixedFontSizePx = geometry.fixedFontPx,
                originalLineHeightPx = geometry.lineHeightPx,
                alignment = bubble.alignment,
                rotationDeg = pixelTiltDegFromPercentQuad(
                    geometry.maskPolygonPercent,
                    source.width,
                    source.height
                ),
                // 背景单色就不描边（判据与 DOM 覆盖层完全一致，两条链路观感才对得上）。
                needsStroke = prep.strokeFlags[index] ?: true
            )
            fontProbe.append("${size.toInt()}/${geometry.lineHeightPx.toInt()}${alignTag(bubble.alignment)} ")
        }
        return output to fontProbe.toString().trim()
    }

    /**
     * 百分比四边形 → **画布像素空间**的文字倾角（度，右倾为正）。
     *
     * 画布上 rotate 作用在像素上，而四边形存的是百分比（x/y 缩放不同，角度不相等），
     * 所以必须换算（与渲染层 [applyAspectCorrectedTilt] 同一口径：取最长边的方向）。
     * 无四边形 / 角度小于 [MIN_RENDER_TILT_DEG] / 超过 60°（多半是竖排误判）返回 null。
     */
    private fun pixelTiltDegFromPercentQuad(flat: List<Float>?, widthPx: Int, heightPx: Int): Float? {
        if (flat == null || flat.size != 8 || widthPx <= 0 || heightPx <= 0) return null
        var longest = -1f
        var longestDeg = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val dx = (flat[j * 2] - flat[i * 2]) / 100f * widthPx
            val dy = (flat[j * 2 + 1] - flat[i * 2 + 1]) / 100f * heightPx
            val length = kotlin.math.hypot(dx, dy)
            if (length <= longest) continue
            longest = length
            var deg = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
            while (deg >= 90f) deg -= 180f
            while (deg < -90f) deg += 180f
            longestDeg = deg
        }
        val magnitude = kotlin.math.abs(longestDeg)
        if (magnitude < MIN_RENDER_TILT_DEG || magnitude >= MAX_RENDER_TILT_DEG) return null
        return longestDeg
    }

    /** 诊断用：对齐方式缩写（L/C/R）。 */
    private fun alignTag(alignment: String): String {
        return when (TextAlignment.normalize(alignment)) {
            TextAlignment.LEFT -> "L"
            TextAlignment.RIGHT -> "R"
            else -> "C"
        }
    }

    private fun buildStitched(source: Bitmap, topStrip: Bitmap?, bottomStrip: Bitmap?): Bitmap {
        val topH = topStrip?.height ?: 0
        val botH = bottomStrip?.height ?: 0
        if (topH == 0 && botH == 0) return source
        val width = maxOf(maxOf(source.width, topStrip?.width ?: 0), bottomStrip?.width ?: 0)
        val stitched = Bitmap.createBitmap(width, topH + source.height + botH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(stitched)
        canvas.drawColor(Color.BLACK)
        topStrip?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        canvas.drawBitmap(source, 0f, topH.toFloat(), null)
        bottomStrip?.let { canvas.drawBitmap(it, 0f, (topH + source.height).toFloat(), null) }
        return stitched
    }

    private fun percentRectToPx(rect: NormalizedRect, width: Int, height: Int): RectF {
        val left = ((rect.left / 100f) * width).coerceIn(0f, width.toFloat())
        val top = ((rect.top / 100f) * height).coerceIn(0f, height.toFloat())
        val right = ((rect.right / 100f) * width).coerceIn(0f, width.toFloat())
        val bottom = ((rect.bottom / 100f) * height).coerceIn(0f, height.toFloat())
        return RectF(left, top, right, bottom)
    }

    /** RectF → 接缝纯几何类型（[SeamBubbleMerger] 不依赖 android.graphics，便于单测）。 */
    private fun RectF.toSeamBox(): SeamBox = SeamBox(left, top, right, bottom)

    // -----------------------------------------------------------------------
    // 气泡检测：气泡区域优先（与悬浮球链路同策略；无气泡结果时回退纯 OCR 分组）。
    // -----------------------------------------------------------------------

    private suspend fun detectBubbles(
        bitmap: Bitmap,
        settings: AppSettings,
        timer: TranslationTimer,
        /** 拼接图里本图下边界的 y（= topStrip + 本图高）；分组时用它挡「跨缝两块被并成一句」。 */
        bottomLimit: Float = 0f,
        /**
         * 拼接图里本图**上边界**的 y（= `topH`）：上方的条带属于上一张图。
         *
         * 两侧的文字永远不是同一段（真机 `[32317270]`：条带里的尾行与本图顶部那半行被并成
         * `방해됐단양이o를 하려면 죽거나`，随后整块被当成「上一张已画过」丢掉）。
         */
        topLimit: Float = 0f
    ): TranslationResult {
        timer.mark("request_send", "stage=ocr mode=bubble_first_webtoon provider=local")
        val (bubbleRegions, structureBlocks) = coroutineScope {
            val regions = async(ioDispatcher) {
                ServiceLocator.bubbleRegionDetector.detect(bitmap)
            }
            val blocks = async(ioDispatcher) {
                ServiceLocator.localTextDetector.detect(bitmap, settings.sourceLanguage)
            }
            regions.await() to blocks.await()
        }
        timer.mark("bubble_region_stats", "detected=${bubbleRegions.size} blocks=${structureBlocks.size}")
        // OCR 原始块清单：**只在检测到文字但没产出气泡时才打**（见下面的 ocr_texts_* 日志）。
        // 为什么要它：用户报「某句话没翻译」时，光看 `bubbles=N` 分不清是
        // 「OCR 压根没认出来」还是「认出来了但没分组」。把块的原样文本与坐标带出来，
        // 一眼就能分辨（2026-09-30 实测：日记里缺的那句既不在 erase_target、
        // 也不在任何分组结果里 —— 必须先证明 OCR 到底看没看到它）。
        val ocrBlockDigest = structureBlocks.take(6).joinToString(" ˌ ") { block ->
            val text = block.text.replace('\n', '⏎').take(18)
            "$text@" + "${block.left.toInt()},${block.top.toInt()}," +
                "${(block.right - block.left).toInt()}x${(block.bottom - block.top).toInt()}"
        }
        // 行块级全量清单（2026-10-12 接缝「误吸邻页泡」取证）：`ocr_texts_*` 只在分组失败/
        // 丢块时打且截断到 6 块，跨缝排查需要**全部**行块的拼接图像素坐标 —— 接缝条带里的
        // 残行（被条带边缘切开）框在哪里、和最终气泡框差多少，看这一行就能对出来。
        if (structureBlocks.isNotEmpty()) {
            timer.mark(
                "ocr_blocks_all",
                "blocks=${structureBlocks.size} img=${bitmap.width}x${bitmap.height} :: " +
                    structureBlocks.joinToString(" ˌ ") { block ->
                        val text = block.text.replace('\n', '⏎').take(12)
                        "$text@" + "${block.left.toInt()},${block.top.toInt()}," +
                            "${(block.right - block.left).toInt()}x${(block.bottom - block.top).toInt()}"
                    }
            )
        }
        // 缝感知分组（2026-10-03）：把**页面切缝**告诉 planner —— 拼接图里「上一气泡的尾行」
        // 与「下一气泡的首行」几何上常常紧挨着，纯邻近合并会把两个气泡的台词并成一句
        // （真机 `erase_target` 实测 `…두 가지 밖에 없는데.! 악질들한테 걸리기라도 …`）。
        // 2026-10-03 补上**上边界**（条带与本图之间）：条带里是上一张图的字，与本图的字
        // 永不合并（真机 `[32317270]` 的 `방해됐단양이o를 하려면 죽거나` 就是跨这条边界并出来的）。
        // 用完立刻复位：planner 是复用的，别把这条缝带给悬浮球链路。
        val seamGrace = run {
            val lines = structureBlocks.map { it.bottom - it.top }.filter { it.isFinite() && it > 0f }.sorted()
            val medianLine = if (lines.isEmpty()) 0f else lines[lines.size / 2]
            if (medianLine > 0f) maxOf(6f, medianLine * 0.35f) else 8f
        }
        bubbleFirstRegionPlanner.setSeamBoundaries(
            topBoundaryPx = topLimit,
            bottomBoundaryPx = bottomLimit,
            cutGracePx = seamGrace
        )
        // 合并判决诊断（2026-10-03）：把「接近但被拒」的出对汇总成一行 ——
        // 排查「两个气泡被并成一段」时，这一行直接给出被拒原因与几何。
        val mergeRejects = mutableListOf<BlockMergeReject>()
        bubbleFirstRegionPlanner.setMergeDiagnostics(mergeRejects)
        val routing = try {
            bubbleFirstRegionPlanner.route(
                bubbleRegions = bubbleRegions,
                textBlocks = structureBlocks,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                separatedByWhitespace = null
            )
        } finally {
            bubbleFirstRegionPlanner.setSeamBoundaries(0f, 0f, 0f)
        }
        val assignments = (
            routing.bubbleAssignments.filter { it.blocks.isNotEmpty() } +
                routing.outsideAssignments.filter { it.blocks.isNotEmpty() }
            )
        val bubbles = try {
            assignments.flatMap { assignment ->
                bubbleFirstRegionPlanner.buildBubblesFromAssignment(
                    assignment = assignment,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    separatedByWhitespace = null
                )
            }
        } finally {
            bubbleFirstRegionPlanner.setMergeDiagnostics(null)
        }
        BubbleFirstRegionPlanner.mergeRejectDiag(mergeRejects)?.let { diag ->
            timer.mark("block_merge_reject", diag)
        }
        timer.mark(
            "bubble_first_result_stats",
            "regions=${bubbleRegions.size} assignments=${assignments.size} final=${bubbles.size}"
        )
        // 竖排判定原因（2026-09-30 用户反馈「横排三行韩文被排成竖排」）。
        //
        // 为什么在这里打而不是在 planner 里：planner 是纯 Kotlin、被 JVM 单测直接调用，
        // 用 android.util.Log 会让单测抛 `not mocked`（实测 7 个单测一起挂）。
        // 所以 planner 把原因作为 `layoutReason` 带出来，由这里记录。
        // 只在**确实判成竖排**时打，避免每张图都刷一行噪声。
        bubbles.filter { it.layout.equals("vertical", ignoreCase = true) }.take(4).forEach { b ->
            android.util.Log.d(
                "WebtoonImageTranslator",
                "vertical_layout reason=${b.layoutReason.ifBlank { "unknown" }} " +
                    "box=${b.w.toInt()}x${b.h.toInt()}(%) text=${b.originalText.take(20)}"
            )
        }
        if (bubbles.isEmpty()) {
            timer.mark("bubble_first_fallback", "reason=empty_result mode=local_group")
            // 这里才打 OCR 原文清单：分组失败时最需要知道「OCR 到底认出了什么」。
            // 走到这一步说明「有块但没组出气泡」，块清单就是定位它的唯一线索。
            timer.mark("ocr_texts_nobubble", "blocks=${structureBlocks.size} :: $ocrBlockDigest")
            return groupLocalBlocks(structureBlocks, bitmap.width, bitmap.height, timer)
        }
        timer.mark("response_ok", "stage=ocr bubbles=${bubbles.size}")
        // 「纯色底就当 solid 处理」（2026-10-13 用户：「这个截页处的气泡框是纯色背景，但是它用的是
        // 清理图。希望处理为 solid」）：
        //
        // 真机日志里 `bubble_region_stats detected=0` 的页面占多数 —— 区域检测没命中时气泡一律走
        // 「无区域」分支 → `bg_type=none` → 盖板从**清理图**（inpaint 产物）裁切。白气泡上 inpaint
        // 必然留下擦不干净的灰痕（用户截图里气泡边上的污迹），而这类气泡本来就是一块平底色。
        //
        // 判据是**量出来的**，两条证据任一成立即可（对齐参考项目 `bubble-colors.js` 的
        // adaptiveBackground 升级，并补上它没有覆盖的「OCR 框比气泡宽」现场）：
        // ① 外圈一致率 ≥ 0.92 且底色够亮（文字框整个落在气泡里时这条成立）；
        // ② 容器内部量测：从字形框中心走出一块**不比字形框窄**的亮色平底
        //    （`BubbleInteriorScanner`，环被气泡外的插画污染时靠它）。
        // 两条都不成立（网点/渐变/插画）就什么都不做 —— 平涂色块比灰痕更糟，底线不破。
        val upgrades = mutableListOf<String>()
        val tuned = bubbles.mapIndexed { index, bubble ->
            if (bubble.bgType.equals("solid", ignoreCase = true)) return@mapIndexed bubble
            val sampled = ServiceLocator.bubbleColorExtractor.sampleBackground(bitmap, bubble)
                ?: return@mapIndexed bubble
            val lineCount = renderer.resolveSourceLineCount(bubble.sourceLineCount, bubble.originalText)
            val glyphBox = bubbleContentRectPx(bubble, bitmap.width, bitmap.height)
            val interior = if (sampled.flatBackground) {
                null
            } else {
                ServiceLocator.bubbleInteriorScanner.scan(bitmap, glyphBox, lineCount, sampled.color)
            }
            val flatBackground = sampled.flatBackground ||
                interior?.isFlatBrightContainer(glyphBox.width(), sampled.color) == true
            if (!flatBackground) return@mapIndexed bubble
            upgrades += "b$index=ring%.2f".format(sampled.coverage) +
                (interior?.let { "/flat%.2f".format(it.flatCoverage) } ?: "")
            bubble.copy(bgType = "solid")
        }
        if (upgrades.isNotEmpty()) {
            timer.mark("solid_upgrade", "count=${upgrades.size} evidence=${upgrades.joinToString(" ")}")
        }
        // 正常路径只在「块数明显多于气泡数」时打一行，用于发现「有文字被丢在分组之外」
        // —— 用户报的缺句就是这种形态（块被认出来了，但没被分进任何气泡）。
        if (structureBlocks.size > bubbles.size) {
            timer.mark(
                "ocr_texts_dropped",
                "blocks=${structureBlocks.size} bubbles=${bubbles.size} :: $ocrBlockDigest"
            )
        }
        return TranslationResult(tuned)
    }

    private suspend fun groupLocalBlocks(
        blocks: List<TextBlock>,
        imageWidth: Int,
        imageHeight: Int,
        timer: TranslationTimer
    ): TranslationResult {
        if (blocks.isEmpty()) return TranslationResult(emptyList())
        val selectedGroups = buildHeuristicTextBlockGroups(blocks, imageWidth, imageHeight)
        timer.mark("group_response", "mode=heuristic groups=${selectedGroups.size}")
        val bubbles = selectedGroups.mapNotNull { memberBlocks ->
            buildLocalBubbleFromGroup(memberBlocks, imageWidth, imageHeight)
        }.filter { it.originalText.isNotBlank() && it.w > 0f && it.h > 0f }
        return TranslationResult(bubbles)
    }

    private fun buildHeuristicTextBlockGroups(
        blocks: List<TextBlock>,
        imageWidth: Int,
        imageHeight: Int
    ): List<List<TextBlock>> {
        if (blocks.isEmpty()) return emptyList()
        val sortedBlocks = blocks.sortedWith(compareBy<TextBlock> { it.left }.thenBy { it.top })
        val parent = IntArray(sortedBlocks.size) { it }

        fun find(x: Int): Int {
            var node = x
            while (parent[node] != node) {
                parent[node] = parent[parent[node]]
                node = parent[node]
            }
            return node
        }

        for (i in sortedBlocks.indices) {
            for (j in (i + 1) until sortedBlocks.size) {
                val a = sortedBlocks[i]
                val b = sortedBlocks[j]
                // 2026-10-03：与 planner 同源，用收严后的行高判据（阈值以**行高**为单位）。
                val merge = OcrBlockMergeRules.shouldMerge(a, b).merge
                if (merge) {
                    val pa = find(i)
                    val pb = find(j)
                    if (pa != pb) parent[pb] = pa
                }
            }
        }

        val grouped = linkedMapOf<Int, MutableList<TextBlock>>()
        sortedBlocks.forEachIndexed { index, block ->
            val root = find(index)
            grouped.getOrPut(root) { mutableListOf() }.add(block)
        }
        return grouped.values.map { members ->
            members.sortedWith(compareBy<TextBlock> { it.top }.thenBy { it.left })
        }
    }

    private fun buildLocalBubbleFromGroup(
        memberBlocks: List<TextBlock>,
        imageWidth: Int,
        imageHeight: Int
    ): Bubble? {
        if (memberBlocks.isEmpty()) return null
        val rawLeft = memberBlocks.minOf { it.left }.coerceIn(0f, imageWidth.toFloat())
        val rawTop = memberBlocks.minOf { it.top }.coerceIn(0f, imageHeight.toFloat())
        val rawRight = memberBlocks.maxOf { it.right }.coerceIn(0f, imageWidth.toFloat())
        val rawBottom = memberBlocks.maxOf { it.bottom }.coerceIn(0f, imageHeight.toFloat())
        val rawWidth = maxOf(1f, rawRight - rawLeft)
        val rawHeight = maxOf(1f, rawBottom - rawTop)
        val padX = maxOf(LOCAL_BUBBLE_MIN_PADDING_PX, rawWidth * LOCAL_BUBBLE_PADDING_RATIO)
        val padY = maxOf(LOCAL_BUBBLE_MIN_PADDING_PX, rawHeight * LOCAL_BUBBLE_PADDING_RATIO)
        val leftPx = (rawLeft - padX).coerceIn(0f, imageWidth.toFloat())
        val topPx = (rawTop - padY).coerceIn(0f, imageHeight.toFloat())
        val rightPx = (rawRight + padX).coerceIn(0f, imageWidth.toFloat())
        val bottomPx = (rawBottom + padY).coerceIn(0f, imageHeight.toFloat())
        val widthPx = maxOf(1f, rightPx - leftPx)
        val heightPx = maxOf(1f, bottomPx - topPx)
        val originalText = memberBlocks
            .sortedWith(compareBy<TextBlock> { it.top }.thenBy { it.left })
            .joinToString(separator = "\n") { it.text.trim() }
            .trim()
        if (originalText.isBlank()) return null
        val quad = TextQuadGeometry.quadOf(memberBlocks, imageWidth, imageHeight)
        val lineBoxesPercent = memberBlocks.flatMap { block -> block.lineBoxes.orEmpty() }
            .filter { it.size >= 4 }
            .map { lb ->
                floatArrayOf(
                    (lb[0] / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
                    (lb[1] / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
                    (lb[2] / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
                    (lb[3] / imageHeight.toFloat() * 100f).coerceIn(0f, 100f)
                )
            }
            .takeIf { it.isNotEmpty() }
        return Bubble(
            x = (leftPx / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
            y = (topPx / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
            w = (widthPx / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
            h = (heightPx / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
            bgType = "solid",
            originalText = originalText,
            translatedText = "",
            layout = "horizontal",
            contentX = (rawLeft / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
            contentY = (rawTop / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
            contentW = (rawWidth / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
            contentH = (rawHeight / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
            // 本地兜底分组同样按逐行框推断原文对齐
            alignment = TextAlignment.infer(memberBlocks, imageWidth.toFloat()),
            sourceLineCount = TextLayoutMetrics.countVisualLines(memberBlocks),
            polygon = quad.first,
            rotationDeg = quad.second,
            lineBoxes = lineBoxesPercent
        )
    }

    // -----------------------------------------------------------------------
    // 视觉预处理：擦字贴片 + 内容框（像素） + 气泡配色（在拼接图坐标系内）
    // -----------------------------------------------------------------------

    private data class ImageVisualPrep(
        /**
         * 按气泡下标索引的擦字贴片。下标口径 = **`prepareVisuals` 收到的气泡列表**（未过滤），
         * 与 `finalBubbles` / `geometries` / 移交列表同一套下标：`erasePage` 的 `bubbleId` 是
         * 「它收到的那个列表」的下标，擦字目标被 `isWorthErasing` 过滤过，取用时已按原下标重编。
         */
        val patchesByBubbleId: Map<Int, ErasePatch>,
        /** 精修后的内容框（像素，拼接图坐标）—— 遮罩/实底范围用它。 */
        val contentRects: Map<Int, RectF>,
        /**
         * **未精修**的 OCR 内容框（像素，拼接图坐标）—— 只给「倾角估计窗口」用。
         *
         * 为什么要单独留一份：精修（[WritableAreaRefiner]）的本职是**刮掉气泡描边那一条**，
         * 一旦它把框砍小，斜排文字的**最小外接矩形就只包得住中段** —— 两端露字
         * （2026-09-30 实测：`283x136` 被砍成 `182x95`，斜排两端各露 40+ px）。
         * 倾角必须在**足够大**的窗口上估计，所以这里保留原框作为窗口下限。
         */
        val ocrRects: Map<Int, RectF>,
        val colors: Map<Int, BubbleColors>,
        /**
         * 按气泡下标索引的「要不要给译文加描边」（2026-09-30 用户要求
         * 「白边黑边只在背景有多种颜色的时候有」）。
         *
         * 判定读的是**清理图**里这一块的亮度离散度（见 `BackgroundOutlinePolicy`）。
         * 缺项（旧数据/拿不到背景）= 保留描边，行为与改动前一致。
         */
        val strokeFlags: Map<Int, Boolean> = emptyMap(),
        /** 整页清理图（拼接图坐标系）；由调用方在场景导出/贴片用完后统一回收。 */
        val cleanedImage: Bitmap? = null,
        /** 「清理图」是否真的用上了（未用上要显式告诉用户，不能静默回退）。 */
        val cleanedAvailable: Boolean = false,
        val patchCount: Int = 0,
        val cleanedPatchedCount: Int = 0
    ) {
        /** 贴片列表可能因空文本/失败而缺项，用 bubbleId 取，避免按下标错位贴错图。 */
        fun patchFor(index: Int): ErasePatch? = patchesByBubbleId[index]
    }

    /**
     * @param includeSolidInpaint 「精细擦字」开关（浏览器设置，默认开）。两条路线的分工是**硬**的
     *   （2026-10-11 用户要求：「开启的时候最符合原文，关闭的时候只考虑速度，只要显示出来译文就行」）：
     *   - **开 = 质量优先**：整页清理图必建（纯色气泡直填背景色、旁白墨迹级 inpaint），每个气泡的
     *     盖板背景都来自它 → 与原文最接近；
     *   - **关 = 速度优先**：一张清理图都不建（整页 inpaint + 整页 JPEG 导出全省），逐气泡策略
     *     `FAST_ONLY`，`bg=none` 气泡改用**不透明实底盖板**（`flat_cover`，见 [buildScene]）。
     *
     *   （2026-09-28 用户反馈「开不开好像都一样」：旧实现关闭时逐气泡仍走 OPENCV_INPAINT 且照建
     *   清理图，出来的背景和开启几乎一样，既没省时间也没看出区别。）
     */
    private suspend fun prepareVisuals(
        result: TranslationResult,
        source: Bitmap,
        pageHash: String,
        timer: TranslationTimer,
        includeSolidInpaint: Boolean = false
    ): ImageVisualPrep {
        // 只擦「有内容可覆盖」的区域：OCR 读不出原文的区域擦了只会留下白块（2026-09-30 用户
        // 要求「读不出就别擦」）。判据与理由见 EraseTargets.kt —— 不做任何语言假设。
        // 读不出的区域已在上游（translateStitched 的 erase_skip）整块丢弃，这里再过滤是兜底。
        //
        // ⚠️ 下标：`erasePage` 拿「传进去那个列表」的下标当 `bubbleId`，而这里传的是**过滤后**
        // 的列表；下游（`patchFor` / 擦字质量统计 / 跨图移交）用的却是**未过滤**的
        // `result.bubbles` 下标 —— 一旦有气泡被 `isWorthErasing` 刷掉，两边就错位（贴片贴到
        // 别的气泡上）。所以先把「擦字目标 → 原下标」记下来，拿到贴片再按原下标重编。
        val eraseTargetIndices = result.bubbles.withIndex()
            .filter { isWorthErasing(it.value) }
            .map { it.index }
        val eraseTargets = eraseTargetIndices.map { result.bubbles[it] }
        timer.mark("erase_targets", eraseTargetsDiag(result.bubbles))
        result.bubbles.forEachIndexed { i, b -> timer.mark("erase_target", eraseTargetDetailDiag(i, b)) }
        // 「精细擦字」开 = **质量优先**：总是建整页清理图（纯色气泡直填背景色、旁白走墨迹级
        // inpaint），所有气泡的背景都从它来 —— 与原文最接近。
        //
        // 「精细擦字」关 = **速度优先**（2026-10-11 用户明确要求：「关闭的时候只考虑速度，
        // 只要显示出来译文就行」）：一张清理图都不建 —— 整页 inpaint 与整页 JPEG 导出全省掉
        // （实测 273–700ms/张），旁白/透明块改用**逐气泡擦字贴片**当盖板背景（渲染层既有的
        // 旁白/透明块改用**不透明实底盖板**把原文盖死（`flat_cover`）。因此「关闭」是真的完全不跑 inpaint。
        val needsCleanedImage = includeSolidInpaint
        val cleanedImage = if (needsCleanedImage) {
            ServiceLocator.cleanedImageProcessor.buildCleanedImage(
                source = source,
                bubbles = eraseTargets,
                pageHash = pageHash,
                includeSolid = includeSolidInpaint
            )
        } else {
            null
        }
        timer.mark(
            "cleaned_image",
            when {
                cleanedImage != null ->
                    "built=${cleanedImage.width}x${cleanedImage.height} include_solid=$includeSolidInpaint"

                needsCleanedImage -> "skipped=build_failed include_solid=$includeSolidInpaint"
                // 关闭「精细擦字」= 速度优先：整页清理图**完全不建**（不跑整页 inpaint、不导出
                // 整页 JPEG）。旁白/透明块的盖板改用不透明实底（场景下发的 flat_cover）。
                else -> "skipped=fast_mode"
            }
        )
        val erasePolicy = if (includeSolidInpaint) {
            com.manga.translator.overlay.EraseStrategyPolicy.OPENCV_WHEN_AVAILABLE
        } else {
            com.manga.translator.overlay.EraseStrategyPolicy.FAST_ONLY
        }
        timer.mark("erase_mode", "inpaint_clean=$includeSolidInpaint policy=${erasePolicy.name}")
        val patches = ServiceLocator.eraseProcessor.erasePage(
            source = source,
            bubbles = eraseTargets,
            pageHash = pageHash,
            policy = erasePolicy,
            onPerf = { tag, extra -> timer.mark(tag, extra) },
            cleanedImage = cleanedImage,
            cleanedForAllBubbles = includeSolidInpaint
        )
        // 清理图不在这里回收：覆盖层模式要把它导出成 JPEG（buildScene），
        // 统一由 translateStitched 收尾释放。
        val ocrRects = result.bubbles.mapIndexed { index, bubble ->
            index to bubbleContentRectPx(bubble, source.width, source.height)
        }.toMap()
        val contentRects = result.bubbles.mapIndexed { index, bubble ->
            val raw = ocrRects[index] ?: bubbleContentRectPx(bubble, source.width, source.height)
            index to writableAreaRefiner.refine(source, raw, bubble.bgType)
        }.toMap()
        // 精修前后对比（2026-09-30）：精修**不该**把框缩得比 OCR 原文框小很多 ——
        // 一旦缩过头，后面的墨迹收紧/倾角估计/遮盖轮廓全都在一个过小的窗口里做，
        // 表现为「斜排文字两端总有字没被遮住」。
        timer.mark(
            "refine_boxes",
            result.bubbles.mapIndexed { index, bubble ->
                val raw = ocrRects[index] ?: bubbleContentRectPx(bubble, source.width, source.height)
                val refined = contentRects[index]
                "b$index=${raw.width().toInt()}x${raw.height().toInt()}" +
                    "@${raw.left.toInt()},${raw.top.toInt()}" +
                    "->${refined?.width()?.toInt() ?: -1}x${refined?.height()?.toInt() ?: -1}" +
                    "@${refined?.left?.toInt() ?: -1},${refined?.top?.toInt() ?: -1}"
            }.joinToString(" ")
        )
        val colors = result.bubbles.mapIndexed { index, bubble ->
            index to ServiceLocator.bubbleColorExtractor.extract(source, bubble)
        }.toMap()
        // 配色诊断：`b0=#文字色/#底色` —— 用户反馈「彩色字被压成黑色」时直接看这行。
        timer.mark(
            "sampled_colors",
            colors.entries.sortedBy { it.key }.joinToString(" ") { (index, color) ->
                "b$index=#%06X/#%06X".format(color.textColor and 0xFFFFFF, color.bgColor and 0xFFFFFF)
            }
        )
        timer.mark("erase_patch_count", "count=${patches.size}")
        // 描边开关：只在「背景有多种颜色 **或** 原文自带描边」时才给译文加白边/黑边
        // （2026-09-30 用户要求）。背景取**清理图**——原图那一块还压着原文笔画，
        // 深浅笔画本身就会把跨度拉满。
        val strokeFlags = result.bubbles.mapIndexed { index, bubble ->
            val sampled = colors[index] ?: BubbleColors(Color.BLACK, Color.WHITE)
            index to com.manga.translator.overlay.BackgroundOutlinePolicy.needsOutline(
                background = cleanedImage,
                rect = contentRects[index],
                bgType = bubble.bgType,
                originalHadOutline = sampled.originalHadOutline
            )
        }.toMap()
        // 描边诊断：`b0=plain` 背景单色且原文无描边（不加边）/ `b0=busy` 背景多色 /
        // `b0=outline` 原文自带描边 / `b0=solid` 实底气泡（本来就不描边）。
        // 用户反馈「看不清 / 描边怎么没了」时，先看这行是「该关没关」还是「该开没开」。
        timer.mark(
            "outline_need",
            result.bubbles.mapIndexed { index, bubble ->
                val sampled = colors[index] ?: BubbleColors(Color.BLACK, Color.WHITE)
                "b$index=" + com.manga.translator.overlay.BackgroundOutlinePolicy.describe(
                    background = cleanedImage,
                    rect = contentRects[index],
                    bgType = bubble.bgType,
                    originalHadOutline = sampled.originalHadOutline
                )
            }.joinToString(" ")
        )
        val patchesByBubbleId = patches.associateBy { patch ->
            eraseTargetIndices.getOrElse(patch.bubbleId) { patch.bubbleId }
        }
        // 擦字质量观测量（2026-09-28 用户反馈「清理图有时候不生效」）：
        // 逐气泡比较「原图内容框里的墨迹占比」与「贴片上同一区域的墨迹占比」，
        // 残墨比 residual 接近 0 = 原字被擦干净；接近 1 = 贴片里还带着原字（没擦到/没贴片）。
        timer.mark("erase_quality", buildEraseQualityReport(source, result.bubbles, patchesByBubbleId))
        return ImageVisualPrep(
            patchesByBubbleId = patchesByBubbleId,
            contentRects = contentRects,
            ocrRects = ocrRects,
            colors = colors,
            strokeFlags = strokeFlags,
            cleanedImage = cleanedImage,
            cleanedAvailable = cleanedImage != null,
            patchCount = patches.size,
            cleanedPatchedCount = patches.count { it.fromCleanedImage }
        )
    }

    /**
     * 「精细擦字」没生效时给一句明确提示（2026-09-28 用户反馈「有时候不生效」——
     * 以前是静默回退到逐气泡贴片，用户只看到效果变差、不知道为什么）。
     */
    private fun describeCleanupIssue(includeSolid: Boolean, prep: ImageVisualPrep): String? {
        if (!includeSolid || prep.patchCount == 0) return null
        return when {
            !prep.cleanedAvailable -> "精细擦字不可用，已改用快速贴片"
            prep.cleanedPatchedCount == 0 -> "精细擦字未命中，已改用快速贴片"
            prep.cleanedPatchedCount < prep.patchCount ->
                "精细擦字部分生效 ${prep.cleanedPatchedCount}/${prep.patchCount}"

            else -> null
        }
    }

    /**
     * 擦字残墨统计：对每个气泡，用「内容框内的中位亮度」当背景基准，
     * 统计偏离基准超过阈值的像素占比（墨迹），比较原图与贴片。
     *
     * @return 形如 `bubbles=6 residual_avg=0.03 residual_max=0.41 worst_px=820x260 missing=0`
     */
    private fun buildEraseQualityReport(
        source: Bitmap,
        bubbles: List<Bubble>,
        patches: Map<Int, ErasePatch>
    ): String {
        var sum = 0f
        var worst = 0f
        var worstRect = ""
        var counted = 0
        var missing = 0
        var residueCount = 0
        bubbles.forEachIndexed { index, bubble ->
            if (bubble.originalText.isBlank()) return@forEachIndexed
            val rect = bubbleContentRectPx(bubble, source.width, source.height)
            if (rect.width() < 6f || rect.height() < 6f) return@forEachIndexed
            val before = inkRatio(source, rect, sampleStride = INK_SAMPLE_STRIDE)
            if (before.sampleCount < 16 || before.inkRatio < MIN_INK_RATIO_FOR_CHECK) {
                return@forEachIndexed // 本来就没字（可能 OCR 有偏差），不参与统计
            }
            val patch = patches[index]
            if (patch == null) {
                missing += 1
                residueCount += 1
                sum += 1f
                if (worst < 1f) {
                    worst = 1f
                    worstRect = "${bubble.bgType}:${rect.width().toInt()}x${rect.height().toInt()}@missing"
                }
                counted += 1
                return@forEachIndexed
            }
            val after = inkRatioInPatch(patch, rect, before.medianLuma)
            val residual = if (before.inkRatio <= 0.001f) 0f else after / before.inkRatio
            counted += 1
            sum += residual
            if (residual > RESIDUE_ALERT_RATIO) residueCount += 1
            if (residual > worst) {
                worst = residual
                worstRect = "${bubble.bgType}:${rect.width().toInt()}x${rect.height().toInt()}"
            }
        }
        if (counted == 0) return "bubbles=${bubbles.size} no_text_checked"
        return "bubbles=${bubbles.size} checked=$counted residual_avg=${
            "%.2f".format(sum / counted)
        } residual_max=${"%.2f".format(worst)} worst=$worstRect residue=$residueCount missing=$missing"
    }

    private data class InkStats(val inkRatio: Float, val medianLuma: Float, val sampleCount: Int)

    private fun inkRatio(source: Bitmap, rect: RectF, sampleStride: Int): InkStats {
        val left = rect.left.toInt().coerceIn(0, source.width - 1)
        val top = rect.top.toInt().coerceIn(0, source.height - 1)
        val right = rect.right.toInt().coerceIn(left + 1, source.width)
        val bottom = rect.bottom.toInt().coerceIn(top + 1, source.height)
        val lumas = ArrayList<Float>(64)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                lumas += luma(source.getPixel(x, y))
                x += sampleStride
            }
            y += sampleStride
        }
        return analyse(lumas)
    }

    /** 贴片上同一内容框的墨迹占比（贴片坐标 = 内容框坐标 - srcRect 原点）。 */
    private fun inkRatioInPatch(patch: ErasePatch, rect: RectF, medianLuma: Float): Float {
        val bitmap = patch.bitmap
        if (bitmap.isRecycled) return 1f
        val left = rect.left.toInt().coerceAtLeast(patch.srcRect.left.toInt())
        val top = rect.top.toInt().coerceAtLeast(patch.srcRect.top.toInt())
        val right = rect.right.toInt().coerceAtMost(patch.srcRect.right.toInt())
        val bottom = rect.bottom.toInt().coerceAtMost(patch.srcRect.bottom.toInt())
        if (right - left < 4 || bottom - top < 4) return 1f
        var ink = 0
        var samples = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val px = (x - patch.srcRect.left).toInt().coerceIn(0, bitmap.width - 1)
                val py = (y - patch.srcRect.top).toInt().coerceIn(0, bitmap.height - 1)
                if (kotlin.math.abs(luma(bitmap.getPixel(px, py)) - medianLuma) > INK_DELTA_THRESHOLD) {
                    ink += 1
                }
                samples += 1
                x += INK_SAMPLE_STRIDE
            }
            y += INK_SAMPLE_STRIDE
        }
        if (samples == 0) return 1f
        return ink.toFloat() / samples.toFloat()
    }

    private fun analyse(lumas: List<Float>): InkStats {
        if (lumas.isEmpty()) return InkStats(0f, 0f, 0)
        val sorted = lumas.sorted()
        val median = sorted[sorted.size / 2]
        var ink = 0
        lumas.forEach { if (kotlin.math.abs(it - median) > INK_DELTA_THRESHOLD) ink += 1 }
        return InkStats(
            inkRatio = ink.toFloat() / lumas.size.toFloat(),
            medianLuma = median,
            sampleCount = lumas.size
        )
    }

    private fun luma(color: Int): Float {
        return Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f
    }

    private fun bubbleContentRectPx(bubble: Bubble, imageWidth: Int, imageHeight: Int): RectF {
        return percentRectToPx(BubbleGeometry.contentRect(bubble), imageWidth, imageHeight)
    }

    companion object {
        private const val TAG = "WebtoonImageTranslator"
        private const val LOCAL_BUBBLE_PADDING_RATIO = 0.10f
        private const val LOCAL_BUBBLE_MIN_PADDING_PX = 6f

        /** 残墨统计：采样步长与「算不算墨迹」的亮度差阈值。 */
        private const val INK_SAMPLE_STRIDE = 3
        private const val INK_DELTA_THRESHOLD = 52f
        private const val MIN_INK_RATIO_FOR_CHECK = 0.02f
        private const val RESIDUE_ALERT_RATIO = 0.25f
        /** 覆盖层清理图导出质量（照片型背景，JPEG 0.9 对齐扩展口径）。 */
        private const val CLEANED_JPEG_QUALITY = 90
        /**
         * 接缝带的外扩（像素）：只比气泡轮廓多出一点，够吃掉抗锯齿与 OCR 框的误差。
         *
         * 带越大，导出/贴图的那张清理图切片越大（带宽 × 带高），但也就多几 KB；
         * 带太小则盖板/译文撑开时可能越出带边界（带 root 允许溢出，不影响正确性，只是边界处的
         * 清理图纹理不够用）。3px 与盖板外扩的下限（`SeamBandGeometry.COVER_PAD_MIN_PX`）同口径。
         */
        private const val SEAM_BAND_PAD_PX = 3f

        /**
         * 跨图气泡擦字时，按「移交过来的字形轮廓」夹紧后再外扩的像素数（2026-10-03）。
         *
         * 轮廓本身已经是「字形 + 几像素」，这里再加一点是为了吃掉两图拼接时的
         * 亚像素重采样误差（条带是按本图宽度重采样过的，边缘会有 1~2px 的漂移）。
         */
        private const val CROSS_GLYPH_PAD_PX = 2f

        /**
         * 本图检出与移交框的「冲突」下限（面积比）：达到它说明移交框在侵占本图内容。
         *
         * 见 [filterContestedIncoming]：与 `SeamTuning.SUPPRESS_INCOMING_OVERLAP`（0.6）一起
         * 划出「[0.35, 0.6) = 本图检出伸出了移交框 → 移交框不可信」这一段。
         */
        private const val INCOMING_CONTEST_MIN_OVERLAP = 0.35f
        /**
         * 渲染层每个横向内边距占字号的倍数。
         *
         * 必须与 `app/src/main/assets/mt-overlay-renderer.js` 的
         * `.mt-bubble{...;padding:0.12em 0.34em;...}` 保持一致 —— 它是 em 值，会随字号缩放。
         * 算跨图/接缝气泡的固定字号时用它扣掉真实可用宽度，否则译文会折行、溢出气泡。
         */
        private const val DOM_BUBBLE_PADDING_EM = 0.34f

        // 盖板外扩（`COVER_PAD_PX` / `COVER_PAD_RATIO` / `COVER_PAD_MAX_PX`）与 ML Kit 专用档
        // 已全部集中到 `SeamBandGeometry`（见那里的 `coverPadPx` / `coverPadPxFor`）——
        // 那两个方向（要盖住残留 / 盖板别太大）只差几个常量，必须和单测放在一起。

        /** 幅图模式下小于该倾角不值得旋转；大于等于 60° 视为竖排误判，不旋转。 */
        private const val MIN_RENDER_TILT_DEG = 3f
        private const val MAX_RENDER_TILT_DEG = 60f
    }
}

/**
 * 把译文直接画进图片坐标系：先贴擦字补丁（= 原图背景），再画译文。
 *
 * 渲染口径对齐 C:\homework\translator（2026-09-28 用户要求「字体大小和原文一样、
 * 颜色和背景尽量和原来一样、不要白色框」）：
 * - **不再画白色衬底**：参考项目 `.mt-bg-none` 的做法是 `background: transparent` +
 *   清理图当背景 + 文字描边；背景由擦字补丁（整页 inpaint 或逐气泡贴片）提供，
 *   叠一层半透明白框会把原画糊掉（用户反馈的「白色框框」）。
 * - **字号从原文字高出发**：参考项目 `fitBubbleFontSize` 用
 *   `originalTextHeight * 1.08` 作起点、放不下才二分缩小、最后 ×安全系数
 *   （`BUBBLE_FONT_ORIGINAL_SCALE=1.08`；参考项目 `BUBBLE_FONT_SAFETY_SCALE=0.96`，
 *   本工程用 **0.98**：0.96 是给 DOM 取整留的余量，画布渲染没有取整误差会显得偏小，
 *   见 [FONT_SAFETY_SCALE]）。
 *   旧实现只按「框高 ×0.72」出发，短译文会被撑成远大于原字的巨字。
 * - **配色**：文字/描边色来自 `BubbleColorExtractor`（背景环中值 + 墨色主导桶，
 *   保留原色判据与参考项目 `bubble-colors.js` 一致）；solid 不描边、旁白加描边。
 * 跨图气泡用 [drawText] 的 fixedFontSizePx 续排，保证拼接缝两侧断行与字号一致。
 */
class BubbleImageRenderer {

    /**
     * 计算适配内容框的字号（供跨图气泡在两张图间复用同一字号）。
     *
     * @param originalLineHeightPx 原文单行字高（内容框高 ÷ 原文行数）。起点与上限都跟着它走，
     *   所以短译文的字号 ≈ 原文字号；只有放不下时才二分缩小。
     * @param horizontalPaddingEmRatio 渲染层每个横向内边距占字号的倍数（em 比例），默认 0。
     *
     *   ⚠️ DOM 渲染的气泡不是「框有多宽就有多宽可用」：`mt-overlay-renderer.js` 的
     *   `.mt-bubble` 带 `padding:0.12em 0.34em`，而 em 内边距**随字号缩放** —— 字号越大
     *   可用文字宽度越小。按常数（[TEXT_MARGIN_PX]）估宽会高估几十像素，译文刚好放不下时
     *   就会折行，折行后文字层高度翻倍、溢出气泡（2026-09-30 实测：盒子 266×59、内容
     *   233×113、两行 → 用户看到「字超出了原来的字体位置」）。
     *
     *   画布渲染（[drawText]）没有 CSS 内边距，不传这个参数（=0）时行为与旧版完全一致。
     */
    fun fitFontSize(
        text: String,
        rect: RectF,
        originalLineHeightPx: Float = 0f,
        horizontalPaddingEmRatio: Float = 0f
    ): Float {
        val trimmed = text.trim()
        if (trimmed.isBlank() || rect.width() <= 2f || rect.height() <= 2f) return MIN_FONT_SIZE_PX
        val paint = newTextPaint(MIN_FONT_SIZE_PX)
        val rectWidth = rect.width()
        return planFontSize(rect.height(), originalLineHeightPx) { size ->
            paint.textSize = size
            fits(trimmed, paint, contentWidthPx(rectWidth, size, horizontalPaddingEmRatio), rect)
        }
    }

    /**
     * 字号决策规则（纯逻辑，便于单测）：
     * 起点 = [resolveStartSize]，放得下就直接用它 ×[FONT_SAFETY_SCALE]；
     * 放不下在 [MIN_FONT_SIZE_PX, 起点] 里二分找最大可放字号，再 ×安全系数并按上限收紧。
     */
    internal fun planFontSize(
        contentHeightPx: Float,
        originalLineHeightPx: Float,
        fitsAt: (Float) -> Boolean
    ): Float {
        val upper = resolveStartSize(contentHeightPx, originalLineHeightPx)
        if (fitsAt(upper)) {
            return (upper * FONT_SAFETY_SCALE).coerceAtLeast(MIN_FONT_SIZE_PX)
        }
        var low = MIN_FONT_SIZE_PX
        var high = upper
        var best = MIN_FONT_SIZE_PX
        var guard = 0
        while (guard < FIT_SEARCH_STEPS && high - low > FIT_EPSILON_PX) {
            val mid = (low + high) / 2f
            if (fitsAt(mid)) {
                best = mid
                low = mid
            } else {
                high = mid
            }
            guard++
        }
        val safe = (best * FONT_SAFETY_SCALE).coerceAtLeast(MIN_FONT_SIZE_PX)
        return minOf(safe, maxFontSizePx(contentHeightPx, originalLineHeightPx))
    }

    /**
     * 字号起点（纯逻辑，便于单测）：原文单行字高 ×1.08，同时不超过框高 ×0.88 与绝对上限；
     * 拿不到原文行高时退回「框高 ×0.78」（参考项目 `BUBBLE_FONT_BASE_RATIO`）。
     */
    internal fun resolveStartSize(contentHeightPx: Float, originalLineHeightPx: Float): Float {
        val base = if (originalLineHeightPx > 0f) {
            minOf(originalLineHeightPx * ORIGINAL_FONT_SCALE, MAX_FONT_SIZE_PX)
        } else {
            (contentHeightPx * FALLBACK_FONT_HEIGHT_RATIO).coerceIn(MIN_FONT_SIZE_PX, MAX_FONT_SIZE_PX)
        }
        return minOf(base, maxFontSizePx(contentHeightPx, originalLineHeightPx))
    }

    /** 字号上限：框高 ×0.88；有原文字高时再压到 原字高 ×1.08（参考项目同口径）。 */
    internal fun maxFontSizePx(contentHeightPx: Float, originalLineHeightPx: Float): Float {
        val byBox = contentHeightPx * MAX_FONT_HEIGHT_RATIO
        val byOriginal = if (originalLineHeightPx > 0f) {
            originalLineHeightPx * ORIGINAL_FONT_SCALE
        } else {
            MAX_FONT_SIZE_PX
        }
        return minOf(byBox, byOriginal, MAX_FONT_SIZE_PX).coerceAtLeast(MIN_FONT_SIZE_PX)
    }

    /**
     * 原文单行字高估计。
     *
     * - 横排：内容框高 ÷ 原文行数（与参考项目 `getBubbleOriginalTextHeight` 同口径）；
     * - **竖排**：字号由**列宽**决定（一行 = 一列），跟框高无关，用 内容框宽 ÷ 列数。
     */
    internal fun originalLineHeightPx(
        contentHeightPx: Float,
        sourceLineCount: Int,
        contentWidthPx: Float = 0f,
        vertical: Boolean = false
    ): Float {
        val lines = sourceLineCount.coerceAtLeast(1).toFloat()
        if (vertical && contentWidthPx > 0f) return contentWidthPx / lines
        if (contentHeightPx <= 0f) return 0f
        return contentHeightPx / lines
    }

    /** 行数：逐行框个数与文本换行数取较大者（两条链路都可能提供）。 */
    internal fun resolveSourceLineCount(sourceLineCount: Int, originalText: String): Int {
        val fromText = originalText.count { it == '\n' } + 1
        return maxOf(sourceLineCount, fromText, 1)
    }

    /**
     * @return 实际使用的字号（px），供诊断日志核对「字号是否与原字一致」。
     */
    fun drawText(
        canvas: Canvas,
        text: String,
        rect: RectF,
        colors: BubbleColors,
        narration: Boolean,
        fixedFontSizePx: Float? = null,
        originalLineHeightPx: Float = 0f,
        alignment: String = TextAlignment.CENTER,
        /** 文字倾角（度，右倾为正）。斜排原文的译文必须同角度（2026-09-30）。 */
        rotationDeg: Float? = null,
        /**
         * 要不要给旁白/透明底译文描边（2026-09-30 用户要求「白边黑边只在背景有多种颜色
         * 的时候有」）。缺省 true = 与改动前一致。
         */
        needsStroke: Boolean = true
    ): Float {
        val trimmed = text.trim()
        if (trimmed.isBlank() || rect.width() <= 2f || rect.height() <= 2f) return 0f
        val rotation = rotationDeg ?: 0f
        val rotated = kotlin.math.abs(rotation) > ROTATION_EPSILON_DEG
        if (rotated) {
            // 绕内容框中心旋转：译文与斜排原文同一个角度，遮罩（四边形）也就对得上。
            canvas.save()
            canvas.rotate(rotation, rect.centerX(), rect.centerY())
        }
        // 画布渲染没有 CSS 内边距 → 只扣画布留白（口径不变）。
        val contentWidth = contentWidthPx(rect.width())
        val textSize = fixedFontSizePx ?: fitFontSize(trimmed, rect, originalLineHeightPx)
        val paint = newTextPaint(textSize)
        val greedyLayout = buildLayout(trimmed, paint, contentWidth, alignment)
        // 像素感知均衡断行（2026-10-14）：字号已定，把「贪心断行」换成「词边界 + 像素宽均衡」。
        // 行数保持不变（行数变了高度就变、字号拟合失效）；断不开（无解/单行/已含换行）保持贪心。
        val balancedText = balancedDrawText(trimmed, paint, contentWidth, greedyLayout.lineCount)
        val layout = if (balancedText != null) buildLayout(balancedText, paint, contentWidth, alignment) else greedyLayout
        val strokeText = balancedText ?: trimmed

        val layoutWidth = widestLine(layout).coerceAtMost(rect.width() - 2f)
        // 左/右对齐：整块贴着内容框对应边；居中：整块居中（行内再由 StaticLayout 对齐）。
        val drawLeft = when (TextAlignment.normalize(alignment)) {
            TextAlignment.LEFT -> rect.left
            TextAlignment.RIGHT -> rect.right - layoutWidth
            else -> rect.left + ((rect.width() - layoutWidth) / 2f).coerceAtLeast(0f)
        }.coerceIn(rect.left, (rect.right - layoutWidth).coerceAtLeast(rect.left))
        val drawTop = rect.top + ((rect.height() - layout.height) / 2f).coerceAtLeast(0f)

        if (narration && needsStroke) {
            // 旁白/透明文字：**只加描边**，不画衬底 —— 背景就是擦字补丁里的原画
            // （参考项目 .mt-bg-none：background: transparent + -webkit-text-stroke）。
            //
            // ⚠️ `needsStroke=false` 时这里整段跳过（背景基本单色，文字色本身的对比度就够；
            // 再描一圈反而把细笔画糊掉 —— 2026-09-30 用户要求「白边只在背景多色时才有」）。
            val strokePaint = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                strokeWidth = (textSize * NARRATION_STROKE_RATIO).coerceIn(2f, 6f)
                color = colors.strokeColor
            }
            val strokeLayout = buildLayout(strokeText, strokePaint, contentWidth, alignment)
            canvas.save()
            canvas.translate(drawLeft, drawTop)
            strokeLayout.draw(canvas)
            canvas.restore()
        }

        paint.color = colors.textColor
        canvas.save()
        // 极端情况下（框极小 + 译文很长）字号已到下限仍可能溢出，用内容框裁剪，
        // 宁可截掉半点边缘也不要糊到相邻画格上。
        canvas.clipRect(
            rect.left - CLIP_SLACK_PX,
            rect.top - CLIP_SLACK_PX,
            rect.right + CLIP_SLACK_PX,
            rect.bottom + CLIP_SLACK_PX
        )
        canvas.translate(drawLeft, drawTop)
        layout.draw(canvas)
        canvas.restore()
        if (rotated) canvas.restore()
        return textSize
    }

    private fun newTextPaint(textSizePx: Float): TextPaint {
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            typeface = Typeface.SERIF
        }
    }

    /**
     * 画布链路（覆盖层关闭时的整图 PNG）的气泡译文重排（2026-10-14）。
     *
     * [BalancedLineBreaker] 断一次；**行数必须与贪心断行一致**才采用（行数变化会让
     * 文字块高度变化、刚定好的字号失效）。不一致/无解返回 null，调用方保持贪心结果。
     */
    private fun balancedDrawText(
        text: String,
        paint: TextPaint,
        contentWidth: Int,
        lineCount: Int
    ): String? {
        if (text.indexOf('\n') >= 0 || lineCount <= 1 || contentWidth <= 8) return null
        val width = contentWidth.toFloat()
        val rows = BalancedLineBreaker.breakLines(
            text = text,
            lineCount = lineCount,
            maxWidthPx = width,
            measure = { value -> paint.measureText(value) }
        ) ?: return null
        val joined = rows.joinToString("\n")
        val greedy = BalancedLineBreaker.countGreedyLines(
            text = joined,
            maxWidthPx = width,
            measure = { value -> paint.measureText(value) }
        )
        return if (greedy == lineCount) joined else null
    }

    /**
     * 可用文字宽度（纯逻辑，便于单测）= 框宽 − 画布留白（[TEXT_MARGIN_PX]）− 渲染层内边距。
     *
     * [horizontalPaddingEmRatio] > 0 时按「比例 × 字号」扣两侧内边距。**必须每档字号各算一次**
     * ——DOM 的 em 内边距随字号缩放，字号越大可用宽度越小，在外面算一个常数会高估。
     */
    internal fun contentWidthPx(
        rectWidthPx: Float,
        textSizePx: Float = 0f,
        horizontalPaddingEmRatio: Float = 0f
    ): Int {
        val padding = if (horizontalPaddingEmRatio > 0f && textSizePx > 0f) {
            horizontalPaddingEmRatio * textSizePx
        } else {
            0f
        }
        return (rectWidthPx - TEXT_MARGIN_PX * 2 - padding * 2).toInt().coerceAtLeast(8)
    }

    private fun fits(text: String, paint: TextPaint, contentWidth: Int, rect: RectF): Boolean {
        val layout = buildLayout(text, paint, contentWidth)
        return layout.height <= rect.height() - 2f && widestLine(layout) <= contentWidth
    }

    private fun buildLayout(
        text: String,
        paint: TextPaint,
        contentWidth: Int,
        alignment: String = TextAlignment.CENTER
    ): StaticLayout {
        val layoutAlignment = when (TextAlignment.normalize(alignment)) {
            TextAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
            .setIncludePad(false)
            .setAlignment(layoutAlignment)
            .setLineSpacing(0f, LINE_SPACING_MULTIPLIER)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            // 不再 setEllipsize(END)：旧实现译文过长会直接显示成「…」，信息丢失比字小更糟。
            .build()
    }

    private fun widestLine(layout: StaticLayout): Float {
        var maxLineWidth = 0f
        for (line in 0 until layout.lineCount) {
            maxLineWidth = maxOf(maxLineWidth, layout.getLineWidth(line))
        }
        return maxLineWidth
    }

    private companion object {
        const val TEXT_MARGIN_PX = 3f
        // 行距见下方常量区（1.08，与常见漫画排版接近，也给字号留空间）。

        // 字号规则（对齐 C:\homework\translator reader-state.js 的同名常量）
        const val ORIGINAL_FONT_SCALE = 1.08f     // BUBBLE_FONT_ORIGINAL_SCALE
        // 参考项目 0.96 是为 DOM 取整留的余量；画布渲染没有取整误差，0.98 更贴近原字号
        // （0.96 叠上 1.08 起点再叠行距，会明显偏小 —— 2026-09-28 用户反馈「字号感觉小了」）。
        const val FONT_SAFETY_SCALE = 0.98f
        const val FALLBACK_FONT_HEIGHT_RATIO = 0.78f // BUBBLE_FONT_BASE_RATIO
        const val MAX_FONT_HEIGHT_RATIO = 0.88f   // maxFont = min(ABS_MAX, height * 0.88)
        const val MIN_FONT_SIZE_PX = 8f
        const val MAX_FONT_SIZE_PX = 260f
        const val FIT_EPSILON_PX = 0.5f
        const val FIT_SEARCH_STEPS = 12
        const val NARRATION_STROKE_RATIO = 0.085f
        const val CLIP_SLACK_PX = 2f
        // 小于该角度视为「没倾斜」，不绕中心旋转（避免浮点噪声把正排文字拧歪）。
        const val ROTATION_EPSILON_DEG = 0.5f
        // 行距：1.16 会在「放不下就缩字号」的判定里进一步压缩可用字号（单行也要占 1.16 倍高），
        // 1.08 与常见漫画排版更接近，同时给字号留出空间。
        const val LINE_SPACING_MULTIPLIER = 1.08f
    }
}

/**
 * 「框比文本矮」向下扩框的**纯判据**（2026-10-12 从 [WebtoonImageTranslator.growBubbleToTextHeight]
 * 提出，全 Float 入参 + [SeamBox]，JVM 可直接单测 —— 这条判据两次闯过祸，必须钉住）。
 *
 * 判据：框底(需) = 框顶 + 硬行数 × 中位行高 × 1.2；不超过 框底 + 0.3×行高 就不扩；
 * 要扩则夹到拼接图底与「下方邻居的顶边」（横向重叠 ≥ 0.3× 较窄者才算邻居）。
 *
 * 现场回归（都用真机数字）：
 * - 2026-10-12 误扩现场：单行小字 316×44@2410、中位行高 36 → 需 2410+43.2=2453.2 ≤
 *   2454+10.8 → **不扩**（旧版按字宽折行算出 2 行 → 扩到 2596 → 字号炸 4 倍）；
 * - 2026-10-11 原始现场：4 行台词框 360×161@2312、行高 43 → 2312+206.4=2518，
 *   下方邻居@2520 不挡（2518 < 2520）→ 扩到 **2518**（当年折行版 2570 被夹回 2520，
 *   纯硬行数反而更贴真实底边）。
 */
internal fun growBubbleBottomPx(
    rectLeft: Float,
    rectTop: Float,
    rectRight: Float,
    rectBottom: Float,
    hardLineCount: Int,
    medianLineHeightPx: Float,
    stitchedHeightPx: Float,
    others: List<SeamBox>
): Float? {
    if (medianLineHeightPx <= 0f || !medianLineHeightPx.isFinite()) return null
    if (hardLineCount <= 0) return null
    val boxWidth = rectRight - rectLeft
    if (boxWidth <= 2f) return null
    val neededBottom = rectTop + hardLineCount * medianLineHeightPx * 1.2f
    if (neededBottom <= rectBottom + medianLineHeightPx * 0.3f) return null
    var newBottom = neededBottom.coerceAtMost(stitchedHeightPx)
    others.forEach { other ->
        if (other.width <= 2f || other.height <= 2f) return@forEach
        val hOverlap = minOf(rectRight, other.right) - maxOf(rectLeft, other.left)
        if (hOverlap >= 0.3f * minOf(boxWidth, other.width) &&
            other.top >= rectBottom - 1f &&
            other.top < newBottom
        ) {
            newBottom = other.top
        }
    }
    if (newBottom <= rectBottom) return null
    return newBottom
}



