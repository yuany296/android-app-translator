package com.manga.translator.overlay

import android.graphics.Bitmap
import android.util.Log
import com.manga.translator.translate.BubbleRegion
import com.manga.translator.translate.BubbleRegionType
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

class BubbleRegionDetector {
    fun detect(source: Bitmap): List<BubbleRegion> {
        if (source.width < 24 || source.height < 24) return emptyList()
        if (!isOpenCvReady()) {
            Log.w(TAG, "bubble_region_detect_skip reason=opencv_unavailable")
            return emptyList()
        }
        val scale = min(1f, DETECT_MAX_EDGE.toFloat() / max(source.width, source.height))
        val work = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                max(1, (source.width * scale).toInt()),
                max(1, (source.height * scale).toInt()),
                false
            )
        } else {
            source
        }
        return try {
            detectOn(work).map { region ->
                if (scale < 1f) {
                    BubbleRegion(
                        id = region.id,
                        left = (region.left / scale).coerceIn(0f, source.width.toFloat()),
                        top = (region.top / scale).coerceIn(0f, source.height.toFloat()),
                        right = (region.right / scale).coerceIn(0f, source.width.toFloat()),
                        bottom = (region.bottom / scale).coerceIn(0f, source.height.toFloat()),
                        regionType = region.regionType,
                        confidence = region.confidence,
                        source = region.source
                    )
                } else {
                    region
                }
            }
        } finally {
            if (work !== source && !work.isRecycled) {
                work.recycle()
            }
        }
    }

    private fun detectOn(source: Bitmap): List<BubbleRegion> {
        val srcMat = Mat()
        val grayMat = Mat()
        val blurMat = Mat()
        val binaryMat = Mat()
        val morphMat = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
        return try {
            Utils.bitmapToMat(source, srcMat)
            Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(grayMat, blurMat, Size(5.0, 5.0), 0.0)
            Imgproc.threshold(blurMat, binaryMat, BRIGHT_THRESHOLD, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.morphologyEx(binaryMat, morphMat, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.findContours(
                morphMat,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            val pageArea = source.width.toFloat() * source.height.toFloat()
            val candidates = contours.mapNotNull { contour ->
                val contourArea = Imgproc.contourArea(contour).toFloat()
                if (contourArea <= 0f) return@mapNotNull null
                val rect = Imgproc.boundingRect(contour)
                buildCandidate(grayMat, contour, rect, contourArea, pageArea, source.width, source.height)
            }
            val deduped = dedupeCandidates(candidates)
            Log.d(TAG, "bubble_region_detect count=${deduped.size} raw=${contours.size}")
            deduped.mapIndexed { index, candidate ->
                BubbleRegion(
                    id = index,
                    left = candidate.left,
                    top = candidate.top,
                    right = candidate.right,
                    bottom = candidate.bottom,
                    regionType = BubbleRegionType.BUBBLE,
                    confidence = candidate.confidence,
                    source = "opencv_contour"
                )
            }
        } catch (err: Throwable) {
            Log.w(TAG, "bubble_region_detect_fail: ${err.message}")
            emptyList()
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
            morphMat.release()
            binaryMat.release()
            blurMat.release()
            grayMat.release()
            srcMat.release()
            kernel.release()
        }
    }

    private fun buildCandidate(
        grayMat: Mat,
        contour: MatOfPoint,
        rect: Rect,
        contourArea: Float,
        pageArea: Float,
        pageWidth: Int,
        pageHeight: Int
    ): CandidateRegion? {
        if (rect.width < MIN_REGION_EDGE_PX || rect.height < MIN_REGION_EDGE_PX) return null
        val rectArea = rect.width.toFloat() * rect.height.toFloat()
        if (rectArea <= 0f) return null
        val areaRatio = rectArea / pageArea.coerceAtLeast(1f)
        if (areaRatio !in MIN_REGION_AREA_RATIO..MAX_REGION_AREA_RATIO) return null

        val fillRatio = contourArea / rectArea
        if (fillRatio < MIN_FILL_RATIO) return null
        if (fillRatio > MAX_FILL_RATIO_FOR_LARGE_RECT && areaRatio >= LARGE_RECT_AREA_RATIO) return null

        val contour2f = MatOfPoint2f(*contour.toArray())
        val approx2f = MatOfPoint2f()
        val approxVertices = try {
            val perimeter = Imgproc.arcLength(contour2f, true)
            Imgproc.approxPolyDP(contour2f, approx2f, perimeter * 0.02, true)
            approx2f.total().toInt()
        } finally {
            approx2f.release()
            contour2f.release()
        }
        if (approxVertices in 4..6 && areaRatio >= LARGE_RECT_AREA_RATIO) return null

        val roi = grayMat.submat(rect)
        val meanLuma = try {
            Core.mean(roi).`val`[0].toFloat()
        } finally {
            roi.release()
        }
        if (meanLuma < MIN_AVERAGE_LUMA) return null

        val aspect = rect.width.toFloat() / rect.height.toFloat().coerceAtLeast(1f)
        if (aspect < MIN_ASPECT_RATIO || aspect > MAX_ASPECT_RATIO) return null

        val padX = max(MIN_PADDING_PX.toFloat(), rect.width.toFloat() * REGION_PAD_RATIO).toInt()
        val padY = max(MIN_PADDING_PX.toFloat(), rect.height.toFloat() * REGION_PAD_RATIO).toInt()
        val left = (rect.x - padX).coerceIn(0, pageWidth - 1).toFloat()
        val top = (rect.y - padY).coerceIn(0, pageHeight - 1).toFloat()
        val right = (rect.x + rect.width + padX).coerceIn(1, pageWidth).toFloat()
        val bottom = (rect.y + rect.height + padY).coerceIn(1, pageHeight).toFloat()
        if (right - left < MIN_REGION_EDGE_PX || bottom - top < MIN_REGION_EDGE_PX) return null

        val confidence = (
            (meanLuma / 255f) * 0.45f +
                fillRatio.coerceIn(0f, 1f) * 0.35f +
                (1f - min(1f, kotlin.math.abs(aspect - 1f) / 2f)) * 0.20f
            ).coerceIn(0f, 1f)

        return CandidateRegion(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            confidence = confidence
        )
    }

    private fun dedupeCandidates(candidates: List<CandidateRegion>): List<CandidateRegion> {
        if (candidates.size <= 1) return candidates
        val sorted = candidates.sortedByDescending { it.confidence }
        val kept = mutableListOf<CandidateRegion>()
        sorted.forEach { candidate ->
            val duplicated = kept.any { intersectionOverMinArea(candidate, it) >= DEDUPE_OVERLAP_RATIO }
            if (!duplicated) {
                kept += candidate
            }
        }
        return kept.sortedWith(compareBy<CandidateRegion> { it.top }.thenBy { it.left })
    }

    private fun intersectionOverMinArea(a: CandidateRegion, b: CandidateRegion): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val minArea = min(a.area(), b.area()).coerceAtLeast(1f)
        return interArea / minArea
    }

    private fun isOpenCvReady(): Boolean {
        opencvReady?.let { return it }
        val ready = runCatching { OpenCVLoader.initDebug() }.getOrDefault(false)
        opencvReady = ready
        return ready
    }

    private data class CandidateRegion(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val confidence: Float
    ) {
        fun area(): Float = (right - left).coerceAtLeast(1f) * (bottom - top).coerceAtLeast(1f)
    }

    private companion object {
        const val TAG = "BubbleRegionDetect"
        private const val BRIGHT_THRESHOLD = 208.0
        private const val MIN_REGION_EDGE_PX = 28
        private const val MIN_REGION_AREA_RATIO = 0.0025f
        private const val MAX_REGION_AREA_RATIO = 0.38f
        private const val MIN_FILL_RATIO = 0.46f
        private const val MAX_FILL_RATIO_FOR_LARGE_RECT = 0.88f
        private const val LARGE_RECT_AREA_RATIO = 0.015f
        private const val MIN_AVERAGE_LUMA = 182f
        private const val MIN_ASPECT_RATIO = 0.24f
        private const val MAX_ASPECT_RATIO = 4.8f
        private const val REGION_PAD_RATIO = 0.06f
        private const val MIN_PADDING_PX = 6
        private const val DEDUPE_OVERLAP_RATIO = 0.72f
        private const val DETECT_MAX_EDGE = 720

        @Volatile
        private var opencvReady: Boolean? = null
    }
}
