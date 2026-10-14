# 项目结构说明

> 配套文档：[CODE_MAP.md](CODE_MAP.md) —— **逐文件一句话职责 + 对应日志 + 死代码清单**。
> 本文件讲模块与主链路入口（宏观），那份讲每个文件在链路里的位置（微观）。

- 日期：2026-03-26
- 执行者：Codex
- 工程根路径：`C:\homework\AI_work\translator\android-app`

## 1. 项目概览

当前工程是一个多模块 Android 应用，根项目名为 `MangaTranslatorMobile`，由 `app`、`core-capture`、`core-translate`、`core-overlay`、`core-store` 五个模块组成。整体运行形态是“前台服务 + 悬浮球 + 覆写层”，主链路围绕“截图 -> OCR -> 翻译 -> 覆写渲染 -> 清理与状态回落”展开。

## 2. 模块职责

| 模块 | 主要职责 | 边界说明 |
| --- | --- | --- |
| `app` | 应用入口、Compose 配置界面、录屏授权、前台服务编排、翻译任务调度、依赖装配 | 负责把各核心模块串成完整业务链路，不承载底层截图、翻译接口或覆写渲染细节实现 |
| `core-capture` | `MediaProjection` 截图、虚拟显示与 `ImageReader` 管线管理、截图恢复与导出 JPEG Data URL | 只负责屏幕采集能力，不处理翻译、排版与 UI 交互 |
| `core-translate` | OCR 与文本翻译请求、OpenAI 兼容接口封装、返回结果解析与归一 | 只输出翻译领域模型，不管理悬浮层和本地持久化 |
| `core-overlay` | 悬浮球、状态浮层、擦字贴片、气泡覆写渲染、交互模式与调整态管理 | 负责最终视觉呈现，依赖翻译模块提供的 `Bubble` 数据结构 |
| `core-store` | 配置持久化、渠道档案管理、翻译缓存、页面偏移调整数据存储 | 负责本地数据读写，不直接参与截图、联网请求或渲染 |

## 3. 模块依赖关系

- `app` 直接依赖 `core-capture`、`core-translate`、`core-overlay`、`core-store`，用于完成完整业务编排。
- `core-overlay` 依赖 `core-translate`，用于复用 `Bubble` 等翻译结果模型。
- `core-capture`、`core-translate`、`core-store` 之间没有项目内直接依赖，保持各自独立。
- Gradle 模块定义以根目录的 `settings.gradle.kts` 为准，新增或删除模块时需要同步更新此文档。

## 4. 关键目录树

```text
android-app/
├─ app/
│  ├─ build.gradle.kts
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ java/com/manga/translator/app/
│     └─ res/
├─ core-capture/
│  ├─ build.gradle.kts
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     └─ java/com/manga/translator/capture/
├─ core-translate/
│  ├─ build.gradle.kts
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     └─ java/com/manga/translator/translate/
├─ core-overlay/
│  ├─ build.gradle.kts
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     └─ java/com/manga/translator/overlay/
├─ core-store/
│  ├─ build.gradle.kts
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     └─ java/com/manga/translator/store/
│        ├─ SettingsRepository.kt          # 配置/渠道档案加密持久化
│        ├─ GlossaryRepository.kt          # 术语表 CRUD + JSON 导入导出 + revision
│        ├─ OcrCacheRepository.kt          # OCR 结果短期缓存（2h TTL）
│        ├─ TranslationHistoryRepository.kt# 翻译历史库（永久，JSON 文件，上限 500 条）
│        ├─ BrowserHistoryRepository.kt    # 内嵌浏览器访问历史（JSON 文件，同 URL 去重置顶，上限 300 条）
│        ├─ PageOverlayAdjustmentRepository.kt
│        └─ ...
├─ docs/
│  ├─ LOCAL_DEBUG_GUIDE.md
│  └─ PROJECT_STRUCTURE.md
├─ scripts/
│  ├─ build_debug.bat
│  ├─ install_debug.bat
│  ├─ reinstall_debug.bat
│  ├─ capture_logcat.bat
│  └─ run_regression_smoke.bat
├─ gradle/
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradle.properties
├─ README.md
├─ Todolist.md
├─ HumanTODO.md
└─ verification.md
```

