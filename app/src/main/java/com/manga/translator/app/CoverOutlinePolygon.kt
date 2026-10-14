package com.manga.translator.app

/**
 * 纯色盖板的「容器轮廓」多边形（2026-10-14 自 `WebtoonImageTranslator.interiorOutlinePolygonPercent`
 * 抽出并加固，纯逻辑、可 JVM 单测）。
 *
 * ## 它解决什么
 *
 * 纯色盖板是一块**矩形**填充，而气泡轮廓不是（椭圆/圆角/波浪边）。用容器逐行边界裁一下，
 * 收腰处就不会在气泡外露出白肩（2026-10-13）。但第一版只有「弦宽过滤」一道防线：
 * 行内笔画/抗锯齿把走向卡住时量出的窄行照样进多边形，裁切于是切进字形区 ——
 * 用户真机截图（2026-10-14）里同时出现**锯齿边**（逐行台阶 + 噪声跳变）与
 * **盖不住原文**（缺口把原文/背景透出来）。
 *
 * ## 两道加固
 *
 * 1. **墨迹守卫（硬保证）**：落在某条文字行带内的行，不许窄于那条行的字形框 ——
 *    字形框里的墨迹本来就属于这块底，轮廓比它窄只可能是测量误差（走向被笔画卡住）；
 *    量到窄了就往回扩，缺口/露原文从根上消除。（多行文字时按**行带**分别守，
 *    不是拿整个并集框守：各行的宽度差很大，用并集会把窄行带一起撑宽。）
 * 2. **整体外扩**：整条轮廓再向外扩几像素（吃掉抗锯齿与亚像素误差）。
 *
 * 刻意**不做**「邻行抹平」：把窄行的边界向更宽的邻行看齐，会在行宽差别大的气泡上
 * 把短行两侧重新撑出白肩（§37 修过的老毛病）。噪声跳变只要不切进字形带，露出的
 * 是气泡自己的底（同色，看不见），不需要为它牺牲轮廓贴合度。
 *
 * 任何一道防线失效（行退化 / 可用行不足 / 弦宽过小）仍然**整体放弃**返回 null ——
 * 调用方落回矩形/四边形轮廓，与「没有这项功能」完全一致：宁可不裁，也不能裁出缺口。
 */
object CoverOutlinePolygon {

    /** 轮廓质量诊断（由调用方聚合成每张图一条 `cover_outline` 日志）。 */
    data class Diag(
        /** 原始轮廓行数（三元组个数）。 */
        val rowsTotal: Int,
        /** 因弦宽过小被丢弃的行数。 */
        val rowsDropped: Int,
        /** 墨迹守卫回扩过的行数。 */
        val rowsInkClamped: Int,
        /** 墨迹守卫里最大的回扩量（px，单侧）；0 = 轮廓本来就盖住字形带。 */
        val maxInkDeficitPx: Float,
        /** 实际使用的外扩量（px）。 */
        val dilatePx: Float,
        /** 最大弦宽（px）：轮廓的整体尺度，配合行数看台阶密度。 */
        val maxChordPx: Float
    )

    data class Result(
        /** 多边形顶点（扁平 x/y 交替，原图百分比）：左侧自上而下、右侧自下而上。 */
        val pointsPercent: List<Float>,
        val diag: Diag
    )

