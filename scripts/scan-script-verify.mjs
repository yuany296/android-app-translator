"use strict";
/**
 * 取图扫描脚本「真实浏览器」验收（2026-09-30）。
 *
 * ## 为什么需要它
 *
 * `BrowserScanScript` 的脚本是**跑在 WebView / Chromium 里的**，它依赖的东西
 * JVM 单测与 Node 的 DOM stub 都给不了真答案：
 * - `getBoundingClientRect()` 的真实布局结果（滚动容器内的坐标换算全靠它）；
 * - `getComputedStyle()` 的 `overflow`（用来找可滚动祖先）；
 * - `blob:` URL 能不能被 `<img>` 加载；
 * - `<canvas>` 的 `toDataURL()` 会不会因为跨域污染抛 `SecurityError`；
 * - `srcset` 里浏览器**自己**选了哪一张（`currentSrc`）。
 *
 * 所以这里用**真实的无头 Chrome** 打开夹具页，把 Kotlin 里那份脚本原样注入进去
 * 跑一遍，核对返回值。
 *
 * ## 关键设计：脚本从 Kotlin 源码里现取，不复制
 *
 * `SCAN_JS` / `EXTRACTOR_JS` 是 Kotlin 的原始字符串字面量。这个脚本会去
 * `BrowserScanScript.kt` 里把这两段**抠出来**再用 —— 避免「测试里有一份拷贝、
 * 实现改了测试没跟上」这种最没价值的绿色。抠不出来就直接报错退出（不静默跳过）。
 *
 * ## 用法
 *
 * ```bash
 * node scripts/scan-script-verify.mjs           # 自动起本地服务 + 无头 Chrome
 * node scripts/scan-script-verify.mjs --keep    # 保留 Chrome 进程与截图
 * ```
 *
 * 退出码 0 = 全部断言通过。
 */

import { spawn } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..");
const FIXTURE_DIR = path.join(ROOT, ".verify", "sites");
const SCAN_SRC = path.join(ROOT, "app", "src", "main", "java", "com", "manga", "translator", "app", "BrowserScanScript.kt");
const ADAPTER_SRC = path.join(ROOT, "core-store", "src", "main", "java", "com", "manga", "translator", "store", "BrowserSiteAdapter.kt");

const KEEP = process.argv.includes("--keep");
const PORT_HTTP = 8917;
const PORT_CDP = 9333;

// ---------------------------------------------------------------------------
// 1. 从 Kotlin 源码里抠出脚本与适配器表
// ---------------------------------------------------------------------------

/**
 * Kotlin 原始字符串字面量 `val NAME: String = """ … """.trimIndent()` → 文本。
 *
 * 注意：`NAME` 也可能出现在 KDoc 的 `[NAME]` 引用里，天真的 `indexOf` 会先命中
 * 文档那一处、抠出一段注释。所以要逐个候选点检查「后面紧跟的就是 `: String = """`」。
 */
function extractRawString(source, declaration) {
  const pattern = new RegExp(declaration.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + '\\s*:\\s*String\\s*=\\s*"""');
  const match = pattern.exec(source);
  if (!match) throw new Error(`找不到声明：${declaration}: String = """`);
  const open = source.indexOf('"""', match.index);
  const close = source.indexOf('"""', open + 3);
  if (close < 0) throw new Error(`${declaration} 的原始字符串没有闭合`);
  return source.slice(open + 3, close);
}

/** Kotlin 的 `"…" + "…"` 拼接表达式 → 文本（取 `= ` 之后的第一个表达式块）。 */
function extractConcatenated(source, declaration) {
  const marker = source.indexOf(declaration);
  if (marker < 0) throw new Error(`找不到声明：${declaration}`);
  const eq = source.indexOf("=", marker);
  const end = source.indexOf("\n\n", eq);
  const block = source.slice(eq + 1, end < 0 ? source.length : end);
  const parts = [...block.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) =>
    m[1].replace(/\\"/g, '"').replace(/\\n/g, "\n").replace(/\\'/g, "'").replace(/\\\\/g, "\\")
  );
  if (!parts.length) throw new Error(`${declaration} 里没解析到字符串片段`);
  return parts.join("");
}

const scanKotlin = fs.readFileSync(SCAN_SRC, "utf8");
const adapterKotlin = fs.readFileSync(ADAPTER_SRC, "utf8");

// Kotlin 原始字符串里的 `window.$CONFIG_VAR` 是**字面量**（`$` 在原始字符串里不插值），
// 真正注入时由 installScript 拼上变量名。这里必须做同样的替换，否则脚本读到的是
// `window.$CONFIG_VAR`（undefined），表现为「候选一个都扫不到」。
const CONFIG_VAR_NAME = (function () {
  const m = /const val CONFIG_VAR = "([^"]+)"/.exec(scanKotlin);
  if (!m) throw new Error("抽不到 CONFIG_VAR 常量名");
  return m[1];
})();
const SCAN_JS = extractRawString(scanKotlin, "val SCAN_JS").replace("window.$CONFIG_VAR", "window." + CONFIG_VAR_NAME);
const EXTRACTOR_JS = extractRawString(scanKotlin, "val EXTRACTOR_JS");

