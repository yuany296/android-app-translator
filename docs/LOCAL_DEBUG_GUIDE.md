# 本地调试与安装手册（Windows）

- 日期：2026-03-25
- 执行者：Codex
- 当前工程路径：`C:\homework\AI_work\android-app-translator`
- Debug APK 路径：`C:\homework\AI_work\android-app-translator\app\build\outputs\apk\debug\app-debug.apk`

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
2. 项目配置：`C:\homework\AI_work\android-app-translator\gradle.properties`
3. 常用项：`systemProp.http.proxyHost` / `http.proxyPort` / `https.proxyHost` / `https.proxyPort`

> ⚠️ **2026-10-10 踩坑记录（务必先读）**
> **`proxyHost` 留空但 `proxyPort` 有值（如 `=80`）会让所有下载走一个不存在的代理**，
> 表现为 `Connection refused: getsockopt`，或 TLS 阶段 `SSL peer shut down incorrectly`。
> 它比"不配代理"更糟 —— 排查时很容易误判成 Google 域名被墙。
> **不用代理时就把这四行整行注释掉，不要只把 host 留空。**
> 判定方法（带/不带代理参数各跑一次，看哪次是 `code=200`）：
> ```powershell
> & "$env:JAVA_HOME\bin\java.exe" -Dhttps.proxyHost= -Dhttps.proxyPort=80 Probe.java
> ```
4. 使用 TUN/VPN 时，若下载握手失败，优先改为本地可达代理或直连后重试构建。

## Android SDK / Android Studio 环境（2026-10-10 实测）

| 项目 | 值 |
|---|---|
| Android Studio | `AI-253.30387.90.2532.14935130`，`C:\Program Files\Android\Android Studio1` |
| Gradle JDK | Studio 自带 JBR 21.0.9（`JAVA_HOME` 与 `org.gradle.java.home` 均指向它） |
| SDK 路径 | `C:\Users\yuanying\AppData\Local\Android\Sdk`（`local.properties` 的 `sdk.dir`） |
| platform | `android-34`（revision 3、extension level 7，`compileSdk = 34` 用）、`android-36.1` |
| build-tools | `34.0.0`、**`35.0.0`**（AGP 8.13.2 的默认要求）、`36.1.0` |
| aapt2 版本（**辨识真伪用**） | 34.0.0 → `2.19-10229193`；35.0.0 → `2.19-11948202`；36.1.0 → `2.20-14042983` |
| cmdline-tools | `cmdline-tools\latest`（23.0，含 `sdkmanager` / `avdmanager` / `android`） |
| AVD | `Medium_Phone`、`Pixel_8`（镜像 `android-36.1;google_apis_playstore;x86_64`） |

### ⚠️ 曾存在两个「伪造」的 SDK 组件（2026-10-10 已全部换成真品）

`platforms\android-34` 和 `build-tools\34.0.0` **都不是真品，而是 `android-36.1` 的整份副本**，
只把 `source.properties` / `package.xml` 里的版本号改了一下。这是 Studio 满屏
`Cannot access 'Object' / 'SharedPreferences' which is a supertype of …` 以及
`Could not find compile target android-34` 的**真正原因**（连框架类和 JDK 类都解析不出来，
说明模块压根没挂上可用的平台 SDK）。

**辨识方法** —— 改版本号能骗过目录名和 `source.properties`，但骗不过二进制：

| 检查项 | 伪造品的表现 |
|---|---|
| `build-tools\<v>\aapt2.exe version` | 34.0.0 与 36.1.0 的构建号**完全相同** |
| 文件哈希 | `aapt2.exe` / `zipalign.exe` / `lib\apksigner.jar` 与 36.1.0 **逐字节相同** |
| `platforms\<v>\build.prop` | `ro.build.version.sdk` 与目录名不符（假的 34 里写的是 `36`） |
| `platforms\<v>\source.properties` | `Pkg.Desc` / `Platform.Version` 与 `AndroidVersion.ApiLevel` **自相矛盾** |
| **APK 交叉验证** | `aapt2 dump badging app-debug.apk` 报的 `compileSdkVersion` 与项目 `compileSdk` 不一致 |

**修复前后对照：**

| | 伪造（旧） | 真品（现） |
|---|---|---|
| `platforms\android-34\android.jar` | 27,906,008（=36.1 的） | **26,361,808** |
| `…\build.prop` | `sdk=36`、`release=16` | **`sdk=34`、`release=14`** |
| `…\source.properties` | `Platform 16`、`Platform.Version=16` | **`Platform 14`、`Platform.Version=14`** |
| `build-tools\34.0.0\aapt2.exe` | 5,976,728（=36.1 的） | **4,566,296** |

