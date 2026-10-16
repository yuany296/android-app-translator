"use strict";
/**
 * 译文渲染层验收（§13 断言的可执行版本，2026-09-29）。
 *
 * 运行：`node app/src/test/js/mt-overlay-acceptance.test.js`
 * 覆盖：§0 层级顺序 / §4 遮罩几何（含倾斜）/ §5 清理图贴图与 blob 出口 / §6 描边 /
 *       §7 字号区间与撑开 / §8 竖排判定 / §9 宿主样式隔离 / §10 稳定性（幂等、不重测、
 *       点击切换、坏气泡不拖垮）/ §11 凸多边形裁剪。
 *
 * 不覆盖（需要真机/浏览器）：真实文字测量的二分结果、滚动合成线程行为、跨页视觉去重
 * （在 Kotlin `SeamBubbleMerger`，由 `SeamBubbleMergerTest` 覆盖）。
 */

const fs = require("fs");
const path = require("path");
const vm = require("vm");
const { install } = require("./dom-stub");

const RENDERER = path.join(__dirname, "..", "..", "main", "assets", "mt-overlay-renderer.js");

let passed = 0;
const failures = [];

function check(name, condition, extra) {
  if (condition) {
    passed += 1;
    return true;
  }
  failures.push(name + (extra === undefined ? "" : " :: " + safeJson(extra)));
  return false;
}

function safeJson(value) {
  try { return JSON.stringify(value); } catch (err) { return String(value); }
}

function near(actual, expected, tolerance = 0.01) {
  return Math.abs(Number(actual) - Number(expected)) <= tolerance;
}

/** 每段用一张干净页面 + 干净渲染器实例，避免用例之间互相污染。 */
function freshPage(options = {}) {
  const { document, window } = install();
  const source = fs.readFileSync(RENDERER, "utf8");
  vm.runInThisContext(source, { filename: "mt-overlay-renderer.js" });
  const Mt = window.MtOverlay;
  if (!Mt) throw new Error("MtOverlay not registered");

  const hostRect = options.hostRect || { left: 0, top: 0, width: 400, height: 800 };
  const host = document.createElement("div");
  host._setRect(hostRect);
  host._setComputed(options.hostOverflow || { overflowY: "auto", overflowX: "auto" });
  host._scrollHeight = 2000;
  host._clientHeight = hostRect.height;
  host._scrollWidth = hostRect.width;
  host._clientWidth = hostRect.width;
  document.body.appendChild(host);

  const image = document.createElement("img");
  image.setAttribute("data-mt-id", "mt1");
  image._setRect(options.imageRect || { left: 0, top: 0, width: 400, height: 1000 });
  host.appendChild(image);

  return { document, window, Mt, host, image };
}

function bubble(overrides = {}) {
  return Object.assign({
    id: "b0",
    x: 20, y: 20, w: 30, h: 10,
    original_text: "原文",
    translated_text: "译文",
    source_line_count: 1,
    vertical: false,
    alignment: "center",
    bg_type: "solid",
    bg_color: "#ffeedd",
    text_color: "#111827",
    stroke_color: "#ffffff",
    font_height_percent: 6,
    polygon: null,
    rotation_deg: null,
    fill_box: null,
    region_polygon: null,
    cleaned_source_box: null,
    font_weight: null,
    stitch_overflow: false,
    fixed_font_percent: null,
    patch_url: null,
    patch_box: null
  }, overrides);
}

function scene(page, bubbles, extra = {}) {
  return JSON.stringify(Object.assign({
    v: 1,
    image_id: "mt1",
    cleaned_url: null,
    bubbles: bubbles
  }, extra));
}

function apply(page, payload) {
  return JSON.parse(page.Mt.applySceneJson(payload));
}

function coverBubbleOf(root) {
  const clip = root.querySelector(".mt-cover-clip");
  return clip ? clip.querySelector(".mt-cover-only") : null;
}

// 斜排四边形：percent 空间里斜率 0.3（等价于像素空间里的另一个角度）。
// 关键点：像素角度与百分比角度本来就不同，渲染层绝不能把两者比对成「冲突」。
const TILTED_POLYGON = [
  { x: 10, y: 41 }, { x: 50, y: 53 }, { x: 50, y: 59 }, { x: 10, y: 47 }
];

// ---------------------------------------------------------------------------
// §0 场景结构：cover 层全部先挂载，text 层全部后挂载
// ---------------------------------------------------------------------------
(function structureAndLayerOrder() {
  const page = freshPage();
  const result = apply(page, scene(page, [
    bubble({ id: "a", x: 5, y: 5, w: 20, h: 10 }),
    bubble({ id: "b", x: 40, y: 40, w: 20, h: 10 })
  ]));
  check("scene result ok", result.ok === true, result);
  check("scene reports covers/texts", result.covers === 2 && result.bubbles === 2, result);

  const root = page.document.querySelector(".__mt_scene_root");
  check("root mounted on scroll host (not body)",
    root.parentElement.className.includes("mt-overlay-layer") &&
    root.parentElement.parentElement === page.host, root.parentElement.className);
  const kinds = root.children.map((node) =>
    node.className.includes("mt-cover-clip") ? "cover" :
      node.className.includes("mt-text-layer") ? "text" : "other");
  check("all covers precede all texts",
    kinds.join(",") === "cover,cover,text,text", kinds);
})();

// ---------------------------------------------------------------------------
// §10 稳定性：相同场景签名只同步位置，绝不重建节点
// ---------------------------------------------------------------------------
(function signatureIdempotence() {
  const page = freshPage();
  const payload = scene(page, [bubble({})]);
  const first = apply(page, payload);
  const root = page.document.querySelector(".__mt_scene_root");
  const second = apply(page, payload);
  const rootAgain = page.document.querySelector(".__mt_scene_root");
  check("first apply rebuilds", first.ok === true && first.rebuilt === true, first);
  check("identical scene does not rebuild", second.ok === true && second.rebuilt === false, second);
  check("identical scene keeps node identity", root === rootAgain);
})();

// ---------------------------------------------------------------------------
// §4 / §13.1 斜排：遮罩跟倾角走，且不撑成包围盒
// ---------------------------------------------------------------------------
(function tiltedCover() {
  const page = freshPage();
  apply(page, scene(page, [bubble({ polygon: TILTED_POLYGON, bg_type: "none" })]));
  const root = page.document.querySelector(".__mt_scene_root");
  const clip = root.querySelector(".mt-cover-clip");
  const text = root.querySelector(".mt-text-layer");
  const expectedRotation = page.Mt._polygonRotationDeg(TILTED_POLYGON);

  check("polygon tilt is detected", Math.abs(expectedRotation) > 0.5, expectedRotation);
  // 渲染层必须把「百分比空间算出来的倾角」换算成**屏幕角度**再 rotate。
  // 测试图是 400x1000（非正方形），两个空间的角度并不相等；不做换算就会出现
  // 「遮罩按 20° 裁、文字只转 13°」→ 文字从遮罩里斜着露出来（用户实测反馈的「没完全覆盖 + 角度不对」）。
  const expectedScreen = Math.atan2(12 / 100 * 1000, 40 / 100 * 400) * 180 / Math.PI;
  const textTransform = text.style.getPropertyValue("--mt-base-transform");
  check("text layer rotates by the aspect-corrected screen angle",
    textTransform.includes("rotate(" + expectedScreen.toFixed(2) + "deg)"),
    { textTransform, expectedScreen });
  check("aspect correction is not a no-op on a non-square image",
    Math.abs(expectedScreen - expectedRotation) > 5,
    { percentRotation: expectedRotation, screenRotation: expectedScreen });
  check("cover clip uses a polygon clip-path (not inset)",
    clip.style.clipPath.startsWith("polygon("), clip.style.clipPath);
  // 2026-09-30 起遮罩外扩**不在渲染层做**（COVER_PADDING_DEFAULT = 0）：用户要求
  // 「盖板不扩展，最多几个像素」，于是外扩改成宿主按像素算好写进 polygon/cover_box。
  // 有了 cover_box 时盖板必须用它（= 字形范围），不能用 x/y/w/h（= 排版框，每侧还扩 15%）。
  check("cover clip bounds == polygon bounds (no render-side padding)",
    near(parseFloat(clip.style.width), 40, 0.6) &&
    near(parseFloat(clip.style.height), 18, 0.6),
    { w: clip.style.width, h: clip.style.height });

  // 关键反模式：rotation_deg 与百分比 polygon 的角度不同 → 绝不能判定成「冲突」而退回旋转外接框。
  const page2 = freshPage();
  apply(page2, scene(page2, [bubble({ polygon: TILTED_POLYGON, rotation_deg: 35, bg_type: "none" })]));
  const clip2 = page2.document.querySelector(".__mt_scene_root").querySelector(".mt-cover-clip");
  check("rotation_deg + tilted polygon keeps polygon bounds",
    near(parseFloat(clip2.style.width), 40, 0.6) &&
    near(parseFloat(clip2.style.height), 18, 0.6),
    { w: clip2.style.width, h: clip2.style.height });

  // 盖板框：宿主给了 cover_box 就必须用它 —— 这是「盖板比文字大 30%」的回归闸门。
  const page4 = freshPage();
  apply(page4, scene(page4, [bubble({
    x: 10, y: 10, w: 80, h: 20,
    cover_box: { x: 20, y: 12, w: 30, h: 10 },
    bg_type: "solid", bg_color: "#ffffff"
  })]));
  const root4 = page4.document.querySelector(".__mt_scene_root");
  const clip4 = root4.querySelector(".mt-cover-clip");
  check("cover box follows cover_box instead of the (grown) text box",
    near(parseFloat(clip4.style.width), 30, 0.6) &&
    near(parseFloat(clip4.style.height), 10, 0.6),
    { w: clip4.style.width, h: clip4.style.height });

  // 只有 rotation_deg、没有 polygon：按倾角旋转蓝框（遮罩不是水平矩形）。
  const page3 = freshPage();
  apply(page3, scene(page3, [bubble({ rotation_deg: 30, x: 40, y: 40, w: 20, h: 4, bg_type: "none" })]));
  const clip3 = page3.document.querySelector(".__mt_scene_root").querySelector(".mt-cover-clip");
  check("rotation-only bubble rotates its cover box",
    parseFloat(clip3.style.height) > 4 && clip3.style.clipPath.startsWith("polygon("),
    { h: clip3.style.height, clip: clip3.style.clipPath });
})();