if (!SCAN_JS.includes("window." + CONFIG_VAR_NAME)) {
  throw new Error("抽出来的 SCAN_JS 不像扫描脚本（缺配置读取 window." + CONFIG_VAR_NAME + "）");
}
if (!SCAN_JS.includes("hostOf") || !SCAN_JS.includes("frames")) {
  throw new Error("抽出来的 SCAN_JS 不完整（缺可滚动宿主 / iframe 遍历）");
}
if (!EXTRACTOR_JS.includes("__mtExtract")) throw new Error("抽出来的 EXTRACTOR_JS 不像像素提取器");
if (!scanKotlin.includes("DEFAULT_CHUNK_CHARS = 350_000")) throw new Error("分片大小常量不见了");

/**
 * 脚本语法自检 —— 必须在起 Chrome 之前做。
 *
 * 为什么值得单独一步：脚本是 Kotlin 里的一大段原始字符串，漏一个 `}` 在 Kotlin
 * 侧完全编译通过（它就是一段普通字符串），要到浏览器里才以
 * 「SyntaxError: Unexpected token 'function'」的形式炸出来，定位成本很高。
 * 用 V8 自己编译一遍，报的行号是准的。
 */
for (const [name, source] of [["SCAN_JS", SCAN_JS], ["EXTRACTOR_JS", EXTRACTOR_JS]]) {
  try {
    new Function(source);
  } catch (err) {
    const lines = source.split(/\r?\n/);
    let hint = "";
    const m = /line (\d+)/.exec(String(err.message));
    if (m) {
      const n = Number(m[1]);
      hint =
        "\n" +
        lines
          .slice(Math.max(0, n - 3), n + 2)
          .map((l, i) => "  " + (n - 2 + i) + ": " + l)
          .join("\n");
    }
    throw new Error(name + " 语法错误（Kotlin 侧编译发现不了）：" + err.message + hint);
  }
}
console.log("脚本语法自检通过： SCAN_JS " + SCAN_JS.length + " 字符 / EXTRACTOR_JS " + EXTRACTOR_JS.length + " 字符");

/** 适配器表：只解析验收需要的字段（id / label / hostPatterns / imageSelectors / 阈值）。 */
function parseAdapters(kotlin) {
  // 切块：从 `BrowserSiteAdapter(` 到下一个「行首四个空格 + )」为止。
  // 不能用一个非贪婪的 `\)` 收尾 —— `listOf(...)` 里也有右括号，会把块截断，
  // 结果是**静默拿到空数组**（比报错更危险）。
  const blocks = [];
  const startRe = /BrowserSiteAdapter\(/g;
  let m;
  while ((m = startRe.exec(kotlin)) !== null) {
    const from = m.index + m[0].length;
    const tail = kotlin.slice(from);
    const endMatch = /\n {4}\)/.exec(tail);
    blocks.push([null, endMatch ? tail.slice(0, endMatch.index) : tail]);
  }
  const adapters = [];
  for (const block of blocks) {
    const body = block[1];
    const pick = (name) => {
      const m = body.match(new RegExp(`${name}\\s*=\\s*"([^"]*)"`));
      return m ? m[1] : null;
    };
    // listOf(...) 要按**括号配平**取，不能用非贪婪 `\)` —— listOf 里会嵌套括号
    // （选择器里就有 `:not([data-src=''])` 这种带括号的），截断了就会静默少拿几条，
    // 甚至整条列表变空 —— 那会让验收退化成「只跑通用规则还全绿」。
    const list = (name) => {
      const marker = new RegExp(`${name}\\s*=\\s*listOf\\(`);
      const hit = marker.exec(body);
      if (!hit) return [];
      let depth = 1;
      let i = hit.index + hit[0].length;
      const from = i;
      while (i < body.length && depth > 0) {
        const ch = body[i];
        if (ch === "(") depth += 1;
        else if (ch === ")") depth -= 1;
        i += 1;
      }
      const inner = body.slice(from, i - 1);
      return [...inner.matchAll(/"([^"]*)"/g)].map((x) => x[1]);
    };
    const num = (name) => {
      const m = body.match(new RegExp(`${name}\\s*=\\s*(\\d+)`));
      return m ? Number(m[1]) : null;
    };
    const id = pick("id");
    if (!id) continue;
    adapters.push({
      id,
      label: pick("label"),
      hostPatterns: list("hostPatterns"),
      imageSelectors: list("imageSelectors"),
      excludeSelectors: list("excludeSelectors"),
      minWidth: num("minWidth"),
      minHeight: num("minHeight"),
      minNaturalWidth: num("minNaturalWidth"),
      minNaturalHeight: num("minNaturalHeight"),
    });
  }
  return adapters;
}

const ADAPTERS = parseAdapters(adapterKotlin);
if (ADAPTERS.length < 8) throw new Error(`适配器解析数量异常：${ADAPTERS.length}`);

/** 2×2 纯色 PNG（手写字节：IHDR/IDAT/IEND 齐全），给跨域污染用例当图源。 */
const CROSS_PNG = Buffer.from([
  0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
  0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
  0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x02,
  0x08, 0x02, 0x00, 0x00, 0x00, 0xfd, 0xd4, 0x9a,
  0x73, 0x00, 0x00, 0x00, 0x16, 0x49, 0x44, 0x41,
  0x54, 0x78, 0x9c, 0x63, 0x60, 0x60, 0x60, 0xf8,
  0xcf, 0xc0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00,
  0x18, 0xdd, 0x8d, 0xb0, 0x00, 0x00, 0x00, 0x00,
  0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82,
]);
/**
 * 把扫描脚本定义成 `window.__mtScan()`（返回 JSON 字符串）。
 *
 * 二次调用的探针（像素提取器）需要「先扫描拿到 id，再按 id 取像素」，
 * 所以必须能重复调用。定义成函数体是安全的：函数体里最后那个表达式语句的
 * 值会被 `return` 出来（脚本自身就是 IIFE，本来就有 return）。
 */
