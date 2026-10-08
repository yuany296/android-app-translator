package com.manga.translator.translate

/**
 * 检测四边形 → 渲染层数据契约（2026-09-29）。
 *
 * 渲染层（DOM 覆盖层 / canvas 合成）需要「文字四边形 + 倾角」才能让遮罩和译文一起
 * 倾斜；OCR 检测器本来就算出了四边形（透视裁剪用），这里把它翻译成
 * [Bubble.polygon] / [Bubble.rotationDeg] 两个字段。
 *
 * 两条硬规则：
 * 1. **只有单框组合才透传**。多个检测框合并出来的段/行一定是轴对齐的多行块
 *    （见 [PaddleOcrBlockMerger]），拿其中一个框的倾角当整块的倾角会让遮罩歪掉。
 * 2. **四边形存百分比、rotationDeg 存的是同一份四边形的角度**。参考项目明确警告过
 *    「不能拿 rotation_deg 与百分比 polygon 的夹角比对是否一致」——像素坐标和百分比
 *    坐标读出的角度本来就不同。这里两者同源，不存在冲突；渲染层只在四边形缺失时
 *    才使用 rotationDeg。
 *
 * 纯 Kotlin、无 Android 依赖，可 JVM 单测。
 */
object TextQuadGeometry {

    /** 四边形最少/最多顶点数（契约就是 4 点任意四边形）。 */
    const val QUAD_POINT_COUNT = 4

    /**
     * 单框组合的四边形（图片像素 TL/TR/BR/BL）→ 原图百分比。
     * 钳制到 [-100, 200]：跨图气泡的四边形可以略微越界，但不能离谱到把遮罩撑爆。
     */
    fun percentPolygon(
        blocks: List<TextBlock>,
        imageWidth: Int,
        imageHeight: Int
    ): List<QuadPoint>? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val quad = blocks.singleOrNull()?.polygon ?: return null
        if (quad.size != QUAD_POINT_COUNT) return null
        val w = imageWidth.toFloat()
        val h = imageHeight.toFloat()
        return quad.map { point ->
            QuadPoint(
                x = (point.x / w * 100f).coerceIn(-100f, 200f),
                y = (point.y / h * 100f).coerceIn(-100f, 200f)
            )
        }
    }

    /** 百分比四边形 → 倾角（度，折到 ±90°）；顶点数不对返回 null。 */
    fun rotationDeg(polygon: List<QuadPoint>?): Float? {
        if (polygon == null || polygon.size != QUAD_POINT_COUNT) return null
        return PaddleQuadGeometry.rotationDeg(
            polygon.map { PaddleQuadGeometry.Point(it.x, it.y) }
        )
    }

    /** 便捷入口：单框组合 → 四边形与倾角（两者同源，要么都有要么都没有）。 */
    fun quadOf(
        blocks: List<TextBlock>,
        imageWidth: Int,
        imageHeight: Int
    ): Pair<List<QuadPoint>?, Float?> {
        val polygon = percentPolygon(blocks, imageWidth, imageHeight)
        return polygon to rotationDeg(polygon)
    }

    /**
     * 拼接图坐标（百分比）→ 原图坐标（百分比）。
     *
     * 覆盖层链路里 OCR 跑在「上图底部条带 + 本图 + 下图顶部条带」拼出来的拼接图上，
     * 而覆盖层挂在**原图**元素上：y 先减去顶部条带的像素高度，再按原图高度折算。
     * 与 [BrowserSceneBuilder.stitchedYPercentToSource] 同一口径，这里只处理四边形。
     */
    fun stitchedPolygonToSource(
        polygon: List<QuadPoint>?,
        stitchedHeightPx: Int,
        topStripHeightPx: Int,
        sourceHeightPx: Int
    ): List<QuadPoint>? {
        if (polygon == null) return null
        if (stitchedHeightPx <= 0 || sourceHeightPx <= 0) return null
        return polygon.map { point ->
            val sourceY = point.y / 100f * stitchedHeightPx - topStripHeightPx
            QuadPoint(
                x = point.x.coerceIn(-100f, 200f),
                y = (sourceY / sourceHeightPx * 100f).coerceIn(-100f, 200f)
            )
        }
    }
}
