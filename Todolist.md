# Todolist

## 30.跨切片续排的配对校验：别处的条带/移交糊到本图 + 本图文字被当「已翻过」丢掉（2026-10-03 深夜）

### 第二轮：用户报「现在变成这样了」（接缝处露字 + 乱码残留 + `page_gap`）

- 用户反馈（附截图：一个带接缝纹理的条带里，上一张的译文、边缘露出的原文 `밖에 …!!`、
  以及本图读坏的一行乱码 `1드하데 거리기라` 同时出现）：**「现在变成这样了。有关接缝处的代码在哪里」**，
  并要求**参考 `C:\homework\translator`**。

- 现场日志（trace `[5467219]`）：
  - `cross_prep: incoming=3 added=3 px=[188,0~491,85] [411,0~500,7] [103,124~357,145]`
    —— 上一张交了 3 块到本图顶部；
  - `erase_target idx=9 box=42x0@17,10 text=[1드하데 거리기라]` —— 本图把
    `악질들한테 걸리기라도` 读坏，框高 **<1%（退化框）** → 擦不到也盖不住；
  - `erase_target idx=7 box=50x3@31,5 text=[我回到连接门或只有两个！]` / `idx=8 box=14x0@68,5 [要么]`
    —— 移交过来的中文，盖板同样很薄；
  - `seam_scene: mode=band bubbles=2 band=311x98@16,2502 bg=cleaned own=[b0,b1] next=[c0,c1]`
    —— 接缝带**建出来了**；
  - `seam_dispatch own=mt2 next=mt3 ok=false bubbles=0 covers=0 reason=page_gap`
    —— 但渲染层要求两张图屏幕间隙 ≤3px，**整块被拒** → 退回「两半各画一份」；
  - `band_pair cur=<promotionBanner.webp> prev=<blob:…> adjacent=false reason=gap gap=46000`
    —— 原因：扫描列表里的「下一张」是**相距 46000px 的推广 banner**，不是下一片切片。

- 参考项目怎么做的（读源码后照抄/偏离都写明）：
  - `canonical/modules/seam-residuals.js`：接缝面吸收两类残片 ——
    `kind:"fragment"`（近完全包含：`captureCoverage ≥ 0.72 && overlap ≥ 0.9 && 残片面积 ≤ winner × 0.25`）
    与 `kind:"adjacent_line"`（同一气泡的相邻行：`isSeamAdjacentBubbleLine` = 纵向间隙落在
    `[-0.4, 0.55] × minHeight` 且横向重叠 ≥ **0.5**；并用 `overlapOverSmaller ≥ 0.35` 把「同一行的碎片」
    与「相邻行」分开）；第三条路径用 `残片面积 ≤ winner × 1.35` 兜底。
  - `canonical/modules/seam-surface-utils.js:buildSeamExtendedBubbles`：吸收相邻行后**把框并集扩大**、
    并把被吸收行的**译文按页序/纵向顺序并入 winner 的译文**（`sourceLineCount` 同步）；
    原文也逐行并进去（注释：「框和译文都盖住两行了，原文却还停在 winner 的半句上」）。
  - **我们的偏离**：吸收窗口取 `lineHeight × 0.3`（参考用 `minHeight × 0.55`）—— 它的输入是**逐行**检测
    （框高即行高），我们是 ML Kit 的**段落级**框，行高得自己算；窗口更紧是因为我们**还没有**「把被吸收
    行的译文并进 winner」这一步（见下「未做」），吃进相邻行会丢那行的译文。

- 本轮改动：
  1. **`next` 侧配对闸**（`SeamNeighbourPolicy.successor` + 会话循环）：只有几何上「本图正下方紧邻」
     的那张（gap ≤4px、同宿主/帧、宽度比 ≥0.9）才当下条带/接缝带/移交目标；否则本图当最后一片。
     真机 `page_gap` 与「两半各画一份」由此消失。
  2. **收到侧吸收**（新增 `SeamIncomingGeometry` + `WebtoonImageTranslator.absorbSameLineFragments`）：
     与本图移交框**同一行被横切成的另一半**（横向重叠 ≥50%、纵向 ≤0.3 行高、面积 ≤1.35×移交框，
     参考项目的 `fragment/adjacent_line` 口径）并进同一个框，并把那些碎片从本图场景里摘掉
     （它们本就是同一行，避免重复翻译）。
  3. **退化框兜底**（`crossPrepareBubbles`）：并框后仍不足一行高的框**向下扩到一行**（`enforceMinimumHeight`），
     仍 <4px 才丢弃 —— 真机 `box=42x0` 那种「擦了等于没擦」的框从此不会静默留下原文。
     并框放在「夹到上一张字形轮廓」**之后**：夹紧是为了不擦到邻居正文（2026-10-03 第一轮的修复），
     并框则用**本图自己的检出**作为「这里确实有字」的证据。
  4. 诊断：`band_pair` 增 `next/next_adjacent/next_reason/next_gap` 并做「同一图+结论只打一次」去重；
     新增 `incoming_absorbed`（吸收了哪几块、各自原文）。

- 验证（第二轮）：
  - 单测 **966 通过 / 0 失败**（全模块）。新增 `SeamIncomingGeometryTest`（9，含 `[5467219]` 的
    原始数字：移交框 `103,124~357,145` + 碎片 `102,110~354,123` → 必须并框；并排气泡/相隔一行的
    下一行/超大块 → 必须不并）、`SeamNeighbourPolicyTest` 增 successor 四条（含「相距 46000px 的
    banner 必须被判 gap」）。
  - **真机夹具 C（下一张是远处 banner，`seam-next-banner.html`：面板 + 20000px 间隔 + banner）**：
    `band_pair cur=two-bubbles.png … next=ani-panel.png next_adjacent=false next_reason=gap
    next_gap=20000`、`cross=0 crossOut=0`、`seam_scene: mode=none`（**不再**出现
    `mode=band` + `seam_dispatch … page_gap`）、该图仍 `final=2` 完整翻译；截图干净无糊字。
  - **回归 A（真相邻切片 `seam-after-line2.html`）**：`next_adjacent=true reason=adjacent next_gap=0`、
    `seam_dispatch ok=true bubbles=1 covers=1`、下一张 `prev adjacent=true` +
    `cross_prep incoming=1 added=1 px=[198,0~519,73]`、`scene_ok (owned=1 cross=1 incoming=1 final=2)`
    —— 跨缝仍是**一句译文、一块盖板**。
  - **回归 B（不相邻 `seam-foreign-band.html`）**：`next_adjacent=false reason=gap gap=72` +
    第二张 `adjacent=false reason=gap` → 两张各自独立翻译（`final=2` / `final=1`），
    无接缝带、无移交。

- **第二轮未做**：
  1. **被吸收行的译文没有并进 winner**（参考项目 `seam-surface-utils.js:buildSeamExtendedBubbles`
     会按页序把相邻行的译文并进去）—— 我们的吸收窗口因此收紧到 `0.3 × 行高`，
     只吸收「同一行的另一半」；「同一气泡的相邻行（缺译文）」目前不吸收（会丢那行译文）。
     要补齐需要一条「把吸收行的原文并进本次翻译请求」的支路；
  2. 接缝带被拒（`page_gap`/`host_mismatch`）后**两半的清理图纹理不同源**这一点没变：本轮只是
     让「下一张错配」不再发生，真·两张图屏幕间隙偏大（>3px）时仍会退回两半各画一份；
  3. `band_pair` 的「等下一页」轮询在有 banner 的场景里最多白等 `NEXT_PAGE_WAIT_ROUNDS` 轮
     （已按 `nextBoxCandidate == null` 收窄到「页面上确实没有下一张」才等）。

### 第一轮：本图文字被当「已翻过」丢掉 + 上一张的白框糊到本图

- 用户反馈（附三张截图：渲染结果 + 结果特写 + 该页原文）：
  **「出现了这种情况。我想看看代码在哪里」**。截图里一个气泡（原文
  `아니…하지만 그게… / 방해됐단말이야…`）出现三种东西：
  1. 第一行译出来了（「不是，但那」）；
  2. 第二行**还是韩文**（既没擦也没翻）；
  3. 旁边一个**白色矩形**，里面是**上一处内容**的中文（「接，死亡或返回连 / 接门或有两件事！」）。

- 现场日志（emulator-5556，Lezhin，trace `[32317270]`，前一条 `[32315535]` 是它的「上一张图」）：
  - `src=600x2400 stitch=600x2544 topH=144`（拼接图上方贴了上一张图底部 144px 条带）；
  - `ocr_texts_dropped … 방해됐단양이o를 하려면 죽거나@83,101,409x79` —— 条带里的尾行与本图顶部的字
    **被并成一行**（`…를 하려면 죽거나` 来自条带，`방해됐단…` 来自本图）；
  - `slice_classify … incoming=1 … dropped_neighbor=2`、`seam_verdict d0=dropped_top_handed d4=dropped_top_handed`
    —— 那块跨边界文本被当成「上一张已画过」**整块丢弃**（本图那半行于是既不擦也不翻）；
  - `cross_prep incoming=1 added=1 px=[187,0~499,82]` + `erase_target idx=4 text=[[笼子!! 要退出连接…]`
    —— 上一张移交的气泡按**整句**重排到本图顶部，就是截图里那个白框；
  - 上一张 `[32315535]` 的 `crossOut=1` 正是它交出来的那个气泡（`케이지라고요!!⏎접속종료를 하려면 죽거나`）。

- 根因（三条，环环相扣）：
  1. **配对没有校验**：`prevBottomStrip`（上一张图底部条带）与 `incomingCross`（上一张移交的气泡）
     跨 DOM 快照存活，而懒加载阅读器每轮只扫到 1~3 张图 ——「上一次处理的图」并不等于「本图正上方
     那张图」（`BrowserScanScript` 还先收显式选择器命中、再收通用命中，顺序不保证是文档序）。
     条带/移交因此可能属于**别处**的内容，而没有任何一处校验过这件事。
  2. **归属判定只看「边界以下那点可见区域」**：`SeamBubbleMerger.isSuppressedByIncoming` 把块裁到
     本图可见区再与 incoming 框比（现场 76% ≥ 60%）→ 整块 `dropped_top_handed`；可那块同时含本图
     自己的半行。
  3. **移交气泡无条件重排**：`crossPrepareBubbles` 用整个 `contentRect`（顶边可负）把上一张的整句译文
     铺到本图顶部 —— 一旦第 1 条配对错了，它就糊在本图的另一段上（白框）。

- 本轮改动（对齐 `Todolist.md` §29 的纪律：**先加证据，再堵闸**）：
  1. **新增 `SeamNeighbourPolicy`（纯逻辑，可单测）**：按**几何**找「本图正上方、最近」的那一张
     （不看扫描顺序、不看 `index-1`），再用 `decide()` 判相邻：同一宿主/帧、`gap ≤ 4px`、
     允许重叠 ≤4px、左右错位 ≤32px、宽度比 ≥0.9；不合格的原因都是稳定字符串
     （`no_prev`/`gap`/`overlap`/`not_above`/`x_offset`/`width_mismatch`/`other_host`/`invalid_size`）。
  2. **会话循环接闸**（`EmbeddedBrowserScreen`）：记录 `prevBottomStripSrc` / `incomingCrossSrc`；
     条带只在「来源图 == 几何上的上一张」时才复用（否则从那一张现场补裁），移交气泡来源不符则
     **整批丢弃并回收贴片**；新增两行诊断 `band_pair …`（cur/prev/adjacent/reason/gap/宽度/带宽/incoming）
     与 `incoming_dropped reason=…`。
  3. **上边界也是合并屏障**（`BubbleFirstRegionPlanner.setSeamBoundaries` + `crossesTopBandBoundary`）：
     拼接图上方条带属于上一张图，两侧文字**永不合并**（上边界不设「切开余量」——那是下边界专有的）。
     真机那行 `방해됐단양이o를 하려면 죽거나` 就是跨这条边界并出来的。
  4. **归属判定改口径**（`SeamBubbleMerger.isHandedOverByIncoming`）：满足**任一**才算「上一张已画过」
     —— ①整块面积被 incoming 覆盖 ≥60%；②可见段被覆盖 **且** 移交框**横向罩得住**本图检出（≥90%）。
     现场那对只有 34% / 74% → 不再丢弃；跨上边界的块在「没被整块移交」时一律
     **本图认领并裁进可见区**（撤掉旧的 `visibleFraction < 0.5 → dropped_mostly_above`）。
  5. **收到侧交叉判定 + 文本证据**（`WebtoonImageTranslator.filterContestedIncoming` +
     新增 `HandoffTextMatch`）：本图某检出与移交框重叠 ≥35% 且 <60%（= 伸出了移交框）且
     **本图文本解释不了移交原文**（字符多重集覆盖率 <0.7）时，丢弃该移交框、交由本图自己翻。
     `CrossBubblePart` 因此新增 `originalText`（下一张图第一次拿到文本证据），并打
     `incoming_decision kept/skipped detail=[原文]~[本图文本] sim=…`。
  6. **验收夹具**（真机 emulator-5556）：`.verify/web/seam-foreign-band.html`
     （两张**不相邻**的图 + 72px 空隙，素材是用户这次给的两张面板）。

- 验证：
  - 单测 **934 通过 / 0 失败**（全模块）。新增 `SeamNeighbourPolicyTest`（11）、
    `HandoffTextMatchTest`（6，含真机两形态 0.83 / 0.54），`SeamBubbleMergerTest` 加现场几何回归
    （`mixedLineStraddlingTopSeamIsNotDiscardedAsHandedOver`，用 `[32317270]` 的原始数字）与反向守卫
    （合法续排仍必须 `dropped_top_handed`），`BubbleFirstRegionPlannerTest` 加上边界屏障正反两条。
  - **真机夹具 A（不相邻，用户这类）**：`band_pair cur=ani-panel.png prev=two-bubbles.png
    adjacent=false reason=gap gap=72 band=0 incoming=0`、`incoming_dropped reason=gap count=1`、
    两张图各自 `scene_ok`（`final=3` / `final=1`），第二张的 `erase_target` 只有它自己的
    `아니.하지만 그게⏎방해됐단말이야`；截图里两段各自有中文、**没有白框糊在另一段上**。
  - **真机夹具 B（真相邻切片，§28 的老夹具 `seam-after-line2.html`，防回归）**：
    `band_pair … adjacent=true reason=adjacent band=90 incoming=1`、**没有** `incoming_decision skipped`
    （文本证据 0.83 ≥ 0.7 → 移交保留）、`cross_prep incoming=1 added=1 px=[198,0~519,73]`、
    `seam_dispatch ok=true covers=1`；截图里跨缝仍是**一句译文、一块盖板**。
  - 环境note：emulator-5556 的 guest 时钟比宿主快 5 天，跑两次后应用窗口又卡在
    `animation-leash of starting_reveal`（整窗黑屏、点击被吞）。**熄屏/亮屏**可解（实测两次），
    最后 `adb reboot` 后完成验证；这不是本轮代码问题，但会干扰真机验证（见 `HumanTODO.md`）。

- **下一步 / 未做**：
  1. **Lezhin 实机复测**（需要用户会话）：翻页 + 上下滚动各一次，看翻页后第一张是否还会出现
     上一页的白框中文、以及被切开的半行是否仍能接回（已写入 `HumanTODO.md`）；
  2. 条带高度自适应（`clamp(width×0.20,144,384)`）：日志显示 144px ≈ 一个面板文字高，
     是本轮现象的放大器（条带里能装下**整段**已翻过的文字），但先解决配对正确性；
  3. 「条带里的字与本图的字被检测器并成一行」目前只能靠归属/交叉判定兜，**不能拆分**；
     若要根治需要把条带与本图分开跑 OCR（代价：多一次识别）；
  4. `planSeamOwnership` 的 `minVisibleFraction` 字段已不再参与判定（保留记录意图），
     后续可连同 `dropped_mostly_above` 分支一起清理。

## 29.本地合并判据收严：两个独立气泡被并成一段（2026-10-03）

- 用户反馈（附两张截图：翻译结果 + 该页原文）：**「合并的规则需要收严。可以参考
  `C:\homework\translator`。图上的是一个气泡框里的两段文字，但是翻译的覆盖变成了一段。」**
  译文截图里上气泡 4 行 + 下气泡 3 行被合成**一句**中文、**一块盖板**（「笼子!! 要退出连接，
  骰子，连接门，或者有两件事，即使你得到徽章，你可能甚至不能死！」），下气泡原文被擦掉
  却没有自己的译文。

- 现场证据（`.verify/38_user.logcat`，Lezhin，本地 OCR）：
  - trace `[21930477]`：`bubble_region_stats: detected=0 blocks=11` →
    `bubble_first_result_stats: regions=0 assignments=5 final=5`；
    `erase_target idx=0` 的文本 = `케이지라고요.!!⏎접속종료를 하려면 죽거나⏎접속게이트로
    돌아가거나⏎두 가지 밖에 없는데.!! 악질들한테 걸리기라도⏎하면 죽지도 못하면서
    고통받을수도 있습니다.!`（**两个气泡的台词拼成一句**）；
  - trace `[21932148]`：该合并块作为跨缝气泡下发（`erase_target idx=5 text=[笼子!! …]`），
    即用户截图；
  - 原始块清单里这些块是 **ML Kit 段落级多行块**（`text` 内含 `⏎`，`@192,83 300x139` 是整段 4 行）。

- 根因（读代码定位）：无气泡区域时 `BubbleFirstRegionPlanner.route()` 走
  `buildOutsideAssignments → buildHeuristicTextBlockGroups → BubbleMergeHeuristics.shouldMerge
  (GENERIC_TEXT)`，而该函数**所有容差都用 `max(块高)` 归一化**：
  间距上限 `maxH × 1.35 = 139 × 1.35 ≈ 188px`，而两块真实间隙只有 **50px**（`272 − 222`）；
  行高被当成 139/65（**段落高度**）而不是 34.75/32.5（单行高）。
  同一个「单位错」上一轮已在 seam 模块修过（§28 第二轮 `medianLineHeight` = 框高 ÷ 行数），
  但 planner / orchestrator 的合并判据没跟着改。

- 对齐参考项目（只取**有调用方**的线上路径）：
  - `ocr-lines.js:getLocalPaddlePairGeometry` —— 所有阈值除以**投影后的行框高度** `avgHeight`；
  - `shouldMergeLocalPaddleSameLine` —— `lineOverlap ≥ 0.5·min(h)`、`|Δbottom| ≤ 0.35·avgH`、
    `inlineGap < 1.2·avgH`；硬闸：倾角差 > 18°、字号比 < 0.65；
  - `shouldMergeLocalPaddleParagraphLines` —— `lineGap < 1.2·avgH`（空行分段）+ 宽度比/边缘/中心对齐；
  - `isLocalPaddleParagraphBoundary` —— 组内 ≥4 行时 `gap ≥ 1.1·avgH` 才是段边界（我们取 1.1 作为合并上限）。
  - ⚠️ `ocr-cluster-geometry.js` 那套（`shouldJoinLocalPaddleCluster` 等）**没有任何调用方**，
    没有照抄。

- 本轮改动：
  1. **新增 `core-translate/…/translate/TextLineGeometry.kt`**：`lineCount`（显式行数、
     文本换行数+1、1 取最大 —— 对齐参考项目 `candidateLineCount`）、`lineHeightPx`
     （横排 = 框高 ÷ 行数；单列竖排 = 列宽）、`isVerticalColumn`（`lineCount == 1` 且
     高 ≥ 宽×2.0；**文本含 `\n` 时永不判竖排**，避免重犯 2026-09-30 的弱宽高比误判）。
  2. **新增 `core-translate/…/translate/OcrBlockMergeRules.kt`**：收严后的判决
     （行内 / 堆叠两分支 + 朝向、字号、间距、对齐四道闸 + 稳定 `reason`）。
     相对参考项目**刻意收紧**：段落分支要求「宽度比 ≥0.35 **且** 边缘/中心对齐」
     （区域已知时退回参考项目的宽松口径）；不实现它的 `sharedSpeechBubbleContainer` 逃生门。
  3. **`TextBlock` 新增 `lineCount` 字段**：ML Kit 取 `block.lines`；Paddle 单框 = 1，
     `PaddleOcrBlockMerger.mergeGroupToBlock` 横排取行数、竖排取 1。
     为什么必须有：Paddle 合并块的文本里**没有 `\n`**（刻意用空格连接，防止换行漏进译文），
     不给行数就只能退回「整块高度当行高」——同一个坑 Paddle 链路会再踩一次。
  4. **接入三处本地分组链路**：`BubbleFirstRegionPlanner`（outside 与区域内拆分两处，
     区域内的 `vSep > maxH×1.2` 改成 `1.1 × 平均行高`）、`TranslateOrchestrator`、
     `WebtoonImageTranslator` 的 local_group 兜底。远程百度 bubble 合并
     （`BubbleMergeHeuristics` + `OpenAiVisionClientTest` 的 7 条锁值）**未改**。
  5. **诊断**：`WebtoonImageTranslator` / `TranslateOrchestrator` 新增一行
     `block_merge_reject`（只打「接近但被拒」的出对，最多 3 条，含 `reason/gap/阈值/lh/框`），
     避免下次再从「最终气泡数」反推。

- 验证：
  - 单测 **全模块 898 通过 / 0 失败**（`:core-translate:test`、`:app:testDebugUnitTest` …）。
    新增 `OcrBlockMergeRulesTest`（17 条，含现场几何回归）、`PaddleOcrBlockMergerTest`
    补行数断言（10 条）、`BubbleFirstRegionPlannerTest` 补 3 条
    （无区域时两气泡不合并 / 一个气泡里两段拆成两个 / 同气泡多行仍是一个）。
  - **真机夹具 1（区域内拆分路径，用户那张原图）**：`.verify/web/two-bubbles.html`
    （`two-bubbles.png` = 用户面板原图，`width/height` 属性 + `aspect-ratio` 都写全）。
    `python scripts/verify-browser-page.py --url http://127.0.0.1:8899/two-bubbles.html
    --out .verify/39_two_bubbles.png --logcat .verify/39_two_bubbles.logcat --settle 35`：
    `bubble_region_stats detected=1 blocks=2` →
    `block_merge_reject pairs=1 sample=[reason=GAP gap=49>38 lh=35 a=51,304,309x104 b=179,112,310x143]`
    → `bubble_first_result_stats regions=1 assignments=2 final=2`，
    **两条** `erase_target`（`케이지라고요…` / `악질들한테…` 各自成句）、`scene_ok bubbles=2`；
    截图里两个气泡各自有中文译文与盖板。
  - **真机夹具 2（outside 路径，`detected=0`，与真机现场同一条链路）**：
    `.verify/web/two-segments-outside.html`（`make_two_segments_outside.py` 合成「无气泡轮廓 +
    两组韩文」，几何按现场日志复刻）。日志：`bubble_region_stats detected=0 blocks=3` →
    `block_merge_reject pairs=1 sample=[reason=GAP gap=56>33 lh=30 a=73,282,254x88 b=188,130,300x96]`
    → `bubble_first_result_stats regions=0 assignments=2 final=2`，两条独立 `erase_target`、
    `scene_ok bubbles=2`。旧代码同一对的上限是 `1.35 × 96 ≈ 130px` → 必定合并（即用户现场）。