## 5. 主链路入口

按“权限/服务启动 -> 截图 -> OCR/翻译 -> 覆写渲染 -> 状态/缓存/配置”的顺序，当前关键入口如下：

1. `app/src/main/java/com/manga/translator/app/MainActivity.kt`
   - 应用主入口，负责权限引导、配置页、录屏授权启动以及前台服务启停。
2. `app/src/main/java/com/manga/translator/app/CaptureForegroundService.kt`
   - 承载前台服务生命周期、悬浮球点击响应、翻译任务触发、页面监测与状态回落。
3. `app/src/main/java/com/manga/translator/app/TranslateOrchestrator.kt`
   - 串联截图、压缩、OCR、翻译、缓存读取、擦字处理和 overlay 渲染，是主业务编排入口。
   - 句子级翻译解析（缓存查找 + 引擎分发 + 缓存/历史回写）已抽出为 `app/.../app/PageTranslationResolver.kt`，与内嵌浏览器链路共用。
4. `app/src/main/java/com/manga/translator/app/WebtoonImageTranslator.kt`（内嵌浏览器链路，2026-09-28）
   - 对单张漫画图片执行「气泡检测 → OCR → 翻译 → 擦字 → 渲染译文图」，输出同尺寸 PNG；复用 ServiceLocator 全部底层能力（与悬浮球链路译文/缓存行为一致）。
   - 截页（中缝截字）处理：相邻图条带拼接 OCR（`EmbeddedBrowserScreen` 裁剪传入，带宽按图宽 `clamp(width*0.20,144,384)`）、气泡归属与「半截字」配对走 `app/.../SeamBubbleMerger.kt`（对齐 C:\homework\translator 的 seam 判据：水平重叠/**行数跨度**/间隙/合并高度 + 句子连续性；与 incomingCross 重叠则抑制，避免同一句画两遍）、跨下边界气泡打包 `CrossBubblePart` 交给下一张图补画（同扩展 stitch_overflow 思路；**注意贴片必须在 crossOut 构造之后才能回收**）。
     - **归属不允许有空洞（2026-10-03）**：规则 2/4 的丢弃以「`incomingCross` 确实覆盖」为前提；没覆盖的框本图**认领并裁进可见区**（`orphan_adopted`），整块落在下条带里的框认领但**只移交、不在本图渲染**（`SeamOwnership.renderHere=false`，`handed_tail`）。
     - **切开余量**：跨下边界的框/配对合并框最多伸进下条带 `max(32px, 行高×1.6)`（`SeamConfig.cutAllowancePx`，裁短计数 `capped_cross`）—— 条带深处是下一张图的正文，不能被盖板/接缝带纹理吃掉。
     - **owned 跨缝气泡允许越界**（`buildScene` 的 `stitchOverflow`）：否则渲染层把锚点夹到 0..100，译文会上提、与盖板错位，整块在画面外的移交块还会在页脚画出一行假译文。
     - **接收侧擦字夹紧**：`crossPrepareBubbles` 把擦字区域再夹到移交过来的真实字形轮廓（`sourcePolygonBoundsPx`），夹完 < 4px 就不夹。
     - **接缝层失效可恢复**（`mt-overlay-renderer.js`）：`suppressBubble` 改成「移出 DOM + 登记」，接缝层失效（`dropSeam` / `invalidateSeam`）时按 `seam_key` 贴回两半并打 `MTSEAM restore`；`removeScenes` 清空登记表。接缝带的两切片间隙容忍 1.5px → **3px**（`SEAM_MAX_PAGE_GAP_PX`）。
     - **诊断**：`slice_classify` 的 `orphan_adopted/handed_tail/capped_cross/cut_allow`、逐框判决 `seam_verdict`、擦字明细 `cross_prep px=…`、条带截断嫌疑 `seam_strip_short`。
