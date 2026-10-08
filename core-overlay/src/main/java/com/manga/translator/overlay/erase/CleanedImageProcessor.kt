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
     * @param includeSolid true 时把 solid 气泡的文字区域也纳入 mask（整页擦字，浏览器链路
     *   「inpaint 清理图」选项，观感更好但更慢）；false 仅擦非 solid（悬浮球链路默认行为）。
     * @return 整页清理图（调用方持有的独立副本），无可用清理区域或 OpenCV 不可用时返回 null。
     */
    fun buildCleanedImage(
        source: Bitmap,
        bubbles: List<Bubble>,
        pageHash: String,
        includeSolid: Boolean = false
    ): Bitmap? {
        if (!isOpenCvReady()) return null
        val cacheKey = cacheKey(pageHash, includeSolid)
        val cached = cache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            return cached.copy(Bitmap.Config.ARGB_8888, false)
        }
        val startedAt = SystemClock.elapsedRealtime()
        val built = runCatching {
            build(source, bubbles, includeSolid)
        }.getOrElse { err ->
            Log.w(TAG, "cleaned_image build failed: ${err.message}")
            null
        }
        if (built != null) {
            cache.put(cacheKey, built.copy(Bitmap.Config.ARGB_8888, false))
            Log.d(
                TAG,
                "cleaned_image built size=${built.width}x${built.height} " +
                    "ms=${SystemClock.elapsedRealtime() - startedAt} bubbles=${bubbles.size} " +
                    "include_solid=$includeSolid"
            )
        } else {
            Log.d(
                TAG,
                "cleaned_image skipped ms=${SystemClock.elapsedRealtime() - startedAt} " +
                    "bubbles=${bubbles.size} include_solid=$includeSolid"
            )
        }
        return built
    }

    /** solid / 非 solid 两种 mask 不能共用缓存条目（2026-09-28）。 */
    private fun cacheKey(pageHash: String, includeSolid: Boolean): String {
        return if (includeSolid) "$pageHash|include_solid" else pageHash
    }

    private fun build(source: Bitmap, bubbles: List<Bubble>, includeSolid: Boolean): Bitmap? {
        val targets = if (includeSolid) bubbles else bubbles.filter { !isSolidBubble(it) }
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
                // 外扩：OCR 内容框是贴着字形的，粗体/描边字的抗锯齿边、连笔常常落在框外，
                // 只擦框内会留一圈原字残影（用户 2026-09-28 反馈「清理图有时候不生效」）。
                // solid 气泡内圈都是背景，可以多擦；但仍要留出气泡描边，故用外框做上限保护。
                val solid = isSolidBubble(bubble)
                val padX = (roi.width() * if (solid) SOLID_PAD_X_RATIO else NARRATION_PAD_RATIO)
                    .roundToInt().coerceIn(2, if (solid) SOLID_MAX_PAD_PX else NARRATION_MAX_PAD_PX)
                val padY = (roi.height() * if (solid) SOLID_PAD_Y_RATIO else NARRATION_PAD_RATIO)
                    .roundToInt().coerceIn(2, if (solid) SOLID_MAX_PAD_PX else NARRATION_MAX_PAD_PX)
                val outer = outerRectPx(source, bubble)
                // ⚠️ 「外框保护」只有在**外框真的把内容框包住**时才允许生效（2026-09-30 修）。
                //
                // 外框（bubble.x/y/w/h）= 区域检测给的亮斑框。亮斑检测器会把整片浅色/渐变页面
                // 检成一个巨大的假阳性区域：实测某页斜排文字在 `y 929..1066`，区域却是
                // `y 981..1399`。此时 `coerceAtLeast(outer.top + 4)` 会把 mask 的上边界推到
                // **文字框下方 56px** —— 斜排文字左上角那半截（「기울」两个音节）从来没被擦过，
                // 清理图里就留着半截原字（用户原话「倾斜文本的清理图也消失了」：译文旁边
                // 还飘着原字，看着就像清理图没生成）。同源判据见
                // `app/…/BubbleFirstRegionPlanner.isSolidBubbleRegion`。
                val usableOuter = outer?.takeIf {
                    isOuterGuardUsable(it.left, it.top, it.right, it.bottom, roi.left, roi.top, roi.right, roi.bottom)
                }
                val left = (roi.left - padX).coerceAtLeast(outerGuardLeft(usableOuter))
                val top = (roi.top - padY).coerceAtLeast(outerGuardTop(usableOuter))
                val right = (roi.right + padX).coerceAtMost(outerGuardRight(usableOuter, source.width))
                val bottom = (roi.bottom + padY).coerceAtMost(outerGuardBottom(usableOuter, source.height))
                if (right - left < 2 || bottom - top < 2) return@forEach
                val target = android.graphics.Rect(left, top, right, bottom)
                // 只把「字」打成 mask，保留框内的画面/渐变：
                // 整块矩形 inpaint 会把旁白底下的画面抹成一片糊（2026-09-28 实测 residual_max=0.26），
                // 墨迹级 mask 则只重建笔画像素，背景纹理原样保留。
                paintInkMask(source, target, mask)
                val radius = maskRadiusPx(bottom - top, solid)
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

    /**
     * 把 [target] 区域里的「文字笔画」写进 mask（0/255），背景纹理不动。
     *
     * ⚠️ 判据必须是**RGB 距离**，不能只看亮度（2026-09-29 用户反馈「粉色的后面没有清理图，
     * 我还看得到文字」的根因）：粉色旁白 `#e0447c` 的亮度是 121，它所在画面的亮度是 153，
     * 只差 32；而旧的 `INK_DELTA_THRESHOLD = 46` 把差值 32 判成「不是字」→ mask 为空 →
     * inpaint 什么都没改 → 导出的清理图与原图**逐像素相同**（诊断页实测 avg 完全一致）。
     * 用 RGB 欧氏距离时这一对的差值是 107，稳稳超过阈值。
     * 参考项目 `rendering/bubble-colors.js` 同样用 RGB 距离（42）而不是亮度。
     */
    private fun paintInkMask(source: Bitmap, target: android.graphics.Rect, mask: Mat) {
        val width = target.width()
        val height = target.height()
        if (width <= 0 || height <= 0) return
        val background = backgroundRgb(source, target)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, target.left, target.top, width, height)
        val data = buildInkMaskData(pixels, background)
        // 笔画极密（> DENSE_INK_RATIO）时退化为整块擦除：这时几乎没有背景可保留，
        // 零碎的 mask 反而会让 inpaint 出花斑。
        var ink = 0
        for (value in data) if (value.toInt() != 0) ink++
        if (ink.toFloat() / pixels.size.toFloat() > DENSE_INK_RATIO) {
            java.util.Arrays.fill(data, 0xFF.toByte())
        }
        // 注意：必须用 submat 定位。Mat.put(row, col, data) 会把 data 当作「从 col 一直铺到行尾」，
        // 宽度不等于整图宽时会把 mask 铺到错误位置（2026-09-28 实测把整页擦花）。
        val region = mask.submat(target.top, target.bottom, target.left, target.right)
        try {
            region.put(0, 0, data)
        } finally {
            region.release()
        }
    }

    /**
     * 区域背景色估计：16 级/通道的粗直方图取众数桶，再对该桶内的真实像素求均值。
     *
     * 用「众数桶」而不是均值：文字块里背景占比通常远高于笔画，众数桶就是背景；
     * 直接求均值会被笔画颜色拖偏（粉色字会把均值往粉里带，反而判不出粉字）。
     */
    private fun backgroundRgb(source: Bitmap, rect: android.graphics.Rect): Int {
        val counts = IntArray(BG_BUCKETS)
        val sumR = LongArray(BG_BUCKETS)
        val sumG = LongArray(BG_BUCKETS)
        val sumB = LongArray(BG_BUCKETS)
        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                val pixel = source.getPixel(x, y)
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                val bucket = ((red shr 4) shl 8) or ((green shr 4) shl 4) or (blue shr 4)
                counts[bucket] = counts[bucket] + 1
                sumR[bucket] = sumR[bucket] + red.toLong()
                sumG[bucket] = sumG[bucket] + green.toLong()
                sumB[bucket] = sumB[bucket] + blue.toLong()
                x += 2
            }
            y += 2
        }
        var best = 0
        for (index in counts.indices) {
            if (counts[index] > counts[best]) best = index
        }
        val samples = counts[best]
        if (samples <= 0) return android.graphics.Color.WHITE
        return android.graphics.Color.rgb(
            (sumR[best] / samples).toInt().coerceIn(0, 255),
            (sumG[best] / samples).toInt().coerceIn(0, 255),
            (sumB[best] / samples).toInt().coerceIn(0, 255)
        )
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

    private fun outerRectPx(source: Bitmap, bubble: Bubble): android.graphics.Rect? {
        val rect = BubbleGeometry.outerRect(bubble)
        if (rect.width <= 0f || rect.height <= 0f) return null
        val left = ((rect.left / 100f) * source.width).roundToInt().coerceIn(0, source.width - 1)
        val top = ((rect.top / 100f) * source.height).roundToInt().coerceIn(0, source.height - 1)
        val right = ((rect.right / 100f) * source.width).roundToInt().coerceIn(0, source.width)
        val bottom = ((rect.bottom / 100f) * source.height).roundToInt().coerceIn(0, source.height)
        if (right <= left || bottom <= top) return null
        return android.graphics.Rect(left, top, right, bottom)
    }

    /** 外扩不得吃掉气泡描边：留 [BUBBLE_BORDER_GUARD_PX] 的边距（无外框信息时退回 0）。 */
    private fun outerGuardLeft(outer: android.graphics.Rect?): Int =
        if (outer == null) 0 else outer.left + BUBBLE_BORDER_GUARD_PX

    private fun outerGuardTop(outer: android.graphics.Rect?): Int =
        if (outer == null) 0 else outer.top + BUBBLE_BORDER_GUARD_PX

    private fun outerGuardRight(outer: android.graphics.Rect?, width: Int): Int =
        if (outer == null) width else (outer.right - BUBBLE_BORDER_GUARD_PX).coerceAtMost(width)

    private fun outerGuardBottom(outer: android.graphics.Rect?, height: Int): Int =
        if (outer == null) height else (outer.bottom - BUBBLE_BORDER_GUARD_PX).coerceAtMost(height)

    private fun maskRadiusPx(boxHeightPx: Int, solid: Boolean): Int {
        val ratio = if (solid) SOLID_DILATE_RATIO else NARRATION_DILATE_RATIO
        val cap = if (solid) SOLID_MAX_DILATE_PX else NARRATION_MAX_DILATE_PX
        return max(2, min(cap, (boxHeightPx * ratio).roundToInt()))
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

        // 内容框外扩（用户反馈「清理图有时候不生效」后的加固，2026-09-28）：
        // solid 气泡内圈都是背景，按字号比例多擦一圈；旁白压在画面上，只小幅外扩。
        private const val SOLID_PAD_X_RATIO = 0.10f
        private const val SOLID_PAD_Y_RATIO = 0.16f
        private const val SOLID_MAX_PAD_PX = 12
        private const val NARRATION_PAD_RATIO = 0.05f
        private const val NARRATION_MAX_PAD_PX = 5

        // 膨胀半径同样按字号缩放（旧实现固定 8px 封顶，粗体/描边大字擦不干净）。
        private const val SOLID_DILATE_RATIO = 0.18f
        private const val SOLID_MAX_DILATE_PX = 22
        private const val NARRATION_DILATE_RATIO = 0.08f
        private const val NARRATION_MAX_DILATE_PX = 8

        /** 外扩上限：至少给气泡描边留这么多像素，避免把气泡边框一起 inpaint 掉。 */
        private const val BUBBLE_BORDER_GUARD_PX = 4

        /** 笔画占比超过该比例就整块擦（没什么背景可保留，零碎 mask 会出花斑）。 */
        private const val DENSE_INK_RATIO = 0.55f
    }
}

