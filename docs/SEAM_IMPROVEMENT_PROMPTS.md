# 接缝处理 · 参考插件分阶段实施 Prompt（2026-10-27）

> 目标仓库：`C:\homework\AI_work\android-app-translator`
> 参考实现：`C:\homework\translator`
> 适用链路：Android 内嵌浏览器漫画（`WebtoonImageTranslator.kt` + `SeamBubbleMerger.kt`）
>
> 用法：每个阶段单独开一个对话，把「公共上下文」+「阶段 N」整段粘过去。
> 阶段 1 只做纯几何，不新增 OCR；阶段 5 才会真正增加识别耗时。

## 0. 总览

| 阶段 | 内容 | 新增 OCR | 对识别速度 | 对整体速度 | 风险 |
|---|---|---|---|---|---|
| 1 | 多碎片成组 + 同容器例外 + 高字/容器跨缝例外 | 否 | 基本不变 | 不变或略快（合并碎片减少翻译条目） | 低 |
| 2 | 每页 span 的 `hasTextPixels` 三态 + text/cover 分离 | 否 | 基本不变 | 不变 | 低 |
| 3 | `SeamPairState` + 双页边缘证据回退 | 否 | 基本不变 | 不变（纯 CPU） | 中 |
| 4 | 接缝清理图从复合带单独生成 | 否（只多 inpaint） | 基本不变 | 每页可能 +0.5~1s，需缓存/按需 | 中 |
| 5 | 独立接缝 OCR + primary/standby | **是** | **会增加** | **会增加**，必须按需触发并实测 | 高 |

---

## 公共上下文

你在 `C:\homework\AI_work\android-app-translator` 工作（Android 漫画翻译 App，Kotlin）。
参考实现是同机的 `C:\homework\translator`（浏览器扩展 + 本地 OCR 服务，JS）。

先读：

- 本仓库：`CLAUDE.md`、`AGENTS.md`、`docs/CODE_MAP.md`
- `app/src/main/java/com/manga/translator/app/WebtoonImageTranslator.kt`
- `app/src/main/java/com/manga/translator/app/SeamBubbleMerger.kt`
- `app/src/main/java/com/manga/translator/app/SeamIncomingGeometry.kt`
- `app/src/main/java/com/manga/translator/app/SeamRaceBand.kt`
- `app/src/test/java/com/manga/translator/app/SeamBubbleMergerTest.kt`
- 插件参考：
  - `C:\homework\translator\extension\src\background\modules\seam-grouping.js`
  - `C:\homework\translator\extension\src\background\modules\seam-handling.js`
  - `C:\homework\translator\extension\src\background\modules\observation-results.js`
  - `C:\homework\translator\extension\src\background\modules\ocr-styles.js`
  - `C:\homework\translator\extension\src\canonical\modules\projection-utils.js`
  - `C:\homework\translator\extension\src\canonical\reconciler-modules\reconciler-pair-fallback.js`
  - `C:\homework\translator\extension\src\canonical\canonical-pipeline-factory\canonical-seam-ocr.js`
  - `C:\homework\translator\extension\src\canonical\canonical-pipeline-factory\canonical-cleaned-artifacts.js`

已经移植、不要再重做：

- 1:1 半行配对、句子连续性、边缘窗口、相邻行吸收、残片守卫、孤儿认领、
  incoming 抑制、接缝带、晚邻接 `SeamRaceBand`。
- 这些的阈值和注释都在 `SeamBubbleMerger.kt` / `SeamIncomingGeometry.kt` 里，先读再改。

约束：

- 韩文横排 webtoon 优先；日文竖排整列续接不在本轮范围。
- 除阶段 5 外，不新增 OCR、不改网络/模型/渲染协议。
- 纯逻辑必须 JVM 可测，不要依赖 `android.graphics`；建议新文件而不是继续撑大 `SeamBubbleMerger.kt`。
- 不提交 git，除非明确要求。
- 每个阶段先给 5 行计划，然后直接改代码并跑测试，不要只给建议。

验证命令：

```bat
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat assembleDebug
```

---

## 阶段 1：多碎片成组 + 同容器例外 + 高字/容器跨缝例外

**目标**：`SeamBubbleMerger.buildHalfPairs` 目前只支持「上半 + 下半」1 对 1；一个气泡被切成 3 块以上时，多出来的块只能 orphan/drop。把插件 `seam-grouping.js` 的成组能力移植过来。

**参考**：

- `seam-grouping.js#buildSeamRegionCandidateGroups`、`buildAnonymousSeamBodyGroups`、
  `areAnonymousSeamLinesCompatible`、`groupFitsSeamWindow`、`mergeSeamSpeechRegion`