    /**
     * @param profileRowsPx `[y, left, right] × N`（拼接图像素，自上而下）；来自
     *   `BubbleInteriorScanner.Interior.profileRows`。
     * @param coverTopPx / [coverBottomPx] 盖板框（含外扩）的上下沿（拼接图像素）：轮廓两端延到这里，
     *   否则盖板顶/底会留一条没被裁到的直边。
     * @param inkBands 文字行带（拼接图像素，每条 `[left, top, right, bottom]`）：墨迹守卫的硬边界；
     *   空表示「不设守卫」（回退到只做外扩）。
     * @param dilatePx 整条轮廓的外扩量（px）。
     * @param minChordRatio 弦宽下限（占最大弦比例）：低于它的行按文字噪声丢弃（既有判据）。
     * @param rowMinChordPx 弦宽的**绝对**下限（px）：比它还窄的行一律是噪声。
     * @return null = 轮廓不可用/太不可信，调用方落回矩形轮廓。
     */
    fun build(
        profileRowsPx: FloatArray,
        coverTopPx: Float,
        coverBottomPx: Float,
        inkBands: List<FloatArray>,
        dilatePx: Float,
        stitchedWidthPx: Int,
        srcHeightPx: Int,
        topStripHeightPx: Int,
        minChordRatio: Float = DEFAULT_MIN_CHORD_RATIO,
        rowMinChordPx: Float = DEFAULT_MIN_CHORD_PX
    ): Result? {
        if (profileRowsPx.size < 6 || stitchedWidthPx <= 0 || srcHeightPx <= 0) return null
        val rows = ArrayList<FloatArray>(profileRowsPx.size / 3 + 2)
        var index = 0
        while (index + 2 < profileRowsPx.size) {
            val y = profileRowsPx[index]
            val left = profileRowsPx[index + 1]
            val right = profileRowsPx[index + 2]
            if (!left.isFinite() || !right.isFinite() || right - left < 4f) return null
            rows += floatArrayOf(y, left, right)
            index += 3
        }
        if (rows.size < 2) return null

        // ---- 弦宽过滤（既有判据）：穿过密集字形时量到的「行内空隙」按噪声丢弃 ----
        val maxChord = rows.maxOf { it[2] - it[1] }
        val chordFloor = maxOf(maxChord * minChordRatio, rowMinChordPx)
        val usable = rows.filter { (it[2] - it[1]) >= chordFloor }.toMutableList()
        val dropped = rows.size - usable.size
        if (usable.size < 3) return null

        // ---- 两端延到盖板上下沿（既有行为）----
        if (usable.first()[0] > coverTopPx + 0.5f) {
            usable.add(0, floatArrayOf(coverTopPx, usable.first()[1], usable.first()[2]))
        }
        if (usable.last()[0] < coverBottomPx - 0.5f) {
            usable += floatArrayOf(coverBottomPx, usable.last()[1], usable.last()[2])
        }

        // ---- 加固 1：墨迹守卫（行带内不许窄于该行带的字形框）----
        var clampedRows = 0
        var maxDeficit = 0f
        if (inkBands.isNotEmpty()) {
            usable.forEach { row ->
                val y = row[0]
                // 与该行 y 相交的行带（取并集）：多行带重叠时不漏守。
                var bandLeft = Float.MAX_VALUE
                var bandRight = -Float.MAX_VALUE
                var hit = false
                inkBands.forEach { band ->
                    if (band.size < 4) return@forEach
                    if (y < band[1] || y > band[3]) return@forEach
                    hit = true
                    if (band[0] < bandLeft) bandLeft = band[0]
                    if (band[2] > bandRight) bandRight = band[2]
                }
                if (!hit) return@forEach
                // 行比文字行带窄的那一侧需要回扩：needLeft/needRight > 0 才需要动。
                val needLeft = row[1] - bandLeft
                val needRight = bandRight - row[2]
                val deficit = maxOf(needLeft, needRight, 0f)
                if (deficit <= 0f) return@forEach
                clampedRows += 1
                if (deficit > maxDeficit) maxDeficit = deficit
                if (bandLeft < row[1]) row[1] = bandLeft
                if (bandRight > row[2]) row[2] = bandRight
            }
        }

        // ---- 加固 2：整体外扩（吃掉抗锯齿/亚像素误差）----
        val dilate = dilatePx.coerceAtLeast(0f)
        if (dilate > 0f) {
            usable.forEach { row ->
                row[1] -= dilate
                row[2] += dilate
            }
        }

        // ---- 转百分比输出（与 region_polygon 同口径）----
        val xPercent: (Float) -> Float = { x -> (x / stitchedWidthPx.toFloat() * 100f).coerceIn(-50f, 150f) }
        val yPercent: (Float) -> Float = { y ->
            ((y - topStripHeightPx) / srcHeightPx.toFloat() * 100f).coerceIn(-50f, 150f)
        }
        val points = ArrayList<Float>(usable.size * 4)
        usable.forEach { row ->
            points += xPercent(row[1])
            points += yPercent(row[0])
        }
        for (i in usable.indices.reversed()) {
            points += xPercent(usable[i][2])
            points += yPercent(usable[i][0])
        }
        return Result(
            pointsPercent = points,
            diag = Diag(
                rowsTotal = rows.size,
                rowsDropped = dropped,
                rowsInkClamped = clampedRows,
                maxInkDeficitPx = maxDeficit,
                dilatePx = dilate,
                maxChordPx = maxChord
            )
        )
    }

    const val DEFAULT_MIN_CHORD_RATIO = 0.45f

    /** 弦宽绝对下限（px）：比它还窄的行不可能是容器（连一个小字都放不下）。 */
    const val DEFAULT_MIN_CHORD_PX = 6f
}
