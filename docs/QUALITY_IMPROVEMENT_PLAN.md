# 质量改进方案

- 创建：2026-09-15 ｜ **本版修订：2026-09-19**
- 执行者：科拉（Android/Kotlin 质量工程专家）
- 阶段进度：阶段 0 ✅（09-15）→ 阶段 1 ✅（09-15）→ **阶段 A ✅（09-19，复跑基线）** → 阶段 B / C 待开工 → 阶段 D 待决定
- 证据入口：`verification.md`（逐轮自动验证记录）、`HumanTODO.md`（需人工确认项）
- 本轮边界：只读发现 + **只读复跑基线**（`test` / `assembleDebug` / `lint`）。
  **未改任何源码 / 构建文件 / 依赖 / CI** —— 跑前跑后 `git status` 与 `git diff HEAD` 指纹一致
  （`status_sha1=7aa4aad2…`、`diff_sha1=dd64b3f1…`）。

---

## 0. 本轮结论

### 0.1 改版后未体检的风险 —— ✅ 已关闭（阶段 A 已完成）

上一次质量基线是 2026-09-15。之后 09-17 / 09-18 落了一整套 UI 改版：
`MainActivity.kt` 被重写（净 -980 行）、`GlossaryScreen.kt` +1,020 行、`HistoryScreen.kt` 改写、
新增 `HomeScreen.kt`（1,254 行）与 `ui/` 包（`MtUi.kt` 598 + `Color.kt` 55 + `Theme.kt` 135），
新增 `ic_empty_box.xml` 与 `themes.xml`。

这轮改版当时**只验证了 `assembleDebug` 成功 + 逐屏截图核对**，`test` 与 `lint` 均未复跑。
本方案把「复跑基线」列为第一优先项，**2026-09-19 已执行完毕**，结论如下：

| 问题 | 现在的答案（2026-09-19 本次证据） |
|---|---|
| 那 130 个单测对**当前这棵树**还是全绿吗？ | ✅ **是**。130 用例 / 0 失败 / 0 错误 / 0 跳过，**五个模块全部真实执行**（无 UP-TO-DATE 复用） |
| UI 改版有没有打破既有用例？ | ✅ **没有**。`app` 12 / `core-overlay` 3 / `core-store` 46 于 14:26 执行；`core-capture` 35 / `core-translate` 34 用 `--rerun-tasks` 于 14:43-14:44 强制重跑 |
| 当前树能不能全量重建？ | ✅ **能**。`assembleDebug --rerun-tasks` → 117/117 tasks executed，BUILD SUCCESSFUL，APK 277,868,832 bytes |
| `app` 模块 lint 还是那 4 条假阳性吗？ | ✅ **是**，且**改版未引入新的 Error**。仍为 4 Error（四个组件的 `Instantiatable`）+ 9 Warning + 4 Information |
| 新写的 4,663 行 UI 有自动化保护吗？ | ❌ **没有**，0 个用例 → 见 G13，仍是最大缺口 |

> 详细证据（含弱证据排除过程）见 `verification.md` 2026-09-19 阶段 A 节。

### 0.2 一个值得记录的坑

首轮 `./gradlew test` 返回 BUILD SUCCESSFUL，但其中 `core-capture` / `core-translate` 两个模块的
测试任务是 **`UP-TO-DATE`**（复用 09-15 的旧结果）。`./gradlew cleanTest test` **无法解决**——
`cleanTest` 只清理聚合任务 `test` 的输出，对分变体任务 `testDebugUnitTest` 是空操作。
正确做法是 `--rerun-tasks`。

**教训：本项目的 "test 通过" 必须核对任务是否真的 executed，否则会把 09-15 的结果误当成本次结果。**

---

## 1. 项目事实（只读发现，2026-09-19）

