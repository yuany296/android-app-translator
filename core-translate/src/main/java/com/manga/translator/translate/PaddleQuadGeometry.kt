package com.manga.translator.translate

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 参考 C:\homework\translator local-ocr-service（crop_geometry.py / crop_recognition.py）
 * 对「倾斜/变形文字」的几何处理：
 *
 * 1. [rotationDeg]：估计检测四边形的文字方向角（polygon_rotation_deg），
 *    横排 ≈ 0，竖排（长边竖直）≈ -90，基线向右下倾斜 ≈ 正角。
 * 2. [isConfidentVerticalCrop]：竖排 crop 判定（is_confident_vertical_crop）。
 * 3. [selectBestOrientation]：多方向识别候选择优（select_best_recognition），
 *    质量明显更优优先；质量接近时按检测方向偏好竖排 ±90°。
 * 4. [isWeakResult]：弱识别判定（分数 < 0.72 或没有目标文字），触发 180° 重试。
 *
 * 纯 Kotlin、无 Android 依赖，可 JVM 单测。
 */
object PaddleQuadGeometry {

    data class Point(val x: Float, val y: Float)

    /** 竖排 crop 的最小宽高比（参考项目 VERTICAL_CROP_MIN_ASPECT_RATIO = 1.4）。 */
    const val VERTICAL_CROP_MIN_ASPECT_RATIO = 1.4f

    /**
     * 判定「这个 crop 是竖排、应该旋转 ±90° 再识别」时要求的最小宽高比。
     *
     * 比 [VERTICAL_CROP_MIN_ASPECT_RATIO] 更严（1.4 → 1.8），因为两者用途不同：
     * - [VERTICAL_CROP_MIN_ASPECT_RATIO] 只用于 [rotationDeg] 里**选哪条边做主轴**，
     *   判错只是倾角估计略有偏差；
     * - 本阈值决定**要不要把整块转 90° 去识别**，判错的代价是识别结果整体错乱
     *   （2026-09-30 用户反馈：横排三行韩文被当成竖排，译文变「捕, 追术教导技将它NPC唯一的那」）。
     *
     * 真竖排一列的高宽比通常 ≥ 2（一列字）；1.8 留出余量，同时挡掉
     * 「多行横排且整体偏高」这种约 1.4~1.8 的形态。
     */
    const val VERTICAL_CROP_DECISION_ASPECT_RATIO = 1.8f

    /** 方向平局判定容差（参考项目 VERTICAL_ORIENTATION_TIE_MARGIN = 0.08）。 */
    const val VERTICAL_ORIENTATION_TIE_MARGIN = 0.08f

    /** 弱识别分数阈值（参考项目 180° 重试条件 0.72）。 */
    const val WEAK_RECOGNITION_SCORE = 0.72f

    /**
     * 采纳一条识别结果的**最低分数**。
     *
     * 低于它的直接丢弃，不再进入分组/翻译。0.55 的取值依据：正常气泡文字普遍 > 0.85，
     * 而 PaddleOCR 在**空白/纹理区域**的误检（用户 2026-09-30 截图里的 `cYa`、`a c`、`c Y`
     * 那种零散拉丁字母）分数集中在 0.3~0.5。
     */
    const val MIN_ACCEPT_SCORE = 0.55f

    /**
     * 目标语言为韩文时，一条结果至少要有几个韩文字符才采纳。
     *
     * 为什么用「至少 1 个」而不是比例：韩漫里的拟声词/外来语可能是纯拉丁（`Bang!`、`OK`），
     * 设比例会误杀它们；但**一个韩文字都没有**却出现在韩文页面上，基本就是噪声 ——
     * 这正是 `cYa` 的形态（3 个拉丁字母，0 个韩文）。
     */
    const val MIN_SCRIPT_CHARS_KO = 1

    /** 识别方向候选：对 deskew crop 施加的旋转角（0 / 90 / -90 / 180）。 */
    data class OrientedResult(val orientation: Int, val text: String, val score: Float)

