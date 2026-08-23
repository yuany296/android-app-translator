# Operations Log

- 日期：2026-03-25
- 执行者：Codex

## 关键操作留痕

1. 读取并核对 Todolist.md / TodoList.md 状态。
2. 修复 OCR/翻译拆分后的编译问题：
- 修复 OpenAiVisionClient.kt 非法转义字符。
- 修复 MainActivity.kt 中 @Composable 调用上下文错误。
3. 扩展链路埋点与失败分支：
- equest_send / esponse_ok / esponse_fail
- capture_failure_type
- skipped=projection_unavailable
- skipped=capture_failed_before_request
- capture_unavailable_enter|exit
4. 新增本地脚本集：
- scripts/check_device.bat
- scripts/build_debug.bat
- scripts/install_debug.bat
- scripts/reinstall_debug.bat
- scripts/capture_logcat.bat
- scripts/run_regression_smoke.bat
5. 新增文档：
- docs/LOCAL_DEBUG_GUIDE.md
- HumanTODO.md
- 重写 AGENTS.md（修复乱码并补充项目简介）。
6. 批量治理 Todolist 未勾选项：统一补充“待你确认”语义，确保无“纯未开始项”。

## 阻断记录

- 安装阶段未检测到设备：db devices 返回空列表，install_debug.bat 失败（无设备）。

## 2026-03-26 截图稳定性与覆写定位收口

1. 读取并核对以下实现落点：
- `app/src/main/java/com/manga/translator/app/CaptureForegroundService.kt`
- `core-capture/src/main/java/com/manga/translator/capture/ScreenCaptureController.kt`
- `app/src/main/AndroidManifest.xml`
- `core-overlay/src/main/java/com/manga/translator/overlay/OverlayController.kt`
- `app/src/main/java/com/manga/translator/app/TranslateOrchestrator.kt`

2. 截图链路调整：
- 为 `ScreenCaptureController` 增加 `projectionStopped`、`lastCaptureSuccessAt`、`lastCaptureFailureType`。
- 将 `isCaptureAlive()` 语义收紧为“对象仍可用”，不再以后台抢帧推断授权失效。
- 移除页面监测中的持续抓图轮询入口，保留手动触发截图优先。

3. 清层链路调整：
- 新增 `OverlayAccessibilityService` 与 `AccessibilityMonitor`，监听窗口切换、滚动与内容变化。
- `CaptureForegroundService` 改为基于无障碍事件执行 `home` / `package_changed` / `page_changed` 清层。
- `ACTION_CLOSE_SYSTEM_DIALOGS` 与 `onTaskRemoved()` 降级为兜底清层，不再主导 unavailable 状态。

4. 页面变化校验：
- 将持续 dHash 轮询改为“事件触发 + settle 后一次校验 + 可选一次复核”。
- 新增 `page_verify_start`、`page_verify_result`、`overlay_clear`、`capture_monitor_event` 留痕。

5. 定位与排版调整：
- 新增 `core-overlay/.../WritableAreaRefiner.kt`。
- `TranslateOrchestrator` 先把 OCR 框精修为 `contentRect`，再映射到 Overlay。
- `OverlayController` 改为优先使用 refined rect 排版，并收紧字号、行高、竖排步进与贴片 padding。

6. 本地验证：
- 执行 `.\gradlew.bat :app:compileDebugKotlin`
- 结果：成功

## 2026-03-26 截图超时优先修复 + 点击恢复交互

1. 代码实现
- `core-capture/ScreenCaptureController.kt`：
  - 新增最近帧缓存（1.2s）回退；
  - 新增 trace 级别分段日志（`wait_frame` / `use_cached_frame` / `hard_recreate` / `capture_timeout_final`）；
  - `captureBitmapWithRecovery` 改为超时后硬重建管线再重试。
- `core-overlay/OverlayController.kt`：
  - 新增结果锁定点击层（点击任意翻译层区域可解锁回调）；
  - 浏览模式触摸穿透判断加入 `translationLocked`。
- `app/CaptureForegroundService.kt`：
  - 翻译成功后进入结果锁定态；
  - 点击翻译层后执行 `clearOverlayForReason("tap_restore")` 恢复；
  - 失败分支与新一轮开始前主动解锁，避免状态残留。
- `app/TranslateOrchestrator.kt` + `TranslationTimer.kt`：
  - 截图调用透传统一 `traceId`，串联全链路日志。
- `scripts/capture_logcat.bat`：
  - 导出原始日志后自动生成 summary（超时/缓存命中/硬重建/P95）。

