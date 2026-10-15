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

## 2026-09-15 质量基线（阶段 0：取本次事实）

- 执行者：科拉（Android/Kotlin 质量工程专家）
- 背景：应「分析当前 Android/Kotlin 项目并制定质量改进方案」。方案见 `docs/QUALITY_IMPROVEMENT_PLAN.md`。
- 基线快照：HEAD `135ff8c`，工作树含未提交改动。已固化指纹并核对：`git diff HEAD` 前后 sha1 一致、未跟踪清单一致、关键源码 mtime 一致 → **本轮未改动任何源码**。
- 环境：Gradle Wrapper 8.7 / AGP 8.5.2 / Kotlin 1.9.24 / JDK 17。

### 1. 单元测试 —— 通过
- 命令：`./gradlew test --console=plain` → **BUILD SUCCESSFUL（exit 0）**
- 按模块用例数（debug 变体，取自 `*/build/test-results/testDebugUnitTest/*.xml`）：

| 模块 | tests | failures | errors |
|---|---|---|---|
| app | 12 | 0 | 0 |
| core-overlay | 3 | 0 | 0 |
| core-store | 46 | 0 | 0 |
| core-translate | 34 | 0 | 0 |
| **合计** | **95** | **0** | **0** |

- `:core-capture:testDebugUnitTest` 与 `:core-capture:testReleaseUnitTest` 均为 **NO-SOURCE** —— 该模块确实没有任何测试源码，不是"漏跑"。
- `app/src/androidTest` 的 5 个文件（7 个用例）**本轮未执行**：`gradlew test` 不覆盖仪器测试，需设备。

### 2. Debug 构建 —— 通过
- 命令：`./gradlew :app:assembleDebug --console=plain` → **BUILD SUCCESSFUL（exit 0）**
- 为排除 UP-TO-DATE 弱证据，先删除 APK 再强制重建，产物重新产出（277,753,350 bytes；体积主要来自 assets 内打包的 PaddleOCR 模型）。

### 3. Android Lint —— 失败（仅 `app` 模块）
- 命令：`./gradlew lint --console=plain` → **BUILD FAILED（exit 1）**，失败任务 `:app:lintDebug`
- **4/5 模块 lint 通过**：`:core-capture:lintDebug`、`:core-overlay:lintDebug`、`:core-store:lintDebug`、`:core-translate:lintDebug`
- `app` 模块共 15 条：

| 严重级别 | 规则 | 数量 |
|---|---|---|
| Fatal | `Instantiatable` | 4 |
| Warning | `GradleDependency` | 8 |
| Information | `AutoboxingStateCreation` | 2 |
| Warning | `UnusedResources` | 1 |

- 4 条 Fatal 覆盖 `app/src/main/AndroidManifest.xml` 里**全部**四个组件：`:10 TranslatorApplication`、`:18 MainActivity`、`:27 CaptureForegroundService`、`:33 OverlayAccessibilityService`。

**根因结论：判定为 `app` 模块 lint 的类解析假阳性，不是代码缺陷。** 依据：

1. 四个类声明均正确：`TranslatorApplication : Application()`（`TranslatorApplication.kt:5`）、`MainActivity : ComponentActivity()`（`MainActivity.kt:655`）、`CaptureForegroundService : Service()`（`CaptureForegroundService.kt:40`）、`OverlayAccessibilityService : AccessibilityService()`（`OverlayAccessibilityService.kt:7`）。
2. `namespace = "com.manga.translator.app"` 与清单相对名一致；同模块四个类**全部**报错 → 属模块级类解析问题，非个别类问题。
3. 四个 `.class` 确实由成功编译产出（`app/build/tmp/kotlin-classes/debug/com/manga/translator/app/`），且 `assembleDebug` 通过。
4. **排除配置缓存**：`org.gradle.configuration-cache=true` 是首要怀疑（`.codex/testing.md:93` 记录过缓存导致的假失败）。实测 `--no-configuration-cache` 仍失败。
5. **排除陈旧报告/分析复用**：首次实验时 `:app:lintAnalyzeDebug` 为 `UP-TO-DATE`（该次结论无效）。清空 `app/build/intermediates/*lint*` 并加 `--rerun-tasks` 强制全量重编译 + 重新分析后，仍以同样 4 条 Fatal 失败 → **确定性复现**。
- 未定位到 AGP lint 内部的具体机制（需 lint model dump 级工具），但"非代码缺陷"已有上述 5 条证据。
- 处理方式（建 baseline / 关闭该规则 / 升级 AGP）属**质量政策类决策，留待人工决定**；本轮未做任何 suppression 或 baseline。
- 完整报告：`app/build/reports/lint-results-debug.xml`。

### 4. 本轮未执行
- `./gradlew connectedAndroidTest` —— 无设备，`app/src/androidTest` 7 个用例未运行。
- 覆盖率 —— 项目无 Kover/JaCoCo，本轮决定不引入。
- SCA（依赖漏洞 / 许可证 / SBOM）—— 项目无相关工具，本轮决定不引入。
- 生产代码 / 构建文件 / CI / 依赖的任何改动。

## 2026-09-15 阶段 1：core-capture 关键行为补测（纯重构 + 新增单测）

- 执行者：科拉（Android/Kotlin 质量工程专家）
- 背景：`core-capture` 606 行、**0 测试**，而「截图超时 / 录屏令牌失效」正是该模块的历史故障高发区（`Todolist.md`、`.codex/testing.md` 多次记录）。
- 原则：**不改变任何判定规则与常量值，只做搬家 + 补测**。

### 改动

1. 新增 `core-capture/src/main/java/com/manga/translator/capture/CaptureFailurePolicy.kt`
   - `classify(error: Throwable): CaptureFailureType` —— 失败归类的全部文案匹配逻辑
   - `retryTimeoutMs(requestedMs: Long): Long` —— 恢复重试超时的夹取
2. 新增 `core-capture/src/main/java/com/manga/translator/capture/FramePolicy.kt`
   - `FrameLumaStats` 数据类
   - `isSuspicious(stats)` —— 可疑帧（整帧近乎纯黑/纯白）判定规则
   - `snapshotUsable(ageMs, maxAgeMs)` —— 最近帧快照新鲜度
   - `shouldScale` / `scaledHeight` / `clampedQuality` —— JPEG 缩放与质量夹取
   - 帧采样与判定相关的 6 个常量集中到此
3. `ScreenCaptureController.kt`
   - `resolveFailureType` / 重试超时 / 帧质量判定 / 快照新鲜度 / JPEG 参数 全部改为调用上述策略
   - `analyzeFrameQuality` 的**像素采样循环与统计量计算原样保留**，只把最后的判定规则交给 `FramePolicy`
   - companion 中已迁出的 6 个常量删除（`FIRST_FRAME_WAIT_TIMEOUT_MS`、`TAG` 保留）
4. 构建改动（**本轮唯一一处**）：`core-capture/build.gradle.kts` 增加
   `testImplementation("junit:junit:4.13.2")` —— 与 `core-store` / `core-overlay` / `core-translate`
   完全一致的同名同版本依赖；该模块此前没有任何测试 source set，是仓库内唯一缺这行的模块。

### 验证证据

| 项 | 命令 | 结果 |
|---|---|---|
| core-capture 单测 | `./gradlew :core-capture:test` | **PASS** —— 35 用例 / 0 失败（改前为 `NO-SOURCE`） |
| 全量单测 | `./gradlew test` | **PASS** —— **130 用例 / 0 失败** |
| Debug 构建 | `./gradlew :app:assembleDebug` | **PASS** |

各模块用例数（debug 变体）：

| 模块 | 改前 | 改后 |
|---|---|---|
| app | 12 | 12 |
| **core-capture** | **0（NO-SOURCE）** | **35** |
| core-overlay | 3 | 3 |
| core-store | 46 | 46 |
| core-translate | 34 | 34 |
| **合计** | **95** | **130** |

### 测试有效性的反向验证（变异测试）

不做这一步，无法区分「测试通过」与「测试根本没在检查」。故意改坏 3 处逻辑后重跑：

| 注入的破坏 | 被抓住的用例 | 结果 |
|---|---|---|
| `isSuspicious` 的 `stddev < 9` 改成 `<=` | `suspicious_boundaryStddevIsExclusive` | FAILED ✓ |
| `snapshotUsable` 的 `ageMs <= maxAge` 改成 `<` | `snapshot_boundaryIsInclusive`、`snapshot_supportsExplicitMaxAge` | FAILED ✓ |
| `classify` 去掉 `.lowercase()`（变成大小写敏感） | `classify_ignoresMessageCase`、`classify_matchesKnownProjectionExpiredMessages` | FAILED ✓ |

- 结果：35 用例中 **5 个失败**、BUILD FAILED（exit 1）—— 三处破坏**全部**被捕获。
- 还原后 md5 与改前逐字节一致（`CaptureFailurePolicy.kt` = `f5ee5c59a227e1552922ce2f91da2031`、
  `FramePolicy.kt` = `43166e4b52ce9c5ef313199d798e679a`），重跑恢复全绿。

### 本轮未执行
- **真机/模拟器行为回归**：本次为纯重构，判定规则与常量值已逐条比对未变，但**未在设备上跑过截图链路**。
- `connectedAndroidTest`、覆盖率、SCA —— 同上一节。

### 顺带发现（未修改，待人工决定）
- `classify` 的 `SecurityException` 分支匹配的是**带空格**的 `"media projection"`，模式表中只有带空格的 `"non-current mediaprojection"`。因此系统若抛出**无空格**的 `"MediaProjection ..."` 文案会落到 `UNKNOWN`，此时不会走「提示重新授权」路径。已用特征化测试 `classify_characterisation_mediaProjectionWithoutSpaceIsNotMatched` 记录当前行为；**未擅自补模式**——补了会改变恢复路径行为，属需确认的改动。

---

## PaddleOCR 识别阶段并行化：模拟器 A/B 基准（2026-09-15）

定位：`paddle_total_ms` 中逐文字框识别（rec）占约 87%，根因是 `runDetect` 的框循环串行执行。

### 方法
- 基准用例：`app/src/androidTest/java/com/manga/translator/app/PaddleOcrPerfBenchmarkTest.kt`
  （设备上读 `bench_page1~3.png`，创建 1 个 detector，预热 1 轮后每页连测 5 次，打印每轮耗时与逐块文字）。
- 驱动：`scripts/bench-paddle-parallel.sh` —— 同一模拟器、同一批图、同一份测试，
  仅切换 `PaddleOcrLocalTextDetector.kt` 的新版/`git HEAD` 旧版，交错跑 2 轮（parallel → serial → parallel2 → serial2）抵消时间漂移。
  脚本内有 `trap` 之外的显式还原 + md5 校验（`restored_md5=61a9bcaf457af2c8f92ecc5a1e77f853`，与备份一致）。
- 分析：`scripts/bench-analyze.py`。
- 环境：Pixel_8 AVD / emulator-5554 / x86_64 / API 36 / **nproc=4**。输入 3 张 1080×2400 整页截图。

### 结果（每页每组 10 次采样，取中位数）

| 页面 | 文字框数 | 串行中位 | 并行中位 | 提速 | 串行 min | 并行 min |
|---|---|---|---|---|---|---|
| bench_page1 | 10 | 1793ms | 1274ms | 1.41x | 1527ms | 1144ms |
| bench_page2 | 15 | 3618ms | 2268ms | 1.59x | 3362ms | 2104ms |
| bench_page3 | 37 | 5377ms | 3120ms | 1.72x | 4822ms | 2893ms |
| **合计 30 次** | — | **3618ms** | **2268ms** | **1.59x** | — | — |

- 提速随文字框数量上升（10→37 框对应 1.41x→1.72x），符合并行摊薄固定开销的预期。
- 串行中位数 3618ms 与历史记录 `paddle_total_ms=4876`（韩漫整页）同量级，可作为方法自洽的旁证。
- 并行档位实际生效：日志 `rec_threads=3`（4 核 → `min(cores-1, 4)`），`rec_ms` 与 `paddle_total_ms` 差值稳定在 100~180ms（det + 后处理）。

### 识别结果一致性（关键）

逐块文字序列比对，三页**均完全一致**：

| 页面 | 串行结果种类 | 并行结果种类 | 文字 | 置信度最大差异 |
|---|---|---|---|---|
| bench_page1 | 1 | 1 | 完全一致 | 1.6e-6 |
| bench_page2 | 1 | 1 | 完全一致 | 1.2e-6 |
| bench_page3 | 1 | 1 | 完全一致 | 2.5e-6 |

- 「组内 1 种结果」说明同配置下多次运行输出可复现。
- 置信度差异停留在浮点末位（≤2.5e-6），来源是 `OptLevel.BASIC_OPT → ALL_OPT` 的算子融合改变了累加顺序，
  不影响任何阈值判定（级联档位 0.93/0.82 的余量远大于该量级）。

### 本轮未覆盖
- **真机（8 核）实测**：模拟器仅 4 核，`recParallelism()` 取 3 而非上限 4，真机预期收益更大，未验证。
- 发热/耗电、长时间连续翻页稳定性、≤4 核低端机兜底 —— 均需真机，已列入 HumanTODO。
- 未做 OCR 结果缓存；同一页重复翻译仍会重跑整段 OCR。

---

## 2026-09-18 设计改版落地核对（Ardot 画布 → Compose，模拟器逐屏）

**验证方式**：真机（模拟器）操作 + 逐屏截图，`docs/design-redesign/app-verify-03~07-*.png`。
**环境**：Pixel_8 AVD（`emulator-5554`，Android 15 / API 36.1，1080×2400，density 420，4 核），
APK `app/build/outputs/apk/debug/app-debug.apk`（`assembleDebug` BUILD SUCCESSFUL）。

### 逐屏结论

| 屏 | 核对点 | 结果 |
|---|---|---|
| 首页（未配置） | 「云端 API 还没配置」红字提示行 + 右侧蓝色「去配置档案」入口；配置档案值显示「未配置」 | 一致 |
| 配置档案弹层（空） | 空状态文案「还没有配置档案」 | 一致 |
| 新建配置表单 | **选完厂商后 Base URL 自动带出 `https://api.openai.com/v1`、Model 自动带出 `gpt-4o-mini`**（用户通常只需粘 API Key） | 一致（本轮修复项） |
| 首页（配置完成） | 配置档案行显示档案名「openai-test」；**异常状态行按条件渲染，此时整行消失** | 一致（本轮修复项） |
| 翻译历史（列表） | 行主文案为「韩文 → 简体中文 · 8 个气泡」（语言显示为**应用内名称**而非 `ko → zh-cn` 代码）；次行灰字时间 + 页面 hash；行尾垃圾桶 | 一致（本轮修复项） |
| 翻译历史（展开） | 点行展开出「原文 → 译文」气泡列表，原文黑字、译文蓝字前缀 `→`，浅底圆角块 | 一致 |
| 术语表 | 空状态为「空箱子」线稿插画（`ic_empty_box`）+ 居中标题说明 | 一致 |
| 圆角/配色 | 首页各卡片 18dp、底部操作卡上圆角 18 / 下 0、主按钮胶囊；页面纯白 `#FFFFFF` | 一致（本轮回填了画布上被误改为直角的 3 处） |

### 本轮修掉的不一致（设计稿 → 代码）

1. 新建配置表单 **Base URL / Model 未随厂商自动带出** → `ifBlank { provider.defaultBaseUrl / defaultModel }`。
2. 翻译历史行显示语言代码 `ko → zh-cn` → 改为 `AppLanguage` 名称（ko → 韩文）。
3. 配置弹层空状态文案「还没有配置，先新建一个」→「还没有配置档案」。
4. 列表行值文本过长挤压右侧箭头 → 加 ellipsis + 最大宽度约束。
5. 画布侧：`识别配置卡` / `翻译配置卡` / `操作卡` 圆角被改成直角 → 回填 18dp（与代码一致）。

### 本轮未覆盖
- 真机（非模拟器）核对与手动操作确认：见 `HumanTODO.md` 同名小节。
- 长文本配置名（超长档案名省略号）+ 多档案切换的视觉表现，未在模拟器上构造。
- 首页「OCR 引擎 = ML Kit 需 Google Play 服务」的检测：**代码中不存在**，属建议新增项，未实现。

---

## 2026-09-18 圆角调整：内容列表类「框框」改直角

**需求**：用户要求把首页「OCR服务 / 翻译服务」两张设置卡、术语表列表卡、翻译历史列表卡改成**直角**。
**范围**：Ardot 画布（`10:30` 页）与 App 代码同步修改。

| 位置 | 画布节点 | 代码 | 改动 |
|---|---|---|---|
| 首页 OCR服务卡 | `3:615` | `HomeScreen.kt` 第 1 个 `MtCard` | 18 → 0 |
| 首页 翻译服务卡 | `10:58` | `HomeScreen.kt` 第 2 个 `MtCard` | 18 → 0 |
| 术语表列表卡 | （画布无此稿，仅空状态稿） | `GlossaryScreen.kt::GlossaryListCard` | 18 → 0 |
| 翻译历史列表卡 | `3:725` | `HistoryScreen.kt` 列表 `MtCard` | 18 → 0 |

**未跟着改（保持原样）**：语言对两个 Filled 输入框（12）、底部「启动服务」操作卡（上 18 / 下 0）、
行内圆形按钮（36dp，半径 18）、术语表序号小标签（8）、弹窗与底部 tab（14）。

**验证**：`assembleDebug` BUILD SUCCESSFUL → 安装到 `emulator-5554` → 逐屏截图。
`docs/design-redesign/app-verify-08-首页-服务卡直角.png`、
`app-verify-09-术语表-列表卡直角.png`、`app-verify-10-翻译历史-列表卡直角.png`（03~07 为改前对照）。

### 本轮未覆盖
- 真机上肉眼确认（模拟器已确认，见 HumanTODO）。

---

## 2026-09-18 下拉菜单文字规格与宽度 + 百度凭据输入框加框

**需求（用户反馈）**：
1. 下拉菜单里的**字体与「设置行」的值不一致**（值：14px / Regular / `#10141C`），且菜单宽度比所在行窄，看着不协调。
2. 百度 APP ID / 密钥两处**看不出哪里能填**（无边框的行内输入），容易点到别的地方。

**改动**：

| 项 | 改动 |
|---|---|
| 新增 `MtUi.kt::MtDropdownMenu` | 容器，`anchorWidthPx` 传入时菜单宽度与锚点对齐（用 `onGloballyPositioned` 量锚点宽度） |
| 新增 `MtUi.kt::MtDropdownItem` | 菜单项统一 14px / Regular / `#10141C` / 单行省略，与「设置行」的值同规格 |
| 首页 语言对 / 设置行 / 配置档案 ⋮ / 新建表单「厂商」 | 全部改用上面两个组件；三个值选择菜单宽度对齐锚点，⋮ 与导出菜单保持内容自适应 |
| 术语表 ⋮ 与「导出」菜单 | 同步换成 `MtDropdownItem`（字体一致） |
| 百度 APP ID / 密钥 | 删除无边框的 `InlineTextField`，改用带框的 `MtInputField`（浅灰底 `#EEF1F7` + 圆角 12 + 高 60 + 整块可点聚焦），两个字段上下排列、间距 10dp |
| 百度凭据标签外置（追加） | `MtInputField` 新增 `labelOutside` 参数：为 true 时标签（13px `#5A6273`）画在框外上方，框内高 52、只放输入值 —— 用户要求「说明要在框外」 |

**验证**：`assembleDebug` BUILD SUCCESSFUL → 安装到 `emulator-5554` → 截图
（`app-verify-11-下拉菜单宽度与字体.png`、`app-verify-12-百度凭据输入框.png`、`app-verify-13-术语表菜单.png`、
`app-verify-14-百度凭据-标签在框外.png`）：
- OCR 引擎菜单宽度与行等宽、条目文字与行内值同字号同字重；
- 切到「百度翻译」后，两个凭据字段显示为明显的浅灰圆角框，且状态行提示「百度翻译还没填 APP ID / 密钥」；
- 术语表 ⋮ 菜单条目文字规格一致；
- 百度两个字段的标签在**框外**上方，框内为空（只用于输入）。

### 本轮未覆盖
- 画布未同步：画布「规范」页已定义输入框（浅灰底 / 圆角 12 / 小标签在值上方），本次改动即按该规范落地，未新增节点；
  百度凭据字段设计稿从未画过（仅代码侧存在）。
- 真机肉眼确认。

---

## 2026-09-18 配置表单：标签移到框外 + API Key 跟随厂商

**需求（用户反馈）**：
1. 表单字段的标签要在**框外**（不是压在框内上方）。
2. **API Key 要跟随厂商** —— 现在「只要填写一次，就都是那个相同的 key」。
   用户选定方案：**每个厂商各记各的 key**（换回来还是原来那把）。

**改动**：

| 项 | 改动 |
|---|---|
| `MtUi.kt::MtFilledField` / `HomeScreen.kt::MtInputField` | 新增 `labelOutside` 参数：为 true 时标签（13px `#5A6273`）画在框外上方，框高压到 52、框内只放值 |
| 新建配置表单 5 个字段 | 配置名称 / 厂商 / Base URL / API Key / Model 全部 `labelOutside = true`（厂商字段是 `MtFilledField`，其余是 `MtInputField`） |
| 首页「翻译服务」卡（百度） | 两个字段此前已是 `labelOutside = true` |
| `SettingsRepository` | 新增 `getProviderApiKey(providerId)` / `saveProviderApiKey(providerId, apiKey)`，存 `provider_api_keys_json`（JSON：厂商 id → key）；`ProfileFormScreen` 以两个 lambda 注入，不耦合仓储 |
| API Key 取值 | **编辑**用配置自己的 key；**新建**用该厂商记住的 key（不再继承上一条配置） |
| 切换厂商 | 非空的当前 key 先记到旧厂商名下 → 再带出新厂商记住的 key（没有则空） |
| 保存配置 | 把这把 key 记到该厂商名下 |