- `seam-handling.js#seamPairTextCanContinue`、`seamLineFitsComposite`
- `ocr-styles.js#getSeamCandidateVisualContributionBox`
- 常数（`background-state.js`）：
  `SEAM_CROSS_MAX_LINE_COVERAGE=0.9`、边缘窗口 24、配对最大间隙 48、
  最小水平重叠 0.25、最小行高比 0.5、最大旋转差 20、最大带宽覆盖 1.5、
  可视宽度覆盖 0.9、可视面积覆盖 0.6

**要做的 4 件事**：

1. **多碎片成组**（放在 `buildHalfPairs` 之前）：
   - region groups：同一个 `region_id` 的 ≥2 个碎片，只要 anchored
     （crossesBoundary，或 upperEdgeOnly + lowerEdgeOnly 同时成立）、
     且 `groupFitsSeamWindow`（整组高度 ≤ maxCrossHeight，相邻块间隙 ≤ 自适应上限），
     就合成一个候选。合并后 `sourceLineCount` 用「相加」，不是 max。
   - anonymous body groups：没有 `region_id` 的正文行，≥3 块、confidence ≥0.8、
     `non_translate` 不为 true、不是聊天时间；兼容判据：
     行高比 ≥0.7、水平重叠 ≥0.4、间隙在 `[-0.25, +0.7] × 平均行高`、旋转差 ≤12°；
     同样 anchored + groupFitsSeamWindow；合并后 line count 相加。
   - 合并：outer/content 取并集；原文按页面/纵向阅读顺序拼接；外框百分比重算。
   - 已使用碎片从 used 排除；剩余继续走现有 1:1 `buildHalfPairs`，阈值不要改。
   - `SeamPlan.verdicts` 新增 `grouped_region` / `grouped_body`，保持下标一一对应；
     新增计数进日志。

2. **同容器句子连续性例外**：
   - `pairScore` 里，上块以句末标点结尾且间隙明显时原来拒绝配对；
     如果两块 `region_id` 非空且相同，则允许配对（对应 `seamPairTextCanContinue`）。
   - 如果 `Bubble` 没有 `regionId`：先查 `BubbleRegion`/`FinalBubble` 的来源；
     能低成本带过来就加可选字段；拿不到就保持现状，region 分支留 TODO 和测试，
     不要为了它大改 OCR 管线。

3. **高字/大字 SFX 例外**：
   - 移植 `seamLineFitsComposite` 语义：框高 > `maxCrossHeight` 时，
     只要 `top > 0`、`bottom < canvasHeight`、`height <= canvasHeight * 0.9`，
     仍可当作有效跨缝行；占满整幅高度/场景大框仍然拒绝。
   - 只用于「单行跨缝」判定，不要放宽所有气泡的 `maxCrossHeight`。

4. **容器轮廓跨缝（solid speech bubble）**：
   - `bg_type=solid` 且 `region_type=speech_bubble` 时，`region_polygon` / `fill_box`
     跨过缝但文字框不跨：按「容器贡献」算作跨缝候选（或 cover-only），
     避免拆成两半/丢弃。`caption_panel` 不参与（它会沿页面背景扩到另一侧）。
   - 边界条件：容器必须与文字框有交集；宽度 ≤ 图宽×0.9、高度 ≤ `maxCrossHeight`、
     面积 ≤ 图面积×0.6。

**测试**：

- 3 块多行气泡：上 1 行 + 下 2 行 + 额外碎片 → 一块，文本顺序正确，`sourceLineCount=3`。
- 3 条无 `region_id` 的正文行跨缝 → 成组；2 条 → 不成组。
- 同 `region_id` + 上块句末 + 明显间隙 → 合并；不同 region 同几何 → 不合并。
- 高于 `maxCrossHeight` 但严格内缩的单行 SFX → 保留；占满整高场景框 → 拒绝。
- 容器跨缝、文字只在一侧 → 识别为跨缝/cover-only，不漏字、不重复。
- 现有 `SeamBubbleMergerTest` / `SeamIncomingGeometryTest` / `SeamRaceBandTest` 全绿。

**不要做**：不要改现有 1:1 阈值；不要新增 OCR；不要动渲染协议。

---

## 阶段 2：每页 span 的 `hasTextPixels` 三态 + text/cover 分离

**目标**：气泡轮廓跨过分页线、但文字像素全在另一页时，没有文字的那一页现在可能多出一块空壳盖板/贴片。插件用 `hasTextPixels: true|false|null` 区分「确实没有文字」和「没记文字框」，我们移植这个三态。

**参考**：

- `observation-results.js#buildObservationPageSpans`（约 212-311 行）：
  `hasTextPixels: Boolean(textBoxOnPage || polygonOnPage)`
