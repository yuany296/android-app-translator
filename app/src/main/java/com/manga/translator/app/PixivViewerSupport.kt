package com.manga.translator.app

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Pixiv `comic-store-viewer` 页会话支持（2026-11-01）。
 *
 * 背景：Pixiv 漫画阅读器把页图画在 `<canvas>` 里，CDN 返回的 JPEG 是**32px 块打乱**的拼图；
 * 直接下载只能得到碎片。但阅读器自己有两条可用的像素通道：
 *
 * 1. 当前可见页：`rendererObj.currentScreen.canvas` 里是**已还原**的像素，`toDataURL` 可导出；
 * 2. 下一页：`rendererObj.renderNextLeft()` 会把下一页画进隐藏的 `o5x` 缓冲，**不改可见页**。
 *    实测真正翻页后，`o5x` 的裁剪结果与可见 canvas **逐字节一致**。
 *
 * 所以这里不做任何打乱算法逆向、也不下载 CDN 原图：只让阅读器自己把「当前页/下一页」
 * 画好，我们按 `leftDrawnRect` 裁出页图区域，交给现有 OCR 管线。
 */
internal data class PixivViewerRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    fun isValid(): Boolean = width > 1f && height > 1f
}

/** 阅读器状态：页号、总页数、下一页是否已解码、当前页 canvas 与页图绘制区域。 */
internal data class PixivViewerProbe(
    val page: Int,
    val total: Int,
    val hasNext: Boolean,
    val nextReady: Boolean,
    val isDoublePage: Boolean,
    /** 当前内容 id（换章节时变）：页缓存不能跨内容复用。 */
    val contentKey: String,
    val canvasWidth: Int,
    val canvasHeight: Int,
    /** `leftDrawnRect`：页图在 canvas 内部像素坐标里的位置。 */
    val drawnRect: PixivViewerRect,
    /** `canvas.getBoundingClientRect()`（CSS 视口坐标）。 */
    val canvasRect: PixivViewerRect,
    /** 当前页 screen 的 DOM id（`viewport1` / `viewport0`）；阅读器翻页时会换，不能写死。 */
    val viewportId: String,
    /** 当前页 screen 的 CSS 视口坐标；旧版本取不到时为 null。 */
    val viewportRect: PixivViewerRect?
)

/**
 * 从页面里抓到的整页图（已按页图绘制区域裁掉黑边）。
 *
 * [blank] = 纯白/纯黑页（封面、扉页、章节间隔），没有可翻译内容；此时 [bitmap] 为 null，
 * 页会话直接写空场景，不再重试。
 */
internal data class PixivViewerFrame(
    val page: Int,
    val bitmap: Bitmap?,
    val width: Int,
    val height: Int,
    val drawnRect: PixivViewerRect,
    val source: String,
    val blank: Boolean = false
)

/** 覆盖层锚点在 `#viewport1` 内的 CSS 坐标（左上角 + 尺寸）。 */
internal data class PixivAnchorRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

internal object PixivViewerSupport {
    private const val TAG = "EmbeddedBrowser"
    private const val CHUNK_CHARS = 350_000
    private const val CAPTURE_TIMEOUT_MS = 5_000L

    /** 只认 Pixiv 的漫画查看器；`www.pixiv.net` 的插画页不走这条会话。 */
    fun isViewerUrl(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return false
        return runCatching {
            // 用 java.net.URI 而不是 android.net.Uri：Uri 在 JVM 单测里是桩实现，
            // 会把 URL 判定测成恒 false；java.net.URI 在 Android 与 JVM 上都可用。
            val uri = java.net.URI(raw)
            val host = uri.host?.lowercase().orEmpty()
            host == "comic-store-viewer.pixiv.net" &&
                uri.path.orEmpty().contains("/static/viewer")
        }.getOrDefault(false)
    }

    suspend fun probe(webView: WebView): PixivViewerProbe? {
        val raw = webView.evaluateJavascriptSuspend(
            PixivViewerScript.PROBE_JS,
            what = "pixiv_probe"
        )
        val json = decodeJsJsonResult(raw)
        val probe = parseProbe(json)
        if (probe == null) {
            Log.w(TAG, "pixiv_probe fail reason=${json?.optString("reason").orEmpty().ifBlank { "bad_result" }}")
        } else {
            // 探测每 ~300ms 一次，成功不打 D 级日志（会刷屏）；排查时用
            // `adb logcat -s EmbeddedBrowser:V` 仍能看到完整几何。
            Log.v(
                TAG,
                "pixiv_probe ok page=${probe.page} total=${probe.total} next=${probe.hasNext} " +
                    "next_ready=${probe.nextReady} double=${probe.isDoublePage} " +
                    "canvas=${probe.canvasWidth}x${probe.canvasHeight} " +
                    "drawn=${probe.drawnRect.width.roundToInt()}x${probe.drawnRect.height.roundToInt()}"
            )
        }
        return probe
    }

