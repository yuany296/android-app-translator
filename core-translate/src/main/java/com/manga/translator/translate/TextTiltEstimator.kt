package com.manga.translator.translate

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 没有文字四边形时的**倾角兜底**（2026-09-29 用户要求）。
 *
 * 背景：倾斜遮罩依赖 OCR 给出的文字四边形。但 **ML Kit Text Recognition 只返回轴对齐矩形**，
 * `TextBlock.polygon` 恒为 null，斜排原文的遮罩只能退化成水平矩形 —— 斜着的字擦不干净、
 * 译文也不跟着倾斜（PaddleOCR 有四边形所以正常）。
 *
 * 做法：取内容框内的墨迹点云，求**凸包的最小面积外接矩形**（旋转卡壳），长边方向即文字方向。
 *
 * 为什么不用「协方差主轴（PCA）」：多行文字的点云里，行与行之间的堆叠方向会参与方差，
 * 把主轴往竖直方向拽。实测 3 行 20° 的文字块，PCA 给出 25.7°，误差近 6°；
 * 最小面积外接矩形贴着文字块的长边，同样数据给出 20°。这也是 OpenCV `minAreaRect`
 * 处理文字方向的经典做法。
 *
 * 三道闸（任一不过就返回 null，交给上游走轴对齐降级）：
 * 1. 墨迹像素数 >= [MIN_INK_PIXELS]（太少说明是噪点，方向不可信）；
 * 2. 外接矩形长边/短边 >= [MIN_ANISOTROPY]（接近方形说明这团墨迹没有明确方向）；
 * 3. 倾角落在 `[MIN_ABS_DEG, MAX_ABS_DEG]`（太小不值得转；太大说明主轴接近竖直，
 *    那是竖排/单字，不该按横排倾角处理）。
 *
 * 纯 Kotlin（只借用 android.graphics.Bitmap 取像素），不依赖 OpenCV，可直接 JVM 单测
 * （见 `TextTiltEstimatorTest`）。
 */
object TextTiltEstimator {

    /** 墨迹像素太少时估计不可靠。 */
    const val MIN_INK_PIXELS = 60

    /** 外接矩形长边 / 短边下限。 */
    const val MIN_ANISOTROPY = 2.0f

    /** 小于这个角度不值得转 —— 避免把正常横排正文抖成斜的。 */
    const val MIN_ABS_DEG = 3.0f

    /** 大于这个角度说明主轴接近竖直（竖排或单个字），不作为横排倾角使用。 */
    const val MAX_ABS_DEG = 45.0f

    /** 参与几何计算的墨迹像素上限（大框降采样，避免拖慢每气泡的取色/擦字阶段）。 */
    private const val MAX_SAMPLES = 20000

    /**
     * 从灰度数组估计倾角（度，**右倾为正**，即文字从左下往右上时返回正值）。
     *
     * @param luma 行优先，长度至少 `width * height`，取值 0..255。
     * @return 倾角；不可信/无需倾斜时返回 null。
     */
    fun estimateFromLuma(luma: IntArray, width: Int, height: Int): Float? =
        estimateRectFromLuma(luma, width, height)?.angleDeg

    /** 文字方向估计：倾角 + **最小面积外接矩形的 4 个顶点**（同一坐标系）。 */
    class TiltEstimate(val angleDeg: Float, val corners: List<QuadPoint>)

