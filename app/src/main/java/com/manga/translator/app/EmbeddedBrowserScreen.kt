package com.manga.translator.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.manga.translator.app.ui.MtArrowRightIcon
import com.manga.translator.app.ui.MtBrowserIcon
import com.manga.translator.app.ui.MtChevronLeftIcon
import com.manga.translator.app.ui.MtDropdownMenu
import com.manga.translator.app.ui.MtListIcon
import com.manga.translator.app.ui.MtPrimaryButton
import com.manga.translator.app.ui.MtSearchIcon
import com.manga.translator.app.ui.theme.MtColors
import com.manga.translator.store.BrowserHistoryEntry
import com.manga.translator.store.BrowserFavoriteRepository
import com.manga.translator.store.BrowserFavorite
import com.manga.translator.store.BrowserHistoryRepository
import com.manga.translator.store.BrowserSearchEngine
import com.manga.translator.store.BrowserSiteAdapter
import com.manga.translator.store.BrowserSiteAdapterRegistry
import com.manga.translator.store.BrowserUrlResolver
import com.manga.translator.store.FavoriteAddResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

// ---------------------------------------------------------------------------
// 译文图内存仓：Kotlin 渲染好的整图 PNG 放这里，WebView 通过拦截
// https://mtlocal.overlay/<token>.png 取图，避免巨大的 base64 走 JS 桥。
// 覆盖层渲染模式下还承载：整页清理图（JPEG）与跨图续排擦字贴片（PNG）。
// ---------------------------------------------------------------------------

object BrowserOverlayImageStore {
    private const val MAX_TOTAL_BYTES = 96L * 1024 * 1024
    private class Entry(val bytes: ByteArray, val mime: String)
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, false)
    private var totalBytes = 0L

    fun put(token: String, bytes: ByteArray, mime: String = "image/png") {
        synchronized(lock) {
            entries.remove(token)?.let { totalBytes -= it.bytes.size }
            entries[token] = Entry(bytes, mime)
            totalBytes += bytes.size
            while (totalBytes > MAX_TOTAL_BYTES && entries.isNotEmpty()) {
                val iterator = entries.entries.iterator()
                val first = iterator.next()
                totalBytes -= first.value.bytes.size
                iterator.remove()
            }
        }
    }

    fun get(token: String): ByteArray? = synchronized(lock) { entries[token]?.bytes }

    fun mimeOf(token: String): String? = synchronized(lock) { entries[token]?.mime }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            totalBytes = 0L
        }
    }
}

/** 漫画图下载器：带 WebView 的 Cookie / Referer / UA，尽量贴近页面自身的取图行为。 */
class BrowserImageDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        // 本机/内网地址绕开系统 HTTP 代理。
        // 为什么必须这样（2026-09-29 实测）：设备开着系统代理（如模拟器的 10.0.2.2:7890）时，
        // **WebView 对 loopback 自带 bypass 所以页面能正常打开**，但 OkHttp 默认走系统代理，
        // 代理连不到设备上的 localhost → `download_http code=502` → `image_fail` →
        // 表现为「页面好好的，点翻译什么都不发生」。Chrome 同样 bypass loopback，这里对齐它。
        // 真机上的漫画站点不受影响（照常走系统代理）。
        .proxySelector(LoopbackBypassProxySelector())
        .build()

    fun download(url: String, referer: String?, userAgent: String?): ByteArray? {
        return runCatching {
            val builder = Request.Builder().url(url)
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            userAgent?.takeIf { it.isNotBlank() }?.let { builder.header("User-Agent", it) }
            referer?.takeIf { it.isNotBlank() }?.let { builder.header("Referer", it) }
            CookieManager.getInstance().getCookie(url)?.let { builder.header("Cookie", it) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "download_http code=${resp.code} url=${BrowserHistoryRepository.urlWithoutScheme(url)}")
                    return null
                }
                resp.body?.bytes()
            }
        }.getOrElse { err ->
            // 下载失败是「图没翻出来」最常见的原因，保留原因便于排查
            // （模拟器/真机上的系统 HTTP 代理是最常见的一条，2026-09-28 实测）。
            Log.w(
                TAG,
                "download_fail reason=${err.javaClass.simpleName}:${err.message?.take(60)} " +
                    "url=${BrowserHistoryRepository.urlWithoutScheme(url)}"
            )
            null
        }
    }
}

/**
 * 代理选择器：对 loopback / 本机地址返回 `Proxy.NO_PROXY`，其余交给系统默认。
 *
 * 与 Chrome 的行为对齐 —— Chromium 隐式 bypass localhost，所以内嵌 WebView 能打开本机测试页，
 * 而默认走系统代理的 OkHttp 会拿到 502。这个差异曾经让「页面能开、点翻译没反应」很难查
 * （2026-09-29 用户反馈「怎么翻译不了了」就是这么来的）。
 */
private class LoopbackBypassProxySelector : java.net.ProxySelector() {
    private val delegate = java.net.ProxySelector.getDefault()

    override fun select(uri: java.net.URI?): MutableList<java.net.Proxy> {
        val host = uri?.host?.lowercase().orEmpty()
        if (isLocalHost(host)) {
            return mutableListOf(java.net.Proxy.NO_PROXY)
        }
        val fallback = runCatching { delegate?.select(uri) }.getOrNull()
        return fallback ?: mutableListOf(java.net.Proxy.NO_PROXY)
    }

    override fun connectFailed(
        uri: java.net.URI?,
        sa: java.net.SocketAddress?,
        ioe: java.io.IOException?
    ) {
        runCatching { delegate?.connectFailed(uri, sa, ioe) }
    }

    private fun isLocalHost(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".localhost") || host == "::1" || host == "[::1]") {
            return true
        }
        // 127.0.0.0/8
        if (host == "127.0.0.1" || host.startsWith("127.")) return true
        // 10.0.2.2 = Android 模拟器里的宿主机；10.0.2.15 = 模拟器自身
        if (host == "10.0.2.2" || host == "10.0.2.15") return true
        return false
    }
}

private data class ScannedImage(
    val id: String,
    val src: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    /**
     * 图片的**定位宿主** id（`BrowserScanScript` 给可滚动祖先打的 `data-mt-host`）。
     *
     * 空串 = 宿主就是 `documentElement`（普通 window 滚动页面）。图片放在
     * `overflow:auto` 容器里时非空 —— 整图覆盖层必须贴进同一个宿主，
     * 才能和图片共享滚动上下文（否则滚动时覆盖层留在原地、图文分家）。
     */
    val hostId: String = "",
    /** 宿主自身的视口左上角；整图覆盖层按它把视口坐标换成容器内坐标。 */
    val hostX: Int = 0,
    val hostY: Int = 0,
    /** iframe 序号（0 = 顶层）；>0 表示图在子文档里（坐标已换算到顶层视口）。 */
    val frame: Int = 0,
    /** 懒加载尚未触发（图还没真正下载）：尺寸取自元素盒子而不是原始像素。 */
    val lazy: Boolean = false,
    /** 被站点适配器的显式选择器命中（诊断用）。 */
    val explicit: Boolean = false,
    /**
     * 图片来源类型（脚本判定的）。
     *
     * - `url` —— 普通 http(s)，Kotlin 侧 OkHttp 直接下载；
     * - `blob` —— 站点在 JS 里造的对象 URL（Kakao Page / Lezhin / 哔哩哔哩漫画）；
     * - `data` —— 内联 base64（少见，但一些站点会把小图内联）。
     *
     * `blob` 只在本页 origin 内有效，OkHttp 下载不了，必须走「让页面把像素交出来」
     * 的 [BrowserScanScript.EXTRACTOR_JS] 路径。
     */
    val kind: String = "url"
) {
    /** 同一 src 换尺寸（占位图 → 真图）时算不同 key，允许重新走一次翻译。 */
    fun attemptKey(): String = "$src#${width}x$height"

    /** 几何子集 → 跨切片相邻性判定用（[SeamNeighbourPolicy]，纯 JVM 可测）。 */
    fun toStripBox(): StripBox = StripBox(
        src = src,
        left = left,
        top = top,
        width = width,
        height = height,
        hostId = hostId,
        frame = frame
    )
}

/**
 * 一次扫描的结果：图列表 + 站点适配诊断。
 *
 * [explicitHits] 为 0 说明这个站的显式选择器一条都没命中（站点改版 / 还没配），
 * 此时脚本靠通用层兜底，画面不会变坏，但日志里能看出来该修规则了。
 */
private data class ScanResult(
    val images: List<ScannedImage>,
    val explicitHits: Int = 0,
    val frameCount: Int = 0,
    val adapterId: String = ""
) {
    val isEmpty: Boolean get() = images.isEmpty()
}

// ---------------------------------------------------------------------------
// 注入页面的 JS：站点配置装载 / 取图扫描 / 覆盖层放置 / 移除覆盖层
// ---------------------------------------------------------------------------

/**
 * 注入的取图扫描脚本（[BrowserScanScript.SCAN_JS]）+ 站点适配配置的装载脚本。
 *
 * 站点配置必须在**每次会话开始时重新注入**：同一个 WebView 会在不同站点之间跳转，
 * 沿用上一次的配置会让新站点套用旧规则（例如从 Kakao Page 跳到 Lezhin 后仍按
 * Kakao 的选择器找图）。
 */
private fun installSiteConfigJs(adapter: BrowserSiteAdapter, siteRulesEnabled: Boolean): String =
    BrowserScanScript.installScript(BrowserScanScript.scriptConfigJson(adapter, siteRulesEnabled))

/**
 * 「原文」视图：把覆盖层节点与渲染层场景一起摘掉。
 *
 * 注意它**不碰会话** —— 译文结果仍留在 `renderedTokens` / `renderedScenes` 里，
 * 切回「译文」可以按同样的数据重发（不重跑 OCR/翻译）。
 */
private const val REMOVE_JS = """(function(){
  var els = document.querySelectorAll('.__mt_overlay, .__mt_scene_root, .__mt_loading_root');
  for (var i = 0; i < els.length; i++) { els[i].remove(); }
  if (window.MtOverlay && MtOverlay.removeScenes) { try { MtOverlay.removeScenes(); } catch (e) {} }
  if (window.MtOverlay && MtOverlay.clearLoadings) { try { MtOverlay.clearLoadings(); } catch (e) {} }
  return els.length;
})()"""

/**
 * loading 框的建卡/改文案（2026-10-02，对齐参考项目 `C:\homework\translator` 的 `.mt-loading-card`）。
 *
 * 为什么放在渲染层而不是 Compose 侧：卡片要**贴在正在处理的那张图上方**并跟着页面滚动
 * （webtoon 一张图 3000px 高，固定在 App 底栏的进度文案说不出「在处理哪一张」）。
 *
 * 调用方是 fire-and-forget：渲染层缺失/目标图不存在时返回 `{"ok":false}`，不抛错。
 */
private fun showLoadingJs(imageId: String, label: String): String {
    val payload = JSONObject().apply {
        put("image_id", imageId)
        put("label", label)
    }.toString()
    return "(window.MtOverlay && MtOverlay.showLoadingJson ? " +
        "MtOverlay.showLoadingJson(${jsStringLiteral(payload)}) : " +
        "'{\"ok\":false,\"reason\":\"renderer_missing\"}')"
}

/** 关掉某张图的 loading 框（成功贴图 / 图里没文字 / 失败，三条出口都要走）。 */
private fun hideLoadingJs(imageId: String): String =
    "((window.MtOverlay && MtOverlay.hideLoading) ? MtOverlay.hideLoading(${jsStringLiteral(imageId)}) : 0)"

/** 清掉全部 loading 框（会话收尾的安全网：中途被取消时不能留一个"正在翻译"的胶囊）。 */
private const val CLEAR_LOADINGS_JS =
    "((window.MtOverlay && MtOverlay.clearLoadings) ? MtOverlay.clearLoadings() : 0)"

/** loading 框的第一段文案（取图/解码由会话侧负责，渲染管线从 OCR 开始）。 */
private const val LOADING_FETCHING = "读取图片…"

/**
 * 整图覆盖层的容器就位：把宿主设成定位元素，并解开会裁掉覆盖层的 overflow。
 *
 * 为什么必须做：覆盖层按「宿主 padding box 原点 + 宿主滚动偏移」定位，
 * 宿主若还是 `position:static`，绝对定位就会以更外层的定位祖先为参照，
 * 整块译文偏出去；`overflow:hidden` 的阅读器则会把覆盖层整个裁掉。
 * 只改宿主自身的 `position` 与 `overflow`，不碰尺寸与布局，
 * 失败一律忽略（改不动也必须继续走原路径）。
 */
private fun hostReadyJs(image: ScannedImage): String {
    if (image.hostId.isBlank()) return ""
    return "(function(){try{" +
        "var h=document.querySelector('[data-mt-host=\"${image.hostId}\"]');" +
        "if(!h)return 0;" +
        "var s=getComputedStyle(h);" +
        "if(!s||s.position===''||s.position==='static')h.style.position='relative';" +
        "if(s&&(s.overflow==='hidden'||s.overflow==='clip'))h.style.overflow='visible';" +
        "return 1;}catch(e){return -1;}})()"
}



// ---------------------------------------------------------------------------
// 覆盖层渲染层（assets/mt-overlay-renderer.js）：页面加载后注入一次，
// 之后每个场景通过 window.MtOverlay.applySceneJson 下发。
// ---------------------------------------------------------------------------

object OverlayRendererScript {
    @Volatile private var cached: String? = null

    fun load(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val text = context.assets.open("mt-overlay-renderer.js").bufferedReader().use { it.readText() }
            cached = text
            return text
        }
    }
}

/**
 * 场景下发：把 JSON 转成安全的 JS 字符串字面量（单引号包裹 + 转义），
 * 渲染层内部 JSON.parse —— 避免 U+2028 之类字符把 evaluateJavascript 的源码弄坏。
 */
private fun applySceneJs(sceneJson: String): String =
    "(window.MtOverlay ? MtOverlay.applySceneJson(${jsStringLiteral(sceneJson)}) : " +
        "'{\"ok\":false,\"reason\":\"renderer_missing\"}')"

/**
 * 接缝覆盖层场景下发（跨两张图的气泡：一块盖板 + 一份译文）。
 *
 * 与 [applySceneJs] 同一条通道，只是入口换成 `MtOverlay.applySeamSceneJson`。
 */
private fun applySeamSceneJs(sceneJson: String): String =
    "(window.MtOverlay && MtOverlay.applySeamSceneJson ? " +
        "MtOverlay.applySeamSceneJson(${jsStringLiteral(sceneJson)}) : " +
        "'{\"ok\":false,\"reason\":\"renderer_missing\"}')"

/**
 * 摘掉某张图里若干气泡的渲染层（`MtOverlay.suppressBubble`）——接缝覆盖层上屏后，
 * 接缝两侧各自的「半块」都要摘掉，画面上只剩那一块盖板与那一份译文。
 *
 * 返回摘掉的节点数；渲染层缺失时返回 -1（调用方据此判断「摘不动」，但不必报错）。
 */
private fun suppressBubblesJs(imageId: String, bubbleIds: List<String>): String {
    val payload = JSONObject().apply {
        put("image_id", imageId)
        put("bubble_ids", JSONArray(bubbleIds.toList()))
    }.toString()
    return "((function(){if(!window.MtOverlay||!MtOverlay.suppressBubble)return -1;" +
        "var p=${jsStringLiteral(payload)};try{" +
        "var o=JSON.parse(p),n=0;" +
        "for(var i=0;i<o.bubble_ids.length;i++){n+=MtOverlay.suppressBubble(o.image_id,o.bubble_ids[i]);}" +
        "return n;}catch(e){return -2;}})())"
}