// ---------------------------------------------------------------------------
// §17 遮罩倾角的两条硬门槛（移植参考项目 cover-geometry.js，2026-10-14）
//
// 用户现场（2026-10-14，Lezhin 内嵌浏览器）：气泡原文透过译文可见、盖板时有时无、
// 有时只盖住一条 —— 根因是 rotation_deg ≈ ±90（检测器对高而窄的框一律给 ±90°）
// 被当成「斜排」，外接框被旋转 90°，盖板变成横条；亚 1° 的识别噪声同理。
// ---------------------------------------------------------------------------
(function coverRotationSemantics() {
  // (a) |倾角| ≥ 60° 是**竖排方向**，不是「歪了」：必须按四边形/原框走，不能旋转外接框。
  const page = freshPage();
  const column = [{ x: 45, y: 10 }, { x: 55, y: 10 }, { x: 55, y: 70 }, { x: 45, y: 70 }];
  apply(page, scene(page, [bubble({
    polygon: column, rotation_deg: 89.97, x: 45, y: 10, w: 10, h: 60, bg_type: "none"
  })]));
  const clip = page.document.querySelector(".__mt_scene_root").querySelector(".mt-cover-clip");
  check("vertical rotation keeps the column cover (not a horizontal band)",
    parseFloat(clip.style.height) > parseFloat(clip.style.width),
    { w: clip.style.width, h: clip.style.height, clip: clip.style.clipPath });

  // 没有四边形时同理：框本身 = 那一列，旋转它只会把盖板转成横条。
  const page2 = freshPage();
  apply(page2, scene(page2, [bubble({ rotation_deg: 89.97, x: 45, y: 10, w: 10, h: 60, bg_type: "none" })]));
  const clip2 = page2.document.querySelector(".__mt_scene_root").querySelector(".mt-cover-clip");
  check("vertical rotation without a polygon is not rotated either",
    parseFloat(clip2.style.height) > parseFloat(clip2.style.width) &&
    clip2.style.transform.indexOf("rotate(") === -1,
    { w: clip2.style.width, h: clip2.style.height, transform: clip2.style.transform });

  // (b) 亚 COVER_ROTATION_NOISE_DEG = 识别噪声：不旋转、不裁剪（转歪只会露出原文边角）。
  const page3 = freshPage();
  apply(page3, scene(page3, [bubble({ rotation_deg: -0.6, x: 10, y: 10, w: 40, h: 10, bg_type: "none" })]));
  const clip3 = page3.document.querySelector(".__mt_scene_root").querySelector(".mt-cover-clip");
  check("sub-noise rotation keeps the axis-aligned cover box",
    near(parseFloat(clip3.style.width), 40, 0.01) && near(parseFloat(clip3.style.height), 10, 0.01) &&
    clip3.style.transform.indexOf("rotate(") === -1,
    { w: clip3.style.width, h: clip3.style.height, transform: clip3.style.transform });

  // (c) 真斜排（2°..60°）行为不变：形状由 clip-path 表达，节点本身不再旋转（否则转两次）。
  const page4 = freshPage();
  apply(page4, scene(page4, [bubble({ rotation_deg: 12, x: 40, y: 40, w: 20, h: 4, bg_type: "none" })]));
  const clip4 = page4.document.querySelector(".__mt_scene_root").querySelector(".mt-cover-clip");
  check("real tilt still tilts the cover (shape via clip-path, no double rotation)",
    parseFloat(clip4.style.height) > 4 && clip4.style.clipPath.startsWith("polygon(") &&
    clip4.style.transform.indexOf("rotate(") === -1,
    { h: clip4.style.height, clip: clip4.style.clipPath, transform: clip4.style.transform });
})();

// ---------------------------------------------------------------------------
// §4 / §13.2 实底：填充色 = 采样底色，范围 = 文字框 ∪ fill_box，按 region_polygon 裁剪
// ---------------------------------------------------------------------------
(function solidFill() {
  const page = freshPage();
  apply(page, scene(page, [bubble({
    x: 20, y: 20, w: 30, h: 10,
    fill_box: { x: 10, y: 12, w: 60, h: 20 },
    region_polygon: [{ x: 12, y: 14 }, { x: 66, y: 14 }, { x: 66, y: 30 }, { x: 12, y: 30 }],
    bg_color: "#ffeedd"
  })]));
  const cover = coverBubbleOf(page.document.querySelector(".__mt_scene_root"));

  check("solid cover uses sampled bg color",
    cover.style.getPropertyValue("--mt-adaptive-bg") === "#ffeedd",
    cover.style.getPropertyValue("--mt-adaptive-bg"));
  check("fill left/top/width/height == union(text box, fill_box)",
    near(parseFloat(cover.style.getPropertyValue("--mt-fill-left")), -33.33, 0.05) &&
    near(parseFloat(cover.style.getPropertyValue("--mt-fill-top")), -80, 0.05) &&
    near(parseFloat(cover.style.getPropertyValue("--mt-fill-width")), 200, 0.05) &&
    near(parseFloat(cover.style.getPropertyValue("--mt-fill-height")), 200, 0.05),
    {
      left: cover.style.getPropertyValue("--mt-fill-left"),
      top: cover.style.getPropertyValue("--mt-fill-top"),
      width: cover.style.getPropertyValue("--mt-fill-width"),
      height: cover.style.getPropertyValue("--mt-fill-height")
    });
  check("fill is clipped by region_polygon",
    cover.style.getPropertyValue("--mt-region-clip").startsWith("polygon("),
    cover.style.getPropertyValue("--mt-region-clip"));

  // 斜排实底：填充挪进裁剪层（世界坐标），节点自己的 ::before 必须关掉。
  const page2 = freshPage();
  apply(page2, scene(page2, [bubble({
    polygon: TILTED_POLYGON, fill_box: { x: 8, y: 38, w: 46, h: 24 }, bg_color: "#223344"
  })]));
  const root2 = page2.document.querySelector(".__mt_scene_root");
  const cover2 = coverBubbleOf(root2);
  const flat = root2.querySelector(".mt-fill-flat");
  check("tilted solid paints fill in the cover clip layer",
    !!flat && cover2.className.includes("mt-fill-outside"),
    { flat: !!flat, cls: cover2.className });
  check("flat fill uses the sampled color",
    !!flat && flat.style.background === "#223344", flat && flat.style.background);
})();

// ---------------------------------------------------------------------------
// §5 / §13.3 复杂背景：没有清理图就保持透明，绝不画纯色补丁
// ---------------------------------------------------------------------------
(function complexBackgroundTransparent() {
  const page = freshPage();
  apply(page, scene(page, [bubble({ bg_type: "none", bg_color: "#ffffff" })]));
  const cover = coverBubbleOf(page.document.querySelector(".__mt_scene_root"));
  check("bg-none cover stays transparent (no adaptive bg)",
    cover.style.getPropertyValue("--mt-adaptive-bg") === "",
    cover.style.getPropertyValue("--mt-adaptive-bg"));
  check("bg-none cover has no cleaned image until it lands",
    cover.style.getPropertyValue("--mt-cleaned-image") === "" &&
    page.document.querySelector(".__mt_scene_root").style.getPropertyValue("--mt-cleaned-image") === "");
  check("bg-none cover still maps the patch math (transparent, not white)",
    cover.style.getPropertyValue("--mt-cleaned-size-x") !== "" &&
    cover.className.includes("mt-bg-none"), cover.className);
})();

// ---------------------------------------------------------------------------
// §5 贴图数学 + cleaned_source_box
// ---------------------------------------------------------------------------
(function patchMath() {
  const page = freshPage();
  const style = page.Mt._getCleanedPatchStyle({ x: 25, y: 50, w: 50, h: 25 });
  check("patch size math", style.sizeX === "200%" && style.sizeY === "400%", style);
  check("patch position math", style.positionX === "50%" && near(parseFloat(style.positionY), 66.666, 0.01), style);

  apply(page, scene(page, [bubble({
    bg_type: "none", x: 20, y: 20, w: 30, h: 10,
    cleaned_source_box: { x: 0, y: 0, w: 50, h: 25 }
  })]));
  const cover = coverBubbleOf(page.document.querySelector(".__mt_scene_root"));
  check("cleaned_source_box drives background-size (not the bubble box)",
    cover.style.getPropertyValue("--mt-cleaned-size-x") === "200%" &&
    cover.style.getPropertyValue("--mt-cleaned-size-y") === "400%",
    {
      x: cover.style.getPropertyValue("--mt-cleaned-size-x"),
      y: cover.style.getPropertyValue("--mt-cleaned-size-y")
    });
})();

// ---------------------------------------------------------------------------
// §15 速度优先模式（关闭「精细擦字」）：旁白/透明块用**不透明实底**盖住原文
//
// 用户 2026-10-11 反馈：「现在会有残影。不要这样的，而是用实体的背景遮挡」——
// 关闭开关时宿主不再建整页清理图（scene.cleaned_url = null），也不再用逐气泡贴片
// （贴片是局部边缘填充，笔画边缘必然留残影），而是下发 `flat_cover`：盖板直接铺采样
// 出来的气泡底色，100% 不透明。
// ---------------------------------------------------------------------------
(function flatCoverForFastMode() {
  const page = freshPage();
  apply(page, scene(page, [bubble({
    bg_type: "none",
    bg_color: "#f3e9d2",
    flat_cover: true,
    // 即使同时带了贴片来源，flat_cover 也必须赢（实底优先，不留残影通道）。
    patch_url: "https://mtlocal.overlay/p1.png",
    patch_box: { x: 10, y: 40, w: 50, h: 20 }
  })]));
  const cover = coverBubbleOf(page.document.querySelector(".__mt_scene_root"));
  check("flat cover marks the node", cover.dataset.coverFill === "flat", cover.dataset.coverFill);
  check("flat cover uses the sampled bubble color",
    cover.style.getPropertyValue("--mt-adaptive-bg") === "#f3e9d2",
    cover.style.getPropertyValue("--mt-adaptive-bg"));
  check("flat cover gives up the patch image (solid block wins)",
    cover.style.getPropertyValue("--mt-cleaned-image") === "",
    cover.style.getPropertyValue("--mt-cleaned-image"));
  check("flat cover still keeps the text readable stroke class",
    cover.className.includes("mt-bg-none"), cover.className);
})();

// ---------------------------------------------------------------------------
// §15 跨图续排：贴片盖板仍然可用（patch_url + patch_box 的裁剪层语义）
// ---------------------------------------------------------------------------
(function patchBackedCoverForFastMode() {
  const page = freshPage();
  apply(page, scene(page, [bubble({
    bg_type: "none",
    patch_url: "https://mtlocal.overlay/p1.png",
    patch_box: { x: 10, y: 40, w: 50, h: 20 }
  })]));
  const root = page.document.querySelector(".__mt_scene_root");
  const clip = root.querySelector(".mt-cover-clip");
  const cover = coverBubbleOf(root);
  check("no scene-level cleaned image in fast mode",
    root.style.getPropertyValue("--mt-cleaned-image") === "",
    root.style.getPropertyValue("--mt-cleaned-image"));
  check("patch_box drives the clip layer (center + size)",
    near(parseFloat(clip.style.left), 35) && near(parseFloat(clip.style.top), 50) &&
    near(parseFloat(clip.style.width), 50) && near(parseFloat(clip.style.height), 20),
    { left: clip.style.left, top: clip.style.top, w: clip.style.width, h: clip.style.height });
  check("patch image fills the cover node 100%",
    cover.style.getPropertyValue("--mt-cleaned-image") === 'url("https://mtlocal.overlay/p1.png")' &&
    cover.style.getPropertyValue("--mt-cleaned-size-x") === "100%" &&
    cover.style.getPropertyValue("--mt-cleaned-size-y") === "100%" &&
    cover.style.getPropertyValue("--mt-cleaned-position-x") === "0" &&
    cover.style.getPropertyValue("--mt-cleaned-position-y") === "0",
    {
      image: cover.style.getPropertyValue("--mt-cleaned-image"),
      sizeX: cover.style.getPropertyValue("--mt-cleaned-size-x")
    });
})();

// ---------------------------------------------------------------------------
// §5 / §13.4 Chromium 行内值 ~2MB 静默上限：统一走 blob URL 出口
// ---------------------------------------------------------------------------
(function blobUrlOutlet() {
  const page = freshPage();
  const small = "data:image/png;base64," + "A".repeat(1024);
  check("small dataURL stays inline",
    page.Mt._maskCssValue(small) === 'url("' + small + '")');

  const huge = "data:image/png;base64," + "A".repeat(1600000);
  const first = page.Mt._maskCssValue(huge);
  const second = page.Mt._maskCssValue(huge);
  check("huge dataURL goes through blob URL", first.startsWith('url("blob:'), first.slice(0, 30));
  check("same dataURL always maps to the same URL (idempotent writes)",
    first === second, { first, second });
  check("blob URL stays short", first.length < 80, first.length);

  const http = page.Mt._maskCssValue("https://mtlocal.overlay/c1.jpg");
  check("http short URL passes through", http === 'url("https://mtlocal.overlay/c1.jpg")', http);
})();

