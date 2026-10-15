package com.manga.translator.app

import org.json.JSONObject

/**
 * 内嵌浏览器「取图扫描脚本」生成器（2026-09-30）。
 *
 * ## 它解决什么
 *
 * 旧实现的扫描脚本把站点差异当成不存在：只看 `document.images` 的 `src`，
 * 只认 `naturalWidth/Height` 与 `getBoundingClientRect`，坐标一律按
 * `rect.left + window.scrollX` 记成**文档坐标**。真实漫画站于是集体失效
 * （懒加载属性没读、滚动容器坐标错位、`<picture>` 取到兜底小图）。
 *
 * 现在脚本分成两层：
 *
 * 1. **通用层（永远生效）**：懒加载属性 / `srcset` / `<picture>` / CSS 背景图 /
 *    `<canvas>` 阅读器 / 可滚动祖先坐标 / iframe 穿透 —— 这些修正不依赖站点知识，
 *    对所有网站都是净收益（旧行为是「这些图根本找不到」或「找到了但贴错位置」）。
 * 2. **站点层（[com.manga.translator.store.BrowserSiteAdapter]）**：额外选择器、
 *    排除区、更宽或更严的尺寸阈值。
 *
 * ## 关键约定
 *
 * - 站点适配只做**补充与收紧**，从不「只信选择器」：显式选择器一个没命中时
 *   自动退回通用启发式，所以站点改版的最坏后果是回到今天的通用行为。
 * - 坐标契约保持 `left/top/width/height` 不变（[ScannedImage] 与
 *   `placeOverlayJs` 都依赖它），但 `left/top` 现在表达的是
 *   **「该图的定位宿主」坐标系**里的位置，宿主由脚本一并给出（`hostId` /
 *   `hostX` / `hostY`）。这样图片放在 `overflow:auto` 容器里时，整图覆盖层
 *   贴进同一个容器就自动跟着滚，不会再错位。
 * - 脚本里**不拼任何变量**（唯一的占位符是配置 JSON 的变量名），避免 U+2028 /
 *   引号之类字符把 `evaluateJavascript` 的源码弄坏；配置本身走
 *   [scriptConfigJson] → `JSONObject.quote` 安全转义。
 */
object BrowserScanScript {

    /** 承载站点配置的页面全局变量名（`installScript` 写、[SCAN_JS] 读）。 */
    const val CONFIG_VAR = "__mtSiteCfg"

    /**
     * 把适配器配置写成一段注入脚本。
     *
     * 必须在**每次会话开始时**重新注入**：用户可能在两个标签页之间来回切
     * （不同站点 → 不同适配器），沿用上一次的配置会让新站点套用旧规则。
     *
     * 配置是直接内联成 JS 对象字面量的（不是字符串包一层再 parse），所以必须
     * 挡住 **U+2028 / U+2029**：它们是合法的 JSON 字符，却是 JS 的换行符，
     * 原样内联会把脚本断成两行 —— `evaluateJavascript` 报的是语法错，
     * 现场看就是「注入配置失败、翻译整个不动」。见 [escapeLineSeparators]。
     */
    fun installScript(configJson: String): String =
        "window.$CONFIG_VAR = ${escapeLineSeparators(configJson)}; 1"

    /**
     * U+2028（行分隔符）/ U+2029（段分隔符）→ `\u2028` / `\u2029` 转义。
     *
     * JSON 允许这两个字符原样出现在字符串里，JS 却把它们当换行 —— 内联进脚本
     * 就是语法错。`JSONObject` 自己不会转义它们，所以这里补一刀。
     */
    fun escapeLineSeparators(value: String): String {
        if (value.indexOf('\u2028') < 0 && value.indexOf('\u2029') < 0) return value
        return value.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    }

    /**
     * 适配器 → 注入用 JSON。
     *
     * 空数组/`null` 一律省略，让脚本里的 `undefined` 判断走最短路径（少传一个字段
     * 就少一次每轮扫描的数组遍历）。数字阈值 `null` 表示「沿用通用默认」。
     */
    fun scriptConfigJson(
        adapter: com.manga.translator.store.BrowserSiteAdapter,
        /** 站点适配总开关（关闭时只跑通用层）。 */
        siteRulesEnabled: Boolean = true
    ): String {
        val json = JSONObject()
        json.put("id", adapter.id)
        json.put("label", adapter.label)
        if (siteRulesEnabled) {
            if (adapter.imageSelectors.isNotEmpty()) {
                json.put("sel", org.json.JSONArray(adapter.imageSelectors as List<*>))
            }
            if (adapter.excludeSelectors.isNotEmpty()) {
                json.put("excl", org.json.JSONArray(adapter.excludeSelectors as List<*>))
            }
            adapter.minWidth?.let { json.put("minW", it) }
            adapter.minHeight?.let { json.put("minH", it) }
            adapter.minNaturalWidth?.let { json.put("minNW", it) }
            adapter.minNaturalHeight?.let { json.put("minNH", it) }
            if (adapter.fixedOverlayPages) json.put("fixedPages", true)
        }
        return json.toString()
    }

