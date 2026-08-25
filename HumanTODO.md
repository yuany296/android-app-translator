# Human TODO（待人工确认）

- 日期：2026-08-25
- 维护者：OpenCode

## 2026-08-25 本地翻译引擎（ML Kit 端侧翻译）人工验证

- 新增「翻译引擎」下拉（首页，OCR 引擎下方）：`云端 API`（默认）/ `ML Kit 本地翻译（离线）`。
- 本地翻译（ML Kit Translation，`com.google.mlkit:translate:17.0.3`）：
  - 支持语言对：英文/日文/韩文 → 简体中文，以及这些语言之间的互译。**繁体中文目标（zh-tw）不支持**，会自动回退云端。
  - 首次使用某语言对需联网下载模型并缓存（约几 MB，需 WiFi）；服务启动后已后台预热（`TranslationPerf` 过滤 `translate_model_prewarm_start/done`）。之后完全离线翻译、无网络往返，明显提速。
  - 需设备装有 Google Play 服务（与现有 ML Kit OCR 同依赖链）。
- 真机验证清单：
  1. 首页「翻译引擎」切到「ML Kit 本地翻译（离线）」后翻译韩漫/日漫/英漫页：logcat 过滤 `TranslationPerf` 看 `translate_local ok=mlkit count=N`，确认不再出现 `request_send stage=translate mode=cloud`，且总耗时显著下降（离线无网络往返）。
  2. 首次切到本地引擎时，确认服务启动后 `translate_model_prewarm_start` → `done`（WiFi 下模型下载），首次点击不再卡在下载。
  3. 将设备切飞行模式（断网）翻译：本地引擎应仍能出译文；若断网且模型未缓存，应回退/提示失败（logcat 过滤 `MlKitLocalTranslator` 看 `mlkit_translate_prewarm_failed` 或 `translate_local_fallback`）。
  4. 目标语言选「繁体中文」时：本地引擎不支持会自动回退云端（`translate_local_skip reason=unsupported_pair`）。
  5. 本地译文质量与云端对比如可接受（端侧模型质量一般低于大模型，台词恐有翻译腔）。
  6. 确认未配置云端 API + 选择本地引擎时仍能正常翻译（之前"仅OCR"分支不再拦截本地引擎）。

## 2026-08-25 PaddleOCR 提速 + 模拟器联网 人工验证

- 模拟器联网：主机 Clash 代理 127.0.0.1:7890 需手动设置模拟器代理（重启后失效）：
  `adb -s emulator-5556 shell settings put global http_proxy 10.0.2.2:7890`
- PaddleOCR 提速改动（模拟器实测：OCR 2846ms→944ms、清理图 566ms→228ms、总耗时含翻译 API ~4.1s）：
  1. 识别级联：原图识别置信度≥0.93 直接返回，跳过其余 7 个变体（低置信度框仍全变体投票）。过滤 logcat `PaddleOcr` 看 `paddle_total_ms` 与 `rec_cascade_hit/full` 比例，确认清晰文字命中快速路径、艺术字/低对比度仍走全量。
  2. recovery 变体改在降采样工作图（长边≤960）构建，quad 映射回原图坐标。确认彩色大字/艺术字召回未回退（重点复测 `이거 재밌네` 场景）。
  3. 区域检测与整页 OCR 并行（`bubble_region_stats` 与 `bubble_region_ocr_stats` 时间戳应相同）。
- 真机验证：正常漫画 App 下连续翻译多页，观察 `TranslationPerf` 各阶段耗时是否与模拟器一致的量级；确认级联对特殊字体识别率无回退。

- 日期：2026-08-25
- 维护者：OpenCode

## 2026-08-25 整页清理图（cleaned image）人工验证

- 需重新构建并安装最新 APK（scripts\build_debug.bat + install_debug.bat）。
- 本次改动对齐参考项目 `C:\homework\translator` 的 cleaned image 链路：
  - 新增 `CleanedImageProcessor`：当页内存在非 solid（旁白/透明文字块）气泡时，用其内容框生成 inpaint mask（椭圆膨胀，半径 clamp(内容高*0.08, 2, 8)），对整页做一次 TELEA inpaint，产出"去字原图"（按 pageHash 缓存）。
  - `TieredEraseProcessor.erasePage` 增加 `cleanedImage` 参数：非 solid 气泡不再用"替换墨色像素"的创可贴，而是从清理图按气泡原始位置裁切贴片（外扩 10px 保证裁切边是无 inpaint 的原图像素、无缝衔接）；无清理图/OpenCV 不可用时回退原逻辑。
  - `TranslateOrchestrator.prepareVisuals`：有非 solid 气泡才构建清理图，与擦除/颜色/布局并行，构建后立即回收。