// ---------------------------------------------------------------------------
// §5 / §13.5 清理图异步落地：直接绑到已存在的 DOM，不重渲染
// ---------------------------------------------------------------------------
(function lateBindCleanedImage() {
  const page = freshPage();
  apply(page, scene(page, [bubble({ bg_type: "none" })]));
  const root = page.document.querySelector(".__mt_scene_root");
  check("no cleaned image before it lands",
    root.style.getPropertyValue("--mt-cleaned-image") === "");

  const applied = page.Mt.applyCleanedImage("mt1", "https://mtlocal.overlay/c1.jpg");
  check("late bind reports a write", applied === true, applied);
  check("late bind writes onto the live root",
    root.style.getPropertyValue("--mt-cleaned-image") === 'url("https://mtlocal.overlay/c1.jpg")',
    root.style.getPropertyValue("--mt-cleaned-image"));
  check("late bind keeps node identity",
    root === page.document.querySelector(".__mt_scene_root"));
  check("late bind is idempotent",
    page.Mt.applyCleanedImage("mt1", "https://mtlocal.overlay/c1.jpg") === false);

  const pending = page.Mt.applyCleanedImage("mt-unknown", "x");
  check("late bind ignores unknown images", pending === false);

  const batch = JSON.parse(page.Mt.applyCleanedImages(JSON.stringify([
    { image_id: "mt1", url: "https://mtlocal.overlay/c2.jpg" },
    { image_id: "nope", url: "x" }
  ])));
  check("batch late bind applies only known images", batch.ok === true && batch.applied === 1, batch);

  const needed = JSON.parse(page.Mt.pendingCleanedImageIds());
  check("pending cleaned ids come from the render side", Array.isArray(needed), needed);
})();

// ---------------------------------------------------------------------------
// §2 / §13.11 横向被裁切：用 clip-path 收边，不卸载重建
// ---------------------------------------------------------------------------
(function clipInsteadOfRebuild() {
  const page = freshPage({
    hostRect: { left: 0, top: 0, width: 200, height: 800 },
    imageRect: { left: 0, top: 0, width: 400, height: 1000 }
  });
  apply(page, scene(page, [bubble({})]));
  const root = page.document.querySelector(".__mt_scene_root");
  check("horizontal clipping uses clip-path inset",
    root.style.clipPath === "inset(0 200.0px 0 0.0px)", root.style.clipPath);
  const again = apply(page, scene(page, [bubble({})]));
  check("clipped overlay is not rebuilt", again.rebuilt === false && root.isConnected);
})();

// ---------------------------------------------------------------------------
// §10 / §13.9 尺寸未变绝不做文字测量
// ---------------------------------------------------------------------------
(function noMeasurementWhenSizeUnchanged() {
  const page = freshPage();
  apply(page, scene(page, [bubble({ translated_text: "滚动时不重测字号" })]));
  const probe = page.document.querySelector(".mt-measure-probe");
  check("measure probe exists", !!probe);
  probe.style.width = "sentinel";

  const again = apply(page, scene(page, [bubble({ translated_text: "滚动时不重测字号" })]));
  check("same size skips font measurement",
    again.rebuilt === false && probe.style.width === "sentinel", probe.style.width);

  page.image._setRect({ left: 0, top: 0, width: 600, height: 1000 });
  apply(page, scene(page, [bubble({ translated_text: "滚动时不重测字号" })]));
  // 探针按**气泡**尺寸测：气泡宽 30% × 图宽 600 = 180px（不是整图宽）。
  check("size change re-runs font measurement", probe.style.width === "180px", probe.style.width);
})();

// ---------------------------------------------------------------------------
// §7 字号：原文字高可信时不设 52px 硬顶；撑开时实底遮罩同步撑满
// ---------------------------------------------------------------------------
(function fontSizeRules() {
  const page = freshPage();
  const withOriginal = page.Mt._resolveFontSizeRange(200, 70);
  check("start size follows original text height",
    near(withOriginal.startSize, 70, 0.01), withOriginal);
  check("max font is capped by original height × 1.08",
    near(withOriginal.maxFont, 75.6, 0.01), withOriginal);
  check("original-height path is not capped at 52px",
    withOriginal.startSize > 52 && withOriginal.maxFont > 52, withOriginal);

  const withoutOriginal = page.Mt._resolveFontSizeRange(200, 0);
  check("fallback start size = box height × 0.78 capped at 52",
    near(withoutOriginal.startSize, 52, 0.01), withoutOriginal);
  check("absolute max stays 120", withoutOriginal.maxFont <= 120, withoutOriginal);

  // 强制探针溢出 → 走缩字号 + 撑开节点分支
  globalThis.__mtStubProbeScroll = { width: 400, height: 400 };
  apply(page, scene(page, [bubble({ translated_text: "这是一段很长很长很长的译文" })]));
  globalThis.__mtStubProbeScroll = null;
  const root = page.document.querySelector(".__mt_scene_root");
  const text = root.querySelector(".mt-text-layer");
  const cover = coverBubbleOf(root);
  const fontSize = parseFloat(text.style.fontSize);
  check("overflowing text shrinks below the start size", fontSize < 52, fontSize);
  check("overflowing text bottoms out at the 8px absolute floor", fontSize === 8, fontSize);
  check("overflowing text expands its own box", text.style.minWidth !== "", text.style.minWidth);
  check("solid mask expands with the text (§7 实底填充同步撑满)",
    cover.style.minWidth === text.style.minWidth && cover.style.minHeight === text.style.minHeight,
    { text: text.style.minWidth, cover: cover.style.minWidth });
})();

// ---------------------------------------------------------------------------
// §7.1 字号自适应（2026-10-12 用户规则）：装不下先缩字号，缩到装得下就**不撑开**；
// 只有缩到 8px 绝对下限仍装不下才允许撑出原文位置，且必须打 MTFONT 诊断。
// ---------------------------------------------------------------------------
(function fontShrinkInsteadOfExpand() {
  const page = freshPage();
  // 探针在字号 > 20px 时溢出、≤ 20px 时装得下 → 必须收字号装进原框。
  globalThis.__mtStubProbeScroll = (probe) => {
    const size = parseFloat(probe.style.fontSize) || 0;
    return size > 20 ? { width: 9999, height: 9999 } : null;
  };
  apply(page, scene(page, [bubble({ translated_text: "四行以内的普通台词" })]));
  globalThis.__mtStubProbeScroll = null;
  const root = page.document.querySelector(".__mt_scene_root");
  const text = root.querySelector(".mt-text-layer");
  const size = parseFloat(text.style.fontSize);
  check("shrink-to-fit picks a size that fits the original box",
    size > 0 && size <= 20.1, size);
  check("shrink-to-fit must not expand the node",
    text.style.minWidth === "" && text.style.minHeight === "",
    { minW: text.style.minWidth, minH: text.style.minHeight });

  // 缩到绝对下限仍装不下：允许撑开，但必须留下 MTFONT 诊断日志。
  const logs = [];
  const originalLog = console.log;
  console.log = (msg) => logs.push(String(msg));
  try {
    globalThis.__mtStubProbeScroll = { width: 400, height: 400 };
    apply(page, scene(page, [bubble({ translated_text: "装不下的超长译文" })]));
  } finally {
    globalThis.__mtStubProbeScroll = null;
    console.log = originalLog;
  }
  const fontLines = logs.filter((line) => line.startsWith("MTFONT expand"));
  check("floor expansion emits an MTFONT diagnostic",
    fontLines.length === 1 && /box=\d+x\d+ need=\d+x\d+ font=8/.test(fontLines[0]),
    fontLines[0]);
})();

// ---------------------------------------------------------------------------
// §7.3 字重默认 500/Medium（2026-10-16 用户定稿）：显式下发仍以它为准；
//      测量探针必须用与节点**相同**的字重 —— 否则探针按一个档位量宽度、节点按另一档显示，
//      溢出测不出来，译文会戳出气泡。
// ---------------------------------------------------------------------------
(function mediumFontWeight() {
  const page = freshPage();
  apply(page, scene(page, [bubble({ id: "medium", translated_text: "默认字重" })]));
  const root = page.document.querySelector(".__mt_scene_root");
  const mediumText = root.children.find((node) => node.className.includes("mt-text-layer"));
  check("default font weight is 500",
    mediumText.style.getPropertyValue("--mt-font-weight") === "500",
    mediumText.style.getPropertyValue("--mt-font-weight"));
  check("node records the effective weight for the line rebalancer",
    mediumText.dataset.mtFontWeight === "500", mediumText.dataset.mtFontWeight);
  const probe = page.document.querySelector(".mt-measure-probe");
  check("measure probe carries the same weight as the node",
    !!probe && probe.style.getPropertyValue("--mt-font-weight") === "500",
    probe && probe.style.getPropertyValue("--mt-font-weight"));

  apply(page, scene(page, [bubble({ id: "explicit", translated_text: "显式字重", font_weight: 300 })]));
  const root2 = page.document.querySelector(".__mt_scene_root");
  const explicitText = root2.children.find((node) => node.className.includes("mt-text-layer"));
  check("explicit font weight still wins over the default",
    explicitText.style.getPropertyValue("--mt-font-weight") === "300",
    explicitText.style.getPropertyValue("--mt-font-weight"));
  check("probe follows the explicit weight too",
    probe.style.getPropertyValue("--mt-font-weight") === "300",
    probe.style.getPropertyValue("--mt-font-weight"));
})();

// ---------------------------------------------------------------------------
// §7.2 排版框重叠诊断（MTGEOM，只记日志）：同 root 内两个文字节点交叠过半必须报出来，
// 用于区分「宿主下发的框本身重叠」和「渲染层自己画重」。
// ---------------------------------------------------------------------------
(function geomOverlapDiagnostic() {
  const page = freshPage();
  const Mt = page.Mt;
  const makeNode = (x, y, w, h) => {
    const node = page.document.createElement("div");
    node.dataset.xPercent = String(x);
    node.dataset.yPercent = String(y);
    node.dataset.wPercent = String(w);
    node.dataset.hPercent = String(h);
    return node;
  };
  const lines = [];
  const originalLog = console.log;
  console.log = (msg) => lines.push(String(msg));
  try {
    Mt._logTextOverlap([makeNode(10, 10, 40, 20), makeNode(20, 12, 40, 20)], "scene=test");
    Mt._logTextOverlap([makeNode(0, 0, 40, 20), makeNode(50, 60, 40, 20)], "scene=test");
  } finally {
    console.log = originalLog;
  }
  check("overlap diagnostic logs once for two half-overlapping boxes",
    lines.length === 1 && lines[0].startsWith("MTGEOM overlap scene=test"),
    lines);
})();

// ---------------------------------------------------------------------------
// §6 描边宽度随字号
// ---------------------------------------------------------------------------
(function strokeWidth() {
  const page = freshPage();
  check("stroke width clamps at the low end", near(page.Mt._dynamicStrokeWidth(20), 1.8, 0.001));
  check("stroke width clamps at the high end", near(page.Mt._dynamicStrokeWidth(60), 4.2, 0.001));
  check("stroke width scales with font size", near(page.Mt._dynamicStrokeWidth(30), 2.6, 0.001));
})();

