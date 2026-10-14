# 代码速查（CODE MAP）

> 用途：**打开任何一个文件，先在这里定位它在链路的哪一环。**
> 与 [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md) 的分工：那份讲**模块职责与主链路入口**（宏观），
> 这份讲**逐个文件一句话职责 + 对应日志**（微观）。改架构时更新那份，加/改文件时更新这份。
>
> 最近更新：2026-10-03（窗口内改动：合并判据收严、跨切片配对校验、接缝收到侧吸收）

---

## 0. 三秒判断法：这个文件要不要读？

| 路径特征 | 类别 | 要不要读 |
|---|---|---|
| `*/src/main/java/**/*.kt`、`*/src/main/assets/*.js` | 手写源码 | **要** |
| `*/src/test/**`（含 `app/src/test/js/*.test.js`） | 单测 = 可执行文档 | **要**（读判据最快的方式） |
| `app/src/main/res/**`、`AndroidManifest.xml`、`*/build.gradle.kts` | 资源与构建配置 | 改界面/依赖时才看 |
| 路径里含 `build/`、`.gradle/`、`.gradle-home/` | 构建产物 | **不要** |
| `.idea/`、`.claude/`、`.codex/`、`.workbuddy/`、`.verify/`、`logs/`、`*.hprof`、`window_dump.xml`、顶层 `*.png`、`local.properties` | 本地状态/调试产物 | **不要**（已在 `.gitignore`） |

拿不准就 `git check-ignore -v <路径>`：有输出 = 本地产物。

---

## 1. 运行时主干（先记这张图）

```
【入口 1｜悬浮球】                                    【入口 2｜内嵌浏览器】
悬浮球 tap                                            底栏「翻译」按钮
  → CaptureForegroundService.handleFloatingTap         → EmbeddedBrowserScreen.startSession
  → TranslateOrchestrator.runOnce                      → EmbeddedBrowserScreen.runTranslationSession
       │                                                    │
       │  截图：ScreenCaptureController                      │  取图：fetchImageBitmap（url/blob/data/canvas）
       │                                                    │  拼接：上一张底部条 + 本图 + 下一张顶部条
       └──────────────┬─────────────────────────────────────┘
                      ▼
        OCR：LocalTextDetector（MlKit / Paddle 二选一）
                      ▼
        分组：BubbleFirstRegionPlanner.route / buildBubblesFromAssignment
              └ 判据：TextLineGeometry（行高）+ OcrBlockMergeRules（收严后的合并）
                      ▼
        接缝（仅浏览器链路）：SeamNeighbourPolicy → SeamBubbleMerger → SeamIncomingGeometry
                      ▼
        翻译：PageTranslationResolver（缓存 → ML Kit / 百度 / 云端）
                      ▼
        擦字与取色：TieredEraseProcessor / CleanedImageProcessor / BubbleColorExtractor / WritableAreaRefiner
                      ▼
        渲染：悬浮球 = OverlayController.renderBubbles（原生 View）
              浏览器 = BrowserSceneBuilder 出 JSON → WebView 里的 mt-overlay-renderer.js
```

**两条链路共用中段**（OCR → 分组 → 翻译 → 擦字），差别只在「怎么拿到图」和「画到哪里」。

---

## 2. 怎么用日志读懂代码（重要）

所有耗时/诊断日志都出自 `TranslationTimer.mark(tag, extra)`：

```
D/TranslationPerf: [traceId] <tag>: <N>ms | <extra>
```

`tag` 就是代码里写死的字符串 —— **想找"某件事在哪做的"，直接 grep 那个字符串**：

```powershell
grep -rn "cross_prep" app/src/main/java       # Windows 下可用 Select-String
Select-String -Path app\src\main\java\com\manga\translator\app\*.kt -Pattern 'cross_prep'
```

浏览器会话另有 `EmbeddedBrowser` 这个 Tag（`scene_ok` / `seam_dispatch` / `band_pair` / `scan` …）。

---

## 3. 关键文件（按调用时间顺序，带日志对照）

### 3.1 共享中段：OCR 与分组（`core-translate`）

| 文件 | 一句话职责 | 关键入口 | 日志 / 备注 |
|---|---|---|---|
| `Models.kt` | 跨模块数据结构：`Bubble` / `TextBlock` / `BubbleRegion` / `TranslationResult` | 数据类 | `TextBlock.lineCount`（行高的来源）在此 |
| `LocalTextDetector.kt` | 本地 OCR 引擎的统一接口 | `detect` / `isReady` / `prewarm` | 两个实现：ML Kit / Paddle |
| `MlKitLocalTextDetector.kt` | ML Kit 识别：段落块 → `TextBlock`（带行数） | `detect` | 失败 `mlkit detect failed` |
| `PaddleOcrLocalTextDetector.kt` | PaddleOCR(ONNX)：检测 + 多方向识别投票 | `detect` | `paddle_total_ms`、`det_*`、`paddle_quad_reject` |
| `PaddleOcrBlockMerger.kt` | 把 Paddle 的行碎片合并成段落块，并写入 `lineCount` | `mergeBlocks` | 日志 `raw_blocks=/merged=` |
| `PaddleQuadGeometry.kt` | 四边形几何：倾角、质量闸、目标文字字符计数 | `rotationDeg` / `isAcceptableResult` | 纯逻辑，可单测 |
| `TextLineGeometry.kt` | **行高口径**：行数、行高、竖列判定（所有阈值的单位） | `lineCount` / `lineHeightPx` / `isVerticalColumn` | 纯逻辑；见 Todolist §29 |
| `OcrBlockMergeRules.kt` | **收严后的块合并判决**（行内/堆叠 + 字号/间距/对齐/朝向四道闸） | `shouldMerge` | 纯逻辑；返回 `reason` 供 `block_merge_reject` 诊断 |
| `BubbleFirstRegionPlanner.kt` | 区域优先分组：把块分到气泡区域，区域内再拆段 | `route` / `buildBubblesFromAssignment` | `layoutReason`（竖排原因）、`block_merge_reject` |
| `TextQuadGeometry.kt` | `TextBlock` 四边形 → `Bubble` 百分比四边形 + 倾角 | `quadOf` / `percentPolygon` | 渲染层遮罩跟着倾角走 |
| `TextLayoutMetrics.kt` | 视觉行数统计、内容框外扩 | `countVisualLines` / `expandContentRect` | 影响译文字号 |
| `TextAlignment.kt` | 原文对齐推断（左/中/右） | `infer` | 译文沿用对齐 |
| `TextTiltEstimator.kt` | 从像素墨迹估倾角（斜排文字） | `estimate` | 日志 `ink_tilt` |
| `NonTranslatableText.kt` | 判断"不值得翻"的文本（纯符号/单字符…） | `isNonTranslatable` | 纯逻辑 |

