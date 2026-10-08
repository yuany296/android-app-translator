package com.manga.translator.store

/**
 * 内嵌浏览器「站点适配」声明（2026-09-30）。
 *
 * ## 为什么需要它
 *
 * 内嵌浏览器原来的取图逻辑是**纯通用启发式**：遍历 `document.images`，留下
 * `src` 是 http、`naturalWidth ≥ 260 && naturalHeight ≥ 200`、
 * `getBoundingClientRect()` 宽高 ≥ 180 的 `<img>`。这套规则在本地测试页上够用，
 * 但真实漫画站各有各的取图方式，于是出现「页面能打开、点翻译什么都不发生」：
 *
 * | 站点做法 | 通用启发式的表现 |
 * | --- | --- |
 * | `data-src` / `data-original` 懒加载（包子漫画、漫画柜、拷贝漫画…） | 占位图没加载 → `naturalWidth=0` → 被过滤掉，永远翻不到 |
 * | `<picture><source srcset>` 响应式 | 只看 `img.src`，拿到的是兜底小图 |
 * | 阅读器把图片放进 `overflow:auto` 的滚动容器 | 覆盖层按 `window.scrollY` 定位 → 整块错位 |
 * | 虚拟列表（哔哩哔哩漫画、快看） | 图片被测量后就卸载，扫到的瞬间已经不在 DOM 里 |
 * | 点击才进入阅读器 / 图片在 iframe 里 | 扫描范围内根本没有目标 |
 *
 * 站点适配把这些差异**声明化**：[BrowserSiteAdapter] 描述「这个站该去哪儿找图、
 * 坐标怎么算、懒加载属性叫什么」，由 `BrowserScanScript` 生成对应的扫描脚本。
 *
 * ## 设计原则
 *
 * 1. **声明数据，不含逻辑**：本文件是纯 Kotlin 数据 + 纯函数，放 `core-store`
 *    是为了让它和 [BrowserSearchEngine] / [BrowserHistoryRepository] 一样
 *    可被单测直接覆盖（不依赖 Android）。
 * 2. **通用启发式永远兜底**：站点适配只负责**补充**候选（额外选择器 / 额外属性），
 *    以及**收紧**判据（`minWidth` 等）。某个站改版导致选择器失效时，扫描脚本会
 *    自动回退到通用启发式，最坏情况等价于今天的行为，不会比现在更糟。
 * 3. **未知站点也用得上**：[GENERIC] 不是「什么都不做」——懒加载属性、`srcset`、
 *    `<picture>`、CSS 背景图、可滚动祖先坐标这些**跨站点通用的修正**都默认生效。
 */
