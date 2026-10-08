package com.manga.translator.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.manga.translator.capture.ScreenCaptureController
import com.manga.translator.overlay.BubbleAdjustment
import com.manga.translator.overlay.EraseStrategyPolicy
import com.manga.translator.overlay.FloatingState
import com.manga.translator.overlay.OverlayController
import com.manga.translator.overlay.OverlayRenderConfig
import com.manga.translator.overlay.WritableAreaRefiner
import com.manga.translator.store.AppSettings
import com.manga.translator.store.PageOverlayAdjustment
import com.manga.translator.translate.Bubble
import com.manga.translator.translate.BubbleGeometry
import com.manga.translator.translate.BubbleRegion
import com.manga.translator.translate.BubbleRegionType
import com.manga.translator.translate.NonTranslatableText
import com.manga.translator.translate.OcrBlockMergeRules
import com.manga.translator.translate.TextBlock
import com.manga.translator.translate.TranslationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class TranslateOrchestrator(
    private val captureController: ScreenCaptureController,
    private val overlayController: OverlayController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val writableAreaRefiner = WritableAreaRefiner()
    private val bubbleFirstRegionPlanner = BubbleFirstRegionPlanner()

    /** 缓存查找 + 引擎分发（本地/百度/云端）+ 缓存回写，2026-09-28 抽出供浏览器链路复用。 */
    private val translationResolver = PageTranslationResolver(ioDispatcher)

    suspend fun runOnce(onStageChanged: ((FloatingState) -> Unit)? = null): RunOnceOutcome {
        val timer = TranslationTimer()
        timer.mark("start")

        val settings = ServiceLocator.settingsRepository.getSettings()
        if (!settings.enabled) {
            overlayController.clearTranslationLayer()
            timer.mark("render_done", "skipped=disabled")
            return RunOnceOutcome(hasOverlay = false, baselineHash = null, warningMessage = null)
        }
        val bitmap = try {
            captureController.captureBitmapWithRecovery(traceId = timer.traceId).also {
                timer.mark("screenshot_done", "w=${it.width},h=${it.height}")
            }
        } catch (err: Throwable) {
            timer.mark("capture_failure_type", err.javaClass.simpleName)
            timer.mark("render_done", "skipped=capture_failed_before_request")
            throw err
        }
        overlayController.setRenderViewport(bitmap.width, bitmap.height)
        val pageHash = withContext(ioDispatcher) { ServiceLocator.eraseProcessor.buildPageHash(bitmap) }
        val baselineHash = withContext(ioDispatcher) {
            try {
                FrameHash.centerDHash(bitmap)
            } catch (_: Throwable) {
                null
            }
        }

        onStageChanged?.invoke(FloatingState.TRANSLATING)

        // 截图内容随时间变化（状态栏时钟等），OCR 结果不做持久缓存，每次都重新识别。
        val ocrResult = try {
            detectBubblesBubbleFirst(
                bitmap = bitmap,
                settings = settings,
                timer = timer
            ).also {
                timer.mark("ocr_response", "bubbles=${it.bubbles.size}")
            }
        } catch (err: Throwable) {
            timer.mark("response_fail", "stage=ocr reason=${err.message?.take(80)}")
            throw err
        }

        if (ocrResult.bubbles.isEmpty()) {
            bitmap.recycle()
            overlayController.clearTranslationLayer()
            timer.mark("render_done", "bubbles=0")
            return RunOnceOutcome(hasOverlay = false, baselineHash = null, warningMessage = null)
        }

        // 「读不出就别动」（2026-09-30 用户要求）：OCR 读不出可覆盖内容的区域（空 /
        // 极短纯符号 / 极短混合文字系统碎片，判据见 EraseTargets.kt）在这里**整块丢弃** ——
        // 不擦（白块）、不翻译（垃圾进垃圾出）、不渲染（没擦却盖字 = 双重污染）。
        // 必须在源头丢，而不是只跳过擦除：擦除与渲染本来共用这份列表，源头一致才能
        // 保证「要么擦掉并盖上译文，要么原样保留」，没有中间态。
        val handledBubbles = ocrResult.bubbles.filter { isWorthErasing(it) }
        val droppedBubbles = ocrResult.bubbles.filterNot { isWorthErasing(it) }
        if (droppedBubbles.isNotEmpty()) {
            timer.mark(
                "erase_skip",
                "dropped=${droppedBubbles.size} kept=${handledBubbles.size} " +
                    "skipped_text=" + droppedBubbles.joinToString(separator = " ") { "[${it.originalText}]" }
            )
        }
        val effectiveOcrResult = if (droppedBubbles.isEmpty()) {
            ocrResult
        } else {
            ocrResult.copy(bubbles = handledBubbles)
        }
        if (effectiveOcrResult.bubbles.isEmpty()) {
            // 全部区域都读不出：整屏保持原样，不做任何覆盖。
            bitmap.recycle()
            overlayController.clearTranslationLayer()
            timer.mark("render_done", "bubbles=0 all_skipped_unreadable")
            return RunOnceOutcome(hasOverlay = false, baselineHash = null, warningMessage = null)
        }

        // 擦除/颜色/布局计算只依赖气泡与截图，与翻译请求并行，减少总等待时间
        val prepDeferred = coroutineScope {
            async(ioDispatcher) {
                prepareVisuals(
                    result = effectiveOcrResult,
                    screenshotBitmap = bitmap,
                    pageHash = pageHash,
                    timer = timer
                )
            }
        }

        val glossaryEntries = ServiceLocator.glossaryRepository.activeGlossaryEntries()
        val translateKey = translationResolver.buildTranslateKey(
            texts = effectiveOcrResult.bubbles.map { it.originalText },
            settings = settings,
            glossaryRevision = ServiceLocator.glossaryRepository.activeRevision()
        )

        var warningMessage: String? = null
        var cacheStats = TranslationCacheStats(0, 0, 0, 0, 0)
        val useLocalEngine = com.manga.translator.store.TranslateEngine.fromId(settings.translateEngine) ==
            com.manga.translator.store.TranslateEngine.ML_KIT &&
            ServiceLocator.localTranslator.isSupported(settings.sourceLanguage, settings.targetLanguage)
        val missingCloudConfig = settings.translateBaseUrl.isBlank() || settings.translateApiKey.isBlank()
        val mergedResult = if (missingCloudConfig && !useLocalEngine) {
            warningMessage = "仅OCR（未配置翻译API）"
            timer.mark("translate_response", "skipped=missing_translate_api")
            TranslationResult(
                effectiveOcrResult.bubbles.map { bubble ->
                    bubble.copy(translatedText = bubble.originalText)
                }
            )
        } else {
            translationResolver.resolveTranslations(
                bubbles = effectiveOcrResult.bubbles,
                settings = settings,
                translateKey = translateKey,
                pageHash = pageHash,
                glossaryEntries = glossaryEntries,
                timer = timer,
                onTranslatorDownloading = { downloading ->
                    onStageChanged?.invoke(
                        if (downloading) FloatingState.MODEL_LOADING else FloatingState.TRANSLATING
                    )
                }
            ).let { outcome ->
                warningMessage = outcome.warningMessage
                cacheStats = outcome.cacheStats
                outcome.result
            }
        }

        val prep = prepDeferred.await()
        return renderAndBuildOutcome(
            mergedResult,
            baselineHash,
            timer,
            bitmap,
            pageHash,
            warningMessage,
            prep,
            cacheStats
        )
    }

    private suspend fun detectBubblesBubbleFirst(
        bitmap: Bitmap,
        settings: AppSettings,
        timer: TranslationTimer
    ): TranslationResult {
        timer.mark("request_send", "stage=ocr mode=bubble_first provider=local")
        // 区域检测与整页 OCR 互不依赖，并行执行省去串行等待。
        val (bubbleRegions, structureBlocks) = coroutineScope {
            val regions = async(ioDispatcher) {
                ServiceLocator.bubbleRegionDetector.detect(bitmap)
            }
            val blocks = async(ioDispatcher) {
                detectStructureBlocks(
                    bitmap = bitmap,
                    settings = settings,
                    timer = timer
                )
            }
            regions.await() to blocks.await()
        }
        timer.mark("bubble_region_stats", "detected=${bubbleRegions.size}")
        val separatedByWhitespace = buildWhitespaceSeparatorDetector(bitmap)
        // 合并判决诊断（2026-10-03）：把「接近但被拒」的出对汇总成一行，
        // 排查「两个气泡被并成一段 / 被拆开」时不必再从最终气泡数反推。
        val mergeRejects = mutableListOf<BlockMergeReject>()
        bubbleFirstRegionPlanner.setMergeDiagnostics(mergeRejects)
        val routing = try {
            bubbleFirstRegionPlanner.route(
                bubbleRegions = bubbleRegions,
                textBlocks = structureBlocks,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                separatedByWhitespace = separatedByWhitespace
            )
        } catch (err: Throwable) {
            bubbleFirstRegionPlanner.setMergeDiagnostics(null)
            throw err
        }
        val promotedAssignments = promoteBubbleLikeOutsideAssignments(
            bitmap = bitmap,
            outsideAssignments = routing.outsideAssignments.filter { it.blocks.isNotEmpty() },
            timer = timer
        )
        val bubbleAssignments = (routing.bubbleAssignments.filter { it.blocks.isNotEmpty() } + promotedAssignments.first)
            .sortedBy { it.region.id }
        val outsideAssignments = promotedAssignments.second
        timer.mark(
            "bubble_region_assignment",
            "detected=${bubbleRegions.size} matched=${bubbleAssignments.size} outside_regions=${outsideAssignments.size} outside_blocks=${routing.outsideBlocks.size}"
        )
        timer.mark("outside_region_stats", "regions=${outsideAssignments.size} blocks=${routing.outsideBlocks.size}")

        val bubbles = try {
            buildBubblesFromAssignments(
                assignments = bubbleAssignments + outsideAssignments,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                separatedByWhitespace = separatedByWhitespace
            )
        } finally {
            bubbleFirstRegionPlanner.setMergeDiagnostics(null)
        }
        BubbleFirstRegionPlanner.mergeRejectDiag(mergeRejects)?.let { diag ->
            timer.mark("block_merge_reject", diag)
        }
        timer.mark(
            "bubble_first_result_stats",
            "bubble_regions=${bubbleAssignments.size} outside_regions=${outsideAssignments.size} final=${bubbles.size}"
        )
        if (bubbles.isEmpty()) {
            timer.mark("bubble_first_fallback", "reason=empty_result")
            return detectBubblesLegacyFallback(
                bitmap = bitmap,
                settings = settings,
                timer = timer
            )
        }
        timer.mark("response_ok", "stage=ocr bubbles=${bubbles.size}")
        return TranslationResult(bubbles)
    }

    private suspend fun detectBubblesLegacyFallback(
        bitmap: Bitmap,
        settings: AppSettings,
        timer: TranslationTimer
    ): TranslationResult {
        val localBlocks = withContext(ioDispatcher) {
            ServiceLocator.localTextDetector.detect(bitmap, settings.sourceLanguage)
        }
        timer.mark("bubble_first_fallback", "mode=local_group blocks=${localBlocks.size}")
        return groupLocalBlocks(
            blocks = localBlocks,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            timer = timer,
            separatedByWhitespace = buildWhitespaceSeparatorDetector(bitmap)
        )
    }

    private suspend fun detectStructureBlocks(
        bitmap: Bitmap,
        settings: AppSettings,
        timer: TranslationTimer
    ): List<TextBlock> {
        val localBlocks = withContext(ioDispatcher) {
            ServiceLocator.localTextDetector.detect(bitmap, settings.sourceLanguage)
        }
        timer.mark("bubble_region_ocr_stats", "provider=local lang=${settings.sourceLanguage} blocks=${localBlocks.size}")
        return localBlocks
    }

    private fun promoteBubbleLikeOutsideAssignments(
        bitmap: Bitmap,
        outsideAssignments: List<BubbleRegionAssignment>,
        timer: TranslationTimer
    ): Pair<List<BubbleRegionAssignment>, List<BubbleRegionAssignment>> {
        if (outsideAssignments.isEmpty()) return emptyList<BubbleRegionAssignment>() to emptyList()
        val promoted = mutableListOf<BubbleRegionAssignment>()
        val retained = mutableListOf<BubbleRegionAssignment>()
        outsideAssignments.forEach { assignment ->
            val promotedRegion = estimateBubbleFallbackRegion(bitmap, assignment) ?: run {
                retained += assignment
                return@forEach
            }
            promoted += BubbleRegionAssignment(region = promotedRegion, blocks = assignment.blocks)
        }
        timer.mark(
            "bubble_region_promote",
            "promoted=${promoted.size} retained_outside=${retained.size}"
        )
        return promoted to retained
    }

    private fun estimateBubbleFallbackRegion(bitmap: Bitmap, assignment: BubbleRegionAssignment): BubbleRegion? {
        if (assignment.blocks.isEmpty()) return null
        val rawLeft = assignment.blocks.minOf { it.left }.coerceIn(0f, bitmap.width.toFloat())
        val rawTop = assignment.blocks.minOf { it.top }.coerceIn(0f, bitmap.height.toFloat())
        val rawRight = assignment.blocks.maxOf { it.right }.coerceIn(0f, bitmap.width.toFloat())
        val rawBottom = assignment.blocks.maxOf { it.bottom }.coerceIn(0f, bitmap.height.toFloat())
        val rawWidth = (rawRight - rawLeft).coerceAtLeast(1f)
        val rawHeight = (rawBottom - rawTop).coerceAtLeast(1f)
        val verticalHintCount = assignment.blocks.count { isVerticalTextBlock(it) }
        val likelyVertical = rawHeight >= rawWidth * 1.02f || verticalHintCount * 2 >= assignment.blocks.size
        val looksLikeShortHorizontalLabel = !likelyVertical &&
            assignment.blocks.size <= 1 &&
            rawWidth >= rawHeight * 2.2f
        if (looksLikeShortHorizontalLabel) return null
        val padX = if (likelyVertical) {
            max(rawWidth * 0.95f, rawHeight * 0.16f)
        } else {
            max(rawWidth * 0.28f, 18f)
        }
        val padY = if (likelyVertical) {
            max(rawHeight * 0.24f, 14f)
        } else {
            max(rawHeight * 0.26f, 14f)
        }
        val left = (rawLeft - padX).coerceIn(0f, bitmap.width.toFloat())
        val top = (rawTop - padY).coerceIn(0f, bitmap.height.toFloat())
        val right = (rawRight + padX).coerceIn(1f, bitmap.width.toFloat())
        val bottom = (rawBottom + padY).coerceIn(1f, bitmap.height.toFloat())
        val regionWidth = (right - left).coerceAtLeast(1f)
        val regionHeight = (bottom - top).coerceAtLeast(1f)
        val regionArea = regionWidth * regionHeight
        val textArea = assignment.blocks.sumOf { it.areaPx().toDouble() }.toFloat().coerceAtLeast(1f)
        val textCoverage = textArea / regionArea.coerceAtLeast(1f)
        if (textCoverage > 0.42f) return null
        val stats = sampleRegionStats(bitmap, left, top, right, bottom)
        val meanLuma = stats.first
        val darkRatio = stats.second
        val likelyBubble = meanLuma >= 208f &&
            darkRatio <= 0.20f &&
            regionArea <= bitmap.width.toFloat() * bitmap.height.toFloat() * 0.18f
        if (!likelyBubble) return null
        return BubbleRegion(
            id = SYNTHETIC_BUBBLE_REGION_ID_BASE + assignment.region.id,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            regionType = BubbleRegionType.BUBBLE,
            confidence = 0.58f,
            source = "text_fallback_bubble"
        )
    }

    private fun sampleRegionStats(
        bitmap: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Pair<Float, Float> {
        val safeLeft = left.toInt().coerceIn(0, bitmap.width - 1)
        val safeTop = top.toInt().coerceIn(0, bitmap.height - 1)
        val safeRight = right.toInt().coerceIn(safeLeft + 1, bitmap.width)
        val safeBottom = bottom.toInt().coerceIn(safeTop + 1, bitmap.height)
        val width = safeRight - safeLeft
        val height = safeBottom - safeTop
        if (width <= 0 || height <= 0) return 0f to 1f
        val stepX = max(1, width / 18)
        val stepY = max(1, height / 18)
        var samples = 0
        var lumaSum = 0f
        var darkCount = 0
        var y = safeTop
        while (y < safeBottom) {
            var x = safeLeft
            while (x < safeRight) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                lumaSum += luma
                if (luma < 170f) darkCount += 1
                samples += 1
                x += stepX
            }
            y += stepY
        }
        if (samples <= 0) return 0f to 1f
        return (lumaSum / samples.toFloat()) to (darkCount.toFloat() / samples.toFloat())
    }

    private fun buildWhitespaceSeparatorDetector(bitmap: Bitmap): (TextBlock, TextBlock) -> Boolean {
        val cache = hashMapOf<Long, Boolean>()
        return { a, b ->
            val firstId = min(a.id, b.id)
            val secondId = max(a.id, b.id)
            val key = (firstId.toLong() shl 32) xor (secondId.toLong() and 0xFFFFFFFFL)
            cache.getOrPut(key) {
                isSeparatedByWhitespace(bitmap, a, b)
            }
        }
    }

    private fun isSeparatedByWhitespace(bitmap: Bitmap, a: TextBlock, b: TextBlock): Boolean {
        val strip = computeWhitespaceProbeRect(bitmap, a, b) ?: return false
        val stats = sampleBrightnessStats(bitmap, strip)
        if (stats.sampleCount < WHITESPACE_MIN_SAMPLE_COUNT) return false
        val brightEnough = stats.meanLuma >= WHITESPACE_BRIGHT_LUMA_THRESHOLD &&
            stats.darkRatio <= WHITESPACE_MAX_DARK_RATIO
        val veryClean = stats.lightRatio >= WHITESPACE_MIN_LIGHT_RATIO &&
            stats.darkRatio <= WHITESPACE_CLEAN_DARK_RATIO
        return brightEnough || veryClean
    }

    private fun computeWhitespaceProbeRect(bitmap: Bitmap, a: TextBlock, b: TextBlock): RectF? {
        val hGap = max(0f, max(a.left, b.left) - min(a.right, b.right))
        val vGap = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))
        if (hGap <= WHITESPACE_MIN_GAP_PX && vGap <= WHITESPACE_MIN_GAP_PX) return null

        val minWidth = min(a.widthPx(), b.widthPx()).coerceAtLeast(1f)
        val minHeight = min(a.heightPx(), b.heightPx()).coerceAtLeast(1f)
        return if (hGap >= vGap) {
            val left = min(a.right, b.right)
            val right = max(a.left, b.left)
            if (right - left < WHITESPACE_MIN_STRIP_SPAN_PX) return null
            val padY = max(2f, minHeight * WHITESPACE_ORTHOGONAL_PAD_RATIO)
            RectF(
                left.coerceIn(0f, bitmap.width.toFloat()),
                (min(a.top, b.top) - padY).coerceIn(0f, bitmap.height.toFloat()),
                right.coerceIn(0f, bitmap.width.toFloat()),
                (max(a.bottom, b.bottom) + padY).coerceIn(0f, bitmap.height.toFloat())
            )
        } else {
            val top = min(a.bottom, b.bottom)
            val bottom = max(a.top, b.top)
            if (bottom - top < WHITESPACE_MIN_STRIP_SPAN_PX) return null
            val padX = max(2f, minWidth * WHITESPACE_ORTHOGONAL_PAD_RATIO)
            RectF(
                (min(a.left, b.left) - padX).coerceIn(0f, bitmap.width.toFloat()),
                top.coerceIn(0f, bitmap.height.toFloat()),
                (max(a.right, b.right) + padX).coerceIn(0f, bitmap.width.toFloat()),
                bottom.coerceIn(0f, bitmap.height.toFloat())
            )
        }.takeIf { it.width() >= 1f && it.height() >= 1f }
    }

    private fun sampleBrightnessStats(bitmap: Bitmap, rect: RectF): BrightnessStats {
        val safeLeft = rect.left.toInt().coerceIn(0, bitmap.width - 1)
        val safeTop = rect.top.toInt().coerceIn(0, bitmap.height - 1)
        val safeRight = rect.right.toInt().coerceIn(safeLeft + 1, bitmap.width)
        val safeBottom = rect.bottom.toInt().coerceIn(safeTop + 1, bitmap.height)
        val width = safeRight - safeLeft
        val height = safeBottom - safeTop
        if (width <= 0 || height <= 0) {
            return BrightnessStats(0f, 1f, 0f, 0)
        }
        val stepX = max(1, width / 14)
        val stepY = max(1, height / 14)
        var samples = 0
        var lumaSum = 0f
        var darkCount = 0
        var lightCount = 0
        var y = safeTop
        while (y < safeBottom) {
            var x = safeLeft
            while (x < safeRight) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                lumaSum += luma
                if (luma < WHITESPACE_DARK_LUMA_THRESHOLD) darkCount += 1
                if (luma >= WHITESPACE_LIGHT_LUMA_THRESHOLD) lightCount += 1
                samples += 1
                x += stepX
            }
            y += stepY
        }
        if (samples <= 0) {
            return BrightnessStats(0f, 1f, 0f, 0)
        }
        return BrightnessStats(
            meanLuma = lumaSum / samples.toFloat(),
            darkRatio = darkCount.toFloat() / samples.toFloat(),
            lightRatio = lightCount.toFloat() / samples.toFloat(),
            sampleCount = samples
        )
    }

    private fun buildBubblesFromAssignments(
        assignments: List<BubbleRegionAssignment>,
        imageWidth: Int,
        imageHeight: Int,
        separatedByWhitespace: ((TextBlock, TextBlock) -> Boolean)? = null
    ): List<Bubble> {
        return assignments.flatMap { assignment ->
            bubbleFirstRegionPlanner.buildBubblesFromAssignment(
                assignment = assignment,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                separatedByWhitespace = separatedByWhitespace
            )
        }
    }