- 真机验证：
  1. KakaoPage 类漫画页（旁白直接压在背景图上）：确认旁白译文底下是原图背景纹理（inpaint 去字后），不再是纯色块/色差，观感接近参考项目。
  2. 纯色底旁白（黑底白字说明框）：确认仍走 `solid` 逻辑、行为不变。
  3. 性能：过滤 logcat `TranslationPerf` 看 `cleaned_image built=...` 耗时与 `erase_apply` 的 `cleaned=true`；若整页 inpaint 过慢（>1s）或卡顿，反馈耗时，评估降采样/降级策略。
  4. 观察清理图贴片边缘是否与原图无缝（无白边/黑边/色块边界）。
- 日志过滤：`CleanedImageProcessor`（cleaned_image built/skipped）、`TranslationPerf`（cleaned_image、erase_apply cleaned=true）。

- 日期：2026-08-24
- 维护者：OpenCode

## 2026-08-24 PaddleOCR 可选引擎 人工验证

- 需重新构建并安装最新 APK（scripts\build_debug.bat + install_debug.bat）。
- 首页新增「OCR 引擎」下拉（ML Kit / PaddleOCR），切换到 PaddleOCR 后首次翻译会下载模型（det 4.6MB + 对应语种 rec 10~16MB，缓存在 files/paddle_models/）。
- 真机验证：
  1. 切到 PaddleOCR 引擎后翻译漫画页，logcat 过滤 `PaddleOcr` 看 `det_boxes`、`paddle_total_ms`、`blocks`；模型下载看 `model_download_start/done`。
  2. 特殊字体/艺术字/竖排场景：对比 ML Kit 与 PaddleOCR 的识别率（重点验证本事项初衷）。
  3. 网络不通或模型下载失败时，应自动回退 ML Kit：logcat 过滤 `EngineSelecting` 看 `paddle_fallback_to_mlkit`。
  4. 首次翻译耗时包含模型下载；模型已缓存后二次翻译速度应明显更快（`TranslationPerf` 观察 OCR 阶段耗时）。
  5. 若 HuggingFace 域名不可达，确认 BOS tar 下载路径可用（代码已做 BOS 优先，直连 HF 回退）。
- 模拟器已验证：日语「こんにちは世界」识别 score 0.999（androidTest PaddleOcrLocalTextDetectorTest）。

- 日期：2026-08-24
- 维护者：OpenCode

## 2026-08-24 句子级翻译缓存 人工验证

- 需重新构建并安装最新 APK（scripts\build_debug.bat + install_debug.bat）。
- 翻译缓存改为句子级"原文→译文"一一对应（key=源语言|目标语言|原文），不再按整页指纹/页码匹配；OCR 不再持久缓存（每次重新识别）。
- 真机验证：
  1. 首次翻译某页（调 API），翻到别的页再翻回/重复翻译，应命中句子缓存：logcat 过滤 `TranslationPerf`，确认 `sentence_cache_hit=true cached=N`（不再出现 `request_send stage=translate`）。
  2. 同一句原文出现在其他页面，也应直接命中（不重复扣费）。
  3. 清除缓存按钮（首页）现在清空句子翻译缓存（toast「翻译缓存已清空」）。
  4. 翻译失败时（logcat `response_fail`），已缓存的句子仍正常显示，未缓存句子显示原文。
  5. 检查文件 `files/mt_sentence_translation.json`（`adb shell run-as com.manga.translator.app cat files/mt_sentence_translation.json`）条目是否正确写入/去重。

- 日期：2026-08-24
- 维护者：OpenCode

## 2026-08-24 旁白清理贴片 + 缓存命中 + 超时修复 人工验证

- 需重新构建并安装最新 APK（scripts\build_debug.bat + install_debug.bat）。
- 1. 旁白"创可贴"清理贴片：旁白/透明文字块现在也按内容框生成擦除贴片（擦除原字、保留背景，含白字场景），译文叠加在贴片上。真机找旁白压旁白/旁白压对白的页面，确认原字被精准盖住、译文可读。过滤 logcat `TranslationPerf` 看 `erase_apply` 的 `narration_patched`、`patches` 数量。
- 2. 翻译缓存命中：同一页再次翻译应命中历史（`TranslationPerf` 过滤 `history_hit=true mode=fingerprint|pagehash`），不再重复调翻译 API；OCR 结果顺序/空白变化不影响指纹；若气泡数一致但指纹不中，应出现 `history_hit=true mode=pagehash`。
- 3. 翻译超时：readTimeout 已从 60s 放宽到 120s。若仍失败，过滤 logcat `VisionClient`/`TranslationPerf response_fail` 反馈错误类型（timeout/SSL/HTTP 码）。
- 4. 若旁白贴片在复杂背景上观感差（色块突兀），反馈页面截图，评估回退策略（半透明白底/仅描边）。