    /**
     * 扫描脚本（幂等安装 + 每次调用返回结果）。
     *
     * 返回值是 **JSON 数组的字符串**（沿用旧契约：WebView 会把结果再包一层引号，
     * Kotlin 侧 [parseScannedImages] 负责剥壳），每项：
     *
     * ```json
     * {"id":"mt7","src":"https://…","l":120,"t":3400,"w":760,"h":1200,
     *  "vy":-800,"hostId":"mtroot3","hostX":0,"hostY":0,"frame":0,"tag":"img","lazy":true}
     * ```
     *
     * - `vy` —— 元素顶边的**视口**纵坐标（CSS px，已含 iframe 偏移）：会话侧
     *   「阅读位置优先调度」用它把用户眼前的图排到最前（见 [BrowserImageOrder]）。
     * - `l/t` —— 在该图**定位宿主**坐标系里的位置；宿主 = 最近的可滚动祖先
     *   （没有则 `documentElement`），与 `mt-overlay-renderer.js` 的
     *   `resolveOverlayHost` 同一判据。
     * - `hostId` / `hostX` / `hostY` —— 宿主的稳定 id（脚本会按需补 `data-mt-host`）
     *   与宿主自身的视口矩形左上角。整图覆盖层（DOM 覆盖层不可用时的兜底路径）
     *   用它把 `<img>` 插进同一个宿主，从而与图片共享滚动上下文。
     * - `frame` —— iframe 序号，0 = 顶层文档；`frameX/frameY` 是 iframe 元素在
     *   **顶层**文档里的视口偏移，仅用于诊断。
     */
    val SCAN_JS: String = """
(function () {
  var CFG = window.$CONFIG_VAR || {};
  var IFRAME_BUDGET = 4;

  function toArray(list) {
    var out = [];
    if (!list) return out;
    for (var i = 0; i < list.length; i++) out.push(list[i]);
    return out;
  }

  function isExcluded(node) {
    var exclusions = toArray(CFG.excl);
    if (!exclusions.length) return false;
    for (var i = 0; i < exclusions.length; i++) {
      try {
        if (node.closest(exclusions[i])) return true;
      } catch (e) { /* 选择器不合法：忽略这条 */ }
    }
    return false;
  }

  function parseSrcset(value) {
    if (!value || typeof value !== "string") return "";
    var parts = value.split(",");
    var bestUrl = "";
    var bestScore = -1;
    for (var i = 0; i < parts.length; i++) {
      var chunk = parts[i].trim();
      if (!chunk) continue;
      var bits = chunk.split(/\s+/);
      var url = bits[0];
      if (!url) continue;
      var weight = 1;
      var descriptor = bits[1] || "";
      if (/^\d+(\.\d+)?w$/.test(descriptor)) weight = parseFloat(descriptor);
      else if (/^\d+(\.\d+)?x$/.test(descriptor)) weight = parseFloat(descriptor) * 1000;
      if (weight > bestScore) { bestScore = weight; bestUrl = url; }
    }
    return bestUrl;
  }

  function isLazyName(name) {
    return /^data-(src|original|orig|url|lazy|lazy-src|lazyload|echo|image|img|actual|delayload|defer|srcset|image-src|big|large|full)$/.test(name);
  }

  /**
   * 路径里带 blank / placeholder / loading 之类字样的图 —— 懒加载占位。
   *
   * 只做**纯路径判据**，不碰 data URI：data URI 大不大跟「是不是占位」没关系，
   * 那是「这张图有没有像素」的问题，交给 [isUsableImageValue] 判断。
   */
  function isLazyish(value) {
    if (typeof value !== "string" || !value) return false;
    return /(^|\/)[^\/]*(blank|placeholder|loading|loader|lazyload|spacer|pixel|grey|gray|empty)[^\/]*\.(gif|png|jpe?g|webp|svg)(\?|$)/i.test(value);
  }

  /**
   * 这个值能不能当「一张真图」用（[lazyUrlOf] 的每个候选都要过这一关）。
   *
   * 内联 data URI 分两档，**不能一律当占位**：
   * - 小体积（≤2KB）且长得像 1×1 GIF / 纯色 SVG 的，是典型占位图；
   * - 大体积的是真实图片（有站点把页图内联成 data URI，也有站点像这里一样把
   *   真地址放在 `data-src` 上、值是几万字符的 base64）。
   *
   * 2026-09-30 连踩两次，所以两件事分开写：
   * 1. 旧版把 `data:image/png;base64,…` 一律当占位 → 「真图是 data URI」的页面全丢；
   * 2. 把体积判断塞进 [isLazyish] 之后，`data-src` 上 2.3 万字符的 base64 真图
   *    又被当成占位跳过 → 懒加载站（Kakao / 包子漫画那类）一张都进不了候选。
   * 体积阈值必须按**候选值**判断，而不是按「属性名像不像懒加载」判断。
   */
  function isUsableImageValue(value) {
    if (typeof value !== "string") return false;
    var v = value.trim();
    if (!v || v === "undefined" || v === "null") return false;
    if (isLazyish(v)) return false;
    if (/^data:/i.test(v)) {
      if (v.length > 2048) return true;
      if (/^data:image\/(gif|png);base64,R0lGOD/i.test(v)) return false;
      if (/^data:image\/svg\+xml/i.test(v)) return false;
    }
    return true;
  }

  /**
   * 元素上可用的图地址 + **它来自哪一类来源**。
   *
   * 返回值形如 `{ url: "…", kind: "src" | "current" | "lazy" | "srcset" }`。
   *
   * 为什么要报来源：懒加载站的 `<img>` 元素上 `src` 是占位图、`naturalWidth` 是
   * 占位图的尺寸（1×1 或 0），**只有真地址来自 `data-src` 这类属性时才说明
   * 「这张图还没加载」**。调用方必须据此决定「要不要拿 `naturalWidth` 卡尺寸」——
   * 2026-09-30 实测：`<img src=占位.gif data-src=真图>` 的 `naturalWidth` 是 1，
   * 被 `minNaturalWidth=200` 判成「缩略图」直接丢掉，表现就是
   * 「站点适配命中 3 张、最终一张都不剩」。
   *
   * 顺序：`currentSrc → src 属性 → src 属性值 → 懒加载属性 → srcset`，逐个候选
   * 过 [isUsableImageValue]，跳过占位图后取第一个真正像图片的地址。
   */
  function lazyUrlOf(el) {
    if (!el || el.nodeType !== 1) return { url: "", kind: "" };
    if (isUsableImageValue(el.currentSrc)) return { url: el.currentSrc, kind: "current" };
    var src = el.getAttribute && el.getAttribute("src");
    if (isUsableImageValue(src)) return { url: src, kind: "src" };
    if (isUsableImageValue(el.src)) return { url: el.src, kind: "src" };
    var attrs = el.attributes;
    if (attrs) {
      for (var i = 0; i < attrs.length; i++) {
        var attr = attrs[i];
        if (!attr || !isLazyName(attr.name)) continue;
        if (isUsableImageValue(attr.value)) return { url: attr.value.trim(), kind: "lazy" };
      }
    }
    var srcset = el.getAttribute && (el.getAttribute("srcset") || el.getAttribute("data-srcset"));
    if (srcset) {
      var best = parseSrcset(srcset);
      if (isUsableImageValue(best)) return { url: best, kind: "srcset" };
    }
    return { url: "", kind: "" };
  }

  function bgUrlsOf(el) {
    var found = [];
    var seen = {};
    var node = el;
    var hops = 0;
    while (node && node.nodeType === 1 && hops < 3) {
      hops += 1;
      var bg = "";
      try { bg = getComputedStyle(node).backgroundImage || ""; } catch (e) { bg = ""; }
      var re = /url\((['"]?)([^'")]+)\1\)/g;
      var match;
      while ((match = re.exec(bg)) !== null) {
        var url = match[2];
        if (!url || seen[url]) continue;
        seen[url] = 1;
        found.push(url);
      }
      if (found.length) break;
      node = node.parentElement;
    }
    return found;
  }

  /**
   * 原始地址 → 绝对地址。
   *
   * **任何**非字符串输入都返回空串 —— 绝不做 `String(undefined)`。
   * 这条守卫是踩坑之后加的：`String(undefined)` 会得到字面量 `"undefined"`，
   * 它既不是空串、又通不过 `isUsableUrl`，于是在日志里表现为
   * 「url 是字符串 "undefined"」，看着像解析成功、实际什么都没解析到，
   * 排查时极易被带偏（2026-09-30 实测）。
   */
  function resolveUrl(raw, base) {
    if (typeof raw !== "string") return "";
    var value = raw.trim();
    if (!value || value === "undefined" || value === "null") return "";
    if (/^(https?:)?\/\//i.test(value)) return value.indexOf("//") === 0 ? "https:" + value : value;
    // blob: / data: 必须保留：Kakao Page、Lezhin、哔哩哔哩漫画这类站点把图片字节
    // 在 JS 里解出来之后自己造 URL，真实地址根本不在 src 上（哔哩哔哩甚至把图的
    // 前 0x5010 字节做了 AES-CBC 加密）。旧实现只认 http(s)，这些站一张都扫不到。
    if (/^blob:/i.test(value)) return value;
    if (/^data:image\//i.test(value)) return value;
    if (/^(javascript:|about:)/i.test(value)) return "";
    if (/^[a-z][a-z0-9+.-]*:/i.test(value)) return "";
    try { return new URL(value, base).href; } catch (e) { return ""; }
  }

  /** 相对 URL 的解析基准文档：遍历 iframe 时指向那一帧自己的文档（见 [frames] 调用处）。 */
  var currentDoc = document;

  function holderOf(el) {
    var node = el;
    var hops = 0;
    while (node && node.nodeType === 1) {
      var tag = (node.tagName || "").toLowerCase();
      if (tag === "picture" || tag === "amp-img" || tag === "source") return node;
      node = node.parentElement;
      hops += 1;
      if (hops > 3) return null;
    }
    return null;
  }

  /**
   * 元素（含 `<picture>` / `<source>` / `<amp-img>` 包裹层）对应的真实图地址。
   *
   * 每一步都过 [resolveUrl]，它保证**只返回字符串**（拿不到就空串）。
   * 这对上层很重要：空串会被 `isUsableUrl` 挡掉，而 `"undefined"` 这种
   * 「看起来像字符串」的脏值会把候选推进到后面的尺寸/下载阶段，白白浪费一轮。
   *
   * `deep` 为真时还会**向下**找：站点常把标记打在容器上而不是 `<img>` 上
   * （例如 `[data-scroll-index]` 包着 `<div><img></div>`，或哈希类名的
   * CSS-module 容器里塞 `<img>`）。只认元素自身会让这些站一张都扫不到。
   * 向下最多看 8 个后代，且要求真的有可解析地址，避免把整页当成候选。
   */
  function srcOf(el, deep) {
    function finish(raw) {
      return resolveUrl(typeof raw === "string" ? raw : "", currentDoc.baseURI);
    }

    /** [lazyUrlOf] 的结果 → 解析后的 { url, kind }；任一步拿不到就返回 null。 */
    function adopt(candidate) {
      if (!candidate || !candidate.url) return null;
      var url = finish(candidate.url);
      return url ? { url: url, kind: candidate.kind || "" } : null;
    }

    var holder = holderOf(el);
    var direct = adopt(lazyUrlOf(el));
    if (direct) return direct;
    if (holder) {
      var inner = adopt(lazyUrlOf(holder));
      if (inner) return inner;
      var sources = holder.querySelectorAll ? holder.querySelectorAll("source") : [];
      for (var i = 0; i < sources.length; i++) {
        var fromAttr = sources[i].getAttribute("srcset");
        if (!fromAttr) fromAttr = sources[i].getAttribute("data-srcset");
        var candidate = adopt(lazyUrlOf(sources[i])) || adopt({ url: parseSrcset(fromAttr), kind: "srcset" });
        if (candidate) return candidate;
      }
    }
    if (deep && el.querySelectorAll) {
      var descendants = el.querySelectorAll("img, canvas");
      for (var d = 0; d < descendants.length && d < 8; d++) {
        var node = descendants[d];
        if ((node.tagName || "").toLowerCase() === "canvas") continue;
        var innerUrl = adopt(lazyUrlOf(node));
        if (innerUrl) return innerUrl;
      }
    }
    var backgrounds = bgUrlsOf(el);
    return backgrounds.length ? adopt({ url: backgrounds[0], kind: "bg" }) : null;
  }

  /**
   * 显式选择器命中后，把容器收窄到真正的图片元素。
   *
   * 为什么必要：坐标是按「图片自身的显示矩形」算的。如果选择器命中的是外层容器
   * （`div` / `a` / `li`），拿容器的矩形去贴译文就会把容器的边距、标题一起算进去，
   * 整块偏移 —— 站点改版把类名挪到容器上时尤其常见。
   */
  function resolveTarget(el) {
    var tag = (el.tagName || "").toLowerCase();
    if (tag === "img" || tag === "canvas") return el;
    if (!el.querySelectorAll) return el;
    var descendants = el.querySelectorAll("img, canvas");
    for (var i = 0; i < descendants.length && i < 8; i++) {
      var node = descendants[i];
      if ((node.tagName || "").toLowerCase() === "canvas") return node;
      // 两条判据缺一不可：
      // - `srcOf(node)` 能解析出真地址（图已加载 / currentSrc 就绪）；
      // - **或者**元素带 `data-src` 这类懒加载属性 —— 图还没加载时 srcOf 返回空串，
      //   只认第一条就会把「容器里全是占位图」的站点整批丢掉。
      // 2026-09-30 实测踩到：Kakao 形态的容器里三张页图全是 data-src 占位，
      // 只判 srcOf 时它们全部消失，容器自己反被当成候选。
      if (srcOf(node, false) || lazyUrlOf(node)) return node;
    }
    return el;
  }

  function isUsableUrl(url) {
    if (!url) return false;
    // 只挡**我们自己**的内存通道，不挡站点自己的图片地址：合法的漫画 CDN 路径里
    // 完全可能出现 "mt" 之类的子串，用宽泛的 __mt 判断会误杀真实页图。
    if (url.indexOf("mtlocal.overlay") !== -1) return false;
    return /^(https?:|blob:|data:image\/)/i.test(url);
  }

  function isScrollHost(node) {
    if (!node || node === document.documentElement || node === document.body) return false;
    var style;
    try { style = getComputedStyle(node); } catch (e) { return false; }
    if (!style) return false;
    var ox = String(style.overflowX || "").toLowerCase();
    var oy = String(style.overflowY || "").toLowerCase();
    var scrolls = (ox === "auto" || ox === "scroll" || ox === "overlay") ||
      (oy === "auto" || oy === "scroll" || oy === "overlay");
    if (!scrolls) return false;
    return node.scrollHeight > node.clientHeight + 4 || node.scrollWidth > node.clientWidth + 4;
  }

  function isFixedish(node) {
    var hops = 0;
    while (node && node.nodeType === 1 && hops < 8) {
      var style;
      try { style = getComputedStyle(node); } catch (e) { return false; }
      var position = style && String(style.position || "").toLowerCase();
      if (position === "fixed") return true;
      if (position === "absolute") return false;
      node = node.parentElement;
      hops += 1;
    }
    return false;
  }

  function hostOf(el) {
    var node = el.parentElement;
    var hops = 0;
    while (node && node !== document.documentElement && hops < 400) {
      if (isScrollHost(node)) return node;
      node = node.parentElement;
      hops += 1;
    }
    return document.documentElement;
  }

  function hostToken(host) {
    if (!host || host === document.documentElement) return "";
    if (host === document.body) return "";
    if (!host.getAttribute("data-mt-host")) {
      window.__mtHostSeq = (window.__mtHostSeq || 0) + 1;
      host.setAttribute("data-mt-host", "mth" + window.__mtHostSeq);
    }
    return host.getAttribute("data-mt-host") || "";
  }

  function hostOrigin(host) {
    if (!host || host === document.documentElement || host === document.body) return { x: 0, y: 0 };
    var rect = host.getBoundingClientRect();
    return { x: rect.left, y: rect.top };
  }

  /** 元素「定位宿主」坐标系里的矩形：宿主 padding box 左上角为原点，含宿主自身滚动偏移。 */
  function frameRect(el) {
    var rect = el.getBoundingClientRect();
    // 固定定位的浮层阅读器（fixedPages=true）：元素随视口定位、不参与宿主滚动，
    // 此时再加 scrollLeft/Top 就是把滚动量算了两遍，整块覆盖层会随滚动漂移。
    var pinned = !!CFG.fixedPages && isFixedish(el);
    var host = pinned ? null : hostOf(el);
    var origin = hostOrigin(host);
    var scrollLeft = (host && host.scrollLeft) || 0;
    var scrollTop = (host && host.scrollTop) || 0;
    return {
      l: Math.round(rect.left - origin.x + scrollLeft),
      t: Math.round(rect.top - origin.y + scrollTop),
      w: Math.round(rect.width),
      h: Math.round(rect.height),
      x: Math.round(rect.left),
      y: Math.round(rect.top),
      host: host,
      hostId: hostToken(host),
      hostX: Math.round(origin.x),
      hostY: Math.round(origin.y)
    };
  }

  function collect(doc, frameIndex, frameOrigin) {
    var found = [];
    var seen = [];

    function push(el, explicit) {
      if (!el || el.nodeType !== 1) return;
      if (seen.indexOf(el) !== -1) return;
      seen.push(el);
      found.push({ el: el, explicit: !!explicit });
    }

    var explicitHits = 0;
    var selectors = toArray(CFG.sel);
    if (selectors.length) {
      selectors.forEach(function (sel) {
        var nodes;
        try { nodes = doc.querySelectorAll(sel); } catch (e) { nodes = null; }
        if (!nodes) return;
        for (var i = 0; i < nodes.length; i++) {
          var node = nodes[i];
          if (!node || node.nodeType !== 1) continue;
          explicitHits += 1;
          push(node, true);
        }
      });
    }

    // 通用层：任何 <img>、<picture>、<canvas>、<amp-img> 都要过一遍。
    // 显式选择器命中过也照跑 —— 选择器只保证「不漏」，不该限制「只取这些」。
    var generic = doc.querySelectorAll("img, picture, canvas, amp-img, [style*='background-image']");
    for (var i = 0; i < generic.length; i++) push(generic[i], false);

    var out = [];
    var limit = 80;
    // 收窄之后的元素去重：容器命中和内部 <img> 命中会指向同一张图，
    // 不去重就会把它翻两遍（浪费一次下载 + 一次 OCR，还会画两层译文）。
    var seenTargets = [];
    for (var k = 0; k < found.length && out.length < limit; k++) {
      var entry = found[k];
      var el = entry.el;
      // 每个候选**单独**兜异常：一个节点让 getBoundingClientRect / closest 抛错，
      // 只应该跳过这一个节点，绝不能让整轮扫描变成空结果。
      // 教训（2026-09-30）：`isExcluded(el, doc)` 传错参数导致 closest 抛 TypeError，
      // 异常一路冒到 collect 外面被静默吞掉，表现为「配了排除项的站点一张图都扫不到」，
      // 而且日志里什么线索都没有。现在这类错误会记进 window.__mtScanErrors 随结果带出。
      try {
      // 注意参数：`isExcluded` 只接一个元素，排除项从 CFG.excl 里读。
      if (isExcluded(el)) continue;
      // 显式选择器可能命中的是容器（站点把标记打在包裹层上）：先收窄到真正的图片元素，
      // 否则坐标会按容器算、译文整块偏移。
      if (entry.explicit) el = resolveTarget(el);
      if (seenTargets.indexOf(el) !== -1) continue;
      seenTargets.push(el);
      var tag = (el.tagName || "").toLowerCase();
      if (el.classList && (el.classList.contains("__mt_overlay") || el.classList.contains("mt-overlay-layer"))) continue;
      if (el.getAttribute && el.getAttribute("data-mt-ov-for")) continue;

      // <canvas> 阅读器：没有 src 可解析，页图就是画布本身的像素。
      // 判据与 <img> 分开写 —— 画布的"原始像素"是 width/height 属性，
      // 不是 naturalWidth（canvas 上那是 undefined）。
      var isCanvas = tag === "canvas";
      // explicit=true 表示这一项来自站点选择器，`el` 可能仍是容器（resolveTarget 没找到
      // 内部 <img> 时原样返回）——此时要允许向下找图，否则容器带 data-* 标记的站点会一张都拿不到。
      // <canvas> 阅读器：没有 src 可解析，页图就是画布本身的像素。
      var isCanvas = tag === "canvas";
      // explicit=true 时 `el` 可能仍是容器（resolveTarget 没找到内部 <img>），
      // 所以要允许向下找图，否则「标记打在包裹层上」的站点一张都拿不到。
      var resolved = isCanvas ? null : srcOf(el, !!entry.explicit);
      if (isCanvas) {
        if (!(el.width > 0) || !(el.height > 0)) continue;
      } else if (!resolved || !isUsableUrl(resolved.url)) {
        continue;
      }
      var url = resolved ? resolved.url : "";
      // 真地址来自懒加载属性（data-src 等）时，元素上的 `src` 还是占位图 ——
      // 此时 `naturalWidth` 是**占位图的尺寸**（1×1 或 0），绝不能拿它卡「是不是缩略图」。
      var fromLazyAttr = !!resolved && resolved.kind !== "src" && resolved.kind !== "current";

      var natural = isCanvas ? (el.width || 0) : (el.naturalWidth || 0);
      var naturalH = isCanvas ? (el.height || 0) : (el.naturalHeight || 0);
      var isImage = tag === "img";
      var attrW = 0;
      var attrH = 0;
      if (isImage) {
        attrW = parseFloat(el.getAttribute("width")) || 0;
        attrH = parseFloat(el.getAttribute("height")) || 0;
      }

      // 「图还没来」的两种形态：
      // 1. 从未开始下载（naturalWidth=0），但元素盒子被 width/height 属性撑住了；
      // 2. **真地址来自懒加载属性**（`src` 还是 1×1 占位图，naturalWidth=1）。
      // 第 2 种最容易被漏掉：`naturalWidth=1 > 0`，看起来像「图已加载」，
      // 于是被 minNaturalWidth 判成缩略图丢掉 —— 2026-09-30 实测，
      // `data-src` 型站点（Kakao / 包子漫画）就是这么「命中 3 张、最后 0 张」的。
      var pendingLazy = isImage && (fromLazyAttr || (natural === 0 && (attrH > 0 || attrW > 0)));

      var rect = frameRect(el);
      if (rect.w < 2 && rect.h < 2) continue;

      var minW = Number(CFG.minW) || 180;
      var minH = Number(CFG.minH) || 180;
      // 懒加载图在真正加载前 rect 可能还是 0×0，此时用 width/height 属性补估一次
      // （这正是「占位图撑住版面、真图还没来」的常见形态）。
      var effW = rect.w;
      var effH = rect.h;
      if (pendingLazy && attrW > 0 && attrH > 0) {
        effW = Math.max(effW, attrW);
        effH = Math.max(effH, attrH);
      }
      if (effW < minW || effH < minH) continue;

      var minNW = Number(CFG.minNW) || 260;
      var minNH = Number(CFG.minNH) || 200;
      // 原始像素只有在「图已经加载」时才有意义；懒加载未触发的图不拿 0 当「太小」，
      // 否则包子漫画 / 漫画柜这类 data-src 站点首屏外永远扫不到。
      if (!pendingLazy && natural > 0 && naturalH > 0) {
        if (natural < minNW || naturalH < minNH) continue;
      }

      var id = el.getAttribute("data-mt-id");
      if (!id) {
        window.__mtSeq = (window.__mtSeq || 0) + 1;
        id = "mt" + window.__mtSeq;
        // 只加 data-mt-id，**不动站点自己的 id**：页面脚本可能按 id 查找这张图，
        // 覆盖掉会把这个站弄坏（`mt-overlay-renderer.js` 两处查找都是
        // `[data-mt-id=…]` 优先、`getElementById` 兜底，改这里不影响它）。
        el.setAttribute("data-mt-id", id);
      }

      out.push({
        id: id,
        // 站点自己的 id / 类名（原样带出）：`data-mt-id` 是脚本生成的，排查真实站点时
        // 光有一串 mtN 没法对回页面结构；这两个字段让日志能直接指认「是哪张图」。
        elId: el.getAttribute("id") || "",
        elClass: (typeof el.className === "string" ? el.className : "").slice(0, 120),
        src: url,
        kind: tag === "canvas" ? "canvas" : (/^blob:/i.test(url) ? "blob" : (/^data:/i.test(url) ? "data" : "url")),
        l: rect.l + frameOrigin.x,
        t: rect.t + frameOrigin.y,
        w: rect.w,
        h: rect.h,
        // 视口纵坐标（顶层视口，CSS px）：会话侧的「阅读位置优先调度」靠它把当前看到的
        // 图排到最前（2026-10-15，用户报 Kakao「我在中间看，它先从头开始翻」）。
        vy: Math.round(rect.y) + frameOrigin.y,
        hostId: rect.hostId,
        hostX: rect.hostX,
        hostY: rect.hostY,
        frame: frameIndex,
        tag: tag,
        lazy: !!pendingLazy,
        explicit: !!entry.explicit
      });
      } catch (err) {
        // 只跳过这一个候选，并把原因留档（随结果 JSON 一起回传，Kotlin 侧打日志）。
        window.__mtScanErrors = window.__mtScanErrors || [];
        if (window.__mtScanErrors.length < 12) {
          window.__mtScanErrors.push(
            ((el && el.tagName) || "?") + "#" + ((el && el.getAttribute && el.getAttribute("id")) || "-") +
            " " + String(err && err.message || err)
          );
        }
      }
    }
    return { items: out, explicitHits: explicitHits };
  }

  function frames() {
    var out = [{ doc: document, origin: { x: 0, y: 0 } }];
    var budget = IFRAME_BUDGET;
    var list = document.querySelectorAll("iframe");
    for (var i = 0; i < list.length && budget > 0; i++) {
      var frameEl = list[i];
      var box = frameEl.getBoundingClientRect();
      if (box.width < 120 || box.height < 120) continue;
      var inner = null;
      try { inner = frameEl.contentDocument; } catch (e) { inner = null; }
      if (!inner) continue;
      out.push({
        doc: inner,
        origin: { x: Math.round(box.left), y: Math.round(box.top) },
        frameX: Math.round(box.left),
        frameY: Math.round(box.top)
      });
      budget -= 1;
    }
    return out;
  }

  var result = [];
  var explicitTotal = 0;
  var scopes = frames();
  for (var f = 0; f < scopes.length; f++) {
    // 相对 URL 必须按**该帧自己的** baseURI 解析：iframe 里的 `data-src="/a.jpg"`
    // 用顶层文档的 baseURI 去补会指到别的站。
    currentDoc = scopes[f].doc;
    var collected = null;
    try {
      collected = collect(scopes[f].doc, f, scopes[f].origin);
    } catch (e) {
      // 整个帧扫描失败：记下来。帧级失败通常意味着脚本自身有问题（站点改版把
      // 某个 API 弄没了之类），**必须**带出去 —— 静默吞掉就会变成
      // 「explicit 命中了 68 条、最后只剩 6 张候选，也没人知道为什么」。
      window.__mtScanErrors = window.__mtScanErrors || [];
      if (window.__mtScanErrors.length < 12) {
        var stackLine = "";
        try { stackLine = String(e && e.stack || "").split("\n")[1] || ""; } catch (ignore) { stackLine = ""; }
        window.__mtScanErrors.push(
          "frame" + f + " " + String(e && e.message || e) + " @" + stackLine.trim()
        );
      }
      continue;
    }
    if (!collected) continue;
    explicitTotal += collected.explicitHits;
    for (var j = 0; j < collected.items.length; j++) result.push(collected.items[j]);
  }

  var payload = {
    items: result,
    explicit: explicitTotal,
    frames: scopes.length - 1,
    cfg: CFG.id || "generic",
    // 顶层视口高度（CSS px）：会话侧「阅读位置优先」的锚点基准（视口高度 35%）。
    vh: (window.innerHeight || 0),
    errors: (window.__mtScanErrors || []).slice(0, 6)
  };
  window.__mtScanErrors = [];
  return JSON.stringify(payload);
})()
""".trimIndent()