/**
 * 墨迹判定阈值：与背景色的 **RGB 欧氏距离**超过该值才算笔画。
 *
 * 不能退回「亮度差」——彩色字在中间调背景上亮度可能几乎一样
 * （粉字 `#e0447c` luma=121 vs 背景 luma=153，只差 32），亮度判据会把整片彩色文字漏掉，
 * 清理图于是与原图**逐像素相同**（2026-09-29 用户反馈「粉色的后面没有清理图」）。
 * 取值与参考项目 `rendering/bubble-colors.js` 的 42 对齐（略高，减少复杂背景误判）。
 */
internal const val INK_RGB_DISTANCE = 45f

/** 背景色直方图桶数：16³（每通道 16 级）。 */
internal const val BG_BUCKETS = 16 * 16 * 16

/**
 * 「气泡外框保护」是否可用（纯函数，便于 JVM 单测）。
 *
 * 只有外框**真的把内容框包住**时才允许用它当外扩上限。理由见
 * [CleanedImageProcessor.build] 里的说明：亮斑区域检测器会把整片浅色页面检成巨大的
 * 假阳性框（实测斜排文字 `y 929..1066` / 区域 `y 981..1399`），拿它收边会把 mask 的
 * 上边界推到文字框下方，半截原文从来没被擦过。
 *
 * @return true = 外框可信（可以拿它收边），false = 外框不可信（当作没有外框，只靠内容框 + pad）
 */