- 日期：2026-08-24
- 维护者：OpenCode

## 2026-08-24 旁白/透明文字块渲染背景 人工验证

- 需重新构建并安装最新 APK（scripts\build_debug.bat + install_debug.bat）。
- 背景渲染改动（参考 C:\homework\translator）：浏览模式下所有译文文字块先绘制背景再绘制文字——
  - `solid` 气泡：原有擦除贴片提供背景；无贴片时回退为采样边框底色（BubbleColors.bgColor）。
  - `none`（旁白）/`transparent`：文字块后绘制半透明白色圆角底（alpha≈0.55）+ 原白描边。
  - 调整模式：非 solid 文字块 View 同样带半透明白底，拖拽时文字仍可读。
- 真机验证：
  1. 找一页文字块互相靠近/重叠的漫画（旁白压旁白、旁白压对白），确认译文不再叠在一起看不清。
  2. 确认 solid 气泡观感与之前一致（擦除贴片未受影响），无贴片时回退底色不明显突兀。
  3. 确认旁白半透明白底不会太脏/太遮画面（alpha 可调，OverlayController.narrationFillColor）。
  4. 过滤 logcat `TranslationPerf` 查看 `background_fallback_count`，与页面上无贴片/非 solid 块数量核对。
- 日志过滤：`TranslationPerf`（`background_fallback_count`）、`RenderDebug`（bgType）。

- 日期：2026-08-24
- 维护者：OpenCode

## 2026-08-24 翻译 API 配置切换 + 译点不动修复 人工验证

- 已在模拟器安装新 APK，需验证：
  1. 翻译设置页创建/编辑多个 API 配置后，下拉切换任一项，切到别的 tab 再回来，当前选中项不会回退。
  2. 切换配置后启动服务翻译，实际使用的是最新选中的配置（可观察 logcat `TranslationPerf` 中请求域名或错误信息对应所选厂商）。
  3. 翻译完成后译文锁定层：在模拟器上单指滑动/长按/点击屏幕，均应释放锁定并清除译文；不再出现“译点不动/滑动被拦截”现象。
  4. 在阅读器内翻页（手势或点击）后，旧译文层应自动清除；若未清除，请导出 logcat 并搜索 `overlay_accessibility_decision`、`overlay_clear`、`TranslationPerf lock_gesture_blocked` 反馈。
- 日志过滤：
  - `OverlayA11yMonitor`：确认无障碍事件已上报。
  - `CaptureForegroundSvc`：查看 `overlay_accessibility_decision`、`overlay_clear`、`overlay_clear_skip`。
  - `OverlayController`：查看 `translation_layer_locked/unlocked/clear_translation_layer`。
  - `TranslationPerf`：查看 `lock_tap_release`、`lock_gesture_blocked`。

- 日期：2026-08-23
- 维护者：Codex

## 2026-08-23 渲染配色重构 + 翻译提速 真机验证

- 需重新构建并安装最新 APK（scripts\build_debug.bat + install_debug.bat）。
- 配色（参考 kakao 设计）：背景色改为边框区域中值采样；文字色优先保留原墨色（对比度≥4.5），否则黑白二选一；描边改为深字白边/浅字黑边（不再是背景色）；文字区不再画不透明底色矩形（透明渲染，由擦除层提供背景）。真机在 kakaopage 页验证：深色气泡浅字、浅色气泡深字、彩色气泡文字的可读性与观感。
- 提速：擦除/颜色/布局计算已与翻译请求并行。真机复测并过滤 logcat `TranslationPerf`，确认总耗时下降；若仍慢，把各阶段耗时贴出来定位。
- PaddleOCR 集成（特殊字体识别）为待办大项，见 Todolist.md。

## 2026-08-23 翻译配置重构（向导式新建 + 模型列表 + 术语表改名） 真机验证

- 需重新构建并安装最新 APK（scripts\build_debug.bat + install_debug.bat）。
- 术语表改名：全局「集合」→「术语表」（新建术语表/删除术语表/导入为术语表等）。
- 翻译设置页重构：页面只显示「选择配置」下拉 + 新建/编辑/删除；表单不内嵌，点「编辑」进入与新建一致的整屏表单。
- 新建配置向导：先选厂商（OpenAI/千问/GLM/DeepSeek/Kimi/硅基流动/火山方舟/自定义 OpenAI 兼容）→ 表单（名称、Base URL 预填可改、API Key、Model）→「获取模型列表」从厂商 /models 拉取（真实 Key 已验证 dashscope 成功、错误 Key 显示失败提示）→ Model 字段原位下拉选择（已验证选中并保存、重开表单回填）。
- 编辑配置界面新增返回按钮（返回即关闭表单）。
- 配置下拉与表单首次进入一致性：表单直接加载所选配置。
- 真机验证清单：新建一个配置走完整向导（含模型列表拉取）；编辑已有配置改模型/名称保存；翻译运行时使用最后保存的配置；术语表页所有文案无「集合」。
- 真机验证清单：详情页点某行原文/译文 -> 输入框出现 -> 改字 -> 点列表空白处应自动保存并退出编辑；编辑原文时点译文应能直接切换；点"添加术语" -> 输入行出现在按钮上方 -> 填写后按键盘完成键保存；点"编辑" -> 复选框多选 -> 全选 -> 删除；287 条大集合下滑动、添加、搜索后添加不互相干扰。