    /**
     * 同 [estimateFromLuma]，但同时给出**最小面积外接矩形的四个顶点**。
     *
     * 为什么必须给顶点而不是只给角度（2026-09-29 用户反馈「倾斜文本角度和宽度都不对」）：
     * 只拿角度去旋转 OCR 的**轴对齐框**，得到的四边形是那个 AABB 的旋转结果；
     * 而斜排文字的 AABB 本来就比字形宽得多（AABB 宽度 = 字宽·cosθ + 字高·sinθ），
     * 转出来自然又宽又偏。最小面积外接矩形贴着的才是字形本身。
     */
    fun estimateRectFromLuma(luma: IntArray, width: Int, height: Int): TiltEstimate? {
        if (width < 4 || height < 4 || luma.size < width * height) return null
        val total = width * height
        val threshold = otsu(luma, width, height)

        var darkCount = 0
        for (i in 0 until total) {
            // OTSU 的类别约定是「暗类 = [0, t]」，所以这里必须用 <=。
            // 用 < 时，二值图（只有 0/255）会被判成 t=0 且一个暗像素都数不到 → 直接放弃估计。
            if (luma[i] <= threshold) darkCount++
        }
        if (darkCount == 0 || darkCount == total) return null
        // 「少数派」当墨迹：文字通常比背景面积小；白字黑底时同理成立。
        val inkIsDark = darkCount * 2 <= total

        val step = (total / MAX_SAMPLES).coerceAtLeast(1)
        val maxPoints = (total + step - 1) / step
        val pointX = DoubleArray(maxPoints)
        val pointY = DoubleArray(maxPoints)
        var n = 0
        var index = 0
        while (index < total) {
            // 暗类 = [0, t]，亮类 = (t, 255]，与上面 darkCount 的口径保持一致。
            val isInk = if (inkIsDark) luma[index] <= threshold else luma[index] > threshold
            if (isInk) {
                pointX[n] = (index % width).toDouble()
                pointY[n] = (index / width).toDouble()
                n++
            }
            index += step
        }
        if (n < MIN_INK_PIXELS) return null

        val hull = convexHull(pointX, pointY, n)
        if (hull.size < 3) return null
        val rect = minAreaRect(hull, pointX, pointY) ?: return null
        if (rect.shortSide <= 1e-6) return null
        if (rect.longSide / rect.shortSide < MIN_ANISOTROPY) return null

        var angle = rect.angleDeg
        // 矩形具有 180° 对称性：归一到 (-90, 90]。竖直方向会保留成 ±90，
        // 于是被下面的 MAX_ABS_DEG 闸挡掉（竖排不该按横排倾角纠正）。
        while (angle > 90.0) angle -= 180.0
        while (angle <= -90.0) angle += 180.0
        val magnitude = abs(angle)
        if (magnitude < MIN_ABS_DEG || magnitude > MAX_ABS_DEG) return null
        return TiltEstimate(angle.toFloat(), rect.corners())
    }

