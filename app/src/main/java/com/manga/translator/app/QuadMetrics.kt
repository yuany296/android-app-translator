package com.manga.translator.app

import com.manga.translator.translate.QuadPoint
import kotlin.math.hypot

/**
 * 四边形几何度量（2026-09-30 从 `WebtoonImageTranslator` 提出来放顶层）。
 *
 * 提出来只有一个理由：**单位口径太容易错，必须能直接单测**。
 * 历史上这里的错误是「百分比被当像素用」，直接把原文单行字高算小 `srcH/100` 倍。
 */

/**
 * 四边形的**厚度**（两条「短边」的平均长度）。给的是像素坐标时返回值就是像素。
 *
 * ⚠️ 不要拿四边形**外接框的高**当字高：斜排文字的包络高 = 行长·sinθ + 字高·cosθ。
 * 实测 19° 的斜排：真实字高 38px，外接框高 125px（3.3 倍）→ 译文字号被算成大 3 倍
 * （用户 2026-09-30 反馈「倾斜文字的高度比起原文太高」）。
 *
 * ⚠️ 也别拿**百分比**四边形喂进来 —— 见 [percentQuadThicknessPx]。
 */
internal fun quadLineThicknessPx(quad: List<QuadPoint>?): Float {
    if (quad == null || quad.size < 4) return 0f
    fun side(a: QuadPoint, b: QuadPoint): Float {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        return hypot(dx, dy).toFloat()
    }
    val first = (side(quad[0], quad[1]) + side(quad[2], quad[3])) / 2f
    val second = (side(quad[1], quad[2]) + side(quad[3], quad[0])) / 2f
    return minOf(first, second)
}

/**
 * **百分比**四边形 → **像素**厚度（短边长度）。
 *
 * 存在的唯一理由就是单位换算：x 按原图宽、y 按原图高换算后，得到的几何与屏幕上的
 * 显示几何只差一个统一缩放，厚度可以直接当像素用（文字框永远是等比显示）。
 *
 * 真实事故（2026-09-30，用户反馈「倾斜的文本看不清」）：
 * 调用方直接 `quadLineThicknessPx(maskQuadSource)`，而 `maskQuadSource` 是原图百分比
 * —— 量到的是「百分比厚度」却被当成像素用。真机实测 `lineH=10.7`，逐像素量出的真值是
 * **37.7px**（3.5 倍差）；`font_height_percent` 由此从 11.09% 掉到 3.13%，
 * 字号被渲染层 `FONT_MIN` 兜在 10px，再叠上 1.8px 白描边 → 看起来像「空心白字、看不清」。
 */
internal fun percentQuadThicknessPx(quad: List<QuadPoint>?, srcW: Int, srcH: Int): Float {
    if (quad == null || quad.size < 4 || srcW <= 0 || srcH <= 0) return 0f
    val pixels = quad.map { QuadPoint(it.x / 100f * srcW, it.y / 100f * srcH) }
    return quadLineThicknessPx(pixels)
}