/** JSON → JS 单引号字符串字面量（换行/引号/控制字符全部转义）。 */
private fun jsStringLiteral(value: String): String {
    val literal = StringBuilder(value.length + 8)
    literal.append('\'')
    value.forEach { ch ->
        when (ch) {
            '\\' -> literal.append("\\\\")
            '\'' -> literal.append("\\'")
            '\n' -> literal.append("\\n")
            '\r' -> literal.append("\\r")
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

/** `evaluateJavascriptSuspend` 的结果 → JSONObject（外层 JSON 字符串被 WebView 再引号包裹过一层）。 */
private fun decodeJsJsonResult(raw: String?): JSONObject? {
    val decoded = runCatching {
        if (raw != null && raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
            JSONObject("{\"v\":$raw}").optString("v")
        } else {
            raw.orEmpty()
        }
    }.getOrDefault("")
    return runCatching { JSONObject(decoded) }.getOrNull()
}

/**
 * 接缝覆盖层的下发记录：「原文 → 翻译」贴回时按同样的两张图重发一遍。
 *
 * 为什么必须记住：两半渲染已经被摘掉了，贴回时不重发接缝就会出现「整条缝上什么都没有」。
 */
private data class PendingSeam(val scene: SeamBandScene, val nextSrc: String)

/**
 * 会话模式（2026-10-02）。
 *
 * - [TRANSLATE]：正常的连续翻译会话（扫描 → OCR → 翻译 → 贴图 → 轮询等新图）；
 * - [RE_SHOW_ONLY]：只把**已有**译文层贴回的一轮一次性会话 —— 不 OCR、不下图、不轮询。
 *   它是「译文」这个视图开关的实现方式。改造前这件事是靠再点一次「翻译」按钮做的，
 *   于是同一个按钮既是「开始翻译」又是「显示译文」，这正是用户反馈的「功能不统一」。
 */
private enum class BrowserSessionMode { TRANSLATE, RE_SHOW_ONLY }

/**
 * 整图覆盖层放置（DOM 覆盖层不可用时的兜底路径）。
 *
 * 与旧实现的差别：不再无脑挂到 `document.body`。图片在 `overflow:auto` 的阅读器里时，
 * 覆盖层挂到 body 会**和图片分属两个滚动上下文** —— 用户一滚，译文留在原地、图片滑走。
 * 这里把覆盖层挂进图片自己的定位宿主（`data-mt-host`），坐标用同一个坐标系
 * （宿主 padding box 原点 + 宿主滚动偏移），于是滚动由合成线程一起搬运，图文不分家。
 * 宿主拿不到（元素被站点卸载 / 还没打标记）时退回 body + 文档坐标，与旧行为一致。
 */
private fun placeOverlayJs(image: ScannedImage, token: String): String {
    val src = "https://mtlocal.overlay/$token.png"
    val style = "position:absolute;left:${image.left}px;top:${image.top}px;" +
        "width:${image.width}px;height:${image.height}px;z-index:2147483000;" +
        "pointer-events:none;border:0;margin:0;padding:0;max-width:none;"
    if (image.hostId.isBlank()) {
        return """(function(){
  var old = document.querySelectorAll('[data-mt-ov-for="${image.id}"]');
  for (var i = 0; i < old.length; i++) { old[i].remove(); }
  var el = document.createElement('img');
  el.src = '$src';
  el.className = '__mt_overlay';
  el.setAttribute('data-mt-ov-for', '${image.id}');
  el.style.cssText = '$style';
  (document.body || document.documentElement).appendChild(el);
  return 1;
})()"""
    }
    return """(function(){
  var old = document.querySelectorAll('[data-mt-ov-for="${image.id}"]');
  for (var i = 0; i < old.length; i++) { old[i].remove(); }
  var host = document.querySelector('[data-mt-host="${image.hostId}"]');
  var el = document.createElement('img');
  el.src = '$src';
  el.className = '__mt_overlay';
  el.setAttribute('data-mt-ov-for', '${image.id}');
  if (!host) {
    el.style.cssText = 'position:absolute;left:${image.left}px;top:${image.top}px;${style}';
    (document.body || document.documentElement).appendChild(el);
    return 0;
  }
  ${hostReadyJs(image)}
  el.style.cssText = '$style';
  host.appendChild(el);
  return 1;
})()"""
}

/**
 * 扫描脚本返回值 → [ScanResult]。
 *
 * 兼容两种形态：新的对象 `{"items":[…],…}` 与旧的裸数组 `[…]`。
 * 为什么要兼容旧的：`onPageFinished` 之前用户就可能点「翻译」，
 * 那一刻注入的还是页面里残留的旧脚本，直接当空结果会让这次会话白跑一轮。
 */
private fun parseScannedImages(raw: String?): ScanResult {
    if (raw.isNullOrBlank()) return ScanResult(emptyList())
    return runCatching {
        val trimmed = raw.trim()
        val decoded = if (trimmed.startsWith("\"")) {
            JSONObject("{\"v\":$trimmed}").optString("v")
        } else {
            trimmed
        }
        val trimmedDecoded = decoded.trim()
        val array = if (trimmedDecoded.startsWith("{")) {
            JSONObject(trimmedDecoded).optJSONArray("items") ?: JSONArray()
        } else {
            JSONArray(trimmedDecoded)
        }
        val images = (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id")
            val src = obj.optString("src")
            if (id.isBlank() || src.isBlank()) return@mapNotNull null
            ScannedImage(
                id = id,
                src = src,
                left = obj.optDouble("l", 0.0).toInt(),
                top = obj.optDouble("t", 0.0).toInt(),
                width = obj.optDouble("w", 0.0).toInt(),
                height = obj.optDouble("h", 0.0).toInt(),
                hostId = obj.optString("hostId"),
                hostX = obj.optDouble("hostX", 0.0).toInt(),
                hostY = obj.optDouble("hostY", 0.0).toInt(),
                frame = obj.optInt("frame", 0),
                lazy = obj.optBoolean("lazy", false),
                explicit = obj.optBoolean("explicit", false),
                kind = obj.optString("kind", "url").ifBlank { "url" }
            )
        }
        if (trimmedDecoded.startsWith("{")) {
            val meta = JSONObject(trimmedDecoded)
            ScanResult(
                images = images,
                explicitHits = meta.optInt("explicit", 0),
                frameCount = meta.optInt("frames", 0),
                adapterId = meta.optString("cfg")
            )
        } else {
            ScanResult(images)
        }
    }.getOrDefault(ScanResult(emptyList()))
}

/**
 * 求值一段 JS 并把结果当字符串拿回来 —— **带超时**。
 *
 * ## 为什么必须有超时（2026-09-30 实测）
 *
 * `evaluateJavascript` 的回调在几种情况下**永远不会来**：
 * - 页面正在导航/销毁（回调被丢弃）；
 * - WebView 已经 `destroy()`；
 * - 页面里的脚本把渲染主线程卡住（同步死循环、超大 `toDataURL`）。
 *
 * 原来的写法是 `suspendCancellableCoroutine { evaluateJavascript(it) { r -> resume(r) } }`，
 * 没有超时。只要有一次回调不来，**整个连续翻译会话就永久挂在那里**：
 * 不再扫描、不再翻任何图，但 UI 上还显示「翻译中」。
 * 实测到的表现就是主循环出现 25.6s / 39.6s / 41.7s / 55.8s 的空档，
 * 用户在这段时间里滑过的图**再也没人管** —— 也就是「有一张图是原图、没有覆盖层」。
 *
 * 超时不是「掩盖问题」：调用方本来就把 `null` 当作「这次拿不到」，会走重试/下一轮；
 * 而且这里会打**一次**警告日志，把「谁卡住了」记下来（避免又变成无线索排查）。
 */
private suspend fun WebView.evaluateJavascriptSuspend(
    script: String,
    timeoutMs: Long = JS_EVAL_TIMEOUT_MS,
    what: String = "eval"
): String? = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { result -> cont.resume(result) }
    }
} ?: run {
    Log.w(TAG, "js_eval_timeout what=$what timeout=${timeoutMs}ms len=${script.length}")
    null
}

/**
 * 一次 JS 求值的超时上限。
 *
 * 取值依据：实测正常的求值都是**毫秒级**（页面侧只是数 DOM / 下发场景）；
 * 3 秒足够覆盖慢设备抖动，又不至于让会话停摆太久。
 */
private const val JS_EVAL_TIMEOUT_MS = 3_000L

/**
 * 连续翻译会话句柄：[token] 用来判断「收尾/回调」是否还属于当前这一轮 ——
 * 用户点「停止」后旧协程仍会走 finally，不能让旧协程把新会话的状态写回去。
 */
private class TranslationSession {
    var job: Job? = null
    var token: Int = 0
}

private const val TAG = "EmbeddedBrowser"
private const val IDLE_POLL_INTERVAL_MS = 1200L
/**
 * 单张图最多尝试几次。
 *
 * 原来 3 次：RIDI 这类「边滚动边挂新页图」的站点上，一张图可能在它还没解码完、
 * 或页面正在重排时就被取像素 —— 那几次失败都是**瞬时**的，3 次不够（实测出现过）。
 * 提到 5 次；真正的确定性失败（图里没文字 / 分组没产出气泡）走
 * `processedWithoutResult`，不受这个上限影响、也不会被反复重跑。
 */
private const val MAX_ATTEMPTS_PER_IMAGE = 5

/**
 * 「当前扫到的最后一张」等下一页出现的轮询次数（懒加载阅读器）。
 *
 * 每轮轮询约 1.2s，3 轮 ≈ 3.6s 的等待：换成「下一页出现后再处理这一张」，
 * 跨缝链路才有下条带可用（真机 Lezhin 实测 `botH=0` 时整条跨缝处理都不生效）。
 */
private const val NEXT_PAGE_WAIT_ROUNDS = 6

/** 已渲染场景缓存上限（每个场景几 KB，防长章节无限增长；整图 PNG 由内存仓 96MB 上限管着）。 */
private const val MAX_RENDERED_SCENES = 80

/** 新标签页「历史记录」区最多展示的最近条数（设计稿 730751285322573：4 行）。 */
private const val RECENT_HISTORY_LIMIT = 4

// ---------------------------------------------------------------------------
// 界面
// ---------------------------------------------------------------------------

/**
 * 浏览器内部页面：[WEB] 网页浏览（自带顶部地址栏 + 底部网页导航栏，隐藏主底栏）；
 * [NEW_TAB] 新标签页（搜索 + 收藏 + 最近历史，主底栏可见）；[HISTORY] 历史记录完整页（主底栏可见）。
 * 视觉来源：设计稿 `730751285322573`（2026-09-28 用户定稿）。
 */
private enum class BrowserPage {
    WEB,
    NEW_TAB,
    HISTORY
}

/** 新标签页收藏格的「添加」占位格标识（末尾固定一格）。 */
private val ADD_FAVORITE_TILE = Any()

/** 收藏新增/编辑弹窗的编辑态；[originalUrl] = null 表示新增。 */
private data class FavoriteEditor(
    val originalUrl: String?,
    val label: String,
    val url: String
)

private val TIME_HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val TIME_MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")

private fun historyDayKey(lastVisitMs: Long): LocalDate =
    Instant.ofEpochMilli(lastVisitMs).atZone(ZoneId.systemDefault()).toLocalDate()

/**
 * 历史行右侧的时间文案。完整历史页有分组标题（今天/昨天），组内行只显示时刻；
 * 新标签页的平铺列表没有分组，昨天的条目直接显示「昨天」。
 */
private fun historyTimeLabel(entry: BrowserHistoryEntry, today: LocalDate, grouped: Boolean): String {
    val date = historyDayKey(entry.lastVisitMs)
    val timeText = TIME_HHMM.format(Instant.ofEpochMilli(entry.lastVisitMs).atZone(ZoneId.systemDefault()))
    return when (date) {
        today -> timeText
        today.minusDays(1) -> if (grouped) timeText else "昨天"
        else -> TIME_MONTH_DAY.format(date)
    }
}

/**
 * 内嵌浏览器：应用内打开漫画网页，网页里的漫画图被逐张翻译并原位覆盖
 * （对齐 C:\homework\translator 扩展效果）。不需要录屏授权、悬浮窗权限，
 * 也不依赖前台服务；OCR/翻译引擎、句子缓存、术语表与悬浮球链路共用。
 *
 * 2026-09-28 第二轮（用户反馈落地）：
 * 1. **连续自动翻译**：点一次「翻译」就按页面顺序整页往下翻，滚动 / lazy-load 出来的新图
 *    自动补翻，直到点「停止」（参考项目是「预先翻译」，这里改为「已加载的都能翻」）。
 * 2. **保存浏览历史**：`BrowserHistoryRepository` 落盘（回访 / 清空）。
 * 3. **「精细擦字」选项（默认开，原「inpaint 清理图」改名）**：整页 inpaint 擦字（含 solid 气泡），
 *    背景连续更接近原画；设置菜单里可一键关掉换取速度。
 *
 * 2026-09-28 第三轮（设计稿 730751285322573 落地）：进「浏览器」tab 先落到**新标签页**
 * （搜索框 + 收藏格 + 最近历史，主底栏可见）；网页态点顶部地址框回到新标签页；
 * 「查看全部」进完整历史记录页（今天/昨天/更早分组，主底栏可见）。
 */
@Composable
fun EmbeddedBrowserScreen(
    onClose: () -> Unit,
    onSelectTab: (Int) -> Unit
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val settingsRepository = ServiceLocator.settingsRepository
    val historyRepository = ServiceLocator.browserHistoryRepository
    val favoriteRepository = ServiceLocator.browserFavoriteRepository

    var browserPage by remember { mutableStateOf(BrowserPage.NEW_TAB) }
    var newTabSearch by remember { mutableStateOf("") }
    var pageUrl by remember { mutableStateOf("") }
    var loadProgress by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    // 地址栏原地编辑（2026-09-28 用户反馈：点地址框就跳新标签页，跟正规浏览器不一致）：
    // 编辑态 = 页面不动、地址栏变成输入框并全选原 URL，回车/Go 才真正跳转。
    var addressEditing by remember { mutableStateOf(false) }
    var addressDraft by remember { mutableStateOf(TextFieldValue("")) }
    val addressFocusRequester = remember { FocusRequester() }
    // 默认搜索引擎（用户反馈 Google 在国内不可用）：默认必应，可切换。
    var searchEngine by remember { mutableStateOf(settingsRepository.getBrowserSearchEngine()) }
    var inpaintClean by remember { mutableStateOf(settingsRepository.isBrowserInpaintCleanEnabled()) }
    // 覆盖层渲染（默认开）：译文画在 DOM 覆盖层上；关 = 旧的整图替换。
    var domOverlay by remember { mutableStateOf(settingsRepository.isBrowserDomOverlayEnabled()) }
    // 站点适配（2026-09-30，默认开）：按当前站点套用取图规则；关 = 只跑通用层。
    var siteAdapterOn by remember { mutableStateOf(settingsRepository.isBrowserSiteAdapterEnabled()) }
    var disabledSites by remember { mutableStateOf(settingsRepository.browserDisabledSiteAdapters()) }
    // 当前页面命中的站点适配器（顶栏状态行与设置菜单展示「已适配：X」）。
    var matchedAdapter by remember { mutableStateOf(BrowserSiteAdapter.GENERIC) }
    var historyEntries by remember {
        mutableStateOf(runCatching { historyRepository.all() }.getOrDefault(emptyList()))
    }
    // 收藏：初始为空（用户要求），全部由用户自己添加；编辑态下格子可删除/改名。
    var favorites by remember {
        mutableStateOf(runCatching { favoriteRepository.all() }.getOrDefault(emptyList()))
    }
    var favoritesEditing by remember { mutableStateOf(false) }
    var favoriteEditor by remember { mutableStateOf<FavoriteEditor?>(null) }
    var lastRecordedUrl by remember { mutableStateOf("") }
    val renderedTokens = remember { HashMap<String, String>() }
    // 覆盖层渲染：src → 已渲染场景（「原文/翻译」秒回贴回用，不重跑 OCR/翻译）。
    val renderedScenes = remember { LinkedHashMap<String, BrowserSceneBuilder.Scene>() }
    // 渲染层 JS 是否已注入当前页面（页面跳转后要重新注入）。
    var rendererInjected by remember { mutableStateOf(false) }
    // 视图模式（2026-10-02 用户反馈「原文/翻译两个按钮功能不统一」后拆分）：
    // 只决定译文层挂不挂在页面上，**不**启停任何任务；任务启停只由底栏主按钮负责。
    var viewMode by remember { mutableStateOf(BrowserViewMode.TRANSLATED) }
    // 当前页面是否至少成功贴过一张译文：驱动底栏分段开关的可用性（没有译文就没得切）。
    var hasRenderOnPage by remember { mutableStateOf(false) }
    // 「一次性贴回」会话在跑（不翻译，只把已有译文层贴回）；这期间两个控件都禁用，避免被打断。
    var reShowBusy by remember { mutableStateOf(false) }
    // 已经贴过覆盖层的图（DOM 元素 id）。提升到 composable 作用域的原因：切「原文」要清空它，
    // 而贴回可能发生在**另一个会话**里 —— 必须是同一份状态，「贴回」才有依据。
    val placedIds = remember { HashSet<String>() }
    // 接缝覆盖层下发记录：同样必须跨会话存活。改造前它是会话内局部变量，而「原文 → 译文」
    // 贴回走的是新会话 → 这条路径实际恒为空，切回来接缝那一段译文会缺一块。
    val seamBySrc = remember { LinkedHashMap<String, PendingSeam>() }
    val session = remember { TranslationSession() }
    val translator = remember { WebtoonImageTranslator() }
    val downloader = remember { BrowserImageDownloader() }

    fun refreshHistory() {
        historyEntries = runCatching { historyRepository.all() }.getOrDefault(emptyList())
    }

    fun refreshFavorites() {
        favorites = runCatching { favoriteRepository.all() }.getOrDefault(emptyList())
    }

    /** 保存收藏（新增或编辑），返回给用户的提示文案。 */
    fun saveFavorite(editor: FavoriteEditor): String {
        val result = if (editor.originalUrl == null) {
            favoriteRepository.add(editor.label, editor.url)
        } else if (favoriteRepository.update(editor.originalUrl, editor.label, editor.url)) {
            FavoriteAddResult.UPDATED
        } else {
            FavoriteAddResult.INVALID_URL
        }
        refreshFavorites()
        return when (result) {
            FavoriteAddResult.ADDED -> "已收藏"
            FavoriteAddResult.UPDATED -> "已更新收藏"
            FavoriteAddResult.INVALID_URL -> "网址无效，请填写完整地址"
            FavoriteAddResult.FULL -> "收藏已满（最多 ${BrowserFavoriteRepository.MAX_FAVORITES} 个）"
        }
    }

    // 声明顺序注意：WebViewClient 回调里要调用 stopSession / recordHistory，
    // 所以这两个函数必须定义在 WebView 之前（它们都不依赖 WebView 实例）。
    fun stopSession(message: String) {
        session.token += 1
        session.job?.cancel()
        session.job = null
        running = false
        // 贴回会话也可能被停（跳转 / 点「翻译」打断）：这个标志必须一起复位，否则两个控件会永久禁用。
        reShowBusy = false
        statusText = message
        Log.d(TAG, "session_stop reason=$message")
    }

    fun recordHistory(url: String, title: String) {
        val normalized = BrowserHistoryRepository.normalizeUrl(url) ?: return
        if (normalized == lastRecordedUrl) return
        lastRecordedUrl = normalized
        scope.launch {
            withContext(Dispatchers.IO) { historyRepository.record(normalized, title) }
            refreshHistory()
            Log.d(TAG, "history_record url=${BrowserHistoryRepository.urlWithoutScheme(normalized)}")
        }
    }

    /**
     * 当前站点是否真的在套用规则：总开关开着 且 这个站没被单独关掉。
     *
     * 定义在 WebView 之前：`onPageStarted` 回调里要用它决定注入什么配置。
     */
    fun siteRulesActive(adapter: BrowserSiteAdapter): Boolean =
        siteAdapterOn && adapter.id !in disabledSites

    /**
     * 换页时重算命中的适配器。返回值就是命中结果，调用方直接拿去注入配置。
     *
     * 必须**每次导航都重算**：同一个 WebView 会跨站跳转（Kakao Page → Lezhin），
     * 沿用上一次的适配器会让新站点套用旧规则。
     */
    fun refreshMatchedAdapter(url: String): BrowserSiteAdapter {
        val adapter = BrowserSiteAdapterRegistry.resolveUrl(url)
        matchedAdapter = adapter
        return adapter
    }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.setSupportMultipleWindows(false)
            settings.userAgentString = WebSettings.getDefaultUserAgent(context).replace("; wv)", ")")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    if (request.url.host == "mtlocal.overlay") {
                        val token = request.url.lastPathSegment?.substringBeforeLast('.') ?: return null
                        val bytes = BrowserOverlayImageStore.get(token) ?: return null
                        val mime = BrowserOverlayImageStore.mimeOf(token) ?: "image/png"
                        // 允许页面读取这张图：mtlocal 是我们自己的内存通道，不存在泄露风险，
                        // 但不带 CORS 头时把图 drawImage 进 canvas 会**污染**画布，页面脚本就再也
                        // 读不到像素（getImageData 抛 SecurityError）——诊断页想核对「这一块到底
                        // 擦干净没有」就无从下手（2026-09-29 实测踩到）。
                        return WebResourceResponse(
                            mime,
                            null,
                            200,
                            "OK",
                            mapOf(
                                "Access-Control-Allow-Origin" to "*",
                                "Cache-Control" to "no-store"
                            ),
                            ByteArrayInputStream(bytes)
                        )
                    }
                    return null
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme ?: return false
                    if (scheme == "http" || scheme == "https") return false
                    // tel:/mailto:/intent:/market: 等交给系统，避免浏览器内白屏。
                    return runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        true
                    }.getOrDefault(true)
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    pageUrl = url
                    // 新页面 = 旧页面的渲染层注入失效，落地后由 onPageFinished 重新注入。
                    rendererInjected = false
                    // 新页面既没有可隐藏的译文层，也没有可贴回的覆盖层：视图回「译文」，
                    // 底栏分段开关回禁用态。不清 renderedTokens/renderedScenes（src 复用有价值，
                    // 但绝不能拿它当「本页已渲染」——那会让「译文」开关点了没反应）。
                    viewMode = BrowserViewMode.TRANSLATED
                    hasRenderOnPage = false
                    placedIds.clear()
                    seamBySrc.clear()
                    // 站点适配器按新 URL 重算（跨站跳转必须换规则）。
                    matchedAdapter = BrowserSiteAdapterRegistry.resolveUrl(url)
                    // 真的开始导航了：地址栏退出编辑态（地址显示为实际 URL）。
                    addressEditing = false
                    // 跳转即终止当前连续翻译：旧页面的图不应该再贴到新页面上。
                    if (session.job != null) {
                        stopSession("页面已跳转，点「翻译」继续")
                    } else if (statusText.startsWith("翻译中") || statusText.startsWith("扫描")) {
                        statusText = ""
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    recordHistory(url, view.title.orEmpty())
                    // 渲染层随页面注入（幂等）：场景下发与测试页都依赖它已就绪。
                    runCatching {
                        view.evaluateJavascript(OverlayRendererScript.load(context), null)
                        rendererInjected = true
                    }
                    // 站点适配配置也在这里注入一次：脚本必须在「用户点翻译」之前就位，
                    // 否则首轮扫描读到的是上一次的配置（跨站跳转时尤其明显）。
                    runCatching {
                        val adapter = refreshMatchedAdapter(url)
                        view.evaluateJavascript(
                            installSiteConfigJs(adapter, siteRulesActive(adapter)),
                            null
                        )
                        Log.d(
                            TAG,
                            "site_adapter page=${BrowserHistoryRepository.urlWithoutScheme(url)} " +
                                "adapter=${adapter.id} rules=${siteRulesActive(adapter)}"
                        )
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    loadProgress = newProgress
                }
            }
        }
    }

    /**
     * 「译文」视图的摘层动作：只把译文层从页面上摘掉，**保留已渲染结果**
     * （renderedTokens + renderedScenes + 内存仓）。所以点「译文」能把它们贴回来
     * （不重跑 OCR/翻译），也不会出现「原文之后再点翻译回不来」。
     */
    fun removeOverlayNodes() {
        webView.evaluateJavascript(REMOVE_JS, null)
    }

    /** 彻底丢弃已渲染结果（切换「清理图/覆盖层渲染」选项时用：旧结果是旧设置画的，必须重画）。 */
    fun clearRenderedOverlays() {
        removeOverlayNodes()
        renderedTokens.clear()
        renderedScenes.clear()
        seamBySrc.clear()
        placedIds.clear()
        hasRenderOnPage = false
        BrowserOverlayImageStore.clear()
    }

    // 视图切换（showOriginal / showTranslations）定义在 startSession 之后 ——
    // Kotlin 的局部函数必须先声明后使用，「译文」视图要起一次性贴回会话。
    BackHandler(enabled = true) {
        when {
            // 编辑地址栏时返回 = 放弃这次修改（不导航、不离开网页），和正规浏览器一致。
            addressEditing -> {
                addressEditing = false
                keyboard?.hide()
            }

            browserPage == BrowserPage.HISTORY -> browserPage = BrowserPage.NEW_TAB
            browserPage == BrowserPage.NEW_TAB -> onClose()
            else -> if (webView.canGoBack()) webView.goBack() else browserPage = BrowserPage.NEW_TAB
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            session.token += 1
            session.job?.cancel()
            session.job = null
            webView.stopLoading()
            runCatching { CookieManager.getInstance().flush() }
            runCatching { webView.destroy() }
            renderedTokens.clear()
            renderedScenes.clear()
            seamBySrc.clear()
            placedIds.clear()
            BrowserOverlayImageStore.clear()
        }
    }

    /** 新标签页搜索框 / 跳转钮提交：裸关键词转**当前搜索引擎**（默认必应），像网址就直接打开。 */
    fun submitUrl() {
        val target = BrowserUrlResolver.resolve(newTabSearch, searchEngine) ?: return
        newTabSearch = ""
        keyboard?.hide()
        browserPage = BrowserPage.WEB
        webView.loadUrl(target)
    }

    /** 切换默认搜索引擎（新标签页芯片 / 设置菜单）：立即落盘，下一次搜索生效。 */
    fun selectSearchEngine(engine: BrowserSearchEngine) {
        searchEngine = engine
        settingsRepository.setBrowserSearchEngine(engine)
        Log.d(TAG, "search_engine_set id=${engine.id}")
    }

    /** 点地址框：进入原地编辑（全文选中，直接打字即替换，和 Chrome 一样）。 */
    fun beginAddressEdit() {
        val current = if (pageUrl.isNotBlank() && pageUrl != "about:blank") pageUrl else ""
        addressDraft = TextFieldValue(text = current, selection = TextRange(0, current.length))
        addressEditing = true
    }

    /** 编辑态提交：走与搜索框相同的归一化（网址直接开、关键词用当前搜索引擎）。 */
    fun commitAddressEdit() {
        val target = BrowserUrlResolver.resolve(addressDraft.text, searchEngine)
        addressEditing = false
        keyboard?.hide()
        if (target == null) return
        if (target == pageUrl) {
            // 地址没改：当作刷新，避免「点了回车什么都没发生」。
            webView.reload()
        } else {
            webView.loadUrl(target)
        }
    }

    /** 收藏 / 历史条目点按打开。 */
    fun openUrl(url: String) {
        newTabSearch = ""
        menuOpen = false
        keyboard?.hide()
        browserPage = BrowserPage.WEB
        webView.loadUrl(url)
    }

    /**
     * 启动会话（底栏主按钮的唯一入口）。
     *
     * [BrowserSessionMode.RE_SHOW_ONLY] 只跑一轮「把已有译文贴回」，不 OCR、不下图、不轮询 ——
     * 它是「译文」视图开关的实现方式，不再借用「翻译」按钮（改造前两者是同一个入口，
     * 用户读不出区别，这是 2026-10-02 反馈的核心）。
     */
    fun startSession(mode: BrowserSessionMode = BrowserSessionMode.TRANSLATE) {
        if (running) {
            stopSession("已停止 · 译文保留")
            return
        }
        if (reShowBusy) return
        val currentUrl = webView.url
        if (currentUrl.isNullOrBlank() || currentUrl == "about:blank") {
            statusText = "请先打开漫画网页"
            return
        }
        session.token += 1
        val myToken = session.token
        if (mode == BrowserSessionMode.RE_SHOW_ONLY) {
            // 贴回不占「运行中」（running 只表示连续翻译在跑）：它是一次性动作，
            // 结束即退出，期间两个控件禁用。
            reShowBusy = true
            statusText = "正在显示译文…"
        } else {
            running = true
            statusText = "扫描页面…"
        }
        // 会话开始时重新注入站点配置：`onPageFinished` 注入过一次，但用户可能在
        // 页面里点了站内跳转（SPA pushState，不触发 onPageFinished），
        // 也可能改过「已适配站点」开关 —— 这里以当前 URL + 当前开关为准。
        val adapter = refreshMatchedAdapter(currentUrl)
        val rulesOn = siteRulesActive(adapter)
        webView.evaluateJavascript(installSiteConfigJs(adapter, rulesOn), null)
        Log.d(
            TAG,
            "session_start token=$myToken mode=$mode inpaint_clean=$inpaintClean dom_overlay=$domOverlay " +
                "adapter=${adapter.id} site_rules=$rulesOn"
        )
        session.job = scope.launch {
            runTranslationSession(
                token = myToken,
                sessionMode = mode,
                sessionPageUrl = currentUrl,
                userAgent = webView.settings.userAgentString,
                renderedTokens = renderedTokens,
                renderedScenes = renderedScenes,
                placedIds = placedIds,
                seamBySrc = seamBySrc,
                useDomOverlay = domOverlay,
                rendererScript = OverlayRendererScript.load(context),
                ensureRenderer = {
                    if (!rendererInjected) {
                        webView.evaluateJavascriptSuspend(OverlayRendererScript.load(context))
                        rendererInjected = true
                    }
                },
                isViewVisible = { viewMode == BrowserViewMode.TRANSLATED },
                onRendered = { hasRenderOnPage = true },
                onFinished = { reShowBusy = false },
                translator = translator,
                downloader = downloader,
                webView = webView,
                onStatus = { statusText = it },
                onRunningChange = { running = it },
                onSessionActive = { session.token == myToken }
            )
        }
    }

    /**
     * 切到「原文」视图：摘掉译文层，**不停会话**。
     *
     * 「不停会话」是 2026-10-02 定稿的：运行中切原文只是换个看法，后台继续翻，
     * 切回来立刻就有已经翻好的结果（与参考项目 `displayMode` 的语义一致）。
     * 清 [placedIds] 是贴回的关键 —— 会话下一轮扫描时 `needsReShow` 才会认为这些图没上屏。
     */
    fun showOriginal() {
        viewMode = BrowserViewMode.ORIGINAL
        removeOverlayNodes()
        placedIds.clear()
        if (!running) {
            statusText = BrowserTranslateUiPolicy.idleHint(
                viewMode, hasRender = hasRenderOnPage, pageLoaded = true
            )
        }
        Log.d(
            TAG,
            "view_mode_set mode=original running=$running " +
                "cached=${renderedTokens.size + renderedScenes.size}"
        )
    }

    /**
     * 切到「译文」视图。
     *
     * - 会话在跑：什么都不用做 —— 下一轮轮询（≤ [IDLE_POLL_INTERVAL_MS]）就会因 [placedIds] 已清而重新贴回；
     * - 会话没跑但有缓存：起一次**只贴回**的一次性会话（不 OCR、不下图、不轮询）；
     * - 什么都没有：只在状态行提示。改造前这里就是「点了没反应」的来源。
     */
    fun showTranslations() {
        viewMode = BrowserViewMode.TRANSLATED
        Log.d(
            TAG,
            "view_mode_set mode=translated running=$running " +
                "cached=${renderedTokens.size + renderedScenes.size}"
        )
        when {
            running -> Unit
            renderedTokens.isEmpty() && renderedScenes.isEmpty() ->
                statusText = BrowserTranslateUiPolicy.idleHint(
                    viewMode, hasRender = hasRenderOnPage, pageLoaded = true
                )

            else -> startSession(BrowserSessionMode.RE_SHOW_ONLY)
        }
    }

    // 进入地址编辑态就聚焦并弹出键盘（等价 Chrome 点地址栏）。
    LaunchedEffect(addressEditing) {
        if (addressEditing) {
            addressFocusRequester.requestFocus()
            keyboard?.show()
        }
    }

    when (browserPage) {
        BrowserPage.NEW_TAB -> BrowserNewTabPage(
            search = newTabSearch,
            onSearchChange = { newTabSearch = it },
            onSubmit = { submitUrl() },
            searchEngine = searchEngine,
            onSelectSearchEngine = { engine -> selectSearchEngine(engine) },
            // 有打开的网页时给一条「马上回网页」的路（2026-09-28 用户要求）。
            showBackToPage = pageUrl.isNotBlank() && pageUrl != "about:blank",
            onBackToPage = {
                keyboard?.hide()
                browserPage = BrowserPage.WEB
            },
            favorites = favorites,
            favoritesEditing = favoritesEditing,
            onToggleFavoritesEditing = { favoritesEditing = !favoritesEditing },
            onAddFavorite = { favoriteEditor = FavoriteEditor(null, "", "") },
            onEditFavorite = { favorite ->
                favoriteEditor = FavoriteEditor(favorite.url, favorite.label, favorite.url)
            },
            onDeleteFavorite = { url ->
                favoriteRepository.remove(url)
                refreshFavorites()
            },
            recent = historyEntries.take(RECENT_HISTORY_LIMIT),
            onOpenUrl = { url -> openUrl(url) },
            onOpenHistory = {
                refreshHistory()
                browserPage = BrowserPage.HISTORY
            },
            onSelectTab = onSelectTab
        )

        BrowserPage.HISTORY -> BrowserHistoryPage(
            entries = historyEntries,
            onBack = { browserPage = BrowserPage.NEW_TAB },
            onOpenUrl = { url -> openUrl(url) },
            onClear = { confirmClearHistory = true },
            onSelectTab = onSelectTab
        )

        // 网页态：浏览器自有顶栏（返回 + 可编辑地址框 + 设置菜单）+ 进度条 + 网页 + 网页导航底栏。
        // 主底栏隐藏；点地址框**原地编辑 URL**（不再跳新标签页，2026-09-28 用户反馈对齐正规浏览器）；
        // 新标签页（收藏/历史）改由设置菜单「新标签页」与返回手势进入。
        BrowserPage.WEB -> Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MtColors.Surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 6.dp)
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MtBrowserIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    desc = "网页后退",
                    onClick = {
                        if (webView.canGoBack()) webView.goBack() else browserPage = BrowserPage.NEW_TAB
                    }
                )
                if (addressEditing) {
                    // 编辑态：输入框就长在地址栏位置，页面不动；回车/Go 才导航。
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MtColors.SurfaceMuted)
                            .border(1.dp, MtColors.Brand, RoundedCornerShape(12.dp))
                            .padding(start = 14.dp, end = 14.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = addressDraft,
                            onValueChange = { addressDraft = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 14.sp, color = MtColors.TextPrimary),
                            cursorBrush = SolidColor(MtColors.Brand),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { commitAddressEdit() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(addressFocusRequester)
                        )
                    }
                    MtBrowserIconButton(
                        icon = Icons.Filled.Close,
                        desc = "取消编辑地址",
                        onClick = {
                            addressEditing = false
                            keyboard?.hide()
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MtColors.SurfaceMuted)
                            .clickable { beginAddressEdit() }
                            .padding(start = 14.dp, end = 14.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        val displayAddress =
                            if (pageUrl.isNotBlank() && pageUrl != "about:blank") {
                                BrowserHistoryRepository.urlWithoutScheme(pageUrl)
                            } else {
                                "新标签页"
                            }
                        Text(
                            text = displayAddress,
                            fontSize = 14.sp,
                            color = MtColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 「马上回到浏览器界面」：直接回到**底栏点「浏览器」时看到的那个界面**
                // （新标签页 = 搜索 + 收藏 + 最近历史，主底栏可见）。
                // 2026-09-29 用户澄清：要的不是「返回上一屏」，而是这个一键直达入口 ——
                // 地址栏点按已改成原地编辑 URL，所以必须有一个独立的回家按钮。
                MtBrowserIconButton(
                    icon = MtBrowserIcon,
                    desc = "回到浏览器界面（新标签页）",
                    onClick = {
                        menuOpen = false
                        addressEditing = false
                        keyboard?.hide()
                        refreshHistory()
                        browserPage = BrowserPage.NEW_TAB
                        Log.d(TAG, "browser_home_tap from=WEB")
                    }
                )
                Box {
                    MtBrowserIconButton(
                        icon = Icons.Filled.Settings,
                        desc = "浏览器设置",
                        onClick = { menuOpen = true }
                    )
                    MtDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        BrowserMenuItem(
                            icon = Icons.Filled.Add,
                            label = "新标签页（收藏 / 历史）",
                            onClick = {
                                menuOpen = false
                                keyboard?.hide()
                                refreshHistory()
                                browserPage = BrowserPage.NEW_TAB
                            }
                        )
                        HorizontalDivider(color = MtColors.Border)
                        // 站点适配：告诉用户「这个站被识别成什么」，并允许逐站关掉。
                        // 为什么要显示：适配规则是按站点 DOM 写的，站点改版后会失效；
                        // 让用户看得见「已适配：Kakao Page」与「通用」，出问题时才分得清
                        // 是「规则没命中」还是「图根本没扫到」。
                        BrowserMenuSiteAdapter(
                            adapter = matchedAdapter,
                            rulesEnabled = siteRulesActive(matchedAdapter),
                            onToggle = { enabled ->
                                val adapter = matchedAdapter
                                if (adapter.id == BrowserSiteAdapter.GENERIC.id) {
                                    // 通用适配器没有独立规则，开关落到总开关上。
                                    siteAdapterOn = enabled
                                    settingsRepository.setBrowserSiteAdapterEnabled(enabled)
                                } else {
                                    disabledSites =
                                        settingsRepository.setBrowserSiteAdapterDisabled(adapter.id, !enabled)
                                }
                                // 换规则就要换配置：会话内重新注入一次，并丢掉旧结果。
                                clearRenderedOverlays()
                                val rulesNow = siteRulesActive(adapter)
                                webView.evaluateJavascript(installSiteConfigJs(adapter, rulesNow), null)
                                stopSession(if (rulesNow) "已启用站点适配，点「翻译」重新翻译" else "已停用站点适配，点「翻译」重新翻译")
                                Log.d(
                                    TAG,
                                    "site_adapter_set adapter=${adapter.id} rules=$rulesNow " +
                                        "disabled=${disabledSites.joinToString(",")}"
                                )
                                menuOpen = false
                            }
                        )
                        BrowserMenuSwitch(
                            title = "精细擦字",
                            subtitle = if (inpaintClean) {
                                "整页 inpaint 重画背景，纹理连续（慢）"
                            } else {
                                "逐块取色贴背景，不跑 inpaint（快，复杂背景会有色差）"
                            },
                            checked = inpaintClean,
                            onCheckedChange = { enabled ->
                                inpaintClean = enabled
                                settingsRepository.setBrowserInpaintCleanEnabled(enabled)
                                // 已贴的成图是旧设置渲染的：全部丢掉，下次「翻译」按新设置重画。
                                clearRenderedOverlays()
                                stopSession(
                                    if (enabled) "已开启精细擦字（整页 inpaint），点「翻译」重新翻译"
                                    else "已关闭精细擦字（逐块快速贴背景），点「翻译」重新翻译"
                                )
                                menuOpen = false
                                Log.d(TAG, "inpaint_clean_set enabled=$enabled")
                            }
                        )
                        BrowserMenuSwitch(
                            title = "覆盖层渲染",
                            subtitle = if (domOverlay) {
                                "译文画在网页上，滚动更顺滑（推荐）"
                            } else {
                                "整张图替换显示（兼容模式）"
                            },
                            checked = domOverlay,
                            onCheckedChange = { enabled ->
                                domOverlay = enabled
                                settingsRepository.setBrowserDomOverlayEnabled(enabled)
                                // 旧结果是旧模式渲染的：全部丢掉，下次「翻译」按新模式重画。
                                clearRenderedOverlays()
                                stopSession(
                                    if (enabled) "已开启覆盖层渲染，点「翻译」重新翻译"
                                    else "已切换整图替换，点「翻译」重新翻译"
                                )
                                menuOpen = false
                                Log.d(TAG, "dom_overlay_set enabled=$enabled")
                            }
                        )
                        BrowserMenuSearchEngine(
                            engine = searchEngine,
                            onSelect = { engine ->
                                selectSearchEngine(engine)
                                menuOpen = false
                            }
                        )
                        HorizontalDivider(color = MtColors.Border)
                        BrowserMenuItem(
                            icon = MtListIcon,
                            label = "浏览历史（${historyEntries.size}）",
                            onClick = {
                                refreshHistory()
                                browserPage = BrowserPage.HISTORY
                                menuOpen = false
                            }
                        )
                        BrowserMenuItem(
                            icon = null,
                            label = "收藏当前页",
                            onClick = {
                                val target = pageUrl
                                if (target.isBlank() || target == "about:blank") {
                                    Toast.makeText(context, "当前没有打开的网页", Toast.LENGTH_SHORT).show()
                                } else {
                                    favoriteEditor = FavoriteEditor(
                                        originalUrl = null,
                                        label = BrowserFavoriteRepository.defaultLabel(target),
                                        url = target
                                    )
                                }
                                menuOpen = false
                            }
                        )
                        // 「恢复原文」不再放在这里：视图切换（原文/译文）是底栏常驻的分段开关，
                        // 菜单里再放一份只会让「哪个才是权威入口」继续含糊（2026-10-02）。
                        BrowserMenuItem(
                            icon = null,
                            label = "清空浏览历史",
                            danger = true,
                            onClick = {
                                confirmClearHistory = true
                                menuOpen = false
                            }
                        )
                    }
                }
            }
            if (loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = loadProgress / 100f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MtColors.Brand,
                    trackColor = MtColors.Border
                )
            }

            // 网页主体（空态只是加载间隙的过渡，真正的入口在新标签页）。
            Box(modifier = Modifier.weight(1f)) {
                val hasPage = pageUrl.isNotBlank() && pageUrl != "about:blank"
                if (hasPage) {
                    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "正在打开网页…",
                            fontSize = 14.sp,
                            color = MtColors.TextTertiary
                        )
                    }
                }
            }

            // 底栏：两行（2026-10-02 重构）。
            //
            // 为什么拆两行：改造前 7 个元素挤一行，360dp 屏上状态行只剩约 68dp（只能放 ~5 个字），
            // 而且两个**语义完全不同**的控件（视图开关 / 任务开关）是同款胶囊并排 ——
            // 用户读不出「哪个是切视图、哪个是启停翻译」。
            // 现在：行1 = 网页导航 + 状态行；行2 = 「原文｜译文」分段开关 + 「翻译/停止」主按钮。
            val controls = BrowserTranslateUiPolicy.resolve(
                pageLoaded = pageUrl.isNotBlank() && pageUrl != "about:blank",
                running = running,
                reShowBusy = reShowBusy,
                hasRender = hasRenderOnPage,
                mode = viewMode
            )
            Column(modifier = Modifier.background(MtColors.Surface)) {
                HorizontalDivider(color = MtColors.Border)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MtBrowserIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        desc = "网页后退",
                        onClick = { if (webView.canGoBack()) webView.goBack() }
                    )
                    MtBrowserIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        desc = "网页前进",
                        onClick = { if (webView.canGoForward()) webView.goForward() }
                    )
                    MtBrowserIconButton(
                        icon = Icons.Filled.Refresh,
                        desc = "刷新",
                        onClick = { webView.reload() }
                    )
                    // 任务在跑（或正在一次性贴回）时给个转圈：光看按钮变红说明不了「还在动」。
                    if (running || reShowBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = if (running) MtColors.Brand else MtColors.TextTertiary
                        )
                    }
                    Text(
                        text = statusText.ifBlank {
                            BrowserTranslateUiPolicy.idleHint(
                                mode = viewMode,
                                hasRender = hasRenderOnPage,
                                pageLoaded = pageUrl.isNotBlank() && pageUrl != "about:blank"
                            )
                        },
                        fontSize = 11.sp,
                        color = MtColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BrowserViewModeToggle(
                        mode = viewMode,
                        enabled = controls.modeToggleEnabled,
                        disabledReason = controls.modeToggleDisabledReason,
                        onSelect = { target ->
                            when (target) {
                                BrowserViewMode.ORIGINAL -> showOriginal()
                                BrowserViewMode.TRANSLATED -> showTranslations()
                            }
                        }
                    )
                    MtPrimaryButton(
                        text = controls.primaryLabel,
                        onClick = {
                            when (controls.primaryAction) {
                                BrowserPrimaryAction.START -> startSession()
                                // 停止后译文留在屏上（除非本来就没翻出东西），提示要说实话。
                                BrowserPrimaryAction.STOP -> stopSession(
                                    if (placedIds.isNotEmpty()) "已停止 · 译文保留" else "已停止"
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        // 不给「停止」配图标：material-icons-core 里没有 Stop/Pause，
                        // 硬凑一个语义相近的（Close）反而更容易混。红色 + 文案 + 状态行转圈足够。
                        container = if (running) MtColors.Danger else MtColors.Brand,
                        enabled = controls.primaryEnabled,
                        height = 44.dp
                    )
                }
            }
        }
    }

    favoriteEditor?.let { editor ->
        FavoriteEditDialog(
            editor = editor,
            onConfirm = { updated ->
                val message = saveFavorite(updated)
                if (message == "已收藏" || message == "已更新收藏") {
                    favoriteEditor = null
                }
                message
            },
            onDismiss = { favoriteEditor = null }
        )
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("清空浏览历史") },
            text = { Text("将删除全部 ${historyEntries.size} 条访问记录，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { historyRepository.clear() }
                        refreshHistory()
                    }
                    confirmClearHistory = false
                }) {
                    Text("清空", color = MtColors.DangerText)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 连续翻译整页：按页面顺序逐张翻译，翻完不退出，继续轮询等 lazy-load 出来的新图，
 * 直到用户点「停止」或页面跳转（对齐参考项目「预先翻译」，只是改为「已加载的都能翻」）。
 *
 * 截页（中缝截字）链路状态在整轮会话内保持：
 * - `pendingBitmap`：预取的下一张图（下一轮直接当当前图用，省一次下载解码）；
 * - `prevBottomStrip`：上一张图的底部条副本，作当前图的 topStrip；
 * - `incomingCross`：上一张图移交的跨图气泡剩余部分。
 *
 * 渲染模式（2026-09-29）：
 * - 覆盖层（默认）：场景下发 `MtOverlay.applySceneJson`，DOM 里擦原文/画译文；
 *   跨图贴片在移交**前**编码落仓（mtlocal 短 URL），经场景的 patch_url 走拦截通道；
 * - 整图替换（回退）：成图 PNG 落仓 + `placeOverlayJs` 原位贴上。
 *   场景下发失败（渲染层异常）时会话内自动切回整图，对失败的那张图重新走一遍
   （OCR/译文都有缓存，重跑只多一次下载与成图编码）。
 *
 * 视图模式（2026-10-02）：
 * - [isViewVisible] 为 false（用户在「原文」视图）时，会话**照常**扫描/OCR/翻译，
 *   只是**不把译文层贴到页面上**，也不登记 `placedIds` ——
 *   切回「译文」时下一轮就会把这些图重新贴回（`needsReShow`），用户看到的是"秒回"。
 * - [BrowserSessionMode.RE_SHOW_ONLY]：只跑一轮贴回，不 OCR、不下图、不轮询。
 */
private suspend fun runTranslationSession(
    token: Int,
    sessionMode: BrowserSessionMode,
    sessionPageUrl: String,
    userAgent: String?,
    renderedTokens: HashMap<String, String>,
    renderedScenes: LinkedHashMap<String, BrowserSceneBuilder.Scene>,
    placedIds: HashSet<String>,
    seamBySrc: LinkedHashMap<String, PendingSeam>,
    useDomOverlay: Boolean,
    rendererScript: String,
    ensureRenderer: suspend () -> Unit,
    isViewVisible: () -> Boolean,
    onRendered: () -> Unit,
    onFinished: () -> Unit,
    translator: WebtoonImageTranslator,
    downloader: BrowserImageDownloader,
    webView: WebView,
    onStatus: (String) -> Unit,
    onRunningChange: (Boolean) -> Unit,
    onSessionActive: () -> Boolean
) {
    var pendingBitmap: Bitmap? = null
    var pendingSrc: String? = null
    var prevBottomStrip: Bitmap? = null
    /**
     * 底部条带与移交气泡各自的**来源图 src**（2026-10-03）。
     *
     * 为什么必须记：两者都跨 DOM 快照存活，而懒加载阅读器每轮只扫到 1~3 张图 ——
     * 不核对来源就会出现「A 图的条带贴到 B 图上、A 图的移交盖到 B 图上」，
     * 那正是用户截图里「上一页白框中文糊在本页另一段、本页那半行既不擦也不翻」的成因。
     */
    var prevBottomStripSrc: String? = null
    var incomingCrossSrc: String? = null
    var incomingCross: List<CrossBubblePart> = emptyList()
    /** 已打过 `band_pair` 诊断的「图 + 结论」组合（见下面的去重注释）。 */
    val loggedBandPairs = HashSet<String>()
    // 接缝覆盖层上屏后要摘掉的「半块」：imageId → 气泡层 id。
    // 摘的时机必须是「那张图的场景已经上屏之后」，否则摘了个不存在的节点。
    // 会话内事务性使用（登记 → 上屏后消费），所以留在会话局部；
    // 而「贴回要重发的场景」[seamBySrc] 必须跨会话存活，由调用方持有。
    val seamSuppress = HashMap<String, MutableList<String>>()
    // attemptKey -> 尝试次数：无文字的图不反复重翻；下载失败的图有限重试（网络抖动可恢复）。
    val attempts = HashMap<String, Int>()
    // 「处理过但没产出译文」的图（src）：图里确实没文字，或 OCR 有检测但分组没产出气泡。
    // 这类图既不该算「待处理」（否则每轮重跑一遍 OCR），也不该算「已渲染」
    // （否则 needsReShow 会把它当成「被『原文』摘掉了」而每轮重发一次空场景）。
    // 2026-09-30 RIDI 实测：同一张图反复 `scene_ok` 就是这么来的。
    val processedWithoutResult = HashSet<String>()
    // 因「下条带太短」而加高条带重跑过的图（src）：每张只重跑一次（2026-10-03）。
    val stripRetried = HashSet<String>()
    // 「当前扫到的最后一张」等下一页出现的轮询次数（懒加载阅读器，2026-10-03）。
    val nextPageWaits = HashMap<String, Int>()
    // 覆盖层渲染开关：会话内可因场景下发失败自动降级为整图替换。
    var useDom = useDomOverlay
    var doneCount = 0
    var cacheCount = 0
    var failureCount = 0
    var lastWarning: String? = null

    /**
     * 状态行上报：处于「原文」视图时统一加前缀。
     *
     * 为什么集中在这一处：会话里有十来处 `onStatus(...)`，各自加前缀迟早会漏，
     * 漏掉的那一处就会让用户以为「切了原文但翻译停了」。
     */
    fun reportStatus(text: String) {
        onStatus(if (isViewVisible()) text else "原文显示中 · 后台继续翻译 · $text")
    }

    /** 贴图前的统一闸门：不可见时**不贴、不计数、不登记**（否则切回来无法贴回）。 */
    fun canPlace(): Boolean = isViewVisible()

    /** 跨图贴片 → mtlocal 短 URL（覆盖层模式的擦字通道；PNG 整图模式不需要）。 */
    suspend fun encodePatchToken(part: CrossBubblePart): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = part.patchBitmap
            if (bitmap.isRecycled) return@runCatching null
            val bos = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
            val patchToken = "p${System.nanoTime()}"
            BrowserOverlayImageStore.put(patchToken, bos.toByteArray(), "image/png")
            "https://mtlocal.overlay/$patchToken.png"
        }.getOrNull()
    }

    /**
     * 取一张漫画图的位图 —— **按来源类型分派**。
     *
     * | `kind` | 路径 |
     * | --- | --- |
     * | `url` | OkHttp 下载（带页面 Cookie / Referer / UA，有些 CDN 强制校验 Referer） |
     * | `data` | 直接 base64 解码，不走网络 |
     * | `blob` | 页面侧 `toDataURL` 取像素（本页 origin 的对象 URL，Kotlin 取不到） |
     *
     * 另外覆盖一个容易漏的场景：站点用 `<canvas>` 画页图（部分阅读器为了防下载会这么干）
     * 时脚本给不出 `src`，此时 `kind` 仍是 `url` 但元素是 canvas —— 直接走像素提取。
     *
     * ⚠️ **线程**：调用方通常在 `withContext(Dispatchers.IO)` 里，但 WebView 的方法
     * 必须在**创建它的那个线程**（主线程）调用。所以 blob/canvas 分支必须显式切回主线程 ——
     * 2026-09-30 实测踩到：`A WebView method was called on thread 'DefaultDispatcher-worker-1'`
     * 直接把整个会话打挂（RIDI 第一张就是 canvas/blob，等于一张都翻不了）。
     */
    suspend fun fetchImageBitmap(image: ScannedImage): Bitmap? {
        val isCanvas = image.kind == "canvas"
        val bitmap = when {
            // 页面侧取像素：**主线程**（WebView 线程受限）。
            isCanvas || image.kind == "blob" -> withContext(mainThreadDispatcher) {
                webView.extractPagePixels(image, TAG)
            }

            image.src.startsWith("data:", ignoreCase = true) -> withContext(Dispatchers.IO) {
                decodeDataUrl(image.src)
            }

            else -> withContext(Dispatchers.IO) {
                downloadDecoded(downloader, image.src, sessionPageUrl, userAgent)
            }
        }
        if (bitmap == null && (isCanvas || image.kind != "url")) {
            Log.w(
                TAG,
                "image_pixels_unavailable kind=${image.kind} id=${image.id} " +
                    "src=${BrowserHistoryRepository.urlWithoutScheme(image.src)}"
            )
        }
        return bitmap
    }

    /** 场景下发；返回 JSON 结果（ok/reason），渲染层缺失或抛错都按失败处理。 */
    suspend fun dispatchScene(imageId: String, scene: BrowserSceneBuilder.Scene): JSONObject? {
        ensureRenderer()
        return decodeJsJsonResult(
            webView.evaluateJavascriptSuspend(
                applySceneJs(BrowserSceneBuilder.buildSceneJson(imageId, scene))
            )
        )
    }

    fun dropIncomingCross() {
        incomingCross.forEach { part -> runCatching { part.patchBitmap.recycle() } }
        incomingCross = emptyList()
        incomingCrossSrc = null
    }

    /**
     * 接缝覆盖层下发（`MtOverlay.applySeamSceneJson`）：跨两张图的气泡只画一块盖板 + 一份译文。
     *
     * 失败（几何不允许 / 元素已卸载 / 渲染层缺失）时返回 false，调用方**保留**原来的两半渲染，
     * 画面不会变坏。
     */
    suspend fun dispatchSeamScene(ownId: String, nextId: String, seam: SeamBandScene): Boolean {
        ensureRenderer()
        val json = BrowserSceneBuilder.buildSeamSceneJson(
            imageId = ownId,
            nextImageId = nextId,
            seamKey = "$ownId|$nextId",
            cleanedUrl = seam.cleanedUrl,
            bandLeftPx = seam.bandLeftPx,
            bandTopPx = seam.bandTopPx,
            bandWidthPx = seam.bandWidthPx,
            bandHeightPx = seam.bandHeightPx,
            topStripHeightPx = seam.topHPx,
            sourceWidthPx = seam.sourceWidthPx,
            sourceHeightPx = seam.sourceHeightPx,
            bubbles = seam.bubbles
        )
        val decoded = decodeJsJsonResult(webView.evaluateJavascriptSuspend(applySeamSceneJs(json), what = "seam_scene"))
        val ok = decoded?.optBoolean("ok") == true
        // 把**移交出去的那半块带着什么译文**打出来。
        //
        // 为什么必须看这个（2026-09-30 用户报「跨页气泡下半段没译文」）：
        // 下半段的译文有两条来源 —— ① 本图自己 OCR 认出来；② 上一张图把整句翻译好、
        // 用 CrossBubblePart 移交给本图。第 ② 条是兜底，也是「本图只看到残片时」唯一的指望。
        // 只打 ok/bubbles 时，看不出移交内容对不对；把它打出来就能判定
        // 「是移交本身没内容」还是「移交有内容但被本图的检测覆盖/抑制掉了」。
        val seamTexts = seam.bubbles.take(4).joinToString(" ˌ ") { bubble ->
            val own = bubble.originalText.replace('\n', '⏎').take(16)
            val trans = bubble.translatedText.replace('\n', '⏎').take(16)
            "[$own→$trans]"
        }
        Log.d(
            TAG,
            "seam_dispatch own=$ownId next=$nextId ok=$ok " +
                "bubbles=${decoded?.optInt("bubbles") ?: -1} " +
                "covers=${decoded?.optInt("covers") ?: -1} " +
                "texts=$seamTexts " +
                "reason=${decoded?.optString("reason").orEmpty()}"
        )
        return ok
    }

    /**
     * 摘掉接缝上的「半块」：本图那块 / 下一张图那块（由上一张图登记，等下发的图场景上屏后再摘）。
     *
     * 只在下发**成功**之后才摘——接缝没上屏时两半就是唯一的内容。
     */
    suspend fun suppressSeamHalves(imageId: String) {
        val ids = seamSuppress.remove(imageId) ?: return
        if (ids.isEmpty()) return
        val raw = webView.evaluateJavascriptSuspend(suppressBubblesJs(imageId, ids), what = "suppress")
        Log.d(TAG, "seam_suppress image=$imageId bubbles=${ids.size} removed=${raw?.trim()}")
    }

    fun recycleChain() {
        pendingBitmap?.let { if (!it.isRecycled) it.recycle() }
        pendingBitmap = null
        pendingSrc = null
        prevBottomStrip?.let { if (!it.isRecycled) it.recycle() }
        prevBottomStrip = null
        dropIncomingCross()
    }

    /**
     * 一张图的场景贴好之后要做的事（普通路径与「原文 → 翻译」贴回路径共用）。
     *
     * 1. 先摘**排队中**的半块：这张图的场景已经上屏，摘它才安全；
     * 2. 本图产生接缝时：登记两张图各自的半块 → 下发接缝（一块盖板 + 一份译文）→
     *    成功后**立刻**摘掉本图半块（下一张图半块等它自己的场景上屏后再摘）；
     * 3. 下发失败就把刚登记的半块撤销 —— 两半渲染是兜底，绝不能摘。
     */
    suspend fun afterScenePlaced(image: ScannedImage, nextImage: ScannedImage?, seam: SeamBandScene?) {
        suppressSeamHalves(image.id)
        if (seam == null || nextImage == null || !useDom) return
        seamBySrc[image.src] = PendingSeam(scene = seam, nextSrc = nextImage.src)
        while (seamBySrc.size > MAX_RENDERED_SCENES) {
            seamBySrc.remove(seamBySrc.keys.first())
        }
        val own = seamSuppress.getOrPut(image.id) { mutableListOf() }
        own += seam.ownBubbleIds
        val next = seamSuppress.getOrPut(nextImage.id) { mutableListOf() }
        next += seam.nextBubbleIds
        if (dispatchSeamScene(image.id, nextImage.id, seam)) {
            suppressSeamHalves(image.id)
        } else {
            own.removeAll(seam.ownBubbleIds.toSet())
            next.removeAll(seam.nextBubbleIds.toSet())
            if (own.isEmpty()) seamSuppress.remove(image.id)
            if (next.isEmpty()) seamSuppress.remove(nextImage.id)
        }
    }

    fun isPending(image: ScannedImage): Boolean {
        // 已经确认「处理过但没产出译文」的图不再当待处理：否则每轮扫描都会重跑一遍 OCR，
        // 既浪费又占主线程（RIDI 实测里那些反复出现的 image_no_text / image_no_bubbles）。
        if (processedWithoutResult.contains(image.src)) return false
        return renderedTokens[image.src] == null && renderedScenes[image.src] == null &&
            (attempts[image.attemptKey()] ?: 0) < MAX_ATTEMPTS_PER_IMAGE
    }

    /** 已有渲染结果（整图 token 或覆盖层场景，例如刚点过「原文」）：直接贴回，不重新下载/OCR/翻译。 */
    fun needsReShow(image: ScannedImage): Boolean {
        // 排除「处理过但没产出译文」的图：它们**根本没有覆盖层可贴**，
        // 不排除就会每轮都以为被「原文」摘掉了 → 重发一遍空场景，白占主线程。
        if (processedWithoutResult.contains(image.src)) return false
        val hasResult = renderedTokens[image.src] != null || renderedScenes[image.src] != null
        return hasResult && !placedIds.contains(image.id)
    }

    fun statusLine(): String {
        return buildString {
            // 「原文」视图下不能说「已显示 N 张」——屏幕上根本没有译文层（写实，不写愿景）。
            if (placedIds.isNotEmpty() && canPlace()) {
                append("已显示 ${placedIds.size} 张")
            } else {
                append("已翻 $doneCount 张")
            }
            if (cacheCount > 0) append(" · 缓存$cacheCount")
            if (failureCount > 0) append(" · 失败$failureCount")
            // 「处理了但没翻出东西」必须可见：以前这些图是**静默**跳过的，
            // 用户只会看到「某张图没翻」，不知道是识别不到还是程序漏了。
            if (processedWithoutResult.isNotEmpty()) append(" · ${processedWithoutResult.size} 张无译文")
            append(" · 监听新图中…")
        }
    }

    try {
        while (currentCoroutineContext().isActive && onSessionActive()) {
            // 步进计时：每轮扫描「从进入循环到扫完」花多久。
            // 为什么要它：实测出现过 25–56 秒的**整轮空档**（这段时间 App 一行日志都没有），
            // 而单张翻译只要 300–900ms —— 光看 TranslationPerf 完全看不出时间花在哪儿。
            // 有了这个就能直接分辨「是扫描慢」还是「是循环外面/线程被占住」。
            val loopStartedAt = System.currentTimeMillis()
            if (webView.url != sessionPageUrl) {
                reportStatus("页面已跳转，点「翻译」继续")
                return
            }
            val scan = parseScannedImages(webView.evaluateJavascriptSuspend(BrowserScanScript.SCAN_JS, what = "scan"))
            val scanCostMs = System.currentTimeMillis() - loopStartedAt
            if (scanCostMs > 2_000) {
                Log.w(TAG, "scan_slow cost=${scanCostMs}ms（扫描本身慢：页面大或主线程被占）")
            }
            val images = scan.images
            // 「原文」视图下**不算**贴回量：否则每轮都会认为"有待贴回的图"而不进空闲轮询，
            // 变成满速空转扫页面（2026-10-02 视图开关引入时特意避开这个坑）。
            val viewVisible = isViewVisible()
            val pendingTotal = images.count { isPending(it) }
            val reShowTotal = if (viewVisible) images.count { needsReShow(it) } else 0
            if (pendingTotal == 0 && reShowTotal == 0) {
                // 一次性贴回会话：这一轮就是它的全部工作，贴完即退（不轮询、不翻译）。
                if (sessionMode == BrowserSessionMode.RE_SHOW_ONLY) {
                    onStatus(if (placedIds.isNotEmpty()) "已显示 ${placedIds.size} 张译文" else "译文已显示")
                    return
                }
                reportStatus(
                    when {
                        // 「什么都没扫到」是最难自查的一种失败：可能是站点把图画在
                        // <canvas> 里、可能是图还没加载、也可能是规则没命中。
                        // 这里把**画像**直接写进状态行 —— 用户看得见才有得报，
                        // 否则只能得到一句「没反应」，排查全靠猜（2026-09-30 实测）。
                        images.isEmpty() -> emptyScanHint(webView, scan)
                        doneCount == 0 && placedIds.isEmpty() && failureCount > 0 ->
                            "翻译失败 $failureCount 张，稍后自动重试…"

                        else -> statusLine()
                    }
                )
                delay(IDLE_POLL_INTERVAL_MS)
                continue
            }
            Log.d(
                TAG,
                "scan pending=$pendingTotal reshow=$reShowTotal scanned=${images.size} " +
                    "adapter=${scan.adapterId} explicit=${scan.explicitHits} " +
                    "frames=${scan.frameCount} lazy=${images.count { it.lazy }} " +
                    "hosted=${images.count { it.hostId.isNotBlank() }} " +
                    "done=$doneCount shown=${placedIds.size}"
            )

            var batchIndex = 0
            for ((index, image) in images.withIndex()) {
                if (!currentCoroutineContext().isActive || !onSessionActive()) return

                // 「原文 → 译文」贴回：已渲染结果直接重发（不重跑 OCR/翻译）。
                // 隐藏期间既不贴、也不登记 placedIds —— 否则切回「译文」时没有任何触发点。
                if (needsReShow(image) && canPlace()) {
                    val cachedScene = renderedScenes[image.src]
                    if (useDom && cachedScene != null) {
                        val result = dispatchScene(image.id, cachedScene)
                        if (result != null && result.optBoolean("ok")) {
                            placedIds += image.id
                            onRendered()
                        }
                    } else {
                        val cachedToken = renderedTokens[image.src]
                        if (cachedToken != null) {
                            placedIds += image.id
                            webView.evaluateJavascript(placeOverlayJs(image, cachedToken), null)
                            onRendered()
                        }
                    }
                    // 「原文 → 译文」贴回：接缝覆盖层必须跟着重发（两半已被摘掉）。
                    if (useDom && placedIds.contains(image.id)) {
                        val nextImage = images.getOrNull(index + 1)
                        val cachedSeam = seamBySrc[image.src]
                            ?.takeIf { it.nextSrc == nextImage?.src }
                            ?.scene
                        afterScenePlaced(image, nextImage, cachedSeam)
                    }
                    continue
                }
                // 一次性贴回会话不做任何翻译工作。
                if (sessionMode == BrowserSessionMode.RE_SHOW_ONLY) continue
                if (!isPending(image)) continue
                batchIndex += 1
                reportStatus(
                    "翻译中 · 已完成 $doneCount 张" +
                        if (pendingTotal > 1) "（本轮 $batchIndex/$pendingTotal）" else ""
                )
                // ---- 跨切片续排的配对判定（2026-10-03）----
                //
                // 条带（上一张图的底部条）、下条带、接缝带、移交气泡的作用范围**只有一条**：
                // 「本图正上方/正下方紧邻的那一片」。而这些都是跨 DOM 快照存活的，懒加载阅读器
                // 每轮只扫到 1~3 张图，扫描列表里还混着离得很远的图（推广 banner 实测相距 46000px）：
                //   · 上一张错配 → 条带里的别处文字被并进本图、那块被当「已翻过」丢掉（`[32317270]`）；
                //   · 下一张错配 → 下条带/接缝带对着 banner → 渲染层以 `page_gap` 拒掉接缝带，
                //     退回「两半各画一份」（`[5467219]` 用户截图里的糊字）。
                // 所以两侧都按**几何**找紧邻的那一张（不看扫描顺序），不合格就当独立图处理。
                val curBox = StripBox(
                    src = image.src,
                    left = image.left,
                    top = image.top,
                    width = image.width,
                    height = image.height,
                    hostId = image.hostId,
                    frame = image.frame
                )
                val scanBoxes = images.map { it.toStripBox() }
                val prevBox = SeamNeighbourPolicy.predecessor(curBox, scanBoxes)
                val prevImage = prevBox?.let { box -> images.firstOrNull { it.src == box.src } }
                val neighbour = SeamNeighbourPolicy.decide(prevBox, curBox)
                val neighbourSrc = prevBox?.src
                // 「下一张」：decide(prev, cur) 问的是「prev 是否紧邻在 cur 上方」，
                // 这里把本图当 prev、候选当 cur —— 即「本图是否紧邻在候选上方」。
                val nextBoxCandidate = SeamNeighbourPolicy.successor(curBox, scanBoxes)
                val nextNeighbour = if (nextBoxCandidate == null) {
                    NeighbourDecision(contiguous = false, reason = "no_next")
                } else {
                    SeamNeighbourPolicy.decide(curBox, nextBoxCandidate)
                }
                val nextImage = if (nextNeighbour.contiguous) {
                    images.firstOrNull { it.src == nextBoxCandidate?.src }
                } else {
                    null
                }
                // 每次判定都打会刷屏（最后一张要等下一页出现，会连判好几轮）→ 同一「图 + 结论」
                // 只打一次；结论变了（上一张/下一张出现、或配对原因变化）立刻再打一条。
                val bandPairKey = "${image.src}|${neighbour.reason}|${nextNeighbour.reason}"
                if (loggedBandPairs.add(bandPairKey)) {
                    Log.d(
                        TAG,
                        "band_pair cur=${BrowserHistoryRepository.urlWithoutScheme(image.src)} " +
                            "prev=${neighbourSrc?.let { BrowserHistoryRepository.urlWithoutScheme(it) } ?: "none"} " +
                            "adjacent=${neighbour.contiguous} reason=${neighbour.reason} " +
                            "gap=${if (prevBox != null) curBox.top - prevBox.bottom else -1} " +
                            "next=${nextBoxCandidate?.src?.let { BrowserHistoryRepository.urlWithoutScheme(it) } ?: "none"} " +
                            "next_adjacent=${nextNeighbour.contiguous} next_reason=${nextNeighbour.reason} " +
                            "next_gap=${if (nextBoxCandidate != null) nextBoxCandidate.top - curBox.bottom else -1} " +
                            "w=${image.width} prevW=${prevBox?.width ?: 0} nextW=${nextBoxCandidate?.width ?: 0}"
                    )
                }
                // 懒加载阅读器（Lezhin 实测）：翻到新页时**下一张 `<img>` 往往还没出现**，
                // 此时直接处理这一张会拿不到下条带（`botH=0`）→ 跨缝链路整条不生效，
                // 被缝切开的句子下半段永远留在原文（真机日志 `seam_strip_short band=0px`）。
                // 所以「当前扫到的最后一张」先等几个轮询（每轮 ~1.2s，最多 [NEXT_PAGE_WAIT_ROUNDS] 轮），
                // 等下一页露出来再处理；等不到就照常处理 —— 不能无限等（章节最后一页就是没有下一张）。
                // ⚠️ 只对「页面上确实没有下一张」等待：判成 gap/width_mismatch 的是**错配**，
                // 再等也不会变对，只会白等几轮（`nextBoxCandidate != null` 时不等）。
                if (nextImage == null && nextBoxCandidate == null) {
                    val waited = nextPageWaits[image.src] ?: 0
                    if (waited < NEXT_PAGE_WAIT_ROUNDS) {
                        nextPageWaits[image.src] = waited + 1
                        continue
                    }
                }
                val imageStartedAt = System.currentTimeMillis()
                // loading 框：开始处理这张图就浮上去（对齐参考项目 `.mt-loading-card`）。
                // 只在「译文」视图下挂 —— 切到「原文」时用户要的就是干净原图。
                // 阶段文案由渲染管线回调（WebtoonPhase），这里只负责落到页面上。
                fun setLoadingLabel(label: String) {
                    if (!canPlace()) return
                    webView.evaluateJavascript(showLoadingJs(image.id, label)) { result ->
                        // 渲染层明确拒绝（no_target / 缺渲染层 / 抛异常）才告警：正常路径每张图
                        // 每个阶段都会走一次，成功也打日志就是刷屏。卡片"不出现"时，
                        // 这一行是唯一能区分「没调用」还是「调了但被拒」的证据。
                        if (result != null && result.contains("\"ok\":false")) {
                            Log.w(TAG, "loading_card_reject image=${image.id} label=$label result=$result")
                        }
                    }
                }
                setLoadingLabel(LOADING_FETCHING)
                val outcome = withContext(Dispatchers.IO) {
                    // 当前图：优先用上一轮预取的位图（同一 URL 已解码过）。
                    val current = if (image.src == pendingSrc && pendingBitmap != null) {
                        pendingBitmap!!
                    } else {
                        pendingBitmap?.recycle()
                        fetchImageBitmap(image)
                    }
                    pendingBitmap = null
                    pendingSrc = null
                    if (current == null) return@withContext null
                    // 配对判定已在进入 withContext 之前算好（见上面的 `band_pair`）：
                    // 这里只做位图相关的活（补裁条带、预取下一张、编码贴片）。
                    val topStripSource = if (!neighbour.contiguous) {
                        prevBottomStrip?.recycle()
                        null
                    } else if (prevBottomStrip != null && prevBottomStripSrc == neighbourSrc) {
                        prevBottomStrip
                    } else {
                        prevBottomStrip?.recycle()
                        val prev = prevImage?.let { fetchImageBitmap(it) }
                        val strip = prev?.let { cropBottomStrip(it, current.width) }
                        prev?.recycle()
                        strip
                    }
                    prevBottomStrip = null
                    prevBottomStripSrc = null
                    // 移交气泡同理：来源不是本图正上方那张 → 整批丢弃（贴片要回收）。
                    val incomingUsed = if (neighbour.contiguous && incomingCrossSrc == neighbourSrc) {
                        incomingCross
                    } else {
                        val dropped = incomingCross.size
                        if (dropped > 0) {
                            Log.d(
                                TAG,
                                "incoming_dropped reason=${if (neighbour.contiguous) "stale_src" else neighbour.reason} " +
                                    "count=$dropped cur=${BrowserHistoryRepository.urlWithoutScheme(image.src)} " +
                                    "prev=${neighbourSrc?.let { BrowserHistoryRepository.urlWithoutScheme(it) } ?: "none"}"
                            )
                        }
                        dropIncomingCross()
                        emptyList()
                    }
                    Log.d(
                        TAG,
                        "band_prep cur=${BrowserHistoryRepository.urlWithoutScheme(image.src)} " +
                            "band=${topStripSource?.height ?: 0} incoming=${incomingUsed.size}"
                    )
                    // 下一张图：预取解码，裁顶部条作 bottomStrip（跨下边界气泡的下半文）。
                    val next = nextImage?.let { fetchImageBitmap(it) }
                    pendingBitmap = next
                    pendingSrc = nextImage?.src
                    // 当前图的底部条副本留给下一轮当 topStrip（current 会被翻译管线回收）。
                    prevBottomStrip = cropBottomStrip(current, current.width)
                    prevBottomStripSrc = image.src
                    // 覆盖层模式：移交的贴片先编码落仓（translateImage 会回收贴片位图）。
                    if (useDom && incomingUsed.isNotEmpty()) {
                        incomingCross = incomingUsed.map { part ->
                            if (part.patchUrl != null) part else part.copy(patchUrl = encodePatchToken(part))
                        }
                    }
                    // ⚠️ 顶部条**原件**会被 translateImage 回收，重跑需要同一份内容 → 留一个副本。
                    val topStripBackup = topStripSource?.let {
                        runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                    }
                    suspend fun runOnce(
                        source: Bitmap,
                        topStrip: Bitmap?,
                        bottomStrip: Bitmap?,
                        incoming: List<CrossBubblePart>
                    ): com.manga.translator.app.WebtoonImageTranslator.Outcome =
                        translator.translateImage(
                            source, topStrip, bottomStrip, incoming,
                            renderMode = if (useDom) BrowserRenderMode.DOM_SCENE else BrowserRenderMode.FULL_PNG,
                            // 阶段回调可能在 IO 线程上（擦字与翻译请求并发）：WebView 线程受限，
                            // 必须 post 回主线程再下发文案（`mainHandler` 见文件末尾说明）。
                            onPhase = { label -> mainHandler.post { setLoadingLabel(label) } }
                        )

                    // 重跑要用的移交贴片副本：`translateImage` 会回收传进去的贴片位图，
                    // 所以要在**第一次调用之前**先复制一份（2026-10-03：真机实测「有移交的图
                    // 恰恰是最需要重跑的那张」——上一张条带太短只交出 134px，这张看到 258px
                    // → 覆盖率 52% < 60% → 又翻一遍 → 缝上两层）。
                    val incomingBackup = if (incomingUsed.isEmpty()) {
                        emptyList()
                    } else {
                        incomingUsed.mapNotNull { part ->
                            runCatching {
                                part.copy(patchBitmap = part.patchBitmap.copy(Bitmap.Config.ARGB_8888, false))
                            }.getOrNull()
                        }
                    }

                    val first = runOnce(
                        current,
                        topStripSource,
                        next?.let { cropTopStrip(it, current.width) },
                        incomingUsed
                    )
                    // 条带太短 → 加高条带重跑一次（2026-10-03）。
                    //
                    // 触发条件（真机实测 RIDI/Lezhin 都有）：`seam_strip_short` —— 跨缝气泡的
                    // 检测框顶到了拼接图底边，说明默认带宽装不下被切开的内容，句子被截断着读
                    // （`…접속게이트로 돌`），译文串味，且移交深度比下一张图看到的浅 →
                    // 下一张图又认领一次 → 缝上两层译文。
                    // 每条 URL 只重跑一次；有移交时用副本（见 incomingBackup）。
                    val hasIncoming = incomingUsed.isEmpty() ||
                        incomingBackup.size == incomingUsed.size
                    val canRetry = first.stripTruncated && next != null && hasIncoming &&
                        stripRetried.add(image.src)
                    if (canRetry) {
                        val retrySource = fetchImageBitmap(image)
                        if (retrySource != null) {
                            val taller = retryStripHeightPx(retrySource.width, next!!.height)
                            Log.d(
                                TAG,
                                "seam_strip_retry url=${BrowserHistoryRepository.urlWithoutScheme(image.src)} " +
                                    "band=${taller}px incoming=${incomingBackup.size}"
                            )
                            val second = runOnce(
                                retrySource,
                                topStripBackup,
                                cropTopStrip(next, retrySource.width, taller),
                                incomingBackup
                            )
                            if (second.stripTruncated) {
                                Log.w(
                                    TAG,
                                    "seam_strip_still_short url=${BrowserHistoryRepository.urlWithoutScheme(image.src)} " +
                                        "band=${taller}px"
                                )
                            }
                            // 第一次的跨图贴片没人接手了，必须自己回收。
                            first.crossOut.forEach { part -> runCatching { part.patchBitmap.recycle() } }
                            return@withContext second
                        }
                    }
                    // 没重跑（或取图失败）：副本没人用，回收掉。
                    incomingBackup.forEach { part -> runCatching { part.patchBitmap.recycle() } }
                    topStripBackup?.let { if (!it.isRecycled) it.recycle() }
                    first
                }
                // 单张图的**端到端**耗时（取位图 + 解码 + 预取 + OCR + 翻译 + 擦字 + 渲染/下发）。
                // 与 TranslationPerf 的 render_done 对照：后者只覆盖 OCR 之后的管线，
                // 差出来的部分就是「取图/解码/预取」的成本 —— 卡顿时用它定位是谁占着不放。
                val imageCostMs = System.currentTimeMillis() - imageStartedAt
                if (imageCostMs > 5_000) {
                    Log.w(
                        TAG,
                        "image_slow cost=${imageCostMs}ms kind=${image.kind} " +
                            "src=${BrowserHistoryRepository.urlWithoutScheme(image.src)}"
                    )
                }
                val png = outcome?.renderedPng
                // 「有结果但一个气泡都没有」：OCR 有检测、分组没产出（实测 `detected=2 finals=0`
                // 和 `detected=0 blocks=3 finals=2` 两种形态都有）。
                // 当成「已翻」会带来两个真实故障（2026-09-30 RIDI 实测）：
                //   1. 图写进 renderedScenes → needsReShow 永远为真 → **每轮扫描都重发一次**，
                //      白占主线程（日志里同一张图反复 scene_ok 就是这么来的）；
                //   2. 用户看到的却是「原图、没有覆盖层」，而它又不再重试。
                // 所以单独记一笔：不当已翻、也不再重发。
                val emptyScene = outcome != null && outcome.scene != null && outcome.bubbleCount == 0
                if (emptyScene || outcome == null || (outcome.scene == null && png == null)) {
                    // 下载失败或图里没有文字：断开跨图链（贴片与条带都不再可用），
                    // 下一张图退回无拼接模式，避免引用已回收的位图。
                    when {
                        outcome == null -> {
                            failureCount += 1
                            attempts[image.attemptKey()] = (attempts[image.attemptKey()] ?: 0) + 1
                            Log.w(TAG, "image_fail url=${BrowserHistoryRepository.urlWithoutScheme(image.src)}")
                        }

                        emptyScene -> {
                            processedWithoutResult += image.src
                            attempts[image.attemptKey()] = MAX_ATTEMPTS_PER_IMAGE
                            Log.d(
                                TAG,
                                "image_no_bubbles url=${BrowserHistoryRepository.urlWithoutScheme(image.src)}"
                            )
                        }

                        else -> {
                            // 确定性结果（图里没有文字）：本轮不再重试。
                            processedWithoutResult += image.src
                            attempts[image.attemptKey()] = MAX_ATTEMPTS_PER_IMAGE
                            Log.d(TAG, "image_no_text url=${BrowserHistoryRepository.urlWithoutScheme(image.src)}")
                        }
                    }
                    prevBottomStrip?.recycle()
                    prevBottomStrip = null
                    dropIncomingCross()
                } else {
                    attempts[image.attemptKey()] = MAX_ATTEMPTS_PER_IMAGE
                    doneCount += 1
                    if (outcome.cacheTotalCount > 0 && outcome.cacheHitCount == outcome.cacheTotalCount) {
                        cacheCount += 1
                    }
                    outcome.warningMessage?.let { lastWarning = it }
                    incomingCross = outcome.crossOut
                    incomingCrossSrc = if (outcome.crossOut.isEmpty()) null else image.src
                    val scene = outcome.scene
                    if (scene != null && useDom) {
                        // 覆盖层渲染：场景下发，WebView 里擦原文/画译文。
                        // 场景**总是**先记下来（含「原文」视图下）：切回「译文」时才贴得回去。
                        renderedScenes[image.src] = scene
                        if (renderedScenes.size > MAX_RENDERED_SCENES) {
                            renderedScenes.remove(renderedScenes.keys.first())
                        }
                        // 「原文」视图：只记结果，不上屏。会话照常往下翻译（用户选定的模型），
                        // 切回「译文」时下一轮 needsReShow 会把这一批一起贴回。
                        if (!canPlace()) {
                            Log.d(
                                TAG,
                                "scene_cached_hidden bubbles=${scene.bubbles.size} done=$doneCount"
                            )
                        } else {
                            val result = dispatchScene(image.id, scene)
                            val ok = result != null && result.optBoolean("ok")
                            if (ok) {
                                placedIds += image.id
                                onRendered()
                                Log.d(
                                    TAG,
                                    "scene_ok bubbles=${result!!.optInt("bubbles")} " +
                                        "rebuilt=${result.optBoolean("rebuilt")} host=${result.optString("host")} " +
                                        // 场景气泡计数（owned/cross/incoming/final）：跨页气泡的
                                        // **下半那半**丢在哪一步，看这四个数就知道
                                        // （用户 2026-09-30 反馈「跨页气泡下半段没译文」）。
                                        "(${outcome.sceneDiag}) done=$doneCount"
                                )
                            } else {
                                val reason = result?.optString("reason").orEmpty()
                                renderedScenes.remove(image.src)
                                if (reason == "no_target") {
                                    // 图在扫描与下发之间被站点卸载：不算渲染层故障，等下一轮扫描。
                                    Log.w(TAG, "scene_skip reason=no_target")
                                } else {
                                    // 渲染层缺失/异常：整段会话降级为整图替换，这张图下一轮重翻
                                    // （OCR/译文有缓存，重跑只多一次下载与成图编码）。
                                    useDom = false
                                    attempts[image.attemptKey()] = MAX_ATTEMPTS_PER_IMAGE - 1
                                    failureCount += 1
                                    reportStatus("覆盖层渲染失败，已切换整图模式")
                                    Log.w(TAG, "scene_fail reason=$reason fallback=full_png")
                                }
                            }
                        }
                    } else if (png != null) {
                        val overlayToken = "t${System.nanoTime()}"
                        BrowserOverlayImageStore.put(overlayToken, png)
                        renderedTokens[image.src] = overlayToken
                        if (canPlace()) {
                            placedIds += image.id
                            webView.evaluateJavascript(placeOverlayJs(image, overlayToken), null)
                            onRendered()
                            Log.d(
                                TAG,
                                "image_ok bubbles=${outcome.bubbleCount} png=${png.size}B " +
                                    "cross_out=${outcome.crossOut.size} done=$doneCount"
                            )
                        } else {
                            Log.d(TAG, "image_cached_hidden png=${png.size}B done=$doneCount")
                        }
                    }
                    // 覆盖层模式：本图场景已上屏 → 处理接缝覆盖层（下发 + 摘掉两半）。
                    if (useDom && placedIds.contains(image.id)) {
                        afterScenePlaced(image, nextImage, outcome.seam)
                    }
                }
                // 这张图处理完了（上屏 / 图里没文字 / 失败都一样）：把 loading 框摘掉。
                // 漏掉任何一条出口，页面上就会永久留一个"正在翻译"的胶囊。
                webView.evaluateJavascript(hideLoadingJs(image.id), null)
            }
            // 一次性贴回（「译文」视图开关）：这一轮就是全部工作，不做轮询。
            if (sessionMode == BrowserSessionMode.RE_SHOW_ONLY) {
                onStatus(
                    if (placedIds.isNotEmpty()) {
                        "已显示 ${placedIds.size} 张译文"
                    } else {
                        "译文已显示"
                    }
                )
                return
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (err: Throwable) {
        Log.w(TAG, "session_fail ${err.javaClass.simpleName}: ${err.message}")
        if (onSessionActive()) {
            reportStatus("翻译失败：${err.message?.take(50) ?: "未知错误"}")
        }
    } finally {
        recycleChain()
        // loading 框的安全网：会话被取消（点「停止」/ 页面跳转 / 离开浏览器 tab）时，
        // 循环里那条「处理完就摘」可能永远走不到 —— 页面上会留一个假的"正在翻译"。
        // 这时 WebView 可能正在销毁，所以整段 runCatching。
        runCatching { webView.evaluateJavascript(CLEAR_LOADINGS_JS, null) }
        if (onSessionActive()) {
            onRunningChange(false)
            // 贴回会话结束时必须复位 busy：否则底栏两个控件会永久禁用（最坏情况是用户只能杀掉 App）。
            onFinished()
            lastWarning?.let { Log.d(TAG, "session_warning=$it") }
            Log.d(
                TAG,
                "session_end token=$token mode=$sessionMode done=$doneCount shown=${placedIds.size} " +
                    "cache=$cacheCount fail=$failureCount"
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 设置菜单（网页态）
// ---------------------------------------------------------------------------

@Composable
private fun BrowserMenuSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 14.sp, color = MtColors.TextPrimary, maxLines = 1)
            Text(text = subtitle, fontSize = 11.sp, color = MtColors.TextTertiary, maxLines = 1)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 设置菜单里的「站点适配」一行：显示当前页面命中的适配器 + 一个开关。
 *
 * 文案分三种情况，对应三种真实处境（用户不需要懂 DOM，但要能区分「谁的问题」）：
 * - 命中具体站点：`已适配：Kakao Page` —— 规则生效，扫不到图就该看别的方向；
 * - 通用兜底：`通用规则` —— 没为这个站写规则，靠懒加载/srcset/滚动容器这些通用修正；
 * - 关掉时：`已停用` —— 用户自己关的，不是 bug。
 */
@Composable
private fun BrowserMenuSiteAdapter(
    adapter: BrowserSiteAdapter,
    rulesEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val isGeneric = adapter.id == BrowserSiteAdapter.GENERIC.id
    val title = if (isGeneric) "站点适配（通用规则）" else "站点适配：${adapter.label}"
    val subtitle = when {
        !rulesEnabled && !isGeneric -> "已停用这个站的专用规则（只跑通用修正）"
        !rulesEnabled -> "已停用（只跑最小判据）"
        isGeneric -> "未识别站点，按通用启发式找图（懒加载 / srcset / 背景图都已生效）"
        adapter.imageSelectors.isNotEmpty() -> "已套用 ${adapter.imageSelectors.size} 条取图规则"
        else -> "已套用站点规则"
    }
    BrowserMenuSwitch(
        title = title,
        subtitle = subtitle,
        checked = rulesEnabled,
        onCheckedChange = onToggle
    )
}

/**
 * 设置菜单里的「搜索引擎」选择：三大引擎平铺可点，当前项打勾。
 *
 * 2026-09-28 用户反馈：原来关键词搜索写死 Google，国内网络打不开。
 * 默认改成必应（`cn.bing.com`，国内可直连），用户可切换到百度或 Google。
 */
@Composable
private fun BrowserMenuSearchEngine(
    engine: BrowserSearchEngine,
    onSelect: (BrowserSearchEngine) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)
    ) {
        Text(
            text = "搜索引擎（关键词搜索用）",
            fontSize = 14.sp,
            color = MtColors.TextPrimary,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrowserSearchEngine.entries.forEach { candidate ->
                SearchEngineChip(
                    engine = candidate,
                    selected = candidate == engine,
                    onClick = { onSelect(candidate) }
                )
            }
        }
    }
}

/** 搜索引擎芯片：选中态用品牌色描边 + 打勾。 */
@Composable
private fun SearchEngineChip(
    engine: BrowserSearchEngine,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MtColors.BrandTint else MtColors.SurfaceMuted)
            .border(
                width = 1.dp,
                color = if (selected) MtColors.Brand else MtColors.Border,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MtColors.BrandInk,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = engine.shortLabel,
            fontSize = 12.sp,
            color = if (selected) MtColors.BrandInk else MtColors.TextSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun BrowserMenuItem(
    icon: ImageVector?,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (danger) MtColors.DangerText else MtColors.TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            text = label,
            fontSize = 14.sp,
            color = if (danger) MtColors.DangerText else MtColors.TextPrimary,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------
// 新标签页 / 历史记录页（设计稿 730751285322573，2026-09-28 定稿）
// ---------------------------------------------------------------------------

/** 历史行左侧圆形徽标的字母：取主域段首字母（跳过 www/m/page/series 等子域前缀）。 */
private val BADGE_SKIP_HOST_PREFIXES = setOf(
    "www", "m", "mobile", "page", "series", "webtoon", "webtoons", "comic", "novel"
)

private fun badgeLetter(url: String): String {
    val host = url
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore(':')
    val segment = host.split('.').firstOrNull { it.isNotBlank() && it !in BADGE_SKIP_HOST_PREFIXES }
    return (segment ?: host).take(1).uppercase()
}

/**
 * 新标签页：搜索行（可选「返回网页」+ 搜索框 + 跳转钮）+ 搜索引擎芯片 + 收藏格 + 最近历史
 * + 主底栏（浏览器 tab 选中）。
 */
@Composable
private fun BrowserNewTabPage(
    search: String,
    onSearchChange: (String) -> Unit,
    onSubmit: () -> Unit,
    searchEngine: BrowserSearchEngine,
    onSelectSearchEngine: (BrowserSearchEngine) -> Unit,
    /** 有打开的网页时显示「返回网页」按钮（用户要求：一键马上回到浏览器界面）。 */
    showBackToPage: Boolean,
    onBackToPage: () -> Unit,
    favorites: List<BrowserFavorite>,
    favoritesEditing: Boolean,
    onToggleFavoritesEditing: () -> Unit,
    onAddFavorite: () -> Unit,
    onEditFavorite: (BrowserFavorite) -> Unit,
    onDeleteFavorite: (String) -> Unit,
    recent: List<BrowserHistoryEntry>,
    onOpenUrl: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onSelectTab: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MtColors.Surface)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 搜索行：搜索框占满剩余宽度，跳转钮紧贴其右（原来固定 238dp + SpaceBetween
            // 会在宽屏上拉出一大段空隙 —— 2026-09-28 用户反馈「距离太大」）。
            // 有打开的网页时左侧多一个「返回网页」箭头：新标签页不再有去无回。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showBackToPage) {
                    MtBrowserIconButton(
                        icon = MtChevronLeftIcon,
                        desc = "返回网页",
                        onClick = onBackToPage,
                        size = 44
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(MtColors.SurfaceMuted)
                        .padding(start = 18.dp, end = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = MtSearchIcon,
                            contentDescription = null,
                            tint = MtColors.TextTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = search,
                                onValueChange = onSearchChange,
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = 15.sp,
                                    color = MtColors.TextPrimary
                                ),
                                cursorBrush = SolidColor(MtColors.Brand),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (search.isEmpty()) {
                                Text(
                                    text = "搜索或输入网址",
                                    fontSize = 15.sp,
                                    color = MtColors.TextTertiary,
                                    maxLines = 1
                                )
                            }
                        }
                        if (search.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "清空",
                                tint = MtColors.TextTertiary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onSearchChange("") }
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MtColors.BrandTint)
                        .clickable(onClick = onSubmit),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = MtArrowRightIcon,
                        contentDescription = "打开",
                        tint = MtColors.BrandInk,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // 搜索引擎：关键词搜索走哪个引擎（默认必应；Google 在国内网络打不开，
            // 用户可在这里一眼看清当前用的是哪个并一键切换 —— 2026-09-28 用户反馈）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "搜索用",
                    fontSize = 12.sp,
                    color = MtColors.TextTertiary,
                    maxLines = 1
                )
                BrowserSearchEngine.entries.forEach { candidate ->
                    SearchEngineChip(
                        engine = candidate,
                        selected = candidate == searchEngine,
                        onClick = { onSelectSearchEngine(candidate) }
                    )
                }
            }

            // 收藏区标题 +「编辑 / 完成」（编辑态可删除、改名；收藏由用户自己添加）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "收藏",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MtColors.TextPrimary
                )
                if (favorites.isNotEmpty()) {
                    Text(
                        text = if (favoritesEditing) "完成" else "编辑",
                        fontSize = 13.sp,
                        color = MtColors.BrandInk,
                        modifier = Modifier.clickable(onClick = onToggleFavoritesEditing)
                    )
                }
            }

            // 收藏格：4 列，末尾固定一个「添加」格
            if (favorites.isEmpty()) {
                Text(
                    text = "还没有收藏，点「+」把常看的漫画站加进来",
                    fontSize = 13.sp,
                    color = MtColors.TextTertiary
                )
            }
            val tiles = buildList<Any> {
                addAll(favorites)
                add(ADD_FAVORITE_TILE)
            }
            tiles.chunked(4).forEach { rowTiles ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    rowTiles.forEach { tile ->
                        when (tile) {
                            is BrowserFavorite -> FavoriteTile(
                                favorite = tile,
                                editing = favoritesEditing,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (favoritesEditing) onEditFavorite(tile) else onOpenUrl(tile.url)
                                },
                                onDelete = { onDeleteFavorite(tile.url) }
                            )

                            else -> AddFavoriteTile(
                                modifier = Modifier.weight(1f),
                                onClick = onAddFavorite
                            )
                        }
                    }
                    // 补齐空位，保证每格宽度一致
                    repeat(4 - rowTiles.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            // 历史区标题 +「查看全部」
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "历史记录",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MtColors.TextPrimary
                )
                Text(
                    text = "查看全部",
                    fontSize = 13.sp,
                    color = MtColors.BrandInk,
                    modifier = Modifier.clickable(onClick = onOpenHistory)
                )
            }

            // 最近历史（平铺 4 行：今天/昨天显示时刻或「昨天」，更早显示 M/d）
            if (recent.isEmpty()) {
                Text(
                    text = "还没有访问记录",
                    fontSize = 13.sp,
                    color = MtColors.TextTertiary
                )
            } else {
                val today = remember { LocalDate.now() }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    recent.forEach { entry ->
                        BrowserHistoryRow(
                            entry = entry,
                            today = today,
                            grouped = false,
                            onClick = { onOpenUrl(entry.url) }
                        )
                    }
                }
            }
        }

        MainBottomBar(selectedIndex = MainTab.BROWSER.ordinal, onSelect = onSelectTab)
    }
}