| 决策项 | 状态 | 值 | 证据 |
|---|---|---|---|
| 版本基线 | DISCOVERED | `main` @ `135ff8c` | `git log` |
| 工作树 | CONFIRMED | **14 个已改 + 19 项未跟踪**（UI 改版整体未提交）；指纹 `status_sha1=7aa4aad2…`、`diff_sha1=dd64b3f1…` | `git status --porcelain` |
| Gradle / AGP / Kotlin | DISCOVERED | Wrapper 8.7 / AGP 8.5.2 / Kotlin 1.9.24 | `gradle-wrapper.properties`、根 `build.gradle.kts` |
| JDK | DISCOVERED | source/target 17；`org.gradle.java.home` 指向 Android Studio jbr | `gradle.properties` |
| SDK | DISCOVERED | compileSdk 34 / minSdk 29 / targetSdk 34（仅 app） | 各模块 `build.gradle.kts` |
| 模块图 | DISCOVERED | app + core-capture / core-translate / core-overlay / core-store | `settings.gradle.kts` |
| 单测框架 | DISCOVERED | **JUnit 4.13.2，唯一，全模块一致** | 各模块 `testImplementation` |
| 仪器测试框架 | DISCOVERED | androidx.test ext-junit 1.1.5 / runner 1.5.2 / core 1.5.0 | `app/build.gradle.kts` |
| Mock / 协程测试库 | DISCOVERED | **均无**（无 MockK / Mockito / kotlinx-coroutines-test） | 全仓 grep 0 命中 |
| Compose UI 测试 | DISCOVERED | **无**（只有 `ui-test-manifest`，缺 `ui-test-junit4`） | `app/build.gradle.kts` |
| 覆盖率工具 | DISCOVERED | **无**（无 Kover / JaCoCo） | `detect_quality_env.py` |
| 静态分析 | DISCOVERED | **仅 Android Lint**，无 `lint {}` 块、无 baseline、无 suppression | 全仓 grep |
| 代码风格工具 | DISCOVERED | 无 ktlint / detekt / `.editorconfig` | 全仓 grep |
| SCA | DISCOVERED | **无**（无依赖漏洞 / 许可证 / SBOM） | `detect_quality_env.py` |
| CI | DISCOVERED | **无**（无 `.github`，无任何 CI 配置） | `ls -a` |
| version catalog | DISCOVERED | **无**（`gradle/` 下只有 wrapper） | `ls gradle/` |
| `configuration-cache` | DISCOVERED | 已开启（09-15 曾作为 lint 假失败嫌疑，**已实测排除**） | `gradle.properties` |
| `testOptions` | DISCOVERED | 未配置（未开 `unitTests.isReturnDefaultValues`） | 全仓 grep |
| 质量证据约定 | CONFIRMED | 自动验证写 `verification.md`；人工确认写 `HumanTODO.md` | `AGENTS.md` 第 2 节 |
| 测试入口约定 | CONFIRMED | `./gradlew test` / `connectedAndroidTest` / `lint`，模块级 `:X:test` | `CLAUDE.md` |
| 测试总数 | DISCOVERED | **单测 18 文件 / 130 用例 / 0 忽略；仪器测试 6 文件 / 9 用例** | `grep -c @Test` |
| 2026-09-19 基线 | **CONFIRMED** | `test` **PASS**（130 用例 0 失败）／`assembleDebug --rerun-tasks` **PASS**（117/117 executed）／`lint` **FAIL**（仅 `app`，4 条假阳性） | `verification.md` 2026-09-19 阶段 A 节 |

---

## 2. 规模与测试密度

| 模块 | 源码 | 单测文件 | 单测用例 | 每千行用例数 | 说明 |
|---|---|---|---|---|---|
| `app` | 7,593 行 | 4 | 12 | **1.6** | 含 4,663 行 UI，零自动化 |
| `core-capture` | 751 行 | 2 | 35 | **46.6** | 09-15 补齐，密度最高 |
| `core-translate` | 4,563 行 | 5 | 34 | 7.5 | 覆盖 OCR 几何与合并规则 |
| `core-overlay` | 3,905 行 | 1 | 3 | **0.8** | **最大空洞** |
| `core-store` | 1,890 行 | 6 | 46 | 24.3 | 密度次好 |
| **合计** | **18,702 行** | **18** | **130** | **7.0** | 仪器测试 6 文件 / 9 用例另计 |

> 「每千行用例数」只是**粗略的疏密指示**，不是质量评价。用例数量多不等于保护到位，
> 反过来也一样——判断标准始终是"故意改坏逻辑时测试会不会红"（09-15 阶段 1 用过这个反证方法）。

### 最大的 6 个文件（维护与测试成本集中处）

| 文件 | 行数 | 现状 |
|---|---|---|
| `core-overlay/.../OverlayController.kt` | 2,058 | 无对应单测 |
| `core-translate/.../OpenAiVisionClient.kt` | 1,632 | 有部分测试 |
| `app/.../GlossaryScreen.kt` | 1,601 | 无 |
| `core-translate/.../PaddleOcrLocalTextDetector.kt` | 1,368 | 有部分测试 |
| `app/.../HomeScreen.kt` | 1,254 | 无 |
| `app/.../TranslateOrchestrator.kt` | 1,219 | 无 |