5. `app/src/main/java/com/manga/translator/app/EmbeddedBrowserScreen.kt`（内嵌浏览器，2026-09-28）
   - 应用内 WebView 打开漫画网页，注入 JS 扫描图片 → 下载（带 Cookie/Referer/UA）→ `WebtoonImageTranslator` 翻译 → 拦截 `https://mtlocal.overlay/<token>.png` 原位覆盖译文图；传递相邻图条带与跨图气泡。入口在底栏「浏览器」tab（`MainActivity.MainTab`）。
   - **连续自动翻译（2026-09-28 第二轮）**：点一次「翻译」后常驻会话按页面顺序逐张翻，翻完不退出，轮询（1.2s）把 lazy-load/滚动出来的新图自动补翻，直到点「停止」或页面跳转；按钮即「翻译／停止」。
      **停止是协作式的（2026-10-14）**：`stopSession` 不再 `job.cancel()`，只把 `session.token += 1`，会话在下一轮 `onSessionActive()` 检查时自己退出；会话跑在 `sessionScope`（`SupervisorJob + Main.immediate`，不被组合销毁连带取消），离开页面时 `onDispose` 先置停、**等会话结束再 `webView.destroy()`**。原因：取消异常穿过会话的 suspend 状态机时，ART 解释器会因「pending 异常 + 首次解析字段」直接 SIGABRT（用户 2026-10-13「不知道为什么悬浮球点不动」= 点球停止即崩溃）。详见 `Todolist.md` §38 的 tombstone。
   - **截图翻译（2026-10-12，悬浮球 ⋮ 菜单，免系统授权）**：只截 WebView 自己的视口（`webView.draw(Canvas)`，应用画自己的 View 无需 MediaProjection / 悬浮窗权限；首页那条授权链路不再接入浏览器）。流程：量视口几何（滚动宿主探测 + 滚动偏移，`BrowserScreenshotTranslate.metricsJs`）→ 截图 → 共用 `WebtoonImageTranslator` 管线 → 按捕获时的文档位置回贴：DOM 覆盖层模式在页面里造一个「视口锚点」`<div data-mt-id="mt-shot">`，现有 `MtOverlay.applySceneJson` 对锚点复用（气泡 = 捕获位图百分比）；「覆盖层渲染」关闭时走整图 PNG（`mtlocal.overlay` 贴到文档坐标）。「原文/译文」切换对截图结果生效（REMOVE_JS 按 `__mt_overlay`/`__mt_scene_root` 一并清理；贴回按存的几何重发）；与连续翻译会话互斥（会话在跑先停、截图进行中拒绝开会话）。已知局限：截图会连同页面上已有译文一起截进去（OCR 可能读到中文）。日志 `shot_start/shot_ok/shot_fail/shot_drop/shot_empty/shot_reshow`（TAG `EmbeddedBrowser`），契约单测 `BrowserScreenshotTranslateTest`。
   - **浏览历史**：`BrowserHistoryRepository` 落盘；空状态「最近访问」+ 齿轮菜单「浏览历史（N）」面板（回访/删单条/清空）；地址栏预填上次网址并支持 × 清空。
   - **渲染选项**：齿轮菜单「精细擦字」（原名「inpaint 清理图」，默认开）→ `CleanedImageProcessor.buildCleanedImage(includeSolid=true)` + `TieredEraseProcessor.erasePage(cleanedForAllBubbles=true)`，固体气泡也走整页 inpaint 背景；开关走 `SettingsRepository.isBrowserInpaintCleanEnabled()`（独立 pref，不在配置档案里）。
     **开 / 关的真实差异（2026-09-29 修正）**：开 = `OPENCV_WHEN_AVAILABLE` + 整页清理图（所有气泡从清理图裁背景，纹理连续、慢）；关 = `FAST_ONLY`（solid 气泡逐块取色贴片、不跑 inpaint，快但复杂背景有色差）。策略选择在 `WebtoonImageTranslator.prepareVisuals`，日志 `erase_mode` / `erase_apply policy=`（此前关闭时逐气泡仍走 inpaint，导致「开不开都一样」）。
      **开 / 关的分工是硬的（2026-10-11 定稿）**：用户要求「开启的时候最符合原文，关闭的时候只考虑速度，只要显示出来译文就行」，两条路线因此彻底分开 ——
      **开 = 质量优先**：整页清理图必建（纯色气泡直填背景色、旁白/透明块墨迹级 TELEA inpaint），逐气泡策略 `OPENCV_WHEN_AVAILABLE`，**所有**气泡的盖板背景都从清理图来（纹理连续、最接近原画，慢）；
      **关 = 速度优先**：`prepareVisuals` 里 `needsCleanedImage = includeSolidInpaint`，**一张清理图都不建**（不跑整页 inpaint、不导出整页 JPEG、不算背景描边判据），逐气泡策略 `FAST_ONLY`（纯色气泡采样底色平涂），`bg=none` 气泡改用**不透明实底盖板**（场景 `flat_cover` → 渲染层 `[data-cover-fill="flat"]` 直接铺 `bg_color`）—— 贴片/清理图都会在笔画边缘留残影，实底不会（用户 2026-10-11：「现在会有残影，用实体的背景遮挡」）。清理图构建失败时也走这条兜底（以前是透明盖板）。
      日志：关闭时 `cleaned_image skipped=fast_mode`、`render_scene … cleaned=false patches=N flat=M`（flat = 用不透明实底遮挡的气泡数）。策略选择点在 `WebtoonImageTranslator.prepareVisuals`，日志 `erase_mode inpaint_clean=… policy=…`。
      *历史（2026-10-11 改造前，保留备查）*：关闭时仍会为旁白气泡建整页清理图（`includeSolid=false`），于是「有旁白的页面开/关耗时基本一致」；只有整页纯色气泡才跳过（建图 273–345ms → 2–12ms）。测量脚本 `scripts/bench-erase-timing.py`，数据见 `verification.md` 2026-10-11 两条。
   - **地址栏与搜索引擎（2026-09-29）**：网页态地址框点按**原地编辑 URL**（全选原文，回车/Go 才导航，✕ 或返回键取消；不再是「点一下跳新标签页」）；新标签页在有网页时搜索行左侧显示「返回网页」‹；关键词搜索走 `BrowserSearchEngine`（默认必应，可切百度/Google，pref `browser_search_engine`），输入归一化统一走 `BrowserUrlResolver`。
   - **debug 明文放行**：`app/src/debug/res/xml/network_security_config_debug.xml` 允许 `localhost / 127.0.0.1 / 10.0.2.2` 走 HTTP，供内嵌浏览器打开本地验证页（`.verify/web` + `python -m http.server` + `adb reverse`）；release 不受影响。
   - **擦字 mask（2026-09-28 修复轮）**：mask 是「墨迹级」的——按内容框亮度直方图众数估背景，只把偏离背景的笔画打进 mask（占比 >55% 才整块擦），并按字号比例外扩 + 膨胀（外框留 4px 保护气泡描边），解决「清理图有时候不生效/擦不干净、旁白底画面被抹糊」。清理图不可用或部分生效时，状态行会明确提示，不再静默回退。
    - **纯色泡按像素升级 solid（2026-10-13）**：区域检测没命中的页面（真机 `bubble_region_stats detected=0` 占多数）所有气泡都是 `bg_type=none` → 盖板从清理图（inpaint）裁切 → 白气泡边上留灰痕。现在 OCR 之后按**像素**复核：外圈一致率 ≥ 0.92 且底色够亮，**或**容器内部一致率 ≥ 0.92 且量到不比字形框窄的平色容器（`core-overlay/BubbleInteriorScanner.kt`）→ 升级 `solid`（纯色盖板 + 纯色直填擦字）。日志 `solid_upgrade … evidence=ring…/flat…`；判据在 `core-overlay/BubbleBackgroundPolicy.kt`。
    - **排版框收进气泡轮廓（2026-10-13）**：`BubbleInteriorScanner` 从字形框中心向外逐行量容器边界（跨文字笔画、撞轮廓即停），按原文字行数分带取最窄可用宽度作为排版框宽度上限（日志 `ink_tilt … interior_cap=`），装不下由渲染层按既有规则缩字号；逐行轮廓同时折成 `region_polygon` 下发，纯色盖板据此裁剪（椭圆/圆角泡收腰处不再露白肩）。斜排（四边形/倾角 > 2°）跳过。
    - **接缝抑制跨场景重建（2026-10-13）**：懒加载站点滚动时把 `<img>` 换成新 blob URL → 会话按内容指纹复用场景重发 → `applyScene` 重建 root；渲染层按 `imageId + data-mt-layer` 把「被接缝层顶掉的半块」的抑制登记**换绑到新 root**（`rebuilt.suppressed=N`），不再让半块跟着重建回来（用户：「向下划后再次向上划的时候可能会出现重复的译文」）。贴回路径重发接缝时也改用几何紧邻的下一张（与正常翻译路径同一判据）。
   - **视图开关（2026-10-02 重构，用户反馈「原文/翻译两个按钮功能不统一、不清晰」）**：底栏拆成两行 —— 行1 = 网页导航（←/→/刷新）+ 状态行，行2 = 「原文｜译文」分段开关 + 「翻译/停止」主按钮。
     分工是硬的：分段开关只决定**看什么**（译文层挂不挂在页面上），主按钮只决定**翻不翻**（唯一能启停会话的地方）。
     切「原文」只摘覆盖层（`REMOVE_JS`：`.__mt_overlay` / `.__mt_scene_root` + `MtOverlay.removeScenes()`）并清 `placedIds`，**不停会话**：后台照常扫描/OCR/翻译，结果只落缓存不上屏（日志 `scene_cached_hidden`），所以切回来是"秒回"。
     切「译文」时 —— 会话在跑就等下一轮轮询（≤1.2s，`needsReShow` 命中，日志 `scan … reshow=N`）；会话没跑但有缓存则起一轮 `BrowserSessionMode.RE_SHOW_ONLY` 的**一次性贴回会话**（不 OCR/不下图/不轮询：`session_start mode=RE_SHOW_ONLY` → `session_end … done=0 shown=N`）。
     没有译文可切（会话没跑且屏上无译文层）时分段开关**置灰禁用**，不再是"能点但点了没反应"；齿轮菜单里的「恢复原文」已删除（视图开关常驻可见，重复入口只会让权威入口含糊）。
     控件状态策略抽成纯函数 `app/.../app/BrowserTranslateUiPolicy.kt`（`BrowserTranslateUiPolicyTest` 表驱动 8 条钉住状态表）。
     接缝覆盖层记录 `seamBySrc` 由会话局部提升到 composable 作用域：贴回走的是**新会话**，留在会话内会让接缝那一段译文贴不回来（既有隐患，本轮一并修掉）。
   - 擦字质量由 `erase_quality`（`TranslationPerf`）量化观测；切换「精细擦字 / 覆盖层渲染」开关会清空已成图并停会话（旧结果是旧设置画的）。
   - **loading 框（2026-10-02，对齐参考项目 `.mt-loading-card`）**：正在处理的那张图上方浮一个半透明胶囊（转圈 + 阶段文案），处理完换成译文覆盖层。渲染层出口 `MtOverlay.showLoadingJson / hideLoading / clearLoadings`，卡片挂在和译文层同一个 `.mt-overlay-layer` 里、锚点取「目标矩形 ∩ 裁切可见部分 ∩ 视口」的中心（webtoon 长图按图片中心会跑到屏幕外），并纳入帧同步跟着滚动；同一张图重复下发只改文案不重建节点。阶段文案由渲染管线给出（`WebtoonImageTranslator` 的 `onPhase` 回调 + `WebtoonPhase` 常量：`识别文字… / 翻译文字… / 擦字渲染…`，会话侧另加 `读取图片…`），回调可能在 IO 线程，经 `mainHandler.post` 回主线程再下发。三条出口都摘卡（成功贴图 / 图里没文字 / 失败）+ 会话 `finally` 补一次 `clearLoadings()` 兜底；「原文」视图（`REMOVE_JS`）与 `removeScenes()` 也会清卡。**卡片是 `pointer-events:none` 的装饰节点，无障碍树里看不到**，验收走页面侧探针 `.verify/web/loadingcheck.html`（logcat 过滤 `LCPROBE`）。
