"use strict";
/**
 * 真实站点取图体检（2026-09-30）。
 *
 * 为什么需要它：`scan-script-verify.mjs` 用的是**手写夹具**，只能证明「脚本对某类
 * 结构有效」；站点适配规则到底在真实 DOM 上命中没有，只有在真页面上跑一遍才知道。
 * 这个脚本把 Kotlin 里那份 `SCAN_JS` 原样注入真实页面（无头 Chrome，不需要登录），
 * 打印：适配器 id、候选数、每张图的 kind/尺寸/坐标、以及淘汰画像。
 *
 * 用法：
 *   node scripts/real-site-probe.mjs
 *   node scripts/real-site-probe.mjs --url https://www.lezhin.com/ko/comic/gamesal/1
 *   node scripts/real-site-probe.mjs --keep      # 保留 Chrome 便于手动看
 */

import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";

const ROOT = process.cwd();
const SCAN_SRC = path.join(ROOT, "app", "src", "main", "java", "com", "manga", "translator", "app", "BrowserScanScript.kt");
const ADAPTER_SRC = path.join(ROOT, "core-store", "src", "main", "java", "com", "manga", "translator", "store", "BrowserSiteAdapter.kt");
const PORT_CDP = 9360;
const KEEP = process.argv.includes("--keep");
/** 用真实 Chrome（非 headless）跑：RIDI 有 Cloudflare 挑战，headless 会被直接判定为机器人。 */
const HEADLESS = process.argv.includes("--headless");
/** 复用真实用户数据目录，带上已有 cookie（站点常常只对「看起来正常」的会话放行）。 */
const USE_REAL_PROFILE = !process.argv.includes("--clean-profile");
const ONLY = (() => {
  const i = process.argv.indexOf("--url");
  return i >= 0 ? process.argv[i + 1] : null;
})();

const TARGETS = ONLY
  ? [{ name: "自定义", url: ONLY }]
  : [
      { name: "Lezhin", url: "https://www.lezhin.com/ko/comic/gamesal/1" },
      { name: "RIDI", url: "https://ridibooks.com/books/5208000065/view" },
    ];

// ---------------------------------------------------------------------------
// 从 Kotlin 抠出脚本与适配器表（与 scan-script-verify.mjs 同一套办法）
// ---------------------------------------------------------------------------

function extractRawString(source, declaration) {
  const pattern = new RegExp(declaration.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + '\\s*:\\s*String\\s*=\\s*"""');
  const m = pattern.exec(source);
  if (!m) throw new Error("找不到声明：" + declaration);
  const open = source.indexOf('"""', m.index);
  const close = source.indexOf('"""', open + 3);
  return source.slice(open + 3, close);
}

const scanKotlin = fs.readFileSync(SCAN_SRC, "utf8");
const adapterKotlin = fs.readFileSync(ADAPTER_SRC, "utf8");
const CONFIG_VAR = (() => {
  const m = /const val CONFIG_VAR = "([^"]+)"/.exec(scanKotlin);
  if (!m) throw new Error("抽不到 CONFIG_VAR");
  return m[1];
})();
const SCAN_JS = extractRawString(scanKotlin, "val SCAN_JS")
  .replace("window.$CONFIG_VAR", "window." + CONFIG_VAR);

try {
  new Function(SCAN_JS);
  console.log("脚本语法自检通过（" + SCAN_JS.length + " 字符）");
} catch (err) {
  throw new Error("SCAN_JS 语法错误：" + err.message);
}

/** 解析适配器表：切块用括号配平，避免 listOf 里的括号把块截断。 */
function parseAdapters(kotlin) {
  const adapters = [];
  const re = /BrowserSiteAdapter\(/g;
  let m;
  while ((m = re.exec(kotlin)) !== null) {
    const tail = kotlin.slice(m.index + m[0].length);
    const end = /\n {4}\)/.exec(tail);
    const body = end ? tail.slice(0, end.index) : tail;
    const pick = (name) => {
      const r = body.match(new RegExp(name + "\\s*=\\s*\"([^\"]*)\""));
      return r ? r[1] : null;
    };
    const list = (name) => {
      const marker = new RegExp(name + "\\s*=\\s*listOf\\(");
      const hit = marker.exec(body);
      if (!hit) return [];
      let depth = 1;
      let i = hit.index + hit[0].length;
      const from = i;
      while (i < body.length && depth > 0) {
        if (body[i] === "(") depth += 1;
        else if (body[i] === ")") depth -= 1;
        i += 1;
      }
      return [...body.slice(from, i - 1).matchAll(/"([^"]*)"/g)].map((x) => x[1]);
    };
    const num = (name) => {
      const r = body.match(new RegExp(name + "\\s*=\\s*(\\d+)"));
      return r ? Number(r[1]) : null;
    };
    const id = pick("id");
    if (!id) continue;
    adapters.push({
      id,
      label: pick("label"),
      hosts: list("hostPatterns"),
      sel: list("imageSelectors"),
      excl: list("excludeSelectors"),
      minW: num("minWidth"),
      minH: num("minHeight"),
      minNW: num("minNaturalWidth"),
      minNH: num("minNaturalHeight"),
    });
  }
  return adapters;
}

