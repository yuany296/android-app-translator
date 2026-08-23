# Testing

- 日期：2026-03-25
- 执行者：Codex

## 构建测试

1. ./gradlew.bat :app:assembleDebug
- 结果：成功

2. scripts/build_debug.bat
- 结果：成功
- APK：C:\homework\AI_work\translator\android-app\app\build\outputs\apk\debug\app-debug.apk

## 回归脚本

1. scripts/run_regression_smoke.bat
- 结果：脚本执行到设备检查阶段失败（无设备）
- 原因：db devices 无在线设备

2. scripts/install_debug.bat
- 结果：失败（无设备）

## 2026-03-26 本轮验证

1. .\gradlew.bat :app:compileDebugKotlin
- 结果：成功
- 覆盖范围：`app`、`core-capture`、`core-overlay`、`core-store`、`core-translate` 及 Manifest / 资源编译链路

## 本轮未执行

- 真机截图稳定性回归
- 无障碍清层回归
- 覆写定位视觉回归
- 原因：当前终端环境未连接设备，无法完成 Home/切应用/翻页等实机场景验证

## 2026-03-26 截图超时修复专项验证

1. .\gradlew.bat :app:compileDebugKotlin
- 结果：成功

2. scripts/run_regression_smoke.bat
- 结果：成功
- 日志：C:\homework\AI_work\translator\android-app\logs\regression-smoke-20260326-234156.txt

3. scripts/capture_logcat.bat
- 结果：成功
- 原始日志：C:\homework\AI_work\translator\android-app\logs\translator-logcat-20260326-234156.txt
- 汇总日志：C:\homework\AI_work\translator\android-app\logs\translator-logcat-20260326-234156-summary.txt
- 二次验证日志：C:\homework\AI_work\translator\android-app\logs\translator-logcat-20260326-234658.txt
- 二次验证汇总：C:\homework\AI_work\translator\android-app\logs\translator-logcat-20260326-234658-summary.txt

## 2026-03-26 本地OCR分组与小字修复验证

1. .\gradlew.bat :app:compileDebugKotlin
- 结果：成功

2. scripts/run_regression_smoke.bat
- 结果：成功
- 日志：C:\homework\AI_work\translator\android-app\logs\regression-smoke-20260326-235644.txt

## 2026-03-27 百度 OCR 竖排合并修复验证

1. .\gradlew.bat :core-translate:test
- 结果：成功
- 覆盖范围：`OpenAiVisionClientTest` 4 个单元测试，覆盖强竖排布局不匹配、竖排近列阈值、竖排堆叠阈值、合并后 layout 保持 vertical

2. .\gradlew.bat :core-translate:lint
- 结果：成功

3. .\gradlew.bat lint
- 结果：失败（既有问题，非本次改动引入）
- 首个错误：`app/src/main/AndroidManifest.xml:10`，`TranslatorApplication must extend android.app.Application [Instantiatable]`
- 同批错误：`MainActivity`、`CaptureForegroundService`、`OverlayAccessibilityService` 也触发 `Instantiatable`
- 结论：`core-translate` 模块 lint 通过，但仓库级 lint 仍被 `app` 模块既有问题阻断

4. 本轮未执行
- 百度 OCR 实机截图翻译回归
- 原因：当前终端未直接接入目标截图与 logcat 场景，需设备侧补充验证

## 2026-03-27 翻译锁定禁止翻页验证

1. `.\gradlew.bat :core-overlay:testDebugUnitTest`
- 结果：成功
- 覆盖：锁定层轻触释放、滑动阻断

2. `.\gradlew.bat --no-configuration-cache -Pkotlin.incremental=false :app:testDebugUnitTest`
- 结果：成功
- 覆盖：锁定期间忽略同包内容变化、Home/切应用仍清层

3. `.\gradlew.bat --no-configuration-cache -Pkotlin.incremental=false :app:compileDebugKotlin`
- 结果：成功
- 说明：首次常规编译受 Gradle/Kotlin 本地缓存影响，禁用配置缓存与增量编译后通过

## 2026-03-27 本轮未执行

- 真机翻页拦截冒烟
- 点击翻译消失后恢复翻页的设备侧验证
- Home/切应用后的实机清层复测
- 原因：当前终端未连接可操作设备

## 2026-03-27 bubble-first OCR 主链路验证

1. `.\gradlew.bat :app:testDebugUnitTest --console=plain`
- 结果：成功
- 覆盖：`BubbleFirstRegionPlannerTest` 验证气泡内归属、outside 分流、outside 透明 bubble 保持独立

2. `.\gradlew.bat :core-translate:test --console=plain`
- 结果：成功
- 覆盖：百度 OCR 文字块解析与既有合并修复单测继续通过，说明新 `detectTextBlocks()` 未破坏 `detectBubbles()` 兼容路径

3. `.\gradlew.bat :app:assembleDebug --console=plain`
- 结果：成功
- 说明：`app`、`core-overlay`、`core-translate` 联编通过，bubble-first 主链路可生成可安装 APK

4. `adb -s AN3GUT4A22006400 install -r app\\build\\outputs\\apk\\debug\\app-debug.apk`
- 结果：成功

5. `adb -s AN3GUT4A22006400 shell am start -n com.manga.translator.app/.MainActivity`
- 结果：成功

6. `.\gradlew.bat :app:lintDebug --console=plain`
- 结果：失败（既有问题，非本次改动引入）
- 首个错误：`app/src/main/AndroidManifest.xml:10`，`TranslatorApplication must extend android.app.Application [Instantiatable]`
- 说明：当前 `app` lint 仍被既有 Android 组件声明问题阻断，bubble-first OCR 改动未新增新的首个 lint 阻断项

## 2026-03-27 内容区覆写与录屏会话门禁修复验证

1. `.\gradlew.bat :app:testDebugUnitTest :core-translate:testDebugUnitTest :core-overlay:testDebugUnitTest --console=plain`
- 首轮结果：失败
- 失败点：
  - `core-overlay`：`TieredEraseProcessor.buildCacheKey()` 的字符串拼接触发 `MessageDigest.update()` 重载歧义；
  - `core-translate`：旧百度测试样本仍假设“严格规则不合并、启发式才合并”，与当前共享保守规则不一致。
- 处理：修正缓存 key 拼接，更新百度几何测试样本与断言。

2. `.\gradlew.bat :app:testDebugUnitTest :core-translate:testDebugUnitTest :core-overlay:testDebugUnitTest --console=plain`
- 结果：成功
- 覆盖：
  - `BubbleFirstRegionPlannerTest`：验证内容区坐标生成与空白沟槽分裂行为；
  - `CaptureSessionPolicyTest`：验证 `ACTIVE` 不再触发录屏授权、`REAUTH_REQUIRED` 才触发；
  - `OpenAiVisionClientTest`：验证百度共享几何判定与合并后内容区边界。

3. `.\gradlew.bat :app:assembleDebug --console=plain`
- 结果：成功
- 说明：`app`、`core-capture`、`core-overlay`、`core-translate` 联编通过，本轮接口改动未破坏整包构建。
