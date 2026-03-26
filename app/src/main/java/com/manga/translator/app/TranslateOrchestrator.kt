package com.manga.translator.app

import android.graphics.RectF
import android.util.Base64
import android.util.Log
import com.manga.translator.capture.ScreenCaptureController
import com.manga.translator.overlay.BubbleAdjustment
import com.manga.translator.overlay.EraseStrategyPolicy
import com.manga.translator.overlay.FloatingState
import com.manga.translator.overlay.OverlayController
import com.manga.translator.overlay.OverlayRenderConfig
import com.manga.translator.overlay.WritableAreaRefiner
import com.manga.translator.store.PageOverlayAdjustment
import com.manga.translator.translate.Bubble
import com.manga.translator.translate.TranslationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

class TranslateOrchestrator(
    private val captureController: ScreenCaptureController,
    private val overlayController: OverlayController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val writableAreaRefiner = WritableAreaRefiner()

    suspend fun runOnce(onStageChanged: ((FloatingState) -> Unit)? = null): RunOnceOutcome {
        val timer = TranslationTimer()
        timer.mark("start")

        val settings = ServiceLocator.settingsRepository.getSettings()
        if (!settings.enabled) {
            overlayController.clearTranslationLayer()
            timer.mark("render_done", "skipped=disabled")
            return RunOnceOutcome(hasOverlay = false, baselineHash = null, warningMessage = null)
        }
        if (settings.ocrBaseUrl.isBlank() || settings.ocrApiKey.isBlank()) {
            overlayController.clearTranslationLayer()
            timer.mark("render_done", "skipped=missing_ocr_api")
            throw IOException("请先配置 OCR API")
        }

        val bitmap = try {
            captureController.captureBitmapWithRecovery().also {
                timer.mark("screenshot_done", "w=${it.width},h=${it.height}")
            }
        } catch (err: Throwable) {
            timer.mark("capture_failure_type", err.javaClass.simpleName)
            timer.mark("render_done", "skipped=capture_failed_before_request")
            throw err
        }
        overlayController.setRenderViewport(bitmap.width, bitmap.height)
        val pageHash = withContext(ioDispatcher) { ServiceLocator.eraseProcessor.buildPageHash(bitmap) }
        val baselineHash = try {
            FrameHash.centerDHash(bitmap)
        } catch (_: Throwable) {
            null
        }

        val dataUrl = captureController.toJpegDataUrl(bitmap = bitmap, quality = 75, maxWidth = 1024)
        val ocrImageWidth = minOf(bitmap.width, 1024)
        val ocrImageHeight = if (bitmap.width > 1024) {
            ((ocrImageWidth.toFloat() / bitmap.width.toFloat()) * bitmap.height.toFloat()).toInt().coerceAtLeast(1)
        } else {
            bitmap.height
        }
        timer.mark("compress_done", "payload_kb=${(dataUrl.length * 3L / 4L) / 1024L}")

        onStageChanged?.invoke(FloatingState.TRANSLATING)

        val cacheKey = buildCacheKey(dataUrl = dataUrl, settings = settings)
        val cached = ServiceLocator.cacheRepository.get(cacheKey)
        if (!cached.isNullOrBlank()) {
            val parsed = parseCachedResult(cached)
            timer.mark("api_response", "cache_hit=true")
            return renderAndBuildOutcome(parsed, baselineHash, timer, bitmap, pageHash, warningMessage = null)
        }

        timer.mark("request_send", "stage=ocr")
        val ocrResult = try {
            withContext(ioDispatcher) {
                ServiceLocator.visionClient.detectBubbles(
                    provider = settings.ocrProvider,
                    baseUrl = settings.ocrBaseUrl,
                    model = settings.ocrModel,
                    apiKey = settings.ocrApiKey,
                    dataUrl = dataUrl,
                    imageWidth = ocrImageWidth,
                    imageHeight = ocrImageHeight,
                    ignoreSimplifiedChinese = settings.ignoreSimplifiedChinese,
                    enableThinking = settings.enableThinking,
                    thinkingBudget = settings.thinkingBudget
                )
            }.also { timer.mark("response_ok", "stage=ocr bubbles=${it.bubbles.size}") }
        } catch (err: Throwable) {
            timer.mark("response_fail", "stage=ocr reason=${err.message?.take(80)}")
            throw err
        }
        timer.mark("ocr_response", "bubbles=${ocrResult.bubbles.size}")

        if (ocrResult.bubbles.isEmpty()) {
            bitmap.recycle()
            overlayController.clearTranslationLayer()
            timer.mark("render_done", "bubbles=0")
            return RunOnceOutcome(hasOverlay = false, baselineHash = null, warningMessage = null)
        }

        var warningMessage: String? = null
        val mergedResult = if (settings.translateBaseUrl.isBlank() || settings.translateApiKey.isBlank()) {
            warningMessage = "仅OCR（未配置翻译API）"
            timer.mark("translate_response", "skipped=missing_translate_api")
            TranslationResult(
                ocrResult.bubbles.map { bubble ->
                    bubble.copy(translatedText = bubble.originalText)
                }
            )
        } else {
            runCatching {
                timer.mark("request_send", "stage=translate")
                val translated = withContext(ioDispatcher) {
                    ServiceLocator.visionClient.translateTexts(
                        provider = settings.translateProvider,
                        baseUrl = settings.translateBaseUrl,
                        model = settings.translateModel,
                        apiKey = settings.translateApiKey,
                        texts = ocrResult.bubbles.map { it.originalText },
                        enableThinking = settings.enableThinking,
                        thinkingBudget = settings.thinkingBudget
                    )
                }
                timer.mark("response_ok", "stage=translate count=${translated.size}")
                timer.mark("translate_response", "count=${translated.size}")
                TranslationResult(
                    ocrResult.bubbles.mapIndexed { index, bubble ->
                        bubble.copy(translatedText = translated.getOrElse(index) { bubble.originalText }.ifBlank { bubble.originalText })
                    }
                )
            }.getOrElse { err ->
                timer.mark("response_fail", "stage=translate reason=${err.message?.take(80)}")
                warningMessage = "翻译失败，已显示原文"
                timer.mark("translate_response", "failed=${err.message?.take(80)}")
                TranslationResult(
                    ocrResult.bubbles.map { bubble ->
                        bubble.copy(translatedText = bubble.originalText)
                    }
                )
            }
        }

        ServiceLocator.cacheRepository.put(cacheKey, serializeResult(mergedResult))
        return renderAndBuildOutcome(mergedResult, baselineHash, timer, bitmap, pageHash, warningMessage)
    }

    private suspend fun renderAndBuildOutcome(
        result: TranslationResult,
        baselineHash: Long?,
        timer: TranslationTimer,
        screenshotBitmap: android.graphics.Bitmap,
        pageHash: String,
        warningMessage: String?
    ): RunOnceOutcome {
        if (result.bubbles.isEmpty()) {
            screenshotBitmap.recycle()
            overlayController.clearTranslationLayer()
            timer.mark("render_done", "bubbles=0")
            return RunOnceOutcome(hasOverlay = false, baselineHash = null, warningMessage = warningMessage)
        }

        val denseMode = totalEffectiveChars(result.bubbles) >= DENSE_CHAR_THRESHOLD
        val sourcePatches = withContext(ioDispatcher) {
            ServiceLocator.eraseProcessor.erasePage(
                source = screenshotBitmap,
                bubbles = result.bubbles,
                pageHash = pageHash,
                policy = EraseStrategyPolicy.AUTO_TIERED
            ) { tag, extra -> timer.mark(tag, extra) }
        }
        val (viewportWidth, viewportHeight) = overlayController.getRenderViewport()
        val bubbleRenderRects = result.bubbles.mapIndexed { index, bubble ->
            val srcRect = bubbleToSourceRect(bubble, screenshotBitmap.width, screenshotBitmap.height)
            val renderRect = mapSourceToRenderRect(
                srcRect = srcRect,
                sourceWidth = screenshotBitmap.width,
                sourceHeight = screenshotBitmap.height,
                renderWidth = viewportWidth,
                renderHeight = viewportHeight
            )
            Log.d(
                "RenderDebug",
                "bubble=$index srcRect=$srcRect renderRect=$renderRect " +
                    "captureSize=${screenshotBitmap.width}x${screenshotBitmap.height} " +
                    "overlaySize=${viewportWidth}x${viewportHeight}"
            )
            Log.d("RenderDebug", "bgType=${bubble.bgType}")
            index to renderRect
        }.toMap()
        val bubbleContentRects = result.bubbles.mapIndexed { index, bubble ->
            val srcRect = bubbleToSourceRect(bubble, screenshotBitmap.width, screenshotBitmap.height)
            val refinedSourceRect = writableAreaRefiner.refine(screenshotBitmap, srcRect, bubble.bgType)
            val refinedRenderRect = mapSourceToRenderRect(
                srcRect = refinedSourceRect,
                sourceWidth = screenshotBitmap.width,
                sourceHeight = screenshotBitmap.height,
                renderWidth = viewportWidth,
                renderHeight = viewportHeight
            )
            Log.d(
                "RenderDebug",
                "content_rect_refined bubble=$index bgType=${bubble.bgType} raw=$srcRect refined=$refinedSourceRect render=$refinedRenderRect"
            )
            index to refinedRenderRect
        }.toMap()
        val erasePatches = sourcePatches.map { patch ->
            patch.copy(
                renderRect = mapSourceToRenderRect(
                    srcRect = patch.srcRect,
                    sourceWidth = screenshotBitmap.width,
                    sourceHeight = screenshotBitmap.height,
                    renderWidth = viewportWidth,
                    renderHeight = viewportHeight
                )
            )
        }
        timer.mark("erase_patch_count", "count=${erasePatches.size}")
        if (!screenshotBitmap.isRecycled) {
            screenshotBitmap.recycle()
        }

        overlayController.renderBubbles(
            bubbles = result.bubbles,
            renderConfig = OverlayRenderConfig(denseMode = denseMode, erasePolicy = EraseStrategyPolicy.AUTO_TIERED),
            erasePatches = erasePatches,
            bubbleRenderRects = bubbleRenderRects,
            bubbleContentRects = bubbleContentRects,
            pageHash = pageHash,
            loadAdjustment = { p, key ->
                ServiceLocator.pageOverlayAdjustmentRepository.get(p, key)
                    ?.let { BubbleAdjustment(it.offsetX, it.offsetY, it.fontScale, it.strokeScale) }
            },
            saveAdjustment = { p, key, adjustment ->
                ServiceLocator.pageOverlayAdjustmentRepository.save(
                    p,
                    key,
                    PageOverlayAdjustment(
                        offsetX = adjustment.offsetX,
                        offsetY = adjustment.offsetY,
                        fontScale = adjustment.fontScale,
                        strokeScale = adjustment.strokeScale
                    )
                )
            },
            onPerf = { tag, extra -> timer.mark(tag, extra) }
        )
        timer.mark("render_done", "bubbles=${result.bubbles.size},dense=$denseMode")
        return RunOnceOutcome(hasOverlay = true, baselineHash = baselineHash, warningMessage = warningMessage)
    }

    private fun mapSourceToRenderRect(
        srcRect: RectF,
        sourceWidth: Int,
        sourceHeight: Int,
        renderWidth: Int,
        renderHeight: Int
    ): RectF {
        if (sourceWidth <= 0 || sourceHeight <= 0 || renderWidth <= 0 || renderHeight <= 0) {
            return RectF(srcRect)
        }
        val scaleX = renderWidth.toFloat() / sourceWidth.toFloat()
        val scaleY = renderHeight.toFloat() / sourceHeight.toFloat()
        val renderWidthF = renderWidth.toFloat()
        val renderHeightF = renderHeight.toFloat()

        val left = (srcRect.left * scaleX).coerceIn(0f, renderWidthF)
        val top = (srcRect.top * scaleY).coerceIn(0f, renderHeightF)
        val rightRaw = (srcRect.right * scaleX).coerceIn(0f, renderWidthF)
        val bottomRaw = (srcRect.bottom * scaleY).coerceIn(0f, renderHeightF)
        val right = rightRaw.coerceAtLeast((left + 1f).coerceAtMost(renderWidthF))
        val bottom = bottomRaw.coerceAtLeast((top + 1f).coerceAtMost(renderHeightF))
        return RectF(left, top, right, bottom)
    }

    private fun bubbleToSourceRect(
        bubble: Bubble,
        sourceWidth: Int,
        sourceHeight: Int
    ): RectF {
        val left = ((bubble.x / 100f) * sourceWidth.toFloat()).coerceIn(0f, sourceWidth.toFloat())
        val top = ((bubble.y / 100f) * sourceHeight.toFloat()).coerceIn(0f, sourceHeight.toFloat())
        val right = (((bubble.x + bubble.w) / 100f) * sourceWidth.toFloat()).coerceIn(0f, sourceWidth.toFloat())
        val bottom = (((bubble.y + bubble.h) / 100f) * sourceHeight.toFloat()).coerceIn(0f, sourceHeight.toFloat())
        val safeRight = right.coerceAtLeast((left + 1f).coerceAtMost(sourceWidth.toFloat()))
        val safeBottom = bottom.coerceAtLeast((top + 1f).coerceAtMost(sourceHeight.toFloat()))
        return RectF(left, top, safeRight, safeBottom)
    }

    private fun totalEffectiveChars(bubbles: List<Bubble>): Int {
        return bubbles.sumOf { bubble ->
            val source = bubble.translatedText.ifBlank { bubble.originalText }
            source.filterNot(Char::isWhitespace).length
        }
    }

    private fun buildCacheKey(dataUrl: String, settings: com.manga.translator.store.AppSettings): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val source = listOf(
            settings.ocrProvider,
            settings.ocrBaseUrl,
            settings.ocrModel,
            settings.translateProvider,
            settings.translateBaseUrl,
            settings.translateModel,
            settings.enableThinking.toString(),
            settings.thinkingBudget.toString(),
            dataUrl.take(1024),
            dataUrl.length.toString()
        ).joinToString("|")
        val bytes = digest.digest(source.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun serializeResult(result: TranslationResult): String {
        val array = JSONArray()
        result.bubbles.forEach { bubble ->
            array.put(
                JSONObject()
                    .put("x", bubble.x)
                    .put("y", bubble.y)
                    .put("w", bubble.w)
                    .put("h", bubble.h)
                    .put("bgType", bubble.bgType)
                    .put("originalText", bubble.originalText)
                    .put("translatedText", bubble.translatedText)
                    .put("layout", bubble.layout)
            )
        }
        return JSONObject().put("bubbles", array).toString()
    }

    private fun parseCachedResult(raw: String): TranslationResult {
        if (raw.isBlank()) return TranslationResult(emptyList())
        return runCatching {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("bubbles") ?: JSONArray()
            val bubbles = mutableListOf<Bubble>()
            for (index in 0 until arr.length()) {
                val item = arr.optJSONObject(index) ?: continue
                bubbles += Bubble(
                    x = item.optDouble("x", 0.0).toFloat(),
                    y = item.optDouble("y", 0.0).toFloat(),
                    w = item.optDouble("w", 0.0).toFloat(),
                    h = item.optDouble("h", 0.0).toFloat(),
                    bgType = item.optString("bgType", "solid"),
                    originalText = item.optString("originalText", ""),
                    translatedText = item.optString("translatedText", ""),
                    layout = item.optString("layout", "horizontal")
                )
            }
            TranslationResult(bubbles)
        }.getOrElse { TranslationResult(emptyList()) }
    }

    companion object {
        private const val DENSE_CHAR_THRESHOLD = 120
    }
}

data class RunOnceOutcome(
    val hasOverlay: Boolean,
    val baselineHash: Long?,
    val warningMessage: String?
)

