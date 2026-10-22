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
 * - 翻译任务的启停**只**由悬浮球负责。
 */
enum class BrowserViewMode {
    ORIGINAL,
    TRANSLATED
}

/**
 * 悬浮球这一下会做的事。
 *
 * [RESTORE] 是 2026-10-16 重设计新增的第三态：截图翻译的覆盖层在屏时，球 = **恢复原文**
 * （对齐首页那条链路 —— `CaptureForegroundService.handleFloatingTap` 里「译文层显示/锁定时
 * 点球 = 恢复原文，不触发新翻译」）。它**不是**"重放旧快照"：改造前还有一种隐藏优先级
 * （球去重发上一次的截图结果），那正是用户报「点球无法翻译」的直接原因，已整个删除。
 */
enum class BrowserPrimaryAction {
    START,
    STOP,
    RESTORE
}

/**
 * 球的呈现结果（纯数据，便于单测；Compose 侧只负责画出来）。
 *
 * [primaryLabel] 与 [primaryAction] 必须一致 —— 这是「按钮文案说它会做什么」的落点。
 * [primaryAction] 为 null = 这一下没有可做的事（例如新标签页还没有网页）。
 */
data class BrowserTranslateControls(
    val modeToggleEnabled: Boolean,
    val modeToggleDisabledReason: String?,
    val primaryLabel: String,
    val primaryAction: BrowserPrimaryAction?,
    val primaryEnabled: Boolean,
    val primaryBusy: Boolean
)

/**
 * 悬浮球 / 「原文｜译文」开关的状态表。
 *
 * 抽成纯函数的原因：这套「什么状态下哪个控件可用/显示什么」的映射是**最容易漂移**的地方，
 * 用单测钉住表格，避免以后再出现「运行时按钮还能点但点了没反应」。
 *
 * 2026-10-16 重设计（用户：「截图翻译和悬浮球翻译模式冲突了，需要重新设计」）：
 * - 删除 `canReshow` 与「点球重看截图译文」的拦截：球不再重放任何旧结果；
 * - 新增 [BrowserShownLayer] 输入：屏上挂着截图覆盖层时球 = 恢复原文（[BrowserPrimaryAction.RESTORE]）；
 * - 新增 [screenshotBusy]：截图翻译进行中球与开关都禁用（以前只是往状态行写一行字，球看起来没反应）。
 */
object BrowserTranslateUiPolicy {

    /** 分段开关禁用时的原因文案（也进 contentDescription，供无障碍与 `.verify` 断言）。 */
    const val HINT_NO_TRANSLATION = "还没有译文"

    /** 截图翻译进行中：球与开关都不可用。 */
    const val HINT_BUSY_SCREENSHOT = "截图翻译进行中…"

    /** 一次性贴回（「译文」段/会话收尾）进行中：同样不可用。 */
    const val HINT_BUSY_RESHOW = "正在显示译文…"

    const val LABEL_START = "翻译"
    const val LABEL_STOP = "停止"
    const val LABEL_RESTORE = "恢复原文"

    /**
     * @param pageLoaded 当前是否是真实网页（新标签页 / about:blank 都算没加载）
     * @param running 连续翻译会话是否存活（常驻轮询，点了「翻译」就一直是 true 直到停止/跳转）
     * @param reShowBusy 是否正在跑「一次性贴回」会话（不翻译、只把已有译文层贴回）
     * @param screenshotBusy 截图翻译是否在跑（截图/管线/回贴期间屏上什么都不要动）
     * @param shown 屏上此刻挂着哪一种译文层（[BrowserShownLayer]，唯一事实来源）
     */
    fun resolve(
        pageLoaded: Boolean,
        running: Boolean,
        reShowBusy: Boolean,
        screenshotBusy: Boolean,
        shown: BrowserShownLayer
    ): BrowserTranslateControls {
        val busy = reShowBusy || screenshotBusy
        // 球的三个语义，判定顺序是硬的：
        // 截图覆盖层在屏 → 恢复原文（首页同款，先收层再说别的）；
        // 会话在跑 → 停止；否则（有网页）→ 开始翻译。
        val action = when {
            shown == BrowserShownLayer.SNAPSHOT -> BrowserPrimaryAction.RESTORE
            running -> BrowserPrimaryAction.STOP
            pageLoaded -> BrowserPrimaryAction.START
            else -> null
        }
        val label = when (action) {
            BrowserPrimaryAction.RESTORE -> LABEL_RESTORE
            BrowserPrimaryAction.STOP -> LABEL_STOP
            BrowserPrimaryAction.START, null -> LABEL_START
        }
        // 分段开关只在「有东西可切」时可用：会话在跑（后续还会产出译文）或屏上已有译文层。
        // 否则点它必然是「没反应」，这就是 2026-10-02 那次抱怨。
        val toggleEnabled = !busy && (running || shown != BrowserShownLayer.NONE)
        val disabledReason = when {
            reShowBusy -> HINT_BUSY_RESHOW
            screenshotBusy -> HINT_BUSY_SCREENSHOT
            !pageLoaded -> "先打开网页"
            toggleEnabled -> null
            else -> HINT_NO_TRANSLATION
        }
        return BrowserTranslateControls(
            modeToggleEnabled = toggleEnabled,
            modeToggleDisabledReason = disabledReason,
            primaryLabel = label,
            primaryAction = action,
            primaryEnabled = !busy && action != null,
            primaryBusy = busy
        )
    }

    /**
     * 状态行为空时的兜底文案（会话在跑时状态行由会话自己驱动，不走这里）。
     *
     * 有意**不**写「点某按钮…」这类教学句，除非确实还没有任何译文 ——
     * 那种情况下用户需要知道下一步做什么。文案一律指向**球**与**球上的 ⋮ 菜单**：
     * 底栏那两个按钮 2026-10-12 就搬走了，指向不存在控件的文案是死文案。
     *
     * 2026-10-16：快照被点掉后**不再承诺"点球重看"** —— 球只负责整页翻译，
     * 想再来一次当前画面走 ⋮ 菜单的「截图翻译」。
     */
    fun idleHint(
        viewMode: BrowserViewMode,
        running: Boolean,
        shown: BrowserShownLayer,
        pageLoaded: Boolean
    ): String = when {
        shown == BrowserShownLayer.SNAPSHOT -> "截图译文显示中 · 点屏幕恢复原文"
        !pageLoaded -> "打开漫画网页后点球翻译"
        shown == BrowserShownLayer.LIVE && running -> "译文显示中 · 点球可停止"
        shown == BrowserShownLayer.LIVE -> "译文显示中"
        viewMode == BrowserViewMode.ORIGINAL && running -> "原文显示中 · 球上菜单切译文"
        viewMode == BrowserViewMode.ORIGINAL -> "原文显示中 · 点球翻译"
        else -> "点球自动翻完整页"
    }
}