/**
 * 收藏格：圆形站点图标（**站点自己的 favicon**，加载失败/没有才用域名首字母兜底）。
 * 编辑态时右上角出现删除角标，点击格子变成「编辑」而不是打开网址。
 */
@Composable
private fun FavoriteTile(
    favorite: BrowserFavorite,
    editing: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.size(56.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MtColors.SurfaceMuted)
                    .border(1.dp, MtColors.Border, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                val favicon = rememberFavicon(favorite.url)
                if (favicon != null) {
                    Image(
                        bitmap = favicon.asImageBitmap(),
                        contentDescription = favorite.label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                } else {
                    Text(
                        text = badgeLetter(favorite.url),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MtColors.BrandInk
                    )
                }
            }
            if (editing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MtColors.Danger)
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "删除收藏",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Text(
            text = favorite.label,
            fontSize = 12.sp,
            color = MtColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 收藏格末尾的「添加」格。 */
@Composable
private fun AddFavoriteTile(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MtColors.SurfaceMuted)
                .border(1.dp, MtColors.Border, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "添加收藏",
                tint = MtColors.TextSecondary,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = "添加",
            fontSize = 12.sp,
            color = MtColors.TextSecondary,
            maxLines = 1
        )
    }
}

/** favicon 异步加载（内存缓存命中则同步返回，避免闪烁）。 */
@Composable
private fun rememberFavicon(url: String): Bitmap? {
    val key = remember(url) { BrowserFavoriteRepository.originOf(url) ?: url }
    var bitmap by remember(key) { mutableStateOf(SiteFaviconLoader.cached(key)) }
    LaunchedEffect(key) {
        if (bitmap == null && !SiteFaviconLoader.isKnown(key)) {
            bitmap = SiteFaviconLoader.load(key)
        }
    }
    return bitmap
}

/** 收藏新增/编辑弹窗：名称可留空（留空用域名），网址必填。 */
@Composable
private fun FavoriteEditDialog(
    editor: FavoriteEditor,
    onConfirm: (FavoriteEditor) -> String,
    onDismiss: () -> Unit
) {
    var label by remember(editor) { mutableStateOf(editor.label) }
    var url by remember(editor) { mutableStateOf(editor.url) }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editor.originalUrl == null) "添加收藏" else "编辑收藏") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("网址") },
                    placeholder = { Text("https://page.kakao.com") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text("名称（可留空）") },
                    placeholder = { Text(BrowserFavoriteRepository.defaultLabel(url)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val message = onConfirm(FavoriteEditor(editor.originalUrl, label, url))
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val message = onConfirm(FavoriteEditor(editor.originalUrl, label, url))
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }) {
                Text("保存", color = MtColors.BrandInk)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 历史记录完整页：今天 / 昨天 / 更早 分组列表 + 主底栏。 */
@Composable
private fun BrowserHistoryPage(
    entries: List<BrowserHistoryEntry>,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onClear: () -> Unit,
    onSelectTab: (Int) -> Unit
) {
    val today = remember { LocalDate.now() }
    val groups = remember(entries, today) {
        buildList {
            entries.filter { historyDayKey(it.lastVisitMs) == today }
                .takeIf { it.isNotEmpty() }
                ?.let { add("今天" to it) }
            entries.filter { historyDayKey(it.lastVisitMs) == today.minusDays(1) }
                .takeIf { it.isNotEmpty() }
                ?.let { add("昨天" to it) }
            entries.filter {
                val day = historyDayKey(it.lastVisitMs)
                day != today && day != today.minusDays(1)
            }.takeIf { it.isNotEmpty() }
                ?.let { add("更早" to it) }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MtColors.Surface)
            .statusBarsPadding()
    ) {
        // 顶栏 56dp：44dp 返回键（corner 22）+ 17px 标题 + 13px 红色「清空」。画布 2:123。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = MtChevronLeftIcon,
                    contentDescription = "返回",
                    tint = MtColors.TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = "历史记录",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MtColors.TextPrimary,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp)
            )
            if (entries.isNotEmpty()) {
                Text(
                    text = "清空",
                    fontSize = 13.sp,
                    color = MtColors.DangerText,
                    modifier = Modifier
                        .clickable(onClick = onClear)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(id = com.manga.translator.app.R.drawable.ic_empty_box),
                        contentDescription = null,
                        modifier = Modifier.size(120.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "还没有访问记录",
                        fontSize = 13.sp,
                        color = MtColors.TextTertiary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp
                )
            ) {
                groups.forEachIndexed { groupIndex, (label, groupEntries) ->
                    item(key = "header_$label") {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            color = MtColors.TextSecondary,
                            modifier = Modifier.padding(
                                top = if (groupIndex == 0) 12.dp else 24.dp,
                                bottom = 8.dp
                            )
                        )
                    }
                    items(groupEntries, key = { it.url }) { entry ->
                        BrowserHistoryRow(
                            entry = entry,
                            today = today,
                            grouped = true,
                            onClick = { onOpenUrl(entry.url) }
                        )
                    }
                }
            }
        }

        MainBottomBar(selectedIndex = MainTab.BROWSER.ordinal, onSelect = onSelectTab)
    }
}