- **下一步 / 未做**：
  1. Lezhin 实机复测需要你的会话（脚本代跑不了）—— 已写入 `HumanTODO.md`；
  2. 远程百度 bubble 合并仍用旧口径（它的竖排放宽是刻意为之，有锁值单测）；
     若日后也要按行高收严，需要先把 `BubbleMergeHeuristics` 的百分比坐标系换算清楚；
  3. 多列竖排块的「行高」拿到的是**并集列宽**（偏大）——本轮靠字号闸兜住（比值 <0.65 直接拒绝），
     没有做逐列还原；
  4. `TranslateOrchestrator` 的竖排判定仍是旧口径（`height ≥ width×1.15 || 文本 ≤ 2 字`），
     与 planner 已定稿的 `columns/narrow_block` 判据不一致 —— 悬浮球链路可另起一轮对齐。

## 28.跨页接缝：缝落在「第 2 行之后一点」时误擦邻居气泡 + 归属空洞（2026-10-03）

- 背景（用户反馈 + 三张截图）：**「感觉跨页的处理不好。原文的页是卡在第二行的后面一点。」**
  截图证据链（本轮逐张读过）：
  1. 原始面板：上气泡 4 行韩文（`『케이지』라고요…!! / 접속종료를 하려면 죽거나 / 접속게이트로
     돌아가거나 / 두 가지 밖에 없는데…!!`）+ 下气泡 3 行；
  2. 元素检查卡 `…to.overflow-hidden 720×2880 w-full …focusable` 的高亮块上边缘**正好在
     第 2 行之后**（= 站点把这一页切成了两张 720×2880 的切片）；
  3. 结果态：跨缝气泡整块被换成 4 行中文，第 1 行后留一团灰色糊痕；
     **下面那个气泡的第 1 行被擦掉却没有译文**（只剩笔画残渣），第 2/3 行仍是韩文。

- 根因（读代码 + 真机日志形态定位，全部有文件行号）：
  1. **归属空洞（最致命）**：`SeamBubbleMerger.planSeamOwnership` 的规则 2（整块跨上边界）与
     规则 4（`box.top` 落在邻居条带内）**无条件 drop**，前提是「上一张图会移交」。但上一张图
     并不总能移交（`botH=0`、贴片不足 2px、配对失败都会让 `crossOut` 为空）——
     于是这块文字**两边都不管**，永远保持原文。用户截图里「下半段没译文」正是这一类。
  2. **条带比「被切开的半行」深得多**：带宽 `clamp(width*0.20,144,384)`（720px 宽 = 144px ≈
     两行多），于是下一张图正文的开头也在条带里；跨缝框/盖板/接缝带纹理不受限地伸进去，
     就会盖到邻居正文上（截图里被擦掉一半的那一行）。
  3. **配对刀锋**：`min(h)/max(h) ≥ 0.5` 对「上 2 行 / 下 1 行」正好卡在 0.5；
     `maxCrossHeight = band × 1.5 = 216px` 又挡掉 3 行以上的合并 → 真机日志 `paired_bottom=0` 是常态。
  4. **两半的锚点被夹**：`buildScene` 给 owned 跨缝气泡**不**设 `stitch_overflow`，
     渲染层会把 `centerY` 夹到 0..100 → 大半落在缝以下时译文被整体上提、与盖板错位；
     整块落在下条带里的移交块更会在**页脚**画出一行假译文。
  5. **接缝层失效不可恢复**：接缝上屏后宿主立刻摘掉两半；之后接缝层若因布局变化
     `target_detached` / `page_gap` 自我隐藏，两半不会回来 —— 那块台词原文译文都没有。
  6. **条带截断无感知**：跨缝框顶到拼接图底边时说明条带装不下剩余部分，OCR 拿到的是半句
     （用户截图里 `Azidhadidagirad` 这种胡言乱语就是这么来的），此前没有任何日志。

- 本轮已做（对齐计划 §4.1–§4.5）：
  1. **归属不再有空洞**（`SeamBubbleMerger.kt`）：规则 2/4 的丢弃改成**以 `incomingCross`
     真的覆盖为前提**（同一 0.6 重叠判据）；没覆盖的框本图**认领**并裁进可见区
     （`clipDetection`，行数按可见比例折算）；整块落在下条带里的框改为
     `SeamOwnership(cross = true, renderHere = false)` —— **认领并移交下一页，但不在本图渲染**。
     新增计数 `orphanAdopted / handedTail / cappedCross` 与**逐框判决** `verdicts`。
  2. **切开余量上限**（`SeamConfig.cutAllowancePx` = `max(32, 行高×1.6)`）：跨下边界的框/配对
     合并框一律裁到 `分界 + 余量`，条带深处属于下一张图的正文不再被盖板/纹理吃进去。
  3. **配对判据改行数跨度**：`min/max ≥ 0.5` 的刀锋换成「以较小那块为一行，跨度 ≤ 3」；
     `maxCrossHeight = max(48, 条带 + 行高×2)`（行高未知时退回旧口径，旧单测不受影响）。
  4. **接收侧擦字夹紧**（`crossPrepareBubbles`）：擦字区域再夹到**移交过来的真实字形轮廓**
     （`sourcePolygonBoundsPx`，与渲染层同一坐标口径），夹完 < 4px 就不夹（口径对不上时宁保守）。
  5. **owned 跨缝气泡允许越界**（`buildScene`: `stitchOverflow = crossFlags[index]`）：
     锚点按真实框走，超出画面由 root 的 `overflow:hidden` 裁掉 —— 既修错位，也修页脚假译文。
  6. **接缝层失效可恢复**（`mt-overlay-renderer.js`）：`suppressBubble` 改成「移出 DOM + 登记」，
     接缝层失效（`dropSeam` / `invalidateSeam`）时按 `seam_key` 把两半**贴回去**并打
     `MTSEAM restore seam=… reason=… nodes=N`；`removeScenes`（「原文」）清空登记表。
  7. **接缝带几何容忍放宽**：两切片间隙容忍 1.5px → **3px**（小间隙由带纹理盖住，比退回两半更好），
     拒绝原因仍为稳定字符串 `page_gap`，实测间隙放在 `gap_px` 字段。
  8. **诊断**：`slice_classify` 增 `orphan_adopted/handed_tail/capped_cross/cut_allow`；
     新增 `seam_verdict`（每框一行判决）、`cross_prep` 明细（本图要求擦哪一块，像素框）、
     `seam_strip_short`（跨缝框顶到拼接图底边 = 条带截断嫌疑）。
  9. **`incomingCrossBoxes` 坐标口径 bug（真机抓到，本轮修）**：`detections` 是**拼接图**像素，
     而 `CrossBubblePart.contentRect` 是**本图**像素 —— 直接比较少了一个 `topH` 偏移。
     实测 `topH=129` 时「可见部分重叠 96%」被算成 **56%**（阈值 60%）→ 上一张图已经移交的
     下半句被本图**又认领又翻一遍**（截图里能看到没盖住的残字）。修法：给 `contentRect`
     的 y 加 `topH` 再传进去。这条在「归属空洞」修好之后才暴露 —— 以前它只表现为「少抑制一次」。
  10. **真机端到端夹具**：`.verify/web/seam-after-line2.html` + `make_seam_after_line2.py`
     （用用户截图那张原始面板按切点切成上下两张等宽切片，2× 放大）。
     ⚠️ 夹具的 `<img>` **必须带 `width`/`height` 属性**：扫描器在图片解码前用元素盒子
     判缩略图，`width:100%;height:auto` 的图解码前盒子高 0 → 上半切片**根本扫不到**
     （实测 `scan scanned=1`，那一半永远是原文；加了显式尺寸才是 `scanned=2`）。

- 验证（本机）：
  - `:app:testDebugUnitTest` **148/148**（新增：上 2 行/下 1 行必须配对、切开余量裁短、
    条带内整块认领并移交、跨上边界块「有移交才丢」的双向用例、`resolveCutAllowance`、
    `maxCrossHeight` 带行高）；
  - `node app/src/test/js/mt-overlay-acceptance.test.js` **186/186**（新增 §14：
    2px 间隙可用 / 大间隙仍拒并带实测值 / 接缝失效后两半贴回 / 幂等 / 「原文」清登记表）；
  - 新增夹具 `.verify/web/seam-after-line2.html`（+ 生成脚本 `make_seam_after_line2.py`）：
    **直接用用户截图那张原始面板按切点切成上下两张等宽切片**，缝落在第 2 行之后一点、
    下半切片里还带着那个被误擦的完整气泡；
  - **真机端到端**（emulator-5554 + `scripts/verify-browser-page.py`，日志
    `.verify/22_seam_line2.logcat`、截图 `.verify/22_seam_line2.png`）：
    页 A `seam_scene mode=band bubbles=1 band=909x437@503,259 own=[b0] next=[c0]`、
    `seam_dispatch ok=true covers=1`；页 B `seam_verdict d1=dropped_top_handed`、
    `cross_prep px=[536,0~1378,177]`（擦字**只在移交过来的那半句**上，下面那个气泡没被碰）、
    `scene_ok bubbles=2 (owned=1 cross=1 incoming=1 final=2)`。

### 第二轮：用户报「那两段合并了」（2026-10-03 晚，合并规则太宽泛）

- 用户反馈（附截图）：**「合并规则是不是太宽泛了。那两段合并了。」**
  截图里两个**各自独立的气泡**被合成一句翻译（「笼子!! 死了全速 但痛苦的笑声也在笑。
  我回到了连接门或两个。!!即使你到了浩瀚，你也可能遭受缺血，即使你不能死！」），
  并且整块字号被放大到铺满两段区域。

- 根因（我上一轮改出来的回归，链条清楚）：
  1. `medianLineHeight` 取的是**框高中位数** —— 缝上一张图只有一个检测框时，
     「行高」就等于**整个多行块**（真机实测 461px = 4 行块）；
  2. `maxCrossHeight` 是 `条带 + 行高×2`，于是被放大到 **1128px**（真机日志 `max_cross_h=1128`）；
  3. `resolveEdgeWindow(maxCrossHeight, lineHeight)` 与 `resolveMaxPairGap(..., maxCrossHeight)`
     又都拿它乘系数 → 贴边窗口 **507px**、配对间隙上限 **507px**；
  4. 结果：缝下方条带里的半行残片与**几十像素外的另一个气泡**同时落在窗口里、间隙也在上限内
     → `pairScore` 通过 → 两块被「配对合并」成一句。

- 修法（**对齐参考项目 `C:\homework\translator` 的纪律，同时辨别它自己的问题**）：
  - 参考 `seam-grouping.js` 的做法：所有判据以**行高为单位**，且要求分组**锚定在缝上**
    （`crossesBoundary` 或「上贴边 + 下贴边」同时存在）；它还给匿名行加了
    `heightRatio ≥ 0.7`、`gap ∈ [-0.25, 0.7]×平均行高`、旋转差 ≤12°、**≥3 片**才成组。
  - **它的问题**（仔细辨别后没有照抄）：`heightRatio ≥ 0.7` + 「匿名行 ≥3 片」会漏掉
    最常见的「一条行被切成两片、上下两片行数不等」（我们现场就是 2 行 / 1 行，比值 ~0.45）；
    而它靠 `region_id` 认「同一个气泡」——我们的拼接链路没有 region id。
    所以：**保留**我们的「行数跨度 ≤3」（补上它的漏），**采纳**它的「间隙按行高算」。
  - 具体改动：
    1. `medianLineHeight` 改成真正的**单行高**：`框高 ÷ 行数`，行数取
       `max(sourceLineCount, 文本换行数+1, 1)` —— 后者正是参考项目的 `candidateLineCount`
       （真机实测：文本里明明有换行而 `sourceLineCount` 还是 1，只信它就又退回「整块高度」）；
    2. `resolveEdgeWindow(lineHeight)`：`max(24, 行高×0.6)` —— **与 `maxCrossHeight` 解耦**；
    3. `resolveMaxPairGap(upperH, lowerH)`：`max(48, 较小那块×0.6)` —— 同样解耦
       （同一条被切开的行，间隙不会超过半行）；
    4. `maxCrossHeight = 条带 + 行高`（只多一行，不再是两行）；
    5. `resolveCutAllowance = 行高×1.2`（半行 + 行距，不再多到能圈住下一行）。

- 验证：
  - 单测 **152/152**（新增：`separateBubblesAcrossTheSeamAreNotMerged`（缝下方另一个气泡
    只在半行之外 → 不合并）、`genuineMidLineCutIsStillMerged`（真被切开的行 → 仍然合并）、
    `lineCountFallsBackToTextLineBreaks`、`medianLineHeightDividesByLineCount`，并更新了
    `edgeWindow`/`maxPairGap`/`maxCrossHeight`/`resolveCutAllowance` 的钉值）；
  - 真机（emulator-5554，`.verify/23_seam_line2.logcat`）：
    页 A `edge_window=272 max_cross_h=661 cut_allow=545`（修前 507 / 1128 / 737），
    `seam_verdict d0=cross`、接缝带 `band=908x429@498,270`；
    页 B `seam_verdict d0=owned d1=dropped_top_handed`（**不再有 orphan/重复翻译**）、
    `cross_prep px=[534,0~1370,180]`（擦字只在移交过来的那半句上）；
    `scene_ok bubbles=2 (owned=1 cross=1 incoming=1 final=2)`。
  - 注：行数回退修好后行高还会再小一半（真机那条检测文本有换行而 `sourceLineCount=1`），
    窗口/余量会进一步收紧 —— 这一条只跑了单测，没再跑一遍真机。

- **还没做（下一步）**：
  1. 真机复测（RIDI 同一页）：看 `seam_verdict` 是否还有去向缺口、`cross_prep` 的擦字框是否
     仍在邻居气泡上、`seam_strip_short` 是否出现；
  2. **条带截断自动重裁**：`seam_strip_short` 出现时把下条带加高到 2×（上限 768px）重跑一次 OCR
     —— 本轮只做了检测与日志，重裁需要会话侧重入，留作下一轮；
  3. **接缝带译文字号偏大**（夹具实测：译文占满整个气泡、字比原文大）：怀疑 `sourceLineCount`
     在拼接图上被低估（`原文字高 = 框高 ÷ 行数`）—— 与 2026-09-30「0.82× 偏小」是同一口径的
     另一侧，要单独收口；
  4. 两切片源像素尺寸不同时接缝带的 scale 口径（当前只用本图比例，靠 2% 显示宽度检查兜底）。

## 进行中（遗留问题，未处理）

- **RIDI 里仍有少量「无译文」的图**（实测 `2 张无译文`）：OCR 有检测（`detected=1/2`）但分组没产出气泡
  （`bubble_first_fallback reason=empty_result`）。这类图**已不再静默**（状态行会显示「N 张无译文」），
  但还没修「为什么分组没产出」。下一步方向：`bubble_first_fallback` 走的是 local_group，
  要看它对「跨页大图 / 旁白压在画面边缘」这类版式为什么产出 0 组。
- **Lezhin 实机确认未完成**：无头探针拿到的是「作品加载中」（按 IP/会话校验），
  只能在你设备的会话里验。Kakao Page 与 **RIDI 已实机通过**。

## 27.内嵌浏览器 loading 框：对齐参考项目的 `.mt-loading-card`（2026-10-02）

- 背景（用户要求）：**「还需要加上参考项目 C:\homework\translator 那样 loading 框」**。
- 参考项目怎么做的（已读源码）：漫画链路上先给目标图挂一个 `div.mt-overlay-root.mt-overlay-loading > .mt-loading-card`
  —— 半透明白色胶囊（`rgba(255,255,255,.85)` + 1px 描边 + 阴影 + 呼吸动画），文案按阶段换：
  「提取单页图片…」「识别当前页…」「解析识别结果…」「翻译文字中…」「处理跨页…」「渲染结果…」；
  位置取**目标可见部分的中心**（`getLoadingOverlayCardPosition`，webtoon 长图按图片中心会跑到屏幕外）；
  处理完换成译文覆盖层。

### 本轮已做

1. **渲染层 `app/src/main/assets/mt-overlay-renderer.js`**：
   - 新增 `MtOverlay.showLoadingJson / hideLoading / clearLoadings`，卡片挂在**和译文覆盖层同一个 layer**
     （`.mt-overlay-layer`）里，跟着滚动由合成线程一起搬运；锚点 = 目标矩形 ∩ 裁切可见部分 ∩ 视口 的中心；
   - 幂等：同一张图重复下发只改文案、不重建节点（重建就是肉眼可见的闪烁）；图片移出视口时置 `visibility:hidden` 但不注销登记；
   - 纳入帧同步（`hasLiveOverlays` / `syncAllOverlays`），`removeScenes()`（=「原文」视图）会一并清卡；
   - CSS 与参考项目同款（胶囊 + 呼吸动画），额外加了一个 10px 转圈；并把 `.mt-loading-card/.mt-loading-label`
     加进**宿主样式隔离**规则（RIDI 那种 `div{font-size:13px;letter-spacing:-.03em}` 会压到卡片上）。
2. **渲染管线 `WebtoonImageTranslator`**：`translateImage(onPhase=…)` 阶段回调 + `WebtoonPhase`
   文案常量（`识别文字… / 翻译文字… / 擦字渲染…`），由管线在 OCR 前、翻译前、渲染前各回调一次。
3. **会话 `EmbeddedBrowserScreen`**：取图前挂卡（`读取图片…`），阶段回调经 `mainHandler.post` 回主线程
   下发（回调可能在 IO 线程，WebView 线程受限）；三条出口都摘卡（成功贴图 / 图里没文字 / 失败），
   会话 `finally` 再补一次 `clearLoadings()` 兜底（点「停止」/ 跳转 / 离开 tab 时循环里那次可能走不到）；
   `REMOVE_JS`（「原文」）也带上 `.__mt_loading_root` 与 `clearLoadings()`；
   **「原文」视图下不挂卡**（用户要的就是干净原图）；贴回会话（RE_SHOW_ONLY）不挂卡（不处理任何图）。
   新增诊断日志 `loading_card_reject`（只在渲染层明确拒绝时打，用来区分「没调用」和「调了被拒」）。
4. **验收**：Node DOM 桩测试 `.verify/web` 前新增 §13（`app/src/test/js/mt-overlay-acceptance.test.js`）；
   真 WebView `rendercheck.html` 增 §12（6 条）；新增探针页 `.verify/web/loadingcheck.html`
   （每 400ms 把 `.__mt_loading_root` 的 DOM 实况打到 console，logcat 过滤 `LCPROBE`）。

### 验证证据（模拟器 emulator-5554，`127.0.0.1:8899/loadingcheck.html`，三张真实漫画图）

- `LCPROBE` 实测（DOM 实况，非 UI 桩）：
  `roots=1 html=<span class="mt-loading-spinner"></span><span class="mt-loading-label">读取图片…</span> top=292` →
  `识别文字…` → `翻译文字…` → `擦字渲染…` → 贴图后 `roots=0`；下一张图卡片跟着跳到 `top=637`；
  图片移出视口时 `visible=hidden`。
- 三个出口都验过：图片处理完 `roots=0`；**翻译中切「原文」**立刻 `roots=0`（且此后隐藏态继续翻完第 3 张但不再挂卡）；
  **处理中点「停止」** `roots=0`（会话 `finally` 兜底生效）。
- 单测：`:app:testDebugUnitTest` **143/143**；Node 渲染层验收 **175/175**（含新增 21 条）；
  真 WebView 自检 `rendercheck.html` → **pass=47 fail=0**（含 §11/§12 共 12 条）。
- 注：uiautomator 的无障碍树**看不到**这个卡片（`pointer-events:none` 的装饰节点），
  所以验收一律看页面侧 DOM 探针 + 截图，别用 `.verify/ui.ps1` 找它。
- 模拟器验证前后已还原系统 HTTP 代理（`10.0.2.2:7890`）。

## 26.内嵌浏览器底栏：「原文/译文」与「翻译/停止」拆成两种语义（2026-10-02）

- 背景（用户反馈）：**「内嵌浏览器那里原文切换和翻译两个按钮的功能不太统一或者说清晰」**。
- 现状诊断（都在代码里对得上）：
  1. 底栏一行塞 7 个元素（`EmbeddedBrowserScreen.kt` 原 1382-1441 行）：3 个导航图标 + 状态行 + 「原文」+「翻译/停止」；
     实测 360dp 屏上状态行只剩 ~68dp（约 5 个字）。
  2. 「原文」是**一次性动作**且带副作用：`restoreOriginal()` 先 `stopSession()` 再摘覆盖层；按钮本身没状态、没禁用态，
     没译过也能点，点了回一句「已恢复原文」。
  3. 「翻译」是**三合一**：开始连续翻译 / 停止 / 把缓存译文秒贴回来（`needsReShow` 分支）—— 文案在「翻译」「停止」之间跳，
     颜色蓝↔红，都没说明这一下会做什么。
  4. 「恢复原文」在齿轮菜单里又出现一次，与底栏按钮重复。
  5. 隐性 bug：接缝盖板的贴回依赖会话局部变量 `seamBySrc`，而贴回走的是**新会话** → 该路径恒为空，
     「原文 → 翻译」之后接缝那一段译文贴不回来。

### 设计定稿（用户从三个模型里选定「分段开关 + 独立任务按钮」）

| 概念 | 控件 | 性质 |
| --- | --- | --- |
| 看什么 | 「原文｜译文」两段式开关 | 只摘/贴译文层，**不**启停任务 |
| 翻不翻 | 「翻译/停止」主按钮 | **唯一**能开始/停止会话的地方 |
| 翻到哪了 | 状态行（行1 右侧） | 只写事实，首次空态才写引导 |

- **运行中点「原文」= 只隐藏，后台继续翻**（对齐参考项目 `C:\homework\translator` 的 `displayMode` 语义）；
  隐藏期间结果照常落缓存（`renderedScenes`/`renderedTokens`），只是不上屏、不进 `placedIds`，所以「译文」贴回是秒回。
- 「译文」在会话没跑时起一轮**只贴回**的一次性会话（`BrowserSessionMode.RE_SHOW_ONLY`），不再借用「翻译」按钮。
- 没有译文可切 → 分段开关置灰禁用（不再"点了没反应"）；齿轮菜单删掉「恢复原文」。

### 本轮已做

1. **新增 `app/.../app/BrowserTranslateUiPolicy.kt`（纯函数策略 + `BrowserTranslateUiPolicyTest` 8 条）**：
   把「什么状态下哪个控件可用/显示什么文案」钉成表格，避免以后再漂移（废掉「能点但没反应」这类问题）。