const ADAPTERS = parseAdapters(adapterKotlin);
if (ADAPTERS.length < 8) throw new Error("适配器解析数量异常：" + ADAPTERS.length);

/** 与 Kotlin `BrowserSiteAdapter.matchScore` 同构：支持精确 / `*.x` / `*x`，取最长命中。 */
function matchScore(adapter, host) {
  const h = String(host || "").trim().toLowerCase().split(":")[0];
  if (!h) return -1;
  let best = -1;
  for (const raw of adapter.hosts) {
    const p = String(raw).trim().toLowerCase();
    let hit = false;
    if (p.startsWith("*.")) {
      const base = p.slice(2);
      hit = h.length > base.length + 1 && h.endsWith("." + base);
    } else if (p.startsWith("*")) {
      const base = p.slice(1);
      hit = h === base || (h.length > base.length + 1 && h.endsWith("." + base));
    } else {
      hit = h === p;
    }
    if (hit && p.length > best) best = p.length;
  }
  return best;
}

function resolveAdapter(host) {
  let best = null;
  let bestScore = -1;
  for (const a of ADAPTERS) {
    const s = matchScore(a, host);
    if (s > bestScore) {
      bestScore = s;
      best = a;
    }
  }
  return best || { id: "generic", label: "通用", sel: [], excl: [], minW: null, minH: null, minNW: null, minNH: null };
}

function configFor(adapter) {
  const cfg = { id: adapter.id, label: adapter.label, sel: adapter.sel, excl: adapter.excl };
  if (adapter.minW) cfg.minW = adapter.minW;
  if (adapter.minH) cfg.minH = adapter.minH;
  if (adapter.minNW) cfg.minNW = adapter.minNW;
  if (adapter.minNH) cfg.minNH = adapter.minNH;
  return cfg;
}

// ---------------------------------------------------------------------------
// 极简 CDP
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
          reject(new Error("CDP 超时：" + method));
        }
      }, 45000);
    });
  }

  async evalJson(expression) {
    const r = await this.send("Runtime.evaluate", { expression, returnByValue: true });
    if (r.exceptionDetails) {
      throw new Error("页面异常：" + r.exceptionDetails.text + " " +
        JSON.stringify(r.exceptionDetails.exception || {}).slice(0, 300));
    }
    const v = r.result && r.result.value;
    return typeof v === "string" ? JSON.parse(v) : v;
  }
}

async function fetchJson(url, attempts = 80) {
  for (let i = 0; i < attempts; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return await res.json();
    } catch {
      /* 还没起来 */
    }
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error("连不上 " + url);
}