---

## 3. 缺口清单（按风险排序，每条绑定证据）

### G12 · 改版后未体检 —— ✅ 已关闭（阶段 A，2026-09-19）
- 原状：09-15 之后 `test` / `lint` 均未复跑。改版涉及 `MainActivity.kt` 重写、
  `GlossaryScreen.kt` +1,020 行、`HistoryScreen.kt` 改写、新增 `HomeScreen.kt` + `ui/` 包。
- **处置结果**：130 用例全部真实执行、全部通过（改版未打破任何既有用例）；
  `assembleDebug --rerun-tasks` 全量 117/117 executed 通过；lint 状态与 09-15 完全一致。
- 过程中排除了一处弱证据：两个模块的测试任务首轮为 `UP-TO-DATE`，`cleanTest` 无效，
  改用 `--rerun-tasks` 才拿到真实执行结果（见第 0.2 节）。

### G3 · `core-overlay` 3,905 行只有 1 个测试文件（3 个用例）【09-15 提出，仍未处理】
- 模块内大量**不依赖 Android 运行时**的判定类，零覆盖：
  `EraseComplexityAnalyzer`、`TieredEraseProcessor`、`EraseStrategy`、`WritableAreaRefiner`、
  `BubbleRegionDetector`、`BubbleRenderState`、`BubbleAdjustment`、`OverlayRenderConfig`、
  `BubbleColorExtractor`、`CleanedImageProcessor`。
- 这些直接决定"擦字用哪个档位 / 译文往哪放 / 底色取什么色"——正是用户在
  `HumanTODO.md` 里反复抱怨的观感问题（"覆写效果不好"）。
- 唯一有测试的是 `TranslationLockGesturePolicy`。

### G13 · 新 UI 层 4,663 行零自动化保护 【新增 2026-09-19】
- `HomeScreen.kt` 1,254 + `GlossaryScreen.kt` 1,601 + `MainActivity.kt` 580 + `MtUi.kt` 598
  + `HistoryScreen.kt` 317 + `SwipeRevealRow.kt` 123 + `theme/` 190 = **4,663 行，0 个用例**。
- 其中含**可纯 JVM 测试的状态逻辑**：首页状态行的条件渲染判定（哪个引擎没配好、显示哪一行）、
  配置档案的表单状态迁移、"厂商 → Base URL / Model 自动带出"、`API Key 跟随厂商` 的存取值规则、
  术语表冲突与生效顺序、历史列表的展开/折叠与文案拼装。
- 这些规则现在**只靠截图人工核对**（`verification.md` 09-18 各节），改一行就可能静默回归。

### G1 · 仓库级 `lint` 被 `app` 模块的假阳性阻断【09-15 已定位根因，处理方式待决】
- 现象：`./gradlew lint` 失败于 `:app:lintDebug`；**其余 4 个模块 lint 全部通过**。
- 4 条 **Fatal** `Instantiatable` 覆盖 `app` 清单里**全部**四个组件
  （`AndroidManifest.xml:10 / :18 / :27 / :33`）。
- 根因结论：**`app` 模块 lint 的类解析假阳性，不是代码缺陷**（5 条证据见 `verification.md` 09-15 节）。
- 影响：`app` 模块 lint 门禁不可用。**2026-09-19 已复核：状态不变**——仍为那 4 条
  Error（四个组件）+ 9 Warning；Information 由 2 条增至 4 条（新 UI 的 `mutableIntStateOf` 建议）。
  **UI 改版未引入新的 Error。**
- 处理方式属质量政策 → 见第 7 节 D5。

### G4 · 无覆盖率工具 → 无法回答"哪些行为没被保护"
- 当前只有"用例数量"这一个指标，而用例数量不能说明覆盖了什么。

### G5 · 无 SCA → 12+ 个直接依赖无漏洞 / 许可证检查
- 关键依赖：`onnxruntime-android:1.29.0`、`opencv:4.10.0`、6 个 mlkit 制品、`okhttp:4.12.0`、
  `security-crypto-ktx:1.1.0-alpha06`（注意：**alpha 版本**）。