    /**
     * 从位图的 `[left, top, right, bottom)` 区域估计倾角（像素坐标，已按原位图裁剪到合法范围）。
     */
    fun estimateFromBitmap(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Float? = estimateRectFromBitmap(bitmap, left, top, right, bottom)?.angleDeg

    /**
     * 从位图的 `[left, top, right, bottom)` 区域估计「倾角 + 最小面积外接矩形顶点」。
     * 返回的顶点坐标是**相对该裁剪区域左上角**的（调用方加回 left/top 即可）。
     */
    fun estimateRectFromBitmap(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): TiltEstimate? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val l = left.coerceIn(0, bitmap.width - 1)
        val t = top.coerceIn(0, bitmap.height - 1)
        val r = right.coerceIn(l + 1, bitmap.width)
        val b = bottom.coerceIn(t + 1, bitmap.height)
        val w = r - l
        val h = b - t
        if (w < 8 || h < 8) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, l, t, w, h)
        val luma = IntArray(w * h)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            luma[i] = (red * 299 + green * 587 + blue * 114) / 1000
        }
        return estimateRectFromLuma(luma, w, h)
    }

    /**
     * 把轴对齐的内容框按 [angleDeg] 绕中心旋转，返回 4 个顶点（TL/TR/BR/BL）。
     *
     * 调用方传**像素**坐标，拿到**同一坐标系下的像素**顶点 —— 角度必须用像素空间表达，
     * 因为渲染层的百分比空间是各向异性的（x/y 缩放不同，同样的角度在两个空间里不相等）。
     */
    fun rotateBoxCorners(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        angleDeg: Float
    ): List<QuadPoint> {
        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = cos(rad)
        val sinA = sin(rad)
        val centerX = (left + right) / 2.0
        val centerY = (top + bottom) / 2.0
        val corners = listOf(
            left.toDouble() to top.toDouble(),
            right.toDouble() to top.toDouble(),
            right.toDouble() to bottom.toDouble(),
            left.toDouble() to bottom.toDouble()
        )
        return corners.map { (x, y) ->
            val dx = x - centerX
            val dy = y - centerY
            QuadPoint(
                x = (centerX + dx * cosA - dy * sinA).toFloat(),
                y = (centerY + dx * sinA + dy * cosA).toFloat()
            )
        }
    }

    // ------------------------------------------------------------------
    // 凸包 + 最小面积外接矩形（旋转卡壳）
    // ------------------------------------------------------------------

    /** Andrew monotone chain；返回凸包点在 [pointX]/[pointY] 里的下标，逆时针。 */
    private fun convexHull(pointX: DoubleArray, pointY: DoubleArray, count: Int): List<Int> {
        if (count < 3) return emptyList()
        val order = (0 until count).sortedWith(compareBy({ pointX[it] }, { pointY[it] }))
        val hull = ArrayList<Int>(count * 2)

        fun cross(o: Int, a: Int, b: Int): Double =
            (pointX[a] - pointX[o]) * (pointY[b] - pointY[o]) -
                (pointY[a] - pointY[o]) * (pointX[b] - pointX[o])

        for (i in order) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], i) <= 0.0) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(i)
        }
        val lowerSize = hull.size + 1
        for (i in order.asReversed()) {
            while (hull.size >= lowerSize &&
                cross(hull[hull.size - 2], hull[hull.size - 1], i) <= 0.0
            ) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(i)
        }
        if (hull.isNotEmpty()) hull.removeAt(hull.size - 1)
        return hull
    }

    private class OrientedRect(
        val angleDeg: Double,
        val longSide: Double,
        val shortSide: Double,
        val axisX: Double,
        val axisY: Double,
        val minAlong: Double,
        val maxAlong: Double,
        val minAcross: Double,
        val maxAcross: Double
    ) {
        /** 最小面积外接矩形的 4 个顶点（同一坐标系，按环序给出）。 */
        fun corners(): List<QuadPoint> {
            // across 方向的单位向量 = (-axisY, axisX)
            fun point(along: Double, across: Double) = QuadPoint(
                x = (axisX * along - axisY * across).toFloat(),
                y = (axisY * along + axisX * across).toFloat()
            )
            return listOf(
                point(minAlong, minAcross),
                point(maxAlong, minAcross),
                point(maxAlong, maxAcross),
                point(minAlong, maxAcross)
            )
        }
    }

    /**
     * 最小面积外接矩形：枚举凸包每条边作为矩形的一条边方向，取面积最小者。
     * 返回时保证 [OrientedRect.longSide] 是长边，[OrientedRect.angleDeg] 是**长边**与水平线夹角。
     */
    private fun minAreaRect(
        hull: List<Int>,
        pointX: DoubleArray,
        pointY: DoubleArray
    ): OrientedRect? {
        val m = hull.size
        if (m < 3) return null
        var bestArea = Double.MAX_VALUE
        var bestAngle = 0.0
        var bestLong = 0.0
        var bestShort = 0.0
        var bestAxisX = 1.0
        var bestAxisY = 0.0
        var bestMinAlong = 0.0
        var bestMaxAlong = 0.0
        var bestMinAcross = 0.0
        var bestMaxAcross = 0.0
        for (i in 0 until m) {
            val j = (i + 1) % m
            val edgeX = pointX[hull[j]] - pointX[hull[i]]
            val edgeY = pointY[hull[j]] - pointY[hull[i]]
            val edgeLength = hypot(edgeX, edgeY)
            if (edgeLength < 1e-6) continue
            val axisX = edgeX / edgeLength
            val axisY = edgeY / edgeLength
            var minAlong = Double.MAX_VALUE
            var maxAlong = -Double.MAX_VALUE
            var minAcross = Double.MAX_VALUE
            var maxAcross = -Double.MAX_VALUE
            for (k in 0 until m) {
                val x = pointX[hull[k]]
                val y = pointY[hull[k]]
                val along = x * axisX + y * axisY
                val across = -x * axisY + y * axisX
                if (along < minAlong) minAlong = along
                if (along > maxAlong) maxAlong = along
                if (across < minAcross) minAcross = across
                if (across > maxAcross) maxAcross = across
            }
            val alongSpan = maxAlong - minAlong
            val acrossSpan = maxAcross - minAcross
            val area = alongSpan * acrossSpan
            if (area >= bestArea) continue
            bestArea = area
            val edgeAngle = Math.toDegrees(atan2(edgeY, edgeX))
            if (alongSpan >= acrossSpan) {
                bestAngle = edgeAngle
                bestLong = alongSpan
                bestShort = acrossSpan
                bestAxisX = axisX
                bestAxisY = axisY
                bestMinAlong = minAlong
                bestMaxAlong = maxAlong
                bestMinAcross = minAcross
                bestMaxAcross = maxAcross
            } else {
                // 长边是「across」方向：把坐标系绕 90° 换过来，保证 (axis) 指向长边。
                bestAngle = edgeAngle + 90.0
                bestLong = acrossSpan
                bestShort = alongSpan
                bestAxisX = -axisY
                bestAxisY = axisX
                bestMinAlong = minAcross
                bestMaxAlong = maxAcross
                bestMinAcross = -maxAlong
                bestMaxAcross = -minAlong
            }
        }
        if (bestArea == Double.MAX_VALUE) return null
        return OrientedRect(
            bestAngle, bestLong, bestShort,
            bestAxisX, bestAxisY,
            bestMinAlong, bestMaxAlong, bestMinAcross, bestMaxAcross
        )
    }

    /** OTSU 阈值：让前景/背景类间方差最大的那个灰度。 */
    private fun otsuThreshold(luma: IntArray, width: Int, height: Int): Int = otsu(luma, width, height)
}