**验证**（`assembleDebug` BUILD SUCCESSFUL → 安装到 `emulator-5554`，全程不按返回键免得误关弹窗；
每步 dump 校验「配置名称」仍存在）：

| 步骤 | 观察值（密码框是掩码，用位数区分） | 结论 |
|---|---|---|
| OpenAI 下填 `sk-aaa` | `••••••`（6） | 填入成功 |
| 切换厂商 → DeepSeek | `[]`（空） | **不再沿用同一把 key** |
| DeepSeek 下填 `sk-bbbbbbb` | `••••••••••`（10） | 填入成功 |
| 换回 OpenAI | `••••••`（6） | **各厂商各记各的 key** |

截图：`app-verify-15-新建配置-标签在框外.png`、`app-verify-16-切到DeepSeek-key为空.png`、
`app-verify-17-换回OpenAI-key恢复.png`。

### 本轮未覆盖
- 真机肉眼确认。
- 模拟器上留下了两条测试用厂商 key（openai=`sk-aaa`、deepseek=`sk-bbbbbbb`），
  新建配置时会带出来，用真机或改掉即可；UI 上目前没有「清除该厂商记住的 key」的入口。
- 画布「新建配置表单」稿仍是标签在框内，未同步（设计稿待更新）。

---

## 2026-09-18 百度凭据改成「一行 + 弹层」（画布方案 1）

**起因**：用户反馈首页翻译卡里百度那两个输入框「太大，和『设置行 翻译引擎』那一行大小不一致，不协调」，
并让我把可选做法画出来。画布上给了三个方案（页 2 右下角 `24:1`），用户选择**方案 1**。

**方案对比（已画在画布 `24:1`，标题已标注「结论：方案 1」）**：

| 方案 | 做法 | 结论 |
|---|---|---|
| 1（采用） | 卡片里只留一行「百度配置」（与「配置档案」行同构，56 高），点开底部弹层填两项 | 卡片节奏不变、弹层里输入框能正常大小、不改数据模型 |
| 2 | 凭据收进「配置档案」（档案 = 凭据 + 引擎的容器） | 长期最正统，但要档案记住引擎类型，改动大，暂不做 |
| 3 | 留在行内，输入框压到 44 高 / 180 宽 | 改动最小，但输入区只有一半宽，长 key 看不全 |

**改动**：
- `HomeScreen.kt`：百度分支由两个 `MtInputField` 改为一行 `MtListRow("百度配置", 已配置/未配置)`，
  点击置 `baiduSheetOpen`；新增 `val baiduConfigured`（两项都非空）供卡片值与状态行共用。
- 新增 `BaiduConfigSheet`（底部弹层，与「配置档案」弹层同款：拖拽条 + 22 圆角上边 + `navigationBarsPadding`）：
  标题「百度翻译配置」+ 一行说明 + 两个 `labelOutside = true` 的 `MtInputField` + 满宽主按钮「保存」。
- 状态行补齐设计稿原定的入口：引擎=百度且未配好时，显示「百度翻译还没填 APP ID / 密钥 → **去填百度配置**」
  （此前该行没有入口，点了没地方去）。

**验证**（`assembleDebug` BUILD SUCCESSFUL → 安装到 `emulator-5554`）：

| 步骤 | 观察值 | 结论 |
|---|---|---|
| 引擎=百度翻译 | 翻译卡只两行：`翻译引擎 / 百度翻译（免费额度，快）` + `百度配置 / 未配置` | 与上方 OCR 卡行高完全对齐 |
| 状态行 | 「百度翻译还没填 APP ID / 密钥」+ 右侧「去填百度配置」 | 入口可用 |
| 点「百度配置」 | 弹层出现：`百度翻译配置` / 说明 / `APP ID` / `密钥（Secret Key）` / `保存` | 两项输入框存在 |
| 填 `12345678` + `abcdefghijkl` → 保存 | 卡片值变 **已配置**，状态行**消失** | 保存链路通 |

截图：`app-verify-18-首页-百度模式一行.png`、`app-verify-19-百度配置弹层.png`、
`app-verify-20-填好保存后已配置.png`。

### 本轮未覆盖
- 真机肉眼确认。
- 模拟器里已写入假的百度凭据（APP ID=`12345678`、密钥=`abcdefghijkl`），真机上请自行替换。
- 画布「新建配置表单」(10:94) 仍是标签在框内的旧写法，未同步。
- 弹层没有背景遮罩（与现有「配置档案」弹层保持一致的做法），如需变暗可另提。

---

## 2026-09-19 质量方案复核（**本轮未执行任何 Gradle 任务**）

- 执行者：科拉（Android/Kotlin 质量工程专家）
- 触发：用户再次要求「分析当前 Android/Kotlin 项目并制定质量改进方案」。
- 性质：**纯只读发现**。未运行 test / build / lint，未改任何源码、构建文件、CI、依赖。
  本节只记录"看到了什么"，不构成任何通过结论。
- 产出：更新 `docs/QUALITY_IMPROVEMENT_PLAN.md`（修订版，含缺口 G12~G14）。

### 关键事实

| 项 | 值 | 来源 |
|---|---|---|
| 版本基线 | `main` @ `135ff8c` | `git log` |
| 工作树 | 14 个已改 + 20 项未跟踪（UI 改版整体未提交） | `git status --porcelain` |
| 规模 | 5 模块 / 68 个 main `.kt` / **18,702 行** | `wc -l` |
| 单测 | 18 文件 / **130 用例**（app 12 / core-capture 35 / core-translate 34 / core-overlay 3 / core-store 46） | `grep -c @Test` |
| 仪器测试 | 6 文件 / **9 用例** | 同上 |
| 新增 UI 层 | `HomeScreen` 1,254 + `GlossaryScreen` 1,601 + `MainActivity` 580 + `MtUi` 598 + `HistoryScreen` 317 + `SwipeRevealRow` 123 + `theme/` 190 = **4,663 行，0 用例** | `wc -l` |
| `core-overlay` | 3,905 行 / 3 用例（唯一测试为 `TranslationLockGesturePolicyTest`） | `wc -l` + 文件清单 |

### 本轮最重要结论

**当前工作树没有"本次证据"。** 上一次质量基线为 2026-09-15；此后 09-17 / 09-18 的 UI 改版
（`MainActivity.kt` 重写、`GlossaryScreen.kt` +1,020、`HistoryScreen.kt` 改写、新增 `HomeScreen.kt` 与 `ui/` 包）
只验证了 `assembleDebug` 成功与逐屏截图，**`./gradlew test` 与 `./gradlew lint` 均未复跑**。

→ 现有 130 个用例对当前树是否仍全绿，状态是 **未知**，不是"通过"。
→ 处置见方案阶段 A（需授权）。

### 本轮核实仍然存在的既有缺口

- **G8**：`TranslationLockGestureOutcome.BLOCKED` 除声明处外**全仓无引用**，`decide()` 恒返回 `RELEASE`（死分支仍在）。
- **G9**：`CLAUDE.md:64` 仍写 `TranslationCacheRepository`（该类已不存在，现为 `SentenceTranslationRepository`）。
- **G1**：`app` 模块 lint 的 4 条 Fatal 假阳性状态**未复核**（需跑 lint 才能确认，本轮未跑）。
- 代码卫生良好：全仓 `TODO/FIXME/HACK` 仅 2 处。

### 本轮未执行

- `./gradlew test` / `:app:assembleDebug` / `lint` / `connectedAndroidTest` —— 全部未运行。
- 任何代码、构建文件、CI、依赖的改动。

---

## 2026-09-19 阶段 A：改版后基线复跑（本次证据）

- 执行者：科拉（Android/Kotlin 质量工程专家）
- 触发：用户授权「跑基线再决定」。针对的是 **2026-09-15 之后落地的整套 UI 改版从未复跑过 test/lint** 这一风险。
- 环境：Gradle Wrapper 8.7 / AGP 8.5.2 / Kotlin 1.9.24 / JDK 17（Android Studio jbr）。
- 指纹核对：跑前跑后 `git status` 指纹与 `git diff HEAD` sha1 **完全一致**
  （`status_sha1=7aa4aad2…`、`diff_sha1=dd64b3f1…`）→ **本轮未改动任何源码 / 构建文件**。
- 日志：`logs/quality-20260919-*.log`（已 gitignore）。

### 1. 单元测试 —— 通过（130 用例 / 0 失败）

| 模块 | tests | failures | errors | skipped | 本次执行时间 |
|---|---|---|---|---|---|
| app | 12 | 0 | 0 | 0 | 14:26（真实执行） |
| core-overlay | 3 | 0 | 0 | 0 | 14:26（真实执行） |
| core-store | 46 | 0 | 0 | 0 | 14:26（真实执行） |
| core-capture | 35 | 0 | 0 | 0 | 14:43（`--rerun-tasks` 强制重跑） |
| core-translate | 34 | 0 | 0 | 0 | 14:44（`--rerun-tasks` 强制重跑） |
| **合计** | **130** | **0** | **0** | **0** | — |

**关键结论：09-17/18 的 UI 改版（`MainActivity.kt` 重写、`GlossaryScreen.kt` +1,020 行、
`HistoryScreen.kt` 改写、新增 `HomeScreen.kt` 与 `ui/` 包）没有打破任何一个既有用例。**

#### 排除弱证据的处理（重要，记录过程）

首轮 `./gradlew test` 返回 BUILD SUCCESSFUL，但 `:core-capture:testDebugUnitTest` 与
`:core-translate:testDebugUnitTest` 是 **`UP-TO-DATE`**，其 XML 时间戳为 09-15 20:07 / 20:43
—— 属于复用旧结果，**不构成本次证据**。

- 尝试 1：`./gradlew cleanTest test` —— **无效**。`cleanTest` 只清理聚合任务 `test` 的输出，
  对分变体任务（`testDebugUnitTest`）是空操作，5 个测试任务仍全部 `UP-TO-DATE`。
- 尝试 2（有效）：`./gradlew :core-capture:testDebugUnitTest :core-translate:testDebugUnitTest --rerun-tasks`
  → 32 个任务全部 executed，BUILD SUCCESSFUL，XML 时间戳更新为 09-19 14:43 / 14:44。
- → **五个模块的 130 个用例全部在 2026-09-19 真实执行过**，无 UP-TO-DATE 复用。

### 2. Debug 构建 —— 通过（全量重建）

- 命令：`./gradlew :app:assembleDebug --rerun-tasks --console=plain`
- 结果：**BUILD SUCCESSFUL（exit 0）**，`117 actionable tasks: 117 executed`（**无 UP-TO-DATE，全部重新执行**）
- 耗时：6 分 46 秒
- 产物：`app/build/outputs/apk/debug/app-debug.apk`，**277,868,832 bytes**，mtime 09-19 14:41

> 为何要做 `--rerun-tasks`：首次 `assembleDebug` 只用了 4 秒且 `112 up-to-date`，
> APK 仍是 09-18 23:38 的旧产物。虽 Gradle 的输入哈希判定本身可靠，但为得到"当前树可全量编译"的
> 直接证据，仍强制全量重跑一次。**这是有效证据，不是 UP-TO-DATE 复用。**

### 3. Android Lint —— 失败（仅 `:app:lintDebug`），与 09-15 结论一致

- 命令：`./gradlew lint --console=plain` → **BUILD FAILED（exit 1）**，失败任务 `:app:lintDebug`
- 报告时间戳 09-19 14:32（本次重新生成）
- **4/5 模块通过**（0 error）：
  | 模块 | 结果 |
  |---|---|
  | core-capture | 0 errors, 1 warning |
  | core-overlay | 0 errors, 1 warning |
  | core-store | 0 errors, 2 warnings |
  | core-translate | 0 errors, 5 warnings |
  | app | **4 errors**, 9 warnings, 4 informations |

- `app` 模块 4 条 **Error 全部是 `Instantiatable`**，仍然覆盖清单里**全部四个组件**
  （`AndroidManifest.xml:10 / :18 / :27 / :33`）—— 与 09-15 完全一致，**改版未引入新的 Error**。
- Warning 9 条 = `GradleDependency` 8 + `UnusedResources` 1（与 09-15 相同）。
- Information 由 2 条变为 4 条：`AutoboxingStateCreation`（`mutableIntStateOf` 建议）从
  `MainActivity.kt:223 / :658` 变为 `HomeScreen.kt:533 / :587 / :1074` + `MainActivity.kt:135`。
  属新 UI 代码带来的**信息级建议**，非缺陷。
- 根因结论不变：**`app` 模块 lint 的类解析假阳性，非代码缺陷**（5 条证据见 09-15 节）。
  处理方式仍待用户决定（D5），本轮**未建 baseline、未关规则、未做任何 suppression**。

### 4. 本轮未执行

- `./gradlew connectedAndroidTest` —— 仪器测试 6 文件 / 9 用例**仍无本次证据**（需设备）。
- 覆盖率 / SCA —— 项目无相应工具。
- 任何代码、构建文件、CI、依赖的改动（指纹已核对一致）。

## 2026-09-29 WebView 译文 DOM 覆盖层对齐参考项目验证

- 执行者：DSH Agent
- 目标：把内嵌浏览器链路的「擦原文 → 画译文」渲染层补到与 `C:\homework\translator` 扩展同级，
  只改渲染相关代码，不重写 OCR / 翻译 / 阅读器适配层。

### 1. Node 验收脚本 —— 通过

- 命令：`node app\src\test\js\mt-overlay-acceptance.test.js`
- 结果：**ALL_OK 116/116**（无失败、无异常、无 UP-TO-DATE 复用）。
- 覆盖：§0 层级顺序 / §4 倾斜遮罩与实底填充 / §5 清理图贴图+blob 出口+晚绑定 /
  §6 描边 / §7 字号区间与遮罩同步撑开 / §8 竖排判定 / §9 宿主样式隔离 /
  §10 幂等/滚动不重测/坏气泡隔离/点击切换 / §11 凸多边形裁剪 + 分片 union 遮罩 +
  三级背景降级 + 视觉去重。
- 文件：
  - `app/src/test/js/dom-stub.js` —— 最小 DOM 桩，支持真实渲染器建节点、同步、事件。
  - `app/src/test/js/mt-overlay-acceptance.test.js` —— 116 条断言。

### 2. Kotlin 单元测试 —— 通过

- 命令：`.\gradlew :app:testDebugUnitTest :core-overlay:testDebugUnitTest :core-translate:testDebugUnitTest`
- 结果：**BUILD SUCCESSFUL（exit 0）**，5 个模块测试任务全部执行。
- 新增/修改：
  - `BrowserSceneBuilderTest`：覆盖 `polygon` / `rotation_deg` / `fill_box` / `region_polygon` / `cleaned_source_box` / `font_weight` 序列化、退化四边形、扁平点数组工具。
  - `TextQuadGeometryTest`：像素 → 百分比、拼接图映射、退化处理、`rotationDeg` 提取。
  - `PaddleOcrBlockMergerTest`：单块透传四边形、多合并丢弃四边形。
- 注意：未做 lint / 仪器测试 / 真机验证（见 HumanTODO.md）。

### 3. 实现范围摘要

- **数据契约**：`BrowserSceneBuilder.SceneBubble` 扩展 6 个字段；`TextBlock/Bubble` 新增 `polygon`、`rotationDeg`；
  `PaddleOcrBlockMerger` 单块透传、多合并丢弃；`BubbleFirstRegionPlanner` / `WebtoonImageTranslator` 转成百分比并生成 `fill_box`。
- **JS 渲染层**：`app/src/main/assets/mt-overlay-renderer.js` 新增倾斜遮罩几何、实底世界坐标填充、清理图 `cleaned_source_box`、
  blob URL 出口、晚绑定 `applyCleanedImage`/`applyCleanedImages`、宿主样式隔离、单气泡 try/catch、幂等场景签名、
  `clipConvexPolygonToRect` 凸多边形裁剪。
- **可交付测试**：Node 验收 116 条断言 + Kotlin 单元测试全绿。

### 3b. §11 跨页分片（第二轮补充，2026-09-29）

- **契约**：`SceneBubble.coverSegments: List<CoverSegment>`（`page_id` / 子框 / 可选四边形 /
  `cleaned_source_box` / `in_band`）；场景级新增 `page_cleaned_url`。
- **union 遮罩**：`resolveCoverSegments` 按 `page_id` 合并多段为每页一块（逐段各建节点会被后一段的
  背景映射顶掉，参考项目实测过遮罩残缺）；多段并集丢四边形退回外接框。
- **三级背景降级**：`in_band=false` 且整页图就位 → `data-cleaned-layers="2"` + CSS 多背景
  （带内复合图压住整页图）；整页图缺位退回单层，绝不出现「有遮罩但背景是空的」。
- **视觉去重**：`suppressBubble(imageId, bubbleId)` 按气泡摘遮罩+译文，不重发场景。
- **跨图 union 增量**：`updateBubbleCover(imageId, bubbleId, geometry)` 把已渲染气泡的遮罩换成
  跨图完整形状，只重建该气泡遮罩、译文与字号不动（避免重排闪烁）；实底撑开链同步改指向。
- **验收**：Node 断言从 104 增至 **116**（新增分片 union、三级背景、视觉去重、跨图增量更新）。

### 3c. §11 与既有拼接机制的对应关系（重要结论）

排查后确认：**union 遮罩在本链路里本来就已达成**，不是靠 `cover_segments`，而是靠既有的
「拼接邻居条带」机制：

1. 识别当前图时把上一张的底部条 / 下一张的顶部条拼进当前图 → 跨边界的气泡在拼接图里是**完整的**，
   OCR 四边形自然完整；
2. `TextQuadGeometry.stitchedPolygonToSource` 换算到源图坐标时允许 y 溢出（`coerceIn(-100,200)`）；
3. 渲染层 `resolveBubblePolygon(value, allowOverflow = stitch_overflow)` 对跨图气泡**不收拢 y**；
4. 于是上一张图的遮罩**已经是完整形状的倾斜四边形**，只是被 root（图片元素矩形）自然裁掉图外部分
   —— 与参考项目 union 遮罩的视觉效果一致。

`cover_segments` + `updateBubbleCover` 的价值是把这个隐式行为变成**显式契约**，覆盖拼接条带不可用的
场景（下一张图下载失败 / 站点按需加载 / 将来改为分片独立识别）。因此 §11 不存在功能性缺口，
只剩「编排层是否调用显式 API」这一项可选增强。

### 4. 本轮未实现（明确遗留）

- §11 显式契约的**编排层接线**（可选增强，非功能缺口）：`EmbeddedBrowserScreen` 在下一张图翻译完后
  记住 `lastImageId` + 源气泡 id，用 `CrossBubblePart` 携带的完整四边形调用 `updateBubbleCover` 回补；
  以及把 `suppressBubble` 接到相邻分片重叠区的去重判定上（当前重复气泡已由 `SeamBubbleMerger` 的
  `droppedNeighbor` 在 OCR 阶段拦住）。
  （注：清理图 `exportCleanedImage` 导出的就是整张源图区域，所以「三级背景」的整页图这一级
  在本链路里本来就已满足；`page_cleaned_url` 是给未来带内专用复合图留的位。）
- §2 浏览器取图 fallback（fetch/Referer/screenshot）和 OCR 输入 ≤1536/0.82 限制（当前由 PaddleOCR 内部 960 限制兜底）。
- §5 清理图异步请求的去重/20s 超时/失败重传（当前本地同步生成）。
- §6 复杂背景 + 纯黑/纯白重采样逻辑。
- §12 canvas 合成路径与 DOM 覆盖层完全对齐。
- 真机验证（已写入 HumanTODO.md）。

## 2026-09-29 模拟器实机验证（构建 + 安装 + WebView 内渲染自检 + 端到端翻译）

- 设备：`emulator-5554`（sdk_gphone64_x86_64，1080x2400，density 420）。
- 被测包：`com.manga.translator.app`（`applicationId` 已从 `com.manga.translator` 更正）。

### 1. 构建 + 安装 —— 通过

- `.\gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**，APK `279,155,844 bytes`。
- `adb install -r app\build\outputs\apk\debug\app-debug.apk` → **Success**。
- `am start -n com.manga.translator.app/.MainActivity` → `topResumedActivity` 为本 App，
  logcat **无 FATAL / ANR**。

### 2. WebView 内渲染自检 —— 25/25 通过（真 DOM 回读，不是桩）

新增 `.verify/web/rendercheck.html`：在真实 WebView 里下发两个场景，然后**回读渲染出来的 DOM**
（`getComputedStyle` / `element.style` / `classList` / `dataset`）逐条断言，结果打 `RC PASS|FAIL` 到
WebView console（logcat tag `chromium`，`[INFO:CONSOLE:]`）。

命令：`python -m http.server 8899`（`.verify/web`）+ `adb reverse tcp:8899 tcp:8899` →
浏览器打开 `http://localhost:8899/rendercheck.html` → `adb logcat -d | Select-String 'RC '`

结果：**`RC SUMMARY pass=25 fail=0`**。关键行（原文摘录）：