## 2026-08-23 两个 UI bug 修复 + 术语导入 真机验证

- 需重新构建并安装最新 APK（scripts\build_debug.bat + install_debug.bat）。
- 修复 1：集合/术语行左划后右侧应露出红色删除按钮（SwipeRevealRow 中 offset 与 background 顺序修正）。
- 修复 2：集合详情页底部「添加术语」按钮之前被压缩为 0 高度不可见（fillMaxSize 改为 weight(1f)），现在应可见。
- 导入验证：`manga-glossary-2026-08-23.json`（287 条）已在模拟器导入为集合「manga-glossary-2026-08-23」并显示 287 条；真机可用同样步骤（术语表页 -> 导入 -> 选择文件 -> 确认命名）验证。
- 翻译时确认该集合术语被注入（logcat 过滤 `glossary_merge`）；若需要全部生效，需在列表页把开关打开（新导入集合默认不激活）。
- 集合重命名（新增）：列表页每行新增编辑图标 -> 弹窗改名 -> 保存后列表刷新；已激活集合改名后仍保持激活（模拟器已验证 TestSwipe -> Renamed）。真机验证重命名后：条目保留、导出文件名/内容正确、翻译注入仍生效。

## 2026-08-23 底栏 + 术语表 + 永久历史 + 颜色参考 真机验证

- 底栏 4 tab 切换是否正常，Back 键行为符合预期。
- 术语表：添加/编辑/删除；导入/导出 JSON（SAF 文件选择）后条目与顺序正确，导入完成后列表自动刷新；下拉刷新可重新读取数据；搜索按原文/译文过滤是否生效。
- 术语表多集合：新建集合、切换编辑集合、多选生效集合；翻译时确认多个生效集合的术语都被注入（过滤 logcat 翻译请求，确认术语行内容）；同源术语冲突时靠前集合优先。
- 永久历史：翻译成功后进入"翻译历史"能看到记录；翻回同一页再次翻译不调用翻译 API（过滤 logcat `translate_response history_hit=true`，确认扣费不重复）；删除/清空生效；重启 App 后历史仍在；下拉刷新正常。
- API Key 掩码：配置页 API Key 显示为 `•`，保存后再次进入仍为掩码，翻译功能正常。
- 颜色参考：覆写文字/描边颜色是否贴近原图（浅底深字、深底浅字、彩色气泡）；竖排气泡同样验证。
- 缓存分离：修改翻译配置或术语表后重译同一页，OCR 不重跑（logcat `ocr_cache hit=true`）。

## 2026-08-23 Glossary Refactor 真机验证

- 集合列表页：**向左划**任一集合行可露出右侧红色删除按钮，点击删除后该集合从列表消失且本地配置被移除。
- 集合列表页：集合开关可切换 active 状态，允许多个集合同时生效；全部关闭后翻译不应注入任何术语。
- 新建集合后自动跳转到该集合详情页；详情页底部导航栏隐藏，点击系统返回或顶部返回回到集合列表。
- 集合详情页：**向左划**任一术语行可露出右侧红色删除按钮，点击删除后移除该术语。
- 集合详情页：点击某条术语的"原文"或"译文"格子，该行切换为输入框，修改后失去焦点/点击完成即保存；列表即时刷新。
- 集合详情页：顶部搜索框可按原文或译文过滤，清空后恢复全部。
- 导入 JSON/CSV：在集合列表页选择导入后弹出命名对话框，默认取文件名；确认后新建集合并自动跳转到详情页；导入的新集合默认不激活。CSV 支持 `source,target` 表头或无表头，支持逗号/引号/换行转义。
- 导出 JSON/CSV：在集合详情页分别提供导出按钮；CSV 含 `source,target` 表头并正确转义特殊字符。
- 集合详情页：下拉刷新正常；返回列表页后下拉刷新正常。
- 多集合术语冲突时，按 active 集合在列表中的先后顺序优先；可在 logcat 过滤 `glossary_merge` 观察注入顺序。
- 空 active 集合时，验证翻译请求中不含 glossary 行（过滤 logcat `translation_request`）。

## 2026-08-21 本地 OCR 化改造后真机验证