/** 历史行：36dp 圆徽标 + 标题/网址（gap 2）+ 右侧时间；行高 56、组内 gap 12。 */
@Composable
private fun BrowserHistoryRow(
    entry: BrowserHistoryEntry,
    today: LocalDate,
    grouped: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MtColors.SurfaceMuted),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = badgeLetter(entry.url),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MtColors.TextSecondary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = BrowserHistoryRepository.displayTitle(entry),
                fontSize = 14.sp,
                color = MtColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = BrowserHistoryRepository.urlWithoutScheme(entry.url),
                fontSize = 12.sp,
                color = MtColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = historyTimeLabel(entry, today, grouped),
            fontSize = 13.sp,
            color = MtColors.TextTertiary
        )
    }
}

/**
 * 「原文｜译文」两段式视图开关（2026-10-02）。
 *
 * 与「翻译/停止」主按钮的分工是硬的：这个开关只决定**看什么**（译文层挂不挂在页面上），
 * 不启停任何任务；主按钮只决定**翻不翻**。两者形状也必须不同 —— 分段控件一眼能看出
 * 「当前选中哪个」，而这正是改造前并排两个同款胶囊按钮读不出来的信息。
 *
 * 没有译文可切时（会话没跑且屏上无译文层）**整块置灰禁用**，而不是「能点但点了没反应」。
 */