### G6 · 无 CI → 所有质量活动依赖"人记得跑"
- `verification.md` / `.codex/testing.md` 中已多次出现"本轮未执行"。
- 远端 GitHub 仓库已存在，具备接 CI 的前提。

### G7 · 依赖版本在 5 个构建文件里重复硬编码
| 依赖 | 重复次数 |
|---|---|
| `androidx.core:core-ktx:1.13.1` | 5 |
| `junit:junit:4.13.2` | 4 |
| `kotlinx-coroutines-android:1.8.1` | 3 |
| `org.opencv:opencv:4.10.0` | 2 |
| `com.squareup.okhttp3:okhttp:4.12.0` | 2 |
| `org.json:json:20240303` | 2 |

无 version catalog。升级一处漏改另一处 → 版本漂移。

### G8 · `TranslationLockGestureOutcome.BLOCKED` 是死分支【09-15 提出，本轮核实仍在】
- `TranslationLockGesturePolicy.kt:5` 声明了 `BLOCKED`，但 `decide()` 的 `outcome` **恒为 `RELEASE`**；
  全仓除该声明外**无任何引用**（本轮 grep 确认）。
- 连带 `OverlayController.kt` 中 `outcome == RELEASE` 的判断是**恒真**。
- 测试侧 3 条用例全部断言 `RELEASE`——断言不可能失败，保护不了 outcome 契约（对 `reason` 分支仍有价值）。
- 背景：`TranslationLockGesturePolicy.kt:19-21` 注释说明 2026-08-27 有意改成"任何手势都释放"。
  **属有意简化留下的残留结构，不是回归**，但读代码的人会误判。

### G9 · 文档漂移【本轮核实仍存在】
- `CLAUDE.md:64` 仍写 `TranslationCacheRepository`（LRU / TTL）—— 该类**已不存在**，
  现为 `SentenceTranslationRepository`（永久保存）。全仓 grep 确认。
- `CLAUDE.md:68-70` 只列 3 家 provider，实际已有 7 家（含硅基流动、火山方舟等；2026-10-04
  删掉了给已下线的百度 OCR 路径用的 `AiProvider.BAIDU`，它本来也被 `HomeScreen` 的 filter 藏着）。

### G10 · 质量证据入口不唯一
- `verification.md` 与 `.codex/testing.md` 内容几乎重复（前者开头就是 `# Testing`）。

### G11 · 缺 `testOptions` 配置，限制了"下沉纯逻辑"的空间
- 未开 `unitTests.isReturnDefaultValues`。触达 Android 框架类的 JVM 单测会抛 `not mocked`。
- 不是缺陷，但意味着：**抽出来的逻辑必须保持 Android-free**，或显式加配置（构建改动，需授权）。

### G14 · 仓库根目录被临时产物污染 【新增 2026-09-19，低风险】
- 未跟踪且未被忽略：`screenshot.png`、`window_dump.xml`。
  `.gitignore` 已覆盖 `verify_*.png` / `bg1_screen.png` / `screen_current.png` / `/logs/`，
  但漏了这两个（以及 `app-verify-*.png` 那类命名是否在 `docs/` 下需确认）。

### 明确不是缺陷的项（避免误判）
- **全仓 `TODO/FIXME/HACK` 只有 2 处**，代码卫生良好。
- `app/src/androidTest` 的 `println` 是"断言 + 打印识别结果供人工比对"的既有模式，**不当作问题**。
- `PaddleSkewVerticalTextTest.upsideDownTextRecognized` 只断言 `isNotEmpty()` 属弱断言，
  但源码注释已明确写明是"已知局限"并指向 `HumanTODO.md`——**属已记录的限制，不是隐瞒**。

---

## 4. 改进方案

原则：**沿用项目既有模式与工具链**；先拿事实，再补行为，最后才是工具与门禁。
任何新工具 / 新依赖 / 门槛都由你决定，不由我默认。

### 阶段 A · 复跑基线 —— ✅ 已完成（2026-09-19）
- 动作：`./gradlew test`、`./gradlew :app:assembleDebug --rerun-tasks`、`./gradlew lint`（只读运行，未改一行代码）。
- 结果：
  - `test` **PASS** —— **130 用例 / 0 失败 / 0 错误 / 0 跳过**，五个模块全部真实执行。
  - `assembleDebug --rerun-tasks` **PASS** —— 117/117 tasks executed，APK 277,868,832 bytes。
  - `lint` **FAIL** —— 仅 `:app:lintDebug`；其余 4 模块 0 error；`app` 仍为 4 条 `Instantiatable` 假阳性。
