# 项目结构说明

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
4. `core-capture/src/main/java/com/manga/translator/capture/ScreenCaptureController.kt`
   - 管理 `MediaProjection`、`VirtualDisplay`、`ImageReader` 与截图恢复逻辑，并输出位图或 JPEG Data URL。
5. `core-translate/src/main/java/com/manga/translator/translate/OpenAiVisionClient.kt`
   - 负责 OCR 检测与文本翻译两条请求链路，以及 OpenAI 兼容返回结果的解析。
6. `core-overlay/src/main/java/com/manga/translator/overlay/OverlayController.kt`
   - 负责悬浮球、状态浮层、翻译框、擦字贴片与浏览/调整态的最终渲染控制。
7. `core-store/src/main/java/com/manga/translator/store/SettingsRepository.kt`
   - 负责配置、渠道档案和开关项的加密持久化，是本地配置读写入口。

补充说明：

- `app/src/main/java/com/manga/translator/app/ServiceLocator.kt` 负责组装 `SettingsRepository`、`GlossaryRepository`、`OcrCacheRepository`、`TranslationHistoryRepository`、`PageOverlayAdjustmentRepository`、`OpenAiVisionClient`、`OverlayController`、`ScreenCaptureController` 与 `BubbleColorExtractor`。
- `core-translate/src/main/java/com/manga/translator/translate/Models.kt` 定义 `Bubble` 与 `TranslationResult`，作为翻译结果在模块间传递的核心数据结构。
- `core-overlay/src/main/java/com/manga/translator/overlay/BubbleColorExtractor.kt` 对每个气泡从截图像素采样背景色与文字色，`OverlayController` 渲染时替换固定颜色。
- 主链路缓存策略：OCR 结果走 `OcrCacheRepository`（key = 图像指纹 + OCR 配置指纹，2h TTL）；译文走 `TranslationHistoryRepository`（key = 原文序列 + 翻译配置指纹 + 术语表 revision，永久保存），翻译成功后写入历史库。

## 6. 维护约定

- 新增、删除或重命名 Gradle 模块时，必须同步更新本文件的“项目概览”“模块职责”“模块依赖关系”与“关键目录树”。
- 关键入口文件发生迁移时，必须同步更新“主链路入口”一节，避免文档路径失效。
- 新增长期维护的稳定目录时，可补充到“关键目录树”；`build/`、缓存、中间产物目录不应写入本文件。
- `README.md` 继续承担运行说明与上手指引，本文件专注工程结构说明，避免两份文档职责重叠。