data class BrowserSiteAdapter(
    /** 稳定 id：写进设置、日志、UI 都用它，改名不影响已落盘的开关。 */
    val id: String,
    /** 展示名（菜单里告诉用户「已识别为：哔哩哔哩漫画」）。 */
    val label: String,
    /**
     * 主机匹配模式。支持三种写法：
     * - `page.kakao.com` —— 精确匹配该主机；
     * - `*.kakao.com` —— 匹配任意子域（不含 `kakao.com` 本身）；
     * - `*naver.com` —— 匹配 `naver.com` 与其所有子域。
     *
     * 越靠前的模式优先级越低，[BrowserSiteAdapterRegistry.resolve] 取**最长命中模式**
     * 的那一条（`page.kakao.com` 胜出 `*kakao.com`），保证特化规则压过泛化规则。
     */
    val hostPatterns: List<String>,
    /**
     * 额外的图片选择器（CSS）。命中的元素若本身不是 `<img>`，会按
     * `<picture>` / `srcset` / 懒加载属性 / CSS 背景图依次解析出真实图地址。
     */
    val imageSelectors: List<String> = emptyList(),
    /**
     * 排除选择器：命中的元素（及其后代）直接跳过。
     * 用来滤掉站点头部 banner、推荐位缩略图这类「够大但不是漫画页」的图。
     */
    val excludeSelectors: List<String> = emptyList(),
    /**
     * 该站漫画页图的**最小显示宽度 / 高度**（px，`getBoundingClientRect` 口径）。
     *
     * `null` = 用通用阈值（180×180）。真实站点里漫画页图通常占满阅读区宽度，
     * 放宽到 120 左右可以救回「小屏 + 双页并排」的排版，但放太宽会把评论头像、
     * 广告位也扫进来，所以只对已知站点单独调。
     */
    val minWidth: Int? = null,
    val minHeight: Int? = null,
    /**
     * 该站漫画页图的**最小原始像素**下限（`naturalWidth/Height`）。
     *
     * 页图未加载完（懒加载/占位）时 `naturalWidth` 为 0，此时不看这一条，
     * 只要求元素显示尺寸达标 —— 否则懒加载图永远进不了候选。
     */
    val minNaturalWidth: Int? = null,
    val minNaturalHeight: Int? = null,
    /**
     * 该站用来承载图片的元素若是**固定定位的浮层**（全屏看图模式），
     * 坐标要按视口算而不是按滚动容器算。置 `true` 时扫描脚本对这类元素
     * 直接把 `l/t` 记成视口坐标，避免叠加 `scrollX/scrollY` 造成偏移。
     */
    val fixedOverlayPages: Boolean = false,
    /** 站点内部说明（为什么这么配），只用于文档与日志。 */
    val note: String = ""
) {
    /** 主机是否命中本适配器；返回命中的模式长度（越长越特化），未命中返回 -1。 */
    fun matchScore(host: String): Int {
        val h = host.trim().lowercase().substringBefore(':')
        if (h.isEmpty()) return -1
        var best = -1
        hostPatterns.forEach { pattern ->
            val p = pattern.trim().lowercase()
            val hit = when {
                p.isEmpty() -> false
                p.startsWith("*.") -> {
                    val base = p.substring(2)
                    h.length > base.length + 1 && h.endsWith(".$base")
                }

                p.startsWith("*") -> {
                    val base = p.substring(1)
                    h == base || (h.length > base.length + 1 && h.endsWith(".$base"))
                }

                else -> h == p
            }
            if (hit && p.length > best) best = p.length
        }
        return best
    }

    companion object {
        /**
         * 通用兜底适配器：不针对任何站点，但把**跨站点通用**的取图修正全开
         * （懒加载属性 / `srcset` / `<picture>` / CSS 背景图 / 可滚动祖先坐标）。
         *
         * 它没有 hostPatterns —— 作为 [BrowserSiteAdapterRegistry.resolve] 的默认返回值，
         * 不参与匹配打分。
         */
        val GENERIC = BrowserSiteAdapter(
            id = "generic",
            label = "通用",
            hostPatterns = emptyList(),
            note = "未识别站点：只走通用启发式 + 懒加载/srcset/背景图/滚动容器坐标修正"
        )
    }
}

/**
 * 内置站点适配表（2026-09-30）。
 *
 * 维护约定：
 * - 只放**有把握**的规则。不确定的选择器宁可不写 —— 写错的后果是「这个站更糟」，
 *   而不写的后果只是「和通用一样」。
 * - 加站时按 [BrowserSiteAdapter.hostPatterns] 的三种写法给模式，并在 `note`
 *   里写清依据（截图 / 线上 DOM / 抓包），方便下次改版时回溯。
 */
object BrowserSiteAdapterRegistry {

    /** Naver Webtoon / Naver 漫画（`comic.naver.com`、`m.comic.naver.com`）。 */
    private val NAVER_COMIC = BrowserSiteAdapter(
        id = "naver_comic",
        label = "Naver Webtoon",
        hostPatterns = listOf("comic.naver.com", "m.comic.naver.com", "*.naver.com"),
        imageSelectors = listOf(
            "#comic_view_area img", "#container img", ".wt_viewer img", ".viewer_img img"
        ),
        excludeSelectors = listOf("#header", "#footer", ".u_cbox", "aside"),
        minWidth = 120,
        minHeight = 120,
        note = "正片在 #comic_view_area / .wt_viewer；评论区头像必须排除"
    )

    /**
     * Line Webtoons（`www.webtoons.com`、`m.webtoons.com`）。
     *
     * 移动版是典型的「window 滚动 + 每话一个 `#_imageList` 里的 <img>」，
     * 但 PC 版阅读器用 `.viewer_img` 且图片带 `data-url` 懒加载属性。
     */
    private val WEBTOONS = BrowserSiteAdapter(
        id = "webtoons",
        label = "Webtoons",
        hostPatterns = listOf("webtoons.com", "*.webtoons.com"),
        imageSelectors = listOf(
            "#_imageList img", ".viewer_img img", ".cont_box img", "img[data-url]"
        ),
        excludeSelectors = listOf("#header", "#footer", ".comment_area", ".ly_img_grp"),
        minWidth = 120,
        minHeight = 120,
        note = "移动版 #_imageList；PC 版 .viewer_img + data-url 懒加载"
    )

