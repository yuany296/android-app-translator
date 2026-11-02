package com.manga.translator.app

import android.util.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class PixivViewerTaskKind { CURRENT, NEXT }

internal data class PixivViewerTask(
    val page: Int,
    val kind: PixivViewerTaskKind
)

/**
 * Pixiv 页会话的调度队列（纯逻辑，便于 JVM 单测）。
 *
 * 优先级是硬的：
 * 1. **当前页**没译文 → 先翻当前页（用户眼前这一页最重要）；
 * 2. 当前页已有 → 预翻译**下一页**；
 * 3. 两者都有 → 不产生任务，等用户翻页。
 *
 * 缓存最多保留 [cacheLimit] 页；淘汰时避开当前页和下一页，防止把正在用的清理图删掉。
 */
internal class PixivViewerQueue(private val cacheLimit: Int = 3) {
    var currentPage: Int = -1
        private set
    var prefetchPage: Int? = null
        private set

    private var urgentPage: Int? = null
    private val cache = LinkedHashMap<Int, BrowserSceneBuilder.Scene>()

    fun onPageChanged(page: Int, hasNext: Boolean) {
        currentPage = page
        urgentPage = page
        prefetchPage = if (hasNext) page + 1 else null
    }

    fun cached(page: Int): BrowserSceneBuilder.Scene? = cache[page]

    fun nextTask(inFlight: Int?): PixivViewerTask? {
        val urgent = urgentPage
        if (urgent != null && inFlight != urgent && !cache.containsKey(urgent)) {
            return PixivViewerTask(urgent, PixivViewerTaskKind.CURRENT)
        }
        val prefetch = prefetchPage
        if (prefetch != null && inFlight != prefetch && !cache.containsKey(prefetch)) {
            return PixivViewerTask(prefetch, PixivViewerTaskKind.NEXT)
        }
        return null
    }

    /** 记录一次翻译结果；返回被淘汰、需要释放清理图 token 的场景。 */
    fun onTranslated(page: Int, scene: BrowserSceneBuilder.Scene): List<BrowserSceneBuilder.Scene> {
        cache[page] = scene
        if (urgentPage == page) urgentPage = null
        val evicted = mutableListOf<BrowserSceneBuilder.Scene>()
        while (cache.size > cacheLimit) {
            val victim = cache.keys.firstOrNull { it != currentPage && it != prefetchPage } ?: break
            cache.remove(victim)?.let { evicted += it }
        }
        return evicted
    }

    /** 抓图/翻译确定失败时写一个空场景，避免会话每轮都重试同一页。 */
    fun markEmpty(page: Int): List<BrowserSceneBuilder.Scene> =
        onTranslated(page, BrowserSceneBuilder.Scene(cleanedUrl = null, bubbles = emptyList()))

    /** 换章节：页号会从头开始，旧缓存绝不能复用；返回需要释放 token 的场景。 */
    fun clearCache(): List<BrowserSceneBuilder.Scene> {
        val old = cache.values.toList()
        cache.clear()
        currentPage = -1
        prefetchPage = null
        urgentPage = null
        return old
    }
}

/**
 * Pixiv 阅读器页会话（2026-11-01）。
 *
 * 两条协程：
 * - watcher：每 [pollMs] 读一次阅读器状态，处理翻页/原文译文切换，并贴已缓存的场景；
 * - worker：串行抓图 → OCR → 翻译。**绝不并发**，Paddle 会话不是并发安全的。
 *
 * 抓图只是「让阅读器自己画」：当前页读 `currentScreen`，下一页调 `renderNextLeft()` 读
 * 隐藏的 `o5x`。不截图、不下载 CDN 原图、不逆向打乱算法。
 */