2. **`EmbeddedBrowserScreen.kt`**：
   - 底栏拆两行（行1 导航 + 状态行；行2 分段开关 + 主按钮），新增 `BrowserViewModeToggle`（选中段品牌色浅底+描边，
     与搜索引擎芯片同视觉语言；`contentDescription` = 「显示原文」/「显示译文」+ `selected` 语义，便于 `.verify/ui.ps1` 断言）；
   - 主按钮复用 `MtPrimaryButton`（给 `MtUi.kt` 加了 `height` 参数，默认 52 不变）；运行中红色「停止」+ 状态行小转圈；
   - `restoreOriginal()` → `showOriginal()`（不停会话）+ 新增 `showTranslations()`；`stopSession` 一起复位 `reShowBusy`；
   - 会话新增 `sessionMode` / `isViewVisible` / `onRendered` / `onFinished` 参数：贴图三处（缓存场景、缓存整图、新图场景/整图）
     与 `afterScenePlaced` 全部按视图闸门走，隐藏时不贴/不计数/不登记；`RE_SHOW_ONLY` 跳过一次 OCR 后直接收尾；
   - `placedIds` / `seamBySrc` 提升到 composable 作用域（修掉上面第 5 条隐患）；页面跳转重置视图与 `hasRenderOnPage`；
   - 「已显示 N 张」改用 `placedIds.size`（原来是"贴回次数"，会把同一张重复计数），隐藏时改说「已翻 N 张」。
3. **`.verify/web/rendercheck.html` 增 §11（6 条断言）**：`removeScenes → applySceneJson(同一份场景)` 后
   只重建一个 root、图层 8 个且 id 不重复、译文文本仍在 —— 这是「原文/译文」往返的 DOM 前提。

### 验证证据（模拟器 emulator-5554，本地验证页 `127.0.0.1:8899`，OCR=PaddleOCR / 翻译=ML Kit 本地）

- 单测：`:app:testDebugUnitTest` **143 个用例 / 12 个类全绿**（含新增 8 条）；`:app:assembleDebug` 成功。
- **未翻译态**：分段开关置灰，dump 里 `content-desc="原文/译文显示切换（还没有译文）"`；点它**无任何日志**（真禁用，不是"点了没反应"）。
- **运行态**：点「翻译」→ 主按钮变「停止」、开关可用；状态行 `已显示 1 张 · 缓存1 · 监听新图中…`（截图 `.verify/15_viewmode_running.png`）。
- **运行中点「原文」**：logcat `view_mode_set mode=original running=true cached=1`，**没有** `session_stop`；
  页面上的译文节点全部消失；状态行 `原文显示中 · 后台继续翻译 · 已翻 1 张…`；主按钮仍是「停止」。
- **后台继续翻**：15 图页面在「原文」态下连翻 11 张，日志逐张 `scene_cached_hidden bubbles=N`（只落缓存不上屏），
  状态行 `原文显示中 · 后台继续翻译 · 已翻 12 张 · 缓存1 · 1 张无译文…`。
- **点「译文」贴回（会话在跑）**：下一轮 `scan … reshow=14`，14 张覆盖层一次贴回（截图 `.verify/17_viewmode_reshow_many.png`）。
- **点「译文」贴回（会话已停）**：`session_start token=3 mode=RE_SHOW_ONLY` → `session_end token=3 mode=RE_SHOW_ONLY done=0 shown=1`
  —— `done=0` 证明没跑 OCR/网络，状态行 `已显示 1 张译文`。
- **往返 3 次无副作用**：每轮 `done=0 shown=1`，无重复 OCR；译文文本节点计数 8 → 0（原文）→ 8（译文），**无重影**。
- **刷新/跳转**：`session_stop reason=页面已跳转…` + 分段开关回到禁用态（`hasRenderOnPage=false`）。
- **齿轮菜单**：已无「恢复原文」条目（截图 `.verify/16_menu_no_restore_original.png`）。
- **WebView 自检**：`.verify/web/rendercheck.html` → **RC SUMMARY pass=41 fail=0**（含新增 §11 六条）。

### 待办（需真机看观感）

见 `HumanTODO.md` 2026-10-02 条目。另：模拟器验证前后已还原系统 HTTP 代理（`10.0.2.2:7890`）。

## 25.内嵌浏览器取图重构：站点适配表 + 懒加载/blob/canvas + 滚动容器坐标（2026-09-30）

- 背景（用户反馈）：**「kakaopage 可以，其他不行」**，Lezhin / RIDI 点「翻译」没反应；
  用户实际在用的三站是 Lezhin、RIDI、Kakao Page。
- 先纠正了一个前提：模拟器上装的是 **09:42 UTC** 的包，而本轮改动是 **21:0x–21:33** 才写的 ——
  **从未构建、从未安装**。所以「其他不行」是旧构建的表现，先重装再谈修复。

### 根因（三个真实 bug，都有证据）

1. **`lazyUrlOf` 无条件返回 `currentSrc`** —— 懒加载站的 `currentSrc` 恰恰是那张占位图，
   于是 `data-src` 上的真地址永远轮不到。表现：站点规则**命中了**（`explicit=3/4`）
   但候选一张都不剩。这是「Kakao 能翻、别的不能」的最直接原因。
2. **`isUsableImageValue` 把大 base64 当占位** —— `data-src` 上是 2.3 万字符的 base64 真图，
   因为 `naturalWidth=1`（占位图尺寸）被 `minNaturalWidth=200` 判成缩略图丢掉。
   区分「图还没来」必须看**地址来源**（`src` / `currentSrc` 还是懒加载属性），不是看尺寸。
3. **`isExcluded(el, doc)` 传错参数** —— 把 `document` 当成了排除项数组，`closest(document)`
   抛 TypeError，异常一路冒到 `collect` 外面被静默吞掉 → **配了排除项的站点整轮扫描返回空**，
   且日志里没有任何线索。现在每个候选单独兜异常，错误随结果带回（`errors` 字段）。

### 本轮已做

1. **`BrowserScanScript.kt`（新）** —— 取图扫描脚本从 `EmbeddedBrowserScreen` 里内联字符串
   变成独立模块，分两层：
   - 通用层：懒加载属性（`data-src`/`data-original`/`data-lazy-src`/`data-echo`…）、
     `srcset`/`<picture>`、CSS 背景图、`<canvas>`、`blob:`/`data:`、iframe（最多 4 个）；
   - 站点层：额外选择器、排除区、更合适的尺寸阈值。
   **坐标契约变了**：`left/top` 从「文档坐标」改成「**定位宿主**坐标系」，宿主 = 最近的可滚动
   祖先（与 `mt-overlay-renderer.js` 的 `resolveOverlayHost` 同一判据）。整图 PNG 兜底路径
   改成插进同一宿主，不再无脑挂 `document.body`（否则内层滚动时图文分家）。
2. **`BrowserSiteAdapter.kt`（新，`core-store`）** —— 12 个内置站点规则（含用户三站），
   纯数据 + 纯函数，匹配取**最长命中模式**让特化压过泛化。每站带 `note` 说明依据。
3. **`extractPagePixels`（新）** —— 让页面把像素交出来：`blob:` / `<canvas>` 走
   `toDataURL` + 分片回传（350k 字符/片）。画布被跨域污染时返回 `reason=tainted`，
   明确上报而不是静默失败。
4. **空结果画像** —— 扫不到图时状态行变成
   `没找到可翻译的漫画图（img=0 canvas=2）· 画面在 <canvas> 里：…`，
   用户看得见才有得报。
5. **站点适配 UI** —— 齿轮菜单新增「站点适配：Kakao Page / 通用规则」一行 + 开关；
   支持逐站关闭（`browser_disabled_sites`），总开关 `browser_site_adapter` 默认开。
6. **防注入损坏** —— 配置内联进 JS 前转义 U+2028/U+2029（它们是合法 JSON、却是 JS 换行符，
   不转义会 `evaluateJavascript` 语法错 → 表现为「点了完全不动」）。

### 验证（证据）

- **Node 真实浏览器验收**：`node scripts/scan-script-verify.mjs` → **34/34**。
  夹具覆盖：内层滚动容器坐标、`data-src` 懒加载、`<canvas>`、`blob:`、跨域污染画布报
  `tainted`、`<picture>`/srcset、CSS 背景图、排除区、容器命中去重、缩略图过滤。
  脚本从 **Kotlin 源码现抠**（不复制），抠不出来直接报错，避免「测试有拷贝、实现改了没跟上」。
- **Kotlin 单测**：Browser 相关 **82 项**全绿（`BrowserScanScriptTest` 15 项新增、
  `BrowserSiteAdapterTest` 17 项新增，含「不许写死哈希类名」这条约束）。
- **设备端到端**（`emulator-5554`，新包 `lastUpdateTime=2026-09-30 14:20:34 UTC`）：
  - `big.html`：`scene_ok bubbles=7`，截图确认 7 个气泡全变中文。
  - `.verify/web/lz.html`（自建「内层滚动容器 + `data-src` 懒加载」夹具，Lezhin 形态）：
    `scan pending=3 … scanned=3 lazy=3 hosted=3` → `scene_ok bubbles=7 host=scroll`。
    **`host=scroll` 就是关键**：覆盖层挂进了内层滚动容器（旧实现挂 document，滚动即错位）。

### 真实站点探针（`node scripts/real-site-probe.mjs`）

无头 Chrome 直接打开真页跑同一份脚本（用户给的免登录链接）：

- **Lezhin** `…/comic/gamesal/1`：页面文本 `01 화 5 / 37`（确实是阅读页），但图片区一直是
  「작품을 불러오고 있습니다」（作品加载中）→ 无头环境拿不到页图。**选择器 `.scroll-view img`
  命中 5 次**，确认阅读器本体是 `.scroll-view`；同时发现 `notice_contents` 警示图
  （`ko_warn_black.webp` 720×616）会被当正文，**已加进排除区**。
- **RIDI** `…/books/5208000065/view`：被 **Cloudflare 安全验证**拦下（`Ray ID …`），
  无头环境进不去。

### 未验完的部分（见 `HumanTODO.md`）

- Lezhin / RIDI 的**实机**取图结果（需要你设备上的登录会话）。
- RIDI 的阅读器是不是 `<canvas>`、画布有没有被跨域污染（决定要不要走「原生重取页图」）。
- 若确认被污染，备选方案（**尚未实现**）：用 `POST https://ridibooks.com/api/web-viewer/generate`
  `{"book_id":…}` 拿 `data.pages[].src`，再用现有 `BrowserImageDownloader`（已带 WebView
  Cookie + 页面 Referer）下载 —— 完全绕开画布与污染问题。该接口非公开，随时可能变更。

### 用户实测后的第二轮（2026-09-30 晚，RIDI「翻译失败」）

你实测 RIDI 后日志给出了**根因，而且是我这轮引入的**：

```
site_adapter page=ridibooks.com/books/5208000065/view adapter=ridibooks rules=true   ← 适配命中
scan pending=6 reshow=0 scanned=6 adapter=ridibooks explicit=68 frames=0 lazy=0 hosted=0
session_fail RuntimeException: A WebView method was called on thread
  'DefaultDispatcher-worker-1'. All WebView methods must be called on the same thread.
```

4. **`fetchImageBitmap` 在 IO 线程上碰 WebView** —— 这个函数整段跑在
   `withContext(Dispatchers.IO)` 里，而 blob/canvas 分支要调 `extractPagePixels()`
   （内部是 `webView.evaluateJavascript`）。WebView 方法是线程受限的，直接抛异常 → 会话当场结束
   → 表现就是「RIDI 翻译失败」。RIDI 第一张页图偏偏就是 canvas/blob，等于一张都翻不了。

   第一版修复用 `withContext(Dispatchers.Main)` **没生效**：栈里仍然是 worker 线程。
   说明这个环境里 `Dispatchers.Main` 没有正确落到 Android 主线程（其行为取决于
   `kotlinx-coroutines-android` 的 `MainDispatcherFactory` 是否被加载，不可用时会**静默**
   退化成共享线程池）。最终改成**自己绑 Looper**：

   ```kotlin
   private val mainThreadDispatcher: CoroutineDispatcher by lazy {
       Handler(Looper.getMainLooper()).asCoroutineDispatcher()
   }
   ```

   语义明确、不依赖任何隐式注册。教训：涉及「线程受限的框架对象」时，别信 `Dispatchers.Main`
   一定存在，要么自己绑 Looper，要么在调用点断言线程。

5. **扫描脚本不再静默吞异常** —— `collect()` 整个帧失败时会带上 `message + 栈首行`，
   随结果 JSON 的 `errors` 字段回传并进日志。上面那个「explicit 命中 68 条、候选只剩 6 张」
   的疑点就是靠这个机制暴露的（不然又是一次「日志里什么线索都没有」）。

**修复后的验证**（设备端，夹具页）：

- `.verify/web/cx.html`（新夹具，`pwsh .verify/make_canvas_page.ps1` 生成）：
  一页 `<canvas>` + 一页 `blob:` 对象 URL，**都画着真韩文**，放在内层滚动容器里。
  改这个夹具是为了补上一个盲区：原来的 `rc.html` 是纯色块，取像素成功也证明不了
  「取到的像素是对的」——纯色块永远 OCR 不出东西。
- 结果：`scan pending=1 … hosted=1` → OCR `detected=2 blocks=3`，读到的原文是
  `고마워요` / `그날 밤, 비가 내렸다` / `기다려 주세요`（**确实是从画布里取出来的像素**）
  → `render_done bubbles=3` → `scene_ok bubbles=3 rebuilt=true host=scroll`，无异常。

**真实 RIDI 实机复测（`https://ridibooks.com/books/5208000065/view`）**：

```
adapter=ridibooks rules=true
scan pending=2 … scanned=2, 4, 6, 7, 9 …        ← 阅读器边滚动边挂新页图
image_no_text url=blob:https://ridibooks.com/…   ← 页图是 blob: 对象 URL，
                                                   RIDI 把字节在 JS 里解出来交给 <img>
scene_ok bubbles=1 … done=7                      ← 逐张翻译，会话不再中断
```

截图确认：两个气泡已是中文（「我会把它放在笼子里。」「我不能吃其他蠕虫。」），
状态行 `翻译中 · 已完成 20 张`。

两个附带结论：

1. **RIDI 的页图是 `blob:`，不是 `<canvas>`** —— 之前调研推测「canvas 渲染」在这本书上不成立。
   这意味着「画布被跨域污染」那个担心的方案（原生重取页图）**暂时不需要**：
   `blob:` 是同源对象 URL，页面侧 `toDataURL` 一定能导出。
   仍需留意的反例：若某本书走 canvas 且被污染，会打 `pixel_extract reason=tainted`。
2. **`explicit=68` 是个观察点** —— RIDI 页面上 68 个元素命中了选择器，但只有 2~9 个是真页图。
   候选里混进了 UI 图（那些 `image_no_text` 基本就是它们）。目前不影响正确性（UI 图 OCR 不出文字，
   会被跳过、也不会贴覆盖层），但白跑 OCR 是浪费；后续可按「同一容器内宽度一致 + 纵向连续」
   再收一道，不急。

### 第三轮：用户报「有一张图是原图，没有覆盖层」（2026-09-30 晚）

用户的原话是「夹在『翅膀很漂亮』和『所以你拯救了蝴蝶吗』中间那张**是原图，没有覆盖层**」，
并问「是没有重试机制吗」。日志给出了答案，而且**卡顿和漏图是同一个根因**：

6. **`evaluateJavascriptSuspend` 没有超时兜底** —— 原来是
   `suspendCancellableCoroutine { evaluateJavascript(it) { r -> resume(r) } }`。
   回调在「页面正在导航 / WebView 已销毁 / 渲染主线程被同步脚本卡住」时**永远不会来**，
   协程就永久挂住 → **整个连续翻译会话停摆**：不再扫描、不再翻图，UI 还显示「翻译中」。
   实测到的表现就是主循环出现 **25.6s / 39.6s / 41.7s / 55.8s** 的空档（单张翻译其实只要
   300–900ms，所以问题不在翻译）。用户在这段时间滑过的图**再也没人管**。
   现在加了 3 秒超时 + 超时即打 `js_eval_timeout what=…`，调用方本来就把 `null` 当「这次拿不到」，
   会走重试或下一轮。

7. **「处理过但没产出译文」的图每轮重发一遍** —— 这条才是 50 秒空档的真凶。
   `scene != null` 时无论有没有气泡都会 `renderedScenes[src] = scene`，于是
   `needsReShow()` 永远为真（它以为覆盖层是被「原文」摘掉的）→ **每轮扫描都重新下发一次空场景**，
   白占主线程。而用户看到的是「原图 + 没有覆盖层」，它还不再重试。
   新增 `processedWithoutResult` 集合：这类图既不算「待处理」（不重跑 OCR），也不算「已渲染」
   （不重发空场景）。日志里 `image_no_bubbles` 与 `image_no_text` 都进这个集合。

**修复效果（同一本书、同一路径实机复测）**：

| | 修复前 | 修复后 |
|---|---|---|
| 扫描空档 | 25.6s / 39.6s / 41.7s / 55.8s（共约 2.7 分钟） | **无 >6s 空档** |
| 进度 | 长时间停在同一个 `done` | `done=1 → 2 → 3 → 4` 连续推进 |

顺带做的两件小事：

- **状态行现在会显示「N 张无译文」**。以前这类图是**静默**跳过的，用户只看到「某张图没翻」，
  分不清是识别不到还是程序漏了。这是「排障规则」里要求的：关键状态必须可见。
- `MAX_ATTEMPTS_PER_IMAGE` 3 → 5，并加了 `scan_slow` / `image_slow` 两处慢操作日志
  （超过 2s / 5s 才打，正常路径零噪声）。

**还没修的**：那些「2 张无译文」的图，是不是就是用户看到的那一张，还需要确认。
它们的特征是 OCR `detected=1/2` 但 `bubble_first_fallback reason=empty_result` ——
**分组**没把检测到的文字变成气泡。这是识别质量问题，不是流程问题，下一步单独查。

### 第四轮：用户报「跨页气泡的下半段没翻译」（2026-09-30 深夜）

用户先确认 **Lezhin 也能翻译了**，然后给出一个更精确的 bug：

> 「可能是跨页的气泡框翻译有问题。刚刚那个 ridi，没翻译的也是。它是一个跨页的气泡框，
> 但是有两段。一段在上面那页，一段在下面。然后下面的那个就没有翻译。」

这条把第三轮那个「某张图没翻」直接指向了**接缝（seam）链路**。日志证据：

```
页A  seam_scene: mode=band own=[b0] next=[c0]  render_done: bubbles=1 crossOut=1 seam=1
页B  render_done: bubbles=1 incoming=1 crossOut=0 seam=0     ← 收到 incoming 却没接缝
```

`Outcome.bubbleCount = finalBubbles.size + incomingCross.size`，页B 是 `1 + 1 = 2`，
但页A 渲染出的场景里**只有 1 个气泡**：

**下半那半进了 `incomingCross`（计数进去了）、却在 `buildScene` 里没被画进场景。**

三条候选路径，**没有猜**，而是加了精确计数让它下次自己说话：

1. `incoming=1 cross=1 final=2` 而渲染层只画了 1 个 → 问题在**下发/渲染层**
   （`mt-overlay-renderer.js` 的 `stitchOverflow` / 越界裁剪）；
2. `incoming=1 cross=0` → 卡在 `crossPrepareBubbles`（4 像素门槛、或坐标算出界）；
3. `incoming=0` → 上半那页根本没交出来。

为此加了 `sceneDiag`：`WebtoonImageTranslator` 在 `buildScene` 里记下
`owned / cross / incoming / final` 四个数，随 `Outcome` 带出，会话日志里打在 `scene_ok` 后面：

```
scene_ok bubbles=1 rebuilt=true host=document (owned=1 cross=1 incoming=1 final=2) done=…
```

> 为什么值得单独加这个：`bubbleCount` 是**和数**，本来就分不清「没进来」和「进来了没画」。
> 这是排障规则的直接应用 —— **先让关键计数可见，再改代码**。

**当前状态（2026-09-30 15:25 UTC 的包）**：诊断已装、跨页气泡的修复**尚未动手** ——
等下一次实机日志确定是上面三条里的哪一条，再改对应那一处。

### 过程记录：验证脚本把设备卡住过一次

第四轮期间我让 `scripts/verify-browser-page.py` 在设备上跑 80 秒的自动化验证，
它**卡在等待环节一直没退出，还占着模拟器**，用户反馈「刚刚好像卡住了」。

根因两条：

1. `wait_for()` 里的 ANR 弹窗处理是**无条件循环**：只要界面上老有「Wait」按钮，
   它就一轮一轮点下去，永远不会结束；
2. 没有全局超时，任何一步卡住都能把整个脚本无限拖住。

已加**三重保护**（用户要求「把验证脚本加个硬超时，防再挂住」）：

- **全局硬超时**：默认 300 秒（`--timeout` 可调），到点抛 `DeadlineExceeded`、
  以退出码 3 结束；
- **等待/取界面每次前检查剩余时间**，卡在轮询里也会被叫停；
- **ANR 弹窗最多连点 3 次**，之后判定「设备不健康」并放弃等待；
- 单个 `adb` 子进程超时封顶 60 秒。

教训：**给设备跑自动化，脚本自己必须有终点**；「等 UI 出现」这类循环没有硬超时，
等于把设备的控制权交给运气。

### 第五轮：用户给出缺句原文，范围收窄到「OCR 没认出来」（2026-09-30 深夜）

用户提供了那句没翻译的原文：**`햇빛을 받으면 반짝반짝 빛나고…`**，并说明它在
「翅膀很漂亮」和「所以你拯救了蝴蝶吗」之间。

有了具体句子就能逐句核对。本轮 OCR 认出的**全部**原文（`erase_target` 去重）是：

```
일렉스. · 나비를 무척 좋아하는 모양이구나. · 날개가 예쁘잖아요. · 그래서 나비를 구해줬니?
다른 벌레들이 잡아먹지 못하게. · 새장에 넣어줄 거예요. · 일렉스, · 감히 예쁜 나비를…
더러운 거미는 죽어야 해요. · 다른 거미들이 또 나비를 노릴지도 몰라요. · 그래서 근처에…
```

对照位置：`날개가 예쁘잖아요.`（翅膀很漂亮）之后直接就跳到 `그래서 나비를 구해줬니?`
（所以你拯救了蝴蝶吗）——**那句 `햇빛을 받으면…` 完全不在 OCR 结果里**。

**所以第四轮怀疑的「跨页下半没画」被排除了**：新加的计数证明接缝数据是齐的
（`scene_ok bubbles=2 (owned=1 cross=1 incoming=1 final=2)` —— 数据齐、也确实画了）。
问题在更前面：**OCR 识别 / 分组**。

于是再加一层诊断（只在可疑时打）：**当 OCR 文字块数多于最终气泡数时**，
把块的原样文本与坐标打出来 ——

- `ocr_texts_nobubble`：分组完全失败（有块没气泡）时打；
- `ocr_texts_dropped`：有气泡、但有块没被分进去时打。