    suspend fun captureCurrent(webView: WebView, expectedPage: Int): PixivViewerFrame? =
        capture(webView, mode = "current", expectedPage = expectedPage)

    suspend fun captureNext(webView: WebView, expectedPage: Int): PixivViewerFrame? =
        capture(webView, mode = "next", expectedPage = expectedPage)

    private suspend fun capture(
        webView: WebView,
        mode: String,
        expectedPage: Int
    ): PixivViewerFrame? {
        val start = decodeJsJsonResult(
            webView.evaluateJavascriptSuspend(
                PixivViewerScript.captureStartJs(mode),
                timeoutMs = CAPTURE_TIMEOUT_MS,
                what = "pixiv_capture_start_$mode"
            )
        )
        if (start == null || !start.optBoolean("ok")) {
            Log.d(
                TAG,
                "pixiv_capture mode=$mode skip reason=" +
                    (start?.optString("reason").orEmpty().ifBlank { "bad_result" })
            )
            return null
        }
        val page = start.optInt("page", -1)
        if (page != expectedPage) {
            Log.d(TAG, "pixiv_capture mode=$mode skip page=$page expected=$expectedPage")
            return null
        }
        val width = start.optInt("w", 0)
        val height = start.optInt("h", 0)
        val drawn = rectOf(start.optJSONObject("rect"))
        if (width <= 1 || height <= 1 || drawn == null) {
            Log.d(TAG, "pixiv_capture mode=$mode page=$page skip=bad_size")
            return null
        }
        if (start.optBoolean("blank")) {
            // 纯白/纯黑页：标成「成功但无内容」，页会话写空场景，避免每 800ms 重试。
            Log.d(TAG, "pixiv_capture mode=$mode page=$page blank")
            return PixivViewerFrame(
                page = page,
                bitmap = null,
                width = width,
                height = height,
                drawnRect = drawn,
                source = mode,
                blank = true
            )
        }
        val len = start.optInt("len", 0)
        if (len <= 0) {
            Log.d(TAG, "pixiv_capture mode=$mode page=$page skip=empty")
            return null
        }

        val builder = StringBuilder(len)
        var offset = 0
        while (offset < len) {
            val chunk = decodeJsJsonResult(
                webView.evaluateJavascriptSuspend(
                    PixivViewerScript.captureChunkJs(offset, CHUNK_CHARS),
                    timeoutMs = CAPTURE_TIMEOUT_MS,
                    what = "pixiv_capture_chunk_$mode"
                )
            )
            if (chunk == null || !chunk.optBoolean("ok")) {
                Log.w(
                    TAG,
                    "pixiv_capture mode=$mode page=$page fail=chunk at=$offset " +
                        "reason=${chunk?.optString("reason").orEmpty()}"
                )
                return null
            }
            val text = chunk.optString("chunk").orEmpty()
            builder.append(text)
            val next = chunk.optInt("next", offset + CHUNK_CHARS)
            if (chunk.optBoolean("done") || next <= offset || text.isEmpty()) break
            offset = next
        }
        if (builder.isEmpty()) return null

        val bitmap = runCatching {
            decodeCapped(Base64.decode(builder.toString(), Base64.DEFAULT))
        }.getOrNull()
        if (bitmap == null) {
            Log.w(TAG, "pixiv_capture mode=$mode page=$page fail=decode")
            return null
        }
        Log.d(
            TAG,
            "pixiv_capture mode=$mode page=$page w=${bitmap.width} h=${bitmap.height} " +
                "b64=$len drawn=${drawn.width.roundToInt()}x${drawn.height.roundToInt()}"
        )
        return PixivViewerFrame(
            page = page,
            bitmap = bitmap,
            width = bitmap.width,
            height = bitmap.height,
            drawnRect = drawn,
            source = mode
        )
    }

