# PaddleOCR 移动端集成评估

- 日期：2026-08-24
- 范围：TodoList #4「特殊字体识别（PaddleOCR）」的评估与可选引擎接入

## 1. 结论摘要

| 项目 | 结论 |
| --- | --- |
| 可选引擎 | 已实现 `PaddleOcrLocalTextDetector` 接入现有 `LocalTextDetector` 接口 |
| 推理框架 | ONNX Runtime（onnxruntime-android 1.29.0），非 Paddle-Lite JNI |
| 模型 | PP-OCRv5 mobile 官方 ONNX（det + rec，日/韩/中三语 rec + 字典） |
| 模型获取 | 首次使用时联网下载到 `filesDir/paddle_models/`，按源语言选 rec 模型 |
| 回退 | Paddle 引擎失败/模型不可用 → 自动回退 ML Kit（`EngineSelectingTextDetector`） |
| 体积增量 | onnxruntime native 约 30MB/ABI（arm64），模型 det 4.6MB + rec 10~16MB 按需下载 |

## 2. 路线对比：Paddle-Lite JNI vs ONNX Runtime

### 2.1 Paddle-Lite（官方移动端 JNI 路线）

- 预编译包：`inference_lite_lib.android.armv8.gcc.c++_shared.with_extra.with_cv.tar.gz`（9.23MB，GitHub release v2.10）。
- 组成：`libpaddle_lite_jni.so`（JNI）+ `PaddlePredictor.jar`（Java API），模型需先用 `paddle_lite_opt` 转成 `.nb` 格式（v2.10 配 PP-OCRv3 模型）。
- 优点：官方移动端方案，推理库小（~9MB tar）。
- 缺点：
  1. **仅 armv7/armv8**，无 x86_64 Android 预编译 → 本机 x86_64 模拟器无法运行验证。
  2. 需维护 `paddle_lite_opt` 转换工具链（pip paddlelite==2.10），多语言 rec 模型需自行转换。
  3. JNI 集成复杂度高于纯 Java API。

### 2.2 ONNX Runtime（本项目选用）

- 依赖：`com.microsoft.onnxruntime:onnxruntime-android:1.29.0`（Maven Central / 阿里云镜像可解析）。
- 模型：PaddleX 官方导出的 PP-OCRv5 mobile ONNX（`paddle-model-ecology.bj.bcebos.com` 与 `huggingface.co/PaddleOcrNet` 均可得），与参考项目使用的 PP-OCRv5 同源。
- 优点：
  1. 纯 Java/Kotlin API（`OrtEnvironment`/`OrtSession`/`OnnxTensor`），无手写 JNI。
  2. 同时支持 arm64-v8a 与 x86_64 → 模拟器可直接验证。
  3. 模型为官方 ONNX 导出，不需要额外转换工具链。
- 缺点：native `.so` 较大（arm64 约 30MB，x86_64 约 36MB），APK 体积增加明显。

## 3. 模型清单与体积

| 模型 | 文件 | 大小 | 用途 | 下载源 |
| --- | --- | --- | --- | --- |
| det | PP-OCRv5_mobile_det.onnx | 4.6MB | 文本检测 | paddle-model-ecology / PaddleOcrNet |
| rec-zh | PP-OCRv5_mobile_rec.onnx | 15.8MB | 中英识别 | 同上 |
| rec-ja | japan_PP-OCRv5_mobile_rec.onnx | 9.6MB | 日文识别 | PaddleOcrNet |
| rec-ko | korean_PP-OCRv5_mobile_rec.onnx | 12.8MB | 韩文识别 | PaddleOcrNet |
| 字典 zh/ja/ko | paddle_{zh,ja,ko}_dict.txt | 17~74KB | CTC 解码 | 内置 assets |

- 模型输入/输出（已用 onnx 元数据验证）：
  - det：`x [N,3,H,W]`（H/W 需为 32 倍数，代码用 128 对齐）→ `[N,1,H,W]` 概率图（模型内已含 sigmoid）。
  - rec：`x [N,3,48,W]`（动态宽）→ `[N,T,C]`，C=字典+blank+space。

## 4. 预处理/后处理（与参考项目参数对齐）

