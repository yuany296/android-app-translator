# 本地网页漫画夹具（P1）

文件：

- `web_reader.html`：模拟网页漫画阅读器的本地 HTML（`<html lang="ko">`）。
- `comic_page.png`：1080×3000 的竖版三格漫画，含白色/浅黄/浅蓝/浅粉气泡与韩文台词。

## 打开方式（推荐：模拟器直接点）

Debug 包 → 浏览器 → 新标签页 → 点「**本地测试夹具**」卡片。

入口只在 `BuildConfig.DEBUG` 渲染，release 不显示。内部走虚拟地址：

```
https://mtlocal.fixture/web_reader.html
```

- WebView 的 `shouldInterceptRequest` 从 `assets/fixtures/` 返回 HTML。
- 漫画链路的 `BrowserImageDownloader` 对同一域名也直接从 assets 读图片，
  不走 OkHttp，因此**不需要本地 HTTP 服务、也不需要 `adb reverse`**。
- 页面里的 `comic_page.png` 相对路径会自动映射到 `assets/fixtures/comic_page.png`。

## 备用打开方式（本机静态服务）

```powershell
python -m http.server 8765 --bind 0.0.0.0 --directory app/src/debug/assets
adb reverse tcp:8765 tcp:8765
# 地址栏输入：
http://127.0.0.1:8765/fixtures/web_reader.html
```

真机：电脑和手机连同一 Wi-Fi，地址栏输入 `http://<电脑局域网 IP>:8765/fixtures/web_reader.html`。

## 验证点

1. 从新标签页卡片能直接打开三格韩漫，图片正常显示。
2. 滚动长页面时悬浮球保持原位；拖动后退出网页再进，位置保持（P0）。
3. 页面 `lang=ko`，可用于网页语言检查（P2）。
4. 页面只有一张 `<img>`，适合验证截图翻译/条带链路的 OCR 切片、接缝和气泡分组。
5. 气泡有白/浅黄/浅蓝/浅粉四种底色，可用于纯色气泡颜色回归。

## 验证记录（2026-11-01，模拟机 Android 16）

- 新标签页卡片可见，点击后页面正常加载。
- 点球启动翻译：OCR 8 blocks → 6 bubbles，翻译请求 HTTP 200；
  无 `image_fail` / `fixture_asset_fail`。
- 滚动页面时悬浮球位置不变。

## 说明

- 夹具只放在 `src/debug/assets`，**不进 release APK**。
- 图片是程序生成的示意页，不依赖任何真实漫画站点。