2. 工具与命令留痕
- `adb devices -l`：确认设备在线。
- `.\gradlew.bat :app:compileDebugKotlin`：构建成功。
- `scripts/run_regression_smoke.bat`：执行成功。
- `scripts/capture_logcat.bat`：执行成功并产出 summary。

3. 输出文件
- `logs/regression-smoke-20260326-234156.txt`
- `logs/translator-logcat-20260326-234156.txt`
- `logs/translator-logcat-20260326-234156-summary.txt`
- `logs/translator-logcat-20260326-234658.txt`
- `logs/translator-logcat-20260326-234658-summary.txt`

4. 脚本稳定性修复
- 修复 `scripts/_env.bat`：使用 `where adb` 解析到真实可执行路径，避免 `ADB_BIN=adb` 时被 `if exist` 误判。
- 修复 `scripts/capture_logcat.bat` summary 写入：改为单次字符串换行写入，确保汇总文件按5行输出。

## 2026-03-26 本地OCR体验修复（小字 + 竖排断裂）

1. `TranslateOrchestrator.groupLocalBlocks` 重构：
- 新增本地几何分组（按块间距/重叠/纵横排特征聚类）；
- 远程分组结果过碎时自动回退本地分组；
- 大块数场景直接使用本地分组，避免慢分组路径。

2. 气泡尺寸与文本拼接优化：
- 本地气泡生成加入边距扩展（提升可读性，减轻“小字”）；
- 竖排文本按右到左、上到下顺序拼接，减少同气泡断裂。

3. 本地验证：
- `.\gradlew.bat :app:compileDebugKotlin` 成功；
- `scripts/run_regression_smoke.bat` 成功；
- 日志：`logs/regression-smoke-20260326-235644.txt`。

## 2026-03-27 百度 OCR 竖排气泡合并修复

1. 上下文与工具留痕
- 先调用 `sequential-thinking` 做任务理解、风险识别与实施规划。
- 当前工具集中未提供 `code-index` 与 `shrimp-task-manager`，改用 PowerShell 检索与 `update_plan` 维护计划。
- `rg.exe` 在当前 PowerShell 环境下返回 `Access is denied`，已降级为 `Get-ChildItem` + `Select-String`。
- 新增本地上下文文件：
  - `.codex/structured-request.json`
  - `.codex/context-scan.json`
  - `.codex/context-question-1.json`

2. 代码实现
- `core-translate/src/main/java/com/manga/translator/translate/OpenAiVisionClient.kt`
  - 放宽竖排布局不匹配时的硬拒绝条件，允许强竖排候选继续进入竖排合并判定。
  - 调整 `nearVerticalColumns` / `nearVerticalStack` 阈值，降低多列竖排与上下堆叠的拒绝率。
  - 合并失败时新增 `baidu_merge_reject` 详细日志，记录拒绝原因与关键几何参数。
  - 修复 `mergeBaiduBubbleGroup()` 在多列竖排合并后误判 horizontal 的问题。
- `core-translate/build.gradle.kts`
  - 增加 `testImplementation("junit:junit:4.13.2")`。
- `core-translate/src/test/java/com/manga/translator/translate/OpenAiVisionClientTest.kt`
  - 新增 4 个单元测试，覆盖强竖排布局不匹配、竖排近列阈值、竖排堆叠阈值与合并后 layout 保持 vertical。

3. 验证结果
- `.\gradlew.bat :core-translate:test`：成功。
- `.\gradlew.bat :core-translate:lint`：成功。
- `.\gradlew.bat lint`：失败，首个阻断为 `app/src/main/AndroidManifest.xml` 的 `Instantiatable` 既有错误；`core-translate` 未出现新增 lint 阻断。

4. 人工补充验证
- 已将“百度 OCR 竖排截图 + logcat 观察 `baidu_merge_stats` / `baidu_merge_reject`”追加到 `HumanTODO.md`，供设备侧继续确认真实效果。

## 2026-03-27 百度 OCR 二阶段本地近邻合并补漏

1. 实现调整
- `core-translate/src/main/java/com/manga/translator/translate/OpenAiVisionClient.kt`
  - 在原有 `mergeNearbyBaiduBubbles()` 之后，新增第二阶段 `mergeNearbyBaiduBubblesHeuristically()`。
  - 第二阶段规则复用本地 OCR 几何分组思路：按相邻框 gap、重叠率、扩张比与中心距离做更宽松的本地近邻聚类。
  - `baidu_merge_stats` 新增 `nearby` 字段，区分第一阶段严格合并与第二阶段补漏后的 `merged`。
  - `baidu_merge_reject` 改为安全日志调用，避免 JVM 单测环境因 `Log.v` 未 mock 而失败。