    /**
     * 哔哩哔哩漫画（`manga.bilibili.com`）。
     *
     * 阅读器是虚拟列表：只挂载视口附近的图片，滚走后节点被卸载。因此适配器把
     * 下限放宽、并且**依赖会话轮询**在新图挂载出来时补翻（现有 1.2s 轮询已经能做，
     * 这里只是别把候选漏掉）。图片走 `data-src`。
     */
    private val BILI_MANGA = BrowserSiteAdapter(
        id = "bilibili_manga",
        label = "哔哩哔哩漫画",
        hostPatterns = listOf("manga.bilibili.com", "*.bilibili.com"),
        imageSelectors = listOf(
            ".image-container img", ".manga-image img", ".reader img",
            "img[data-src]", "img[data-original]"
        ),
        excludeSelectors = listOf(".header", ".nav", ".recommend", ".comment"),
        minWidth = 100,
        minHeight = 100,
        minNaturalWidth = 200,
        minNaturalHeight = 200,
        note = "虚拟列表只挂载视口内图片（靠会话轮询补翻）；页图走 data-src"
    )

    /**
     * 漫画柜 `manhuagui.com`（含 `www` / `m` / `tw` 镜像）。
     *
     * 阅读器把每页包成 `img` 放在 `#mangaFile` / `.manga-page` 下，
     * 滚动容器是 `#manga`（不是 window），这是覆盖层错位最典型的场景。
     */
    private val MANHUAGUI = BrowserSiteAdapter(
        id = "manhuagui",
        label = "漫画柜",
        hostPatterns = listOf("*.manhuagui.com", "manhuagui.com"),
        imageSelectors = listOf("#mangaFile img", ".manga-page img", "#manga img", ".pic img"),
        excludeSelectors = listOf("#header", ".header", "#footer", ".ad", ".tip"),
        minWidth = 120,
        minHeight = 120,
        note = "滚动容器是 #manga 而非 window；页图在 #mangaFile"
    )

    /** 拷贝漫画 `copymanga.tv` / `copymanga.com`（含 `www` / `m`）。 */
    private val COPYMANGA = BrowserSiteAdapter(
        id = "copymanga",
        label = "拷貝漫畫",
        hostPatterns = listOf("*.copymanga.tv", "copymanga.tv", "*.copymanga.com", "copymanga.com"),
        imageSelectors = listOf(
            ".comicContent img", ".comic-content img", "#images img",
            "img[data-src]", "img[data-original]"
        ),
        excludeSelectors = listOf(".header", ".footer", ".ad", ".recommend"),
        minWidth = 120,
        minHeight = 120,
        note = "页图在 .comicContent 内，走 data-src 懒加载"
    )

    /** 动漫之家 `dmzj.com` / `manhuaren.com`（同一套阅读器）。 */
    private val DMZJ = BrowserSiteAdapter(
        id = "dmzj",
        label = "动漫之家",
        hostPatterns = listOf("*.dmzj.com", "dmzj.com", "*.manhuaren.com", "manhuaren.com"),
        imageSelectors = listOf(
            "#images img", ".comic_wraCon img", ".comic-wrap img",
            "img[data-src]", "img[data-original]"
        ),
        excludeSelectors = listOf(".header", ".footer", ".ad", ".comment"),
        minWidth = 120,
        minHeight = 120,
        note = "阅读器 #images / .comic_wraCon，data-src 懒加载"
    )

    /**
     * 包子漫画 `baozimh.com`（含 `www` / `m` / `tw` 等镜像）。
     *
     * 页图是 `<amp-img>` 包 `<img>` 或直接 `<img data-src>`，
     * 而且**首屏之外的图在滚动到之前完全不加载**（`naturalWidth=0`），
     * 所以必须允许「显示尺寸够大、原始像素未知」的候选通过。
     */
    private val BAOZIMH = BrowserSiteAdapter(
        id = "baozimh",
        label = "包子漫画",
        hostPatterns = listOf("*.baozimh.com", "baozimh.com", "*.baozimh.org", "baozimh.org"),
        imageSelectors = listOf(
            "#chapcontent img", ".comic-contain img", "amp-img img",
            "img[data-src]", "img[data-original]"
        ),
        excludeSelectors = listOf(".header", ".footer", ".ad", ".index-manga"),
        minWidth = 120,
        minHeight = 120,
        note = "amp-img + data-src；首屏外图片未加载，必须允许 naturalWidth=0 的候选"
    )

