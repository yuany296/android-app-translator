// 把扫描脚本的「工具函数区」（IIFE 开头到 collect 之前）抽成独立文件，
// 附上追踪包装，方便在真实 Chrome 里逐个函数核对。
import fs from "node:fs";

function extractRawString(source, declaration) {
  const pattern = new RegExp(declaration.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + '\\s*:\\s*String\\s*=\\s*"""');
  const m = pattern.exec(source);
  const open = source.indexOf('"""', m.index);
  const close = source.indexOf('"""', open + 3);
  return source.slice(open + 3, close);
}

const kt = fs.readFileSync("app/src/main/java/com/manga/translator/app/BrowserScanScript.kt", "utf8");
const SCAN = extractRawString(kt, "val SCAN_JS").replace("window.$CONFIG_VAR", "window.__mtSiteCfg");

// IIFE 内部：从 "var CFG =" 到 "function collect(" 之前
const start = SCAN.indexOf("var CFG =");
const end = SCAN.indexOf("function collect(");
if (start < 0 || end < 0) throw new Error("切片标记找不到");
const helpers = SCAN.slice(start, end);

const out = `// 自动生成（scripts/extract-scan-helpers.mjs）：扫描脚本的工具函数区 + 追踪包装。
// 用途：在真实 Chrome 里逐个核对 lazyUrlOf / srcOf / isLazyish 的行为。
window.__mtTrace = [];
(function(){
${helpers}

// 追踪包装：记录每个分支
var __origLazy = lazyUrlOf;
lazyUrlOf = function(el) {
  var id = el && el.id ? el.id : "?";
  var rec = { id: id, fn: "lazyUrlOf", currentSrc: String(el && el.currentSrc || "").slice(0, 24) };
  try {
    var got = __origLazy(el);
    rec.result = String(got == null ? got : got).slice(0, 28);
    rec.resultType = typeof got;
    rec.resultLen = got ? String(got).length : -1;
    return got;
  } catch (e) {
    rec.threw = String(e && e.message || e);
    throw e;
  } finally {
    window.__mtTrace.push(rec);
  }
};

var __origSrcOf = srcOf;
srcOf = function(el, deep) {
  var rec = { id: el && el.id ? el.id : "?", fn: "srcOf", deep: !!deep };
  try {
    var got = __origSrcOf(el, deep);
    rec.result = String(got == null ? got : got).slice(0, 28);
    rec.resultType = typeof got;
    return got;
  } catch (e) {
    rec.threw = String(e && e.message || e);
    throw e;
  } finally {
    window.__mtTrace.push(rec);
  }
};

window.__mtHelpers = {
  isLazyish: isLazyish,
  lazyUrlOf: function (el) { return lazyUrlOf(el); },
  srcOf: function (el, deep) { return srcOf(el, deep); },
  isUsableUrl: isUsableUrl,
  parseSrcset: parseSrcset,
  resolveUrl: resolveUrl
};
})();
1;
`;

fs.writeFileSync(".verify/sites/_scan_helpers.js", out, "utf8");
console.log("已写出 .verify/sites/_scan_helpers.js（" + out.length + " 字符）");
