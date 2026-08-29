# Todolist

## 进行中（遗留问题，未处理）

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

---

## 已完成功能记录

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