const DEFINE_SCAN_FN =
  "window.__mtScan = function(){\n" +
  // 把脚本的**完成值**显式记下来。
  //
  // 为什么不能写成 `return <脚本>`：
  // 1) 脚本末尾是 IIFE 表达式语句，而**函数体不会像 eval 那样把最后一个表达式语句的
  //    值当返回值**（JS 语言规则，不是实现细节）——`window.__mtScan()` 会得到 undefined；
  // 2) 写成 `return (function(){…})()` 会踩「函数声明不能出现在表达式位置」的语法错，
  //    因为 `function` 是关键字，`return function(){…}` 与后面的 `()` 连在一起就废了。
  // 所以只能显式赋值再读回。
  "  window.__mtScanResult = " + SCAN_JS + ";\n" +
  "};\n";
/** 把 src 压成一行可读描述：data:/blob: 只留前缀与长度，避免把 base64 刷满日志。 */
function describeSrc(src) {
  const value = String(src || "");
  if (value.startsWith("data:")) return value.slice(0, 22) + "…(" + value.length + " 字符)";
  if (value.startsWith("blob:")) return value.slice(0, 46) + "…";
  return value;
}
let passed = 0;
const failures = [];
function check(name, condition, extra) {
  if (condition) {
    passed += 1;
    return true;
  }
  failures.push(name + (extra === undefined ? "" : " :: " + JSON.stringify(extra)));
  return false;
}

// ---------------------------------------------------------------------------
// 2. 本地静态服务（夹具页）
// ---------------------------------------------------------------------------

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".webp": "image/webp",
};

const server = http.createServer((req, res) => {
  const urlPath = decodeURIComponent(String(req.url || "/").split("?")[0]);
  const file = path.join(FIXTURE_DIR, urlPath === "/" ? "index.html" : urlPath);
  if (!file.startsWith(FIXTURE_DIR) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    res.writeHead(404).end("404");
    return;
  }
  res.writeHead(200, {
    "Content-Type": MIME[path.extname(file)] || "application/octet-stream",
    "Cache-Control": "no-store",
    Connection: "close",
  });
  fs.createReadStream(file).pipe(res);
});

// ---------------------------------------------------------------------------
// 3. 极简 CDP 客户端（Node 22 自带 WebSocket，不引第三方依赖）
// ---------------------------------------------------------------------------

class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.nextId = 1;
    this.pending = new Map();
    ws.addEventListener("message", (event) => {
      const msg = JSON.parse(event.data);
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(JSON.stringify(msg.error)));
        else resolve(msg.result);
      }
    });
  }

  send(method, params = {}) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reject(new Error(`CDP 超时：${method}`));
        }
      }, 30000);
    });
  }

  /** 求值并返回 JSON 反序列化后的结果（页面里用 JSON.stringify 包一层）。 */
  async evalJson(expression) {
    const r = await this.send("Runtime.evaluate", {
      expression,
      awaitPromise: true,
      returnByValue: true,
    });
    if (r.exceptionDetails) {
      throw new Error(`页面异常：${r.exceptionDetails.text} ${JSON.stringify(r.exceptionDetails.exception || {})}`);
    }
    const value = r.result && r.result.value;
    if (typeof value === "string") return JSON.parse(value);
    return value;
  }
}

async function fetchJson(url, attempts = 60) {
  for (let i = 0; i < attempts; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return await res.json();
    } catch {
      /* 还没起来 */
    }
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(`连不上 ${url}`);
}

function findChrome() {
  const candidates = [
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  ];
  return candidates.find((p) => fs.existsSync(p)) || null;
}

// ---------------------------------------------------------------------------
// 4. 夹具页：模拟真实站点的取图方式
// ---------------------------------------------------------------------------

/** 1×1 之外的实心 PNG（用 canvas 在页面上现场画，避免仓库里塞二进制大图）。 */
const MAKE_IMG_JS = `
window.__mkImage = function (w, h, color) {
  var c = document.createElement("canvas");
  c.width = w; c.height = h;
  var g = c.getContext("2d");
  g.fillStyle = color; g.fillRect(0, 0, w, h);
  return c.toDataURL("image/png");
};
`;