    /** 四边形健康检查结果。 */
    data class QuadHealth(val healthy: Boolean, val reason: String)

    /**
     * 估计四边形文字方向角，归一化到 [-90, 90)（与参考 polygon_rotation_deg 一致）。
     * 四边形须按 TL/TR/BR/BL 顺序给出。
     */
    fun rotationDeg(points: List<Point>): Float {
        if (points.size != 4) return 0f
        val tl = points[0]
        val tr = points[1]
        val bl = points[3]
        val topLen = dist(tr, tl)
        val sideLen = dist(bl, tl)
        val aspect = max(topLen, sideLen) / max(1f, min(topLen, sideLen))
        val useSide = aspect >= VERTICAL_CROP_MIN_ASPECT_RATIO && sideLen > topLen
        val vector = if (useSide) Point(bl.x - tl.x, bl.y - tl.y) else Point(tr.x - tl.x, tr.y - tl.y)
        var angle = Math.toDegrees(atan2(vector.y.toDouble(), vector.x.toDouble())).toFloat()
        while (angle >= 90f) angle -= 180f
        while (angle < -90f) angle += 180f
        return angle
    }

    /**
     * 竖排 crop 判定：是否该把整块**旋转 ±90° 再识别**。
     *
     * 阈值用 [VERTICAL_CROP_DECISION_ASPECT_RATIO]（1.8，比主轴选择的 1.4 更严）——
     * 理由见该常量的注释：判错的代价是识别结果整体错乱，不是倾角略有偏差。
     */
    fun isConfidentVerticalCrop(width: Int, height: Int): Boolean =
        height >= max(1, width) * VERTICAL_CROP_DECISION_ASPECT_RATIO