| 断言 | 实测值 |
|---|---|
| §0 遮罩全部在译文之前 | `cccctttt` |
| §4 斜排遮罩用多边形裁剪 | `polygon(6.58% 0%, 100% 64.34%, 93.16% 100%, 0% 35.66%)` |
| §4 斜排译文带旋转 | `translate(-50%, -50%) rotate(20.00deg)` |
| §4 裁剪边确实不水平（真判据） | `top\|Δy\|=64.34 bottom\|Δy\|=64.34` |
| §4 遮罩外接框 = 四边形外接框（不撑大） | `12.9%` |
| §13.2 实底用采样底色 | `--mt-adaptive-bg = #ffffff` |
| §13.3 有清理图时贴上清理图 | `url("http://localhost:8899/syn1_cleaned.jpg")` |
| **§13.3 无清理图时绝不画白块** | `image=none color=rgba(0, 0, 0, 0)` |
| §8 竖排气泡 | class 含 `mt-jp-vertical` |
| **§13.10 字号不被宿主 13px 钉住** | `17.6px`（宿主规则是 `div{font-size:13px}`） |
| **§13.10 颜色不被宿主 #333 钉住** | `rgb(20, 60, 110)`（= 气泡 `#143c6e`） |
| §13.10 内层 content 继承字号 | `text=17.6px content=17.6px` |
| §13.11 横向裁切由滚动容器承载 | host = `.clipbox` |
| §13.11 收边用 clip-path | `inset(0px 598.1px 0px 0px)` |
| §10 相同场景不重建 | `{"ok":true,"rebuilt":false,"bubbles":4}` + root 身份不变 |
| §11 suppressBubble / updateBubbleCover | 摘掉 cover+text；换遮罩后 layer id 仍为 `b3:cover` |

> 首轮跑出 1 条 FAIL（「斜排遮罩不是轴对齐矩形」），排查后确认是**断言写错**：倾角四边形的外接框高度
> 本来就可能等于气泡框高，不能用「高度更大」判断。已改成「裁剪多边形上下边不水平（左右两端 y 不同）」，
> 这才是「倾斜」的正确判据；同时修掉自检页 `waitImages` 的兜底 timer 会二次触发 `run()` 的 bug
> （第二轮作用在已被 suppress/update 改过的 DOM 上，产生 5 条假失败）。

### 3. 端到端翻译（OCR → 翻译 → 擦字 → 场景下发 → DOM 覆盖层）—— 通过

- `big.html`（1 张大图）：`response_ok stage=ocr bubbles=3` → `cleaned_image built size=900x1580 include_solid=true`
  → `erase_apply policy=OPENCV_WHEN_AVAILABLE patches=3 narration_patched=1 cleaned_patched=3`
  → `erase_quality residual_avg=0.06 residual_max=0.11 missing=0`
  → `render_done bubbles=3 mode=DOM_SCENE`
  → **`scene_ok bubbles=3 rebuilt=true host=document done=1`**。
- `seambubble.html`（跨页气泡，两张图）：
  - 图 A：`slice_classify detected=1 owned=1 cross=1` → `cross_out_probe flags=[1] probe=i0:ok` → `crossOut=1`
    → `scene_ok bubbles=1`；
  - 图 B：`slice_classify detected=1 owned=0 dropped_neighbor=1 incoming=1` → `render_done incoming=1`
    → `scene_ok bubbles=1`。
  - 即 **§11 视觉去重（`dropped_neighbor=1`）+ 跨图续排（`crossOut=1` → `incoming=1`）在真机链路上生效**。
- 「原文」按钮：`session_stop reason=已恢复原文`（覆盖层摘除正常）。
- 截图：`.verify/11_seam_translated_ver2.png`、`.verify/12_big_translated_ver2.png`。

### 4. 验证过程中踩到的环境坑（重要，复现端到端时必须处理）

模拟器带着系统 HTTP 代理 `10.0.2.2:7890`（宿主机代理）。**WebView 对 localhost 有自己的 bypass，
所以页面能打开；但 OkHttp 下载图片走系统代理 → `download_http code=502` → `image_fail`**，
端到端根本跑不起来（表现为「页面正常、点翻译无反应、一直 image_fail」）。

处理：`adb shell settings put global http_proxy :0` → 端到端立刻跑通（上面第 3 节的日志）。
**验证完已还原为 `10.0.2.2:7890`**（已确认 `settings get global http_proxy` 回到原值）。

### 5. 仍需人工确认（写进 HumanTODO.md）

模拟器只能证明「链路通 + DOM 属性对」。**观感**（遮罩是否真的看不出补丁、擦字是否干净、
字号是否与原文同号、斜排是否贴合）需要真机 + 真漫画页面人眼确认。

## 2026-09-29 用户四点反馈的实测定位（斜排 / 按钮 / 接缝重影 / 测试站）

- 设备：`emulator-5554`（重启过一次以清掉 ANR 态）。诊断页 `.verify/web/dump.html` 会把渲染出来的
  覆盖层几何每 1.5s 打成一行 `MTDUMP [...]`（真 DOM 回读），`adb logcat | Select-String MTDUMP` 取。

### 1. 斜排：根因是 OCR 引擎，不是「精细擦字」开关

同一个 `syn1.png`（内含 20° 斜排韩文），固定其它条件只换变量：

| 变量 | 斜排气泡实测 |
|---|---|
| PaddleOCR + 精细擦字**开** | `clip: polygon(4.86% 0%, 100% 66.63%, 95.14% 100%, 0% 33.27%)`、`rotate(11.30deg)`、`tilted:"true"`、`hasFlat:true` |
| PaddleOCR + 精细擦字**关** | **与上面逐字节一致**（`erase_mode inpaint_clean=false policy=FAST_ONLY`、`cleaned_image skipped=all_solid`） |
| ML Kit | 所有气泡 `clip: polygon(0% 0%, 100% 0%, 100% 100%, 0% 100%)`、`rotate(0.00deg)`，无 polygon |

结论：**倾斜处理与清理图开关无关**；ML Kit Text Recognition 只返回轴对齐矩形，没有四边形，
`TextBlock.polygon` / `Bubble.rotationDeg` 全为 null，渲染层拿不到倾角（退化到轴对齐裁剪是正确的降级）。
遗留：ML Kit 下用「内容框内墨迹 PCA 主轴」估计倾角的兜底**未实现**。

### 2. 「所有按钮点不动」：系统弹窗 ANR 导致整帧输入被丢弃

- 复现：`am start` 后快速操作 → `dumpsys window` 焦点变成
  `Window{... Application Not Responding: com.android.systemui}`。
- 根因日志：

  ```
  WindowManager: ANR in ActivityRecord{... com.android.systemui/.mediaprojection.permission.MediaProjectionPermissionActivity t175}
                 Reason: Input dispatching timed out (Application does not have a focused window).
  WindowManager: onPointerDownOutsideFocusLocked, drop event because
                 ActivityRecord{... com.manga.translator.app/.MainActivity t175} is occluded and should not be focused.
  ```

  即：投屏授权弹窗卡死 → 主界面被判为 occluded → WindowManager **主动丢弃**所有 pointer 事件。
  这不是 App 的 Compose 层拦截（`dumpsys window` 里 App 只有 MainActivity 一个窗口，无覆盖窗）。
- 反证（重启到干净态后逐一实测，全部正常响应）：首页「交换语言」（左右语言互换）、
  底栏四个 tab、浏览器顶栏齿轮菜单、以及**翻译进行中**再点齿轮仍能弹出菜单。
- 恢复办法：重启设备 / 关掉卡住的系统弹窗；App 侧不需要（也无法）改。

### 3. 接缝重影：真 bug，已修

- 现象取证：`seambubble.html` 翻译后 a11y 树里**同一句「这是什么？」出现两次**且框不同
  （`[149,1342][714,1479]` 与 `[186,1237][837,1584]`）。
- 根因两条：
  1. `CrossBubblePart.contentRect` 是**上一张图的像素坐标**，下一张图却按**自己的宽高**折百分比；
     两图尺寸不同时 x/宽被缩放错 → 两半错位。
  2. 本图那一半走 DOM 自适应字号拟合，下一张图那一半走 Kotlin 固定字号 → 两半字号不同。
- 修法：`CrossBubblePart` 增 `sourceBoxPercent` + `sourceHeightPx`（携带上一张图**已算好**的百分比框），
  下一张图原样换算（y 下移一整张；高度不同按显示比例折）；`buildScene` 增 `crossFlags`，
  跨图气泡本图那一半写 `fixed_font_percent`（与下一张图同一个 Kotlin 拟合值）。
- 修复前后实测（`seamdump.html`，两图均 760x800）：

  | | x | w | h | font |
  |---|---|---|---|---|
  | 修前 A / B | 33.49 / 39.67 | 18.77 / 22.23 | 16.25 / 16.25 | DOM 拟合 / Kotlin 拟合 |
  | 修后 A / B | **47.16 / 47.16** | **69.86 / 69.86** | **18.59 / 18.59** | **55.4px / 55.4px** |

  两半的框与字号完全一致（A 的 y 中点在 A 坐标 791.6px，B 的 y 中点在 B 坐标 -8.3px ⇒ 全局同为 791.6px）。
- 已知残留：A 那半带 `rotate(0.56deg)`（来自文字四边形），下一张图的续排半没有带旋转 → 强倾角跨页气泡
  仍可能有不到 1° 的差异；要彻底消除需把四边形一并放进 `CrossBubblePart`。

### 4. 测试站

- `.verify/web/index.html` 改为验证站首页（分用例：自检 / 斜排合成 / 跨页接缝 / 硬用例 / 擦字开关对比 /
  诊断工具，每项写明「该看什么」+ 图例 + 入口）。
- 新增 `scripts/serve-verify.mjs`：Node 静态服务，显式 `Connection: close` ——
  `python -m http.server` 单线程会被 WebView 长连接挂死（实测 `net::ERR_EMPTY_RESPONSE`）。
- 新增 `.verify/web/seamdump.html`（接缝专用几何回读）、`pages.html`（原三页漫画）。
- 用法：`node scripts/serve-verify.mjs .verify/web 8899` + `adb reverse tcp:8899 tcp:8899`。

### 5. 本轮回归

- `node app/src/test/js/mt-overlay-acceptance.test.js` → **ALL_OK 116/116**。
- `:app:testDebugUnitTest :core-translate:testDebugUnitTest :core-overlay:testDebugUnitTest :core-store:testDebugUnitTest`
  → **BUILD SUCCESSFUL**。
- 模拟器重装后跨页两图均 `scene_ok bubbles=1`；系统代理已还原为 `10.0.2.2:7890`。

## 2026-09-29 彩色字被压成黑色（墨色量化桶越界截断）

- 现象（用户反馈）：「粉红色的字没有变红，我想要尽可能和原文相似」。
- 定位：`core-overlay/BubbleColorExtractor.sampleInkColor` 里

  ```kotlin
  val bucketCount = 64 * 64 * 64                                       // 262144
  val buckets = IntArray(bucketCount.coerceAtMost(MAX_INK_SAMPLES*2))  // 实际 16384
  if (bucket < buckets.size) { buckets[bucket]++; total++ }            // 静默丢弃
  ```

  桶下标值域 `0..262143`，数组只有 16384 格 → **红通道 ≥ 16 的像素全部统计不到**，
  只留下近黑像素满足 `MIN_INK_SAMPLES`，彩色墨色一律退化成 `DARK_TEXT = #181818`。
- 修复：桶数组开满 `INK_BUCKET_COUNT = 64³`；量化下标抽成 `inkBucketIndex()`（内部 clamp，
  不再静默越界）；新增 `sampled_colors` 日志（`b0=#文字色/#底色`）。
- 回归锁：`BubbleColorRulesTest` 新增
  `everyRgbMapsInsideTheInkBucketArray`（全色域采样点必须落在桶内）与
  `saturatedColorsGetTheirOwnBuckets`（粉/深蓝桶下标必须 ≠ 近黑桶，粉字桶下标 > 16384）。
- 实测（模拟器，`dump.html`，PaddleOCR + 精细擦字关）——`sampled_colors` 日志：

  ```
  b0=#020202/#FFFFFF b1=#020202/#FFFFFF b2=#163E6E/#ACBFD7 b3=#1A1A2E/#BACADE b4=#E2467E/#92AAC9
  ```

  DOM 回读（MTDUMP）：`b4:text color=#e2467e`（原图粉 `#e0447c`）、`b2:text color=#163e6e`
  （原图深蓝 `#143c6e`）、`b3:text color=#1a1a2e`（原图 `#1a1a2e`，逐位一致）。
  修复前这三个气泡全是 `#181818`。
- 回归：Node 验收 116/116；四个模块 JVM 测试 BUILD SUCCESSFUL。

## 2026-09-29 ML Kit 倾角兜底 + 「显示原文」未恢复 + 接缝两半一致

### 1. ML Kit 下的倾角兜底（新增 `TextTiltEstimator`）

ML Kit Text Recognition 只返回轴对齐矩形，`TextBlock.polygon` 恒为 null，斜排遮罩只能退化成水平矩形。
新增 `core-translate/TextTiltEstimator.kt`：内容框内墨迹点云 → **凸包最小面积外接矩形（旋转卡壳）**
→ 长边方向即文字方向。

- **为何不用 PCA**：用户建议的是协方差主轴。实测 3 行 20° 的文字块，PCA 被「行间距的竖直堆叠」拽到
  **25.7°**（差 5.7°）；改用 minAreaRect 后同样数据给 **20.0°**（测试容差收紧到 1.5° 仍通过）。
- 三道闸：墨迹 ≥ 60 像素；矩形长短边比 ≥ 2.0；倾角 ∈ `[3°, 45°]`。竖排直接跳过（主轴≈90°）。
- 角度在**像素空间**求、再转成百分比顶点交给渲染层（渲染层的百分比空间各向异性，角度不能直接搬）。
- 接线：`buildScene` 中 `OCR 四边形 ?: 墨迹估计四边形`；跨图续排复用同一个四边形。
- 设备实测（ML Kit，`dump.html`）：`倾斜的文本` → `rotate(13.04deg)` + `tilted:true`；
  同一气泡 PaddleOCR 的真实四边形给 `11.30°`，两者差 1.7°。

### 2. 点击「显示原文」没有恢复原文（用户反馈 #2/#3 的同一个根因）

- 现象：点一下气泡，译文消失但**底色还在**，原文被糊住 —— 用户描述为「点击之后还有字体，没有恢复原文」。
- 根因：`mt-show-source` 只写了 `.mt-overlay-root.mt-show-source .mt-bubble{...opacity:0}` 与
  `.mt-bubble::before{content:none}`；而**斜排实底的填充块 `.mt-fill-flat` 是裁剪层里的独立 `div`**，
  既不是 `.mt-bubble`，也不是 `::before`，于是完全不受影响，继续盖着原文。
- 修复：按层类型整块隐藏 ——

  ```css
  .mt-overlay-root.mt-show-source .mt-cover-clip,
  .mt-overlay-root.mt-show-source .mt-fill-flat,
  .mt-overlay-root.mt-show-source .mt-fill-tilted,
  .mt-overlay-root.mt-show-source .mt-text-layer{display:none!important;}
  ```

- 真实 WebView 自检（`rendercheck.html`，专门用「斜排 + 实底」气泡）：

  ```
  RC PASS §10 tilted solid really paints a flat fill
  RC PASS §10 show-source hides the cover clip :: none
  RC PASS §10 show-source hides the flat fill :: none
  RC PASS §10 show-source hides the text layer :: none
  RC PASS §10 show-source leaves no cover background image :: none
  RC PASS §10 click again restores the translation
  RC SUMMARY pass=33 fail=0
  ```

### 3. 接缝两半的倾角也要一致

上一轮修了「框」和「字号」，本轮补上「倾角」：`CrossBubblePart` 增加 `sourcePolygon`，
下一张图直接复用上一张图那一半的四边形（只把 y 减 100，两图紧邻）。
不这样做时两半各自估计会得到不同角度（实测 `-4.07°` vs `0.00°`）—— 一半斜一半平比重影还显眼。
设备实测（`seamdump.html`）：两半 `rotate(0.00deg)`、`font=47.6px`，完全一致。

### 4. 本轮回归

- `node app/src/test/js/mt-overlay-acceptance.test.js` → **ALL_OK 122/122**。
- 新增 `TextTiltEstimatorTest`（13 例）与 `core-overlay` 桶值域回归锁；
  `:app :core-translate :core-overlay :core-store` 测试与 `:app:assembleDebug` → **BUILD SUCCESSFUL**。
- WebView 内自检 `rendercheck.html` → **pass=33 fail=0**。
- 模拟器：重装后跨页两图 `scene_ok bubbles=1`；系统代理已还原 `10.0.2.2:7890`。

## 2026-09-29 「怎么翻译不了了」：系统代理打挂 loopback 图片下载

- 现象（用户反馈）：点翻译没有任何反应。
- 定位（logcat）：
  ```
  EmbeddedBrowser: download_http code=502 url=localhost:8901/big1.png
  EmbeddedBrowser: image_fail url=localhost:8901/big1.png
  ```
  设备系统代理 = `10.0.2.2:7890`（宿主机代理）。**WebView 对 loopback 隐式 bypass，页面能打开；
  OkHttp 默认走系统代理，代理连不到设备自身的 localhost → 502**。上一轮验收结束时我把代理还原成
  `10.0.2.2:7890`，于是复现。
- 修复（改 App，不改设备设置）：`BrowserImageDownloader` 增加 `LoopbackBypassProxySelector` ——
  `localhost` / `*.localhost` / `127.0.0.0/8` / `::1` / `10.0.2.2` / `10.0.2.15` 返回 `Proxy.NO_PROXY`，
  其余主机交给系统默认代理。与 Chromium 的隐式 loopback bypass 对齐。
- 验证（**代理保持 `10.0.2.2:7890` 不动**，重装后跑 `big.html`）：

  ```
  proxy(before)=10.0.2.2:7890
  TranslationPerf: erase_mode: 1407ms | inpaint_clean=false policy=FAST_ONLY
  EmbeddedBrowser: scene_ok bubbles=7 rebuilt=true host=document done=1
  proxy(after)=10.0.2.2:7890
  ```

  修复前同样配置：`download_http code=502` → `image_fail` → 无任何翻译产出。
- 非 loopback 主机（真实漫画站）行为不变，仍走系统代理。
- 回归：Node 验收 122/122；四个模块 JVM 测试 BUILD SUCCESSFUL。



## 2026-09-30 用户三连反馈修复：定位扩张 / 斜排倾角 / 接缝定位（含清理图）

用户原始反馈（逐字）：

> 1. 当前定位不准，扩张似乎太大了，以至于超出了气泡框
> 2. 倾斜文本翻译过后并不倾斜。倾斜文本的清理图也消失了
> 3. seam 图定位不准，清理图不见了。希望只有一个清理图，而不是上下都有一个层

后续追加：

> 我的希望是不进行扩展，要扩张也只是几个像素，顶多遮住 OCR 识别没有识别到的像素点。

### 结论速览

| # | 现象 | 根因 | 修改位置 | 状态 |
|---|---|---|---|---|
| 1 | 覆盖框比文字大 30%+，超出气泡 | 盖板用的是**排版框**（字形框每侧再扩 15%）；外扩的 px→% 换算用了拼接图尺寸 | `WebtoonImageTranslator.resolveFinalBubbles`（新增 `coverBoxPercent` / `COVER_PAD_PX=3`）、`inflatePercentQuadByPx` / `expandPercentBoxByPx` | 已修 |
| 2a | 斜排译文不斜 | 倾角/四边形只在宿主算好但没下发，且字高取了**外接框高**（20° 时是真实字高的 3.3 倍） | `Bubble.rotationDeg` / `polygon` 下发；`quadLineThicknessPx`（取四边形厚度） | 已修 |
| 2b | 斜排「清理图」看不出效果：译文旁还飘着原字 | ①斜排气泡被假阳性区域判成 `solid` → 盖板纯色平涂，渐变背景上肉眼可见一块平行四边形；②清理图的「外框保护」被同一个假阳性区域带偏，mask 上边界被推到文字框**下方 56px** → 原字上半截从没被擦过 | `BubbleFirstRegionPlanner.isSolidBubbleRegion`、`CleanedImageProcessor.isOuterGuardUsable` | 已修 |
| 3a | 接缝气泡上下两端露字 | `WritableAreaRefiner.scanInset` **判据方向写反**：把「文字内部的字间空隙」当成边界，缩到 217..484（首字切一半、尾字只剩前半） | `WritableAreaRefiner.scanInset` + 整段采样 + `DARK_RATIO_THRESHOLD=0f` | 已修 |
| 3b | 接缝处「上下各一层清理图」 | 跨图移交用的是**只在有倾角时才有值**的 `maskPolygonPercent`，轴对齐气泡退回拿排版框当盖板，比本图那块大 30% | `CrossBubblePart.sourcePolygon = geometry.regionPolygonPercent` | 已修 |

---

### 第 1 轮：定位与扩张

- **盖板框与排版框彻底分开**：渲染层此前拿 `x/y/w/h`（= 排版框 = 字形框每侧再扩 `LAYOUT_GROW_RATIO/2`，
  合计 1.3 倍）当盖板。现在宿主单独下发 `cover_box`（= 字形范围 + `COVER_PAD_PX = 3f`），
  排版框只用于译文排版（用户看不见）。
- **px→% 换算必须用原图尺寸**：`maskRectPercent` / `maskQuadSource` 都是**原图**百分比，
  之前配的是拼接图高度（800+152=952）→ `padY` 只有 `3×800/952 = 2.52px`，垂直方向静默少遮 0.5px。
