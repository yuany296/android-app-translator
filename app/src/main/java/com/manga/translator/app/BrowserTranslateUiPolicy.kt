package com.manga.translator.app

/**
 * 内嵌浏览器底栏「看什么」的两种视图：原文 / 译文。
 *
 * 为什么要有这个枚举（2026-10-02 用户反馈「原文切换和翻译两个按钮功能不统一/不清晰」）：
 * 改造前底栏并排放着两个同款胶囊按钮 ——「原文」是**一次性动作**（还会顺手停掉翻译会话），
 * 「翻译/停止」是**持续任务开关**。两者长得一样、语义却是两类，用户没法从界面读出
 * 「现在显示的是原文还是译文」「翻译到底在不在跑」。
 *
 * 现在的分工是硬的：
 * - [BrowserViewMode]（视图）只决定「译文层挂不挂在页面上」，不启动也不停止任何任务；
 * - 翻译任务的启停**只**由底栏主按钮负责。
 */
enum class BrowserViewMode {
    ORIGINAL,
    TRANSLATED
}

/** 主按钮这一下会做的事：开始翻译（含把缓存贴回）或停止翻译。 */
enum class BrowserPrimaryAction {
    START,
    STOP
}

/**
 * 底栏两个控件的呈现结果（纯数据，便于单测；Compose 侧只负责画出来）。
 *
 * [primaryLabel] 与 [primaryAction] 必须一致 —— 这是「按钮文案说它会做什么」的落点。
 */
data class BrowserTranslateControls(
    val modeToggleEnabled: Boolean,
    val modeToggleDisabledReason: String?,
    val primaryLabel: String,
    val primaryAction: BrowserPrimaryAction,
    val primaryEnabled: Boolean
)

/**
 * 底栏控件状态策略。
 *
 * 抽成纯函数的原因：这套「什么状态下哪个控件可用/显示什么」的映射是**最容易漂移**的地方，
 * 用单测钉住表格，避免以后再出现「运行时按钮还能点但点了没反应」。
 */
object BrowserTranslateUiPolicy {

    /** 分段开关禁用时的原因文案（也进 contentDescription，供无障碍与 `.verify/ui.ps1` 断言）。 */
    const val HINT_NO_TRANSLATION = "还没有译文"

    /**
     * @param pageLoaded 当前是否是真实网页（新标签页 / about:blank 都算没加载）
     * @param running 连续翻译会话是否存活（常驻轮询，点了「翻译」就一直是 true 直到停止/跳转）
     * @param reShowBusy 是否正在跑「一次性贴回」会话（不翻译、只把已有译文层贴回）
     * @param hasRender 当前页面是否至少成功贴过一张译文
     * @param mode 当前视图
     * @param canReshow 有「摘掉后可重看」的截图译文结果（2026-10-15：以前点一下恢复原文后
     *   hasRender 归 false，开关被禁、点球又只会重开翻译 —— 用户反馈「翻译层点不回来」）
     */
    fun resolve(
        pageLoaded: Boolean,
        running: Boolean,
        reShowBusy: Boolean,
        hasRender: Boolean,
        mode: BrowserViewMode,
        canReshow: Boolean = false
    ): BrowserTranslateControls {
        // 分段开关只在「有东西可切」时可用：要么会话在跑（后续还会产出译文），
        // 要么屏上/缓存里已经有译文层，要么有截图结果可重看。否则点它必然是「没反应」，
        // 这就是改造前的第二个抱怨。
        val toggleEnabled = !reShowBusy && (running || hasRender || canReshow)
        val disabledReason = when {
            reShowBusy -> "正在显示译文…"
            toggleEnabled -> null
            !pageLoaded -> "先打开网页"
            else -> HINT_NO_TRANSLATION
        }
        return BrowserTranslateControls(
            modeToggleEnabled = toggleEnabled,
            modeToggleDisabledReason = disabledReason,
            primaryLabel = if (running) "停止" else "翻译",
            primaryAction = if (running) BrowserPrimaryAction.STOP else BrowserPrimaryAction.START,
            // 运行中永远可点（要能停下来）；空闲时要有页面才可翻。
            // 贴回中禁用：那一瞬间的「翻译」会打断贴回，等它结束（通常 <1s）。
            primaryEnabled = !reShowBusy && (running || pageLoaded)
        )
    }

    /**
     * 状态行为空时的兜底文案（会话在跑时状态行由会话自己驱动，不走这里）。
     *
     * 有意**不**写「点某按钮…」这类教学句，除非确实还没有任何译文 ——
     * 那种情况下用户需要知道下一步做什么。
     *
     * 2026-10-12 第三轮：底栏的「翻译/停止」「原文｜译文」都搬走了（前者成悬浮球、
     * 后者进 ⋮ 菜单），文案随之改成指**球**与**球上菜单**，不再指向已不存在的底栏按钮。
     */
    fun idleHint(mode: BrowserViewMode, hasRender: Boolean, pageLoaded: Boolean): String = when {
        !pageLoaded -> "打开漫画网页后点球翻译"
        mode == BrowserViewMode.ORIGINAL && hasRender -> "原文显示中 · 点球上菜单切译文"
        mode == BrowserViewMode.ORIGINAL -> "原文显示中"
        hasRender -> "译文已盖上 · 点球可停止"
        else -> "点球自动翻完整页"
    }
}
