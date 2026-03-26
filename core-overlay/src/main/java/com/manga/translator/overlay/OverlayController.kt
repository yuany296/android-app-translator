package com.manga.translator.overlay

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.manga.translator.overlay.erase.ErasePatch
import com.manga.translator.translate.Bubble
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class OverlayController(private val context: Context) {
    private val patchFillColor = Color.parseColor("#181818")
    private val patchStrokeColor = Color.parseColor("#E6E2DB")
    private val floatingSizeDp = 40
    private val denseFontScale = 0.75f
    private val normalFontScale = 0.85f
    private val minFitSp = if (context.resources.displayMetrics.densityDpi >= 360) 4.5f else 5f
    private val maxFitSp = 16f
    private val adjustDurationMs = 10_000L
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val tapTimeoutMs = ViewConfiguration.getTapTimeout().toLong()
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val floatingPeekDp = 18
    private val floatingDockSnapThresholdDp = 28

    private var interactionMode: InteractionMode = InteractionMode.BROWSE

    private var floatingButton: TextView? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var floatingStatusView: TextView? = null
    private var floatingStatusParams: WindowManager.LayoutParams? = null
    private var floatingPulseAnimator: ObjectAnimator? = null

    private var translationLayer: FrameLayout? = null
    private var translationParams: WindowManager.LayoutParams? = null
    private var expandedCardView: TextView? = null
    private var expandedCardParams: WindowManager.LayoutParams? = null
    private var erasePatchLayer: ErasePatchLayerView? = null
    private val activeErasePatches = mutableListOf<ErasePatch>()

    private val bubbleStates = linkedMapOf<String, BubbleRenderState>()
    private val activeBubbleViews = linkedMapOf<String, View>()
    private var lastRenderConfig: OverlayRenderConfig = OverlayRenderConfig()
    private var renderViewportWidthPx: Int? = null
    private var renderViewportHeightPx: Int? = null

    private var adjustTimeoutRunnable: Runnable? = null
    private var floatingCollapsed: Boolean = false
    private var dockSide: DockSide = DockSide.RIGHT
    private var lastExpandedX: Int? = null
    private var lastExpandedY: Int? = null
    private var currentPageHash: String = ""
    private var loadAdjustment: ((String, String) -> BubbleAdjustment?)? = null
    private var saveAdjustment: ((String, String, BubbleAdjustment) -> Unit)? = null
    private val patchTextSpecs = linkedMapOf<String, PatchTextSpec>()

    fun showFloatingButton(onClick: () -> Unit) {
        if (floatingButton != null) return

        val button = TextView(context).apply {
            text = "译"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            width = dp(floatingSizeDp)
            height = dp(floatingSizeDp)
            isClickable = true
            isFocusable = false
            setOnClickListener {
                if (floatingCollapsed) {
                    setFloatingCollapsed(false)
                    return@setOnClickListener
                }
                onClick()
            }
            setOnTouchListener(FloatingButtonTouchListener())
        }

        val params = WindowManager.LayoutParams(
            dp(floatingSizeDp),
            dp(floatingSizeDp),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(180)
        }

        windowManager.addView(button, params)
        floatingButton = button
        floatingParams = params
        lastExpandedX = params.x
        lastExpandedY = params.y
        ensureStatusView()
        applyFloatingButtonColor(Color.parseColor("#E53935"))
        clampFloatingPosition()
        setFloatingState(FloatingState.IDLE, null)
    }

    fun setFloatingState(state: FloatingState, message: String?) {
        val button = floatingButton ?: return
        if (button.visibility != View.VISIBLE) {
            hideStatus()
            return
        }
        when (state) {
            FloatingState.IDLE -> {
                stopPulse()
                applyFloatingButtonColor(Color.parseColor("#E53935"))
                if (interactionMode == InteractionMode.ADJUST) {
                    showStatus("调整模式")
                } else {
                    hideStatus()
                }
            }

            FloatingState.CAPTURING -> {
                startPulse()
                applyFloatingButtonColor(Color.parseColor("#F57C00"))
                showStatus(message ?: "截图中")
            }

            FloatingState.TRANSLATING -> {
                startPulse()
                applyFloatingButtonColor(Color.parseColor("#1976D2"))
                showStatus(message ?: "翻译中")
            }

            FloatingState.DONE -> {
                stopPulse()
                applyFloatingButtonColor(Color.parseColor("#2E7D32"))
                showStatus(message ?: "完成")
            }

            FloatingState.ERROR -> {
                stopPulse()
                applyFloatingButtonColor(Color.parseColor("#C62828"))
                showStatus(message ?: "失败")
            }
        }
        clampFloatingPosition()
    }

    fun setFloatingVisible(visible: Boolean) {
        floatingButton?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            hideStatus()
            return
        }
        clampFloatingPosition()
        updateStatusPosition()
    }

    fun setFloatingCollapsed(collapsed: Boolean) {
        if (floatingButton == null || floatingParams == null) return
        if (interactionMode == InteractionMode.ADJUST && collapsed) {
            return
        }
        if (floatingCollapsed == collapsed) return
        val params = floatingParams ?: return
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val buttonSize = dp(floatingSizeDp)
        val peek = dp(floatingPeekDp)
        if (!collapsed) {
            floatingCollapsed = false
            val restoreX = lastExpandedX ?: params.x
            val restoreY = lastExpandedY ?: params.y
            params.x = restoreX.coerceIn(0, (screenW - buttonSize).coerceAtLeast(0))
            params.y = restoreY.coerceIn(0, (screenH - buttonSize).coerceAtLeast(0))
            floatingButton?.let { runCatching { windowManager.updateViewLayout(it, params) } }
            updateStatusPosition()
            Log.d("TranslationPerf", "floating_dock_state=expanded side=$dockSide")
            return
        }

        lastExpandedX = params.x.coerceIn(0, (screenW - buttonSize).coerceAtLeast(0))
        lastExpandedY = params.y.coerceIn(0, (screenH - buttonSize).coerceAtLeast(0))
        floatingCollapsed = collapsed
        val centerX = params.x + buttonSize / 2f
        dockSide = if (centerX < screenW / 2f) DockSide.LEFT else DockSide.RIGHT
        params.x = when (dockSide) {
            DockSide.LEFT -> (-buttonSize + peek)
            DockSide.RIGHT -> (screenW - peek)
        }
        params.y = params.y.coerceIn(0, (screenH - buttonSize).coerceAtLeast(0))
        floatingButton?.let { runCatching { windowManager.updateViewLayout(it, params) } }
        updateStatusPosition()
        if (collapsed) {
            Log.d("TranslationPerf", "floating_dock_state=collapsed side=$dockSide")
        }
    }

    fun resetFloatingPosition() {
        val params = floatingParams ?: return
        val buttonSize = dp(floatingSizeDp)
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        dockSide = DockSide.RIGHT
        floatingCollapsed = false
        params.x = (screenW - buttonSize).coerceAtLeast(0)
        params.y = (screenH / 2 - buttonSize / 2).coerceAtLeast(dp(16))
        lastExpandedX = params.x
        lastExpandedY = params.y
        floatingButton?.let { runCatching { windowManager.updateViewLayout(it, params) } }
        updateStatusPosition()
        Log.d("TranslationPerf", "floating_position_clamped action=reset x=${params.x} y=${params.y}")
    }

    fun setInteractionMode(mode: InteractionMode) {
        if (interactionMode == mode) {
            if (mode == InteractionMode.ADJUST) {
                scheduleAdjustTimeout()
            }
            return
        }
        interactionMode = mode
        applyLayerTouchMode()
        refreshPatchTextLayer()
        updateSolidEditorVisibility()
        if (mode == InteractionMode.ADJUST) {
            setFloatingCollapsed(false)
            showStatus("调整模式：可拖动文字")
            scheduleAdjustTimeout()
        } else {
            bubbleStates.values.forEach { persistAdjustment(it) }
            cancelAdjustTimeout()
            hideExpandedCard()
            activeBubbleViews.keys.forEach { key ->
                bubbleStates[key]?.expanded = false
            }
            hideStatus()
        }
    }

    fun setRenderViewport(widthPx: Int, heightPx: Int) {
        if (widthPx > 0 && heightPx > 0) {
            renderViewportWidthPx = widthPx
            renderViewportHeightPx = heightPx
        }
    }

    fun getRenderViewport(): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val width = renderViewportWidthPx?.takeIf { it > 0 } ?: metrics.widthPixels
        val height = renderViewportHeightPx?.takeIf { it > 0 } ?: metrics.heightPixels
        return width to height
    }

    fun renderBubbles(
        bubbles: List<Bubble>,
        renderConfig: OverlayRenderConfig = OverlayRenderConfig(),
        onToggle: ((Bubble, Boolean) -> Unit)? = null,
        erasePatches: List<ErasePatch> = emptyList(),
        bubbleRenderRects: Map<Int, RectF> = emptyMap(),
        bubbleContentRects: Map<Int, RectF> = emptyMap(),
        pageHash: String = "",
        loadAdjustment: ((String, String) -> BubbleAdjustment?)? = null,
        saveAdjustment: ((String, String, BubbleAdjustment) -> Unit)? = null,
        onPerf: ((tag: String, extra: String?) -> Unit)? = null
    ) {
        ensureLayer()
        val layer = translationLayer ?: return
        clearErasePatches()
        layer.removeAllViews()
        ensurePatchLayer(layer)
        hideExpandedCard()
        bubbleStates.clear()
        activeBubbleViews.clear()
        patchTextSpecs.clear()

        this.currentPageHash = pageHash
        this.loadAdjustment = loadAdjustment
        this.saveAdjustment = saveAdjustment
        this.lastRenderConfig = renderConfig
        applyLayerTouchMode()
        applyErasePatches(erasePatches)

        if (bubbles.isEmpty()) {
            return
        }

        val allowExpand = renderConfig.allowExpand
        val autoFit = renderConfig.autoFitText
        val displayMetrics = context.resources.displayMetrics
        val screenW = renderViewportWidthPx?.toFloat()?.takeIf { it > 0f } ?: displayMetrics.widthPixels.toFloat()
        val screenH = renderViewportHeightPx?.toFloat()?.takeIf { it > 0f } ?: displayMetrics.heightPixels.toFloat()
        val placedRects = mutableListOf<RectF>()
        val fitCache = hashMapOf<String, TextFitResult>()
        val ordered = bubbles.withIndex().sortedByDescending { it.value.w * it.value.h }
        val prepared = mutableListOf<PreparedBubble>()

        var truncatedCount = 0
        var forcedSingleLineCount = 0
        var verticalNormalizedCount = 0
        var dialogueOpaqueCount = 0
        var overlayShiftPxTotal = 0f
        val layoutStartedAt = SystemClock.elapsedRealtime()

        ordered.forEachIndexed { orderIndex, indexed ->
            val bubble = indexed.value
            Log.d(
                "RenderDebug",
                "bubble=${indexed.index} bgType=${bubble.bgType} layout=${bubble.layout}"
            )
            val translatedRaw = bubble.translatedText.ifBlank { bubble.originalText }
            val normalizedTranslatedText = normalizeVerticalLayoutText(translatedRaw)
            if (normalizedTranslatedText != translatedRaw) {
                verticalNormalizedCount++
            }
            val mappedRect = bubbleRenderRects[indexed.index]
            val fallbackRect = RectF(
                ((bubble.x / 100f) * screenW).coerceIn(0f, screenW),
                ((bubble.y / 100f) * screenH).coerceIn(0f, screenH),
                (((bubble.x + bubble.w) / 100f) * screenW).coerceIn(0f, screenW),
                (((bubble.y + bubble.h) / 100f) * screenH).coerceIn(0f, screenH)
            )
            val baseRect = (bubbleContentRects[indexed.index] ?: mappedRect ?: fallbackRect).let {
                val leftBound = min(it.left, it.right).coerceIn(0f, screenW)
                val rightBound = max(it.left, it.right).coerceIn(0f, screenW)
                val topBound = min(it.top, it.bottom).coerceIn(0f, screenH)
                val bottomBound = max(it.top, it.bottom).coerceIn(0f, screenH)
                RectF(leftBound, topBound, rightBound, bottomBound)
            }
            val boxW = baseRect.width().coerceAtLeast(1f)
            val boxH = baseRect.height().coerceAtLeast(1f)
            val centerY = baseRect.centerY()
            val left = baseRect.left
            val top = baseRect.top
            val isNarration = isNarrationBubble(bubble)
            val isSolidPatched = bubble.bgType.equals("solid", ignoreCase = true)
            val useVerticalLayout = isVerticalLayoutBubble(bubble, normalizedTranslatedText)
            val key = "v2:${indexed.index}:${bubble.x}:${bubble.y}:${bubble.w}:${bubble.h}"

            val state = BubbleRenderState(key = key)
            bubbleStates[key] = state

            val densityScale = if (renderConfig.denseMode) denseFontScale else normalFontScale
            val baseTextSp = (computeFontSp(boxH) * densityScale * if (isNarration) 0.92f else 1f).coerceIn(minFitSp, maxFitSp)
            val baseStrokeDp = if (renderConfig.denseMode) 1.0f else 1.5f
            state.baseTextSp = baseTextSp
            state.baseStrokeDp = baseStrokeDp
            state.bubbleId = indexed.index
            state.solidPatched = isSolidPatched
            state.verticalLayout = useVerticalLayout
            state.displayText = normalizedTranslatedText
            state.baseRect = RectF(baseRect)

            val tv = if (useVerticalLayout) {
                VerticalTextView(context).apply {
                    text = normalizedTranslatedText
                    setTextColor(patchFillColor)
                    setStroke(patchStrokeColor, naturalStrokeWidth(baseTextSp, baseStrokeDp))
                    setBackgroundColor(Color.TRANSPARENT)
                    gravity = Gravity.CENTER
                    setPadding(dp(1), dp(1), dp(1), dp(1))
                }
            } else {
                StrokeTextView(context).apply {
                    text = normalizedTranslatedText
                    setTextColor(patchFillColor)
                    setStroke(patchStrokeColor, naturalStrokeWidth(baseTextSp, baseStrokeDp))
                    setBackgroundColor(Color.TRANSPARENT)
                    gravity = Gravity.CENTER
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
                    setPadding(dp(1), dp(1), dp(1), dp(1))
                }
            }
            if (!isNarration && !isSolidPatched) {
                dialogueOpaqueCount++
            }

            val aspect = (boxW / boxH).coerceIn(0.6f, 1.8f)
            val dynamicLetter = if (aspect > 1.25f) 0.02f else if (aspect < 0.85f) -0.01f else 0f
            tv.letterSpacing = dynamicLetter
            val baseMaxLines = if (renderConfig.denseMode) {
                3
            } else {
                max(1, (boxH / dpF(12f)).toInt() + 1)
            }
            val fitInput = "$normalizedTranslatedText|${boxW.toInt()}|${boxH.toInt()}|$baseMaxLines|${renderConfig.denseMode}|$isNarration"
            val fit = fitCache.getOrPut(fitInput) {
                if (autoFit) {
                    fitTextToBox(
                        text = normalizedTranslatedText,
                        templatePaint = tv.paint,
                        widthPx = boxW.toInt().coerceAtLeast(1),
                        heightPx = boxH.toInt().coerceAtLeast(1),
                        maxLines = baseMaxLines,
                        minSp = minFitSp,
                        maxSp = baseTextSp.coerceAtMost(maxFitSp)
                    )
                } else {
                    TextFitResult(baseTextSp, truncated = false, lineSpacingMultiplier = 1f)
                }
            }
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fit.textSizeSp)
            tv.maxLines = baseMaxLines
            tv.setLineSpacing(0f, fit.lineSpacingMultiplier)
            state.isTruncated = fit.truncated
            state.lineSpacingMultiplier = fit.lineSpacingMultiplier
            if (state.isTruncated) {
                tv.maxLines = 1
                tv.ellipsize = TextUtils.TruncateAt.END
                truncatedCount++
            }

            applyPersistedAdjustmentIfAny(state, key)
            applyBubbleStyle(tv, state)
            tv.isClickable = true
            tv.isFocusable = false
            tv.setOnTouchListener(BubbleDragTouchListener(state))
            tv.setOnClickListener { view ->
                if (interactionMode != InteractionMode.ADJUST) return@setOnClickListener
                val nextExpanded = !state.expanded
                bubbleStates.values.forEach { it.expanded = false }
                state.expanded = nextExpanded
                if (nextExpanded && allowExpand) {
                    showExpandedCard(normalizedTranslatedText, currentViewRect(view))
                } else {
                    hideExpandedCard()
                }
                onToggle?.invoke(bubble, state.expanded)
                scheduleAdjustTimeout()
            }
            tv.setOnLongClickListener { view ->
                if (interactionMode != InteractionMode.ADJUST) {
                    setInteractionMode(InteractionMode.ADJUST)
                    setFloatingState(FloatingState.IDLE, null)
                } else {
                    state.strokeScale = when {
                        state.strokeScale < 1.0f -> 1.0f
                        state.strokeScale < 1.2f -> 1.2f
                        else -> 0.85f
                    }
                    applyBubbleStyle(view as TextView, state)
                    persistAdjustment(state)
                    scheduleAdjustTimeout()
                }
                if (allowExpand) {
                    bubbleStates.values.forEach { it.expanded = false }
                    state.expanded = true
                    showExpandedCard(normalizedTranslatedText, currentViewRect(view))
                    onToggle?.invoke(bubble, true)
                }
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                true
            }
            tv.setTag(TAG_KEY_STATE, key)

            val shouldAvoid = interactionMode == InteractionMode.ADJUST
            val layoutElapsed = SystemClock.elapsedRealtime() - layoutStartedAt
            var placement = if (!shouldAvoid || layoutElapsed > LAYOUT_BUDGET_MS) {
                PlacementResult(
                    rect = clampRect(
                        left = left,
                        top = top,
                        width = boxW,
                        height = boxH,
                        screenW = screenW,
                        screenH = screenH
                    ),
                    forceSingleLine = false
                )
            } else {
                placeWithLimitedAvoidance(
                    desiredLeft = left,
                    desiredTop = top,
                    width = boxW,
                    height = boxH,
                    screenW = screenW,
                    screenH = screenH,
                    placed = placedRects
                )
            }
            if (placement.forceSingleLine && shouldAvoid) {
                tv.maxLines = 1
                tv.ellipsize = TextUtils.TruncateAt.END
                state.isTruncated = true
                truncatedCount++
                forcedSingleLineCount++
                val refined = placeWithLimitedAvoidance(
                    desiredLeft = left,
                    desiredTop = top,
                    width = boxW,
                    height = boxH,
                    screenW = screenW,
                    screenH = screenH,
                    placed = placedRects
                )
                val beforeOverlap = overlapArea(placement.rect, placedRects, dpF(3f))
                val afterOverlap = overlapArea(refined.rect, placedRects, dpF(3f))
                if (afterOverlap <= beforeOverlap) {
                    placement = refined.copy(forceSingleLine = true)
                }
            }

            val lp = buildBubbleLayoutParams(
                width = boxW.toInt().coerceAtLeast(1),
                height = boxH.toInt().coerceAtLeast(1),
                left = placement.rect.left.toInt() + state.offsetX,
                top = placement.rect.top.toInt() + state.offsetY
            )
            state.offsetX = lp.leftMargin - placement.rect.left.toInt()
            state.offsetY = lp.topMargin - placement.rect.top.toInt()
            state.baseRect = RectF(
                placement.rect.left,
                placement.rect.top,
                placement.rect.left + boxW,
                placement.rect.top + boxH
            )
            if (placement.rect.left > 8f && placement.rect.top > 8f && lp.leftMargin == 0 && lp.topMargin == 0) {
                Log.w("TranslationPerf", "layout_origin_warning key=$key target=${placement.rect.left.toInt()},${placement.rect.top.toInt()}")
            }
            tv.visibility = if (interactionMode == InteractionMode.ADJUST) View.VISIBLE else View.INVISIBLE
            placedRects += RectF(
                lp.leftMargin.toFloat(),
                lp.topMargin.toFloat(),
                lp.leftMargin + boxW,
                lp.topMargin + boxH
            )
            overlayShiftPxTotal += hypot((placement.rect.left - left).toDouble(), (placement.rect.top - top).toDouble()).toFloat()
            Log.d(
                "RenderDebug",
                "bubble=${indexed.index} srcRect=$baseRect renderRect=${state.baseRect} " +
                    "finalTextBounds=(${lp.leftMargin},${lp.topMargin},${(lp.leftMargin + boxW).toInt()},${(lp.topMargin + boxH).toInt()}) " +
                    "overlaySize=${screenW.toInt()}x${screenH.toInt()} bgType=${bubble.bgType} layout=${bubble.layout}"
            )
            upsertPatchTextSpec(state)

            val frameElapsed = SystemClock.elapsedRealtime() - layoutStartedAt
            val animDuration = if (frameElapsed > FRAME_BUDGET_MS) 0L else 220L
            if (interactionMode == InteractionMode.ADJUST) {
                tv.alpha = if (animDuration == 0L) 1f else 0f
                tv.animate().alpha(1f).setDuration(animDuration).setStartDelay((orderIndex * 30L).coerceAtMost(280L)).start()
            } else {
                tv.alpha = 1f
            }
            prepared += PreparedBubble(
                key = key,
                view = tv,
                layoutParams = lp,
                centerY = centerY
            )
        }
        refreshPatchTextLayer()

        val firstBatch = prepared
            .filter { it.centerY in (screenH * 0.2f)..(screenH * 0.8f) }
            .ifEmpty { prepared.take(6) }
            .take(12)
        onPerf?.invoke("layout_prepare", "prepared=${prepared.size} elapsed_ms=${SystemClock.elapsedRealtime() - layoutStartedAt}")
        val firstKeys = firstBatch.map { it.key }.toHashSet()
        val rest = prepared.filterNot { firstKeys.contains(it.key) }
        firstBatch.forEach { item ->
            layer.addView(item.view, item.layoutParams)
            activeBubbleViews[item.key] = item.view
        }
        onPerf?.invoke("first_paint", "count=${firstBatch.size}")
        if (rest.isNotEmpty()) {
            mainHandler.post {
                rest.forEach { item ->
                    layer.addView(item.view, item.layoutParams)
                    activeBubbleViews[item.key] = item.view
                }
                onPerf?.invoke("full_paint", "count=${prepared.size}")
            }
        } else {
            onPerf?.invoke("full_paint", "count=${prepared.size}")
        }

        Log.d(
            "TranslationPerf",
            "viewport_w=${screenW.toInt()} viewport_h=${screenH.toInt()} " +
                "render_truncated_count=$truncatedCount render_forced_single_line_count=$forcedSingleLineCount " +
                "vertical_normalized_count=$verticalNormalizedCount dialogue_opaque_count=$dialogueOpaqueCount " +
                "overlay_shift_px=${overlayShiftPxTotal.toInt()} total=${bubbles.size}"
        )
    }

    fun clearTranslationLayer() {
        clearErasePatches()
        translationLayer?.removeAllViews()
        erasePatchLayer = null
        bubbleStates.clear()
        activeBubbleViews.clear()
        patchTextSpecs.clear()
        hideExpandedCard()
    }

    fun hideAll() {
        cancelAdjustTimeout()
        floatingButton?.let { runCatching { windowManager.removeView(it) } }
        floatingButton = null
        floatingParams = null

        floatingStatusView?.let { runCatching { windowManager.removeView(it) } }
        floatingStatusView = null
        floatingStatusParams = null
        stopPulse()

        hideExpandedCard()
        clearErasePatches()
        translationLayer?.let { runCatching { windowManager.removeView(it) } }
        translationLayer = null
        translationParams = null
        erasePatchLayer = null
        bubbleStates.clear()
        activeBubbleViews.clear()
        patchTextSpecs.clear()
    }

    private fun ensurePatchLayer(layer: FrameLayout) {
        if (erasePatchLayer != null) return
        val patchLayer = ErasePatchLayerView(context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        layer.addView(
            patchLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        erasePatchLayer = patchLayer
    }

    private fun applyErasePatches(patches: List<ErasePatch>) {
        activeErasePatches.clear()
        activeErasePatches.addAll(patches)
        erasePatchLayer?.setPatches(activeErasePatches)
        refreshPatchTextLayer()
    }

    private fun clearErasePatches() {
        erasePatchLayer?.setPatches(emptyList())
        erasePatchLayer?.setPatchTexts(emptyList())
        activeErasePatches.forEach { patch ->
            if (!patch.bitmap.isRecycled) {
                patch.bitmap.recycle()
            }
        }
        activeErasePatches.clear()
    }

    private fun applyPersistedAdjustmentIfAny(state: BubbleRenderState, key: String) {
        val page = currentPageHash
        if (page.isBlank()) return
        val adjustment = loadAdjustment?.invoke(page, key) ?: return
        val limitX = max(8, (state.baseRect.width() * 0.4f).toInt())
        val limitY = max(8, (state.baseRect.height() * 0.4f).toInt())
        val clampedX = adjustment.offsetX.coerceIn(-limitX, limitX)
        val clampedY = adjustment.offsetY.coerceIn(-limitY, limitY)
        if (clampedX != adjustment.offsetX || clampedY != adjustment.offsetY) {
            Log.w(
                "RenderDebug",
                "adjustment_clamped key=$key raw=(${adjustment.offsetX},${adjustment.offsetY}) " +
                    "clamped=($clampedX,$clampedY) limit=($limitX,$limitY)"
            )
        }
        state.offsetX = clampedX
        state.offsetY = clampedY
        state.fontScale = adjustment.fontScale
        state.strokeScale = adjustment.strokeScale
    }

    private fun persistAdjustment(state: BubbleRenderState) {
        val page = currentPageHash
        if (page.isBlank()) return
        saveAdjustment?.invoke(
            page,
            state.key,
            BubbleAdjustment(
                offsetX = state.offsetX,
                offsetY = state.offsetY,
                fontScale = state.fontScale,
                strokeScale = state.strokeScale
            )
        )
    }

    private fun applyBubbleStyle(view: TextView, state: BubbleRenderState) {
        val scaledText = (state.baseTextSp * state.fontScale).coerceIn(minFitSp, maxFitSp)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledText)
        view.setLineSpacing(0f, state.lineSpacingMultiplier.coerceIn(0.85f, 1f))
        view.setTextColor(patchFillColor)
        if (view is StrokeTextView) {
            val strokeWidth = naturalStrokeWidth(scaledText, state.baseStrokeDp * state.strokeScale)
            view.setStroke(patchStrokeColor, strokeWidth)
        }
        if (view is VerticalTextView) {
            val strokeWidth = naturalStrokeWidth(scaledText, state.baseStrokeDp * state.strokeScale)
            view.setStroke(patchStrokeColor, strokeWidth)
        }
    }

    private fun buildBubbleLayoutParams(width: Int, height: Int, left: Int, top: Int): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(width, height).apply {
            leftMargin = left
            topMargin = top
        }
    }

    private fun upsertPatchTextSpec(state: BubbleRenderState) {
        patchTextSpecs[state.key] = PatchTextSpec(
            key = state.key,
            bubbleId = state.bubbleId,
            text = state.displayText,
            rect = RectF(
                state.baseRect.left + state.offsetX,
                state.baseRect.top + state.offsetY,
                state.baseRect.right + state.offsetX,
                state.baseRect.bottom + state.offsetY
            ),
            vertical = state.verticalLayout,
            textSizeSp = (state.baseTextSp * state.fontScale).coerceIn(minFitSp, maxFitSp),
            strokeDp = (state.baseStrokeDp * state.strokeScale).coerceAtLeast(0.6f),
            lineSpacingMultiplier = state.lineSpacingMultiplier
        )
    }

    private fun refreshPatchTextLayer() {
        val specs = if (interactionMode == InteractionMode.ADJUST) {
            emptyList()
        } else {
            patchTextSpecs.values.toList()
        }
        erasePatchLayer?.setPatchTexts(specs)
    }

    private fun updateSolidEditorVisibility() {
        activeBubbleViews.forEach { (_, view) ->
            view.visibility = if (interactionMode == InteractionMode.ADJUST) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun ensureLayer() {
        if (translationLayer != null) {
            applyLayerTouchMode()
            return
        }
        val layer = FrameLayout(context).apply {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            computeLayerFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowManager.addView(layer, params)
        translationLayer = layer
        translationParams = params
    }

    private fun applyLayerTouchMode() {
        val params = translationParams ?: return
        params.flags = computeLayerFlags()
        translationLayer?.let { layer -> runCatching { windowManager.updateViewLayout(layer, params) } }
    }

    private fun computeLayerFlags(): Int {
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        // 默认浏览模式保持触摸穿透；进入调整模式时允许文字块接收触摸事件。
        val shouldPassThrough = interactionMode == InteractionMode.BROWSE && !lastRenderConfig.touchEnabled
        return if (shouldPassThrough) {
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            base
        }
    }

    private fun computeFontSp(boxHeightPx: Float): Float {
        val metrics = context.resources.displayMetrics
        val fontScale = context.resources.configuration.fontScale.coerceAtLeast(0.85f)
        val scaledDensity = metrics.density * fontScale
        val sp = (boxHeightPx / scaledDensity) * 0.136f
        return sp.coerceIn(minFitSp, maxFitSp)
    }

    private fun fitTextToBox(
        text: String,
        templatePaint: TextPaint,
        widthPx: Int,
        heightPx: Int,
        maxLines: Int,
        minSp: Float,
        maxSp: Float
    ): TextFitResult {
        if (text.isBlank()) {
            return TextFitResult(maxSp, truncated = false, lineSpacingMultiplier = 1f)
        }

        val spacingCandidates = listOf(0.98f, 0.94f, 0.9f, 0.87f)
        var bestSize = minSp
        var bestSpacing = spacingCandidates.last()
        var foundFit = false

        spacingCandidates.forEach { spacing ->
            var low = minSp
            var high = maxSp
            var localBest = minSp
            repeat(6) {
                val mid = (low + high) / 2f
                if (fitsText(text, templatePaint, widthPx, heightPx, maxLines, mid, spacing)) {
                    localBest = mid
                    low = mid
                } else {
                    high = mid
                }
            }

            val refineCandidates = listOf(localBest + 0.5f, localBest + 0.25f)
            var refined = localBest
            for (candidate in refineCandidates) {
                if (
                    candidate <= maxSp &&
                    fitsText(text, templatePaint, widthPx, heightPx, maxLines, candidate, spacing)
                ) {
                    refined = candidate
                }
            }

            if (fitsText(text, templatePaint, widthPx, heightPx, maxLines, refined, spacing)) {
                if (!foundFit || refined > bestSize || (refined == bestSize && spacing > bestSpacing)) {
                    foundFit = true
                    bestSize = refined
                    bestSpacing = spacing
                }
            }
        }

        if (!foundFit) {
            return TextFitResult(
                textSizeSp = minSp.coerceIn(minSp, maxSp),
                truncated = true,
                lineSpacingMultiplier = spacingCandidates.last()
            )
        }
        return TextFitResult(
            textSizeSp = bestSize.coerceIn(minSp, maxSp),
            truncated = false,
            lineSpacingMultiplier = bestSpacing
        )
    }

    private fun fitsText(
        text: String,
        templatePaint: TextPaint,
        widthPx: Int,
        heightPx: Int,
        maxLines: Int,
        textSizeSp: Float,
        lineSpacingMultiplier: Float
    ): Boolean {
        val paint = TextPaint(templatePaint).apply { textSize = spToPx(textSizeSp) }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
            .setIncludePad(false)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setLineSpacing(0f, lineSpacingMultiplier)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        if (layout.lineCount > maxLines) return false
        if (layout.height > heightPx.coerceAtLeast(1)) return false
        val lastLine = (layout.lineCount - 1).coerceAtLeast(0)
        return layout.getLineEnd(lastLine) >= text.length
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    private fun dpF(value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        )

    private fun spToPx(value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            context.resources.displayMetrics
        )

    private fun isNarrationBubble(bubble: Bubble): Boolean {
        return bubble.bgType.equals("none", ignoreCase = true) || bubble.w >= 75f
    }

    private fun isVerticalLayoutBubble(bubble: Bubble, text: String): Boolean {
        val layout = bubble.layout.lowercase()
        return when (layout) {
            "vertical" -> true
            "horizontal" -> false
            else -> isVerticalLayoutText(text)
        }
    }

    private fun normalizeVerticalLayoutText(text: String): String {
        val normalizedPunctuation = normalizeVerticalPunctuation(text)
        if (!isVerticalLayoutText(normalizedPunctuation)) {
            return normalizedPunctuation
        }
        return normalizedPunctuation.split('\n').joinToString(separator = "") { it.trim() }
    }

    private fun normalizeVerticalPunctuation(text: String): String {
        return text
            .replace('「', '﹁')
            .replace('」', '﹂')
            .replace('（', '﹙')
            .replace('）', '﹚')
    }

    private fun isVerticalLayoutText(text: String): Boolean {
        val lines = text
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return lines.size >= 3 && lines.all { it.length <= 2 }
    }

    private fun drawPatchText(
        canvas: Canvas,
        spec: PatchTextSpec
    ) {
        if (spec.rect.width() <= 1f || spec.rect.height() <= 1f) return
        val text = spec.text.trim()
        if (text.isBlank()) return
        if (spec.vertical) {
            drawVerticalPatchText(canvas, text, spec)
            return
        }

        val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = patchFillColor
            textSize = spToPx(spec.textSizeSp)
        }
        val strokePaint = TextPaint(fillPaint).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = max(dpF(0.55f), naturalStrokeWidth(spec.textSizeSp, spec.strokeDp))
            color = patchStrokeColor
        }
        val contentWidth = (spec.rect.width() - dpF(2f)).toInt().coerceAtLeast(1)
        val layoutFill = StaticLayout.Builder.obtain(text, 0, text.length, fillPaint, contentWidth)
            .setIncludePad(false)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, spec.lineSpacingMultiplier)
            .build()
        val layoutStroke = StaticLayout.Builder.obtain(text, 0, text.length, strokePaint, contentWidth)
            .setIncludePad(false)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, spec.lineSpacingMultiplier)
            .build()

        val contentRect = computeHorizontalTextRect(spec, layoutFill)
        val drawX = contentRect.left
        val drawY = contentRect.top
        canvas.save()
        canvas.translate(drawX, drawY)
        layoutStroke.draw(canvas)
        layoutFill.draw(canvas)
        canvas.restore()
    }

    private fun drawVerticalPatchText(
        canvas: Canvas,
        rawText: String,
        spec: PatchTextSpec
    ) {
        val text = normalizeVerticalPunctuation(rawText.replace("\n", ""))
        if (text.isBlank()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = patchFillColor
            textSize = spToPx(spec.textSizeSp)
            textAlign = Paint.Align.CENTER
        }
        val strokePaint = Paint(paint).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = max(dpF(0.55f), naturalStrokeWidth(spec.textSizeSp, spec.strokeDp))
            color = patchStrokeColor
        }
        val step = (paint.textSize * 1.03f).coerceAtLeast(1f)
        val rows = max(1, ((spec.rect.height() - dpF(4f)) / step).toInt())
        val cols = kotlin.math.ceil(text.length / rows.toFloat()).toInt().coerceAtLeast(1)
        val requiredW = cols * step
        val startX = spec.rect.left + ((spec.rect.width() - requiredW).coerceAtLeast(0f) / 2f) + requiredW - step / 2f
        val fm = paint.fontMetrics
        val baselineOffset = -(fm.ascent + fm.descent) / 2f

        var index = 0
        for (col in 0 until cols) {
            val x = startX - col * step
            for (row in 0 until rows) {
                if (index >= text.length) break
                val y = spec.rect.top + dpF(1f) + row * step + step / 2f + baselineOffset
                val ch = text[index].toString()
                canvas.drawText(ch, x, y, strokePaint)
                canvas.drawText(ch, x, y, paint)
                index++
            }
        }
    }

    private fun computeHorizontalTextRect(spec: PatchTextSpec, layout: StaticLayout): RectF {
        var maxLineWidth = 0f
        for (line in 0 until layout.lineCount) {
            maxLineWidth = max(maxLineWidth, layout.getLineWidth(line))
        }
        val width = (maxLineWidth + dpF(2f)).coerceAtMost(spec.rect.width()).coerceAtLeast(1f)
        val height = layout.height.toFloat().coerceAtLeast(1f)
        val left = spec.rect.left + ((spec.rect.width() - width) / 2f).coerceAtLeast(0f)
        val top = spec.rect.top + ((spec.rect.height() - height) / 2f).coerceAtLeast(0f)
        return RectF(left, top, left + width, top + height)
    }

    private fun computeVerticalTextRect(spec: PatchTextSpec): RectF {
        val text = normalizeVerticalPunctuation(spec.text.replace("\n", ""))
        if (text.isBlank()) return RectF(spec.rect)
        val textSizePx = spToPx(spec.textSizeSp)
        val step = (textSizePx * 1.03f).coerceAtLeast(1f)
        val rows = max(1, ((spec.rect.height() - dpF(2f)) / step).toInt())
        val cols = kotlin.math.ceil(text.length / rows.toFloat()).toInt().coerceAtLeast(1)
        val width = (cols * step).coerceAtMost(spec.rect.width()).coerceAtLeast(1f)
        val height = (min(rows, text.length) * step).coerceAtMost(spec.rect.height()).coerceAtLeast(1f)
        val left = spec.rect.left + ((spec.rect.width() - width) / 2f).coerceAtLeast(0f)
        val top = spec.rect.top + ((spec.rect.height() - height) / 2f).coerceAtLeast(0f)
        return RectF(left, top, left + width, top + height)
    }

    private fun computePatchContentRect(spec: PatchTextSpec): RectF {
        return if (spec.vertical) {
            computeVerticalTextRect(spec)
        } else {
            val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = patchFillColor
                textSize = spToPx(spec.textSizeSp)
            }
            val contentWidth = (spec.rect.width() - dpF(2f)).toInt().coerceAtLeast(1)
            val layout = StaticLayout.Builder.obtain(spec.text, 0, spec.text.length, fillPaint, contentWidth)
                .setIncludePad(false)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, spec.lineSpacingMultiplier)
                .build()
            computeHorizontalTextRect(spec, layout)
        }
    }

    private fun computePatchBackgroundRect(spec: PatchTextSpec): RectF {
        val textRect = computePatchContentRect(spec)
        val padX = dpF(2.4f)
        val padY = dpF(1.2f)
        return RectF(
            (textRect.left - padX).coerceAtLeast(spec.rect.left),
            (textRect.top - padY).coerceAtLeast(spec.rect.top),
            (textRect.right + padX).coerceAtMost(spec.rect.right),
            (textRect.bottom + padY).coerceAtMost(spec.rect.bottom)
        )
    }

    private fun naturalStrokeWidth(textSizeSp: Float, strokeDp: Float): Float {
        val adaptive = dpF(0.22f + (textSizeSp / 16f) * 0.18f)
        val base = dpF(strokeDp)
        return min(base, adaptive).coerceAtLeast(dpF(0.5f))
    }

    private fun placeWithLimitedAvoidance(
        desiredLeft: Float,
        desiredTop: Float,
        width: Float,
        height: Float,
        screenW: Float,
        screenH: Float,
        placed: MutableList<RectF>
    ): PlacementResult {
        val safeWidth = width.coerceIn(dp(24).toFloat(), screenW.coerceAtLeast(dp(24).toFloat()))
        val safeHeight = height.coerceIn(dp(20).toFloat(), screenH.coerceAtLeast(dp(20).toFloat()))
        val step = dpF(4f)
        val maxOffset = dpF(8f)
        val collisionGap = dpF(3f)
        val offsets = listOf(
            0f to 0f,
            0f to step,
            0f to -step,
            step to 0f,
            -step to 0f,
            step to step,
            -step to step,
            step to -step,
            -step to -step,
            0f to maxOffset,
            0f to -maxOffset,
            maxOffset to 0f,
            -maxOffset to 0f,
            maxOffset to maxOffset,
            -maxOffset to maxOffset,
            maxOffset to -maxOffset,
            -maxOffset to -maxOffset
        )

        var bestRect: RectF? = null
        var bestOverlap = Float.MAX_VALUE
        offsets.forEach { (dx, dy) ->
            val candidate = clampRect(
                left = desiredLeft + dx,
                top = desiredTop + dy,
                width = safeWidth,
                height = safeHeight,
                screenW = screenW,
                screenH = screenH
            )
            val overlap = overlapArea(candidate, placed, collisionGap)
            if (overlap <= 0f) {
                return PlacementResult(rect = candidate, forceSingleLine = false)
            }
            if (overlap < bestOverlap) {
                bestOverlap = overlap
                bestRect = candidate
            }
        }

        val fallbackRect = bestRect ?: clampRect(
            left = desiredLeft,
            top = desiredTop,
            width = safeWidth,
            height = safeHeight,
            screenW = screenW,
            screenH = screenH
        )
        return PlacementResult(rect = fallbackRect, forceSingleLine = true)
    }

    private fun estimateViewHeightPx(
        view: TextView,
        text: CharSequence,
        widthPx: Int,
        maxLines: Int
    ): Float {
        val contentWidth = (widthPx - view.totalPaddingLeft - view.totalPaddingRight).coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(view.paint), contentWidth)
            .setIncludePad(false)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines.coerceAtLeast(1))
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val lineCount = layout.lineCount.coerceAtLeast(1)
        val lastLine = (lineCount - 1).coerceAtLeast(0)
        val textHeight = layout.getLineBottom(lastLine) - layout.getLineTop(0)
        val paddedHeight = textHeight + view.totalPaddingTop + view.totalPaddingBottom
        return max(paddedHeight.toFloat(), dp(20).toFloat())
    }

    private fun clampRect(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        screenW: Float,
        screenH: Float
    ): RectF {
        val clampedLeft = left.coerceIn(0f, (screenW - width).coerceAtLeast(0f))
        val clampedTop = top.coerceIn(0f, (screenH - height).coerceAtLeast(0f))
        return RectF(clampedLeft, clampedTop, clampedLeft + width, clampedTop + height)
    }

    private fun overlapArea(candidate: RectF, placed: List<RectF>, gap: Float): Float {
        val expandedCandidate = RectF(
            candidate.left - gap,
            candidate.top - gap,
            candidate.right + gap,
            candidate.bottom + gap
        )
        var total = 0f
        placed.forEach { existing ->
            val expandedExisting = RectF(
                existing.left - gap,
                existing.top - gap,
                existing.right + gap,
                existing.bottom + gap
            )
            val interLeft = max(expandedCandidate.left, expandedExisting.left)
            val interTop = max(expandedCandidate.top, expandedExisting.top)
            val interRight = min(expandedCandidate.right, expandedExisting.right)
            val interBottom = min(expandedCandidate.bottom, expandedExisting.bottom)
            if (interRight > interLeft && interBottom > interTop) {
                total += (interRight - interLeft) * (interBottom - interTop)
            }
        }
        return total
    }

    private fun ensureStatusView() {
        if (floatingStatusView != null) return
        val view = TextView(context).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#A6000000"))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        windowManager.addView(view, params)
        floatingStatusView = view
        floatingStatusParams = params
        updateStatusPosition()
    }

    private fun showStatus(message: String) {
        ensureStatusView()
        val view = floatingStatusView ?: return
        view.text = message
        view.visibility = View.VISIBLE
        updateStatusPosition()
    }

    private fun hideStatus() {
        floatingStatusView?.visibility = View.GONE
    }

    private fun updateStatusPosition() {
        val bubbleParams = floatingParams ?: return
        val params = floatingStatusParams ?: return
        params.x = bubbleParams.x + dp(floatingSizeDp + 6)
        params.y = bubbleParams.y + dp(8)
        floatingStatusView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    private fun applyFloatingButtonColor(color: Int) {
        val button = floatingButton ?: return
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun clampFloatingPosition() {
        val params = floatingParams ?: return
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val buttonSize = dp(floatingSizeDp)
        val minX = if (floatingCollapsed) (-buttonSize + dp(floatingPeekDp)) else 0
        val maxX = if (floatingCollapsed) {
            (screenW - dp(floatingPeekDp)).coerceAtLeast(minX)
        } else {
            (screenW - buttonSize).coerceAtLeast(0)
        }
        val oldX = params.x
        val oldY = params.y
        params.x = params.x.coerceIn(minX, maxX)
        params.y = params.y.coerceIn(0, (screenH - buttonSize).coerceAtLeast(0))
        if (params.x != oldX || params.y != oldY) {
            Log.d("TranslationPerf", "floating_position_clamped x=${params.x} y=${params.y}")
            floatingButton?.let { runCatching { windowManager.updateViewLayout(it, params) } }
            updateStatusPosition()
        }
    }

    private fun settleFloatingAfterDrag() {
        val params = floatingParams ?: return
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val buttonSize = dp(floatingSizeDp)
        params.x = params.x.coerceIn(0, (screenW - buttonSize).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenH - buttonSize).coerceAtLeast(0))

        val leftDistance = params.x
        val rightDistance = screenW - (params.x + buttonSize)
        val shouldDock = min(leftDistance, rightDistance) <= dp(floatingDockSnapThresholdDp)
        if (shouldDock) {
            dockSide = if (leftDistance <= rightDistance) DockSide.LEFT else DockSide.RIGHT
            setFloatingCollapsed(true)
        } else {
            floatingCollapsed = false
            lastExpandedX = params.x
            lastExpandedY = params.y
            dockSide = if (params.x + buttonSize / 2f < screenW / 2f) DockSide.LEFT else DockSide.RIGHT
            floatingButton?.let { runCatching { windowManager.updateViewLayout(it, params) } }
            updateStatusPosition()
            Log.d("TranslationPerf", "floating_dock_state=expanded side=$dockSide")
        }
    }

    private fun startPulse() {
        if (floatingPulseAnimator?.isRunning == true) return
        val button = floatingButton ?: return
        floatingPulseAnimator = ObjectAnimator.ofFloat(button, View.ALPHA, 1f, 0.55f, 1f).apply {
            duration = 900L
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        floatingPulseAnimator?.cancel()
        floatingPulseAnimator = null
        floatingButton?.alpha = 1f
    }

    private fun showExpandedCard(text: String, anchor: Rect) {
        hideExpandedCard()

        val metrics = context.resources.displayMetrics
        val maxCardWidth = (metrics.widthPixels * 0.72f).toInt().coerceAtLeast(dp(160))
        val view = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#E6202020"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxWidth = maxCardWidth
            isClickable = true
            isFocusable = true
            setOnClickListener { hideExpandedCard() }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        view.measure(
            View.MeasureSpec.makeMeasureSpec(maxCardWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val cardW = view.measuredWidth.coerceAtLeast(dp(120))
        val cardH = view.measuredHeight.coerceAtLeast(dp(64))
        val targetX = (anchor.centerX() - cardW / 2).coerceIn(dp(8), metrics.widthPixels - cardW - dp(8))
        val preferTop = anchor.top - cardH - dp(8)
        val targetY = if (preferTop > dp(16)) preferTop else (anchor.bottom + dp(8)).coerceAtMost(metrics.heightPixels - cardH - dp(16))
        params.x = targetX
        params.y = targetY

        windowManager.addView(view, params)
        expandedCardView = view
        expandedCardParams = params
    }

    private fun hideExpandedCard() {
        expandedCardView?.let { runCatching { windowManager.removeView(it) } }
        expandedCardView = null
        expandedCardParams = null
        bubbleStates.values.forEach { it.expanded = false }
    }

    private fun currentViewRect(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun scheduleAdjustTimeout() {
        cancelAdjustTimeout()
        val runnable = Runnable {
            setInteractionMode(InteractionMode.BROWSE)
            setFloatingState(FloatingState.IDLE, null)
        }
        adjustTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, adjustDurationMs)
    }

    private fun cancelAdjustTimeout() {
        adjustTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        adjustTimeoutRunnable = null
    }

    private inner class FloatingButtonTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var downTimeMs = 0L
        private var isDragging = false
        private var longPressTriggered = false
        private var activePointerId = MotionEvent.INVALID_POINTER_ID
        private var longPressRunnable: Runnable? = null

        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            val params = floatingParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = event.getPointerId(0)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    downTimeMs = SystemClock.uptimeMillis()
                    isDragging = false
                    longPressTriggered = false
                    scheduleLongPress(v)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (activePointerId == MotionEvent.INVALID_POINTER_ID) return true
                    val dxTotal = event.rawX - downRawX
                    val dyTotal = event.rawY - downRawY
                    if (!isDragging && (kotlin.math.abs(dxTotal) > touchSlop || kotlin.math.abs(dyTotal) > touchSlop)) {
                        isDragging = true
                        cancelLongPress()
                    }
                    if (isDragging) {
                        val dx = (event.rawX - lastRawX).toInt()
                        val dy = (event.rawY - lastRawY).toInt()
                        params.x += dx
                        params.y += dy
                        val buttonSize = dp(floatingSizeDp)
                        val screenW = context.resources.displayMetrics.widthPixels
                        val screenH = context.resources.displayMetrics.heightPixels
                        params.x = params.x.coerceIn(0, (screenW - buttonSize).coerceAtLeast(0))
                        params.y = params.y.coerceIn(0, (screenH - buttonSize).coerceAtLeast(0))
                        windowManager.updateViewLayout(floatingButton, params)
                        updateStatusPosition()
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    cancelLongPress()
                    val elapsed = SystemClock.uptimeMillis() - downTimeMs
                    val shouldClick = !isDragging && !longPressTriggered && elapsed <= tapTimeoutMs
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    if (isDragging) {
                        settleFloatingAfterDrag()
                        return true
                    }
                    if (shouldClick) {
                        v?.performClick()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    if (isDragging) {
                        settleFloatingAfterDrag()
                    }
                    return true
                }
            }
            return false
        }

        private fun scheduleLongPress(target: View?) {
            cancelLongPress()
            val runnable = Runnable {
                if (isDragging) return@Runnable
                longPressTriggered = true
                if (interactionMode != InteractionMode.ADJUST && bubbleStates.isNotEmpty()) {
                    setInteractionMode(InteractionMode.ADJUST)
                    showStatus("调整模式：可拖动文字")
                } else {
                    setInteractionMode(InteractionMode.BROWSE)
                    resetFloatingPosition()
                    showStatus("已复位")
                }
                setFloatingState(FloatingState.IDLE, null)
                target?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
            longPressRunnable = runnable
            mainHandler.postDelayed(runnable, longPressTimeoutMs)
        }

        private fun cancelLongPress() {
            longPressRunnable?.let { mainHandler.removeCallbacks(it) }
            longPressRunnable = null
        }
    }

    private inner class BubbleDragTouchListener(
        private val state: BubbleRenderState
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var downTimeMs = 0L
        private var isDragging = false
        private var isScaling = false
        private var initialDistance = 0f
        private var initialFontScale = 1f
        private var initialStrokeScale = 1f

        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            if (interactionMode != InteractionMode.ADJUST) return false
            val view = v ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    downTimeMs = SystemClock.uptimeMillis()
                    isDragging = false
                    isScaling = false
                    return true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        isScaling = true
                        initialDistance = pointerDistance(event)
                        initialFontScale = state.fontScale
                        initialStrokeScale = state.strokeScale
                        hideExpandedCard()
                    }
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isScaling && event.pointerCount >= 2) {
                        val distance = pointerDistance(event)
                        if (initialDistance > 1f) {
                            val ratio = (distance / initialDistance).coerceIn(0.75f, 1.35f)
                            state.fontScale = (initialFontScale * ratio).coerceIn(0.7f, 1.5f)
                            state.strokeScale = (initialStrokeScale * ratio).coerceIn(0.6f, 1.6f)
                            applyBubbleStyle(view as TextView, state)
                            upsertPatchTextSpec(state)
                            refreshPatchTextLayer()
                        }
                        return true
                    }
                    val totalDx = event.rawX - downRawX
                    val totalDy = event.rawY - downRawY
                    if (!isDragging && (kotlin.math.abs(totalDx) > touchSlop || kotlin.math.abs(totalDy) > touchSlop)) {
                        isDragging = true
                        hideExpandedCard()
                    }
                    if (isDragging) {
                        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return true
                        val parent = translationLayer ?: return true
                        val dx = (event.rawX - lastRawX).toInt()
                        val dy = (event.rawY - lastRawY).toInt()
                        lp.leftMargin = (lp.leftMargin + dx).coerceIn(0, (parent.width - view.width).coerceAtLeast(0))
                        lp.topMargin = (lp.topMargin + dy).coerceIn(0, (parent.height - view.height).coerceAtLeast(0))
                        view.layoutParams = lp
                        state.offsetX += dx
                        state.offsetY += dy
                        upsertPatchTextSpec(state)
                        refreshPatchTextLayer()
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val elapsed = SystemClock.uptimeMillis() - downTimeMs
                    persistAdjustment(state)
                    upsertPatchTextSpec(state)
                    refreshPatchTextLayer()
                    if (!isDragging && !isScaling && elapsed <= tapTimeoutMs) {
                        view.performClick()
                    }
                    isScaling = false
                    return true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount <= 2) {
                        isScaling = false
                    }
                    persistAdjustment(state)
                    upsertPatchTextSpec(state)
                    refreshPatchTextLayer()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isScaling = false
                    persistAdjustment(state)
                    upsertPatchTextSpec(state)
                    refreshPatchTextLayer()
                    return true
                }
            }
            return false
        }

        private fun pointerDistance(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 0f
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
    }

    private inner class ErasePatchLayerView(context: Context) : View(context) {
        private val patchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        private var patches: List<ErasePatch> = emptyList()
        private var patchTexts: List<PatchTextSpec> = emptyList()

        fun setPatches(value: List<ErasePatch>) {
            patches = value
            invalidate()
        }

        fun setPatchTexts(value: List<PatchTextSpec>) {
            patchTexts = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val textSpecsByBubbleId = patchTexts.associateBy { it.bubbleId }
            patches.forEach { patch ->
                if (patch.bitmap.isRecycled) return@forEach
                if (patch.renderRect.width() <= 0f || patch.renderRect.height() <= 0f) return@forEach
                val spec = textSpecsByBubbleId[patch.bubbleId]
                if (spec == null) {
                    canvas.drawBitmap(patch.bitmap, null, patch.renderRect, patchPaint)
                    return@forEach
                }
                val dstRect = computePatchBackgroundRect(spec)
                val renderRect = patch.renderRect
                val width = renderRect.width().coerceAtLeast(1f)
                val height = renderRect.height().coerceAtLeast(1f)
                val leftRatio = ((dstRect.left - renderRect.left) / width).coerceIn(0f, 1f)
                val topRatio = ((dstRect.top - renderRect.top) / height).coerceIn(0f, 1f)
                val rightRatio = ((dstRect.right - renderRect.left) / width).coerceIn(0f, 1f)
                val bottomRatio = ((dstRect.bottom - renderRect.top) / height).coerceIn(0f, 1f)
                val srcRect = Rect(
                    (leftRatio * patch.bitmap.width).toInt().coerceIn(0, patch.bitmap.width - 1),
                    (topRatio * patch.bitmap.height).toInt().coerceIn(0, patch.bitmap.height - 1),
                    (rightRatio * patch.bitmap.width).toInt().coerceIn(1, patch.bitmap.width),
                    (bottomRatio * patch.bitmap.height).toInt().coerceIn(1, patch.bitmap.height)
                )
                canvas.drawBitmap(patch.bitmap, srcRect, dstRect, patchPaint)
            }
            patchTexts.forEach { spec ->
                drawPatchText(canvas, spec)
            }
        }
    }

    private data class PlacementResult(
        val rect: RectF,
        val forceSingleLine: Boolean
    )

    private data class TextFitResult(
        val textSizeSp: Float,
        val truncated: Boolean,
        val lineSpacingMultiplier: Float
    )

    private data class PreparedBubble(
        val key: String,
        val view: TextView,
        val layoutParams: FrameLayout.LayoutParams,
        val centerY: Float
    )

    private data class PatchTextSpec(
        val key: String,
        val bubbleId: Int,
        val text: String,
        val rect: RectF,
        val vertical: Boolean,
        val textSizeSp: Float,
        val strokeDp: Float,
        val lineSpacingMultiplier: Float
    )

    private enum class DockSide {
        LEFT,
        RIGHT
    }

    private companion object {
        const val TAG_KEY_STATE = 0x44A1001
        const val LAYOUT_BUDGET_MS = 120L
        const val FRAME_BUDGET_MS = 16L
    }
}