- **实底填充范围砍掉「∪ 气泡外框」**：历史实现是「文字框 ∪ fill_box(气泡外框，限幅 1.4 倍)」，
  矩形四角会伸到圆形气泡轮廓之外 → 正是用户说的「扩张太大、超出气泡框」。现在 `fill_box = null`，
  只按字形轮廓裁剪。
- **外框只在「真的包住字形」时才允许用来收边**（`growTowardOuter` 的 `outerUsable`）：
  区域检测偶尔给出与文字**错位**的外框（实测 syn1 蓝字斜排没有气泡轮廓，
  外框被检成画面底部 `y 981~1399` 而字形在 `y 945~1051`），拿它收边会把排版框压成一条细缝。

### 第 2 轮：斜排倾角

- 倾角与四边形全链路下发：`Bubble.polygon`（0-100 百分比四点，0=左上/1=右上/2=右下/3=左下）+ `Bubble.rotationDeg`。
- **字高必须取四边形厚度**（短边），不能取外接框高：斜排包络高 = 行长·sinθ + 字高·cosθ，
  实测 19° 斜排真实字高 38px、外接框高 125px（3.3 倍）→ 译文字号被算成 3 倍
  （用户反馈「倾斜文字的高度比起原文太高」）。新增 `quadLineThicknessPx`。
- **倾角估计窗口不能被精修带小**：精修一旦砍掉两端，最小外接矩形就只包得住中段 → 两端露字。
  `tiltWindow = unionRect(refinedRect, prep.ocrRects[index])`，保证窗口不小于 OCR 原始框。

### 第 3 轮：接缝（seam）

**3a. `scanInset` 判据方向写反（头号根因）**

```kotlin
// 旧：条带还有墨 → 继续往里缩，遇到空白就停
// 新：条带还是背景 → 继续往里收；一碰到有墨的条带就停
internal fun scanInset(limit: Int, brightCut: Float, sampler: (Int) -> StripSignal): Int {
    var inset = 0
    while (inset < limit) {
        val signal = sampler(inset)
        if (!WritableAreaThresholds.looksLikeBackground(...)) break
        inset += STRIP_STEP_PX
    }
    return inset.coerceAtMost(limit)
}
```

离线逐像素复刻（`logs/refine_probe.py`，可切 `mode=old/new`、`band=True/False`）与真机 `refine_boxes` 完全一致：
接缝气泡 OCR 框 `174..551`、真实墨迹 `174..543`，旧算法给出 `217..484` ——
左端整个首字被切掉一半、右端尾字只剩前半。

**3b. 采样带太窄 + 暗比阈值太宽**

`sampleVerticalStrip` / `sampleHorizontalStrip` 原来只采 ROI 的 22%~78%，漏掉字的最上/最下一行；
配合 `DARK_RATIO_THRESHOLD = 0.18f`，「只有 5.7% 深墨的首行」被判成空白。
实测接缝气泡文字墨迹 `y 751..833`，首行 751~752 深墨占比 0.057、末行 831~833 只有 0.088
→ 上下各收掉 2px，盖板底边只比墨迹多 1.5px，抗锯齿灰边露在外面（截图里「这是什」下面那条细横线）。
现改为整段采样 + `DARK_RATIO_THRESHOLD = 0f`。

**3c. 跨缝两层盖板**

`CrossBubblePart.sourcePolygon` 此前传 `maskPolygonPercent`，而它**只在有倾角时非 null**；
轴对齐的跨图气泡在下一张图里退回拿 `x/y/w/h`（排版框，大 30%）当盖板 → 肉眼「上下两个盖板不一样宽」。
改用 `geometry.regionPolygonPercent`（两种情形都有值，语义就是「字形 + 几像素」）。

**3d. 接缝下半截改用本图自己的清理图**

新增 `crossPrepareBubbles`：上一张图移交的跨图气泡在本图**先参与 mask/擦字**，
于是本图渲染这一半时直接用**本图自己的清理图**（整页同一张纹理），
不再需要「从上一张图裁一块贴片贴过来」—— 那是「上下各一层清理图」和贴片错位的来源。

### 第 4 轮：斜排「清理图」到底去哪了（本轮新增）

第 3 轮结束后 `refine_boxes` 已达标，但斜排页截图里仍能看到 —— 顺着查下去发现**两个**独立根因。

**4a. 假阳性区域 → 斜排气泡被误判成实底**

区域检测器（`BubbleRegionDetector`）是「亮斑轮廓」法（阈值 208 + 形态学闭运算）。
漫画里整片浅色/渐变的页面本身就会被检出一个巨大亮块。实测（`logs/h2-tilt.logcat`）：

```
seam_boxes: d2=981~1399/929~1066/ocr=(22,66,31,9)
```

斜排文字在 `y 929..1066`，却被分到一个 `y 981..1399` 的区域 —— **区域根本不含文字**。
旧判据是「`regionType == BUBBLE` 就 `solid`」，于是盖板用采样底色 `#ACBED6` **纯色平涂**，
而那一带背景其实是平滑渐变（实测 `#A8BCD5` → `#B2C3DA`），画面上出现一块肉眼可见的平行四边形：

```
logs/h2-tilt.png @ (450,1450) = (173,192,215)   ← 平涂色 #ACBED6 = (172,190,214)
logs/h2-tilt.png @ (700,1450) = (182,198,220)   ← 真实背景
```

参考项目 `extension/src/background/modules/baidu-results.js:42` 的语义是
「拿不到可信容器（`adaptiveBackground`）就一律 `"none"`」，绝不画纯色补丁。本工程对齐：

```kotlin
private fun isSolidBubbleRegion(region, rawLeft, rawTop, rawRight, rawBottom): Boolean {
    if (region.regionType != BubbleRegionType.BUBBLE) return false
    val slack = REGION_CONTAIN_SLACK_PX   // 4f
    return region.left <= rawLeft + slack && region.top <= rawTop + slack &&
        region.right >= rawRight - slack && region.bottom >= rawBottom - slack
}
```

**4b. 清理图的「外框保护」被同一个假阳性区域带偏（本轮真正让「原字消失」的那一刀）**

`CleanedImageProcessor.build` 用区域框给「外扩」设上限（避免把气泡描边 inpaint 掉）：

```kotlin
val top = (roi.top - padY).coerceAtLeast(outerGuardTop(outer))   // outer.top + 4
```

区域框是 `981..1399`、内容框是 `929..1066` → `top = max(929-5, 985) = 985`，
**mask 的上边界被推到文字框下方 56px**，斜排文字左上角那半截（「기울」）从来没进 mask。
第一次修复后真机日志正好把这个坐实：

```
# 只改 4a（logs/k2-tilt.logcat）
erase_apply: narration_patched=2 cleaned=true cleaned_patched=4     ← b2 已转为非实底 ✓
refine_boxes: b2=283x136@206,929->283x136@206,923                   ← 不再缩减 ✓
erase_quality: residual_avg=0.14 residual_max=0.41 worst=none:283x136 residue=1   ← 41% 原字残留 ✗
```

（日志里还能看到 `narration_patched` 从 1 变 2 —— 这行就是「b2 现在是 `none` 了」的现成证据。）

修法：外框只在**真的包住内容框**时才允许当上限（`isOuterGuardUsable`，纯函数便于单测）：

```kotlin
internal fun isOuterGuardUsable(outerLeft, outerTop, outerRight, outerBottom,
                                contentLeft, contentTop, contentRight, contentBottom): Boolean =
    outerLeft <= contentLeft && outerTop <= contentTop &&
        outerRight >= contentRight && outerBottom >= contentBottom
```

### 设备实测证据

环境：Pixel_8（emulator-5554，1080×2400，API 36.1，x86_64），
页面 `http://127.0.0.1:8899/v-tilt.html`、`v-seam.html`（`adb reverse tcp:8899`），
驱动脚本 `scripts/verify-browser-page.py`，APK 全量重建（118 任务全部 executed）。

**斜排页（`logs/m1-tilt.logcat`，修复后）**

```
bubble_region_stats: detected=2 blocks=4
seam_boxes:  d0=292~363/302~353  d1=185~248/194~239  d2=981~1399/929~1066  d3=591~647/599~639
erase_apply: fast=0 edge=0 opencv=0 patches=4 narration_patched=2 cleaned=true cleaned_patched=4
refine_boxes: b0=296x50@245,302->280x45@261,304  b1=280x44@261,194->279x44@261,194
              b2=283x136@206,929->283x136@206,923  b3=308x40@99,599->308x40@99,597
sampled_colors: b0=#020202/#FFFFFF b1=#020202/#FFFFFF b2=#163E6E/#ACBED6 b3=#E2467E/#97A7C6
erase_quality: bubbles=4 checked=3 residual_avg=0.00 residual_max=0.00 residue=0 missing=0
ink_tilt: none(296x50) none(280x44) ok(19deg,283x142) none(308x42)
render_scene: owned=4 incoming=0 cleaned=true patches=0
render_done:  bubbles=4 incoming=0 crossOut=0 mode=DOM_SCENE
```

关键指标：`residual_max 0.41 → 0.00`（原字擦干净）、`narration_patched 1 → 2`（b2 走清理图路径）、
`ink_tilt ok(19deg,283x142)`（倾角保住、窗口没被裁）。

**「平涂块硬边消失」的像素级取证**（`logs/h2-tilt.png` = 修复前，`logs/m1-tilt.png` = 修复后）：

不要用「区域内某一点的颜色」下结论 —— 那一带正好和背景同色，看不出来。
要跨过平涂块的**斜边**扫描，看有没有「一整段亮度被钉死」的平涂特征：

```
沿 x=300 从 y=1330 到 1620 每 4px 采一次亮度（设备坐标）

修复前 h2: … 183.0, | 187.4 187.4 187.4 187.4 187.4 187.4 187.4 187.4 187.4 187.4 187.4 187.4 | 185.8, 185.8 …
                        ↑ 跨边 4.4 亮度硬台阶      ↑ 连续 12 个采样点恒为 187.4 = 纯色平涂
修复后 m1: … 183.0, 183.9, 184.9, 184.9, 185.9, 185.9, 186.9, 187.9, 188.9, 188.9, 188.9, 187.9, 187.8, 186.8, 185.8 …
                     ↑ 最大相邻台阶 1.0，全程连续渐变（中间 188.9→187.8 的起伏是译文笔画边缘）
```

- x=300：`max|相邻台阶|` **4.4 → 1.0**；
- x=340：修复前同样有「连续 25 个采样点恒为 187.4」的平涂段，修复后消失；
- x=620 / 680（已在平涂块之外）：两版逐点完全相同。

**接缝页（`logs/m2-seam.logcat`，修复后回归）**

```
[29511548] refine_boxes: b0=377x83@174,751->377x83@174,751        ← 不再缩减 ✓
[29511548] erase_quality: residual_avg=0.00 residual_max=0.00 residue=0
[29511548] cross_out_probe: owned=1 bubbles=1 botH=152 flags=[1] probe=i0:ok
[29511548] render_done: bubbles=1 incoming=0 crossOut=1 mode=DOM_SCENE
[29512430] render_scene: owned=0 incoming=1 cleaned=true patches=1
[29512430] render_done: bubbles=0 incoming=1 crossOut=0 mode=DOM_SCENE
```

两页各画一层盖板，各自只在本图范围内（页 1 可见部分 / 页 2 补条），合起来正好拼成一个气泡。
`logs/m2-seam-zoom.png` 里椭圆气泡只剩「这是什么?」，无残留、无双层。

**零回归核对**

把修复前后的两张截图逐像素对比（`logs/h2-tilt.png` vs `logs/m1-tilt.png`，同为 1080×2400）：

- 椭圆气泡区（设备 `x 300..760, y 480..745`，**121,900 像素**）→ **有差异的像素 0 个，最大差异 0** ——
  正常实底气泡的盖板几何、配色、字号一个像素都没动。
- 全图 5px 网格采样：差异 >3 的点共 498 个，其中 **471 个落在斜排区域 `(200,1330)-(740,1600)` 内**；
  区域外 27 个全部落在页面上方的状态栏/地址栏（时钟从 `6:25` 走到 `6:52`、地址栏高亮差异，
  最大差异 255 出现在 `(120,55)` 的时钟区）—— **内容区里唯一发生变化的就是斜排那一条**。

### 自动化回归

- `./gradlew :app :core-capture :core-overlay :core-store :core-translate:testDebugUnitTest --rerun-tasks`
  → **289 tests, 0 failures, 0 errors**（app 69 / core-capture 35 / core-overlay 28 / core-store 76 / core-translate 81）。
  结果 XML 归档 `logs/quality-20260930/`。
  本轮新增 6 例：
  - `BubbleFirstRegionPlannerTest`：`regionHuggingTextStaysSolid`、
    `regionNotContainingTextFallsBackToNone`、`toleratesFewPixelRegionShrink`（用真机实测数值）
  - `CleanedImageInkMaskTest`：`outerBoxThatDoesNotContainTheTextIsNotUsableAsGuard`、
    `outerBoxThatFullyContainsTheTextIsUsable`、`outerBoxTouchingTheContentBoundaryIsStillUsable`
- `node app/src/test/js/mt-overlay-acceptance.test.js` → **ALL_OK 125/125**。
- `:app:clean :app:assembleDebug --rerun-tasks --no-build-cache` → **BUILD SUCCESSFUL，118 任务全部 executed**；
  APK 健康校验 `classes7.dex | strings | grep -c TranslatorApplication` = **3**（非坏档）。

### 本轮未覆盖 / 遗留

- **只有一台模拟器（emulator-5554）**；`connectedAndroidTest` 未跑（重装会清 `/sdcard` 素材）。
- **悬浮球（覆盖层）链路未上真机复跑**：`isSolidBubbleRegion` 同时作用于
  `TranslateOrchestrator.detectBubblesBubbleFirst`，逻辑同一份代码，但没有真机截图证据。
- **椭圆气泡右缘有 1~2px 极浅残影**（`logs/m1-oval-z2.png` 放大可见）：
  refine 把右边界收到 `540` 而原文墨迹到 `541`，盖板 +3px 后仍差约 1px。
  **这不是本轮引入的**（改动前后该区域逐像素一致），属于「盖板贴合」的既有亚像素边界，
  用户本轮未反馈，未处理。若要处理，方向是让 `COVER_PAD_PX` 或墨迹收紧的下界与 OCR 框取并集。
- `logs/` 与 `.verify/`：`logs/` 已在 `.gitignore`；**`.verify/` 目前是未跟踪目录**
  （验证页 + 素材，约 1.6MB，含 `v-tilt.html` / `v-seam.html` / `mtdump.js` / `syn1.png`）。
  是保留为可复用夹具还是加进 `.gitignore` / 删除，待用户定。
- 模拟器系统代理已还原 `10.0.2.2:7890`。

---

## 2026-09-30（续）接缝覆盖层：译文「超出原来的字体位置」

用户反馈：「虽然已经是一块了。但是字超出了原来的字体位置了」
（上一轮：接缝从「上下两半各一套」改成了「一条带 = 一块盖板 + 一份译文」）。

### 真机取证（Pixel_8 / emulator-5554，`v-seam.html`）

`.verify/web/mtdump.js` 增加文字层实测字段后拿到的关键读数（修复前）：

```
font: 47.8px   文字层盒子: 266x59  （内容宽 233 = 266 − 2×0.34em 内边距）
内容块实测:     233x113            lines: 2        行高 56.4px
```

**内容高 113、盒子只有 59 → 文字上下各溢出 27px（设备 71px）**，这就是「超出原来的字体位置」。
逐像素量化（同页两版截图，`logs/n2-*` vs `logs/m2-*`）：文字/椭圆 =
宽 0.435 高 **0.572**（旧两半）→ 宽 0.435 高 0.572，两版一致 ⇒ **不是接缝改造引入的**，
而是接缝气泡的**字号/排版框口径**问题。

### 根因（两条，互不排斥）

1. **折行**：译文「这是什么？」= 5 个全角字。宿主算固定字号时按
   `rect.width() − 3px×2`（= 484 图像像素）估可用宽度 → 认为放得下；
   而真正排版的是 DOM 盒子，`.mt-bubble` 有 `padding:0.12em 0.34em`，
   em 内边距**随字号缩放** → 真实内容宽只有 ≈430，5 字放不下 → CJK 折行。
   折行后「行首禁则」又把 `？` 前面的 `么` 一起挪到第二行 → 3+2 两行 → 文字块高度直接翻倍。
2. **两套度量**：宿主用 `Typeface.SERIF` 的 Paint 量宽，DOM 用 `Source Han Sans SC/Noto Sans SC`；
   同一段文本两者估算不一致，就算宽度估算对了也可能差一点点。