    /** 快看漫画 `kuaikanmanhua.com`（虚拟列表阅读器）。 */
    private val KUAIKAN = BrowserSiteAdapter(
        id = "kuaikan",
        label = "快看漫画",
        hostPatterns = listOf("*.kuaikanmanhua.com", "kuaikanmanhua.com"),
        imageSelectors = listOf(".comic-content img", ".reader img", "img[data-src]"),
        excludeSelectors = listOf(".header", ".nav", ".footer", ".recommend"),
        minWidth = 100,
        minHeight = 100,
        minNaturalWidth = 200,
        minNaturalHeight = 200,
        note = "虚拟列表阅读器；滚动时卸载旧页，靠会话轮询补翻"
    )

    /** MangaDex（`mangadex.org`）——阅读器是 React 单页，页图在 `img[data-src]`。 */
    private val MANGADEX = BrowserSiteAdapter(
        id = "mangadex",
        label = "MangaDex",
        hostPatterns = listOf("mangadex.org", "*.mangadex.org"),
        imageSelectors = listOf(
            ".reader-images img", "[data-manga-image] img", "img[data-src]"
        ),
        excludeSelectors = listOf("header", "nav", "footer"),
        minWidth = 120,
        minHeight = 120,
        note = "React 阅读器；页图 data-src 懒加载 + 虚拟列表"
    )

    /** Pixiv 漫画查看器（`www.pixiv.net`）——页图在 `img[src*="i.pximg.net"]`。 */
    private val PIXIV = BrowserSiteAdapter(
        id = "pixiv",
        label = "Pixiv",
        hostPatterns = listOf("pixiv.net", "*.pixiv.net"),
        imageSelectors = listOf(
            "img[src*='i.pximg.net']", "img[data-src*='pximg']", ".gtm-manga-viewer img"
        ),
        excludeSelectors = listOf("header", "nav", "footer", "aside"),
        minWidth = 100,
        minHeight = 100,
        note = "页图域名 i.pximg.net；CDN 强制校验 Referer，下载器已带页面 Referer"
    )

    /** 日本 raw 站：`rawkuma`、`klmanga`、`comic-days.com`（阅读器 DOM 高度相似）。 */
    private val JP_RAW = BrowserSiteAdapter(
        id = "jp_raw",
        label = "日本生肉站",
        hostPatterns = listOf(
            "*.rawkuma.com", "rawkuma.com",
            "*.klmanga.com", "klmanga.com",
            "comic-days.com", "*.comic-days.com"
        ),
        imageSelectors = listOf(
            ".reading-content img", "#readerarea img", ".entry-content img", "img[data-src]"
        ),
        excludeSelectors = listOf(".header", ".footer", ".ad", ".related"),
        minWidth = 120,
        minHeight = 120,
        note = ".reading-content / #readerarea 是这类主题的通用阅读器容器"
    )