6. `core-capture/src/main/java/com/manga/translator/capture/ScreenCaptureController.kt`
   - 管理 `MediaProjection`、`VirtualDisplay`、`ImageReader` 与截图恢复逻辑，并输出位图或 JPEG Data URL。
7. `core-translate/src/main/java/com/manga/translator/translate/OpenAiVisionClient.kt`
   - 负责 OCR 检测与文本翻译两条请求链路，以及 OpenAI 兼容返回结果的解析。
8. `core-translate/src/main/java/com/manga/translator/translate/PaddleOcrLocalTextDetector.kt`
   - 可选本地 OCR 引擎（PP-OCRv5 mobile，ONNX Runtime），模型按源语言首次下载缓存，失败回退 ML Kit（app 层 `EngineSelectingTextDetector` 选择引擎）。
9. `core-overlay/src/main/java/com/manga/translator/overlay/OverlayController.kt`
   - 负责悬浮球、状态浮层、翻译框、擦字贴片与浏览/调整态的最终渲染控制。
10. `core-store/src/main/java/com/manga/translator/store/SettingsRepository.kt`
   - 负责配置、渠道档案和开关项的加密持久化，是本地配置读写入口。

补充说明：

- `app/src/main/java/com/manga/translator/app/ServiceLocator.kt` 负责组装 `SettingsRepository`、`GlossaryRepository`、`OcrCacheRepository`、`TranslationHistoryRepository`、`BrowserHistoryRepository`、`PageOverlayAdjustmentRepository`、`OpenAiVisionClient`、`OverlayController`、`ScreenCaptureController` 与 `BubbleColorExtractor`。
- `core-translate/src/main/java/com/manga/translator/translate/Models.kt` 定义 `Bubble` 与 `TranslationResult`，作为翻译结果在模块间传递的核心数据结构。
- `core-overlay/src/main/java/com/manga/translator/overlay/BubbleColorExtractor.kt` 对每个气泡从截图像素采样背景色与文字色，`OverlayController` 渲染时替换固定颜色。
- 主链路缓存策略：OCR 结果走 `OcrCacheRepository`（key = 图像指纹 + OCR 配置指纹，2h TTL）；译文走 `TranslationHistoryRepository`（key = 原文序列 + 翻译配置指纹 + 术语表 revision，永久保存），翻译成功后写入历史库。

## 6. 维护约定

- 新增、删除或重命名 Gradle 模块时，必须同步更新本文件的“项目概览”“模块职责”“模块依赖关系”与“关键目录树”。
- 关键入口文件发生迁移时，必须同步更新“主链路入口”一节，避免文档路径失效。
- 新增长期维护的稳定目录时，可补充到“关键目录树”；`build/`、缓存、中间产物目录不应写入本文件。
- `README.md` 继续承担运行说明与上手指引，本文件专注工程结构说明，避免两份文档职责重叠。
