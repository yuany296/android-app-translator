package com.manga.translator.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.manga.translator.translate.PaddleModelCatalog.RecModelKey
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PP-OCRv5 mobile 本地 OCR 引擎（ONNX Runtime 推理，模型同 PaddleOCR 参考项目）。
 *
 * 管线：
 *   1. det：PP-OCRv5 mobile 文本检测（resize_long=960，128 对齐，DB 后处理 thresh=0.3 / box=0.6 / unclip=1.2）
 *   2. rec：按检测四边形透视矫正裁剪 → PP-OCRv5 mobile 识别 → CTC 解码
 *
 * 模型在首次使用时按源语言下载并缓存到 filesDir/paddle_models/。
 */
class PaddleOcrLocalTextDetector(
    private val appContext: Context,
    private val catalog: PaddleModelCatalog = PaddleModelCatalog(),
    private val speedModeProvider: (() -> String)? = null
) : LocalTextDetector {

    private val loadLock = ReentrantLock()
    private val ortEnv: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var detSession: OrtSession? = null
    private val recSessions = HashMap<RecModelKey, OrtSession>()
    private var modelDir: File? = null
    private var opencvReady: Boolean? = null

    private val recCascadeHits = AtomicInteger()

    private val recCascadeFull = AtomicInteger()

    private val verticalCrops = AtomicInteger()

    private val verticalOrient90 = AtomicInteger()

    private val verticalOrientNeg90 = AtomicInteger()

    private val rot180Retries = AtomicInteger()

    /**
     * 2026-09-15 新增：文字框识别并行池。
     *
     * 原先 `allQuads.forEachIndexed { ... }` 串行执行，每个文字框都是一次独立的 rec 推理，
     * 一张漫画页 30+ 个框只能用一个核跑，是 `paddle_total_ms` 的主要来源（实测约 87%）。
     * 这里改为多线程并行，每个线程处理一个文字框，结果按下标回填，输出顺序与串行一致。
     * ONNX Runtime 的 `OrtSession.run` 官方声明线程安全，可被多线程并发调用。
     */
    private val recPool: ExecutorService by lazy {
        Executors.newFixedThreadPool(recParallelism(), object : ThreadFactory {
            private val seq = AtomicInteger(0)
            override fun newThread(r: Runnable): Thread =
                Thread(r, "paddle-rec-${seq.incrementAndGet()}").apply { isDaemon = true }
        })
    }

    override fun detect(bitmap: Bitmap, sourceLanguageId: String): List<TextBlock> {
        if (!ensureOpenCvLoaded()) {
            Log.w(TAG, "paddle_engine_skip reason=opencv_unavailable")
            return emptyList()
        }
        val recKey = catalog.recKeyFor(sourceLanguageId)
        val engineReady = loadLock.withLock { ensureEngineReady(recKey) }
        if (!engineReady) return emptyList()
        return try {
            runDetect(bitmap, recKey)
        } catch (err: Throwable) {
            Log.w(TAG, "paddle_detect_fail reason=${err.message?.take(400)}")
            emptyList()
        }
    }

    override fun isReady(sourceLanguageId: String): Boolean {
        val recKey = catalog.recKeyFor(sourceLanguageId)
        return loadLock.withLock { detSession != null && recSessions.containsKey(recKey) }
    }

    override fun prewarm(sourceLanguageId: String) {
        if (!ensureOpenCvLoaded()) return
        val recKey = catalog.recKeyFor(sourceLanguageId)
        loadLock.withLock { ensureEngineReady(recKey) }
    }

    /** 确保 det + rec 会话已创建（含模型下载/加载）；返回是否就绪。 */
    private fun ensureEngineReady(recKey: RecModelKey): Boolean {
        val ready = ensureModelsLoaded(recKey)
        if (ready) {
            if (detSession == null) {
                detSession = createSession(
                    modelDir!!.resolve(catalog.detModelFileName).absolutePath,
                    detIntraOpThreads()
                )
            }
            if (!recSessions.containsKey(recKey)) {
                recSessions[recKey] = createSession(
                    modelDir!!.resolve(catalog.recModelFileName(recKey)).absolutePath,
                    recIntraOpThreads()
                )
            }
        }
        return ready
    }

    private fun runDetect(bitmap: Bitmap, recKey: RecModelKey): List<TextBlock> {
        val startedAt = System.currentTimeMillis()
        val srcW = bitmap.width
        val srcH = bitmap.height
        verticalCrops.set(0)
        verticalOrient90.set(0)
        verticalOrientNeg90.set(0)
        rot180Retries.set(0)

        // 2026-08-27 新增：按首页选择的 OCR 速度模式调整检测/识别参数。
        val config = speedModeConfig()

        // 1. 主检测：原图 + 默认参数（与参考项目 production 默认值对齐）
        val primaryQuads = runDetPass(
            bitmap = bitmap,
            resizeLong = config.detResizeLong,
            thresh = DET_THRESH,
            boxThresh = DET_BOX_THRESH,
            unclipRatio = DET_UNCLIP_RATIO,
            tag = "primary"
        )
        val allQuads = primaryQuads.toMutableList()

        // 2. 如果主检测块数少 / 覆盖率低，跑 recovery 检测：
        //    用增强灰度/二值掩码/反色增强 + 更松的阈值，召回彩色艺术字和低对比度字。
        val coverage = estimateTextCoverage(primaryQuads, srcW, srcH)
        val needRecovery = primaryQuads.size < config.recoveryMinBlocks || coverage < config.recoveryMinCoverage
        if (needRecovery) {
            // recovery 变体只需在降采样工作图上构建：det 内部仍会缩到 detResizeLong，
            // 在缩小图上跑像素级变体（灰度/二值/反色）比全分辨率省 ~6 倍，quad 再映射回原图坐标。
            val scale = min(1f, config.detResizeLong / max(srcW, srcH).toFloat())
            val workW = max(1, (srcW * scale).roundToInt())
            val workH = max(1, (srcH * scale).roundToInt())
            val work = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, workW, workH, true)
            } else {
                bitmap
            }
            val variants = listOf(
                "gray" to { PaddleOcrImagePreprocessor.buildEnhancedGrayscale(work, invert = false) },
                "binary" to { PaddleOcrImagePreprocessor.buildBinaryTextMask(work) },
                "inv_gray" to { PaddleOcrImagePreprocessor.buildEnhancedGrayscale(work, invert = true) }
            )
            var variantsRun = 0
            var addedQuads = 0
            for ((name, builder) in variants) {
                if (variantsRun >= config.recoveryMaxVariants) break
                val variant = runCatching { builder() }.getOrNull() ?: continue
                variantsRun++
                val quads = runDetPass(
                    bitmap = variant,
                    resizeLong = config.detResizeLong,
                    thresh = DET_THRESH_RECOVERY,
                    boxThresh = DET_BOX_THRESH_RECOVERY,
                    unclipRatio = DET_UNCLIP_RATIO_RECOVERY,
                    tag = "recovery_$name"
                )
                var added = 0
                for (quad in quads) {
                    val mapped = quad.map { point ->
                        PointF(
                            (point.x * srcW / workW).coerceIn(0f, srcW.toFloat()),
                            (point.y * srcH / workH).coerceIn(0f, srcH.toFloat())
                        )
                    }.toTypedArray()
                    if (allQuads.none { existing -> quadIoU(existing, mapped) >= MERGE_IOU_THRESHOLD }) {
                        allQuads += mapped
                        added++
                    }
                }
                variant.recycle()
                addedQuads += added
                // 已召回到足够多的新框就停止，减少多余 det 变体耗时。
                if (addedQuads >= config.recoveryEarlyStopBoxes) break
            }
            if (work !== bitmap) {
                work.recycle()
            }
        }

        // 3. 对每个候选框在原图上做透视矫正裁剪，再按方向（横排 0° / 竖排 ±90° / 弱结果 180°）识别，
        //    多个识别变体投票选出最可信结果。
        //    2026-09-15：改为多线程并行识别（每个文字框一个任务），结果按下标回填，输出顺序与串行一致。
        val recSession = recSessions[recKey] ?: return emptyList()
        val alphabet = loadDict(recKey)
        val lang = recKey.id
        val recStartedAt = System.currentTimeMillis()
        val slots = arrayOfNulls<TextBlock>(allQuads.size)
        val cropFailures = AtomicInteger()
        val degenerateQuads = AtomicInteger()
        val parallelism = if (allQuads.size > 1) recParallelism() else 1
        val recognizeOne: (Int) -> Unit = { index ->
            slots[index] = recognizeQuad(
                index = index,
                quad = allQuads[index],
                bitmap = bitmap,
                recSession = recSession,
                alphabet = alphabet,
                lang = lang,
                config = config,
                srcW = srcW,
                srcH = srcH,
                cropFailures = cropFailures,
                degenerateQuads = degenerateQuads
            )
        }
        if (parallelism <= 1) {
            allQuads.indices.forEach(recognizeOne)
        } else {
            val futures = allQuads.indices.map { index -> recPool.submit { recognizeOne(index) } }
            futures.forEach { future ->
                runCatching { future.get(REC_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                    .onFailure { err -> Log.w(TAG, "paddle_rec_task_fail reason=${err.message?.take(80)}") }
            }
        }
        val rawBlocks = slots.filterNotNull().sortedBy { it.id }
        val recMs = System.currentTimeMillis() - recStartedAt

        // 合并前的**行级**清单（2026-10-14，用户现场 47 话报纸标题）：
        // `ocr_blocks_all`（app 层）打的是**合并后**的块 —— 标题被读成「…벗어 아니 레온…」
        // 这类「认错字」，到底是识别器自己吐出来的、还是后聚合把两块并起来引入的，
        // 只有合并前这一行能一眼分开（行级还带四边形倾角，斜排是否被正确读出也看得到）。
        Log.d(
            TAG,
            "paddle_lines_all raw=${rawBlocks.size} img=${srcW}x${srcH} :: " + rawBlocks.take(MAX_LINE_PROBE).joinToString(" ˌ ") { block ->
                val angle = block.polygon?.let { polygon ->
                    PaddleQuadGeometry.rotationDeg(polygon.map { PaddleQuadGeometry.Point(it.x, it.y) })
                }
                "${block.text.replace('\n', '⏎').take(12)}@" +
                    "${block.left.toInt()},${block.top.toInt()}," +
                    "${(block.right - block.left).toInt()}x${(block.bottom - block.top).toInt()}" +
                    (angle?.let { ",rot=${"%.1f".format(it)}" } ?: "")
            } + if (rawBlocks.size > MAX_LINE_PROBE) " …(+${rawBlocks.size - MAX_LINE_PROBE})" else ""
        )

        // 4. 后聚合：把同一行/同一段落的碎片合并成完整句子（参考 translator 的 ocr-lines）
        val merged = PaddleOcrBlockMerger.mergeBlocks(rawBlocks, srcW, srcH)
        val totalMs = System.currentTimeMillis() - startedAt
        Log.d(
            TAG,
            "paddle_total_ms=${totalMs} rec_ms=${recMs} rec_threads=$parallelism " +
                "primary=${primaryQuads.size} total_quads=${allQuads.size} " +
                "raw_blocks=${rawBlocks.size} merged=${merged.size} recovery=$needRecovery coverage=${"%.4f".format(coverage)} " +
                "rec_cascade_hit=${recCascadeHits.get()} rec_cascade_full=${recCascadeFull.get()} " +
                "vertical=${verticalCrops.get()} orient90=${verticalOrient90.get()} orient-90=${verticalOrientNeg90.get()} rot180=${rot180Retries.get()} " +
                "crop_fail=${cropFailures.get()} degenerate=${degenerateQuads.get()} speed_mode=${speedModeProvider?.invoke() ?: "balanced"} " +
                "lang=${recKey.id}"
        )
        return merged
    }

    private fun runDetPass(
        bitmap: Bitmap,
        resizeLong: Float,
        thresh: Float,
        boxThresh: Float,
        unclipRatio: Float,
        tag: String
    ): List<Array<PointF>> {
        val detSession = detSession ?: return emptyList()
        val srcW = bitmap.width
        val srcH = bitmap.height
        val detInput = detPreprocess(bitmap, resizeLong)
        val ratioH = detInput.resizeH / detInput.srcH.toFloat()
        val ratioW = detInput.resizeW / detInput.srcW.toFloat()

        val detStart = System.currentTimeMillis()
        val probMap = runDet(ortEnv, detSession, detInput)
        val detMs = System.currentTimeMillis() - detStart
        var probMax = 0f
        var probSum = 0f
        for (p in probMap) {
            if (p > probMax) probMax = p
            probSum += p
        }
        val quads = dbPostProcess(
            prob = probMap,
            mapH = detInput.resizeH,
            mapW = detInput.resizeW,
            ratioH = ratioH,
            ratioW = ratioW,
            srcW = srcW,
            srcH = srcH,
            thresh = thresh,
            boxThresh = boxThresh,
            unclipRatio = unclipRatio
        )
        Log.d(
            TAG,
            "det_pass_ms=${detMs} tag=$tag in=${detInput.resizeW}x${detInput.resizeH} " +
                "prob_max=${probMax} prob_mean=${probSum / probMap.size} boxes=${quads.size}"
        )
        return quads
    }

    /**
     * 单个文字框的完整识别流程（透视裁剪 → 按方向识别 → 生成 TextBlock）。
     * 2026-09-15：从 `runDetect` 的串行循环中抽出，供并行池调用；线程内只读 `bitmap`，不共享可变状态。
     */
    private fun recognizeQuad(
        index: Int,
        quad: Array<PointF>,
        bitmap: Bitmap,
        recSession: OrtSession,
        alphabet: List<String>,
        lang: String,
        config: SpeedModeConfig,
        srcW: Int,
        srcH: Int,
        cropFailures: AtomicInteger,
        degenerateQuads: AtomicInteger
    ): TextBlock? {
        val rotation = PaddleQuadGeometry.rotationDeg(quad.toPoints())
        val geometryCheck = PaddleQuadGeometry.checkQuadHealth(quad.toPoints())
        if (!geometryCheck.healthy) {
            degenerateQuads.incrementAndGet()
            Log.w(
                TAG,
                String.format(
                    java.util.Locale.US,
                    "paddle_quad_degenerate quad_idx=%d rot=%d reason=%s",
                    index, rotation.roundToInt(), geometryCheck.reason
                )
            )
        }
        val cropOriginal = cropQuad(bitmap, quad) ?: run {
            cropFailures.incrementAndGet()
            Log.w(
                TAG,
                String.format(
                    java.util.Locale.US,
                    "paddle_crop_fail quad_idx=%d rot=%d",
                    index, rotation.roundToInt()
                )
            )
            return null
        }
        if (cropOriginal.width < MIN_BOX_SIZE_PX || cropOriginal.height < MIN_BOX_SIZE_PX) {
            cropFailures.incrementAndGet()
            cropOriginal.recycle()
            return null
        }
        val (text, score) = recognizeWithOrientation(
            cropOriginal, rotation, recSession, alphabet, lang, config
        )
        cropOriginal.recycle()
        if (text.isBlank()) return null
        // 质量闸门（2026-09-30）：不达标就整条丢弃。
        //
        // 为什么必须有：`isWeakResult` 只用来决定「要不要再转 180° 重试」，重试完照样返回，
        // 于是 PaddleOCR 在空白/纹理区域的误检（用户截图里的 `cYa`）会一路流到分组、翻译、
        // 覆盖层。这里把它挡在入口，并**记下丢弃原因**——不然「某些噪声莫名消失/出现」
        // 又会变成无线索排查（排障规则：关键决策要有日志）。
        if (!PaddleQuadGeometry.isAcceptableResult(text, score, lang)) {
            Log.d(
                TAG,
                String.format(
                    java.util.Locale.US,
                    "paddle_quad_reject quad_idx=%d lang=%s score=%.3f script=%d text=%s",
                    index,
                    lang,
                    score,
                    PaddleQuadGeometry.countScriptChars(text, lang),
                    text.take(24)
                )
            )
            return null
        }
        val left = quadsLeft(quad).coerceIn(0f, srcW.toFloat())
        val top = quadsTop(quad).coerceIn(0f, srcH.toFloat())
        val right = quadsRight(quad).coerceIn(left + 1f, srcW.toFloat())
        val bottom = quadsBottom(quad).coerceIn(top + 1f, srcH.toFloat())
        return TextBlock(
            id = index,
            text = text.trim(),
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            confidence = score,
            // 语义四边形（TL/TR/BR/BL，原图像素）随块带出：渲染层用它把遮罩/译文
            // 一起倾斜，轴对齐外框做不到（2026-09-29）。退化四边形同样带出——
            // 渲染层有 |倾角|≥60° 且框宽>框高 的自洽检查，不会把多行横排转 90°。
            polygon = quad.toPoints().map { QuadPoint(it.x, it.y) },
            // 单框识别结果就是一行；多行块的行数由 `PaddleOcrBlockMerger` 在合并时写入。
            lineCount = 1
        )
    }

    /** 识别并行度：留一个核给 UI/检测，上限 [REC_MAX_PARALLELISM]。 */
    private fun recParallelism(): Int =
        Runtime.getRuntime().availableProcessors().minus(1).coerceIn(1, REC_MAX_PARALLELISM)

    /** det 单次调用可用的线程数。 */
    private fun detIntraOpThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, DET_MAX_INTRA_OP_THREADS)

    /** rec 单次调用的线程数：并行度>1 时压低，避免与框级并行叠加成线程超订。 */
    private fun recIntraOpThreads(): Int =
        if (recParallelism() > 1) REC_INTRA_OP_THREADS_WHEN_PARALLEL else REC_INTRA_OP_THREADS_WHEN_SERIAL

    private fun recognizeBest(
        crop: Bitmap,
        recSession: OrtSession,
        alphabet: List<String>,
        config: SpeedModeConfig
    ): Pair<String, Float> {
        // 级联快速路径：原图识别置信度足够高就直接返回，跳过其余识别变体。
        // 清晰文字是绝大多数场景，能省 ~7/8 的识别耗时；低置信度框仍走全变体投票。
        val raw = runCatching { recognizeCrop(crop, recSession, alphabet) }.getOrDefault("" to 0f)
        if (raw.first.isNotBlank() && raw.second >= config.recCascadeHighConf) {
            recCascadeHits.incrementAndGet()
            return raw
        }
        recCascadeFull.incrementAndGet()
        // 中等置信度档：只跑灰度/反色两个增强变体（共 3 次识别），
        // 跳过 2x 放大等高成本变体，兼顾速度与召回。
        if (raw.first.isNotBlank() && raw.second >= config.recCascadeMidConf) {
            val gray = runCatching { PaddleOcrImagePreprocessor.buildEnhancedGrayscale(crop, invert = false) }.getOrNull()
            val invGray = runCatching { PaddleOcrImagePreprocessor.buildEnhancedGrayscale(crop, invert = true) }.getOrNull()
            val result = listOfNotNull(
                raw,
                gray?.let { recognizeCrop(it, recSession, alphabet) },
                invGray?.let { recognizeCrop(it, recSession, alphabet) }
            )
                .filter { it.first.isNotBlank() }
                .maxByOrNull { it.second }
                ?: raw
            gray?.recycle()
            invGray?.recycle()
            return result
        }
        val gray = runCatching { PaddleOcrImagePreprocessor.buildEnhancedGrayscale(crop, invert = false) }.getOrNull()
        val invGray = runCatching { PaddleOcrImagePreprocessor.buildEnhancedGrayscale(crop, invert = true) }.getOrNull()
        val binary = runCatching { PaddleOcrImagePreprocessor.buildBinaryTextMask(crop) }.getOrNull()
        val otsu = runCatching { PaddleOcrImagePreprocessor.buildOtsuBinaryMask(crop) }.getOrNull()
        // 2x 放大：让识别模型在 48x? 的固定高度下获得更宽的水平分辨率
        val scaled2x = runCatching {
            Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
        }.getOrNull()
        val gray2x = scaled2x?.let {
            runCatching { PaddleOcrImagePreprocessor.buildEnhancedGrayscale(it, invert = false) }.getOrNull()
        }
        val otsu2x = scaled2x?.let {
            runCatching { PaddleOcrImagePreprocessor.buildOtsuBinaryMask(it) }.getOrNull()
        }
        val candidates = listOfNotNull(
            raw,
            gray?.let { recognizeCrop(it, recSession, alphabet) },
            invGray?.let { recognizeCrop(it, recSession, alphabet) },
            binary?.let { recognizeCrop(it, recSession, alphabet) },
            otsu?.let { recognizeCrop(it, recSession, alphabet) },
            scaled2x?.let { recognizeCrop(it, recSession, alphabet) },
            gray2x?.let { recognizeCrop(it, recSession, alphabet) },
            otsu2x?.let { recognizeCrop(it, recSession, alphabet) }
        )
        gray?.recycle()
        invGray?.recycle()
        binary?.recycle()
        otsu?.recycle()
        scaled2x?.recycle()
        gray2x?.recycle()
        otsu2x?.recycle()
        return candidates
            .filter { it.first.isNotBlank() }
            .maxByOrNull { it.second }
            ?: ("" to 0f)
    }

    private fun recognizeCrop(crop: Bitmap, recSession: OrtSession, alphabet: List<String>): Pair<String, Float> {
        return runCatching {
            val recInput = recPreprocess(crop)
            runRec(ortEnv, recSession, recInput, alphabet)
        }.getOrDefault("" to 0f)
    }

    /**
     * 按文字方向识别（参考 C:\homework\translator crop_recognition.py）：
     * - 竖排 crop（高 ≥ 宽 × 1.4）：旋转 ±90° 后识别，按识别质量 + 检测四边形方向选择最优方向。
     *   先对两个方向各跑一次原图识别（高分即可级联结束），质量更优的方向再做全变体投票，
     *   另一方向保留原图结果参与择优，控制耗时。
     * - 横排 crop：先 0° 识别，弱结果（分数 < 0.72 或没有目标文字）再旋转 180° 重试。
     */
    private fun recognizeWithOrientation(
        crop: Bitmap,
        rotation: Float,
        recSession: OrtSession,
        alphabet: List<String>,
        lang: String,
        config: SpeedModeConfig
    ): Pair<String, Float> {
        if (PaddleQuadGeometry.isConfidentVerticalCrop(crop.width, crop.height)) {
            verticalCrops.incrementAndGet()
            val cw = runCatching { rotate90(crop, clockwise = true) }.getOrNull()
            val ccw = runCatching { rotate90(crop, clockwise = false) }.getOrNull()
            val cwRaw = cw?.let { recognizeCrop(it, recSession, alphabet) } ?: ("" to 0f)
            val ccwRaw = ccw?.let { recognizeCrop(it, recSession, alphabet) } ?: ("" to 0f)
            val cwQuality = PaddleQuadGeometry.recognitionQuality(cwRaw.first, cwRaw.second, lang)
            val ccwQuality = PaddleQuadGeometry.recognitionQuality(ccwRaw.first, ccwRaw.second, lang)
            val winnerIsCw = cwQuality >= ccwQuality
            val winnerCrop = if (winnerIsCw) cw else ccw
            val winnerRaw = if (winnerIsCw) cwRaw else ccwRaw
            val winnerFull = if (winnerCrop == null || winnerRaw.second >= config.recCascadeHighConf) {
                winnerRaw
            } else {
                recognizeBest(winnerCrop, recSession, alphabet, config)
            }
            val results = mutableListOf<PaddleQuadGeometry.OrientedResult>()
            if (winnerIsCw) {
                results += PaddleQuadGeometry.OrientedResult(90, winnerFull.first, winnerFull.second)
                results += PaddleQuadGeometry.OrientedResult(-90, ccwRaw.first, ccwRaw.second)
            } else {
                results += PaddleQuadGeometry.OrientedResult(-90, winnerFull.first, winnerFull.second)
                results += PaddleQuadGeometry.OrientedResult(90, cwRaw.first, cwRaw.second)
            }
            cw?.recycle()
            ccw?.recycle()
            val chosen = PaddleQuadGeometry.selectBestOrientation(rotation, results, lang)
            if (chosen == null || chosen.text.isBlank()) return "" to 0f
            if (chosen.orientation == 90) verticalOrient90.incrementAndGet() else verticalOrientNeg90.incrementAndGet()
            Log.d(
                TAG,
                String.format(
                    java.util.Locale.US,
                    "paddle_quad rot=%d vertical=true orient=%+d text=%s score=%.3f",
                    rotation.roundToInt(),
                    chosen.orientation,
                    chosen.text.take(24),
                    chosen.score
                )
            )
            return chosen.text to chosen.score
        }
        // 横排：弱结果（分数 < 0.72 / 无目标文字，含空结果）再旋转 180° 重试
        val raw = recognizeBest(crop, recSession, alphabet, config)
        if (PaddleQuadGeometry.isWeakResult(raw.first, raw.second, lang)) {
            val rotated = runCatching { rotate180(crop) }.getOrNull()
            if (rotated != null) {
                val retry = recognizeBest(rotated, recSession, alphabet, config)
                rotated.recycle()
                if (retry.first.isNotBlank() &&
                    PaddleQuadGeometry.recognitionQuality(retry.first, retry.second, lang) >
                    PaddleQuadGeometry.recognitionQuality(raw.first, raw.second, lang)
                ) {
                    rot180Retries.incrementAndGet()
                    Log.d(
                        TAG,
                        String.format(
                            java.util.Locale.US,
                            "paddle_quad rot=%d vertical=false orient=180 text=%s score=%.3f",
                            rotation.roundToInt(),
                            retry.first.take(24),
                            retry.second
                        )
                    )
                    return retry
                }
            }
        }
        Log.d(
            TAG,
            String.format(
                java.util.Locale.US,
                "paddle_quad rot=%d vertical=false orient=0 text=%s score=%.3f",
                rotation.roundToInt(),
                raw.first.take(24),
                raw.second
            )
        )
        return raw
    }

    private fun rotate90(bitmap: Bitmap, clockwise: Boolean): Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(if (clockwise) 90f else -90f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun rotate180(bitmap: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(180f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun estimateTextCoverage(quads: List<Array<PointF>>, imageW: Int, imageH: Int): Float {
        if (imageW <= 0 || imageH <= 0) return 0f
        var area = 0f
        for (quad in quads) {
            val w = quadsRight(quad) - quadsLeft(quad)
            val h = quadsBottom(quad) - quadsTop(quad)
            area += w * h
        }
        return area / (imageW * imageH).toFloat()
    }

    private fun quadIoU(a: Array<PointF>, b: Array<PointF>): Float {
        val al = quadsLeft(a)
        val at = quadsTop(a)
        val ar = quadsRight(a)
        val ab = quadsBottom(a)
        val bl = quadsLeft(b)
        val bt = quadsTop(b)
        val br = quadsRight(b)
        val bb = quadsBottom(b)
        val interW = max(0f, min(ar, br) - max(al, bl))
        val interH = max(0f, min(ab, bb) - max(at, bt))
        val inter = interW * interH
        val areaA = (ar - al) * (ab - at)
        val areaB = (br - bl) * (bb - bt)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    // ---------- 模型加载与下载 ----------

    private fun ensureModelsLoaded(recKey: RecModelKey): Boolean {
        val dir = File(appContext.filesDir, MODEL_DIR_NAME)
        modelDir = dir
        val detFile = File(dir, catalog.detModelFileName)
        val recFile = File(dir, catalog.recModelFileName(recKey))
        if (!dir.exists()) dir.mkdirs()

        // 1) 优先使用打包进 APK assets 的模型：存在则复制到 filesDir 供 ONNX 加载，
        //    完全离线、无网络依赖，也避免重复占用设备存储。
        val detFromAsset = ensureAssetModel(catalog.detAssetName(), detFile)
        val recFromAsset = ensureAssetModel(catalog.recAssetName(recKey), recFile)

        // 2) 任一模型在 assets 缺失（本地打包不含该语言）时，回退在线下载。
        val needDownload = !(detFile.exists() && detFile.length() > 1_000_000 && recFile.exists() && recFile.length() > 1_000_000)
        if (!needDownload) return true
        if (!isNetworkAvailable()) {
            Log.w(TAG, "model_download_skip reason=no_network det_asset=$detFromAsset rec_asset=$recFromAsset")
            return false
        }
        Log.i(TAG, "model_download_start det=${catalog.detModelFileName} rec=${catalog.recModelFileName(recKey)}")
        val ok = runCatching {
            if (detFile.length() <= 1_000_000) {
                downloadWithFallback(
                    primary = catalog.detModelTarUrl(),
                    primaryIsTar = true,
                    direct = catalog.detModelUrl(),
                    target = detFile
                )
            }
            if (recFile.length() <= 1_000_000) {
                downloadWithFallback(
                    primary = catalog.recModelTarUrl(recKey),
                    primaryIsTar = true,
                    direct = catalog.recModelUrl(recKey),
                    target = recFile
                )
            }
            true
        }.getOrElse { err ->
            Log.w(TAG, "model_download_fail reason=${err.message?.take(120)}")
            detFile.delete()
            recFile.delete()
            false
        }
        Log.i(TAG, "model_download_done ok=$ok")
        return ok
    }

    /** 若 assets 中存在同名模型，则（按需）复制到 filesDir 目标文件；返回是否来自 assets。 */
    private fun ensureAssetModel(assetPath: String, target: File): Boolean {
        return runCatching {
            // 文件已就绪（来自之前复制或下载）则视为存在
            if (target.exists() && target.length() > 1_000_000) return true
            val stream = appContext.assets.open(assetPath) ?: return false
            stream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            val ok = target.length() > 1_000_000
            if (ok) {
                Log.i(TAG, "model_loaded_from_asset path=$assetPath bytes=${target.length()}")
            } else {
                target.delete()
            }
            ok
        }.getOrElse { err ->
            Log.w(TAG, "model_asset_missing path=$assetPath reason=${err.message?.take(100)}")
            target.delete()
            false
        }
    }

    private fun downloadWithFallback(primary: String?, primaryIsTar: Boolean, direct: String?, target: File) {
        if (!primary.isNullOrBlank()) {
            val ok = runCatching {
                if (primaryIsTar) {
                    downloadTarAndExtract(primary, target)
                } else {
                    downloadFile(primary, target)
                }
                true
            }.getOrElse { err ->
                Log.w(TAG, "model_download_primary_fail url=${primary.take(80)} reason=${err.message?.take(80)}")
                target.delete()
                false
            }
            if (ok) return
        }
        if (!direct.isNullOrBlank()) {
            downloadFile(direct, target)
        } else {
            error("no_model_url")
        }
    }

    private fun ensureOpenCvLoaded(): Boolean {
        if (opencvReady == null) {
            opencvReady = runCatching { org.opencv.android.OpenCVLoader.initDebug() }.getOrDefault(false)
            if (opencvReady == false) {
                Log.w(TAG, "opencv_init_failed")
            }
        }
        return opencvReady == true
    }

    private fun isNetworkAvailable(): Boolean {
        return runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
    }

    private fun downloadFile(url: String, target: File) {
        val request = okhttp3.Request.Builder().url(url).build()
        catalog.httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "http_${response.code}" }
            val body = response.body ?: error("empty_body")
            val bytes = body.bytes()
            check(bytes.size > 1_000_000) { "small_file_${bytes.size}" }
            target.writeBytes(bytes)
        }
    }

    /** 从 BOS tar 中提取 inference.onnx（tar 结构：目录 + inference.onnx + inference.yml）。 */
    private fun downloadTarAndExtract(url: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".tar")
        downloadFile(url, tmp)
        val bytes = tmp.readBytes()
        var offset = 0
        var extracted = false
        while (offset + 512 <= bytes.size) {
            val name = readTarField(bytes, offset, 0, 100)
            val size = readTarField(bytes, offset, 124, 12).trim().toLongOrNull(8) ?: 0L
            val typeFlag = if (offset + 156 < bytes.size) bytes[offset + 156].toInt().toChar() else '0'
            if (name.isEmpty()) break
            val dataStart = offset + 512
            if (typeFlag == '0' && name.endsWith(".onnx")) {
                val dataSize = (size + 511) / 512 * 512
                if (dataStart + size <= bytes.size) {
                    target.writeBytes(bytes.copyOfRange(dataStart, dataStart + size.toInt()))
                    extracted = true
                }
            }
            val blockSize = ((size + 511) / 512) * 512
            offset = dataStart + blockSize.toInt()
        }
        tmp.delete()
        check(extracted) { "tar_no_onnx" }
        check(target.length() > 1_000_000) { "small_file_${target.length()}" }
    }

    private fun readTarField(bytes: ByteArray, offset: Int, fieldOffset: Int, length: Int): String {
        val start = offset + fieldOffset
        val end = min(start + length, bytes.size)
        var i = start
        while (i < end && bytes[i].toInt() != 0) i++
        return String(bytes, start, i - start, Charsets.UTF_8)
    }

    /**
     * 创建推理会话。
     *
     * 2026-09-15 调整：
     * - 优化级别从 `BASIC_OPT` 提到 `ALL_OPT`（图融合/常量折叠更彻底，纯加速、不改计算结果）。
     * - det 是单次大输入调用，给它多线程；rec 是「多线程跑多个小框」的模式，
     *   单次调用只给小量线程，避免线程数超订（8 核上开 3×4=12 线程反而变慢）。
     */
    private fun createSession(modelPath: String, intraOpThreads: Int): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraOpThreads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return runCatching { ortEnv.createSession(modelPath, options) }
            .getOrElse { err ->
                options.close()
                throw err
            }
    }

    private fun loadDict(recKey: RecModelKey): List<String> {
        val lines = runCatching {
            appContext.assets.open(catalog.dictAssetName(recKey)).bufferedReader().use { it.readText() }
                .replace("\ufeff", "")
                .split("\n")
                .filter { it.isNotEmpty() }
        }.getOrElse { err ->
            Log.w(TAG, "dict_load_fail reason=${err.message?.take(80)}")
            emptyList()
        }
        // CTCLabelDecode(use_space_char=true): ['blank'] + dict + [' ']
        return buildList {
            add("blank")
            addAll(lines)
            add(" ")
        }
    }

    // ---------- det 预处理 ----------

    private data class DetInput(
        val srcW: Int,
        val srcH: Int,
        val resizeW: Int,
        val resizeH: Int,
        val floats: FloatArray
    )

    private fun detPreprocess(bitmap: Bitmap, resizeLong: Float): DetInput {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val longSide = max(srcH, srcW)
        val ratio = resizeLong / longSide.toFloat()
        val resizeH = max((((srcH * ratio).roundToInt() + 127) / 128) * 128, 32)
        val resizeW = max((((srcW * ratio).roundToInt() + 127) / 128) * 128, 32)

        val scaled = Bitmap.createScaledBitmap(bitmap, resizeW.coerceAtLeast(1), resizeH.coerceAtLeast(1), false)
        val floats = FloatArray(3 * resizeH * resizeW)
        val pixels = IntArray(resizeW * resizeH)
        scaled.getPixels(pixels, 0, resizeW, 0, 0, resizeW, resizeH)

        var idx = 0
        for (c in 0 until 3) {
            var i = 0
            while (i < pixels.size) {
                val px = pixels[i]
                val channel = when (c) {
                    0 -> (px shr 16) and 0xFF
                    1 -> (px shr 8) and 0xFF
                    else -> px and 0xFF
                }
                floats[idx++] = ((channel / 255f) - DET_MEAN[c]) / DET_STD[c]
                i++
            }
        }
        if (scaled !== bitmap) scaled.recycle()
        return DetInput(srcW, srcH, resizeW, resizeH, floats)
    }

    private fun runDet(env: OrtEnvironment, session: OrtSession, input: DetInput): FloatArray {
        val shape = longArrayOf(1, 3, input.resizeH.toLong(), input.resizeW.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input.floats), shape).use { tensor ->
            session.run(mapOf("x" to tensor)).use { result ->
                val output = result.get(0) as OnnxTensor
                val fb = output.floatBuffer
                val buf = FloatArray(fb.remaining())
                fb.get(buf)
                return buf
            }
        }
    }

    // ---------- DB 后处理（与 PaddleOCR db_postprocess 一致） ----------

    private fun dbPostProcess(
        prob: FloatArray,
        mapH: Int,
        mapW: Int,
        ratioH: Float,
        ratioW: Float,
        srcW: Int,
        srcH: Int,
        thresh: Float = DET_THRESH,
        boxThresh: Float = DET_BOX_THRESH,
        unclipRatio: Float = DET_UNCLIP_RATIO
    ): List<Array<PointF>> {
        // 概率图 -> Mat
        val probMat = Mat(mapH, mapW, CvType.CV_32F)
        val row = FloatArray(mapW)
        for (y in 0 until mapH) {
            System.arraycopy(prob, y * mapW, row, 0, mapW)
            probMat.put(y, 0, row)
        }
        // mask = prob > thresh（先阈值到 CV_32F，再转 CV_8U）
        val maskF = Mat(mapH, mapW, CvType.CV_32F)
        Imgproc.threshold(probMat, maskF, thresh.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        val mask = Mat(mapH, mapW, CvType.CV_8UC1)
        maskF.convertTo(mask, CvType.CV_8U)

        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)

        val boxes = mutableListOf<Array<PointF>>()
        val labelRow = IntArray(mapW)
        for (comp in 1 until n) {
            val x0 = stats.get(comp, Imgproc.CC_STAT_LEFT)[0].toInt()
            val y0 = stats.get(comp, Imgproc.CC_STAT_TOP)[0].toInt()
            val cw = stats.get(comp, Imgproc.CC_STAT_WIDTH)[0].toInt()
            val ch = stats.get(comp, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            if (cw < MIN_BOX_SIZE || ch < MIN_BOX_SIZE) continue

            // 收集该连通域像素，计算 minAreaRect
            val pts = mutableListOf<Point>()
            val x1 = min(mapW - 1, x0 + cw - 1)
            val y1 = min(mapH - 1, y0 + ch - 1)
            for (y in y0..y1) {
                labels.get(y, 0, labelRow)
                var x = x0
                while (x <= x1) {
                    if (labelRow[x] == comp) {
                        pts += Point(x.toDouble(), y.toDouble())
                    }
                    x++
                }
            }
            if (pts.size < MIN_POINTS) continue
            val pointsMat = MatOfPoint2f(*pts.toTypedArray())
            val rect = Imgproc.minAreaRect(pointsMat)
            pointsMat.release()

            // box score = 原始 minAreaRect 框内 prob 均值（与 PaddleOCR 一致，先打分再 unclip）
            val score = boxMeanScore(probMat, rect)
            if (score <= boxThresh) continue

            // unclip：按距离向外扩张（d = unclip_ratio * area / perimeter）
            val area = rect.size.width * rect.size.height
            val perimeter = 2 * (rect.size.width + rect.size.height)
            val d = unclipRatio * area / perimeter
            val expanded = RotatedRect(
                rect.center,
                Size(rect.size.width + 2 * d, rect.size.height + 2 * d),
                rect.angle
            )

            // 顺序化四角：TL, TR, BR, BL
            val quad = orderedCorners(expanded, ratioH, ratioW, srcW, srcH)
            // 退化兜底：薄/线状组件 minAreaRect 可能给出近零厚度的矩形，
            // 退化为轴对齐包围盒 quad，避免透视裁剪失败（参考项目 normalize_detection_polygon 同样过滤退化框）。
            if (quadsRight(quad) - quadsLeft(quad) < 2f || quadsBottom(quad) - quadsTop(quad) < 2f) {
                boxes += arrayOf(
                    PointF((x0 / ratioW).toFloat().coerceIn(0f, srcW.toFloat()), (y0 / ratioH).toFloat().coerceIn(0f, srcH.toFloat())),
                    PointF((x1 / ratioW).toFloat().coerceIn(0f, srcW.toFloat()), (y0 / ratioH).toFloat().coerceIn(0f, srcH.toFloat())),
                    PointF((x1 / ratioW).toFloat().coerceIn(0f, srcW.toFloat()), (y1 / ratioH).toFloat().coerceIn(0f, srcH.toFloat())),
                    PointF((x0 / ratioW).toFloat().coerceIn(0f, srcW.toFloat()), (y1 / ratioH).toFloat().coerceIn(0f, srcH.toFloat()))
                )
            } else {
                boxes += quad
            }
        }
        mask.release()
        maskF.release()
        labels.release()
        stats.release()
        centroids.release()
        probMat.release()
        // 按 (top, left) 排序，保证自上而下、从左到右
        return boxes.sortedWith(compareBy({ quadsTop(it) }, { quadsLeft(it) }))
    }

    private fun boxMeanScore(probMat: Mat, rect: RotatedRect): Float {
        val box = Array(4) { Point() }
        rect.points(box)
        val minX = max(0, box.minOf { it.x }.toInt())
        val minY = max(0, box.minOf { it.y }.toInt())
        val maxX = min(probMat.cols() - 1, box.maxOf { it.x }.toInt())
        val maxY = min(probMat.rows() - 1, box.maxOf { it.y }.toInt())
        if (maxX <= minX || maxY <= minY) return 0f
        val width = maxX - minX + 1
        val height = maxY - minY + 1

        val poly = MatOfPoint2f(*box)
        val polyMask = Mat(height, width, CvType.CV_8UC1, Scalar(0.0))
        val polyPts = box.map { p -> Point(p.x - minX, p.y - minY) }.toTypedArray()
        val polyMat = MatOfPoint(*polyPts)
        Imgproc.fillConvexPoly(polyMask, polyMat, Scalar(255.0))

        val sub = probMat.submat(minY, maxY + 1, minX, maxX + 1)
        val mean = Core.mean(sub, polyMask)
        sub.release()
        polyMask.release()
        polyMat.release()
        poly.release()
        return mean.`val`[0].toFloat()
    }

    /**
     * 将 RotatedRect 四点按 TL/TR/BR/BL 顺序还原到原图坐标。
     *
     * 采用参考项目 _order_polygon_points 的角度排序法（按相对中心角度排序 +
     * 最小 (x+y) 起点 + 叉积定向），避免按象限归类在薄/倾斜矩形上
     * 出现角点重叠（两个角落在同一象限互相覆盖 → 退化四边形 → 透视裁剪失败）。
     */
    private fun orderedCorners(
        rect: RotatedRect,
        ratioH: Float,
        ratioW: Float,
        srcW: Int,
        srcH: Int
    ): Array<PointF> {
        val raw = Array(4) { Point() }
        rect.points(raw)
        val cx = rect.center.x
        val cy = rect.center.y
        val angles = raw.map { atan2(it.y - cy, it.x - cx) }
        val order = angles.indices.sortedBy { angles[it] }
        val sorted = order.map { raw[it] }
        val start = sorted.indices.minByOrNull { sorted[it].x + sorted[it].y } ?: 0
        val rolled = List(4) { sorted[(it + start) % 4] }
        val firstEdge = Point(rolled[1].x - rolled[0].x, rolled[1].y - rolled[0].y)
        val secondEdge = Point(rolled[2].x - rolled[1].x, rolled[2].y - rolled[1].y)
        val cross = firstEdge.x * secondEdge.y - firstEdge.y * secondEdge.x
        val orderedPts = if (cross < 0) {
            listOf(rolled[0], rolled[3], rolled[2], rolled[1])
        } else {
            rolled
        }
        fun toOriginal(p: Point): PointF {
            val x = (p.x / ratioW).toFloat().coerceIn(0f, srcW.toFloat())
            val y = (p.y / ratioH).toFloat().coerceIn(0f, srcH.toFloat())
            return PointF(x, y)
        }
        return arrayOf(
            toOriginal(orderedPts[0]),
            toOriginal(orderedPts[1]),
            toOriginal(orderedPts[2]),
            toOriginal(orderedPts[3])
        )
    }

    /**
     * 按检测四边形做透视矫正裁剪（参考 C:\homework\translator _deskew_crop_region）：
     * - 目标宽高取四边形的边（width = max(上边, 下边)、height = max(左边, 右边)），
     *   避免用轴对齐包围盒导致倾斜/变形文字被拉伸失真；
     * - 沿中心向外扩展 padding（min(12, max(2, 边长 × 0.06))），给识别模型留出边距；
     * - 双线性过滤绘制（等价 INTER_CUBIC 的平滑效果）。
     */
    private fun cropQuad(bitmap: Bitmap, quad: Array<PointF>): Bitmap? {
        val tl = quad[0]
        val tr = quad[1]
        val br = quad[2]
        val bl = quad[3]
        // 2026-08-27 增强：对明显退化的四边形直接拒绝，避免 polyToPoly 失败或拉伸失真。
        val health = PaddleQuadGeometry.checkQuadHealth(quad.toPoints())
        if (!health.healthy) {
            Log.w(TAG, "paddle_crop_fail reason=${health.reason} quad=${quadSummary(quad)}")
            return null
        }
        val edge = max(dist(tr, tl), dist(bl, tl))
        // 对倾斜/窄四边形减小 padding，降低角点被压到边界导致失真的风险。
        val aspect = max(dist(tr, tl), dist(bl, tl)) / max(1f, min(dist(tr, tl), dist(bl, tl)))
        val paddingBase = if (aspect > 3f) 0.04f else 0.06f
        val padding = min(10f, max(2f, edge * paddingBase))
        val cx = (tl.x + tr.x + br.x + bl.x) / 4f
        val cy = (tl.y + tr.y + br.y + bl.y) / 4f
        val src = Array(4) { i ->
            val p = quad[i]
            val vx = p.x - cx
            val vy = p.y - cy
            val len = max(1f, hypot(vx.toDouble(), vy.toDouble()).toFloat())
            PointF(
                (p.x + vx / len * padding).coerceIn(0f, bitmap.width - 1f),
                (p.y + vy / len * padding).coerceIn(0f, bitmap.height - 1f)
            )
        }
        val width = max(1f, max(dist(src[1], src[0]), dist(src[2], src[3])))
        val height = max(1f, max(dist(src[3], src[0]), dist(src[2], src[1])))
        val srcPts = floatArrayOf(
            src[0].x, src[0].y,
            src[1].x, src[1].y,
            src[2].x, src[2].y,
            src[3].x, src[3].y
        )
        val dstPts = floatArrayOf(
            0f, 0f,
            width, 0f,
            width, height,
            0f, height
        )
        val m = android.graphics.Matrix()
        if (!m.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) {
            Log.w(
                TAG,
                String.format(
                    java.util.Locale.US,
                    "paddle_crop_fail reason=poly_to_poly quad=%.1f,%.1f|%.1f,%.1f|%.1f,%.1f|%.1f,%.1f dst=%.1fx%.1f",
                    src[0].x, src[0].y, src[1].x, src[1].y, src[2].x, src[2].y, src[3].x, src[3].y,
                    width, height
                )
            )
            return null
        }
        return runCatching {
            Bitmap.createBitmap(width.roundToInt(), height.roundToInt(), Bitmap.Config.ARGB_8888).also { out ->
                val canvas = android.graphics.Canvas(out)
                canvas.drawColor(android.graphics.Color.WHITE)
                val paint = android.graphics.Paint().apply {
                    isFilterBitmap = true
                    isAntiAlias = true
                }
                canvas.drawBitmap(bitmap, m, paint)
            }
        }.getOrNull()
    }

    // ---------- rec 预处理 ----------

    private data class RecInput(val width: Int, val floats: FloatArray)

    private fun recPreprocess(crop: Bitmap): RecInput {
        val srcH = crop.height
        val srcW = crop.width
        val ratio = srcW.toFloat() / srcH.toFloat()
        val maxWhRatio = max(REC_REF_WIDTH / REC_HEIGHT.toFloat(), ratio)
        var targetW = (REC_HEIGHT * maxWhRatio).roundToInt()
        var resizedW: Int
        if (targetW > REC_MAX_IMG_W) {
            resizedW = REC_MAX_IMG_W
            targetW = REC_MAX_IMG_W
        } else {
            resizedW = ceil(REC_HEIGHT * ratio).toInt()
            if (resizedW > targetW) resizedW = targetW
        }
        val safeW = resizedW.coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(crop, safeW, REC_HEIGHT, false)
        val floats = FloatArray(3 * REC_HEIGHT * targetW)
        val pixels = IntArray(REC_HEIGHT * targetW)
        // 2026-09-15：宽度已等于目标宽度时直接读 scaled，省掉一次「建位图 + 画一次」的开销。
        // rec 是整页最热的路径（每页几十~上百次），这里的省法是乘出来的。
        if (safeW == targetW) {
            scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, REC_HEIGHT)
        } else {
            val padded = Bitmap.createBitmap(targetW, REC_HEIGHT, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(padded).drawBitmap(scaled, 0f, 0f, null)
            padded.getPixels(pixels, 0, targetW, 0, 0, targetW, REC_HEIGHT)
            padded.recycle()
        }

        var idx = 0
        for (c in 0 until 3) {
            var i = 0
            while (i < pixels.size) {
                val px = pixels[i]
                val channel = when (c) {
                    0 -> (px shr 16) and 0xFF
                    1 -> (px shr 8) and 0xFF
                    else -> px and 0xFF
                }
                // (x/255 - 0.5) / 0.5
                floats[idx++] = (channel / 255f - 0.5f) / 0.5f
                i++
            }
        }
        if (scaled !== crop) scaled.recycle()
        return RecInput(targetW, floats)
    }

    private fun runRec(
        env: OrtEnvironment,
        session: OrtSession,
        input: RecInput,
        alphabet: List<String>
    ): Pair<String, Float> {
        val shape = longArrayOf(1, 3, REC_HEIGHT.toLong(), input.width.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input.floats), shape).use { tensor ->
            session.run(mapOf("x" to tensor)).use { result ->
                val output = result.get(0) as OnnxTensor
                val info = output.info
                val dims = info.shape
                val fb = output.floatBuffer
                val out = FloatArray(fb.remaining())
                fb.get(out)
                val timeSteps = if (dims.size >= 2) dims[1].toInt() else 0
                return ctcDecode(out, timeSteps, alphabet)
            }
        }
    }

    private fun ctcDecode(logits: FloatArray, width: Int, alphabet: List<String>): Pair<String, Float> {
        val classes = alphabet.size // logits: [T, classes]
        val sb = StringBuilder()
        var prev = -1
        var scoreSum = 0f
        var scoreCount = 0
        for (t in 0 until width) {
            var best = 0
            var bestVal = Float.NEGATIVE_INFINITY
            val offset = t * classes
            if (offset + classes > logits.size) break
            for (c in 0 until classes) {
                val v = logits[offset + c]
                if (v > bestVal) {
                    bestVal = v
                    best = c
                }
            }
            scoreSum += bestVal
            scoreCount++
            if (best != 0 && best != prev) {
                val ch = alphabet.getOrNull(best) ?: continue
                sb.append(ch)
            }
            prev = best
        }
        val avg = if (scoreCount == 0) 0f else scoreSum / scoreCount
        return sb.toString() to avg
    }

    // ---------- 几何辅助 ----------

    private fun quadsLeft(quad: Array<PointF>) = quad.minOf { it.x }
    private fun quadsTop(quad: Array<PointF>) = quad.minOf { it.y }
    private fun quadsRight(quad: Array<PointF>) = quad.maxOf { it.x }
    private fun quadsBottom(quad: Array<PointF>) = quad.maxOf { it.y }

    private fun Array<PointF>.toPoints(): List<PaddleQuadGeometry.Point> =
        map { PaddleQuadGeometry.Point(it.x, it.y) }

    private fun dist(a: PointF, b: PointF): Float =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    private fun quadSummary(quad: Array<PointF>): String {
        return quad.joinToString("|") { "%.1f,%.1f".format(java.util.Locale.US, it.x, it.y) }
    }

    private fun speedModeConfig(): SpeedModeConfig {
        val mode = speedModeProvider?.invoke()?.trim()?.lowercase() ?: "balanced"
        return when (mode) {
            "fast" -> SpeedModeConfig.FAST
            "quality" -> SpeedModeConfig.QUALITY
            else -> SpeedModeConfig.BALANCED
        }
    }

    /**
     * PaddleOCR 速度/质量模式参数配置。
     * 2026-08-27 新增：首页可选「快速/均衡/质量」，调整检测分辨率与识别变体数量。
     */
    data class SpeedModeConfig(
        val detResizeLong: Float,
        val recCascadeHighConf: Float,
        val recCascadeMidConf: Float,
        val recoveryMinBlocks: Int,
        val recoveryMinCoverage: Float,
        val recoveryMaxVariants: Int,
        val recoveryEarlyStopBoxes: Int
    ) {
        companion object {
            val FAST = SpeedModeConfig(
                detResizeLong = 768f,
                recCascadeHighConf = 0.92f,
                recCascadeMidConf = 0.86f,
                recoveryMinBlocks = 3,
                recoveryMinCoverage = 0.008f,
                recoveryMaxVariants = 1,
                recoveryEarlyStopBoxes = 1
            )
            val BALANCED = SpeedModeConfig(
                detResizeLong = 960f,
                recCascadeHighConf = 0.90f,
                recCascadeMidConf = 0.82f,
                recoveryMinBlocks = 4,
                recoveryMinCoverage = 0.005f,
                recoveryMaxVariants = 3,
                recoveryEarlyStopBoxes = 2
            )
            val QUALITY = SpeedModeConfig(
                detResizeLong = 960f,
                recCascadeHighConf = 0.88f,
                recCascadeMidConf = 0.80f,
                recoveryMinBlocks = 6,
                recoveryMinCoverage = 0.004f,
                recoveryMaxVariants = 3,
                recoveryEarlyStopBoxes = 3
            )
        }
    }

    private companion object {
        const val TAG = "PaddleOcr"
        const val MODEL_DIR_NAME = "paddle_models"
        const val DET_THRESH = 0.3f
        const val DET_BOX_THRESH = 0.6f
        const val DET_UNCLIP_RATIO = 1.2f
        const val DET_THRESH_RECOVERY = 0.2f
        const val DET_BOX_THRESH_RECOVERY = 0.42f
        const val DET_UNCLIP_RATIO_RECOVERY = 1.5f
        const val MIN_BOX_SIZE = 3
        const val MIN_POINTS = 4
        const val REC_HEIGHT = 48
        const val REC_REF_WIDTH = 320
        const val REC_MAX_IMG_W = 3200
        const val MERGE_IOU_THRESHOLD = 0.45f
        const val MIN_BOX_SIZE_PX = 2

        /** 文字框识别的最大并行度（框级并行）。 */
        const val REC_MAX_PARALLELISM = 4

        /** 单个文字框识别任务的超时上限，超时只丢该框结果、不阻塞整页。 */
        const val REC_TASK_TIMEOUT_MS = 20_000L

        /** `paddle_lines_all`（合并前行级清单）最多打印几条：够看清边界行，又不把 logcat 撑爆。 */
        private const val MAX_LINE_PROBE = 14
        /** det 单次推理最大 intra-op 线程数。 */
        const val DET_MAX_INTRA_OP_THREADS = 4

        /** rec 并行度>1（框级并行已占满核）时，单次推理给 2 线程。 */
        const val REC_INTRA_OP_THREADS_WHEN_PARALLEL = 2

        /** rec 并行度=1（单核/单框）时，单次推理用满 4 线程。 */
        const val REC_INTRA_OP_THREADS_WHEN_SERIAL = 4
        val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

/**
 * 模型清单与下载地址（默认 PaddleOcrNet 托管；模型与参考项目同源）。
 */
class PaddleModelCatalog {
    val httpClient: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    enum class RecModelKey(val id: String, val recModel: String, val dictAsset: String) {
        ZH("zh", "PP-OCRv5_mobile_rec.onnx", "paddle_models/paddle_zh_dict.txt"),
        JA("ja", "japan_PP-OCRv5_mobile_rec.onnx", "paddle_models/paddle_ja_dict.txt"),
        KO("ko", "korean_PP-OCRv5_mobile_rec.onnx", "paddle_models/paddle_ko_dict.txt");

        companion object {
            fun fromSourceLanguage(lang: String): RecModelKey {
                return when (lang.lowercase()) {
                    "ja", "jpn", "japanese", "日文" -> JA
                    "ko", "kor", "korean", "韩文" -> KO
                    else -> ZH
                }
            }
        }
    }

    val detModelFileName: String = "PP-OCRv5_mobile_det.onnx"

    fun recKeyFor(sourceLanguageId: String): RecModelKey = RecModelKey.fromSourceLanguage(sourceLanguageId)

    fun recModelFileName(key: RecModelKey): String = key.recModel

    fun dictAssetName(key: RecModelKey): String = key.dictAsset

    /** 打包进 APK assets 的 det 模型路径（存在即离线；缺失才回退下载）。 */
    fun detAssetName(): String = "paddle_models/$detModelFileName"

    /** 打包进 APK assets 的 rec 模型路径（存在即离线；缺失才回退下载）。 */
    fun recAssetName(key: RecModelKey): String = "paddle_models/${key.recModel}"

    fun detModelUrl(): String = "$HF_BASE_URL/PP-OCRv5_mobile_det.onnx"

    fun recModelUrl(key: RecModelKey): String = "$HF_BASE_URL/${key.recModel}"

    /** 百度 BOS 官方 ONNX tar（国内可达，需解包 inference.onnx）；日/韩 rec 无官方 tar，直接走 HF。 */
    fun detModelTarUrl(): String = "$BOS_BASE_URL/PP-OCRv5_mobile_det_onnx.tar"

    fun recModelTarUrl(key: RecModelKey): String? {
        return if (key == RecModelKey.ZH) {
            "$BOS_BASE_URL/PP-OCRv5_mobile_rec_onnx.tar"
        } else {
            null
        }
    }

    companion object {
        private const val HF_BASE_URL = "https://huggingface.co/PaddleOcrNet/PaddleOcrNet-models/resolve/main"
        private const val BOS_BASE_URL = "https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0"
    }
}