- 使用当前安装包，在韩漫 App（Webtoon 类）验证：
  - 源语言选韩文后，ML Kit 识别韩语对白是否准确、是否成组为气泡。
  - 点击悬浮球 -> 显示译文；点击屏幕任意处 -> 译文消失。
  - 翻译结果是否为目标语言（默认简体中文）。
  - 翻页/滚动后再次点击悬浮球是否重新翻译（缓存命中时不重复扣费）。
  - 返回桌面/切应用后译文层是否自动清除。
  - 若 ML Kit 对竖排/拟声字识别不佳，再评估是否引入 Paddle Lite。

## 覆写效果确认

- 验证普通对白页与复杂背景页的擦除观感（FAST/EDGE_AWARE/OPENCV 分级）。
- 验证翻译框可读性（不截断、重叠可接受、横竖屏位置稳定）。
- 验证悬浮球吸附、半隐藏、两段点击、长按复位体感。
- 验证调整模式拖拽/缩放/描边切换、超时退出体感。

## 稳定性确认

- 连续点击翻译 10 次，确认无卡顿和无并发叠加。
- 翻页后旧覆盖是否及时清理。
- unavailable 场景下悬浮球与覆盖层隐藏/恢复是否符合预期。

## 2026-03-27 百度 OCR 竖排合并确认

- 使用百度 OCR 对竖排日漫截图做一次完整翻译，确认同一气泡内多列竖排文字不再被拆成多个覆盖框。
- 观察 logcat 中 `baidu_merge_stats`，确认 `merged` 小于 `raw`，说明近邻合并已实际发生。
- 若仍有漏合并，过滤 `baidu_merge_reject` 日志并保留样本，重点记录 `reason`、`overlap_y_ratio`、`overlap_x_ratio`、`h_gap`、`v_gap`。

## 2026-03-27 bubble-first OCR 主链路确认

- 使用当前安装包在同一问题页复测，确认正文对白优先按气泡框成组，不再依赖整页 OCR 后合并。
- 观察页边旁白、拟声、说明文字，确认它们作为独立框显示，没有并入最近对白气泡。
- 过滤 logcat 中 `bubble_region_stats`、`bubble_region_assignment`、`outside_region_stats`、`bubble_first_result_stats`，比对区域数量、outside 数量与肉眼观感是否一致。

- 日期：2026-08-24
- 维护者：DSH Agent

## 2026-08-24 译文背景不透明化 + LLM 提速（缓存模糊匹配/UI过滤/max_tokens） 真机验证

- 已构建并安装到模拟器（adb install app-debug.apk），验证通过项：同页重复翻译命中句子缓存零 LLM 调用（1.4s vs 修复前 20s）；网页 UI 词（Prologue/Previous/Next/Auto/3-52）被过滤不再送 LLM。
- 待真机确认：
  1. 旁白/非 solid 气泡译文背后现在是不透明采样底色（参考 C:\homework\translator kakaopage 的 fillRect 形式），确认原文被完全盖住、色块观感可接受（太突兀可反馈，考虑降为半透明）。
  2. 完成态悬浮球文字显示「完成·缓存x/y」是否符合预期；全命中时无 LLM 调用（logcat 过滤 TranslationPerf 看 sentence_cache 行 missing=0）。
  3. UI 词过滤（TranslateOrchestrator.isNonTranslatableText + UI_LABEL_WORDS）是否误伤漫画对白（如 Previous/Next 等词出现在正文中）；误伤时反馈具体词。
  4. OCR 识别抖动场景应出现模糊命中：logcat 过滤 SentenceCache 看 lookup fuzzy_hit（如 COUNTUESS vs COUNTLESS），确认不再重复调 LLM。
  5. 翻译请求现在带 max_tokens=2048（VisionClient request_send 后 bytes 增大），观察 response_ok 的 cost_ms 是否回落。

- 日期：2026-08-24
- 维护者：DSH Agent

## 2026-08-24 追加：UI 文本过滤已回退

- 按用户要求回退 UI 过滤（UI_LABEL_WORDS 词表、页码/倍速格式、1-2 字符 ASCII 三条规则全部移除）。
- 保留：URL/域名片段与全同字符噪声（IIII 等）拦截——这两类无对白歧义，防止垃圾条目挤占缓存。
- 理由：UI 文本首次翻译后即进入句子缓存，后续全部命中（实测同页 exact=6、missing=0、1.4s 零 LLM 调用），过滤反而有误伤对白的风险。
- 模拟器已验证：回退后同一页面 7 个文本 6 精确命中 + 1 URL 跳过，无 LLM 调用。

- 日期：2026-08-24
- 维护者：DSH Agent

## 2026-08-24 追加：完成态状态文案自动隐藏