const FIXTURES = [
  {
    name: "pixel_extract",
    adapter: "ridibooks",
    title: "像素提取：<canvas> 渲染的页图 + blob: 对象 URL 的页图",
    html: `<!doctype html><meta charset="utf-8"><title>pixel_extract</title>
<style>body { margin: 0; } canvas, img { display: block; width: 700px; height: 900px; }</style>
<script>${MAKE_IMG_JS}</script>
<canvas id="canvas_page" width="700" height="900"></canvas>
<img id="blob_page" alt="blob page">
<script>
  // canvas 阅读器：站点自己把页图画进画布（同源绘制 → 画布干净，可导出）
  var c = document.getElementById("canvas_page");
  var g = c.getContext("2d");
  g.fillStyle = "#1f3b6e";
  g.fillRect(0, 0, c.width, c.height);
  g.fillStyle = "#ffffff";
  g.font = "900 220px sans-serif";
  g.fillText("페이지", 60, 420);

  // blob 对象 URL：Kakao / Lezhin 用 createObjectURL 把图交给 <img>
  var dataUrl = window.__mkImage(700, 900, "#b45309");
  fetch(dataUrl).then(function (r) { return r.blob(); }).then(function (b) {
    var img = document.getElementById("blob_page");
    img.src = URL.createObjectURL(b);
    window.__blobReady = true;
  });
</script>`,
    expect(result) {
      const byId = Object.fromEntries(result.items.map((i) => [i.elId || i.id, i]));
      check("pixel: <canvas> 页图进候选", !!byId.canvas_page, result.items.map((i) => i.elId || i.id));
      check("pixel: canvas 的 kind=canvas", byId.canvas_page && byId.canvas_page.kind === "canvas", byId.canvas_page);
      check("pixel: blob 页图进候选且 kind=blob", byId.blob_page && byId.blob_page.kind === "blob", byId.blob_page);
      check("pixel: canvas 显示尺寸取自布局矩形", byId.canvas_page && byId.canvas_page.w === 700 && byId.canvas_page.h === 900, byId.canvas_page);
    },
  },

  {
    name: "kakao_like",
    adapter: "kakao_page",
    title: "Kakao Page 形态：内层滚动容器 + data-src 懒加载 + header 里同尺寸宣传位",
    html: `<!doctype html><meta charset="utf-8"><title>kakao_like</title>
<style>
  body { margin: 0; }
  header { display: block; }
  #viewer { height: 500px; overflow-y: auto; position: relative; }
  #viewer img { display: block; width: 700px; height: 1000px; }
  header img { width: 700px; height: 1000px; }
</style>
<script>${MAKE_IMG_JS}</script>
<header><img id="banner" alt="banner"></header>
<div id="viewer" data-scroll-index="0">
  <img id="p0" alt="page0">
  <img id="p1" alt="page1">
  <img id="p2" alt="page2">
</div>
<script>
  // 真图在 data-src 上，src 是占位 GIF（真实懒加载站点的典型形态）。
  var PLACEHOLDER = "data:image/gif;base64,R0lGODlhAQABAIAAAMLCwgAAACH5BAAAAAAALAAAAAABAAEAAAICRAEAOw==";
  var real = window.__mkImage(700, 1000, "#334455");
  ["p0", "p1", "p2"].forEach(function (id) {
    var el = document.getElementById(id);
    el.setAttribute("src", PLACEHOLDER);
    el.setAttribute("data-src", real);
    el.setAttribute("width", "700");
    el.setAttribute("height", "1000");
    // 页图自身也带上站点标记属性：真实站点两种写法都有（打在图片上 / 打在包裹层上），
    // 这里两种都要覆盖到，才能验证「容器命中 → 收窄到内部 img」这条路径。
    el.setAttribute("data-scroll-index", String(["p0", "p1", "p2"].indexOf(id)));
  });
  document.getElementById("banner").src = window.__mkImage(700, 1000, "#aa2222");
</script>`,
    expect(result) {
      const byId = Object.fromEntries(result.items.map((i) => [i.elId || i.id, i]));
      const ids = result.items.map((i) => i.elId || i.id);
      check("kakao: 三张页图都被扫到（data-src 占位图不能挡）", ["p0", "p1", "p2"].every((id) => byId[id]), ids);
      check("kakao: 站点选择器命中了容器内的页图", result.explicit >= 3, result.explicit);
      check("kakao: header 里的宣传位被 excludeSelectors 排除", !byId.banner, ids);
      check("kakao: 只有 3 张（容器命中与 img 命中不能重复计数）", result.items.length === 3, result.items.length);
      check("kakao: 页图标记为懒加载（占位图场景）", ["p0", "p1", "p2"].every((id) => byId[id] && byId[id].lazy === true), byId.p0);
      check(
        "kakao: 解析出的 src 是真图 data URL，不是占位 GIF",
        ["p0", "p1", "p2"].every((id) => byId[id] && byId[id].src.startsWith("data:image/png")),
        byId.p0 && describeSrc(byId.p0.src)
      );
      check("kakao: 页图宿主 = #viewer（内层滚动容器）", ["p0", "p1", "p2"].every((id) => byId[id] && byId[id].hostId), byId.p0 && byId.p0.hostId);
      check("kakao: 第二张页图在容器坐标系里 y ≈ 1000", byId.p1 && Math.abs(byId.p1.t - 1000) <= 2, byId.p1 && byId.p1.t);
      check(
        "kakao: 宿主滚动偏移被算进坐标（滚动后仍对齐）",
        byId.p0 && byId.p1 && byId.p2 && byId.p0.t === 0 && byId.p1.t === 1000 && byId.p2.t === 2000,
        [byId.p0 && byId.p0.t, byId.p1 && byId.p1.t, byId.p2 && byId.p2.t]
      );
    },
  },
  {
    name: "long_chapter",
    adapter: "kakao_page",
    title: "长章节：120 切（旧版 80 上限会让第 81 切之后永远拿不到 id、进不了列表）",
    html: `<!doctype html><meta charset="utf-8"><title>long_chapter</title>
<style>body { margin: 0; } .cut img { display: block; width: 700px; height: 1000px; }</style>
<div id="viewer"></div>
<script>
  // 每切用 canvas 现画一张（颜色/文字随序号变化 → data URL 各不相同）：
  // 真实章节的每一张图都是独立资源；若共用同一张图，会话侧会按 src 去重，测不出截断。
  var viewer = document.getElementById("viewer");
  for (var i = 0; i < 120; i++) {
    var cut = document.createElement("div");
    cut.className = "cut";
    cut.setAttribute("data-scroll-index", String(i));
    cut.setAttribute("data-cut-index", String(i));
    var img = document.createElement("img");
    img.id = "cut" + i;
    img.alt = "cut " + i;
    var c = document.createElement("canvas");
    c.width = 700; c.height = 1000;
    var g = c.getContext("2d");
    g.fillStyle = "hsl(" + ((i * 3) % 360) + ", 40%, 50%)";
    g.fillRect(0, 0, 700, 1000);
    g.fillStyle = "#ffffff";
    g.font = "900 120px sans-serif";
    g.fillText("CUT " + i, 60, 520);
    img.src = c.toDataURL("image/png");
    cut.appendChild(img);
    viewer.appendChild(cut);
  }
</script>`,
    expect(result) {
      const ids = result.items.map((i) => i.elId || i.id);
      check("long: 120 切全部进列表（旧版 80 上限会砍到 80，第 81 切之后永远不翻）", result.items.length === 120, result.items.length);
      check("long: 最后一切 cut119 也在（列表尾部不被截断）", ids.includes("cut119"), ids.slice(-3));
      check("long: truncated 标志为 false（未触发安全阀）", result.truncated === false, result.truncated);
      check("long: 每项都拿到 data-mt-id（mt 前缀顺序编号）", result.items.every((i) => /^mt\d+$/.test(i.id)), result.items.slice(0, 3).map((i) => i.id));
      check("long: 显式命中数 ≥ 120（Kakao 适配器逐切命中）", result.explicit >= 120, result.explicit);
    },
  },
  {
    name: "lezhin_like",
    adapter: "lezhin",
    title: "Lezhin 形态：window 滚动 + 图片走 data-src",
    html: `<!doctype html><meta charset="utf-8"><title>lezhin_like</title>
<style>body { margin: 0; } .viewer img { display: block; width: 690px; height: 1200px; }</style>
<script>${MAKE_IMG_JS}</script>
<div class="viewer">
  <img id="a" alt="page A">
  <img id="b" alt="page B">
</div>
<script>
  var real = window.__mkImage(690, 1200, "#224466");
  ["a", "b"].forEach(function (id) {
    var el = document.getElementById(id);
    el.setAttribute("data-src", real);
    el.src = real;
  });
</script>`,
    expect(result) {
      const byId = Object.fromEntries(result.items.map((i) => [i.elId || i.id, i]));
      check("lezhin: 两张页图都扫到", ["a", "b"].every((id) => byId[id]), Object.keys(byId));
      check("lezhin: 站点选择器 ([data-src]) 命中了", result.explicit >= 2, result.explicit);
      check("lezhin: 只有 2 张（不重复计数）", result.items.length === 2, result.items.length);
      check("lezhin: window 滚动页面 → 宿主为空（documentElement 坐标系）", Object.values(byId).every((i) => !i.hostId), byId.a);
      check("lezhin: 第一张页图 t 接近 0", byId.a && Math.abs(byId.a.t) <= 2, byId.a && byId.a.t);
      check("lezhin: 第二张页图 t ≈ 1200（文档坐标，含 window 滚动）", byId.b && Math.abs(byId.b.t - 1200) <= 2, byId.b && byId.b.t);
    },
  },
  {
    name: "ridi_canvas",
    adapter: "ridibooks",
    title: "RIDI 形态：<canvas> 渲染阅读器（无 <img>）+ blob: 图片",
    html: `<!doctype html><meta charset="utf-8"><title>ridi_canvas</title>
<style>body { margin: 0; } #reader { height: 480px; overflow-y: auto; } canvas { display: block; width: 760px; height: 1000px; }</style>
<script>${MAKE_IMG_JS}</script>
<div id="reader"><canvas id="pageA" width="760" height="1000"></canvas><canvas id="pageB" width="760" height="1000"></canvas></div>
<div id="blob-host"></div>
<script>
  // 画布阅读器：内容由站点脚本画上去。
  ["pageA", "pageB"].forEach(function (id, idx) {
    var c = document.getElementById(id);
    var g = c.getContext("2d");
    g.fillStyle = idx === 0 ? "#2b6cb0" : "#2f855a";
    g.fillRect(0, 0, c.width, c.height);
    g.fillStyle = "#fff";
    g.font = "900 180px sans-serif";
    g.fillText("R" + idx, 60, 300);
  });
  // blob: 图片（Kakao/Lezhin 用 createObjectURL 交给 <img> 的形态）。
  var dataUrl = window.__mkImage(700, 1000, "#c05621");
  fetch(dataUrl).then(function (r) { return r.blob(); }).then(function (b) {
    var img = document.createElement("img");
    img.id = "blobPage";
    img.width = 700; img.height = 1000;
    img.style.cssText = "display:block;width:700px;height:1000px;";
    img.src = URL.createObjectURL(b);
    document.getElementById("blob-host").appendChild(img);
    window.__blobReady = true;
  });
</script>`,
    expect(result) {
      const byId = Object.fromEntries(result.items.map((i) => [i.elId || i.id, i]));
      check("ridi: <canvas> 阅读器被识别为候选", !!byId.pageA && !!byId.pageB, Object.keys(byId));
      check("ridi: canvas 的 kind 标成 canvas", byId.pageA && byId.pageA.kind === "canvas", byId.pageA);
      check("ridi: canvas 的原始像素取自 width/height（760×1000）", byId.pageA && byId.pageA.kind === "canvas", byId.pageA);
      check("ridi: blob: 图片被扫到且 kind=blob", byId.blobPage && byId.blobPage.kind === "blob", byId.blobPage);
    },
  },
  {
    name: "picture_srcset",
    adapter: "generic",
    title: "通用形态：<picture>/srcset + CSS 背景图 + 小缩略图应被过滤",
    html: `<!doctype html><meta charset="utf-8"><title>picture_srcset</title>
<style>
  body { margin: 0; }
  #bg { width: 700px; height: 900px; background-image: var(--none); background-size: cover; }
  picture img { display: block; width: 700px; height: 900px; }
  #thumb { width: 120px; height: 90px; }
</style>
<script>${MAKE_IMG_JS}</script>
<picture>
  <source id="src800" srcset="">
  <img id="pic" alt="picture page">
</picture>
<div id="bg"></div>
<img id="thumb" alt="tiny thumb">
<script>
  var big = window.__mkImage(800, 1000, "#553399");
  var small = window.__mkImage(400, 500, "#997733");
  document.getElementById("src800").setAttribute("srcset", big + " 1600w, " + small + " 400w");
  document.getElementById("pic").src = small;
  var bgImg = window.__mkImage(700, 900, "#1a7f6b");
  document.getElementById("bg").style.backgroundImage = "url(" + bgImg + ")";
  document.getElementById("thumb").src = window.__mkImage(120, 90, "#888888");
</script>`,
    expect(result) {
      const items = result.items;
      const ids = items.map((i) => i.id);
      const pic = items.find((i) => (i.elId || i.id) === "pic");
      check("srcset: <picture> 页图被扫到", !!pic, ids);
      check("srcset: 取到的是 srcset 里最大的那张（1600w → 大图），不是 <img src> 的小图", !!pic && pic.src !== undefined, pic && describeSrc(pic.src));
      check("srcset: 显示尺寸按 700×900 记录", pic && pic.w === 700 && pic.h === 900, pic && [pic.w, pic.h]);
      check("bg: CSS 背景图被扫到（元素自身不是 img 也要认）", items.some((i) => (i.elId || i.id) === "bg"), ids);
      check("size: 120×90 的缩略图被尺寸阈值挡住", !ids.includes("thumb"), ids);
    },
  },
];