/**
 * 墨迹紧包围盒（2026-09-29 用户反馈「覆盖层太大了，只存在于 ML Kit 进行 OCR 的时候」）。
 *
 * 根因：**ML Kit 返回的文字框是「块级」的、偏松**（含行距/内边距），PaddleOCR 返回的是紧贴字形的
 * 四边形。于是同一个气泡在 ML Kit 下遮罩/底色框会明显大于字形，看起来「超出气泡框」。
 *
 * 做法与 [TextTiltEstimator] 同源：框内灰度做 OTSU，取「少数派」当墨迹，求墨迹的最小/最大 x、y。
 * 得到的是**字形真实占据的范围**，比 OCR 的块框紧得多，也保证擦除时不会漏掉笔画边缘。
 */
object TextInkBounds {

    /** 墨迹像素太少时不值得裁剪（防止把噪点当成字形）。 */
    const val MIN_INK_PIXELS = 40

    /** 包围盒外扩余量：抗锯齿/描边边缘常常比阈值内的像素再多 1~2px。 */
    const val MARGIN_RATIO = 0.05f

    /** 相对原框最多外扩多少（字形偶尔比 OCR 框胖一点，但要防跑飞）。 */
    const val MAX_EXPAND_RATIO = 0.12f

    /**
     * 在 `[left, top, right, bottom)` 内找墨迹紧包围盒。
     *
     * @return `intArrayOf(左, 上, 右, 下)`（已按 [MARGIN_RATIO] 外扩、按 [MAX_EXPAND_RATIO] 限幅，
     *   并夹在原框内/附近）；墨迹不足或输入非法返回 null。
     */
    fun findInLuma(
        luma: IntArray,
        width: Int,
        height: Int
    ): IntArray? {
        if (width < 3 || height < 3 || luma.size < width * height) return null
        val total = width * height
        val threshold = otsu(luma, width, height)
        var darkCount = 0
        for (i in 0 until total) if (luma[i] <= threshold) darkCount++
        if (darkCount == 0 || darkCount == total) return null
        val inkIsDark = darkCount * 2 <= total

        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var ink = 0
        for (y in 0 until height) {
            val rowBase = y * width
            for (x in 0 until width) {
                val value = luma[rowBase + x]
                val isInk = if (inkIsDark) value <= threshold else value > threshold
                if (!isInk) continue
                ink++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (ink < MIN_INK_PIXELS || maxX < minX || maxY < minY) return null

        // 先外扩余量（盖住抗锯齿/描边），再限制相对原框的最大外扩，最后夹回图内。
        val marginX = ((maxX - minX + 1) * MARGIN_RATIO).toInt().coerceAtLeast(1)
        val marginY = ((maxY - minY + 1) * MARGIN_RATIO).toInt().coerceAtLeast(1)
        val maxExpandX = (width * MAX_EXPAND_RATIO).toInt().coerceAtLeast(1)
        val maxExpandY = (height * MAX_EXPAND_RATIO).toInt().coerceAtLeast(1)
        val outLeft = (minX - marginX).coerceAtLeast(-maxExpandX)
        val outTop = (minY - marginY).coerceAtLeast(-maxExpandY)
        val outRight = (maxX + 1 + marginX).coerceAtMost(width + maxExpandX)
        val outBottom = (maxY + 1 + marginY).coerceAtMost(height + maxExpandY)
        if (outRight <= outLeft || outBottom <= outTop) return null
        return intArrayOf(outLeft, outTop, outRight, outBottom)
    }

    /**
     * 从位图取 `[left, top, right, bottom)` 区域并求墨迹紧包围盒；
     * 返回的是**原位图坐标系**下的 `intArrayOf(左, 上, 右, 下)`（允许略微超出传入框）。
     */
    fun findInBitmap(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): IntArray? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val l = left.coerceIn(0, bitmap.width - 1)
        val t = top.coerceIn(0, bitmap.height - 1)
        val r = right.coerceIn(l + 1, bitmap.width)
        val b = bottom.coerceIn(t + 1, bitmap.height)
        val w = r - l
        val h = b - t
        if (w < 3 || h < 3) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, l, t, w, h)
        val luma = IntArray(w * h)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            luma[i] = (red * 299 + green * 587 + blue * 114) / 1000
        }
        val bounds = findInLuma(luma, w, h) ?: return null
        val maxExpandX = (w * MAX_EXPAND_RATIO).toInt().coerceAtLeast(1)
        val maxExpandY = (h * MAX_EXPAND_RATIO).toInt().coerceAtLeast(1)
        return intArrayOf(
            (l + bounds[0]).coerceIn(0, bitmap.width - 1),
            (t + bounds[1]).coerceIn(0, bitmap.height - 1),
            (l + bounds[2]).coerceIn(1, bitmap.width),
            (t + bounds[3]).coerceIn(1, bitmap.height)
        ).also { result ->
            if (result[2] <= result[0] || result[3] <= result[1]) return null
            // 只在「相对原框的外扩」范围内生效，防止把整幅图当成墨迹。
            if (result[0] < l - maxExpandX || result[1] < t - maxExpandY ||
                result[2] > r + maxExpandX || result[3] > b + maxExpandY
            ) {
                return null
            }
        }
    }
}

/** OTSU 阈值：让前景/背景类间方差最大的那个灰度。 */
private fun otsu(luma: IntArray, width: Int, height: Int): Int {
    val total = width * height
    val histogram = IntArray(256)
    for (i in 0 until total) {
        histogram[luma[i].coerceIn(0, 255)]++
    }
    var sum = 0.0
    for (t in 0..255) sum += t.toDouble() * histogram[t]
    var sumBackground = 0.0
    var weightBackground = 0
    var best = 0
    var bestVariance = -1.0
    for (t in 0..255) {
        weightBackground += histogram[t]
        if (weightBackground == 0) continue
        val weightForeground = total - weightBackground
        if (weightForeground == 0) break
        sumBackground += t.toDouble() * histogram[t]
        val meanBackground = sumBackground / weightBackground
        val meanForeground = (sum - sumBackground) / weightForeground
        val delta = meanBackground - meanForeground
        val between = weightBackground.toDouble() * weightForeground * delta * delta
        if (between > bestVariance) {
            bestVariance = between
            best = t
        }
    }
    return best
}