    /**
     * 取「页面里那份像素」的脚本 —— 给 `blob:` / `data:` 与 `<canvas>` 阅读器用。
     *
     * ## 为什么必须有这条路径
     *
     * Kakao Page、Lezhin、哔哩哔哩漫画这类站点把图片字节在 JS 里解出来（哔哩哔哩甚至
     * 把前 0x5010 字节做了 AES-CBC 加密），再 `URL.createObjectURL` 成 `blob:` 交给
     * `<img>`。`blob:` 只在本页的 origin 里有效 —— Kotlin 侧用 OkHttp 根本下载不了，
     * 地址栏里也没有可下载的 URL。唯一能拿到像素的办法就是**让页面自己把它交出来**：
     * 把 blob 图（或 canvas）画进一张临时 canvas，再 `toDataURL`。
     *
     * ## 为什么分片回传
     *
     * `evaluateJavascript` 的结果要按字符串回传，几 MB 的 base64 一次性给过去容易
     * 把 WebView 的 IPC 顶爆。这里按 [chunkChars] 一片一片取，Kotlin 侧拼回完整
     * data URL（[ChunkExtractJs]）。
     *
     * ## 会失败的情况（必须让调用方知道）
     *
     * - **画布被污染**：站点从跨域 CDN 取图且没给 `Access-Control-Allow-Origin` 时，
     *   `toDataURL` 抛 `SecurityError`。漫画 CDN 基本都不给 CORS 头，所以 canvas
     *   阅读器**大概率拿不到像素** —— 这时返回 `reason=tainted`，由上层退回截屏链路，
     *   而不是假装成功。这条限制是浏览器的安全模型，不是实现缺陷。
     * - **WebGL 画布**：站点没开 `preserveDrawingBuffer` 时导出的是空白图，
     *   这种情况无法从外部补救（无法追溯修改已创建的上下文）。
     */
    fun extractDataUrlJs(
        imageId: String,
        chunkOffset: Int,
        chunkChars: Int = DEFAULT_CHUNK_CHARS
    ): String {
        val payload = JSONObject().apply {
            put("id", imageId)
            put("at", chunkOffset)
            put("len", chunkChars)
        }.toString()
        return """
(function(){
  if (!window.__mtExtract) return JSON.stringify({ok:false,reason:"extractor_missing"});
  var p = ${jsLiteral(payload)};
  try { return window.__mtExtract(JSON.parse(p)); }
  catch (e) { return JSON.stringify({ok:false,reason:"threw",message:String(e && e.message || e)}); }
})()
""".trimIndent()
    }