@Composable
private fun BrowserViewModeToggle(
    mode: BrowserViewMode,
    enabled: Boolean,
    disabledReason: String?,
    onSelect: (BrowserViewMode) -> Unit
) {
    val desc = if (enabled) {
        "原文/译文显示切换"
    } else {
        "原文/译文显示切换（${disabledReason ?: "不可用"}）"
    }
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MtColors.SurfaceMuted)
            .border(1.dp, MtColors.Border, RoundedCornerShape(12.dp))
            .padding(2.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics { contentDescription = desc },
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrowserViewModeSegment("原文", BrowserViewMode.ORIGINAL, mode, enabled, onSelect)
        BrowserViewModeSegment("译文", BrowserViewMode.TRANSLATED, mode, enabled, onSelect)
    }
}

/** 分段开关的一段；选中段用品牌色浅底 + 描边（与搜索引擎芯片同一套视觉语言）。 */
@Composable
private fun BrowserViewModeSegment(
    label: String,
    target: BrowserViewMode,
    mode: BrowserViewMode,
    enabled: Boolean,
    onSelect: (BrowserViewMode) -> Unit
) {
    val selectedNow = mode == target
    Box(
        modifier = Modifier
            .height(40.dp)
            .width(50.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selectedNow) MtColors.BrandTint else Color.Transparent)
            .then(
                if (selectedNow) {
                    Modifier.border(1.dp, MtColors.Brand, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                }
            )
            // 点已选中的那一段不做任何事（它本来就已经是当前状态，不该看起来"没反应"）。
            .clickable(enabled = enabled && !selectedNow) { onSelect(target) }
            .semantics {
                contentDescription = "显示$label"
                selected = selectedNow
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (selectedNow) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selectedNow) MtColors.BrandInk else MtColors.TextSecondary
        )
    }
}

