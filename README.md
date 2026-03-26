# Android 无后端漫画翻译器（手动优先）

目录：`android-app/`

项目结构说明：`docs/PROJECT_STRUCTURE.md`

## 已实现能力
- 多模块工程：`app` + `core-capture` + `core-translate` + `core-overlay` + `core-store`
- 手动优先：悬浮球点击触发翻译
- `MediaProjection` 截屏 -> OpenAI 兼容视觉接口 -> 气泡覆写
- 覆写层支持：loading 占位、分批淡入、点击切换原文/译文
- 配置页支持：`baseUrl`、`model`、`apiKey`、启用开关、忽略简体中文开关
- 配置加密存储：`EncryptedSharedPreferences + MasterKey`
- 本地短期缓存：2 小时 TTL

## 关键文件
- `app/src/main/java/com/manga/translator/app/MainActivity.kt`
- `app/src/main/java/com/manga/translator/app/CaptureForegroundService.kt`
- `app/src/main/java/com/manga/translator/app/TranslateOrchestrator.kt`
- `core-capture/src/main/java/com/manga/translator/capture/ScreenCaptureController.kt`
- `core-translate/src/main/java/com/manga/translator/translate/OpenAiVisionClient.kt`
- `core-overlay/src/main/java/com/manga/translator/overlay/OverlayController.kt`
- `core-store/src/main/java/com/manga/translator/store/SettingsRepository.kt`

## 运行步骤（Android Studio）
1. 打开 `android-app/`。
2. 等待 Gradle Sync 完成。
3. 连接真机（Android 10+），运行 `app`。
4. 在应用中先点击“授予悬浮窗权限”。
5. 填写 `Base URL / Model / API Key`，点“保存配置”。
6. 点击“启动服务（请求录屏权限）”。
7. 进入任意漫画阅读 App，点击悬浮球 `译` 触发翻译。

## 打包 debug APK
- Windows: `build-debug.bat`
- macOS/Linux: `./build-debug.sh`

> 如果 `gradlew` 不存在：请先在 Android Studio 完成一次 Sync，或本机安装 Gradle 后执行 `gradle wrapper` 生成 wrapper。

## release 签名占位流程
1. 在 Android Studio: `Build > Generate Signed Bundle / APK`。
2. 新建或选择 keystore。
3. 选择 `release` variant。
4. 生成并安装测试。

## 权限顺序建议
1. 悬浮窗权限（`SYSTEM_ALERT_WINDOW`）
2. 通知权限（Android 13+）
3. 屏幕录制授权（MediaProjection）

## 验证清单（最小可复现）
1. 悬浮球点击后 500ms 内出现 `OCR + 翻译中...`。
2. 成功后覆写气泡显示中文，点击可切换原文/译文。
3. 模型返回空 bubbles 时，overlay 自动清空。
4. 错误 Base URL / API Key 时，loading 文案变更为错误信息，不崩溃。

## 环境提速配置（按你给的文章思路落地）
1. 项目镜像仓库已配置（无需手动改）
- 文件：`settings.gradle.kts`
- 已优先使用阿里云镜像：
  - `https://maven.aliyun.com/repository/gradle-plugin`
  - `https://maven.aliyun.com/repository/google`
  - `https://maven.aliyun.com/repository/public`
  - `https://maven.aliyun.com/repository/central`

2. Gradle 性能参数已开启
- 文件：`gradle.properties`
- 已启用：`parallel / caching / daemon / configuration-cache`

3. Android Studio 代理（强烈建议）
- 路径：`File > Settings > Appearance & Behavior > System Settings > HTTP Proxy`
- 有本地代理工具时，选择 `Manual proxy configuration` 并填写端口
- 点击 `Check connection` 测试 `https://dl.google.com`

4. 首次安装只装最小 SDK 组件
- `Android SDK Platform 34`
- `Android SDK Build-Tools 34.x`
- `Android SDK Platform-Tools`
- 先不要装 Emulator / System Image / NDK（后续需要再装）

5. 若仍然慢
- 关闭无关下载（网盘/系统更新/视频）
- 换手机热点测试是否是宽带链路问题
- 先完成一次 Sync，再执行构建