三种走向一眼可辨：

| 日志 | 结论 | 改哪儿 |
|---|---|---|
| 块清单里有这句 | 分组把它漏了 | `BubbleFirstRegionPlanner` 归属判据 |
| 块清单里没有 | OCR 没认出来（小字/低对比/斜排） | 检测输入（放大、二值化、分辨率） |
| 连 `ocr_texts_*` 都没打 | 被并进了别的块 | 合并逻辑 |

**待用户复测**（新包 `lastUpdateTime=2026-09-30 15:32:51 UTC`）。

### 第六轮：盖板外扩改为随字号自适应（2026-09-30 收尾）

用户反馈：**「现在的译文下面有时候会有一点点原文残留。所以感觉覆盖层需要扩大一点点。」**
（截图：译文下缘能看到原文字形的一线笔画。）

根因：盖板（`cover_box`）的外扩是**固定 3px**（`COVER_PAD_PX`）。这个值来自同一天早些时候
用户「不扩展、最多几个像素」的要求 —— 但那次投诉的根因是**当时拿排版框（字形框 ×1.3）当盖板**，
而不是「3px 这个数本身不对」。固定 3px 在韩文小字上刚好，而韩文字形（尤其带收音的）
会探出 OCR 字形框一点点，**字号越大探得越多**，大字上就露出一线原文。

改法：**外扩随字高自适应并封顶** ——

```kotlin
fun coverPadPx(lineHeightPx: Float): Float =
    (lineHeightPx * COVER_PAD_RATIO).coerceIn(COVER_PAD_MIN_PX, COVER_PAD_MAX_PX)
// 0.06 / 3px / 8px
```

- 字高 108px → 约 6.5px（比原来多一线，够盖掉残留）；
- **8px 硬上限**：不会回到「盖板大出许多」那个被投诉过的状态；
- 三处（`region_polygon` / 四边形外扩 / `cover_box`）共用同一个值 ——
  它们口径不一致时，实底填充会被轮廓裁掉一圈。

**为防「调参调过头」，把逻辑抽成纯函数并加了 3 条单测把两个方向都钉住**：

| 测试 | 守的是什么 |
|---|---|
| `coverPadPx_neverShrinksBelowOriginalThreePixels` | 小字/字高未知 → 退回 3px，不许退步 |
| `coverPadPx_growsWithLineHeightButStaysCapped` | 大字要变大，但**必须封顶**，并断言上限仍是「几个像素」量级 |
| `coverPadPx_isMonotonicAndNeverExceedsCap` | 字高 1→500px 全程单调不减、不越界 |

> 为什么值得为「几个像素」写三条测试：这两个要求（盖多一点 / 盖板别太大）
> 是**互相拉扯**的，而两者只差几个常量。没有测试守着，某次调参就会悄悄越界，
> 而这类越界在真机上只表现为「观感不对」，很难反推是哪个数错了。

`SeamBandGeometryTest` 现在 10 项、app 单测合计 **122 项**全绿。

### 第七轮：OCR 质量闸门（过滤 `cYa` 这类误检噪声）（2026-09-30 收尾）

用户反馈：**「paddle 识别会出现这种字母。它没有去掉置信度低的识别框吗。
此外可能需要过滤一下这种无意义的句子」**（截图：空白处出现 `cYa`）。

#### 根因：只有一个「重试闸门」，没有「采纳闸门」

`PaddleQuadGeometry.isWeakResult`（分数 < 0.72 或无目标文字）**只用来决定
「要不要再转 180° 重试」**，重试完不管好坏都照样 `return` —— 于是误检一路流到
分组 → 翻译 → 覆盖层，最后被画在画面上。

设备日志完整复现了这个现象，**而且给出了分数，直接验证了判据该怎么设计**：

```
paddle_quad rot=-26 orient=180 text=cYa   score=0.550
paddle_quad rot=0   orient=0   text=caal  score=0.548
paddle_quad rot=0   orient=0   text=S     score=0.710   ← 高分噪声
paddle_quad rot=0   orient=0   text=O     score=0.783   ← 0.783！分数阈值杀不掉
…而真实台词：다른 거미들이 0.905 / 노릴지도 몰라요. 0.977
```

**结论：只靠分数阈值不够**（`S`/`O` 高达 0.71/0.78），必须加「目标语言文字数为 0」这一条。
真实韩文台词都在 0.90+，两者分得很开。

#### 改法

新增 `PaddleQuadGeometry.isAcceptableResult(text, score, lang)`，在
`PaddleOcrLocalTextDetector.recognizeQuad` 里做**入口质检**，不达标整条丢弃：

1. **分数下限** `MIN_ACCEPT_SCORE = 0.55`；
2. **必须是目标语言文字**：韩文页面上一个韩文字都没有 → 丢弃（专杀 `cYa`）；
3. 纯标点/无字母数字 → 丢弃。

两条刻意的设计约束：

- **只对韩文要求脚本**。日语**故意不要求** —— 日漫拟声词常是拉丁/片假名混排
  （`BANG`、`ドドド`），按脚本过滤会误杀。**宁可漏过滤，不可误杀。**
- 用「至少 1 个韩文字」而不是比例：韩漫里可能有纯拉丁的拟声词/外来语，
  比例会误杀；而「一个韩文字都没有」出现在韩文页面上基本就是噪声。

丢弃时打 `paddle_quad_reject quad_idx=… score=… script=… text=…` ——
不然「噪声莫名消失/出现」又会变成无线索排查（排障规则：关键决策要有日志）。

#### 与已有过滤器 `NonTranslatableText` 的分工（别混）

| 层 | 处理对象 | 行为 |
|---|---|---|
| **OCR 质量闸门**（本轮新增） | 误检噪声（`cYa`、`S`） | **丢弃**，不贴覆盖层 |
| `NonTranslatableText`（已有） | 纯数字/时钟/日期/URL/字母数字编码 | **原样直显**，跳过翻译引擎省钱 |

用户截图里那个漏在**第一层**：`cYa` 是「合法但无意义」的字符组合，
`isNonTranslatable` 判它可翻译（含字母），于是被翻译并显示。

#### 测试

`PaddleQuadGeometryTest` 新增 6 条，现在 18 项：

| 测试 | 守的是什么 |
|---|---|
| `acceptableResult_rejectsLatinNoiseOnKoreanPage` | `cYa`/`a c`/`c Y`/`XYZ` 全丢，**高分也丢** |
| `acceptableResult_acceptsRealKoreanEvenWhenShort` | `큭`、`얼음` 这类一个字的短台词不许误杀 |
| `acceptableResult_rejectsLowScoreRegardlessOfLanguage` | 分数下限对所有语言生效 |
| `acceptableResult_neverKillsJaLatinOnomatopoeia` | `BANG`/`ドドド` 不许误杀（防「改韩文顺手改了日文」） |
| `acceptableResult_rejectsPunctuationOnlyAndBlank` | 纯标点/空白 |
| `weakResultAndAcceptableResultAreDifferentGates` | 两个闸门职责不同，**高分噪声只被后者挡住** |

#### 一个已知的遗留风险（已评估，暂不处理）

`EngineSelectingTextDetector` 在「Paddle 返回空」时会回退 ML Kit。加了闸门后，
某页若 Paddle 只产出噪声（被全滤掉 → 空），就会触发回退，理论上噪声可能从 ML Kit 回来。
评估：**暂不处理** —— 回退只在「Paddle 一无所获」时发生，此时 ML Kit 顶多也返回噪声，
最坏情况不比改之前差；而「ML Kit 找到了 Paddle 漏掉的真文字」是收益。
若日后确认噪声真的从 ML Kit 回来，再改成「只有 Paddle **抛异常**才回退」。

### 第八轮：盖板「只对 ML Kit」再扩大一点点（2026-10-01）

用户反馈：**「现在的译文下面有时候会有一点点原文残留。所以感觉覆盖层需要扩大一点点。仅对于 ML Kit。」**
（截图：白色气泡里「我睡了三个小时。」下方露出一线原文字脚。）

#### 为什么上一轮（第六轮）的自适应外扩没根治

第六轮把盖板外扩从固定 3px 改成 `clamp(字高×0.06, 3, 8)`，但那是**通用档**。
用户这次的「仅对于 ML Kit」正是关键：两条链路的**字形框来源根本不同**——

| 引擎 | 字形框来源 | 盖板会不会切字 |
|---|---|---|
| PaddleOCR | **逐字紧贴字形的四边形** | 不会，轮廓就是字形本身 |
| ML Kit | 只给**块级矩形**，字形范围靠 `TextInkBounds` 在框内做 OTSU 反推 | **会** —— OTSU 把收笔/抗锯齿这类浅像素判成背景，墨迹框下沿落在字形里 |

于是 ML Kit 的盖板下边正好切在原文最后一行笔画上 → 译文下方那一线残留。

#### 改法：按「有没有文字四边形」分档（判据 = `bubble.polygon == null`）

| 路径 | 均匀外扩 | 下沿额外 | 下沿合计 |
|---|---|---|---|
| 有四边形（PaddleOCR） | `clamp(字高×0.06, 3, 8)`（**一个数没动**） | 0 | 3 ~ 8px |
| 无四边形（ML Kit） | `clamp(字高×0.08, 4, 10)` | `clamp(字高×0.08, 4, 6)` | **8 ~ 16px** |

- 字高 38px：下沿 3px → **8px**；字高 108px：6.5px → **14.6px**（用户截图那种单行气泡就在这个区间）。
- **额外余量只加下沿**：用户 09-30 投诉过「盖板框的**宽度**超出文字许多」，
  整体放大会把那个问题带回来；左右上只涨 1~2px。
- 斜排（≥3°，世界坐标的「下沿」≠ 文字的「下沿」）只吃均匀那一档，避免把四边形拉歪 ——
  这一条写进了代码注释与 `HumanTODO`。

#### 改动位置

| 文件 | 改动 |
| --- | --- |
| `SeamBandGeometry.kt` | 新增 ML Kit 档：`mlKitCoverPadPx` / `mlKitCoverPadBottomExtraPx` / `coverPadPxFor` / `coverPadBottomExtraPx` + 5 个常量（通用档 KDoc 标注「只给有四边形路径」） |
| `WebtoonImageTranslator.kt` | `resolveFinalBubbles` 按 `bubble.polygon != null` 选档；下沿额外值喂给 `region_polygon` 与 `cover_box` **同一份**参数（两处口径必须一致，否则实底填充会被轮廓裁掉一圈）；删掉已失效的三份重复常量 |
| `BrowserSceneBuilder.kt` | `expandPercentBoxByPx` 从私有方法提为公开纯函数并新增 `bottomPadPx`（默认等于 `padPx`）—— 「只加下沿」是用户可见行为的分界线，必须能直接单测 |

#### 验证（自动化）

| 证据 | 结果 |
| --- | --- |
| `SeamBandGeometryTest` | **13 项**（新增 3 项）：无四边形才吃新档、Paddle 路径逐值不变、ML Kit 档单调且封顶、下沿额外量恒 >0 且 ≤6px |
| `BrowserSceneBuilderTest` | **25 项**（新增 4 项）：下沿单独变大、默认对称口径不变、**横向一个像素都不多**、退化输入原样返回 |
| `./gradlew.bat :app:testDebugUnitTest --rerun` | **129 tests / 0 failures**（10 个测试类） |
| `./gradlew.bat :app:assembleDebug` | **BUILD SUCCESSFUL**，`app-debug.apk` @ `2026-10-01 00:56:08`（280 MB，含 PaddleOCR 模型） |

#### 本轮未覆盖

- **未上真机/模拟器**：观感（够不够干净 / 会不会盖过头）只能由用户判断，已写入 `HumanTODO.md`。
- 斜排 ML Kit 气泡（≥3°）没有下沿余量，若那里也有残留需要另做（不是简单加像素能解决的）。

### 第八轮：横排韩文被排成竖排（2026-09-30 收尾）

用户反馈：**「竖排的识别有问题。这里被识别成了竖排了。」**
截图：横排三行韩文 → 译文成了「捕, 追术教导技将它NPC唯一的那」。
用户补充关键限定：**「这个出现在 paddle 识别，ML Kit 没有」**。

#### 走错的一步（已撤回，记录教训）

第一版修法是**按源语言关掉竖排判定**（韩文→不信几何）。用户当场否掉：

> 「这样不好吧。韩文里面也可能会出现竖排文字」

**用户是对的。** 而且有反面证据：渲染层 `mt-overlay-renderer.js:600` **早就有**
「原文含韩文→不竖排」的判断，它并没有拦住这个 bug —— 说明按语言砍既无效又危险。
正确做法是**纯几何、与语言无关**的排布判据。该方向已全部撤回（含
`languageIsHorizontalOnly`，以及透传到 `route()` / `buildBubblesFromAssignment` 的 `sourceLanguage`）。

#### 根因：一个过松的几何判据被三处共用

`height >= width * 1.15f` 被用来判断「块是不是竖排」，出现在：

| 位置 | 后果 |
|---|---|
| `PaddleOcrBlockMerger.mergeGroupToBlock` | **主犯**：按 `centerX` 降序 + `joinToString("")` 拼原文 → **翻译前原文就乱了** |
| `BubbleFirstRegionPlanner.isVerticalTextBlock` | 下游再乱一次；且含 `text.length <= 2` 这条**语言无关**的误判 |
| `PaddleQuadGeometry.isConfidentVerticalCrop` | 决定要不要把 crop 转 ±90° 识别，判错则整段错乱 |

Paddle 倾向把整段合成**少数大块**，而 `hasTallBlockMajority`（`count*2 >= size`）
在只有 1 个块时**恒真** —— 这就是「Paddle 有、ML Kit 没有」的来源。

#### 改法：看**块的排布关系**，不看单个块的胖瘦

竖排的块是「**X 方向并排、Y 方向重叠**」→ 两块合起来**竖长**；
横排多行是「**Y 方向堆叠、X 方向重叠**」→ 合起来**偏宽**。

```kotlin
// 一对块是否构成并排的列
xOverlap < minW * 0.25f && (并集高 >= 并集宽 * 1.15f)
```

实测（临时埋点 `MT_MERGE_TRACE`，已移除）：

| 情形 | 并集 | 判断 |
|---|---|---|
| 横排一行碎片 | 200×60（0.30） | 横排 ✅ 未被拆散 |
| 横排第 1、2 行 | 500×160（0.32） | 横排 ✅ |
| 真竖排两列 | 90×200（2.22） | 竖排 ✅ 仍按右→左合并 |

**只看「有没有横向间隔」是错的**：真竖排相邻两列可能只隔 10px，横排同行碎片也可能隔 20px
—— 实测按间隔判会把一行拆成 4 块。必须叠加「合起来竖长」。

顺带修掉一个**原有 bug**：竖排分支要求 `overlapX >= minW * 0.55f`（水平重叠 ≥55%），
这与「并排的列」自相矛盾（并排按定义没有水平重叠）→ **真竖排的两列永远合不起来**。
改为「纵向重叠 + 水平相邻」。

#### 诊断日志不能打在 planner 里

`BubbleFirstRegionPlanner` 是被 JVM 单测**直接调用**的纯 Kotlin 类；加 `android.util.Log.d`
后 7 个单测一起抛 `Method d in android.util.Log not mocked`（实测踩到）。
改为把判定原因作为**数据**带出（`Bubble.layoutReason`），由 `WebtoonImageTranslator` 记录：

```
vertical_layout reason=columns box=11x13(%) text=…
```

`reason ∈ {columns, narrow_block, block_aspect}`，空 = 横排；**只在确实判竖排时打**。

#### 测试

`PaddleOcrBlockMergerTest` 新增 3 条，其中
`threeHorizontalLinesKeepRowMajorReadingOrder` 是**本 bug 的直接回归**；
`verticalColumnBlocksStillMergeRightToLeft` 是「不许为修横排牺牲真竖排」的守卫。
这两条**在改到一半时真实失败过**（`expected:1 but was:4` / `but was:2`），证明确实卡住了链路。

`BubbleFirstRegionPlannerTest` 新增 5 条，含
`legacyHeightMajorityHeuristicWouldMisjudgeShortWideRegion` —— 把**旧判据的错误行为**
钉在测试里防回流。

`PaddleQuadGeometryTest.verticalCropDetection` 更新为新契约（阈值 1.4 → 1.8），
并显式断言「1.41 倍这种旧阈值会误判的形态现在必须被拒绝」。

#### 诚实记录：planner 对「三行横排」其实算的是横排

用 `git show HEAD` 取回原始判据复算后发现：用户那个「三行横排 + 宽扁区域」，
**旧 planner 判据算出来是横排** —— 所以那次错乱的**主因是 merger**（原文按列序打乱），
不是 planner。planner 的旧判据确实会翻车，但翻在**矮而窄**区域上（`1*2>=1` 恒真）。
两条路径都改了、各自有测试守住；这个区分已写进对照测试，避免后人误判。

#### 验证

- app 134 / core-translate 90 / core-store 93 = **317 项单测全绿**（`--rerun-tasks` 强制重跑）；
- 无头扫描脚本 34/34；
- 新包已装：`lastUpdateTime=2026-09-30 17:05:23 UTC`（**只装不跑自动化**）。

## 24.用户四点：seam 单覆盖框 / 字号按框收缩 / 粉字清理图 / ML Kit 几何（含详细计划）（2026-09-29）

- 背景（用户四点）：
  1. seam 的覆盖框需要只有一个；
  2. 字号要仿照原文，但文字太多时可以适量缩小以适应 OCR 框；
  3. 粉字后面还是没有清理图（怀疑「各模式缓存互通」）；
  4. 倾斜文本仍未完全覆盖，角度和宽度都不对，字号和位置也不对 —— **要求给详细计划**。
- 本轮已做：
  1. **#4 详细计划**：写入 `docs/MLKIT_OVERLAY_PLAN.md`（现状实测数据表 + 六条问题根因
     A~F + 四步执行计划 + 验收方式 + 风险取舍）。核心结论：PaddleOCR 走「检测→四边形」正路，
     ML Kit 只有轴对齐块框，现在等于「拿块框当四边形用」再打补丁；应先按几何来源分两条分支。
  2. **#4 的核心修复（角度/宽度）**：`TextTiltEstimator` 增加 `estimateRectFromLuma/FromBitmap`，
     返回**最小面积外接矩形的 4 个顶点**。之前只给角度、让调用方旋转 ML Kit 的**轴对齐框** ——
     斜排文字的 AABB 宽 = 字宽·cosθ + 字高·sinθ，转出来必然又宽又偏，这正是「宽度不对」。
     实测：斜排实底填充块 `315.69×314.23` → **`105.24×72.45`**；`regionClip` 变成真正贴合的外接矩形。
     新增 3 条单测（顶点比 AABB 紧、顶点不越界、顶点长边角与 angleDeg 一致）。
  3. **#3 缓存假设已排除**：`CleanedImageProcessor.cacheKey` 本来就区分模式
     （`if (includeSolid) "$pageHash|include_solid" else pageHash`），开/关不会互相复用。
     实测粉字遮罩**确实挂上了清理图 URL 且映射公式正确**
     （`bgImg: url("https://mtlocal.overlay/c1581…jpg")`、`cleanedX=266.3%`、`cleanedPosX=15.1%`）——
     所以要查的是「这张图有没有真的加载/那块有没有真被擦掉/遮罩带是不是太窄」，
     计划里列了 3 条待查项与诊断页方案（`.verify/web/cleanedcheck.html`）。
  4. **#1 / #2 未做**（计划里排在第 3、4 步）：seam 覆盖框改用共享文字框、贴片退化为框内背景；
     字号拟合放宽上限到框高、去掉跨图气泡的 `fixed_font_percent` 短路。
- 验证：Node 验收 123/123；`:app :core-translate :core-overlay` 测试全绿；
  设备实测（ML Kit）斜排 `regionClip` 为外接矩形、填充块缩小到 1/3、`fillW ≤ 120.7%`。

## 23.用户六点反馈：覆盖层过大 / 点击切不回译文 / seam 两个译文 / 覆盖不全 / 粉字无遮罩 / 单页测试站（2026-09-29）

- 背景（用户六点，其中「覆盖层太大」用户补充：**只在 ML Kit 做 OCR 时出现**）。
- 逐条定位与修复：
  1. **覆盖层太大（ML Kit 专属）** —— 两个原因叠加：
     - ML Kit 返回的是**块级、偏松**的文字框（含行距/内边距），PaddleOCR 返回紧贴字形的四边形；
     - 实底填充用的是气泡**外框**，再叠上偏松的文字框 → `--mt-fill-width` 实测到 **351% / 505%**，
       填充宽是文字框的 1.85~5 倍、占整幅图 80% 宽，糊掉大片画面。
     修法两处：
     - 新增 `TextInkBounds`（复用 OTSU + 「少数派=墨迹」逻辑）：**没有四边形时**（= ML Kit）
       把内容框收紧到墨迹实际范围（面积需 < 原框 92% 才替换，避免为几像素抖动改布局）。
       实测粉色旁白框 `39.97×4.13` → `37.55×3.21`；
     - 新增 `BrowserSceneBuilder.clampFillBox`：填充相对文字框**每边最多外扩 20%**。
       实测同一页 `fillW` 从 `185% / 351% / 505%` 全部收敛到 **≤140%**。
  2. **点两次切不回译文** —— **是我上一轮改坏的**：为了让「显示原文」彻底生效，我把
     `.mt-cover-clip/.mt-fill-flat/.mt-text-layer` 设成 `display:none`；而点击监听器就挂在
     **文字节点自身**上，`display:none` 让节点再也收不到第二次点击 → 单向卡死。
     我上一轮的验收用 `dispatchEvent` 直接派发，绕过了命中测试，所以没发现。
     改成 `opacity:0`（看不见但保留命中区域），并在真实 WebView 里用
     `document.elementFromPoint` 断言文字节点仍可被点中。
  3. **seam 页有两个译文** —— 覆盖层根 `.mt-overlay-root` 是 `overflow:visible`，
     跨页气泡那一半的文字框会伸到图片外**仍然可见**：A 图显示整句、B 图也显示整句 → 看起来两份。
     改为 `overflow:hidden`，把覆盖层夹在图片元素范围内（每一半只显示自己那部分，合起来恰好一句）。
  4. **倾斜文本没完全覆盖 / 5. 粉字后面没有覆盖层** —— 遮罩按检测框 1:1 裁，字形边缘
     （抗锯齿、描边、框偏紧）会露出一点。给 `resolveCoverQuad` 传 `padding: 0.6`（外扩 3%），
     再叠加第 1 条的墨迹收紧，遮罩既不会过大也能盖住笔画边缘。
  6. **单页测试站** —— `.verify/web/index.html` 改成**单页**：所有测试图（syn1 / seamBubble A+B /
     seam A+B+Full / hard1+2 / align / page1-3 / big1 / 清理图对照）按用例顺序排在一页，
     每张上面一行「该看什么」，不再有跳转卡片。