@Composable
private fun MtBrowserIconButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    size: Int = 40,
    tint: Color = MtColors.TextPrimary
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = tint,
            modifier = Modifier.size((size * 0.5f).dp)
        )
    }
}

/** 大图解码：长边超过 [MAX_LONG_SIDE] 时降采样，控制内存；文字尺寸在 2400px 内足够 OCR。 */
private fun decodeCapped(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val longSide = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longSide / (sample * 2) > MAX_LONG_SIDE) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private const val MAX_LONG_SIDE = 2400

/**
 * 跨图拼接条带高度（px，解码后坐标系）——即分片接缝的上下文带宽。
 *
 * 作用是把相邻图的边缘拼到当前图上下，让跨图断点的气泡在 OCR 眼里完整。
 * 2026-09-28：改为**按图宽取**（对齐 C:\homework\translator 的
 * `calculateKanonicalSeamHeight = clamp(width * ratio, min, max)` 思路）——
 * 固定 300px 在窄图上占掉近半张图（OCR 面积/误配概率都偏高），在宽图上又不够覆盖大字行。
 * 带宽只负责给 OCR 上下文；被劈开的半截字由 [planSeamOwnership] 配对补全，
 * 跨图气泡的精确位置由 [CrossBubblePart] 换算，都不依赖这个高度。
 */
