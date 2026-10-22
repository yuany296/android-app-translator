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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.manga.translator.app.ui.MtArrowRightIcon
import com.manga.translator.app.ui.MtChevronDownIcon
import com.manga.translator.app.ui.MtChevronLeftIcon
import com.manga.translator.app.ui.MtChevronRightIcon
import com.manga.translator.app.ui.MtDropdownItem
import com.manga.translator.app.ui.MtDropdownMenu
import com.manga.translator.app.ui.MtLeavePageIcon
import com.manga.translator.app.ui.MtLockIcon
import com.manga.translator.app.ui.MtScanIcon
import com.manga.translator.app.ui.MtSearchIcon
import com.manga.translator.app.ui.MtStarFilledIcon
import com.manga.translator.app.ui.MtStarIcon
import com.manga.translator.app.ui.MtSwapIcon
import com.manga.translator.app.ui.MtSwitch
import com.manga.translator.app.ui.MtTonalButton
import com.manga.translator.app.ui.theme.MtColors
import com.manga.translator.store.AiProvider
import com.manga.translator.store.AppLanguage
import com.manga.translator.store.AppSettings
import com.manga.translator.translate.Bubble
import com.manga.translator.store.BrowserHistoryEntry
import com.manga.translator.store.BrowserFavoriteRepository
import com.manga.translator.store.BrowserFavorite
import com.manga.translator.store.BrowserHistoryRepository
import com.manga.translator.store.BrowserSearchEngine
import com.manga.translator.store.BrowserSiteAdapter
import com.manga.translator.store.BrowserSiteAdapterRegistry
import com.manga.translator.store.BrowserUrlResolver
import com.manga.translator.store.FavoriteAddResult
import com.manga.translator.store.OcrEngine
import com.manga.translator.store.TranslateEngine
import com.manga.translator.store.applyProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlin.math.roundToInt

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
    val kind: String = "url",
    /**
     * 元素顶边的**视口**纵坐标（CSS px，顶层视口，扫描瞬间的值）。
     *
     * 「阅读位置优先调度」（[BrowserImageOrder]）靠它把用户眼前的图排到最前 ——
     * 2026-10-15 用户报 Kakao「我在中间看，它先从头开始翻」。每轮扫描都会刷新。
     */
    val viewportTop: Int = 0
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
    val adapterId: String = "",
    /** 顶层视口高度（CSS px，扫描脚本给）；<=0 时「阅读位置优先」退化为文档序。 */
    val viewportHeight: Int = 0,
    /**
     * 候选被扫描脚本的安全阀截断（2026-10-15）。
     *
     * 触发时说明页面上**还有**符合尺寸的图没进列表 —— 它们不会被翻译（旧版 80 上限
     * 在 Kakao 174 切章上就是这个症状：翻到第 81 切突然停了）。阀值 600 对真实章节
     * 绰绰有余，所以这个标记正常应恒为 false；一旦为真，会话会打 `scan_capped` 警告，
     * 把「为什么后面还有图没翻」的线索直接留在日志里。
     */
    val truncated: Boolean = false
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
 * 截图覆盖层在页面里的锚点 `data-mt-id`（同一时刻只有一张截图结果，重复截图原地替换）。
 *
 * 提到顶层常量是为了让「建立覆盖层」与「收起覆盖层」两边的 JS 拼装引用同一个值 ——
 * 名字抄错一次就会留下摘不掉的孤儿节点（2026-10-16 重设计时统一到这里）。
 */
private const val SHOT_ANCHOR_ID = "mt-shot"

/**
 * 解除「点一下恢复原文」的一次性监听（2026-10-16，Todolist §56）。
 *
 * 为什么必须显式解除：那是挂在 document 捕获阶段的监听，**只在它自己触发时自摘**。
 * 改造前 `showOriginal()` 与 REMOVE_JS 都不解除它 —— 一旦被留下，之后任意一次页面点击
 * 就会把 `.__mt_scene_root` 整片删掉（连活翻译的译文一起），再回宿主把视图状态拨成原文，
 * 于是"会话还在跑、屏上一张都上不来"。这是用户报「点球无法翻译」的另一条真实成因。
 */
private const val SHOT_DISARM_JS =
    "if(window.__mtShotDismiss){document.removeEventListener('click',window.__mtShotDismiss,true);" +
        "window.__mtShotDismiss=null;}"

/**
 * 页面侧摘除截图覆盖层的语句（**定向**：锚点 + 整图贴片 + 它自己的场景根 + 解除监听）。
 *
 * 定向是硬要求：截图覆盖层与活翻译层共用同一套 imageId 通道，整体摘除（`removeScenes()`）
 * 会把活翻译的译文一起拔掉，而会话的 `placedIds` 仍记着"已贴过" → 那些译文再也不回来。
 */
private const val SHOT_TEARDOWN_STMT =
    "var a=document.querySelector('[data-mt-id=\"$SHOT_ANCHOR_ID\"]');" +
        "if(a&&a.parentNode){a.parentNode.removeChild(a);}" +
        "var o=document.querySelectorAll('[data-mt-ov-for=\"$SHOT_ANCHOR_ID\"]');" +
        "for(var i=0;i<o.length;i++){o[i].remove();}" +
        "try{if(window.MtOverlay&&MtOverlay.removeScene){MtOverlay.removeScene('$SHOT_ANCHOR_ID');}}" +
        "catch(e){}"

/**
 * 「原文」视图：把覆盖层节点与渲染层场景一起摘掉。
 *
 * 注意它**不碰会话** —— 译文结果仍留在 `renderedTokens` / `renderedScenes` 里，
 * 切回「译文」可以按同样的数据重发（不重跑 OCR/翻译）。
 *
 * 2026-10-16：整层摘除之前先**解除截图覆盖层的一次性点击监听**（见 [SHOT_DISARM_JS]）——
 * 不解除的话，切回原文之后用户在页面上的任意一次点击都会把刚贴回来的活译文清掉。
 */
