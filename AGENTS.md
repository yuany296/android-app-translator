# AGENTS

## 项目介绍

一个漫画翻译软件。

## 1. 目标

- 持续推进项目可交付能力，优先完成 `TodoList.md` 中未完成事项。

## 2. 默认执行规则（长期生效）

- 当用户提出“继续完成 TodoList / Todolist / 待办”时：
- 自动继续实现 `TodoList.md` 中未完成项，直到当前轮次可落地部分完成。
- 所有需要人工处理的事项（检查软件效果等）统一写入 `HumanTODO.md`。
- `HumanTODO.md` 不存在则创建，存在则追加，不覆盖已有条目。

## 3. 项目补充说明

项目结构记录在docs\PROJECT_STRUCTURE.md

- 运行形态：前台服务 + 悬浮球 + 覆盖层。
- 主链路：截图 -> OCR -> 翻译 -> 覆写渲染 -> 翻页清理。
- 模块职责：
- `app`：服务编排、权限与状态流转。
- `core-capture`：截图采集与恢复。
- `core-translate`：OCR/翻译请求与结果归一。
- `core-overlay`：悬浮层、布局与渲染。
- `core-store`：配置、缓存与本地持久化。

## 4. 排障规则（长期生效）

- 遇到异常、超时、错位或行为不符合预期时，先补充最小必要日志，再进行修复。
- 日志应覆盖关键时序与关键参数（如输入、输出、坐标换算、状态切换、错误类型），并使用稳定 Tag 便于过滤。
- 完成修复前必须基于日志做一次检错闭环：复现 -> 采集日志 -> 定位 -> 修复 -> 复测 -> 清理临时日志（保留必要诊断日志）。

## 5. 真机设备测试安全（长期生效）

- **禁止**直接运行 `:app:connectedDebugAndroidTest`：任务结束 Gradle/AGP 会自动卸载主 app，
  连同 `files/`、`shared_prefs/` 一起清空；该卸载走 ddmlib，`permission.bash` 的
  `adb uninstall` deny 规则拦不住。
- 根 `build.gradle.kts` 已加闸门：`connected*AndroidTest` 会在任务图阶段直接失败；
  只有显式 `-PALLOW_CONNECTED_ANDROID_TEST=true` 且先备份时才允许运行。
- 跑设备测试统一用 `scripts\run_device_test_safe.bat`（先备份、`install -r`、`am instrument`，
  不卸载主 app）；详见 `docs/LOCAL_DEBUG_GUIDE.md`。
- **禁止**卸载 `com.manga.translator.app`；只有测试包 `com.manga.translator.app.test` 可以单独卸载。
- 任何可能重装/卸载/清数据的操作前，先运行 `scripts\backup_device_data.bat`。

## Agent skills

### Issue tracker

Issues and specs live as markdown under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context: `GLOSSARY.md` at the repo root and ADRs in `docs/adr/`. See `docs/agents/domain.md`.
