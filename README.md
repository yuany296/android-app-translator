# MangaTranslatorMobile — Android 漫画翻译器

一款运行在 Android 上的**无后端漫画/漫画翻译工具**。采用「前台服务 + 悬浮球 + 覆盖层」的形态，主链路为 **截图 → OCR → 翻译 → 覆写渲染 → 翻页清理**。支持云端视觉模型与本地离线引擎（PaddleOCR 本地 OCR + ML Kit 本地翻译）。还实现了在内嵌浏览器中的漫画翻译和网页翻译。

工程结构说明见 [`docs/PROJECT_STRUCTURE.md`](docs/PROJECT_STRUCTURE.md)。

## 功能特性

- **多模块工程**：`app` + `core-capture` + `core-translate` + `core-overlay` + `core-store`
- **手动优先**：悬浮球点击触发翻译，不自动后台联网
- **OCR 双引擎**：
  - 云端：OpenAI 兼容视觉接口（`/chat/completions`）返回气泡坐标（0–100% 比例）
  - 本地：PaddleOCR（PP-OCRv5 mobile / ONNX Runtime），首次按源语言下载模型并缓存；失败自动回退检测引擎
- **翻译双引擎**：
  - 云端：OpenAI / Qwen（千问）/ GLM（智谱），同一兼容接口，支持扩展思考模式
  - 本地：ML Kit 端侧翻译（支持中/英/日/韩；繁体中文需回退云端）
- **覆写渲染**：loading 占位、分批淡入、点击切换原文/译文；渲染前采样截图像素还原每个气泡的背景色与字体色
- **擦字清理**：OpenCV inpaint 分层擦除原文字（TieredErase / CleanedImageProcessor）
- **术语表**：源→目标术语映射注入翻译 prompt（人名/地名一致），**多集合管理**，上限 500 条/集合，JSON 导入导出（SAF）
- **翻译历史**：译文永久保存（JSON，上限 500 条），翻回同页直接复用，可查看/删除/清空
- **缓存策略**：OCR 结果 2 小时短期缓存；翻译结果按「原文序列 + 翻译配置指纹 + 术语表 revision」按键持久化，改配置/术语表不重跑 OCR
- **配置加密**：`EncryptedSharedPreferences + MasterKey`，支持多档案（profile）配置
- **API Key 掩码**：输入显示为 `***`

## 支持的云端服务商

| 服务商 | 模型示例 | Base URL |
| --- | --- | --- |
| OpenAI | `gpt-4o-mini` | `https://api.openai.com/v1` |
| 阿里千问 Qwen | `qwen-vl-max-latest` | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| 智谱 GLM | `glm-4v-plus` | `https://open.bigmodel.cn/api/paas/v4` |

均使用 OpenAI 兼容的 `/chat/completions` 接口；Qwen / GLM 支持扩展思考模式。

## 技术栈

- Kotlin + Coroutines（`Dispatchers.IO` 处理网络/图像，`Dispatchers.Main` 处理 UI）
- Jetpack Compose + Material 3（配置/启动界面）
- OkHttp（20s 连接、60s 读写超时）
- OpenCV 4.10.0（文字擦除 inpaint）
- ONNX Runtime（本地 PaddleOCR）
- ML Kit（端侧翻译）
- `androidx.security:security-crypto-ktx`（AES-256-GCM 加密配置）
- Min SDK 29（Android 10），Target SDK 34，Java 17

## 运行步骤（Android Studio）

1. 用 Android Studio 打开项目根目录，等待 Gradle Sync 完成。
2. 连接真机（Android 10+），运行 `app` 模块。
3. 按提示依次授予：**悬浮窗权限 → 通知权限（Android 13+）→ 屏幕录制授权（MediaProjection）**。
4. 在配置页填写 `Base URL / Model / API Key` 并「保存配置」；或选择本地 OCR/翻译引擎。
5. 点击「启动服务」，进入任意漫画阅读 App，点击悬浮球 `译` 触发翻译。

## 打包 Debug APK

- Windows：`build-debug.bat`
- macOS / Linux：`./build-debug.sh`

> 若 `gradlew` 不存在：先在 Android Studio 完成一次 Sync，或执行 `gradle wrapper` 生成 wrapper。

### 常用 Gradle 命令

```bash
./gradlew assembleDebug        # 构建 Debug APK
./gradlew assembleRelease      # 构建 Release APK
./gradlew installDebug         # 构建并安装到已连接设备
./gradlew test                 # 运行单元测试
./gradlew lint                 # 运行 lint 检查
```

Maven / Gradle 镜像已通过阿里云配置在 `settings.gradle.kts`（面向国内网络优化）。

## 验证清单（最小可复现）

1. 悬浮球点击后 500ms 内出现「OCR + 翻译中…」。
2. 成功后覆写气泡显示译文，点击可切换原文/译文。
3. 模型返回空气泡时，overlay 自动清空。
4. 错误的 Base URL / API Key 时，loading 文案变为错误信息且不崩溃。
5. 本地 OCR / 本地翻译引擎离线可用（无网络时）。

## 目录结构（简）

```text
app/              应用入口、服务编排、权限引导、Compose 配置界面
core-capture/     屏幕采集（MediaProjection + VirtualDisplay + ImageReader）
core-translate    云端 OCR/翻译客户端 + 本地 PaddleOCR / ML Kit / 本地翻译器
core-overlay/     悬浮球、状态浮层、擦字贴片、气泡覆写渲染与交互
core-store/       配置加密、术语表、翻译历史、OCR/翻译缓存与分页调整
docs/             工程结构与调试文档
scripts/          构建 / 安装 / 抓日志等辅助脚本
```

## 更多文档

- [`docs/PROJECT_STRUCTURE.md`](docs/PROJECT_STRUCTURE.md) — 工程结构与主链路说明
- [`docs/PADDLE_OCR_MOBILE_INTEGRATION.md`](docs/PADDLE_OCR_MOBILE_INTEGRATION.md) — 本地 PaddleOCR 移动端接入
- [`docs/LOCAL_DEBUG_GUIDE.md`](docs/LOCAL_DEBUG_GUIDE.md) — 本地联调指南
- [`Todolist.md`](Todolist.md) — 待办清单
- [`HumanTODO.md`](HumanTODO.md) — 需人工处理事项