private const val REMOVE_JS = """(function(){
  $SHOT_DISARM_JS
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
 * 摘掉某张图里若干气泡的渲染层（`MtOverlay.suppressBubble`）——接缝覆盖层上屏后， * 接缝两侧各自的「半块」都要摘掉，画面上只剩那一块盖板与那一份译文。
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

/**
 * 视口截图（2026-10-14 二修）：`PixelCopy` 读窗口**真实合成后**的像素 ——
 * 站点把正文图提升成 GPU 合成层时（Lezhin 等），软件层直画（`webView.draw`）抓不到那部分，
 * 抓出来是空白；PixelCopy 没有这个问题，也不需要任何系统权限。
 *
 * @return true = PixelCopy 成功；false = 失败（调用方回退软件直画）。
 */
private suspend fun captureViaPixelCopy(view: android.view.View, bitmap: Bitmap): Boolean {
    if (bitmap.width <= 0 || bitmap.height <= 0) return false
    // 取窗口：View 的 context 一般是 Activity（可能被 ContextWrapper 包着）。
    var context: android.content.Context? = view.context
    var window: android.view.Window? = null
    while (context != null && window == null) {
        if (context is android.app.Activity) window = context.window
        context = (context as? android.content.ContextWrapper)?.baseContext
    }
    val target = window ?: return false
    val location = IntArray(2)
    view.getLocationInWindow(location)
    val rect = android.graphics.Rect(
        location[0],
        location[1],
        location[0] + bitmap.width,
        location[1] + bitmap.height
    )
    return kotlin.coroutines.suspendCoroutine<Boolean> { continuation ->
        val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
        val listener = android.view.PixelCopy.OnPixelCopyFinishedListener { result ->
            if (resumed.compareAndSet(false, true)) {
                continuation.resume(result == android.view.PixelCopy.SUCCESS)
            }
        }
        try {
            android.view.PixelCopy.request(
                target,
                rect,
                bitmap,
                listener,
                android.os.Handler(android.os.Looper.getMainLooper())
            )
        } catch (err: Throwable) {
            if (resumed.compareAndSet(false, true)) continuation.resume(false)
        }
    }
}

/**
 * 截图前等上一会话收尾的上限（2026-10-16）。
 *
 * `stopSession` 是**协作式**的（不 cancel，理由见 `sessionScope` 的注释），它可能正卡在
 * 一张图的 OCR 里；不等它，截图管线就会和它抢同一个 Paddle 引擎（会话不是并发安全的）。
 * 超时只是兜底：宁可继续截图，也不让用户对着"正在截取画面…"干等。
 */
private const val PREV_SESSION_JOIN_TIMEOUT_MS = 15_000L

/**
 * 截图前等「DOM 变更已合成」的上限（2026-10-16）。
 *
 * PixelCopy 读的是**合成后**的窗口帧：先把当前译文层摘掉再截图时，如果不等一次视觉提交，
 * 抓到的还是带着旧译文的那一帧（老限制「截图把已有译文一起截进去」就是这么来的）。
 */
private const val SHOT_VISUAL_COMMIT_TIMEOUT_MS = 600L

/**
 * 等 WebView 下一次**视觉提交**（真的画到窗口上），用于"摘层之后再截像素"。
 *
 * 为什么用 `postVisualStateCallback` 而不是 `post{}`：后者只保证 UI 线程跑过一轮，
 * 不保证渲染线程已经把新的 DOM 状态合成出来；而 PixelCopy 抓的是合成结果。
 * 超时/不支持（API < 23 或 View 未附着）都按"已提交"处理 —— 截图宁可有极小概率带上
 * 旧译文，也不能把流程卡住。
 */
private suspend fun awaitVisualCommit(view: android.webkit.WebView, timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val posted = runCatching {
                // VisualStateCallback 是抽象类（Java），没有 SAM 转换，必须显式 object。
                view.postVisualStateCallback(
                    android.os.SystemClock.uptimeMillis(),
                    object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                )
                true
            }.getOrDefault(false)
            if (!posted && continuation.isActive) continuation.resume(Unit)
        }
        true
    } ?: false

/**
 * 截图翻译的**取证日志**（2026-10-14 用户报「截图里没读到可翻译的文字」）：
 * 把「截到的那张图长什么样」量化出来，区分两种完全不同的原因 ——
 * ① 截图本身是空白/黑屏（`nonblank≈0`、`mean≈0`）→ 采集侧问题；
 * ② 截图有内容但 OCR 没读出（`nonblank≈1`、`mean` 正常）→ 识别侧问题（看 `ocr_blocks_all`）。
 * 缩放采样统计，不逐像素读 1080×2400（那会卡主线程）。
 */
private fun logCaptureStats(bitmap: Bitmap, viaPixelCopy: Boolean) {
    val stats = runCatching {
        val scaled = Bitmap.createScaledBitmap(bitmap, 64, 128, false)
        val pixels = IntArray(64 * 128)
        scaled.getPixels(pixels, 0, 64, 0, 0, 64, 128)
        if (scaled !== bitmap) scaled.recycle()
        var sum = 0L
        var nonBlank = 0
        pixels.forEach { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            sum += luma
            if (luma > 12) nonBlank += 1
        }
        val count = pixels.size.coerceAtLeast(1)
        "mean=${"%.1f".format(sum.toDouble() / count)} nonblank=${"%.2f".format(nonBlank.toDouble() / count)}"
    }.getOrElse { err -> "stats_failed=${err.javaClass.simpleName}" }
    Log.d(TAG, "shot_capture w=${bitmap.width} h=${bitmap.height} src=${if (viaPixelCopy) "pixelcopy" else "software"} $stats")
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
 * 同内容去重的缓存项（2026-10-11「那个坑」）：内容指纹 → 已渲染的场景与接缝带。
 *
 * 场景的气泡几何全是**百分比坐标**，换 image id 照样成立；接缝带同理（own/next 气泡层 id
 * 是场景内的相对 id）。移交（crossOut）不缓存——下一张图早就拿着第一次的移交翻好了。
 */
private data class CachedSceneRender(
    val scene: BrowserSceneBuilder.Scene,
    val seam: SeamBandScene?
)

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
 * 截图翻译（2026-10-12，悬浮球 ⋮ 菜单入口）：只截 **WebView 自己的视口**，
 * 走 `webView.draw(Canvas)` —— 应用画自己的 View，**不需要**任何系统授权。
 * 首页那条 MediaProjection / 悬浮窗授权链路服务的是「翻译别的 App」的场景，这里不掺和。
 *
 * 捕获位图交给与图链路共用的 [WebtoonImageTranslator]（OCR → 翻译 → 擦字），
 * 结果回到页面有两种画法：
 * - **DOM 场景**（覆盖层渲染开，默认）：在页面里造一个「视口锚点」`<div>`（[ANCHOR_ID]
 *   指向它）。渲染层对宿主元素的唯一要求是 `getBoundingClientRect()`，所以现有
 *   `MtOverlay.applySceneJson` **一字不改**就能复用 —— 气泡按捕获位图的百分比
 *   落在锚点框上，锚点挂在文档/滚动容器里跟着内容滚。
 * - **整图 PNG**（覆盖层渲染关）：成图 `<img>` 直接叠在捕获时的文档位置（[overlayJs]）。
 *
 * ## 滚动宿主探测（锚点必须跟内容一起滚）
 *
 * 管线要跑几秒，结果必须贴在「捕获那一刻的画面」对应的**文档位置**上，所以捕获前
 * 要先量出几何（[metricsJs]）：页面靠文档滚动 → 锚点按 `pageXOffset/pageYOffset` 定位；
 * 页面靠某个大容器滚动（阅读器常见）→ 锚点挂进那个容器、按它的 scrollTop 定位，
 * 与图链路覆盖层同宿主，滚动由合成线程一起搬运。宿主打上 [HOST_ATTR] 标记，
 * 「原文→译文」贴回时按标记找回同一个容器（页面没换的前提下）。
 */
internal object BrowserScreenshotTranslate {

    /** 视口锚点的 `data-mt-id`。同一时刻只有一张截图结果，重复截图原地替换。 */
    const val ANCHOR_ID = SHOT_ANCHOR_ID

    /** 整图模式的 mtlocal token。固定复用一个：`put` 同 key 会顶掉旧字节，不积累内存。 */
    const val PNG_TOKEN = "shotpng"

    /** 滚动宿主标记：捕获时打上，「原文→译文」贴回时按它找回同一个容器。 */
    const val HOST_ATTR = "data-mt-shot-host"

    /** 采集到的视口几何。`docX/docY` = 锚点在宿主坐标系里的位置（CSS px）。 */
    data class ViewportGeometry(
        val anchoredToElement: Boolean,
        val docX: Int,
        val docY: Int,
        val viewportWidth: Int,
        val viewportHeight: Int
    )

    /**
     * 读视口几何（截图**前一刻**在主线程求值）：滚动宿主探测 + 滚动偏移 + 视口尺寸。
     * 顺带把宿主打上 [HOST_ATTR] 标记。探测规则：文档序里第一个
     * 「clientHeight ≥ 60% 视口高 且 实际可滚动」的元素（由外向内，外层才是阅读器主容器）。
     */
    fun metricsJs(): String = """(function(){
  function scrollable(el){
    try{
      var s=getComputedStyle(el);
      var re=/(auto|scroll|overlay)/;
      var oy=String(s.overflowY).toLowerCase(),ox=String(s.overflowX).toLowerCase();
      if(!re.test(oy)&&!re.test(ox))return false;
      return el.scrollHeight>el.clientHeight+4||el.scrollWidth>el.clientWidth+4;
    }catch(e){return false;}
  }
  var host=null;
  var nodes=document.querySelectorAll('body *');
  for(var i=0;i<nodes.length;i++){
    var el=nodes[i];
    if(el.clientHeight>=window.innerHeight*0.6&&scrollable(el)){host=el;break;}
  }
  if(host){try{host.setAttribute('$HOST_ATTR','1');}catch(e){}}
  return JSON.stringify({
    mode:host?'element':'document',
    x:host?Math.round(host.scrollLeft):Math.round(window.pageXOffset||0),
    y:host?Math.round(host.scrollTop):Math.round(window.pageYOffset||0),
    vw:window.innerWidth,
    vh:window.innerHeight
  });
})()"""

    /** `evaluateJavascript` 返回值 → [ViewportGeometry]；尺寸非法 / 解析失败返回 null。 */
    fun parseMetrics(raw: String?): ViewportGeometry? = runCatching {
        val decoded = decodeJsJsonResult(raw) ?: return@runCatching null
        val width = decoded.optInt("vw", 0)
        val height = decoded.optInt("vh", 0)
        if (width < 2 || height < 2) return@runCatching null
        ViewportGeometry(
            anchoredToElement = decoded.optString("mode") == "element",
            docX = decoded.optInt("x", 0),
            docY = decoded.optInt("y", 0),
            viewportWidth = width,
            viewportHeight = height
        )
    }.getOrNull()

    /**
     * 创建/替换视口锚点（DOM 场景模式）。锚点带 `__mt_overlay` 类 ——「原文」视图的
     * REMOVE_JS 会把它与场景根一起摘掉；`visibility:hidden`，它只给渲染层量几何用。
     */
    fun anchorJs(geometry: ViewportGeometry): String {
        val style = "position:absolute;left:${geometry.docX}px;top:${geometry.docY}px;" +
            "width:${geometry.viewportWidth}px;height:${geometry.viewportHeight}px;" +
            "pointer-events:none;visibility:hidden;"
        return """(function(){
  var old=document.querySelector('[data-mt-id="$ANCHOR_ID"]');
  if(old&&old.parentNode)old.parentNode.removeChild(old);
  var host=document.querySelector('[$HOST_ATTR="1"]');
  var parent=document.body||document.documentElement;
  if(host){
    try{
      var s=getComputedStyle(host);
      if(!s||s.position===''||s.position==='static')host.style.position='relative';
    }catch(e){}
    parent=host;
  }
  var a=document.createElement('div');
  a.setAttribute('data-mt-id','$ANCHOR_ID');
  a.className='__mt_overlay';
  a.style.cssText='$style';
  parent.appendChild(a);
  return 1;
})()"""
    }

    /**
     * 截图译文层切成**纯展示**（触摸穿透到页面）：
     * 场景根禁用指针事件 —— 截图译文盖在仍可交互的网页上，气泡自带的 click 会吃掉
     * 触摸/滚动（用户 2026-10-14 报「影响应用交互」）。渲染层 `MtOverlay.noTouch`。
     */
    fun noTouchJs(): String =
        "((function(){if(!window.MtOverlay||!MtOverlay.noTouch)return -1;" +
            "return MtOverlay.noTouch('$ANCHOR_ID');})())"

    /**
     * 收起截图覆盖层（页面侧定向摘除 + 解除一次性监听）。
     *
     * 调用点：球/菜单把覆盖层收起、开始活翻译前让位、设置变更作废、跳转前兜底。
     * 与 [dismissOnTapJs] 用的是同一段语句，保证"点屏幕收起"和"宿主收起"结果一致。
     */
    fun teardownJs(): String = "((function(){${SHOT_DISARM_JS}${SHOT_TEARDOWN_STMT}return 1;})())"

    /**
     * 「原文」视图的整层摘除脚本（[REMOVE_JS]）。
     *
     * 暴露成函数只为一个原因：契约单测要钉住「它必须顺便解除一次性点屏监听」——
     * 漏了解除，切回原文之后用户在页面上的任意一次点击都会把刚贴回来的活译文清掉。
     */
    fun removeAllJs(): String = REMOVE_JS

    /**
     * 截图译文「点一下消失」（用户 2026-10-14：要悬浮球翻译那种交互 —— 点屏幕就恢复原文）。
     *
     * 在页面捕获阶段挂**一次性** click 监听：任何点击都先摘掉**截图自己的**覆盖层
     * （锚点/贴片/它的场景根，定向，绝不整体清场景），再通过 `MTShotBridge` 通知宿主更新状态；
     * 监听随即自摘（只吃这一次点击，之后的点击照常操作页面）。
     *
     * 2026-10-16：响应体从"整片摘除 + removeScenes()"改成定向摘除 —— 改造前它会把活翻译的
     * 译文一并清掉，而宿主侧的 `placedIds` 还记着已贴过，那些译文再也不会回来。
     */
    fun dismissOnTapJs(): String =
        "((function(){" +
            SHOT_DISARM_JS +
            "window.__mtShotDismiss=function(){" +
            SHOT_DISARM_JS +
            SHOT_TEARDOWN_STMT +
            "try{if(window.MTShotBridge&&MTShotBridge.onDismissed){MTShotBridge.onDismissed();}}catch(e){}" +
            "};" +
            "document.addEventListener('click',window.__mtShotDismiss,true);" +
            "return 1;})())"

    /** 整图模式：成图 PNG 叠在捕获时的视口位置（`data-mt-ov-for` 便于原地替换旧贴图）。 */
    fun overlayJs(token: String, geometry: ViewportGeometry): String {
        val src = "https://mtlocal.overlay/$token.png"
        val style = "position:absolute;left:${geometry.docX}px;top:${geometry.docY}px;" +
            "width:${geometry.viewportWidth}px;height:${geometry.viewportHeight}px;" +
            "z-index:2147483000;pointer-events:none;border:0;margin:0;padding:0;max-width:none;"
        return """(function(){
  var old=document.querySelectorAll('[data-mt-ov-for="$ANCHOR_ID"]');
  for(var i=0;i<old.length;i++){old[i].remove();}
  var host=document.querySelector('[$HOST_ATTR="1"]');
  var parent=document.body||document.documentElement;
  if(host){
    try{
      var s=getComputedStyle(host);
      if(!s||s.position===''||s.position==='static')host.style.position='relative';
    }catch(e){}
    parent=host;
  }
  var el=document.createElement('img');
  el.src='$src';
  el.className='__mt_overlay';
  el.setAttribute('data-mt-ov-for','$ANCHOR_ID');
  el.style.cssText='$style';
  parent.appendChild(el);
  return 1;
})()"""
    }
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
                kind = obj.optString("kind", "url").ifBlank { "url" },
                viewportTop = obj.optDouble("vy", 0.0).toInt()
            )
        }
        if (trimmedDecoded.startsWith("{")) {
            val meta = JSONObject(trimmedDecoded)
            ScanResult(
                images = images,
                explicitHits = meta.optInt("explicit", 0),
                frameCount = meta.optInt("frames", 0),
                adapterId = meta.optString("cfg"),
                viewportHeight = meta.optInt("vh", 0),
                truncated = meta.optBoolean("truncated", false)
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
 * 「阅读位置优先」调度的锚点比例（2026-10-15）：视口高度 35% 处为「正在读的位置」，
 * 与参考项目 `scheduler.js` 的 `viewportAnchor = innerHeight * 0.35` 同口径 ——
 * 长条漫通常上下各露出半张，35% 线穿过的是用户视线的落点。
 */
private const val VIEWPORT_ANCHOR_RATIO = 0.35f

/**
 * 「当前扫到的最后一张」等下一页出现的**单轮**等待时长（懒加载阅读器，2026-10-03）。
 *
 * 为什么不是主轮询的 1.2s：等待期间用户通常正在连续滚动，下一张随时会进扫描列表 ——
 * 步子小才能「一出现就处理」（等待成本通常 0.7~1.4s）。总窗 = 步长 × [NEXT_PAGE_WAIT_ROUNDS]。
 */
private const val NEXT_PAGE_WAIT_STEP_MS = 700L
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

/**
 * 已渲染场景缓存上限（每个场景几 KB，防长章节无限增长；整图 PNG 由内存仓 96MB 上限管着）。
 *
 * 2026-10-15 由 80 提到 600：扫描安全阀提到 600 后，一对 174 切的长章节（Kakao 实测）
 * 也整章进列表 —— 80 的上限会让最早 94 张的场景在翻到后面时被淘汰，
 * 「原文/译文」切回来时那段译文贴不回去（接缝带一起被淘汰还会露出两半）。600 覆盖
 * 真实章节；极端页面被淘汰时**不会**触发重翻（「已产出过结果」由 `renderedSrcs` 单独记）。
 */
private const val MAX_RENDERED_SCENES = 600

/** 同内容去重缓存上限（2026-10-11）：长会话防内存增长，LRU 淘汰最早的。 */
private const val CONTENT_SCENE_CACHE_MAX = 12

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
 * 悬浮球 ⋮ 菜单的「截图翻译」（2026-10-12）同样免授权：只截 WebView 自己的视口
 * （`webView.draw`），结果由 [BrowserScreenshotTranslate] 按捕获时的文档位置回贴。
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
    /**
     * 会话专用作用域（2026-10-14，修「点悬浮球就闪退」）。
     *
     * 为什么不能用 `scope`（= `rememberCoroutineScope()`）跑会话：它在组合被销毁时会**取消**
     * 里面的协程，而「取消异常（JobCancellationException）穿过会话的 suspend 状态机」在本机
     * ART 解释器上会触发断言崩溃：
     *
     * ```
     * Abort message: 'No pending exception expected: kotlinx.coroutines.JobCancellationException:
     *                 StandaloneCoroutine was cancelled'
     * #04 art::Thread::AssertNoPendingException
     * #09 art::interpreter::FindFieldSlow     ← 异常还在 pending 时解析「冷路径」里的字段
     * #13 com.manga.translator.app.EmbeddedBrowserScre…（解释器帧）
     * #29 kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith
     * ```
     *
     * 现场：点悬浮球「停止」→ `stopSession` 里 `job.cancel()` → 会话被打断 → 进程 SIGABRT
     * （`Force finishing MainActivity`）→ 用户看到的就是「悬浮球点不动 / App 直接没了」。
     * 2026-10-12 §31 记的那条未修 SIGABRT 就是这一处。
     *
     * 现在改成**协作式停止**：`session.token += 1` 之后会话在下一次检查时自己退出（每轮最多
     * 等一个 1.2s 轮询间隔），不再有取消异常穿过状态机；会话作用域只在会话真正结束后才取消
     * （见 `DisposableEffect` 的 onDispose）。
     */
    val sessionScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val settingsRepository = ServiceLocator.settingsRepository
    val historyRepository = ServiceLocator.browserHistoryRepository
    val favoriteRepository = ServiceLocator.browserFavoriteRepository

    var browserPage by remember { mutableStateOf(BrowserPage.NEW_TAB) }
    var newTabSearch by remember { mutableStateOf("") }
    var pageUrl by remember { mutableStateOf("") }
    var loadProgress by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    // 「浏览器与翻译」底部弹层（画布 `10:133`）：2026-10-12 把原来那个长条下拉菜单整体换掉。
    // 换掉的原因：菜单条目（新标签页/站点适配/两个开关/搜索引擎/历史/收藏/清空）早就超出屏幕，
    // 而且「设置」这个齿轮点开一堆「打开历史、清空历史」也不合语义。
    var settingsSheetOpen by remember { mutableStateOf(false) }
    // 弹层里的语言对 / 识别引擎 / 翻译配置要读写同一份设置，所以在页面里持一份可变的副本。
    // 切到别的 tab 再回来会重新 remember → 重新读盘，不会看到首页改过的旧值。
    var appSettings by remember { mutableStateOf(settingsRepository.getSettings()) }
    var profileNames by remember { mutableStateOf(settingsRepository.getProfileNames()) }
    // 当前生效的翻译配置名。口径与首页一致：上次选过的那个，否则第一个（都没有就是空）。
    var activeProfile by remember {
        mutableStateOf(
            settingsRepository.getLastTranslateProfileName().takeIf { it.isNotEmpty() }
                ?: settingsRepository.getProfileNames().firstOrNull().orEmpty()
        )
    }
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
    // 网页文字翻译（2026-10-14）：与漫画图链路独立 —— 只翻译 DOM 文本节点。
    var webpageTextBusy by remember { mutableStateOf(false) }
    var webpageTextApplied by remember { mutableStateOf(false) }
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
    // 「已产出过结果」的图（src）—— **不随场景缓存淘汰**（2026-10-15）。
    // 为什么单独记一份：`renderedScenes` 有容量上限，长章节里最早的条目会被淘汰；
    // 而 `isPending` 若只看缓存有没有，被淘汰的图会重新变回「待翻译」—— 会话把
    // 全章翻完后又会去重翻最早的几十张（翻一批、淘汰一批，永远转不完）。
    val renderedSrcs = remember { HashSet<String>() }
    // 渲染层 JS 是否已注入当前页面（页面跳转后要重新注入）。
    var rendererInjected by remember { mutableStateOf(false) }
    // 视图模式（2026-10-02 用户反馈「原文/翻译两个按钮功能不统一」后拆分）：
    // 只决定译文层挂不挂在页面上，**不**启停任何任务；任务启停只由悬浮球负责。
    var viewMode by remember { mutableStateOf(BrowserViewMode.TRANSLATED) }
    /**
     * 屏上此刻挂着哪一种译文层 —— **唯一事实来源**（2026-10-16 重设计）。
     *
     * 改造前这里有三个各自为政的布尔/可空状态（`hasRenderOnPage` / `viewMode` /
     * `screenshotRender`），于是出现「截图译文点掉之后点球翻不动」那类事故。
     * 现在只保留这一个：写它的只有 hideTranslationLayer / displaySnapshotLayer /
     * showLiveLayer 与会话的 onRendered，其余代码一律读。
     */
    var shownLayer by remember { mutableStateOf(BrowserShownLayer.NONE) }
    // 「一次性贴回」会话在跑（不翻译，只把已有译文层贴回）；这期间球与开关都禁用，避免被打断。
    var reShowBusy by remember { mutableStateOf(false) }
    // 截图翻译（2026-10-12 悬浮球 ⋮ 菜单，免系统授权）：busy = 一次截图翻译在跑。
    // 2026-10-16 起**不再保存可重看的截图结果** —— 覆盖层是一次性的（点任意处即收起，
    // 对齐首页那条链路的交互），想再来一次就重新截。
    var screenshotBusy by remember { mutableStateOf(false) }
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
        // 协作式停止（2026-10-14）：**不 cancel** —— 取消异常穿过会话的 suspend 状态机会触发
        // 本机 ART 的解释器断言（SIGABRT，见 [sessionScope] 的注释）。token 一变，会话在下一轮
        // `onSessionActive()` 检查时就自己退出（最多等一个 1.2s 轮询间隔）；期间它不再写状态行
        // （见 [reportStatus] 的守卫）。
        session.token += 1
        running = false
        // 贴回会话也可能被停（跳转 / 点球打断）：这个标志必须一起复位，否则两个控件会永久禁用。
        reShowBusy = false
        statusText = message
        Log.d(TAG, "session_stop reason=$message cooperative=true")
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
     * 改一份设置并落盘（2026-10-12，「浏览器与翻译」弹层里的语言 / 识别引擎 / 翻译配置走这里）。
     *
     * 为什么要有这一层：翻译链路（[WebtoonImageTranslator]）是**每次调用**才去
     * `settingsRepository.getSettings()` 读一份最新设置，所以弹层里改完就立刻生效，
     * 不需要回来重启什么；这里只负责「写盘 + 同步本地这份副本」，让弹层显示的值马上对上。
     */
    fun updateSettings(block: (AppSettings) -> AppSettings) {
        val next = block(appSettings)
        settingsRepository.saveSettings(next)
        appSettings = next
    }

    /**
     * 「翻译配置」行的值（画布 `11:379` 写的是「百度翻译 · 默认」）。
     *
     * 只有**云端 API** 才拼配置档案名：档案里存的是厂商 / Base URL / 模型 / Key，
     * 百度翻译和 ML Kit 本地根本不走那条路 —— 硬拼一个档案名上去（比如
     * 「ML Kit 本地 · qwen」）会让用户以为选的那个档案在生效。
     */
    val translateProfileLabel = when (val engine = TranslateEngine.fromId(appSettings.translateEngine)) {
        TranslateEngine.CLOUD -> {
            val provider = AiProvider.fromId(appSettings.translateProvider).label
            if (activeProfile.isEmpty()) "$provider · 未配置" else "$provider · $activeProfile"
        }

        // 非云端只报引擎名，不带档案 —— 档案里那套 Base URL / 模型 / Key 根本没在走。
        else -> engine.sheetLabel()
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
            // debug 包开 WebView 远程调试（chrome://inspect / adb forward + CDP）：
            // 渲染层排障需要读 `MtOverlay._overlays` 的真实几何（排版框 vs 盖板框 vs 字号），
            // 日志只有中间量，DOM 里才是最终事实（2026-10-12 用户反馈「你能自己干吗」）。
            if (BuildConfig.DEBUG) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
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

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    // SPA 站内跳转（pushState/replaceState）不触发 onPageStarted/onPageFinished ——
                    // 收藏页里点来点去「实际 URL 变了、顶栏不变」（用户 2026-10-14 报）就是这里漏的。
                    // 只刷新地址与站点适配；页面没重载，渲染层注入/译文层/会话都不动。
                    if (url.isNullOrBlank() || url == "about:blank" || url == pageUrl) return
                    pageUrl = url
                    matchedAdapter = BrowserSiteAdapterRegistry.resolveUrl(url)
                    // SPA 站内跳转换的是**同一份文档**里的另一页，onPageStarted 不会来 ——
                    // 上一页的网页文字翻译状态必须在这里一起清掉，否则新页面还没翻过，
                    // 弹层按钮却已经写着「恢复页面原文」（用户 2026-10-15 报）。
                    webpageTextBusy = false
                    webpageTextApplied = false
                    Log.d(TAG, "url_updated source=doUpdateVisitedHistory url=${BrowserHistoryRepository.urlWithoutScheme(url)}")
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
                    // 屏上真实状态随旧文档一起消失（截图覆盖层也是一次性的：跳转即结束）。
                    shownLayer = BrowserShownLayer.NONE
                    placedIds.clear()
                    seamBySrc.clear()
                    // 网页文字翻译的注册表随旧文档一起没了：状态复位（运行中的回填会因
                    // 注册表失效自动返回 applied=0，不用专门取消）。
                    webpageTextBusy = false
                    webpageTextApplied = false
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
        webView.evaluateJavascript(BrowserScreenshotTranslate.removeAllJs(), null)
    }

    /** 彻底丢弃已渲染结果（切换「清理图/覆盖层渲染」选项时用：旧结果是旧设置画的，必须重画）。 */
    fun clearRenderedOverlays() {
        removeOverlayNodes()
        renderedTokens.clear()
        renderedScenes.clear()
        renderedSrcs.clear()
        seamBySrc.clear()
        placedIds.clear()
        // 屏上真实状态一并复位：旧结果（含截图覆盖层）是旧设置下产出的，留着只会误导开关。
        shownLayer = BrowserShownLayer.NONE
        BrowserOverlayImageStore.clear()
    }

    /**
     * 截图译文「点一下消失」的宿主侧状态（页面侧监听见 [BrowserScreenshotTranslate.dismissOnTapJs]）：
     * 点击发生在 WebView 里，需要它回来说一声，把「屏上还有没有译文」收掉、状态行说实话。
     * 用户 2026-10-14：要悬浮球翻译那种交互 —— 点屏幕就恢复原文。
     *
     * 2026-10-16 重设计：
     * - 守卫从「有没有贴过译文」收紧为「屏上挂的是不是截图覆盖层」——活翻译层被页面点击
     *   摘掉时不该冒充"用户点了截图译文"；
     * - `placedIds.clear()` 必须一起做：页面侧已经把场景根删了，若宿主还记着"已贴过"，
     *   会话就再也不会把它们贴回来（`needsReShow` 判假）；
     * - 状态行不再承诺「点球重看截图译文」（那条机制已删除）：球只负责整页翻译，
     *   想再来一次当前画面走 ⋮ 菜单的「截图翻译」。
     */
    val shotDismissBridge = object {
        @android.webkit.JavascriptInterface
        fun onDismissed() {
            mainHandler.post {
                if (shownLayer != BrowserShownLayer.SNAPSHOT) return@post
                shownLayer = BrowserShownLayer.NONE
                // 视图状态跟着真实画面走：覆盖层收起后用户看到的就是原文。
                viewMode = BrowserViewMode.ORIGINAL
                placedIds.clear()
                statusText = "已恢复原文 · 再点球翻译整页"
                Log.d(TAG, "shot_dismiss source=page_tap")
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // JS 接口按名字注册一次（重复注册会覆盖，无害）；必须在任何 evaluateJavascript 之前。
        webView.addJavascriptInterface(shotDismissBridge, "MTShotBridge")
    }

    /**
     * 「翻译条件变了」的统一处理（语言 / 翻译配置 / 识别引擎，2026-10-12）。
     *
     * 页面上已经贴着的译文是**旧条件**下算出来的：不丢掉的话，用户会看到「一半旧语言、
     * 一半新语言」，或者旧识别引擎的字框压在新结果上。与两个开关（精细擦字 / 覆盖层渲染）
     * 同一套口径：清缓存 + 提示「点『翻译』重新翻译」，**不**在后台悄悄重跑 ——
     * 整页重翻很花时间，替用户做这个决定比不做更糟。
     *
     * 声明位置必须在 [clearRenderedOverlays] 之后：Kotlin 局部函数只能看到自己前面声明的东西。
     */
    fun invalidateRenderedResults(reason: String) {
        clearRenderedOverlays()
        stopSession("$reason，点「翻译」重新翻译")
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
            // 协作式停止（2026-10-14）：置停让会话自己退出，**不 cancel**
            // （取消异常穿过 suspend 状态机会触发 ART 断言崩溃，见 [sessionScope] 的注释）。
            session.token += 1
            running = false
            webView.stopLoading()
            runCatching { CookieManager.getInstance().flush() }
            renderedTokens.clear()
            renderedScenes.clear()
            renderedSrcs.clear()
            seamBySrc.clear()
            placedIds.clear()
            BrowserOverlayImageStore.clear()
            // WebView 必须等会话真正结束才能 destroy：会话可能正卡在 `evaluateJavascript`
            // 里，提前销毁会让它打到已销毁的 WebView（另一条崩溃路径）。会话不在时立即销毁。
            val runningJob = session.job
            session.job = null
            val destroyWebView = {
                runCatching { webView.destroy() }
                sessionScope.cancel()
            }
            if (runningJob == null) {
                destroyWebView()
            } else {
                runningJob.invokeOnCompletion { mainHandler.post { destroyWebView() } }
            }
            Log.d(TAG, "browser_dispose deferred_destroy=${runningJob != null}")
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

    /**
     * 地址胶囊右侧的 ✕（画布 `11:139`）：清空地址并直接进入编辑态。
     *
     * 语义对齐 Chrome 地址栏的 ✕ —— 「清掉当前网址、准备输入新的」，
     * **不**关掉当前页面（关页面有返回键和「窗口」按钮，不该藏在 ✕ 里）。
     */
    fun clearAddressAndEdit() {
        addressDraft = TextFieldValue(text = "", selection = TextRange(0, 0))
        addressEditing = true
        Log.d(TAG, "address_clear_and_edit")
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
        settingsSheetOpen = false
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
        // 截图翻译与连续会话共用同一份 OCR 引擎与 translator：并行会互相踩
        // （Paddle 会话不是并发安全的），忙时拒绝并说明，而不是静默失败。
        if (screenshotBusy) {
            statusText = "截图翻译进行中…"
            return
        }
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
        val job = sessionScope.launch {
            runTranslationSession(
                token = myToken,
                sessionMode = mode,
                sessionPageUrl = currentUrl,
                userAgent = webView.settings.userAgentString,
                renderedTokens = renderedTokens,
                renderedScenes = renderedScenes,
                renderedSrcs = renderedSrcs,
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
                // 活翻译真的把一张贴上了屏：屏上事实变成 LIVE（唯一写入口之一）。
                onRendered = { shownLayer = BrowserShownLayer.LIVE },
                onFinished = { reShowBusy = false },
                translator = translator,
                downloader = downloader,
                webView = webView,
                onStatus = { statusText = it },
                onRunningChange = { running = it },
                onSessionActive = { session.token == myToken }
            )
        }
        session.job = job
        // 会话自然结束（正常退出/异常）后释放句柄：停止是协作式的，不能再靠 cancel；
        // onDispose 需要它来判断「还能不能马上销毁 WebView」。
        job.invokeOnCompletion { if (session.job === job) session.job = null }
    }

    /**
     * 收起屏上挂着的译文层 —— **唯一**的「回到原文」入口（2026-10-16 重设计）。
     *
     * 一次只有一个译文来源（[BrowserLayerPolicy]），所以收起只有一个实现：
     * - 页面侧整层摘除（[REMOVE_JS]，它同时解除截图覆盖层的一次性点屏监听）；
     * - [placedIds] 清空 —— 这是切回「译文」时 `needsReShow` 能把它们贴回来的唯一依据；
     * - [shownLayer] 归 NONE、[viewMode] 拨回「原文」，让开关/球/状态行三处说的是同一件事。
     *
     * 它**不停会话**（2026-10-02 定稿）：运行中切原文只是换个看法，后台继续翻。
     *
     * @param reason 日志用的稳定原因（menu_original / shot_capture / ball_restore / …）
     */
    fun hideTranslationLayer(reason: String) {
        val previous = shownLayer
        viewMode = BrowserViewMode.ORIGINAL
        removeOverlayNodes()
        placedIds.clear()
        shownLayer = BrowserShownLayer.NONE
        Log.d(TAG, "layer_hide reason=$reason previous=$previous running=$running")
    }

    /**
     * 把截图覆盖层挂上屏（DOM 场景 / 整图两条画法）。
     *
     * 一次性覆盖层：不保存任何"可重放"结果，收起即结束；**只有真的挂上屏才写**
     * [shownLayer]/[viewMode]。改造前的 `reShowScreenshot` 不写 `viewMode`，
     * 于是随后的会话以为自己在「原文」视图 —— 扫描/OCR/翻译全做了却一张都不上屏，
     * 用户看到的就是「点球无法翻译」。
     *
     * @param bubbles 读到多少处文字（只用于状态行文案）
     * @param pngBytes 整图模式的成图（DOM 模式传 null）
     * @return true = 真的挂上屏了
     */
    suspend fun displaySnapshotLayer(
        geometry: BrowserScreenshotTranslate.ViewportGeometry,
        scene: BrowserSceneBuilder.Scene?,
        pngBytes: ByteArray?,
        bubbles: Int
    ): Boolean {
        if (scene == null && pngBytes == null) return false
        if (scene != null) {
            if (!rendererInjected) {
                webView.evaluateJavascriptSuspend(
                    OverlayRendererScript.load(context),
                    what = "shot_renderer"
                )
                rendererInjected = true
            }
            webView.evaluateJavascriptSuspend(
                BrowserScreenshotTranslate.anchorJs(geometry),
                what = "shot_anchor"
            )
            val result = decodeJsJsonResult(
                webView.evaluateJavascriptSuspend(
                    applySceneJs(
                        BrowserSceneBuilder.buildSceneJson(BrowserScreenshotTranslate.ANCHOR_ID, scene)
                    ),
                    what = "shot_scene"
                )
            )
            if (result?.optBoolean("ok") != true) {
                val why = result?.optString("reason").orEmpty().ifBlank { "未知原因" }
                Log.w(TAG, "layer_show_fail source=snapshot mode=dom reason=$why")
                statusText = "覆盖层渲染失败：$why"
                // 半成品不要留在页面上（锚点已经建好了，场景却没挂上）。
                webView.evaluateJavascript(BrowserScreenshotTranslate.teardownJs(), null)
                return false
            }
            // 纯展示：触摸穿透（覆盖层盖在仍可交互的网页上）+ 点一下恢复原文。
            webView.evaluateJavascript(BrowserScreenshotTranslate.noTouchJs(), null)
            webView.evaluateJavascript(BrowserScreenshotTranslate.dismissOnTapJs(), null)
            viewMode = BrowserViewMode.TRANSLATED
            shownLayer = BrowserShownLayer.SNAPSHOT
            statusText = "截图翻译完成（$bubbles 处）· 点屏幕恢复原文"
            Log.d(TAG, "layer_show source=snapshot mode=dom bubbles=$bubbles")
            return true
        }
        val bytes = pngBytes ?: return false
        // 整图模式固定复用同一个 token：`put` 同 key 会顶掉旧字节，不积累内存。
        BrowserOverlayImageStore.put(BrowserScreenshotTranslate.PNG_TOKEN, bytes)
        webView.evaluateJavascriptSuspend(
            BrowserScreenshotTranslate.overlayJs(BrowserScreenshotTranslate.PNG_TOKEN, geometry),
            what = "shot_overlay"
        )
        webView.evaluateJavascript(BrowserScreenshotTranslate.dismissOnTapJs(), null)
        viewMode = BrowserViewMode.TRANSLATED
        shownLayer = BrowserShownLayer.SNAPSHOT
        statusText = "截图翻译完成（$bubbles 处）· 点屏幕恢复原文"
        Log.d(TAG, "layer_show source=snapshot mode=png bubbles=$bubbles")
        return true
    }

    /**
     * 显示**活翻译层**（⋮ 菜单「译文」段；截图链路失败后的自动贴回也走它）。
     *
     * - 会话在跑：什么都不用做 —— 下一轮轮询（≤ [IDLE_POLL_INTERVAL_MS]）就会因 [placedIds] 已清而重新贴回；
     * - 会话没跑但有缓存：起一次**只贴回**的一次性会话（不 OCR、不下图、不轮询）；
     * - 什么都没有：只在状态行提示（改造前这里就是「点了没反应」的来源）。
     *
     * 截图覆盖层**不在**这里重放：它是一次性的（点屏幕即收起），想再来一次走 ⋮ 菜单「截图翻译」。
     */
    fun showLiveLayer(reason: String) {
        viewMode = BrowserViewMode.TRANSLATED
        Log.d(
            TAG,
            "layer_show source=live reason=$reason running=$running " +
                "cached=${renderedTokens.size + renderedScenes.size}"
        )
        when {
            running -> Unit
            renderedTokens.isEmpty() && renderedScenes.isEmpty() ->
                statusText = BrowserTranslateUiPolicy.idleHint(
                    viewMode = viewMode,
                    running = running,
                    shown = shownLayer,
                    pageLoaded = true
                )

            else -> startSession(BrowserSessionMode.RE_SHOW_ONLY)
        }
    }

    /**
     * 切到「原文」视图（⋮ 菜单「原文」段；截图覆盖层在屏时它也是"收起覆盖层"的入口）。
     */
    fun showOriginal() {
        hideTranslationLayer("menu_original")
        if (!running) {
            statusText = BrowserTranslateUiPolicy.idleHint(
                viewMode = viewMode,
                running = false,
                shown = shownLayer,
                pageLoaded = true
            )
        }
    }

    /**
     * 切到「译文」视图（⋮ 菜单「译文」段）：只贴回活翻译层。
     */
    fun showTranslations() {
        showLiveLayer("menu_translated")
    }

    /**
     * 截图翻译（悬浮球 ⋮ 菜单入口，2026-10-12）。
     *
     * 与首页悬浮球链路的本质区别：只截 **WebView 自己的视口**（`webView.draw`），
     * 不申请 MediaProjection、不申请悬浮窗 —— 应用画自己的 View 不需要任何系统授权。
     * 流程：量几何（滚动宿主 + 偏移）→ 截图 → 共用管线（OCR→翻译→擦字）→
     * 按捕获时的文档位置回贴（DOM 场景走视口锚点；整图走 mtlocal 贴图）。
     *
     * 与连续会话互斥：会话在跑就先停（译文保留）；截图进行中点「翻译」会被
     * [startSession] 里的守卫挡下并提示。
     */
    /**
     * 网页文字翻译（2026-10-14，参考 C:\homework\translator 扩展的 webpage-* 模块）：
     * 收集 DOM 文本节点 → 走句级翻译解析器（缓存/术语表/引擎分发与漫画链路完全同源）→
     * 按批回填（每批一evaluate，边翻边贴，长页面不用等全页）。
     *
     * 与漫画链路的关系：互不干扰 —— 图链路只碰 `<img>`/`<canvas>`，这里只碰文本节点，
     * 双方的注册表/覆盖层互不可见（页面侧脚本用 `closest(MT_OWN_UI)` 跳过我们的覆盖层）。
     */
    fun startWebpageTextTranslation() {
        if (webpageTextBusy) {
            statusText = "正在翻译页面文字…"
            return
        }
        if (webView.url.isNullOrBlank() || webView.url == "about:blank") {
            statusText = "请先打开网页"
            return
        }
        webpageTextBusy = true
        statusText = "正在收集页面文字…"
        Log.d(TAG, "webpage_text_start url=${BrowserHistoryRepository.urlWithoutScheme(webView.url.orEmpty())}")
        sessionScope.launch {
            var appliedTotal = 0
            try {
                // 1) 安装 + 收集（主线程：WebView 线程受限）。
                webView.evaluateJavascriptSuspend(BrowserWebpageTextScript.installJs(), what = "webpage_install")
                val collected = BrowserWebpageTextScript.parseCollectResult(
                    decodeJsJsonResult(
                        webView.evaluateJavascriptSuspend(
                            BrowserWebpageTextScript.collectJs(),
                            what = "webpage_collect"
                        )
                    )
                )
                if (collected.isEmpty()) {
                    statusText = "这个页面没有可翻译的文字"
                    Log.d(TAG, "webpage_text_empty")
                    return@launch
                }
                // 2) 页面语言 == 目标语言 → 整页跳过（参考项目是逐节点判定，我们取页面级一次判定，
                //    拟声词/人名这类混排节点交给翻译引擎自己兜）。
                val settings = settingsRepository.getSettings()
                val pageLanguage = com.manga.translator.translate.SourceScriptDetector.detect(collected.map { it.text })
                if (pageLanguage?.languageId == settings.targetLanguage) {
                    statusText = "页面语言与目标语言相同，无需翻译"
                    Log.d(TAG, "webpage_text_same_lang lang=${pageLanguage.languageId}")
                    return@launch
                }
                // 3) 逐批翻译 → 回填。句级解析器复用：句子缓存 / 术语表 / 历史 / 引擎分发
                //    与漫画链路同源，同一句话在两条链路之间还能共享缓存。
                val glossaryEntries = ServiceLocator.glossaryRepository.activeGlossaryEntries()
                val resolver = PageTranslationResolver()
                val batches = BrowserWebpageTextScript.buildBatches(collected)
                Log.d(
                    TAG,
                    "webpage_text_plan collected=${collected.size} batches=${batches.size} " +
                        "lang=${pageLanguage?.languageId ?: "auto"} target=${settings.targetLanguage}"
                )
                batches.forEachIndexed { batchIndex, batch ->
                    if (!isActive) return@forEachIndexed
                    statusText = "正在翻译页面文字…（${batchIndex + 1}/${batches.size}）"
                    val bubbles = batch.map { item ->
                        Bubble(
                            x = 0f, y = 0f, w = 0f, h = 0f,
                            bgType = "none",
                            originalText = item.text,
                            translatedText = item.text
                        )
                    }
                    val translateKey = resolver.buildTranslateKey(
                        texts = bubbles.map { it.originalText },
                        settings = settings,
                        glossaryRevision = ServiceLocator.glossaryRepository.activeRevision()
                    )
                    val outcome = withContext(Dispatchers.IO) {
                        resolver.resolveTranslations(
                            bubbles = bubbles,
                            settings = settings,
                            translateKey = translateKey,
                            pageHash = "webpage-text",
                            glossaryEntries = glossaryEntries,
                            timer = TranslationTimer(),
                            onTranslatorDownloading = null
                        )
                    }
                    val payload = buildList {
                        batch.forEachIndexed { index, item ->
                            val translated = outcome.result.bubbles.getOrNull(index)?.translatedText
                            if (!translated.isNullOrBlank() && translated != item.text) {
                                add(item.id to translated)
                            }
                        }
                    }
                    if (payload.isEmpty()) return@forEachIndexed
                    val applied = decodeJsJsonResult(
                        webView.evaluateJavascriptSuspend(
                            BrowserWebpageTextScript.applyJs(BrowserWebpageTextScript.applyPayload(payload)),
                            what = "webpage_apply"
                        )
                    )?.optInt("applied", 0) ?: 0
                    appliedTotal += applied
                    Log.d(TAG, "webpage_text_batch i=$batchIndex sent=${payload.size} applied=$applied total=$appliedTotal")
                }
                webpageTextApplied = appliedTotal > 0
                statusText = if (webpageTextApplied) {
                    "已翻译 $appliedTotal 段网页文字"
                } else {
                    "网页文字翻译未生效（译文与原文相同或回填被页面覆盖）"
                }
                Log.i(TAG, "webpage_text_done collected=${collected.size} applied=$appliedTotal")
            } catch (err: Exception) {
                statusText = "网页文字翻译失败：${err.message?.take(60) ?: "未知错误"}"
                Log.w(TAG, "webpage_text_fail reason=${err.message?.take(120)}")
            } finally {
                webpageTextBusy = false
            }
        }
    }

    /** 「恢复页面原文」：按页面侧注册表回写（只在仍等于译文时回写，页面自己改过的不碰）。 */
    fun restoreWebpageText() {
        sessionScope.launch {
            val restored = decodeJsJsonResult(
                webView.evaluateJavascriptSuspend(BrowserWebpageTextScript.restoreJs(), what = "webpage_restore")
            )?.optInt("restored", 0) ?: 0
            webpageTextApplied = false
            statusText = "已恢复页面原文"
            Log.d(TAG, "webpage_text_restore restored=$restored")
        }
    }

    fun startScreenshotTranslation() {
        if (screenshotBusy) {
            statusText = "正在截图翻译…"
            return
        }
        if (reShowBusy) return
        if (running) stopSession("已停止 · 译文保留")
        val captureUrl = webView.url
        if (captureUrl.isNullOrBlank() || captureUrl == "about:blank") {
            statusText = "请先打开漫画网页"
            return
        }
        screenshotBusy = true
        statusText = "正在截取画面…"
        Log.d(
            TAG,
            "shot_start url=${BrowserHistoryRepository.urlWithoutScheme(captureUrl)} " +
                "dom_overlay=$domOverlay shown=$shownLayer"
        )
        sessionScope.launch {
            // 位图所有权：translateImage 的所有出口（成功/失败）都会回收入参位图；
            // 只有「还没交出去」的路径（量几何失败 / 管线没跑）才由 finally 兜底回收。
            var captured: Pair<BrowserScreenshotTranslate.ViewportGeometry, Bitmap>? = null
            var ownedByPipeline = false
            // 截图前被收起来的是活翻译层吗（用于失败时原样贴回）。
            var hidLiveLayer = false
            try {
                // 0) 等上一会话真正收尾：`stopSession` 是协作式的（不 cancel），它可能正卡在
                //    一张图的 OCR 里 —— 不等它，截图管线就会和它抢同一个 Paddle 引擎。
                val previousJob = session.job
                if (previousJob != null) {
                    val waitStartedAt = System.currentTimeMillis()
                    withTimeoutOrNull(PREV_SESSION_JOIN_TIMEOUT_MS) { previousJob.join() }
                    Log.d(
                        TAG,
                        "shot_wait_prev waited=${System.currentTimeMillis() - waitStartedAt}ms"
                    )
                }
                // 1) 先收起当前译文层（R1：一次只有一个来源）——顺带修掉「截图把已有译文一起
                //    截进去、OCR 读到中文」的老限制；失败时 finally 会贴回。
                val previousLayer = shownLayer
                if (previousLayer != BrowserShownLayer.NONE) {
                    hideTranslationLayer("shot_capture")
                    hidLiveLayer = previousLayer == BrowserShownLayer.LIVE
                }
                // 2) 等一次视觉提交：PixelCopy 读的是**合成后**的窗口，DOM 刚摘掉的那一帧
                //    可能还没提交，不等就会把旧译文一起截进去。超时照旧截图（不阻塞用户）。
                val committed = awaitVisualCommit(webView, SHOT_VISUAL_COMMIT_TIMEOUT_MS)
                Log.d(TAG, "shot_frame_wait committed=$committed")
                // 3) 主线程：量几何与截图必须是同一瞬间（中间隔一次用户滚动就对不上）。
                captured = withContext(mainThreadDispatcher) {
                    if (webView.width <= 0 || webView.height <= 0) return@withContext null
                    val metrics = BrowserScreenshotTranslate.parseMetrics(
                        webView.evaluateJavascriptSuspend(
                            BrowserScreenshotTranslate.metricsJs(),
                            what = "shot_metrics"
                        )
                    ) ?: return@withContext null
                    val bitmap = Bitmap.createBitmap(
                        webView.width, webView.height, Bitmap.Config.ARGB_8888
                    )
                    // 2026-10-14 二修（用户实测 Lezhin 正文抓不到 → 两个 OCR 引擎都读不出）：
                    // `webView.draw(Canvas)` 画的是**软件层**，站点把正文图提升成 GPU 合成层
                    // （transform/lazy 渲染）时，那部分内容根本不在软件层里 —— 抓到的图
                    // 只剩背景/空白。改用 PixelCopy 直接读窗口**真实合成后的像素**
                    // （GPU 层也在内），不需要任何系统权限；失败才回退软件直画。
                    val copied = captureViaPixelCopy(webView, bitmap)
                    if (!copied) {
                        val previousLayerType = webView.layerType
                        webView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                        try {
                            webView.draw(Canvas(bitmap))
                        } finally {
                            webView.setLayerType(previousLayerType, null)
                        }
                    }
                    logCaptureStats(bitmap, copied)
                    metrics to bitmap
                }
                val geometry = captured?.first
                val bitmap = captured?.second
                if (geometry == null || bitmap == null) {
                    statusText = "截图失败：页面还没准备好"
                    Log.w(TAG, "shot_fail stage=capture")
                    return@launch
                }
                // 4) 共用管线（IO）：位图交给 translateImage，此后不再手动回收。
                val useDom = domOverlay
                statusText = "识别翻译中…"
                val startedAt = System.currentTimeMillis()
                val outcome = withContext(Dispatchers.IO) {
                    translator.translateImage(
                        bitmap,
                        renderMode = if (useDom) {
                            BrowserRenderMode.DOM_SCENE
                        } else {
                            BrowserRenderMode.FULL_PNG
                        },
                        onPhase = { label -> mainHandler.post { if (screenshotBusy) statusText = label } }
                    )
                }
                ownedByPipeline = true
                val costMs = System.currentTimeMillis() - startedAt
                // 5) 管线跑的这几秒里页面跳走了：结果作废（几何对新页面对不上）。
                //    「同一文档」判定：阅读器滚动写 ?cut= 之类不算跳转，截图结果不该因此作废。
                val urlNow = webView.url
                if (!BrowserUrlResolver.isSameDocument(urlNow, captureUrl)) {
                    Log.w(
                        TAG,
                        "shot_drop reason=navigated " +
                            "url=${BrowserHistoryRepository.urlWithoutScheme(urlNow.orEmpty())}"
                    )
                    statusText = "页面已跳转，截图结果已丢弃"
                    return@launch
                }
                // 6) 回贴：DOM 场景 → 视口锚点 + applySceneJson；整图 → mtlocal 贴图。
                //    两条画法都收口到 [displaySnapshotLayer]（它负责 shownLayer/viewMode/监听）。
                val scene = outcome.scene
                val png = outcome.renderedPng
                val shown = when {
                    useDom && scene != null && outcome.bubbleCount > 0 ->
                        displaySnapshotLayer(geometry, scene, null, outcome.bubbleCount)

                    png != null ->
                        displaySnapshotLayer(geometry, null, png, outcome.bubbleCount)

                    outcome.warningMessage != null -> {
                        // 管线自己失败了（translateImage 的兜底 Outcome：scene/png 都是 null）。
                        statusText = "截图翻译失败：${outcome.warningMessage.take(50)}"
                        Log.w(TAG, "shot_fail stage=pipeline msg=${outcome.warningMessage.take(60)}")
                        false
                    }

                    else -> {
                        // 确定性空结果：绝大多数是截图里真没有可读文字。
                        statusText = "截图里没读到可翻译的文字"
                        Log.d(
                            TAG,
                            "shot_empty bubbles=${outcome.bubbleCount} scene=${scene != null} useDom=$useDom"
                        )
                        false
                    }
                }
                if (shown) {
                    Log.d(
                        TAG,
                        "shot_ok mode=${if (useDom && scene != null) "dom" else "png"} " +
                            "bubbles=${outcome.bubbleCount} cost=${costMs}ms"
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Throwable) {
                statusText = "截图翻译失败：${err.message?.take(50) ?: "未知错误"}"
                Log.w(TAG, "shot_fail stage=pipeline ${err.javaClass.simpleName}: ${err.message}")
            } finally {
                screenshotBusy = false
                if (!ownedByPipeline) {
                    captured?.second?.let { if (!it.isRecycled) it.recycle() }
                }
                // 失败贴回：截图前收起了活翻译层，结果却没挂上屏 —— 把用户原来的译文还给他
                // （走一次性贴回，不再跑 OCR）。页面已跳转则什么都不做。
                if (hidLiveLayer && shownLayer == BrowserShownLayer.NONE &&
                    BrowserUrlResolver.isSameDocument(webView.url, captureUrl)
                ) {
                    showLiveLayer("shot_restore")
                }
            }
        }
    }

    // 悬浮球与「原文｜译文」开关共用的控件状态（纯函数，成本可忽略；提升到
    // when(browserPage) 之前是因为悬浮球在网页态 Box 里也要用同一份判定）。
    // 声明必须在 onPrimaryAction 之前：Kotlin 局部函数看不到自己后面才声明的变量
    // （2026-10-12 修复：原来声明在使用点之后，app 模块整体编译不过）。
    val controls = BrowserTranslateUiPolicy.resolve(
        pageLoaded = pageUrl.isNotBlank() && pageUrl != "about:blank",
        running = running,
        reShowBusy = reShowBusy,
        screenshotBusy = screenshotBusy,
        shown = shownLayer
    )

    /**
     * 翻译任务的主操作（2026-10-12 起唯一入口是悬浮球单击；原底栏主按钮已移除）。
     *
     * 三个语义由 [BrowserTranslateUiPolicy.resolve] 判定，这里只负责执行 ——
     * 判定与执行分开是为了让"按钮文案说它会做什么"这件事可单测：
     *
     * - [BrowserPrimaryAction.RESTORE]：截图覆盖层在屏 → **恢复原文**（对齐首页那条链路：
     *   译文层在屏时点球 = 恢复原文，不触发新翻译）；
     * - [BrowserPrimaryAction.STOP]：会话在跑 → 停止（译文保留）；
     * - [BrowserPrimaryAction.START]：**开始整页翻译**，并把视图切到「译文」——
     *   否则用户切过「原文」之后点球，会话会安静地后台翻、屏上一张都不上（"点球没反应"）。
     *
     * 2026-10-16 删除：改造前这里有一条「空闲且屏上无译文 → 重发上一次截图结果」的前置拦截
     * （§46 的实现）。它正是用户报「点球无法翻译」的直接原因 —— 截图结果既不过期也不让位，
     * 球被它劫持后永远起不了新翻译。现在截图覆盖层是一次性的，重放机制整个不存在。
     */
    fun onPrimaryAction() {
        val action = controls.primaryAction
        if (!controls.primaryEnabled || action == null) {
            statusText = controls.modeToggleDisabledReason ?: "暂时不可用"
            Log.d(
                TAG,
                "ball_tap_rejected busy=${controls.primaryBusy} shown=$shownLayer running=$running"
            )
            return
        }
        when (action) {
            BrowserPrimaryAction.RESTORE -> {
                // 覆盖层收起走统一的收层入口（页面侧定向摘除 + 解除监听 + 记账复位）。
                hideTranslationLayer("ball_restore")
                statusText = "已恢复原文 · 再点球翻译整页"
                Log.d(TAG, "ball_tap action=restore")
            }

            BrowserPrimaryAction.STOP -> {
                stopSession(if (placedIds.isNotEmpty()) "已停止 · 译文保留" else "已停止")
                Log.d(TAG, "ball_tap action=stop")
            }

            BrowserPrimaryAction.START -> {
                // 保证点球一定看到中文：START 同时切到「译文」视图。
                viewMode = BrowserViewMode.TRANSLATED
                Log.d(TAG, "ball_tap action=start shown=$shownLayer")
                startSession()
            }
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

        // 网页态：浏览器自有顶栏（离开网页 + 可编辑地址框 + 收藏星 + 设置）+ 进度条 + 网页 + 网页导航底栏。
        // 主底栏隐藏；点地址框**原地编辑 URL**（不再跳新标签页，2026-09-28 用户反馈对齐正规浏览器）；
        // 新标签页（收藏/历史）由顶栏左位的「离开网页」与系统返回手势进入。
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
                // 「离开网页」（画布 `11:3` / `11:220` / `23:153` 三屏顶栏第一位）。
                //
                // 这里原来是「网页后退」，和底栏的后退是同一个动作 —— 2026-10-12 用户指出重复后换掉。
                // 换成离开键而不是「什么都不放」：底栏保留 后退/前进/刷新 三件套，
                // 顶栏左位承担「离开这个网页、回到浏览器界面」；它**不动网页自己的历史**，
                // 误触也能再点回去，所以放在最顺手的位置不危险。
                MtBrowserIconButton(
                    icon = MtLeavePageIcon,
                    desc = "离开网页",
                    onClick = {
                        settingsSheetOpen = false
                        addressEditing = false
                        keyboard?.hide()
                        refreshHistory()
                        browserPage = BrowserPage.NEW_TAB
                        Log.d(TAG, "browser_leave_page from=WEB")
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
                    // 地址胶囊（画布 `11:139`）：h40 / r20 / Filled 底 #EEF1F7 / L14 R10 / gap8
                    // = [锁 14][网址][✕ 14]。网址用**主文字色**：它是这一行唯一的信息，
                    // 用次要色会被两侧的图标压下去。
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MtColors.SurfaceMuted)
                            .clickable { beginAddressEdit() }
                            .padding(start = 14.dp, end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 锁只在 https 下出现：设计稿画的是 https 站（kakaopage.com），
                        // 但给 http 站挂一把锁就是撒谎，所以按真实协议显示（唯一一处有意偏离设计稿）。
                        if (pageUrl.startsWith("https://")) {
                            Icon(
                                imageVector = MtLockIcon,
                                contentDescription = "安全连接",
                                tint = MtColors.TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        val displayAddress =
                            if (pageUrl.isNotBlank() && pageUrl != "about:blank") {
                                BrowserHistoryRepository.urlWithoutScheme(pageUrl)
                            } else {
                                "新标签页"
                            }
                        Text(
                            text = displayAddress,
                            fontSize = 14.sp,
                            color = MtColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        // ✕ 清除：自己吃掉点击，否则会穿透到父级的「点按进入编辑」。
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .clickable { clearAddressAndEdit() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "清除地址",
                                tint = MtColors.TextTertiary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
                // 收藏当前页（画布 `31:42`「收藏星 · 两态」）。
                //
                // 2026-10-12 用户把收藏从设置弹层提到顶栏：读漫画时「想收藏这个站」是
                // 读到哪里冒出来的冲动动作，原来要先点齿轮、再从弹层最底下找「收藏当前页」，
                // 隔着两层；现在一按即收、再按即撤。
                // 两态：未收藏 = 空心描边 `#10141C`；已收藏 = 实心 `#F5A623`（见 [MtColors.FavoriteStar]）。
                val normalizedPageUrl = BrowserHistoryRepository.normalizeUrl(pageUrl)
                val pageFavorited = normalizedPageUrl != null &&
                    favorites.any { it.url == normalizedPageUrl }
                MtBrowserIconButton(
                    icon = if (pageFavorited) MtStarFilledIcon else MtStarIcon,
                    desc = if (pageFavorited) "取消收藏当前页" else "收藏当前页",
                    tint = if (pageFavorited) MtColors.FavoriteStar else MtColors.TextPrimary,
                    onClick = {
                        when {
                            normalizedPageUrl == null -> {
                                Toast.makeText(context, "当前没有打开的网页", Toast.LENGTH_SHORT).show()
                            }
                            pageFavorited -> {
                                favoriteRepository.remove(normalizedPageUrl)
                                refreshFavorites()
                                Toast.makeText(context, "已取消收藏", Toast.LENGTH_SHORT).show()
                                Log.d(
                                    TAG,
                                    "favorite_remove url=${BrowserHistoryRepository.urlWithoutScheme(normalizedPageUrl)}"
                                )
                            }
                            else -> {
                                // 名字留空 → 仓储用域名兜底；改名走首页收藏格的「编辑」。
                                val result = favoriteRepository.add(
                                    BrowserFavoriteRepository.defaultLabel(normalizedPageUrl),
                                    normalizedPageUrl
                                )
                                refreshFavorites()
                                val toast = when (result) {
                                    FavoriteAddResult.ADDED -> "已收藏"
                                    FavoriteAddResult.UPDATED -> "已更新收藏"
                                    FavoriteAddResult.FULL ->
                                        "收藏已满（最多 ${BrowserFavoriteRepository.MAX_FAVORITES} 个）"
                                    FavoriteAddResult.INVALID_URL -> "这个网址不能收藏"
                                }
                                Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
                                Log.d(
                                    TAG,
                                    "favorite_add url=${BrowserHistoryRepository.urlWithoutScheme(normalizedPageUrl)}"
                                )
                            }
                        }
                    }
                )
                // 「浏览器与翻译」底部弹层（画布 `10:133`，整屏稿 `10:1`）。
                //
                // 原来这个齿轮挂的是 MtDropdownMenu，条目一路长出屏幕（新标签页 / 站点适配 /
                // 精细擦字 / 覆盖层渲染 / 搜索引擎 / 浏览历史 / 收藏当前页 / 清空浏览历史），
                // 而且「设置」里塞「清空历史」本身就不合语义。
                // 现在：设置项全部进弹层，弹层里一条操作都没有（收藏当前页已提到左边那颗星）；
                // 新标签页 / 浏览历史 / 清空历史在浏览器首页（收藏格 + 历史记录区）都有入口。
                MtBrowserIconButton(
                    icon = Icons.Filled.Settings,
                    desc = "浏览器与翻译设置",
                    onClick = { settingsSheetOpen = true }
                )
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
                // 翻译悬浮球（2026-10-12 用户要求「翻译按钮变成悬浮球，好按一点」）：
                // 单击 = 翻译/停止/恢复原文（三态由 [BrowserTranslateUiPolicy.resolve] 判定）；
                // 长按或点 ⋮ 胶囊 = 截图翻译 + 原文/译文菜单。只盖在网页内容区上。
                BrowserTranslateFloatingBall(
                    running = running,
                    busy = controls.primaryBusy,
                    enabled = controls.primaryEnabled,
                    action = controls.primaryAction,
                    pageUrl = pageUrl,
                    viewMode = viewMode,
                    modeToggleEnabled = controls.modeToggleEnabled,
                    modeToggleDisabledReason = controls.modeToggleDisabledReason,
                    onPrimaryTap = { onPrimaryAction() },
                    onSelectViewMode = { target ->
                        when (target) {
                            BrowserViewMode.ORIGINAL -> showOriginal()
                            BrowserViewMode.TRANSLATED -> showTranslations()
                        }
                    },
                    onScreenshotTranslate = { startScreenshotTranslation() }
                )
            }

            // 底栏：单行 48dp（2026-10-12 第三轮，画布 `11:220` 定稿）。
            //
            // 只留网页导航（后退/前进/刷新）+ 状态文字：
            // - 「翻译/停止」主按钮 → 网页上的**悬浮球**（原按钮在底栏角落，长漫画阅读时够不着）；
            // - 「原文｜译文」分段开关 → 移到悬浮球的 ⋮ 菜单里（底栏再放一份只会让
            //   「哪个才是权威入口」继续含糊，2026-10-02 的老问题）。
            Column(modifier = Modifier.background(MtColors.Surface)) {
                HorizontalDivider(color = MtColors.Border)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .height(48.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                    // 状态行：任务文案优先，空闲时兜底文案。
                    // （2026-10-15 用户裁定移除「语言可能选错了」提示 —— 韩漫里夹汉字/英文是常态，
                    // 按脚本族判「选错了」误报太多。这个位置不再有任何提示优先级逻辑。）
                    Text(
                        text = statusText.ifBlank {
                            BrowserTranslateUiPolicy.idleHint(
                                viewMode = viewMode,
                                running = running,
                                shown = shownLayer,
                                pageLoaded = pageUrl.isNotBlank() && pageUrl != "about:blank"
                            )
                        },
                        fontSize = 12.sp,
                        color = MtColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }

    /**
     * 套用某个云端配置档案（与首页 `ProfileSheet` 同一套动作：套配置 + 记住这次的选择）。
     *
     * 档案自带 engine/provider/baseUrl/model/key，所以「选档案」这个动作本身就把引擎切到了云端。
     * 2026-10-15：与首页同走 [applyProfile] —— 只搬档案自己的字段，**语言对 / OCR 引擎保留
     * 用户当前值**（档案表单里根本没有语言项，整包替换会让语言跟着档案建时的快照乱跳，
     * 用户反馈「换翻译配置会换掉源语言」）。
     */
    fun pickTranslateProfile(name: String) {
        settingsRepository.getProfile(name)?.let { profile ->
            updateSettings { it.applyProfile(profile) }
        }
        settingsRepository.setLastTranslateProfileName(name)
        activeProfile = name
        invalidateRenderedResults("已切换翻译配置（$name）")
        Log.d(TAG, "translate_profile_set name=$name")
    }

    /**
     * 换翻译引擎（百度 / ML Kit 本地）。
     *
     * 云端不是一条独立的「引擎」，必须落到某个配置档案上才有 Base URL / 模型 / Key，
     * 所以下拉里的云端入口就是各个档案本身 —— 见下面的 `translateProfileOptions`。
     */
    fun pickTranslateEngine(engine: TranslateEngine) {
        updateSettings { it.copy(translateEngine = engine.id) }
        invalidateRenderedResults("已切换翻译引擎（${engine.sheetLabel()}）")
        Log.d(TAG, "translate_engine_set engine=${engine.id}")
    }

    // 「浏览器与翻译」弹层（画布 `10:133`）：网页态齿轮点开。
    // 放在这一层而不是 `when(browserPage)` 里面，是为了让它在任何页面态都能盖上来。
    if (settingsSheetOpen) {
        BrowserSettingsSheet(
            sourceLanguage = AppLanguage.fromId(appSettings.sourceLanguage),
            targetLanguage = AppLanguage.fromId(appSettings.targetLanguage),
            onSetSourceLanguage = { language ->
                updateSettings { it.copy(sourceLanguage = language.id) }
                invalidateRenderedResults("已切换源语言")
            },
            onSetTargetLanguage = { language ->
                updateSettings { it.copy(targetLanguage = language.id) }
                invalidateRenderedResults("已切换目标语言")
            },
            onSwapLanguages = {
                // 直接对调两个字段，不要走两次 copy：中间态会让「源=目标」短暂成立。
                updateSettings {
                    it.copy(
                        sourceLanguage = it.targetLanguage,
                        targetLanguage = it.sourceLanguage
                    )
                }
                invalidateRenderedResults("已交换语言")
            },
            adapter = matchedAdapter,
            rulesActive = siteRulesActive(matchedAdapter),
            inpaintClean = inpaintClean,
            onInpaintCleanChange = { enabled ->
                inpaintClean = enabled
                settingsRepository.setBrowserInpaintCleanEnabled(enabled)
                // 已贴的成图是旧设置渲染的：全部丢掉，下次「翻译」按新设置重画。
                clearRenderedOverlays()
                stopSession(
                    if (enabled) "已开启精细擦字（整页 inpaint），点「翻译」重新翻译"
                    else "已关闭精细擦字（逐块快速贴背景），点「翻译」重新翻译"
                )
                Log.d(TAG, "inpaint_clean_set enabled=$enabled")
            },
            domOverlay = domOverlay,
            onDomOverlayChange = { enabled ->
                domOverlay = enabled
                settingsRepository.setBrowserDomOverlayEnabled(enabled)
                // 旧结果是旧模式渲染的：全部丢掉，下次「翻译」按新模式重画。
                clearRenderedOverlays()
                stopSession(
                    if (enabled) "已开启覆盖层渲染，点「翻译」重新翻译"
                    else "已切换整图替换，点「翻译」重新翻译"
                )
                Log.d(TAG, "dom_overlay_set enabled=$enabled")
            },
            webpageTextBusy = webpageTextBusy,
            webpageTextApplied = webpageTextApplied,
            onTranslatePageText = {
                settingsSheetOpen = false
                startWebpageTextTranslation()
            },
            onRestorePageText = {
                settingsSheetOpen = false
                restoreWebpageText()
            },
            translateProfile = translateProfileLabel,
            // 「翻译配置」下拉 = 两个内置引擎 + 云端配置档案。
            //
            // 为什么把引擎也塞进来：这一行显示的是「引擎 · 档案」，候选里如果只有档案名，
            // 用户当前选着「ML Kit 本地翻译」，点开只看到一个「qwen」—— 既解释不了现在用的是谁，
            // 也没有路走回百度 / ML Kit。候选必须包含当前值，这是这行的底线。
            // 云端不单列一项：脱离档案的「云端」没有 Base URL / 模型 / Key，选中了也不成立。
            translateProfileOptions = buildList {
                add(TranslateEngine.BAIDU.sheetLabel() to { pickTranslateEngine(TranslateEngine.BAIDU) })
                add(TranslateEngine.ML_KIT.sheetLabel() to { pickTranslateEngine(TranslateEngine.ML_KIT) })
                profileNames.forEach { name ->
                    // 档案项文案跟「当前值」同规格（厂商 · 档案名），选中后能和行上的值对上。
                    val providerLabel = settingsRepository.getProfile(name)
                        ?.let { AiProvider.fromId(it.translateProvider).label }
                    val text = if (providerLabel == null) name else "$providerLabel · $name"
                    add(text to { pickTranslateProfile(name) })
                }
            },
            ocrEngine = OcrEngine.fromId(appSettings.ocrEngine),
            onPickOcrEngine = { engine ->
                updateSettings { it.copy(ocrEngine = engine.id) }
                invalidateRenderedResults("已切换识别引擎（${engine.label}）")
                Log.d(TAG, "ocr_engine_set engine=${engine.id}")
            },
            onDismiss = { settingsSheetOpen = false }
        )
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
    /**
     * 「已产出过结果」的图（src），**不随场景缓存淘汰**。
     *
     * 为什么与 [renderedScenes] 分开：场景缓存按 [MAX_RENDERED_SCENES] 淘汰，
     * 淘汰后 `renderedScenes[src]` 查不到 —— 若 `isPending` 只看缓存，长章节里
     * 最早那批图会重新变回「待翻译」，会话整章翻完后回头再翻一遍（永远转不完，
     * 2026-10-15 随之开放的 174 切长章节实测风险）。
     */
    renderedSrcs: HashSet<String>,
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
    /**
     * 移交**来源图**的内容指纹（[contentFingerprint]）。
     *
     * 为什么必须记：懒加载站会把 `<img>` 换成新 blob URL（内容不变）。移交刚登记完、
     * 下一张还没处理时来源图被换 URL 重扫，src 失配判定会把自己刚登记的移交清掉
     * （2026-10-12 真机 gamesal 7/8 页实测）→ 页 8 拿不到移交。src 失配但指纹相同 =
     * 同一张图换了 URL → 换绑 src 保留移交（见消费点的 `incoming_rebind`）。
     */
    var incomingCrossSrcFp: String? = null
    var incomingCross: List<CrossBubblePart> = emptyList()
    /** 已打过 `band_pair` 诊断的「图 + 结论」组合（见下面的去重注释）。 */
    val loggedBandPairs = HashSet<String>()
    /** `scan_capped` 警告是否已打过（截断发生时每轮都打会刷屏，只留第一条）。 */
    var cappedWarned = false
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
    // 上一轮等待时读到的页面滚动位置：连续两轮不变 = 用户停住了，懒加载不会再来新图，
    // 等待立即放弃（否则每张图白等满窗 —— 2026-10-12「识别突然变慢」）。
    var lastNextWaitScrollY = -1f
    // 同内容去重（2026-10-11「那个坑」）：内容指纹 → 已渲染场景（含接缝带）。
    // 阅读器重建 `<img>`（新 blob URL）后，扫描器把同一张图当新图重翻——这里按内容指纹
    // 复用已渲染场景，不再重跑 OCR/翻译/擦除。上限防止长会话内存增长。
    val scenesByContent = LinkedHashMap<String, CachedSceneRender>()
    // 本轮正在处理的图的内容指纹（withContext 内算好，上屏成功后回填 [scenesByContent]）。
    var currentContentKey: String? = null
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
        // 会话被停（token 变了）之后不再写状态行：停止是协作式的，旧会话还会收尾一小会儿，
        // 它写回「翻译中…」会把「已停止 · 译文保留」顶掉（2026-10-14）。
        // 它写回「翻译中…」会把「已停止 · 译文保留」顶掉（2026-10-14）。
        if (!onSessionActive()) return
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
        incomingCrossSrcFp = null
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

    fun BrowserSceneBuilder.SceneBubble.toRaceCandidate(): SeamRaceBand.Candidate =
        SeamRaceBand.Candidate(
            id = id,
            x = x,
            y = y,
            w = w,
            h = h,
            originalText = originalText,
            coverBox = coverBox
        )

    /**
     * 晚邻接竞态的**接缝带补建**（2026-10-14，Todolist §41 后续；纯几何匹配在 [SeamRaceBand]）。
     *
     * 现场：上一张图（A）处理时下一张图还没出现 → A 把跨缝句当自己的泡整块画掉；
     * 本图（B）拿到 A 的底条带后又整段认领重画（从缝口起排）→ 同一句画两遍、且向下溢出气泡。
     *
     * 这里在 B 侧补一条与正常路径同构的接缝带（一个覆盖层跨两张图），成功后摘掉 A/B 两边的重复块。
     * 触发条件全部收窄（缺一不可）：B 有紧邻的上一张、A/B 场景都在屏上、A 没有带过带、
     * B 的泡贴顶 + A 的泡贴底 + 文本按字符覆盖率匹配（[HandoffTextMatch] ≥ 0.7）、
     * **B 的泡是实底**（非实底要清理图纹理，竞态时拿不到上一张的清理图 → 不补，保持现状）。
     */
    suspend fun tryLateSeamBand(
        image: ScannedImage,
        prevImage: ScannedImage?,
        scene: BrowserSceneBuilder.Scene,
        hasOwnBand: Boolean
    ): Boolean {
        if (!useDom || prevImage == null || hasOwnBand) return false
        if (!placedIds.contains(prevImage.id)) return false
        if (seamBySrc.containsKey(prevImage.src)) return false
        val prevScene = renderedScenes[prevImage.src] ?: run {
            Log.d(TAG, "seam_race_skip reason=no_prev_scene own=${prevImage.id}")
            return false
        }
        val match = SeamRaceBand.findMatch(
            previous = prevScene.bubbles.map { it.toRaceCandidate() },
            current = scene.bubbles.map { it.toRaceCandidate() },
            previousWidthPx = prevImage.width,
            previousHeightPx = prevImage.height,
            currentWidthPx = image.width,
            currentHeightPx = image.height,
            // 同图其它气泡 = 盖板要避让的对象（认领框比真实气泡高时不许盖住它们的文字）。
            blockers = scene.bubbles.map { it.toRaceCandidate() }
        ) ?: run {
            // 诊断（用户报「三个覆盖层」时按这一行定位：几何不贴边还是文本不匹配）。
            val prevDigest = prevScene.bubbles.take(3).joinToString(" ") { b ->
                "y+h=${"%.1f".format(b.y + b.h)}"
            }
            val curDigest = scene.bubbles.take(3).joinToString(" ") { b ->
                "y=${"%.1f".format(b.y)}"
            }
            val bestScore = prevScene.bubbles.take(3).maxOfOrNull { prev ->
                scene.bubbles.take(3).maxOfOrNull { cur ->
                    maxOf(
                        HandoffTextMatch.overlap(prev.originalText, cur.originalText),
                        HandoffTextMatch.overlap(cur.originalText, prev.originalText)
                    )
                } ?: 0f
            } ?: 0f
            Log.d(
                TAG,
                "seam_race_skip reason=no_match own=${prevImage.id} next=${image.id} " +
                    "prev[$prevDigest] cur[$curDigest] best=${"%.2f".format(bestScore)}"
            )
            return false
        }
        val bubble = scene.bubbles.firstOrNull { it.id == match.currentId } ?: return false
        if (!bubble.bgType.equals("solid", ignoreCase = true)) {
            // 非实底（旁白/插画底）：接缝带必须带清理图纹理才不会糊画面，竞态时拿不到（A 的清理图已回收）。
            Log.d(TAG, "seam_race_skip reason=not_solid own=${prevImage.id} id=${bubble.id}")
            return false
        }
        // 字号：B 泡的原文单行字高（相对 B 图）→ 像素 → 换算到 A 的像素空间 → 带内百分比。
        val factor = prevImage.width.toFloat() / image.width.toFloat()
        val fontPercent = if (match.bandHeightPx > 0) {
            bubble.fontHeightPercent * image.height * factor / match.bandHeightPx
        } else {
            0f
        }
        val bandBubble = bubble.copy(
            id = "race0",
            x = match.layoutBoxPercent[0],
            y = match.layoutBoxPercent[1],
            w = match.layoutBoxPercent[2],
            h = match.layoutBoxPercent[3],
            coverBox = match.coverBoxPercent,
            // 轮廓/四边形/清理图补丁都按「上一张图的坐标系」重算不了 → 全部退回矩形盖板
            // （带本身只覆盖这一句，矩形不越出多少；宁可不裁也不能用错坐标裁）。
            regionPolygon = null,
            polygon = null,
            rotationDeg = null,
            fillBox = null,
            cleanedSourceBox = null,
            fixedFontPercent = null,
            fontHeightPercent = fontPercent,
            // 跨缝：译文排版框允许越出带上边界（文字本来就压在缝上）。
            stitchOverflow = true
        )
        val bandScene = SeamBandScene(
            bubbles = listOf(bandBubble),
            bandLeftPx = match.bandLeftPx,
            bandTopPx = match.bandTopPx,
            bandWidthPx = match.bandWidthPx,
            bandHeightPx = match.bandHeightPx,
            topHPx = 0,
            sourceWidthPx = prevImage.width,
            sourceHeightPx = prevImage.height,
            // 实底泡不需要纹理（与正常路径同口径：纯色带 cleanedUrl=null）。
            cleanedUrl = null,
            ownBubbleIds = listOf(match.previousId),
            nextBubbleIds = listOf(match.currentId)
        )
        val ok = dispatchSeamScene(prevImage.id, image.id, bandScene)
        Log.d(
            TAG,
            "seam_race_band own=${prevImage.id} next=${image.id} ok=$ok " +
                "prev=${match.previousId} cur=${match.currentId} " +
                "score=${"%.2f".format(match.textScore)} " +
                "band=${match.bandWidthPx}x${match.bandHeightPx}@${match.bandLeftPx},${match.bandTopPx}"
        )
        if (!ok) return false
        seamBySrc[prevImage.src] = PendingSeam(scene = bandScene, nextSrc = image.src)
        while (seamBySrc.size > MAX_RENDERED_SCENES) {
            seamBySrc.remove(seamBySrc.keys.first())
        }
        seamSuppress.getOrPut(prevImage.id) { mutableListOf() } += match.previousId
        seamSuppress.getOrPut(image.id) { mutableListOf() } += match.currentId
        // 两边的场景都已经上屏：补带成功后**立刻**摘掉重复块（与正常路径的时序一致）。
        suppressSeamHalves(prevImage.id)
        suppressSeamHalves(image.id)
        return true
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
        // 「已产出过结果」的图同样不算待处理 —— 判据用**不受场景缓存淘汰**的集合
        // （2026-10-15 长章节修复）：只看 renderedScenes/renderedTokens 的话，
        // 缓存淘汰会把老图打回「待翻译」，会话永远在重翻最早的几十张。
        if (renderedSrcs.contains(image.src)) return false
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
            // 「同一份文档」判定而不是字符串相等（2026-10-15，用户报 Kakao「翻到一半停下来」）：
            // 阅读器滚动时用 pushState 把阅读位置写进地址（`?cut=N` / `#cut-N`），文档没换 ——
            // 字符串比较会在用户每次滚动后命中，整段会话被误判为「页面已跳转」而终止。
            if (!BrowserUrlResolver.isSameDocument(webView.url, sessionPageUrl)) {
                reportStatus("页面已跳转，点「翻译」继续")
                return
            }
            val scan = parseScannedImages(webView.evaluateJavascriptSuspend(BrowserScanScript.SCAN_JS, what = "scan"))
            val scanCostMs = System.currentTimeMillis() - loopStartedAt
            if (scanCostMs > 2_000) {
                Log.w(TAG, "scan_slow cost=${scanCostMs}ms（扫描本身慢：页面大或主线程被占）")
            }
            val images = scan.images
            // 阅读位置优先（2026-10-15，用户报 Kakao「我在中间看，它先从头开始翻」）：
            // 把处理列表旋转到「当前阅读锚点」（视口高度 35% 处的图）起，往下翻；
            // 视口上方已读过的部分轮转到末尾。视口高度缺失（老 payload）退化为文档序。
            // 只改处理顺序，不改任何几何/接缝判据（它们按几何找相邻，与列表顺序无关）。
            val orderedImages = if (scan.viewportHeight > 0 && images.isNotEmpty()) {
                BrowserImageOrder.rotateToReadingAnchor(
                    spans = images.map {
                        BrowserImageOrder.ViewportSpan(top = it.viewportTop, bottom = it.viewportTop + it.height)
                    },
                    anchorY = (scan.viewportHeight * VIEWPORT_ANCHOR_RATIO).toInt()
                ).map { images[it] }
            } else {
                images
            }
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
                    "anchor=${(scan.viewportHeight * VIEWPORT_ANCHOR_RATIO).toInt()} " +
                    "first_vy=${orderedImages.firstOrNull()?.viewportTop ?: -1} " +
                    "capped=${scan.truncated} " +
                    "done=$doneCount shown=${placedIds.size}"
            )
            // 安全阀截断（旧版 80 上限的教训：静默截断 = 用户看到「翻到一半不翻了」）。
            // 正常恒不触发；一旦触发，这条日志是「后面还有图没翻」的唯一线索。
            if (scan.truncated && !cappedWarned) {
                cappedWarned = true
                Log.w(
                    TAG,
                    "scan_capped scanned=${images.size} pending=$pendingTotal " +
                        "adapter=${scan.adapterId} —— 候选超过扫描上限，" +
                        "列表之外的图不会被翻译（见 BrowserScanScript 的 limit 注释）"
                )
            }

            var batchIndex = 0
            for (image in orderedImages) {
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
                    //
                    // ⚠️ 「下一张」必须与正常翻译路径用**同一个判据**（几何紧邻，见下面的
                    // `SeamNeighbourPolicy.successor`）：这里此前拿的是扫描顺序的下一张，
                    // 而扫描列表顺序不保证是文档序（显式选择器命中先收）—— 一旦取错，
                    // `PendingSeam.nextSrc` 对不上 → 接缝层既不重发、被它顶掉的半块也不补摘
                    // → 同一句台词画两遍（用户 2026-10-13：「向下划后再次向上划的时候
                    // 可能会出现重复的译文」）。
                    if (useDom && placedIds.contains(image.id)) {
                        val curBox = StripBox(
                            src = image.src,
                            left = image.left,
                            top = image.top,
                            width = image.width,
                            height = image.height,
                            hostId = image.hostId,
                            frame = image.frame
                        )
                        val nextCandidate = SeamNeighbourPolicy.successor(curBox, images.map { it.toStripBox() })
                        val nextImage = nextCandidate
                            ?.takeIf { SeamNeighbourPolicy.decide(curBox, it).contiguous }
                            ?.let { box -> images.firstOrNull { it.src == box.src } }
                        val cachedSeam = seamBySrc[image.src]
                            ?.takeIf { it.nextSrc == nextImage?.src }
                            ?.scene
                        afterScenePlaced(image, nextImage, cachedSeam)
                        // 晚邻接竞态补带（2026-10-14 第二轮，用户报「翻下去再滑上来，一句话出现两次」）：
                        // 阅读器把 `<img>` 换成新 blob URL 重建元素后，接缝带按 src 找不到
                        // （注册时的 src 已经失效）→ 这里与翻译路径同源，按几何（贴边）+ 文本匹配
                        // 重新补一条带（内部用**当前**的元素 id 下发），把两边的重复块摘掉。
                        if (cachedSeam == null && cachedScene != null) {
                            val prevBox = SeamNeighbourPolicy.predecessor(curBox, images.map { it.toStripBox() })
                            val prevImage = prevBox?.let { box -> images.firstOrNull { it.src == box.src } }
                            tryLateSeamBand(
                                image = image,
                                prevImage = prevImage,
                                scene = cachedScene,
                                hasOwnBand = false
                            )
                        }
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
                        // 滚动静止检测（2026-10-12「识别突然变慢」回归修复）：懒加载是
                        // 滚动驱动的 —— 用户停住时下一张永远不会出现，傻等满窗只会让
                        // 每张图都白挂几秒。连续两轮 scrollY 不变 = 用户停住了，立即放弃
                        // 等待照常处理；正在连续翻页时每 700ms 重扫一次，下一张一出现
                        // 立即处理（等待成本从最高 7.2s 降到通常 0.7~1.4s）。
                        val scrollYNow = webView.evaluateJavascriptSuspend("window.scrollY||0", what = "wait_scroll")
                            ?.trim()?.toFloatOrNull() ?: -1f
                        val pageStill = scrollYNow >= 0f && scrollYNow == lastNextWaitScrollY
                        lastNextWaitScrollY = scrollYNow
                        if (pageStill && waited >= 1) {
                            nextPageWaits[image.src] = NEXT_PAGE_WAIT_ROUNDS
                        } else {
                            nextPageWaits[image.src] = waited + 1
                            delay(NEXT_PAGE_WAIT_STEP_MS)
                            continue
                        }
                    }
                }
                val imageStartedAt = System.currentTimeMillis()
                currentContentKey = null
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
                    // ⚠️ 例外（2026-10-12 blob 轮换）：站点会在处理途中把 `<img>` 换成新 blob
                    // URL —— 移交来源图换了 URL 后重扫，src 失配会把自己刚登记的移交清掉
                    // （真机 gamesal 7/8 页实测 `incoming_dropped reason=no_prev count=2` →
                    // 页 8 incoming=0 → 缝上残行被自己翻出坏译文叠在接缝带上）。当前图与
                    // 移交来源图**内容指纹相同** = 同一张图换了 URL：换绑 src 保留移交，
                    // 本图自己不消费（它接着重新产出自己的移交；content_reuse 命中时移交
                    // 原样保留给下一张）。
                    val incomingUsed: List<CrossBubblePart> = if (incomingCross.isNotEmpty() &&
                        incomingCrossSrcFp != null && incomingCrossSrc != image.src &&
                        incomingCrossSrcFp == contentFingerprint(current)
                    ) {
                        Log.d(
                            TAG,
                            "incoming_rebind from=${BrowserHistoryRepository.urlWithoutScheme(incomingCrossSrc ?: "")} " +
                                "to=${BrowserHistoryRepository.urlWithoutScheme(image.src)} count=${incomingCross.size}"
                        )
                        incomingCrossSrc = image.src
                        emptyList()
                    } else if (neighbour.contiguous && incomingCrossSrc == neighbourSrc) {
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
                    // 同内容去重（2026-10-11「那个坑」）：阅读器会把已翻译的 `<img>` 换成新
                    // blob URL 重建元素（真机 MTSEAM restore reason=target_detached）——扫描器
                    // 看到的是「新图」，实际像素相同。重翻一遍的代价：整页译文闪没了、接缝带
                    // 换绑（mt1|mt2 → mt4|mt2）、两半贴回再摘除，还白烧一次 OCR+翻译。
                    // 指纹命中 → 直接复用已渲染场景（含接缝带），几何是百分比坐标，换 image id
                    // 照样成立；移交（crossOut）不重建——下一张早就拿着第一次的移交翻好了。
                    val contentKey = contentFingerprint(current)
                    val cachedRender = if (contentKey.isNotEmpty()) scenesByContent[contentKey] else null
                    if (cachedRender != null) {
                        Log.d(
                            TAG,
                            "content_reuse url=${BrowserHistoryRepository.urlWithoutScheme(image.src)} " +
                                "bubbles=${cachedRender.scene.bubbles.size} seam=${cachedRender.seam != null}"
                        )
                        incomingUsed.forEach { part -> runCatching { part.patchBitmap.recycle() } }
                        topStripSource?.let { strip -> if (!strip.isRecycled) strip.recycle() }
                        if (!current.isRecycled) current.recycle()
                        return@withContext com.manga.translator.app.WebtoonImageTranslator.Outcome(
                            bubbleCount = cachedRender.scene.bubbles.size,
                            cacheHitCount = 0,
                            cacheTotalCount = 0,
                            warningMessage = null,
                            renderedPng = null,
                            scene = cachedRender.scene,
                            crossOut = emptyList(),
                            seam = cachedRender.seam,
                            sceneDiag = "reused",
                            stripTruncated = false
                        )
                    }
                    currentContentKey = contentKey.takeIf { it.isNotEmpty() }
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
                            // 有接缝带瞄准本图（上一张的 seam 已登记、nextSrc == 本图）时关闭
                            // 「相邻行并回」：接缝带的译文在缝上下连成一句，并回的整句重翻会在
                            // 带派发时被摘掉（见 translateImage.allowIncomingMerge 的 KDoc）。
                            allowIncomingMerge = seamBySrc.values.none { it.nextSrc == image.src },
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
                // 会话已被停（协作式停止，token 变了）：这张图的结果不再上屏/登记，直接收尾退出
                // （清理交给 finally）。少了这一步，停止后旧会话还会把缓存/贴回状态写到新会话头上
                // （2026-10-14）。⚠️ 这条路径不再往下走，跨图贴片要在这里自己回收，否则每停一次漏一批位图。
                if (!onSessionActive() || !currentCoroutineContext().isActive) {
                    outcome?.crossOut?.forEach { part -> runCatching { part.patchBitmap.recycle() } }
                    return
                }
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
                    incomingCrossSrcFp = if (outcome.crossOut.isEmpty()) null else currentContentKey
                    val scene = outcome.scene
                    if (scene != null && useDom) {
                        // 覆盖层渲染：场景下发，WebView 里擦原文/画译文。
                        // 场景**总是**先记下来（含「原文」视图下）：切回「译文」时才贴得回去。
                        renderedScenes[image.src] = scene
                        // 同内容去重缓存（2026-10-11「那个坑」）：本轮成功产出场景 → 按内容指纹
                        // 存一份，阅读器重建 <img> 后同一张图直接复用（见 runOnce 前的命中分支）。
                        currentContentKey?.let { key ->
                            scenesByContent[key] = CachedSceneRender(scene = scene, seam = outcome.seam)
                            if (scenesByContent.size > CONTENT_SCENE_CACHE_MAX) {
                                scenesByContent.remove(scenesByContent.keys.first())
                            }
                        }
                        if (renderedScenes.size > MAX_RENDERED_SCENES) {
                            renderedScenes.remove(renderedScenes.keys.first())
                        }
                        // 「原文」视图：只记结果，不上屏。会话照常往下翻译（用户选定的模型），
                        // 切回「译文」时下一轮 needsReShow 会把这一批一起贴回。
                        if (!canPlace()) {
                            renderedSrcs += image.src
                            Log.d(
                                TAG,
                                "scene_cached_hidden bubbles=${scene.bubbles.size} done=$doneCount"
                            )
                        } else {
                            val result = dispatchScene(image.id, scene)
                            val ok = result != null && result.optBoolean("ok")
                            if (ok) {
                                renderedSrcs += image.src
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
                        renderedSrcs += image.src
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
                        // 晚邻接竞态补带（2026-10-14）：上一张先译完 → 跨缝句被画两遍时，
                        // 在 B 侧补一条接缝带（一个覆盖层跨两张图）并摘掉两边的重复块。
                        outcome.scene?.let { placedScene ->
                            tryLateSeamBand(
                                image = image,
                                prevImage = prevImage,
                                scene = placedScene,
                                hasOwnBand = outcome.seam != null
                            )
                        }
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
// 「浏览器与翻译」底部弹层（画布 `10:133`，整屏稿 `10:1`；2026-10-12）
// ---------------------------------------------------------------------------

/** 弹层里能展开下拉的四个位置。用枚举而不是四个布尔量：同一时刻只允许开一个。 */
private enum class BrowserSheetPicker { SOURCE_LANGUAGE, TARGET_LANGUAGE, PROFILE, OCR_ENGINE }

/**
 * 下拉项 / 值行用的短名。
 *
 * 枚举里的 `label` 带「（…）」补充说明（比如 ML Kit 的「（离线，需 Google Play 服务…）」），
 * 那是给首页行内解释用的；弹层里这一行只有一行宽，整段塞进去会被省略成半句，
 * 所以在这里统一砍到第一个「（」之前。百度 / 云端本身没有括号，原文返回。
 */
private fun TranslateEngine.sheetLabel(): String = label.substringBefore("（").trim()

/**
 * 浏览器与翻译设置（画布 `10:133`）—— 网页态右上角齿轮点开的就是它。
 *
 * 顺序与设计稿一致：语言对 → 站点适配（只读）→ 精细擦字 → 覆盖层渲染 →
 * 翻译配置 → 识别引擎。
 *
 * 这里**全是设置**，一条操作都不留：2026-10-12 把最后一条操作「收藏当前页」
 * 挪到了顶栏的收藏星（见 `BrowserPage.WEB` 的顶栏），所以现在不存在
 * 「设置里混着动作」的问题，弹层也短了一行。
 *
 * 为什么改成底部弹层（换掉原来的 `MtDropdownMenu`）：
 * - 这几项本来就是**设置**，下拉菜单从齿轮一路长到屏幕外，一屏根本看不全；
 * - 原菜单还把「打开历史 / 清空历史」这类**操作**塞在「设置」里，语义是拧的；
 * - 语言对放进浏览器：内嵌浏览器是读韩漫的主场景，不该为了换语言退回首页。
 */
@Composable
private fun BrowserSettingsSheet(
    sourceLanguage: AppLanguage,
    targetLanguage: AppLanguage,
    onSetSourceLanguage: (AppLanguage) -> Unit,
    onSetTargetLanguage: (AppLanguage) -> Unit,
    onSwapLanguages: () -> Unit,
    adapter: BrowserSiteAdapter,
    rulesActive: Boolean,
    inpaintClean: Boolean,
    onInpaintCleanChange: (Boolean) -> Unit,
    domOverlay: Boolean,
    onDomOverlayChange: (Boolean) -> Unit,
    webpageTextBusy: Boolean,
    webpageTextApplied: Boolean,
    onTranslatePageText: () -> Unit,
    onRestorePageText: () -> Unit,
    translateProfile: String,
    /**
     * 「翻译配置」下拉的候选，由调用方拼好 —— 弹层自己不知道「云端档案」是什么，
     * 只知道「这行点开有哪些选项、点每个选项做什么」。
     */
    translateProfileOptions: List<Pair<String, () -> Unit>>,
    ocrEngine: OcrEngine,
    onPickOcrEngine: (OcrEngine) -> Unit,
    onDismiss: () -> Unit
) {
    var picker by remember { mutableStateOf<BrowserSheetPicker?>(null) }

    // 站点适配的只读文案，沿用改造前菜单里的口径：
    // 用户不需要懂 DOM，但要能区分「规则没命中」还是「图根本没扫到」。
    val adapterIsGeneric = adapter.id == BrowserSiteAdapter.GENERIC.id
    val adapterSubtitle = when {
        !rulesActive && !adapterIsGeneric -> "${adapter.label} · 这个站的专用规则已停用"
        !rulesActive -> "已停用"
        adapterIsGeneric -> "未识别站点 · 按通用规则找图"
        adapter.imageSelectors.isNotEmpty() -> "${adapter.label} · 已套用 ${adapter.imageSelectors.size} 条取图规则"
        else -> "${adapter.label} · 已套用站点规则"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom
        ) {
            // 弹层以上的空白：点一下关闭（Dialog 自己的遮罩只负责变暗，这里补上「点空白关掉」）。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 24.dp,
                        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
                        clip = false
                    )
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(MtColors.Surface)
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 抓取条区（h4 → 36×4 r2）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MtColors.Border)
                    )
                }

                // 标题行：17 Bold + 15 品牌色「完成」
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "浏览器与翻译",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MtColors.TextPrimary
                    )
                    Text(
                        text = "完成",
                        fontSize = 15.sp,
                        color = MtColors.Brand,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // 语言对：两个 Filled 框（128×44）+ 中间 40 圆底交换键，整组居中（画布 `23:35`）
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.width(320.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "源语言", fontSize = 13.sp, color = MtColors.TextSecondary)
                        Text(text = "目标语言", fontSize = 13.sp, color = MtColors.TextSecondary)
                    }
                    Row(
                        modifier = Modifier.width(320.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BrowserSheetLanguageBox(
                            label = sourceLanguage.label,
                            expanded = picker == BrowserSheetPicker.SOURCE_LANGUAGE,
                            onToggle = {
                                picker = if (picker == BrowserSheetPicker.SOURCE_LANGUAGE) {
                                    null
                                } else {
                                    BrowserSheetPicker.SOURCE_LANGUAGE
                                }
                            },
                            onDismiss = { picker = null },
                            onPick = {
                                onSetSourceLanguage(it)
                                picker = null
                            }
                        )
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MtColors.BrandTint)
                                .clickable(onClick = onSwapLanguages)
                                .semantics { contentDescription = "交换源语言与目标语言" },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = MtSwapIcon,
                                contentDescription = null,
                                tint = MtColors.BrandInk,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        BrowserSheetLanguageBox(
                            label = targetLanguage.label,
                            expanded = picker == BrowserSheetPicker.TARGET_LANGUAGE,
                            onToggle = {
                                picker = if (picker == BrowserSheetPicker.TARGET_LANGUAGE) {
                                    null
                                } else {
                                    BrowserSheetPicker.TARGET_LANGUAGE
                                }
                            },
                            onDismiss = { picker = null },
                            onPick = {
                                onSetTargetLanguage(it)
                                picker = null
                            }
                        )
                    }
                }

                // 网页文字翻译（2026-10-14 定稿为 Tonal 按钮）：只翻 DOM 文本节点，与漫画图链路互不干扰。
                // 放在设置分隔线之上（画布 32:55）——它是一个动作，不是设置项，不该和开关挤在一起。
                MtTonalButton(
                    text = when {
                        webpageTextBusy -> "翻译中…"
                        webpageTextApplied -> "恢复页面原文"
                        else -> "网页文字翻译"
                    },
                    onClick = if (webpageTextApplied) onRestorePageText else onTranslatePageText,
                    enabled = !webpageTextBusy,
                    modifier = Modifier.fillMaxWidth(),
                    height = 44.dp,
                    fontSize = 15
                )

                HorizontalDivider(color = MtColors.Border)

                // 站点适配：只读 + 绿对勾。**不给开关**（2026-10-12 用户定：默认开启，用户不自己关）。
                BrowserSheetStatusRow(title = "站点适配", subtitle = adapterSubtitle)

                BrowserSheetSwitchRow(
                    title = "精细擦字",
                    // 开态副标题 = 用户 2026-10-14 定稿原文（逐字用，不润色）。
                    subtitle = if (inpaintClean) {
                        "可点击翻译直接切换原图。"
                    } else {
                        "只求快：纯色平涂 + 实底遮挡"
                    },
                    checked = inpaintClean,
                    onCheckedChange = onInpaintCleanChange
                )

                BrowserSheetSwitchRow(
                    title = "覆盖层渲染",
                    subtitle = if (domOverlay) {
                        "译文画在网页上，滚动更顺滑（推荐）"
                    } else {
                        "整张图替换显示（兼容模式）"
                    },
                    checked = domOverlay,
                    onCheckedChange = onDomOverlayChange
                )

                HorizontalDivider(color = MtColors.Border)

                BrowserSheetValueRow(
                    label = "翻译配置",
                    value = translateProfile,
                    expanded = picker == BrowserSheetPicker.PROFILE,
                    onToggle = {
                        // 候选里永远至少有百度 / ML Kit 两项，所以不需要「空菜单不给开」的守卫。
                        picker = if (picker == BrowserSheetPicker.PROFILE) {
                            null
                        } else {
                            BrowserSheetPicker.PROFILE
                        }
                    },
                    onDismiss = { picker = null },
                    options = translateProfileOptions
                )

                HorizontalDivider(color = MtColors.Border)

                BrowserSheetValueRow(
                    label = "识别引擎",
                    value = ocrEngine.label,
                    expanded = picker == BrowserSheetPicker.OCR_ENGINE,
                    onToggle = {
                        picker = if (picker == BrowserSheetPicker.OCR_ENGINE) {
                            null
                        } else {
                            BrowserSheetPicker.OCR_ENGINE
                        }
                    },
                    onDismiss = { picker = null },
                    options = OcrEngine.entries.map { engine ->
                        engine.label to {
                            onPickOcrEngine(engine)
                            picker = null
                        }
                    }
                )

                HorizontalDivider(color = MtColors.Border)

                // 弹层手势条区（h23 → 108×4）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(23.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 108.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MtColors.Illustration)
                    )
                }
            }
        }
    }
}

/** 弹层每行左侧的「标题 + 副标题」（15 主色 / 12 弱色，行距 2）。 */
@Composable
private fun BrowserSheetTextGroup(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, fontSize = 15.sp, color = MtColors.TextPrimary, maxLines = 1)
        Text(text = subtitle, fontSize = 12.sp, color = MtColors.TextTertiary, maxLines = 1)
    }
}

/** 只读状态行（站点适配）：标题 + 副标题 + 右侧绿色对勾，行高 46。 */
@Composable
private fun BrowserSheetStatusRow(title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        BrowserSheetTextGroup(title = title, subtitle = subtitle)
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(MtColors.Success),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MtColors.Surface,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/** 开关行：标题 + 副标题 + 右侧开关（[MtSwitch]，品牌蓝轨），行高 46。 */
@Composable
private fun BrowserSheetSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        BrowserSheetTextGroup(title = title, subtitle = subtitle)
        MtSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = title }
        )
    }
}

/**
 * 值行：标签 13 次级色 + 值 14 主色 + chevron，点开一个下拉（画布 `11:376` / `11:383`）。
 *
 * [options] 是「显示文字 to 点它做什么」，用 [Pair] 而不是两个平行 List —— 长度错位过一次就懂了。
 */
@Composable
private fun BrowserSheetValueRow(
    label: String,
    value: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    options: List<Pair<String, () -> Unit>>
) {
    var anchorWidth by remember { mutableStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { anchorWidth = it.size.width }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, fontSize = 13.sp, color = MtColors.TextSecondary, maxLines = 1)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = value,
                    fontSize = 14.sp,
                    color = MtColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = MtChevronRightIcon,
                    contentDescription = null,
                    tint = MtColors.TextTertiary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        MtDropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            anchorWidthPx = anchorWidth
        ) {
            options.forEach { (text, action) ->
                MtDropdownItem(
                    text = text,
                    onClick = {
                        // 先收菜单再执行：换引擎 / 换档案会把已渲染的结果作废重画，
                        // 菜单还挂在那儿会显得「点了没反应」（其实反应在菜单底下）。
                        onDismiss()
                        action()
                    }
                )
            }
        }
    }
}

/** 语言框：128×44 / r12 / Filled 底 / 右侧 chevron（画布 `11:360` / `11:371`）。 */
@Composable
private fun BrowserSheetLanguageBox(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (AppLanguage) -> Unit
) {
    var anchorWidth by remember { mutableStateOf(0) }
    Box(
        modifier = Modifier
            .width(128.dp)
            .onGloballyPositioned { anchorWidth = it.size.width }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MtColors.SurfaceMuted)
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 15.sp,
                color = MtColors.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            Icon(
                imageVector = MtChevronDownIcon,
                contentDescription = null,
                tint = MtColors.TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
        MtDropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            anchorWidthPx = anchorWidth
        ) {
            AppLanguage.entries.forEach { language ->
                MtDropdownItem(text = language.label) {
                    // 同值行：先收菜单，再改语言。
                    onDismiss()
                    onPick(language)
                }
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
 * 内嵌浏览器翻译悬浮球（2026-10-12 用户要求「翻译按钮变成悬浮球，好按一点」）。
 *
 * 手势表（与常见「小白点」一致，无需学习成本）：
 * - **单击球** = 三态动作（由 [BrowserTranslateUiPolicy.resolve] 判定，文案与图标跟着变）：
 *   开始翻译（蓝底「译」）/ 停止翻译（红底白方块）/ **恢复原文**（蓝底 ✕，截图覆盖层在屏时，
 *   对齐首页那条链路的交互）；
 * - **单击球上的 ⋮ 胶囊** = 展开 / 收起菜单；
 * - **长按球** = 展开菜单（老手势保留）；
 * - **拖动球** = 移动位置，松手吸附到左/右边缘（纵向保持拖到的高度）。
 *
 * 收起的三种方式：点 ⋮ 胶囊 / 点球 / 点菜单外的空白（透明 scrim）。
 *
 * 三个用户诉求的落点：
 * 1. 翻译与停止 → 单击悬浮球；
 * 2. 原文/译文切换 → ⋮ 菜单（与 [BrowserTranslateUiPolicy] 同一份状态，两处永远一致）；
 * 3. 截图翻译快捷方式 → ⋮ 菜单：只截 WebView 自己的视口（`webView.draw`），
 *    免 MediaProjection / 悬浮窗授权（[onScreenshotTranslate]，2026-10-12 由首页授权链路改来）。
 *
 * 2026-10-16 重设计：删掉 `reShowBusy` 一个参数的"半禁用"写法，改成明确的
 * `enabled` / `busy` / `action` 三件套 —— 截图翻译与一次性贴回期间球呈忙碌态且不响应，
 * 用户点它时会看到状态行给出原因，而不是静默无事发生。
 *
 * 几何规则（夹紧 / 吸附 / 菜单朝向）全部在 [BrowserFloatingBallPolicy]（纯函数 + 单测），
 * 本组件只负责手势与绘制。菜单展开时有一层透明 scrim：点空白收起，也防止误触网页。
 */
@Composable
private fun BrowserTranslateFloatingBall(
    running: Boolean,
    busy: Boolean,
    enabled: Boolean,
    action: BrowserPrimaryAction?,
    pageUrl: String,
    viewMode: BrowserViewMode,
    modeToggleEnabled: Boolean,
    modeToggleDisabledReason: String?,
    onPrimaryTap: () -> Unit,
    onSelectViewMode: (BrowserViewMode) -> Unit,
    onScreenshotTranslate: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val containerSize = remember { mutableStateOf(IntSize.Zero) }
    // 拖动中的原始位置；null = 还没初始化（等容器量出尺寸后按策略给默认点）。
    var rawOffset by remember { mutableStateOf<Offset?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    // ⚠️ `pointerInput(Unit)` 里的手势 lambda **只在首次组合时创建**：直接读 `running` /
    // `busy` / `onPrimaryTap` 会永远停在那一刻的值（实测：点「停止」时日志里 running 仍是
    // false）。用 rememberUpdatedState 让回调读到最新状态 —— 这是「点不动」类问题的排查线索之一。
    val latestRunning by rememberUpdatedState(running)
    val latestBusy by rememberUpdatedState(busy)
    val latestAction by rememberUpdatedState(action)
    val latestExpanded by rememberUpdatedState(expanded)
    val latestPrimaryTap by rememberUpdatedState(onPrimaryTap)
    // 页面跳转后菜单里的「原文/译文」已与新页无关（onPageStarted 也会停会话）：收起。
    LaunchedEffect(pageUrl) { expanded = false }

    val ballSizePx = with(density) { BrowserFloatingBallPolicy.SIZE_DP.dp.toPx() }
    val dockMarginPx = with(density) { BrowserFloatingBallPolicy.DOCK_MARGIN_DP.dp.toPx() }
    val bottomMarginPx =
        with(density) { BrowserFloatingBallPolicy.DEFAULT_BOTTOM_MARGIN_DP.dp.toPx() }
    val menuGapPx = with(density) { BrowserFloatingBallPolicy.MENU_GAP_DP.dp.toPx() }
    val menuWidth = BrowserFloatingBallPolicy.MENU_WIDTH_DP.dp
    val menuWidthPx = with(density) { menuWidth.toPx() }
    val menuHeightPx = with(density) { BrowserFloatingBallPolicy.MENU_HEIGHT_DP.dp.toPx() }
    val capsuleWidth = BrowserFloatingBallPolicy.CAPSULE_WIDTH_DP.dp
    val capsuleHeight = BrowserFloatingBallPolicy.CAPSULE_HEIGHT_DP.dp
    val capsuleWidthPx = with(density) { capsuleWidth.toPx() }
    val capsuleHeightPx = with(density) { capsuleHeight.toPx() }
    val capsuleGapPx = with(density) { BrowserFloatingBallPolicy.CAPSULE_GAP_DP.dp.toPx() }

    Box(modifier = Modifier.fillMaxSize().onSizeChanged { containerSize.value = it }) {
        // 首次布局：给一个默认停靠点（右边缘、30% 高度）。之后位置完全由用户拖动决定。
        LaunchedEffect(containerSize.value) {
            if (rawOffset == null && containerSize.value.width > 0 && containerSize.value.height > 0) {
                rawOffset = BrowserFloatingBallPolicy.defaultPosition(
                    containerSize.value.width.toFloat(),
                    containerSize.value.height.toFloat(),
                    ballSizePx,
                    dockMarginPx,
                    bottomMarginPx
                )
            }
        }
        val raw = rawOffset ?: return@Box
        val clamped = BrowserFloatingBallPolicy.clamp(
            raw,
            ballSizePx,
            containerSize.value.width.toFloat(),
            containerSize.value.height.toFloat()
        )
        val dockedRight = BrowserFloatingBallPolicy.isDockedRight(
            clamped.x, ballSizePx, containerSize.value.width.toFloat()
        )
        // 拖动时跟手（snap），松手后从当前位置滑到吸附点（tween）。
        val ballX by animateFloatAsState(
            targetValue = if (dragging) {
                clamped.x
            } else {
                BrowserFloatingBallPolicy.dockX(
                    clamped.x, ballSizePx, containerSize.value.width.toFloat(), dockMarginPx
                )
            },
            animationSpec = if (dragging) snap() else tween(durationMillis = 160),
            label = "browserFloatingBallX"
        )
        val ballY = clamped.y
        val menuLeft = BrowserFloatingBallPolicy.menuLeft(
            ballX, ballSizePx, menuWidthPx, containerSize.value.width.toFloat(),
            dockedRight, menuGapPx
        )
        val menuTop = BrowserFloatingBallPolicy.menuTop(
            ballY, ballSizePx, menuHeightPx, containerSize.value.height.toFloat()
        )
        // ⋮ 胶囊：贴球上方（顶边放不下时自动翻到球下方），与球同心。
        val capsuleLeft = BrowserFloatingBallPolicy.capsuleLeft(ballX, ballSizePx, capsuleWidthPx)
        val capsuleTop = BrowserFloatingBallPolicy.capsuleTop(
            ballY, ballSizePx, capsuleHeightPx, capsuleGapPx
        )

        // 展开时的透明 scrim：点空白收起；同时挡住对网页的误触（菜单开着时页面不该响应）。
        if (expanded) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { detectTapGestures { expanded = false } }
            )
        }

        // 展开菜单（白卡，画布 `23:265`）：球在右半 → 菜单朝左，球在左半 → 朝右；
        // 纵向由 [BrowserFloatingBallPolicy.expandsUpward] 决定往上/往下长。
        //
        // 2026-10-12 用户反馈旧菜单 176dp 宽「非常遮挡原图」，瘦到 120dp 并去掉「设置」
        // （设置常驻顶栏齿轮）与两条分隔线；内容只留两项：截图翻译 + 原文｜译文。
        if (expanded) {
            Column(
                modifier = Modifier
                    .offset { IntOffset(menuLeft.roundToInt(), menuTop.roundToInt()) }
                    .width(menuWidth)
                    .shadow(6.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(MtColors.Surface)
                    .border(1.dp, MtColors.Border, RoundedCornerShape(14.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            expanded = false
                            Log.d(TAG, "capture_shortcut_from_ball")
                            onScreenshotTranslate()
                        }
                        .padding(horizontal = 8.dp)
                        .semantics { contentDescription = "截图翻译（悬浮球菜单）" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = MtScanIcon,
                        contentDescription = null,
                        tint = MtColors.TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "截图翻译",
                        fontSize = 14.sp,
                        color = MtColors.TextPrimary,
                        maxLines = 1
                    )
                }
                // 与底栏同一个开关组件（同一份状态、同一套禁用规则）；
                // 没有译文可切时整块置灰，点它 toast 说明原因。
                Box(
                    modifier = Modifier.clickable(enabled = !modeToggleEnabled) {
                        Toast.makeText(
                            context,
                            modeToggleDisabledReason ?: "暂时不可用",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    BrowserViewModeToggle(
                        mode = viewMode,
                        enabled = modeToggleEnabled,
                        disabledReason = modeToggleDisabledReason,
                        onSelect = onSelectViewMode
                    )
                }
            }
        }

        // ⋮ 胶囊（画布 `23:167`）：菜单唯一的展开入口，点它开/关。
        // 收起态 = 白底 + 灰描边，展开态 = 浅蓝底 + 蓝描边 + 蓝点（与球形成一组）。
        Box(
            modifier = Modifier
                .offset { IntOffset(capsuleLeft.roundToInt(), capsuleTop.roundToInt()) }
                .size(capsuleWidth, capsuleHeight)
                .shadow(4.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(
                    // 轻微透底：胶囊也只该是一条轻提示，不该像一张白贴片压在画面上。
                    if (expanded) MtColors.BrandTint else MtColors.Surface.copy(alpha = 0.96f)
                )
                .border(
                    width = 1.dp,
                    color = if (expanded) MtColors.BrandInk else MtColors.Border,
                    shape = RoundedCornerShape(18.dp)
                )
                .clickable {
                    expanded = !expanded
                    Log.d(TAG, "ball_capsule_tap expanded=$expanded")
                }
                .semantics {
                    contentDescription = if (expanded) "收起翻译菜单" else "打开翻译菜单"
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = null,
                tint = if (expanded) MtColors.BrandInk else MtColors.TextPrimary,
                modifier = Modifier.size(16.dp)
            )
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(ballX.roundToInt(), ballY.roundToInt()) }
                .size(BrowserFloatingBallPolicy.SIZE_DP.dp)
                .alpha(if (enabled) 1f else 0.65f)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    // 轻微透底：悬浮球不该让用户感觉「挡了一大块画面」，
                    // 但也不能太透以至于和页面内容混在一起。
                    // 红色只给「停止」（会话在跑）；忙碌时压暗，表示"这一下不会被采纳"。
                    (if (action == BrowserPrimaryAction.STOP) MtColors.Danger else MtColors.Brand)
                        .copy(alpha = if (busy) 0.55f else 0.92f)
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (latestExpanded) {
                                expanded = false
                            } else {
                                // 忙碌（截图翻译/一次性贴回）时也照常上报：宿主会把原因写进状态行。
                                // 以前这里是**静默忽略**，用户「点不动」的排查连一行日志都没有
                                // （2026-10-14 的教训），这条已留成硬约束。
                                Log.d(
                                    TAG,
                                    "ball_primary_tap running=$latestRunning busy=$latestBusy " +
                                        "action=${latestAction ?: "none"}"
                                )
                                latestPrimaryTap()
                            }
                        },
                        onLongPress = {
                            Log.d(TAG, "ball_menu_open dockedRight=$dockedRight")
                            expanded = true
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false }
                    ) { change, dragAmount ->
                        change.consume()
                        rawOffset = (rawOffset ?: Offset.Zero) + dragAmount
                    }
                }
                .semantics {
                    // 三态各有自己的说法：无障碍（与 .verify 的 dump 断言）靠它区分球此刻干什么。
                    contentDescription = when {
                        busy -> "翻译悬浮球：正在截图翻译，稍后可用"
                        action == BrowserPrimaryAction.RESTORE ->
                            "翻译悬浮球：点按恢复原文（截图译文显示中），长按打开菜单"

                        action == BrowserPrimaryAction.STOP ->
                            "翻译悬浮球：点按停止翻译，长按打开菜单"

                        else -> "翻译悬浮球：点按开始翻译，长按打开菜单"
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
                // 运行中 = 红底白方块（停止）；material-icons-core 没有 Stop 图标，
                // 画一个 14dp 方块比硬凑语义相近的图标更直白（与原底栏按钮的决定一致）。
                action == BrowserPrimaryAction.STOP -> Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White)
                )
                // 截图覆盖层在屏 = 蓝底白 ✕（点它就收起，恢复原文）——首页那条链路同款语义。
                action == BrowserPrimaryAction.RESTORE -> Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )

                else -> Text(
                    text = "译",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
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
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MtColors.SurfaceMuted)
            .padding(3.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics { contentDescription = desc },
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrowserViewModeSegment(
            label = "原文",
            target = BrowserViewMode.ORIGINAL,
            mode = mode,
            enabled = enabled,
            onSelect = onSelect,
            modifier = Modifier.weight(1f)
        )
        BrowserViewModeSegment(
            label = "译文",
            target = BrowserViewMode.TRANSLATED,
            mode = mode,
            enabled = enabled,
            onSelect = onSelect,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 分段开关的一段；选中段用品牌色浅底 + 蓝字（与菜单里的选中态同一套视觉语言）。 */
@Composable
private fun BrowserViewModeSegment(
    label: String,
    target: BrowserViewMode,
    mode: BrowserViewMode,
    enabled: Boolean,
    onSelect: (BrowserViewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedNow = mode == target
    Box(
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selectedNow) MtColors.BrandTint else Color.Transparent)
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
            fontSize = 13.sp,
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

/**
 * 图片内容指纹（2026-10-11「那个坑」）：阅读器把已翻译的 `<img>` 换成新 blob URL 重建元素，
 * 扫描器看到的是「新图」，实际像素相同。缩到 16 列网格取 MD5 —— 同一张图重编码/重解码后
 * 指纹不变；宽高参与指纹（不同尺寸不算同图）。不同面板的碰撞概率可忽略。
 */
private fun contentFingerprint(bitmap: Bitmap): String {
    if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return ""
    val cols = 16
    val rows = ((bitmap.height.toLong() * cols / bitmap.width.toLong()).toInt()).coerceIn(8, 64)
    val scaled = Bitmap.createScaledBitmap(bitmap, cols, rows, true)
    val pixels = IntArray(cols * rows)
    scaled.getPixels(pixels, 0, cols, 0, 0, cols, rows)
    if (scaled !== bitmap) scaled.recycle()
    return try {
        val digest = java.security.MessageDigest.getInstance("MD5")
        digest.update("${bitmap.width}x${bitmap.height}".toByteArray(Charsets.US_ASCII))
        val buffer = ByteArray(3)
        for (pixel in pixels) {
            buffer[0] = ((pixel shr 16) and 0xFF).toByte()
            buffer[1] = ((pixel shr 8) and 0xFF).toByte()
            buffer[2] = (pixel and 0xFF).toByte()
            digest.update(buffer)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (err: Throwable) {
        Log.w(TAG, "content_fingerprint failed: ${err.message}")
        ""
    }
}

private const val RETRY_STRIP_MAX_PX = 768




