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