### 3.2 共享中段：翻译（`core-translate` + `app`）

| 文件 | 一句话职责 | 关键入口 | 日志 / 备注 |
|---|---|---|---|
| `app/PageTranslationResolver.kt` | 翻译调度：句子缓存 → 引擎分支 → 回写历史 | `resolveTranslations` / `buildTranslateKey` | `sentence_cache`、`translate_local`、`mode=baidu`、`mode=cloud` |
| `core-translate/MlKitLocalTranslator.kt` | ML Kit 本地翻译（语言对支持/模型下载） | `translate` / `isSupported` | `translate_local`、`translate_local_fallback`、`translate_local_skip` |
| `core-translate/BaiduTranslateClient.kt` | 百度翻译专用接口（整页多句一次请求 + 签名） | `translate` | `mode=baidu` |
| `core-translate/OpenAiVisionClient.kt` | OpenAI 兼容客户端：**现在只用于翻译**（+设置页拉模型列表）；术语表以 `buildGlossaryLine` 注入提示词 | `translateTexts` / `fetchAvailableModels` / `buildGlossaryLine` | `mode=cloud`；单测 `GlossaryPromptTest` 钉住术语表行格式；⚠️ OCR 方法已无调用方（见 §7） |

### 3.3 悬浮球链路（`app`）

| 文件 | 一句话职责 | 关键入口 | 日志 / 备注 |
|---|---|---|---|
| `TranslatorApplication.kt` | 应用入口，初始化依赖装配 | `onCreate` | → `ServiceLocator.init` |
| `ServiceLocator.kt` | 全局依赖装配（单例）+ OCR 引擎选择/回退 | `init`、`EngineSelectingTextDetector` | Paddle 空结果会回退 ML Kit |
| `CaptureForegroundService.kt` | 前台服务：悬浮球点击、投射状态、翻页监测、状态回落 | `handleFloatingTap` | `runOnce failed`、`capture_failure_type` |
| `TranslateOrchestrator.kt` | **悬浮球链路主编排** | `runOnce` / `prepareVisuals` / `renderAndBuildOutcome` | `screenshot_done`、`ocr_response`、`erase_skip`、`render_done` |
| `EraseTargets.kt` | 「读不出就别动」：哪些区域值得擦/翻 | `isWorthErasing` | `erase_targets` / `erase_target` 日志由它格式化 |
| `core-capture/ScreenCaptureController.kt` | MediaProjection 截图与恢复 | `captureBitmapWithRecovery` | `screenshot_done` |

### 3.4 内嵌浏览器链路（`app`）

| 文件 | 一句话职责 | 关键入口 | 日志 / 备注 |
|---|---|---|---|
| `MainActivity.kt` | Compose 外壳（底栏 tab：首页/浏览器/术语表/翻译历史）、权限引导、服务启停 | `MainTab` | 浏览器 tab → `EmbeddedBrowserScreen` |
| `EmbeddedBrowserScreen.kt` | **浏览器会话编排**（扫描轮 → 每张图 → 下发 → 接缝）+ 界面 | `startSession` / `runTranslationSession` | `session_start`、`scan pending=`、`band_pair`、`scene_ok`、`image_ok/no_text/no_bubbles/fail` |
| `BrowserScanScript.kt` | 注入网页的扫描脚本：找图、坐标契约、宿主标记、像素提取 | `SCAN_JS` / `EXTRACTOR_JS` | `site_adapter`、`scan` |
| `BrowserSceneBuilder.kt` | 场景 JSON 生产者（普通场景 + 接缝带场景）= Kotlin↔JS 契约 | `buildSceneJson` / `buildSeamSceneJson` | 渲染层字段以它为准 |
| `WebtoonImageTranslator.kt` | **浏览器链路核心管线**：拼接→OCR→归属→翻译→擦字→场景/贴片 | `translateImage` / `translateStitched` | `slice_classify`、`seam_verdict`、`cross_prep`、`incoming_absorbed`、`render_done` |
| `SeamNeighbourPolicy.kt` | 跨切片**配对闸**：上一张/下一张是否几何紧邻 | `predecessor` / `successor` / `decide` | `band_pair`、`incoming_dropped`；纯逻辑 |
| `SeamBubbleMerger.kt` | 接缝**归属**：跨缝块归谁、是否已被上一张画过 | `planSeamOwnership` / `isHandedOverByIncoming` / `SeamTuning` | `seam_verdict`、`slice_classify`；纯逻辑 |
| `SeamIncomingGeometry.kt` | 收到侧**并框** + 退化框最小高度 | `isSameLineFragment` / `expandForAdjacentFragments` / `enforceMinimumHeight` | 纯逻辑 |
| `SeamBandGeometry.kt` | 接缝带几何换算与盖板外扩 | `coverPadPx` / `coverPadPxFor` / `sourcePercentToStitched` | 纯逻辑 |
| `HandoffTextMatch.kt` | 移交可用性的**文本证据**（字符重合度 ≥0.7 才算同一句） | `explainedByHandoff` | `incoming_decision`；纯逻辑 |

### 3.5 渲染层（`core-overlay` + 网页 JS）