- 验证：
  - Node 验收 → **ALL_OK 123/123**（新增「show-source 不得用 display:none，否则点击回不去」断言）；
  - `BrowserSceneBuilderTest` 新增 4 例填充限幅用例；`:app :core-translate :core-overlay` 测试
    与 `:app:assembleDebug` → BUILD SUCCESSFUL；
  - **真实 WebView 自检** `rendercheck.html` → **pass=34 fail=0**，含
    `text layer is still hit-testable while showing source :: mt-bubble-content`（真命中测试）
    与 `click again restores the translation`；
  - 设备实测（ML Kit）：`fillW` 收敛到 ≤140%；粉色旁白框收紧为 `37.55×3.21`。
- 待确认：第 3/4/5 条的**观感**需要你在测试站上再看一眼（几何与规则已按上面对齐）。

## 22.「怎么翻译不了了」：系统代理把 loopback 图片下载打挂（2026-09-29）

- 背景（用户反馈）：「怎么翻译不了了」。
- 定位（logcat）：
  ```
  EmbeddedBrowser: download_http code=502 url=localhost:8901/big1.png
  EmbeddedBrowser: image_fail url=localhost:8901/big1.png   ← 反复重试，永远翻不出来
  ```
  设备系统代理是 `10.0.2.2:7890`（宿主机代理）。
  **WebView 对 loopback 自带 bypass，所以页面能正常打开；但 OkHttp 默认走系统代理，
  代理连不到设备上的 localhost → 502 → image_fail**。表现就是「页面好好的，点翻译什么都不发生」。
  上一轮验收结束时我把代理还原成 `10.0.2.2:7890`，于是复现了这个问题。
- 修复（不是靠改设备设置，而是修 App 本身）：`EmbeddedBrowserScreen.BrowserImageDownloader`
  给 OkHttp 装上 `LoopbackBypassProxySelector` —— **本机地址（localhost / 127.0.0.0/8 / ::1 /
  10.0.2.2 / 10.0.2.15）直接 `Proxy.NO_PROXY`，其余主机照常交给系统默认代理**。
  这与 Chrome 的行为一致（Chromium 隐式 bypass localhost），也是 WebView 能开、
  OkHttp 开不了的根本差异。
- 实测：**代理保持 `10.0.2.2:7890` 不动**，重装后翻译 `big.html` →
  `erase_mode inpaint_clean=false policy=FAST_ONLY` → `scene_ok bubbles=7 rebuilt=true host=document done=1`，
  日志里不再有 `download_http code=502` / `image_fail`。
- 影响面：真机上的漫画站点不是 loopback，照常走系统代理，无行为变化。
- 副作用（正面）：以后本地测试页不再需要 `adb shell settings put global http_proxy :0`，
  也不用担心验证完忘了还原。

## 21.ML Kit 倾角兜底 + 「显示原文」没恢复 + 接缝两半倾角/字号一致（2026-09-29）

- 背景（用户三点）：
  1. 「ML Kit 下也尽量倾斜的 PCA 兜底」——做；
  2. 接缝还是有重影，「点击之后还有字体，没有恢复原文」；
  3. `dump.html` 里倾斜和竖排的覆盖背景图还在，点击不恢复。
- 修复：
  1. **ML Kit 倾角兜底**（新增 `core-translate/TextTiltEstimator.kt`）：取内容框内墨迹点云，
     求**凸包的最小面积外接矩形**（旋转卡壳），长边方向即文字方向。
     - 一开始按用户建议写的 **PCA（协方差主轴）**，但多行文字会被「行间距的竖直堆叠」拽偏：
       实测 3 行 20° 的文字块 PCA 给 25.7°（差 5.7°）。改成 minAreaRect 后同样数据给 20°。
     - 三道闸：墨迹像素数 ≥ 60、外接矩形长短边比 ≥ 2.0、倾角落在 `[3°, 45°]`；
       另外竖排直接跳过（主轴≈90°，不是横排倾角）。
     - 接线：`buildScene` 里 `OCR 四边形 ?: 墨迹估计四边形`，角度在**像素空间**算、
       再换成百分比顶点（渲染层的百分比空间是各向异性的，角度不能直接搬）。
     - 实测（模拟器，ML Kit）：`倾斜的文本` 气泡 → `rotate(13.04deg)` + `tilted:true`
       （同一气泡 PaddleOCR 的真实四边形给 11.30°，两者差 1.7°）。
  2. **点击「显示原文」没恢复**（`mt-overlay-renderer.js` CSS）：`mt-show-source` 只藏了 `.mt-bubble`
     和它的 `::before`，而**斜排实底的填充块 `.mt-fill-flat` 是裁剪层里的独立 div**，不继承 `.mt-bubble`
     的规则 → 点一下译文没了、底色还在，原文被糊住。
     改成按层类型整块隐藏：`.mt-cover-clip` / `.mt-fill-flat` / `.mt-fill-tilted` / `.mt-text-layer`
     统一 `display:none!important`。
  3. **接缝两半不一致**：
     - 框：上一轮已修（携带百分比框）；
     - 字号：上一轮已修（`fixed_font_percent` 共用）；
     - **倾角**：本轮补上 —— `CrossBubblePart` 增加 `sourcePolygon`，下一张图直接复用上一张图那一半
       的四边形（只把 y 减 100，两图紧邻），否则两半各自估计会得到不同角度（实测 -4.07° vs 0.00°）。
- 验证：
  - Node 验收 `mt-overlay-acceptance.test.js` → **ALL_OK 122/122**（新增「show-source 必须藏掉
    cover-clip / fill-flat / text-layer」等断言）；
  - 新增 `core-translate/TextTiltEstimatorTest`（13 例：正/负/小角度、多行、深底白字、横平/竖直/各向同性/墨点过少
    一律拒绝、旋转顶点几何），`core-overlay` 新增桶值域回归锁；全部 BUILD SUCCESSFUL；
  - **WebView 内自检**（`rendercheck.html`）→ **RC SUMMARY pass=33 fail=0**，其中
    `show-source hides the flat fill :: none`、`hides the cover clip :: none`、`hides the text layer :: none`、
    再点一下能恢复译文，全部通过；
  - 模拟器实测 ML Kit 斜排生效（见上）；接缝两半 `rotate` 与 `font` 一致（`0.00deg` / `47.6px`）。

## 20.彩色字被压成黑色：墨色量化桶数组开小了（2026-09-29）

- 背景（用户反馈）：「粉红色的字没有变红，我想要尽可能和原文相似」。
- 根因（`core-overlay/BubbleColorExtractor.sampleInkColor`）：
  ```kotlin
  val bucketCount = 64 * 64 * 64                       // 262144
  val buckets = IntArray(bucketCount.coerceAtMost(MAX_INK_SAMPLES * 2))  // ← 只有 16384
  ...
  if (bucket < buckets.size) { buckets[bucket]++; total++ }              // ← 静默丢弃
  ```
  桶下标 `(qr shl 12) or (qg shl 6) or qb` 值域是 `0..262143`，但数组只开了 16384 格，
  配合越界守卫等于**把所有红通道 ≥ 16 的像素全部丢掉** —— 只有接近纯黑（r<16）的墨色能被统计。
  于是彩色文字永远凑不够 `MIN_INK_SAMPLES`，一律退化到 `DARK_TEXT = #181818`，
  用户看到的就是「粉字/蓝字全变成黑的」。
- 修复：
  1. 桶数组改开满 `INK_BUCKET_COUNT = 64³`（提到文件级 `internal const`，便于单测引用）；
  2. 量化下标抽成 `inkBucketIndex(r,g,b)`（内部 `coerceIn(0,63)` 保证值域），去掉静默越界丢弃；
  3. 新增 `sampled_colors` 日志：`b0=#文字色/#底色 …`，以后配色问题直接看这一行。
- 回归锁（`BubbleColorRulesTest` 新增 2 例）：
  - `everyRgbMapsInsideTheInkBucketArray`：全色域采样点都必须落在桶数组内（钉死这类静默截断）；
  - `saturatedColorsGetTheirOwnBuckets`：粉字 `#e0447c`、深蓝字 `#143c6e` 的桶下标必须不同于近黑桶，
    且粉字桶下标必须 > 16384（正是旧数组放不下的区间）。
- 实测验证（模拟器 `dump.html` = 真实 20° 斜排 + 粉色旁白 + 竖排 + 实底的合成页）：
  | 气泡 | 原图颜色 | 修复前 | 修复后 |
  |---|---|---|---|
  | b4 粉红旁白 | `#e0447c` | `#181818` | **`#E2467E`** |
  | b2 斜排深蓝 | `#143c6e` | `#181818` | **`#163E6E`** |
  | b3 竖排 | `#1a1a2e` | `#181818` | **`#1A1A2E`** |
  | b0/b1 黑字 | 黑 | `#020202` | `#020202`（未回归） |
  DOM 侧确认（MTDUMP 回读）：`b4:text color=#e2467e`、`b2:text color=#163e6e`。
- 顺带说明「更像原文」的其余维度现状：底色取文字框边框区域中值、描边按文字亮度取反的黑/白
  （彩色字可读性由描边保证）、字号按原文字高拟合 —— 这几项此前已对齐参考项目，本轮只修了墨色这一处。

## 19.用户四点反馈修复：斜排随清理图开关 / 按钮点不动 / 接缝重影 / 测试站（2026-09-29）

- 背景（用户四点）：
  1. 关闭清理图模式时，倾斜文本的处理也要生效（怀疑没给它生效）；
  2. 设置图标点不动，软件内各种按钮都点不动；
  3. seam 图没处理好，有重影，「分开的和一起的会叠在一起」；
  4. 把测试图连成一个网站，方便自己看。
- 结论与修复：
  1. **斜排（#1）—— 根因不是清理图开关，是 OCR 引擎**。实测（`dump.html` 的 MTDUMP 真 DOM 回读）：
     - **PaddleOCR**：斜排气泡 `clip: polygon(4.86% 0%, 100% 66.63%, 95.14% 100%, 0% 33.27%)`、
       `rotate(11.30deg)`、`tilted:"true"`、`hasFlat:true`；
     - **ML Kit**：所有气泡 `rotate(0.00deg)` + 满矩形 clip（ML Kit Text Recognition 只给轴对齐矩形，
       **没有四边形**，所以 `polygon`/`rotation_deg` 全为 null）；
     - 把「精细擦字」开/关各跑一遍，MTDUMP **完全一致** → 倾斜处理与清理图开关无关。
     - 遗留：ML Kit 下**无法**拿到倾角。计划用「内容框内墨迹做 PCA 主轴估计」兜底（纯 Kotlin、
       可单测），本轮未做。
  2. **按钮点不动（#2）—— 环境态，不是 App bug**。复现到 `com.android.systemui` 的
     `MediaProjectionPermissionActivity` ANR，WindowManager 报
     `onPointerDownOutsideFocusLocked, drop event because ...MainActivity is occluded` —— 输入事件被整帧丢弃，
     表现就是「所有按钮都点不动」。冷启动 / 重启模拟器后实测：首页「交换语言」、底栏四个 tab、
     浏览器顶栏齿轮菜单、以及**翻译进行中**点齿轮，全部正常响应。恢复办法：重启设备 / 强制停掉
     卡住的系统对话框。
  3. **接缝重影（#3）—— 真 bug，已修**。根因两条：
     - **框不一致**：`CrossBubblePart.contentRect` 是**上一张图的像素坐标**，下一张图却拿它按
       **自己的宽高**折百分比；两图尺寸不同时 x/宽被缩放错，同一气泡在接缝两侧画成两个错位的框。
     - **字号不一致**：本图那一半走 DOM 自适应拟合，下一张图那一半走 Kotlin 算出的固定字号，
       两半字号不同 → 断行/位置都不齐。
     修法：`CrossBubblePart` 增加 `sourceBoxPercent`（上一张图**已经算好**的百分比框）+ `sourceHeightPx`，
     下一张图直接原样换算（y 下移一整张、高度不同按显示比例折）；`buildScene` 增加 `crossFlags`，
     跨图气泡本图那一半也写入 `fixed_font_percent`（与下一张图同一个 Kotlin 拟合值）。
     实测（`seamdump.html`）：修前两半 `x 33.49/39.67`、`w 18.77/22.23`；修后两半
     `x 47.16/47.16`、`w 69.86/69.86`、`h 18.59/18.59`、`font 55.4px/55.4px` —— 完全一致。
  4. **测试站（#4）—— 已做**。`.verify/web/index.html` 改成验证站首页（每个用例写了「该看什么」+
     图例 + 进入按钮），并新增：`scripts/serve-verify.mjs`（Node 静态服务，显式 `Connection: close`，
     替代会挂死的 `python -m http.server`）、`seamdump.html`（接缝专用几何回读）、`pages.html`
     （原三页漫画）。用法：`node scripts/serve-verify.mjs .verify/web 8899` + `adb reverse tcp:8899 tcp:8899`。
- 验证：
  - Node 验收 `mt-overlay-acceptance.test.js` → ALL_OK 116/116；
  - JVM `:app:testDebugUnitTest :core-translate:testDebugUnitTest :core-overlay:testDebugUnitTest :core-store:testDebugUnitTest` → BUILD SUCCESSFUL；
  - 模拟器：构建+安装成功；`scene_ok bubbles=1` ×2（跨页两图）；MTDUMP 两半几何/字号一致（见上）。
  - 注意：模拟器系统代理会让 App 下图 502（页面能开、点翻译没反应），测前
    `adb shell settings put global http_proxy :0`，测完还原。

## 18.WebView 译文 DOM 覆盖层对齐参考项目：数据契约 / 倾斜遮罩 / 晚绑定清理图 / 验收脚本（2026-09-29）

- 背景：参考项目 `C:\homework\translator` 扩展已经实现了一套高度成熟的「擦原文 → 画译文」渲染层（cover/text 双 layer、倾斜遮罩、清理图贴图、字号拟合、DP 断行、样式隔离）。内嵌浏览器链路虽然已有覆盖层，但数据契约不齐、斜排/晚绑定/验收脚本缺失。
- 目标：只改渲染相关代码，不重写 OCR / 翻译 / 阅读器适配层。
- 已做：
  1. **RenderScene 数据契约补全**：`BrowserSceneBuilder.SceneBubble` 增加 `polygon`、`rotation_deg`、`fill_box`、`region_polygon`、`cleaned_source_box`、`font_weight`，JSON 序列化与缺省/退化规则落地。
  2. **四边形透传**：`TextBlock` / `Bubble` 新增 `polygon`（TL/TR/BR/BL）和 `rotationDeg`；`PaddleOcrLocalTextDetector` 把检测四边形随块带出；`PaddleOcrBlockMerger` 单块保留、多合并丢弃；`BubbleFirstRegionPlanner` 与本地兜底分组转成百分比；`WebtoonImageTranslator.buildScene` 映射到原图坐标。
  3. **实底填充范围**：`fill_box` 生产者 = 气泡外框（与内容框取并集），`region_polygon` 裁剪，`mt-fill-flat` 在裁剪层（世界坐标）里画斜排实底，避免节点旋转把填充带歪。
  4. **复杂背景遮罩**：`cleaned_source_box` 支持；无清理图时保持透明、绝不画纯色补丁；`mt-bg-none` 只设 `--mt-adaptive-bg` 当 `solid` 时才写入。
  5. **清理图晚绑定**：`MtOverlay.applyCleanedImage(id, url)` / `applyCleanedImages(json)` / `pendingCleanedImageIds()`，幂等地把异步落地清理图直接绑到已存在的 DOM 上，不等场景重发。
  6. **Chromium 行内样式 2MB 上限**：`maskCssValue` 统一出口，dataURL ≤1.5MB 行内，>1.5MB 转 Blob URL 并 LRU 缓存，保证同一 dataURL 返回同一 URL。
  7. **字号拟合与遮罩同步**：提取纯函数 `resolveFontSizeRange`（原文字高可信时不设 52px 硬顶）；`expandForTextOverflow` 把 `min-width/min-height` 同步到 solid 遮罩链，实底填充同步撑满。
  8. **宿主样式隔离**：`.mt-overlay-layer` 显式声明 `font-size:16px`；`.mt-overlay-root/.mt-bubble/.mt-bubble-content` 显式声明 `font-size:inherit`，防止宿主元素选择器覆盖 inline 字号与探针测量。
  9. **稳定性**：场景签名 idempotence（相同内容不重建节点）、单气泡 try/catch 不坏整页、点击切换原文、幂等遮罩写入、`removeScenes` 正确清理。
  10. **凸多边形裁剪**：移植 `clipConvexPolygonToRect`（Sutherland–Hodgman），用于跨页/分片倾斜四边形按页矩形取交。
  11. **可执行验收**：新增 `app/src/test/js/dom-stub.js` + `mt-overlay-acceptance.test.js`，共 116 条断言覆盖 §0/§4/§5/§6/§7/§8/§9/§10/§11/§13 关键行为；Node 跑全过。
  12. **Kotlin 单测**：`BrowserSceneBuilderTest` 覆盖新契约字段；新增 `TextQuadGeometryTest`（像素→百分比、拼接图映射、退化处理）；`PaddleOcrBlockMergerTest` 覆盖单块/合并四边形透传；`gradlew :app:testDebugUnitTest :core-overlay:testDebugUnitTest :core-translate:testDebugUnitTest` 全过。
- §11 跨页分片（本轮第二轮，2026-09-29）：
  1. **分片遮罩契约**：`SceneBubble.coverSegments: List<CoverSegment>`（`page_id` / 子框 / 可选四边形 / `cleaned_source_box` / `in_band`），JSON 输出 `cover_segments`，缺省 null。
  2. **union 遮罩**：渲染层 `resolveCoverSegments` 按 `page_id` 把多段合并成**每页一块**遮罩（逐段各建节点会被后一段的背景映射顶掉，实测过遮罩残缺）；多段并集丢掉四边形退回外接框（宁可少斜，不可画歪）。
  3. **三级背景降级**：新增场景级 `page_cleaned_url`；分段跨出捕获带（`in_band=false`）且整页图就位时置 `data-cleaned-layers="2"`，CSS 多背景让带内复合清理图压住整页图；整页图缺位则退回单层复合图（绝不出现「有遮罩但背景是空的」）。`page_cleaned_url` 参与场景签名，落地后会触发重建。
  4. **视觉去重**：新增 `MtOverlay.suppressBubble(imageId, bubbleId)`，按气泡把遮罩+译文整组摘掉，不重发场景（重发就是肉眼可见的闪烁）；层 id 落到 `data-mt-layer`，分片遮罩 id 带 `:N` 后缀。
  5. **跨图 union 增量入口**：新增 `MtOverlay.updateBubbleCover(imageId, bubbleId, geometry)`，相邻分片翻译完回来时，把上一张图里某个**已经渲染过**的气泡遮罩换成跨图完整形状（完整四边形 / 跨出边界的框）。只重建这一个气泡的遮罩节点，**译文与字号完全不动**（重排文字会让读了一半的台词跳一下，比遮罩略斜更糟）；实底气泡的撑开链同步改指向新遮罩。这样不需要把场景下发延迟一格，也能修正「斜排跨图气泡在下半张丢掉倾角」。
- §11 结论（重要，避免误判成「没做」）：
  - **union 遮罩在本链路里本来就已达成**，只是走另一条路：识别时把上一张的底部条 / 下一张的顶部条**拼接**进当前图，跨边界的气泡在拼接图里是完整的，四边形随块带出；`TextQuadGeometry.stitchedPolygonToSource` 换算时允许 y 溢出（`coerceIn(-100,200)`），`resolveBubblePolygon(..., allowOverflow=true)` 也不收拢 —— 所以上一张图的遮罩**已经是完整形状的倾斜四边形**，只是被 root（图片元素）自然裁掉图外部分。这正是参考项目 union 遮罩想达到的效果。
  - 本轮新增的 `cover_segments`（按 `page_id` union）与 `updateBubbleCover`（相邻分片回来时增量补遮罩）是**同一件事的显式契约**：当拼接条带不可用（下一张图下载失败 / 站点按需加载 / 将来改成分片独立识别）时，靠它们仍能表达跨图完整形状，而不必把整套场景下发延迟一格。
- 剩余（后续继续）：
  1. §11 显式契约的**编排层接线**（可选增强）：`EmbeddedBrowserScreen` 记 `lastImageId` + `CrossBubblePart.sourceBubbleId` + 完整四边形，在下一张图翻译完后调用 `updateBubbleCover` 回补；以及把 `suppressBubble` 接到相邻分片重叠区的去重判定上（当前重复气泡已由 `SeamBubbleMerger` 的 `droppedNeighbor` 在 OCR 阶段拦住）。
  2. §2 浏览器取图 fallback（fetch/Referer/screenshot）和 OCR 输入长边 ≤1536 + JPEG 0.82 限制；当前由 PaddleOCR 内部 960 限制兜底，大图内存仍有优化空间。
  3. §5 清理图异步请求的去重/20s 超时/失败重试（当前本地流程同步生成）。
  4. §6 复杂背景 + 纯黑/纯白按原图重采的兜底逻辑。
  5. §12 canvas 合成路径与 DOM 覆盖层完全对齐。
- 验证（Node + JVM + 模拟器实机均已做；真机观感见 HumanTODO.md）：
  - Node：`node app/src/test/js/mt-overlay-acceptance.test.js` → ALL_OK 116/116。
  - JVM：`:app:testDebugUnitTest :core-overlay:testDebugUnitTest :core-translate:testDebugUnitTest :core-store:testDebugUnitTest` → BUILD SUCCESSFUL。
  - 模拟器（emulator-5554）：`adb install -r` 成功、启动无崩溃；WebView 内渲染自检 `.verify/web/rendercheck.html` → **RC SUMMARY pass=25 fail=0**（真 DOM 回读：斜排裁剪多边形、宿主 13px/#333 隔离、无清理图不画白块、横向裁切 `inset(...)`、幂等不重建、suppressBubble/updateBubbleCover）；端到端 `big.html` → `scene_ok bubbles=3`、`seambubble.html` → 跨页 `dropped_neighbor=1` + `incoming=1` → `scene_ok bubbles=1`；「原文」按钮 `session_stop reason=已恢复原文`。截图 `.verify/11_seam_translated_ver2.png`、`.verify/12_big_translated_ver2.png`。明细见 verification.md。
  - 坑：模拟器系统代理 `10.0.2.2:7890` 会让 OkHttp 下图 502（WebView 有 localhost bypass 所以页面能开），需先 `adb shell settings put global http_proxy :0`，验证后已还原。

## 17.内嵌浏览器四点反馈修复：地址栏原地编辑 / 「返回网页」按钮 / 精细擦字真差异 / 搜索引擎可换（2026-09-29）

- 背景（用户四点）：
  1. 跳到页面后点搜索框会立刻切回浏览器新标签页（底栏 tab 那个），正规浏览器应该是对 URL 原地编辑；
  2. 需要一个「马上回到浏览器界面（网页）」的返回按钮；
  3. 「清理图（精细擦字）」开不开好像一样，差距在哪里；
  4. 搜索引擎写死 Google，国内网络用不了。