private fun seamBandHeightPx(width: Int): Int {
    if (width <= 0) return MIN_SEAM_BAND_PX
    return (width * SEAM_BAND_WIDTH_RATIO).toInt().coerceIn(MIN_SEAM_BAND_PX, MAX_SEAM_BAND_PX)
}

private const val SEAM_BAND_WIDTH_RATIO = 0.20f
private const val MIN_SEAM_BAND_PX = 144
private const val MAX_SEAM_BAND_PX = 384

/** 下载并按 [MAX_LONG_SIDE] 限长解码一张漫画图（普通 http(s) 路径）。 */
private fun downloadDecoded(
    downloader: BrowserImageDownloader,
    url: String,
    referer: String?,
    userAgent: String?
): Bitmap? {
    val bytes = downloader.download(url, referer, userAgent) ?: return null
    return decodeCapped(bytes)
}

/**
 * `data:image/…;base64,…` → 位图（内联小图路径，不需要网络）。
 *
 * 单独一条路径而不是塞进 OkHttp：OkHttp 只认 http/https，
 * `data:` 交给它只会得到一个 `IllegalArgumentException`。
 */
private fun decodeDataUrl(dataUrl: String): Bitmap? {
    val marker = dataUrl.indexOf("base64,")
    if (marker < 0) return null
    return runCatching {
        val base64 = dataUrl.substring(marker + "base64,".length)
        decodeCapped(Base64.decode(base64, Base64.DEFAULT))
    }.getOrNull()
}