- 完成标准：✅ 已区分清「已通过 / 已失败 / 未执行 / 被阻塞」，并给出**改版未打破任何既有用例**的明确结论。

### 阶段 B · 补 `core-overlay` 的擦字与排版判定（风险最高、收益最直接）
- 动作：按 `TranslationLockGesturePolicy` 的既有模式，把纯判定逻辑抽成 `internal object` + 数据类，
  保持 Android-free，用 JUnit 4 覆盖全部公开分支。**不改变任何判定规则与常量值。**
- 优先顺序：`EraseComplexityAnalyzer`（决定擦除档位）→ `WritableAreaRefiner`（决定文字可写区域，
  直接对应"覆写歪了"）→ `BubbleRegionDetector` / `BubbleRenderState`。
- 完成标准：`./gradlew :core-overlay:testDebugUnitTest` 全绿，且**用变异测试反证**
  （故意改坏 2~3 处判定，确认用例会红），流程同 09-15 阶段 1。

### 阶段 C · 把新 UI 的状态逻辑抽出来测
- 动作：不引入 Compose UI 测试（那是新依赖，需你授权）。改为按项目既有模式，
  把 UI 里的**纯判定**抽成 Android-free 的 `internal object` / 纯函数：
  - 首页状态行：给定（引擎、配置档案、ML Kit 状态、百度凭据）→ 该不该显示、显示哪一行、什么颜色；
  - 配置表单：厂商 → Base URL / Model 的带出规则；API Key 的记住 / 带出 / 切换规则；
  - 术语表：生效顺序、冲突归属、合并计划；
  - 翻译历史：行文案拼装（语言显示名而非 `ko → zh-cn` 代码）。
- 完成标准：上述规则各有用例，且改成坏值时会红。

### 阶段 D · 工具与门禁（四项都是政策决定，逐项等你拍板）
- D-1 覆盖率：引入 Kover，**只出报告、不设门槛、不做过滤**。
- D-2 SCA：依赖漏洞 + 许可证扫描，范围 = 实际打包依赖，先出清单不阻断。
- D-3 CI：在已有 GitHub 远端上接最小流水线，跑 `test` + `assembleDebug`；lint 是否进门禁取决于 D5。
- D-4 version catalog：收敛 45 处依赖声明（改动面大，建议单独一轮）。

### 顺手小修（低风险，可搭车）
- G8：删掉死枚举 `BLOCKED`，把 `decide()` 语义写成"总是允许释放"，同步修正恒真判断与测试命名。
- G9：更新 `CLAUDE.md`，使缓存 / Provider 描述与现状一致。
- G10：把 `verification.md` 与 `.codex/testing.md` 合并为单一证据入口。
- G14：把 `screenshot.png` / `window_dump.xml` 补进 `.gitignore`。

---

## 5. 完成标准（针对本方案整体）

1. 每个阶段的基线都有**本次**证据，且明确区分已通过 / 已失败 / 未执行 / 被阻塞。
2. 新增测试保护的是**需求与公开行为**，不是实现细节；每条都能在故意改坏对应逻辑时失败。
3. 覆盖率（若引入）只作为反馈信号，未被用来宣称质量。
4. 未通过缩小范围、排除代码、扩张 baseline、放宽规则或删除测试获得通过。
5. `verification.md` / `HumanTODO.md` 按项目既有格式记录，"待你实机确认"项明确。
6. 用户原有改动（尤其是未提交的 UI 改版）完整保留。

---

## 6. 需要人工判断、本方案不得代为决定的项

- 业务正确性、验收条件的具体数值（例如"覆写歪到什么程度算不可接受"）。
- 覆盖率门槛、漏洞 CVSS / 可利用性阈值、许可证例外。
- 性能与稳定性阈值（如 `capture_timeout` 可接受比例）。
- 是否接受 G1 遗留、是否接受发布放行。
- 是否允许改动生产代码、构建文件、CI 与依赖。

---

## 7. 决策记录

### 已确认（2026-09-15 用户答复，仍然有效）

| # | 决策 | 结论 |
|---|---|---|
| D1 | 本轮范围 | 要方案 + 跑基线 → 阶段 0 已完成 |
| D2 | 允许改动边界 | 改测试 + 抽纯逻辑（`internal object` + 数据类，Android-free，沿用 JUnit 4）；**不动构建文件 / CI / 依赖** |
| D3 | 覆盖率 / SCA | **都先不加** |
| D4 | 工作区 UI 改版状态 | 已算当前基线，正常纳入分析与测试范围 |