    /** 单次回传的 base64 字符数。约 256KB 原文/片，够大以减少往返、又不至于顶爆 IPC。 */
    const val DEFAULT_CHUNK_CHARS = 350_000

    /** 拼回完整 data URL 时用的前缀（`extractDataUrlJs` 只回传逗号之后的 base64）。 */
    const val DATA_URL_PREFIX = "data:image/jpeg;base64,"

    /** JSON → JS 单引号字符串字面量（`evaluateJavascript` 的源码里不能出现裸引号）。 */
    fun jsLiteral(value: String): String {
        val literal = StringBuilder(value.length + 8)
        literal.append('\'')
        value.forEach { ch ->
            when (ch) {
                '\\' -> literal.append("\\\\")
                '\'' -> literal.append("\\'")
                '\n' -> literal.append("\\n")
                '\r' -> literal.append("\\r")
                // U+2028 / U+2029 是合法 JSON、却是 JS 的换行符：内联进脚本会把语句
                // 断成两行（`evaluateJavascript` 报语法错）。它们都大于 ' '，
                // 只判控制字符挡不住，必须单独处理。
                '\u2028' -> literal.append("\\u2028")
                '\u2029' -> literal.append("\\u2029")
                else ->
                    if (ch < ' ') {
                        literal.append("\\u").append(String.format("%04x", ch.code))
                    } else {
                        literal.append(ch)
                    }
            }
        }
        literal.append('\'')
        return literal.toString()
    }

