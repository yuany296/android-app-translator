package com.manga.translator.app

/**
 * 屏上此刻挂着**哪一种**译文层 —— 浏览器链路唯一的「显示事实」。
 *
 * 为什么需要这个枚举（2026-10-16 用户反馈「截图翻译后点球无法翻译」的重设计）：
 * 改造前屏上状态散在三处 —— `hasRenderOnPage`（是否贴过译文）、`viewMode`（开关选哪段）、
 * `screenshotRender`（截图结果存不存在）。三者各写各的，于是出现两种典型事故：
 *
 * 1. 截图译文被点掉后 `hasRenderOnPage` 归假，但 `viewMode` 停在「译文」/ 截图结果还在 →
 *    下一次点球起的会话 `isViewVisible` 为假，扫描、OCR、翻译全都做了，屏上却一张都不上；
 * 2. 截图译文的上屏/收回路径不碰活翻译的 `placedIds`，而页面侧却把 `.__mt_scene_root`
 *    整片删掉 → 会话以为"已经在屏上"，再也不会重贴。
 *
 * 现在只有一个事实来源：谁在屏上。写它的只有 EmbeddedBrowserScreen 里那四个函数
 * （hideTranslationLayer / displaySnapshotLayer / showLiveLayer / teardown 路径），
 * 其余代码一律读，不许写。
 */
enum class BrowserShownLayer {
    /** 屏上没有译文层（显示的是原图）。 */
    NONE,

    /** 活翻译层（连续会话逐张上屏的那套）。 */
    LIVE,

    /** 截图翻译的一次性覆盖层（点任意处即收起，不保留可重放状态）。 */
    SNAPSHOT
}

/**
 * 一次「显示来源切换」需要先收掉哪些层（纯函数，单测钉住）。
 *
 * 规则只有一条（R1）：**一次只有一个译文来源**。切到某一层之前，把其它正在屏上的层收干净；
 * 目标层自己若已在屏上则不必动（重复调用幂等，不会闪屏）。
 */
object BrowserLayerPolicy {

    /**
     * @param shown 此刻屏上挂着的层
     * @param target 想挂上去的层
     * @return 必须**先**收掉的层（空集 = 不用动）
     */
    fun retireBefore(shown: BrowserShownLayer, target: BrowserShownLayer): Set<BrowserShownLayer> {
        if (shown == BrowserShownLayer.NONE) return emptySet()
        if (shown == target) return emptySet()
        return setOf(shown)
    }
}