- 已做：
  1. **地址栏原地编辑**（`app/EmbeddedBrowserScreen.kt`）：网页态顶栏地址框点按**不再跳新标签页**，改为就地变成输入框（`TextFieldValue` + `TextRange(0, len)` 全文选中，像 Chrome：直接打字即替换），回车 / IME Go 才导航；右侧「✕」或系统返回键取消编辑（`BackHandler` 优先处理编辑态），`onPageStarted` 自动退出编辑态。新标签页（收藏 / 历史）入口改到网页态设置菜单「新标签页（收藏 / 历史）」+ 返回手势。
  2. **「返回网页」按钮**：新标签页搜索行左侧在**有打开网页时**出现 ‹ 箭头（`content-desc=返回网页`），一键回网页态（`showBackToPage` / `onBackToPage`）。
  3. **「精细擦字」开关真的产生差异**（用户问的「差距在哪里」，旧实现确实没有差距）：以前关闭时逐气泡仍走 `OPENCV_INPAINT`，出来的背景和整页清理图几乎一样 —— 既没省时间也看不出区别。现在 `WebtoonImageTranslator.prepareVisuals` 按开关选擦字策略：
     - 开（默认）= `OPENCV_WHEN_AVAILABLE` + 整页 inpaint 清理图（`includeSolid=true`，**所有**气泡从清理图裁背景，纹理连续）；
     - 关 = `FAST_ONLY`（solid 气泡逐块取色贴片，**不跑 inpaint**，快但复杂背景会有色差；旁白仍用清理图贴片）。
     新增日志 `TranslationPerf: erase_mode: inpaint_clean=… policy=…`，`erase_apply` 补 `policy=` 便于核对。
  4. **搜索引擎可换**：新增 `core-store/BrowserSearchEngine`（必应 `cn.bing.com` / 百度 / Google，**默认必应**，国内可直连）+ `SettingsRepository` 独立 pref `browser_search_engine`（不进配置档案）；新标签页搜索框下方「搜索用」芯片行 + 网页态设置菜单里都能切，落盘立即生效。地址栏与搜索框共用 `core-store/BrowserUrlResolver`：完整地址原样开、裸域名 / IP / `localhost:端口` 补 `https://`、其余关键词走当前引擎（`URLEncoder` 编码）。
- 顺带：
  - **debug 构建放行本机明文**（新增 `app/src/debug/`）：内嵌浏览器开本地测试页（`.verify/web` + `python -m http.server` + `adb reverse tcp:8899 tcp:8899`）此前会被 Chromium 拒成 `net::ERR_CLEARTEXT_NOT_PERMITTED`（走 `10.0.2.2` 还会被系统 HTTP 代理转给宿主代理，连不通）；现在 debug 允许 `localhost / 127.0.0.1 / 10.0.2.2` 走 HTTP，release 不受影响。
  - **修掉历史遗留红测试**：`BubbleFontRulesTest` 仍按参考项目的 0.96 安全系数断言，而实现早在 2026-09-28「字号感觉小了」后上调到 0.98 → 2 条用例失败；已把用例与 `BubbleImageRenderer` 的 KDoc 对齐到 **0.98**（**未改渲染值**，只改测试与注释）。
- 验证（模拟器 emulator-5554，Pixel 8 AVD，`adb` 驱动；本地测试页 `big.html`，7 气泡）：
  - **地址栏**：网页态点地址框 → a11y 树出现 `EditText text="http://localhost:8899/big.html"`，网页与底部「原文 / 翻译」仍在（**没有**跳新标签页）✓；编辑态输入 `ridi` 回车 → `history_record url=baidu.com/s?wd=ridi`（提交走当前引擎）✓；点「✕」→ 回到只读地址显示、仍在网页 ✓。
  - **返回网页**：新标签页有网页时出现 `content-desc=返回网页`（bounds `[82,188][140,246]`），点按回到网页态（顶栏地址 `localhost:8899/big.html` + 网页导航底栏 + 「翻译」）✓。
  - **精细擦字**（同一页同一轮，只切开关）：
    - 开：`cleaned_image built=1600x2400 include_solid=true`(7366ms) → `erase_mode inpaint_clean=true policy=OPENCV_WHEN_AVAILABLE` → `erase_apply policy=OPENCV_WHEN_AVAILABLE fast=0 edge=0 opencv=0 patches=7 cleaned=true cleaned_patched=7 erase_dark_replace_ratio=0.000` → `image_ok bubbles=7 png=511585B`。
    - 关：`cleaned_image built=1600x2400 include_solid=false`(5224ms) → `erase_mode inpaint_clean=false policy=FAST_ONLY` → `erase_apply policy=FAST_ONLY fast=3 edge=0 opencv=0 patches=7 cleaned=true cleaned_patched=4 erase_dark_replace_ratio=0.174` → `image_ok bubbles=7 png=461895B`（**策略 / 成图字节数 / 耗时都不同**）。
  - **搜索引擎**：点「百度」芯片 → `search_engine_set id=baidu`；关键词 `ridi` → `baidu.com/s?wd=ridi`，历史里显示 `ridi - 百度` ✓；菜单里切回必应 → `search_engine_set id=bing` ✓；**重启 App 后仍保持百度**（芯片选中态布局与重启前一致）✓。
  - **单测**：`gradlew :app:testDebugUnitTest :core-store:testDebugUnitTest :core-overlay:testDebugUnitTest :core-translate:testDebugUnitTest :core-capture:testDebugUnitTest` **全绿**（新增 `BrowserSearchEngineTest` 4 条、`BrowserUrlResolverTest` 5 条；`BubbleFontRulesTest` 12 条由红转绿）。
- 待办（需真机看观感）：见 `HumanTODO.md` 2026-09-29 内嵌浏览器四点反馈确认清单。

## 16.浏览器新标签页收尾：搜索行排版 / 收藏真功能 + favicon / 对齐收尾（2026-09-28）

- 背景：并行会话按设计稿 `730751285322573` 重写了浏览器 UI（新标签页 / 历史页），但中途停止，留下三处占位：搜索行固定 238dp + SpaceBetween 导致右侧空隙过大、收藏是 7 个硬编码站点、「添加 / 编辑」只弹「即将开放」。用户要求：①搜索框与跳转钮排版收紧；②收藏显示站点 favicon、失败才用字母兜底；收藏初始为空、由用户自己添加（「添加」要变真功能）；③把之前没做的补上。
- 已做：
  1. **搜索行排版**：搜索框改 `weight(1f)`、与 52dp 圆形跳转钮之间固定 `spacedBy(10.dp)`（原来固定 238dp + `SpaceBetween`，宽屏上会拉出一大段空白）；框内右侧新增「清空」×（有内容时出现）。
  2. **收藏持久化**：新增 `core-store/BrowserFavoriteRepository`（JSON 落盘 `mt_browser_favorites.json`，同 URL 覆盖名称并保留原添加时间，上限 24 = 4 列 × 6 行；名称留空用域名兜底）。**初始为空**，全部由用户添加。
  3. **favicon**：新增 `app/SiteFaviconLoader`（先 `origin/favicon.ico` 再 `/favicon.png`，限体积 256KB、解码到 ≤96px，内存 LRU，失败也缓存「无图标」避免反复请求）；收藏格优先显示站点图标，失败/没有才用域名首字母兜底。
  4. **「添加 / 编辑」变真功能**：`FavoriteEditDialog`（网址必填、名称可留空，留空自动用域名）；收藏区标题右侧「编辑 / 完成」，编辑态下每个格子右上角出现删除角标、点格子变成改名；空态提示「还没有收藏，点「+」把常看的漫画站加进来」；网页态设置菜单新增「收藏当前页」（名称自动填域名）。
  5. **对齐收尾（之前列过没做的两项）**：
     - 竖排字号：竖排不再用框高估字高，改按**列宽 ÷ 列数**（横排仍为 框高 ÷ 行数）。
     - 悬浮球（截屏）链路接入对齐：`BubbleRenderState.alignment` / `PatchTextSpec.alignment`，擦字贴片文字层与调整态 `StrokeTextView` 的 `gravity` 都按 左/居中/右 渲染（原来一律居中）。
- 验证（模拟器 emulator-5556，隔离目录 + `--no-build-cache` 构建后真跑）：
  - 新标签页空态：显示「还没有收藏，点「+」把常看的漫画站加进来」+「添加」格。
  - 添加 `https://page.kakao.com` → 落盘 `{"entries":[{"label":"page.kakao.com","url":"https://page.kakao.com",...}]}`；格子显示的是**站点 favicon**（a11y 节点是带 `content-desc=page.kakao.com` 的 Image，不是字母兜底）✓。
  - 「编辑」→ 出现「完成」+ 删除角标 → 删除后回到空态且落盘 `{"entries":[]}` ✓。
  - 顶部布局：搜索框占满左侧、跳转钮紧贴其右（截图 `.verify/08_newtab_empty_favorites.png`）。
  - `gradlew test` 全模块通过（新增 `BrowserFavoriteRepositoryTest` 10 项、`BubbleFontRulesTest` 增加竖排用例）。
- 待办（真机）：见 HumanTODO.md 2026-09-28 浏览器新标签页收尾清单。悬浮球链路对齐目前只有编译级验证（跑通需要录屏授权 + 真机漫画界面）。

## 15.清理图模式渲染对齐参考项目：去白框 / 字号同原文 / 配色同原图 / 改名（2026-09-28）

- 背景（用户五点）：
  1. 「清理图上面叠一层白色框框，去掉它」；
  2. 「清理图模式的渲染逻辑要按照 C:\homework\translator」；
  3. 「字体大小要和原文一样」；4.「字体颜色尽量和原来一样」；5.「背景尽量和原来一样」；
  6. 「模式改个名字，普通人恐怕不知道这是什么意思」。
- 参考项目口径（`extension/public/styles.css` + `content/modules/lifecycle-font-fit.js` + `rendering/bubble-colors.js` + `reader-state.js`）：
  - `.mt-bg-none`（无实底/旁白）：**`background: transparent`** + 清理图当背景 + `-webkit-text-stroke` 描边 —— **没有白色衬底**（我们之前画的半透明白圆角框就是用户说的「白色框框」）。
  - `.mt-bg-solid`：透明底 + `::before` 填采样到的气泡底色（本工程等价物 = 擦字补丁/清理图里的原画背景）。
  - 字号：`startSize = 原文字高（originalTextHeight）`，上限 `min(120, 框高×0.88, 原字高×1.08)`，二分找最大可放字号后 ×`BUBBLE_FONT_SAFETY_SCALE(0.96)`；拿不到原字高才退回 `框高×0.78`。原字高 = `框高 ÷ 原文行数`。
  - 配色：对比度 ≥4.5 **或彩色（色度 ≥40）** → 保留原墨色；否则按对比度取黑/白；另有白描边守卫（主导桶无彩近白时让位给彩色笔画桶）。
- 已做：
  1. **去掉白色衬底**：`BubbleImageRenderer.drawText` 删除旁白白色圆角底（连 `narrationBgPaint` 一并删），旁白/透明气泡改为**只加描边**；背景完全由擦字补丁（精细擦字=整页 inpaint 的原画背景）提供。
  2. **字号同原文**：新增 `resolveStartSize / maxFontSizePx / originalLineHeightPx / planFontSize`（纯逻辑、可单测），字号起点 = 原文单行字高 ×1.08、放不下才二分缩小、×0.96；`renderSlice` 用「内容框高 ÷ 原文行数」算原字高并传给渲染器；跨图续排的 `fitFontSize` 同样带上原字高。
  3. **配色同原图**：`BubbleColorExtractor` 补上「彩色（chroma ≥40）也保留原墨色」判据 + 白描边守卫（跳过低对比度桶、主导桶无彩近白时让位给彩色笔画桶），常量与参考项目同名同值。
  4. **模式改名**：浏览器设置里「inpaint 清理图」→ **「精细擦字」**，副标题改成「整页重画背景，更接近原画（较慢）」/「逐块贴背景，速度快（可能有色差）」；状态提示同步改为「精细擦字不可用/未命中/部分生效」。
  5. 新增字号诊断日志 `TranslationPerf: render_font: 字号/原文行高 …`，真机可直接核对「字号是否与原文一致」。
  6. **左/居中/右对齐区分**（用户追加要求）：
     - 新增 `core-translate/.../TextAlignment.kt`：对齐参考项目 `inferTextAlignmentFromBoxes` 的判据（chat/comment/ui 一律左对齐；「短行嵌在长行中间」中心线更稳 → 居中；左/右边缘更稳 → 左/右；兜底取分布最稳者，判不出来才居中）。
     - `Bubble` 增加 `alignment` 字段；`BubbleFirstRegionPlanner.buildBubbleFromBlocks` 与浏览器链路本地兜底分组在构建气泡时用逐行 OCR 框推断；渲染器按对齐设置 `Layout.Alignment`（ALIGN_NORMAL/CENTER/OPPOSITE）**并决定整块的水平位置**（贴左/居中/贴右），跨图续排 `CrossBubblePart.alignment` 一并传递，缝两侧对齐一致。
  7. **原文字高修正（实测发现）**：第一版把「内容框高」当原字高，但规划器把逐行文字用空格连成一句、文本里没有换行 → 3 行气泡的行数被当成 1，原字高估成 213px，译文只剩 80px。新增 `Bubble.sourceLineCount`（= 逐行 OCR 框个数），字高 = 内容框高 ÷ 行数；接缝配对取两半行数的较大者（被切开的是同一行，不能相加）。
- 验证：
  - `:core-overlay:testDebugUnitTest` **通过**（新增 `BubbleColorRulesTest` 5 项：高对比保留、彩色低对比仍保留、灰阶低对比回退黑白、色度计算）。
  - `:core-translate:testDebugUnitTest` **通过**（新增 `TextAlignmentTest` 10 项：左/右/居中三类排版、长行嵌短行判居中、chat 左对齐、退化框忽略、归一化、兜底取最稳边缘）。
  - `gradlew test` 全模块 **通过**；新增 `BubbleFontRulesTest` 11 项（原文字高换算、行数取框数与换行数较大者、起点跟原字高不跟框高、上限收紧、短译文同字号、长译文缩小、不破下限、无原字高回退等）。
  - **设备实测（模拟器 emulator-5556，本地测试页）**：
    - 三气泡（左/右/居中）测试页 → `render_font: 73/71L 71/69C 72/69R`：三种对齐全部正确识别并沿用；**译文 71~73px ≈ 原文字高 69~71px**（1:1 同字号）。
    - 旁白/透明气泡不再画白色衬底（改前是 85% 白圆角框 + 描边；改后只剩描边，背景由清理图提供）。
  - 排障记录：并行会话（浏览器新标签页改版）同时跑 Gradle，**共享 build 目录 / build-cache 竞争**，曾产出缺类的坏 APK（`ClassNotFoundException: com.manga.translator.app.TranslatorApplication`）。本轮验证改用**独立目录副本 + `--no-build-cache`** 隔离构建后才拿到可用包 —— 多会话协作时要注意这一点。

## 14.分片接缝「半截字/缺字/跨页断句」处理（对齐 C:\homework\translator）（2026-09-28）

- 背景（用户）：漫画跨页或页边处的文字被中缝/裁切线截断，出现半截字、缺字或跨页断句，导致 OCR 识别错误；要求仿照 `C:\homework\translator` 处理。
- 参考项目调研（`extension/src/background/modules/seam-handling.js` / `seam-grouping.js` / `canonical-seam-ocr.js` / `stitch-mapping.js` / `content/modules/recognition-seam.js`）：
  - 接缝**复合图**：取上片底部 band + 下片顶部 band 拼成一张图再 OCR（band = `clamp(width*0.15, 64, 96)`）；本工程原本就是「当前图上下各拼一条邻居条带」。
  - 候选过滤：只保留确实跨边界的框；**只贴边**的框作为「半块」配对候选。
  - **半截字配对**（`buildSeamCrossPairCandidate`）：水平重叠 ≥0.25、行高比 ≥0.5、垂直间隙 ≤ 自适应上限、合并高度 ≤ `max(48, min(band)×1.5)`、旋转差 ≤20°。
  - **句子连续性**（`seamPairTextCanContinue`）：上块以句末标点收尾且两块间有明显间隙 → 是两句话，不合并。
  - 渲染：合并结果带 `stitch_overflow` → `allowVerticalOverflow`，即跨边界台词由拥有方渲染本页部分、剩余部分交给邻页继续画。
- 已做：
  1. **新增 `app/.../SeamBubbleMerger.kt`**（纯 float 几何，不依赖 android.graphics，便于 JVM 单测）：整块跨边界归属 + 半截字配对（判据同上，含句子连续性）+ 「与 incomingCross 明显重叠则抑制」（避免同一句被上一张移交的副本和本图自己配出来的副本画两遍）+ 上边界配对的几何裁进本图（保证整句都画得出来）。
  2. 接入 `WebtoonImageTranslator.translateStitched`；`slice_classify` 日志新增 `paired_top / paired_bottom / dropped_neighbor / suppressed_incoming / edge_window / max_cross_h`。
  3. 接缝带宽由固定 300px 改为**按图宽**（`clamp(width*0.20, 144, 384)`），对齐参考项目 `calculateCanonicalSeamHeight` 的 width×ratio+clamp 思路；窄图不再被条带吃掉近半张。
  4. **修复致命顺序 bug（本次最关键）**：擦字贴片在构造 `crossOut` **之前**就被回收 → `cross_out_probe: i0:recycled` → 「跨图气泡剩余部分交给下一张图继续画」这条链路**从来没有真正跑通过**（历史日志里 `cross_out` 恒为 0）。已把回收挪到 crossOut 之后，并修正裁切高度 `coerceAtMost(bitmap.height - offsetY)`（原写法在圆整后可能越界，被 runCatching 吞成 null）。
  5. 修正 `maxCrossHeight` 取 `min(带宽)` 的问题：本工程拼接图只有「另一侧」有带宽（章节首尾甚至没有），min 会退化成 48px → 配对永远失败（实测 `max_cross_h=48`、`paired_*=0`）；改为取**可用带宽**。
- 验证：
  - `SeamBubbleMergerTest` **21 条纯单测**：整块跨下/上边界归属、上下半块配对（含几何并集与裁剪）、水平不重叠/行高比不足/合并过高/文本重复/句末标点+间隙 → 不配对、贴边未配对者的归属、邻居条带丢弃、incomingCross 抑制、edgeWindow/maxPairGap/maxCrossHeight 自适应、句末标点识别。
  - 端到端（模拟器 emulator-5556 + 本地分片测试页，`.verify/make_seam_pages.ps1`、`make_seam_bubble_pages.ps1`）：760×1600 整图按 y=800 切成两张上下紧贴的图（切线正好穿字）——
    - 第一张：`cross=1` → `cross_out_probe: i0:ok` → `crossOut=1`；
    - 第二张：`incoming=1`，且自己重复检测到的同一行被 `dropped_neighbor=1` 丢弃（不重复翻译/不重影）；
    - 切线穿过整个对话气泡的变体同样 `crossOut=1` → `incoming=1`。
  - `gradlew test`：**162 项全通过**（新增 21 项）；`:app:assembleDebug` + `installDebug` 成功。
- 已知边界：
  - 参考项目的 region 级合并（`region_id` 多行聚合、anonymous body groups）未实现 —— 本工程 `Bubble` 模型没有 region id；1:1 半块配对该覆盖的场景已覆盖。
  - 悬浮球（截屏）链路没有邻页上下文，仍是单张截图；**扫描件里被物理裁掉/中缝压没的字**任何方案都无法恢复（只能靠拼接把「还画在纸上的另一半」找回）。
  - 上一张图失败或走缓存命中时链路会断（本图配对可兜底整句翻译，但上一张那半边的原文不会被擦除）。
- 待办（真机）：见 HumanTODO.md 2026-09-28 分片接缝真机验证清单。

## 13.内嵌浏览器实测反馈修复（清理图擦字 / 原文·翻译切换）（2026-09-28）

- 背景（用户实测两条）：
  1. 「那个清理图渲染有时候会不生效」；
  2. 「点击原文后切换不会来译文了」。
- 复现与定位（模拟器真跑，非猜测）：
  - **问题 2 复现**：会话跑完一轮后仍在轮询（按钮是「停止」），点「原文」只摘掉了覆盖层，而会话内 `attempts` 已把每张图标记为「已完成」→ 即使 `renderedTokens` 被清空也永远不会重贴；按钮又还是「停止」，用户再点一次只是把会话停掉，表现就是「译文回不来」。日志佐证：`session_stop` 后 `scan pending=0 reshow=0`。
  - **问题 1 三个成因都成立**：
    a) 切换「清理图」开关时旧成图同样被 `attempts` 挡住，选项看起来没生效（那时提示只说「下次翻译生效」）；
    b) 清理图构建失败（大图内存/异常）时**静默**回退到逐气泡贴片，观感与关闭时几乎一样，用户看到的就是「没生效」；
    c) OCR 内容框紧贴字形，粗体/描边字的抗锯齿边与连笔落在框外 → 留残影；而整块矩形 inpaint 又会把旁白底下的画面抹糊（实测 `residual_max=0.26`）。
- 已做：
  1. **原文/翻译改成视图开关**：`原文` = 只摘掉覆盖层、**保留已渲染成图**（`renderedTokens` + 内存仓），并停掉连续翻译会话；再点`翻译`直接贴回（`scan pending=0 reshow=N`，不重跑 OCR/翻译，实测约 2s 回来）。状态行区分「已翻 N 张 / 已显示 N 张」。
  2. **切换清理图开关**：清空已渲染成图 + 停会话 + 明确提示「点「翻译」重新翻译」，不再静默无效。
  3. **清理图状态可见**：不可用/未命中/部分生效时状态行直接说明（`清理图不可用，已回退贴片` / `清理图未命中，已回退贴片` / `清理图部分生效 N/M`）。`ErasePatch` 增加 `fromCleanedImage` 以便精确统计。
  4. **mask 加固**：内容框按字号比例外扩（solid 10%/16%，旁白 5%）+ 膨胀半径随字号缩放（旧实现固定 8px 封顶，粗体大字擦不净）；外扩以上限保护（气泡外框留 4px），避免把气泡描边一起 inpaint 掉。
  5. **mask 改为「墨迹级」**：用内容框亮度直方图众数估计背景，只把偏离背景的笔画像素打进 mask（笔画占比 >55% 才退化成整块擦）。旁白压在画面上的纹理/渐变不再被整块 inpaint 抹糊。
  6. **新增擦字质量诊断**：`erase_quality residual_avg=… residual_max=… worst=bgType:WxH residue=N missing=N`（逐气泡比较原图内容框与贴片同区域的墨迹占比），走 `TranslationPerf` 输出，便于真机取证。
