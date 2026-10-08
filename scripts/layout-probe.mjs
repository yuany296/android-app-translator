"use strict";
/**
 * 页面布局体检（2026-09-30）。
 *
 * 用途：夹具页/真实页面「明明是空的」时，一次把关键元素的盒模型与计算样式打出来，
 * 而不是靠改 CSS 反复猜。输出：视口、元素 rect、display/overflow/height、
 * canvas 与 img 的 naturalWidth / width / height、以及可见性判据。
 *
 * 用法：node scripts/layout-probe.mjs http://127.0.0.1:8899/cx.html [选择器...]
 */

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";

const url = process.argv[2];
if (!url) throw new Error("用法：node scripts/layout-probe.mjs <url> [selector...]");
const extraSelectors = process.argv.slice(3);
const PORT_CDP = 9362;

function findChrome() {
  return [
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
  ].find((p) => fs.existsSync(p));
}

const udd = fs.mkdtempSync(path.join(os.tmpdir(), "mt-layout-"));
const chrome = spawn(findChrome(), [
  "--headless=new",
  "--remote-debugging-port=" + PORT_CDP,
  "--user-data-dir=" + udd,
  "--no-first-run",
  "--disable-gpu",
  "--window-size=412,915",
  "about:blank",
], { stdio: "ignore" });

async function fetchJson(u, tries = 80) {
  for (let i = 0; i < tries; i++) {
    try {
      const r = await fetch(u);
      if (r.ok) return await r.json();
    } catch {}
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error("连不上 " + u);
}

try {
  await fetchJson("http://127.0.0.1:" + PORT_CDP + "/json/version");
  const t = await (await fetch("http://127.0.0.1:" + PORT_CDP + "/json/new?about:blank", { method: "PUT" })).json();
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  await new Promise((res, rej) => {
    ws.addEventListener("open", res, { once: true });
    ws.addEventListener("error", rej, { once: true });
  });
  let id = 1;
  const pending = new Map();
  ws.addEventListener("message", (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id);
      pending.delete(m.id);
      m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result);
    }
  });
  const send = (method, params = {}) =>
    new Promise((res, rej) => {
      pending.set(id, { res, rej });
      ws.send(JSON.stringify({ id: id++, method, params }));
    });

  await send("Page.enable");
  await send("Runtime.enable");
  await send("Emulation.setDeviceMetricsOverride", {
    width: 412, height: 915, deviceScaleFactor: 2.625, mobile: true,
  });
  await send("Page.navigate", { url });
  await new Promise((r) => setTimeout(r, 3500));

  const report = await send("Runtime.evaluate", {
    expression:
      "(function(){" +
      "function box(el){if(!el)return null;var r=el.getBoundingClientRect();var cs=getComputedStyle(el);" +
      "return {tag:el.tagName.toLowerCase(),id:el.id||'',cls:String(el.className||'').slice(0,60)," +
      "rect:[Math.round(r.left),Math.round(r.top),Math.round(r.width),Math.round(r.height)]," +
      "display:cs.display,visibility:cs.visibility,opacity:cs.opacity,overflow:cs.overflowY," +
      "cssH:cs.height,cssW:cs.width,position:cs.position,zIndex:cs.zIndex," +
      "natural:[el.naturalWidth||0,el.naturalHeight||0]," +
      "attr:[el.getAttribute&&el.getAttribute('width')||'',el.getAttribute&&el.getAttribute('height')||'']," +
      "prop:[el.width||0,el.height||0]};}" +
      "var out={viewport:[innerWidth,innerHeight],scrollY:Math.round(scrollY)," +
      "bodyScrollH:document.body.scrollHeight,docScrollH:document.documentElement.scrollHeight," +
      "elements:[]};" +
      "var sels=['html','body','header','#reader','canvas','img'].concat(" + JSON.stringify(extraSelectors) + ");" +
      "for(var i=0;i<sels.length;i++){var els=document.querySelectorAll(sels[i]);" +
      "for(var j=0;j<els.length&&j<4;j++){out.elements.push(box(els[j]));}}" +
      "return JSON.stringify(out);})()",
    returnByValue: true,
  });

  if (report.exceptionDetails) {
    console.log("页面异常：" + report.exceptionDetails.text);
  } else {
    const data = JSON.parse(report.result.value);
    console.log("视口 " + data.viewport.join("×") + "  scrollY=" + data.scrollY +
      "  bodyScrollH=" + data.bodyScrollH + "  docScrollH=" + data.docScrollH);
    console.log("");
    for (const e of data.elements) {
      if (!e) continue;
      console.log(
        (e.tag + (e.id ? "#" + e.id : "") + (e.cls ? "." + e.cls.split(" ")[0] : "")).padEnd(28) +
        " rect=" + JSON.stringify(e.rect).padEnd(24) +
        " display=" + e.display.padEnd(12) +
        " overflowY=" + String(e.overflowY).padEnd(9) +
        " cssH=" + String(e.cssH).padEnd(10) +
        " natural=" + JSON.stringify(e.natural).padEnd(14) +
        " prop=" + JSON.stringify(e.prop).padEnd(14) +
        " z=" + e.zIndex
      );
    }
  }
} finally {
  try { chrome.kill(); } catch {}
  await new Promise((r) => setTimeout(r, 300));
  fs.rmSync(udd, { recursive: true, force: true });
}