### 修改位置

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/.../WebtoonImageTranslator.kt` | `BubbleImageRenderer.contentWidthPx` 改为纯函数并新增 `horizontalPaddingEmRatio`；`fitFontSize` 每档字号单独扣 `2×比例×字号`（画布路径不传 → 行为不变）；跨图气泡调用处传 `DOM_BUBBLE_PADDING_EM = 0.34f` |
| 同上（`buildSeamBubble`） | 接缝带**不再下发** `fixed_font_percent` —— 一条带本来就是**一个**渲染单元，不存在「两页字号必须一致」，交给渲染层用真实盒子 + 真实字体自己拟合；两半渲染兜底仍锁字号 |
| `app/src/main/assets/mt-overlay-renderer.js` | `fitBubbleFontSize` 中固定字号从「硬值」改成「只收不放的上限」：放得下照抄、放不下二分收小（兜住任何宿主/DOM 口径差） |
| `app/src/test/js/dom-stub.js` | `__mtStubProbeScroll` 支持函数形式（按探针当前字号给不同结果），用于验二分收字号分支 |

### 验证结果

| 证据 | 结果 |
| --- | --- |
| 真机 MTDUMP（`v-seam.html`，实底接缝气泡） | **修复前** font 47.8px / content 233x113 / lines **2** / 盒子 266x59（溢出）→ **修复后** font 39.9px / content 239x47 / scroll 239x52 / lines **1** / 盒子 266x59（装得下） |
| 真机链路日志 | `seam_scene: mode=band bubbles=1 band=496x114@114,736 bg=solid`；`seam_dispatch ok=true bubbles=1 covers=1`；`seam_suppress` 两图各摘 2 个半块 |
| 真机截图 `logs/r1-seam.png` | 椭圆气泡内只有一行「这是什么？」，居中、无溢出、无残留原文 |
| 复杂背景跨缝（`.verify/web/v-seam-none.html`，洋红旁白被 y=619 从字中切开） | `seam_scene: mode=band band=366x66@62,588 bg=cleaned`；截图 `logs/r2-seam-none.png` 只有一行「沉闷的颜色附近」+ 一块盖板 + 一层清理图纹理 |
| 文字/椭圆归一化比例（原图 0.635/0.257） | 旧 0.435/**0.572**（高 2.2×，溢出）→ 新 0.549/**0.210**（高 0.82×，完整落在气泡内） |
| `node app/src/test/js/mt-overlay-acceptance.test.js` | **ALL_OK 148/148**（新增 §12.3 固定字号只是上限，4 条断言） |
| `./gradlew :app:testDebugUnitTest --rerun-tasks` | **80 tests / 0 failures**（`BubbleFontRulesTest` 16 例，新增 4 例含真机数值回归）；83 任务全部 executed（非 UP-TO-DATE） |
| `:app:assembleDebug` + APK 健康校验 | BUILD SUCCESSFUL；`classes7.dex … grep -c TranslatorApplication` = **3**；APK 内 `assets/mt-overlay-renderer.js` 与源码同为 **2314 行** |
| 证据归档 | `logs/quality-20260930/seam/`（8 份测试 XML、JS 验收输出、修复前后截图与 logcat） |

### 本轮未覆盖 / 遗留

- **只有 emulator-5554 一台**参与验证（另一台 `127.0.0.1:16384` 是用户新开的 MUMU，未触碰）。
- 接缝气泡译文当前是原文高度的 **0.82×**（不是 1:1）：排版框 108 图像像素 ÷（1.18 行高 + 0.24em 上下内边距）
  ≈ 最大 76 图像像素字号，这已经是「不溢出、不折行」前提下的上限。再想放大只能改排版框或行高/内边距，
  属于全局排版口径，本轮未动。
- `v-seam-none.html` 那一页的 `MTDUMP` 超过 logcat 单行上限被截断（该页有 3 个气泡、节点多），
  该页只用 `seam_scene` / `seam_dispatch` 日志 + 截图判读，没有 DOM 级读数。
- 复杂背景接缝带清理图右端可见一条**极浅的矩形色差**（inpaint 兜底策略的产物），用户未反馈，未处理。
- `.verify/`（未跟踪目录，约 1.6MB）保留 / 加 `.gitignore` / 删除 —— 仍待用户定。
- **（本轮新观察，写入 HumanTODO 第 5 条）** 复核 `logs/r2-seam-none.png` 时发现：页面右缘（图像 x≈760、
  y≈950 一带）有**竖排原文笔画未被擦除、也未翻译**（`logs/z-r2-right.png` 放大可见白色描边残留）。
  该位置**在接缝带（x 62..428、y 588..654）之外**，且本次改动只作用于接缝带与两页各 2 个半块节点的摘除，
  结构上不构成因果 → 判为**既有问题**（疑似 OCR 未检出的边缘竖排文字），本轮未处理、未深挖。
  中途 `m2-seam.png` 的同一区域对照不可用（两张截图滚动位置不同，看到的是椭圆右缘，非同一区域），
  所以「改造前就有」这一点目前是**结构性推断，不是像素级 A/B 证据**。

## 2026-09-30（续二）描边开关：只在「背景多色」或「原文自带描边」时才加

用户反馈（逐字）：

> 有个问题。那个字体的白边黑边。要不只在背景有多种颜色的时候有吧。我看那个倾斜的文本，可能是白边的问题，看不清。

> 除了背景复杂，还有就是原文有的时候，也需要黑边白边

### 结论速览

| 用户诉求 | 结论 |
| --- | --- |
| 白边/黑边只在「背景有多种颜色」时才有 | 已实现 —— 新增 `BackgroundOutlinePolicy`，按清理图亮度 5%~95% 分位跨度判「多色」 |
| 原文自带黑边/白边时，译文的描边不能吞掉 | 已实现 —— `BubbleColorExtractor` 统计墨迹里近白/近黑像素占比，≥15% 即判「原文有描边」，直接放行 |
| 「倾斜的文本看不清」 | **真因不是白边本身，是字号被算小了 3.5 倍**（单位错误）。已修复 |

### 根因：「倾斜的文本看不清」

真机诊断（`v-tiltonly.html`：580×340 的最小页，只有一段 20° 斜排韩文旁白 + 平滑蓝渐变）：

```
改前 ink_tilt: ok(19deg,283x140) |b0 lineH=10.7 quad=10.7 lines=1 mask=283x134 src=580x340 poly=0 maskQuad=4
改前渲染层 src.fhp = 3.13        → 字号被 FONT_MIN 兜在 10px
逐像素实测真值（沿墨迹两条长边）= 37.7px / 11.09%
```

`maskQuadSource` 由 `estimateInkTiltQuadStitched` 产出，口径是**原图百分比（0-100）**；
但量厚度时被直接当**像素**四边形传进 `quadLineThicknessPx`，于是又混进了横纵两轴的缩放比：

```
10.7  ≈  37.7 × (580 / 340) / 6
```

后果是双重的：字号小 3.5 倍，**并且**给这 10px 的小字挂了 1.8px 白描边
（`STROKE_RATIO=0.085` 撞上 `clamp[1.8, 4.2]` 的下限）→ 笔画被白边糊成一团。
**所以「白边让字看不清」这个观感，一半是白边、一半是字号。**

### 修改位置

| 文件 | 改动 |
| --- | --- |
| `app/.../app/QuadMetrics.kt`（**新建**） | `quadLineThicknessPx` 从类内提到顶层（改 `internal`，单测可达）；新增 `percentQuadThicknessPx(quad, srcW, srcH)`：先把百分比四边形按原图宽高换算成像素，**再**量厚度 |
| `app/.../app/WebtoonImageTranslator.kt` | ① `quadThicknessPx = percentQuadThicknessPx(maskQuadSource, srcW, srcH)`；② `ImageVisualPrep` 新增 `strokeFlags: Map<Int, Boolean>`（**缺项 = 保留描边**，老场景 JSON 行为不变）；③ `prepareImageVisuals` 逐气泡算 `strokeFlags` 并打 `outline_need` 诊断；④ `buildSeamBubble(entry, band, cleanedImage)` 增加 `needsStroke`；⑤ owned 气泡与跨图 `c$index` 两条路径补 `needsStroke`；⑥ `drawText(needsStroke)`（旁白画布路径）；⑦ `ink_tilt` 诊断补齐 `quad / lines / mask / src / stitch / maskQuad / vertical` |
| `core-overlay/.../BackgroundOutlinePolicy.kt`（**新建**） | `needsOutline(background, rect, bgType, originalHadOutline)`：solid → false；原文有描边 → true；样本不足 → true；否则按「亮度 5%~95% 分位跨度 > 56」判多色。配套 `brightnessSamples`（步长 √(面积/1024)，只取 alpha≥200）与 `isBusy` |
| `core-overlay/.../BubbleColorExtractor.kt` | `BubbleColors` 新增 `originalHadOutline`；采样结构体升为 `InkSampling`（带 `nearWhitePixels` / `nearBlackPixels`）；新增 `originalHadOutline()`（近白/近黑占墨迹 ≥ 15%，并用「文字色本身是否近白/近黑」排除白字/黑字）；新增 `isNearWhitePixel` / `isNearBlackPixel`（近黑额外要求**无彩**，避免深蓝字被当成黑边） |
| `app/.../app/BrowserSceneBuilder.kt` | `SceneBubble.needsStroke = true`（缺省，老 JSON 行为不变）；JSON 追加 `needs_stroke`；`buildSeamSceneJson` 复用同一个 `bubbleJson`，接缝路径自动带上 |
| `app/src/main/assets/mt-overlay-renderer.js` | 新 CSS `.mt-bubble.mt-no-stroke{-webkit-text-stroke:0 transparent;text-shadow:none!important;}`；节点写 `dataset.mtNoStroke`；新增统一入口 `applyStrokeWidth(node, fontSize)` 并替换两处旧调用 |

**为什么「只把宽度置 0」不够**：`.mt-bg-none` 除 `-webkit-text-stroke` 外还挂着
`text-shadow:0 0 1px <描边色>`（抗锯齿用）。只把描边宽度置 0，那 1px 阴影还在 → 笔画仍然发虚。
所以关描边必须同时 `text-shadow:none!important`，这就是 `.mt-no-stroke` 这个类存在的唯一理由。

### 验证结果

**A. 字号链路（`v-tiltonly.html`，改前 → 改后）**

| 指标 | 改前 | 改后 | 逐像素真值 |
| --- | --- | --- | --- |
| `ink_tilt \|b0 lineH / quad` | 10.7 / 10.7 | **37.7 / 37.7** | 37.7 |
| 场景 `src.fhp` | 3.13 | **11.09** | 11.09% |
| 渲染层字号 | 10px（被 `FONT_MIN` 兜底） | **25.6px** | — |
| 排版框 / 内容框 / 行数 | — | 220×103 / 200×99 / 1 行（lh 30.2px） | — |
| 译文厚度 ÷ 原文厚度 | **0.34** | **0.89** | 1.0 |

**B. 描边开关**

| 场景 | `outline_need` | 渲染层读数 |
| --- | --- | --- |
| 斜排旁白 + 平滑蓝渐变（原文无描边） | `b0=plain` | `ink.strokeW=0px`、`shadow=none`、`cls` 含 `mt-no-stroke` |
| 实底气泡跨缝（`v-seam.html`） | `b0=solid` | 不描边（solid 分支本来就不描边） |
| 同一区域近白像素（`v-seam-none.html` 斜排） | — | **1918 → 0** |
| 原文自带白描边（合成素材） | `outline` | 保留描边（单测锁定） |

**C. 回归（三条路径）**

- `v-seam.html`：`seam_scene: mode=band bubbles=1 band=496x114@114,736 bg=solid own=[b0] next=[c0]`、
  `seam_dispatch ok=true bubbles=1 covers=1`、`seam_suppress` 各摘 2；
  `s0:text` 仍 `font 39.9px / lines 1`（无回归）。
- `v-seam-none.html`：`outline_need: b0=plain b1=solid b2=solid`、
  `seam_scene: mode=band band=366x66@62,588 bg=cleaned`、`seam_suppress` 各摘 2；
  `ink_tilt: ok(19deg,298x148) |b0 lineH=37.3 quad=37.3`；
  斜排文字 `墨点 749→2425`、`近白 1918→0`、`厚度 34.7→42.6`（原文 287.7 / 37.9）。
- 上一轮两个老坑复跑确认未复发：`band_left_px` 漏传（本轮 `band=496x114@114,736` 正常）、接缝译文折行溢出。

**D. 自动化**

| 命令 | 结果 |
| --- | --- |
| `node app/src/test/js/mt-overlay-acceptance.test.js` | **ALL_OK 154/154**（§12.3 新增接缝带 `needs_stroke:false` 用例；§12.4 新增 `strokeSwitch` 4 例） |
| `./gradlew :app:testDebugUnitTest --rerun-tasks` | **83 tests / 0 failures**（`BubbleFontRulesTest` 16→19，新增百分比四边形换算 + 真机数值回归） |
| `./gradlew :core-overlay:testDebugUnitTest --rerun-tasks` | **42 tests / 0 failures**（`BackgroundOutlinePolicyTest` 新建 6 例；`BubbleColorRulesTest` 8→15） |
| `:app:assembleDebug` + APK 健康校验 | BUILD SUCCESSFUL；`classes7.dex … grep -c TranslatorApplication` = **3**；APK 内 `assets/mt-overlay-renderer.js` = 源码 = **2338 行** |
| 证据归档 | `logs/quality-20260930/outline/`（3 份测试 XML、JS 验收输出 `ALL_OK 154/154`、改前/改后截图与 logcat） |

### 本轮未覆盖 / 遗留

- 只有 **emulator-5554** 一台参与验证；用户新开的 MUMU（`127.0.0.1:16384`）全程未触碰。
- **「背景多色」的阈值 `BUSY_LUMA_RANGE = 56`（5%~95% 分位跨度）是在合成素材上标定的**，
  没拿真实漫画页校准。真实照片背景（尤其低对比度实景照片，以及「大面积单色 + 一小块暗纹」这类）
  可能需要调阈值或改成「离群占比」判据 —— 等用户用真实页扫完再定。
- 「原文自带描边」的 `OUTLINE_MIN_RATIO = 15%` 同理，只过了合成用例 + 真机素材（该素材实测近白/近黑 **0** 点）。
- **描边颜色还没做到「照搬原文」**：`BubbleColors.strokeColor` 仍是
  `if (relativeLuminance(textColor) < 0.45) WHITE else BLACK`，即由**译文文字亮度**决定，与原文描边是黑是白无关。
  多数场景下结果自动一致（深字配白边 / 浅字配黑边），但原文若用非常规搭配（例如深字配黑边、彩色描边），
  译文不会照搬。本轮只做了「**要不要**描边」，没做「**用什么颜色**描边」。已写入 `HumanTODO.md` 待用户裁决。
- `v-seam-none.html` 页 `MTDUMP` 仍超 logcat 单行上限被截断（既有问题，未处理）。
- 用户上一轮遗留未决项仍在 `HumanTODO.md`：译文 0.82× 是否可接受、复杂背景接缝带右端极浅色差是否羽化、
  右缘竖排原文未擦、`.verify/` 目录去留。

## 2026-09-30（续三）ML Kit 置信度实测 + 「源语言韩文，页面里混着日文」怎么办

### 起因

用户问「ML KIT 有置信度吗，那个日文应该怎么处理」，并纠正：「（夹具图里）除了韩文还有一个日文」
——指的是 `syn1b.png` 右缘那段**竖排日文「これは縦書きです」**；且观察到「**那个日文被擦掉了，
但没有被识别正确**」。

**先更正一条旧判断**：上一轮把该现象记成「右缘竖排原文既没擦除也没翻译（疑似 OCR 漏检）」——**错了**。
用户是对的：那段是**被擦了、而识别为空**。下面实测给出机制。

### 一、ML Kit 确实有置信度（实测，bundled 版）

依赖是 bundled：`com.google.mlkit:text-recognition{,-chinese,-japanese,-korean}`（非 `play-services-mlkit-*`），
因此不受官方那条「unbundled + Play 服务 < 22.30 返回 0」的限制。探针：
`app/src/androidTest/.../MlKitConfidenceProbeTest.kt`（程序生成图，不依赖素材），原始输出
`logs/quality-20260930/ja-probe/mlkit-confidence.txt`。

| 脚本 / 用例 | Line 置信度 | Symbol 置信度区间 | 非零 |
| --- | --- | --- | --- |
| 拉丁 `Hello confidence probe` | 0.897 | 0.703 ~ 0.961 | 20/20 |
| 中文 `你好世界` | 0.845 | 0.633 ~ 0.945 | 4/4 |
| 日文横排 `こんにちは世界` | **0.926** | 0.855 ~ 0.957 | 7/7 |
| **日文竖排 `こんにちは`** | **0.411** | 0.090 ~ 0.809 | 6/6 |
| 韩文 `안녕하세요 세계` | 0.878 | 0.789 ~ 0.961 | 7/7 |

结论：
- **CJK 脚本同样返回真实置信度**（不是 0），逐字符（Symbol）也有；
- `recognizedLanguage` 在 **Line 级**可用（实测返回 `ja` / `ko` / `zh` / `en`），**Block 级返回 `und`**；
- **`Text.TextBlock` 本身没有 `getConfidence()` / `getRotationDegrees()`**（编译期确证：写 `block.confidence` 报
  Unresolved reference），要取只能在 Line / Element / Symbol 上取；
- **竖排文字的置信度显著偏低**（0.411 vs 横排 0.926），且多识别出一个 `|` 噪声符号 —— 这本身就是
  「这个框需要特殊处理」的信号。
- **项目现状：`MlKitLocalTextDetector` 把 `confidence` 写死 `0f`**，等于把上面这些信号全丢了
  （Paddle 路径有真置信度并已用于择优，ML Kit 路径现在是瞎子）。

### 二、「韩文页面里混着日文」实测（决定性数据）

探针 `app/src/androidTest/.../MlKitLanguageMixProbeTest.kt`，素材 `app/src/androidTest/assets/syn1b.png`
（夹具站 `v-seam-none.html` 用的同一张）。原始输出 `logs/quality-20260930/ja-probe/mix-language.txt`。

**整页识别**

| 引擎 + 语言 | 结果 |
| --- | --- |
| ML Kit 韩文 | `blockCount=1` —— 只有斜排韩文 `기울어진 텍스트`(0.845)；**粉色旁白与右缘日文都没检出** |
| ML Kit 日文 | `blockCount=1` —— **正是右缘那段**：`これは縦書 きです`(0.557)，box `Rect(751,134 - 792,521)` |
| Paddle 韩文 | `문홍색 나데이선@0.939`（粉色旁白，识别有误）\| `기울어진 텍스트 cYaob@0.986` \| `M@0.893` \| `a@0.678` |
| Paddle 日文 | 全页乱码：`モさ一I一iO@0.919 \| 書れば新手目までま@0.988 \| フ0利山上巨 _@0.950 \| 目さでま@0.953` |

**按真实链路裁剪出右缘那一块**（box 外扩 30px → `Bitmap.createBitmap(full, 721, 104, 101, 447)`）

| 引擎 | 1× | 3× 放大 |
| --- | --- | --- |
| **ML Kit 韩文** | **`blockCount=0`（零识别）** | **`blockCount=0`（零识别）** |
| ML Kit 日文 | `これは績書きです`（0.522，`縦`→`績` 错 1 字） | **`これは 縦書きです`（0.611，全对）** |

### 三、机制：「擦了却没有译文」是怎么来的

两条流水线**解耦**造成的：

```
区域检测（BubbleRegionDetector，只看墨迹/颜色，与语言无关）
        └─> 检到 → 生成 bubble ──┬─> 擦除 erasePage(source, bubbles)  ← 只看 bubble，不看识别结果
                                 └─> OCR（按 sourceLanguage 选模型）
                                            └─> 韩文模型对日文 = 零识别 → 无文字 → 无译文