/** 页面取像素的分片大小（对齐 [BrowserScanScript.DEFAULT_CHUNK_CHARS]）。 */
private const val PIXEL_CHUNK_CHARS = BrowserScanScript.DEFAULT_CHUNK_CHARS

/** 单张图最多回传多少 base64 字符，防止站点给出病态大的画布把内存吃穿。 */
private const val PIXEL_MAX_CHARS = 48_000_000

/**
 * 「一张图都没扫到」时的状态行文案 —— 带上页面画像与下一步建议。
 *
 * 为什么值得单独做：这是**最难自查**的一类失败。可能的根因至少有四种：
 * 站点把漫画画在 `<canvas>` 里、图还没加载出来、规则没命中、页面压根不是阅读页。
 * 光说「没找到可翻译的漫画图」，用户和开发者都只能猜；把画像写出来之后，
 * 一眼就能分流（2026-09-30 实测 RIDI 就是靠这个把范围缩到 canvas）。
 */
private suspend fun emptyScanHint(webView: WebView, scan: ScanResult): String {
    // 注意：**不要** withContext(Dispatchers.IO) —— WebView 是线程受限的，
    // evaluateJavascript 必须留在调用它的那个线程（这里是主线程）。
    val profile = webView.currentPageProfile()
    if (profile == null) {
        return "没找到可翻译的漫画图（往下滑或等图加载，会自动继续）"
    }
    val parts = buildList {
        add("img=${profile.images}")
        if (profile.canvases > 0) add("canvas=${profile.canvases}")
        if (profile.blobImages > 0) add("blob=${profile.blobImages}")
        if (profile.frames > 0) add("iframe=${profile.frames}")
    }
    val adapterLabel = if (scan.adapterId.isBlank()) "通用" else scan.adapterId
    val advice = when {
        profile.canvases > 0 ->
            "画面在 <canvas> 里：翻译需要页面允许导出像素；不支持时请用悬浮球截屏链路"
        profile.images == 0 ->
            "这一页可能不是阅读页，或图还没加载 —— 往下滑一下再点「翻译」"
        else ->
            "规则=${adapterLabel} 命中 ${scan.explicitHits} 条，但都不够像漫画页（可逐站关掉适配试试）"
    }
    return "没找到可翻译的漫画图（${parts.joinToString(" ")}）· $advice"
}

/** 页面画像：扫不到图时用来判断「是哪一类页面」。 */
private data class PageProfile(
    val images: Int,
    val canvases: Int,
    val blobImages: Int,
    val frames: Int
)

/**
 * 取一次页面画像（诊断用，失败一律返回 null —— 它绝不能把翻译流程搞挂）。
 *
 * 刻意不用 [BrowserScanScript.SCAN_JS]：那条路径带尺寸/排除过滤，画出来的像
 * 是「过滤之后的结果」；这里要的是**页面原貌**，所以直接数 DOM。
 */
private suspend fun WebView.currentPageProfile(): PageProfile? {
    val raw = runCatching { evaluateJavascriptSuspend(PAGE_PROFILE_JS) }.getOrNull() ?: return null
    val json = decodeJsJsonResult(raw) ?: return null
    return PageProfile(
        images = json.optInt("images", 0),
        canvases = json.optInt("canvases", 0),
        blobImages = json.optInt("blobImages", 0),
        frames = json.optInt("frames", 0)
    )
}

private const val PAGE_PROFILE_JS = """(function(){
  try {
    var imgs = document.images || [];
    var blob = 0;
    for (var i = 0; i < imgs.length; i++) {
      if (String(imgs[i].currentSrc || imgs[i].src || '').indexOf('blob:') === 0) blob += 1;
    }
    return JSON.stringify({
      images: imgs.length,
      canvases: document.querySelectorAll('canvas').length,
      blobImages: blob,
      frames: document.querySelectorAll('iframe').length
    });
  } catch (e) {
    return JSON.stringify({ images: -1, canvases: -1, blobImages: -1, frames: -1 });
  }
})()"""

/**
 * 「主线程」调度器 —— **自己建一个**，不用 `Dispatchers.Main`。
 *
 * 为什么不用 `Dispatchers.Main`：它的行为取决于 `kotlinx-coroutines-android` 的
 * `MainDispatcherFactory` 有没有被加载。一旦它不可用（依赖缺失、多模块拆分、
 * 主线程 dispatcher 未初始化…），`Dispatchers.Main` 会**静默退化成共享线程池**，
 * 于是 WebView 的 `checkThread()` 直接抛
 * `A WebView method was called on thread 'DefaultDispatcher-worker-N'`，
 * 把整轮翻译打挂 —— 而且从代码上看不出任何问题（2026-09-30 RIDI 实测踩到：
 * 明明是 `withContext(Dispatchers.Main)`，栈里却仍然是 worker 线程）。
 *
 * 这里直接绑到 `Looper.getMainLooper()`，语义明确、不依赖任何隐式注册。
 */
private val mainThreadDispatcher: CoroutineDispatcher by lazy {
    Handler(Looper.getMainLooper()).asCoroutineDispatcher()
}

/**
 * 主线程 Handler：给「**非挂起**回调 → WebView」用（WebView 方法线程受限）。
 *
 * 典型场景是渲染管线的阶段回调（`WebtoonImageTranslator.translateImage(onPhase=…)`）：
 * 它可能在 IO 线程上被调用，而那里没法 `withContext(mainThreadDispatcher)`，
 * 直接碰 WebView 就是 2026-09-30 踩过的 `A WebView method was called on thread ...`。
 */
private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

/**
 * 让**页面自己**把某张图的像素交出来（`blob:` / `<canvas>` 路径）。
 *
 * 为什么必须走这条路：`blob:` URL 只在本页 origin 内有效，Kotlin 侧的 OkHttp
 * 拿不到它，地址栏里也没有可下载的地址；而 Kakao Page / Lezhin / 哔哩哔哩漫画
 * 正是把图片字节在 JS 里解出来再 `createObjectURL` 的。唯一可行的办法是让页面
 * 把图重画进一张临时 canvas 再 `toDataURL`，我们分片取回后拼成完整 data URL。
 *
 * 返回 null 的原因会打日志（`zero_size` / `not_loaded` / `tainted` …）——
 * 其中 `tainted` 是浏览器的安全模型（跨域图 + 无 CORS 头），无法从页面侧绕过，
 * 只能由用户改用悬浮球截屏链路。
 */
private suspend fun WebView.extractPagePixels(image: ScannedImage, tag: String): Bitmap? {
    evaluateJavascript(BrowserScanScript.EXTRACTOR_JS, null)
    val builder = StringBuilder()
    var offset = 0
    while (offset < PIXEL_MAX_CHARS) {
        val decoded = decodeJsJsonResult(
            evaluateJavascriptSuspend(
                BrowserScanScript.extractDataUrlJs(image.id, offset, PIXEL_CHUNK_CHARS)
            )
        )
        if (decoded == null) {
            Log.w(tag, "pixel_extract reason=bad_result id=${image.id} at=$offset")
            return null
        }
        if (!decoded.optBoolean("ok")) {
            Log.w(
                tag,
                "pixel_extract reason=${decoded.optString("reason")} id=${image.id} " +
                    "src=${BrowserHistoryRepository.urlWithoutScheme(image.src)} " +
                    "msg=${decoded.optString("message")}"
            )
            return null
        }
        builder.append(decoded.optString("chunk"))
        offset = decoded.optInt("next", offset + PIXEL_CHUNK_CHARS)
        if (decoded.optBoolean("done")) break
    }
    if (builder.isEmpty()) return null
    return runCatching {
        decodeCapped(Base64.decode(builder.toString(), Base64.DEFAULT))
    }.getOrNull().also { bitmap ->
        if (bitmap == null) Log.w(tag, "pixel_extract reason=decode_failed id=${image.id}")
    }
}

/**
 * 从位图顶部/底部裁出条带并归一化到目标宽度（拼接进另一张图用）。
 * 总是返回新位图（不与源图共享像素），源图可独立回收。
 */
private fun cropStrip(bitmap: Bitmap, fromBottom: Boolean, targetWidth: Int, heightPx: Int = 0): Bitmap {
    // heightPx > 0 时用指定高度（跨缝重跑用更高的条带），否则沿用按图宽推出来的带宽。
    val stripH = if (heightPx > 0) {
        heightPx.coerceIn(1, bitmap.height)
    } else {
        minOf(seamBandHeightPx(bitmap.width), bitmap.height / 4).coerceAtLeast(1)
    }
    val y = (if (fromBottom) bitmap.height - stripH else 0).coerceIn(0, bitmap.height - stripH)
    val out = Bitmap.createBitmap(bitmap.width, stripH, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(bitmap, 0f, -y.toFloat(), null)
    if (out.width == targetWidth) return out
    val scaledH = (stripH.toLong() * targetWidth / out.width).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(out, targetWidth, scaledH, true)
    out.recycle()
    return scaled
}

private fun cropTopStrip(bitmap: Bitmap, targetWidth: Int, heightPx: Int = 0): Bitmap =
    cropStrip(bitmap, fromBottom = false, targetWidth = targetWidth, heightPx = heightPx)

private fun cropBottomStrip(bitmap: Bitmap, targetWidth: Int, heightPx: Int = 0): Bitmap =
    cropStrip(bitmap, fromBottom = true, targetWidth = targetWidth, heightPx = heightPx)

/**
 * 跨缝重跑时的下条带高度（2026-10-03）。
 *
 * 为什么需要：`seam_strip_short` 出现说明默认带宽（`clamp(width*0.20,144,384)`）装不下
 * 被缝切开的内容 —— 缝落在气泡第 2 行之后时第 3/4 行也在条带里，144px 只够一行半。
 * 重跑用 2 倍带宽（上限 [RETRY_STRIP_MAX_PX]），仍然只占整页高度的一半以内。
 */
private fun retryStripHeightPx(width: Int, pageHeightPx: Int): Int {
    val doubled = seamBandHeightPx(width) * 2
    return doubled.coerceAtMost(minOf(RETRY_STRIP_MAX_PX, maxOf(1, pageHeightPx / 2)))
}

private const val RETRY_STRIP_MAX_PX = 768




