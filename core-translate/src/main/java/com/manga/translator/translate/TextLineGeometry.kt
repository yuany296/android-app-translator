package com.manga.translator.translate

import kotlin.math.max
import kotlin.math.min

/**
 * **行高**口径（2026-10-03）。
 *
 * ## 为什么要有这个文件
 *
 * 合并判据的唯一单位必须是**行高**（参考项目 `C:\homework\translator` 的
 * `getLocalPaddlePairGeometry`：所有阈值都除以投影后的**行框高度** `avgHeight`）。
 *
 * 本工程这条纪律曾经只在 seam 模块落地过（`SeamTuning.medianLineHeight` = 框高 ÷ 行数，
 * 见 `Todolist.md` §28 第二轮），本地分组的合并判据一直拿**检测块高度**当单位 ——
 * 而 ML Kit 返回的是**段落级多行块**（`text` 内含 `\n`，`@192,83 300x139` 是整个 4 行），
 * 于是间距上限被放大到 `139 × 1.35 ≈ 188px`，两个真正独立的气泡（真实间隙 50px）
 * 就被并成一句、一块盖板（真机 `erase_target` 现场）。
 *
 * 纯几何、无 Android 依赖，可 JVM 单测。
 */
object TextLineGeometry {

    /**
     * **竖排列**的最小高宽比。
     *
     * 沿用工程里已经定稿的值（`PaddleOcrBlockMerger.VERTICAL_BLOCK_MIN_ASPECT` /
     * `BubbleFirstRegionPlanner.VERTICAL_BLOCK_MIN_ASPECT`）：`1.15` 太松 ——
     * 横排三行合成的大框就能满足，而那个误判曾在 2026-09-30 造成整段原文按列错乱。
     */
    const val VERTICAL_COLUMN_MIN_ASPECT = 2.0f

    /**
     * 一个检测块占几行。
     *
     * 行数取三者最大，对齐参考项目 `seam-grouping.js` 的 `candidateLineCount`：
     * 1. 检测器显式给的行数（[TextBlock.lineCount]：ML Kit = `block.lines`，Paddle 合并块 = 行数）；
     * 2. **文本换行数 + 1** —— 真机实测检测器有时把 4 行段落的行数给成 1，只信它就会退回
     *    「整块高度当行高」（`Todolist.md` §28 第二轮同一个坑）；
     * 3. 1。
     */
    fun lineCount(block: TextBlock): Int {
        val explicit = block.lineCount.coerceAtLeast(1)
        val fromText = block.text.split('\n', '\r').count { it.isNotBlank() }
        return max(explicit, max(fromText, 1))
    }

    /**
     * 这个块是不是「**单列竖排**」—— 一列字，文字沿 Y 流动。
     *
     * 两个条件同时成立：
     * 1. 只有一行（[lineCount] == 1）。**文本含 `\n` 时永不判竖排**：换行表示「横排堆叠」，
     *    这是与语言无关的强信号，比宽高比可靠（横排三行的大框高宽比会落在 1.5~2.2，
     *    正是 2026-09-30 那次误判的形状）；
     * 2. 高 ≥ 宽 × [VERTICAL_COLUMN_MIN_ASPECT]。
     */
    fun isVerticalColumn(block: TextBlock): Boolean {
        if (lineCount(block) > 1) return false
        return heightPx(block) >= widthPx(block) * VERTICAL_COLUMN_MIN_ASPECT
    }

    /**
     * 行高（像素）：**一个字形占的高度**，也就是合并阈值唯一的单位。
     *
     * - 横排：`框高 ÷ 行数`（多行块自动摊平）；
     * - 单列竖排（[isVerticalColumn]）：**列宽**（竖排一个字占的横向宽度就是它的「行高」，
     *   参考项目把竖排框旋转到阅读系之后取的就是这个量）。
     *
     * ⚠️ 已知近似（不静默）：**多列**竖排块（Paddle 把并排的列合并成的块）量到的是
     * 并集列宽，比单列字宽偏大。它不会造成跨气泡误合并 —— 邻居块的行高比会落到
     * 字号闸（`OcrBlockMergeRules.FONT_SCALE_MIN_RATIO = 0.65`）之下而被拒绝。
     *
     * 判据只用「形态 + 行数」，不猜语言：文本含 `\n` 的块由 [lineCount] 判为多行，
     * [isVerticalColumn] 因此返回 false（横排堆叠）。
     */
    fun lineHeightPx(block: TextBlock): Float {
        val lineHeight = if (isVerticalColumn(block)) {
            widthPx(block)
        } else {
            heightPx(block) / lineCount(block).toFloat()
        }
        return lineHeight.coerceAtLeast(1f)
    }

    /** 一对块的**平均行高**：合并阈值的分母。 */
    fun averageLineHeight(a: TextBlock, b: TextBlock): Float {
        return (lineHeightPx(a) + lineHeightPx(b)) / 2f
    }

    /** 较小的行高（字号闸用）。 */
    fun minLineHeight(a: TextBlock, b: TextBlock): Float = min(lineHeightPx(a), lineHeightPx(b))

    /** 较大的行高（字号闸用）。 */
    fun maxLineHeight(a: TextBlock, b: TextBlock): Float = max(lineHeightPx(a), lineHeightPx(b))

    fun widthPx(block: TextBlock): Float = (block.right - block.left).coerceAtLeast(1f)

    fun heightPx(block: TextBlock): Float = (block.bottom - block.top).coerceAtLeast(1f)
}
