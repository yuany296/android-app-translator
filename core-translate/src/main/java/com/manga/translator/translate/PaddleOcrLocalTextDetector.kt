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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.ceil
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
    private val catalog: PaddleModelCatalog = PaddleModelCatalog()
) : LocalTextDetector {

    private val loadLock = ReentrantLock()
    private val ortEnv: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var detSession: OrtSession? = null
    private val recSessions = HashMap<RecModelKey, OrtSession>()
    private var modelDir: File? = null
    private var opencvReady: Boolean? = null

    @Volatile
    private var recCascadeHits = 0

    @Volatile
    private var recCascadeFull = 0

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
                detSession = createSession(modelDir!!.resolve(catalog.detModelFileName).absolutePath)
            }
            if (!recSessions.containsKey(recKey)) {
                recSessions[recKey] = createSession(modelDir!!.resolve(catalog.recModelFileName(recKey)).absolutePath)
            }
        }
        return ready
    }

    private fun runDetect(bitmap: Bitmap, recKey: RecModelKey): List<TextBlock> {
        val startedAt = System.currentTimeMillis()
        val srcW = bitmap.width
        val srcH = bitmap.height

        // 1. 主检测：原图 + 默认参数（与参考项目 production 默认值对齐）
        val primaryQuads = runDetPass(
            bitmap = bitmap,
            thresh = DET_THRESH,
            boxThresh = DET_BOX_THRESH,
            unclipRatio = DET_UNCLIP_RATIO,
            tag = "primary"
        )
        val allQuads = primaryQuads.toMutableList()

        // 2. 如果主检测块数少 / 覆盖率低，跑 recovery 检测：
        //    用增强灰度/二值掩码/反色增强 + 更松的阈值，召回彩色艺术字和低对比度字。
        val coverage = estimateTextCoverage(primaryQuads, srcW, srcH)
        val needRecovery = primaryQuads.size < RECOVERY_MIN_BLOCKS || coverage < RECOVERY_MIN_COVERAGE
        if (needRecovery) {
            // recovery 变体只需在降采样工作图上构建：det 内部仍会缩到 DET_RESIZE_LONG，
            // 在缩小图上跑像素级变体（灰度/二值/反色）比全分辨率省 ~6 倍，quad 再映射回原图坐标。
            val scale = min(1f, DET_RESIZE_LONG / max(srcW, srcH).toFloat())
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
            for ((name, builder) in variants) {
                if (variantsRun >= RECOVERY_MAX_VARIANTS) break
                val variant = runCatching { builder() }.getOrNull() ?: continue
                variantsRun++
                val quads = runDetPass(
                    bitmap = variant,
                    thresh = DET_THRESH_RECOVERY,
                    boxThresh = DET_BOX_THRESH_RECOVERY,
                    unclipRatio = DET_UNCLIP_RATIO_RECOVERY,
                    tag = "recovery_$name"
                )
                for (quad in quads) {
                    val mapped = quad.map { point ->
                        PointF(
                            (point.x * srcW / workW).coerceIn(0f, srcW.toFloat()),
                            (point.y * srcH / workH).coerceIn(0f, srcH.toFloat())
                        )
                    }.toTypedArray()
                    if (allQuads.none { existing -> quadIoU(existing, mapped) >= MERGE_IOU_THRESHOLD }) {
                        allQuads += mapped
                    }
                }
                variant.recycle()
            }
            if (work !== bitmap) {
                work.recycle()
            }
        }

        // 3. 对每个候选框在原图上裁剪，并用多个识别变体投票，选出最可信结果。
        val recSession = recSessions[recKey] ?: return emptyList()
        val alphabet = loadDict(recKey)
        val rawBlocks = mutableListOf<TextBlock>()
        allQuads.forEachIndexed { index, quad ->
            val cropOriginal = cropQuad(bitmap, quad) ?: return@forEachIndexed
            if (cropOriginal.width < MIN_BOX_SIZE_PX || cropOriginal.height < MIN_BOX_SIZE_PX) {
                cropOriginal.recycle()
                return@forEachIndexed
            }
            val (text, score) = recognizeBest(cropOriginal, recSession, alphabet)
            cropOriginal.recycle()
            if (text.isBlank()) return@forEachIndexed
            val left = quadsLeft(quad).coerceIn(0f, srcW.toFloat())
            val top = quadsTop(quad).coerceIn(0f, srcH.toFloat())
            val right = quadsRight(quad).coerceIn(left + 1f, srcW.toFloat())
            val bottom = quadsBottom(quad).coerceIn(top + 1f, srcH.toFloat())
            rawBlocks += TextBlock(
                id = index,
                text = text.trim(),
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                confidence = score
            )
        }

        // 4. 后聚合：把同一行/同一段落的碎片合并成完整句子（参考 translator 的 ocr-lines）
        val merged = PaddleOcrBlockMerger.mergeBlocks(rawBlocks, srcW, srcH)
        val totalMs = System.currentTimeMillis() - startedAt
        Log.d(
            TAG,
            "paddle_total_ms=${totalMs} primary=${primaryQuads.size} total_quads=${allQuads.size} " +
                "raw_blocks=${rawBlocks.size} merged=${merged.size} recovery=$needRecovery coverage=${"%.4f".format(coverage)} " +
                "rec_cascade_hit=$recCascadeHits rec_cascade_full=$recCascadeFull lang=${recKey.id}"
        )
        return merged
    }

    private fun runDetPass(
        bitmap: Bitmap,
        thresh: Float,
        boxThresh: Float,
        unclipRatio: Float,
        tag: String
    ): List<Array<PointF>> {
        val detSession = detSession ?: return emptyList()
        val srcW = bitmap.width
        val srcH = bitmap.height
        val detInput = detPreprocess(bitmap)
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

    private fun recognizeBest(crop: Bitmap, recSession: OrtSession, alphabet: List<String>): Pair<String, Float> {
        // 级联快速路径：原图识别置信度足够高就直接返回，跳过其余 7 个变体。
        // 清晰文字是绝大多数场景，能省 ~7/8 的识别耗时；低置信度框仍走全变体投票。
        val raw = runCatching { recognizeCrop(crop, recSession, alphabet) }.getOrDefault("" to 0f)
        if (raw.first.isNotBlank() && raw.second >= REC_CASCADE_HIGH_CONF) {
            recCascadeHits++
            return raw
        }
        recCascadeFull++
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
        val needDownload = !(detFile.exists() && detFile.length() > 1_000_000 && recFile.exists() && recFile.length() > 1_000_000)
        if (!needDownload) return true
        if (!isNetworkAvailable()) {
            Log.w(TAG, "model_download_skip reason=no_network")
            return false
        }
        Log.i(TAG, "model_download_start det=${catalog.detModelFileName} rec=${catalog.recModelFileName(recKey)}")
        if (!dir.exists()) dir.mkdirs()
        val ok = runCatching {
            downloadWithFallback(
                primary = catalog.detModelTarUrl(),
                primaryIsTar = true,
                direct = catalog.detModelUrl(),
                target = detFile
            )
            downloadWithFallback(
                primary = catalog.recModelTarUrl(recKey),
                primaryIsTar = true,
                direct = catalog.recModelUrl(recKey),
                target = recFile
            )
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

    private fun createSession(modelPath: String): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
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

    private fun detPreprocess(bitmap: Bitmap): DetInput {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val longSide = max(srcH, srcW)
        val ratio = DET_RESIZE_LONG.toFloat() / longSide.toFloat()
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
            boxes += quad
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

    /** 将 RotatedRect 四点按 TL/TR/BR/BL 顺序还原到原图坐标。 */
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
        fun toOriginal(p: Point): PointF {
            val x = (p.x / ratioW).toFloat().coerceIn(0f, srcW.toFloat())
            val y = (p.y / ratioH).toFloat().coerceIn(0f, srcH.toFloat())
            return PointF(x, y)
        }
        // 按相对中心位置归类：TL(x<cx,y<cy) TR(x>cx,y<cy) BR(x>cx,y>cy) BL(x<cx,y>cy)
        var tl: Point? = null
        var tr: Point? = null
        var br: Point? = null
        var bl: Point? = null
        for (p in raw) {
            val left = p.x < cx
            val top = p.y < cy
            when {
                left && top -> tl = p
                !left && top -> tr = p
                !left && !top -> br = p
                else -> bl = p
            }
        }
        // 旋转矩形可能存在退化（点几乎在中心线上），退化时按 minAreaRect 惯例处理
        val fallback = raw.toList()
        val ordered = arrayOf(
            tl ?: fallback[0],
            tr ?: fallback[1],
            br ?: fallback[2],
            bl ?: fallback[3]
        )
        return arrayOf(toOriginal(ordered[0]), toOriginal(ordered[1]), toOriginal(ordered[2]), toOriginal(ordered[3]))
    }

    private fun cropQuad(bitmap: Bitmap, quad: Array<PointF>): Bitmap? {
        val w = max(1f, quadsRight(quad) - quadsLeft(quad))
        val h = max(1f, quadsBottom(quad) - quadsTop(quad))
        val srcPts = floatArrayOf(
            quad[0].x, quad[0].y,
            quad[1].x, quad[1].y,
            quad[2].x, quad[2].y,
            quad[3].x, quad[3].y
        )
        val dstPts = floatArrayOf(
            0f, 0f,
            w, 0f,
            w, h,
            0f, h
        )
        val m = android.graphics.Matrix()
        if (!m.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) {
            return null
        }
        return runCatching {
            Bitmap.createBitmap(w.roundToInt(), h.roundToInt(), Bitmap.Config.ARGB_8888).also { out ->
                val canvas = android.graphics.Canvas(out)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(bitmap, m, null)
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
        val scaled = Bitmap.createScaledBitmap(crop, resizedW.coerceAtLeast(1), REC_HEIGHT, false)
        val floats = FloatArray(3 * REC_HEIGHT * targetW)
        val pixels = IntArray(REC_HEIGHT * targetW)
        val padded = Bitmap.createBitmap(targetW, REC_HEIGHT, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(padded).drawBitmap(scaled, 0f, 0f, null)
        padded.getPixels(pixels, 0, targetW, 0, 0, targetW, REC_HEIGHT)

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
        padded.recycle()
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

    private companion object {
        const val TAG = "PaddleOcr"
        const val MODEL_DIR_NAME = "paddle_models"
        const val DET_RESIZE_LONG = 960f
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
        // 原图识别置信度达到该值即跳过其余识别变体（级联快速路径）。
        const val REC_CASCADE_HIGH_CONF = 0.93f
        const val RECOVERY_MIN_BLOCKS = 4
        const val RECOVERY_MIN_COVERAGE = 0.005f
        const val RECOVERY_MAX_VARIANTS = 3
        const val MERGE_IOU_THRESHOLD = 0.45f
        const val MIN_BOX_SIZE_PX = 2
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