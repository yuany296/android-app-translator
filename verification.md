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

## 2026-03-26 截图超时与点击恢复修复验证

- 执行者：Codex
- 构建验证：`.\gradlew.bat :app:compileDebugKotlin` 成功。
- 冒烟验证：`scripts/run_regression_smoke.bat` 成功，应用可重装并启动。
- 日志采集验证：`scripts/capture_logcat.bat` 成功，产出原始日志和汇总日志。
- 日志样本：`logs/translator-logcat-20260326-234658-summary.txt`（当前样本未触发翻译流程，因此统计为0/NA，属于预期）。
- 当前风险：尚未完成你要求的“连续30次触发翻译”实机稳定性统计，`capture_timeout_final/use_cached_frame/hard_recreate` 指标需在该场景下复测。

## 2026-03-26 本地OCR分组可读性修复补充

- 已实现：本地几何分组、碎片化远程分组回退、本地气泡扩边、竖排拼接顺序优化。
- 构建验证：`.\gradlew.bat :app:compileDebugKotlin` 成功。
- 回归脚本：`scripts/run_regression_smoke.bat` 成功。
- 待你实机确认：本地OCR下文字是否明显变大、竖排日文是否在同一气泡中连续显示。

## 2026-03-27 百度 OCR 竖排气泡合并修复验证

- 已完成单元测试：`.\gradlew.bat :core-translate:test` 成功。
- 已完成模块 lint：`.\gradlew.bat :core-translate:lint` 成功。
- 已尝试仓库级 lint：`.\gradlew.bat lint` 失败，阻断来自 `app/src/main/AndroidManifest.xml` 的既有 `Instantiatable` 错误，不属于本次 `core-translate` 改动。
- 已新增诊断日志：`baidu_merge_reject`，用于定位未合并样本的拒绝原因与关键几何参数。
- 待你实机确认：百度 OCR 竖排日漫截图下，`baidu_merge_stats` 中 `merged` 是否小于 `raw`，以及同气泡多列竖排文字是否已合并为单个覆盖框。

## 2026-03-27 翻译锁定禁止翻页补充

- 已实现：翻译显示期间锁定层吞掉滑动/长按/多指手势，仅轻触覆盖层才会清除翻译并恢复翻页。
- 已实现：锁定期间忽略同包 `page_changed/window_content_changed` 自动清层；`home`、切应用、任务移除、投屏异常仍按原逻辑清层。
- 单元测试：
  - `:core-overlay:testDebugUnitTest` 成功；
  - `:app:testDebugUnitTest` 成功。
- 构建验证：`.\gradlew.bat --no-configuration-cache -Pkotlin.incremental=false :app:compileDebugKotlin` 成功。
- 待你实机确认：
  - 翻译显示时左右滑动不会翻页；
  - 轻点翻译层后翻译立即消失；
  - 消失后再次滑动可正常翻页。

## 2026-03-27 bubble-first OCR 主链路验证

- 已实现：所有 OCR 模式统一改为“先检测气泡框，再按区域 OCR，再翻译/渲染”。
- 已实现：未落入气泡区域的文字不再并入正文气泡，而是作为 `outside` 独立 bubble 渲染，默认 `bgType=none`。
- 已完成自动验证：
  - `.\gradlew.bat :app:testDebugUnitTest --console=plain` 成功；
  - `.\gradlew.bat :core-translate:test --console=plain` 成功；
  - `.\gradlew.bat :app:assembleDebug --console=plain` 成功。
- 已确认 lint 现状：
  - `.\gradlew.bat :app:lintDebug --console=plain` 失败；
  - 首个错误仍是 `app/src/main/AndroidManifest.xml:10` 的既有 `Instantiatable` 问题，不属于本轮 bubble-first 改动新增。
- 已完成设备动作：
  - `adb -s AN3GUT4A22006400 install -r app\\build\\outputs\\apk\\debug\\app-debug.apk` 成功；
  - `adb -s AN3GUT4A22006400 shell am start -n com.manga.translator.app/.MainActivity` 成功。
