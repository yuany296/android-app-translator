package com.manga.translator.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.manga.translator.store.PageBubbleRect
import com.manga.translator.store.PageSnapshotImage
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 翻译历史页面快照（2026-11-02 用户要求）。
 *
 * 用户原话：「按页查看翻译历史的时候保留原图」「原图和添加标记版都要」「其他问题也可能需要保存」。
 *
 * 设计取舍：
 * - 只持久化**原图缩略图**（360px 宽、最长 1400px、JPEG 72），不存全尺寸原图；
 * - 「标记版」不落盘，查看时用页面快照里的气泡坐标在缩略图上实时画框 ——
 *   标记层可随时调整、也不额外占空间；
 * - 网页链路的历史坐标是**拼接图**（上下含接缝条带），缩略图只裁当前页，
 *   因此用 [PageSnapshotImage.topPercent]/[heightPercent] 记录裁剪映射，
 *   画框时先减起点再按高度比例缩放。
 */
object HistorySnapshotFactory {

    private const val TAG = "HistorySnapshot"

    /** 缩略图最大宽度（px）。360 足够看清页面结构与框的大致位置，单张通常 20-60KB。 */
    private const val MAX_WIDTH = 360

    /** 缩略图最大高度（px）。webtoon 长图按高度也限一下，防止单张过大。 */
    private const val MAX_HEIGHT = 1400

    private const val JPEG_QUALITY = 72

    /** 外框颜色（Bubble x/y/w/h）。 */
    private const val OUTER_COLOR = 0xFF3B82F6.toInt()

    /** 内容框颜色（Bubble contentX/Y/W/H）—— 与渲染盖板/擦字用的是同一个框。 */
    private const val CONTENT_COLOR = 0xFFEF4444.toInt()

    /**
     * 从翻译时的源图裁出当前页并压缩成缩略图。
     *
     * @param source 翻译时的图（网页链路是拼接图，悬浮球链路是整张截图）
     * @param cropTopPx 当前页在 [source] 里的起点；悬浮球链路传 0
     * @param cropHeightPx 当前页高度；<=0 表示整张图
     */
    fun create(
        source: Bitmap,
        cropTopPx: Int = 0,
        cropHeightPx: Int = 0
    ): PageSnapshotImage? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        val top = cropTopPx.coerceIn(0, source.height - 1)
        val height = (if (cropHeightPx > 0) cropHeightPx else source.height)
            .coerceIn(1, source.height - top)

        val scale = min(
            MAX_WIDTH.toFloat() / source.width.toFloat(),
            MAX_HEIGHT.toFloat() / height.toFloat()
        ).coerceAtMost(1f)
        val outW = (source.width * scale).roundToInt().coerceAtLeast(1)
        val outH = (height * scale).roundToInt().coerceAtLeast(1)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(
                source,
                Rect(0, top, source.width, top + height),
                Rect(0, 0, outW, outH),
                paint
            )
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
            val topPercent = top.toFloat() / source.height.toFloat() * 100f
            val heightPercent = height.toFloat() / source.height.toFloat() * 100f
            Log.d(
                TAG,
                "snapshot_create src=${source.width}x${source.height} crop=$top+$height " +
                    "out=${outW}x${outH} bytes=${bos.size()}"
            )
            PageSnapshotImage(
                jpeg = bos.toByteArray(),
                topPercent = topPercent,
                heightPercent = heightPercent
            )
        } catch (err: Throwable) {
            Log.w(TAG, "snapshot_create_fail reason=${err.message?.take(80)}")
            null
        } finally {
            out.recycle()
        }
    }

    /**
     * 在缩略原图上画标记版：蓝 = 外框，红 = 内容框。
     *
     * 标记版**不落盘**，每次查看时现画（页面快照本来就有气泡几何）。
     * 这样用户既能看原图，也能看框位置，且不会因为多存一份图翻倍占空间。
     */
    fun annotate(
        original: Bitmap,
        rects: List<PageBubbleRect>,
        topPercent: Float,
        heightPercent: Float
    ): Bitmap {
        val out = original.copy(Bitmap.Config.ARGB_8888, true) ?: return original
        if (rects.isEmpty()) return out
        val canvas = Canvas(out)
        val safeHeight = heightPercent.coerceAtLeast(0.1f)
        val stroke = max(2f, out.width / 180f)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        rects.forEach { rect ->
            val outer = mapRect(rect.x, rect.y, rect.w, rect.h, out, topPercent, safeHeight)
            val content = mapRect(
                rect.contentX,
                rect.contentY,
                rect.contentW,
                rect.contentH,
                out,
                topPercent,
                safeHeight
            )
            if (outer != null) {
                strokePaint.color = OUTER_COLOR
                canvas.drawRect(outer, strokePaint)
            }
            if (content != null) {
                fillPaint.color = Color.argb(46, 239, 68, 68)
                canvas.drawRect(content, fillPaint)
                strokePaint.color = CONTENT_COLOR
                canvas.drawRect(content, strokePaint)
            }
        }
        return out
    }

    private fun mapRect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        out: Bitmap,
        topPercent: Float,
        heightPercent: Float
    ): RectF? {
        if (w <= 0f || h <= 0f) return null
        val left = x / 100f * out.width
        val right = (x + w) / 100f * out.width
        val top = ((y - topPercent) / heightPercent) * out.height
        val bottom = ((y + h - topPercent) / heightPercent) * out.height
        val rect = RectF(
            left.coerceIn(0f, out.width.toFloat()),
            top.coerceIn(0f, out.height.toFloat()),
            right.coerceIn(0f, out.width.toFloat()),
            bottom.coerceIn(0f, out.height.toFloat())
        )
        return rect.takeIf { it.width() > 1f && it.height() > 1f }
    }

    /** 读缩略图；[targetWidth] > 0 时按 2 的幂降采样（列表小图用，避免整张解码）。 */
    fun decode(file: File, targetWidth: Int = 0): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        if (targetWidth > 0) {
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
            .onFailure { Log.w(TAG, "snapshot_decode_fail file=${file.name}") }
            .getOrNull()
    }

    /** 把当前展示的图（原图或标记版）保存到相册，返回是否成功。 */
    fun saveToGallery(context: Context, bitmap: Bitmap, displayName: String): Boolean {
        if (bitmap.isRecycled) return false
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/MangaTranslator"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: return false
            val stream = resolver.openOutputStream(uri)
            if (stream == null) {
                resolver.delete(uri, null, null)
                return false
            }
            stream.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "snapshot_saved_to_gallery name=$displayName")
            true
        }.onFailure {
            Log.w(TAG, "snapshot_save_gallery_fail reason=${it.message?.take(80)}")
        }.getOrDefault(false)
    }
}
