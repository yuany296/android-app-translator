package com.manga.translator.app

/**
 * 晚邻接竞态的**接缝带补建**（2026-10-14，Todolist §40 未做项 A/B 的落地）。
 *
 * ## 现场（用户 2026-10-14 截图 + §40 日志链条）
 *
 * 一张跨缝气泡被画了两遍：
 * 1. **覆盖层 1**：上一张图（A）处理时下一张图（B）还没出现 → A 把跨缝气泡当「自己的泡」
 *    整块画掉（`cross=0`），B 的根把 A 的下半截裁在缝上（标题说的「截断」）；
 * 2. **覆盖层 2**：B 拿到 A 的底条带后，把整段认领/整段重画（`orphan_adopted`），
 *    但框被裁到 B 的可见区（从缝口起排）→ 译文向下溢出气泡（「起点在第二页开头」）。
 *
 * 正常路径（A 处理时 B 已在）会由 A 建一条**接缝带**：一个覆盖层跨两张图，
 * 并摘掉 A/B 各自的半块。竞态时这条带没人建 —— 本对象负责**在 B 侧把它补出来**：
 * 拿 A 场景里贴底的泡 + B 场景里贴顶的泡做**文本+几何**匹配，给出带几何与两个待摘 id。
 *
 * ## 判据
 *
 * - 几何：A 的泡贴 A 的底边（`y+h ≥ 100−EDGE`）、B 的泡贴 B 的顶边（`y ≤ EDGE`）；
 * - 文本：[HandoffTextMatch] 的字符多重集覆盖率 ≥ 0.7（OCR 变体容忍），且归一化长度 ≥ [MIN_TEXT_CHARS]；
 * - 两图显示宽度必须相同（接缝带的前提，由渲染层复核），像素换算按 `prevW/curW` 比例。
 *
 * 纯逻辑、无 Android 依赖，可 JVM 单测。
 */
internal object SeamRaceBand {

    /** 场景里一个气泡的几何 + 原文（只取匹配需要的字段）。 */
    data class Candidate(
        val id: String,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val originalText: String,
        /** 盖板框（百分比）；缺省时用排版框。 */
        val coverBox: FloatArray? = null
    )

    /**
     * 匹配结果：接缝带几何（**上一张图的像素坐标**，topH=0）+ 两个待摘 id +
     * 带内百分比的排版/盖板框与字号。
     */
    data class Match(
        val previousId: String,
        val currentId: String,
        val bandLeftPx: Int,
        val bandTopPx: Int,
        val bandWidthPx: Int,
        val bandHeightPx: Int,
        /** 带内百分比的译文排版框 `[x,y,w,h]`（两泡并集）。 */
        val layoutBoxPercent: FloatArray,
        /** 带内百分比的盖板框 `[x,y,w,h]`（两盖板并集）。 */
        val coverBoxPercent: FloatArray,
        /** 文本匹配分（0..1），供日志核对。 */
        val textScore: Float
    )

    /** 贴边判据（百分比）：A 泡泡底距 A 底边 / B 泡泡顶距 B 顶边。 */
    const val EDGE_PERCENT = 8f

    /** 带外扩（像素）：盖板/译文要完整包住两半，别贴边切字。 */
    const val PAD_PX = 4f

    /** 文本匹配的最小长度（归一化后字符数）：太短的泡不参与，避免误配。 */
    const val MIN_TEXT_CHARS = 4

    fun findMatch(
        previous: List<Candidate>,
        current: List<Candidate>,
        previousWidthPx: Int,
        previousHeightPx: Int,
        currentWidthPx: Int,
        currentHeightPx: Int,
        /**
         * 当前图里**其它**气泡（不算被匹配的那个）：接缝带的盖板不许压到它们的文字上
         * （真机夹具实测：认领框比真实气泡高一点，带盖板会盖住下一个气泡的第一行）。
         */
        blockers: List<Candidate> = emptyList()
    ): Match? {
        if (previous.isEmpty() || current.isEmpty()) return null
        if (previousWidthPx <= 0 || previousHeightPx <= 0 || currentWidthPx <= 0 || currentHeightPx <= 0) {
            return null
        }
        var best: Match? = null
        var bestScore = 0f
        previous.forEach { prev ->
            if (prev.y + prev.h < 100f - EDGE_PERCENT) return@forEach
            current.forEach { cur ->
                if (cur.y > EDGE_PERCENT) return@forEach
                val score = matchScore(cur.originalText, prev.originalText)
                if (score < HandoffTextMatch.SAME_SENTENCE_RATIO) return@forEach
                if (score <= bestScore) return@forEach
                val match = buildMatch(
                    prev, cur, previousWidthPx, previousHeightPx, currentWidthPx, currentHeightPx, score,
                    blockers.filter { it.id != cur.id }
                ) ?: return@forEach
                best = match
                bestScore = score
            }
        }
        return best
    }

    private fun matchScore(currentText: String, previousText: String): Float {
        val normalized = HandoffTextMatch.normalize(previousText)
        if (normalized.length < MIN_TEXT_CHARS) return 0f
        if (HandoffTextMatch.normalize(currentText).length < MIN_TEXT_CHARS) return 0f
        // 双向取大：正常竞态里 B 的整段包含 A 的残缺半句（overlap(prev, cur)≈1），
        // 反向（B 只读到残片）也要覆盖。
        return maxOf(
            HandoffTextMatch.overlap(previousText, currentText),
            HandoffTextMatch.overlap(currentText, previousText)
        )
    }