    /**
     * 锚点位置：页图绘制区域从 canvas 内部像素坐标换到 `#viewport1` 的 CSS 坐标。
     *
     * 场景里的百分比坐标是相对**裁剪后的页图**，所以锚点必须正好盖住页图区域，
     * 不能盖整个 canvas（否则上下黑边会被算进高度，气泡整体偏小/偏上）。
     */
    internal fun anchorRect(probe: PixivViewerProbe): PixivAnchorRect? {
        if (probe.canvasWidth <= 1 || probe.canvasHeight <= 1) return null
        if (!probe.canvasRect.isValid() || !probe.drawnRect.isValid()) return null
        val scaleX = probe.canvasRect.width / probe.canvasWidth
        val scaleY = probe.canvasRect.height / probe.canvasHeight
        if (!scaleX.isFinite() || !scaleY.isFinite() || scaleX <= 0f || scaleY <= 0f) return null
        val viewportX = probe.viewportRect?.x ?: 0f
        val viewportY = probe.viewportRect?.y ?: 0f
        val left = (probe.canvasRect.x - viewportX) + probe.drawnRect.x * scaleX
        val top = (probe.canvasRect.y - viewportY) + probe.drawnRect.y * scaleY
        val width = probe.drawnRect.width * scaleX
        val height = probe.drawnRect.height * scaleY
        if (!left.isFinite() || !top.isFinite() || width <= 1f || height <= 1f) return null
        return PixivAnchorRect(left, top, width, height)
    }

    internal fun parseProbe(json: JSONObject?): PixivViewerProbe? {
        if (json == null || !json.optBoolean("ok")) return null
        val page = json.optInt("page", -1)
        val total = json.optInt("total", 0)
        val canvasWidth = json.optInt("cw", 0)
        val canvasHeight = json.optInt("ch", 0)
        val drawn = rectOf(json.optJSONObject("dr")) ?: return null
        val canvas = rectOf(json.optJSONObject("cr")) ?: return null
        if (page < 0 || total <= 0 || canvasWidth <= 1 || canvasHeight <= 1) return null
        if (!drawn.isValid() || !canvas.isValid()) return null
        return PixivViewerProbe(
            page = page,
            total = total,
            hasNext = json.optBoolean("hasNext", page + 1 < total),
            nextReady = json.optBoolean("nextReady", false),
            isDoublePage = json.optBoolean("isDouble", false),
            contentKey = json.optString("cid").orEmpty(),
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            drawnRect = drawn,
            canvasRect = canvas,
            viewportId = json.optString("vpid").ifBlank { "viewport1" },
            viewportRect = rectOf(json.optJSONObject("vp"))
        )
    }

    internal fun rectOf(json: JSONObject?): PixivViewerRect? {
        if (json == null) return null
        val width = json.optDouble("w", -1.0).toFloat()
        val height = json.optDouble("h", -1.0).toFloat()
        if (!width.isFinite() || !height.isFinite()) return null
        return PixivViewerRect(
            x = json.optDouble("x", 0.0).toFloat(),
            y = json.optDouble("y", 0.0).toFloat(),
            width = width,
            height = height
        )
    }

    /**
     * 淘汰场景时释放它的清理图/贴片 token。
     *
     * 与 [BrowserOverlayImageStore] 的 96MB LRU 配合：LRU 只是最后一道保险，
     * 页会话自己淘汰时必须主动删，否则当前页的清理图可能被后面的页挤掉。
     */
    fun releaseSceneImages(scene: BrowserSceneBuilder.Scene) {
        sequenceOf(scene.cleanedUrl, scene.pageCleanedUrl).forEach { url ->
            tokenOf(url)?.let(BrowserOverlayImageStore::remove)
        }
        scene.bubbles.forEach { bubble ->
            tokenOf(bubble.patchUrl)?.let(BrowserOverlayImageStore::remove)
        }
    }

    internal fun tokenOf(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (!value.startsWith("https://mtlocal.overlay/")) return null
        return value.substringAfterLast('/').substringBefore('.').ifBlank { null }
    }

    /**
     * 只保留**真的被翻译过**的气泡。
     *
     * 为什么需要：云端/本地翻译对短标题、专有名词、拟声词可能原样返回；场景仍会画
     * 盖板把原文盖掉，结果就是「原文没了、译文还是原文或空白」——TOC/扉页尤其明显
     * （Pixiv 第 4 页实测：22 个标题全部原样返回，盖板是灰色、字是深灰，看起来像
     * 一排灰块）。这里把 `translatedText == originalText` 的气泡剔除，原文保持可见。
     *
     * 注意：只用于 Pixiv 页会话，不改韩漫链路的既有行为。
     */
    fun filterTranslatedScene(scene: BrowserSceneBuilder.Scene): BrowserSceneBuilder.Scene {
        val kept = scene.bubbles.filterNot { bubble ->
            normalizeForCompare(bubble.originalText) == normalizeForCompare(bubble.translatedText)
        }
        if (kept.size == scene.bubbles.size) return scene
        return scene.copy(bubbles = kept)
    }

