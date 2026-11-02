package com.manga.translator.app

/**
 * 悬浮球的**状态呈现**（2026-11-01 用户定稿的方案 B）。
 *
 * 为什么不是「换图」：两颗球现在各自有固定图标（漫画分页 / 浏览器窗口），
 * 换图会让用户在最需要确认「这是哪颗球」的时候（翻译中/已翻译）反而认不出来。
 * 所以图标常驻，状态只通过外圈与角标表达 —— 与参考项目 `floating-states.js`
 * 的 `spinner / runningRing / badge` 是同一套分工。
 */
enum class BrowserBallBadge {
    /** 网页文字已翻译：右上角绿底白对勾。 */
    CHECK,

    /** 漫画连续翻译中：右下角红底白方块（点球停止）。 */
    STOP,

    /** 截图译文在屏：右上角蓝底白 ✕（点球恢复原文）。 */
    RESTORE
}

data class BrowserBallPresentation(
    val badge: BrowserBallBadge? = null,
    val spinner: Boolean = false,
    val runningRing: Boolean = false,
    val dimmed: Boolean = false
)

object BrowserFloatingBallPresentation {

    /**
     * 漫画球。
     *
     * @param action 宿主判定的单击语义（START / STOP / RESTORE / null = 没有可翻的页面）
     * @param busy 截图翻译 / 一次性贴回期间：球只转圈，不接受点击语义
     */
    fun comic(action: BrowserPrimaryAction?, busy: Boolean): BrowserBallPresentation = when {
        busy -> BrowserBallPresentation(spinner = true)
        action == BrowserPrimaryAction.STOP -> BrowserBallPresentation(
            badge = BrowserBallBadge.STOP,
            runningRing = true
        )
        action == BrowserPrimaryAction.RESTORE -> BrowserBallPresentation(
            badge = BrowserBallBadge.RESTORE
        )
        action == null -> BrowserBallPresentation(dimmed = true)
        else -> BrowserBallPresentation()
    }

    /**
     * 网页文字球：忙碌优先（只转圈），其次已翻译（绿对勾），否则空闲。
     * 网页文字没有「不可用」态 —— 没有文字的页面由状态行给出原因，球本身不置灰。
     */
    fun webpage(busy: Boolean, applied: Boolean): BrowserBallPresentation = when {
        busy -> BrowserBallPresentation(spinner = true)
        applied -> BrowserBallPresentation(badge = BrowserBallBadge.CHECK)
        else -> BrowserBallPresentation()
    }
}
