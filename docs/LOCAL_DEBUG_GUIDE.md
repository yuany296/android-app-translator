# 本地调试与安装手册（Windows）

- 日期：2026-03-25
- 执行者：Codex
- 当前工程路径：`C:\homework\AI_work\translator\android-app`
- Debug APK 路径：`C:\homework\AI_work\translator\android-app\app\build\outputs\apk\debug\app-debug.apk`

## 一键脚本

- 设备检查：`scripts\check_device.bat`
- 构建 Debug：`scripts\build_debug.bat`
- 安装/覆盖安装：`scripts\install_debug.bat`
- 重装并拉起：`scripts\reinstall_debug.bat`
- 导出日志：`scripts\capture_logcat.bat`
- 回归冒烟脚本：`scripts\run_regression_smoke.bat`

## USB 授权修复（unauthorized）

1. 手机开启开发者选项与 USB 调试。
2. 执行 `adb kill-server` 后重新插拔数据线。
3. 执行 `adb devices`，手机弹窗勾选“始终允许”，点击允许。
4. 若仍 unauthorized：
- 电脑删除 `C:\Users\yuanying\.android\adbkey*`
- 手机“撤销 USB 调试授权”后重试。

## 代理/网络问题排查

1. Gradle 用户配置：`C:\Users\yuanying\.gradle\gradle.properties`
2. 项目配置：`C:\homework\AI_work\translator\android-app\gradle.properties`
3. 常用项：
- `systemProp.http.proxyHost`
- `systemProp.http.proxyPort`
- `systemProp.https.proxyHost`
- `systemProp.https.proxyPort`
4. 使用 TUN/VPN 时，若下载握手失败，优先改为本地可达代理或直连后重试构建。

## 安装失败排查

1. `INSTALL_FAILED_VERSION_DOWNGRADE`：先 `adb uninstall com.manga.translator.app` 再安装。
2. `INSTALL_FAILED_USER_RESTRICTED`：手机侧允许 USB 安装/调试安装。
3. `no devices/emulators found`：先执行 `scripts\check_device.bat`。
4. APK 不存在：先执行 `scripts\build_debug.bat`。

## 建议执行顺序

1. `scripts\check_device.bat`
2. `scripts\build_debug.bat`
3. `scripts\install_debug.bat`
4. 需要误退出恢复时执行 `scripts\reinstall_debug.bat`