    /**
     * 页面侧像素提取器（幂等安装）。
     *
     * 与扫描脚本分开注入：扫描脚本每轮会话都跑，提取器只在真的遇到 blob/canvas 时
     * 才需要，装一次即可。返回 `chunk`（base64 片段）/ `total` / `done` 或 `reason`。
     */
    val EXTRACTOR_JS: String = """
(function () {
  if (window.__mtExtract) return 1;

  window.__mtExtract = function (req) {
    var id = String((req && req.id) || "");
    var at = Number((req && req.at) || 0);
    var len = Number((req && req.len) || 350000);
    var el = document.querySelector('[data-mt-id="' + id + '"]');
    if (!el && id) el = document.getElementById(id);
    if (!el) return JSON.stringify({ ok: false, reason: "no_target" });

    var cacheKey = id;
    var store = window.__mtExtractCache || (window.__mtExtractCache = {});
    var entry = store[cacheKey];

    if (!entry) {
      var tag = (el.tagName || "").toLowerCase();
      var width = 0;
      var height = 0;
      if (tag === "canvas") {
        width = el.width || 0;
        height = el.height || 0;
      } else {
        width = el.naturalWidth || 0;
        height = el.naturalHeight || 0;
      }
      if (!(width > 0) || !(height > 0)) {
        return JSON.stringify({ ok: false, reason: "zero_size" });
      }
      // 超大画布先按比例缩到长边 2400：OCR 用不到更高分辨率，
      // 而 base64 体积是随像素数平方级增长的（这也和 Kotlin 侧 decodeCapped 同一口径）。
      var longSide = Math.max(width, height);
      var scale = longSide > 2400 ? 2400 / longSide : 1;
      var cw = Math.max(1, Math.round(width * scale));
      var chh = Math.max(1, Math.round(height * scale));
      var canvas = document.createElement("canvas");
      canvas.width = cw;
      canvas.height = chh;
      var ctx = canvas.getContext("2d");
      if (!ctx) return JSON.stringify({ ok: false, reason: "no_2d_context" });
      try {
        if (tag === "canvas") {
          ctx.drawImage(el, 0, 0, cw, chh);
        } else if (el.complete && el.naturalWidth > 0) {
          ctx.drawImage(el, 0, 0, cw, chh);
        } else {
          return JSON.stringify({ ok: false, reason: "not_loaded" });
        }
      } catch (e) {
        return JSON.stringify({ ok: false, reason: "draw_failed", message: String(e && e.message || e) });
      }
      var dataUrl = "";
      try {
        dataUrl = canvas.toDataURL("image/jpeg", 0.92);
      } catch (e) {
        // 画布被跨域图污染：浏览器的安全模型不允许导出像素，只能换截屏链路。
        return JSON.stringify({ ok: false, reason: "tainted", message: String(e && e.message || e) });
      }
      if (!dataUrl || dataUrl.indexOf("data:image") !== 0) {
        return JSON.stringify({ ok: false, reason: "empty_export" });
      }
      var comma = dataUrl.indexOf(",");
      entry = {
        base64: comma >= 0 ? dataUrl.substring(comma + 1) : "",
        mime: dataUrl.substring(5, Math.max(5, comma)).split(";")[0]
      };
      store[cacheKey] = entry;
      canvas.width = 0;
      canvas.height = 0;
    }

    var total = entry.base64.length;
    var chunk = at < total ? entry.base64.substring(at, at + len) : "";
    var done = at + chunk.length >= total;
    if (done) delete store[cacheKey];
    return JSON.stringify({
      ok: true,
      chunk: chunk,
      total: total,
      next: at + chunk.length,
      done: done,
      mime: entry.mime || "image/jpeg"
    });
  };

  return 1;
})()
""".trimIndent()
}