| 文件 | 一句话职责 | 关键入口 | 日志 / 备注 |
|---|---|---|---|
| `core-overlay/OverlayController.kt` | 悬浮球链路渲染：悬浮球、气泡视图、擦字贴片、浏览/调整态 | `renderBubbles` / `showFloatingButton` | 详见 §4 core-overlay |
| `app/src/main/assets/mt-overlay-renderer.js` | **网页侧渲染层**：擦原文、画译文、接缝带、loading 卡 | `MtOverlay.applySceneJson` / `applySeamSceneJson` / `suppressBubble` / `showLoadingJson` | 契约见 §4 渲染层 JS |

---

## 4. 全量文件速查（按模块）

### 4.1 `core-translate/src/main/java/com/manga/translator/translate`

| 文件 | 一句话职责 | 关键入口 | 备注 |
|---|---|---|---|
| `Models.kt` | 全链路数据模型（气泡/文字块/四边形/结果） | `Bubble` / `TextBlock` / `QuadPoint` | 被三个模块引用；`TextBlock.lineCount` 是行高来源 |
| `LocalTextDetector.kt` | 本地 OCR 引擎接口（检测/就绪/预热） | `detect` / `isReady` / `prewarm` | 两个实现由 `ServiceLocator.EngineSelectingTextDetector` 分流 |
| `MlKitLocalTextDetector.kt` | ML Kit 端侧识别 → 段落级块 + 行数 | `detect` | 日志 TAG `MlKitLocalDetector`；只有 androidTest |
| `PaddleOcrLocalTextDetector.kt` | PP-OCRv5 本地 OCR 主引擎：检测 + 并行识别 + 后聚合 | `detect` / `runDetPass` / `ensureEngineReady` | 日志 `paddle_total_ms=… rec_ms=… speed_mode=`、`paddle_quad_reject`、`model_download_*` |
| `PaddleOcrBlockMerger.kt` | 把 Paddle 碎片按行/段合并成完整句子（并写入行数） | `mergeBlocks` | 只被 Paddle 检测器调用；纯逻辑 + 单测 |
| `PaddleOcrImagePreprocessor.kt` | 生成灰度/反色/二值变体提升彩色字检出率 | `buildEnhancedGrayscale` / `buildOtsuBinaryMask` | 只被 Paddle 检测器调用；依赖 Bitmap |
| `PaddleQuadGeometry.kt` | 四边形几何：倾角、竖排 crop、识别质量、方向择优 | `rotationDeg` / `isAcceptableResult` / `selectBestOrientation` | 纯逻辑 + 单测；`PaddleOcrBlockMerger`/`TextQuadGeometry`/`OcrBlockMergeRules` 都用它 |
| `TextLineGeometry.kt` | **行高口径**（唯一单位） | `lineHeightPx` / `lineCount` / `isVerticalColumn` | 纯逻辑；测试在 `OcrBlockMergeRulesTest` |
| `OcrBlockMergeRules.kt` | **收严后的合并判决**（行内/堆叠 + 四道闸 + reason） | `shouldMerge` | 三个调用方都打 `block_merge_reject` 诊断；纯逻辑 + 单测 |
| `BubbleGeometry.kt` | 气泡框归一化（0-100%）；另含旧版合并启发式 | `outerRect` / `contentRect` / `BubbleMergeHeuristics` | ⚠️ `BubbleMergeHeuristics` 实质死代码（见 §7） |
| `TextQuadGeometry.kt` | 四边形 → 气泡百分比坐标 + 倾角（渲染契约） | `quadOf` / `percentPolygon` / `stitchedPolygonToSource` | 有单测；拼接图→原图映射也在这 |
| `TextLayoutMetrics.kt` | 视觉行数统计（同一行碎片算一行） | `countVisualLines` | ⚠️ `expandContentRect` 无调用方（见 §7） |
| `TextAlignment.kt` | 从逐行框推断左/中/右对齐 | `infer` / `normalize` | 纯逻辑 + 单测 |
| `TextTiltEstimator.kt` | 无四边形时用墨迹最小外接矩形估倾角；`TextInkBounds` 收紧文字框 | `estimateRectFromLuma` / `TextInkBounds.findInBitmap` | 日志 `ink_tilt`；luma 版可单测 |
| `NonTranslatableText.kt` | 判定语言中立文本（数字/时间/URL/符号）免翻译 | `isNonTranslatable` | 纯逻辑 + 单测 |
| `OpenAiVisionClient.kt` | OpenAI 兼容云端客户端：**翻译** + 模型列表 | `translateTexts` / `fetchAvailableModels` | 日志 TAG `VisionClient`；⚠️ OCR 三方法死代码（见 §7） |
| `BaiduTranslateClient.kt` | 百度翻译专用接口（整页多句一次请求 + 分批限流） | `translate` / `toBaiduLanguage` | 上游日志 `mode=baidu`；TAG `BaiduTranslate` 定义了但未打点 |
| `LocalTranslator.kt` | 本地离线翻译接口（含模型下载/磁盘探测） | `isSupported` / `translate` / `downloadModel` | 实现方 `MlKitLocalTranslator` |
| `MlKitLocalTranslator.kt` | ML Kit 端侧翻译：语言对模型下载 + 逐句容错 | `translate` / `prewarm` | 日志 TAG `MlKitLocalTranslator`、`mlkit_translate_*`；上游 `translate_local*` |

### 4.2 `core-store/src/main/java/com/manga/translator/store`