结果：原文被擦掉，位置没有新内容可盖 → 留白 + 未擦净的笔画残渣
```

即：**「有没有东西要擦」和「能不能翻译它」用的是两套判据**，前者永远成立，后者在跨语言时失败。
所以最坏情况不是「日文没翻译」，而是「**日文被擦掉且没有任何替代内容**」——信息净损失。

### 四、处理方案（待用户裁决）

| 方案 | 内容 | 代价 |
| --- | --- | --- |
| **① 日文兜底识别（推荐）** | 源语言≠日文时，对「检出区域但 OCR 结果为空」的区域，用**日文识别器**再识别一次（小区域需放大，实测 3× 才全对） | 只对空结果区域多跑一次识别，每页通常 0~2 块；ML Kit 为 bundled，耗时可控 |
| **② 识别为空就不擦（推荐，与 ① 配套）** | 兜底后仍识别不出文字的区域**不擦** | 几乎零成本；最坏情况原文还在，不丢信息 |
| ③ 双识别器整页并行 | 每页同时跑韩文 + 日文识别器，按 `recognizedLanguage` 合并 | 每页多一次整页识别；框可能重叠，需去重规则 |
| ④ 不提 | 维持现状 | 韩漫里出现日文属少数情况；但一旦出现就是白块 |

推荐 ①+②：先用日文模型把「其实是日文」的内容救回来，救不回来的就别动原文。

### 五、本轮产物与未覆盖

- 新增探针（**留在仓库，可复用**）：`MlKitConfidenceProbeTest.kt`、`MlKitLanguageMixProbeTest.kt`，
  素材 `app/src/androidTest/assets/syn1b.png`。
- `app/build.gradle.kts` 新增 4 条 `androidTestImplementation`（ML Kit 各脚本 + `play-services-tasks`）：
  `core-translate` 用的是 `implementation`，编译期不传递给 app 的测试源集，只能再声明一次（**只影响测试 APK**）。
- 证据：`logs/quality-20260930/ja-probe/{mlkit-confidence.txt, mix-language.txt}`。
- **未覆盖**：Paddle 的置信度探针没做（本轮只测 ML Kit）；日文兜底方案的实现未落地（等用户裁决）；
  真机上「韩文模式扫到日文页」的整页表现未复测（本轮只在夹具图上实测）。

## 2026-09-30（续四）「读不出就别擦」落地：真相是碎片 `J유t,`，不是零识别

### 起因

上一节末尾留了下一步：把「读不出」区域的**真实识别文本**打出来再定阈值。为此先把诊断从
「只在跳过时打印」扩成「**每个区域无条件打印**」（原文 + 长度 + 前 24 个码点 + 判定），
因为真机复测已经证明：旧诊断在恰好需要证据的形态下什么都打不出来
（`erase_targets: total=3 erasable=3 skipped=0` → 一个区域都没跳过 → 文本一行不出）。

### 决定性证据（真机，源语言=韩文）

`logs/quality-20260930/ja-probe/after3-seam-none.logcat`，traceId 79409133（第二页 = `syn1b.png`）：

```
erase_target: idx=0 ... len=8 text=[기울어진 텍스트]     ← 斜排韩文，正常
erase_target: idx=1 ... box=6x23@82,55 len=4 codes=[U+004A,U+C720,U+0074,U+002C] text=[J유t,]
erase_target: idx=2 ... len=8 text=[분흥색 나레이션]     ← 粉色旁白，正常
```

右缘竖排日文「これは縦書きです」在**韩文模型**下的真实读出是 **`J유t,`** ——
4 个字符横跨拉丁（`J`、`t`）与谚文（`유`）。**不是空串，也不是 `|`，是跨文字系统的碎片**。
这正好落在用户预判里：「但是也有可能不是日文啊」——模型读不对时会硬凑乱码，
乱码长什么样取决于模型，不能按语言预设形态。

### 判据（第三条，仍不做语言假设）

| # | 判据 | 拦截形态 | 依据 |
|---|------|----------|------|
| 1 | `originalText` 空白 | 真零识别 | 擦了必然白块 |
| 2 | ≤2 字符且无字母数字 | `\|`、`。`、`..`、`!!` | 符号噪声 |
| 3 | ≤5 字符且横跨 ≥2 个**文字家族** | `J유t,` | 真实短句几乎总属单一文字家族 |

文字家族合并规则：**假名与汉字同属 CJK 一家**（日文 `読む` 合法混用，不能误伤）；
谚文、拉丁各一家；其余按 Unicode 脚本。两个方向的代价不对称：
误判 = 原文没擦、原样可见（读者无损）；漏判 = 擦掉原文盖上垃圾（画面被破坏）→ 拿不准一律不擦。

### 改动位置

- `app/.../EraseTargets.kt`：新增 `isFragmentNoise` + `scriptFamily`（判据 3）；
  新增 `eraseTargetDetailDiag`（逐区域无条件诊断，tag `erase_target`）。
- `app/.../TranslateOrchestrator.kt`（悬浮球链路）：OCR 结果在 `runOnce` **源头整块丢弃**
  读不出的区域（`erase_skip` 诊断），下游擦除/翻译/渲染全部用过滤后的列表；
  全部区域都读不出时整屏保持原样。
- `app/.../WebtoonImageTranslator.kt`（内嵌浏览器链路）：同策略，在 `seamPlan.owned` 源头过滤。
  在源头丢而不是只跳过擦除的原因：擦除与渲染共用这份列表，源头一致才能保证
  「要么擦掉并盖上译文，要么原样保留」，不会出现「没擦却盖字」的双重污染。

### 验证

| 项 | 结果 |
|----|------|
| `EraseTargetsTest`（含新旗舰用例 `J유t,`） | **21/21 通过**（`app/build/test-results/testDebugUnitTest/`，17:42） |
| APK 健康 | `classes7.dex` 含 `TranslatorApplication`=3、`EraseTargets`=14（17:42 重打，非坏档） |
| 真机第二页 | `erase_skip: dropped=1 kept=1 skipped_text=[J유t,]`；擦字贴片 `3→2`；`refine_boxes` 不再出现 `b1=40x362@753,303`；`scene_ok bubbles=2`（那条日文不再进场景） |
| 右缘目视 | 「これは縦書きです」**完整保留**（`after4-right-edge.png`）；修复前同位置是被擦掉后竖排乱码 `J유t,`（`after3-right-edge.png`） |
| 回归 | 第一页三个正常韩文区域行为不变（贴片 `count=3`、`scene_ok bubbles=3`、`crossOut=1 seam=1` 接缝移交照常） |

证据：`logs/quality-20260930/ja-probe/after4-seam-none.{png,logcat}`（修复后）、
`after3-*`（修复前）、`after4-right-edge.png` / `after3-right-edge.png`（裁剪对比）。

### 未覆盖 / 待定

- 本轮只让读不出的区域**不被破坏**；要让它被**读对、翻出来**，需要多语言候选识别
  （源语言+日文+拉丁并行、按字符数与置信度择优、候选语言做成设置项）——等用户裁决。
- 判据 3 的阈值（5 字符 / 2 家族）是在合成夹具上标的，未用真实漫画页校准；
  若真实页出现「超短混排真实台词」（如 `S급!` 之类 2 家族短句）会被保守地不擦——
  方向是安全的（原文可见），但如果实际出现太频繁，再收紧。
- `MlKitLocalTextDetector` 置信度透传（现为写死 0）仍未做；它是判据之外的第二道可用信号。

---

# 2026-10-02 配置表单「思考功能」开关 + 请求体思考三态

- 执行者：DSH Agent
- 需求原话：「这里可能需要限制一下思考功能。因为有思考功能会慢很多」→「需要添加一个参数。表示是否开启思考功能」
- 决策（用户选定）：① 参数放**配置档案表单**（每档案一份，默认关）；② 关思考时**显式发
  `enable_thinking=false`**（只对千问/GLM 这类支持的厂商）；③ 不暴露 `thinking_budget`。

## 改动

| 文件 | 内容 |
|------|------|
| `core-translate/.../OpenAiVisionClient.kt` | 新增 `ThinkingMode`（`ENABLED`/`DISABLED_EXPLICIT`/`OMIT`）与 `ThinkingSupport`（厂商门控 / 初始模式 / 降级阶梯 / 字段落法）；三个公开方法（`detectBubbles`/`translateTexts`/`groupTextBlocks`）删掉重复的 `providerId == "qwen" \|\| "glm"` 硬编码；`requestWithFallback` 改为**有界阶梯**（≤4 次 + `attempted` 去重，思考降级与 `response_format` 降级正交）；`isThinkingUnsupported` 增加「parameter + unknown/unsupported/invalid」第二判据；日志 `thinking=<MODE>` + 新增 `request_retry` |
| `app/.../HomeScreen.kt` | `ProfileFormScreen` 新增 `MtThinkingRow`（标签 + 副标题 + M3 Switch，配色对齐术语表页）；新建默认关、编辑回读档案值；切到不支持厂商自动收回 false；保存写 `enableThinking` |
| `app/.../PageTranslationResolver.kt` | `TranslationPerf request_send` 补 `provider=` / `thinking=` 两个字段 |
| `core-translate/src/test/.../ThinkingSupportTest.kt` | 新增 8 条 |
| `app/src/test/.../ThinkingProviderConsistencyTest.kt` | 新增 1 条（UI 门控 vs 客户端门控一致性） |
| `.gitignore` | 加 `.gradle-home/`（本机沙箱构建用的 GRADLE_USER_HOME，体积大不入库） |

行为变化（有意）：旧的重试分支在思考参数被拒后会发「不带 `enable_thinking` 但带 `thinking_budget`」的怪组合，
现在那一级发 `enable_thinking=false`（+`max_tokens`），再失败才退到完全不发（`OMIT`，即旧行为）。

## 验证

| 项 | 结果 |
|----|------|
| `:core-translate:testDebugUnitTest` | **98 条全通过**（含新增 `ThinkingSupportTest` 8/8，0 失败 0 错误） |
| `:core-store:testDebugUnitTest` | **93 条全通过** |
| `:app:testDebugUnitTest` | **135 条全通过**（含新增 `ThinkingProviderConsistencyTest` 1/1） |
| `:app:assembleDebug` | **BUILD SUCCESSFUL**（APK 278,726,956 字节，2026-10-03 00:07） |
| 表单·OpenAI | 开关 `checked=false enabled=false`，副标题「当前厂商不支持开关（仅千问 / GLM 支持）。」→ `verify_thinking_openai.png` |
| 表单·千问 | 开关 `enabled=true`；点一下 → `checked=true`，副标题「开启后模型先思考再作答：更准，但明显更慢（默认关）。」→ `verify_thinking_qwen_on.png` |
| 新建默认关 | 在「当前档案思考=开」的情况下打开新建表单，开关为 `checked=false`（有意行为） |
| 档案回读 | 创建 `qwen-thinking`（千问）后重新「编辑」→ 开关 `checked=true` → `verify_thinking_qwen_edit_persisted.png` |
| 切厂商收回 | 编辑态切到 DeepSeek → 开关 `checked=false enabled=false`（避免存下永不生效的 true） |
| 崩溃检查 | 全流程 `logcat` 无 `FATAL EXCEPTION`（仅 `adb install -r` 导致的 `app died, no saved state`） |

模拟器：`emulator-5554`（sdk_gphone64_x86_64）。截图：仓库根目录 `verify_thinking_*.png`（`.gitignore` 已忽略）。

## 补充修复：「无法编辑配置」（2026-10-02 用户反馈）

- 现象：配置档案底部弹层里，**当前使用**的那一行只有勾标、没有「更多」菜单 → 打不开 编辑/重命名/复制/删除；
  而新建配置后它立刻变成当前使用，等于「刚建好的配置反而编辑不了」。
- 根因：`app/.../HomeScreen.kt` 的 `ProfileRow` 把「勾标」与「更多菜单」写成了互斥分支
  （`if (active) 勾标 else 更多菜单`）。
- 修法：勾标与「更多」并存（菜单对所有行显示）；另补上随之放开的「删除当前使用配置」路径——
  删掉最后一条时同步清空翻译 API 配置（provider/baseUrl/model/apiKey/enableThinking），
  避免「首页显示未配置、后台仍用已删档案的 key」；provider 记住的 key 不受影响。

| 验证项 | 结果 |
|--------|------|
| 当前使用行可编辑 | 唯一档案 `qwen-thinking`（`当前使用`）→ 点「更多」→「编辑」→ 打开 `编辑配置` 表单，`思考功能` 回读 `checked=true` → `verify_profile_menu_fixed.png` |
| 菜单项完整 | 当前使用行的菜单含 编辑 / 重命名 / 复制 / 删除（uiautomator 节点核对） |
| 删除当前使用（最后一条） | 弹层回到「还没有配置档案」，首页「配置档案=未配置」+ 恢复「云端 API 还没配置，先新建一个配置档案」提示（说明设置确实被清空） |
| 重新建档案 | 再建 `qwen-thinking`（千问 + 思考开）成功，首页「配置档案=qwen-thinking」 |
| 崩溃检查 | 全流程无 `FATAL EXCEPTION` |


## 未覆盖 / 待定

- **未在设备上抓到真实请求体**：验证环境没有可用的千问/GLM Key，且 App 未开明文 HTTP
  （无 `usesCleartextTraffic`），无法用本地 mock server 抓包；请求体映射由单测
  （`ThinkingSupportTest.applyTo*`）+ 厂商门控一致性测试覆盖，设备侧只验到
  「表单状态 + 档案持久化 + 切厂商收回」。
- **耗时对比未做**：需真实 Key + **未翻译过的新页面**（句子级缓存命中会跳过 API），见 HumanTODO.md 2026-10-02。
- 思考仅对千问 / GLM 生效；硅基流动 / 火山方舟 / 自定义 目前拿不到开关（要扩展需同时改
  `AiProvider.supportsThinking` 与 `ThinkingSupport.PROVIDER_IDS`，一致性测试会挡住漏改）。
- 句子级缓存不区分思考开关：同一句第二次翻译直接命中缓存，不重新请求（性能特性，非缺陷）。

## 附：本机构建环境（与项目代码无关，供后续 session 复用）

- `GRADLE_USER_HOME=<repo>\.gradle-home`（沙箱挡住用户目录）+ `GRADLE_RO_DEP_CACHE=C:\Users\yuanying\.gradle\caches`。
- 工作区内解包出的 `aapt2.exe` **无法创建文件**（`stableIds.txt: failed to open: 数据无效 (13)`；
  同样的字节放到用户目录下正常），需 `-Pandroid.aapt2FromMavenOverride=<用户目录>\...\aapt2-8.5.2-11315950-windows\aapt2.exe`。
- 内存紧张（16G / 空闲约 3G）时 Gradle 守护进程会 GC 抖动自杀，需
  `--max-workers=2 "-Dorg.gradle.jvmargs=-Xmx2560m -XX:MaxMetaspaceSize=768m"`；
  构建前用 `gradlew --stop` 清掉**旧沙箱下启动的守护进程**（否则会一直报 `.gradle\8.7\fileHashes\fileHashes.lock` 拒绝访问）。
- 本轮做过一次工作区文件权限修复（`.gradle`、`.gradle\8.7`、`.gradle\8.7\fileHashes` 补当前用户完全控制），
  报告与回滚脚本：`.verify/acl-repair-20261002/`。


## 2026-10-03 跨页接缝（缝落在第 2 行之后一点）：定位、修复与真机验证

用户反馈「感觉跨页的处理不好。原文的页是卡在第二行的后面一点。」+ 三张截图。
本轮把接缝链路读完（`WebtoonImageTranslator` → `SeamBubbleMerger` → `SeamBandGeometry` →
会话下发 → `mt-overlay-renderer.js`），定位到 6 处缺陷并修掉，跑通了 **真机端到端**。

### 1. 复现夹具（用用户那张原始面板做的）

- `.verify/web/make_seam_after_line2.py`：把用户截图里的原始面板（817×673）按切点
  **切成上下两张等宽切片**（`seamLine2A/B.png`，2× 放大 → 1634×516 / 1634×830），
  切点正好落在 `접속종료를 하려면 죽거나`（第 2 行）下沿之后几像素；下半切片里带着
  「被误擦的那个气泡」。
- `.verify/web/seam-after-line2.html`：两张 `<img>` 无间隙叠放（= 站点切片同构）。
  ⚠️ `width`/`height` 属性必须写：扫描器在图片解码前用元素盒子判缩略图（`minW/minH=180`），
  `width:100%;height:auto` 的图解码前盒子高 0 → **上半切片根本扫不到**（实测 `scan scanned=1`，
  那一半永远是原文）。第一版夹具就踩了这个坑，加显式尺寸后 `scanned=2`。

### 2. 真机端到端（emulator-5554，`scripts/verify-browser-page.py`）

修复前后各跑一次（日志 `.verify/21_seam_line2.logcat` → `.verify/22_seam_line2.logcat`）：

- **页 A（1634×516，`topH=0 botH=207`）**：
  `slice_classify detected=1 owned=1 cross=1 … cut_allow=737`、`seam_verdict d0=cross`、
  `seam_scene mode=band bubbles=1 band=909x437@503,259 bg=cleaned own=[b0] next=[c0]`、
  `crossOut=1 seam=1`、`seam_dispatch own=mt1 next=mt2 ok=true covers=1`、
  `seam_suppress image=mt1 bubbles=1 removed=2`
  —— 接缝带**在真机上成立**：一条缝一块盖板一份译文。
- **页 B（1634×830，`topH=129 incoming=1`）**：
  - 修前：`slice_classify … orphan_adopted=1`、`seam_verdict d1=orphan_adopted`
    → 本图的检测框被当成「没人管」，**又翻了一遍**（截图 `.verify/21_seam_line2.png` 里
    左侧能看到没盖住的竖排残字）。
  - 修后：`dropped_neighbor=1 orphan_adopted=0`、`seam_verdict d1=dropped_top_handed`
    → 正确识别「上一张已移交」，不再重复渲染（`.verify/22_seam_line2.png`：一块盖板 +
    一份译文、无残字）。
  - `cross_prep incoming=1 added=1 px=[536,0~1378,177]` —— 擦字区域**只在本图顶部
    0~177px（= 移交过来的那半句）**，下面那个气泡（y≈379 起）完全没被碰
    （修前用户截图里「邻居气泡第 1 行被擦掉」的那类误擦，在这个夹具上不再出现）。

### 3. 修复前后差异的真因（一个坐标 bug）

`planSeamOwnership` 的 `incomingCrossBoxes` 用的是 `CrossBubblePart.contentRect`（**本图**像素），
而 `detections` 是**拼接图**像素（0 = 上条带顶端）—— 直接比较等于少了一个 `topH` 的偏移：

```
topH=129 时：可见部分重叠 96% 被算成 56% < 阈值 60% → 本图把「已移交的下半句」又翻一遍
```

已修（`WebtoonImageTranslator` 里给 `contentRect` 的 y 加 `topH`）。这个 bug 在修复
「归属空洞」之后才暴露出来：以前它只是「少抑制一次」（多画一份），现在会导致「多认领一块」。

### 4. 单测 / 渲染层验收

- `:app:testDebugUnitTest` **148/148**（新增 6 条：上 2 行/下 1 行必须配对、切开余量裁短、
  下条带整块「认领+移交但不渲染」、跨上边界块「有移交才丢 / 没移交就认领」、
  `resolveCutAllowance`、`maxCrossHeight` 带行高）。
- `node app/src/test/js/mt-overlay-acceptance.test.js` **186/186**（新增 §14：2px 间隙可用、
  大间隙仍拒且带 `gap_px`、接缝失效后两半贴回、幂等、「原文」清登记表）。
- `./gradlew test`（全部模块）**BUILD SUCCESSFUL**。

### 5. 本轮踩到的环境坑（可复用）

- 工作区权限修复后，`.verify/`、`.gradle/` 写入恢复；但**旧沙箱下启动的 Gradle 守护进程**
  仍会报 `.gradle\8.7\fileHashes\fileHashes.lock` 拒绝访问 → 先 `gradlew --stop`。
- 工作区内解包的 `aapt2.exe` 仍然不能创建文件（`stableIds.txt: failed to open (13)`）→
  继续用 `-Pandroid.aapt2FromMavenOverride=<用户目录>\...\aapt2-8.5.2-11315950-windows\aapt2.exe`。
- `scripts/verify-browser-page.py` 在本机 Python 3.14 上**直接 SyntaxError**
  （`global GLOBAL_TIMEOUT_S` 写在读取之后）→ 已把 `global` 提到函数体最前面。
- 验证跑完后已还原模拟器系统代理 `10.0.2.2:7890`、移除 `adb reverse tcp:8899`。

### 6. 仍未解决（已写进 HumanTODO）

- **接缝带的译文字号偏大**：夹具里译文占满整个气泡（6 行、字比原文大），
  怀疑与 `sourceLineCount` 在拼接图上被低估（`原文字高 = 框高 ÷ 行数`）有关；
  这条与 2026-09-30「0.82× 偏小」是同一个字号口径问题的另一侧，需要单独收口。
- **条带截断自动重裁**未做（本轮只加了 `seam_strip_short` 检测与日志）。
- 两切片源像素尺寸不同时的接缝带 scale 口径未处理（仍靠 2% 显示宽度检查兜底）。

## 2026-10-11 「精细擦字（原 inpaint 清理图）」开 vs 关：实测差异与耗时

> ⚠️ 本条记录的是**改造前**的行为（关闭时旁白仍会建整页清理图）。当天下午按用户要求改成硬分工 ——
> 关闭 = 完全不建清理图 —— 见下面《2026-10-11 第二轮》；本条的耗时数字只作历史对照，结论 1/2 已过时。

用户问题（逐字）：

> 那个清理图模式和关闭 inpaint 的时候有什么区别。检测一下用时。

### 1. 差异本体（先把概念对齐——开关同时改两件事）

「精细擦字」= `SettingsRepository.isBrowserInpaintCleanEnabled()`（默认开，**只作用于内嵌浏览器链路**；
悬浮球链路 `TranslateOrchestrator.prepareVisuals` 恒定 `OPENCV_WHEN_AVAILABLE`，没有这个开关）。
分支都在 `WebtoonImageTranslator.prepareVisuals`：

| | 开（清理图模式） | 关（关闭 inpaint） |
|---|---|---|
| 整页清理图 | **总是**建（`includeSolid=true`）；纯色气泡走「直填背景色」不进 inpaint（2026-10-11 起） | **只在页面里存在旁白/透明（`bg=none`）文字块时**才建（`includeSolid=false`）；整页全是纯色气泡才真正跳过（日志 `cleaned_image skipped=all_solid`） |
| 逐气泡擦字策略 | `OPENCV_WHEN_AVAILABLE` | `FAST_ONLY` |
| 纯色气泡背景来源 | 从清理图裁切（`cleaned_patched=N`） | 逐块取色快速贴片（`fast=N`；`cleaned_patched` 只剩旁白那几块） |
| 旁白/透明文字块背景 | 清理图裁切 | **一样是清理图裁切**，不会退回纯色块 |

即：**「关闭 inpaint」不等于「完全不跑 inpaint」**——只要画面上有旁白/压在背景上的文字，
整页 inpaint 照跑；关闭只让**纯色气泡**不再从清理图取背景。设置菜单里关闭时的副标题
「逐块取色贴背景，**不跑 inpaint**」只对纯色气泡成立，文案偏窄（已记入 HumanTODO）。

日志判据：`erase_mode inpaint_clean=… policy=…`、`cleaned_image built…/skipped=all_solid`、
`erase_apply … fast=/edge=/opencv=/cleaned_patched=`。

### 2. 实测（模拟器 emulator-5554，逐轮冷内存缓存）

夹具（`.verify/web`）：`solid-only.png`（800×2100，3 个纯色气泡、无旁白）、`v-tilt.html`→`syn1.png`
（900×1400，2 纯色 + 2 旁白）、`big.html`→`big1.png`（1600×2400，3 纯色 + 4 旁白）。
每轮 `force-stop` + 重开页面（清掉 `CleanedImageProcessor`/`TieredEraseProcessor` 的内存缓存），
翻译走句缓存（`sentence_cache_hit=true`）→ 变量只剩擦字这一侧。切换顺序 开/关 交替，各 3 轮。

| 夹具 | 开：清理图阶段 | 开：擦字阶段 | 关：清理图阶段 | 关：擦字阶段 | 整页总耗时（开 / 关，中位） |
|---|---|---|---|---|---|
| solid-only 800×2100 | 273 / 286 / 345 ms | 18–24 ms | **2 / 12 ms**（`skipped=all_solid`） | 116–182 ms | 1423 / 1420 ms |
| syn1 900×1400 | 176 / 234 / 237 ms | 9–14 ms | 138 / 166 / 272 ms | 53–64 ms | 1580 / 1629 ms |
| big1 1600×2400 | 433 / 516 / 699 ms | 6–10 ms | 487 / 565 / 596 ms | 66–70 ms | 1968 / 1994 ms |

结论：

1. **只有「整页都是纯色气泡」时，关闭才真正省掉整页 inpaint**：清理图阶段 273–345 ms → 2–12 ms，
   但逐气泡快速贴片多花 ~100–160 ms，**净省约 0.1–0.2 s**（该页整页 1.4 s 量级，≈10%）。
2. **有旁白/透明气泡的页面（长条漫常见）：耗时基本无差别** —— 整页清理图两边都要建
   （关闭时也打 `cleaned_image built … include_solid=false`，旁白仍靠它取背景），
   清理图阶段的差异落在噪声内；整页总耗时差（±0.1–0.3 s）由本地 OCR（0.5–1.2 s）波动主导。
   实测同批气泡两边 `cleaned_patched` 分别 7/7（开）与 3/7（关）——省的是纯色那 3 块的裁切，
   而裁切本身只要 ~10 ms。
3. **真正的差别在背景来源与质量指标上**（逐轮一致，不是噪声）：

   | 夹具 | 开：`erase_dark_replace_ratio` / `erase_quality` | 关：同 |
   |---|---|---|
   | solid-only | 0.000 / `residual_avg=0.00 residual_max=0.00` | **0.329** / `residual_avg=0.20 residual_max=0.44 residue=1` |
   | big1 | 0.000 / `0.00 / 0.00` | 0.268 / `0.01 / 0.02`（worst=solid） |
   | syn1 | 0.000 / `0.00 / 0.00` | 0.218 / `0.01 / 0.02`（worst=solid） |

   即：开启时纯色气泡背景来自整页 inpaint 的清理图（纹理连续、残留 0）；关闭时是「逐块取色 +
   墨色替换」，重写了 22%–33% 的候选像素，在合成大色块夹具上残留率最高到 0.44。
   注：浏览器默认走 `DOM_SCENE`（覆盖层渲染），纯色气泡另外还有一层平涂盖板，
   这 0.44 在**最终像素上**是否可见，需要真机/肉眼确认（已写进 HumanTODO）。
4. 内存缓存命中时（同一页来回滚动）`cleaned_image` ≈ 0 ms（LRU 键 `pageHash|include_solid`，
   开/关是不同的键，切开关必然重建一次），所以开关的成本只在「每张图第一次翻」时出现。

### 3. 复现方式（新脚本）

```bash
python -m http.server 8899 --bind 127.0.0.1 --directory .verify/web   # 宿主机起验证页
adb -s emulator-5554 reverse tcp:8899 tcp:8899
python scripts/bench-erase-timing.py --url http://127.0.0.1:8899/solid-only.html \
    --outdir logs/erase-bench-solid --rounds 3 --order on,off --cold --shot
