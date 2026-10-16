#!/usr/bin/env node
// 一次连接里连续执行 Page.captureScreenshot + Runtime.evaluate，
// 间隔毫秒级，页面滚动也不会错位（排障取证用）。
// 用法：node scripts/cdp-shot-eval.mjs <out.png> <exprFile|expr> [out.json]
import fs from "node:fs";
const port = process.env.CDP_PORT || "9222";
const outPng = process.argv[2] || "shot.png";
const exprArg = process.argv[3] || "1";
const outJson = process.argv[4];
const expr = exprArg.startsWith("@") ? fs.readFileSync(exprArg.slice(1), "utf8") : exprArg;
const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const page = targets.find((t) => t.type === "page") || targets[0];
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const pending = new Map();
function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const msgId = ++id;
    pending.set(msgId, { resolve, reject });
    ws.send(JSON.stringify({ id: msgId, method, params }));
  });
}
ws.onmessage = (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    const p = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? p.reject(new Error(msg.error.message)) : p.resolve(msg.result);
  }
};
await new Promise((r) => (ws.onopen = r));
const shot = await send("Page.captureScreenshot", { format: "png" });
fs.writeFileSync(outPng, Buffer.from(shot.data, "base64"));
const ev = await send("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true });
if (outJson) fs.writeFileSync(outJson, JSON.stringify(ev.result?.value ?? null));
else console.log(ev.result?.value ?? JSON.stringify(ev));
ws.close();
process.exit(0);