// ---------------------------------------------------------------------------
// §8 / §13.8 竖排判定：宽扁气泡不被误转 90°
// ---------------------------------------------------------------------------
(function verticalLayout() {
  const page = freshPage();
  const Mt = page.Mt;
  check("explicit vertical flag wins",
    Mt._shouldUseVerticalLayout({ vertical: true, w: 40, h: 4 }, "译文") === true);
  check("korean original stays horizontal",
    Mt._shouldUseVerticalLayout({ original_text: "안녕하세요", w: 10, h: 40 }, "译文") === false);
  check("japanese tall bubble goes vertical",
    Mt._shouldUseVerticalLayout({ original_text: "こんにちは", w: 10, h: 40 }, "译文") === true);
  check("japanese flat bubble stays horizontal",
    Mt._shouldUseVerticalLayout({ original_text: "こんにちは", w: 40, h: 5 }, "译文") === false);
  check("|90°| on a wide box is treated as a misread",
    Mt._sanitizeRenderRotationDeg(90, 200, 20) === 0);
  check("|90°| on a tall box is kept",
    Mt._sanitizeRenderRotationDeg(90, 20, 200) === 90);
})();

// ---------------------------------------------------------------------------
// §9 / §13.10 宿主样式隔离：字号/颜色/字重/字体在覆盖层自己的类上显式声明
// ---------------------------------------------------------------------------
(function hostStyleIsolation() {
  const page = freshPage();
  apply(page, scene(page, [bubble({})]));
  const styleText = page.document.head.children.map((node) => node.textContent).join("\n");
  check("overlay layer declares an explicit font-size",
    /\.mt-overlay-layer\{[^}]*font-size:16px/.test(styleText));
  check("overlay root/bubble/content/loading declare font-size:inherit",
    // 2026-10-02：loading 框也进了这条隔离规则（RIDI 的 div{...} 元素级规则不能压到它身上），
    // 所以断言从「三个类的固定串」改成「这条规则里必须包含这三个类，且声明了 font-size:inherit」。
    /\.mt-overlay-root,\.mt-bubble,\.mt-bubble-content[^{]*\{[^}]*font-size:inherit/.test(styleText));
  check("bubble declares family/weight/color explicitly",
    /\.mt-bubble\{[^}]*font-family:/.test(styleText) &&
    /\.mt-bubble\{[^}]*font-weight:var\(--mt-font-weight/.test(styleText) &&
    /\.mt-bubble\{[^}]*color:var\(--mt-text-color/.test(styleText));
  check("content re-declares every inherited text property",
    /\.mt-bubble-content\{[^}]*font-family:inherit/.test(styleText) &&
    /\.mt-bubble-content\{[^}]*font-weight:inherit/.test(styleText) &&
    /\.mt-bubble-content\{[^}]*color:inherit/.test(styleText));
  // §10/§14 显示原文必须**彻底**恢复：斜排实底的填充块 .mt-fill-flat 是裁剪层里的独立 div，
  // 不继承 .mt-bubble 的规则，只藏 .mt-bubble 会留下一块底色糊住原文（用户实测反馈）。
  check("show-source hides the cover clip layer",
    /\.mt-overlay-root\.mt-show-source \.mt-cover-clip/.test(styleText));
  check("show-source hides the tilted flat fill",
    /\.mt-overlay-root\.mt-show-source \.mt-fill-flat/.test(styleText));
  check("show-source hides the text layer",
    /\.mt-overlay-root\.mt-show-source \.mt-text-layer\{opacity:0!important;\}/.test(styleText));
  // 关键：必须用 opacity 而不是 display:none —— 点击监听器挂在文字节点自身，
  // display:none 会让它收不到第二次点击，「点一次看原文、再点回不去译文」。
  check("show-source must not use display:none (breaks the click-back)",
    !/mt-show-source[^{]*\.mt-text-layer\{display:none/.test(styleText) &&
    !/mt-show-source[^{]*\{display:none!important;\}/.test(styleText),
    styleText.match(/mt-show-source[^{]*\{[^}]*\}/g) || []);
})();

// ---------------------------------------------------------------------------
// §10 点击切换原文：所有遮罩层都要被藏掉（斜排实底曾经漏掉 .mt-fill-flat）
// ---------------------------------------------------------------------------
(function showSourceCoversEveryLayer() {
  const page = freshPage();
  apply(page, scene(page, [
    bubble({ id: "flat", polygon: TILTED_POLYGON, bg_type: "solid", bg_color: "#223344" }),
    bubble({ id: "plain", x: 60, y: 60, w: 20, h: 10, bg_type: "none" })
  ]));
  const root = page.document.querySelector(".__mt_scene_root");
  check("tilted solid bubble really has a flat fill to hide",
    !!root.querySelector(".mt-fill-flat"));
  check("tilted solid cover is marked as fill-outside",
    root.querySelector(".mt-cover-only").className.includes("mt-fill-outside"));
  const styleText = page.document.head.children.map((node) => node.textContent).join("\n");
  check("every painted layer is covered by the show-source rule",
    [".mt-cover-clip", ".mt-fill-flat", ".mt-fill-tilted", ".mt-text-layer"].every((sel) =>
      styleText.includes(".mt-overlay-root.mt-show-source " + sel)));
})();

// ---------------------------------------------------------------------------
// §11 跨页/分片：倾斜四边形与页矩形求交
// ---------------------------------------------------------------------------
(function convexClip() {
  const page = freshPage();
  const clipped = page.Mt._clipConvexPolygonToRect(
    [{ x: -10, y: 50 }, { x: 110, y: 50 }, { x: 110, y: 60 }, { x: -10, y: 60 }],
    { x: 0, y: 0, w: 100, h: 100 }
  );
  check("convex clip keeps 4 points", clipped.length === 4, clipped);
  check("convex clip clamps to the page rect",
    clipped.every((point) => point.x >= -0.001 && point.x <= 100.001), clipped);

  const tilted = [{ x: 20, y: 20 }, { x: 80, y: 60 }, { x: 76, y: 68 }, { x: 16, y: 28 }];
  const halves = page.Mt._clipConvexPolygonToRect(tilted, { x: 0, y: 0, w: 50, h: 100 });
  check("tilted quad is clipped to the left page", halves.length >= 3, halves);

  const empty = page.Mt._clipConvexPolygonToRect(tilted, { x: 0, y: 0, w: 5, h: 5 });
  check("non-overlapping page yields an empty clip", empty.length === 0, empty);
})();

// ---------------------------------------------------------------------------
// §10 点击气泡切换显示原文
// ---------------------------------------------------------------------------
(function clickTogglesSource() {
  const page = freshPage();
  apply(page, scene(page, [bubble({})]));
  const root = page.document.querySelector(".__mt_scene_root");
  const text = root.querySelector(".mt-text-layer");
  text.dispatch("click", { preventDefault() {}, stopPropagation() {} });
  check("click toggles source view on", root.classList.contains("mt-show-source"));
  text.dispatch("click", { preventDefault() {}, stopPropagation() {} });
  check("click toggles source view off", !root.classList.contains("mt-show-source"));
})();

// ---------------------------------------------------------------------------
// §10 渲染分支兜底：坏气泡不影响其余气泡上屏
// ---------------------------------------------------------------------------
(function badBubbleIsolation() {
  const page = freshPage();
  const result = apply(page, scene(page, [
    bubble({ id: "broken", w: 0, h: 0 }),
    bubble({ id: "ok", x: 50, y: 50, w: 20, h: 10 })
  ]));
  check("scene still renders when one bubble is broken", result.ok === true, result);
  check("only the valid bubble is mounted", result.bubbles === 1, result);

  const bad = apply(page, "{ not json");
  check("bad payload is reported, not thrown",
    bad.ok === false && String(bad.reason).startsWith("exception:"), bad);

  const missing = apply(page, JSON.stringify({ image_id: "nope", bubbles: [bubble({})] }));
  check("missing target is reported", missing.ok === false && missing.reason === "no_target", missing);
})();

// ---------------------------------------------------------------------------
// §10 移除场景（「原文」按钮）
// ---------------------------------------------------------------------------
(function removeScenes() {
  const page = freshPage();
  apply(page, scene(page, [bubble({})]));
  const removed = page.Mt.removeScenes();
  check("removeScenes removes the mounted root", removed === 1, removed);
  check("removeScenes clears the overlay registry", page.Mt._overlays.size === 0);
  check("removeScenes detaches the root from the DOM",
    page.document.querySelector(".__mt_scene_root") === null);
})();

// ---------------------------------------------------------------------------
// §0 场景数据不可变（Object.freeze）
// ---------------------------------------------------------------------------
(function frozenScene() {
  const page = freshPage();
  const sceneObject = page.Mt.applyScene ? null : null;
  const payload = scene(page, [bubble({})]);
  apply(page, payload);
  const entry = page.Mt._overlays.get("mt1");
  check("overlay entry tracks the scene signature",
    typeof entry.signature === "string" && entry.signature.length > 0, entry.signature);
  check("overlay entry keeps text node references",
    Array.isArray(entry.textNodes) && entry.textNodes.length === 1);
  check("scene value is unused placeholder", sceneObject === null);
})();

// ---------------------------------------------------------------------------
// §11 跨页/分片遮罩段（union 遮罩）
// ---------------------------------------------------------------------------
(function coverSegments() {
  const page = freshPage();
  // 无分片信息时返回 null → 单块遮罩（保证既有链路行为不变）。
  check("no cover_segments yields null", page.Mt._resolveCoverSegments({}) === null);
  check("empty cover_segments yields null", page.Mt._resolveCoverSegments({ cover_segments: [] }) === null);
  check("all-invalid cover_segments yields null",
    page.Mt._resolveCoverSegments({ cover_segments: [{ x: 0, y: 0, w: 0, h: 0 }] }) === null);

  // 同页两段 → 合并成一块 union 遮罩（否则后一段的背景会顶掉前一段）。
  const twoSegments = page.Mt._resolveCoverSegments({
    cover_segments: [
      { page_id: "p1", x: 10, y: 10, w: 20, h: 10, in_band: true },
      { page_id: "p1", x: 12, y: 26, w: 16, h: 8, in_band: false }
    ]
  });
  check("same page_id segments union into one block",
    twoSegments.length === 1 && near(twoSegments[0].box.x, 10) &&
    near(twoSegments[0].box.y, 10) && near(twoSegments[0].box.w, 20) &&
    near(twoSegments[0].box.h, 24), twoSegments[0].box);
  check("union of a mixed band group is not all-in-band",
    twoSegments[0].allInBand === false, twoSegments[0]);

  // 不同页 → 各自一块。
  const twoPages = page.Mt._resolveCoverSegments({
    cover_segments: [
      { page_id: "p1", x: 10, y: 10, w: 20, h: 10 },
      { page_id: "p2", x: 30, y: 60, w: 20, h: 10 }
    ]
  });
  check("different page_id segments stay separate", twoPages.length === 2, twoPages.length);

  // 多段 union 丢掉四边形（并集没有简单四边形表达，宁可少斜也不画歪）。
  const tiltedUnion = page.Mt._resolveCoverSegments({
    cover_segments: [
      { page_id: "p1", x: 10, y: 10, w: 20, h: 10, polygon: TILTED_POLYGON },
      { page_id: "p1", x: 12, y: 30, w: 16, h: 8 }
    ]
  });
  check("multi-segment union drops the polygon", tiltedUnion[0].polygon === null);

  // 单段保留四边形 → 遮罩仍按倾角裁剪。
  const singleTilted = page.Mt._resolveCoverSegments({
    cover_segments: [{ page_id: "p1", x: 10, y: 41, w: 40, h: 18, polygon: TILTED_POLYGON }]
  });
  check("single segment keeps its polygon",
    !!singleTilted[0].polygon && singleTilted[0].polygon.length === 4);

  // DOM：两个分段 → 两个遮罩节点 + 一个译文节点，遮罩仍全部在译文之前。
  const page2 = freshPage();
  apply(page2, scene(page2, [bubble({
    bg_type: "none",
    cover_segments: [
      { page_id: "p1", x: 10, y: 10, w: 20, h: 10, in_band: true },
      { page_id: "p2", x: 60, y: 60, w: 20, h: 10, in_band: true }
    ]
  })]));
  const root2 = page2.document.querySelector(".__mt_scene_root");
  const clips = root2.children.filter((node) => node.className.includes("mt-cover-clip"));
  check("two segments mount two cover nodes", clips.length === 2, clips.length);
  check("segment nodes carry their page id",
    clips.map((node) => node.dataset.pageId).join(",") === "p1,p2",
    clips.map((node) => node.dataset.pageId));
  check("cover segments still precede the text layer",
    root2.children.map((node) => node.className.includes("mt-cover-clip") ? "c" : "t").join("") === "cct",
    root2.children.map((node) => node.className));
  check("cover segment reports its layer id for dedupe",
    clips[0].dataset.mtLayer === "b0:cover", clips[0].dataset.mtLayer);
  check("second cover segment gets a distinct layer id",
    clips[1].dataset.mtLayer === "b0:cover:1", clips[1].dataset.mtLayer);

  // 单段（等价于没有分片）→ 仍是一块遮罩，layer id 不带后缀。
  const page3 = freshPage();
  apply(page3, scene(page3, [bubble({
    cover_segments: [{ page_id: "p1", x: 20, y: 20, w: 30, h: 10 }]
  })]));
  const clips3 = page3.document.querySelector(".__mt_scene_root")
    .children.filter((node) => node.className.includes("mt-cover-clip"));
  check("single segment keeps the legacy layer id",
    clips3.length === 1 && clips3[0].dataset.mtLayer === "b0:cover",
    clips3.map((node) => node.dataset.mtLayer));
})();

// ---------------------------------------------------------------------------
// §11 三级背景降级：带内复合图 + 带外整页图
// ---------------------------------------------------------------------------
(function backgroundFallbackLayers() {
  const page = freshPage();
  // 整页图缺位 → 只有单层复合图（绝不能出现「有遮罩但背景是空的」）。
  apply(page, scene(page, [bubble({
    bg_type: "none",
    cover_segments: [{ page_id: "p1", x: 20, y: 20, w: 30, h: 10, in_band: false }]
  })]));
  const cover1 = coverBubbleOf(page.document.querySelector(".__mt_scene_root"));
  check("out-of-band segment without a page image stays single-layer",
    cover1.dataset.cleanedLayers === undefined &&
    cover1.style.getPropertyValue("--mt-cleaned-page-image") === "",
    { layers: cover1.dataset.cleanedLayers });

  // 整页图就位 → 两层背景，带内复合图压在上面。
  const page2 = freshPage();
  apply(page2, JSON.stringify({
    v: 1,
    image_id: "mt1",
    cleaned_url: "https://mtlocal.overlay/band.jpg",
    page_cleaned_url: "https://mtlocal.overlay/page.jpg",
    bubbles: [bubble({
      bg_type: "none",
      cover_segments: [{ page_id: "p1", x: 20, y: 20, w: 30, h: 10, in_band: false }]
    })]
  }));
  const cover2 = coverBubbleOf(page2.document.querySelector(".__mt_scene_root"));
  check("out-of-band segment with a page image switches to two layers",
    cover2.dataset.cleanedLayers === "2", cover2.dataset.cleanedLayers);
  check("page layer maps the full-page cleaned image",
    cover2.style.getPropertyValue("--mt-cleaned-page-image") === 'url("https://mtlocal.overlay/page.jpg")',
    cover2.style.getPropertyValue("--mt-cleaned-page-image"));
  check("page layer carries its own background math",
    cover2.style.getPropertyValue("--mt-page-size-x") === "333.33%" ||
    cover2.style.getPropertyValue("--mt-page-size-x") !== "",
    cover2.style.getPropertyValue("--mt-page-size-x"));

  // 全部在带内 → 不引入整页图（省一次解码，也避免整页图过期导致的花屏）。
  const page3 = freshPage();
  apply(page3, JSON.stringify({
    v: 1,
    image_id: "mt1",
    cleaned_url: "https://mtlocal.overlay/band.jpg",
    page_cleaned_url: "https://mtlocal.overlay/page.jpg",
    bubbles: [bubble({
      bg_type: "none",
      cover_segments: [{ page_id: "p1", x: 20, y: 20, w: 30, h: 10, in_band: true }]
    })]
  }));
  const cover3 = coverBubbleOf(page3.document.querySelector(".__mt_scene_root"));
  check("all-in-band segment stays single-layer",
    cover3.dataset.cleanedLayers === undefined, cover3.dataset.cleanedLayers);

  // 场景签名必须把整页图算进去，否则整页图落地后不会重建。
  const page4 = freshPage();
  const payloadA = JSON.stringify({
    v: 1, image_id: "mt1", cleaned_url: null, page_cleaned_url: null,
    bubbles: [bubble({ bg_type: "none" })]
  });
  const payloadB = JSON.stringify({
    v: 1, image_id: "mt1", cleaned_url: null,
    page_cleaned_url: "https://mtlocal.overlay/page.jpg",
    bubbles: [bubble({ bg_type: "none" })]
  });
  apply(page4, payloadA);
  const again = apply(page4, payloadB);
  check("page cleaned url participates in the scene signature",
    again.rebuilt === true, again);
})();

// ---------------------------------------------------------------------------
// §11 视觉去重：按气泡摘节点，不重发场景
// ---------------------------------------------------------------------------
(function visualDedupe() {
  const page = freshPage();
  apply(page, scene(page, [
    bubble({ id: "dup" }),
    bubble({ id: "keep", x: 50, y: 50, w: 20, h: 10 })
  ]));
  const root = page.document.querySelector(".__mt_scene_root");
  const before = root.children.length;
  const removed = page.Mt.suppressBubble("mt1", "dup");
  check("suppressBubble removes the cover and the text", removed === 2, removed);
  check("suppressBubble shrinks the root", root.children.length === before - 2, root.children.length);
  check("suppressBubble keeps the other bubble",
    root.children.every((node) => node.dataset.mtLayer.indexOf("keep") === 0),
    root.children.map((node) => node.dataset.mtLayer));
  check("suppressBubble keeps the root identity",
    root === page.document.querySelector(".__mt_scene_root"));
  check("suppressBubble is a no-op for unknown bubbles",
    page.Mt.suppressBubble("mt1", "nope") === 0 && page.Mt.suppressBubble("nope", "dup") === 0);
})();

// ---------------------------------------------------------------------------
// §11 跨图 union：相邻分片回来时增量换掉遮罩几何（不重建 root / 不动译文）
// ---------------------------------------------------------------------------
(function crossImageUnionUpdate() {
  const page = freshPage();
  // 本图那张只画了「图内那一半」：轴对齐小框。
  apply(page, scene(page, [bubble({
    id: "cross", x: 30, y: 90, w: 20, h: 8,
    bg_type: "none", stitch_overflow: true,
    translated_text: "跨页台词"
  })]));
  const root = page.document.querySelector(".__mt_scene_root");
  const textBefore = root.querySelector(".mt-text-layer");
  const coverBefore = root.querySelector(".mt-cover-clip");
  const fontBefore = textBefore.style.fontSize;
  const heightBefore = parseFloat(coverBefore.style.height);

  // 下一张图翻译完回来，带来完整四边形（跨出本图下边界 → 高得多）。
  const fullPolygon = [
    { x: 30, y: 90 }, { x: 50, y: 96 }, { x: 50, y: 116 }, { x: 30, y: 110 }
  ];
  const applied = page.Mt.updateBubbleCover("mt1", "cross", { polygon: fullPolygon });
  check("updateBubbleCover reports a write", applied === true, applied);

  const textAfter = root.querySelector(".mt-text-layer");
  const coverAfter = root.querySelector(".mt-cover-clip");
  check("union update keeps the root identity",
    root === page.document.querySelector(".__mt_scene_root"));
  check("union update keeps the text node (no re-layout)",
    textAfter === textBefore && textAfter.style.fontSize === fontBefore,
    { same: textAfter === textBefore, font: textAfter.style.fontSize });
  check("union update replaces the cover node", coverAfter !== coverBefore);
  check("union update widens the cover to the full shape",
    parseFloat(coverAfter.style.height) > heightBefore,
    { before: heightBefore, after: parseFloat(coverAfter.style.height) });
  check("union update keeps the layer id stable",
    coverAfter.dataset.mtLayer === "cross:cover", coverAfter.dataset.mtLayer);
  check("union update keeps covers before texts",
    root.children.map((n) => n.className.includes("mt-cover-clip") ? "c" : "t").join("") === "ct",
    root.children.map((n) => n.className));

  check("union update is a no-op for unknown image",
    page.Mt.updateBubbleCover("nope", "cross", { polygon: fullPolygon }) === false);
  check("union update is a no-op for unknown bubble",
    page.Mt.updateBubbleCover("mt1", "nope", { polygon: fullPolygon }) === false);

  // 实底气泡：撑开链必须指向新遮罩，否则译文再撑开时遮罩不会跟着长。
  const page2 = freshPage();
  apply(page2, scene(page2, [bubble({
    id: "solid", x: 20, y: 40, w: 30, h: 10, bg_type: "solid",
    bg_color: "#ffeedd", stitch_overflow: true
  })]));
  const root2 = page2.document.querySelector(".__mt_scene_root");
  const text2 = root2.querySelector(".mt-text-layer");
  check("solid bubble starts with a mask chain", Array.isArray(text2.__mtMaskChain));
  page2.Mt.updateBubbleCover("mt1", "solid", { polygon: TILTED_POLYGON });
  const chain = text2.__mtMaskChain;
  const newCover = root2.querySelector(".mt-cover-clip");
  check("mask chain points at the new cover after the union update",
    Array.isArray(chain) && chain.indexOf(newCover) >= 0, chain && chain.length);
  check("mask chain still reaches the inner cover node",
    Array.isArray(chain) && chain.indexOf(newCover.querySelector(".mt-cover-only")) >= 0);
})();

// ---------------------------------------------------------------------------
// §12 接缝覆盖层：跨两张图的气泡只画「一个 root / 一块盖板 / 一份译文」
//
// 用户 2026-09-30 反馈「为什么不能只有一个覆盖层，而是必须分离在两页。字也是」。
// 旧结构：一张图一个 root，跨缝气泡被拆成上页半块 + 下页半块（两次 cover、两次 text、
// 两张清理图）→ 接缝处必然出现两层纹理。现在把「接缝带」当成虚拟图，一条缝一个 root。
// ---------------------------------------------------------------------------
(function seamSingleLayer() {
  const page = freshPage();
  const second = page.document.createElement("img");
  second.setAttribute("data-mt-id", "mt2");
  second._setRect({ left: 0, top: 1000, width: 400, height: 1000 });
  page.host.appendChild(second);

  function seamScene(overrides = {}) {
    return JSON.stringify(Object.assign({
      v: 1,
      kind: "seam",
      seam_key: "mt1|mt2",
      image_id: "mt1",
      next_image_id: "mt2",
      cleaned_url: null,
      // 带：本图拼接坐标系里 x 40 起、y 800 起、496... → 用 400x400 便于口算
      // （左/上边距都必须参与换算：带是横竖都裁过的一小块，漏掉左边距整块会横向平移）
      band_left_px: 40,
      band_top_px: 800,
      band_width_px: 400,
      band_height_px: 400,
      top_h_px: 0,
      source_width_px: 400,
      source_height_px: 1000,
      bubbles: [bubble({
        id: "s0", x: 5, y: 25, w: 60, h: 40, bg_type: "none",
        stitch_overflow: true, fixed_font_percent: 8
      })]
    }, overrides));
  }

  const first = JSON.parse(page.Mt.applySeamSceneJson(seamScene()));
  check("seam scene applies", first.ok === true && first.mode === "seam", first);
  check("seam scene reports one cover and one text",
    first.covers === 1 && first.bubbles === 1, first);

  const root = page.document.querySelector(".__mt_seam_root");
  check("seam root exists", !!root);
  check("seam root is a single root (not one per page)",
    page.document.querySelectorAll(".__mt_seam_root").length === 1,
    page.document.querySelectorAll(".__mt_seam_root").length);
  check("seam root holds exactly one cover + one text node",
    !!root && root.children.length === 2 &&
    root.children[0].className.includes("mt-cover-clip") &&
    root.children[1].className.includes("mt-text-layer"),
    root && root.children.map((n) => n.className));
  check("seam root shares the scroll host with both images",
    root.parentElement.className.includes("mt-overlay-layer") &&
    root.parentElement.parentElement === page.host, root.parentElement.className);
  // 相对层原点的位置 = 带的屏幕矩形（视口 left 40×1 + 层原点 0、top 800、400x400）
  check("seam root sits on the band rect (left offset included)",
    root.style.left === "40px" && root.style.top === "800px" &&
    root.style.width === "400px" && root.style.height === "400px",
    [root.style.left, root.style.top, root.style.width, root.style.height]);
  check("seam scene state is visible", page.Mt.seamSceneState("mt1|mt2") === "visible",
    page.Mt.seamSceneState("mt1|mt2"));
  // 固定字号：带高 400 × 8% = 32px（与两半各自渲染时同一个屏幕字号）
  check("seam text uses the shared fixed font size",
    root.children[1].style.fontSize === "32.0px", root.children[1].style.fontSize);

  // 同一场景（「原文 → 翻译」贴回）：只同步位置，不重建节点。
  const again = JSON.parse(page.Mt.applySeamSceneJson(seamScene()));
  check("identical seam scene does not rebuild",
    again.ok === true && again.rebuilt === false, again);
  check("seam node identity kept",
    page.document.querySelector(".__mt_seam_root") === root);

  // 「原文」：接缝覆盖层必须跟着一起摘掉，登记表也要清空。
  page.Mt.removeScenes();
  check("removeScenes also drops seam roots",
    page.document.querySelectorAll(".__mt_seam_root").length === 0 &&
    page.Mt.seamSceneState("mt1|mt2") === "absent");
})();

// ---------------------------------------------------------------------------
// §12.2 接缝覆盖层的拒绝条件：几何不成立时必须返回 ok:false，让调用方保留两半渲染
// （一条带会盖住页面间隙 / 两边缩放不一致时带上像素无法同时对齐）
// ---------------------------------------------------------------------------
(function seamGeometryGuards() {
  function pageWithSecond(rect) {
    const page = freshPage();
    const second = page.document.createElement("img");
    second.setAttribute("data-mt-id", "mt2");
    second._setRect(rect);
    page.host.appendChild(second);
    return page;
  }
  const payload = {
    v: 1, kind: "seam", seam_key: "k", image_id: "mt1", next_image_id: "mt2",
    cleaned_url: null, band_left_px: 0, band_top_px: 800, band_width_px: 400, band_height_px: 400,
    top_h_px: 0, source_width_px: 400, source_height_px: 1000,
    bubbles: [bubble({ id: "s0", stitch_overflow: true })]
  };

  const gapPage = pageWithSecond({ left: 0, top: 1012, width: 400, height: 1000 });
  const gap = JSON.parse(gapPage.Mt.applySeamSceneJson(JSON.stringify(payload)));
  check("page gap is refused", gap.ok === false && gap.reason === "page_gap", gap);

  const widthPage = pageWithSecond({ left: 0, top: 1000, width: 300, height: 1000 });
  const widthMismatch = JSON.parse(widthPage.Mt.applySeamSceneJson(JSON.stringify(payload)));
  check("width mismatch is refused",
    widthMismatch.ok === false && widthMismatch.reason === "width_mismatch", widthMismatch);

  const offsetPage = pageWithSecond({ left: 24, top: 1000, width: 400, height: 1000 });
  const offset = JSON.parse(offsetPage.Mt.applySeamSceneJson(JSON.stringify(payload)));
  check("horizontal page offset is refused",
    offset.ok === false && offset.reason === "page_offset_x", offset);

  const missingPage = (function () {
    const page = freshPage();
    return page;
  })();
  const missing = JSON.parse(missingPage.Mt.applySeamSceneJson(JSON.stringify(payload)));
  check("missing next image is refused",
    missing.ok === false && missing.reason === "no_target", missing);

  // 纯函数口径：合法几何给出带矩形，非法几何给出原因。
  const band = missingPage.Mt._resolveSeamBand(payload,
    { width: 400, height: 1000, left: 0, top: 0, bottom: 1000 },
    { width: 400, height: 1000, left: 0, top: 1000, bottom: 2000 });
  check("band rect math is linear in the source scale",
    band.ok === true && band.scaleY === 1 && band.scaleX === 1 &&
    band.top === 800 && band.height === 400, band);
  // 左边距必须计入（漏掉它整块盖板会横向平移一个 band_left_px，2026-09-30 真机实测偏出 114px）
  const offsetBand = missingPage.Mt._resolveSeamBand(
    Object.assign({}, payload, { band_left_px: 120 }),
    { width: 400, height: 1000, left: 12, top: 0, bottom: 1000 },
    { width: 400, height: 1000, left: 12, top: 1000, bottom: 2000 });
  check("band left offset is applied",
    offsetBand.ok === true && offsetBand.left === 132, offsetBand);
  const scaledBand = missingPage.Mt._resolveSeamBand(
    Object.assign({}, payload, { band_left_px: 120 }),
    { width: 200, height: 500, left: 0, top: 0, bottom: 500 },
    { width: 200, height: 500, left: 0, top: 500, bottom: 1000 });
  check("band left offset follows the display scale",
    scaledBand.ok === true && scaledBand.scaleX === 0.5 && scaledBand.left === 60 &&
    scaledBand.top === 400 && scaledBand.height === 200, scaledBand);
})();

// ---------------------------------------------------------------------------
// §12.3 固定字号（fixed_font_percent）只是**上限**，不是硬值
//
// 背景（2026-09-30 用户反馈「虽然已经是一块了，但是字超出了原来的字体位置了」）：
// 接缝/跨图气泡的字号由宿主按图像像素算出后下发，宿主估「可用文字宽度」时用的是
// 图像像素口径（不含渲染层 `.mt-bubble` 的 em 内边距），差一点点就会让 CJK 译文折行。
// 折行后文字层高度直接翻倍、译文从气泡里溢出来（真机实测：盒子 266×59、内容 233×113、2 行）。
// 渲染层必须能兜住：放得下照抄固定值，放不下二分收小。
// ---------------------------------------------------------------------------
(function seamFixedFontIsOnlyAnUpperBound() {
  function pageWithTwoImages() {
    const page = freshPage();
    const second = page.document.createElement("img");
    second.setAttribute("data-mt-id", "mt2");
    second._setRect({ left: 0, top: 1000, width: 400, height: 1000 });
    page.host.appendChild(second);
    return page;
  }

  const seamPayload = {
    v: 1, kind: "seam", seam_key: "mt1|mt2", image_id: "mt1", next_image_id: "mt2",
    cleaned_url: null, band_left_px: 0, band_top_px: 800, band_width_px: 400, band_height_px: 400,
    top_h_px: 0, source_width_px: 400, source_height_px: 1000,
    // 带高 400 × 8% = 32px 固定字号；盒子 60%×400 = 240 宽、40%×400 = 160 高
    bubbles: [bubble({ id: "s0", x: 5, y: 25, w: 60, h: 40, stitch_overflow: true, fixed_font_percent: 8 })]
  };

  // 1) 放得下（桩默认不溢出）→ 必须**照抄**固定值，跨图两侧字号才一致
  const fitsPage = pageWithTwoImages();
  fitsPage.Mt.applySeamSceneJson(JSON.stringify(seamPayload));
  const fitsText = fitsPage.document.querySelector(".__mt_seam_root").children[1];
  check("fixed font is used verbatim when the text fits",
    fitsText.style.fontSize === "32.0px", fitsText.style.fontSize);

  // 2) 强制「字号 > 20px 就放不下」→ 必须收小到刚好放得下，且不得超过固定值
  const shrinkPage = pageWithTwoImages();
  const seen = [];
  globalThis.__mtStubProbeScroll = function (probe) {
    const size = parseFloat(probe.style.fontSize) || 0;
    seen.push(size);
    return size > 20 ? { width: 600, height: 600 } : { width: 0, height: 0 };
  };
  shrinkPage.Mt.applySeamSceneJson(JSON.stringify(seamPayload));
  const shrinkText = shrinkPage.document.querySelector(".__mt_seam_root").children[1];
  globalThis.__mtStubProbeScroll = null;
  const shrunk = parseFloat(shrinkText.style.fontSize);
  check("overflowing fixed font shrinks below the fixed value",
    shrunk < 32, shrunk);
  check("shrink search brackets the real limit (≈20px × 0.96)",
    shrunk >= 18 && shrunk <= 20.5, shrunk);
  check("shrink search probes more than one size", seen.length > 3, seen.length);

  // 3) 描边开关在接缝带上必须同样生效 —— 走的是同一套节点构建，
  //    不然跨缝那一条会独独留着白边（用户会看成「这一块没改」）。
  const strokeOff = JSON.parse(JSON.stringify(seamPayload));
  strokeOff.bubbles[0].needs_stroke = false;
  const offPage = pageWithTwoImages();
  offPage.Mt.applySeamSceneJson(JSON.stringify(strokeOff));
  const offText = offPage.document.querySelector(".__mt_seam_root").children[1];
  check("seam band honors needs_stroke:false",
    offText.style.getPropertyValue("--mt-stroke-width") === "0.0px" &&
    offText.classList.contains("mt-no-stroke"),
    offText.style.getPropertyValue("--mt-stroke-width"));
})();

// ---------------------------------------------------------------------------
// §12.4 描边开关：只在「背景多色 或 原文自带描边」时才加白边/黑边
//
// 用户 2026-09-30 要求：「那个字体的白边黑边，要不只在背景有多种颜色的时候有吧」，
// 补充：「除了背景复杂，还有就是原文有的时候，也需要黑边白边」。
// 判定在宿主（`BackgroundOutlinePolicy` + `BubbleColorExtractor.originalHadOutline`），
// 渲染层只负责执行 —— 所以这里锁的是**执行**部分与**向后兼容**部分：
// 字段缺失（老宿主 / 老存档场景 JSON）必须保留描边，否则观感会被静默改掉。
// ---------------------------------------------------------------------------
(function strokeSwitch() {
  // 1) 字段缺失 = 老宿主 → 保留描边，且不挂 mt-no-stroke
  const legacyPage = freshPage();
  apply(legacyPage, scene(legacyPage, [bubble({ id: "a", bg_type: "none" })]));
  const legacy = legacyPage.document.querySelector(".__mt_scene_root").querySelector(".mt-text-layer");
  check("missing needs_stroke keeps the outline",
    parseFloat(legacy.style.getPropertyValue("--mt-stroke-width")) > 0 &&
    !legacy.classList.contains("mt-no-stroke"),
    legacy.style.getPropertyValue("--mt-stroke-width"));

  // 2) needs_stroke:false → 宽度归零 + mt-no-stroke
  const offPage = freshPage();
  apply(offPage, scene(offPage, [bubble({ id: "a", bg_type: "none", needs_stroke: false })]));
  const off = offPage.document.querySelector(".__mt_scene_root").querySelector(".mt-text-layer");
  check("needs_stroke:false zeroes the outline width",
    off.style.getPropertyValue("--mt-stroke-width") === "0.0px",
    off.style.getPropertyValue("--mt-stroke-width"));
  check("needs_stroke:false marks the node mt-no-stroke",
    off.classList.contains("mt-no-stroke"), off.className);
  // 只把宽度置 0 不够：`.mt-bg-none` 还有一圈 1px 白 text-shadow，小字上照样糊笔画。
  const styleText = offPage.document.head.children.map((node) => node.textContent).join("\n");
  check("mt-no-stroke also kills the white text-shadow",
    /\.mt-bubble\.mt-no-stroke\{-webkit-text-stroke:0 transparent;text-shadow:none!important;\}/.test(styleText),
    styleText.match(/mt-no-stroke[^{]*\{[^}]*\}/g) || []);

  // 3) needs_stroke:true → 与缺省同一条路（原文自带描边时宿主就是这么下发的）
  const onPage = freshPage();
  apply(onPage, scene(onPage, [bubble({ id: "a", bg_type: "none", needs_stroke: true })]));
  const on = onPage.document.querySelector(".__mt_scene_root").querySelector(".mt-text-layer");
  check("needs_stroke:true keeps the outline",
    parseFloat(on.style.getPropertyValue("--mt-stroke-width")) > 0 &&
    !on.classList.contains("mt-no-stroke"),
    on.style.getPropertyValue("--mt-stroke-width"));
})();

// ---------------------------------------------------------------------------
// §13 loading 框（2026-10-02）
//
// 对齐参考项目 `C:\homework\translator` 的 `.mt-loading-card`：正在处理的那张图上方浮一个
// 半透明胶囊（转圈 + 阶段文案），贴图成功后换成译文覆盖层。
//
// 锁三件事（都是"看着像小事、坏了很难查"的）：
//   1. 锚点是**可见部分的中心** —— webtoon 一张图能有 3000px 高，按图片中心摆会跑到屏幕外；
//   2. 同一张图重复下发**只改文案、不重建节点**（重建就是肉眼可见的闪烁）；
//   3. 每个出口都真的清掉（hideLoading / removeScenes / clearLoadings），
//      否则页面上会永久留一个"正在翻译"的胶囊。
// ---------------------------------------------------------------------------
(function loadingCard() {
  const page = freshPage({
    hostRect: { left: 0, top: 0, width: 400, height: 800 },
    imageRect: { left: 0, top: 0, width: 400, height: 1000 }
  });
  const host = page.host;
  const show = (label) => JSON.parse(page.Mt.showLoadingJson(JSON.stringify({
    image_id: "mt1", label: label
  })));

  const first = show("识别文字…");
  check("loading card reports ok", first.ok === true, first);
  const root = page.document.querySelector(".__mt_loading_root");
  check("loading root lives in the overlay layer",
    !!root && !!root.closest(".mt-overlay-layer"), root && root.parentElement.className);
  check("loading root carries a loading card + spinner + label",
    !!root && !!root.querySelector(".mt-loading-card") &&
    !!root.querySelector(".mt-loading-spinner") &&
    !!root.querySelector(".mt-loading-label"), root && root.className);
  check("loading card shows the phase text",
    root.querySelector(".mt-loading-label").textContent === "识别文字…",
    root.querySelector(".mt-loading-label").textContent);

  // 图高 1000、视口高 800 → 可见部分是 0..800，卡片落在它的中心（200,400），
  // 而不是图片中心（200,500）。这正是「3000px 长图卡片跑出屏幕」的防线。
  const hostRect = host.getBoundingClientRect();
  check("loading card anchors to the visible part centre",
    root.style.left === "200px" && root.style.top === "400px",
    root.style.left + "," + root.style.top + " host=" + hostRect.height);
  check("loading card is centred on itself",
    root.style.transform === "translate(-50%, -50%)", root.style.transform);

  // 幂等：同一张图第二次下发复用同一个节点（identity 不变），只改文字。
  const labelNode = root.querySelector(".mt-loading-label");
  const second = show("翻译文字…");
  check("second show reuses the same node",
    second.reused === true && page.document.querySelector(".__mt_loading_root") === root, second);
  check("second show reuses the same label node",
    root.querySelector(".mt-loading-label") === labelNode, "rebuilt");
  check("second show only updates the text",
    labelNode.textContent === "翻译文字…", labelNode.textContent);
  check("only one loading root exists per image",
    page.document.querySelectorAll(".__mt_loading_root").length === 1,
    page.document.querySelectorAll(".__mt_loading_root").length);

  // 滚动/位移：图往上移 600px 后可见部分变成 0..400（视口内），中心 y=200。
  page.image._setRect({ left: 0, top: -600, width: 400, height: 1000 });
  show("渲染结果…");
  check("loading card follows the image when it moves",
    root.style.top === "200px", root.style.top);
  check("loading card keeps its x when only scrolled",
    root.style.left === "200px", root.style.left);

  // 图片整张移出视口 → 卡片隐藏（但登记还在，滚回来能接着显示）。
  page.image._setRect({ left: 0, top: 2000, width: 400, height: 1000 });
  show("渲染结果…");
  check("loading card is hidden while the image is off-screen",
    root.style.visibility === "hidden", root.style.visibility);

  // 出口 1：正常贴图后 hideLoading。
  page.image._setRect({ left: 0, top: 0, width: 400, height: 1000 });
  check("hideLoading removes the card",
    page.Mt.hideLoading("mt1") === 1 &&
    page.document.querySelector(".__mt_loading_root") === null &&
    page.Mt._loadings.size === 0,
    page.Mt._loadings.size);

  // 出口 2：切「原文」= removeScenes，正在处理的 loading 框也必须一起消失。
  show("识别文字…");
  const beforeRemove = page.document.querySelectorAll(".__mt_loading_root").length;
  page.Mt.removeScenes();
  check("removeScenes also clears the loading cards",
    beforeRemove === 1 && page.document.querySelector(".__mt_loading_root") === null &&
    page.Mt._loadings.size === 0,
    "before=" + beforeRemove + " after=" + page.Mt._loadings.size);

  // 出口 3：clearLoadings 一次清干净（多张图同时在处理时用）。
  show("识别文字…");
  const secondImage = page.document.createElement("img");
  secondImage.setAttribute("data-mt-id", "mt2");
  secondImage._setRect({ left: 0, top: 0, width: 400, height: 400 });
  host.appendChild(secondImage);
  JSON.parse(page.Mt.showLoadingJson(JSON.stringify({ image_id: "mt2", label: "识别文字…" })));
  check("two images can show two loading cards",
    page.Mt._loadings.size === 2, page.Mt._loadings.size);
  check("clearLoadings clears them all",
    page.Mt.clearLoadings() === 2 && page.Mt._loadings.size === 0 &&
    page.document.querySelectorAll(".__mt_loading_root").length === 0,
    page.Mt._loadings.size);

  // 目标图不在页面上：明确报 no_target，绝不抛异常（Kotlin 侧是 fire-and-forget 调用）。
  const missing = JSON.parse(page.Mt.showLoadingJson(JSON.stringify({ image_id: "nope" })));
  check("missing target reports no_target", missing.ok === false && missing.reason === "no_target", missing);
  check("missing image_id is rejected",
    JSON.parse(page.Mt.showLoadingJson(JSON.stringify({}))).reason === "no_image_id");

  // 宿主样式隔离：RIDI 那种 `div{letter-spacing:-0.03em}` 元素级规则不能压到卡片上。
  const styleText = page.document.head.children.map((node) => node.textContent).join("\n");
  check("loading card is in the host-style isolation list",
    /\.mt-loading-card,[^{]*\{letter-spacing:normal/.test(styleText) ||
    /\.mt-loading-label\{letter-spacing:normal/.test(styleText),
    "isolation rule missing");
  check("loading card declares its own pulse animation",
    /\.mt-loading-card\{[^}]*animation:mt-loading-pulse/.test(styleText.replace(/\n/g, "")));
})();

// ---------------------------------------------------------------------------
// §14 跨页接缝（2026-10-03）：2px 间隙容忍 + 被顶掉的两半可恢复
//
// 用户现场：缝落在气泡第 2 行之后一点，结果「下面那个气泡被擦掉一半、没译文」。
// 两条修复在这一层可验证：
//   1. 阅读器给两张切片留 2px 边距时，接缝带不再被判 `page_gap`（退回两半 = 重影）；
//   2. 接缝层失效时，它顶掉的两半必须贴回来（否则那块台词原文译文都没有）。
// ---------------------------------------------------------------------------
(function seamRecoveryAndGapTolerance() {
  const page = freshPage();
  const second = page.document.createElement("img");
  second.setAttribute("data-mt-id", "mt2");
  // 2px 间隙：旧阈值 1.5px 会拒绝，新阈值 3px 应当接受
  second._setRect({ left: 0, top: 1002, width: 400, height: 1000 });
  page.host.appendChild(second);

  const seamPayload = {
    v: 1,
    kind: "seam",
    seam_key: "mt1|mt2",
    image_id: "mt1",
    next_image_id: "mt2",
    cleaned_url: null,
    band_left_px: 0,
    band_top_px: 800,
    band_width_px: 400,
    band_height_px: 200,
    top_h_px: 0,
    source_width_px: 400,
    source_height_px: 1000,
    bubbles: [bubble({ id: "s0", x: 5, y: 25, w: 60, h: 40, bg_type: "none" })]
  };

  const band = page.Mt._resolveSeamBand(
    seamPayload,
    { left: 0, top: 0, width: 400, height: 1000, bottom: 1000 },
    { left: 0, top: 1002, width: 400, height: 1000, bottom: 2002 }
  );
  check("2px page gap is tolerated (band still usable)", band.ok === true, band);

  const wide = page.Mt._resolveSeamBand(
    seamPayload,
    { left: 0, top: 0, width: 400, height: 1000, bottom: 1000 },
    { left: 0, top: 1120, width: 400, height: 1000, bottom: 2120 }
  );
  check("a real gap still reports page_gap with its measured size",
    wide.ok === false && wide.reason === "page_gap" && wide.gap_px === 120, wide);

  // 先给两张图各布一个普通场景（含跨缝的两半 b0 / c0）
  const ownScene = JSON.stringify({
    v: 1,
    kind: "scene",
    image_id: "mt1",
    cleaned_url: null,
    bubbles: [bubble({ id: "b0", x: 10, y: 80, w: 50, h: 25, stitch_overflow: true })]
  });
  const nextScene = JSON.stringify({
    v: 1,
    kind: "scene",
    image_id: "mt2",
    cleaned_url: null,
    bubbles: [bubble({ id: "c0", x: 10, y: -20, w: 50, h: 25, stitch_overflow: true })]
  });
  check("own half scene applies", JSON.parse(page.Mt.applySceneJson(ownScene)).ok === true);
  check("next half scene applies", JSON.parse(page.Mt.applySceneJson(nextScene)).ok === true);

  const seam = JSON.parse(page.Mt.applySeamSceneJson(JSON.stringify(seamPayload)));
  check("seam scene applies with a 2px gap", seam.ok === true, seam);

  // 宿主把两半摘掉（接缝生效时的正常动作）
  const removedOwn = page.Mt.suppressBubble("mt1", "b0");
  const removedNext = page.Mt.suppressBubble("mt2", "c0");
  check("both halves are suppressed", removedOwn > 0 && removedNext > 0,
    [removedOwn, removedNext]);
  check("suppressed halves are gone from the DOM", halfLayers() === 0, halfLayers());

  // 用覆盖层登记表数层（DOM 桩的属性选择器不参与匹配，类选择器才可靠）
  function layerCount(imageId, layerId) {
    const entry = page.Mt._overlays.get(imageId);
    const kids = (entry && entry.root && entry.root.children) || [];
    return kids.filter((n) => n.dataset && n.dataset.mtLayer === layerId).length;
  }
  function halfLayers() {
    return layerCount("mt1", "b0:text") + layerCount("mt2", "c0:text");
  }

  // 接缝层随后失效（两张切片不再相接 = 用户滚动/重排后的真实情形）
  second._setRect({ left: 0, top: 1400, width: 400, height: 1000 });
  const invalidated = JSON.parse(page.Mt.applySeamSceneJson(JSON.stringify(seamPayload)));
  check("seam dispatch is refused once the slices no longer abut",
    invalidated.ok === false && invalidated.reason === "page_gap", invalidated);
  check("the two halves come back after the seam layer is invalidated",
    halfLayers() === 2, halfLayers());

  // 恢复是幂等的：再接一次失败的下发不会贴出第二份。
  page.Mt.applySeamSceneJson(JSON.stringify(seamPayload));
  check("restoring halves is idempotent", halfLayers() === 2, halfLayers());

  // 「原文」视图：登记表清空，不再误贴回已经卸掉的层里。
  page.Mt.removeScenes();
  check("removeScenes clears suppressed record",
    page.Mt._suppressedHalves().length === 0, page.Mt._suppressedHalves().length);
})();

// ---------------------------------------------------------------------------
// §15 虚拟化阅读器：元素被回收给别的图 → 旧译文必须整体卸掉（2026-10-03）
//
// 用户反馈「有时候不属于这个界面的译文会出现」。真机（Lezhin）日志显示阅读器会**复用 `<img>`**：
// 元素还在 DOM 里、`isConnected` 为真，内容已经换成别的页 → 只认元素的覆盖层就把上一页的译文
// 留在新画面上；而「接缝失效把两半贴回来」的恢复逻辑会把它放大。
// ---------------------------------------------------------------------------
(function staleTargetIsDropped() {
  const page = freshPage();
  // 绑定前先让这张图有内容地址（真实页面一定先有 src，才有必要记它）
  page.image.setAttribute("src", "https://cdn.example.com/page1.jpg");
  const scene = JSON.stringify({
    v: 1,
    kind: "scene",
    image_id: "mt1",
    cleaned_url: null,
    bubbles: [bubble({ id: "b0", x: 10, y: 10, w: 40, h: 20 })]
  });
  check("scene applies with a recorded target src",
    JSON.parse(page.Mt.applySceneJson(scene)).ok === true && page.Mt._overlays.has("mt1"));

  // 阅读器把这个元素回收给了别的页
  page.image.setAttribute("src", "https://cdn.example.com/page9.jpg");
  // 触发一次位置同步（applyCleanedImage 内部会 syncOverlayPosition）
  page.Mt.applyCleanedImage("mt1", "https://mtlocal.overlay/cleaned.jpg");

  const entry = page.Mt._overlays.get("mt1");
  check("stale overlay is dropped when the target image content changes",
    !entry || !entry.root || !entry.root.isConnected,
    entry ? "still registered" : "unregistered");
  check("stale scene root is removed from the DOM",
    page.document.querySelectorAll(".__mt_scene_root").length === 0,
    page.document.querySelectorAll(".__mt_scene_root").length);
})();

// ---------------------------------------------------------------------------
// §16 场景重建后「被接缝层顶掉的半块」必须继续被顶掉（2026-10-14）
//
// 用户反馈：「向下划后再次向上划的时候可能会出现重复的译文」。
//
// 现场链条：懒加载站点滚动时把 `<img>` 换成新 blob URL（内容不变）→ 会话按内容指纹复用
// 场景重新下发 → `applyScene` 建新 root，而被 `suppressBubble` 摘掉的那半块又回来了；
// 接缝层还活着 → 同一句台词画两遍（一份接缝带、一份半块）。
// 修复：同名 `data-mt-layer` 的抑制登记在重建时**换绑**到新 root，继续摘掉；
// 接缝层一旦失效（restoreSeamHalves）仍能把它们贴回新 root。
// ---------------------------------------------------------------------------
(function suppressionSurvivesSceneRebuild() {
  const page = freshPage();
  function sceneWith(cleanedUrl) {
    return JSON.stringify({
      v: 1,
      kind: "scene",
      image_id: "mt1",
      cleaned_url: cleanedUrl,
      bubbles: [
        bubble({ id: "b0", x: 10, y: 80, w: 50, h: 25, stitch_overflow: true }),
        bubble({ id: "b1", x: 10, y: 10, w: 40, h: 20 })
      ]
    });
  }
  function layersOf(layerId) {
    const entry = page.Mt._overlays.get("mt1");
    const kids = (entry && entry.root && entry.root.children) || [];
    return kids.filter((n) => n.dataset && n.dataset.mtLayer === layerId).length;
  }

  check("scene applies", JSON.parse(page.Mt.applySceneJson(sceneWith(null))).ok === true);
  check("both bubbles are on screen", layersOf("b0:text") === 1 && layersOf("b1:text") === 1);

  // 宿主摘掉上半块（接缝层生效时的正常动作）。
  const removed = page.Mt.suppressBubble("mt1", "b0");
  check("half is suppressed", removed > 0 && layersOf("b0:text") === 0, removed);

  // 站点换图（新 blob URL）→ 同一份场景换一个清理图 URL 重新下发 = 触发重建。
  const rebuilt = JSON.parse(page.Mt.applySceneJson(sceneWith("https://mtlocal.overlay/cleaned-b.jpg")));
  check("scene is rebuilt on a changed signature", rebuilt.ok === true && rebuilt.rebuilt === true, rebuilt);
  check("rebuilt scene reports the suppressed bubble", rebuilt.suppressed === 1, rebuilt);
  check("suppressed half stays off screen after the rebuild",
    layersOf("b0:text") === 0 && layersOf("b0:cover") === 0,
    [layersOf("b0:text"), layersOf("b0:cover")]);
  check("the other bubble is still there", layersOf("b1:text") === 1);

  // 接缝层失效（两张切片不再相接）→ 那半块必须能贴回**新** root（换绑后仍有效）。
  page.Mt.restoreSeamHalves("mt1|mt2");
  check("restore brings the half back into the rebuilt root",
    layersOf("b0:text") === 1 && layersOf("b0:cover") === 1,
    [layersOf("b0:text"), layersOf("b0:cover")]);
  check("suppression registry is drained after restore",
    page.Mt._suppressedHalves().length === 0, page.Mt._suppressedHalves().length);
})();

// ---------------------------------------------------------------------------
// 像素感知均衡断行（2026-10-14）：纯函数表驱动 —— 词边界 / 标点禁则 / 均衡 / 行数
// ---------------------------------------------------------------------------
(function balancedLineBreaking() {
  const page = freshPage();
  const Mt = page.Mt;

  // 注入式分词与测宽（与 WebView 环境无关）：`|` 分隔词，每字符宽 10。
  const measure = (text) => Array.from(String(text || "")).length * 10;
  const tokenize = (text) => String(text || "").split("|").filter(Boolean)
    .map((word) => ({ text: word, sepAfter: "", wordLike: true }));
  const breakRows = (units, widths, lines, maxWidth) =>
    Mt._breakBalancedLines(
      units.map((text) => ({ text, sepAfter: "", wordLike: true })),
      widths,
      lines,
      maxWidth
    );

  // 1) 单元（词）永不被劈开：每行都是连续单元的拼接。
  const units = ["一二三", "四五", "六七", "八九", "十零一"];
  const widths = units.map((u) => u.length * 10);
  const rows = breakRows(units, widths, 2, 1000);
  check("balanced break returns the requested line count", rows && rows.length === 2, rows);
  const rebuilt = rows ? rows.join("") : "";
  check("balanced break never splits a unit", rebuilt === units.join(""), rows);

  // 2) 行首禁则：收尾标点不许起行（即使均分更划算）。
  const punctRows = breakRows(["一二三四五", "！", "六七八九十"], [50, 10, 50], 2, 1000);
  check("closing punctuation never starts a line",
    punctRows && punctRows[1].charAt(0) !== "！", punctRows);

  // 3) 标点优先：能断在逗号之后就不在别处断。
  const commaRows = breakRows(["今天天气很好", "，", "我们一起出去玩吧"], [60, 10, 90], 2, 1000);
  check("break prefers right after punctuation",
    commaRows && commaRows[0].charAt(commaRows[0].length - 1) === "，", commaRows);

  // 4) 均衡：11 个同宽字断 2 行 → 6/5，不允许 10/1。
  const eleven = Array.from({ length: 11 }, (_, i) => "字" + i);
  const balanced = breakRows(eleven, eleven.map(() => 10), 2, 1000);
  check("balanced break avoids a one-character last line",
    balanced && Array.from(balanced[1]).length >= 4, balanced);

  // 5) 行数不足/已含换行/单行目标 → 组合入口返回 null（调用方保持原文本）。
  check("formatBalancedLines rejects line count <= 1",
    Mt._formatBalancedLines("顶部哦我讨厌它", 1, 500, measure, tokenize) === null);
  check("formatBalancedLines rejects text with hard lines",
    Mt._formatBalancedLines("已有\n换行", 2, 500, measure, tokenize) === null);

  // 6) 组合入口：词边界保持（不劈开「讨厌」）——用真实分词器（WebView/Node 都有 Intl.Segmenter）。
  if (typeof Intl !== "undefined" && typeof Intl.Segmenter === "function") {
    const realText = "顶部!?哦一我讨厌它！不要吓太";
    const formatted = Mt._formatBalancedLines(realText, 2, 220, measure);
    const lines = formatted ? formatted.split("\n") : [];
    check("real segmenter keeps words intact",
      lines.length === 2 && !lines.some((line) => /讨$/.test(line) || /^厌/.test(line)),
      formatted);
  } else {
    check("real segmenter keeps words intact (skipped: no Intl.Segmenter)", true);
  }

  // 7) 贪心行数统计（重排必须保持行数不变）。
  check("countGreedyLines counts hard lines and wraps",
    Mt._countGreedyLines("一二三|四五六|七八九十", 50, measure, tokenize) === 3, null);
})();


const total = passed + failures.length;
if (failures.length > 0) {
  console.log("FAILED " + failures.length + "/" + total);
  failures.forEach((line) => console.log("  FAIL " + line));
  process.exit(1);
}
console.log("ALL_OK " + passed + "/" + total);
