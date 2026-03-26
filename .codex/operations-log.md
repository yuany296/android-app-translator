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