### 已完成阶段

| 阶段 | 内容 | 结果 |
|---|---|---|
| 阶段 0（09-15） | 基线：`test` / `assembleDebug` / `lint` | `test` PASS（95 用例）、构建 PASS、`lint` FAIL（仅 `app`，4 条假阳性）；G1 根因定位 |
| 阶段 1（09-15） | `core-capture` 补关键行为测试 | 新增 `CaptureFailurePolicy` / `FramePolicy`；35 用例全绿；**变异测试反证 3 处破坏全部被捕获**；全量 130 用例全绿 |
| **阶段 A（09-19）** | **改版后基线复跑** | `test` PASS（130 用例全真实执行）、`assembleDebug --rerun-tasks` PASS（117/117）、`lint` FAIL（仅 `app` 4 条假阳性）；**UI 改版未打破任何既有用例** |

### 本轮待确认（2026-09-19）

| # | 决策 | 现状 | 影响 |
|---|---|---|---|
| **D11** | 是否授权跑阶段 A（只读复跑基线） | **✅ CONFIRMED（2026-09-19）** —— 阶段 A 已完成 | 结论：改版未打破任何测试；构建全量可重建；lint 状态不变 |
| D12 | 阶段 B（`core-overlay` 补测）是否开工 | `UNRESOLVED` | 沿用 D2 边界即可，无需新授权 |
| D13 | 阶段 C 的形态：抽纯逻辑（无新依赖）vs 引入 Compose UI 测试（新依赖） | `UNRESOLVED` | 新依赖必须你点头；我建议**先抽纯逻辑**，与项目既有模式一致 |
| D5 | **G1 处理方式**：建 lint baseline / 关 `Instantiatable` 规则 / 升级 AGP / 暂不处理 | `UNRESOLVED`（09-15 提出至今） | 决定 `app` 模块 lint 能否成为门禁；四项都需你明确授权 |
| D7 | 截图链路真机回归 | `UNRESOLVED` | 阶段 1 是纯重构，但未上设备跑过截图链路 |
| D8 | `classify` 的**无空格** `"MediaProjection ..."` 文案是否补进匹配表 | `UNRESOLVED` | 补了会把这类异常从 `UNKNOWN` 改为「提示重新授权」，**属行为改动** |
| D9 | 是否需要 `connectedAndroidTest` 基线（需设备/模拟器） | `UNRESOLVED` | `app/src/androidTest` 9 个用例仍无本次证据 |
| D10 | CI 与 version catalog 是否纳入后续轮次 | `DEFERRED` | 阶段 D-3 / D-4 |

### 附录：G1 的候选处理方式（供 D5 判断，未执行）

| 方案 | 代价 | 风险 |
|---|---|---|
| 建 lint baseline | 低 | 会永久掩盖 `app` 模块**未来**新增的同类问题；且 baseline 一旦建立，容易继续往里加 |
| 关闭 `Instantiatable` | 低 | 关闭的是**全局**规则，同时失去对真实"组件不可实例化"缺陷的检测 |
| 升级 AGP | 中 | 变更工具链；与 Kotlin 1.9.24 / Compose 编译器 1.5.14 的兼容性需重新验证。**当前不在授权范围内** |
| 暂不处理 | 零 | `app` 模块 lint 门禁继续不可用（其余 4 个模块可用） |

> 我的倾向：**先不建 baseline、不关规则**。lint 报的是假阳性，建 baseline 会让这份报告永久变脏。
> 但这是政策决定，请你定。

---

## 8. 本轮明确不做

- 不升级 AGP / Gradle / Kotlin / 任何依赖（除非你要求）。
- 不引入第二套测试框架（不加 MockK / Mockito / kotlinx-coroutines-test / Truth）。
- 不改动 `app/src/androidTest` 的 `println` 诊断模式。
- 不设任何覆盖率、告警数、漏洞数门槛。
- **不建 lint baseline、不关闭任何 lint 规则、不做任何 suppression。**
- 不改任何生产代码 / 构建文件 / CI / 依赖 —— 本轮为只读发现 + 只读复跑基线
  （运行了 Gradle 的 `test` / `assembleDebug` / `lint`，跑前跑后工作树指纹一致）。