- 验证（模拟器 emulator-5556，端到端）：
  - 新增本地用例：`hard.html`（粗描边大字 + 渐变底 + 无底气旁白，760×1200）、`big.html`（1600×2400、6 气泡 + 旁白，真实韩漫尺寸级别），生成脚本在 `.verify/`。
  - 墨迹级 mask 效果：hard `residual_avg/max = 0.00/0.00`；big `0.03/0.19`（整块矩形版本为 `0.07/0.26`，明显更干净）。
  - **诊断反过来抓到一个自己引入的坑**：`Mat.put(row, col, data)` 会被解释成「从 col 一直铺到行尾」，宽度不等于整图宽时 mask 错位（residual 飙到 `1.37`、整页被擦花）→ 改用 `mask.submat(...).put(...)`。
  - 原文 → 翻译：`session_stop reason=已恢复原文，点「翻译」显示译文` → `session_start` → `scan pending=0 reshow=1` → 覆盖层回来且无 `image_ok`（确认未重跑 OCR）✓
  - 会话运行中切开关：`session_stop reason=已关闭清理图，点「翻译」重新翻译` + 覆盖层清空 + 按钮回「翻译」✓
  - `gradlew test` 141 项全通过；`:app:assembleDebug` + `installDebug` 成功。
- 待办（真机）：见 HumanTODO.md 2026-09-28 内嵌浏览器实测反馈修复清单。

## 12.内嵌浏览器第二轮：浏览历史 / 连续自动翻译 / inpaint 清理图（2026-09-28）

- 背景（用户 4 点反馈）：
  1. 「内嵌浏览器的入口改为底栏」——底栏 tab 上一轮已加，但**没有构建安装**，用户在设备上看不到（原话「是不是没有构建啊。我没看到」）；本轮补齐构建 + 安装 + 模拟器实测。
  2. 「保存浏览历史」。
  3. 「翻译需要自动往下翻译，像 C:\homework\translator 的预先翻译；不过我觉得可以改为已经加载的都可以预先翻译」→ 用户进一步明确：**手动点一次「翻译」后整页自动翻，直到点「停止」**。
  4. 「渲染可以好看一点吗，最好用 inpaint 清理图；如果降低了速度就做成选项」→ **默认开启**，浏览器设置里可关。
- 已做：
  - **浏览历史**：新增 `core-store/.../BrowserHistoryRepository.kt`（JSON 落盘 `mt_browser_history.json`；同 URL 去重置顶 + 访问次数累加，上限 300；合并/裁剪/序列化/标题回退为纯函数便于单测）。
    - 空状态新增「最近访问」（≤6 条）+「全部历史」入口；设置菜单新增「浏览历史（N）」全屏面板（点条目回访 / 右侧 × 删除单条 / 清空带确认弹窗）。
    - 进 tab 时地址栏预填上次网址；地址栏右侧加 × 一键清空（预填长网址否则要逐字删）。
    - `onPageFinished` 记录（同一次加载多次回调只记一次），标题为空时列表退回「域名+路径」。
  - **连续自动翻译**：单次批处理改为常驻会话 `runTranslationSession(...)`——点「翻译」后按页面顺序逐张翻，翻完不退出，每 1.2s 轮询扫描，lazy-load/滚动出来的新图自动补翻；按钮变「停止」（红底），点了即停（token 序号防止旧协程回写状态）；页面跳转自动终止旧会话。
    - 扫描上限 40→80（长章节一页上百张图，尾部以前永远翻不到）；同 src 换尺寸（占位图→真图）重新翻；无文字的图本轮不重试，下载失败最多重试 3 次（网络抖动可恢复）。
    - 截页链路（`pendingBitmap` 预取 / `prevBottomStrip` / `incomingCross`）在整轮会话内保持，跨图气泡衔接到后续批次仍成立。
  - **inpaint 清理图（选项，默认开）**：`CleanedImageProcessor.buildCleanedImage(..., includeSolid)` + `TieredEraseProcessor.erasePage(..., cleanedForAllBubbles)`——开启时 solid 气泡也走整页 inpaint 清理图裁切（日志 `cleaned_patched=N`），背景连续、无逐气泡贴片色差；关闭时回到逐气泡贴片（日志 `opencv=N`）。开关存 `SettingsRepository.isBrowserInpaintCleanEnabled()`（独立 pref，**不进 AppSettings/配置档案**，否则切换配置档案时会被 `saveSettings` 连带重置）；切换后清掉已贴译文，下次翻译按新设置重画。
  - **渲染美化**：`BubbleImageRenderer` 字号改**二分搜索**「放得下的最大字号」（旧实现从 0.72×高 乘 0.9 退，容易退过头显得字小）；去掉 `setEllipsize(END)`（长译文以前会显示成「…」，信息丢失）；旁白衬底留白/圆角/描边随字号缩放；断行改 `BREAK_STRATEGY_SIMPLE`；极端溢出按内容框裁剪（±2px 余量）而不是糊到相邻画格。
  - 顺带修：`WebtoonImageTranslator` 擦字贴片改为按 `ErasePatch.bubbleId` 取（原来按下标 `getOrNull(index)`，空文本/失败缺项时会错位贴错图）。
- 验证（模拟器 emulator-5556，端到端真跑非仅编译）：
  - 底栏 4 tab 实测可见（首页/**浏览器**/术语表/翻译历史）；设置菜单 inpaint 开关默认 `checked=true`，关→开可往返。
  - 本地测试页（3 张 760×1200 韩文对白+旁白图，`python -m http.server`，`adb` 驱动）：点「翻译」→ `scan pending=3 scanned=3` → 3×`image_ok` → 状态「已翻 3 张 · 监听新图中…」，DOM 中出现 3 个 `mtlocal.overlay` 覆盖图；点「停止」→「已停止」按钮复原；跳转页面 → `session_stop reason=页面已跳转`。
  - inpaint 开：`cleaned_image built ... include_solid=true` + `erase_apply cleaned_patched=3`；关：`cleaned_patched=0 / opencv=3`。同批小图耗时同量级（开 0.36~1.00s，关 0.45~0.49s）；大图/多气泡时整页 inpaint 省掉 N 次逐气泡 inpaint，倾向更省。
  - 浏览历史：文件落盘含 `title/visitCount`、空状态「最近访问」、面板列表、× 删除后文件同步、`visitCount` 递增、重装 APK 后历史仍在。
  - `gradlew test`：5 个模块 **141 项全通过**（新增 `BrowserHistoryRepositoryTest` 11 项）；`:app:assembleDebug` + `installDebug` 成功。
  - 排障确认的坑：模拟器/真机若配了系统 HTTP 代理，WebView 对 `localhost` 直连（Chromium 内置 bypass）、OkHttp 走代理 → 本地测试页「页面能开、图下载失败」；已给下载器补 `download_fail` / `download_http` 日志（保留必要诊断）。
- 待办（真机）：见 HumanTODO.md 2026-09-28 第二轮清单（长章节连续翻译稳定性/耗时、真实站点历史与登录态、inpaint 开关在大图上的速度差）。

## 11.内嵌浏览器 + 网页漫画原位翻译（含截页处理）（2026-09-28）

- 背景：用户要求加「内嵌翻译」——App 内置浏览器打开漫画网页，网页里的漫画图逐张走现有翻译管线，画好译文的成图直接贴回网页原位（对齐 C:\homework\translator 扩展效果）；不需要录屏授权、悬浮窗权限、前台服务。并明确两点：①跨页/中缝截断的文字（半截字、缺字、跨页断句导致 OCR 识别错误）要专门处理；②入口放底栏，不放首页卡片。
- 已做：
  - **句子翻译解析器抽出**：`PageTranslationResolver.kt`（新建）从 `TranslateOrchestrator` 抽出句子缓存查找（EXACT/COMPACT/FUZZY）+ 引擎分发（ML Kit 本地/百度/云端）+ 缓存回写 + 历史异步写入，悬浮球与浏览器两条链路共用（`TranslateOrchestrator` 仅搬移不改动，1220→929 行）。
  - **单图翻译管线**：`WebtoonImageTranslator.kt`（新建）——气泡区域优先检测（bubble-first，与悬浮球同策略）→ OCR → 翻译 → 擦字贴片 → `BubbleImageRenderer` 渲染同尺寸译文图；渲染风格对齐扩展 kakaopage 设计（solid 气泡擦字+裸字、旁白半透明衬底+描边字、SERIF 居中自适应字号）。
  - **截页（中缝截字）处理**：`translateImage(source, topStrip, bottomStrip, incomingCross)`——①相邻图条带（各 300px）拼到当前图上下再 OCR，跨图断点气泡在拼接图里完整；②气泡按 `outer.top` 归属分类（邻居条内的跳过、跨下边界归当前图）；③跨图气泡的译文/擦字贴片按真实坐标渲染本图部分，剩余部分打包 `CrossBubblePart`（贴片裁子块、坐标减 `bottomLimit`、`fitFontSize` 在完整内容框定字号）交给下一张图；④下一张图渲染时补画（内容框顶边可为负续排，两侧同字号同 `contentWidth` 保证断行一致）。
  - **内嵌浏览器**：`EmbeddedBrowserScreen.kt`（新建）——WebView + JS 扫描 `<img>`（≤40 张、过滤小图/已贴图）→ OkHttp 下载（带 WebView Cookie/Referer/UA）→ 限长 2400px 解码 → 翻译管线 → 内存仓 `BrowserOverlayImageStore` → `shouldInterceptRequest` 拦截 `mtlocal.overlay` → JS 注入绝对定位覆盖层（pointer-events:none）；串行处理，预取下一张图，传递条带与 `crossOut`；「原文」按钮 REMOVE_JS 恢复。缓存命中（同 src）直接复用 token 不重复翻译。
  - **底栏入口**：`MainActivity.MainTab` 增至 4 个（首页/**浏览器**/术语表/翻译历史），`MtUi.kt` 新增 `MtBrowserIcon`（地球）；`pageIndex` 直接驱动浏览器开关（删除独立 `browserOpen` 状态）；`HomeScreen` 删除临时「内嵌浏览器」卡片与 `onOpenBrowser` 参数。
