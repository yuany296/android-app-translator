package com.manga.translator.overlay.erase

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.manga.translator.translate.Bubble
import com.manga.translator.translate.BubbleGeometry
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 整页“清理图”生成器，对齐参考项目 local-ocr-service 的 build_cleaned_image_data_url：
 * 用 OCR 检测出的文字区域（此处为非 solid 气泡的内容框）生成 mask，
 * 椭圆膨胀后对整页做一次 TELEA inpaint，得到“去字原图”。
 * 渲染层按每个气泡的原始位置裁切贴回覆盖层，作为旁白/无框文字的底纹。
 */
class CleanedImageProcessor {
    private val cache = object : LruCache<String, Bitmap>(CLEANED_CACHE_COUNT) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (!oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    private var opencvReady: Boolean? = null

    /**
     * @return 整页清理图（调用方持有的独立副本），无可用清理区域或 OpenCV 不可用时返回 null。
     */
    fun buildCleanedImage(source: Bitmap, bubbles: List<Bubble>, pageHash: String): Bitmap? {
        if (!isOpenCvReady()) return null
        val cached = cache.get(pageHash)
        if (cached != null && !cached.isRecycled) {
            return cached.copy(Bitmap.Config.ARGB_8888, false)
        }
        val startedAt = SystemClock.elapsedRealtime()
        val built = runCatching {
            build(source, bubbles)
        }.getOrElse { err ->
            Log.w(TAG, "cleaned_image build failed: ${err.message}")
            null
        }
        if (built != null) {
            cache.put(pageHash, built.copy(Bitmap.Config.ARGB_8888, false))
            Log.d(
                TAG,
                "cleaned_image built size=${built.width}x${built.height} " +
                    "ms=${SystemClock.elapsedRealtime() - startedAt} bubbles=${bubbles.size}"
            )
        } else {
            Log.d(
                TAG,
                "cleaned_image skipped ms=${SystemClock.elapsedRealtime() - startedAt} bubbles=${bubbles.size}"
            )
        }
        return built
    }

    private fun build(source: Bitmap, bubbles: List<Bubble>): Bitmap? {
        val targets = bubbles.filter { !isSolidBubble(it) }
        if (targets.isEmpty()) return null
        if (source.width <= 0 || source.height <= 0 || source.isRecycled) return null

        val srcMat = Mat()
        val bgrMat = Mat()
        val mask = Mat.zeros(source.height, source.width, CvType.CV_8UC1)
        val dst = Mat()
        try {
            Utils.bitmapToMat(source, srcMat)
            // inpaint 仅支持 3 通道 BGR 输入，bitmapToMat 产出 4 通道 RGBA，先转换。
            Imgproc.cvtColor(srcMat, bgrMat, Imgproc.COLOR_RGBA2BGR)
            var changed = false
            targets.forEach { bubble ->
                val roi = contentRectPx(source, bubble) ?: return@forEach
                if (roi.width() <= 0 || roi.height() <= 0) return@forEach
                Imgproc.rectangle(
                    mask,
                    Point(roi.left.toDouble(), roi.top.toDouble()),
                    Point((roi.right - 1).toDouble(), (roi.bottom - 1).toDouble()),
                    Scalar(255.0),
                    -1
                )
                val radius = maskRadiusPx(roi.height())
                if (radius > 0) {
                    val kernel = Imgproc.getStructuringElement(
                        Imgproc.MORPH_ELLIPSE,
                        Size((radius * 2 + 1).toDouble(), (radius * 2 + 1).toDouble())
                    )
                    Imgproc.dilate(mask, mask, kernel)
                }
                changed = true
            }
            if (!changed) return null
            Photo.inpaint(bgrMat, mask, dst, INPAINT_RADIUS, Photo.INPAINT_TELEA)
            val rgba = Mat()
            try {
                Imgproc.cvtColor(dst, rgba, Imgproc.COLOR_BGR2RGBA)
                val cleaned = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(rgba, cleaned)
                return cleaned
            } finally {
                rgba.release()
            }
        } finally {
            srcMat.release()
            bgrMat.release()
            mask.release()
            dst.release()
        }
    }

    private fun contentRectPx(source: Bitmap, bubble: Bubble): android.graphics.Rect? {
        val rect = BubbleGeometry.contentRect(bubble)
        if (rect.width <= 0f || rect.height <= 0f) return null
        val left = ((rect.left / 100f) * source.width).roundToInt().coerceIn(0, source.width - 1)
        val top = ((rect.top / 100f) * source.height).roundToInt().coerceIn(0, source.height - 1)
        val right = ((rect.right / 100f) * source.width).roundToInt().coerceIn(0, source.width)
        val bottom = ((rect.bottom / 100f) * source.height).roundToInt().coerceIn(0, source.height)
        if (right <= left || bottom <= top) return null
        return android.graphics.Rect(left, top, right, bottom)
    }

    private fun maskRadiusPx(boxHeightPx: Int): Int {
        return max(2, min(8, (boxHeightPx * 0.08f).roundToInt()))
    }

    private fun isSolidBubble(bubble: Bubble): Boolean {
        return bubble.bgType.equals("solid", ignoreCase = true)
    }

    private fun isOpenCvReady(): Boolean {
        opencvReady?.let { return it }
        val ready = runCatching { OpenCVLoader.initDebug() }.getOrDefault(false)
        opencvReady = ready
        return ready
    }

    companion object {
        private const val TAG = "CleanedImageProcessor"
        private const val CLEANED_CACHE_COUNT = 4
        private const val INPAINT_RADIUS = 3.0
    }
}