- 待你实机确认：
  - 同一对白气泡内多列竖排文字是否明显更容易收敛为单个覆盖框；
  - 页边旁白/拟声是否保持独立显示，而不是吞进最近对白气泡；
  - logcat 中 `bubble_region_stats`、`bubble_region_assignment`、`outside_region_stats`、`bubble_first_result_stats` 是否与页面观感一致。

## 2026-03-27 气泡内容区贴合、共享并框与录屏授权门禁

- 已实现：
  - `Bubble` 新增内容区坐标，覆写/擦除优先围绕内容区工作；
  - 本地 OCR、bubble-first 拆分、百度 OCR 合并统一复用共享几何规则；
  - `MainActivity` 启动服务前先读取 `CaptureSessionState`，`ACTIVE` 时不再重复弹录屏授权；
  - `CaptureForegroundService` 与 `ScreenCaptureController` 会记录 `ACTIVE / UNAVAILABLE / REAUTH_REQUIRED / STOPPED` 状态变迁。
- 已完成自动验证：
  - `.\gradlew.bat :app:testDebugUnitTest :core-translate:testDebugUnitTest :core-overlay:testDebugUnitTest --console=plain` 成功；
  - `.\gradlew.bat :app:assembleDebug --console=plain` 成功。
- 待你实机确认：
  - 百度 OCR 与本地 OCR 下，相邻对白气泡是否不再被并成一个大框；
  - 覆写后原文是否不再残留在译文框四周；
  - 服务已运行时再次点击“启动服务”是否不再弹出录屏授权；
  - 真正失效后是否只在 `REAUTH_REQUIRED` 路径下重新请求授权，并能在日志中看到明确状态切换。

## 2026-08-23 Glossary Refactor 完成验证

- 已实现：
  - `GlossaryRepository` 支持 active 集合全空、`uniqueCollectionName` 纯函数、`count()` 查询；多集合合并按 active 顺序去重优先。
  - `SwipeRevealRow` 自定义右划露出删除按钮（绕过 foundation 1.6.8 `AnchoredDraggableState` 编译问题）。
  - `GlossaryCollectionsScreen`：集合列表 + Switch 生效控制 + 右划删除 + 新建/导入。
  - `GlossaryCollectionScreen`：集合详情 + 点击原文/译文内联编辑 + 搜索 + 添加条目 + 右划删除；已移除未使用的编辑图标。
  - `MainActivity`：二级导航（列表/详情）、详情页隐藏底栏、返回键回到列表、导入命名对话框、新建集合与导入集合均自动跳转详情页。
- 已完成自动验证：
  - 新增单元测试 `GlossaryUniqueNameTest`：`:core-store:testDebugUnitTest` 成功；
  - `.\gradlew.bat :app:assembleDebug --console=plain` 成功；
  - `.\gradlew.bat :app:installDebug --console=plain` 成功（`emulator-5554`、`MAA-AN10`）。
- 待你实机确认：
  - 集合列表右划删除手感；
  - 详情页点击原文/译文内联编辑与保存；
  - 新建集合/导入 JSON 后是否自动进入详情页；
  - active 集合全空时翻译请求不含 glossary 行。

## 2026-08-23 Glossary CSV + 左滑删除完成验证

- 已实现：
  - `GlossaryRepository` 新增 `importCsv()` / `exportCsv()`，内部 `GlossaryCsv` 解析器支持表头识别、引号/逗号/换行转义。
  - `SwipeRevealRow` 改为**左滑**：向右拖动露出右侧红色垃圾桶删除按钮（微信聊天列表式）。
  - `MainActivity` 导入 launcher 支持 JSON/CSV 自动识别（按扩展名），导出分别提供 JSON/CSV 两个按钮。
- 已完成自动验证：
  - 新增单元测试 `GlossaryCsvTest`：`:core-store:testDebugUnitTest` 成功；
  - `.\gradlew.bat :app:assembleDebug --console=plain` 成功；
  - `.\gradlew.bat :app:installDebug --console=plain` 成功。
- 待你实机确认：
  - 集合列表/详情左滑删除手感；
  - 导入 CSV 后条目正确；
  - 导出 CSV 后在 Excel/记事本中打开格式正确。