- 验证（模拟器环境编译级，非真机）：
  - `:app:compileDebugKotlin` BUILD SUCCESSFUL（0 error；已清理全部新增 warning）。
  - `:app:testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL：4 个测试类 12 条用例全过（0 失败 0 错误），结果 XML mtime 19:34 已核对（防 build cache 假绿）。
  - emulator-5554 当时被用户占用（前台是游戏），未做安装/UI 验证。
- 待办（真机）：见 HumanTODO.md 2026-09-28 内嵌浏览器真机验证清单。

## 10.翻译 API 配置按引擎按需显示（2026-09-15）

- 背景：用户要求「翻译 API 配置只在需要的时候显示，也就是选择云端 API 的时候」。此前「翻译 API 配置」整块（配置档案下拉 + 新建/编辑/删除）在翻译设置页**无条件常驻**，选百度或 ML Kit 本地引擎时依旧占着屏幕且与本引擎无关。
- 已做（`app/MainActivity.kt` `SimpleConfigScreen`）：
  - 「翻译 API 配置」整块改用 `if (TranslateEngine.fromId(translateEngine) == TranslateEngine.CLOUD)` 包裹，仅「云端 API」引擎下渲染；百度 / ML Kit 引擎下整块（含标题、配置档案下拉、三个按钮）全部不显示。
  - 该块内新增一行说明「配置保存在本机，切换翻译引擎后不会丢失。」，避免用户误以为切走引擎即丢失配置。
  - 顶部引擎说明文案「云端 API」一句改为「联网调用大模型翻译（质量好，延迟 2~5s）；选此项时下方会出现「翻译 API 配置」。」，与新的按需显示行为一致。
  - 百度引擎下的「百度翻译配置」原有条件显示行为不变。
- 验证（模拟器 emulator-5554，非真机）：
  - `:app:compileDebugKotlin` 与 `:app:assembleDebug` 均构建成功；APK 安装成功。
  - uiautomator 节点树核对三种引擎：**云端** → 有「翻译 API 配置 / 配置档案 / 新建配置·编辑·删除」；**ML Kit 本地** → 该块全部消失，仅剩引擎选择；**百度翻译** → 该块全部消失，仅剩「百度翻译配置」两项输入框。
  - 切回云端后配置档案仍为原选中项（`deepseek-v4-flash-260425`），确认隐藏不影响持久化。
  - 截图：`verify_apiconfig_cloud.png` / `verify_apiconfig_mlkit.png` / `verify_apiconfig_baidu.png` / `verify_apiconfig_back_to_cloud.png`。
- 待办（真机）：见 HumanTODO.md 2026-09-15 翻译 API 配置按需显示真机确认。

## 9.多术语表管理（冲突可见 + 优先级可控 + 表间复制/合并）（2026-09-15）

- 背景：用户提出「多术语表管理」。核对发现多表基础能力（多集合 CRUD、多选生效、合并注入、JSON/CSV 导入导出）在 2026-08-23 已具备，缺的是"管理"层能力。
- 已做：
  - **生效优先级可调**：`GlossaryRepository.moveActiveCollection(name, delta)` + 列表页 ↑/↓ 按钮。此前优先级由存储顺序决定、界面不可控，冲突时"谁赢"对用户不可知。
  - **跨表冲突可见**：`findConflicts()` 检测同一原文在多张生效表中译文不一致的条目；列表页显示「N 处术语冲突」徽标，点击弹出冲突面板，逐条标注哪张表**生效**、哪些被**忽略**，并提示调整生效顺序即可换赢家。
  - **术语表级操作**：`duplicateCollection`（复制整表，副本不自动生效）、`mergeCollections`（A 并入 B，重复原文跳过保留目标表译文，合并后删除 A；**若 A 原本生效，则 B 顶替 A 的生效位次**）。
  - 新增 `listInfos()` / `totalEntryCount()` 概览快照；列表页改为「统计条 + 生效区（带序号）/未生效区」分组。
  - 列表页行内 ⋮ 菜单：重命名 / 复制为新术语表 / 合并到其他术语表… / 删除（左滑删除保留）。
  - 新增 `MAX_COLLECTIONS = 50` 上限（`createCollection` / `duplicateCollection` 均做校验）。
  - 纯逻辑抽出为可单测的伴生函数 `movedOrder` / `planMergedActiveOrder` / `collectConflicts`。
- 验证（模拟器 Pixel_8 API 36.1，非真机）：
  - `:core-store:test` **46 项全通过**（新增 `GlossaryManagementTest` 15 项）；`:app:assembleDebug` 构建成功。
  - 注入 3 张表（2 张生效、7 条术语、1 处冲突）实测：概览统计正确 → 冲突面板标注「生效 通用术语：艾伦 / 忽略 进击的巨人：艾连」→ 点 ↑ 把「进击的巨人」提到第 1 位后，存储 `active` 变为 `["进击的巨人","通用术语"]`、冲突赢家同步变为「艾连」→ ⋮ 菜单四项正常 → 合并「进击的巨人」进「通用术语」后源表删除、`Levi` 并入、`Eren` 去重跳过、生效位次继承、冲突徽标消失。
  - 截图：`verify_glossary_overview.png` / `verify_glossary_conflict.png` / `verify_glossary_menu.png` / `verify_glossary_merge_dialog.png` / `verify_glossary_merged.png`。
- 待办（真机）：见 HumanTODO.md 2026-09-15 多术语表管理真机验证。

## 8.接入百度翻译 API（免费 + 快）（2026-08-27）

- 背景：用户要求"免费又快，或便宜点"的翻译方案，已确认有百度账号。
- 已做：`BaiduTranslateClient`（整页多句一次请求 <1s、MD5 签名、按 2000 字节分批 + QPS=1 限流）；`TranslateEngine.BAIDU`；设置项 `baiduTranslateAppId/SecretKey`（加密存储）；编排分支（未配置/失败提示，不回退云端，成功入句子缓存）；翻译设置页配置输入 UI。模拟器已验证 UI 切换与配置框显示。
- 待办：真实 APP ID/密钥验证翻译链路与质量（见 HumanTODO.md 2026-08-27 百度翻译接入）。

## 6.PaddleOCR 合并规则收紧 + 速度模式 + 倾斜文字日志（2026-08-27）

- 背景：用户反馈「paddle 的合并规则太宽松」「paddle 的速度真的好慢」「倾斜文字的处理不够好」。
- 已做：
  - `PaddleOcrBlockMerger` 再次收紧：行间距 1.0→0.65 倍字高、段落中心距 1.2→0.85 倍字高、同行内联间距 0.9→0.65、倾斜碎片斜率范围收窄、增加全局段落中心距上限与高度一致性检查。
  - 新增 `OcrSpeedMode`（快速/均衡/质量）与首页选择器：`PaddleOcrLocalTextDetector` 按模式动态调整检测分辨率、级联置信度阈值、recovery 变体数量；快速模式降低分辨率并减少变体。
  - 倾斜文字：`PaddleQuadGeometry.checkQuadHealth` 检测退化四边形，`cropQuad` 对高长宽比倾斜框减小 padding 并提前拒绝退化框；`paddle_total_ms` 日志增加 `crop_fail` / `degenerate` / `speed_mode`。
  - ML Kit 翻译：新增「译文质量可疑」启发式检查（目标为中文时 CJK 表意文字占比过低），可疑时抛异常回退云端，避免把未翻译/翻译腔句子写入缓存。
- 待办：
  - 真机验证 PaddleOCR 合并是否不再把不同气泡/旁白误合并为一句。
  - 真机对比三种速度模式：快速模式 `paddle_total_ms` 是否显著下降、识别率是否可接受；质量模式对艺术字/彩色大字是否有改善。
  - 真机验证倾斜/竖排文字：logcat `paddle_quad_degenerate` / `paddle_crop_fail` 数量，确认倾斜文字识别结果是否改善。

## 7.ML Kit 翻译不可用/误判/回退云端 修复（2026-08-27）

- 已做（模拟器已验证 `translate_local ok=mlkit` 全链路成功）：
  - 符号/emoji 文本不再被质量检查误判为"未翻译"（`containsTranslatableScript` 前置过滤 + 逐句容错）。
  - 模型未就绪时翻译不再阻塞 6 分钟（`ensureModelReadyOrThrow` 3s 探测 + 后台下载 + 提示稍后重试）。
  - 选 ML Kit 引擎时本地失败不再回退云端，直接显示原文并 Toast 提示原因。
  - 服务启动强制清除残留译文层；锁定层任何手势（含双指/滑动）都释放锁定清除译文。
  - **译文层显示时点击悬浮球 = 恢复原文**（`hasActiveTranslationLayer` + `floating_tap_restore_original`），不再显示"翻译中"；无译文层时点击悬浮球照常翻译（模拟器已验证两者）。
- 待办（真机）：见 HumanTODO.md 2026-08-27 真机验证清单（下载完成后重试、纯 emoji 文本、滑动解锁、重启服务无残留译文层、译文层时点悬浮球恢复原文）。

## 4.特殊字体识别（PaddleOCR）✅ 已接入可选引擎（2026-08-24）

- 现状：ML Kit 对特殊字体/艺术字识别率不足，参考项目 C:\homework\translator 使用 PaddleOCR。
- 已做（2026-08-24）：
  - 评估 Paddle-Lite（JNI，仅 arm，9.23MB 预编译）vs ONNX Runtime（纯 Java API，含 x86_64 可模拟器验证），选用 ONNX Runtime，详见 docs/PADDLE_OCR_MOBILE_INTEGRATION.md。
  - 新增 `PaddleOcrLocalTextDetector` 接入现有 `LocalTextDetector` 接口：PP-OCRv5 mobile det+rec（zh/ja/ko 三语模型+字典内置 assets），参数与参考项目对齐（det thresh 0.3 / box 0.6 / unclip 1.2、rec 48x320、CTC 解码）。
  - 模型加载支持**打包进 APK**（2026-08-25）：`ensureModelsLoaded` 优先从 assets 加载模型，assets 缺失才回退在线下载（BOS tar 主源 + HF 直连回退，det/rec 各自独立判断可部分打包）；提供 `scripts\download-paddle-models.ps1` 一键把 det + zh/ja/ko rec 四个 ONNX 下到 `core-translate\src\main\assets\paddle_models\`，实现完全离线 OCR。失败自动回退 ML Kit（`EngineSelectingTextDetector`）。见 HumanTODO.md 2026-08-25 PaddleOCR 模型打包。
  - 首页新增「OCR 引擎」选择（ML Kit / PaddleOCR）；AppSettings/SettingsRepository 新增 `ocrEngine` 字段。
  - 模拟器 androidTest 已真识别日语「こんにちは世界」（score 0.999）通过。
- 待办（真机）：
  - 跑 `scripts\download-paddle-models.ps1` 下载模型后 `assembleDebug`，安装到无网设备验证 PaddleOCR 全程离线（logcat `model_loaded_from_asset`，无 `model_download_*`）；确认包体积增量（约 50MB）可接受。
  - 真机确认特殊字体/艺术字识别率对比 ML Kit；若 assets 为部分打包，确认缺失语言仍走下载回退。
  - 若 HuggingFace 不可达，确认 BOS tar 下载路径（已做 BOS 优先）。
  - 确认本次增强（recovery 检测变体、多 crop 识别投票、ratio-based 行/段落合并）对彩色大字和跨行对白的召回/聚合效果（见 HumanTODO.md 2026-08-24 PaddleOCR 彩色大字/聚合改进）。

## 5.翻译速度

- 已做：擦除/颜色/布局计算与翻译请求并行（async + coroutineScope），减少总等待时间；OCR 已本地化（bubble-first + ML Kit / PaddleOCR 可选）；翻译已可选本地引擎（ML Kit 端侧离线，2026-08-25，见 HumanTODO.md）。
- 待办：真机采集 TranslationPerf 日志确认各阶段耗时，若翻译请求本身慢则考虑流式输出/更小 prompt。

## 8.思考功能开关（2026-10-02）

- 背景：用户指着「新建配置 · 填写信息」表单反馈「这里可能需要限制一下思考功能。因为有思考功能会慢很多」→「需要添加一个参数，表示是否开启思考功能」。
- 已做：
  - 表单新增**思考功能**开关（配置档案级，默认关）：`app/.../HomeScreen.kt` 的 `ProfileFormScreen`（新组件 `MtThinkingRow`）。新建一律 false，编辑沿用档案里保存的值；厂商不支持时开关置灰并提示「仅千问 / GLM 支持」，切到不支持的厂商会把开关收回 false，避免存下一个永不生效的 true。
  - 保存写入 `AppSettings.enableThinking`（字段本就有，档案 JSON + 全局 prefs 都已读写，**无需迁移**）；`thinkingBudget` 不暴露，沿用默认 8192。
  - 请求体改为**思考三态**（`core-translate/.../OpenAiVisionClient.kt` 新增 `ThinkingMode` / `ThinkingSupport`）：`ENABLED`（`enable_thinking:true` + `thinking_budget`）/ `DISABLED_EXPLICIT`（**显式 `enable_thinking:false`** + `max_tokens`，专治 Qwen3 / qwen3-vl 这类「默认就开思考」的模型）/ `OMIT`（厂商不支持该参数，保持旧行为完全不发）。
  - 重试阶梯改为**有界阶梯**（最多 4 次 + `attempted` 去重）：`ENABLED → DISABLED_EXPLICIT → OMIT` 逐级降级，`response_format` 降级保留；顺手去掉旧代码「不发 `enable_thinking` 却塞 `thinking_budget`」的怪逻辑。
  - `isThinkingUnsupported` 增加第二判据（错误文本含 `parameter` + unknown/unsupported/invalid），兜住「拒绝参数但不回显字段名」的端点——否则「关」路径新发的字段可能把本来能用的请求打挂。
  - 日志：`VisionClient request_send … thinking=ENABLED|DISABLED_EXPLICIT|OMIT` + 新增 `request_retry`；`TranslationPerf request_send … thinking=${enableThinking} provider=…`，便于按 traceId 对比开/关思考耗时。
- 单测（9 条全绿）：`ThinkingSupportTest`（8 条：厂商门控、三态、降级阶梯、字段落法、budget clamp）、`ThinkingProviderConsistencyTest`（1 条：UI 门控 `AiProvider.supportsThinking` 与客户端门控一致，防两处漂移）。
- 待办（真机/人工）：
  - 用真实千问 / GLM Key 比对「开思考 vs 关思考」的耗时（`TranslationPerf response_ok`、`VisionClient response_ok cost_ms=`）；**必须用没翻译过的新页面**，句子级缓存命中会直接跳过 API。
  - 扩展思考到更多厂商（硅基流动 / 火山方舟等）需要同时改 `AiProvider.supportsThinking` 与 `ThinkingSupport.PROVIDER_IDS`（一致性测试会挡住漏改）。

## 9.「无法编辑配置」修复（2026-10-02 用户反馈）

- 现象：用户「我发现无法编辑配置」——配置档案底部弹层里，**当前使用**的那一条只有勾标、没有「更多」菜单，所以打不开 编辑/重命名/复制/删除。而新建配置后它**立刻变成当前使用**，等于「刚建好的配置反而编辑不了」（模拟器复现一致）。
- 根因：`app/.../HomeScreen.kt` 的 `ProfileRow`：`if (active) 勾标 else 更多菜单` —— 两者被写成了互斥分支。
- 已做：
  - 「当前使用」只用勾标表示，**「更多」菜单对所有行都显示**（含当前使用行）。
  - 顺带补上新出现的状态：现在可以删掉「当前使用」的那条配置，删的若是最后一条，会把翻译 API 配置（provider/baseUrl/model/apiKey + enableThinking）一起清空，避免「首页显示未配置、后台还在用已删档案的 key」；provider 记住的 key 不受影响，重建同厂商档案会自动带回。
- 模拟器已验证：唯一档案（当前使用）→ 点「更多」→「编辑」能打开编辑表单，思考功能开关回读为「开」。

---

## 已完成功能记录

- 2026-10-02 「无法编辑配置」修复：配置档案弹层的「更多」菜单改为对所有行显示（此前与「当前使用」勾标互斥，导致当前使用/刚新建的档案无法编辑）；删掉最后一条配置时同步清空翻译 API 配置。模拟器已验证当前使用行可编辑
- 2026-10-02 思考功能开关：配置表单可开关「思考功能」（默认关、仅千问/GLM 可开），请求体改三态发送（关思考时**显式** `enable_thinking:false`，专治 Qwen3 默认开思考导致的慢）；重试阶梯改为有界阶梯；新增 9 条单测（`ThinkingSupportTest` 8 + `ThinkingProviderConsistencyTest` 1，全绿）；模拟器已验证表单四种状态（OpenAI 置灰 / 千问可开 / 新建默认关 / 编辑回读为开）与「切不支持厂商自动收回」

- 2026-08-25 倾斜/变形文字处理（对齐 C:\homework\translator crop_geometry.py / crop_recognition.py）：PaddleOCR 检测四边形按边长度透视矫正裁剪（修复轴对齐包围盒导致的倾斜字拉伸变形）+ 外扩 padding（min(12, max(2, 边长*0.06))）；竖排 crop（高≥宽×1.4）旋转 ±90° 识别并按「识别质量 + 检测方向」择优（VERTICAL_ORIENTATION_TIE_MARGIN=0.08、弱识别 0.72 阈值）；横排弱结果旋转 180° 重试；`orderedCorners` 改参考项目角度排序法修复薄/倾斜矩形角点重叠导致的退化四边形（15° 倾斜文字模拟器实测 こんにちは@0.996 完全识别，修复前整行漏检）。新增 `PaddleQuadGeometry`（纯 Kotlin 可单测，12 条单测通过；模拟器 androidTest 竖排/倾斜/倒置 3 条通过）。见 HumanTODO.md 2026-08-25 倾斜/变形文字
- 2026-08-25 本地翻译引擎（ML Kit 端侧翻译）接入：新增「翻译引擎」选择（云端 API / ML Kit 本地离线），`TranslateEngine` 枚举 + `AppSettings/SettingsRepository.translateEngine` 字段；新增 `LocalTranslator` 接口与 `MlKitLocalTranslator`（com.google.mlkit:translate:17.0.3，覆盖 英/日/韩 → 简中 及互译，繁体目标不支持自动回退云端）；`TranslateOrchestrator.translateMissing` 本地优先、云端回退；`CaptureForegroundService.prewarmTranslateModel` 服务启动后台预热模型；首页「翻译引擎」下拉 UI。详见 HumanTODO.md 2026-08-25
- 2026-08-25 PaddleOCR 提速：识别级联（原图置信度≥0.93 直接返回，跳过其余 7 个变体，配 rec_cascade_hit/full 计数）；recovery 变体在降采样工作图（长边≤960）上构建再映射回原图坐标；区域检测与整页 OCR 并行；centerDHash 移 IO。模拟器实测 OCR 2846ms→944ms、清理图 566ms→228ms
- 2026-08-25 整页"清理图"（cleaned image）接入：新增 `CleanedImageProcessor`（对齐参考项目 local-ocr-service 的 build_cleaned_image_data_url：非 solid 气泡内容框生成 mask + 椭圆膨胀（半径 clamp(高*0.08,2,8)）+ 整页 TELEA inpaint），`TieredEraseProcessor` 对非 solid（旁白/透明）气泡改为从清理图裁切贴片（外扩 10px 保证无缝），无清理图时回退原"创可贴"墨色替换；按 pageHash 缓存
- 2026-08-24 PaddleOCR 可选 OCR 引擎：PP-OCRv5 mobile（ONNX Runtime）接入 LocalTextDetector，zh/ja/ko 三语模型按需下载，失败回退 ML Kit，首页引擎选择，评估文档 docs/PADDLE_OCR_MOBILE_INTEGRATION.md
- 2026-08-23 底栏 4 tab：首页 / 翻译设置 / 术语表 / 翻译历史
- 2026-08-23 术语表：CRUD + JSON 导入导出（SAF）+ 翻译 prompt 注入 + revision 指纹
- 2026-08-23 OCR / 翻译分离缓存：OCR 层 2h TTL，译文永久保存
- 2026-08-23 翻译历史库：永久保存（JSON 文件，上限 500 条）+ 历史命中复用 + 历史页查看/删除/清空
- 2026-08-23 背景/字体颜色参考原图：气泡区域像素采样，覆写颜色贴近原图（默认直接应用）
- 2026-08-23 术语表重构：集合列表/详情双级页面 + 内联编辑 + 右滑删除 + JSON 导入新建集合 + 新建集合自动跳转详情 + 空 active 集合支持
- 2026-08-23 术语表多集合：多选生效集合、冲突按 active 顺序优先、翻译时合并注入
- 2026-08-23 术语表 UI 修复：左滑删除按钮不可见（offset/background 顺序）、添加术语按钮被压缩为 0 高度（fillMaxSize→weight(1f)）、失焦自动保存、原文/译文编辑切换、长列表添加行固定显示 + imePadding
- 2026-08-23 术语表批量编辑：清空按钮改为编辑模式（复选框多选 + 全选 + 删除选中）、标题直接显示术语表名、JSON/CSV 合并为导出下拉
- 2026-08-23 全局命名：「集合」→「术语表」
- 2026-08-23 翻译配置档案：多配置下拉管理（新建/编辑/删除），编辑表单与新建一致
- 2026-08-23 配置向导：新建配置先选厂商（OpenAI/千问/GLM/DeepSeek/Kimi/硅基流动/火山方舟/自定义），Base URL 预填，获取模型列表（/models）+ Model 原位下拉选择，自定义无预填 URL
- 2026-08-23 渲染配色参考 kakao 设计：背景=边框区域中值采样，墨色=RGB 距离量化主导桶，文字对比度≥4.5 保留原色否则黑白，描边=深字白边/浅字黑边；去掉文字区不透明底色矩形
- 2026-08-23 翻译速度：擦除/颜色/布局计算与翻译请求并行
- 2026-08-24 旁白/透明文字块渲染背景：所有文字块先画背景再画译文（旁白/transparent 画半透明白色圆角底、solid 无擦除贴片时回退为采样边框底色），解决文字叠加看不清
- 2026-08-24 旁白"创可贴"清理贴片：擦除处理器不再只处理 solid，旁白/透明文字块同样按内容框生成清理贴片（含白色文字墨色处理），精准盖住原字
- 2026-08-24 翻译缓存命中增强：translate 指纹改为空白归一化+文本排序；新增 pageHash 兜底命中（气泡数一致才复用）；翻译 readTimeout 60s→120s 防慢接口误报超时
- 2026-08-24 翻译缓存改为句子级"原文→译文"一一对应：按（源语言,目标语言）隔离（mt_sentence_translation.json，上限 5000 条），缺失句子才调 API，不再依赖页码/指纹；移除 OCR 持久缓存（截图随时间变化命中率低），每次重新 OCR

### 第三轮：夹具换成用户给的真面板 + 「切开余量」口径修正（2026-10-03 深夜）

- 用户要求：**「你可以不用夹具图，而是直接用这个图然后截开吗」**（给了一张 622×829 的真面板：
  上气泡 4 行韩文 + 下气泡 3 行 + 人物，比例接近真页面）。
- 做法：`make_seam_after_line2.py` 改成**自动找切点** —— 在气泡文字区按行统计暗像素聚成
  「文字行带」（顺手滤掉气泡描边：描边带 18px、正文行 29-37px，按中位数 0.75 倍过滤），
  取第 N 条行带下沿 + pad 作为切点。真面板实测行带
  `[(282,310),(319,348),(357,385),(394,423),(467,503),…]` → 取第 2 条 + 12px = **y=360**，
  切成 `seamLine2A.png 622x360` / `seamLine2B.png 622x469`（咬进第 3 行一点，复现你的「半截字」）。
- **夹具立刻证伪了我第一轮的「切开余量」**（这正是换真面板的价值）：
  - 现象（`.verify/24_seam_realpanel.logcat` + 截图 `24_seam_realpanel.png`）：
    A `cut_allow=51`（行高×1.2）→ 只交出去 51px，而 B 的可见剩余是 80px → 覆盖率 57% < 60%
    → B 把自己那块**又认领又翻了一遍** → 缝上出现两层译文（截图里那行被压住的糊字）。
  - 结论：**「被切开的半行不会超过一行」这个前提是错的** —— 缝落在第 2 行之后时，
    第 3/4 行本来就在下一张图上（也在条带里），气泡是**跨缝延续**的，不是只被切掉半行。
  - 改法：`resolveCutAllowance(行高, 条带)` = **条带本身**（A 只能看到条带里的内容），
    条带极短时保底两行；邻居正文的保护交给「不合并两个独立气泡」（第二轮）+
    「接收侧擦字夹到移交过来的字形轮廓」。
- 修后真机（`.verify/25_seam_realpanel.logcat`）：
  A `cut_allow=117 capped_cross=0`、`seam_scene mode=band band=347x168@185,269`、`seam_dispatch ok=true`；
  B `dropped_neighbor=1`、**`d1=dropped_top_handed`（不再是 orphan_adopted → 不再重复翻译）**、
  `cross_prep px=[198,0~519,73]`（擦字只覆盖移交过来的那条，下面那个气泡完全没被碰）、
  `scene_ok bubbles=2 (owned=1 cross=1 incoming=1 final=2)`。
- 单测 **152/152**（`cutAllowanceFollowsTheStripNotTheLineHeight` 替换原用例）。
- 注：这一轮的最终截图没抓干净（模拟器连续操作后停在浏览器起始页，点历史行没进去），
  但日志已经把 A/B 两侧的判决、擦字框与场景计数都记全了。

### 第四轮：在真机 Lezhin 上实测（用户：「不是 ridi，是 lezhin 上的漫画」）2026-10-03

- 用户指出夹具被**拉长**（我的错：`<img>` 写了 `height` 属性，而 CSS 只有 `width:100%` →
  呈现提示覆盖了原始比例）。已改成 `height:auto` + `aspect-ratio:622/360`：
  **属性**保证解码前有确定盒子（否则扫描器把上半切片当缩略图跳过），**CSS** 保证不拉伸。
- 用户还说「我不知道这些数据在哪里找」→ 这些日志不用他找：**我在同一台设备上用 adb 直接抓**
  （`adb logcat -s TranslationPerf:V EmbeddedBrowser:V`）。实测抓到的真实页面证据
  （`.verify/29_lezhin_live.logcat`，`adapter=lezhin`，真实章节）：
  - 页 A（600×2400，`botH=144`）：`detected=5 … d3=cross`、`seam_scene mode=band band=405x227`、
    `seam_dispatch ok=true`；
  - **`seam_strip_short: count=1 band=144px stitched_h=2544 :: [케이시 접속종료를 죽거나 ]`**
    —— 默认带宽装不下被切开的内容，句子被**截断着**读出来（`…접속게이트로 돌`，译文因此串味），
    而且移交深度（134px）比下一张图看到的浅；
  - 页 B：`orphan_adopted=1`（`d2` 被本图又认领一次）→ **缝上两层译文**，正是用户看到的现象。
- 因此本轮补上两件事（都是真机证据直接指向的）：
  1. **`seam_strip_short` → 加高条带重跑一次**：`Outcome.stripTruncated` 由管线带出，
     会话用 2× 带宽（上限 768px、且不超过整页一半）重裁并重跑一次（每张图只重跑一次；
     仅当 `incomingCross` 为空，因为移交贴片已被上一次调用回收）。日志 `seam_strip_retry` /
     `seam_strip_still_short`。
  2. **懒加载阅读器等待下一页**：Lezhin 翻页时下一张 `<img>` 往往还没出现，此时 `botH=0`
     → 跨缝链路整条不生效（真机 `seam_strip_short band=0px`）。现在「当前扫到的最后一张」
     先等最多 `NEXT_PAGE_WAIT_ROUNDS=3` 轮轮询（≈3.6s）等下一页露出来；等不到照常处理
     （章节最后一页就是没有下一张）。
- 状态：`./gradlew :app:testDebugUnitTest` **152/152**、已 installDebug 到 emulator-5554。
  **Lezhin 上的端到端复测还没拿到干净证据**：Lezhin 是分页阅读器，我脚本化的 scroll 手势
  会误触底栏（实测跳到了「术语表」tab），推进不了它的翻页；需要用户手动翻到问题页，
  我再用 adb 抓日志（`slice_classify / seam_verdict / cross_prep / seam_strip_short /
  seam_strip_retry`）。

### 第五轮：用户「试了，没有好」→ 抓真机日志定位并修掉两处（2026-10-03）

用户实测反馈后，我从设备上抓了他那次会话的日志（`.verify/34_lezhin_user.logcat`，201 行），
按每条 `seam_boxes` 算清了两个原因：

1. **加高重跑被「有移交」挡掉了**（主因）：上一张图 `d1=2457~2688`（底边正好顶到拼接图底边
   → `seam_strip_short band=144px`），因此只移交了 **134px**；下一张图的 `d2=54~402`，
   可见部分 `144~402`（258px），与 incoming（stitched 144~278）重叠 **134/258 = 52% < 60%**
   → 判成 `orphan_adopted` → **这张图又翻一遍 → 缝上两层**。
   而重跑当时被限制成「只在 `incomingCross` 为空时才做」——真机上**最需要重跑的恰恰是有移交的那张**。
   修：重跑前先把移交贴片**复制一份**（`incomingBackup`），有副本就允许重跑；副本没人用就回收。
2. **条带边缘的残片被当气泡**：`d1=23~169`（可见高度只有 25px，OCR 文本 `용살가`）被判
   `orphan_adopted`、`d2=2533~2574`（41px）判 `cross` → 画出 25px 的假气泡 / 43px 的薄盖板。
   修：新增**碎片守卫** `SeamConfig.fragmentHeightPx`（= 行高 × 0.4）：
   可见高度不足它的框一律 `dropped_fragment`（不认领、不移交、不渲染），留给真正看得到整行的那一侧。

验证：`./gradlew :app:testDebugUnitTest` **155/155**（新增 3 条：上边缘残片丢弃、跨下边界薄片不移交、
正常高度不受守卫影响），已 installDebug 到 emulator-5554。
Lezhin 的端到端复测仍需用户手动翻页（它是分页阅读器，脚本化手势会误触底栏），
日志我这边用 `adb logcat -s TranslationPerf:V EmbeddedBrowser:V` 直接抓。

### 第六轮：用户「现在是两段文字合成为一块了」→ 顶部尾巴被当成整句（2026-10-03）

抓日志（`.verify/35_user.logcat`，366 行）定位到的是**上边界侧**的问题，与上一轮的「下边界移交不足」相对称：

```
页3 topH=144 botH=0：
  d0 = 97~186（整块 89px，其中 47px 在上一张的条带里）
  可见部分 = 144~186 = 42px（片段守卫阈值 17px → 不算碎片 → 认领）
  refine_boxes b0 = 236x34@67,144     ← 把**整句**译文画进 34px 高的框
  OCR 文本 = "아니하지만 그게 방해됐단말이야"（整句）
```

上一张那边这段原文没被擦（它只是被上一张「截断着」画了上部），本页又在下面叠了一份整句译文
→ 读者看到「韩文 + 中文上下叠着」= 用户说的「两段文字合成为一块」。

- 修：`SeamConfig.minVisibleFraction = 0.5` —— 跨上边界的块**只有可见部分占整块 ≥50% 才认领**，
  否则整块归上一张（`dropped_mostly_above`）。这与下边界「移交覆盖率」的要求是对称的。
- 顺带：`NEXT_PAGE_WAIT_ROUNDS` 3 → 6（≈7s）：Lezhin 懒加载下一页常常慢于 3 轮，
  等待窗口太短就拿不到下条带（`botH=0`），跨缝链路整条不生效。
- 验证：`./gradlew :app:testDebugUnitTest` **157/157**（新增：大半在上条带 → 不认领；
  大半在本页 → 仍然认领并裁进可见区），已 installDebug。

### 第七轮：用户「有时候不属于这个界面的译文会出现」→ 元素回收（2026-10-03）

真机日志（`.verify/37_user.logcat`）里的关键几行：

```
seam_dispatch own=mt5 next=mt2 ok=true ...                    ← 一条缝被绑到两个**元素 id**
seam_suppress image=mt5 bubbles=1 removed=2                   ← 只摘掉了一半
[CONSOLE] "MTSEAM restore seam=mt1|mt2 reason=target_detached nodes=2"   ← 目标脱离 → 把两半贴回
scene_ok bubbles=5 (owned=4 cross=1 incoming=1 final=5)        ← 于是同一句在页面上出现两份
```

根因：Lezhin 是**虚拟化/回收 `<img>`** 的阅读器 —— 元素还在 DOM 里（`isConnected` 为真），
内容已经换成别的页。覆盖层只认元素 id，于是**旧译文留在新画面上**；而上一轮为「接缝失效不许
空白」加的「把两半贴回来」在元素被回收后会把这份错位放大（贴回的是旧页的节点）。

- 修 1（覆盖层）：`applyScene` 记录绑定时的 `targetSrc`；`syncOverlayPosition` 里发现元素的
  内容地址变了 → **整块卸掉**并打 `MTSTALE drop image=… reason=target_src_changed`。
- 修 2（接缝层）：同样记录 `t0Src/t1Src`；任一元素内容变了 → `dropSeam(restoreHalves=false)`
  并 `discardSeamHalves`（不贴回旧节点）。
- 修 3（恢复路径）：`restoreSeamHalves` 增加「记录仍有效」校验 —— 节点所在 root 必须还是当前
  注册的那个 root、目标图内容没变；否则丢弃并打 `MTSTALE discard-halves`。
- 验证：渲染层验收 **189/189**（新增 §15：目标图内容变了 → 旧覆盖层被卸掉、DOM 里不留 scene root），
  已 installDebug。

### 第八轮：用户再报「那两段合并成一块」→ 合并发生在**分组层**（2026-10-03）

用户第二次报同一症状，并提示「也许可以看看参考项目」。真机日志（`.verify/38_user.logcat`）把层次钉死了：

```
erase_target idx=0 text = 케이지라고요!! / 접속종료를 하려면 죽거나 / 접속게이트로 돌아가거나
                         두 가지 밖에 없는데.! 악질들한테 걸리기라도 / 하면 죽지도 못하면서 고통받을수도 있습니다.!
seam_boxes  d0 = 2305~2658（353px，横跨两个气泡）
bubble_first_result_stats regions=0 assignments=5 final=5   ← 气泡区域检测没命中 → 纯邻近分组
```

即：**加高条带之后**，条带里同时能看到「上一气泡的尾行」和「下一气泡的首行」，两块几何上确实挨得
很近（两个气泡轮廓几乎相接）→ 邻近合并把它们并成一个气泡 → 一个译文同时含两个气泡的台词，
而下一气泡自己又翻一遍 → 用户看到「两段合成一块」。

参考项目靠**气泡区域**（`region_id` + `sharedSpeechBubble`）区分同一气泡；我们这页 `regions=0`
退化成邻近度。而我们有一个参考项目没有的确定事实：**页面切缝**。

- 修（`BubbleFirstRegionPlanner`）：新增**缝感知分组** —— `setSeamBoundary(缝, 余量)`；
  两块分处缝两侧且间隙 > 余量（默认 `max(6px, 行高×0.35)`）时**不合并**（`crossesSeamBoundary`）。
  真被切开的同一行（间隙 ≤ 余量）仍然合并；而且**跨缝那半行的归属另有归属/配对层处理**
  （`SeamBubbleMerger` 的规则 1/3a），所以这里不合并不会丢句子。
- 接线（`WebtoonImageTranslator.detectBubbles`）：把 `bottomLimit`（本图下边界）与余量传给 planner，
  `route()` 用完立刻复位（planner 是复用的，避免把这条缝带给悬浮球链路）。
- 验证：`:app:testDebugUnitTest` **159/159**（新增两条：跨缝两块不合并、贴着缝的同一行仍合并），
  已 installDebug。