2. 测试与安装
- `core-translate/src/test/java/com/manga/translator/translate/OpenAiVisionClientTest.kt`
  - 新增“严格规则失败但 heuristic 规则成功”的单测场景。
- `.\gradlew.bat :core-translate:test`：成功。
- `.\gradlew.bat :core-translate:lint`：成功。
- `.\gradlew.bat :app:assembleDebug`：成功。
- `scripts/install_debug.bat`：成功，已重新安装到设备 `AN3GUT4A22006400`。

## 2026-03-27 翻译锁定期间禁止翻页

1. 代码实现
- `core-overlay/OverlayController.kt`：
  - 保留现有 `lockTranslationUntilTap` / `unlockTranslationLayer` 入口；
  - 将锁定层升级为手势拦截层，整段触摸序列统一吞掉；
  - 仅“位移不超过 touchSlop 且时长不超过 tapTimeout”时解锁；
  - 新增 `isTranslationLocked()` 供服务层判断；
  - 补充 `lock_tap_release` / `lock_gesture_blocked` 日志。
- `core-overlay/TranslationLockGesturePolicy.kt`：
  - 新增纯逻辑手势判定器，便于 JVM 单测覆盖“点击释放/滑动阻断”。
- `app/OverlayAccessibilityPolicy.kt`：
  - 新增无障碍事件清层决策器，统一判定 `home` / `package_changed` / `page_changed` / `locked_content_change`。
- `app/CaptureForegroundService.kt`：
  - 锁定期间同包 `content_change` 不再触发 `page_changed` 清层；
  - 仍保留 `home`、切应用、任务移除、投屏异常等强制清层路径；
  - 补充 `overlay_clear_skip reason=locked_content_change` 留痕。

2. 测试补充
- `core-overlay/src/test/.../TranslationLockGesturePolicyTest.kt`
- `app/src/test/.../OverlayAccessibilityPolicyTest.kt`
- `app/build.gradle.kts`、`core-overlay/build.gradle.kts` 增加 JUnit 依赖。

3. 工具与命令留痕
- `.\gradlew.bat :core-overlay:testDebugUnitTest`：成功。
- `.\gradlew.bat :app:testDebugUnitTest`：首次受 Gradle/Kotlin 本地缓存影响，改用禁用配置缓存与增量编译后成功。
- `.\gradlew.bat :app:compileDebugKotlin`：改用 `--no-configuration-cache` 与 `-Pkotlin.incremental=false` 后成功。

4. 当前遗留
- 终端未执行真机翻页冒烟；仍需设备侧确认“翻译显示时滑动不翻页、点击后恢复翻页”。

## 2026-03-27 全量切换为 bubble-first OCR 主链路

1. 代码实现
- `core-translate/src/main/java/com/manga/translator/translate/Models.kt`
  - 新增 `BubbleRegionType`、`BubbleRegion`、`RegionOcrResult`，统一“区域检测 -> 区域 OCR -> 结果组装”的中间模型。
- `core-overlay/src/main/java/com/manga/translator/overlay/BubbleRegionDetector.kt`
  - 新增基于 OpenCV 亮区轮廓的气泡区域检测器，输出稳定 `bubble` 包围框。
- `app/src/main/java/com/manga/translator/app/BubbleFirstRegionPlanner.kt`
  - 新增 bubble-first 区域规划器，负责区域归属、outside 分流、单区域 bubble 组装、远程裁框结果回填。
- `app/src/main/java/com/manga/translator/app/TranslateOrchestrator.kt`
  - 所有 OCR 模式统一改为先执行 `bubble_region_detect`，再按区域构建最终 bubble；
  - 本地 OCR 与百度 OCR 直接消费区域内文字块；
  - 其他远程 OCR 改为按区域裁图请求，只有区域过多时才回退到本地结构块兜底；
  - 新增 `bubble_region_stats`、`bubble_region_assignment`、`outside_region_stats`、`bubble_region_ocr_stats`、`bubble_first_result_stats` 留痕；
  - 缓存 key 增加 `bubble_first_v1` 版本标记，避免复用旧整页 OCR 缓存。
