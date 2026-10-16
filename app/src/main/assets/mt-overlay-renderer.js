/*!
 * MtOverlay —— 内嵌浏览器「译文 DOM 覆盖层」渲染器
 *
 * 移植自 C:\homework\translator 扩展的渲染层（styles.css + rendering/* +
 * content/modules/lifecycle-*），适配 Android WebView：
 *   - 场景数据由 Kotlin 侧（BrowserSceneBuilder）下发，全部百分比坐标；
 *   - 清理图走 https://mtlocal.overlay/clean/<token> 拦截通道（短 URL，天然避开
 *     Chromium 行内样式单个值约 2MB 的静默上限；dataURL → blob URL 出口仍保留兜底）；
 *   - OCR 暂不提供倾斜四边形时，遮罩按轴对齐框渲染（可降级路径，不报错）。
 *
 * 数据流：applySceneJson(payload) → RenderScene(冻结) → cover 层全部挂载 → text 层
 * 全部挂载 → 逐帧只同步 root 的 left/top/width/height（相对层原点）。
 */
(function () {
  "use strict";
  if (window.MtOverlay) return;

  // ---------------------------------------------------------------------------
  // 常量（对齐扩展 reader-state.js 的同名口径）
  // ---------------------------------------------------------------------------
  var FONT_MIN = 10;
  var FONT_MAX = 52;
  var FONT_ABS_MAX = 120;
  /**
   * 字号**绝对下限**（2026-10-12 用户规则「译文装不下原文位置时，自适应缩减字号」）。
   *
   * 此前二分下限就是 FONT_MIN=10：10px 仍装不下 → 走 `expandForTextOverflow`
   * 把节点撑出气泡框 —— 译文叠到邻近气泡/面板上（用户 2026-10-12 截图）。
   * 现在二分一路缩到 8px（与画布渲染 `BubbleImageRenderer.MIN_FONT_SIZE_PX` 同档）
   * 才允许撑开，撑开从「常规兜底」降级成「极端兜底」。
   */
  var FONT_ABS_MIN = 8;
  var FONT_BASE_RATIO = 0.78;          // 拿不到原字高时：起点 = 框高 × 0.78
  var FONT_BINARY_STEPS = 9;
  var FONT_SAFETY = 0.96;              // 横排安全系数
  var FONT_SAFETY_VERTICAL = 0.92;
  var FONT_ORIGINAL_SCALE = 1.08;      // 原字高 ×1.08 = 起点/上限
  var FONT_CACHE_MAX = 600;
  var STROKE_RATIO = 0.085;            // 描边宽 = 字号 × 0.085，clamp [1.8, 4.2]
  var COVER_TILT_TOLERANCE_DEG = 0.5;
  /**
   * 「OCR 的 rotation_deg 是不是真在说这块文字歪了」的**旋转噪声地板**（对齐参考项目
   * `cover-geometry.js` 的同名常量，2026-10-14 移植）。
   *
   * 与 [COVER_TILT_TOLERANCE_DEG] 分工不同：那个判定的是**四边形本身**是否轴对齐，
   * 这个判定的是 rotation_deg 这条独立证据值不值得相信。参考项目现场（Lezhin 跨页气泡）：
   * rotation_deg = -0.6° 是纯粹的识别噪声（0.6° 在 122px 高的框上只挪约 1px），
   * 却让「斜排」成立 → 盖板按字形四边形裁剪，原文直接露在译文下面。
   * 真正的斜排 SFX 远大于这个角度（夹具实测 12°/20°/35°），所以地板取 2°：
   * 低于它一律按轴对齐处理（不旋转、不裁剪）。
   */
  var COVER_ROTATION_NOISE_DEG = 2;
  // 遮罩外扩档位（见 resolveCoverQuad）。**默认 0**：外扩不在这里做。
  // 2026-09-30 用户要求「盖板不进行扩展，要扩也只是几个像素，顶多遮住 OCR 没识别到的像素点」——
  // 于是外扩改成宿主（Kotlin）按**像素**算好，写进 `polygon` / `region_polygon` / `cover_box`：
  // 百分比外扩在 x/y 上共用同一比例，而 x%、y% 的像素尺度不同，算出来的像素数两个方向并不相等。
  var COVER_PADDING_BASE = 0.05;
  var COVER_PADDING_MAX = 1.2;
  /**
   * 遮罩外扩档位（见 resolveCoverQuad）。**现在恒为 0**：
   * 外扩已改成宿主按像素算好写进 `polygon` / `region_polygon` / `cover_box`。
   * 保留这个旋钮是为了让 `_resolveCoverQuad` 的单测还能喂非 0 值。
   */
  var COVER_PADDING_DEFAULT = 0;
  var RENDER_ROTATION_VERTICAL_MIN_DEG = 60;
  /**
   * 接缝带允许的两张切片间隙（px）—— 见 [resolveSeamBand]。
   * 超过它才判定「两张图真的不相接」，退回两半渲染。
   */
  var SEAM_MAX_PAGE_GAP_PX = 3;

  // ---------------------------------------------------------------------------
  // 基础工具
  // ---------------------------------------------------------------------------
  function clamp(value, min, max) {
    var n = Number(value);
    if (!Number.isFinite(n)) return min;
    return Math.min(max, Math.max(min, n));
  }

  function isDataUrl(value) {
    return /^data:image\//i.test(String(value || ""));
  }

  // Chromium 对行内 style 的单个 CSS 值有约 2MB 静默上限（写入不报错、读回空串）。
  // 本工程清理图主走 mtlocal 短 URL，不会触线；这里保留 dataURL → Blob URL 的统一
  // 出口兜底：同一 dataURL 恒返回同一 blob:URL，保证「值相等不重写」的幂等判断成立。
  var MASK_INLINE_STYLE_SAFE_LIMIT = 1500000;
  var MASK_BLOB_URL_CACHE_LIMIT = 16;
  var maskBlobUrlCache = new Map();

  function dataUrlToObjectUrl(dataUrl) {
    try {
      var comma = dataUrl.indexOf(",");
      if (comma < 0) return "";
      var meta = dataUrl.slice(5, comma);
      var mime = meta.split(";")[0] || "image/png";
      var body = dataUrl.slice(comma + 1);
      var bytes;
      if (/;base64/i.test(meta)) {
        var binary = atob(body);
        bytes = new Uint8Array(binary.length);
        for (var i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
      } else {
        bytes = new TextEncoder().encode(decodeURIComponent(body));
      }
      if (typeof URL === "undefined" || typeof URL.createObjectURL !== "function") return "";
      return URL.createObjectURL(new Blob([bytes], { type: mime }));
    } catch (err) {
      return "";
    }
  }

  function maskCssValue(value) {
    var raw = String(value || "");
    if (!raw) return "";
    if (isDataUrl(raw)) {
      if (raw.length <= MASK_INLINE_STYLE_SAFE_LIMIT) return 'url("' + raw + '")';
      var cached = maskBlobUrlCache.get(raw);
      if (cached) {
        maskBlobUrlCache.delete(raw);
        maskBlobUrlCache.set(raw, cached);
        return 'url("' + cached + '")';
      }
      var blobUrl = dataUrlToObjectUrl(raw);
      if (!blobUrl) return 'url("' + raw + '")';
      maskBlobUrlCache.set(raw, blobUrl);
      while (maskBlobUrlCache.size > MASK_BLOB_URL_CACHE_LIMIT) {
        var oldestKey = maskBlobUrlCache.keys().next().value;
        var oldestUrl = maskBlobUrlCache.get(oldestKey);
        maskBlobUrlCache.delete(oldestKey);
        try { URL.revokeObjectURL(oldestUrl); } catch (err) { /* 回收失败不影响遮罩 */ }
      }
      return 'url("' + blobUrl + '")';
    }
    // http(s) 短 URL（mtlocal 拦截通道）原样包 url()
    return 'url("' + raw + '")';
  }

  function djb2Hash(text) {
    var hash = 5381;
    var raw = String(text || "");
    for (var i = 0; i < raw.length; i += 1) {
      hash = ((hash << 5) + hash + raw.charCodeAt(i)) | 0;
    }
    return (hash >>> 0).toString(36);
  }

  // ---------------------------------------------------------------------------
  // 遮罩几何（rendering/cover-geometry.js 移植，纯数学，单位由调用方决定）
  // ---------------------------------------------------------------------------

  function normalizeBox(value) {
    if (!value || typeof value !== "object") return null;
    var x = Number(value.x != null ? value.x : value.left);
    var y = Number(value.y != null ? value.y : value.top);
    var w = Number(value.w != null ? value.w : value.width);
    var h = Number(value.h != null ? value.h : value.height);
    if (![x, y, w, h].every(Number.isFinite) || !(w > 0) || !(h > 0)) return null;
    return { x: x, y: y, w: w, h: h };
  }

  function normalizePoints(points, minimum) {
    if (!Array.isArray(points) || points.length < (minimum || 4)) return null;
    var normalized = points.slice(0, 4).map(function (point) {
      return { x: Number(point && point.x), y: Number(point && point.y) };
    });
    var ok = normalized.every(function (point) {
      return Number.isFinite(point.x) && Number.isFinite(point.y);
    });
    return ok ? normalized : null;
  }

  function boundsOfPoints(points) {
    var normalized = normalizePoints(points, 1);
    if (!normalized) return null;
    var xs = normalized.map(function (point) { return point.x; });
    var ys = normalized.map(function (point) { return point.y; });
    var x = Math.min.apply(null, xs);
    var y = Math.min.apply(null, ys);
    var w = Math.max.apply(null, xs) - x;
    var h = Math.max.apply(null, ys) - y;
    return w > 0 && h > 0 ? { x: x, y: y, w: w, h: h } : null;
  }

  /** 绕外接框中心等比外扩：方向无关，对任意四边形都成立。 */
  function inflatePolygonAboutCenter(points, scale) {
    var normalized = normalizePoints(points, 1);
    var bounds = normalized && boundsOfPoints(normalized);
    if (!bounds) return null;
    var factor = Number.isFinite(scale) && scale > 0 ? scale : 1;
    var centerX = bounds.x + bounds.w / 2;
    var centerY = bounds.y + bounds.h / 2;
    return normalized.map(function (point) {
      return {
        x: centerX + (point.x - centerX) * factor,
        y: centerY + (point.y - centerY) * factor
      };
    });
  }

  /** 轴对齐框 → 绕自身中心缩放后旋转的四边形（只有 rotation_deg、没有倾斜 polygon 时用）。 */
  function rotateBoxQuad(box, rotationDeg, scale) {
    var normalized = normalizeBox(box);
    if (!normalized) return null;
    var factor = Number.isFinite(scale) && scale > 0 ? scale : 1;
    var centerX = normalized.x + normalized.w / 2;
    var centerY = normalized.y + normalized.h / 2;
    var halfW = normalized.w / 2 * factor;
    var halfH = normalized.h / 2 * factor;
    var radians = (Number(rotationDeg) || 0) * Math.PI / 180;
    var cos = Math.cos(radians);
    var sin = Math.sin(radians);
    return [[-halfW, -halfH], [halfW, -halfH], [halfW, halfH], [-halfW, halfH]].map(function (pair) {
      return {
        x: centerX + pair[0] * cos - pair[1] * sin,
        y: centerY + pair[0] * sin + pair[1] * cos
      };
    });
  }

  /** 四边形相对坐标轴的倾斜角（折到 ±45°）：轴对齐矩形返回 0。 */
  function polygonTiltDeg(points) {
    var normalized = normalizePoints(points, 4);
    if (!normalized) return 0;
    return normalized.reduce(function (max, point, index) {
      var next = normalized[(index + 1) % normalized.length];
      var dx = next.x - point.x;
      var dy = next.y - point.y;
      if (!(Math.abs(dx) > 0 || Math.abs(dy) > 0)) return max;
      var deg = Math.atan2(dy, dx) * 180 / Math.PI;
      while (deg >= 90) deg -= 180;
      while (deg < -90) deg += 180;
      deg = Math.abs(deg) <= 45 ? Math.abs(deg) : 90 - Math.abs(deg);
      return Math.max(max, deg);
    }, 0);
  }

  var LONG_EDGE_MIN_ASPECT = 1.2;

  /**
   * 四边形倾角（度，折到 ±90°）：先看歪没歪，歪了取最长边作为文字行方向。
   * 轴对齐框（倾斜 ≤ 0.5°）一律返回 0；近正方形判断不出行方向时取更接近水平的边。
   * scaleX/scaleY 把百分比坐标换算成像素比例（百分比角度与像素角度本来就不同）。
   */
  function polygonRotationDeg(points, scaleX, scaleY) {
    var normalized = normalizePoints(points, 4);
    if (!normalized) return 0;
    if (polygonTiltDeg(normalized) <= COVER_TILT_TOLERANCE_DEG) return 0;
    var sx = Number.isFinite(Number(scaleX)) && Number(scaleX) > 0 ? Number(scaleX) : 1;
    var sy = Number.isFinite(Number(scaleY)) && Number(scaleY) > 0 ? Number(scaleY) : 1;
    var edges = normalized.map(function (current, index) {
      var next = normalized[(index + 1) % normalized.length];
      var dx = (next.x - current.x) * sx;
      var dy = (next.y - current.y) * sy;
      var deg = Math.atan2(dy, dx) * 180 / Math.PI;
      while (deg >= 90) deg -= 180;
      while (deg < -90) deg += 180;
      return { length: Math.hypot(dx, dy), deg: deg };
    }).filter(function (edge) { return edge.length > 0; });
    if (edges.length === 0) return 0;
    var longest = edges[0];
    var shortest = edges[0];
    edges.forEach(function (edge) {
      if (edge.length > longest.length) longest = edge;
      if (edge.length < shortest.length) shortest = edge;
    });
    if (longest.length >= shortest.length * LONG_EDGE_MIN_ASPECT) return longest.deg;
    return edges.reduce(function (best, edge) {
      return Math.abs(edge.deg) < Math.abs(best.deg) ? edge : best;
    }, edges[0]).deg;
  }

  /**
   * 倾角取证：显式 rotationDeg 优先（0 视作「没有明确信息」），否则在候选四边形里
   * 「哪个真的倾斜就用哪个」。四条渲染路径共用这一个口径，避免文字倾斜、遮罩水平。
   */
  function resolveTiltRotationDeg(rotationDeg, polygons, scaleX, scaleY) {
    var explicit = Number(rotationDeg);
    if (Number.isFinite(explicit) && Math.abs(explicit) > COVER_TILT_TOLERANCE_DEG) return explicit;
    var list = Array.isArray(polygons) ? polygons : [];
    for (var i = 0; i < list.length; i += 1) {
      var angle = polygonRotationDeg(list[i], scaleX, scaleY);
      if (Math.abs(angle) > COVER_TILT_TOLERANCE_DEG) return angle;
    }
    return 0;
  }

  /**
   * 几何自洽：|倾角| ≥ 60° 且框宽 > 框高 → 判为误判（OCR 把多行横排读成竖排），按水平渲染。
   * 真正的竖排（框高大于宽）与斜排 SFX（|倾角| < 60°）不受影响。
   */
  function sanitizeRenderRotationDeg(rotation, boxWidth, boxHeight) {
    var angle = Number(rotation) || 0;
    if (Math.abs(angle) < RENDER_ROTATION_VERTICAL_MIN_DEG) return angle;
    var width = Number(boxWidth) || 0;
    var height = Number(boxHeight) || 0;
    if (!(width > 0 && height > 0)) return angle;
    return width > height ? 0 : angle;
  }

  /**
   * 遮罩四边形：
   * - polygon 本身倾斜 → 直接用（外扩后），贴着字形；
   * - polygon 是轴对齐框但 rotation_deg 说它歪 → 按倾角旋转该框；
   * - 都没有 → 轴对齐框。
   * ⚠️ 绝不拿 rotation_deg 与百分比 polygon 的夹角「比对是否一致」：两套坐标读出的
   *    角度本来就不同，误判成冲突会退回旋转外接框，把遮罩撑成包围盒。
   *
   * ⚠️ rotation_deg 有两条"不算斜排"的硬门槛（2026-10-14 移植参考项目 cover-geometry.js）：
   * - **|倾角| ≥ [RENDER_ROTATION_VERTICAL_MIN_DEG] 是竖排方向，不是"歪了"**：检测器对
   *   高而窄的框一律给 ±90°，照它旋转外接框会把竖列转成**横条** —— 盖板/清理图只盖上
   *   一条横带、原文大面积露出（用户 2026-10-14 截图：气泡原文透过译文可见），
   *   外框还会被撑成横跨画面的包围盒（"盖板太大"同源）。这种角度必须按四边形/原框走。
   * - **亚 [COVER_ROTATION_NOISE_DEG] 是识别噪声**：0.6° 在 120px 高的框上只挪 1px，
   *   旋转只会把盖板转歪、把原文边角露出来。
   */
  function resolveCoverQuad(options) {
    var padding = Number(options && options.padding) || 0;
    var scale = 1 + COVER_PADDING_BASE * clamp(padding, 0, COVER_PADDING_MAX);
    var rotation = Number(options && options.rotationDeg) || 0;
    var rotationTilted = Math.abs(rotation) > COVER_ROTATION_NOISE_DEG
      && Math.abs(rotation) < RENDER_ROTATION_VERTICAL_MIN_DEG;
    var polygonPoints = normalizePoints(options && options.polygon, 4);
    var polygonBox = polygonPoints && boundsOfPoints(polygonPoints);
    var normalizedBox = normalizeBox(options && options.box) || polygonBox;
    if (!polygonPoints && !normalizedBox) return null;
    var quad = null;
    var tilted = rotationTilted;
    var polygonTilted = Boolean(polygonPoints) && polygonTiltDeg(polygonPoints) > COVER_TILT_TOLERANCE_DEG;
    if (polygonPoints && rotationTilted && !polygonTilted) {
      quad = rotateBoxQuad(polygonBox, rotation, scale);
    } else if (polygonPoints) {
      quad = inflatePolygonAboutCenter(polygonPoints, scale);
      tilted = rotationTilted || polygonTilted;
    } else {
      // 只有框、没有四边形：竖排（≥60°）与噪声（≤2°）都不旋转 —— 竖排的框就是那一列，
      // 噪声的框本来就该是轴对齐的。
      quad = rotateBoxQuad(normalizedBox, rotationTilted ? rotation : 0, scale);
    }
    var bounds = boundsOfPoints(quad || []);
    if (!bounds) {
      var fallback = rotateBoxQuad(normalizedBox, rotationTilted ? rotation : 0, 1);
      var fallbackBounds = boundsOfPoints(fallback || []);
      return fallbackBounds
        ? { quad: fallback, bounds: fallbackBounds, rotationDeg: rotation, tilted: tilted }
        : null;
    }
    return { quad: quad, bounds: bounds, rotationDeg: rotation, tilted: tilted };
  }

  /** 四边形 → 相对 bounds 的 clip-path 百分比；退化时返回空串（调用方保留兜底遮罩）。 */
  function polygonClipPathPercent(points, bounds) {
    var normalized = normalizePoints(points, 3);
    var box = normalizeBox(bounds);
    if (!normalized || !box) return "";
    var values = normalized.map(function (point) {
      var x = (point.x - box.x) / box.w * 100;
      var y = (point.y - box.y) / box.h * 100;
      return Number.isFinite(x) && Number.isFinite(y)
        ? x.toFixed(2) + "% " + y.toFixed(2) + "%"
        : "";
    });
    return values.every(Boolean) ? "polygon(" + values.join(", ") + ")" : "";
  }

  /** region_polygon → 相对指定框的 clip-path（实底填充裁剪用）。 */
  function buildRegionClipPath(points, x, y, width, height) {
    if (!Array.isArray(points) || points.length < 3 || !(width > 0) || !(height > 0)) return "";
    var values = points.map(function (point) {
      var localX = (Number(point && point.x) - x) / width * 100;
      var localY = (Number(point && point.y) - y) / height * 100;
      return Number.isFinite(localX) && Number.isFinite(localY)
        ? localX.toFixed(2) + "% " + localY.toFixed(2) + "%"
        : "";
    });
    return values.every(Boolean) ? "polygon(" + values.join(", ") + ")" : "";
  }

  /**
   * Sutherland–Hodgman：把凸多边形裁到矩形内（跨页/分片遮罩按页取交用）。
   * 与参考项目 cover-geometry.js 的 `clipConvexPolygonToRect` 同口径：
   * 斜排文字压在两张图上时，每页的遮罩 =「倾斜四边形 ∩ 页矩形」。
   */
  function clipConvexPolygonToRect(points, rect) {
    var normalized = normalizePoints(points, 3);
    var box = normalizeBox(rect);
    if (!normalized || !box) return [];
    var right = box.x + box.w;
    var bottom = box.y + box.h;
    var boundaries = [
      { inside: function (p) { return p.x >= box.x; }, cut: function (a, b) { return cutVertical(a, b, box.x); } },
      { inside: function (p) { return p.x <= right; }, cut: function (a, b) { return cutVertical(a, b, right); } },
      { inside: function (p) { return p.y >= box.y; }, cut: function (a, b) { return cutHorizontal(a, b, box.y); } },
      { inside: function (p) { return p.y <= bottom; }, cut: function (a, b) { return cutHorizontal(a, b, bottom); } }
    ];
    var output = normalized;
    for (var i = 0; i < boundaries.length; i += 1) {
      var boundary = boundaries[i];
      var input = output;
      output = [];
      for (var index = 0; index < input.length; index += 1) {
        var current = input[index];
        var previous = input[(index + input.length - 1) % input.length];
        var currentInside = boundary.inside(current);
        var previousInside = boundary.inside(previous);
        if (currentInside) {
          if (!previousInside) output.push(boundary.cut(previous, current));
          output.push(current);
        } else if (previousInside) {
          output.push(boundary.cut(previous, current));
        }
      }
      if (output.length === 0) return [];
    }
    return output;
  }

  function cutVertical(a, b, x) {
    var dx = b.x - a.x;
    var ratio = Math.abs(dx) > Number.EPSILON ? (x - a.x) / dx : 0;
    return { x: x, y: a.y + (b.y - a.y) * ratio };
  }

  function cutHorizontal(a, b, y) {
    var dy = b.y - a.y;
    var ratio = Math.abs(dy) > Number.EPSILON ? (y - a.y) / dy : 0;
    return { x: a.x + (b.x - a.x) * ratio, y: y };
  }

  // ---------------------------------------------------------------------------
  // 样式注入（styles.css 覆盖层子集 + 宿主样式隔离）
  // ---------------------------------------------------------------------------

  var OVERLAY_CSS = [
    ".mt-overlay-layer{position:absolute;inset:0;z-index:2147483000;pointer-events:none;overflow:visible;",
    // 字号基准显式写死：宿主若用元素级选择器统一正文（div,li,a{font-size:13px}），
    // 元素选择器优先级高于继承，会把还没被 inline 字号覆盖的节点（例如遮罩层）压成 13px。
    "font-size:16px;line-height:normal;}",
    ".mt-overlay-root{position:absolute;pointer-events:none;contain:layout style;overflow:hidden;",
    "font-size:inherit;line-height:normal;}",
    // 接缝覆盖层（跨两张图一个气泡）：root 就是「接缝带」本体的矩形，两端各有一半落在
    // 相邻两张图上；abut 的两张图之间没有可裁的边界，所以**不能** overflow:hidden
    // （译文排版框按带尺寸自适应，撑开时不能被带边裁掉）。
    ".__mt_seam_root{overflow:visible;z-index:2;}",
    // 宿主页面文字样式隔离：页面常用元素级规则（div,li,a{font-size:13px;color:#333}）
    // 高于继承，会把覆盖层 inline 的 40~70px 字号与颜色全部改掉，而且测量探针同样被改，
    // 溢出永远测不出来、字号再也不会被收小。这里显式声明=原本的继承值，在正常站点是 no-op。
    // font-size 也一并声明：探针/遮罩在 inline 字号写入之前就要和宿主无关。
    ".mt-overlay-root,.mt-bubble,.mt-bubble-content,.mt-loading-card,.mt-loading-label{letter-spacing:normal;font-style:normal;",
    "text-transform:none;font-size:inherit;line-height:normal;}",
    // 截图翻译的译文层 = **纯展示**：整棵场景根（含气泡的 click 目标）禁用指针事件，
    // 触摸/滚动全部**穿透到页面**，不再影响站内交互（用户 2026-10-14 报「译文盖在上面、
    // 影响应用交互」）。点气泡看原文只保留在活翻译链路（那本来就是半透明探索层）。
    ".__mt_no_touch,.__mt_no_touch *{pointer-events:none!important;cursor:default!important;}",
    ".mt-bubble{position:absolute;box-sizing:border-box;max-width:none;max-height:none;",
    "display:flex;flex-direction:column;align-items:center;justify-content:center;",
    "font-family:\"Source Han Sans SC\",\"Noto Sans SC\",\"Noto Sans CJK SC\",sans-serif;",
    "font-weight:var(--mt-font-weight,500);border-radius:0.36em;padding:0.12em 0.34em;",
    "overflow:visible;cursor:pointer;user-select:none;pointer-events:auto;isolation:isolate;",
    "color:var(--mt-text-color,#111827);--mt-stroke-width:2px;--mt-base-transform:none;",
    "transform:var(--mt-base-transform);transition:opacity 0.15s ease;}",
    ".mt-bubble-content{display:block;width:100%;font-family:inherit;font-size:inherit;font-weight:inherit;",
    "color:inherit;text-align:center;white-space:pre-line;line-height:1.18;letter-spacing:normal;",
    "margin:0;padding:0;word-break:break-word;}",
    ".mt-bubble.mt-jp-vertical .mt-bubble-content{white-space:normal;line-height:1.34;}",
    ".mt-bubble.mt-jp-vertical{writing-mode:vertical-rl;text-orientation:upright;white-space:normal;",
    "line-height:1.34;padding:0.34em 0.12em;",
    "font-family:\"Noto Serif JP\",\"Noto Serif CJK JP\",serif;}",
    ".mt-bubble.mt-stream-enter{opacity:0;animation:mt-bubble-in 0.24s ease forwards;",
    "animation-delay:var(--mt-stream-delay,0ms);}",
    ".mt-bubble.mt-cover-only{color:transparent!important;pointer-events:none;cursor:default;",
    "-webkit-text-stroke:0 transparent!important;text-shadow:none!important;}",
    ".mt-cover-clip{position:absolute;box-sizing:border-box;overflow:hidden;clip-path:inset(0);pointer-events:none;}",
    ".mt-bubble.mt-text-layer{background:transparent!important;background-image:none!important;",
    "border-color:transparent!important;box-shadow:none!important;backdrop-filter:none!important;",
    "-webkit-backdrop-filter:none!important;}",
    ".mt-bubble.mt-text-layer::before{content:none!important;}",
    // 斜排实底把填充挪到裁剪层（世界坐标）后，节点自己的 ::before 必须闭嘴，否则画两遍。
    ".mt-bubble.mt-fill-outside::before{content:none!important;}",
    ".mt-fill-flat{position:absolute;z-index:-1;pointer-events:none;}",
    ".mt-bubble.mt-text-layer>.mt-fill-tilted{display:none!important;}",
    ".mt-measure-probe{position:fixed!important;left:-200vw!important;top:-200vh!important;",
    "visibility:hidden!important;opacity:0!important;pointer-events:none!important;z-index:-1!important;",
    "transform:none!important;justify-content:flex-start!important;}",
    ".mt-bg-solid{background:transparent;border:0;box-shadow:none;overflow:visible;}",
    ".mt-bg-solid::before{content:\"\";position:absolute;left:var(--mt-fill-left,0);top:var(--mt-fill-top,0);",
    "width:var(--mt-fill-width,100%);height:var(--mt-fill-height,100%);z-index:-1;",
    "background:var(--mt-adaptive-bg,rgba(255,255,255,0.96));clip-path:var(--mt-region-clip,inset(0));pointer-events:none;}",
    ".mt-fill-tilted{position:absolute;left:0;top:0;width:100%;height:100%;z-index:-1;pointer-events:none;}",
    ".mt-bg-transparent{background:rgba(255,255,255,0.45);border:1px solid rgba(15,23,42,0.3);",
    "backdrop-filter:blur(1.8px);-webkit-backdrop-filter:blur(1.8px);",
    "text-shadow:0 1px 0 rgba(255,255,255,0.5),0 0 2px rgba(255,255,255,0.72);}",
    // 拿不到清理图时保持透明，绝不画纯色补丁（实测纯色补丁比没有遮罩更糟）。
    ".mt-bg-none{background:transparent;background-image:var(--mt-cleaned-image,none);",
    "background-repeat:no-repeat;background-size:var(--mt-cleaned-size-x,auto) var(--mt-cleaned-size-y,auto);",
    "background-position:var(--mt-cleaned-position-x,0) var(--mt-cleaned-position-y,0);",
    "border:1px solid transparent;-webkit-text-stroke:var(--mt-stroke-width,2px) var(--mt-stroke-color,#fff);",
    "paint-order:stroke fill;text-shadow:0 0 1px var(--mt-stroke-color,#fff);}",
    // **不透明实底盖板**（宿主显式下发 `flat_cover`，见 createCoverNode）：速度优先路线上宁可
    // 用一块纯色把原文彻底盖住，也不要「贴片边缘留残影」。选择器比 .mt-bg-none 更具体，
    // 也压过 data-cleaned-layers="2" 的多层清理图规则。
    ".mt-bubble.mt-cover-only[data-cover-fill=\"flat\"],",
    ".mt-bubble.mt-cover-only[data-cover-fill=\"flat\"]::before{background:var(--mt-adaptive-bg,rgba(255,255,255,0.96))!important;",
    "background-image:none!important;}",
    // 背景单色且原文无描边 → 整条描边关掉（2026-09-30 用户要求）。
    // ⚠️ 只把 --mt-stroke-width 置 0 还不够：`.mt-bg-none` 还有一条 1px 的白 text-shadow，
    // 留着就是一圈极浅的白晕，10px 小字上依然会糊掉笔画。选择器比 `.mt-bg-none` 多一个类，稳赢。
    ".mt-bubble.mt-no-stroke{-webkit-text-stroke:0 transparent;text-shadow:none!important;}",
    // §11 三级背景降级第 2/3 级：同一遮罩里带内区域用复合清理图、带外区域用整页清理图。
    // 选择器比 .mt-bg-none 更具体，因此只在显式置 data-cleaned-layers="2" 时生效；
    // CSS 多背景第一层盖在最上面，正好让带内复合图压住整页图，两层各管一段。
    ".mt-bubble[data-cleaned-layers=\"2\"]{background-image:var(--mt-cleaned-image),var(--mt-cleaned-page-image);",
    "background-repeat:no-repeat,no-repeat;",
    "background-size:var(--mt-cleaned-size-x,auto) var(--mt-cleaned-size-y,auto),var(--mt-page-size-x,auto) var(--mt-page-size-y,auto);",
    "background-position:var(--mt-cleaned-position-x,0) var(--mt-cleaned-position-y,0),var(--mt-page-position-x,0) var(--mt-page-position-y,0);}",
    ".mt-overlay-root.mt-show-source .mt-bubble{color:transparent!important;background:transparent!important;",
    "background-image:none!important;border-color:transparent!important;box-shadow:none!important;",
    "-webkit-text-stroke:0 transparent!important;text-shadow:none!important;opacity:0;}",
    ".mt-overlay-root.mt-show-source .mt-bubble::before{content:none;}",
    // 「显示原文」必须把**所有**遮罩层一起藏掉。
    // 历史 bug（2026-09-29 用户反馈「点击之后还有字体，没有恢复原文」）：
    // 斜排实底的填充块 `.mt-fill-flat` 是裁剪层里的**独立 div**，不继承 `.mt-bubble` 的规则，
    // 只藏 `.mt-bubble` 会让它继续盖住原文。
    // ⚠️ 这里必须用 opacity 而**不能**用 display:none —— 点击监听器挂在文字节点自身，
    // display:none 会让它再也收不到第二次点击，于是「点一次能看原文、再点回不去译文」
    // （2026-09-29 用户实测反馈）。opacity:0 既看不见、又保留命中区域，双向切换才成立。
    ".mt-overlay-root.mt-show-source .mt-cover-clip,",
    ".mt-overlay-root.mt-show-source .mt-fill-flat,",
    ".mt-overlay-root.mt-show-source .mt-fill-tilted,",
    ".mt-overlay-root.mt-show-source .mt-text-layer{opacity:0!important;}",
    // --- loading 框（2026-10-02，对齐参考项目 `C:\\homework\\translator` 的 .mt-loading-card）---
    // 正在处理的那张图上方浮一个半透明胶囊：让用户知道「这张图在处理」，而不是盯着原图干等。
    ".mt-loading-root{position:absolute;pointer-events:none;z-index:2147483002;}",
    ".mt-loading-card{display:inline-flex;align-items:center;gap:6px;",
    "min-width:118px;max-width:min(74%,300px);padding:7px 12px;border-radius:999px;",
    "font-size:12px;line-height:1.2;text-align:center;color:#0f172a;",
    "background:rgba(255,255,255,0.85);border:1px solid rgba(15,23,42,0.2);",
    "box-shadow:0 3px 10px rgba(15,23,42,0.12);",
    "backdrop-filter:blur(1px);-webkit-backdrop-filter:blur(1px);",
    "white-space:nowrap;overflow:hidden;text-overflow:ellipsis;",
    "animation:mt-loading-pulse 1.05s ease-in-out infinite;}",
    ".mt-loading-spinner{flex:0 0 auto;width:10px;height:10px;border-radius:50%;",
    "border:2px solid rgba(15,23,42,0.18);border-top-color:#3b82f6;",
    "animation:mt-loading-spin 0.7s linear infinite;}",
    ".mt-loading-label{overflow:hidden;text-overflow:ellipsis;}",
    "@keyframes mt-loading-pulse{0%,100%{opacity:0.72;}50%{opacity:1;}}",
    "@keyframes mt-loading-spin{to{transform:rotate(360deg);}}",
    "@keyframes mt-bubble-in{from{opacity:0;transform:var(--mt-base-transform) scale(0.985);}",
    "to{opacity:1;transform:var(--mt-base-transform) scale(1);}}"
  ].join("\n");

  var cssInjected = false;
  function injectCss() {
    if (cssInjected) return;
    var style = document.createElement("style");
    style.id = "mt-overlay-style";
    style.textContent = OVERLAY_CSS;
    (document.head || document.documentElement).appendChild(style);
    cssInjected = true;
  }

  /**
   * 当前场景的清理图 URL（渲染是同步的，所以用模块级变量传递，避免逐气泡透传）。
   * - composite：捕获带内专用的复合清理图（payload.cleaned_url）
   * - page：整页清理图（payload.page_cleaned_url，可选；用于带外区域补背景）
   */
  var sceneCleanedUrls = { composite: null, page: null };

  // ---------------------------------------------------------------------------
  // 排版规则（lifecycle-bubble.js 移植）
  // ---------------------------------------------------------------------------

  function cleanRenderableText(text) {
    return String(text || "").replace(/[\r\t]+/g, " ").trim();
  }

  function normalizeBgType(value) {
    var raw = String(value || "").trim().toLowerCase();
    if (raw === "solid") return "solid";
    if (raw === "transparent") return "transparent";
    return "none";
  }

  function normalizeAlignment(value) {
    var raw = String(value || "").trim().toLowerCase();
    if (raw === "left" || raw === "right") return raw;
    return "center";
  }

  function normalizeFontWeight(value, fallback) {
    // ⚠️ 必须显式拦 null/空串：`Number(null) === 0` 且 isFinite(0) 为真 —— 场景 JSON 里
    // host 下发 `"font_weight":null`，旧实现于是把 null 钳成 **100（极细）**，fallback 600
    // 永远轮不到（2026-10-16 真机 CDP 实测：节点 --mt-font-weight=100）。这就是用户
    // 「字体加粗一点」的直接原因。
    if (value === null || value === undefined || value === "") return fallback;
    var n = Number(value);
    if (!Number.isFinite(n)) return fallback;
    n = Math.round(n / 100) * 100;
    return clamp(n, 100, 900);
  }

  function looksLikeCjkText(text) {
    return /[\u3400-\u9fff\u3040-\u30ff\uac00-\ud7af]/.test(String(text || ""));
  }

  function looksLikeJapaneseText(text) {
    var raw = String(text || "").trim();
    if (!raw) return false;
    if (/[\u3040-\u30ff\u31f0-\u31ff\u30fc\uff66-\uff9f]/.test(raw)) return true;
    if (/[\u3001\u3002\u30fb\u300c\u300d\u300e\u300f\u301c]/.test(raw) && /[\u4e00-\u9fff]/.test(raw)) return true;
    return false;
  }

  /**
   * 断行：已知原文行数时用全局最优（DP）替换贪心换行。
   * 代价 = Σ(行长 - 目标行长)² + 单字行 120 + 末行≤2字 45 + CJK 非标点处断行 4。
   * 贪心换行会让最后一行只剩一个字 —— 气泡排版最常见的丑。
   */
  function formatTranslationForOriginalLines(text, requestedLines) {
    var raw = String(text || "").replace(/\s+/g, " ").trim();
    var lineCount = Math.max(1, Math.min(8, Math.round(Number(requestedLines) || 1)));
    if (!raw || lineCount <= 1 || raw.indexOf("\n") >= 0) return raw;
    var isCjk = /[\u3400-\u9fff]/.test(raw);
    var units = isCjk ? Array.from(raw.replace(/\s+/g, "")) : raw.split(/\s+/).filter(Boolean);
    if (units.length <= lineCount) return raw;

    var targetLength = units.length / lineCount;
    var punctuation = /[，。！？、；：,.!?;:]/;
    var states = [];
    for (var s = 0; s <= lineCount; s += 1) states.push(new Map());
    states[0].set(0, { score: 0, breaks: [] });
    for (var line = 1; line <= lineCount; line += 1) {
      var current = states[line];
      for (var end = line; end <= units.length - (lineCount - line); end += 1) {
        var best = null;
        for (var start = line - 1; start < end; start += 1) {
          var previous = states[line - 1].get(start);
          if (!previous) continue;
          var length = end - start;
          var score = previous.score + Math.pow(length - targetLength, 2);
          if (length === 1) score += 120;
          if (line === lineCount && length <= 2) score += 45;
          if (isCjk && start > 0 && !punctuation.test(units[start - 1])) score += 4;
          var candidate = { score: score, breaks: previous.breaks.concat([end]) };
          if (!best || candidate.score < best.score) best = candidate;
        }
        if (best) current.set(end, best);
      }
    }
    var result = states[lineCount].get(units.length);
    if (!result) return raw;
    var rows = [];
    var begin = 0;
    result.breaks.forEach(function (at) {
      rows.push(units.slice(begin, at).join(isCjk ? "" : " "));
      begin = at;
    });
    return rows.filter(Boolean).join("\n");
  }

  // ---------------------------------------------------------------------------
  // 像素感知均衡断行（2026-10-14）
  //
  // 为什么加：字符数均衡（上面的 formatTranslationForOriginalLines）只解决「行数对」，
  // 不解决三件事 ——
  // 1. 行数 = 1 的气泡完全不参与（全靠 CSS 贪心换行 → 末行一两个字）；
  // 2. 按**字数**均衡，不是按**渲染宽度**均衡（CJK 标点/英数字宽度不同，实际仍不均）；
  // 3. 断点可以落在**词中间**（用户截图：「讨｜厌它」被劈成两行）。
  //
  // 这一层用「分词 + 实测宽度 + 动态规划」替换贪心：断点只在词边界，标点优先，
  // 行首/行尾禁则，且每行宽度不超过容器可用宽度。
  // ---------------------------------------------------------------------------

  /** 行首不能出现的收尾标点（会挂在前一行末尾）。 */
  var BREAK_CLOSING_PUNCT = /[。，、！？；：）］｝」』〉》’”…‥・,!?;:)\]}]/;
  /** 行尾不能出现的开引/开括号（会挂到下一行开头）。 */
  var BREAK_OPENING_PUNCT = /[（［｛「『〈《“‘〔【(\[{]/;
  /** 断点落在标点之后是「自然断点」：给折扣，优先在标点后断行。 */
  var BREAK_PUNCT_BONUS = 60;
  /** 行首出现收尾标点 / 行尾出现开括号：罚到几乎不可能（除非无解）。 */
  var BREAK_FORBIDDEN_PENALTY = 600;
  /** 单单元成行（一个词/一个字占一行）：丑，罚分。 */
  var BREAK_SINGLE_UNIT_PENALTY = 120;
  /** 末行太短（≤2 字符）：丑，罚分。 */
  var BREAK_SHORT_LAST_PENALTY = 45;
  /** 超宽（行宽超过可用宽度）：尽量别发生；实在无解时允许（CSS 会再折，字号拟合兜底）。 */
  var BREAK_OVERFLOW_WEIGHT = 8;
  var BREAK_OVERFLOW_FLAT = 400;
  /** 均衡断行最多支持几行（与字符数 DP 的上限一致）。 */
  var BREAK_MAX_LINES = 8;
  /** `.mt-bubble` 的水平内边距（em，见 OVERLAY_CSS 的 padding:0.12em 0.34em）。 */
  var BUBBLE_PADDING_X_EM = 0.34;
  /** CJK 字符判定（含假名/谚文），用于「超宽单元拆字」。 */
  var CJK_CHAR_RE = /[\u3400-\u9fff\u3040-\u30ff\uac00-\ud7af]/;

  /**
   * 分词（Intl.Segmenter，词典分词）：返回 `[{text, sepAfter, wordLike}]`。
   * 段后空白挂到前一段（`sepAfter=" "`）；不支持 Segmenter 的旧 WebView 退化为
   * 「拉丁按空格切词 + CJK/标点逐字」——不强求精度，只求断点不落在词中间。
   */
  function segmentTextForBreaking(text) {
    var raw = String(text || "");
    var units = [];
    var segmenter = null;
    try {
      if (typeof Intl !== "undefined" && typeof Intl.Segmenter === "function") {
        segmenter = new Intl.Segmenter(undefined, { granularity: "word" });
      }
    } catch (err) {
      segmenter = null;
    }
    if (segmenter) {
      try {
        var segments = segmenter.segment(raw);
        for (var segment of segments) {
          if (/^\s+$/.test(segment.segment)) {
            if (units.length > 0 && !units[units.length - 1].sepAfter) {
              units[units.length - 1].sepAfter = " ";
            }
            continue;
          }
          units.push({
            text: segment.segment,
            sepAfter: "",
            wordLike: segment.isWordLike === true
          });
        }
        return units;
      } catch (err) {
        units = [];
      }
    }
    var buffer = "";
    var flush = function () {
      if (buffer) {
        units.push({ text: buffer, sepAfter: "", wordLike: true });
        buffer = "";
      }
    };
    Array.from(raw).forEach(function (ch) {
      if (/\s/.test(ch)) {
        flush();
        if (units.length > 0 && !units[units.length - 1].sepAfter) {
          units[units.length - 1].sepAfter = " ";
        }
        return;
      }
      if (CJK_CHAR_RE.test(ch) || BREAK_CLOSING_PUNCT.test(ch) || BREAK_OPENING_PUNCT.test(ch)) {
        flush();
        units.push({ text: ch, sepAfter: "", wordLike: CJK_CHAR_RE.test(ch) });
        return;
      }
      buffer += ch;
    });
    flush();
    return units;
  }

  /** 单元有效宽度 = 文本宽 + 段后空白宽（换行时行尾空格会被 CSS 折叠，这里略微保守）。 */
  function unitWidths(units, measure) {
    return units.map(function (unit) {
      return Number(measure(unit.text + (unit.sepAfter || ""))) || 0;
    });
  }

  /** 单个 CJK 单元比一行还宽（长词/无标点长串）时拆成单字，否则 DP 只能让它超宽。 */
  function splitOverwideUnits(units, maxWidthPx, measure) {
    var result = [];
    units.forEach(function (unit) {
      var width = Number(measure(unit.text + (unit.sepAfter || ""))) || 0;
      if (width <= maxWidthPx || !CJK_CHAR_RE.test(unit.text)) {
        result.push(unit);
        return;
      }
      var chars = Array.from(unit.text);
      chars.forEach(function (ch, index) {
        result.push({
          text: ch,
          sepAfter: index === chars.length - 1 ? (unit.sepAfter || "") : "",
          wordLike: false
        });
      });
    });
    return result;
  }

  /** 贪心换行需要几行（用于「保持当前行数」——重排后行数变了会让字号拟合失效）。 */
  function greedyLineCount(units, widths, maxWidthPx) {
    if (!units || units.length === 0) return 0;
    var lines = 1;
    var used = 0;
    for (var i = 0; i < units.length; i += 1) {
      var width = widths[i];
      if (used > 0 && used + width > maxWidthPx) {
        lines += 1;
        used = width;
      } else {
        used += width;
      }
    }
    return lines;
  }

  /**
   * 动态规划断行（纯函数，可单测）：把 [units] 断成恰好 [lineCount] 行。
   *
   * 代价 = Σ(行宽 − 目标行宽)² + 单单元成行罚 + 末行过短罚 + 超宽重罚
   *        − 标点后断行折扣（≥0）+ 行首收尾标点/行尾开括号重罚。
   * 无解（装不下、单元数不够）返回 null —— 调用方保持原文本，不赌。
   */
  function breakBalancedLines(units, widths, lineCount, maxWidthPx) {
    var n = units.length;
    var lines = Math.max(1, Math.min(BREAK_MAX_LINES, Math.round(Number(lineCount) || 1)));
    if (n === 0 || lines <= 1 || n < lines) return null;
    var prefix = new Array(n + 1);
    prefix[0] = 0;
    for (var i = 0; i < n; i += 1) prefix[i + 1] = prefix[i] + widths[i];
    var total = prefix[n];
    var target = total / lines;
    var INF = 1e12;

    var cost = [];
    var back = [];
    for (var k = 0; k <= lines; k += 1) {
      cost.push(new Array(n + 1).fill(INF));
      back.push(new Array(n + 1).fill(-1));
    }
    cost[0][0] = 0;
    for (var line = 1; line <= lines; line += 1) {
      // 第 line 行至少得有 line 个单元；后面还要留 (lines - line) 个单元。
      for (var end = line; end <= n - (lines - line); end += 1) {
        var best = INF;
        var bestStart = -1;
        for (var start = line - 1; start < end; start += 1) {
          var previous = cost[line - 1][start];
          if (previous >= INF) continue;
          var width = prefix[end] - prefix[start];
          var lineCost = previous + Math.pow(width - target, 2);
          if (width > maxWidthPx) {
            lineCost += Math.pow(width - maxWidthPx, 2) * BREAK_OVERFLOW_WEIGHT + BREAK_OVERFLOW_FLAT;
          }
          var unitsOnLine = end - start;
          var firstText = units[start].text;
          var lastText = units[end - 1].text;
          if (unitsOnLine === 1) lineCost += BREAK_SINGLE_UNIT_PENALTY;
          if (end === n && unitsOnLine <= 2) lineCost += BREAK_SHORT_LAST_PENALTY;
          if (BREAK_CLOSING_PUNCT.test(firstText.charAt(0))) lineCost += BREAK_FORBIDDEN_PENALTY;
          if (BREAK_OPENING_PUNCT.test(lastText.charAt(lastText.length - 1))) lineCost += BREAK_FORBIDDEN_PENALTY;
          if (BREAK_CLOSING_PUNCT.test(lastText.charAt(lastText.length - 1))) lineCost -= BREAK_PUNCT_BONUS;
          if (lineCost < best) {
            best = lineCost;
            bestStart = start;
          }
        }
        cost[line][end] = best;
        back[line][end] = bestStart;
      }
    }
    if (cost[lines][n] >= INF) return null;
    var rows = new Array(lines);
    var cursor = n;
    for (var rowIndex = lines; rowIndex >= 1; rowIndex -= 1) {
      var startIndex = back[rowIndex][cursor];
      if (startIndex < 0) return null;
      var row = "";
      for (var index = startIndex; index < cursor; index += 1) {
        row += units[index].text + (units[index].sepAfter || "");
      }
      rows[rowIndex - 1] = row.replace(/\s+$/, "");
      cursor = startIndex;
    }
    return rows;
  }

  /** 对「已有换行的文本」按可用宽度数实际行数（每段各算一次贪心换行）。 */
  function countGreedyLines(text, maxWidthPx, measure, tokenize) {
    var raw = String(text || "");
    if (!raw || !(maxWidthPx > 0)) return 0;
    var total = 0;
    raw.split("\n").forEach(function (segment) {
      var trimmed = segment.trim();
      if (!trimmed) return;
      var units = (tokenize || segmentTextForBreaking)(trimmed);
      if (!units || units.length === 0) return;
      total += greedyLineCount(units, unitWidths(units, measure), maxWidthPx);
    });
    return total;
  }

  /**
   * 组合入口：分词 → 测宽 → DP → 用 `\n` 连接。
   * @param {string} text 归一空白后的译文（不允许已含 `\n`）
   * @param {number} lineCount 目标行数（调用方按「当前渲染行数」给）
   * @param {number} maxWidthPx 可用宽度（内容宽 = 盒子宽 − 左右内边距）
   * @param {function(string):number} measure 文本宽度测量
   * @param {function(string):Array} [tokenize] 分词（测试可注入）
   * @returns {string|null} 断好的文本；不行返回 null（调用方保持原文本）
   */
  function formatBalancedLines(text, lineCount, maxWidthPx, measure, tokenize) {
    var raw = String(text || "").replace(/\s+/g, " ").trim();
    var lines = Math.round(Number(lineCount) || 0);
    if (!raw || lines <= 1 || lines > BREAK_MAX_LINES || raw.indexOf("\n") >= 0) return null;
    if (!(maxWidthPx > 0) || typeof measure !== "function") return null;
    var units = (tokenize || segmentTextForBreaking)(raw);
    if (!units || units.length <= lines) return null;
    units = splitOverwideUnits(units, maxWidthPx, measure);
    var rows = breakBalancedLines(units, unitWidths(units, measure), lines, maxWidthPx);
    if (!rows) return null;
    return rows.join("\n");
  }

  /** canvas 测量缓存（同一字号只用一套 font 串）。 */
  var measureCanvasContext = null;

  /**
   * 文本宽度测量（px）。canvas `measureText` 优先；不可用时按字宽估算兜底
   * （CJK ≈ 1em，其余 ≈ 0.55em）——估算只影响均衡与断点选择，硬约束由字号拟合兜底。
   */
  function measureTextPx(text, fontSizePx, node) {
    var value = String(text || "");
    if (!value) return 0;
    try {
      if (!measureCanvasContext && typeof document !== "undefined" && document.createElement) {
        var canvas = document.createElement("canvas");
        measureCanvasContext = canvas && canvas.getContext ? canvas.getContext("2d") : null;
      }
      if (measureCanvasContext) {
        var weight = String((node && node.dataset && node.dataset.mtFontWeight) || "500");
        var family = "\"Source Han Sans SC\",\"Noto Sans SC\",\"Noto Sans CJK SC\",sans-serif";
        try {
          var style = node && typeof getComputedStyle === "function" ? getComputedStyle(node) : null;
          if (style && style.fontFamily) family = String(style.fontFamily);
        } catch (err) {
          // 拿不到就用默认字体族（宽度略有出入，不影响断点决策的大局）。
        }
        measureCanvasContext.font = weight + " " + fontSizePx + "px " + family;
        return Number(measureCanvasContext.measureText(value).width) || 0;
      }
    } catch (err) {
      measureCanvasContext = null;
    }
    var width = 0;
    Array.from(value).forEach(function (ch) {
      width += CJK_CHAR_RE.test(ch) ? fontSizePx : fontSizePx * 0.55;
    });
    return width;
  }

  /**
   * 就地重排一个文字节点（在字号拟合之后调用）。
   *
   * 约束「重排前后行数不变」：行数变 = 文字块高度变 = 刚拟合好的字号失效。
   * 断点用词边界 + 标点禁则；断不开（无解/竖排/跨图固定字号）就什么都不做。
   * 幂等：同一 (原文, 宽, 字号) 只算一次；宽度/字号变化时按**原文**重算。
   */
  function rebalanceTextNode(node, widthPx, fontSizePx) {
    if (!node || !node.dataset || !node.classList) return;
    if (node.classList.contains("mt-jp-vertical")) return;
    if (Number(node.dataset.fixedFontPercent) > 0 || node.dataset.stitchOverflow === "true") return;
    var raw = node.dataset.translated || "";
    var content = node.querySelector ? node.querySelector(".mt-bubble-content") : null;
    if (!raw || !content || !(widthPx > 8) || !(fontSizePx > 0)) return;
    var key = raw + "|" + Math.round(fontSizePx * 10) + "|" + Math.round(widthPx);
    if (node.dataset.mtRebalanceKey === key) return;
    node.dataset.mtRebalanceKey = key;
    var measure = function (text) { return measureTextPx(text, fontSizePx, node); };
    var contentWidth = Math.max(8, widthPx - 2 * BUBBLE_PADDING_X_EM * fontSizePx);
    var current = String(content.textContent || "");
    var lineCount = countGreedyLines(current, contentWidth, measure);
    if (lineCount <= 1) return;
    var formatted = formatBalancedLines(raw, lineCount, contentWidth, measure);
    if (!formatted || formatted === current) return;
    if (countGreedyLines(formatted, contentWidth, measure) !== lineCount) return;
    content.textContent = formatted;
    console.log(
      "MTBREAK rebalance bubble=" + (node.dataset.mtLayer || node.dataset.index || "?") +
        " lines=" + lineCount + " text=" + formatted.replace(/\n/g, "⏎").slice(0, 40)
    );
  }

  /**
   * 竖排判定：原文含韩文/拉丁且不像日文 → 横排；日文且高宽比 ≥ 0.82 → 竖排；
   * OCR 明确给出 vertical=true 时直接竖排（宽扁气泡不会被误转，writing-mode 不涉及旋转）。
   */  function shouldUseVerticalLayout(bubble, displayText) {
    if (bubble.vertical === true) return true;
    var originalText = String(bubble.original_text || "").trim();
    if (originalText && /[\uac00-\ud7afA-Za-z]/.test(originalText) && !looksLikeJapaneseText(originalText)) {
      return false;
    }
    if (!looksLikeJapaneseText(originalText)) return false;
    if (!looksLikeCjkText(displayText)) return false;
    var hPercent = Number(bubble.h) || 0;
    var wPercent = Number(bubble.w) || 1;
    return hPercent / Math.max(wPercent, 0.1) >= 0.82;
  }

  /** 译文永远以原文字块中心为锚点（translate(-50%,-50%) rotate(θ)），不继承左上角。 */
  function applyAnchorStyle(node, options) {
    var x = Number(options.x) || 0;
    var y = Number(options.y) || 0;
    var w = Math.max(0, Number(options.w) || 0);
    var h = Math.max(0, Number(options.h) || 0);
    var angle = Number(options.rotation) || 0;
    var unit = options.unit || "%";
    var allowVerticalOverflow = options.allowVerticalOverflow === true;
    var centerX = x + w / 2;
    var centerY = y + h / 2;
    var anchorCenterY = allowVerticalOverflow || unit !== "%" ? centerY : clamp(centerY, 0, 100);
    node.style.left = centerX + unit;
    node.style.top = anchorCenterY + unit;
    node.style.transformOrigin = "center center";
    node.style.setProperty("--mt-base-transform", "translate(-50%, -50%) rotate(" + angle.toFixed(2) + "deg)");
  }

  // ---------------------------------------------------------------------------
  // 清理图贴图数学（target-resolve.js getCleanedPatchStyle 同口径）
  // ---------------------------------------------------------------------------

  function normalizeFillBox(value, allowVerticalOverflow) {
    if (!value || typeof value !== "object") return null;
    var rawX = Number(value.x != null ? value.x : value.left);
    var rawY = Number(value.y != null ? value.y : value.top);
    var rawW = Number(value.w != null ? value.w : value.width);
    var rawH = Number(value.h != null ? value.h : value.height);
    if (![rawX, rawY, rawW, rawH].every(Number.isFinite) || rawW <= 0 || rawH <= 0) return null;
    var box = {
      x: clamp(rawX, 0, 100),
      y: allowVerticalOverflow ? rawY : clamp(rawY, 0, 100),
      w: clamp(rawW, 0, 100),
      h: clamp(rawH, 0, 100)
    };
    return box.w > 0 && box.h > 0 ? box : null;
  }

  /**
   * 整页清理图 → 单个气泡局部坐标：
   *   sizeX = 10000/box.w %，positionX = box.x/(100-box.w)×100 %
   * （CSS background-size/position 的百分比相对元素自身尺寸，公式已按此推导。）
   *
   * box 是**要贴出来的源区域**：默认就是气泡自身的框；清理图补丁另有源区域时
   * 传 cleaned_source_box，公式不变 —— 元素整块正好显示源区域那一块像素。
   */
  function getCleanedPatchStyle(sourceBox) {
    var box = normalizeFillBox(sourceBox) || { x: 0, y: 0, w: 100, h: 100 };
    var positionX = box.w < 100 ? box.x / (100 - box.w) * 100 : 0;
    var positionY = box.h < 100 ? box.y / (100 - box.h) * 100 : 0;
    return {
      sizeX: 10000 / box.w + "%",
      sizeY: 10000 / box.h + "%",
      positionX: positionX + "%",
      positionY: positionY + "%"
    };
  }

  // ---------------------------------------------------------------------------
  // 气泡节点（cover 投影 + text 层，lifecycle-bubble.js / renderer-cover.js 移植）
  // ---------------------------------------------------------------------------

  function resolveBubblePolygon(value, allowOverflow) {
    if (!Array.isArray(value) || value.length < 4) return null;
    var points = value.slice(0, 4).map(function (point) {
      var x = clamp(Number(point && point.x), 0, 100);
      var rawY = Number(point && point.y);
      var y = allowOverflow ? rawY : clamp(rawY, 0, 100);
      return Number.isFinite(x) && Number.isFinite(y) ? { x: x, y: y } : null;
    });
    return points.every(Boolean) ? points : null;
  }

  function resolveBubbleRotation(bubble) {
    var explicit = Number(bubble && bubble.rotation_deg);
    var candidates = [bubble && bubble.polygon];
    return sanitizeRenderRotationDeg(
      resolveTiltRotationDeg(explicit, candidates),
      Number(bubble && bubble.w) || 0,
      Number(bubble && bubble.h) || 0
    );
  }

  /**
   * 盖板框：优先用宿主算好的 `cover_box`（= 字形范围 + 几像素，**不含**排版外扩），
   * 否则退回倾斜 polygon 的外接框，最后才是 x/y/w/h（排版框）。
   */
  function resolveBubbleCoverBox(bubble) {
    var allowVerticalOverflow = bubble.stitch_overflow === true;
    var explicit = normalizeFillBox(bubble.cover_box, allowVerticalOverflow);
    if (explicit) return explicit;
    var polygon = resolveBubblePolygon(bubble.polygon, allowVerticalOverflow);
    if (polygon) return boundsOfPoints(polygon);
    return normalizeFillBox({ x: bubble.x, y: bubble.y, w: bubble.w, h: bubble.h }, allowVerticalOverflow);
  }

  /**
   * cover 节点：负责遮掉原文。
   * - solid：::before 填采样底色（范围 = 文字框 ∪ fill_box，再按 region_polygon 裁剪）；
   * - none：透明 + 整页清理图按贴图数学映射为局部背景（patch_url 直连时用 100% 铺满）；
   * - 拿不到清理图：保持透明（绝不画纯色补丁）。
   */
  function createCoverNode(bubble, index) {
    var allowVerticalOverflow = bubble.stitch_overflow === true;
    // 盖板用 cover_box（字形范围 + 几像素）；**不要**用 x/y/w/h —— 那是排版框，
    // 字形框每侧还外扩 15%，当盖板用就会「比文字大出 30%+」。
    var box = normalizeFillBox(bubble.cover_box, allowVerticalOverflow) ||
      normalizeFillBox({ x: bubble.x, y: bubble.y, w: bubble.w, h: bubble.h }, allowVerticalOverflow);
    if (!box) return null;
    var bgType = normalizeBgType(bubble.bg_type);
    var rotation = resolveBubbleRotation(bubble);
    var coverBubble = {
      x: box.x, y: box.y, w: box.w, h: box.h,
      bg_type: bgType, bg_color: bubble.bg_color,
      fill_box: bubble.fill_box, region_polygon: bubble.region_polygon,
      cleaned_source_box: bubble.cleaned_source_box,
      polygon: bubble.polygon, rotation_deg: rotation,
      stitch_overflow: allowVerticalOverflow, patch_url: bubble.patch_url,
      patch_box: bubble.patch_box,
      // §11 分片：是否为「全部落在捕获带内」，决定要不要叠整页清理图。
      __segmentAllInBand: bubble.__segmentAllInBand
    };
    var node = document.createElement("div");
    node.className = "mt-bubble mt-bg-" + bgType + " mt-cover-only";
    node.dataset.mtOverlay = "true";
    node.dataset.index = String(index);
    node.dataset.xPercent = String(box.x);
    node.dataset.yPercent = String(box.y);
    node.dataset.wPercent = String(box.w);
    node.dataset.hPercent = String(box.h);
    if (bgType === "solid" && coverBubble.bg_color) {
      // 只有实底气泡才允许出现纯色填充；复杂背景（none/transparent）绝不画纯色补丁，
      // 否则会出现「比没有遮罩更糟」的白色方块（§14 反模式）。
      node.style.setProperty("--mt-adaptive-bg", String(coverBubble.bg_color));
    }
    if (bgType === "none") {
      if (bubble.flat_cover === true) {
        // **不透明实底盖板**（2026-10-11 用户要求：「现在会有残影，不要这样的，而是用实体的
        // 背景遮挡」）。宿主在没有清理图纹理时（速度优先模式关闭「精细擦字」，或清理图建失败）
        // 直接下发 flat_cover：这里用采样出来的气泡底色铺一块 100% 不透明的底，原文一点不露。
        // 代价是背景纹理变成纯色 —— 这是「只要显示出来译文就行」的路线上明确接受的取舍；
        // 贴片/清理图那两条路保持原样（有纹理时仍然用纹理，老场景没有这个字段 → 行为不变）。
        node.dataset.coverFill = "flat";
        node.style.setProperty("--mt-adaptive-bg", String(bubble.bg_color || "rgba(255,255,255,0.96)"));
      } else if (coverBubble.patch_url) {
        // 跨图续排的擦字贴片：独立小图 100% 铺满补丁框（patch_box 在外层 clip 节点上）。
        node.style.setProperty("--mt-cleaned-image", maskCssValue(coverBubble.patch_url));
        node.style.setProperty("--mt-cleaned-size-x", "100%");
        node.style.setProperty("--mt-cleaned-size-y", "100%");
        node.style.setProperty("--mt-cleaned-position-x", "0");
        node.style.setProperty("--mt-cleaned-position-y", "0");
      } else {
        // 清理图补丁要映射的**源区域**：缺省就是气泡框自身。
        var sourceBox = normalizeFillBox(coverBubble.cleaned_source_box, allowVerticalOverflow) ||
          { x: box.x, y: box.y, w: box.w, h: box.h };
        var patch = getCleanedPatchStyle(sourceBox);
        node.style.setProperty("--mt-cleaned-size-x", patch.sizeX);
        node.style.setProperty("--mt-cleaned-size-y", patch.sizeY);
        node.style.setProperty("--mt-cleaned-position-x", patch.positionX);
        node.style.setProperty("--mt-cleaned-position-y", patch.positionY);
      }
      // §11 三级背景降级（第 2 级）：分段只有一部分落在捕获带外时，带内用复合清理图、
      // 带外用整页清理图。整页图缺位就退回单层复合图 —— 不能出现「有遮罩但背景是空的」。
      if (coverBubble.__segmentAllInBand === false && sceneCleanedUrls.page) {
        var pagePatch = getCleanedPatchStyle(sourceBox || { x: box.x, y: box.y, w: box.w, h: box.h });
        node.style.setProperty("--mt-cleaned-page-image", maskCssValue(sceneCleanedUrls.page));
        node.style.setProperty("--mt-page-size-x", pagePatch.sizeX);
        node.style.setProperty("--mt-page-size-y", pagePatch.sizeY);
        node.style.setProperty("--mt-page-position-x", pagePatch.positionX);
        node.style.setProperty("--mt-page-position-y", pagePatch.positionY);
        node.dataset.cleanedLayers = "2";
      }
    }
    var fillBox = bgType === "solid"
      ? buildSolidBackgroundBox(box, coverBubble.fill_box, allowVerticalOverflow)
      : null;
    if (fillBox) {
      node.style.setProperty("--mt-fill-left", (fillBox.x - box.x) / box.w * 100 + "%");
      node.style.setProperty("--mt-fill-top", (fillBox.y - box.y) / box.h * 100 + "%");
      node.style.setProperty("--mt-fill-width", fillBox.w / box.w * 100 + "%");
      node.style.setProperty("--mt-fill-height", fillBox.h / box.h * 100 + "%");
    }
    if (bgType === "solid") {
      // 实底容器轮廓：优先 region_polygon（真正的实底轮廓），没有就用文字四边形收边。
      // 两者都是**世界坐标**百分比；轴对齐时直接交给 ::before 的 clip-path 即可。
      var clipTarget = fillBox || box;
      var regionPoints = Array.isArray(coverBubble.region_polygon) && coverBubble.region_polygon.length >= 3
        ? coverBubble.region_polygon
        : (Array.isArray(bubble.polygon) && bubble.polygon.length >= 4 ? bubble.polygon : null);
      var regionClip = regionPoints
        ? buildRegionClipPath(regionPoints, clipTarget.x, clipTarget.y, clipTarget.w, clipTarget.h)
        : "";
      if (regionClip) node.style.setProperty("--mt-region-clip", regionClip);
      // 斜排实底：::before 活在**旋转后的节点局部坐标**里，世界坐标的填充范围会被转歪。
      // 这里改成把填充画到裁剪层（世界坐标、不旋转）里，见 createCoverClipNode。
      if (Math.abs(rotation) > COVER_TILT_TOLERANCE_DEG) {
        node.dataset.fillTilted = "true";
      }
    }
    node.style.width = box.w + "%";
    node.style.height = box.h + "%";
    applyAnchorStyle(node, {
      x: box.x, y: box.y, w: box.w, h: box.h,
      rotation: rotation, unit: "%",
      allowVerticalOverflow: allowVerticalOverflow
    });
    return node;
  }

  function buildSolidBackgroundBox(textBox, fillBoxValue, allowVerticalOverflow) {
    var text = normalizeFillBox(textBox, allowVerticalOverflow);
    var fill = normalizeFillBox(fillBoxValue, allowVerticalOverflow);
    if (!text) return fill;
    if (!fill) return text;
    var left = Math.min(text.x, fill.x);
    var top = Math.min(text.y, fill.y);
    var right = Math.max(text.x + text.w, fill.x + fill.w);
    var bottom = Math.max(text.y + text.h, fill.y + fill.h);
    return { x: left, y: top, w: right - left, h: bottom - top };
  }

  /**
   * §11 跨页/分片遮罩段：把 `cover_segments` 按 page_id 合并成「每页一块 union 遮罩」。
   *
   * 为什么必须按页 union：同一页可能有多段（被扫描线切成两半的行、落在捕获带外的
   * 相邻行）。逐段各建一个节点时，后一段的背景映射会顶掉前一段，遮罩出现残缺
   * （参考项目实测过 662px 残留）。合并成一块后每页只写一次背景。
   *
   * 返回 null 表示「没有分片信息」→ 调用方走单块遮罩，行为与旧版完全一致。
   */
  function resolveCoverSegments(bubble) {
    var raw = bubble && bubble.cover_segments;
    if (!Array.isArray(raw) || raw.length === 0) return null;
    var allowVerticalOverflow = bubble.stitch_overflow === true;
    var groups = [];
    var indexByPage = {};
    raw.forEach(function (segment, i) {
      if (!segment) return;
      var box = normalizeFillBox(
        { x: segment.x, y: segment.y, w: segment.w, h: segment.h },
        allowVerticalOverflow
      );
      if (!box) return;
      var pageId = String(segment.page_id || ("p" + i));
      var polygon = resolveBubblePolygon(segment.polygon, allowVerticalOverflow);
      var cleanedSourceBox = normalizeFillBox(segment.cleaned_source_box, allowVerticalOverflow);
      var inBand = segment.in_band !== false;
      var slot = indexByPage[pageId];
      if (slot === undefined) {
        indexByPage[pageId] = groups.length;
        groups.push({
          pageId: pageId,
          box: box,
          polygon: polygon,
          cleanedSourceBox: cleanedSourceBox,
          allInBand: inBand,
          count: 1
        });
        return;
      }
      var group = groups[slot];
      var left = Math.min(group.box.x, box.x);
      var top = Math.min(group.box.y, box.y);
      var right = Math.max(group.box.x + group.box.w, box.x + box.w);
      var bottom = Math.max(group.box.y + group.box.h, box.y + box.h);
      group.box = { x: left, y: top, w: right - left, h: bottom - top };
      // 多段的并集没有简单的四边形表达：丢掉倾角、退回外接框。
      // 宁可少斜一点，也不能把两段的四边形硬拼成歪的遮罩。
      group.polygon = null;
      group.allInBand = group.allInBand && inBand;
      group.cleanedSourceBox = group.cleanedSourceBox || cleanedSourceBox;
      group.count += 1;
    });
    return groups.length > 0 ? groups : null;
  }

  /** 把气泡几何换成某一分段的（尺寸/四边形/清理图源区域），其余字段原样继承。 */
  function scopeBubbleToSegment(bubble, group) {
    var scoped = {};
    Object.keys(bubble).forEach(function (key) { scoped[key] = bubble[key]; });
    scoped.x = group.box.x;
    scoped.y = group.box.y;
    scoped.w = group.box.w;
    scoped.h = group.box.h;
    scoped.cover_segments = null;
    scoped.polygon = group.polygon || bubble.polygon;
    if (group.cleanedSourceBox) scoped.cleaned_source_box = group.cleanedSourceBox;
    scoped.__segmentAllInBand = group.allInBand === true;
    scoped.__segmentPageId = group.pageId;
    return scoped;
  }

  /**
   * 一个气泡可能产出多块遮罩（多页/多段）。没有分片信息时就是单块，
   * 保证既有链路的节点结构与层级顺序完全不变。
   */
  function createCoverLayers(bubble, index) {
    var segments = resolveCoverSegments(bubble);
    if (!segments) {
      var coverNode = createCoverNode(bubble, index);
      if (!coverNode) return [];
      var clipNode = createCoverClipNode(bubble, coverNode);
      return clipNode ? [clipNode] : [];
    }
    var nodes = [];
    segments.forEach(function (group) {
      var scoped = scopeBubbleToSegment(bubble, group);
      var cover = createCoverNode(scoped, index);
      if (!cover) return;
      var clip = createCoverClipNode(scoped, cover);
      if (!clip) return;
      clip.dataset.pageId = group.pageId;
      clip.dataset.coverSegments = String(segments.length);
      nodes.push(clip);
    });
    return nodes;
  }

  /** 遮罩裁剪层：外层按四边形外接框 + clip-path 承担倾角，内层填充不旋转。 */
  function createCoverClipNode(bubble, coverNode) {
    var allowVerticalOverflow = bubble.stitch_overflow === true;
    var box = resolveBubbleCoverBox(bubble) ||
      normalizeFillBox(bubble, allowVerticalOverflow);
    if (!coverNode || !box) return coverNode;
    if (bubble.patch_box) {
      // 补丁背书气泡（跨图续排）：clip 层直接用补丁目标框，cover 节点 100% 铺满。
      var patchBox = normalizeFillBox(bubble.patch_box, true) || box;
      var clipNode = document.createElement("div");
      clipNode.className = "mt-cover-clip";
      clipNode.dataset.mtOverlay = "true";
      clipNode.style.left = (patchBox.x + patchBox.w / 2) + "%";
      clipNode.style.top = (patchBox.y + patchBox.h / 2) + "%";
      clipNode.style.width = patchBox.w + "%";
      clipNode.style.height = patchBox.h + "%";
      clipNode.style.transform = "translate(-50%, -50%)";
      clipNode.style.transformOrigin = "center center";
      coverNode.style.left = "50%";
      coverNode.style.top = "50%";
      coverNode.style.width = "100%";
      coverNode.style.height = "100%";
      coverNode.style.setProperty("--mt-base-transform", "translate(-50%, -50%)");
      clipNode.appendChild(coverNode);
      return clipNode;
    }
    var polygon = resolveBubblePolygon(bubble.polygon, allowVerticalOverflow);
    var rotation = resolveBubbleRotation(bubble);
    var geometry = resolveCoverQuad({
      polygon: polygon,
      rotationDeg: rotation,
      box: box,
      // 遮罩按比例略微外扩：字形边缘（抗锯齿、描边、ML Kit 偏松/偏紧的框）常常比检测框
      // 多出一点，不外扩就会留下原字的边角（2026-09-29 用户反馈「倾斜文本没有完全覆盖」
      // 「粉红色的字后面没有覆盖层」）。0.6 → 外扩约 3%，足以吃掉边缘又不至于糊到相邻画面。
      padding: COVER_PADDING_DEFAULT
    });
    var bounds = geometry && geometry.bounds ? geometry.bounds : box;
    var clip = document.createElement("div");
    clip.className = "mt-cover-clip";
    clip.dataset.mtOverlay = "true";
    clip.style.left = (bounds.x + bounds.w / 2) + "%";
    clip.style.top = (bounds.y + bounds.h / 2) + "%";
    clip.style.width = bounds.w + "%";
    clip.style.height = bounds.h + "%";
    // 裁剪层恒为世界坐标轴对齐：倾角只由 quad 的 clip-path 表达（与下面「斜排实底」
    // 的注释同一口径）。此前这里在「无四边形」时额外 rotate 了整层 —— 与 clip-path
    // 叠加等于转两次；竖排（±90°）与噪声（≤2°）现在都不旋转，rotate 只会帮倒忙。
    clip.style.transform = "translate(-50%, -50%)";
    clip.style.transformOrigin = "center center";
    // 斜排文字绝不能退回轴对齐矩形：拿到四边形就用它裁剪；
    // 无倾角时裁剪结果等于原蓝框，视觉与轴对齐一致。
    var clipPath = geometry ? polygonClipPathPercent(geometry.quad, bounds) : "";
    if (clipPath) {
      clip.style.clipPath = clipPath;
    } else if (polygon) {
      clip.style.clipPath = buildRegionClipPath(polygon, bounds.x, bounds.y, bounds.w, bounds.h);
    }
    var x = Number(coverNode.dataset.xPercent) || box.x;
    var y = Number(coverNode.dataset.yPercent) || box.y;
    var w = Number(coverNode.dataset.wPercent) || box.w;
    var h = Number(coverNode.dataset.hPercent) || box.h;
    // 斜排实底：填充块画在裁剪层里（裁剪层是世界坐标轴对齐的，只靠 clip-path 表达倾角），
    // 于是「填充范围 = 文字框 ∪ fill_box + region_polygon 裁剪」不会被节点旋转带歪。
    if (coverNode.dataset && coverNode.dataset.fillTilted === "true") {
      var flatFill = createFlatFillNode(bubble, bounds, w, h, x, y);
      if (flatFill) {
        clip.appendChild(flatFill);
        coverNode.classList.add("mt-fill-outside");
      }
    }
    coverNode.style.left = (x + w / 2 - bounds.x) / bounds.w * 100 + "%";
    coverNode.style.top = (y + h / 2 - bounds.y) / bounds.h * 100 + "%";
    coverNode.style.width = w / bounds.w * 100 + "%";
    coverNode.style.height = h / bounds.h * 100 + "%";
    coverNode.style.setProperty("--mt-base-transform", "translate(-50%, -50%)");
    clip.appendChild(coverNode);
    return clip;
  }

  /**
   * 世界坐标（不旋转）的实底填充块：相对裁剪层 bounds 定位，
   * 填充范围 = 文字框 ∪ fill_box，再按 region_polygon（或文字四边形）裁剪。
   */
  function createFlatFillNode(bubble, bounds, w, h, x, y) {
    var allowVerticalOverflow = bubble.stitch_overflow === true;
    var textBox = normalizeFillBox({ x: x, y: y, w: w, h: h }, allowVerticalOverflow);
    if (!textBox) return null;
    var fill = buildSolidBackgroundBox(textBox, bubble.fill_box, allowVerticalOverflow);
    if (!fill) return null;
    var node = document.createElement("div");
    node.className = "mt-fill-flat";
    node.dataset.mtOverlay = "true";
    node.style.left = (fill.x - bounds.x) / bounds.w * 100 + "%";
    node.style.top = (fill.y - bounds.y) / bounds.h * 100 + "%";
    node.style.width = fill.w / bounds.w * 100 + "%";
    node.style.height = fill.h / bounds.h * 100 + "%";
    node.style.background = String(bubble.bg_color || "rgba(255,255,255,0.96)");
    var regionPoints = Array.isArray(bubble.region_polygon) && bubble.region_polygon.length >= 3
      ? bubble.region_polygon
      : (Array.isArray(bubble.polygon) && bubble.polygon.length >= 4 ? bubble.polygon : null);
    var regionClip = regionPoints
      ? buildRegionClipPath(regionPoints, fill.x, fill.y, fill.w, fill.h)
      : "";
    if (regionClip) node.style.clipPath = regionClip;
    return node;
  }

  /**
   * text 节点：负责画译文。背景一律透明（背景在 cover 层），
   * .mt-bg-none 的描边规则照搬（旁白类文字必渲染描边保证可读性）。
   */
  function createTextNode(bubble, index) {
    var allowVerticalOverflow = bubble.stitch_overflow === true;
    var box = normalizeFillBox({ x: bubble.x, y: bubble.y, w: bubble.w, h: bubble.h }, allowVerticalOverflow);
    if (!box) return null;
    var originalText = cleanRenderableText(bubble.original_text);
    var translatedText = cleanRenderableText(bubble.translated_text) || originalText;
    if (!translatedText) return null;
    var bgType = normalizeBgType(bubble.bg_type);
    var rotation = resolveBubbleRotation(bubble);
    var alignment = normalizeAlignment(bubble.alignment);
    // 默认字重 500（Medium；2026-10-16 用户定稿）：此前 host 下发 `font_weight:null` 被
    // null 陷阱钳成 100（极细），修掉后先定 700，用户试看后定为 500 ——
    // 比正文（400）重半档，压在插画上够稳、又不像 Bold 那么黑。
    var fontWeight = normalizeFontWeight(bubble.font_weight, 500);
    var node = document.createElement("div");
    node.className = "mt-bubble mt-bg-" + bgType + " mt-text-layer mt-align-" + alignment;
    node.dataset.mtOverlay = "true";
    node.dataset.index = String(index);
    node.dataset.original = originalText;
    node.dataset.translated = translatedText;
    node.dataset.sourceLineCount = String(Math.max(1, Math.round(Number(bubble.source_line_count) || 1)));
    node.dataset.sourceFontHeightPercent = String(Number(bubble.font_height_percent) || 0);
    node.dataset.fixedFontPercent = String(Number(bubble.fixed_font_percent) || 0);
    node.dataset.wPercent = String(box.w);
    node.dataset.hPercent = String(box.h);
    node.dataset.xPercent = String(box.x);
    node.dataset.yPercent = String(box.y);
    node.dataset.rotationDeg = String(rotation);
    node.dataset.mtFontWeight = String(fontWeight);
    node.dataset.stitchOverflow = allowVerticalOverflow ? "true" : "";
    // 描边开关（2026-09-30 用户要求「白边黑边只在背景有多种颜色的时候有」）：
    // 宿主按**背景（清理图）的亮度离散度**判定后下发；缺省（老宿主 / 字段缺失）保留描边，
    // 与改动前行为一致。见 applyStrokeWidth / .mt-no-stroke。
    node.dataset.mtNoStroke = bubble.needs_stroke === false ? "1" : "";
    if (bubble.text_color) node.style.setProperty("--mt-text-color", String(bubble.text_color));
    if (bubble.stroke_color) node.style.setProperty("--mt-stroke-color", String(bubble.stroke_color));
    node.style.setProperty("--mt-font-weight", String(fontWeight));
    node.style.width = box.w + "%";
    node.style.height = box.h + "%";
    applyAnchorStyle(node, {
      x: box.x, y: box.y, w: box.w, h: box.h,
      rotation: rotation, unit: "%",
      allowVerticalOverflow: allowVerticalOverflow
    });
    // 倾角要**按图片实际像素尺寸再校正一次**（见 applyAspectCorrectedTilt）：
    // 上面算出的 rotation 是「百分比空间」的角度，而 CSS rotate 作用在屏幕像素上。
    // 先把多边形存下来，等 root 有尺寸时换算并覆盖 --mt-base-transform。
    if (Array.isArray(bubble.polygon) && bubble.polygon.length >= 4) {
      node.dataset.mtTiltPolygon = JSON.stringify(bubble.polygon);
    }
    node.addEventListener("click", function (event) {
      event.preventDefault();
      event.stopPropagation();
      var root = node.closest(".mt-overlay-root");
      if (root) root.classList.toggle("mt-show-source");
    });
    return node;
  }

  // ---------------------------------------------------------------------------
  // 字号拟合（lifecycle-font-fit.js 移植）
  // ---------------------------------------------------------------------------

  var fontFitCache = new Map();
  var measureProbe = null;

  function ensureMeasureProbe() {
    if (measureProbe && measureProbe.isConnected) return measureProbe;
    measureProbe = document.createElement("div");
    measureProbe.className = "mt-bubble mt-measure-probe";
    document.documentElement.appendChild(measureProbe);
    return measureProbe;
  }

  function isProbeOverflowing(probe) {
    return probe.scrollHeight > probe.clientHeight + 0.5 ||
      probe.scrollWidth > probe.clientWidth + 0.5;
  }

  function prepareMeasureProbe(node, width, height, text, fontSize) {
    resetOverflowExpansion(node);
    var probe = ensureMeasureProbe();
    probe.className = node.className + " mt-measure-probe";
    // 字重必须与真实节点一致（否则粗体字宽不同、溢出测不出来）：节点上是 inline
    // 自定义属性，探针复制不了 inline style，这里手动搬一份；没有就退回 CSS 默认（500）。
    var weight = node.style && node.style.getPropertyValue("--mt-font-weight");
    if (weight) probe.style.setProperty("--mt-font-weight", weight);
    else probe.style.removeProperty("--mt-font-weight");
    probe.style.width = width + "px";
    probe.style.height = height + "px";
    probe.style.fontSize = fontSize + "px";
    probe.textContent = "";
    // 探针复制真实双层结构（.mt-bubble > .mt-bubble-content），行距/换行测量才一致。
    var inner = document.createElement("div");
    inner.className = "mt-bubble-content";
    inner.textContent = text;
    probe.appendChild(inner);
    return probe;
  }

  function rememberFontFit(key, value) {
    fontFitCache.set(key, value);
    if (fontFitCache.size <= FONT_CACHE_MAX) return;
    fontFitCache.delete(fontFitCache.keys().next().value);
  }

  function resetOverflowExpansion(node) {
    if (!node || !node.style) return;
    node.style.removeProperty("min-width");
    node.style.removeProperty("min-height");
    expandMaskChain(node, null);
    if (node.dataset && node.dataset.expandedForText === "true") {
      restoreFillVar(node, "--mt-fill-left", node.dataset.originalFillLeft);
      restoreFillVar(node, "--mt-fill-top", node.dataset.originalFillTop);
      restoreFillVar(node, "--mt-fill-width", node.dataset.originalFillWidth);
      restoreFillVar(node, "--mt-fill-height", node.dataset.originalFillHeight);
    }
    if (node.dataset) node.dataset.expandedForText = "";
  }

  /** 把文字节点的撑开尺寸同步到它配对的遮罩链（cover + clip 层）；尺寸为空则复位。 */
  function expandMaskChain(node, minWidth) {
    var chain = node && node.__mtMaskChain;
    if (!chain) return;
    var width = minWidth == null ? "" : minWidth;
    var height = minWidth == null ? "" : node.style.minHeight;
    chain.forEach(function (target) {
      if (!target || !target.style) return;
      if (!width) {
        target.style.removeProperty("min-width");
        target.style.removeProperty("min-height");
        return;
      }
      target.style.minWidth = width;
      target.style.minHeight = height;
    });
  }

  function restoreFillVar(node, name, value) {
    if (value) node.style.setProperty(name, value);
    else node.style.removeProperty(name);
  }

  function expandForTextOverflow(node, width, height, probe) {
    if (!node || !probe) return;
    node.style.minWidth = Math.max(width, Math.ceil(probe.scrollWidth + 2)) + "px";
    node.style.minHeight = Math.max(height, Math.ceil(probe.scrollHeight + 2)) + "px";
    expandMaskChain(node, node.style.minWidth);
    if (!node.dataset) return;
    node.dataset.expandedForText = "true";
    if (!node.classList.contains("mt-bg-solid")) return;
    node.dataset.originalFillLeft = node.style.getPropertyValue("--mt-fill-left") || "";
    node.dataset.originalFillTop = node.style.getPropertyValue("--mt-fill-top") || "";
    node.dataset.originalFillWidth = node.style.getPropertyValue("--mt-fill-width") || "";
    node.dataset.originalFillHeight = node.style.getPropertyValue("--mt-fill-height") || "";
    node.style.setProperty("--mt-fill-left", "0%");
    node.style.setProperty("--mt-fill-top", "0%");
    node.style.setProperty("--mt-fill-width", "100%");
    node.style.setProperty("--mt-fill-height", "100%");
  }

  function getOriginalTextHeight(node, bubbleHeightPx, imageHeightPx) {
    var percent = Number(node && node.dataset && node.dataset.sourceFontHeightPercent);
    if (percent > 0 && Number(imageHeightPx) > 0) {
      return Number(imageHeightPx) * percent / 100;
    }
    var lines = Math.max(1, Number(node && node.dataset && node.dataset.sourceLineCount) || 1);
    return Number(bubbleHeightPx) / lines;
  }

  /**
   * 字号区间（纯函数，可单测）：起点跟原文字高走，拿不到才用「框高 × 0.78」；
   * 上限 = min(120, 框高×0.88, 原字高×1.08)。
   * originalH 可信时**不设 52px 硬顶**：译文应尽量与原文同字号，只保留 120px 防爆。
   */
  function resolveFontSizeRange(height, originalH) {
    var startSize = originalH > 0
      ? Math.min(originalH, FONT_ABS_MAX)
      : Math.min(clamp(height * FONT_BASE_RATIO, FONT_MIN, FONT_MAX), FONT_MAX);
    var maxFont = Math.min(FONT_ABS_MAX, height * 0.88);
    if (originalH > 0) {
      startSize = Math.min(startSize, originalH * FONT_ORIGINAL_SCALE);
      maxFont = Math.min(maxFont, originalH * FONT_ORIGINAL_SCALE);
    }
    return { startSize: startSize, maxFont: Math.max(maxFont, FONT_MIN) };
  }

  /**
   * 字号拟合：原文字高可信 → 从原字高出发（不设 52px 硬顶，只留 120px 防爆）；
   * 否则按框高×0.78 起步。离屏探针上二分 9 次，判据 scroll 尺寸溢出；
   * ×0.96（竖排 ×0.92）安全系数后仍溢出则允许把节点撑开。
   */
  function fitBubbleFontSize(node, bubbleWidthPx, bubbleHeightPx, imageHeightPx) {
    var width = Math.max(8, Math.round(bubbleWidthPx));
    var height = Math.max(8, Math.round(bubbleHeightPx));
    var text = String(node.textContent || "").trim();
    if (!text) return FONT_MIN;
    var vertical = node.classList.contains("mt-jp-vertical");
    var fixedPercent = Number(node.dataset.fixedFontPercent) || 0;
    if (fixedPercent > 0 && Number(imageHeightPx) > 0) {
      // 跨图续排：与上一张图同一个字号（换算成显示像素），保证接缝两侧断行一致。
      //
      // ⚠️ 「固定字号」只能当**上限**，不能当硬值（2026-09-30）：给号的宿主算字号时按
      // 「图像像素宽度」估可用宽度，而真正排版的是这里的 DOM 盒子（`.mt-bubble` 有
      // `padding:0.12em 0.34em` 的 em 内边距、两侧图显示宽度也可能有一两像素差）。
      // 只要差一点点，CJK 译文就会折行 —— 折行后文字层高度直接翻倍、从气泡里溢出来
      // （实测：盒子 266×59、内容 233×113、2 行；用户看到的正是「字超出了原来的字体位置」）。
      // 所以这里只**收不放**：放得下就照抄固定值，放不下才二分收小到塞得进去。
      var fixed = Math.round(Number(imageHeightPx) * fixedPercent / 100 * 10) / 10;
      var fixedProbe = prepareMeasureProbe(node, width, height, text, fixed);
      if (!isProbeOverflowing(fixedProbe)) return fixed;
      var fixLow = FONT_ABS_MIN;
      var fixHigh = fixed;
      var fixBest = FONT_ABS_MIN;
      for (var fixStep = 0; fixStep < FONT_BINARY_STEPS; fixStep += 1) {
        var fixMid = (fixLow + fixHigh) / 2;
        fixedProbe.style.fontSize = fixMid + "px";
        if (isProbeOverflowing(fixedProbe)) {
          fixHigh = fixMid;
        } else {
          fixBest = fixMid;
          fixLow = fixMid;
        }
      }
      var fixSafety = vertical ? FONT_SAFETY_VERTICAL : FONT_SAFETY;
      return Math.round(Math.max(fixBest * fixSafety, FONT_ABS_MIN) * 10) / 10;
    }
    var originalH = Number(getOriginalTextHeight(node, height, imageHeightPx)) || 0;
    var range = resolveFontSizeRange(height, originalH);
    var startSize = range.startSize;
    var maxFont = range.maxFont;
    var sourceHeightKey = originalH > 0 ? Math.round(originalH * 10) / 10 : 0;
    var cacheKey = "std|" + (vertical ? "v" : "h") + "|" + width + "x" + height + "|" + sourceHeightKey + "|" + text;
    var cachedSize = fontFitCache.get(cacheKey);
    if (Number.isFinite(cachedSize)) {
      var cachedProbe = prepareMeasureProbe(node, width, height, text, cachedSize);
      if (isProbeOverflowing(cachedProbe)) {
        logFontExpansion(node, width, height, cachedSize, cachedProbe, "cache");
        expandForTextOverflow(node, width, height, cachedProbe);
      }
      return cachedSize;
    }

    resetOverflowExpansion(node);
    var probe = prepareMeasureProbe(node, width, height, text, startSize);
    var low = FONT_ABS_MIN;
    var high = Math.max(startSize, FONT_ABS_MIN);
    var best = FONT_ABS_MIN;
    for (var step = 0; step < FONT_BINARY_STEPS; step += 1) {
      var mid = (low + high) / 2;
      probe.style.fontSize = mid + "px";
      if (isProbeOverflowing(probe)) {
        high = mid;
      } else {
        best = mid;
        low = mid;
      }
    }
    var safety = vertical ? FONT_SAFETY_VERTICAL : FONT_SAFETY;
    var safeSize = clamp(best * safety, FONT_ABS_MIN, maxFont);
    probe.style.fontSize = safeSize + "px";
    if (isProbeOverflowing(probe)) {
      // 到绝对下限仍装不下才撑开（极端：框比一个字还小）。撑开是最后手段，
      // 会把译文画出原文位置 —— 必须留下诊断，下一份日志能直接定位是哪个气泡、差多少。
      logFontExpansion(node, width, height, safeSize, probe, "floor");
      expandForTextOverflow(node, width, height, probe);
    }
    var normalized = Math.round(safeSize * 10) / 10;
    rememberFontFit(cacheKey, normalized);
    return normalized;
  }

  /**
   * 撑开兜底的诊断（2026-10-12）：走到这里 = 缩到 8px 下限仍装不下，节点要被撑出
   * 原文位置了。打出「哪个气泡、框多大、字实际需要多大、最终几号」，下一份 logcat
   * 能直接回答「是框给小了还是译文太长」。
   */
  function logFontExpansion(node, width, height, fontSize, probe, reason) {
    var meta = node && node.dataset;
    var id = (meta && meta.mtLayer) || (meta && meta.index) || "?";
    console.log(
      "MTFONT expand reason=" + reason + " bubble=" + id +
        " box=" + width + "x" + height +
        " need=" + Math.ceil(probe.scrollWidth) + "x" + Math.ceil(probe.scrollHeight) +
        " font=" + fontSize +
        " text=" + String((meta && meta.translated) || "").slice(0, 24)
    );
  }

  function dynamicStrokeWidth(fontSize) {
    var width = clamp((Number(fontSize) || 0) * STROKE_RATIO, 1.8, 4.2);
    return Math.round(width * 10) / 10;
  }

  /**
   * 节点最终该用多宽的描边（并挂上 `.mt-no-stroke` 类）。
   *
   * 宿主按「背景多色 或 原文自带描边」判定后下发 `needs_stroke`（见 [BackgroundOutlinePolicy]）；
   * 这一层只负责执行。**没下发字段时保留描边** —— 老版本宿主的场景 JSON 里没有这个键，
   * 走 `undefined === false` 为假 → 与改动前完全一致。
   */
  function applyStrokeWidth(node, fontSize) {
    if (!node || !node.style || !node.classList) return 0;
    var noStroke = node.dataset && node.dataset.mtNoStroke === "1";
    var width = noStroke ? 0 : dynamicStrokeWidth(fontSize);
    node.classList.toggle("mt-no-stroke", noStroke);
    node.style.setProperty("--mt-stroke-width", width.toFixed(1) + "px");
    return width;
  }

  // ---------------------------------------------------------------------------
  // 挂载宿主与位置同步（overlay-host.js + lifecycle-position.js 精简移植）
  // ---------------------------------------------------------------------------

  var layersByHost = new WeakMap();
  var layers = new Set();
  var overlaysById = new Map();
  // 接缝覆盖层：一条图缝一个 root（key = seam_key），跨两张图只画「一块盖板 + 一份译文」。
  var seamsByKey = new Map();
  var frameSyncHandle = 0;

  function isScrollHost(node) {
    if (!node || typeof getComputedStyle !== "function") return false;
    var style = getComputedStyle(node);
    var scrollValues = /(auto|scroll|overlay|hidden)/;
    var scrolls = scrollValues.test(String(style.overflowY || "").toLowerCase()) ||
      scrollValues.test(String(style.overflowX || "").toLowerCase());
    if (!scrolls) return false;
    return node.scrollHeight > node.clientHeight + 4 || node.scrollWidth > node.clientWidth + 4;
  }

  function isDocumentHost(node) {
    return !node || node === document.documentElement || node === document.body;
  }

  /** 宿主选择：目标最近的可滚动祖先（覆盖层与原图共享滚动上下文，滚动由合成线程一起移动）；没有就退回 documentElement。 */
  function resolveOverlayHost(target) {
    var node = target && target.parentElement;
    while (node && node !== document.documentElement) {
      if (isScrollHost(node)) return node;
      node = node.parentElement;
    }
    return document.documentElement;
  }

  function ensureHostPosition(host) {
    if (!host || isDocumentHost(host) || typeof getComputedStyle !== "function" || !host.style) return;
    var position = getComputedStyle(host).position;
    if (!position || position === "static") host.style.position = "relative";
  }

  function pruneEmptyLayers(keep) {
    layers.forEach(function (layer) {
      if (layer === keep) return;
      if (!layer || !layer.isConnected) {
        layers.delete(layer);
        return;
      }
      if (layer.childElementCount > 0) return;
      layer.remove();
      layers.delete(layer);
    });
  }

  function ensureLayerOnHost(target) {
    var host = resolveOverlayHost(target);
    var known = layersByHost.get(host);
    if (known && known.isConnected) {
      pruneEmptyLayers(known);
      return known;
    }
    ensureHostPosition(host);
    var layer = document.createElement("div");
    layer.className = "mt-overlay-layer";
    layer.dataset.mtOverlay = "true";
    host.appendChild(layer);
    layersByHost.set(host, layer);
    layers.add(layer);
    pruneEmptyLayers(layer);
    return layer;
  }

  /**
   * 可视矩形：与所有 overflow 裁剪祖先求交（否则横滑列表会算错）。
   * 横向被裁切时用 clip-path: inset(0 R 0 L) 收边，不卸载重建。
   */
  function visibleRectOf(target, rect) {
    var visible = { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom };
    var node = target.parentElement;
    while (node && node !== document.documentElement) {
      var style;
      try { style = getComputedStyle(node); } catch (err) { style = null; }
      if (style) {
        var ox = String(style.overflowX || "").toLowerCase();
        var oy = String(style.overflowY || "").toLowerCase();
        var clipsX = ox === "hidden" || ox === "clip" || ox === "auto" || ox === "scroll";
        var clipsY = oy === "hidden" || oy === "clip" || oy === "auto" || oy === "scroll";
        if (clipsX || clipsY) {
          var hostRect = node.getBoundingClientRect();
          if (clipsX) {
            visible.left = Math.max(visible.left, hostRect.left);
            visible.right = Math.min(visible.right, hostRect.right);
          }
          if (clipsY) {
            visible.top = Math.max(visible.top, hostRect.top);
            visible.bottom = Math.min(visible.bottom, hostRect.bottom);
          }
          if (visible.right <= visible.left || visible.bottom <= visible.top) return null;
        }
      }
      node = node.parentElement;
    }
    return visible;
  }

  /**
   * 把「百分比空间算出来的倾角」换算成**屏幕角度**并写回译文节点。
   *
   * 为什么必须换算（2026-09-29 用户反馈「倾斜文本角度和宽度都不对 / 没有完全覆盖」的根因）：
   * 多边形与 `rotation_deg` 都是在**图像百分比空间**里表达的，而 CSS `rotate()` 作用在
   * **屏幕像素**上。图片不是正方形时这两个空间的角度不相等 —— 实测 900×1400 的图上同一个
   * 斜排：百分比空间 13.07°，换成像素是 **19.86°**。遮罩用的是 `clip-path`（百分比直接映射到
   * 元素盒子，形状本来就是对的），只有译文节点的 `rotate()` 会偏；不换算就成了
   * 「遮罩 20°、文字 13°」——文字从遮罩里斜着露出来，看起来就是「没盖住 + 角度不对」。
   *
   * 这个 bug 与 OCR 引擎无关：PaddleOCR 的真实四边形同样会偏（实测 11.30° vs 真实 20°）。
   */
  function applyAspectCorrectedTilt(nodes, rootWidth, rootHeight) {
    if (!nodes || !nodes.length) return;
    var sx = Number(rootWidth);
    var sy = Number(rootHeight);
    if (!(sx > 0) || !(sy > 0)) return;
    nodes.forEach(function (node) {
      if (!node || !node.dataset || !node.dataset.mtTiltPolygon) return;
      var polygon = null;
      try {
        polygon = JSON.parse(node.dataset.mtTiltPolygon);
      } catch (err) {
        polygon = null;
      }
      if (!polygon) return;
      var angle = sanitizeRenderRotationDeg(
        polygonRotationDeg(polygon, sx, sy),
        Number(node.dataset.wPercent) || 0,
        Number(node.dataset.hPercent) || 0
      );
      // 只在真的需要倾斜时改写：轴对齐的多边形 polygonRotationDeg 返回 0，
      // 覆盖成 rotate(0.00deg) 与原来的 translate(-50%,-50%) 等价。
      node.style.setProperty(
        "--mt-base-transform",
        "translate(-50%, -50%) rotate(" + angle.toFixed(2) + "deg)"
      );
    });
  }

  /**
   * 目标 `<img>` 当前的内容地址（虚拟化阅读器会换内容，见 [syncOverlayPosition]）。
   * canvas 没有 src，返回空串（那种情况下不做这个判定）。
   */
  function targetImageSrc(target) {
    if (!target) return "";
    var raw = target.currentSrc || target.src || target.getAttribute && target.getAttribute("src") || "";
    return String(raw || "");
  }

  function syncOverlayPosition(entry) {
    if (!entry || !entry.target || !entry.root.isConnected) return;
    if (!entry.target.isConnected) {
      destroyOverlay(entry);
      return;
    }
    var rect = entry.target.getBoundingClientRect();
    if (!(rect.width >= 2) || !(rect.height >= 2)) {
      entry.root.style.display = "none";
      entry.fontPending = true;
      return;
    }
    // 元素被阅读器回收给了**别的图**：这块覆盖层不属于当前画面，整体卸掉（2026-10-03）。
    // 只认元素会导致「上一页的译文留在新画面上」（用户反馈「有时候不属于这个界面的译文会出现」），
    // 而虚拟化阅读器（Lezhin）恰恰会复用 `<img>`：元素还在、`isConnected` 为真、内容已换。
    if (entry.targetSrc) {
      var nowSrc = targetImageSrc(entry.target);
      if (nowSrc && nowSrc !== entry.targetSrc) {
        console.log("MTSTALE drop image=" + entry.imageId + " reason=target_src_changed");
        destroyOverlay(entry);
        return;
      }
    }
    var layer = entry.layer && entry.layer.isConnected ? entry.layer : ensureLayerOnHost(entry.target);
    entry.layer = layer;
    if (entry.root.parentElement !== layer) layer.appendChild(entry.root);
    var layerRect = layer.getBoundingClientRect();
    var visible = visibleRectOf(entry.target, rect);

    var maskStyle = entry.root.style;
    var maskUrl = entry.cleanedUrl ? maskCssValue(entry.cleanedUrl) : "";
    if (maskUrl) {
      if (maskStyle.getPropertyValue("--mt-cleaned-image") !== maskUrl) {
        maskStyle.setProperty("--mt-cleaned-image", maskUrl);
      }
    } else if (maskStyle.getPropertyValue("--mt-cleaned-image")) {
      maskStyle.removeProperty("--mt-cleaned-image");
    }

    entry.root.style.display = "block";
    if (!visible) {
      entry.root.style.visibility = "hidden";
      entry.fontPending = true;
      return;
    }
    entry.root.style.visibility = "";
    var clipLeft = Math.max(0, visible.left - rect.left);
    var clipRight = Math.max(0, rect.right - visible.right);
    if (clipLeft > 0.5 || clipRight > 0.5) {
      entry.root.style.clipPath = "inset(0 " + clipRight.toFixed(1) + "px 0 " + clipLeft.toFixed(1) + "px)";
    } else {
      entry.root.style.removeProperty("clip-path");
    }

    // 覆盖层与原图在同一个滚动容器里：这个差值在滚动中恒定 —— 只在布局变化时写入，
    // 滚动期间零写入，由合成线程一起移动（不会「拖拽感」「跳走又跳回」）。
    var nextRect = {
      left: Math.round(rect.left - layerRect.left),
      top: Math.round(rect.top - layerRect.top),
      width: Math.round(rect.width),
      height: Math.round(rect.height)
    };
    var last = entry.lastRect;
    var positionChanged = !last || last.left !== nextRect.left || last.top !== nextRect.top;
    var sizeChanged = !last || last.width !== nextRect.width || last.height !== nextRect.height;
    if (positionChanged || sizeChanged) {
      entry.root.style.left = nextRect.left + "px";
      entry.root.style.top = nextRect.top + "px";
      entry.root.style.width = nextRect.width + "px";
      entry.root.style.height = nextRect.height + "px";
      entry.lastRect = nextRect;
      // 百分比 → 像素的倾角换算依赖 root 尺寸，所以只在尺寸变化时重算（滚动期零写入）。
      applyAspectCorrectedTilt(entry.textNodes, nextRect.width, nextRect.height);
    }

    // 平移只更新根坐标；尺寸不变绝不重测文字（避免滚动抖动）。
    // 但 rect 无效期挂起的气泡（mtFontPending）在尺寸恢复后必须补一次适配。
    var pendingFontFit = entry.fontPending === true || entry.textNodes.some(function (node) {
      return node.isConnected && !node.style.fontSize;
    });
    if (!sizeChanged && !pendingFontFit) return;

    entry.textNodes.forEach(function (node) {
      if (!node.isConnected) return;
      var wPercent = Number(node.dataset.wPercent) || 0;
      var hPercent = Number(node.dataset.hPercent) || 0;
      var bubbleWidthPx = rect.width * wPercent / 100;
      var bubbleHeightPx = rect.height * hPercent / 100;
      if (bubbleWidthPx < 2 || bubbleHeightPx < 2) {
        node.dataset.mtFontPending = "1";
        entry.fontPending = true;
        return;
      }
      node.dataset.mtFontPending = "";
      entry.fontPending = false;
      var fitted = fitBubbleFontSize(node, bubbleWidthPx, bubbleHeightPx, rect.height);
      node.style.fontSize = fitted.toFixed(1) + "px";
      applyStrokeWidth(node, fitted);
      // 字号定了之后做一次「词边界 + 像素宽」均衡重排（2026-10-14）：
      // 保持行数不变，只换断点；断不开就保持现状（见 rebalanceTextNode）。
      try {
        rebalanceTextNode(node, bubbleWidthPx, fitted);
      } catch (err) {
        // 重排失败不能拖垮渲染：保持原断行。
      }
    });
  }

  function destroyOverlay(entry) {
    if (entry.root && entry.root.isConnected) entry.root.remove();
    overlaysById.delete(entry.imageId);
    stopFrameSyncIfIdle();
  }

  /** 帧同步只在「一个覆盖层都没有」时停：接缝覆盖层与 loading 框也要跟着一起同步。 */
  function hasLiveOverlays() {
    return overlaysById.size > 0 || seamsByKey.size > 0 || loadingById.size > 0;
  }

  function stopFrameSyncIfIdle() {
    if (!hasLiveOverlays()) stopFrameSync();
  }

  function syncAllOverlays() {
    overlaysById.forEach(function (entry) {
      try {
        syncOverlayPosition(entry);
      } catch (err) {
        // 单个覆盖层同步失败不能拖垮整页。
      }
    });
    seamsByKey.forEach(function (entry) {
      try {
        syncSeamPosition(entry);
      } catch (err) {
        // 接缝覆盖层同步失败同样只影响它自己。
      }
    });
    loadingById.forEach(function (entry) {
      try {
        syncLoadingPosition(entry);
      } catch (err) {
        // loading 框同步失败不能拖垮整页。
      }
    });
  }

  function startFrameSync() {
    if (frameSyncHandle) return;
    var tick = function () {
      frameSyncHandle = 0;
      if (!hasLiveOverlays()) return;
      syncAllOverlays();
      frameSyncHandle = window.requestAnimationFrame(tick);
    };
    frameSyncHandle = window.requestAnimationFrame(tick);
  }

  function stopFrameSync() {
    if (frameSyncHandle) {
      window.cancelAnimationFrame(frameSyncHandle);
      frameSyncHandle = 0;
    }
  }

  // ---------------------------------------------------------------------------
  // 场景构建（render-scene.js 精简移植：冻结的纯数据 + 渲染器）
  // ---------------------------------------------------------------------------

  var LAYER_TYPES = Object.freeze({ COVER: "cover", TEXT: "text", DEBUG: "debug", LOADING: "loading" });

  function createRenderLayer(value) {
    if (!Object.values(LAYER_TYPES).includes(value && value.type)) {
      throw new TypeError("Unknown render layer type: " + (value && value.type));
    }
    var id = String(value.id || "").trim();
    if (!id) throw new TypeError("Render layer id is required");
    return Object.freeze({
      id: id,
      type: value.type,
      active: value.active !== false,
      geometry: Object.freeze(Object.assign({}, value.geometry || {})),
      content: value.content ? Object.freeze(Object.assign({}, value.content)) : null
    });
  }

  /**
   * RenderScene：不可变数据（Object.freeze）。layers 顺序 = 挂载顺序：
   * cover 层全部在前，text 层全部在后（相邻遮罩不能盖住别人的译文）。
   */
  function createRenderScene(id, payload) {
    var bubbles = Array.isArray(payload.bubbles) ? payload.bubbles : [];
    // §11 三级背景降级：复合清理图（带内）+ 整页清理图（带外）。跨页分段渲染依赖它们。
    sceneCleanedUrls = {
      composite: payload.cleaned_url || null,
      page: payload.page_cleaned_url || null
    };
    var layers = [];
    bubbles.forEach(function (bubble, index) {
      var bubbleId = String(bubble && bubble.id || "b" + index);
      // 单个气泡的渲染异常不能拖垮整页：坏气泡跳过，其余照常上屏。
      var coverNode = null;
      var clipNodes = [];
      try {
        clipNodes = createCoverLayers(bubble, index);
        coverNode = clipNodes.length > 0 ? clipNodes[0].querySelector(".mt-cover-only") : null;
      } catch (err) {
        coverNode = null;
        clipNodes = [];
      }
      clipNodes.forEach(function (clipNode, segIndex) {
        layers.push(createRenderLayer({
          id: bubbleId + ":cover" + (segIndex > 0 ? ":" + segIndex : ""),
          type: LAYER_TYPES.COVER,
          geometry: { box: resolveBubbleCoverBox(bubble) },
          content: { node: clipNode }
        }));
      });
      var textNode = null;
      try {
        textNode = createTextNode(bubble, index);
      } catch (err) {
        textNode = null;
      }
      if (textNode) {
        var displayText = formatTranslationForOriginalLines(
          cleanRenderableText(bubble.translated_text) || cleanRenderableText(bubble.original_text),
          Math.max(1, Math.round(Number(bubble.source_line_count) || 1))
        );
        var vertical = shouldUseVerticalLayout(bubble, displayText);
        textNode.classList.toggle("mt-jp-vertical", vertical);
        var contentNode = document.createElement("div");
        contentNode.className = "mt-bubble-content";
        contentNode.textContent = displayText;
        // 气泡专用文字排版兜底：文字统一由内层节点居中显示并保留换行。
        contentNode.style.width = "100%";
        contentNode.style.textAlign = "center";
        contentNode.style.whiteSpace = "pre-line";
        textNode.appendChild(contentNode);
        // 字号兜底撑开时，实底遮罩必须同步撑满（否则译文张到框外、原文露在外面）。
        // 只对 solid 生效：清理图背景的贴图数学相对节点自身尺寸，撑开会错位。
        // 分片场景下多块遮罩各自撑开；这里把整条链都挂上，保证没有一块漏掉。
        if (clipNodes.length > 0 && coverNode && coverNode.classList.contains("mt-bg-solid")) {
          var maskChain = [];
          clipNodes.forEach(function (clipNode) {
            var inner = clipNode.querySelector(".mt-cover-only");
            if (inner) maskChain.push(inner, clipNode);
          });
          if (maskChain.length > 0) textNode.__mtMaskChain = maskChain;
        }
        layers.push(createRenderLayer({
          id: bubbleId + ":text",
          type: LAYER_TYPES.TEXT,
          geometry: { box: { x: Number(bubble.x) || 0, y: Number(bubble.y) || 0, w: Number(bubble.w) || 0, h: Number(bubble.h) || 0 } },
          content: { node: textNode }
        }));
      }
    });
    return Object.freeze({
      id: String(id || ""),
      surface: Object.freeze({ width: Number(payload.surface_width) || 0, height: Number(payload.surface_height) || 0 }),
      layers: Object.freeze(layers)
    });
  }

  // ---------------------------------------------------------------------------
  // loading 框（2026-10-02）
  //
  // 参考项目 `C:\homework\translator` 在漫画链路上会先给目标图挂一个 `.mt-loading-card`
  // （半透明胶囊 + 阶段文案：「识别当前页…」「翻译文字中…」「渲染结果…」），
  // 处理完再换成译文覆盖层。这里做同一件事，只是文案由 Kotlin 侧按阶段下发：
  // 单张图要走「下载解码 → OCR → 翻译 → 擦字渲染」，RIDI/Lezhin 这种大图上动辄 2–5 秒，
  // 没有卡片就是「盯着原图干等」。
  //
  // 三个容易做错的点：
  // 1. **锚点取「可见部分的中心」**：webtoon 一张图能有 3000px 高，图片中心在屏幕外，
  //    按图片中心摆等于没显示（参考项目的 getLoadingOverlayCardPosition 同理）；
  // 2. 卡片挂在**和译文覆盖层同一个 layer** 里，跟着滚动一起被合成线程搬运，不抖不飘；
  // 3. **任何出口都要清掉**（成功贴图 / 无文字 / 失败 / 停止会话 / 切「原文」/ 页面跳转），
  //    否则页面上会永久留一个"正在翻译"的胶囊 —— 那比没有 loading 更糟。
  // ---------------------------------------------------------------------------

  var loadingById = new Map();
  var DEFAULT_LOADING_LABEL = "OCR + 翻译中…";

  /** 视口尺寸兜底：极少数环境下 innerWidth/Height 拿不到，退回 documentElement 的盒子。 */
  function viewportSize() {
    var width = Number(window.innerWidth) || 0;
    var height = Number(window.innerHeight) || 0;
    if (!(width > 0) || !(height > 0)) {
      try {
        var docRect = document.documentElement.getBoundingClientRect();
        if (!(width > 0)) width = Number(docRect.width) || 0;
        if (!(height > 0)) height = Number(docRect.height) || 0;
      } catch (err) {
        // 量不到就按 0 处理：下面的 clamp 会用图片自己的边界兜底。
      }
    }
    return { width: width, height: height };
  }

  /** 图片元素：与场景同一条查找规则（扫描脚本打的 data-mt-id，兜底用元素 id）。 */
  function findImageTarget(imageId) {
    var id = String(imageId == null ? "" : imageId);
    if (!id) return null;
    return document.querySelector('[data-mt-id="' + id + '"]') || document.getElementById(id);
  }

  /** 卡片相对 layer 的落点：目标矩形 ∩ 被裁剪后的可见部分 ∩ 视口，取三者交集的中心。 */
  function loadingAnchor(entry) {
    var target = entry.target;
    if (!target || !target.isConnected) return null;
    var rect = target.getBoundingClientRect();
    if (!(rect.width >= 2) || !(rect.height >= 2)) return null;
    var visible = visibleRectOf(target, rect);
    if (!visible) return null;
    var view = viewportSize();
    var left = Math.max(rect.left, visible.left, 0);
    var right = Math.min(rect.right, visible.right, view.width > 0 ? view.width : rect.right);
    var top = Math.max(rect.top, visible.top, 0);
    var bottom = Math.min(rect.bottom, visible.bottom, view.height > 0 ? view.height : rect.bottom);
    if (!(right > left) || !(bottom > top)) return null;
    var layer = entry.layer && entry.layer.isConnected ? entry.layer : ensureLayerOnHost(target);
    var layerRect = layer.getBoundingClientRect();
    return {
      layer: layer,
      left: Math.round((left + right) / 2 - layerRect.left),
      top: Math.round((top + bottom) / 2 - layerRect.top)
    };
  }

  function syncLoadingPosition(entry) {
    if (!entry || !entry.root || !entry.root.isConnected) return;
    if (!entry.target || !entry.target.isConnected) {
      destroyLoading(entry);
      return;
    }
    var anchor = loadingAnchor(entry);
    if (!anchor) {
      // 整张图当前完全不可见（划走了 / 被裁掉）：藏起来但保留登记，滚回来还能接着显示。
      entry.root.style.visibility = "hidden";
      entry.lastPos = null;
      return;
    }
    if (entry.root.parentElement !== anchor.layer) anchor.layer.appendChild(entry.root);
    entry.layer = anchor.layer;
    entry.root.style.visibility = "";
    var last = entry.lastPos;
    if (!last || last.left !== anchor.left || last.top !== anchor.top) {
      entry.root.style.left = anchor.left + "px";
      entry.root.style.top = anchor.top + "px";
      entry.lastPos = { left: anchor.left, top: anchor.top };
    }
  }

  function destroyLoading(entry) {
    if (!entry) return false;
    if (entry.root && entry.root.isConnected) entry.root.remove();
    loadingById.delete(entry.imageId);
    stopFrameSyncIfIdle();
    return true;
  }

  /** 建卡/更新文案（幂等）：同一张图重复下发只改文字，不重建节点。 */
  function showLoading(payload) {
    injectCss();
    var imageId = String(payload && payload.image_id || "");
    if (!imageId) return JSON.stringify({ ok: false, reason: "no_image_id" });
    var target = findImageTarget(imageId);
    if (!target) return JSON.stringify({ ok: false, reason: "no_target" });
    var label = String((payload && payload.label) || "").trim() || DEFAULT_LOADING_LABEL;
    var existing = loadingById.get(imageId);
    if (existing && existing.target === target && existing.root.isConnected) {
      if (existing.label.textContent !== label) existing.label.textContent = label;
      syncLoadingPosition(existing);
      startFrameSync();
      return JSON.stringify({ ok: true, reused: true, image_id: imageId, label: label });
    }
    if (existing) destroyLoading(existing);

    var layer = ensureLayerOnHost(target);
    var root = document.createElement("div");
    root.className = "mt-loading-root __mt_loading_root";
    root.dataset.mtOverlay = "true";
    root.dataset.imageId = imageId;
    root.style.position = "absolute";
    root.style.transform = "translate(-50%, -50%)";
    var card = document.createElement("div");
    card.className = "mt-loading-card";
    var spinner = document.createElement("span");
    spinner.className = "mt-loading-spinner";
    var text = document.createElement("span");
    text.className = "mt-loading-label";
    text.textContent = label;
    card.appendChild(spinner);
    card.appendChild(text);
    root.appendChild(card);
    layer.appendChild(root);

    var entry = {
      imageId: imageId,
      target: target,
      root: root,
      card: card,
      label: text,
      layer: layer,
      lastPos: null
    };
    loadingById.set(imageId, entry);
    syncLoadingPosition(entry);
    startFrameSync();
    return JSON.stringify({ ok: true, reused: false, image_id: imageId, label: label });
  }

  function showLoadingJson(jsonText) {
    try {
      var payload = typeof jsonText === "string" ? JSON.parse(jsonText) : jsonText;
      return showLoading(payload);
    } catch (err) {
      return JSON.stringify({ ok: false, reason: "exception:" + String(err && err.message || err).slice(0, 80) });
    }
  }

  /** 关掉一张图的 loading 框（成功贴图 / 无文字 / 失败都会走到这里）。 */
  function hideLoading(imageId) {
    var entry = loadingById.get(String(imageId == null ? "" : imageId));
    if (!entry) return 0;
    destroyLoading(entry);
    return 1;
  }

  /** 清掉全部 loading 框（切「原文」、停止会话、页面跳转前的安全网）。 */
  function clearLoadings() {
    var count = 0;
    Array.from(loadingById.keys()).forEach(function (imageId) {
      var entry = loadingById.get(imageId);
      if (entry && destroyLoading(entry)) count += 1;
    });
    return count;
  }

  // ---------------------------------------------------------------------------
  // 入口
  // ---------------------------------------------------------------------------

  /**
   * 排版框重叠诊断（2026-10-12，只记日志不改行为）。
   *
   * 用户截图里「两份译文叠在同一处」可能有两种成因：宿主分组层把两个气泡的排版框
   * 算到了一起（框本身重叠），或接缝层与场景层各画了一份。这条日志把「同一 root 内
   * 两个文字节点的排版框大面积交叠」直接打出来 —— 下一份 logcat 就能区分：
   * MTGEOM 有输出 = 宿主下发的框本身重叠（查分组/接缝归属）；没有 = 渲染层自己画重了。
   *
   * 阈值 0.5：排版框是字形框朝气泡方向外扩过的产物，相邻气泡轻微相交属正常；
   * 交叠面积超过较小框的一半才值得报告。斜排气泡的轴对齐外接框相交更多，可能误报
   * —— 所以只记日志，绝不作为行为的判据。
   */
  function logTextOverlap(textNodes, context) {
    for (var i = 0; i < textNodes.length; i += 1) {
      for (var j = i + 1; j < textNodes.length; j += 1) {
        var a = textNodes[i];
        var b = textNodes[j];
        if (!a || !b || !a.dataset || !b.dataset) continue;
        var ax = Number(a.dataset.xPercent) || 0;
        var ay = Number(a.dataset.yPercent) || 0;
        var aw = Number(a.dataset.wPercent) || 0;
        var ah = Number(a.dataset.hPercent) || 0;
        var bx = Number(b.dataset.xPercent) || 0;
        var by = Number(b.dataset.yPercent) || 0;
        var bw = Number(b.dataset.wPercent) || 0;
        var bh = Number(b.dataset.hPercent) || 0;
        var ix = Math.max(0, Math.min(ax + aw, bx + bw) - Math.max(ax, bx));
        var iy = Math.max(0, Math.min(ay + ah, by + bh) - Math.max(ay, by));
        var inter = ix * iy;
        var minArea = Math.min(aw * ah, bw * bh);
        if (inter <= 0 || minArea <= 0) continue;
        var ratio = inter / minArea;
        if (ratio < 0.5) continue;
        console.log(
          "MTGEOM overlap " + context + " ratio=" + (Math.round(ratio * 100) / 100) +
            " a=" + ((a.dataset.mtLayer || a.dataset.index) || "?") + " " +
            Math.round(ax) + "," + Math.round(ay) + " " + Math.round(aw) + "x" + Math.round(ah) +
            " b=" + ((b.dataset.mtLayer || b.dataset.index) || "?") + " " +
            Math.round(bx) + "," + Math.round(by) + " " + Math.round(bw) + "x" + Math.round(bh)
        );
      }
    }
  }

  function applyScene(payload) {
    injectCss();
    var imageId = String(payload && payload.image_id || "");
    if (!imageId) return JSON.stringify({ ok: false, reason: "no_image_id" });
    var target = findImageTarget(imageId);
    if (!target) return JSON.stringify({ ok: false, reason: "no_target" });

    // 同一张 `<img>` 只允许一套覆盖层（2026-10-16，用户报「背景覆盖定位出错、超出对话框」）。
    //
    // 去重本来按 imageId 走，但 id 不总跟元素走：站点 SPA 重渲染 / 虚拟化重建 `<img>`
    // 后扫描脚本会赋**新 id**（`__mtSeq` 归零重来），旧 id 的覆盖层还挂在同一张图上 ——
    // `targetSrc` 判定只管「元素内容换人」，管不了「元素没换、id 换了」。两套覆盖层
    // 各自被帧同步锚到不同时刻的矩形，肉眼就是「盖板错位/戳出气泡」。
    // 这里按**目标元素**兜底清理：同一 `<img>` 上除当前 id 外的覆盖层一律卸掉。
    overlaysById.forEach(function (stale, staleId) {
      if (staleId === imageId) return;
      if (stale.target !== target) return;
      console.log("MTSTALE drop image=" + staleId + " reason=same_target_new_id current=" + imageId);
      destroyOverlay(stale);
    });

    var bubbles = Array.isArray(payload.bubbles) ? payload.bubbles : [];
    var signature = djb2Hash(JSON.stringify([
      payload.cleaned_url || "", payload.page_cleaned_url || "", bubbles
    ]));
    var existing = overlaysById.get(imageId);
    if (existing && existing.signature === signature) {
      // 相同场景只同步位置，保留 root 与节点身份 —— 重建就是肉眼可见的闪烁。
      syncOverlayPosition(existing);
      startFrameSync();
      return JSON.stringify({ ok: true, rebuilt: false, bubbles: bubbles.length });
    }

    var scene = createRenderScene(imageId, payload);
    var coverNodes = [];
    var textNodes = [];
    scene.layers.forEach(function (layer) {
      if (!layer.active) return;
      var node = layer.content && layer.content.node;
      if (!node) return;
      // 层 id 落到 DOM 上：§11 视觉去重按气泡摘节点时不用重发整个场景。
      node.dataset.mtLayer = layer.id;
      if (layer.type === LAYER_TYPES.COVER) coverNodes.push(node);
      if (layer.type === LAYER_TYPES.TEXT) textNodes.push(node);
    });
    if (coverNodes.length === 0 && textNodes.length === 0) {
      if (existing) destroyOverlay(existing);
      return JSON.stringify({ ok: true, rebuilt: true, bubbles: 0 });
    }
    logTextOverlap(textNodes, "scene=" + imageId);

    var layer = ensureLayerOnHost(target);
    var root = document.createElement("div");
    root.className = "mt-overlay-root __mt_scene_root";
    root.dataset.mtOverlay = "true";
    root.dataset.imageId = imageId;
    // 接缝层顶掉的「两半」在场景**重建**后要继续被顶掉（2026-10-14，用户：「向下划后再次
    // 向上划的时候可能会出现重复的译文」）。
    //
    // 现场：懒加载站点滚动时把 <img> 换成新 blob URL —— 会话按内容指纹复用场景重新下发，
    // `applyScene` 建新 root，而 `suppressBubble` 摘掉的那半块又回来了；接缝层仍活着
    // → 同一句台词画两遍（一份来自接缝带、一份来自被贴回来的半块）。
    // 登记表里的节点在这里**换绑到新 root**（同名 layerId 视为同一块），贴回时依旧有效。
    var visibleCoverNodes = rebindSuppressedNodes(imageId, root, coverNodes);
    var visibleTextNodes = rebindSuppressedNodes(imageId, root, textNodes);
    // 所有背景先绘制、所有译文后绘制：相邻蓝框再接近，背景也不能盖住别人的文字。
    visibleCoverNodes.forEach(function (node) { root.appendChild(node); });
    visibleTextNodes.forEach(function (node) {
      var delay = Math.min(visibleTextNodes.indexOf(node) * 34, 320);
      node.classList.add("mt-stream-enter");
      node.style.setProperty("--mt-stream-delay", delay + "ms");
      root.appendChild(node);
    });

    var entry = {
      imageId: imageId,
      target: target,
      /**
       * 绑定时这张图的内容地址（2026-10-03）。
       *
       * 为什么必须记住：虚拟化阅读器（Lezhin 实测）会**回收 `<img>` 元素**再拿去显示别的页 ——
       * 元素还在 DOM 里、`isConnected` 依然为真，但内容已经换人。覆盖层只认元素就会把**上一页的
       * 译文留在新画面上**（用户反馈「有时候不属于这个界面的译文会出现」）。
       * 见 [syncOverlayPosition] 里的 sameTargetImage 判定。
       */
      targetSrc: targetImageSrc(target),
      root: root,
      layer: layer,
      signature: signature,
      cleanedUrl: String(payload.cleaned_url || ""),
      // 只登记**留在画面上**的文字层：被接缝层顶掉的半块虽然换绑到了新 root，
      // 但它此刻不在 DOM 里（见 rebindSuppressedNodes），字号拟合/同步都该跳过它。
      textNodes: visibleTextNodes,
      lastRect: null,
      fontPending: true,
      // §11 跨图 union 需要原始气泡数据来重建遮罩几何；按 id 存一份，避免二次解析 payload。
      bubbleById: (function () {
        var map = new Map();
        bubbles.forEach(function (bubble, index) {
          var id = String(bubble && bubble.id || "b" + index);
          map.set(id, { bubble: bubble, index: index });
        });
        return map;
      })()
    };
    if (existing) {
      if (existing.root.isConnected) existing.root.replaceWith(root);
      overlaysById.delete(existing.imageId);
    }
    layer.appendChild(root);
    overlaysById.set(imageId, entry);
    syncOverlayPosition(entry);
    startFrameSync();
    // 流式入场：淡入结束后移除动画类（之后滚动/重建不再触发动画）。
    visibleTextNodes.forEach(function (node, index) {
      window.setTimeout(function () {
        if (node.isConnected) {
          node.classList.remove("mt-stream-enter");
          node.style.removeProperty("--mt-stream-delay");
        }
      }, Math.min(index * 34, 320) + 420);
    });
    return JSON.stringify({
      ok: true,
      rebuilt: true,
      bubbles: visibleTextNodes.length,
      suppressed: textNodes.length - visibleTextNodes.length,
      covers: visibleCoverNodes.length,
      host: entry.layer === layer ? (isDocumentHost(layer.parentElement) ? "document" : "scroll") : "unknown"
    });
  }

  /**
   * 场景重建时把「被接缝层顶掉的半块」继续摘掉（2026-10-14）。
   *
   * 返回**该挂进新 root**的节点；被顶掉的节点换绑登记表的 parent/node 后不返回
   * （接缝层失效时 [restoreSeamHalves] 会把它们贴回来 —— 那时它们会被挂到新 root 上）。
   *
   * 只在同名 `data-mt-layer` 命中登记表时生效：登记表按 imageId + layerId 记录，
   * 会话重发的场景与第一次下发的场景同源（同一份 `BrowserSceneBuilder` 数据）。
   */
  function rebindSuppressedNodes(imageId, root, nodes) {
    if (!suppressedHalves.length) return nodes;
    var kept = [];
    nodes.forEach(function (node) {
      var layerId = String((node && node.dataset && node.dataset.mtLayer) || "");
      var record = layerId ? suppressedHalves.find(function (item) {
        return item.imageId === imageId && item.layerId === layerId;
      }) : null;
      if (!record) {
        kept.push(node);
        return;
      }
      record.node = node;
      record.parent = root;
    });
    return kept;
  }

  /** 由 Kotlin 侧调用：JSON 字符串 → 场景。 */
  function applySceneJson(jsonText) {
    try {
      var payload = typeof jsonText === "string" ? JSON.parse(jsonText) : jsonText;
      return applyScene(payload);
    } catch (err) {
      return JSON.stringify({ ok: false, reason: "exception:" + String(err && err.message || err).slice(0, 80) });
    }
  }

  /**
   * 移除全部场景 root（「原文」视图）。
   *
   * loading 框也要一起清掉：切「原文」的语义是「让我看原图」，
   * 留一个"正在翻译"的胶囊在上面既没意义、又会让用户以为切失败了。
   */
  function removeScenes() {
    var removed = 0;
    document.querySelectorAll(".__mt_scene_root").forEach(function (node) {
      node.remove();
      removed += 1;
    });
    Array.from(overlaysById.keys()).forEach(function (key) { overlaysById.delete(key); });
    // 接缝覆盖层也带着 __mt_scene_root（上面已从 DOM 摘掉），这里清掉它的登记表。
    seamsByKey.clear();
    // 「原文」视图：整个覆盖层都没了，被顶掉的两半也就不必再贴回去（登记表清掉，避免下次
    // 误贴进已经重建的层里）。
    suppressedHalves = [];
    clearLoadings();
    stopFrameSync();
    return removed;
  }

  /**
   * 清理图晚绑定（§5 第 2 步）：整页清理图是页面级异步产物，覆盖层永远先上屏
   * （透明遮罩）。图落地那一刻**直接写到已经存在的 DOM 上**，不等下一次场景下发 ——
   * 少了这一步就会出现「同一章里有些分片有遮罩、有些没有」。
   * 幂等：值没变不重写（同一 dataURL 恒映射到同一个 blob URL 是这个判断成立的前提）。
   */
  function applyCleanedImage(imageId, url) {
    var entry = overlaysById.get(String(imageId || ""));
    if (!entry || !entry.root || !entry.root.isConnected) return false;
    var next = String(url || "");
    if (entry.cleanedUrl === next) return false;
    entry.cleanedUrl = next;
    var value = next ? maskCssValue(next) : "";
    if (value) entry.root.style.setProperty("--mt-cleaned-image", value);
    else entry.root.style.removeProperty("--mt-cleaned-image");
    // 定向刷新一次：只同步位置/裁剪，绝不重建节点。
    try { syncOverlayPosition(entry); } catch (err) { /* 单帧同步失败不影响遮罩已上屏 */ }
    return true;
  }

  /** 批量晚绑定：`[{"image_id":"..","url":".."}]`；返回实际写入条数。 */
  function applyCleanedImages(jsonText) {
    var list;
    try {
      list = typeof jsonText === "string" ? JSON.parse(jsonText) : jsonText;
    } catch (err) {
      return JSON.stringify({ ok: false, reason: "bad_json", applied: 0 });
    }
    var applied = 0;
    (Array.isArray(list) ? list : []).forEach(function (item) {
      try {
        if (applyCleanedImage(item && item.image_id, item && item.url)) applied += 1;
      } catch (err) { /* 单条失败不阻断其余 */ }
    });
    return JSON.stringify({ ok: true, applied: applied });
  }

  /** 渲染侧反推「我需要哪张清理图」：只有真的画了透明遮罩（bg_type=none）的覆盖层才需要。 */
  function pendingCleanedImageIds() {
    var ids = [];
    overlaysById.forEach(function (entry, imageId) {
      if (!entry || !entry.root || !entry.root.isConnected) return;
      if (entry.cleanedUrl) return;
      var needsCleaned = entry.textNodes.some(function (node) {
        return node.isConnected && node.classList.contains("mt-bg-none");
      });
      if (needsCleaned) ids.push(imageId);
    });
    return JSON.stringify(ids);
  }

  /**
   * §11 视觉去重：相邻分片重叠区里，同一个气泡可能被两轮翻译各渲染一次。
   * 上层判定出「这条是重复的」后，把指定气泡的遮罩+译文整组摘掉，
   * 不必重发整个场景（重发就是肉眼可见的闪烁）。
   * 返回被摘掉的节点数；气泡不存在时返回 0。
   *
   * 被接缝层顶掉的「两半」节点（2026-10-03）。
   *
   * 为什么必须留着：接缝层成功上屏后，宿主会立刻把上页/下页各自那一半摘掉（否则一块
   * 台词会画两遍）。但接缝层的几何前提**可能随后失效** —— 页面重排、两张切片不再相接、
   * 图被卸载重建 —— 那时接缝层会把自己 `display:none`，而两半已经被摘掉且**无法恢复**，
   * 接缝那块就只剩清理图背景（原文和译文同时消失）。
   *
   * 所以摘掉改成「移出 DOM 但登记起来」，接缝层一旦失效就按 seam_key 把对应节点贴回去。
   */
  var suppressedHalves = [];

  function suppressBubble(imageId, bubbleId) {
    var entry = overlaysById.get(String(imageId || ""));
    if (!entry || !entry.root) return 0;
    var target = String(bubbleId || "");
    if (!target) return 0;
    var removed = 0;
    var nodes = [];
    var all = entry.root.children || [];
    for (var i = 0; i < all.length; i += 1) {
      var node = all[i];
      var lid = node && node.dataset && node.dataset.mtLayer;
      if (!lid) continue;
      if (lid === target + ":text" || lid === target + ":cover" ||
        lid.indexOf(target + ":cover:") === 0) {
        nodes.push(node);
      }
    }
    nodes.forEach(function (node) {
      var parent = node.parentElement;
      if (!parent) return;
      suppressedHalves.push({
        node: node,
        parent: parent,
        imageId: String(imageId || ""),
        layerId: String((node.dataset && node.dataset.mtLayer) || "")
      });
      parent.removeChild(node);
      removed += 1;
    });
    if (removed > 0) {
      entry.textNodes = (entry.textNodes || []).filter(function (node) { return node.isConnected; });
    }
    return removed;
  }

  /**
   * 丢弃某个接缝登记的两半节点（**不贴回**）：目标图内容已换，贴回去等于把旧译文画到新画面上。
   */
  function discardSeamHalves(seamKey) {
    var parts = String(seamKey || "").split("|");
    if (parts.length !== 2) return 0;
    var own = parts[0];
    var next = parts[1];
    var kept = [];
    var dropped = 0;
    suppressedHalves.forEach(function (record) {
      if (record.imageId === own || record.imageId === next) {
        dropped += 1;
        return;
      }
      kept.push(record);
    });
    suppressedHalves = kept;
    return dropped;
  }

  /**
   * 把某个接缝（seam_key = `ownImageId|nextImageId`）挡掉的两半贴回去。
   *
   * 只在接缝层确实失效时调用；返回贴回的节点数。
   */
  function restoreSeamHalves(seamKey) {
    var parts = String(seamKey || "").split("|");
    if (parts.length !== 2) return 0;
    var own = parts[0];
    var next = parts[1];
    var kept = [];
    var restored = 0;
    var discarded = 0;
    suppressedHalves.forEach(function (record) {
      if (record.imageId !== own && record.imageId !== next) {
        kept.push(record);
        return;
      }
      // 记录还有效才贴回（2026-10-03）：节点所在的场景 root 可能已经被重建、或目标 `<img>`
      // 已被阅读器换成了别的页 —— 那时贴回去就是把**上一页的译文画到新画面上**。
      var entry = overlaysById.get(record.imageId);
      var stillValid = !!entry && !!entry.root && entry.root.isConnected &&
        record.parent === entry.root &&
        (!entry.targetSrc || targetImageSrc(entry.target) === entry.targetSrc);
      if (!stillValid) {
        discarded += 1;
        return;
      }
      if (record.parent && record.parent.isConnected && !record.node.isConnected) {
        record.parent.appendChild(record.node);
        restored += 1;
      }
    });
    suppressedHalves = kept;
    if (discarded > 0) {
      console.log("MTSTALE discard-halves seam=" + seamKey + " nodes=" + discarded);
    }
    return restored;
  }

  /**
   * §11 跨图 union 的增量入口：相邻分片翻译完回来时，把上一张图里某个已渲染气泡的
   * **遮罩几何**换成「跨图完整形状」（完整四边形 / 跨出边界的框）。
   *
   * 只重建这一个气泡的遮罩节点，译文节点与字号完全不动 —— 重排文字会让已经读了一半的
   * 台词跳一下，那是比遮罩略斜更糟的观感。分片场景下若换成了多段，也一并替换。
   *
   * 返回是否真的改动了 DOM。
   */
  function updateBubbleCover(imageId, bubbleId, geometry) {
    var entry = overlaysById.get(String(imageId || ""));
    if (!entry || !entry.root || !entry.root.isConnected || !entry.bubbleById) return false;
    var target = String(bubbleId || "");
    var record = entry.bubbleById.get(target);
    if (!target || !record) return false;
    var patch = geometry || {};

    var oldCovers = [];
    var textNode = null;
    var children = entry.root.children || [];
    for (var i = 0; i < children.length; i += 1) {
      var lid = children[i].dataset && children[i].dataset.mtLayer;
      if (!lid) continue;
      if (lid === target + ":cover" || lid.indexOf(target + ":cover:") === 0) oldCovers.push(children[i]);
      else if (lid === target + ":text") textNode = children[i];
    }
    if (oldCovers.length === 0) return false;

    // 原始气泡 + 跨图补丁：完整形状可能跨出本图（y 为负 / 超出 100），一律允许溢出。
    var merged = {};
    Object.keys(record.bubble).forEach(function (key) { merged[key] = record.bubble[key]; });
    ["x", "y", "w", "h"].forEach(function (key) {
      if (patch[key] != null && Number.isFinite(Number(patch[key]))) merged[key] = Number(patch[key]);
    });
    if (patch.polygon != null) merged.polygon = patch.polygon;
    if (patch.rotation_deg != null) merged.rotation_deg = patch.rotation_deg;
    if (patch.cleaned_source_box != null) merged.cleaned_source_box = patch.cleaned_source_box;
    if (patch.bg_color != null) merged.bg_color = patch.bg_color;
    // 增量更新只表达「一个完整遮罩」：显式清掉分片，交给单块路径重建。
    merged.cover_segments = null;
    merged.stitch_overflow = true;

    var freshCovers = [];
    try {
      freshCovers = createCoverLayers(merged, record.index);
    } catch (err) {
      return false;
    }
    if (freshCovers.length === 0) return false;

    var anchor = oldCovers[0];
    var parent = anchor.parentElement;
    if (!parent) return false;
    var insertAt = Array.prototype.indexOf.call(parent.children, anchor);
    oldCovers.forEach(function (node) { if (node.parentElement) node.parentElement.removeChild(node); });
    freshCovers.forEach(function (node, offset) {
      node.dataset.mtLayer = target + ":cover" + (offset > 0 ? ":" + offset : "");
      parent.insertBefore(node, parent.children[insertAt + offset] || null);
    });

    // 实底气泡的撑开链指向旧遮罩节点：换成新的，否则译文再撑开时遮罩不会跟着长。
    if (textNode && textNode.__mtMaskChain) {
      var inner = freshCovers[0].querySelector(".mt-cover-only");
      if (inner && inner.classList.contains("mt-bg-solid")) {
        var chain = [];
        freshCovers.forEach(function (clip) {
          var node = clip.querySelector(".mt-cover-only");
          if (node) chain.push(node, clip);
        });
        textNode.__mtMaskChain = chain;
      } else {
        textNode.__mtMaskChain = null;
      }
    }
    return true;
  }

  // ---------------------------------------------------------------------------
  // 接缝覆盖层（跨两张图一个气泡：**一块盖板 + 一份译文**）
  //
  // 现象：普通场景是「一张图一个 root」，root 尺寸 = 那张图的可见矩形。跨缝的气泡于是被
  // 拆成两个独立渲染单元（上页半块 + 下页半块），各自用自己那张清理图 → 接缝处必然出现
  // 「上下两层清理图」，译文也被排了两遍。用户要求「只有一个覆盖层」。
  //
  // 做法（对齐参考项目 crosspage-cover.js 的思路，坐标系换成更省事的一种）：
  // 把「接缝带」——Kotlin 侧从本图那张清理图里裁出来的、正好覆盖整个跨缝气泡的那一条
  // （上端落在本图内、下端落在下一张图内）——当成一张独立的虚拟图，root 就摆在这条虚拟图
  // 的屏幕矩形上。于是：
  //   * 整条带子来自**同一张清理图**，接缝处不可能出现两种纹理；
  //   * 带内坐标就是普通场景的百分比坐标，遮罩/四边形/实底/字号/清理图贴图数学全链路复用；
  //   * 一个 root、一个 cover 节点、一个 text 节点，跨缝两半不再各自渲染。
  // 前提是两张图「同宽同缩放、首尾相接」——否则这条带的像素映射不唯一，直接返回
  // ok:false，调用方保留原来的两半渲染，画面不会变坏。
  // ---------------------------------------------------------------------------

  function findOverlayTarget(imageId) {
    var id = String(imageId || "");
    if (!id) return null;
    return document.querySelector('[data-mt-id="' + id + '"]') || document.getElementById(id);
  }

  function isAncestorOf(ancestor, node) {
    for (var cursor = node; cursor; cursor = cursor.parentElement) {
      if (cursor === ancestor) return true;
    }
    return false;
  }

  /**
   * 接缝带的屏幕矩形 + 合法性判据（纯函数，可单测）。
   *
   * 带在「本图拼接坐标系」里：左上角 (band_left_px, band_top_px)、尺寸 band_width_px ×
   * band_height_px；该坐标系里本图从 top_h_px 开始、位图 source_width_px × source_height_px。
   * 屏幕矩形因此是
   *   scaleY = rect0.height / source_height_px,  scaleX = rect0.width / source_width_px
   *   left   = rect0.left + band_left_px * scaleX
   *   top    = rect0.top + (band_top_px - top_h_px) * scaleY
   *   width  = band_width_px * scaleX
   *
   * ⚠️ 左边距不能省：带是拼接图上**横向裁出来的一小块**（宽度往往远小于图宽），
   * 漏掉 band_left_px 就会把带当成「从图左边开始」，整块盖板与译文横向平移一个左边距
   * （2026-09-30 实测：496px 宽的带在 760px 宽的图上偏出 114px，盖板左边缘糊到气泡外面，
   * 气泡右侧的原文没被盖住）。
   *
   * 合法性判据全部来自「宿主拼接本身已经依赖的前提」，任何一条不成立都直接拒绝，
   * 让调用方保留原来的两半渲染（画面不会变坏）：
   * 1. 两张图**显示宽度相同**：拼接用的上下条带是按本图宽度重采样出来的
   *    （Kotlin `cropStrip`），只有显示宽度一致，带上的像素才能同时对齐两张图；
   * 2. 两张图**首尾相接**（间隙/重叠 ≤1.5px）：一条带会连页面间隙一起盖住；
   * 3. 左右对齐：x 轴偏移会让带整体错位。
   */
  function resolveSeamBand(payload, rect0, rect1) {
    var sourceWidth = Number(payload && payload.source_width_px) || 0;
    var sourceHeight = Number(payload && payload.source_height_px) || 0;
    var bandHeight = Number(payload && payload.band_height_px) || 0;
    var bandWidth = Number(payload && payload.band_width_px) || 0;
    var bandLeft = Number(payload && payload.band_left_px) || 0;
    var bandTop = Number(payload && payload.band_top_px) || 0;
    var topH = Number(payload && payload.top_h_px) || 0;
    if (!(sourceWidth > 0) || !(sourceHeight > 0) || !(bandHeight > 0) || !(bandWidth > 0)) {
      return { ok: false, reason: "bad_band_metrics" };
    }
    if (!(rect0.width > 2) || !(rect0.height > 2) || !(rect1.width > 2) || !(rect1.height > 2)) {
      return { ok: false, reason: "target_not_laid_out" };
    }
    if (Math.abs(rect1.width - rect0.width) / rect0.width > 0.02) {
      // 显示宽度不一致：拼接条带的重采样比例与下一张图的实际显示比例不再相消。
      return { ok: false, reason: "width_mismatch" };
    }
    var gap = rect1.top - rect0.bottom;
    // 2026-10-03：容忍放宽到 3px。1.5px 太紧 —— 阅读器给两张切片留 2px 边距就会被判
    // `page_gap`，于是接缝层整块被拒、退回「两半各画一份」（用户现场看到的重影/糊字）。
    // 小间隙由接缝带自己的纹理盖住，比退回两半更好。
    if (gap > SEAM_MAX_PAGE_GAP_PX || gap < -SEAM_MAX_PAGE_GAP_PX) {
      // reason 保持稳定字符串（宿主日志/单测都按它判断），实测间隙另放一个字段，
      // 日志里能分清「差 2px」和「差 200px」。
      return { ok: false, reason: "page_gap", gap_px: gap };
    }
    if (Math.abs(rect1.left - rect0.left) > 1.5) {
      return { ok: false, reason: "page_offset_x" };
    }
    var scaleX = rect0.width / sourceWidth;
    var scaleY = rect0.height / sourceHeight;
    return {
      ok: true,
      scaleX: scaleX,
      scaleY: scaleY,
      left: rect0.left + bandLeft * scaleX,
      top: rect0.top + (bandTop - topH) * scaleY,
      width: bandWidth * scaleX,
      height: bandHeight * scaleY
    };
  }

  function dropSeam(seamKey, restoreHalves) {
    var entry = seamsByKey.get(seamKey);
    if (restoreHalves !== false) {
      // 接缝没了 → 两半必须回来，否则这块台词原文和译文都没有。
      var restored = restoreSeamHalves(seamKey);
      if (restored > 0) {
        console.log("MTSEAM restore seam=" + seamKey + " reason=drop nodes=" + restored);
      }
    }
    if (!entry) return false;
    if (entry.root && entry.root.isConnected) entry.root.remove();
    seamsByKey.delete(seamKey);
    stopFrameSyncIfIdle();
    return true;
  }

  /**
   * 接缝层失效时的统一出口（2026-10-03）：藏起来 + 把被它顶掉的两半贴回去。
   *
   * 幂等：同一 seam 的同一原因只贴一次（逐帧同步会反复走到这里）。
   */
  function invalidateSeam(entry, reason) {
    if (!entry || !entry.root) return;
    if (entry.root.isConnected) entry.root.style.display = "none";
    if (entry.reason === reason) return;
    entry.reason = reason;
    var restored = restoreSeamHalves(entry.seamKey);
    if (restored > 0) {
      // 走 console 是为了能在 logcat 里看到（WebView 的 console 会进 chromium CONSOLE 行）。
      console.log("MTSEAM restore seam=" + entry.seamKey + " reason=" + reason + " nodes=" + restored);
    }
  }

  /** 接缝覆盖层的位置同步：结构与 [syncOverlayPosition] 相同，只把「目标矩形」换成带矩形。 */
  function syncSeamPosition(entry) {
    if (!entry || !entry.root) return;
    if (!entry.root.isConnected) {
      seamsByKey.delete(entry.seamKey);
      stopFrameSyncIfIdle();
      return;
    }
    if (!entry.t0 || !entry.t0.isConnected || !entry.t1 || !entry.t1.isConnected) {
      invalidateSeam(entry, "target_detached");
      return;
    }
    // 两张图之一被阅读器回收换成别的页：这条缝（以及它顶掉的两半）都属于旧内容，
    // 直接丢掉、**不要**把两半贴回去（贴回去就是把上一页的译文画到新画面上）。
    var t0Now = entry.t0Src ? targetImageSrc(entry.t0) : "";
    var t1Now = entry.t1Src ? targetImageSrc(entry.t1) : "";
    if ((t0Now && entry.t0Src && t0Now !== entry.t0Src) ||
      (t1Now && entry.t1Src && t1Now !== entry.t1Src)) {
      console.log("MTSTALE drop seam=" + entry.seamKey + " reason=target_src_changed");
      discardSeamHalves(entry.seamKey);
      dropSeam(entry.seamKey, false);
      return;
    }
    var layer = entry.layer && entry.layer.isConnected ? entry.layer : ensureLayerOnHost(entry.t0);
    if (!layer || !layer.parentElement) {
      invalidateSeam(entry, "no_layer");
      return;
    }
    entry.layer = layer;
    if (entry.root.parentElement !== layer) layer.appendChild(entry.root);
    if (!isAncestorOf(layer.parentElement, entry.t1)) {
      // 两张图不在同一个覆盖层宿主里：一条带跨不过去。
      invalidateSeam(entry, "host_mismatch");
      return;
    }
    var band = resolveSeamBand(entry.payload, entry.t0.getBoundingClientRect(), entry.t1.getBoundingClientRect());
    if (!band.ok) {
      invalidateSeam(entry, band.reason);
      return;
    }
    entry.reason = "";
    entry.root.style.display = "block";
    var layerRect = layer.getBoundingClientRect();
    var nextRect = {
      left: Math.round(band.left - layerRect.left),
      top: Math.round(band.top - layerRect.top),
      width: Math.round(band.width),
      height: Math.round(band.height)
    };
    var last = entry.lastRect;
    var positionChanged = !last || last.left !== nextRect.left || last.top !== nextRect.top;
    var sizeChanged = !last || last.width !== nextRect.width || last.height !== nextRect.height;
    if (positionChanged || sizeChanged) {
      entry.root.style.left = nextRect.left + "px";
      entry.root.style.top = nextRect.top + "px";
      entry.root.style.width = nextRect.width + "px";
      entry.root.style.height = nextRect.height + "px";
      entry.lastRect = nextRect;
      // 百分比 → 像素的倾角换算依赖 root 尺寸，尺寸变了才重算（滚动期零写入）。
      applyAspectCorrectedTilt(entry.textNodes, nextRect.width, nextRect.height);
    }
    var pendingFontFit = entry.fontPending === true || entry.textNodes.some(function (node) {
      return node.isConnected && !node.style.fontSize;
    });
    if (!sizeChanged && !pendingFontFit) return;
    entry.textNodes.forEach(function (node) {
      if (!node.isConnected) return;
      var wPercent = Number(node.dataset.wPercent) || 0;
      var hPercent = Number(node.dataset.hPercent) || 0;
      var bubbleWidthPx = nextRect.width * wPercent / 100;
      var bubbleHeightPx = nextRect.height * hPercent / 100;
      if (bubbleWidthPx < 2 || bubbleHeightPx < 2) {
        node.dataset.mtFontPending = "1";
        entry.fontPending = true;
        return;
      }
      node.dataset.mtFontPending = "";
      entry.fontPending = false;
      var fitted = fitBubbleFontSize(node, bubbleWidthPx, bubbleHeightPx, nextRect.height);
      node.style.fontSize = fitted.toFixed(1) + "px";
      applyStrokeWidth(node, fitted);
    });
  }

  /**
   * 接缝场景下发：一条图缝只建一个 root，里面只放这一条缝上所有跨缝气泡的
   * 「一块盖板 + 一份译文」（每个气泡仍各一个 cover/一个 text，但不再是两个页面各一套）。
   *
   * payload：
   *   seam_key / image_id / next_image_id / cleaned_url（接缝带清理图）
   *   band_left_px / band_top_px / band_width_px / band_height_px / top_h_px /
   *   source_width_px / source_height_px / bubbles[]（百分比坐标以**带**为坐标系，允许溢出）
   */
  function applySeamScene(payload) {
    injectCss();
    var seamKey = String(payload && payload.seam_key || "");
    var t0 = findOverlayTarget(payload && payload.image_id);
    var t1 = findOverlayTarget(payload && payload.next_image_id);
    // 拒绝任何一条几何前提时都要顺手扔掉这条缝的旧 root：留着它就会和「两半渲染」
    // 同时出现在屏幕上（三个东西），而调用方并不知道我们拒绝了。
    if (!seamKey || !t0 || !t1) {
      dropSeam(seamKey);
      return JSON.stringify({ ok: false, reason: "no_target" });
    }
    var host = resolveOverlayHost(t0);
    if (!isAncestorOf(host, t1)) {
      dropSeam(seamKey);
      return JSON.stringify({ ok: false, reason: "host_mismatch" });
    }
    var band = resolveSeamBand(payload, t0.getBoundingClientRect(), t1.getBoundingClientRect());
    if (!band.ok) {
      dropSeam(seamKey);
      return JSON.stringify({ ok: false, reason: band.reason });
    }

    var bubbles = Array.isArray(payload.bubbles) ? payload.bubbles : [];
    var signature = djb2Hash(JSON.stringify([payload.cleaned_url || "", bubbles]));
    var existing = seamsByKey.get(seamKey);
    if (existing && existing.signature === signature && existing.root.isConnected) {
      // 同一场景（「原文 → 翻译」贴回）：只同步位置，保留节点身份（重建就是肉眼可见的闪烁）。
      existing.payload = payload;
      existing.t0 = t0;
      existing.t1 = t1;
      syncSeamPosition(existing);
      startFrameSync();
      return JSON.stringify({ ok: true, rebuilt: false, bubbles: bubbles.length, mode: "seam" });
    }
    if (coverNodesEmpty(bubbles)) {
      if (existing) dropSeam(seamKey);
      return JSON.stringify({ ok: true, rebuilt: true, bubbles: 0, mode: "seam" });
    }

    var scene = createRenderScene(seamKey, { bubbles: bubbles, cleaned_url: payload.cleaned_url || null });
    var coverNodes = [];
    var textNodes = [];
    scene.layers.forEach(function (layer) {
      if (!layer.active) return;
      var node = layer.content && layer.content.node;
      if (!node) return;
      node.dataset.mtLayer = layer.id;
      // 层 id 落到 DOM 上（约定与普通场景一致）：接缝上的气泡也可以按 id 摘除。
      node.dataset.mtSeamKey = seamKey;
      if (layer.type === LAYER_TYPES.COVER) coverNodes.push(node);
      if (layer.type === LAYER_TYPES.TEXT) textNodes.push(node);
    });
    if (coverNodes.length === 0 && textNodes.length === 0) {
      if (existing) dropSeam(seamKey);
      return JSON.stringify({ ok: true, rebuilt: true, bubbles: 0, mode: "seam" });
    }
    logTextOverlap(textNodes, "seam=" + seamKey);

    var layer = ensureLayerOnHost(t0);
    var root = document.createElement("div");
    root.className = "mt-overlay-root __mt_scene_root __mt_seam_root";
    root.dataset.mtOverlay = "true";
    root.dataset.seamKey = seamKey;
    var cleanedValue = payload.cleaned_url ? maskCssValue(payload.cleaned_url) : "";
    if (cleanedValue) root.style.setProperty("--mt-cleaned-image", cleanedValue);
    // 所有背景先绘制、所有译文后绘制（与普通场景同序）。
    coverNodes.forEach(function (node) { root.appendChild(node); });
    textNodes.forEach(function (node) {
      var delay = Math.min(textNodes.indexOf(node) * 34, 320);
      node.classList.add("mt-stream-enter");
      node.style.setProperty("--mt-stream-delay", delay + "ms");
      root.appendChild(node);
    });

    var entry = {
      seamKey: seamKey,
      payload: payload,
      t0: t0,
      t1: t1,
      // 绑定时两张图的内容地址：虚拟化阅读器换图后这条缝就不属于当前画面了（见 syncSeamPosition）。
      t0Src: targetImageSrc(t0),
      t1Src: targetImageSrc(t1),
      root: root,
      layer: layer,
      cleanedUrl: String(payload.cleaned_url || ""),
      textNodes: textNodes,
      signature: signature,
      reason: "",
      lastRect: null,
      fontPending: true
    };
    if (existing && existing.root.isConnected) existing.root.replaceWith(root);
    layer.appendChild(root);
    seamsByKey.set(seamKey, entry);
    syncSeamPosition(entry);
    startFrameSync();
    textNodes.forEach(function (node, index) {
      window.setTimeout(function () {
        if (node.isConnected) {
          node.classList.remove("mt-stream-enter");
          node.style.removeProperty("--mt-stream-delay");
        }
      }, Math.min(index * 34, 320) + 420);
    });
    return JSON.stringify({
      ok: true,
      rebuilt: true,
      bubbles: textNodes.length,
      covers: coverNodes.length,
      mode: "seam"
    });
  }

  function coverNodesEmpty(bubbles) {
    return !Array.isArray(bubbles) || bubbles.length === 0;
  }

  function applySeamSceneJson(jsonText) {
    try {
      var payload = typeof jsonText === "string" ? JSON.parse(jsonText) : jsonText;
      return applySeamScene(payload);
    } catch (err) {
      return JSON.stringify({ ok: false, reason: "exception:" + String(err && err.message || err).slice(0, 80) });
    }
  }

  /** 某条缝当前是否真的在渲染（诊断用：Kotlin 侧据此决定要不要抑制两半）。 */
  function seamSceneState(seamKey) {
    var entry = seamsByKey.get(String(seamKey || ""));
    if (!entry) return "absent";
    if (!entry.root || !entry.root.isConnected) return "detached";
    if (entry.root.style.display === "none") return entry.reason || "hidden";
    return "visible";
  }

  /**
   * 把某张图（imageId）的场景层切成**纯展示**：整棵 root 禁用指针事件，触摸穿透到页面。
   *
   * 用途：截图翻译的译文是盖在**仍可交互的网页**上的，气泡自带的 click（点一下看原文）
   * 会吃掉触摸/滚动（用户 2026-10-14 报「影响应用交互」）。活翻译链路不调用它。
   */
  function noTouch(imageId) {
    var root = document.querySelector('.__mt_scene_root[data-image-id="' + String(imageId || "") + '"]');
    if (!root || !root.classList) return 0;
    root.classList.add("__mt_no_touch");
    return 1;
  }

  window.MtOverlay = Object.freeze({
    noTouch: noTouch,
    applyScene: applyScene,
    applySceneJson: applySceneJson,
    // 接缝覆盖层：一条图缝一个 root，一块盖板 + 一份译文（跨缝不再各画半块）。
    applySeamScene: applySeamScene,
    applySeamSceneJson: applySeamSceneJson,
    seamSceneState: seamSceneState,
    removeSeamScene: dropSeam,
    removeScenes: removeScenes,
    // loading 框（2026-10-02）：正在处理的图上方浮一个胶囊，文案由 Kotlin 按阶段下发。
    showLoadingJson: showLoadingJson,
    hideLoading: hideLoading,
    clearLoadings: clearLoadings,
    // 清理图晚绑定（§5）：页面级异步产物落地时直接写已存在的 DOM，不重渲染。
    applyCleanedImage: applyCleanedImage,
    applyCleanedImages: applyCleanedImages,
    pendingCleanedImageIds: pendingCleanedImageIds,
    // §11 视觉去重：按气泡摘掉已渲染的遮罩+译文，不重发场景。
    suppressBubble: suppressBubble,
    restoreSeamHalves: restoreSeamHalves,
    // §11 跨图 union：相邻分片回来时把某气泡的遮罩换成跨图完整形状。
    updateBubbleCover: updateBubbleCover,
    // 诊断出口：验收脚本可直接读。
    _overlays: overlaysById,
    _seams: seamsByKey,
    _loadings: loadingById,
    _suppressedHalves: function () { return suppressedHalves; },
    _resolveSeamBand: resolveSeamBand,
    _fitBubbleFontSize: fitBubbleFontSize,
    _resolveFontSizeRange: resolveFontSizeRange,
    _logTextOverlap: logTextOverlap,
    _resolveCoverQuad: resolveCoverQuad,
    _resolveCoverSegments: resolveCoverSegments,
    _createCoverLayers: createCoverLayers,
    _formatTranslationForOriginalLines: formatTranslationForOriginalLines,
    // 像素感知均衡断行（2026-10-14）：纯函数入口，供验收测试表驱动。
    _breakBalancedLines: breakBalancedLines,
    _formatBalancedLines: formatBalancedLines,
    _segmentTextForBreaking: segmentTextForBreaking,
    _countGreedyLines: countGreedyLines,
    _rebalanceTextNode: rebalanceTextNode,
    _polygonRotationDeg: polygonRotationDeg,
    _sanitizeRenderRotationDeg: sanitizeRenderRotationDeg,
    _shouldUseVerticalLayout: shouldUseVerticalLayout,
    _getCleanedPatchStyle: getCleanedPatchStyle,
    _clipConvexPolygonToRect: clipConvexPolygonToRect,
    _polygonClipPathPercent: polygonClipPathPercent,
    _maskCssValue: maskCssValue,
    _dynamicStrokeWidth: dynamicStrokeWidth,
    _buildSolidBackgroundBox: buildSolidBackgroundBox,
    _dataUrlToObjectUrl: dataUrlToObjectUrl
  });
})();
