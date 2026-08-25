package com.manga.translator.overlay.erase

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.LruCache
import android.util.Log
import com.manga.translator.overlay.EraseStrategy
import com.manga.translator.overlay.EraseStrategyPolicy
import com.manga.translator.translate.Bubble
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

class TieredEraseProcessor(
    private val analyzer: EraseComplexityAnalyzer = EraseComplexityAnalyzer()
) {
    private val cache = object : LruCache<String, List<CachedPatch>>(8) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: List<CachedPatch>, newValue: List<CachedPatch>?) {
            if (!evicted) return
            oldValue.forEach { cached ->
                if (!cached.bitmap.isRecycled) {
                    cached.bitmap.recycle()
                }
            }
        }
    }

    private var opencvReady: Boolean? = null

    fun buildPageHash(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sampleStepX = max(1, bitmap.width / 48)
        val sampleStepY = max(1, bitmap.height / 48)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                digest.update((p and 0xFF).toByte())
                digest.update(((p shr 8) and 0xFF).toByte())
                digest.update(((p shr 16) and 0xFF).toByte())
                x += sampleStepX
            }
            y += sampleStepY
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun erasePage(
        source: Bitmap,
        bubbles: List<Bubble>,
        pageHash: String,
        policy: EraseStrategyPolicy,
        onPerf: ((tag: String, extra: String?) -> Unit)? = null,
        cleanedImage: Bitmap? = null
    ): List<ErasePatch> {
        if (bubbles.isEmpty()) return emptyList()
        val key = buildCacheKey(pageHash, bubbles, policy, cleanedImage != null)
        val cached = cache.get(key)
        if (cached != null) {
            onPerf?.invoke("erase_apply", "cache_hit=true patches=${cached.size}")
            return cached.map { patch ->
                ErasePatch(
                    bitmap = patch.bitmap.copy(Bitmap.Config.ARGB_8888, false),
                    srcRect = RectF(patch.srcRect),
                    renderRect = RectF(patch.srcRect),
                    bubbleId = patch.bubbleId
                )
            }
        }

        onPerf?.invoke("erase_prepare", "bubbles=${bubbles.size}")
        val summary = hashMapOf<EraseStrategy, Int>()
        val patches = mutableListOf<ErasePatch>()
        var replacedPixels = 0L
        var candidatePixels = 0L
        var narrationPatched = 0
        val startedAt = SystemClock.elapsedRealtime()

        bubbles.forEachIndexed { index, bubble ->
            if (bubble.originalText.isBlank()) {
                return@forEachIndexed
            }
            val isSolid = isSolidBubble(bubble)

            // 非 solid（旁白/透明面板）：优先从整页清理图（inpaint 去字原图）裁切贴片，
            // 纹理直接来自原图背景，避免纯色块/墨色替换带来的色差，观感接近参考项目。
            if (!isSolid && cleanedImage != null && !cleanedImage.isRecycled) {
                val cleanedPatch = createCleanedPatch(cleanedImage, bubble, index)
                if (cleanedPatch != null) {
                    patches += cleanedPatch
                    narrationPatched++
                    return@forEachIndexed
                }
            }

            val complexity = analyzer.analyze(source, bubble)
            var strategy = resolveStrategy(policy, complexity)
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed > ERASE_BUDGET_MS && strategy == EraseStrategy.OPENCV_INPAINT) {
                strategy = EraseStrategy.EDGE_AWARE_FILL
            }
            summary[strategy] = (summary[strategy] ?: 0) + 1

            // 非 solid（旁白/透明面板）同样生成“创可贴”清理贴片：以文字内容框为 ROI，
            // 只替换文字墨色像素，边缘感知填充贴近原背景，避免文字叠加看不清。
            val textureAware = !isSolid || complexity.variance >= HIGH_VARIANCE_THRESHOLD
            val result = when (strategy) {
                EraseStrategy.FAST_FILL -> createLocalPatch(
                    source = source,
                    bubble = bubble,
                    bubbleId = index,
                    extraPaddingPx = if (isSolid) FAST_PADDING_PX else EDGE_PADDING_PX,
                    edgeAware = textureAware,
                    enableEdgeTransition = textureAware,
                    includeBrightInk = !isSolid
                )

                EraseStrategy.EDGE_AWARE_FILL -> createLocalPatch(
                    source = source,
                    bubble = bubble,
                    bubbleId = index,
                    extraPaddingPx = EDGE_PADDING_PX,
                    edgeAware = true,
                    enableEdgeTransition = textureAware,
                    includeBrightInk = !isSolid
                )

                EraseStrategy.OPENCV_INPAINT -> createOpenCvPatch(source, bubble, index)
                    ?: createLocalPatch(
                        source = source,
                        bubble = bubble,
                        bubbleId = index,
                        extraPaddingPx = EDGE_PADDING_PX,
                        edgeAware = true,
                        enableEdgeTransition = true,
                        includeBrightInk = !isSolid
                    )
            }

            if (result != null) {
                replacedPixels += result.replacedPixelCount
                candidatePixels += result.candidatePixelCount
                patches += result.patch
                if (!isSolid) {
                    narrationPatched++
                }
            }
        }

        val fast = summary[EraseStrategy.FAST_FILL] ?: 0
        val edge = summary[EraseStrategy.EDGE_AWARE_FILL] ?: 0
        val cv = summary[EraseStrategy.OPENCV_INPAINT] ?: 0
        val ratio = if (candidatePixels > 0L) {
            (replacedPixels.toFloat() / candidatePixels.toFloat())
        } else {
            0f
        }
        onPerf?.invoke(
            "erase_apply",
            "cache_hit=false fast=$fast edge=$edge opencv=$cv patches=${patches.size} narration_patched=$narrationPatched erase_dark_replace_ratio=${"%.3f".format(ratio)} cleaned=${cleanedImage != null}"
        )

        cache.put(
            key,
            patches.map { patch ->
                CachedPatch(
                    bitmap = patch.bitmap.copy(Bitmap.Config.ARGB_8888, false),
                    srcRect = RectF(patch.srcRect),
                    bubbleId = patch.bubbleId
                )
            }
        )
        return patches
    }

    private fun resolveStrategy(policy: EraseStrategyPolicy, complexity: EraseComplexity): EraseStrategy {
        return when (policy) {
            EraseStrategyPolicy.FAST_ONLY -> EraseStrategy.FAST_FILL
            EraseStrategyPolicy.OPENCV_WHEN_AVAILABLE -> {
                if (isOpenCvReady()) EraseStrategy.OPENCV_INPAINT else EraseStrategy.EDGE_AWARE_FILL
            }

            EraseStrategyPolicy.AUTO_TIERED -> {
                if (complexity.variance > EDGE_VARIANCE_TRIGGER || complexity.edgeDensity > EDGE_DENSITY_TRIGGER) {
                    EraseStrategy.EDGE_AWARE_FILL
                } else {
                    EraseStrategy.FAST_FILL
                }
            }
        }
    }

    private fun createCleanedPatch(cleanedImage: Bitmap, bubble: Bubble, bubbleId: Int): ErasePatch? {
        val roi = analyzer.bubbleToRoi(cleanedImage, bubble, CLEANED_PATCH_PAD_PX)
        if (roi.width() <= 0 || roi.height() <= 0) return null
        val crop = runCatching {
            Bitmap.createBitmap(cleanedImage, roi.left, roi.top, roi.width(), roi.height())
        }.getOrNull() ?: return null
        // 独立副本：crop 与清理图共享像素缓冲，需复制避免后续回收互相影响。
        val bitmap = crop.copy(Bitmap.Config.ARGB_8888, false)
        crop.recycle()
        val srcRect = RectF(roi)
        return ErasePatch(
            bitmap = bitmap,
            srcRect = srcRect,
            renderRect = RectF(srcRect),
            bubbleId = bubbleId
        )
    }

    private fun createLocalPatch(
        source: Bitmap,
        bubble: Bubble,
        bubbleId: Int,
        extraPaddingPx: Int,
        edgeAware: Boolean,
        enableEdgeTransition: Boolean,
        includeBrightInk: Boolean = false
    ): PatchBuildResult? {
        val roi = analyzer.bubbleToRoi(source, bubble, extraPaddingPx)
        if (roi.width() <= 0 || roi.height() <= 0) return null

        val width = roi.width()
        val height = roi.height()
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, roi.left, roi.top, width, height)

        val borderProfile = sampleBorderProfile(source, roi)
        val fillColor = borderProfile.centerColor
        val topColor = if (edgeAware) borderProfile.topColor else fillColor
        val bottomColor = if (edgeAware) borderProfile.bottomColor else fillColor
        val leftColor = if (edgeAware) borderProfile.leftColor else fillColor
        val rightColor = if (edgeAware) borderProfile.rightColor else fillColor
        val radius = (min(width, height) * if (edgeAware) 0.14f else 0.12f).coerceAtLeast(6f)
        val fillIsDark = luma(fillColor) < BRIGHT_FILL_LUMA_GUARD

        var replacedCount = 0
        var candidateCount = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val origin = pixels[index]
                if (!isInsideRoundedRect(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), radius)) {
                    pixels[index] = Color.TRANSPARENT
                    continue
                }
                candidateCount += 1
                val isDarkInk = luma(origin) < DARK_LUMA_THRESHOLD
                val isBrightInk = includeBrightInk && fillIsDark && luma(origin) >= BRIGHT_INK_LUMA_THRESHOLD
                if (isDarkInk || isBrightInk) {
                    val target = if (edgeAware) {
                        interpolateEdgeColor(x, y, width, height, topColor, bottomColor, leftColor, rightColor)
                    } else {
                        fillColor
                    }
                    val refinedTarget = regressTowardBubbleBrightness(target, borderProfile.centerBrightness)
                    pixels[index] = if (enableEdgeTransition) {
                        blendWithEdgeTransition(origin, refinedTarget, x, y, width, height)
                    } else {
                        refinedTarget
                    }
                    replacedCount += 1
                } else {
                    pixels[index] = if (enableEdgeTransition) {
                        softenPatchEdge(origin, borderProfile.centerColor, x, y, width, height)
                    } else {
                        origin
                    }
                }
            }
        }

        val patchBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        patchBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val srcRect = RectF(roi)
        return PatchBuildResult(
            patch = ErasePatch(
                bitmap = patchBitmap,
                srcRect = srcRect,
                renderRect = RectF(srcRect),
                bubbleId = bubbleId
            ),
            replacedPixelCount = replacedCount,
            candidatePixelCount = candidateCount
        )
    }

    private fun createOpenCvPatch(source: Bitmap, bubble: Bubble, bubbleId: Int): PatchBuildResult? {
        if (!isOpenCvReady()) return null
        val roi = analyzer.bubbleToRoi(source, bubble, EDGE_PADDING_PX)
        if (roi.width() <= 1 || roi.height() <= 1) return null

        return runCatching {
            val width = roi.width()
            val height = roi.height()
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, roi.left, roi.top, width, height)
            val patchBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            patchBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

            val srcMat = Mat()
            Utils.bitmapToMat(patchBitmap, srcMat)
            val mask = Mat.zeros(srcMat.rows(), srcMat.cols(), CvType.CV_8UC1)
            val maskBytes = ByteArray(width * height)
            val radius = (min(width, height) * 0.14f).coerceAtLeast(6f)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val inside = isInsideRoundedRect(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), radius)
                    if (inside) {
                        maskBytes[y * width + x] = 0xFF.toByte()
                    }
                }
            }
            mask.put(0, 0, maskBytes)

            val dst = Mat()
            Imgproc.GaussianBlur(mask, mask, Size(5.0, 5.0), 0.0)

            val blurred = Mat()
            val texture = Mat()
            Imgproc.GaussianBlur(srcMat, blurred, Size(7.0, 7.0), 0.0)

            Photo.inpaint(blurred, mask, dst, OPENCV_INPAINT_RADIUS, Photo.INPAINT_NS)
            Core.subtract(srcMat, blurred, texture)
            Core.add(dst, texture, dst)
            Utils.matToBitmap(dst, patchBitmap)
            applyRoundAlphaMask(patchBitmap, radius)

            srcMat.release()
            mask.release()
            blurred.release()
            texture.release()
            dst.release()

            val srcRect = RectF(roi)
            PatchBuildResult(
                patch = ErasePatch(
                    bitmap = patchBitmap,
                    srcRect = srcRect,
                    renderRect = RectF(srcRect),
                    bubbleId = bubbleId
                ),
                replacedPixelCount = 0,
                candidatePixelCount = 0
            )
        }.getOrElse { err ->
            Log.w(TAG, "opencv inpaint fallback: ${err.message}")
            null
        }
    }

    private fun applyRoundAlphaMask(bitmap: Bitmap, radius: Float) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!isInsideRoundedRect(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), radius)) {
                    pixels[y * width + x] = Color.TRANSPARENT
                }
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun isOpenCvReady(): Boolean {
        opencvReady?.let { return it }
        val ready = runCatching { OpenCVLoader.initDebug() }.getOrDefault(false)
        opencvReady = ready
        return ready
    }

    private fun isSolidBubble(bubble: Bubble): Boolean {
        return bubble.bgType.equals("solid", ignoreCase = true)
    }

    private fun luma(color: Int): Float {
        return Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f
    }

    private fun blendWithEdgeTransition(origin: Int, target: Int, x: Int, y: Int, width: Int, height: Int): Int {
        val distanceToEdge = min(min(x, y), min(width - 1 - x, height - 1 - y)).toFloat()
        if (distanceToEdge >= EDGE_TRANSITION_PX) return target
        val ratio = (distanceToEdge / EDGE_TRANSITION_PX).coerceIn(0f, 1f)
        return lerpColor(origin, target, ratio)
    }

    private fun isInsideRoundedRect(x: Float, y: Float, width: Float, height: Float, radius: Float): Boolean {
        val cx = x.coerceIn(radius, width - 1f - radius)
        val cy = y.coerceIn(radius, height - 1f - radius)
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= radius * radius
    }

    private fun interpolateEdgeColor(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        top: Int,
        bottom: Int,
        left: Int,
        right: Int
    ): Int {
        val fx = if (width <= 1) 0f else x.toFloat() / (width - 1).toFloat()
        val fy = if (height <= 1) 0f else y.toFloat() / (height - 1).toFloat()
        val vertical = lerpColor(top, bottom, fy)
        val horizontal = lerpColor(left, right, fx)
        return lerpColor(vertical, horizontal, 0.5f)
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val ratio = t.coerceIn(0f, 1f)
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * ratio).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * ratio).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ratio).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    private fun sampleBorderProfile(bitmap: Bitmap, roi: Rect): BorderProfile {
        val topSamples = ArrayList<Int>()
        val bottomSamples = ArrayList<Int>()
        val leftSamples = ArrayList<Int>()
        val rightSamples = ArrayList<Int>()
        val brightSamples = ArrayList<Int>()
        val topY = (roi.top + 1).coerceAtMost(roi.bottom - 1)
        val bottomY = (roi.bottom - 2).coerceAtLeast(roi.top)
        val leftX = (roi.left + 1).coerceAtMost(roi.right - 1)
        val rightX = (roi.right - 2).coerceAtLeast(roi.left)

        var x = roi.left
        while (x < roi.right) {
            val top = bitmap.getPixel(x, topY)
            val bottom = bitmap.getPixel(x, bottomY)
            topSamples += top
            bottomSamples += bottom
            if (luma(top) >= DARK_LUMA_THRESHOLD) brightSamples += top
            if (luma(bottom) >= DARK_LUMA_THRESHOLD) brightSamples += bottom
            x += SAMPLE_STEP_PX
        }
        var y = roi.top
        while (y < roi.bottom) {
            val left = bitmap.getPixel(leftX, y)
            val right = bitmap.getPixel(rightX, y)
            leftSamples += left
            rightSamples += right
            if (luma(left) >= DARK_LUMA_THRESHOLD) brightSamples += left
            if (luma(right) >= DARK_LUMA_THRESHOLD) brightSamples += right
            y += SAMPLE_STEP_PX
        }
        val allSamples = ArrayList<Int>(topSamples.size + bottomSamples.size + leftSamples.size + rightSamples.size)
        allSamples += topSamples
        allSamples += bottomSamples
        allSamples += leftSamples
        allSamples += rightSamples
        val centerColor = weightedBlendColor(
            medianColor(allSamples),
            averageColor(if (brightSamples.isEmpty()) allSamples else brightSamples),
            0.38f
        )
        return BorderProfile(
            centerColor = centerColor,
            centerBrightness = luma(centerColor),
            topColor = weightedBlendColor(medianColor(topSamples), centerColor, 0.35f),
            bottomColor = weightedBlendColor(medianColor(bottomSamples), centerColor, 0.35f),
            leftColor = weightedBlendColor(medianColor(leftSamples), centerColor, 0.35f),
            rightColor = weightedBlendColor(medianColor(rightSamples), centerColor, 0.35f)
        )
    }

    private fun sampleEdgeMedianColor(bitmap: Bitmap, roi: Rect, edge: Edge): Int {
        val samples = ArrayList<Int>()
        when (edge) {
            Edge.TOP -> {
                val y = (roi.top + 1).coerceAtMost(roi.bottom - 1)
                var x = roi.left
                while (x < roi.right) {
                    samples += bitmap.getPixel(x, y)
                    x += SAMPLE_STEP_PX
                }
            }

            Edge.BOTTOM -> {
                val y = (roi.bottom - 2).coerceAtLeast(roi.top)
                var x = roi.left
                while (x < roi.right) {
                    samples += bitmap.getPixel(x, y)
                    x += SAMPLE_STEP_PX
                }
            }

            Edge.LEFT -> {
                val x = (roi.left + 1).coerceAtMost(roi.right - 1)
                var y = roi.top
                while (y < roi.bottom) {
                    samples += bitmap.getPixel(x, y)
                    y += SAMPLE_STEP_PX
                }
            }

            Edge.RIGHT -> {
                val x = (roi.right - 2).coerceAtLeast(roi.left)
                var y = roi.top
                while (y < roi.bottom) {
                    samples += bitmap.getPixel(x, y)
                    y += SAMPLE_STEP_PX
                }
            }
        }
        return medianColor(samples)
    }

    private fun medianColor(samples: List<Int>): Int {
        if (samples.isEmpty()) return Color.WHITE
        val rs = IntArray(samples.size)
        val gs = IntArray(samples.size)
        val bs = IntArray(samples.size)
        samples.forEachIndexed { index, color ->
            rs[index] = Color.red(color)
            gs[index] = Color.green(color)
            bs[index] = Color.blue(color)
        }
        rs.sort()
        gs.sort()
        bs.sort()
        val mid = samples.size / 2
        return Color.rgb(rs[mid], gs[mid], bs[mid])
    }

    private fun averageColor(samples: List<Int>): Int {
        if (samples.isEmpty()) return Color.WHITE
        var r = 0f
        var g = 0f
        var b = 0f
        samples.forEach { color ->
            r += Color.red(color)
            g += Color.green(color)
            b += Color.blue(color)
        }
        val count = samples.size.toFloat().coerceAtLeast(1f)
        return Color.rgb(
            (r / count).toInt().coerceIn(0, 255),
            (g / count).toInt().coerceIn(0, 255),
            (b / count).toInt().coerceIn(0, 255)
        )
    }

    private fun weightedBlendColor(primary: Int, secondary: Int, secondaryWeight: Float): Int {
        return lerpColor(primary, secondary, secondaryWeight.coerceIn(0f, 1f))
    }

    private fun regressTowardBubbleBrightness(target: Int, desiredLuma: Float): Int {
        val currentLuma = luma(target).coerceAtLeast(1f)
        val ratio = (desiredLuma / currentLuma).coerceIn(0.92f, 1.06f)
        val r = (Color.red(target) * ratio).toInt().coerceIn(0, 255)
        val g = (Color.green(target) * ratio).toInt().coerceIn(0, 255)
        val b = (Color.blue(target) * ratio).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun softenPatchEdge(origin: Int, target: Int, x: Int, y: Int, width: Int, height: Int): Int {
        val distanceToEdge = min(min(x, y), min(width - 1 - x, height - 1 - y)).toFloat()
        if (distanceToEdge > SOFTEN_EDGE_PX) return origin
        val ratio = ((SOFTEN_EDGE_PX - distanceToEdge) / SOFTEN_EDGE_PX).coerceIn(0f, 1f) * 0.18f
        return lerpColor(origin, target, ratio)
    }

    private fun buildCacheKey(
        pageHash: String,
        bubbles: List<Bubble>,
        policy: EraseStrategyPolicy,
        hasCleanedImage: Boolean = false
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CACHE_KEY_VERSION.toByteArray(Charsets.UTF_8))
        digest.update(pageHash.toByteArray(Charsets.UTF_8))
        digest.update(policy.name.toByteArray(Charsets.UTF_8))
        digest.update("cleaned=$hasCleanedImage".toByteArray(Charsets.UTF_8))
        bubbles.forEachIndexed { index, bubble ->
            val keyPart =
                "$index,${bubble.x},${bubble.y},${bubble.w},${bubble.h}," +
                    "${bubble.contentX},${bubble.contentY},${bubble.contentW},${bubble.contentH},${bubble.bgType}"
            digest.update(
                keyPart.toByteArray(Charsets.UTF_8)
            )
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private enum class Edge {
        TOP,
        BOTTOM,
        LEFT,
        RIGHT
    }

    private data class CachedPatch(
        val bitmap: Bitmap,
        val srcRect: RectF,
        val bubbleId: Int
    )

    private data class PatchBuildResult(
        val patch: ErasePatch,
        val replacedPixelCount: Int,
        val candidatePixelCount: Int
    )

    private data class BorderProfile(
        val centerColor: Int,
        val centerBrightness: Float,
        val topColor: Int,
        val bottomColor: Int,
        val leftColor: Int,
        val rightColor: Int
    )

    companion object {
        private const val TAG = "TieredEraseProcessor"

private const val DARK_LUMA_THRESHOLD = 190f
        private const val BRIGHT_INK_LUMA_THRESHOLD = 225f
        private const val BRIGHT_FILL_LUMA_GUARD = 200f
        private const val HIGH_VARIANCE_THRESHOLD = 800f
        private const val EDGE_TRANSITION_PX = 4f
        private const val OPENCV_INPAINT_RADIUS = 5.0
        private const val SOFTEN_EDGE_PX = 2f
        private const val SAMPLE_STEP_PX = 3

        private const val FAST_PADDING_PX = 2
        private const val EDGE_PADDING_PX = 4
        // 清理图裁切贴片的外扩：需 >= mask 最大膨胀半径（8px），保证裁切边是未 inpaint 的原图像素，无缝衔接。
        private const val CLEANED_PATCH_PAD_PX = 10

        private const val EDGE_VARIANCE_TRIGGER = 320f
        private const val EDGE_DENSITY_TRIGGER = 0.16f

        // OpenCV inpaint 单气泡约 50-150ms；预算过小会在首个气泡后就全部降级为 EDGE_AWARE_FILL。
        // 提高到 3000ms，让一页（约 10-20 个气泡）都能走整块背景纹理恢复，接近参考项目 cleaned image 观感。
        private const val ERASE_BUDGET_MS = 3000L
        private const val CACHE_KEY_VERSION = "erase_v4|opencv_enabled|cleaned_image"
    }
}