    /** 目标语言文字字符数（参考 count_target_script_chars）。 */
    fun countScriptChars(text: String, lang: String): Int {
        val raw = text
        return when (lang.lowercase()) {
            "ko", "kor", "korean", "韩文" -> raw.count { it in '\uac00'..'\ud7af' }
            "ja", "jpn", "japanese", "日文" -> raw.count { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' }
            "en", "eng", "english", "英文" -> raw.count { it.isAsciiAlpha() }
            else -> raw.count { it in '\u4e00'..'\u9fff' }
        }
    }

    /** 识别质量 = 置信度 + 目标文字字符数奖励 + 有效字符数奖励（参考 recognition_quality）。 */
    fun recognitionQuality(text: String, score: Float, lang: String): Float {
        val scriptChars = countScriptChars(text, lang)
        val meaningful = text.count { it.isLetterOrDigit() || it in '\u3040'..'\ud7af' }
        return score + min(scriptChars, 12) * 0.025f + min(meaningful, 20) * 0.003f
    }

    /** 弱识别：分数低于阈值或完全没有目标文字（参考 180° 重试条件）。 */
    fun isWeakResult(text: String, score: Float, lang: String): Boolean =
        score < WEAK_RECOGNITION_SCORE || countScriptChars(text, lang) == 0

    /**
     * 质量闸门：这条识别结果**要不要采纳**。不采纳则整条丢弃（不进入分组、不翻译、不贴覆盖层）。
     *
     * ## 为什么需要它（2026-09-30 用户反馈）
     *
     * 用户截图里出现了 `cYa` 这种零散拉丁字母 —— PaddleOCR 在**空白/纹理区域**的典型误检。
     * 原来只有 [isWeakResult]，而它**仅用于判断「要不要再转 180° 重试」**，
     * 重试完不管好坏都照样返回 → 噪声一路流到分组、翻译、覆盖层。
     *
     * ## 判据（针对「噪声」而不是「短文本」，这点很关键）
     *
     * 1. **分数下限** [MIN_ACCEPT_SCORE]：误检普遍低分。
     * 2. **必须是目标语言文字**：韩文页面上一个韩文字都没有 → 丢弃。
     *    这一条专门杀 `cYa`；而纯数字（页脚 `12`）、纯标点（`…`、`?!`）
     *    本来也翻不出东西，一并挡掉。
     *
     * ⚠️ **刻意不像 [isWeakResult] 那样对日语/英语也要求目标文字**：日漫里大量
     * 拟声词是片假名/拉丁混排，按脚本过滤会误杀；只有韩文这一条是安全且高收益的
     * （韩文漫画正文不会出现「一个韩文字都没有」的句子）。拿不准的语言一律放行 ——
     * **宁可漏过滤，不可误杀**。
     */
    fun isAcceptableResult(
        text: String,
        score: Float,
        lang: String,
        minScore: Float = MIN_ACCEPT_SCORE
    ): Boolean {
        if (text.isBlank()) return false
        if (!score.isFinite() || score < minScore) return false
        // 纯标点/符号（没有任何字母数字）：翻不出东西，丢弃。
        if (text.none { it.isLetterOrDigit() }) return false
        val normalized = lang.lowercase()
        val requiresScript = normalized in setOf("ko", "kor", "korean", "韩文")
        if (requiresScript && countScriptChars(text, lang) < MIN_SCRIPT_CHARS_KO) return false
        return true
    }

    /**
     * 从多个方向候选中选择最优（参考 select_best_recognition）：
     * - 质量明显更优的结果优先；
     * - 质量接近（差距 ≤ 0.08）且双方都有目标文字时，按检测四边形旋转方向偏好 ±90 竖排方向。
     */
    fun selectBestOrientation(rotation: Float, results: List<OrientedResult>, lang: String): OrientedResult? {
        if (results.isEmpty()) return null
        val best = results.maxByOrNull { recognitionQuality(it.text, it.score, lang) } ?: return null
        if (abs(rotation) < 45f) return best
        val preferredOrientation = if (rotation > 0) 90 else -90
        val preferred = results
            .filter { it.orientation == preferredOrientation }
            .maxByOrNull { recognitionQuality(it.text, it.score, lang) }
            ?: return best
        if (preferred === best) return best
        val bestScript = countScriptChars(best.text, lang)
        val preferredScript = countScriptChars(preferred.text, lang)
        val gap = recognitionQuality(best.text, best.score, lang) -
            recognitionQuality(preferred.text, preferred.score, lang)
        if (bestScript > 0 && preferredScript > 0 && gap <= VERTICAL_ORIENTATION_TIE_MARGIN) {
            return preferred
        }
        return best
    }

    /**
     * 检查检测四边形是否健康，避免退化四边形（近零厚度、自相交）进入透视裁剪。
     * 2026-08-27 新增：为倾斜文字调试增加退化框日志。
     */
    fun checkQuadHealth(points: List<Point>): QuadHealth {
        if (points.size != 4) return QuadHealth(false, "not_4_points")
        val p0 = points[0]
        val p1 = points[1]
        val p2 = points[2]
        val p3 = points[3]
        val top = dist(p0, p1)
        val right = dist(p1, p2)
        val bottom = dist(p2, p3)
        val left = dist(p3, p0)
        val minEdge = minOf(top, right, bottom, left)
        val maxEdge = maxOf(top, right, bottom, left)
        if (minEdge <= 2f) return QuadHealth(false, "zero_edge")
        if (maxEdge / minEdge > 25f) return QuadHealth(false, "extreme_aspect")
        // 简单自相交检查：对角线中点应接近（凸四边形）。
        val cx1 = (p0.x + p2.x) / 2f
        val cy1 = (p0.y + p2.y) / 2f
        val cx2 = (p1.x + p3.x) / 2f
        val cy2 = (p1.y + p3.y) / 2f
        if (dist(Point(cx1, cy1), Point(cx2, cy2)) > maxEdge * 0.25f) {
            return QuadHealth(false, "diagonal_mismatch")
        }
        return QuadHealth(true, "ok")
    }

    private fun dist(a: Point, b: Point): Float =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    private fun Char.isAsciiAlpha(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
}