python scripts/bench-erase-timing.py --analyze logs/erase-bench-solid
```

产物：`logs/erase-bench-solid/`（含 `roundN_on/off.logcat` + 截图）、`logs/erase-bench-tilt/`、
`logs/erase-bench-big/`；分析脚本按 traceId 算「阶段增量」（`cleaned_image` 阶段 =
`cleaned_image` − `erase_targets`，`erase` 阶段 = `erase_apply` − `erase_mode`）。

- 本轮新增夹具：`.verify/web/solid-only.{png,html}`（从 `big1.png` 左列裁出的「只有纯色气泡、
  没有旁白」页面——用来暴露「关闭才真正省掉整页 inpaint」这条路径）。
- 环境还原：跑完已把「精细擦字」恢复为**默认开**、停掉临时 `python -m http.server 8899`。
  （`adb reverse tcp:8899` 仍保留，方便复跑。）

## 2026-10-11 第二轮：把「精细擦字」的两条路线分工硬化（开 = 最接近原画 / 关 = 只求速度）

用户要求（逐字）：

> 我想要的是开启精细擦字的时候最符合原文。关闭的时候只考虑速度，不考虑效率，只要显示出来译文就行。

上面那条实测暴露的问题：关闭开关时**只有「整页纯色气泡」才真省**，一有旁白就照样建整页清理图
（旁白要从清理图取背景）—— 用户要的「关闭 = 只求速度」根本没兑现。本轮把它改成硬分工。

### 1. 代码改动

| 位置 | 改动 |
|---|---|
| `WebtoonImageTranslator.prepareVisuals` | `needsCleanedImage = includeSolidInpaint`（不再带 `|| any(non-solid)`）→ **关闭时一张清理图都不建**：不跑整页 TELEA inpaint、不导出整页 JPEG、背景描边判据拿不到背景（旁白一律保留描边，安全缺省） |
| 同上（新增）| `exportFastPatches()`：关闭时把每个 `bg=none`（旁白/压在画面上的文字块）气泡的**逐气泡擦字贴片**编码成 PNG，经 mtlocal 短 URL 下发，渲染层用 `patch_url` + `patch_box` 100% 铺满补丁框当盖板背景 |
| `WebtoonImageTranslator.buildScene` | `cleaned_url == null` 时给 `bg=none` 气泡挂 `patchUrl`/`patchBox`（纯色气泡不需要：`.mt-bg-solid::before` 画的是采样底色平涂）|
| 同上（顺带修）| **贴片下标错位隐患**：`erasePage` 的 `bubbleId` 是「传进去那个列表」的下标，而传进去的是 `isWorthErasing` 过滤后的列表，下游（`patchFor`/擦字质量统计/跨图移交）用的却是未过滤下标 —— 一有气泡被过滤就会贴错。现在按「擦字目标 → 原下标」重编 |
| `TranslationPerf` 日志 | 关闭时 `cleaned_image: … skipped=fast_mode`（以前误记成 `skipped=all_solid`）；`render_scene … cleaned=false patches=N`（N = 场景里带贴片盖板的气泡数）|
| 设置菜单副标题 | 「整页重画背景，最接近原画（慢）」/「只求快：不做整页重画，纯色平涂 + 逐块贴背景」|

### 2. 实测（模拟器 emulator-5554，冷内存缓存，3 夹具 × 各 3 轮，开/关交替）

同一页面、同一批气泡、翻译走句缓存；关这一侧的 `cleaned` 阶段基本为 0（只剩一次判断）。

| 夹具 | 开：建清理图 | 关：建清理图 | 开：擦字 | 关：擦字 | 阶段净差（关−开） |
|---|---|---|---|---|---|
| `solid-only` 800×2100（3 纯色） | 246 / 363 / 598 ms | **1 / 2 / 4 ms**（`skipped=fast_mode`） | 9–13 ms | 114–131 ms | **约 −250 ms/张** |
| `syn1` 900×1400（2 纯色+2 旁白） | 138 / 259 / 424 ms | **1 / 13 / 33 ms** | 10–11 ms | 109–217 ms | **约 −120 ms/张** |
| `big1` 1600×2400（3 纯色+4 旁白） | 671 / 787 / 846 ms | **5 / 15 / 21 ms** | 9–52 ms | 170–188 ms | **约 −630 ms/张** |

- 关闭省下的是**整页 inpaint + 整页 JPEG 导出**（随面积走：800×2100 ≈ 0.25–0.6s，1600×2400 ≈ 0.7–0.85s），
  多花的是逐块局部填充（~0.1–0.2s）；净下来每张**快 0.12–0.63s**。
- 整页总耗时（含本地 OCR 0.9–2.3s 的波动）中位数：`solid-only` 1757 → 1473 ms；
  `big1` 3786 → 2955 ms（−22%）；`syn1` 2125 → 2136 ms（该页 OCR 波动更大，阶段差被淹没）。
- 观感侧的指标差（关闭 = 逐块贴片）：`erase_dark_replace_ratio` 0.296–0.559（开 = 0.000）、
  纯色大色块夹具上 `residual_avg=0.20 / max=0.44`（开 = 0.00/0.00）—— 这是「只求速度」的价钱，用户已接受。

### 3. 渲染层证据（真 DOM 回读，`v-tiltonly.html` 单个斜排旁白）

`.verify/dom-cover-probe.py` 把页面 `mtdump.js` 的 `MTDUMP`（tag = `chromium`，抓日志时必须带这条
filterspec）解析出来，直接看盖板实际用的背景图：

```
关闭：{"layer":"b0:cover","cls":"mt-cover-clip","bgImg":"url(\"https://mtlocal.overlay/p12234782732900.png\")"}
开启：{"layer":"b0:cover","cls":"mt-cover-clip","bgImg":"url(\"https://mtlocal.overlay/c12257745452000.jpg\")"}
```

即：关闭 = **逐气泡 PNG 贴片**（`p…png`），开启 = **整页清理图 JPEG**（`c…jpg`）。
两种模式下旁白都被盖住，不存在「原文露着」；`scene_ok bubbles=N` 正常。

### 4. 回归

- `node app/src/test/js/mt-overlay-acceptance.test.js` → **ALL_OK 192/192**（新增 §15：patch_url + patch_box
  盖板的裁剪层尺寸/中心与 100% 铺满，3 条断言）。
- `./gradlew :app:assembleDebug test` → **BUILD SUCCESSFUL**（四个模块 JVM 测试）。
- 模拟器三轮 × 三夹具（`logs/erase-bench2-*`，含截图）：关闭时 `cleaned_image skipped=fast_mode`、
  `render_scene cleaned=false patches=N`、`scene_ok` 齐全。

> ⚠️ 本节的「关闭时挂逐气泡擦字贴片」在当天被下面《第三轮》取代：贴片是局部边缘填充，
> 笔画边缘会留残影，用户要求改成不透明实底遮挡。

## 2026-10-11 第三轮：关闭模式的盖板改成**不透明实底**（不再用贴片，消残影）

用户反馈（逐字）：

> 现在会有残影。不要这样的，而是用实体的背景遮挡

根因：上一轮关闭模式给 `bg=none` 气泡挂的是**逐气泡擦字贴片**（局部边缘填充，`FAST_ONLY`）。
贴片本身带着「没擦净的笔画」——实测残墨比 `residual_avg` 0.01–0.20（合成色块夹具最高 0.44），
盖到页面上就是残影。用户要的是「一块实心背景把原文挡住」，不需要纹理。

### 1. 改动

| 位置 | 改动 |
|---|---|
| `BrowserSceneBuilder.SceneBubble` | 新增 `flatCover: Boolean = false`；JSON 只在 true 时下发 `"flat_cover":true`（老渲染层没有这个字段 → 行为不变） |
| `mt-overlay-renderer.js` | `createCoverNode`：`bg_type=none && flat_cover` → `dataset.coverFill="flat"` + `--mt-adaptive-bg = bg_color`（**优先于** patch_url / 清理图分支）；新增 CSS `.mt-bubble.mt-cover-only[data-cover-fill="flat"]{background:var(--mt-adaptive-bg,…)!important;background-image:none!important}`（选择器比 `.mt-bg-none` 与 `[data-cleaned-layers="2"]` 都更具体） |
| `WebtoonImageTranslator.buildScene` | `useFlatCover = cleanedUrl == null` → `bg=none` 气泡下发 `flatCover=true`；删掉 `exportFastPatches()`（不再需要逐气泡 PNG 编码，顺带更快） |
| `TranslationPerf` 日志 | `render_scene … cleaned=… patches=N flat=M`（M = 用不透明实底遮挡的气泡数）|
| 触发面 | 除了「关闭精细擦字」，**清理图构建/导出失败**时也走这条兜底（以前是透明盖板 → 原文露着）|

### 2. 真实 Blink 里的计算样式（Chrome + 同一个渲染层文件）

`.verify/web/flatcover.html`（`python -m http.server 8901` 服务仓库根、页面里 `<script src="/app/src/main/assets/mt-overlay-renderer.js">`）

```
flat_cover=true  → coverFill=flat  adaptive=#f3e9d2  computedBgColor=rgb(243,233,210)  computedBgImage=none  opacity=1
老行为(无字段)   → coverFill=""     adaptive=""       computedBgColor=rgba(0,0,0,0)    computedBgImage=none  opacity=1
```

即：下发 `flat_cover` 的盖板在真 Blink 里就是**一块不透明纯色**（`background-image:none`，不会再走
清理图/贴片通道），没下发的仍然保持老行为（透明）。CSS 特异性实测生效。

### 3. 模拟器实测（新构建，冷内存缓存，夹具 1600×2400 / 800×2100，各 3 轮）

日志证据：关闭时 `render_scene … cleaned=false patches=0 flat=4`（4 个 `bg=none` 气泡全部实底遮挡）；
开启时 `cleaned=true … flat=0`（纹理路线没动）。

| 夹具 | 开：建清理图 | 关：建清理图 | 开：擦字 | 关：擦字 | 整页总耗时（开 / 关，中位） |
|---|---|---|---|---|---|
| 1600×2400（纯色3+旁白4） | 520 / 590 / 981 ms | **2 / 3 / 8 ms** | 7–35 ms | 95–140 ms | 2632 / **1874** ms（−29%） |
| 800×2100（纯色3，无旁白） | 243 / 287 / 330 ms | **1 / 2 / 7 ms** | 10–69 ms | 124–144 ms | 1450 / **1281** ms（−12%） |

阶段净差：省下整页 inpaint+JPEG 导出 0.28–0.98 s，多花逐块局部填充 0.1–0.2 s → **净快 0.16–0.47 s/张**。
（擦字贴片仍照常生成：跨图续排与整图模式还要用；关闭模式下它不再上屏。）

### 4. 回归

- `node app/src/test/js/mt-overlay-acceptance.test.js` → **ALL_OK 201/201**（§15 两条：flat_cover 实底优先、
  跨图 `patch_url` 贴片语义仍可用）。
- `BrowserSceneBuilderTest` 新增 `flat cover flag is emitted only when set`（26/26 通过）。
- `./gradlew :app:assembleDebug test` → **BUILD SUCCESSFUL**。
- 模拟器（`logs/erase-bench3-*`，含截图）：`flat=4` / `flat=0` 与开/关一一对应，`scene_ok` 齐全。

## 2026-10-12 内嵌浏览器：悬浮球加 ⋮ 胶囊 + 菜单瘦身到 120dp（画布 → 工程）

把画布 `730751285322573` 屏 `11:3` / `11:220` / `23:153` 的定稿落到工程（用户指令：「应用到现在的项目里」）。

### 1. 改动

| 位置 | 改前 | 改后 |
|---|---|---|
| 顶栏网址框 | Box h44 / r12 / 单行文字 | Row h40 / r20 / `#EEF1F7`，`[锁 14dp（仅 https）][网址 14sp][✕ 14dp 清除]` |
| 顶栏「回家」按钮 | 地球 `MtBrowserIcon` | **浏览器窗口** `MtBrowserWindowIcon`（新增；地球会被读成「网络/语言/系统浏览器」，且紧挨齿轮抢语义） |
| 悬浮球 | 48dp 圆，无阴影 | 48dp 圆 + `shadow(6.dp, CircleShape)` |
| **新增 ⋮ 胶囊** | 无 | 44×36 / r18；收起 = 白 96% 底 + `#E4E8F0` 描边 + 黑点；展开 = `#E8F1FE` 底 + `#2563EB` 描边 + 蓝点。**点击 = 开/关菜单**（长按球的老手势保留） |
| ⋮ 菜单 | 「视图开关 h44」+「截图翻译 h40」两行，宽 104 | 白卡 120×102 / r14 / 描边 + 阴影；`行·截图翻译 h44`（`MtScanIcon` 20dp + 14sp）+ `分段·原文译文 h34`（段 h28 / r8 / 13sp） |
| 底栏 | 两行：行1 导航+状态、行2 原文｜译文分段 | **单行 h48**，padding 8 / gap 4，只留 后退·前进·刷新 + 状态文字；`navigationBarsPadding()` |
| 状态行文案 | 「点『翻译』自动翻完整页」等指向底栏按钮 | 统一指向悬浮球/球上菜单（`BrowserTranslateUiPolicy.idleHint`） |
| 悬浮球默认停靠点 | 内容区高度 30% | **贴内容区底部、球底距底 16dp**（`DEFAULT_BOTTOM_MARGIN_DP`） |

新增三个 ImageVector（`ui/MtUi.kt`）：`MtBrowserWindowIcon`(20) / `MtLockIcon`(14) / `MtScanIcon`(24)。
新增 `BrowserFloatingBallPolicy` 常量：`CAPSULE_WIDTH_DP=44` `CAPSULE_HEIGHT_DP=36` `CAPSULE_GAP_DP=10`
`MENU_WIDTH_DP=120` `MENU_HEIGHT_DP=102` `DEFAULT_BOTTOM_MARGIN_DP=16`，以及 `capsuleTop` / `capsuleLeft` / `expandsUpward`。

### 2. 为什么改默认停靠点（不是顺手改）

画布定稿里「胶囊在上、菜单在上」的构图，前提是**球落在内容区下半部** —— `expandsUpward` 判定为真时菜单才朝上长。
原来默认 30% 高度时球在上半部，菜单会朝下长，与画布正好相反。改成贴底后两者一致（实测见下）。

### 3. 单测（`:app:testDebugUnitTest --rerun-tasks`，90 tasks 全部 executed）

```
TOTAL tests=226  fail=0  skip=0        （logs/quality-20261012-ball-menu/*.xml）
  BrowserFloatingBallPolicyTest      14/14   ← 本轮新增
  BrowserTranslateUiPolicyTest        8/8    ← idleHint 口径更新
```

新增 `BrowserFloatingBallPolicyTest`（此前该策略**没有任何单测**）：菜单尺寸 = 画布定稿值、
胶囊贴球上方留 10dp、顶边放不下时翻到球下方、与球同心、`menuLeft` 随停靠侧翻转且夹在容器内、
`menuTop` 按上下半部翻转、默认点落在下半部（防「菜单朝下长」回潮）。

### 4. 模拟器实测（Pixel_8 / emulator-5554 / 1080×2400 / 420dpi → 2.625 px/dp）

夹具：`.verify/web/lezhin_like.html`（`adb reverse tcp:8899`，`scripts/serve-verify.mjs` 已有实例在 8899）。
截图与量测脚本：`logs/ardot-verify/`（`app-v2-collapsed.png` / `app-v2-expanded.png` / `pngcrop.py` / `compare-11-3.png`）。

| 元素 | 画布定稿 | 实测 | 结论 |
|---|---|---|---|
| 球 | 48×48，r24，`#3B82F6` | 126×126 px = **48.0 dp** | ✅ |
| ⋮ 胶囊 | 44×36，r18 | 115×95 px = **43.8×36.2 dp** | ✅ |
| 胶囊↔球间距 | 10 | 26 px = **9.9 dp** | ✅ |
| 胶囊横向 | 与球同心 | 中心 x 均 = 1012 px | ✅ |
| 菜单白卡 | 120×102，r14 | 宽 315 px = **120.0 dp**；高 8+44+8+34+8 = 102 | ✅ |
| 卡片↔球横向间距 | 10 | 949−923 = 26 px = **9.9 dp** | ✅ |
| 行·截图翻译 | h44 / padding 8 | 273×116 px = **104×44.2 dp** | ✅ |
| 分段·原文译文 | 轨道 h34 / 段 h28 | 段 49–50×28 dp，轨道内容区 98×28 | ✅ |
| 底栏 | h48 / padding 8 / gap 4 / 图标 40 | 第一个图标中心 28.0 dp；图标中心间距 116 px = **44.2 dp**（=40+4） | ✅ |
| 顶栏网址胶囊 | h40 / r20 | 105 px = **40.0 dp**；左内边距 48 px = 18.3 dp（=返回 40 + 8） | ✅ |
| 状态行 | 12sp `#8B93A5` | 「点球自动翻完整页」单行、浅灰 | ✅ |

构图一致性：球贴底后展开菜单**朝上长**（卡片底 2032 px vs 球顶 2040 px），胶囊在球正上方 —— 与画布 `11:3` 同构。
`logs/ardot-verify/compare-11-3.png` 为「画布 `11:3` ‖ 真机」并排图。

顶栏网址框在 `http://` 页面**不显示锁头**（画布 `11:3` 是 `kakaopage.com` 故有锁）—— 这是既定逻辑（仅 https 显示），不是缺失。

### 5. 顺带修的画布不一致

画布 `11:3` 底栏提示（`23:80`）还是旧文案「点『翻译』自动翻完整页」，而同批的 `11:220`（`23:95`）已改成
「译文已盖上 · 点球可停止」。已把 `23:80` 统一为「点球自动翻完整页」。

### 6. 未覆盖 / 待定

- **已翻译态**的菜单（「译文」选中 + 亮态、非 0.4 透明度）未在真机复现：需要真实翻译（配置厂商 key）。
  截图里「原文/译文」偏灰是 `modeToggleEnabled=false` 的既定置灰，不是渲染问题。
- 真实 **https** 站点下锁头的显示未验（本轮夹具是 http）。
- 球「运行中变红 + 白方块」的展开态（菜单 + 红球同时出现）未截图。
- 画布 `2:3` 里的**底栏 tab 图标**（`5:20`）仍是地球 —— 它旁边有文字「浏览器」故不歧义，是否一起换成窗口图标**待用户定**。


## 2026-10-13 三连修验证：重复译文 / 纯色泡用清理图 / 译文戳出气泡框

执行者：OpenCode（DeepSeek V4.1 Flash）。设备 emulator-5556（1080x2400 / 420dpi），debug 包。

### 1. 单测

```
:core-overlay:testDebugUnitTest      55 tests（新增 BubbleBackgroundPolicyTest 7 + BubbleInteriorScannerTest 8）
全模块 testDebugUnitTest             TOTAL tests=537  fail=0  errors=0
node app/src/test/js/mt-overlay-acceptance.test.js   ALL_OK 210/210（新增 §16 场景重建后抑制仍生效 9 条）
```

### 2. 椭圆白气泡夹具（用户第 2/3 条）

夹具：`.verify/web/ellipse-bubble.html` + `ellipse-bubble.png`（生成脚本 `scripts/make_ellipse_bubble_fixture.py`：
600x460，灰云背景 + 白椭圆泡 + 4 行韩文）。驱动：`scripts/verify-browser-page.py --url http://127.0.0.1:8899/ellipse-bubble.html`。

| 阶段 | 关键日志 | 结果 |
|---|---|---|
| 修前（旧包） | `erase_target idx=0 keep=Y bg=none`、`render_scene … cleaned=true`、无 `solid_upgrade` | 盖板从 inpaint 清理图裁切：气泡内外一圈灰痕；译文左右戳出椭圆（`.verify/70_before_shot.png`） |
| 修后（新包） | `solid_upgrade count=1 evidence=b0=ring0.82/flat0.99`、`ink_tilt … interior_cap=401x347 mid=475`、`scene_ok bubbles=1` | 盖板纯白 + 按容器轮廓裁剪；译文在椭圆内（`.verify/74_after_shot.png`） |

实测像素校验（`.verify/74_after_shot.png`）：椭圆轮廓外 **0** 个「明显在轮廓外」的深色像素
（v = ((x-cx)/a)^2+((y-cy)/b)^2 > 1.06 的深色点计数为 0），译文包围盒从修前的 757px 宽收到 624px。

### 3. 接缝 + blob 轮换夹具（用户第 1 条）

夹具：`.verify/web/seam-rotate.html`（`seamLine2A/B.png` 两张切片 + 每 6s 把 `<img>` 换成新 blob URL，
复现 Lezhin 滚动时的元素重建 → 会话 `content_reuse` 重发场景）。

- 日志：每次轮换 `content_reuse … seam=true` → `scene_ok … rebuilt=true` → `seam_dispatch … ok=true` →
  `seam_suppress image=mt1 bubbles=1 removed=2`（抑制在重建后重新生效）；共 5 轮。
- DOM 探针（CDP，150ms 采样 x 202 次，跑 30s）：含跨缝台词的节点**最多 1 个**
  （`{max:1, ids:["mt2:b0:text"]}`），`_overlays` 稳定为 `mt1/mt2` 两条。

### 4. 未覆盖

- 真机站点（Lezhin）复测需要用户会话：见 `HumanTODO.md` 2026-10-13 条目；
- 斜排气泡的排版框收窄（本轮跳过）；轮廓剖面 18 行的台阶（1~2px）。


## 2026-10-14 「悬浮球点不动」= 点球停止时 SIGABRT（§31 遗留崩溃）