    private fun normalizeForCompare(text: String): String =
        text.trim().replace(Regex("\\s+"), " ")

    fun anchorJs(anchorId: String, probe: PixivViewerProbe): String? {
        val rect = anchorRect(probe) ?: return null
        return """(function(){
  var old=document.querySelector('[data-mt-id="$anchorId"]');
  if(old&&old.parentNode)old.parentNode.removeChild(old);
  var host=document.getElementById('${probe.viewportId}')||document.querySelector('.currentScreen')||document.body;
  var a=document.createElement('div');
  a.setAttribute('data-mt-id','$anchorId');
  a.className='__mt_overlay';
  a.style.cssText='position:absolute;left:${rect.left}px;top:${rect.top}px;width:${rect.width}px;height:${rect.height}px;pointer-events:none;visibility:hidden;';
  host.appendChild(a);
  return JSON.stringify({ok:true,host:(host.id||host.className||host.tagName),left:${rect.left},top:${rect.top},w:${rect.width},h:${rect.height}});
})()"""
    }

    fun removeSceneJs(anchorId: String): String = """(function(){
  var a=document.querySelector('[data-mt-id="$anchorId"]');
  if(a&&a.parentNode)a.parentNode.removeChild(a);
  try{if(window.MtOverlay&&MtOverlay.removeScene){MtOverlay.removeScene('$anchorId');}}catch(e){}
  return 1;
})()"""

    fun noTouchJs(anchorId: String): String =
        "((function(){if(!window.MtOverlay||!MtOverlay.noTouch)return -1;" +
            "return MtOverlay.noTouch('$anchorId');})())"
}

/** Pixiv 阅读器页会话的页面侧脚本。 */
internal object PixivViewerScript {

    /**
     * 读阅读器状态。路径用的是当前版本（`viewer_loader_1.0.0_2024-04-10.js`）的
     * `NFBR.a6G.Initializer.F7F.menu.{model,options}`；取不到就返回 `no_viewer`，
     * 由上层回退截图链路。
     */
    val PROBE_JS: String = """(function(){
  try {
    var N = window.NFBR || {};
    var I = N.a6G && N.a6G.Initializer;
    var O = I && I.F7F && I.F7F.menu && I.F7F.menu.options;
    var M = I && I.F7F && I.F7F.menu && I.F7F.menu.model && I.F7F.menu.model.attributes;
    var R = O && O.a6l && O.a6l.rendererObj;
    if (!M || !R) return JSON.stringify({ok:false,reason:'no_viewer'});
    var CS = R.currentScreen;
    var C = CS && CS.canvas;
    var DR = CS && CS.leftDrawnRect;
    if (!C || !DR || !(C.width > 1) || !(C.height > 1)) {
      return JSON.stringify({ok:false,reason:'no_screen'});
    }
    if (!(DR.width > 1) || !(DR.height > 1)) {
      return JSON.stringify({ok:false,reason:'no_drawn_rect'});
    }
    var CR = C.getBoundingClientRect();
    var VP = CS.viewport && CS.viewport.getBoundingClientRect ? CS.viewport.getBoundingClientRect() : null;
    var total = (M.a6v && M.a6v.a6F && M.a6v.a6F.X9U) || 0;
    var page = typeof M.viewerPage === 'number' ? M.viewerPage : -1;
    var next = M.a2I && M.a2I.attributes ? M.a2I.attributes : null;
    var nextReady = !!(next && next.image && next.image.bitmap && next.image.bitmap.width > 0);
    var spread = M.viewerSpread || {};
    var isDouble = !!spread.isDoublePage || !!(spread.right && spread.right.pageIndex >= 0);
    return JSON.stringify({
      ok:true,
      page:page,
      total:total,
      hasNext:page >= 0 && page + 1 < total,
      nextReady:nextReady,
      isDouble:isDouble,
      cid:String(M.contentId || ''),
      cw:C.width,
      ch:C.height,
      vpid:(CS.viewport && CS.viewport.id) ? CS.viewport.id : 'viewport1',
      dr:{x:DR.x,y:DR.y,w:DR.width,h:DR.height},
      cr:{x:CR.left,y:CR.top,w:CR.width,h:CR.height},
      vp:VP ? {x:VP.left,y:VP.top,w:VP.width,h:VP.height} : null
    });
  } catch (e) {
    return JSON.stringify({ok:false,reason:String(e && e.name || e)});
  }
})()"""