internal class PixivViewerSession(
    private val host: PixivViewerSessionHost,
    private val pollMs: Long = 300L,
    private val cacheLimit: Int = 3
) {
    private val TAG = "EmbeddedBrowser"
    private val queue = PixivViewerQueue(cacheLimit)
    private var lastProbe: PixivViewerProbe? = null
    private var appliedPage = -1
    private var wasVisible = true
    private var pageChangedAtMs = 0L
    private var applyRetryAfterMs = 0L
    private var contentKey = ""
    private var contentGeneration = 0
    private var inFlightPage: Int? = null
    private var loadingPage = -1
    private var probeFailures = 0
    private var stopRequested = false
    private val captureAttempts = HashMap<Int, Int>()
    private val translateAttempts = HashMap<Int, Int>()

    suspend fun run() = coroutineScope {
        val watcher = launch { watchLoop() }
        val worker = launch { workLoop() }
        // 协作式退出：host.isActive() 变假后两个循环各自在下一轮结束。
        watcher.join()
        worker.join()
    }

    private suspend fun watchLoop() {
        while (host.isActive() && !stopRequested) {
            val probe = host.probe()
            if (probe == null) {
                probeFailures += 1
                if (probeFailures >= 5) {
                    Log.w(TAG, "pixiv_probe_give_up failures=$probeFailures")
                    host.status("Pixiv 阅读器状态读取失败 · 点球停止")
                    stopRequested = true
                    return
                }
                delay(1_000)
                continue
            }
            probeFailures = 0
            lastProbe = probe
            val visible = host.isViewVisible()

            // 换章节（cid 变）时阅读器可能不重新加载文档：页号会从头开始，
            // 旧章节的缓存绝不能按页号复用，否则会把上一话的译文贴到新一话上。
            if (probe.contentKey.isNotBlank() && probe.contentKey != contentKey) {
                if (contentKey.isNotBlank()) {
                    contentGeneration += 1
                    queue.clearCache().forEach(PixivViewerSupport::releaseSceneImages)
                    if (appliedPage != -1) {
                        host.removeScene()
                        appliedPage = -1
                    }
                    Log.d(
                        TAG,
                        "pixiv_content_change from=$contentKey to=${probe.contentKey} " +
                            "generation=$contentGeneration"
                    )
                }
                contentKey = probe.contentKey
            }

            if (probe.page != queue.currentPage) {
                queue.onPageChanged(probe.page, probe.hasNext)
                pageChangedAtMs = System.currentTimeMillis()
                applyRetryAfterMs = 0L
                if (appliedPage != -1) {
                    host.removeScene()
                    appliedPage = -1
                }
                if (loadingPage != -1) {
                    host.hideLoading()
                    loadingPage = -1
                }
                Log.d(
                    TAG,
                    "pixiv_page page=${probe.page} total=${probe.total} " +
                        "next=${probe.hasNext} next_ready=${probe.nextReady}"
                )
                host.status("第 ${probe.page + 1}/${probe.total} 页 · 预翻译中…")
            } else if (visible && !wasVisible) {
                // 「原文 → 译文」：REMOVE_JS 把页面侧整层摘掉了，必须重贴缓存。
                appliedPage = -1
            }
            wasVisible = visible

            if (!visible) {
                if (appliedPage != -1) {
                    host.removeScene()
                    appliedPage = -1
                }
                if (loadingPage != -1) {
                    host.hideLoading()
                    loadingPage = -1
                }
            } else {
                val scene = queue.cached(probe.page)
                // worker 可能正好在 loading 挂起期间把场景贴上了：先收 loading，别压住新译文。
                if (loadingPage != -1 && (scene != null || appliedPage == probe.page)) {
                    host.hideLoading()
                    loadingPage = -1
                }
                // 当前页还没译文、也没有正在贴的场景：先挂 loading，别让用户对着原图干等。
                if (scene == null && loadingPage != probe.page) {
                    host.showLoading(probe, "预翻译中…")
                    // showLoading 里有一次 JS 挂起；期间 worker 可能已经缓存/贴上了场景。
                    if (queue.cached(probe.page) != null ||
                        appliedPage == probe.page ||
                        !host.isViewVisible()
                    ) {
                        host.hideLoading()
                        loadingPage = -1
                    } else {
                        loadingPage = probe.page
                    }
                }
                // 翻页动画期间 `currentScreen` 会短暂指向过渡缓冲；等一个轮询再贴，
                // 避免把新旧页的几何混在一起（多等 ~300ms，用户几乎无感）。
                val settled = System.currentTimeMillis() - pageChangedAtMs >= pollMs.coerceAtLeast(250L)
                val now = System.currentTimeMillis()
                if (scene != null && appliedPage != probe.page && inFlightPage != probe.page &&
                    settled && now >= applyRetryAfterMs
                ) {
                    if (loadingPage != -1) {
                        host.hideLoading()
                        loadingPage = -1
                    }
                    if (scene.bubbles.isEmpty()) {
                        host.removeScene()
                        appliedPage = probe.page
                        host.status("第 ${probe.page + 1} 页没有可翻译文字")
                    } else if (host.applyScene(scene, probe)) {
                        appliedPage = probe.page
                        Log.d(
                            TAG,
                            "pixiv_apply page=${probe.page} bubbles=${scene.bubbles.size} source=cache"
                        )
                        host.status("第 ${probe.page + 1}/${probe.total} 页 · 点球停止")
                    } else {
                        // 渲染层偶尔会被站点重建/短暂 no_target：退避 2s，别每 300ms 重试刷屏。
                        applyRetryAfterMs = now + 2_000
                    }
                }
            }
            delay(pollMs)
        }
    }

    private suspend fun workLoop() {
        while (host.isActive() && !stopRequested) {
            val task = queue.nextTask(inFlightPage)
            if (task == null) {
                delay(200)
                continue
            }
            inFlightPage = task.page
            val generation = contentGeneration
            val frame = when (task.kind) {
                PixivViewerTaskKind.CURRENT -> host.captureCurrent(task.page)
                PixivViewerTaskKind.NEXT -> host.captureNext(task.page)
            }
            if (frame == null) {
                // 下一页可能还没解码好、或用户刚翻页导致抓图页号对不上。等一会儿再试。
                val attempts = (captureAttempts[task.page] ?: 0) + 1
                captureAttempts[task.page] = attempts
                inFlightPage = null
                if (task.kind == PixivViewerTaskKind.CURRENT && attempts >= 5) {
                    // 当前页连续 5 次抓不到：写空场景，避免会话一直空转；用户翻页后会重新触发。
                    Log.w(TAG, "pixiv_capture_give_up page=${task.page} attempts=$attempts")
                    queue.markEmpty(task.page).forEach(PixivViewerSupport::releaseSceneImages)
                    captureAttempts.remove(task.page)
                    if (loadingPage == task.page) {
                        host.hideLoading()
                        loadingPage = -1
                    }
                }
                delay(800)
                continue
            }
            captureAttempts.remove(task.page)
            if (frame.blank) {
                // 纯白/纯黑页：记空场景，翻到它时直接显示原文，不重试 OCR。
                if (loadingPage == task.page) {
                    host.hideLoading()
                    loadingPage = -1
                }
                queue.markEmpty(task.page).forEach(PixivViewerSupport::releaseSceneImages)
                if (task.page == queue.currentPage && host.isViewVisible()) {
                    host.removeScene()
                    appliedPage = task.page
                    host.status("第 ${task.page + 1} 页没有可翻译文字")
                }
                inFlightPage = null
                delay(50)
                continue
            }

            val rawScene = host.translate(frame)
            if (generation != contentGeneration) {
                // 翻译期间换了章节：结果作废（位图已被管线回收），不能写进新章节的缓存。
                rawScene?.let(PixivViewerSupport::releaseSceneImages)
                inFlightPage = null
                Log.d(
                    TAG,
                    "pixiv_translate_stale page=${task.page} gen=$generation now=$contentGeneration"
                )
                delay(100)
                continue
            }
            if (rawScene == null) {
                inFlightPage = null
                val attempts = (translateAttempts[task.page] ?: 0) + 1
                translateAttempts[task.page] = attempts
                Log.w(TAG, "pixiv_translate_fail page=${task.page} attempts=$attempts")
                if (attempts >= 2) {
                    queue.markEmpty(task.page).forEach(PixivViewerSupport::releaseSceneImages)
                    translateAttempts.remove(task.page)
                    if (loadingPage == task.page) {
                        host.hideLoading()
                        loadingPage = -1
                    }
                }
                delay(500)
                continue
            }

            val scene = PixivViewerSupport.filterTranslatedScene(rawScene)
            if (scene.bubbles.size != rawScene.bubbles.size) {
                Log.d(
                    TAG,
                    "pixiv_scene_filter page=${task.page} " +
                        "kept=${scene.bubbles.size} total=${rawScene.bubbles.size}"
                )
                if (scene.bubbles.isEmpty()) {
                    PixivViewerSupport.releaseSceneImages(rawScene)
                }
            }

            queue.onTranslated(task.page, scene).forEach(PixivViewerSupport::releaseSceneImages)
            translateAttempts.remove(task.page)
            Log.d(
                TAG,
                "pixiv_translate_ok page=${task.page} kind=${task.kind} " +
                    "bubbles=${scene.bubbles.size}"
            )

            if (task.page == queue.currentPage && host.isViewVisible()) {
                val probe = lastProbe?.takeIf { it.page == task.page } ?: host.probe()
                if (probe != null && probe.page == task.page) {
                    if (loadingPage == task.page) {
                        host.hideLoading()
                        loadingPage = -1
                    }
                    if (scene.bubbles.isEmpty()) {
                        host.removeScene()
                        appliedPage = task.page
                        host.status("第 ${task.page + 1} 页没有可翻译文字")
                    } else if (host.applyScene(scene, probe)) {
                        appliedPage = task.page
                        Log.d(
                            TAG,
                            "pixiv_apply page=${task.page} bubbles=${scene.bubbles.size} source=live"
                        )
                        host.status("第 ${task.page + 1}/${probe.total} 页 · 正在预翻译下一页…")
                    }
                }
            } else if (task.page == queue.prefetchPage && scene.bubbles.isNotEmpty()) {
                host.status("第 ${queue.currentPage + 1} 页译文 · 下一页已预翻译")
            }

            inFlightPage = null
            delay(50)
        }
    }
}

internal interface PixivViewerSessionHost {
    fun isActive(): Boolean
    suspend fun probe(): PixivViewerProbe?
    suspend fun captureCurrent(page: Int): PixivViewerFrame?
    suspend fun captureNext(page: Int): PixivViewerFrame?
    suspend fun translate(frame: PixivViewerFrame): BrowserSceneBuilder.Scene?
    suspend fun applyScene(scene: BrowserSceneBuilder.Scene, probe: PixivViewerProbe): Boolean
    suspend fun removeScene()
    suspend fun showLoading(probe: PixivViewerProbe, label: String)
    suspend fun hideLoading()
    fun updateLoading(label: String)
    fun isViewVisible(): Boolean
    fun status(text: String)
}
