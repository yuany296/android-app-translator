#!/usr/bin/env node
/**
 * CDP 一次性求值器：adb forward 之后读模拟器里 App WebView 的运行时状态。
 *
 * 用法：
 *   adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
 *   node scripts/cdp-eval.mjs "Js 表达式"          # 表达式可 async（自动 awaitPromise）
 *
 * 为什么存在：渲染层排障要读 `MtOverlay._overlays` 里的真实几何
 * （排版框 vs 盖板框 vs 实际字号），logcat 只有中间量，DOM 里才是最终事实。
 */
import fs from "node:fs";

const port = process.env.CDP_PORT || "9222";
const expr = process.argv[2];
if (!expr) {
  console.error("usage: node scripts/cdp-eval.mjs '<js expression>'");
  process.exit(2);
}

const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const page = targets.find((t) => t.type === "page") || targets[0];
if (!page) {
  console.error("no CDP page target");
  process.exit(1);
}
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const pending = new Map();

function send(method, params) {
  return new Promise((resolve, reject) => {
    const msgId = ++id;
    pending.set(msgId, { resolve, reject });
    ws.send(JSON.stringify({ id: msgId, method, params }));
  });
}

const timeout = setTimeout(() => {
  console.error("CDP eval timed out");
  process.exit(1);
}, 20000);

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
  }
};

await new Promise((resolve, reject) => {
  ws.onopen = resolve;
  ws.onerror = reject;
});

const result = await send("Runtime.evaluate", {
  expression: expr,
  awaitPromise: true,
  returnByValue: true,
});
clearTimeout(timeout);
ws.close();
if (result.exceptionDetails) {
  console.error("EXCEPTION: " + JSON.stringify(result.exceptionDetails, null, 2));
  process.exit(1);
}
const value = result.result?.value;
process.stdout.write(typeof value === "string" ? value : JSON.stringify(value, null, 2) + "\n");