- `reconciler-observation.js`（约 215-216 行）：`true`/`false`/`null` 原样保留，**不能把 null 塌成 false**
- `projection-utils.js#canonicalSeamPageBoxes`（约 17-58 行）：
  显式 `false` 的 span **既不进文字框，也不进遮罩框**
- `reconciler-projection-utils.js#pageCoverIsEligible`（约 14-38 行）：
  整页几何都声明 false 时不发 cover 投影

**Android 落点**：

- `WebtoonImageTranslator.kt` 的 `CrossBubblePart` / `SeamBubbleGeometry` /
  `SeamBandScene` 增加 `hasTextPixels: Boolean?`。
- 在 `planSeamOwnership` / `crossPrepareBubbles` 生成移交块时判定：
  - 内容框（contentRect）在接收页上还有可见面积 → `true`
  - 内容框整块在接收页之外、只剩外层轮廓 → `false`
  - 没有内容框/老数据 → `null`（按 true 处理）
- `false` 时接收侧：
  - 不生成文字、不翻译、不擦字、不进清理图 mask；
  - 不生成空壳盖板/贴片；
  - 该气泡的译文由真正有文字的那一页拥有。
- `true` / `null` 保持现有行为。
- 注意 `crossPrepareBubbles` 现在会把擦字区域夹到 `sourcePolygonBoundsPx`；
  `false` 的块不要进入这条路径。

**测试**：

- 文字全在上页、外框跨缝 → 下页 `hasTextPixels=false`，无 patch/无 erase/无 mask。
- 文字在上下两页都有 → `true`，行为不变。
- 老数据没有字段 → `null`，按 true 处理。
- 现有接缝测试全绿。

**不要做**：不要把 `null` 当 `false`；不要在拿不准时猜测。

---

## 阶段 3：`SeamPairState` + 双页边缘证据回退

**目标**：现在接缝归属只依赖拼图 OCR 的结果。插件在 seam OCR 没合并成功时，会用**两页各自的贴边 observation** 做几何连通回退（`reconciler-pair-fallback.js`）。Android 的 `SeamRaceBand` 只处理晚到邻页，且靠文本匹配（≥4 字、字符多重集 ≥0.7），短句/OCR 变体匹配不上。

**参考**：

- `reconciler-pair-fallback.js#completedStructuralPairs`：状态必须 `completed`，
  `upper_ocr_edge` + `lower_ocr_edge` 同时存在，且叠加 `pixel_overlap` / `fragment_structure`；
  按当前 `imageRevision` 过滤旧证据。
- `reconciler-pair-fallback.js#buildStatePairFragmentGroups`：
  取两页贴边 observation，按 `translation_role` 分组，做连通分量；
  上下两组水平重叠 ≥0.55、中心对齐 ≥0.76，总碎片 ≥3；
  按阅读顺序拼 authoritative text，边界重复项标 loser。
- `reconciler-seam-ocr.js` 的 seam 状态机：`skipped / running / completed / failed / stale`，
  按 `imageRevisionByPage` 过期。

**Android 落点**：

- 新 `SeamPairState`（纯 Kotlin）：
  - key = 两页内容指纹（`FrameHash`/已有 content hash）+ 邻接 token；
  - 字段：`status`、`reasons`、`prevRevision`、`curRevision`、`updatedAt`；
  - 换图/懒加载 blob 变化时标记 `stale`。
- 在接缝归属之后加回退：
  - 取上一页底部贴边的气泡（优先 `CrossBubblePart` / `renderedScenes` / 已保存场景；
    拿不到原始 OCR 就用已有几何与原文，**不要为此新增一页 OCR**）
  - 取本页顶部贴边的气泡（拼图 OCR 结果）；
  - 按角色分组、连通分量、对齐判据、≥3 碎片；
  - 能拼出整句就替换两半的独立译文，重复的标 loser/不渲染。
- `SeamRaceBand` 改成：先看 `SeamPairState` + 几何回退，文本匹配作为第二判据；
  短句不再因为 <4 字直接放弃。
- 可选：上页底带 + 下页顶带的像素重叠风险检测，用来决定是否运行回退。

**测试**：

- 两页各有一个贴边半块 + 一个本页碎片（≥3）→ 回退合并成一句。
- 只有两个半块（<3）→ 不合并，保持现状。
- 旧 revision 的 seam 证据 → 标记 stale，不参与。
- 短句（`응?` / `뭐?`）不依赖文本相似度也能走几何回退。
- 现有 `SeamRaceBandTest` 全绿。

**不要做**：不要为回退新增 OCR；不要把低置信/无关的贴边整块吸进来（插件有 role/面积守卫）。

---