| 阶段 | 参数 | 取值 | 依据 |
| --- | --- | --- | --- |
| det 预处理 | resize_long | 960 | 模型 inference.yml `DetResizeForTest` |
| det 预处理 | 归一化 | mean/std 0.485,0.456,0.406 / 0.229,0.224,0.225 | 同上 `NormalizeImage` |
| det 后处理 | thresh | 0.3 | 参考项目 `DEFAULT_TEXT_DET_THRESH`=0.3，与 yml 一致 |
| det 后处理 | box_thresh | 0.6 | 参考项目 `DEFAULT_TEXT_DET_BOX_THRESH`=0.6 |
| det 后处理 | unclip_ratio | 1.2 | 参考项目默认 1.2（模型 yml 默认 1.5，参考项目刻意收紧） |
| det recovery | thresh / box_thresh / unclip_ratio | 0.2 / 0.42 / 1.5 | 参考项目 recovery 检测的放松参数 |
| rec 预处理 | 高度/参考宽 | 48 / 320 | 模型 yml `RecResizeImg image_shape [3,48,320]` |
| rec 预处理 | 归一化 | (x/255-0.5)/0.5，0 填充 | paddlex `OCRReisizeNormImg` 实现 |
| rec 解码 | CTC | blank=0，去除重复，字典+末尾空格 | paddlex `CTCLabelDecode(use_space_char=True)` |
| 后聚合 | 行合并 | 基于字高比例 | 参考 translator `ocr-lines.js` |
| 后聚合 | 段落合并 | 基于字高/对齐比例 | 参考 translator `ocr-lines.js` |

## 5. 特殊字体/彩色大字增强策略

针对参考项目中 ML Kit 漏检、Paddle mobile 能定位但识别错的彩色/艺术字场景，做了三层增强：

1. **检测增强（Recovery）**
   - 主检测仍用原图 + 默认参数（`det_thresh=0.3 / box_thresh=0.6 / unclip=1.2`）。
   - 当主检测块数 < 4 或覆盖率 < 0.5% 时，额外跑最多 3 个变体：
     - `gray`：grayscale + autocontrast(1%) + contrast×2.2
     - `binary`：深色字/红褐字保留为黑、其余变白
     - `inv_gray`：反色增强灰度
   - recovery 使用更松的参数：`0.2 / 0.42 / 1.5`；结果按 IoU<0.45 去重合并。

2. **识别增强（Multi-crop voting）**
   - 每个检测框在原图裁剪后，会再生成增强灰度和二值掩码两个子图。
   - 对原图/增强灰度/二值掩码分别跑识别，按置信度选最优结果。

3. **聚合增强（Block merging）**
   - `PaddleOcrBlockMerger` 按字高比例合并同行碎片，并进一步把跨行但同一对白/段落的行合并为完整句子。
   - 全部阈值参考 translator 的 `ocr-lines.js`/`ocr-cluster-geometry.js`。

日志中会新增 `det_pass_ms`、`recovery`、`coverage`、`primary`、`total_quads`、`raw_blocks` 等字段，便于真机调优。

## 6. 模型下载与本地推理

- 时机：首次 `detect()` 且本地无缓存时，在 IO 线程阻塞下载（无网络则直接回退 ML Kit）。
- 位置：`filesDir/paddle_models/`，按文件存在+大小校验（>1MB 视为有效），下载失败清理半成品。
- 语言路由：源语言 `ja/jpn/日文`→JA、`ko/kor/韩文`→KO、其余→ZH（覆盖英文/中文）。
- 推理：OrtSession 会话按模型懒加载并复用；det 4 线程，BASIC_OPT。
- 日志 Tag：`PaddleOcr`（det_ms / det_boxes / paddle_total_ms / blocks）、`EngineSelecting`（回退原因）。

## 7. 待真机确认事项（见 HumanTODO.md）

- 真机（arm64）首次触发模型下载与识别效果。
- 特殊字体/艺术字识别率与 ML Kit 的对比。
- 推理耗时（TranslationPerf 观察总链路耗时）。
- 模型托管地址如遇网络问题可替换 `PaddleModelCatalog.BASE_URL` 为内网镜像。