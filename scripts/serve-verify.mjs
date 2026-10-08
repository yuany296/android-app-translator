// 本地验证页静态服务器（2026-09-29）。
//
// 为什么不直接用 `python -m http.server`：它是单线程的，WebView 会保持长连接，
// 一旦某个请求挂住，后续请求全部卡死（实测表现为 net::ERR_EMPTY_RESPONSE）。
// 这里显式 Connection: close + 无缓存，避免 keep-alive 把测试卡住。
//
// 用法：node scripts/serve-verify.mjs [rootDir] [port]

import http from "node:http";
import fs from "node:fs";
import path from "node:path";

const ROOT = path.resolve(process.argv[2] || ".verify/web");
const PORT = Number(process.argv[3] || 8899);

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
  ".svg": "image/svg+xml",
  ".txt": "text/plain; charset=utf-8"
};

const server = http.createServer((req, res) => {
  const rawUrl = String(req.url || "/").split("?")[0];
  let pathname;
  try {
    pathname = decodeURIComponent(rawUrl);
  } catch {
    pathname = rawUrl;
  }
  const file = path.join(ROOT, pathname === "/" ? "index.html" : pathname);
  if (!file.startsWith(ROOT)) {
    res.writeHead(403, { "Content-Type": "text/plain; charset=utf-8" }).end("403 forbidden");
    return;
  }
  fs.stat(file, (err, stat) => {
    if (err || !stat.isFile()) {
      res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" }).end("404 " + pathname);
      return;
    }
    res.writeHead(200, {
      "Content-Type": MIME[path.extname(file).toLowerCase()] || "application/octet-stream",
      "Content-Length": stat.size,
      "Cache-Control": "no-store",
      Connection: "close"
    });
    fs.createReadStream(file).on("error", () => res.end()).pipe(res);
  });
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`serving ${ROOT}`);
  console.log(`http://127.0.0.1:${PORT}/`);
});