修复后 APK 的 `compileSdkVersion` 由 `36` 变回 **`34`**，与项目 `compileSdk = 34` 一致。

#### ⚠️ 补 `package.xml` 时最容易写错的一个字段：`base-extension`

`android-34` 是**基准平台**（`source.properties` 里 `AndroidVersion.IsBaseSdk=true`），
所以必须写 `<base-extension>true</base-extension>`。**写成 `false` 不报任何错，但会让 SDK 库
给这个平台算出错误的包标识**：

```
base-extension=false  →  id: "android-34-ext7"   ✗  compileSdk = 34 找不到编译目标
base-extension=true   →  id: "android-34"        ✓
```

验证方法（`avdmanager` 与 Studio 用的是**同一个** SDK 解析库）：

```powershell
& "$env:ANDROID_SDK_ROOT\cmdline-tools\latest\bin\avdmanager.bat" list target
# 必须看到： id: 1 or "android-34"      Type: Platform      API level: 34
```

这一处正是 `Could not find compile target android-34` 与满屏
`Cannot access '…' which is a supertype of …` 的**直接来源** —— 平台标识对不上，
`compileSdk = 34` 就解析不到任何平台 SDK，模块连 `java.lang.Object` 都解析不出来。

安装来源（腾讯镜像；官方 `dl.google.com` 直连不稳）：

```powershell
# 基准 platform 34 在仓库里以 ext7 rev3 交付，display-name 就是 "Android SDK Platform 34"
curl.exe -L -o platform-34.zip    https://mirrors.cloud.tencent.com/AndroidSDK/platform-34-ext7_r03.zip
curl.exe -L -o build-tools-34.zip https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r34-windows.zip
# 解压后目录名分别为 android-34 / android-14，后者需重命名为 34.0.0
# 两个官方 zip 都不含 package.xml（AGP/Studio 靠它识别版本），需按同版本格式补一个
```

旧的伪造品已移到
`C:\Users\yuanying\AppData\Local\Android\_backup_fake_android-34_20261010\` 和
`…\_backup_fake_build-tools-34.0.0_20261010\`，确认无用后可整目录删除。

### 关键坑：AGP 8.13.2 默认要 build-tools 35.0.0

只有 34.0.0 / 36.1.0 时，构建会停在
`Failed to find Build Tools revision 35.0.0`，Studio 同步也跟着失败。
补装方式（`android` CLI 走官方仓库，国内常连不上；镜像直下更稳）：

```powershell
curl.exe -L -o bt35.zip https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r35_windows.zip
# 解压后目录名是 android-15，重命名为 35.0.0 放进 <SDK>\build-tools\
# 官方 zip 不含 package.xml（AGP 靠它识别版本），需照 36.1.0 的格式补一个
```

### dl.google.com 直连不稳

官方地址时通时断（`SSLHandshakeException` / `EOFException`）。腾讯镜像镜像了整套 SDK 仓库清单，
**但只能用 `curl` 直接取具体文件**（`repository2-3.xml`、`platform-34-ext7_r03.zip`、
`build-tools_r35_windows.zip` 这类）。

> ❌ **不要把 `https://mirrors.cloud.tencent.com/AndroidSDK/` 加为 Studio 的 SDK Update Site**。
> Studio 的更新站点会去请求该目录本身，返回 **HTTP 400**，界面上显示红色感叹号
> （日志：`Request failed with status code 400. Url=https://mirrors.cloud.tencent.com/AndroidSDK/`），
> 而且没有任何好处 —— 需要的组件按上面的方式手动装即可。

### Studio 侧的两个坑（都踩过）

1. **「重启」不等于「重新同步」**。Studio 启动时会直接复用缓存里的项目模型，日志原文：
   ```
   AndroidGradleProjectStartupActivity - Up-to-date models found in the cache. Not invoking Gradle sync.
   ```
   如果那份缓存是坏的（上一次同步失败留下的），重启一百次也没用 —— 满屏红字依旧。
   改完 SDK / JDK 后必须**手动**执行 `File → Sync Project with Gradle Files`；
   仍不行再 `File → Invalidate Caches… → Invalidate and Restart`。
2. **改完 SDK 要让它重新枚举平台**。`options\jdk.table.xml` 里只有
   `jbr-21` + 一个默认的 Android 平台条目是**正常**的 —— 每个 API 级别的条目是在同步成功时
   按需创建的。所以判断「Studio 认不认 android-34」不要看这个文件有几条，
   要看 `avdmanager list target` 里的 **包标识**（见上文 `base-extension` 一节）。

### 验证命令

```powershell
.\gradlew.bat :app:assembleDebug            # 出包
.\gradlew.bat test                          # 全模块单测
.\gradlew.bat :app:assembleDebugAndroidTest # 仪器测试包
```


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