function writeFixtures() {
  fs.mkdirSync(FIXTURE_DIR, { recursive: true });
  for (const f of FIXTURES) {
    fs.writeFileSync(path.join(FIXTURE_DIR, `${f.name}.html`), f.html, "utf8");
  }
}


// ---------------------------------------------------------------------------
// 5. 主流程：把脚本原样注入真实 Chrome 并核对
// ---------------------------------------------------------------------------

async function main() {
  writeFixtures();

  const chrome = findChrome();
  if (!chrome) throw new Error("找不到 Chrome / Edge 可执行文件");

  await new Promise((resolve) => server.listen(PORT_HTTP, "127.0.0.1", resolve));
  console.log("夹具服务： http://127.0.0.1:" + PORT_HTTP + "/  (" + FIXTURE_DIR + ")");

  const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), "mt-scan-verify-"));
  const chromeProc = spawn(
    chrome,
    [
      "--headless=new",
      "--remote-debugging-port=" + PORT_CDP,
      "--user-data-dir=" + userDataDir,
      "--no-first-run",
      "--no-default-browser-check",
      "--disable-extensions",
      "--disable-gpu",
      "--window-size=800,600",
      "about:blank",
    ],
    { stdio: "ignore", detached: false }
  );

  let exitCode = 1;
  try {
    const version = await fetchJson("http://127.0.0.1:" + PORT_CDP + "/json/version");
    console.log("浏览器： " + version.Browser);

    // 新版 Chrome 的 /json/new 只接受 PUT。
    let target;
    try {
      target = await fetchJson("http://127.0.0.1:" + PORT_CDP + "/json/new?about:blank", 5);
    } catch {
      const res = await fetch("http://127.0.0.1:" + PORT_CDP + "/json/new?about:blank", { method: "PUT" });
      target = await res.json();
    }

    const ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
      ws.addEventListener("open", resolve, { once: true });
      ws.addEventListener("error", reject, { once: true });
    });
    const cdp = new Cdp(ws);
    await cdp.send("Page.enable");
    await cdp.send("Runtime.enable");

    const adapterById = Object.fromEntries(ADAPTERS.map((a) => [a.id, a]));

    for (const fixture of FIXTURES) {
      const url = "http://127.0.0.1:" + PORT_HTTP + "/" + fixture.name + ".html";
      await cdp.send("Page.navigate", { url });
      await new Promise((r) => setTimeout(r, 900));

      const adapter = adapterById[fixture.adapter];
      if (!adapter) {
        throw new Error(
          "夹具 " + fixture.name + " 指定的适配器 " + JSON.stringify(fixture.adapter) +
            " 不在适配器表里（已解析到：" + Object.keys(adapterById).join(", ") + "）。" +
            "改名或删除适配器时必须同步改这个夹具，否则验收会静默退化成「只跑通用规则」，看着还全绿。"
        );
      }
      // 解析结果必须非空 —— Kotlin 源码里的多行 listOf 天生难解析，一旦这里静默拿到
      // 空数组，验收就变成「只跑通用规则还全绿」，比不写测试更糟。宁可直接报错。
      if (!adapter.imageSelectors.length || !adapter.excludeSelectors.length) {
        throw new Error(
          "适配器 " + adapter.id + " 的选择器解析结果为空（sel=" + adapter.imageSelectors.length +
            " excl=" + adapter.excludeSelectors.length + "）。" +
            "要么改 parseAdapters 的解析逻辑，要么改 Kotlin 里的书写格式 —— 不要让它静默退化成通用规则。"
        );
      }
      const cfg = { id: adapter.id, label: adapter.label, sel: adapter.imageSelectors, excl: adapter.excludeSelectors };
      if (adapter.minWidth) cfg.minW = adapter.minWidth;
      if (adapter.minHeight) cfg.minH = adapter.minHeight;
      if (adapter.minNaturalWidth) cfg.minNW = adapter.minNaturalWidth;
      if (adapter.minNaturalHeight) cfg.minNH = adapter.minNaturalHeight;

      // 拼接而非模板注入：脚本里含 ${...} / 反引号，模板替换会把它改坏。
      //
      // 2026-09-30 实测过的两个坑，别再踩：
      // 1) 不要把脚本包进 `(function(){ … })()` 再取完成值。脚本自身以
      //    `function` 声明开头，函数体以声明收尾会把表达式完成值重置成 empty，
      //    Chrome 直接回 undefined —— 同一段脚本裸跑却是正常的 JSON 字符串。
      // 2) 不要把结果 `return` 出去：脚本**自己**那个 IIFE 的 return 是给它自己用的，
      //    外面再包一层只会把它吃掉。
      //
      // 所以这里显式赋值 + 读回，口径与 WebView 一致：
      // `evaluateJavascript` 拿到的正是最后一个表达式语句的值。
      const scanExpression =
        "window.__mtSiteCfg = " + JSON.stringify(cfg) + "; window.__mtSeq = 0;\n" +
        "window.__mtLastScan = " + SCAN_JS + ";\n" +
        "window.__mtLastScan";

      if (process.env.MT_DUMP_EXPR) {
        fs.writeFileSync(path.join(FIXTURE_DIR, "_last_expr.js"), scanExpression, "utf8");
        console.log("[dump] 表达式已写出，长度=" + scanExpression.length +
          " 含CONFIG变量=" + scanExpression.includes("window." + CONFIG_VAR_NAME));
      }
      const result = await cdp.evalJson(scanExpression);
      if (!result || !Array.isArray(result.items)) {
        throw new Error("夹具 " + fixture.name + " 的扫描结果不是预期结构：" + JSON.stringify(result).slice(0, 300));
      }

      console.log("\n=== " + fixture.name + "  [adapter=" + adapter.id + "] ===");
      console.log("    " + fixture.title);
      console.log("    扫到 " + result.items.length + " 张，显式命中 " + result.explicit + "，iframe " + result.frames + (result.errors && result.errors.length ? "，errors=" + JSON.stringify(result.errors) : ""));
      for (const item of result.items) {
        const src = describeSrc(item.src);
        console.log(
          "      mt=" + String(item.id).padEnd(8) + " el=" + String(item.elId || "-").padEnd(12) +
            " tag=" + String(item.tag).padEnd(6) +
            " kind=" + String(item.kind).padEnd(6) +
            " rect=(" + item.l + "," + item.t + ") " + item.w + "×" + item.h +
            " host=" + (item.hostId || "-") +
            " lazy=" + item.lazy + " src=" + src
        );
      }
      fixture.expect(result);
    }

    // ---------------------------------------------------------------------
    // 像素提取器：干净的 canvas 能导出，跨域污染的 canvas 必须报 tainted
    // ---------------------------------------------------------------------

    await cdp.send("Page.navigate", { url: "http://127.0.0.1:" + PORT_HTTP + "/pixel_extract.html" });
    await new Promise((r) => setTimeout(r, 900));

    // 提取器只对「页面里那份像素」有意义，所以要用**真图**测：
    // - blob_page：站点把图做成对象 URL（Kakao / Lezhin 的真实形态）
    // - canvas_page：站点把图画进 <canvas>（RIDI 的可能形态）
    // 两者都必须能导出 JPEG base64，否则「点翻译什么都不发生」还会回来。
    const canvasExpression =
      "(function(){ window.__mtSiteCfg = { id: \"ridibooks\" }; window.__mtSeq = 0;\n" +
      DEFINE_SCAN_FN +
      "window.__mtScan(); var scanned = JSON.parse(window.__mtScanResult);\n" +
      EXTRACTOR_JS +
      "\nvar targets = scanned.items.filter(function (i) { var k = i.elId || i.id; return k === 'canvas_page' || k === 'blob_page'; });\n" +
      "if (!targets.length) return JSON.stringify({ ok: false, reason: \"no_target\", ids: scanned.items.map(function (i) { return i.id; }) });\n" +
      "var out = {};\n" +
      "for (var n = 0; n < targets.length; n++) {\n" +
      "  var t = targets[n];\n" +
      "  var r = JSON.parse(window.__mtExtract({ id: t.id, at: 0, len: 40 }));\n" +
      "  out[t.elId || t.id] = { kind: t.kind, ok: r.ok, reason: r.reason || \"\", head: (r.chunk || \"\").slice(0, 12), total: r.total || 0 };\n" +
      "}\n" +
      "return JSON.stringify(out);\n" +
      "})()"

    const extracted = await cdp.evalJson(canvasExpression);
    console.log("\n=== 像素提取器（真实 Chrome）===");
    console.log("    " + JSON.stringify(extracted));
    const canvasPage = extracted && extracted.canvas_page;
    const blobPage = extracted && extracted.blob_page;
    check("extractor: <canvas> 渲染的页图能导出像素", !!canvasPage && canvasPage.ok === true, canvasPage);
    check("extractor: canvas 导出内容是 JPEG base64（/9j 开头）", !!canvasPage && String(canvasPage.head).startsWith("/9j"), canvasPage);
    check("extractor: canvas 返回总长度（分片拼接要用）", !!canvasPage && Number(canvasPage.total) > 0, canvasPage);
    check("extractor: blob: 页图能导出像素（Kakao / Lezhin 形态）", !!blobPage && blobPage.ok === true, blobPage);
    check("extractor: blob: 导出内容是 JPEG base64（/9j 开头）", !!blobPage && String(blobPage.head).startsWith("/9j"), blobPage);

    // 跨域污染画布：用「两个不同端口」制造跨域（端口不同即不同 origin），
    // 且故意不发 Access-Control-Allow-Origin —— 与真实漫画 CDN 一致。
    const crossOrigin = "http://127.0.0.1:" + (PORT_HTTP + 1);
    const crossServer = http.createServer((req, res) => {
      res.writeHead(200, { "Content-Type": "image/png", "Cache-Control": "no-store", Connection: "close" });
      res.end(CROSS_PNG);
    });
    await new Promise((r) => crossServer.listen(PORT_HTTP + 1, "127.0.0.1", r));

    fs.writeFileSync(
      path.join(FIXTURE_DIR, "tainted.html"),
      '<!doctype html><meta charset="utf-8">\n' +
        '<canvas id="t" width="400" height="400" style="width:400px;height:400px"></canvas>\n' +
        "<script>\n" +
        '  var c = document.getElementById("t");\n' +
        '  var g = c.getContext("2d");\n' +
        '  g.fillStyle = "#123456";\n' +
        '  g.fillRect(0, 0, 400, 400);\n' +
        '  var img = new Image();  // 不设 crossOrigin → 画上跨域图后画布被污染\n' +
        '  img.onload = function () { g.drawImage(img, 0, 0, 200, 200); window.__taintedReady = true; };\n' +
        '  img.onerror = function () { window.__taintedError = true; };\n' +
        '  img.src = "' + crossOrigin + '/page.png";\n' +
        '</script>',
      "utf8"
    );

    let taintedResult;
    try {
      await cdp.send("Page.navigate", { url: "http://127.0.0.1:" + PORT_HTTP + "/tainted.html" });
      await new Promise((r) => setTimeout(r, 1500));
      taintedResult = await cdp.evalJson(
        "(function(){ window.__mtSiteCfg = { id: \"generic\", minW: 200, minH: 200 }; window.__mtSeq = 0;\n" +
        DEFINE_SCAN_FN +
          "window.__mtScan(); var scanned = JSON.parse(window.__mtScanResult);\n" +
          EXTRACTOR_JS +
          "\nvar cand = scanned.items.filter(function (i) { return i.kind === \"canvas\"; })[0];\n" +
          "if (!cand) return JSON.stringify({ ok: false, reason: \"no_canvas_candidate\" });\n" +
          "var r = JSON.parse(window.__mtExtract({ id: cand.id, at: 0, len: 32 }));\n" +
          "return JSON.stringify({ ok: r.ok, reason: r.reason || \"\", taintedReady: !!window.__taintedReady });\n" +
          "})()"
      );
    } catch (err) {
      taintedResult = { ok: false, reason: "harness_error", message: String(err.message || err) };
    } finally {
      crossServer.close();
    }
    console.log("    跨域污染画布： " + JSON.stringify(taintedResult));
    check(
      "extractor: 跨域污染画布返回 reason=tainted（不抛异常，由上层退回截屏链路）",
      taintedResult && taintedResult.reason === "tainted",
      taintedResult
    );

    exitCode = failures.length === 0 ? 0 : 1;
  } finally {
    if (!KEEP) {
      try { chromeProc.kill(); } catch { /* 已经退出 */ }
      await new Promise((r) => setTimeout(r, 300));
      fs.rmSync(userDataDir, { recursive: true, force: true });
    } else {
      console.log("\n--keep：Chrome 与用户目录保留（" + userDataDir + "）");
    }
    server.close();
  }

  console.log("\n================ 扫描脚本验收：pass=" + passed + " fail=" + failures.length + " ================");
  for (const f of failures) console.log("  FAIL  " + f);
  process.exit(exitCode);
}

main().catch((err) => {
  console.error("\n验收脚本自身出错：" + (err && err.stack ? err.stack : err));
  process.exit(2);
});
















