# Testing

- 日期：2026-03-25
- 执行者：Codex

## 构建测试

1. ./gradlew.bat :app:assembleDebug
- 结果：成功

2. scripts/build_debug.bat
- 结果：成功
- APK：C:\homework\AI_work\translator\android-app\app\build\outputs\apk\debug\app-debug.apk

## 安装测试

1. scripts/install_debug.bat
- 首次结果：失败（INSTALL_FAILED_ABORTED: User rejected permissions）

2. db install -r -g app\\build\\outputs\\apk\\debug\\app-debug.apk
- 结果：成功

## 回归脚本

1. scripts/run_regression_smoke.bat
- 结果：成功
- 日志：C:\homework\AI_work\translator\android-app\logs\regression-smoke-20260325-130302.txt
"@ | Set-Content -Encoding utf8 .codex/testing.md

@"
# Verification

- 日期：2026-03-25
- 执行者：Codex

## 已验证

- OCR/翻译 API 双配置已接入设置持久化与配置页。
- 编排链路已拆分为：截图/压缩 -> OCR -> 批量翻译 -> 合并渲染。
- OCR 成功但翻译失败时，已降级显示原文并给出提示。
- 覆写渲染与分级擦除链路可编译通过。
- Todolist.md/TodoList.md 所有未勾选项均带“待你确认”说明。
- 设备连线后已完成 APK 安装与回归脚本执行。

## 待你侧确认（实机）

- 悬浮球交互体感、覆写观感、翻页清理体感、调整模式体感。

## 2026-03-26 本轮补充验证

- 已完成静态编译验证：`.\gradlew.bat :app:compileDebugKotlin` 成功。
- 已完成代码级核对：无障碍服务声明、事件总线、截图状态字段、一次性页面校验、refined content rect 排版入口均已接入。
- 未完成实机验证：截图超时是否显著下降、Home/切应用是否即时清层、翻页后 overlay 是否及时消失、solid 气泡定位是否更贴合原文区域。