- 完成态「完成·缓存x/y」胶囊实测 193x52px（约 74x20dp），位于悬浮球右侧，窗口 FLAG_NOT_TOUCHABLE+NOT_FOCUSABLE（不挡触摸、不抢焦点）。
- 新增：完成态文案展示 3 秒后自动隐藏（OverlayController.scheduleStatusAutoHide，STATUS_AUTO_HIDE_MS=3000），避免长期停留在角落遮挡译文。模拟器已验证：完成后 mViewVisibility 0x0 -> 5 秒后 0x8（隐藏）。
- 真机可顺带确认胶囊位置/时长是否符合预期（时长可调 STATUS_AUTO_HIDE_MS）。

- 日期：2026-08-24
- 维护者：OpenCode

## 2026-08-24 PaddleOCR 彩色大字/聚合改进 人工验证

- 需重新构建并安装最新 APK（scripts\build_debug.bat + install_debug.bat）。
- 本次改动（参考 C:\homework\translator 的参数与聚合方法）：
  1. 检测增强：主检测后若块数少/覆盖率低，自动跑增强灰度、二值掩码、反色增强三个 recovery 变体，阈值放松到 0.2/0.42/1.5。
  2. 识别增强：每个检测框分别用原图、增强灰度、二值掩码跑识别，按置信度选最优。
  3. 聚合增强：同行碎片与跨行段落按字高比例合并，解决同一句话被拆成多个块的问题。
- 真机验证清单（用你提供的韩漫截图 /sdcard/Pictures/test_manga.jpg）：
  1. 顶部句子 "뭔가에 확 꽂혀본 적 있는가?" 应被合并为一个块（含换行或空格）。
  2. 中部 "단순히" 应单独识别。
  3. 底部彩色大字 "이거 재밌네" 应被 PaddleOCR 识别（ML Kit 此前漏检）。
  4. logcat 过滤 `PaddleOcr` 看 `recovery=true/false`、`primary=N`、`total_quads=N`、`raw_blocks=N`、`merged=N`，确认 recovery 被触发且耗时可接受。
  5. 若彩色大字仍识别错误/漏检，保留该页截图并导出 logcat，重点记录 `det_pass_ms recovery_*` 的 boxes 数量和识别出的 text。

## 2026-08-24 PaddleOCR 新截图模拟器实测（Chrome 截图 1224x2700）

- 测试图：C:\Users\yuanying\Downloads\Screenshot_20260823_235349_com_microsoft_emmx_ChromeTabbedActivity.jpg（推送为 /sdcard/Android/data/com.manga.translator.app/files/test_manga.jpg）。
- 运行：androidTest `PaddleOcrKoreanMangaTest`（模拟器 Pixel_8 API 16，Paddle 引擎 lang=ko，模型已缓存）。
- logcat 关键行：
  - `det_pass_ms=3911 tag=primary boxes=9`
  - `paddle_total_ms=12113 primary=9 total_quads=9 raw_blocks=8 merged=5 recovery=false coverage=0.2022 lang=ko`
  - 说明 recovery 未触发（coverage 0.20 > recovery 阈值?），总耗时约 12s。
- Paddle 识别结果：
  1. 顶部整句 → 已合并为一个块 `뭔가에: 확꽃혀본적\n있는가?`（box 218,272,1009,504 conf 0.975）。PASS（聚合生效，含换行）；但存在错字：`꽂`→`꽃`、空格丢失（应 `확 꽂혀본 적`）。
  2. 中部 `단순히` → 单独块 conf 0.89。PASS。
  3. 底部彩色大字 `이거 재밌네` → **FAIL**，仅识别出一个大字 `이`（box 59,1806,1212,2238 conf 0.72，框几乎覆盖整个底部区域）。Paddle 对彩色艺术字仍难以识别，与本事项初衷（特殊字体/彩色大字）目标仍有差距。
- androidTest 断言：1、2 通过，第 3 条（이거/재밌네）失败 → connectedDebugAndroidTest 返回失败。
- 结论/待办：彩色大字 `이거 재밌네` 仍需专项处理（recovery 阈值/增强变体、或针对性检测参数）；保留本截图与 logcat 供后续改进对照。

## 2026-08-25 Overlay 渲染对齐 kakaopage 规则（模拟器人工验证）

- 参考 `C:\homework\translator` 的 kakaopage 渲染规则（`extension/public/styles.css` + `embedded-scene-adapter.js`）：
  - `solid`（对话气泡/有底）：垫不透明背景（`--mt-adaptive-bg`），**不加描边**。
  - `none`（旁白/无框文字）：`background: transparent`，靠**擦除原文后的清洁图（inpaint 贴片）**垫底，**加白/自适应描边**（`-webkit-text-stroke`，宽 `clamp(fontSize*0.085,1.8,4.2)`）。
