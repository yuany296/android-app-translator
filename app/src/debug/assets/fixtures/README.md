# 本地网页漫画夹具（P1）

文件：

- `web_reader.html`：模拟网页漫画阅读器的本地 HTML（`<html lang="ko">`）。
- `comic_page.png`：1080×3000 的竖版三格漫画，含白色/浅黄/浅蓝/浅粉气泡与韩文台词。

## 推荐打开方式（本机静态服务）

地址栏只接受 `http://` / `https://`，`file:///android_asset/...` 会被当成搜索词，所以用本机 HTTP 服务：

```powershell
# 在仓库根目录执行
python -m http.server 8765 --bind 0.0.0.0 --directory app/src/debug/assets
```

模拟器（推荐，绕过 Windows 防火墙）：

```powershell
adb reverse tcp:8765 tcp:8765
# 然后在 app 地址栏输入：
http://127.0.0.1:8765/fixtures/web_reader.html
```

真机：把电脑和手机连同一 Wi-Fi，地址栏输入 `http://<电脑局域网 IP>:8765/fixtures/web_reader.html`。

## 验证点

1. 页面能加载出三格漫画和韩文台词，图片可随页面滚动。
2. 滚动长页面时悬浮球保持原位；拖动后退出网页再进，位置保持（P0）。
3. 页面 `lang=ko`，可用于网页语言检查（P2）。
4. 页面只有一张 `<img>`，适合验证截图翻译/条带链路的 OCR 切片、接缝和气泡分组。
5. 气泡有白/浅黄/浅蓝/浅粉四种底色，可用于纯色气泡颜色回归。

## 说明

- 夹具只放在 `src/debug/assets`，**不进 release APK**。
- 图片是程序生成的示意页，不依赖任何真实漫画站点。
- 2026-11-01 已在模拟机（`sdk_gphone64_x86_64`，Android 16）验证：页面加载、滚动、悬浮球固定均正常。