    private fun buildMatch(
        prev: Candidate,
        cur: Candidate,
        previousWidthPx: Int,
        previousHeightPx: Int,
        currentWidthPx: Int,
        currentHeightPx: Int,
        score: Float,
        blockers: List<Candidate>
    ): Match? {
        // 像素换算：两图显示宽度相同 → 当前图 1px = 上一张图 `prevW/curW` px。
        val factor = previousWidthPx.toFloat() / currentWidthPx.toFloat()
        val prevLeft = prev.x / 100f * previousWidthPx
        val prevRight = (prev.x + prev.w) / 100f * previousWidthPx
        val prevTop = prev.y / 100f * previousHeightPx
        val prevBottom = (prev.y + prev.h) / 100f * previousHeightPx
        val curLeft = cur.x / 100f * currentWidthPx * factor
        val curRight = (cur.x + cur.w) / 100f * currentWidthPx * factor
        val curTop = previousHeightPx + cur.y / 100f * currentHeightPx * factor
        val curBottom = previousHeightPx + (cur.y + cur.h) / 100f * currentHeightPx * factor

        val unionLeft = minOf(prevLeft, curLeft)
        val unionRight = maxOf(prevRight, curRight)
        val unionTop = minOf(prevTop, curTop)
        val unionBottom = maxOf(prevBottom, curBottom)
        if (unionRight - unionLeft < 8f || unionBottom - unionTop < 8f) return null

        val bandLeft = (unionLeft - PAD_PX).coerceIn(0f, previousWidthPx.toFloat() - 1f)
        val bandTop = (unionTop - PAD_PX).coerceIn(0f, previousHeightPx.toFloat() - 1f)
        val bandRight = (unionRight + PAD_PX).coerceAtMost(previousWidthPx.toFloat())
        val bandBottom = unionBottom + PAD_PX
        val bandWidth = bandRight - bandLeft
        val bandHeight = bandBottom - bandTop
        if (bandWidth < 8f || bandHeight < 8f) return null

        fun toBandBox(left: Float, top: Float, right: Float, bottom: Float): FloatArray =
            floatArrayOf(
                (left - bandLeft) / bandWidth * 100f,
                (top - bandTop) / bandHeight * 100f,
                (right - left) / bandWidth * 100f,
                (bottom - top) / bandHeight * 100f
            )

        val layoutBox = toBandBox(unionLeft, unionTop, unionRight, unionBottom)

        val prevCover = prev.coverBox?.takeIf { it.size == 4 }
        val curCover = cur.coverBox?.takeIf { it.size == 4 }
        val coverLeft: Float
        val coverTop: Float
        val coverRight: Float
        val coverBottom: Float
        if (prevCover != null && curCover != null) {
            val pl = prevCover[0] / 100f * previousWidthPx
            val pt = prevCover[1] / 100f * previousHeightPx
            val pr = (prevCover[0] + prevCover[2]) / 100f * previousWidthPx
            val pb = (prevCover[1] + prevCover[3]) / 100f * previousHeightPx
            val cl = curCover[0] / 100f * currentWidthPx * factor
            val ct = previousHeightPx + curCover[1] / 100f * currentHeightPx * factor
            val cr = (curCover[0] + curCover[2]) / 100f * currentWidthPx * factor
            val cb = previousHeightPx + (curCover[1] + curCover[3]) / 100f * currentHeightPx * factor
            coverLeft = minOf(pl, cl)
            coverTop = minOf(pt, ct)
            coverRight = maxOf(pr, cr)
            coverBottom = maxOf(pb, cb)
        } else {
            coverLeft = unionLeft
            coverTop = unionTop
            coverRight = unionRight
            coverBottom = unionBottom
        }
        // 避让当前图里的其它气泡：认领框常常比真实气泡高一点，带盖板一旦压到
        // 下一个气泡的文字上，就会把它盖掉一行（真机夹具实测）。
        var clampedTop = coverTop
        var clampedBottom = coverBottom
        blockers.forEach { blocker ->
            val top = previousHeightPx + blocker.y / 100f * currentHeightPx * factor
            val bottom = previousHeightPx + (blocker.y + blocker.h) / 100f * currentHeightPx * factor
            val left = blocker.x / 100f * currentWidthPx * factor
            val right = (blocker.x + blocker.w) / 100f * currentWidthPx * factor
            if (right <= coverLeft + 1f || left >= coverRight - 1f) return@forEach
            if (top in clampedTop..clampedBottom && bottom > clampedBottom) {
                clampedBottom = minOf(clampedBottom, top - 1f)
            }
            if (bottom in clampedTop..clampedBottom && top < clampedTop) {
                clampedTop = maxOf(clampedTop, bottom + 1f)
            }
        }
        val coverBox = if (clampedBottom - clampedTop < 4f || coverRight - coverLeft < 4f) {
            // 收完边没地方了：退回排版框（不裁总比盖错好，日志里还能看到）。
            layoutBox
        } else {
            toBandBox(coverLeft, clampedTop, coverRight, clampedBottom)
        }

        // 字号：由调用方用**当前场景气泡自己的** `fontHeightPercent`（相对 B 图）换算到带内 ——
        // 见会话侧（这里拿不到颜色/字号等场景字段，保持本对象只做几何与文本）。
        return Match(
            previousId = prev.id,
            currentId = cur.id,
            bandLeftPx = bandLeft.toInt(),
            bandTopPx = bandTop.toInt(),
            bandWidthPx = bandWidth.toInt().coerceAtLeast(8),
            bandHeightPx = bandHeight.toInt().coerceAtLeast(8),
            layoutBoxPercent = layoutBox,
            coverBoxPercent = coverBox,
            textScore = score
        )
    }
}