internal fun isOuterGuardUsable(
    outerLeft: Int,
    outerTop: Int,
    outerRight: Int,
    outerBottom: Int,
    contentLeft: Int,
    contentTop: Int,
    contentRight: Int,
    contentBottom: Int
): Boolean =
    outerLeft <= contentLeft &&
        outerTop <= contentTop &&
        outerRight >= contentRight &&
        outerBottom >= contentBottom

/**
 * 纯函数版墨迹 mask（便于 JVM 单测）：与 [background] 的 RGB 距离超过 [INK_RGB_DISTANCE]
 * 的像素标记为 0xFF。
 */
internal fun buildInkMaskData(pixels: IntArray, background: Int): ByteArray {
    val br = (background shr 16) and 0xFF
    val bg = (background shr 8) and 0xFF
    val bb = background and 0xFF
    val limit = INK_RGB_DISTANCE * INK_RGB_DISTANCE
    val out = ByteArray(pixels.size)
    for (index in pixels.indices) {
        val pixel = pixels[index]
        val dr = ((pixel shr 16) and 0xFF) - br
        val dg = ((pixel shr 8) and 0xFF) - bg
        val db = (pixel and 0xFF) - bb
        val distanceSquared = (dr * dr + dg * dg + db * db).toFloat()
        if (distanceSquared > limit) out[index] = 0xFF.toByte()
    }
    return out
}