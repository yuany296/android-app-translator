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
 * 3. **孤立窄行（尖刺）抑制（2026-10-15）**：真机现场（Kakao 面板型气泡）里有两行
 *    被字形卡住测量、随后被墨迹守卫夹到文字行带宽度 —— 它们比上下邻行窄约 40%，
 *    多边形在这里切出**台阶/缺口**，浅底渐变面板上肉眼可见（用户：「出现了锯齿状的背景」）。
 *    孤立尖刺不是容器真实形状：内部行直接丢弃（邻行连成坡），首/末行改用邻行边界
 *    （保住上下沿覆盖）。端部收敛（椭圆顶/底）是逐行渐变，不满足「比两邻都窄一大截」，
 *    不受影响。
 *
 * 刻意**不做**「把窄行两侧一条条抹平到邻行」的全面平滑：那会在行宽差别大的气泡上
 * 把短行两侧重新撑出白肩（§37 修过的老毛病）；这里只处理**孤立**尖刺。
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
        /** 因「孤立窄行（尖刺）」被丢弃/并入邻行的行数。 */
        val rowsSpikeFixed: Int,
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

        // ---- 加固 3：孤立窄行（尖刺）抑制（2026-10-15）----
        // 现场：面板型气泡里，个别行被字形卡住 → 墨迹守卫把它夹到文字行带宽度，
        // 于是它比上下邻行窄一大截，轮廓在这里切出台阶（用户：「锯齿状的背景」）。
        // 判据（只打**孤立**尖刺，端部收敛与整体收腰不碰）：
        //   弦 < 两邻行弦的较大者 × SPIKE_CHORD_RATIO 且 弦 < 最大弦 × SPIKE_ABS_MAX_RATIO
        // 处理：内部行丢弃（邻行连成坡）；首/末行把边界并成邻行的（保住上下沿覆盖，
        // 丢弃端行会在盖板上下沿留一道没裁到的缝）。跑两遍：端行是「扩展行、复制了
        // 前一行尖刺」时，第一遍丢内部尖刺后，第二遍才看得出端行本身也是尖刺。
        var spikeFixed = 0
        var smoothRows = usable
        repeat(2) {
            val pass = suppressSpikes(smoothRows, maxChord, inkBands)
            spikeFixed += pass.fixed
            smoothRows = pass.rows
        }

        // ---- 加固 2：整体外扩（吃掉抗锯齿/亚像素误差）----
        val dilate = dilatePx.coerceAtLeast(0f)
        if (dilate > 0f) {
            smoothRows.forEach { row ->
                row[1] -= dilate
                row[2] += dilate
            }
        }

        // ---- 转百分比输出（与 region_polygon 同口径）----
        val xPercent: (Float) -> Float = { x -> (x / stitchedWidthPx.toFloat() * 100f).coerceIn(-50f, 150f) }
        val yPercent: (Float) -> Float = { y ->
            ((y - topStripHeightPx) / srcHeightPx.toFloat() * 100f).coerceIn(-50f, 150f)
        }
        val points = ArrayList<Float>(smoothRows.size * 4)
        smoothRows.forEach { row ->
            points += xPercent(row[1])
            points += yPercent(row[0])
        }
        for (i in smoothRows.indices.reversed()) {
            points += xPercent(smoothRows[i][2])
            points += yPercent(smoothRows[i][0])
        }
        return Result(
            pointsPercent = points,
            diag = Diag(
                rowsTotal = rows.size,
                rowsDropped = dropped,
                rowsSpikeFixed = spikeFixed,
                rowsInkClamped = clampedRows,
                maxInkDeficitPx = maxDeficit,
                dilatePx = dilate,
                maxChordPx = maxChord
            )
        )
    }

    /** [suppressSpikes] 的结果：处理后的行 + 修复的行数。 */
    private class SpikePass(val rows: MutableList<FloatArray>, val fixed: Int)

    /**
     * 孤立窄行（尖刺）抑制：内部尖刺行丢弃、首末尖刺行并入邻行边界（保住上下沿覆盖）。
     *
     * 见 [build] 加固 3；`maxChord` 是**原始**行的最大弦，作为绝对尺度基准（不随处理变化）。
     */
    private fun suppressSpikes(
        rows: MutableList<FloatArray>,
        maxChord: Float,
        inkBands: List<FloatArray>
    ): SpikePass {
        if (rows.size < 3) return SpikePass(rows, 0)
        // 「台阶」最小可感知幅度：按最大弦的比例算（气泡越大，像素台阶越明显）。
        val step = maxChord * SPIKE_SIDE_STEP_RATIO
        if (step <= 0f) return SpikePass(rows, 0)
        val chords = FloatArray(rows.size) { rows[it][2] - rows[it][1] }
        val out = ArrayList<FloatArray>(rows.size)
        var fixed = 0
        for (i in rows.indices) {
            val row = rows[i]
            val prev = rows.getOrNull(i - 1)
            val next = rows.getOrNull(i + 1)
            // 单侧局部内凹：这条边的左右两侧邻行都比它更靠外，且幅度 ≥ 台阶。
            // 逐行收敛（椭圆/圆角顶底）不会同时比两侧都靠内，因此不受影响。
            val leftInner = (prev == null || row[1] - prev[1] > step) &&
                (next == null || row[1] - next[1] > step) && (prev != null || next != null)
            val rightInner = (prev == null || prev[2] - row[2] > step) &&
                (next == null || next[2] - row[2] > step) && (prev != null || next != null)
            if (!leftInner && !rightInner) {
                out += row
                continue
            }
            val isEnd = prev == null || next == null
            // 端行还有一个**真实轮廓**的形态：椭圆/圆角顶底的收敛尾（往里两行稳定变窄）。
            // 只有「行贴着文字（行带内）」或「平着突然断崖」才当尖刺；收敛尾保持贴合。
            val glyphNearby = inkBands.any { band ->
                band.size >= 4 && row[0] >= band[1] && row[0] <= band[3]
            }
            if (isEnd && !glyphNearby && !hasFlatInnerContext(chords, i)) {
                out += row
                continue
            }
            fixed += 1
            if (isEnd) {
                // 端行不能丢（丢了盖板上下沿会裂缝）：并入邻行边界。
                val neighborRow = if (i == 0) rows[1] else rows[rows.size - 2]
                row[1] = neighborRow[1]
                row[2] = neighborRow[2]
                out += row
            }
            // 内部行：丢弃（不加入 out），邻行直接连成坡。
        }
        return SpikePass(out, fixed)
    }

    /**
     * 端行往里两行的弦是否「平」（第一段 / 第二段 ≥ [SPIKE_TAIL_FLAT_RATIO]）。
     *
     * 用途：把「端部逐行收敛的盖子」（椭圆底/顶，弦 600→500→380→200，内侧两段都在变小）
     * 与「平坦区域突然出现一行窄行」（真机现场：102→102→62，内侧一段是平的）区分开 ——
     * 前者要保持轮廓贴合，后者才是要并掉的尖刺。
     */
    private fun hasFlatInnerContext(chords: FloatArray, index: Int): Boolean {
        val inward = if (index == 0) 1 else -1
        val first = chords[index + inward]
        val second = chords.getOrNull(index + inward * 2) ?: return false
        if (second <= 0f) return false
        return first / second >= SPIKE_TAIL_FLAT_RATIO
    }

    const val DEFAULT_MIN_CHORD_RATIO = 0.45f

    /** 弦宽绝对下限（px）：比它还窄的行不可能是容器（连一个小字都放不下）。 */
    const val DEFAULT_MIN_CHORD_PX = 6f

    /**
     * 「台阶」最小可感知幅度（× 最大弦）：单侧边界比**两侧**邻行都更靠内、且幅度 ≥
     * 这个值，才算孤立尖刺（见 [build] 加固 3）。
     *
     * 0.15 是拿两个真机现场标的：用户 Kakao 面板泡的缺口为最大弦的 0.36/0.20，普通
     * 行抖在 0.05 以内；逐行收敛（椭圆顶/底）永远不会「同时比两侧都靠内」。
     */
    const val SPIKE_SIDE_STEP_RATIO = 0.15f

    /**
     * 端行「平坦上下文」判据（见 `hasFlatInnerContext`）：往里第一段弦 / 第二段弦 ≥ 它 =
     * 上下文是平的（断崖式窄行 = 尖刺）；低于它 = 端部在收敛（椭圆顶/底，保持轮廓）。
     */
    const val SPIKE_TAIL_FLAT_RATIO = 0.9f
}