    /**
     * 开始一次抓图：按 `leftDrawnRect` 把页图区域画进临时 canvas，PNG dataURL 存进
     * `window.__mtPixivCaptureStore`，只回传元数据（base64 分片取，避免一次顶爆 IPC）。
     *
     * `next` 模式会先调 `rendererObj.renderNextLeft()`：它只画隐藏的 `o5x`，
     * **不改可见页**。实测翻页后可见 canvas 与这里抓到的缓冲逐字节相同。
     */
    fun captureStartJs(mode: String): String {
        val m = if (mode == "next") "next" else "current"
        return """(function(){
  try {
    var N = window.NFBR || {};
    var I = N.a6G && N.a6G.Initializer;
    var O = I && I.F7F && I.F7F.menu && I.F7F.menu.options;
    var M = I && I.F7F && I.F7F.menu && I.F7F.menu.model && I.F7F.menu.model.attributes;
    var R = O && O.a6l && O.a6l.rendererObj;
    if (!M || !R) return JSON.stringify({ok:false,reason:'no_viewer'});
    var mode = '$m';
    if (mode === 'next') {
      if (typeof R.renderNextLeft !== 'function') {
        return JSON.stringify({ok:false,reason:'no_render_next'});
      }
      var next = M.a2I && M.a2I.attributes ? M.a2I.attributes : null;
      if (!next || !next.image || !next.image.bitmap) {
        return JSON.stringify({ok:false,reason:'next_not_ready'});
      }
      R.renderNextLeft();
    }
    var S = mode === 'next' ? R.o5x : R.currentScreen;
    var C = S && S.canvas;
    var DR = S && S.leftDrawnRect;
    if (!C || !DR || !(DR.width > 1) || !(DR.height > 1)) {
      return JSON.stringify({ok:false,reason:'no_drawn_rect'});
    }
    var w = Math.round(DR.width);
    var h = Math.round(DR.height);
    var out = document.createElement('canvas');
    out.width = w;
    out.height = h;
    var g = out.getContext('2d');
    g.drawImage(C, DR.x, DR.y, DR.width, DR.height, 0, 0, w, h);
    var min = 255;
    var max = 0;
    var sampled = false;
    try {
      var img = g.getImageData(0, 0, w, h).data;
      var step = Math.max(8, Math.floor(Math.sqrt(w * h / 4096)));
      for (var y = 0; y < h; y += step) {
        for (var x = 0; x < w; x += step) {
          var i = (y * w + x) * 4;
          var l = (img[i] * 299 + img[i + 1] * 587 + img[i + 2] * 114) / 1000;
          if (l < min) min = l;
          if (l > max) max = l;
        }
      }
      sampled = true;
    } catch (e) {}
    // 纯白/纯黑页（封面、扉页、章节间隔）不生成大 base64，直接标 blank。
    var blank = sampled && (max - min) < 24;
    var data = '';
    if (!blank) {
      var url = out.toDataURL('image/png');
      data = url.substring(url.indexOf(',') + 1);
    }
    var page = (typeof M.viewerPage === 'number') ? M.viewerPage : -1;
    if (mode === 'next') page = page + 1;
    window.__mtPixivCaptureStore = {
      data: data,
      w: w,
      h: h,
      page: page,
      rect: {x:DR.x,y:DR.y,w:DR.width,h:DR.height},
      blank: blank
    };
    return JSON.stringify({
      ok:true,
      page:page,
      w:w,
      h:h,
      len:data.length,
      blank:blank,
      rect:{x:DR.x,y:DR.y,w:DR.width,h:DR.height}
    });
  } catch (e) {
    return JSON.stringify({ok:false,reason:String(e && e.name || e)});
  }
})()"""
    }

    fun captureChunkJs(offset: Int, len: Int): String = """(function(){
  var e = window.__mtPixivCaptureStore;
  if (!e) return JSON.stringify({ok:false,reason:'no_capture'});
  var at = $offset;
  var size = $len;
  var chunk = e.data.substr(at, size);
  return JSON.stringify({
    ok:true,
    chunk:chunk,
    next:at + chunk.length,
    done:at + chunk.length >= e.data.length
  });
})()"""
}