function findChrome() {
  return [
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    "/usr/bin/google-chrome",
  ].find((p) => fs.existsSync(p));
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------

const chrome = findChrome();
if (!chrome) throw new Error("找不到 Chrome / Edge");

// 真实 profile 才有站点的 cookie；没有就退回临时目录。
const realProfile = path.join(process.env.LOCALAPPDATA || os.homedir(), "Google", "Chrome", "User Data");
const userDataDir = USE_REAL_PROFILE && fs.existsSync(realProfile)
  ? realProfile
  : fs.mkdtempSync(path.join(os.tmpdir(), "mt-real-probe-"));
const tempProfile = userDataDir !== realProfile;

const chromeArgs = [
  "--remote-debugging-port=" + PORT_CDP,
  "--user-data-dir=" + userDataDir,
  "--no-first-run",
  "--no-default-browser-check",
  "--disable-extensions",
  "--window-size=1000,1500",
  "about:blank",
];
if (HEADLESS) chromeArgs.splice(0, 0, "--headless=new");
if (tempProfile) chromeArgs.splice(chromeArgs.indexOf("--window-size=1000,1500"), 0, "--disable-gpu");

console.log("模式：" + (HEADLESS ? "headless" : "真实窗口") +
  "，profile：" + (tempProfile ? "临时目录（无 cookie）" : "真实用户目录（带 cookie）"));
const chromeProc = spawn(chrome, chromeArgs, { stdio: "ignore" });

let exitCode = 0;
try {
  const version = await fetchJson("http://127.0.0.1:" + PORT_CDP + "/json/version");
  console.log("浏览器：" + version.Browser);

  const target = await (async () => {
    try {
      return await fetchJson("http://127.0.0.1:" + PORT_CDP + "/json/new?about:blank", 5);
    } catch {
      const res = await fetch("http://127.0.0.1:" + PORT_CDP + "/json/new?about:blank", { method: "PUT" });
      return await res.json();
    }
  })();

  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    ws.addEventListener("open", resolve, { once: true });
    ws.addEventListener("error", reject, { once: true });
  });
  const cdp = new Cdp(ws);
  await cdp.send("Page.enable");
  await cdp.send("Runtime.enable");

  for (const t of TARGETS) {
    console.log("\n" + "=".repeat(78));
    console.log("目标：" + t.name + "  " + t.url);
    console.log("=".repeat(78));

    let host = "";
    try {
      host = new URL(t.url).host;
    } catch {
      /* 忽略 */
    }
    const adapter = resolveAdapter(host);
    console.log("命中适配器：" + adapter.id + "（" + adapter.label + "），取图选择器 " + adapter.sel.length + " 条");

    try {
      await cdp.send("Page.navigate", { url: t.url });
    } catch (err) {
      console.log("! 导航失败：" + err.message);
      exitCode = 1;
      continue;
    }

    // 真实站点都是「异步加载 + 懒加载」：
    // - 首屏给一个「请稍候…」的壳，阅读器要几百毫秒到几秒后才建出来；
    // - 页图只在**真正滚进视口**时才请求 —— `window.scrollTo` 对「内层滚动容器」
    //   完全无效，必须往元素上派发真实 wheel 事件（2026-09-30 实测踩到）。
    // 所以这里**轮询等页图出现**而不是死等固定秒数；同时把拦截页（Cloudflare 挑战 /
    // 登录墙）显式认出来 —— 那是「探针拿不到内容」，不是「站点没有图」。
    const probePage = async () => {
      return cdp.evalJson(
        "(function(){try{var body=document.body?document.body.innerText:'';" +
          "var gate=null;" +
          "if(/安全验证|security verification|Just a moment|Checking your browser/i.test(body))gate='cloudflare_challenge';" +
          "else if(/로그인|로그인이 필요|login required/i.test(body))gate='login_wall';" +
          "var imgs=document.images||[];var loaded=0;for(var i=0;i<imgs.length;i++){if(imgs[i].naturalWidth>0)loaded++;}" +
          "return JSON.stringify({images:imgs.length,loaded:loaded," +
          "canvases:document.querySelectorAll('canvas').length,gate:gate," +
          "excerpt:body.replace(/\\s+/g,' ').slice(0,160)});}catch(e){return JSON.stringify({error:String(e&&e.message||e)});}})()"
      );
    };

    const settleMs = 8000;
    console.log("等待 " + settleMs + "ms 让阅读器建出来…");
    await new Promise((r) => setTimeout(r, settleMs));

    const scrollJs = (dy) =>
      "(function(){try{" +
      "var el=document.elementFromPoint(Math.floor(innerWidth/2),Math.floor(innerHeight/2))||document.body;" +
      "for(var i=0;i<6;i++){el.dispatchEvent(new WheelEvent('wheel',{deltaY:" + dy + ",bubbles:true,cancelable:true}));}" +
      "window.scrollBy(0," + dy + ");" +
      "if(el.scrollTop!==undefined)el.scrollTop+=" + dy + ";" +
      "var sc=document.scrollingElement;if(sc)sc.scrollTop+=" + dy + ";" +
      "return JSON.stringify({scrollY:Math.round(window.scrollY),hostTop:Math.round(el.scrollTop||0)});" +
      "}catch(e){return JSON.stringify({error:String(e&&e.message||e)});}})()";

    // 轮询：滚动 → 看页图有没有真的加载出来。最多 12 轮（约 30s）。
    let lastProbe = null;
    let gate = null;
    for (let round = 0; round < 12; round++) {
      const st = await cdp.evalJson(scrollJs(900)).catch(() => null);
      if (round === 0 || round === 11) {
        console.log("滚动第 " + (round + 1) + " 轮：" + JSON.stringify(st));
      }
      await new Promise((r) => setTimeout(r, 2200));
      lastProbe = await probePage().catch(() => null);
      if (lastProbe && lastProbe.gate) {
        gate = lastProbe.gate;
        console.log("⚠ 被拦住：gate=" + gate + " —— " + lastProbe.excerpt);
        break;
      }
      if (lastProbe && lastProbe.loaded > 0) {
        console.log("第 " + (round + 1) + " 轮后已有 " + lastProbe.loaded + " 张图加载完成");
        break;
      }
    }
    if (!gate && lastProbe) {
      console.log("轮询结束时：images=" + lastProbe.images + " loaded=" + lastProbe.loaded +
        " canvases=" + lastProbe.canvases);
    }

    // 页面画像（无论有没有命中都要有，这才是排查依据）
    const profile = await cdp.evalJson(
      "(function(){try{var imgs=document.images||[];var blob=0;for(var i=0;i<imgs.length;i++){" +
        "if(String(imgs[i].currentSrc||imgs[i].src||'').indexOf('blob:')===0)blob++;}" +
        "return JSON.stringify({title:document.title,url:location.href,images:imgs.length," +
        "canvases:document.querySelectorAll('canvas').length,blobImages:blob," +
        "frames:document.querySelectorAll('iframe').length," +
        "scrollHosts:document.querySelectorAll('*').length" +
        "});}catch(e){return JSON.stringify({error:String(e&&e.message||e)});}})()"
    );
    console.log("页面画像：" + JSON.stringify(profile));
    // 登录墙/付费墙要显式说明：那不是「取图失败」，是页面根本没给内容。
    const gate = await cdp.evalJson(
      "(function(){var t=(document.body?document.body.innerText:'').slice(0,4000);" +
        "var pats=['로그인','로그인이','login','Login','购买','구매','대여','请稍候','잠시','결제','성인','19세'];" +
        "var hits=[];for(var i=0;i<pats.length;i++){if(t.indexOf(pats[i])>=0)hits.push(pats[i]);}" +
        "return JSON.stringify({hits:hits,excerpt:t.replace(/\\s+/g,' ').slice(0,220)});})()"
    ).catch(() => null);
    if (gate) console.log("页面文本线索：" + JSON.stringify(gate));

    // 真实运行扫描脚本
    const cfg = configFor(adapter);
    let scan;
    try {
      scan = await cdp.evalJson(
        "window." + CONFIG_VAR + " = " + JSON.stringify(cfg) + "; window.__mtSeq = 0;\n" + SCAN_JS
      );
    } catch (err) {
      console.log("! 扫描脚本在真实页面上失败：" + err.message);
      exitCode = 1;
      continue;
    }

    console.log("扫描结果：候选 " + scan.items.length + " 张，显式命中 " + scan.explicit +
      "，iframe " + scan.frames + (scan.errors && scan.errors.length ? "，errors=" + JSON.stringify(scan.errors) : ""));
    scan.items.slice(0, 20).forEach((it, i) => {
      const src = String(it.src || "");
      const shown = src.startsWith("data:") ? src.slice(0, 20) + "…(" + src.length + ")" : src.slice(0, 90);
      console.log(
        "  [" + i + "] id=" + it.id + " el=" + (it.elId || "-") + " tag=" + it.tag + " kind=" + it.kind +
        " " + it.w + "×" + it.h + " @" + it.l + "," + it.t +
        " host=" + (it.hostId || "-") + " lazy=" + it.lazy + " explicit=" + it.explicit
      );
      console.log("      src=" + shown);
    });
    if (scan.items.length === 0) {
      console.log("  ⚠ 一张都没扫到 —— 这就是「点翻译没反应」的现场");
    }

    // 命中的选择器逐条核对（最有用的一条：哪条规则失效了）
    const selCheck = await cdp.evalJson(
      "(function(){var out=[];var sels=" + JSON.stringify(adapter.sel) + ";" +
        "for(var i=0;i<sels.length;i++){var n=0;try{n=document.querySelectorAll(sels[i]).length;}catch(e){n=-1;}" +
        "out.push({sel:sels[i],hits:n});}return JSON.stringify(out);})()"
    );
    console.log("选择器逐条命中：");
    selCheck.forEach((s) => console.log("  " + (s.hits < 0 ? "非法" : String(s.hits).padStart(4)) + "  " + s.sel));
  }
} finally {
  if (!KEEP) {
    try {
      chromeProc.kill();
    } catch {
      /* 已退出 */
    }
    await new Promise((r) => setTimeout(r, 300));
    fs.rmSync(userDataDir, { recursive: true, force: true });
  } else {
    console.log("\n--keep：Chrome 与用户目录保留（" + userDataDir + "）");
  }
}

process.exit(exitCode);