    /**
     * **用户主力站**：Lezhin Comics。
     *
     * 域名有一套：`lezhin.com`（韩国主站）/ `lezhin.jp` / `lezhin.es`，
     * 以及用户实际在用的 **`lezhinus.com`**（英文站，2026-09-30 从设备历史里看到）。
     *
     * ## 选择器依据（2026-09-30 实测线上页面）
     *
     * Lezhin 已迁到 **Next.js App Router**：HTML 里能直接看到
     * `"className":"lzCntnr"` / `lzError__title` / `lzError__imageWrapper` 这类
     * **带 `lz` 前缀的语义化 BEM 类名**，以及一份内联的 `initialAppConfigState`。
     * 所以：
     * - **可以用** `lz` 前缀 + BEM 的类名片段（`[class*='lzViewer']` 这类）：它是站点的
     *   设计系统命名，比哈希稳定得多；
     * - **不要用** `topBtn__8FP1p` 那种「名字 + 哈希」的旧 CSS-module 类名：哈希每次
     *   前端发版都会变（同一次实测里两种命名同时存在，说明正在迁移期）。
     *
     * 阅读器把每一话的图按「切图」逐张放进垂直列表，图片地址走 `data-src`
     * （`src` 是占位图）；列表本身在一个 `overflow` 容器里滚动而不是 window，
     * 所以坐标必须按可滚动祖先算 —— 通用层已经处理，这里只补选择器与排除区。
     */
    private val LEZHIN = BrowserSiteAdapter(
        id = "lezhin",
        label = "Lezhin",
        hostPatterns = listOf(
            "lezhin.com", "*.lezhin.com",
            "*.lezhin.jp", "lezhin.jp",
            "*.lezhin.es", "lezhin.es",
            "*.lezhinus.com", "lezhinus.com"
        ),
        imageSelectors = listOf(
            // 懒加载属性：切图的真地址在这里（最稳的一条）。
            "[data-src]:not([data-src=''])",
            "[data-loaded] img",
            // **实测命中的那条**（2026-09-30 在真实 `…/comic/gamesal/1` 页面上跑扫描脚本，
            // `.scroll-view img` 命中 5 次）—— 阅读器本体就是这个 `.scroll-view`。
            ".scroll-view img",
            // `lz` 前缀阅读器（新架构的语义类名）。
            "[class*='lzViewer'] img", "[class*='lzViewer']",
            "[class*='lzCut'] img", "[class*='lzCut']",
            // 旧架构 / 兼容写法：结构与 data 属性，不含哈希。
            "[data-role='page'] img", "[data-role='page']",
            "[class*='scrollView'] img",
            "[class*='ImageContainer'] img",
            ".viewer img", "#viewer img", ".comic-viewer img"
        ),
        excludeSelectors = listOf(
            "header", "nav", "footer", "aside",
            // `notice_contents` 是 Lezhin 的**公告/警示图**（实测真页面上会返回
            // `…/comics/notice_contents/ko_warn_black.webp` 与 `ko_logo_black.webp`，
            // 尺寸 720×616 / 720×473，正好落在漫画页的尺寸区间里）。
            // 不排掉的话，未登录/加载失败时会「一本正经地把警示图当漫画页翻译」。
            "[src*='notice_contents']",
            // Next.js 错误页/占位图（实测有 `lzError__imageWrapper`），
            // 它跟正文图一样是 `<img>`，不排掉会被当成漫画页。
            "[class*='lzError']",
            "[class*='header']", "[class*='gnb']", "[class*='Gnb']",
            "[class*='footer']", "[class*='banner']", "[class*='thumb']"
        ),
        minWidth = 120,
        minHeight = 120,
        note = "切图走 data-src、阅读器滚动在容器内；类名锚 `lz` 前缀语义类（哈希类名一律不写）"
    )

    /**
     * **用户主力站**：RIDIbooks（`ridibooks.com` / `m.ridibooks.com` / `webview.ridibooks.com`）。
     *
     * ⚠️ 这个站要特别说明：RIDI 的漫画阅读器**很可能是 `<canvas>` 渲染**的
     * （把整页画在画布上而不是放 `<img>`），那样 `document.images` 里根本没有目标。
     * 扫描脚本的通用层已经把 `<canvas>` 纳入候选、并支持把画布像素导出来
     * （[BrowserScanScript.EXTRACTOR_JS]），所以这里只补主机匹配、阈值与排除区，
     * 不去猜具体类名 —— 猜错的规则比没有规则更糟。
     *
     * 已知限制（浏览器安全模型，不是实现缺陷）：如果 RIDI 把**跨域 CDN** 的图
     * 画进画布且 CDN 没给 `Access-Control-Allow-Origin`，画布会被污染，
     * `toDataURL()` 抛 `SecurityError`，页面侧拿不到像素；此时只能改用悬浮球
     * 截屏链路。日志里会打 `pixel_extract reason=tainted`。
     */
    private val RIDIBOOKS = BrowserSiteAdapter(
        id = "ridibooks",
        label = "RIDIbooks",
        hostPatterns = listOf(
            "ridibooks.com", "*.ridibooks.com",
            "*.ridicdn.net", "ridicdn.net"
        ),
        imageSelectors = listOf(
            "[data-src]:not([data-src=''])",
            ".viewer img", "#viewer img", "#book_viewer img",
            "[class*='viewer'] img", "[class*='Viewer'] img",
            "[class*='reader'] img", "[class*='Reader'] img"
        ),
        excludeSelectors = listOf(
            "header", "nav", "footer", "aside",
            "[class*='header']", "[class*='gnb']", "[class*='footer']",
            "[class*='banner']", "[class*='thumbnail']", "[class*='BookList']"
        ),
        minWidth = 120,
        minHeight = 120,
        note = "阅读器可能是 canvas 渲染（通用层已支持 canvas 取像素）；跨域污染时退回截屏链路"
    )