| 文件 | 一句话职责 | 关键入口 | 备注 |
|---|---|---|---|
| `AppSettings.kt` | 全量设置数据载体（引擎/密钥/开关） | `AppSettings` | ⚠️ `ocrMode`、`baiduOcrLanguage` 两个字段无人读（见 §7） |
| `SettingsRepository.kt` | 加密 SharedPreferences 读写全部设置与配置档案 | `getSettings` / `saveSettings` / `getProfile` / `saveProfile` | 几乎所有 app 类都依赖它；`ocrMode`/`baiduOcrLanguage` **没有**对应的 prefs 键 |
| `AiProvider.kt` | 各 AI 服务商默认地址/模型/思考开关 | `fromId` / `supportsThinking` | 与 `ThinkingSupport` 有跨模块一致性测试 |
| `AppLanguage.kt` | 源/目标语言枚举与 id 归一 | `fromId` | 设置页/历史页用 |
| `TranslateEngine.kt` | 翻译引擎枚举（云端 / 百度 / ML Kit） | `fromId` | 对应日志 `mode=cloud|baidu`、`translate_local` |
| `OcrEngine.kt` | OCR 引擎枚举（ML Kit / Paddle） | `fromId` | 在 `EngineSelectingTextDetector` 分流 |
| `OcrSpeedMode.kt` | Paddle 速度/质量模式 | `fromId` | 体现在 `paddle_total_ms` 的 `speed_mode=` |
| `OcrMode.kt` | OCR 模式枚举（远程 OCR / 本地+LLM 分组） | `fromId` | ⚠️ 随远程 OCR 下线而闲置（见 §7） |
| `BaiduOcrLanguage.kt` | 百度 OCR 语言码枚举 | `JAP` / `KOR` / … | ⚠️ 同上下线（见 §7） |
| `GlossaryRepository.kt` | 多术语表增删改、生效优先级、导入导出、冲突检测 | `activeGlossaryEntries` / `activeRevision` / `importCsv` | 5 个单测（ActiveMerge/Csv/Merge/Management/UniqueName） |
| `TranslationHistoryRepository.kt` | 翻译历史落盘、按页/指纹查询与淘汰 | `all` / `findByPageHash` / `add` | 单测 `HistoryCodecTest` |
| `SentenceTranslationRepository.kt` | 句子级翻译缓存（精确/紧凑/模糊三级匹配） | `lookup` / `put` | 日志 TAG `SentenceCache`、`lookup miss`、`put skip_noop` |
| `PageOverlayAdjustmentRepository.kt` | 逐页逐气泡的覆盖层微调持久化 | `get` / `save` | 悬浮球链路调参数 |
| `BrowserHistoryRepository.kt` | 浏览历史落盘、同 URL 去重置顶、超限淘汰 | `record` / `recent` / `lastVisited` | 日志 `history_record`；`urlWithoutScheme` 被全仓用于日志脱敏 |
| `BrowserFavoriteRepository.kt` | 浏览器收藏落盘与增删改 | `all` / `add` / `update` | 新标签页收藏格子 |
| `BrowserSearchEngine.kt` | 搜索引擎枚举与搜索 URL 模板 | `buildSearchUrl` / `fromId` | 默认必应，可切百度/Google |
| `BrowserSiteAdapter.kt` | 站点适配声明表 + 按主机/URL 解析 | `BrowserSiteAdapterRegistry.resolve` / `byId` | 日志 `site_adapter page=…`；`BrowserScanScript` 用它注入配置 |
| `BrowserUrlResolver.kt` | 地址栏/搜索框输入 → 可加载地址 | `resolve` / `searchUrl` | 纯逻辑 + 单测 |

### 4.3 `core-overlay/src/main/java/com/manga/translator/overlay`

| 文件 | 一句话职责 | 关键入口 | 备注 |
|---|---|---|---|
| `BackgroundOutlinePolicy.kt` | 按清理图亮度跨度决定译文要不要加描边 | `needsOutline` / `isBusy` | 只被浏览器链路 `WebtoonImageTranslator` 调用；日志 `outline_need`（plain/busy/outline/solid）；`isBusy` 等纯逻辑可单测 |
| `BubbleAdjustment.kt` | 单个气泡的用户调整量（位移/字号/描边） | `BubbleAdjustment` | `OverlayController.renderBubbles` 的 load/save 回调；持久化在 `PageOverlayAdjustmentRepository` |
| `BubbleColorExtractor.kt` | 采样背景中值色与墨色，产出译文配色 | `extract` / `chooseReadableTextColor` | 日志 `sampled_colors b0=#文字色/#底色`（浏览器）/ `bubble_colors count=`（悬浮球）；配色判据是 internal 纯函数可单测 |
| `BubbleRegionDetector.kt` | OpenCV 亮斑轮廓框出候选气泡区域 | `detect` | 日志 TAG `BubbleRegionDetect`；调用方计时 `bubble_region_stats`；**注意**：整页浅色会被检成大亮块，`isSolidBubbleRegion` 会因此退回 `none` |
| `BubbleRenderState.kt` | 单个译文视图的可变渲染状态 | `BubbleRenderState` | 仅 `OverlayController` 使用 |
| `EraseStrategy.kt` | 擦字策略/质量档位枚举 | `EraseStrategy` / `EraseStrategyPolicy` | ⚠️ `RenderQualityProfile` **无读取方 = 死代码** |
| `FloatingState.kt` | 悬浮球阶段（空闲/加载模型/截图/翻译/完成/失败） | `FloatingState` | 由 `CaptureForegroundService` 驱动、`OverlayController` 呈现 |
| `InteractionMode.kt` | 浏览态 / 手动调整态（决定覆盖层是否吃触摸） | `InteractionMode` | 长按切态；服务复位为 BROWSE |
| `OverlayController.kt` | **悬浮球链路渲染主力**：WindowManager 管悬浮球 + 译文/贴片层 | `renderBubbles` / `showFloatingButton` / `setFloatingState` / `clearTranslationLayer` | 日志 TAG `OverlayController`：`clear_translation_layer`、`translation_layer_locked/unlocked`；perf `render_font_stats` |
| `OverlayRenderConfig.kt` | 渲染开关载体（密集模式/字号/触摸/擦字策略） | `OverlayRenderConfig` | ⚠️ `qualityProfile`、`erasePolicy` **只写不读** |
| `StrokeTextView.kt` | 横排译文自绘 TextView（先描边后填充） | `onDraw` / `setStroke` | 仅 `OverlayController` 横排气泡用 |
| `VerticalTextView.kt` | 竖排译文自绘 TextView（按列排字） | `onDraw` / `setStroke` | 仅 `OverlayController` 竖排气泡用 |
| `TranslationLockGesturePolicy.kt` | 判定锁定层上的手势是否释放译文锁定 | `decide` | 纯逻辑 + 单测；日志 `lock_tap_release` / `lock_gesture_blocked` |
| `WritableAreaRefiner.kt` | 把 OCR 内容框收进真正可写区（刮掉框外空白） | `refine` / `scanInset` | 日志 `refine_boxes`；`scanInset`/`WritableAreaThresholds` 纯逻辑可单测 |
| `erase/CleanedImageProcessor.kt` | 整页 inpaint 去字 → 「清理图」（接缝带/盖板背景来源） | `buildCleanedImage` / `buildInkMaskData` | 日志 `cleaned_image built/skipped/failed`；墨迹 mask 判据纯逻辑可单测 |
| `erase/EraseComplexityAnalyzer.kt` | 统计气泡区亮度方差/边缘密度 → 擦字难度 | `analyze` / `bubbleToRoi` | 结果体现在 `erase_apply` 的 `fast/edge/opencv` 计数 |
| `erase/ErasePatch.kt` | 一块擦字贴片（位图 + 源区/渲染区归属） | `ErasePatch` / `fromCleanedImage` | 产出 `TieredEraseProcessor`，消费 `OverlayController.applyErasePatches` |
| `erase/TieredEraseProcessor.kt` | 按复杂度分层选填充/inpaint，生成整页擦字贴片并缓存 | `erasePage` / `buildPageHash` | 日志 `erase_prepare`、`erase_apply`（`cache_hit`、`fast/edge/opencv`、`cleaned_patched`）；`buildPageHash` 也是缓存键来源 |

