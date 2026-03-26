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