    /**
     * **用户主力站**：Kakao Page（`page.kakao.com`）与 Kakao Webtoon。
     *
     * 两条已知事实（2026-09-30 调研）：
     * 1. 页图地址来自 XHR 返回的 `viewer_data.imagedownloaddata.files[].secure_url`，
     *    DOM 里再渲染出来 —— 所以我们**不去解析那个 JSON**（接口随时会变），
     *    只认渲染结果；有时站点把地址包成 `blob:`，通用层已经支持。
     * 2. 容器带 `data-index` / `data-scroll-index` / `data-cut-index` 这类
     *    **语义属性**（不是哈希类名），这些是稳定的，可以作为选择器锚点。
     */
    private val KAKAO_PAGE = BrowserSiteAdapter(
        id = "kakao_page",
        label = "Kakao Page",
        hostPatterns = listOf("page.kakao.com", "webtoon.kakao.com", "*.kakao.com"),
        imageSelectors = listOf(
            "[data-scroll-index] img", "[data-cut-index] img", "[data-index] img",
            "[data-scroll-index]", "[data-cut-index]",
            "[data-src]:not([data-src=''])",
            ".viewer img", "[class*='viewer'] img", "[class*='Viewer'] img"
        ),
        excludeSelectors = listOf(
            "header", "nav", "footer", "aside",
            "[class*='Header']", "[class*='Gnb']", "[class*='Footer']",
            "[class*='Banner']", "[class*='Thumbnail']", "[class*='Card']"
        ),
        minWidth = 120,
        minHeight = 120,
        minNaturalWidth = 200,
        minNaturalHeight = 200,
        note = "页图地址来自 XHR（secure_url），有时是 blob:；选择器锚在 data-* 语义属性上，不锚哈希类名"
    )

    /** 全部内置适配器（顺序无关；匹配按模式长度取最特化的一条）。 */
    val ALL: List<BrowserSiteAdapter> = listOf(
        // 用户主力站排在最前，方便在日志/菜单里一眼看到（匹配本身与顺序无关）。
        KAKAO_PAGE,
        LEZHIN,
        RIDIBOOKS,
        NAVER_COMIC,
        WEBTOONS,
        BILI_MANGA,
        MANHUAGUI,
        COPYMANGA,
        DMZJ,
        BAOZIMH,
        KUAIKAN,
        MANGADEX,
        PIXIV,
        JP_RAW
    )

    /**
     * 按主机解析适配器：取**最长命中模式**的一条；没有命中返回 [BrowserSiteAdapter.GENERIC]。
     *
     * 取「最长」而不是「第一个」是为了让 `page.kakao.com` 这类特化规则稳定压过
     * `*.kakao.com` 这类泛化规则，而与 [ALL] 的排列顺序无关 —— 后面插新站时
     * 不必担心顺序把已有规则顶掉。
     */
    fun resolve(host: String?): BrowserSiteAdapter {
        val h = host?.trim()?.lowercase().orEmpty()
        if (h.isEmpty()) return BrowserSiteAdapter.GENERIC
        var best: BrowserSiteAdapter? = null
        var bestScore = -1
        ALL.forEach { adapter ->
            val score = adapter.matchScore(h)
            if (score > bestScore) {
                bestScore = score
                best = adapter
            }
        }
        return best ?: BrowserSiteAdapter.GENERIC
    }

    /** 从完整 URL 解析适配器（拿不到主机时返回通用）。 */
    fun resolveUrl(url: String?): BrowserSiteAdapter {
        if (url.isNullOrBlank()) return BrowserSiteAdapter.GENERIC
        return resolve(hostOf(url))
    }

    /** 从 URL 里抠主机；解析失败返回空串（调用方会退回通用适配器）。 */
    fun hostOf(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        return runCatching {
            val schemeEnd = trimmed.indexOf("://")
            val afterScheme = if (schemeEnd >= 0) trimmed.substring(schemeEnd + 3) else trimmed
            val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            val hostPort = authority.substringAfter('@')
            val host = if (hostPort.startsWith("[")) {
                // IPv6 字面量：[::1]:8080
                hostPort.substringBefore(']') + "]"
            } else {
                hostPort.substringBefore(':')
            }
            host.trim().lowercase()
        }.getOrDefault("")
    }

    /** 按 id 取适配器（设置页回显用）；找不到返回 null（含 [BrowserSiteAdapter.GENERIC]）。 */
    fun byId(id: String?): BrowserSiteAdapter? {
        val key = id?.trim()?.lowercase().orEmpty()
        return ALL.firstOrNull { it.id == key }
    }
}