private data class VisualPrep(
        val erasePatches: List<com.manga.translator.overlay.erase.ErasePatch>,
        val bubbleRenderRects: Map<Int, RectF>,
        val bubbleContentRects: Map<Int, RectF>,
        val bubbleColors: Map<Int, com.manga.translator.overlay.BubbleColors>
    )

    private suspend fun prepareVisuals(
        result: TranslationResult,
        screenshotBitmap: Bitmap,
        pageHash: String,
        timer: TranslationTimer
    ): VisualPrep {
        // 有非 solid（旁白/透明文字块）时构建整页清理图：inpaint 去字原图按气泡位置裁切贴回，
        // 纹理来自原图背景，观感对齐参考项目 local-ocr-service 的 cleanedImage。
        // 只擦「有内容可覆盖」的区域：OCR 读不出原文的区域擦了只会留下白块（2026-09-30 用户
        // 要求「读不出就别擦」）。判据与理由见 EraseTargets.kt。读不出的区域已在上游
        // （runOnce 的 erase_skip）整块丢弃，这里再过滤一遍是兜底 —— 理论上全部通过。
        val eraseTargets = result.bubbles.filter { isWorthErasing(it) }
        timer.mark("erase_targets", eraseTargetsDiag(result.bubbles))
        result.bubbles.forEachIndexed { i, b -> timer.mark("erase_target", eraseTargetDetailDiag(i, b)) }
        val needsCleanedImage = eraseTargets.any { !it.bgType.equals("solid", ignoreCase = true) }
        val cleanedImage = if (needsCleanedImage) {
            ServiceLocator.cleanedImageProcessor.buildCleanedImage(screenshotBitmap, eraseTargets, pageHash)
        } else {
            null
        }
        timer.mark(
            "cleaned_image",
            if (cleanedImage != null) {
                "built=${cleanedImage.width}x${cleanedImage.height}"
            } else if (needsCleanedImage) {
                "skipped=build_failed"
            } else {
                "skipped=all_solid"
            }
        )
        val sourcePatches = ServiceLocator.eraseProcessor.erasePage(
            source = screenshotBitmap,
            bubbles = eraseTargets,
            pageHash = pageHash,
            policy = EraseStrategyPolicy.OPENCV_WHEN_AVAILABLE,
            onPerf = { tag, extra -> timer.mark(tag, extra) },
            cleanedImage = cleanedImage
        )
        if (cleanedImage != null && !cleanedImage.isRecycled) {
            cleanedImage.recycle()
        }
        val (viewportWidth, viewportHeight) = overlayController.getRenderViewport()
        val bubbleRenderRects = result.bubbles.mapIndexed { index, bubble ->
            val srcRect = bubbleToSourceRect(
                bubble = bubble,
                sourceWidth = screenshotBitmap.width,
                sourceHeight = screenshotBitmap.height,
                useContentRect = false
            )
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
            val rawContentRect = bubbleToSourceRect(
                bubble = bubble,
                sourceWidth = screenshotBitmap.width,
                sourceHeight = screenshotBitmap.height,
                useContentRect = true
            )
            val refinedSourceRect = writableAreaRefiner.refine(screenshotBitmap, rawContentRect, bubble.bgType)
            val refinedRenderRect = mapSourceToRenderRect(
                srcRect = refinedSourceRect,
                sourceWidth = screenshotBitmap.width,
                sourceHeight = screenshotBitmap.height,
                renderWidth = viewportWidth,
                renderHeight = viewportHeight
            )
            Log.d(
                "RenderDebug",
                "content_rect_refined bubble=$index bgType=${bubble.bgType} raw=$rawContentRect refined=$refinedSourceRect render=$refinedRenderRect"
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
        val bubbleColors = result.bubbles.mapIndexed { index, bubble ->
            index to ServiceLocator.bubbleColorExtractor.extract(screenshotBitmap, bubble)
        }.toMap()
        timer.mark("bubble_colors", "count=${bubbleColors.size}")
        return VisualPrep(
            erasePatches = erasePatches,
            bubbleRenderRects = bubbleRenderRects,
            bubbleContentRects = bubbleContentRects,
            bubbleColors = bubbleColors
        )
    }

    private suspend fun renderAndBuildOutcome(
        result: TranslationResult,
        baselineHash: Long?,
        timer: TranslationTimer,
        screenshotBitmap: android.graphics.Bitmap,
        pageHash: String,
        warningMessage: String?,
        prep: VisualPrep,
        cacheStats: TranslationCacheStats = TranslationCacheStats(0, 0, 0, 0, 0)
    ): RunOnceOutcome {
        if (result.bubbles.isEmpty()) {
            screenshotBitmap.recycle()
            overlayController.clearTranslationLayer()
            timer.mark("render_done", "bubbles=0")
            return RunOnceOutcome(hasOverlay = false, baselineHash = null, warningMessage = warningMessage)
        }

        val totalChars = totalEffectiveChars(result.bubbles)
        val denseMode = shouldUseDenseMode(result.bubbles.size, totalChars)
        if (!screenshotBitmap.isRecycled) {
            screenshotBitmap.recycle()
        }

        overlayController.renderBubbles(
            bubbles = result.bubbles,
            renderConfig = OverlayRenderConfig(denseMode = denseMode, erasePolicy = EraseStrategyPolicy.OPENCV_WHEN_AVAILABLE),
            erasePatches = prep.erasePatches,
            bubbleRenderRects = prep.bubbleRenderRects,
            bubbleContentRects = prep.bubbleContentRects,
            pageHash = pageHash,
            bubbleColors = prep.bubbleColors,
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
        timer.mark("render_done", "bubbles=${result.bubbles.size},dense=$denseMode,total_chars=$totalChars")
        return RunOnceOutcome(
            hasOverlay = true,
            baselineHash = baselineHash,
            warningMessage = warningMessage,
            cacheHitCount = cacheStats.hitCount,
            cacheTotalCount = cacheStats.total
        )
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
        sourceHeight: Int,
        useContentRect: Boolean
    ): RectF {
        val rect = if (useContentRect) BubbleGeometry.contentRect(bubble) else BubbleGeometry.outerRect(bubble)
        val left = ((rect.left / 100f) * sourceWidth.toFloat()).coerceIn(0f, sourceWidth.toFloat())
        val top = ((rect.top / 100f) * sourceHeight.toFloat()).coerceIn(0f, sourceHeight.toFloat())
        val right = ((rect.right / 100f) * sourceWidth.toFloat()).coerceIn(0f, sourceWidth.toFloat())
        val bottom = ((rect.bottom / 100f) * sourceHeight.toFloat()).coerceIn(0f, sourceHeight.toFloat())
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

    private fun shouldUseDenseMode(bubbleCount: Int, totalChars: Int): Boolean {
        return totalChars >= DENSE_CHAR_THRESHOLD && bubbleCount >= DENSE_BUBBLE_THRESHOLD
    }

private suspend fun groupLocalBlocks(
        blocks: List<TextBlock>,
        imageWidth: Int,
        imageHeight: Int,
        timer: TranslationTimer,
        separatedByWhitespace: ((TextBlock, TextBlock) -> Boolean)? = null
    ): TranslationResult {
        if (blocks.isEmpty()) return TranslationResult(emptyList())
        val selectedGroups = buildHeuristicTextBlockGroups(
            blocks = blocks,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            separatedByWhitespace = separatedByWhitespace
        )
        timer.mark("group_response", "mode=heuristic groups=${selectedGroups.size}")

        val bubbles = selectedGroups.mapNotNull { memberBlocks ->
            buildLocalBubbleFromGroup(memberBlocks, imageWidth, imageHeight)
        }.filter { it.originalText.isNotBlank() && it.w > 0f && it.h > 0f }
        logLocalOcrGroupingSample(
            traceId = timer.traceId,
            blocks = blocks,
            groups = selectedGroups,
            bubbles = bubbles
        )
        return TranslationResult(bubbles)
    }

    private fun buildHeuristicTextBlockGroups(
        blocks: List<TextBlock>,
        imageWidth: Int,
        imageHeight: Int,
        separatedByWhitespace: ((TextBlock, TextBlock) -> Boolean)? = null
    ): List<List<TextBlock>> {
        if (blocks.isEmpty()) return emptyList()
        val sortedBlocks = blocks.sortedWith(compareBy<TextBlock> { it.left }.thenBy { it.top })
        val parent = IntArray(sortedBlocks.size) { it }

        fun find(x: Int): Int {
            var node = x
            while (parent[node] != node) {
                parent[node] = parent[parent[node]]
                node = parent[node]
            }
            return node
        }

        fun union(a: Int, b: Int) {
            val pa = find(a)
            val pb = find(b)
            if (pa != pb) {
                parent[pb] = pa
            }
        }

        for (i in sortedBlocks.indices) {
            for (j in (i + 1) until sortedBlocks.size) {
                if (shouldMergeLocalBlocks(sortedBlocks[i], sortedBlocks[j], imageWidth, imageHeight, separatedByWhitespace)) {
                    union(i, j)
                }
            }
        }

        val grouped = linkedMapOf<Int, MutableList<TextBlock>>()
        sortedBlocks.forEachIndexed { index, block ->
            val root = find(index)
            grouped.getOrPut(root) { mutableListOf() }.add(block)
        }
        return grouped.values.map { members ->
            members.sortedWith(compareBy<TextBlock> { it.top }.thenBy { it.left })
        }
    }

    private fun shouldMergeLocalBlocks(
        a: TextBlock,
        b: TextBlock,
        imageWidth: Int,
        imageHeight: Int,
        separatedByWhitespace: ((TextBlock, TextBlock) -> Boolean)? = null
    ): Boolean {
        if (separatedByWhitespace?.invoke(a, b) == true) return false
        // 2026-10-03：与 planner 同源，用收严后的行高判据（阈值以**行高**为单位）。
        // 旧判据以块高为单位，多行段落块会把间距上限放大数倍 → 两个独立气泡被并成一句。
        return OcrBlockMergeRules.shouldMerge(a, b).merge
    }

    private fun buildLocalBubbleFromGroup(
        memberBlocks: List<TextBlock>,
        imageWidth: Int,
        imageHeight: Int
    ): Bubble? {
        if (memberBlocks.isEmpty()) return null
        val rawLeft = memberBlocks.minOf { it.left }.coerceIn(0f, imageWidth.toFloat())
        val rawTop = memberBlocks.minOf { it.top }.coerceIn(0f, imageHeight.toFloat())
        val rawRight = memberBlocks.maxOf { it.right }.coerceIn(0f, imageWidth.toFloat())
        val rawBottom = memberBlocks.maxOf { it.bottom }.coerceIn(0f, imageHeight.toFloat())
        val rawWidth = max(1f, rawRight - rawLeft)
        val rawHeight = max(1f, rawBottom - rawTop)
        val verticalHintCount = memberBlocks.count { isVerticalTextBlock(it) }
        val verticalLayout = rawHeight >= rawWidth * 1.12f || verticalHintCount * 2 >= memberBlocks.size + 1
        val layout = if (verticalLayout) "vertical" else "horizontal"
        val padX = max(LOCAL_BUBBLE_MIN_PADDING_PX, rawWidth * LOCAL_BUBBLE_PADDING_RATIO)
        val padY = max(LOCAL_BUBBLE_MIN_PADDING_PX, rawHeight * LOCAL_BUBBLE_PADDING_RATIO)
        val leftPx = (rawLeft - padX).coerceIn(0f, imageWidth.toFloat())
        val topPx = (rawTop - padY).coerceIn(0f, imageHeight.toFloat())
        val rightPx = (rawRight + padX).coerceIn(0f, imageWidth.toFloat())
        val bottomPx = (rawBottom + padY).coerceIn(0f, imageHeight.toFloat())
        val widthPx = max(1f, rightPx - leftPx)
        val heightPx = max(1f, bottomPx - topPx)
        val originalText = if (verticalLayout) {
            memberBlocks
                .sortedWith(compareByDescending<TextBlock> { it.centerX() }.thenBy { it.top })
                .joinToString(separator = "") { it.text.trim().replace("\n", "") }
        } else {
            val sorted = memberBlocks.sortedWith(compareBy<TextBlock> { it.top }.thenBy { it.left })
            sorted.joinToString(separator = "\n") { it.text.trim() }
        }.trim()
        if (originalText.isBlank()) return null
        return Bubble(
            x = (leftPx / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
            y = (topPx / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
            w = (widthPx / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
            h = (heightPx / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
            bgType = "solid",
            originalText = originalText,
            translatedText = "",
            layout = layout,
            contentX = (rawLeft / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
            contentY = (rawTop / imageHeight.toFloat() * 100f).coerceIn(0f, 100f),
            contentW = (rawWidth / imageWidth.toFloat() * 100f).coerceIn(0f, 100f),
            contentH = (rawHeight / imageHeight.toFloat() * 100f).coerceIn(0f, 100f)
        )
    }

    private fun isVerticalTextBlock(block: TextBlock): Boolean {
        return block.heightPx() >= block.widthPx() * 1.15f || block.text.trim().length <= 2
    }

    private fun TextBlock.widthPx(): Float = (right - left).coerceAtLeast(1f)

    private fun TextBlock.heightPx(): Float = (bottom - top).coerceAtLeast(1f)

    private fun TextBlock.areaPx(): Float = widthPx() * heightPx()

    private fun TextBlock.centerX(): Float = (left + right) / 2f

    private fun TextBlock.centerY(): Float = (top + bottom) / 2f

    private fun logLocalOcrGroupingSample(
        traceId: String,
        blocks: List<TextBlock>,
        groups: List<List<TextBlock>>,
        bubbles: List<Bubble>
    ) {
        Log.d(
            LOCAL_OCR_DEBUG_TAG,
            "trace=$traceId blocks=${blocks.size} groups=${groups.size} bubbles=${bubbles.size}"
        )
        val sampleCount = min(LOCAL_OCR_DEBUG_SAMPLE_SIZE, bubbles.size)
        for (index in 0 until sampleCount) {
            val bubble = bubbles[index]
            val groupBlocks = groups.getOrNull(index).orEmpty()
            val sampleText = bubble.originalText
                .replace("\n", "\\n")
                .take(LOCAL_OCR_DEBUG_TEXT_PREVIEW_LEN)
            val blockPreview = groupBlocks
                .take(4)
                .joinToString(separator = " | ") { block ->
                    val text = block.text.replace("\n", "").take(16)
                    "${text}[${block.left.toInt()},${block.top.toInt()},${block.right.toInt()},${block.bottom.toInt()}]"
                }
            Log.d(
                LOCAL_OCR_DEBUG_TAG,
                "trace=$traceId bubble#$index layout=${bubble.layout} groupBlocks=${groupBlocks.size} " +
                    "text=$sampleText blocks={$blockPreview}"
            )
        }
    }

    companion object {
        private const val DENSE_CHAR_THRESHOLD = 180
        private const val DENSE_BUBBLE_THRESHOLD = 8
        private const val LOCAL_BUBBLE_PADDING_RATIO = 0.10f
        private const val LOCAL_BUBBLE_MIN_PADDING_PX = 6f
        private const val LOCAL_OCR_DEBUG_TAG = "LocalOcrDebug"
        private const val LOCAL_OCR_DEBUG_SAMPLE_SIZE = 6
        private const val LOCAL_OCR_DEBUG_TEXT_PREVIEW_LEN = 96
        private const val SYNTHETIC_BUBBLE_REGION_ID_BASE = 100000
        private const val WHITESPACE_MIN_GAP_PX = 6f
        private const val WHITESPACE_MIN_STRIP_SPAN_PX = 4f
        private const val WHITESPACE_ORTHOGONAL_PAD_RATIO = 0.10f
        private const val WHITESPACE_DARK_LUMA_THRESHOLD = 180f
        private const val WHITESPACE_LIGHT_LUMA_THRESHOLD = 228f
        private const val WHITESPACE_BRIGHT_LUMA_THRESHOLD = 214f
        private const val WHITESPACE_MAX_DARK_RATIO = 0.10f
        private const val WHITESPACE_MIN_LIGHT_RATIO = 0.70f
        private const val WHITESPACE_CLEAN_DARK_RATIO = 0.06f
        private const val WHITESPACE_MIN_SAMPLE_COUNT = 10
    }
}

private data class BrightnessStats(
    val meanLuma: Float,
    val darkRatio: Float,
    val lightRatio: Float,
    val sampleCount: Int
)

data class RunOnceOutcome(
    val hasOverlay: Boolean,
    val baselineHash: Long?,
    val warningMessage: String?,
    val cacheHitCount: Int = 0,
    val cacheTotalCount: Int = 0
)