- `core-translate/src/main/java/com/manga/translator/translate/OpenAiVisionClient.kt`
  - 新增 `detectTextBlocks()`；
  - 百度 OCR 新增单独的 `words_result -> TextBlock` 解析链路；
  - 保留 `detectBubbles()` 兼容路径，供裁框远程 OCR 和过渡期逻辑兜底。
- `app/src/main/java/com/manga/translator/app/ServiceLocator.kt`
  - 注入 `BubbleRegionDetector` 供编排层统一调用。

2. 测试与设备动作
- `app/src/test/java/com/manga/translator/app/BubbleFirstRegionPlannerTest.kt`
  - 新增 bubble-inside / outside 分流单测。
- `.\gradlew.bat :app:testDebugUnitTest --console=plain`：成功。
- `.\gradlew.bat :core-translate:test --console=plain`：成功。
- `.\gradlew.bat :app:assembleDebug --console=plain`：成功。
- `adb -s AN3GUT4A22006400 install -r app\\build\\outputs\\apk\\debug\\app-debug.apk`：成功。
- `adb -s AN3GUT4A22006400 shell am start -n com.manga.translator.app/.MainActivity`：成功。

3. 当前遗留
- 新增的 `BubbleRegionDetector` 目前只输出 `bubble` 区域，`outside` 仍由未匹配文本块在本地几何分组后生成，属于第一版实现取舍。
- 真实页面效果仍需设备侧验证，重点观察同一对白气泡是否收敛成单框，以及页边旁白是否以 `bgType=none` 独立显示。

## 2026-03-27 气泡内容区、共享并框与录屏会话门禁修复

1. 关键实现
- `core-translate/src/main/java/com/manga/translator/translate/Models.kt`
  - `Bubble` 新增 `contentX/contentY/contentW/contentH`，作为覆写可写区权威来源。
- `core-translate/src/main/java/com/manga/translator/translate/BubbleGeometry.kt`
  - 新增共享几何工具与 `BubbleMergeHeuristics`，统一本地 OCR、bubble-first、百度合并的基础判定。
- `app/src/main/java/com/manga/translator/app/TranslateOrchestrator.kt`
  - 新增截图级空白沟槽检测器；
  - bubble-first、本地 OCR fallback、区域本地回退统一接入共享并框规则；
  - 覆写链路改为“外框负责展示位置，内容区负责擦除与排版”，并把缓存 key 升级到 `bubble_first_v2`。
- `core-translate/src/main/java/com/manga/translator/translate/OpenAiVisionClient.kt`
  - 百度/远程链路开始消费和合并 `content` 坐标；
  - 百度严格合并与启发式合并统一复用共享几何规则。
- `core-overlay/src/main/java/com/manga/translator/overlay/erase/EraseComplexityAnalyzer.kt`
  - patch ROI 从气泡外框切到内容区。
- `core-overlay/src/main/java/com/manga/translator/overlay/OverlayController.kt`
  - patch 绘制改为覆盖完整内容区，不再裁到最终文字背景框。
- `app/src/main/java/com/manga/translator/app/MainActivity.kt`
  - 启动按钮改为先读取 `CaptureSessionState`，`ACTIVE/UNAVAILABLE` 不再重复请求录屏授权。
- `app/src/main/java/com/manga/translator/app/CaptureForegroundService.kt`
  - 新增会话状态同步；在启动成功、恢复、不可用、销毁时写入 `CaptureSessionStateStore`。
- `core-capture/src/main/java/com/manga/translator/capture/ScreenCaptureController.kt`
  - 新增 `projectionStopListener`，区分外部失效与内部主动停止。

2. 工具与验证留痕
- 工具：`apply_patch`
  - 用途：分批修改 `TranslateOrchestrator`、百度 OCR、overlay 擦除、录屏会话状态与测试文件。
- 工具：`.\gradlew.bat :app:testDebugUnitTest :core-translate:testDebugUnitTest :core-overlay:testDebugUnitTest --console=plain`
  - 首轮结果：失败；定位到 `TieredEraseProcessor` 缓存 key 拼接和旧百度测试假设不再成立。
  - 修复后结果：成功。
- 工具：`.\gradlew.bat :app:assembleDebug --console=plain`
  - 结果：成功。

3. 当前遗留
- 本轮未做真机截图复现，仍需设备侧确认百度 OCR 与本地 OCR 的相邻气泡误并框是否明显下降。
- `CaptureSessionState` 目前以 SharedPreferences 持久化，若后续需要更细粒度状态（如 pending）再扩展。