### 4.4 `core-capture/src/main/java/com/manga/translator/capture`

| 文件 | 一句话职责 | 关键入口 | 备注 |
|---|---|---|---|
| `CaptureFailurePolicy.kt` | 归类截图异常（超时/录屏失效…）并夹取重试超时 | `classify` / `retryTimeoutMs` | 仅 `ScreenCaptureController` 用；纯逻辑可单测 |
| `FramePolicy.kt` | 判帧是否可疑、缓存是否新鲜、缩放与 JPEG 质量参数 | `isSuspicious` / `snapshotUsable` / `shouldScale` / `clampedQuality` | 日志 `stage=image_to_bitmap … suspicious=true`、`stage=cache_frame skip=suspicious`；纯逻辑可单测 |
| `ScreenCaptureController.kt` | 管 MediaProjection/VirtualDisplay，抓帧判质编码并超时重建重试 | `start` / `captureBitmapWithRecovery` / `toJpegDataUrl` | 日志 TAG `ScreenCaptureCtrl`：`capture_start`、`media_projection_onStop`、`capture_timeout_attempt`、`stage=hard_recreate`、`capture_recover_*` |

### 4.5 `app`：应用外壳与服务

| 文件 | 一句话职责 | 关键入口 | 备注 |
|---|---|---|---|
| `MainActivity.kt` | 应用唯一 Activity：四个 tab、录屏授权、服务启停 | `onCreate` / `MainBottomBar` / `startCaptureFlow` | manifest 里唯一 launcher；`startCaptureFlow` 构造 MediaProjection 授权 Intent 并 `startForegroundService`；术语表导入导出/历史刷新也挂这里 |
| `TranslatorApplication.kt` | 进程启动即初始化全局依赖容器 | `onCreate` | → `ServiceLocator.init`；不初始化则首次访问 `lateinit` 崩溃 |
| `ServiceLocator.kt` | 全局单例：集中创建并持有各仓库与客户端 | `init` / `createOverlayController` / `EngineSelectingTextDetector` | 按设置里的 `OcrEngine` 选 Paddle/ML Kit，Paddle 抛错或空结果回退 ML Kit —— 设置页「OCR 引擎」在此生效 |
| `CaptureSessionState.kt` | 录屏会话的四种状态与快照数据结构 | `CaptureSessionState` / `fromId` | 纯数据可单测；id 字符串是 prefs 与日志的持久化口径 |
| `CaptureSessionStateStore.kt` | 会话状态与原因持久化到 SharedPreferences | `getSnapshot` / `update` | prefs `mt_capture_session`；`MainActivity.startCaptureFlow` 读它决定是否重新申请录屏授权 |
| `CaptureSessionGate.kt` | 用系统服务枚举校正已过期的会话状态 | `normalizeStoredState` / `isCaptureServiceRunning` | 服务没跑却存着 active → 纠正为 stopped；`isCaptureServiceRunning` 走已废弃的 `getRunningServices`，被 HomeScreen 每 1.5s 轮询 |
| `CaptureSessionPolicy.kt` | 只回答「当前状态要不要重新弹录屏授权」 | `shouldRequestProjection` | 纯函数 + 单测；唯一生产调用方是 `MainActivity.startCaptureFlow` |
| `FrameHash.kt` | 对截图中心区做 dHash，得到可比较的页面指纹 | `centerDHash` | 被 `TranslateOrchestrator.runOnce` 用作 `baselineHash`；⚠️ `hammingDistance` **无调用方 = 死代码** |
| `QuadMetrics.kt` | 由文字四边形算字号所需的「厚度」 | `percentQuadThicknessPx` | 生产调用方只有 `WebtoonImageTranslator`（决定译文字号）；纯几何可单测 |
| `AccessibilityMonitor.kt` | 把无障碍事件转成全局事件流并记住前台包名 | `publish` / `events` / `currentForegroundPackage` | 由 `OverlayAccessibilityService` 发布，`CaptureForegroundService` 消费 → 决定切应用/翻页是否清覆盖层 |
| `OverlayAccessibilityService.kt` | 系统无障碍服务，转发窗口/滚动事件 | `onAccessibilityEvent` | 需用户在系统设置手动开启；只转发 4 类事件，不做业务判断 |
| `OverlayAccessibilityPolicy.kt` | 按包名与事件类型决定覆盖层清理动作 | `decide` | 纯函数 + 单测；直接决定「翻页/切应用后译文还挂不挂在屏上」 |
| `BrowserTranslateUiPolicy.kt` | 算出浏览器底栏按钮的可用性与文案 | `resolve` / `idleHint` | 被 `EmbeddedBrowserScreen` 底栏调用；纯数据可单测（2026-10-02「原文/翻译语义不统一」的收口点） |