## 阶段 4：接缝清理图从复合带单独生成

**目标**：`buildSeamScene` 现在从**当前页的清理图**裁出接缝带；带子的下半段来自下一张图的条带，但下一张图自己的清理图可能和这张不一致，接缝处容易出现纹理不一致/残留。插件对 seam 复合带单独跑 inpaint，生成一张两页共享的清理图。

**参考**：

- `canonical-cleaned-artifacts.js#refreshSeamCleanedArtifact`（约 333-388 行）
- `buildSeamCleanedArtifactPayload` / `buildCanonicalCleanMasks`
- 服务端 `api_handlers.py` 的 `return_cleaned_image` 路径

**Android 落点**：

- `WebtoonImageTranslator.prepareVisuals` / `buildSeamScene` / `exportSeamBandImage`：
  - 需要背景（`needsBackground` 且非纯实底）时，用**复合带**（stitched 的 band 区域）
    + 合并后的最终 mask 单独做一张 `cleanedBand`；
  - 或者下半段优先取下一张图的 cleaned image；
  - 按 band rect + 两页 revision + mask 指纹缓存，避免每页重做；
  - 失败回退当前页 cleaned image。
- 只在 `SeamBandScene` 需要清理图时启用；纯实底气泡不跑。

**测试/验收**：

- 同一跨缝气泡：接缝带上下纹理连续，不出现两种 inpaint 痕迹。
- 纯实底场景不新增 inpaint。
- 缓存命中时 `seam_cleaned` 阶段接近 0ms；未命中时记录耗时。
- 现有接缝带测试全绿。

**速度**：会多一次 inpaint（每页约 0.5~1s，取决于图大小）；必须缓存 + 按需，不能无条件跑。

---

## 阶段 5：独立接缝 OCR + primary/standby（最后做）

**目标**：插件在接缝处 OCR 上片、下片与合成图三份，reconciler 只让一份当 primary，其余标 standby。Android 现在只有拼图一次 OCR；合成图读歪时没有第二意见。**这一阶段会增加识别耗时，必须最后做、按需触发、默认关闭。**

**参考**：

- `canonical-seam-ocr.js#processSeamPair`：seam payload、`requireCleanedImage`、状态机
- `seam-band.js`：带宽 `max(64, min(96, round(width * 0.15)))`
- `evaluateSeamEvidence`：edge/short_page/pixel_overlap 才跑
- `reconciler-projection-utils.js`：primary / standby 角色
- `content/configure.js`：渲染前过滤 standby

**Android 落点**：

- 新增小带宽 seam-band OCR：
  - 先只做 **composite band 一份**（成本最低）；不够再加 upper/lower；
  - 带宽用插件的 64~96px，不要用现在的 144~384px 拼接条带；
  - 只在「拼图 OCR 有贴边文字」或「像素重叠风险」时触发；
  - 按 band hash 缓存。
- 合并：
  - 拼图 OCR 为主证据，seam OCR 为补充；
  - 选面积/置信度更高的一份当 primary，另一份标 standby；
  - 渲染前过滤 standby，避免同一句画两遍。
- 加设置/特性开关，默认关闭；先 A/B 测耗时与质量。
- 如果测量发现收益不足，保留代码但默认关，不要影响现有链路。

**测试/验收**：

- 合成图 OCR 把两行并歪时，seam OCR 能纠正/补全。
- 同一句不会出现两份译文。
- 记录 `seam_ocr_ms`、`primary/standby` 计数；默认关闭时耗时与现在一致。
- 现有测试全绿。

**速度**：会增加 OCR 请求（小带宽，但仍是一次推理）；必须按需触发、缓存、默认关闭。不要在没有实测收益前设为默认。

---

## 公共验收清单

- `.\gradlew.bat :app:testDebugUnitTest` 全绿。
- `.\gradlew.bat assembleDebug` 成功。
- 现有接缝测试（`SeamBubbleMergerTest` / `SeamIncomingGeometryTest` /
  `SeamBandGeometryTest` / `SeamRaceBandTest` / `HandoffTextMatchTest`）全绿。
- 新增测试独立文件，避免把现有 600+ 行测试继续撑大。
- 日志/诊断：新增 verdict/计时键要能在 logcat 里看到；不要静默失败。
- 不提交 git；报告改了哪些文件、测试结果、未覆盖的情况。

## 不建议照搬

- 整个 canonical/reconciler 管线（14 个模块、coverage ledger、projection plan）：
  Android 是逐页会话模型，抄概念即可（entity / page spans / primary-standby），不要抄整套。
- 每对都三份 OCR：成本太高，必须按需。
- CMOA / pageComposite / 小说链路相关逻辑：与接缝无关。