- Android 已具备等价能力：`TieredEraseProcessor.erasePage` 对非 solid（旁白）同样生成 inpaint「创可贴」贴片（ROI=文字框、只替换墨色像素、边缘感知填充），等价于 kakaopage 的 cleaned image（局部版）。
- Android 改动（`OverlayController.kt`）：
  1. `bubbleViewBackgroundFill`：非 solid（旁白）→ 返回透明，不再垫采样色块。
  2. `drawTextBackgrounds`（ErasePatchLayer）：仅 solid 且无擦除贴片时画圆角背景；旁白不再画色块（依赖 inpaint 贴片垫底）。
  3. 视图与 patch 文本描边：solid → 无描边；旁白 → 用 `kakaopageStrokeWidthPx`（spToPx(size)*0.085，clamp dp(1.8)~dp(4.2)），颜色沿用 `BubbleColorExtractor` 自适应（深字→白边、浅字→黑边）。新增 `isNarrationBg(bgType)` = 非 solid。
- 需真机/模拟器复现一条真实翻译链确认：
  1. 对话气泡（solid）：透明背景盖住原文 + 裸字无描边。
  2. 旁白（none）：无采样色块，擦除贴片垫底（对白/旁白文字框处原图被清洁）+ 白描边。
  3. 具体看一张韩漫页，观察旁白区是否不再有突兀色块、文字白边是否清晰。
- `naturalStrokeWidth`、`PatchTextSpec.strokeDp` 现为保留未用（兼容，可后续清理）。

## 2026-08-25 投影失效自动停止服务（模拟器人工验证）

- 背景：单应用授权 + 切首页/他应用触发 `MediaProjection.onStop()`，导致投影失效后悬浮球点击无反应（`projectionUnavailable` 分支 toast 被 1500ms 限流吞掉）。
- 根因确认：投影一旦停止 `isCaptureAlive()` 恒 false（`projectionStopped` 不会自复位、`mediaProjection` 置 null），且本项目**没有**"重新获取 MediaProjection token"的路径（需 App 的 MainActivity 重新 `createScreenCaptureIntent()`），故 `background_capture_recovered` 实际触发不到 —— 服务无法自行恢复授权。
- 改动（`CaptureForegroundService.kt`）：`enterProjectionUnavailable(reason)` 现在**统一自动停止服务**（不再区分 reauth/临时不可用，也不再静默保留悬浮球）：
  1. 弹 `Toast.LENGTH_LONG` 明确提示："录屏授权已失效，服务已停止，请重新打开 App 重新授权"（不经过限流）。
  2. `stopSelf()` → `onDestroy` 会把会话状态存为 `REAUTH_REQUIRED`。
  3. 移除未用的 `shouldToast` 参数。
- 验证清单：
  1. 单应用授权后切到首页/他应用，确认服务自动停止、悬浮球消失、弹出长 toast。
  2. 重开 App，主界面应从 `REAUTH_REQUIRED` 状态引导重新授权（MainActivity "录屏授权已失效，请重新授权"）。
  3. 重新授权后能正常翻译。

## 2026-08-25 启用 OpenCV inpaint 擦除 + OCR 模型加载提示 + 悬浮球锁定层修复（模拟器已验证）

### 1) 擦除策略 FAST_ONLY → OPENCV_WHEN_AVAILABLE
- 背景：Android 的 `TieredEraseProcessor` 内置 OpenCV inpaint（整块圆角矩形区域背景纹理恢复，等价参考项目 cleaned image 的本地实现），但 `TranslateOrchestrator` 写死 `FAST_ONLY`（只用「替换墨色像素」的局部贴片），且 `ERASE_BUDGET_MS=180ms` 页面级预算过小，OpenCV 在首个气泡后就被降级 —— 日志里 `opencv` 计数恒为 0。
- 改动：
  1. `TranslateOrchestrator.kt` 两处 `FAST_ONLY` → `OPENCV_WHEN_AVAILABLE`（erasePage policy + renderConfig.erasePolicy）。
  2. `TieredEraseProcessor.kt` `ERASE_BUDGET_MS` 180→3000ms；缓存 key 升 `erase_v3|opencv_enabled`。
- 模拟器验证（单应用授权 Photos 下）：
  - bg1：`erase_apply ... fast=0 edge=0 opencv=3 patches=3 narration_patched=3`
  - bg2：`erase_apply ... fast=0 edge=0 opencv=4 patches=4 narration_patched=4`
  - 之前恒为 `fast=3 edge=0 opencv=0`，现在旁白全部走 OpenCV inpaint 整块背景修复。
- 需人工确认：擦除观感是否接近参考项目 cleaned image（背景纹理自然恢复、无补丁感）；若个别页 OpenCV 效果不佳或性能慢，可考虑对 solid 气泡仍用 FAST_FILL。