执行者：OpenCode（DeepSeek V4.1 Flash）。设备 emulator-5556（1080x2400 / 420dpi），debug 包。

### 1. 复现（全自动，脚本化 start/stop）

| 轮次 | 操作 | 结果 |
|---|---|---|
| 0 | 点球（开始） | `ball_primary_tap` + `session_start`，pid 不变 |
| 1 | 再点（停止） | `session_stop` → **`Fatal signal 6 (SIGABRT)`**、`Force finishing MainActivity`、pid 消失 |
| 2 | — | 前台回到桌面 |

### 2. 根因（tombstone + `logcat -b crash`）

```
Abort message: 'No pending exception expected: kotlinx.coroutines.JobCancellationException:
                StandaloneCoroutine was cancelled (Throwable with no stack trace)'
#04 art::Thread::AssertNoPendingException
#05 art::ClassLinker::FindClass          ← pending 异常未清时解析类
#09 art::interpreter::FindFieldSlow
#13 com.manga.translator.app.EmbeddedBrowserScre…（解释器帧）
#29 kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith
#31 androidx.compose.ui.platform.AndroidUiDispatcher.performTrampolineDispatch
```

`stopSession()` 里的 `job.cancel()` 把 `JobCancellationException` 送进会话的 suspend 状态机；
ART 解释器对「异常传播中 + 首次解析字段」直接 abort。与 Todolist §31 记录的未修 SIGABRT 同源。

### 3. 修复后复测（同脚本）

- 连点 3 轮 start/stop：pid 不变、无 SIGABRT；`session_stop … cooperative=true`；
  日志 `ball_primary_tap running=false/true/false` 与真实状态一致（修复前恒为 false）。
- 会话运行中「离开网页」（组合销毁）：pid 不变、无 SIGABRT；`browser_dispose deferred_destroy=…`。
- 系统悬浮球（首页「启动服务」，`appops set … PROJECT_MEDIA allow` 放行录屏）：
  点球 → `translate_local ok=mlkit count=6` + 覆写渲染成功；译文层在时再点 →
  `floating_tap_restore_original`；全程无崩溃。

### 4. 回归

```
全模块 testDebugUnitTest             TOTAL tests=537  fail=0  errors=0
node app/src/test/js/mt-overlay-acceptance.test.js   ALL_OK 210/210
```

### 未覆盖

- `CaptureForegroundService` 的 `translateJob?.cancel()`（`onDestroy` / `resetActiveSession`）
  未改（语义上确实要中断），只补了 `floating_tap_busy` 诊断；
- 「停止」的完全即时性：协作式停止最多晚一个轮询间隔（1.2s）退出。


## 2026-10-14（第二轮）「超出太多了吧」：白底同色页面 + 块内多行

执行者：OpenCode（DeepSeek V4.1 Flash）。设备 emulator-5556（1080x2400 / 420dpi），debug 包。

### 1. 新夹具（复刻用户截图）

`scripts/make_white_ellipse_fixture.py` → `.verify/web/white-ellipse.html/png`
（白面板 + 上高椭圆泡（原文占上半 4 行韩文）+ 下气泡；与用户附图同构）。

### 2. 修复前（`.verify/93_after.png` + `93_after.logcat`）

```
solid_upgrade: count=2 evidence=b0=ring0.70/flat0.99 b1=ring0.87/flat0.99
refine_boxes: b0=338x164@75,508  b1=420x210@100,135
ink_tilt: |b0 interior_cap=213x659 mid=620 |b1 interior_cap=620x600 mid=620
          |b0 lineH=41.0 lines=4 |b1 lineH=70.0 lines=3
```

- 「容器」= 整页白底（`mid=620` = 页宽）→ 逐行最窄处算成 213px → 排版框被压成窄柱；
- `b1` 行数 3（实际 4）→ 行高 70（真值 42.5）→ 字号翻倍；
- 排版框还保留 15%/侧 的外扩 → 译文的行比原文宽 30%。
  截图：两个气泡的译文都比原文大、整块戳出气泡。

### 3. 修复后（`.verify/95_after.png` + CDP 实测）

| 气泡 | 字号 | 行数 | 原文行高% | 排版框(CSS) | 内容(CSS) |
|---|---|---|---|---|---|
| b0 | 34.8px | 4 | 5.85%（=41px） | 224x180 | 200x169 |
| b1 | 34.8px | 4 | 6.07%（=42.5px） | 278x187 | 255x169 |

译文与原文同字号、内容完全落在气泡内（`interior_cap` 不再出现 = 白底同色页面按「无容器证据」
处理，排版框 = 原文文字框宽度）。

### 4. 回归

- 灰云椭圆夹具（`.verify/96e.png`）：容器仍有边界 → 被采信；行数修对后 cap 按**每行**取
  （401 → 333）→ 译文比原文略小、不越出轮廓（用户规则：装不下才缩字号）。
- 单测：全模块 **542 通过 / 0 失败**（新增 `TextLayoutMetricsTest` 2 条、`BubbleInteriorScannerTest`
  3 条）；JS 验收 **210/210**。

### 5. 未覆盖

- 逐行可用宽度只下发一个「最窄值」，长译文的字号会比理论最优再小一点；
- 斜排气泡不吃这套规则。

## 2026-10-14（第三轮）原文回退三件套 + 像素感知均衡断行 + 盖板轮廓墨迹守卫

### 1. 单测与 JS 验收

- Gradle 全模块：**569 通过 / 0 失败**（52 个测试类；新增 `CoverOutlinePolygonTest` 8 条、
  `BalancedLineBreakerTest` 9 条、`OpenAiVisionClientTest` 新增 6 条）；
- JS 验收：**219/219**（`node app/src/test/js/mt-overlay-acceptance.test.js`，新增均衡断行 9 条）。

### 2. 盖板轮廓（C：锯齿 + 盖不住原文）模拟器复现与验证

夹具：`.verify/web/ellipse-bubble.html`（灰云背景椭圆泡，容器可量到）+ emulator-5556。

```
solid_upgrade: count=1 evidence=b0=ring0.78/flat1.00
ink_tilt:      |b0 interior_cap(cover_outline_only)=333x343 mid=471 |b0 lineH=36.0 lines=4
cover_outline: b0=rows18/drop0/clamp5(+19px)/dil2        ← 5 行轮廓本会切进字形带，被墨迹守卫回扩
render_scene:  owned=1 cleaned=true patches=0 flat=0
```

- 修复前该气泡的 `region_polygon` 会按容器最窄处（333px，比字形带 457px 窄 124px）裁剪，
  切进字形区 → 缺口处原文/背景透出（用户截图里的「锯齿 + 不实心 + 盖不住原文」）；
- 修复后 CDP 实测 `--mt-region-clip` 覆盖字形带（左缘 ~0.44%、右缘 ~99.6%，两端 4.5%/95.9%
  仍贴合椭圆轮廓），截图 `.verify/mt_cover_fix.png`：译文 4 行（8/8/9/9 字）、无孤字行、原文无残留。

### 3. 原文回退（A）与均衡断行（B）的验证口径

- A：`OpenAiVisionClientTest` 钉住「瞬时错误判据（0/408/425/429/5xx）」与
  「解析失败标记（垃圾文本/缺 key/截断 JSON）」；请求级重试路径靠真机日志闭环
  （`request_retry_transient` / `translate_split_retry` / `translate_parse_retry` / `sentence_retry`）。
- B：JS 端用注入式分词/测宽做表驱动（词不被劈开、标点禁则、标点优先、末行不孤字、行数保持）；
  Kotlin 端 `BalancedLineBreakerTest` 同口径；三链路（DOM/悬浮球/整图 PNG）都走「保持贪心行数」重排。

### 4. 未覆盖

- 「模仿原文逐行断点」需要原文逐行文本（本轮只带行数）；
- 竖排不重排；悬浮球手动放大字号后不重排。

## 2026-10-14（第四轮）截页晚邻接竞态「三覆盖层」补带（Todolist §42）

### 1. 夹具与复现

`.verify/web/seam-race.html`：`seamLine2A.png` + **延迟 90s 插入**的 `seamLine2B.png`
（模拟懒加载：A 处理完时 B 还没出现）。复现日志：

```
A: slice_classify detected=1 owned=1 cross=0        → 整块画掉（覆盖层 1）
B: band_prep band=90 incoming=0
   slice_classify detected=2 owned=2 orphan_adopted=1 → 整段认领重画（覆盖层 2）
   seam_scene mode=none（A 没建带）
```

### 2. 修复后（模拟器）

```
seam_race_band own=mt1 next=mt2 ok=true prev=b0 cur=b0 score=0.94 band=213x156@130,182
seam_dispatch   own=mt1 next=mt2 ok=true bubbles=1 covers=1
seam_suppress   image=mt1 bubbles=1 removed=2
seam_suppress   image=mt2 bubbles=1 removed=2
```

- CDP：`__mt_seam_root=1`；可见文字节点 = 接缝带 1 份 + 下方气泡 1 份；
  A 的重复块被摘（`visibility:hidden`、离屏 x=-823）。
- 截图 `.verify/mt_seam_race_fixed2.png`：跨缝句「笼子!! 要死或返回连接门或两个，只有两个。」
  一块盖板 + 一份译文、缝口无截断、不溢出；下方气泡「即使你得到一个徽 / 章…」完整
  （修复前被带盖板压掉第一行，见 `.verify/mt_seam_race_fixed.png`）。
- 中间踩坑记录：第一次补带因为 A 泡底 96.7% < 阈值 97% 被拒（`seam_race_skip reason=no_match
  prev[y+h=96.7] cur[y=0.0] best=0.94`）——阈值放宽到 8% 并把该几何写进单测；
  第二次盖板压到下一个气泡（认领框比真实气泡高），加「避让同图其它气泡」收边。

### 3. 测试

- `SeamRaceBandTest` 8 条（匹配/不误伤/事务几何/盖板避让/不同图像素尺寸换算）；
- 全模块单测 **577 通过 / 0 失败**；JS 验收 **219/219**。

### 4. 未覆盖

- 非实底跨缝句不补带（需要上一张清理图纹理）；斜排跨缝句不补带；
- A 泡离缝 > 8% 时不匹配（宁可漏补，不乱摘）。

## 2026-10-14（第五轮）图片重建（新 blob URL）后的接缝重复：补带兜底进贴回分支

用户步骤：翻到下面 → 滑回上面 → 图片重新加载 → 同一句出现两份。

修复：`EmbeddedBrowserScreen` 的「原文→译文」贴回分支在 `afterScenePlaced` 之后
补一次 `tryLateSeamBand`（与翻译路径同源：几何贴边 + 文本匹配 ≥0.7，用当前元素 id 下发）。

夹具：`seam-race.html` 追加「150s 后用新 blob URL 重建两张图」。模拟器实测（emulator-5556）：

```
16:12:44 SEAMRACE rebuilt
16:12:50 seam_dispatch own=mt3 next=mt4 ok=true bubbles=1 covers=1
16:12:50 seam_suppress image=mt3 bubbles=1 removed=2
16:12:53 seam_suppress image=mt4 bubbles=1 removed=2
```

CDP：可见 = 带 1 份（s0:text，[135,187,205,95]）+ 下方气泡 1 份（b0:text）；
旧带节点（race0）0 尺寸已失效；A 的重复块 hidden。截图 `.verify/mt_seam_reshow.png` 无重复。
全模块单测 **577 通过 / 0 失败**；JS 验收 **219/219**。

## 2026-10-14（第六轮）截图翻译「Lezhin 正文抓不到」：改用 PixelCopy 采集

用户发现：Lezhin 的正文（GPU 合成层的漫画图）在截图翻译里读不出，两个 OCR 引擎都读不出
→ 采集侧问题。根因：`webView.draw(Canvas)` 只画**软件层**，站点把正文图提升成 GPU 合成层时
那部分内容不在软件层里（抓到的图缺正文）。

修复：`EmbeddedBrowserScreen.startScreenshotTranslation` 改用 **PixelCopy**（读窗口真实合成像素，
GPU 层也在内，不需要系统权限）；失败自动回退「临时切软件层再 draw」。
`shot_capture` 日志新增 `src=pixelcopy|software` 便于区分。

两个设备都已安装（emulator-5556 + 真机 AN3GUT4A22006400）。

## 2026-10-15 翻译历史：搜索 / 改译文 / 作废缓存（新增功能）

用户反馈：截页被错误合并后，错误的「原文 → 译文」进了句子缓存，之后遇到同一原文一直复用错译。
本次给翻译历史加「搜索 + 改译文 + 作废」，并把改写**同步回句子缓存**（关键：只改历史快照没用）。

**改了什么**

- `core-store/SentenceTranslationRepository`：新增 `remove(SentenceCacheKey)` / `remove(Collection)` ——
  单条 / 批量作废缓存（批量一次落盘）。
- `core-store/TranslationHistoryRepository`：新增 `bubbleRefAt` / `pageBubbleRefs` / `updateBubbleTranslation`
  + 顶层 `parseHistoryBubbles`；`rewriteBubble`（internal，纯函数）只改 `translatedText` / `edited`，
  `x/y/w/h/bgType` 等渲染字段原样保留。
- `app/HistoryScreen`：顶部搜索框（按原文 / 译文过滤 + 命中高亮，最多标 20 处）、展开区逐句列出
  原文 / 译文 + 行尾「改」、修改译文弹层（原文只读 + 译文可编辑 + 保存 + 作废这句缓存）、
  展开区底部「作废本页缓存」、绿色「已修正」标记。
- `app/MainActivity`：三个回调接线（改译文同时 `sentenceRepo.put`；作废走 `sentenceRepo.remove`）+ toast 反馈。

**自动化证据**

| 项 | 结果 |
| --- | --- |
| `:core-store:testDebugUnitTest` | 12 个测试类全绿（新增 `HistoryBubbleEditTest` 5 例：坐标保留 / 越界返回 null / 坏 JSON / edited 解析 / 空 JSON） |
| `:app:testDebugUnitTest` | 257 通过 / 0 失败 |
| `:app:compileDebugKotlin` + `:app:assembleDebug` | BUILD SUCCESSFUL；dex 含 `TranslatorApplication`（非坏档） |

**装机验证（emulator-5554，Pixel_8，1080×2400，真实历史 145 条）**

1. **搜索**：输入 `Chapter` → 「找到 2 条」，列表由 145 条过滤为 2 条；展开后 `Chapter Two` /
   `Open Chapter 2` 命中处品牌蓝高亮（`logs/ardot-verify/history-search-expanded.png`）。
2. **改译文**：弹层把「작품을 불러오고 있습니다.」的译文改成 `。HELLO` → 保存后列表显示新译文 +
   绿色「已修正」；**文件级核对**：`mt_sentence_translation.json` 里同句译文同步为 `。HELLO`
   （这是根治「错误被反复复用」的关键一环）。
3. **作废本页**：对「韩文 · 4 个气泡」那条点「作废本页缓存」→ 句子缓存条数 468 → 464，
   该页 4 句全部从缓存消失（下次遇到会重新调翻译）。

**未覆盖**

- 弹层里的「作废这句缓存」与批量走同一个 `remove` API，装机时只验了批量入口
  （单句入口因模拟器 IME 遮挡坐标未点中），两者逻辑同源。
- 未在真机复测（本次为模拟器 + 真实历史数据）。
- 历史列表里的「原文」是当时 OCR 的结果；「原文本身就被错拼」的条目目前只靠用户自己看出来，
  界面上没有自动标记（见 HumanTODO）。

## 2026-10-15（第二轮）Kakao「翻到一半突然不翻了」的深层根因：扫描脚本 80 张硬上限（Todolist §48）

### 1. 真机取证（用户手机 AN3GUT4A22006400，正在读的一话）

用 CDP（`adb forward` + `scripts/cdp-eval.mjs`，本轮给该脚本加了 `@file` 入参绕开
PowerShell 5.1 吞引号的问题）读页面真实状态：

```
imgCount=230（其中 174 张是 370×486 / 760×1000 的漫画切图）
带 data-mt-id 的只有前 80 张（mt1..mt80）；第 81 张起（94 张）永远没有 id
```

扫到第 80 张就停 —— 会话列表里根本没有后面的切图（`BrowserScanScript` 的 `limit = 80`）。
这同时解释用户的另一句「我在中间看，它从开头翻」：阅读位置在 80 切之后时，扫描列表里
没有眼前那几张 → 「阅读位置优先」无锚可对，只能从第 1 张开始。

### 2. 修复

| 位置 | 改动 |
|---|---|
| `BrowserScanScript.kt` | `limit` 80 → 600（安全阀）；payload 增加 `truncated` / `candidates` |
| `EmbeddedBrowserScreen.kt` | `ScanResult.truncated` → `scan … capped=` + 首次命中打 `scan_capped` 警告；`MAX_RENDERED_SCENES` 80 → 600（场景/接缝缓存）；新增 `renderedSrcs`（「已产出过结果」不随缓存淘汰，防长章节翻完后再重翻最早的几十张） |
| `scripts/scan-script-verify.mjs` | 新增 `long_chapter` 夹具（120 切）：全部进列表、`truncated=false`、尾部 cut119 不丢 |
| `BrowserScanScriptTest.kt` | 新增 1 条：limit ≥ 500 且带 `truncated` 标记（防退回静默截断） |

### 3. 验证

```
:app:testDebugUnitTest             265 通过 / 0 失败（新增 BrowserScanScriptTest 1 条）
全模块 testDebugUnitTest           638 通过 / 0 失败（app 265 / core-translate 165 / core-store 113 / core-overlay 60 / core-capture 35）
node scripts/scan-script-verify.mjs  39 通过 / 0 失败（真实 Chrome，含新 long_chapter 120 切夹具）
node app/src/test/js/mt-overlay-acceptance.test.js  ALL_OK 223/223
```

模拟器端到端（emulator-5556，新夹具 `.verify/web/kakao-long.html`，100 切 + `?cut=70` 打开）：

```
session_start token=1 mode=TRANSLATE …
scan pending=100 reshow=0 scanned=100 … anchor=256 first_vy=219 capped=false   ← 从眼前第 70 切开始（旧版这里 scanned=80 且从第 1 切开始）
scene_ok … done=100（status「已显示 100 张 · 监听新图中…」，随后 30s 无重翻）      ← 旧版翻到第 80 切就停
CDP：100 张 dst 图全部有 __mt_scene_root（mt71..mt100 也在，旧版不可能存在）
```

已知夹具噪声：本夹具用 canvas 现画韩文，模拟器 OCR（本地 ML Kit 链路）读出的是乱码
（`E人L LＲ`），译文质量不作数 —— 本轮的验收点是「扫描/调度/不中断」，不是 OCR 质量。

真机（AN3GUT4A22006400）已装新包，等用户复测（HumanTODO 第十一轮）。

## 2026-10-15（第三轮）Kakao 盖板「锯齿状的背景」：region_polygon 孤立尖刺抑制（Todolist §49）

### 1. 真机取证（CDP 读活页面）

用户手机（AN3GUT4A22006400）正在读的一话里，某纯色盖板的 `--mt-region-clip` 多边形：

```
polygon(-12.00% 0.00%, …, -12.00% 32.67%, 50.71% 47.52%, …, 100.61% 32.67%, …,
        -12.00% 100.00%, 50.71% 100.00%, 90.63% 87.05%, …, 91.79% 67.33%, 92.07% 57.43%,
        50.71% 47.52%, 100.61% 32.67%, …)
```

两处**孤立内凹行**（y=47.52% / 100%：右边界 50.71%，上下邻行 90~100%）—— 即盖板
被切出单侧台阶。隐藏覆盖层对比截图（`.verify/jag_cmp.png`、`jag_zoom_cmp.png`）
确认台阶处透出的是原画面。

### 2. 修复

`CoverOutlinePolygon` 加固 3：逐侧检测「比两侧邻行都更靠内、幅度 ≥ 0.15×最大弦」的孤立
尖刺 —— 内部行丢弃、端行并入邻行边界；端行额外要求「行带内或往里两行是平的」以保住
椭圆/圆角顶底的收敛尾。日志 `cover_outline … /spikeN`。

### 3. 夹具复现（修复前后各跑一次，emulator-5556）

`scripts/make_panel_gradient_fixture.py` → `.verify/web/panel-gradient.html`
（浅色渐变面板 760×390 + 两行短韩文，容器宽 ≈ 字形 1.85 倍）。

| | 日志 | DOM 多边形 | 观感 |
|---|---|---|---|
| 修复前 | `b0=rows16/drop0/clamp1(+101px)/dil4` | 有 `0.62% 20.73%` 内凹点（两侧邻行 -32.96%） | 左侧台阶可见（`.verify/panel_cmp.png` 左） |
| 修复后 | `b0=rows16/drop0/spike1/clamp1(+101px)/dil4` | 该行被丢弃、全行 -32.96% 平滑 | 无台阶（`.verify/panel_cmp.png` 右） |

回归：椭圆夹具 `ellipse-bubble.html` → `b0=rows20/drop0/spike0/clamp7(+17px)/dil2`，
译文不出椭圆、无白肩（`.verify/ellipse_after.png`）。

### 4. 自动化

- `CoverOutlinePolygonTest` 12 条（新增 4：内部尖刺丢弃 / 端部尖刺平坦上下文并入 /
  端部尖刺行带内并入 / 收敛尾保持；调整 2 条既有用例口径）；
- 全模块 **642 通过 / 0 失败**；`node app/src/test/js/mt-overlay-acceptance.test.js` **223/223**。

真机已装新包，等用户重翻复测（HumanTODO 第十二轮）。