### 4.6 `app`：界面

| 文件 | 一句话职责 | 关键入口 | 备注 |
|---|---|---|---|
| `HomeScreen.kt` | 首页：语言对、OCR/翻译服务配置、启停入口 | `LauncherScreen` / `ProfileSheet` / `ProfileFormScreen` | 设置改动经 `onSettingsChange` 落盘；`ProfileSheet/ProfileFormScreen/BaiduConfigSheet` 管多套 provider 配置（含百度凭据） |
| `GlossaryScreen.kt` | 术语表集合列表与集合内词条增删改查 | `GlossaryCollectionsScreen` / `GlossaryCollectionScreen` | 写操作回 `MainActivity` → `GlossaryRepository`；复制/合并/重命名/冲突对话框都在这里 |
| `HistoryScreen.kt` | 翻译历史列表：展开看气泡、下拉刷新、清空 | `HistoryScreen` / `parseHistoryBubbles` | 清空历史 = 失去该页复用能力（会重新调 API） |
| `ui/MtUi.kt` | 全应用共用按钮/卡片/列表行/自绘图标 | `MtCard` / `MtPrimaryButton` / `MtListRow` | 规格取设计稿「14-配色与组件规范」；⚠️ `MtOutlineButton`/`MtTextButton` **无调用方 = 死代码** |
| `ui/theme/Color.kt` | 全应用色板常量 | `MtColors` | 改配色只改这里 |
| `app/ui/theme/Theme.kt` | 把色板、字号阶梯与圆角组装成 MaterialTheme（App 自己界面的设计令牌总闸） | `MangaTranslatorTheme` / `MtTypography` / `MtShapes` | 只在 [MainActivity:250](app/src/main/java/com/manga/translator/app/MainActivity.kt#L250) `setContent` 里用一次，包住全部 Compose 界面；只做**浅色**（`lightColorScheme`）；`surfaceTint = Surface` 刻意**关掉 M3 高程染色**（设计稿弹窗是纯白）；字号全 `letterSpacing = 0`（中文排版）⚠️ `MtShapes` 无消费方（见 §7） |
| `SwipeRevealRow.kt` | 通用「左滑露出删除」列表行 | `SwipeRevealRow` | 仅 GlossaryScreen 两处使用；行高 72dp、右侧 80dp 删除键 |
| `SiteFaviconLoader.kt` | 抓网站 favicon 并做内存 LRU 缓存 | `load` / `cached` | 浏览器收藏/历史格子用；只试 `/favicon.ico|.png`，失败也缓存「无图标」 |

### 4.7 渲染层 JS（`app/src/main/assets/mt-overlay-renderer.js`）

| 文件 | 一句话职责 | 关键入口 | 备注 |
|---|---|---|---|
| `mt-overlay-renderer.js` | 注入 WebView 的译文 DOM 覆盖层：收 Kotlin 下发的场景 JSON，在目标 `<img>` 上建绝对定位 DOM，cover 层遮原文、text 层画译文，逐帧跟随滚动 | `applySceneJson` / `applySeamSceneJson` / `showLoadingJson` | 单文件 IIFE，92 个函数只经 `window.MtOverlay` 暴露；无任何前端依赖；注入点 `EmbeddedBrowserScreen.onPageFinished`（脚本带 `cached`，首行 `if (window.MtOverlay) return;` 保证幂等） |

**Kotlin 真正调用的 7 个出口**（其余导出只服务 Node 验收测试）：

| 出口 | 入参关键字段 | 返回 | 用途 |
|---|---|---|---|
| `applySceneJson` | `image_id` / `cleaned_url` / `page_cleaned_url` / `bubbles[]` | `{ok, rebuilt, bubbles, covers, host}` | 普通场景（一张图一个 root）；签名相同只同步位置、不重建（防闪烁） |
| `applySeamSceneJson` | 再加 `seam_key` / `next_image_id` / `band_*_px` / `top_h_px` / `source_*_px` | 同上 + `mode:"seam"` | 接缝带（一条缝一个 root，跨缝只画一块盖板 + 一份译文） |
| `suppressBubble(imageId, bubbleId)` | — | 摘掉的节点数 | 接缝带上屏后**移出 DOM 并登记**（可逆，不销毁） |
| `showLoadingJson` / `hideLoading` / `clearLoadings` | `{image_id, label}` | `{ok, reused}` / count | 目标图上方的「读取图片…/识别…/翻译…」胶囊 |
| `removeScenes()` | — | 节点数 | 「原文」视图：清场景 + 接缝登记 + 两半登记 + loading，并停帧同步 |

**payload 关键字段**（生产者 = `BrowserSceneBuilder`）：坐标一律**相对原图百分比 0–100**；
`bubbles[]` 里排版用 `x/y/w/h`，**遮罩优先 `cover_box`**（没有才退回 polygon 外接框 / 排版框）；
`polygon` + `rotation_deg` 表达倾角，`cleaned_source_box` 指向清理图补丁，`fixed_font_percent` 是跨图续排的**字号上限**（只收不放），`stitch_overflow` 允许跨越 0–100。
接缝带额外有 `band_left/top/width/height_px`、`top_h_px`、`source_width/height_px`。

**关键阈值 / 常量**：

| 常量 | 值 | 含义 |
|---|---|---|
| `FONT_MIN / FONT_MAX / FONT_ABS_MAX` | 10 / 52 / 120 px | 字号下限 / 常规上限 / 绝对上限 |
| `FONT_BINARY_STEPS` / `FONT_SAFETY`（竖排 0.92） | 9 / 0.96 | 二分拟合次数 / 安全系数 |
| `STROKE_RATIO` | 0.085（clamp 1.8~4.2） | 描边宽 = 字号 × 0.085 |
| `SEAM_MAX_PAGE_GAP_PX` | **3 px** | 接缝带允许的两切片间隙；超过即 `page_gap` → 退回「两半各画一份」（2026-10-03 由 1.5 放宽，阅读器留 2px 边距就误判） |
| `MASK_INLINE_STYLE_SAFE_LIMIT` / `MASK_BLOB_URL_CACHE_LIMIT` | 1.5MB / 16 | 清理图 dataURL 超限转 Blob URL（LRU） |
| `RENDER_ROTATION_VERTICAL_MIN_DEG` | 60° | \|倾角\|≥60° 且框宽>框高 → 判 OCR 误判，按水平渲染 |
| 内联阈值 | 宽差 2% / 左右偏移 1.5px / 目标与气泡最小 2px / 探针溢出 +0.5px / 竖排 h/w ≥ 0.82 / 流式入场 `min(i×34, 320)ms` | `width_mismatch` / `page_offset_x` / 隐藏与字号挂起 / 溢出判据 / 竖排判定 / 淡入 |
| `z-index` | 层 2147483000、loading 2147483002 | 覆盖层压页面之上，loading 再压一层 |

**易踩点（读这个文件必须先知道的 6 条）**：

1. **两套坐标系**：payload 是百分比，CSS `rotate()` 作用在屏幕像素上 —— 倾角必须经
   `applyAspectCorrectedTilt` 换算（图片非正方形时两者不等，实测 900×1400 图上 13.07° vs 19.86°）；
   遮罩用 `clip-path` 百分比反而天然正确。
2. **几何非法时的降级方向是「不变坏」**：接缝带任一条前提（同宽 / 首尾相接 / 左右对齐）不成立 → `ok:false`，
   调用方保留「两半各画一份」。
3. **摘掉 ≠ 销毁**：`suppressBubble` 是「移出 DOM + 登记」，`dropSeam`/`invalidateSeam` 负责贴回；
   只有确认目标图内容已换（`target.src` 变化）才 `discardSeamHalves` 只丢不贴。
4. **没有 scroll/resize 监听**：靠 `requestAnimationFrame` 逐帧同步（普通层 + 接缝层 + loading 三者都空才停）。
5. **宿主页面样式会污染覆盖层**：`injectCss` 显式声明 `font-size:inherit` 等，否则站点 CSS（`div,li,a{font-size:13px}`）
   会把覆盖层压成 13px，且让溢出永远测不出来。
6. **`v` / `kind` / `surface_*` / `COVER_PADDING_*` 定义了但未生效**；`removeScenes()` 不删 `.mt-overlay-layer`
   （留到下次 `pruneEmptyLayers` 回收）；`.__mt_overlay` 这个类在渲染层里**不存在**（`REMOVE_JS` 实际靠
   `.__mt_scene_root` / `.__mt_loading_root` + `MtOverlay.removeScenes()` 生效）。

---

## 5. 测试速查（把判据当文档读）

| 测试 | 钉住什么 |
|---|---|
| `core-translate/.../OcrBlockMergeRulesTest.kt` | 合并判据：真机两气泡几何不合并、同气泡多行仍合并、字号/对齐/朝向闸、行高口径 |
| `core-translate/.../OcrBlockMergeRulesTest.kt`（同文件内） | `TextLineGeometry` 的口径：行数/行高/竖列判定 |
| `core-translate/.../GlossaryPromptTest.kt` | 术语表提示词行格式（`buildGlossaryLine`） |
| `core-translate/.../PaddleOcrBlockMergerTest.kt` | Paddle 合并顺序（横排按行、竖排右→左）+ 合并块带出行数 |
| `app/.../BubbleFirstRegionPlannerTest.kt` | 分组/归属：无区域时两气泡不合并、区域内两段拆开、上边界合并屏障、缝感知 |
| `app/.../SeamNeighbourPolicyTest.kt` | 配对闸：相邻/间隙/重叠/错位/宽度不符/远处 banner |
| `app/.../SeamBubbleMergerTest.kt` | 归属规则：cross/handed_tail/orphan_adopted/dropped_*，含真机数字回归 |
| `app/.../SeamIncomingGeometryTest.kt` | 收到侧并框与最小高度（含真机 `box=42x0` 退化框） |
| `app/.../HandoffTextMatchTest.kt` | 移交文本证据（真机两形态 0.83 / 0.54） |
| `app/.../SeamBandGeometryTest.kt` | 接缝带几何与盖板外扩 |
| `app/.../BrowserSceneBuilderTest.kt` / `BrowserScanScriptTest.kt` | Kotlin↔JS 契约 |
| `app/.../EraseTargetsTest.kt` | 「读不出就别动」判据 |
| `app/.../BubbleFontRulesTest.kt` | 译文字号规则 |
| `app/.../CaptureSession*Test.kt` / `OverlayAccessibilityPolicyTest.kt` / `BrowserTranslateUiPolicyTest.kt` | 会话/无障碍/底栏状态表 |
| `app/src/test/js/mt-overlay-acceptance.test.js` | 渲染层验收（node + `dom-stub.js`，含接缝/恢复/加载卡） |
| `core-store/...`（术语表/历史/浏览器数据） | 持久化与归一化 |
| `core-overlay/...`（背景描边/取色/写出区域/清理图墨迹） | 擦字与配色判据 |
| `app/src/androidTest/**`（探针） | 真机：ML Kit 置信度/语言混排、Paddle 复杂背景/斜排竖排/性能 |

跑测试：

```powershell
./gradlew test                                  # 全部模块的 JVM 单测
./gradlew :core-translate:test                  # 只跑 OCR/翻译模块
./gradlew :app:testDebugUnitTest                # 只跑 app 模块
node app/src/test/js/mt-overlay-acceptance.test.js   # 渲染层（node）
```

---

## 6. 不用读的（构建产物 / 本地产物）

- 构建产物：`build/`、`*/build/`（含 `app/build/outputs/apk`）、`.gradle/`、`.gradle-home/`、`.cxx/`
- 本地产物（已 gitignore）：`.idea/`、`.claude/`、`.codex/`、`.workbuddy/`、`.verify/`、`logs/`、`*.hprof`、`window_dump.xml`、顶层 `verify_*.png` / `screen_*.png` / `app-icon-*.png`、`local.properties`

---

## 7. 已确认的死代码 / 可疑项（读代码时的坑）

| 位置 | 情况 | 说明 |
|---|---|---|
| `core-translate/OpenAiVisionClient.kt` | `detectBubbles` / `detectTextBlocks` / `groupTextBlocks`（含 `*WithBaidu` 分支）**无调用方** | 远程视觉 OCR 路径已下线（`OcrEngine` 只剩 ML Kit / Paddle）；保留会让人误以为还有远程 OCR。**注意该类没全死**：`translateTexts` / `fetchAvailableModels` 仍被 `PageTranslationResolver` / `HomeScreen` 使用 |
| `core-store/AiProvider.kt`（原 `BAIDU`） | 枚举项 `BAIDU`（label「百度OCR」，`aip.baidubce.com`）**已于 2026-10-04 删除** | 它是上面那条已下线的百度 OCR 路径的厂商入口，`HomeScreen` 早已用 `.filter { it != AiProvider.BAIDU }` 把它藏起来。删枚举项时**必须同时删掉那条 filter**（否则不编译）。现在配置档案里的「厂商」下拉就是 `AiProvider.entries` 本身 |
| `core-translate/BubbleGeometry.kt` | `BubbleMergeHeuristics` **只被上面那条死路径调用**（+ 单测） | 本地分组的判据已换成 `OcrBlockMergeRules` |
| `core-translate/TextLayoutMetrics.kt` | `expandContentRect` **无调用方**（`WebtoonImageTranslator` 只在注释里提到它） | 排版框外扩已改成在像素空间算（`growTowardOuter`） |
| `core-store/AppSettings.kt` + `OcrMode.kt` | `ocrMode` 字段**无人读取**，`SettingsRepository` 也没有对应 prefs 键 | 随远程 OCR 下线而闲置的遗留配置 |
| `core-store/AppSettings.kt` + `BaiduOcrLanguage.kt` | `baiduOcrLanguage` 只被**已下线的百度 OCR 路径**读，且不持久化 | 运行时恒为默认值 `JAP` |
| `app/FrameHash.kt` | `hammingDistance` **无调用方** | `centerDHash` 仍在用（`baselineHash`） |
| `app/ui/MtUi.kt` | `MtOutlineButton` / `MtTextButton` **无调用方** | 纯 UI 组件 |
| `app/ui/theme/Theme.kt` | `MtShapes`（五档圆角令牌 6/8/14/18/22dp）**无消费方** | 全仓无人读 `MaterialTheme.shapes`；各屏幕直接硬写 `RoundedCornerShape(x.dp)`（`MtUi.kt`、`GlossaryScreen.kt` 等）—— 想收口就把硬写换成 `MaterialTheme.shapes.*` |
| `core-overlay/EraseStrategy.kt` | `RenderQualityProfile` 枚举 **无读取方** | 连同 `OverlayRenderConfig.qualityProfile` 一起是死代码 |
| `core-overlay/OverlayRenderConfig.kt` | `qualityProfile` / `erasePolicy` **只写不读** | 配置载体本身仍在用（`denseMode`/`touchEnabled` 等被读） |
| `app/SeamBubbleMerger.kt` | `SeamConfig.minVisibleFraction` 字段**不再参与判定** | 2026-10-03 由「整块是否被 incoming 覆盖」替代，字段保留仅为记录意图 |
| `core-translate/TextLineGeometry.kt` | `minLineHeight` / `maxLineHeight` **无调用方** | `OcrBlockMergeRules` 用的是自己的 frame-aware 版本 |
| `mt-overlay-renderer.js` | `applyScene` / `applySeamScene`（非 Json 版）**无调用方**；`applyCleanedImage(s)` / `pendingCleanedImageIds` / `updateBubbleCover` / `seamSceneState` / `removeSeamScene` / `restoreSeamHalves` 只有 Node 验收测试在用 | Kotlin 真正调的只有那 7 个出口（见 §4.7）；`updateBubbleCover` 在 `Todolist.md` 里记为「可选增强，非功能缺口」 |
| `mt-overlay-renderer.js` | `v` / `kind` / `surface_*` / `COVER_PADDING_*` / `LAYER_TYPES.DEBUG|LOADING` **定义了但未生效**；`.__mt_overlay` 类**不存在** | `REMOVE_JS` 实际靠 `.__mt_scene_root` / `.__mt_loading_root` + `MtOverlay.removeScenes()` 生效 |

> 另外：`docs/PROJECT_STRUCTURE.md` 里还提到两个已不存在的文件（`OcrCacheRepository.kt`、`docs/LOCAL_DEBUG_GUIDE.md`），
> 且根路径写的是旧机器路径 —— 那份文档下次维护时顺手修。

---

## 8. 常用命令

```powershell
./gradlew :app:assembleDebug            # 构建 debug APK
./gradlew :app:installDebug             # 装到已连接设备
./gradlew test                          # 全部单测
adb logcat -s TranslationPerf:V EmbeddedBrowser:V     # 只看翻译链路日志
node scripts/serve-verify.mjs .verify/web 8899        # 本地验证页服务（配合 adb reverse）
python scripts/verify-browser-page.py --url http://127.0.0.1:8899/<页>.html `
    --out .verify/xx.png --logcat .verify/xx.logcat --settle 35
```