### 2) OCR 模型加载提示 + 悬浮球不可用
- 新增能力：
  1. `LocalTextDetector` 接口加默认 `isReady(lang)` / `prewarm(lang)`；Paddle 实现为「det+rec 会话是否已建」与「只加载不检测」（`ensureEngineReady` 抽取共用）；`EngineSelectingTextDetector` 按当前 OCR 引擎转发。
  2. `FloatingState.MODEL_LOADING`（灰色按钮 + 旁白"OCR模型加载中"）；`OverlayController.setFloatingEnabled(false)` 置半透明并忽略触摸。
  3. `CaptureForegroundService`：服务启动后后台 `prewarmOcrModel()`；点击悬浮球时若 `isReady=false` → 悬浮球置灰不可用 + 旁白"OCR模型加载中" → 后台 prewarm → 完成后恢复可用、提示消失（本次不立即翻译，需再点一次）。
- 模拟器验证：`model_prewarm_start lang=ko` → `model_prewarm_done lang=ko`（缓存模型约 600ms）。
- 需人工确认：首次冷启动/换新语种（模型未缓存）时点击悬浮球，是否出现置灰按钮 + "OCR模型加载中"提示，加载完成后恢复红色可用。

### 3) 悬浮球被锁定层吞点击（"要点多次才有反应"根因）修复
- 根因：翻译完成后 `lockTranslationUntilTap` 在全屏翻译层内加 lockTapView，翻译层窗口在悬浮球**之上**，锁定后第一次点击（即使点在悬浮球上）被锁定层吞掉 → 需点一次解锁、再点一次才翻译。
- 改动：`OverlayController` 新增 `bringFloatingButtonToFront()`（移除+重加悬浮球窗口），在 `ensureLayer()` 添加翻译层后调用，使悬浮球始终在翻译层之上、随时可点。
- 模拟器验证：翻译完成（层已锁定）后**第一次**点击悬浮球 → logcat 立即 `start: 0ms`（不再出现 `lock_gesture_blocked`）。
- 需人工确认：真机上手感是否"每点必应"（不再需要连点）。

### 4) 三张复杂背景截图识别结果（用户提供，参考项目可识别）
- 在 Photos（单应用授权）中逐张开 bg1/bg2/bg3 实拍识别，Paddle `lang=ko`：
  - bg1：3 块（`를 넘어서 깊은 인상을\n받은 나머지` + Photos 底栏 Share/Lens）
  - bg2：4 块（`시간과 돈을 추가로 써본적이\n있냐는 뜻이다.` / `예를 들자면` + Photos 底栏）
  - bg3：部分识别（含 `머` 碎片，见 history 条目 3）
- 说明：完整韩语句子识别正确（`sentence_cache_hit` 稳定命中）；Photos 底栏 Share/Lens/Trash/Edit 属于测试环境 UI 干扰（真机漫画 App 无此问题）；复杂背景仍有低置信碎片（如 `머`）。
- 需人工确认：对照原图确认上述识别文本是否正确；重点看彩色/艺术字场景（此前的 `이거 재밌네` 仍是 FAIL，见上文 Paddle 章节）。

## 2026-08-25 合并文本残留换行符导致翻译/显示异常（模拟器已验证修复）

- 现象：用户反馈"合并后的文字是不是保留了换行符，翻译得很怪"。
- 根因：`PaddleOcrBlockMerger.mergeGroupToBlock` 横向跨行用 `\n` 连接（`joinToString("\n")`），`\n` 随 originalText 进入翻译请求；LLM 返回的译文也残留 `\n` 直接回填渲染（如 `超越……留下深刻印象\n以至于`），显示与断句都奇怪。
- 改动（3 处）：
  1. `PaddleOcrBlockMerger.kt`：跨行连接符 `\n` → 空格，原文为完整句子（译文由渲染侧按气泡宽度自动换行）。
  2. `TranslateOrchestrator.kt` 翻译请求：`originalText.replace(Regex("\\s+"), " ").trim()`（防其他路径带入换行）。
  3. `TranslateOrchestrator.kt` 译文回填：LLM 返回值同样归一化空白后再回填/入缓存。
- 模拟器验证（清缓存强制走 API 新鲜翻译）：
  - originalText：`를 넘어서 깊은 인상을\n받은 나머지` → `를 넘어서 깊은 인상을 받은 나머지`（无 `\n`）；竖排误合并 `0\nR\nshare` → `0 R share`。
  - translatedText：`超越……留下深刻印象\n以至于` → `在超越之后，留下了深刻的印象`（无 `\n` 且翻译更完整流畅）。
- 需人工确认：真机上旁白/对白译文不再出现奇怪换行，排版正常。
