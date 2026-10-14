package com.manga.translator.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.manga.translator.capture.ScreenCaptureController
import com.manga.translator.overlay.FloatingState
import com.manga.translator.overlay.InteractionMode
import com.manga.translator.overlay.OverlayController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.io.StringWriter

class CaptureForegroundService : Service() {
    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val homePackages by lazy { resolveHomePackages() }
    private val captureSessionStateStore by lazy { ServiceLocator.captureSessionStateStore }

    private var captureController: ScreenCaptureController? = null
    private var overlayController: OverlayController? = null
    private var orchestrator: TranslateOrchestrator? = null
    private var translateJob: Job? = null
    private var captureAvailabilityJob: Job? = null
    private var accessibilityMonitorJob: Job? = null

    /**
     * OCR 模型加载状态（2026-09-15 新增，解决「第一次用点不动」）：
     * 加载期间用户按下悬浮球时不再静默吞掉——用 [pendingTapAfterOcrLoad] 记住这一下，
     * 加载结束后由 [finishOcrLoad] 自动补做，用户不需要重新点。
     */
    private var ocrLoadInFlight = false
    private var pendingTapAfterOcrLoad = false

    /** 本次 OCR 加载是否把悬浮球置灰过；只有置灰过才需要在收尾时恢复，避免误清「翻译中」状态。 */
    private var ocrLoadBlockedUi = false

    private var captureTimeoutStreak = 0
    private var projectionUnavailable = false
    private var lastUnavailableToastAt = 0L
    private var activeContentPackage: String? = null
    private var currentBaselineHash: Long? = null

    private var systemUiReceiverRegistered = false
    private val systemUiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
            val reason = intent.getStringExtra(SYSTEM_DIALOG_REASON).orEmpty()
            if (reason == REASON_HOME_KEY) {
                diag("system_dialogs reason=$reason fallback_clear")
                clearOverlayForReason("home")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        diag("onStartCommand action=${intent?.action} startId=$startId")
        val fullyStarted = captureController != null && overlayController != null && orchestrator != null
        if (intent?.action == null && !fullyStarted) {
            diag("onStartCommand missing_action_without_session stop_self")
            updateCaptureSessionState(CaptureSessionState.STOPPED, "service_restart_without_projection")
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                diag("received stop action")
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                diag("start action resultCode=$resultCode dataNull=${data == null}")
                if (resultCode == 0 || data == null) {
                    if (fullyStarted) {
                        diag("invalid media projection grant ignored: session already running")
                        updateCaptureSessionState(CaptureSessionState.ACTIVE, "duplicate_start_reused_session")
                        return START_STICKY
                    }
                    diag("invalid media projection grant, stop self")
                    updateCaptureSessionState(CaptureSessionState.STOPPED, "invalid_projection_grant")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (fullyStarted) {
                    if (!projectionUnavailable) {
                        diag("start action ignored: session already running")
                        updateCaptureSessionState(CaptureSessionState.ACTIVE, "duplicate_start_reused_session")
                        return START_STICKY
                    }
                    diag("start action forcing restart from projection_unavailable")
                }
                if (captureController != null || overlayController != null || orchestrator != null) {
                    resetActiveSession("start_action_recover")
                }

                startForegroundCompat()
                runCatching {
                    diag("calling startOverlayFlow")
                    startOverlayFlow(resultCode, data)
                    diag("startOverlayFlow success")
                }.onFailure { err ->
                    diag("startOverlayFlow failed: ${err.javaClass.simpleName}: ${err.message}\n${stack(err)}")
                    updateCaptureSessionState(CaptureSessionState.STOPPED, "start_overlay_failed")
                    Toast.makeText(
                        this,
                        "悬浮窗创建失败: ${err.message ?: "未知错误"}",
                        Toast.LENGTH_SHORT
                    ).show()
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private fun startOverlayFlow(resultCode: Int, data: Intent) {
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(displayMetrics)

        captureController = ServiceLocator.createCaptureController().also {
            it.projectionStopListener = projectionStopListener@{ externalStop ->
                diag("media_projection_callback externalStop=$externalStop projectionUnavailable=$projectionUnavailable")
                if (!externalStop) {
                    return@projectionStopListener
                }
                serviceScope.launch {
                    enterProjectionUnavailable(reason = "projection_stopped_callback")
                }
            }
            it.start(resultCode, data, displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.densityDpi)
        }
        overlayController = ServiceLocator.createOverlayController()
        // 2026-08-27 防御：启动时强制清除可能残留的译文层/锁定层，
        // 避免"上次的译文层突然出现在屏幕上且屏幕被锁定层拦截"。
        overlayController?.unlockTranslationLayer()
        overlayController?.clearTranslationLayer()
        orchestrator = TranslateOrchestrator(captureController!!, overlayController!!)
        ensureSystemUiReceiver()
        ensureAccessibilityMonitor()

        captureTimeoutStreak = 0
        projectionUnavailable = false
        activeContentPackage = null
        currentBaselineHash = null
        stopCaptureAvailabilityMonitor()

        overlayController?.setInteractionMode(InteractionMode.BROWSE)
        overlayController?.setFloatingVisible(true)
        overlayController?.setFloatingState(FloatingState.IDLE, null)
        startCaptureAvailabilityMonitor()
        updateCaptureSessionState(CaptureSessionState.ACTIVE, "start_overlay_flow_success")
        prewarmOcrModel()
        prewarmTranslateModel()

        overlayController?.showFloatingButton { handleFloatingTap() }
    }

    /**
     * 悬浮球点击的完整处理流程。
     *
     * 独立成函数（而不是内联在 [showFloatingButton] 的 lambda 里）是为了支持「OCR 模型加载完成后
     * 自动补做这次点击」：加载期间用户按下的那一下会被 [pendingTapAfterOcrLoad] 记住，
     * 加载结束后重新调用本函数走一遍正常流程，用户不必再点第二次。
     */
    private fun handleFloatingTap() {
        if (translateJob?.isActive == true) {
            // 诊断（2026-10-14）：这一条以前是**静默 return** —— 用户报「悬浮球点不动」时，
            // 日志里连一行都没有，没法区分「没收到点击」和「收到了但被这里挡掉」。
            Log.d(
                "TranslationPerf",
                "floating_tap_busy overlay_active=${overlayController?.hasActiveTranslationLayer() == true}"
            )
            return
        }
        // 2026-08-27 新增：译文层显示/锁定时点击悬浮球 = 恢复原文（清除译文层），
        // 不触发新翻译，避免出现"应该点不动/恢复原文，结果却显示翻译中"。
        if (overlayController?.hasActiveTranslationLayer() == true) {
            Log.d("TranslationPerf", "floating_tap_restore_original")
            overlayController?.unlockTranslationLayer()
            overlayController?.clearTranslationLayer()
            overlayController?.setFloatingState(FloatingState.IDLE, null)
            clearOverlayTracking()
            return
        }
        overlayController?.unlockTranslationLayer()

        val settings = ServiceLocator.settingsRepository.getSettings()
        if (!settings.enabled) {
            Toast.makeText(this, "翻译已关闭", Toast.LENGTH_SHORT).show()
            overlayController?.clearTranslationLayer()
            return
        }
        if (projectionUnavailable) {
            Log.d("TranslationPerf", "skipped=projection_unavailable")
            showUnavailableToast("请切回已投射应用")
            return
        }

        // OCR 模型未就绪：明确告知 + 记住这次点击，加载完成后自动补做（用户不必再点一次）。
        if (!ServiceLocator.localTextDetector.isReady(settings.sourceLanguage)) {
            requestOcrLoadThenReplayTap(settings.sourceLanguage)
            return
        }

        overlayController?.setFloatingVisible(true)
        overlayController?.setInteractionMode(InteractionMode.BROWSE)
        val current = orchestrator ?: return
        translateJob = serviceScope.launch {
            var keepDoneStateUntilTap = false
            try {
                overlayController?.setFloatingState(FloatingState.CAPTURING, "截图中")
                val outcome = current.runOnce { state ->
                    when (state) {
                        FloatingState.MODEL_LOADING ->
                            overlayController?.setFloatingState(FloatingState.MODEL_LOADING, "正在下载翻译模型")
                        FloatingState.TRANSLATING ->
                            overlayController?.setFloatingState(FloatingState.TRANSLATING, "翻译中")
                        else -> {}
                    }
                }
                onCaptureSuccess()

                if (outcome.hasOverlay) {
                    val doneText = when {
                        outcome.cacheTotalCount > 0 ->
                            "完成·缓存${outcome.cacheHitCount}/${outcome.cacheTotalCount}"
                        else -> outcome.warningMessage ?: "完成"
                    }
                    overlayController?.setFloatingState(FloatingState.DONE, doneText)
                    outcome.warningMessage?.let {
                        Toast.makeText(this@CaptureForegroundService, it, Toast.LENGTH_SHORT).show()
                    }
                    activeContentPackage = resolveActiveContentPackage()
                    currentBaselineHash = outcome.baselineHash
                    keepDoneStateUntilTap = true
                    overlayController?.lockTranslationUntilTap {
                        clearOverlayForReason("tap_restore")
                        overlayController?.setFloatingState(FloatingState.IDLE, null)
                    }
                } else {
                    overlayController?.setFloatingState(FloatingState.DONE, "无文本")
                    clearOverlayTracking()
                    overlayController?.unlockTranslationLayer()
                }
                delay(1200)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Throwable) {
                diag("runOnce failed: ${err.javaClass.simpleName}: ${err.message}")
                Log.d("TranslationPerf", "capture_failure_type=${err.javaClass.simpleName}")
                overlayController?.unlockTranslationLayer()
                clearOverlayForReason("translate_failed")
                if (isProjectionExpiredError(err)) {
                    enterProjectionUnavailable(reason = "projection_expired")
                    Toast.makeText(
                        this@CaptureForegroundService,
                        "请重新启动服务并授权录屏",
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (isCaptureTimeoutError(err)) {
                    onCaptureTimeoutFailure()
                } else {
                    captureTimeoutStreak = 0
                    overlayController?.setFloatingState(FloatingState.ERROR, "失败")
                    Toast.makeText(this@CaptureForegroundService, err.message ?: "翻译失败", Toast.LENGTH_SHORT).show()
                }
                delay(1200)
            } finally {
                if (!projectionUnavailable && !keepDoneStateUntilTap) {
                    overlayController?.setFloatingState(FloatingState.IDLE, null)
                }
            }
        }.also { launched ->
            launched.invokeOnCompletion {
                if (translateJob === launched) {
                    translateJob = null
                }
            }
        }
    }

    /**
     * OCR 模型尚未就绪时被点击的处理。
     *
     * 2026-09-15 新增（用户反馈「第一次用的时候一直点不动」）：
     * 旧逻辑是 `setFloatingEnabled(false)` + `return`，而 `OverlayController` 的点击回调里
     * `if (!floatingEnabled) return` 会把点击**静默吞掉**（悬浮球是独立 overlay 窗口，
     * 点击也不会穿透给底下的漫画 App），用户看到的就是「点了毫无反应」，分不清是坏了还是在忙。
     *
     * 现在：明确弹提示说明在加载，并记住这次点击，加载完成后自动补做。
     * 重复点击只提示、不重复排队。
     */
    private fun requestOcrLoadThenReplayTap(sourceLanguage: String) {
        val detector = ServiceLocator.localTextDetector
        // 竞态兜底：可能恰好在本次点击前加载完成，直接放行走正常流程，不打扰用户。
        if (detector.isReady(sourceLanguage)) {
            handleFloatingTap()
            return
        }
        // 无论加载是本函数发起的还是服务启动预热发起的，都要把这次点击记下来等待补做。
        pendingTapAfterOcrLoad = true
        if (ocrLoadInFlight) {
            // 加载已在跑（多半是服务启动预热）：只提示，等它结束自动补做，不重复排队。
            Toast.makeText(this, "OCR 模型加载中，加载完成后会自动翻译", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d("TranslationPerf", "model_loading_prewarm lang=$sourceLanguage")
        Toast.makeText(this, "OCR 模型加载中，加载完成后会自动翻译", Toast.LENGTH_SHORT).show()
        overlayController?.setFloatingEnabled(false)
        overlayController?.setFloatingState(FloatingState.MODEL_LOADING, "OCR模型加载中")
        ocrLoadInFlight = true
        ocrLoadBlockedUi = true
        serviceScope.launch {
            withContext(Dispatchers.IO) { detector.prewarm(sourceLanguage) }
            Log.d("TranslationPerf", "model_prewarm_done lang=$sourceLanguage")
            finishOcrLoad(sourceLanguage)
        }
    }

    /**
     * OCR 加载收尾：恢复悬浮球（仅当本次加载禁用过它），并在用户加载期间点过球时自动补做那次翻译。
     * 启动预热（[prewarmOcrModel]）与点击兜底（[requestOcrLoadThenReplayTap]）共用。
     *
     * 注意：只有 [ocrLoadBlockedUi] 为真时才动悬浮球状态——否则启动预热完成会把
     * 正在进行的「截图中/翻译中」指示误清成 IDLE。
     */
    private fun finishOcrLoad(sourceLanguage: String) {
        ocrLoadInFlight = false
        val ready = ServiceLocator.localTextDetector.isReady(sourceLanguage)
        if (ocrLoadBlockedUi) {
            ocrLoadBlockedUi = false
            overlayController?.setFloatingEnabled(true)
            overlayController?.setFloatingState(FloatingState.IDLE, null)
        }
        val replay = pendingTapAfterOcrLoad
        pendingTapAfterOcrLoad = false
        if (!replay) return
        if (!ready) {
            // 加载失败（assets 缺失且无网络等）：给出明确失败反馈，不再排队，避免死循环。
            Log.w("TranslationPerf", "model_load_failed_no_replay lang=$sourceLanguage")
            Toast.makeText(this, "OCR 模型加载失败，请检查网络后重试", Toast.LENGTH_LONG).show()
            return
        }
        Log.d("TranslationPerf", "model_load_replay_tap lang=$sourceLanguage")
        handleFloatingTap()
    }

    /** 服务启动后后台预热 OCR 模型，减少首次点击等待（模型下载/加载在 IO 线程）。 */
    private fun prewarmOcrModel() {
        val settings = ServiceLocator.settingsRepository.getSettings()
        if (!settings.enabled) return
        val detector = ServiceLocator.localTextDetector
        if (detector.isReady(settings.sourceLanguage)) return
        Log.d("TranslationPerf", "model_prewarm_start lang=${settings.sourceLanguage}")
        ocrLoadInFlight = true
        serviceScope.launch {
            withContext(Dispatchers.IO) { detector.prewarm(settings.sourceLanguage) }
            Log.d("TranslationPerf", "model_prewarm_done lang=${settings.sourceLanguage}")
            // 预热期间用户若已点过球，这里会顺手补做那次点击。
            finishOcrLoad(settings.sourceLanguage)
        }
    }

    /** 服务启动后后台预热本地翻译模型（ML Kit），首次点击无需联网下载，减少等待。 */
    private fun prewarmTranslateModel() {
        val settings = ServiceLocator.settingsRepository.getSettings()
        if (!settings.enabled) return
        val engine = com.manga.translator.store.TranslateEngine.fromId(settings.translateEngine)
        if (engine != com.manga.translator.store.TranslateEngine.ML_KIT) return
        val translator = ServiceLocator.localTranslator
        if (!translator.isSupported(settings.sourceLanguage, settings.targetLanguage)) return
        Log.d("TranslationPerf", "translate_model_prewarm_start pair=${settings.sourceLanguage}->${settings.targetLanguage}")
        serviceScope.launch {
            withContext(Dispatchers.IO) {
                translator.prewarm(settings.sourceLanguage, settings.targetLanguage)
            }
            Log.d("TranslationPerf", "translate_model_prewarm_done pair=${settings.sourceLanguage}->${settings.targetLanguage}")
        }
    }

    private fun resetActiveSession(reason: String) {
        diag("reset_active_session reason=$reason")
        translateJob?.cancel()
        translateJob = null
        stopCaptureAvailabilityMonitor()

        overlayController?.setInteractionMode(InteractionMode.BROWSE)
        overlayController?.clearTranslationLayer()
        overlayController?.hideAll()
        overlayController = null

        captureController?.stop()
        captureController = null
        orchestrator = null

        projectionUnavailable = false
        captureTimeoutStreak = 0
        clearOverlayTracking()
    }

    private fun onCaptureSuccess() {
        captureTimeoutStreak = 0
        if (projectionUnavailable) {
            projectionUnavailable = false
            overlayController?.setFloatingVisible(true)
            overlayController?.setFloatingState(FloatingState.IDLE, null)
            Log.d("TranslationPerf", "capture_unavailable_exit reason=recovered")
            diag("capture_recover_success")
        }
        updateCaptureSessionState(CaptureSessionState.ACTIVE, "capture_success")
    }

    private fun onCaptureTimeoutFailure() {
        captureTimeoutStreak += 1
        diag("capture_timeout_attempt streak=$captureTimeoutStreak")
        if (captureTimeoutStreak >= CAPTURE_TIMEOUT_STREAK_THRESHOLD) {
            // timeout 不等同于授权失效：仅做软降级提示并重置计数，避免误触发重新授权状态。
            diag("capture_timeout_threshold_reached action=soft_fail")
            captureTimeoutStreak = 0
            overlayController?.setFloatingState(FloatingState.ERROR, "截图超时")
            Toast.makeText(this, "截图超时，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        overlayController?.setFloatingState(FloatingState.ERROR, "截图超时")
        Toast.makeText(this, "截图超时，请保持在已投射应用内", Toast.LENGTH_SHORT).show()
    }

    private fun enterProjectionUnavailable(reason: String) {
        val requiresReauth = reason.contains("projection_expired")
            || reason.contains("projection_stopped")
        if (projectionUnavailable && captureSessionStateStore.getState() == CaptureSessionState.REAUTH_REQUIRED) {
            return
        }
        projectionUnavailable = true
        captureTimeoutStreak = 0
        clearOverlayForReason(reason)
        // 投影一旦失效（过期或被停止）就无法自行恢复：本项目没有"重新获取
        // MediaProjection token"的路径（那需要 App 的 Activity 在 MainActivity
        // 里重新发起 createScreenCaptureIntent()）。isCaptureAlive() 依赖
        // projectionStopped=false 且 mediaProjection 非空，onStop 后永不复原，
        // 因此 background_capture_recovered 实际上触发不到。
        // 与其留一个点了没反应、toast 还被限流的悬浮球，不如明确停止服务，
        // 让用户重开 App 走重新授权流程（onDestroy 会把状态存为 REAUTH_REQUIRED）。
        updateCaptureSessionState(
            state = if (requiresReauth) CaptureSessionState.REAUTH_REQUIRED else CaptureSessionState.UNAVAILABLE,
            reason = "projection_unavailable:$reason"
        )
        Log.d("TranslationPerf", "capture_unavailable_auto_stop reason=$reason requiresReauth=$requiresReauth")
        diag("auto_stop_service reason=$reason")
        Toast.makeText(
            this,
            if (requiresReauth) "录屏授权已失效，服务已停止，请重新打开 App 重新授权"
            else "截图暂不可用，服务已停止，请重新打开 App 启动",
            Toast.LENGTH_LONG
        ).show()
        stopSelf()
    }

    private fun startCaptureAvailabilityMonitor() {
        if (captureAvailabilityJob?.isActive == true) return
        captureAvailabilityJob = serviceScope.launch {
            while (isActive) {
                delay(CAPTURE_HEALTH_CHECK_INTERVAL_MS)
                if (translateJob?.isActive == true) {
                    continue
                }
                val controller = captureController ?: continue
                if (controller.isCaptureAlive()) {
                    if (projectionUnavailable) {
                        projectionUnavailable = false
                        captureTimeoutStreak = 0
                        overlayController?.setFloatingVisible(true)
                        overlayController?.setFloatingState(FloatingState.IDLE, null)
                        Log.d("TranslationPerf", "capture_unavailable_exit reason=background_recovered")
                        diag("capture_recover_success")
                        updateCaptureSessionState(CaptureSessionState.ACTIVE, "background_capture_recovered")
                    }
                    continue
                }
                if (!projectionUnavailable && (controller.hasProjectionStopped() || controller.getLastCaptureFailureType() == com.manga.translator.capture.CaptureFailureType.PROJECTION_EXPIRED)) {
                    diag(
                        "capture_monitor_event type=projection_state package=${AccessibilityMonitor.currentForegroundPackage} " +
                            "projectionStopped=${controller.hasProjectionStopped()} lastFailure=${controller.getLastCaptureFailureType()}"
                    )
                    enterProjectionUnavailable(reason = if (controller.hasProjectionStopped()) "projection_stopped" else "projection_expired_bg")
                }
            }
        }.also { launched ->
            launched.invokeOnCompletion {
                if (captureAvailabilityJob === launched) {
                    captureAvailabilityJob = null
                }
            }
        }
    }

    private fun stopCaptureAvailabilityMonitor() {
        captureAvailabilityJob?.cancel()
        captureAvailabilityJob = null
    }

    private fun showUnavailableToast(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUnavailableToastAt < UNAVAILABLE_TOAST_THROTTLE_MS) return
        lastUnavailableToastAt = now
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun isCaptureTimeoutError(err: Throwable): Boolean {
        val message = err.message.orEmpty().lowercase()
        return message.contains("screen capture timeout") || message.contains("capture timeout") || message.contains("timeout")
    }

    private fun isProjectionExpiredError(err: Throwable): Boolean {
        var cursor: Throwable? = err
        while (cursor != null) {
            val message = cursor.message.orEmpty().lowercase()
            if (message.contains("media projection stopped") ||
                message.contains("projection has been stopped") ||
                message.contains("token is no longer valid") ||
                message.contains("projection token expired") ||
                message.contains("don't re-use the resultdata") ||
                message.contains("non-current mediaprojection")
            ) {
                return true
            }
            cursor = cursor.cause
        }
        return false
    }

    private fun ensureAccessibilityMonitor() {
        if (accessibilityMonitorJob?.isActive == true) return
        accessibilityMonitorJob = serviceScope.launch {
            AccessibilityMonitor.events.collect { event ->
                handleAccessibilityMonitorEvent(event)
            }
        }.also { launched ->
            launched.invokeOnCompletion {
                if (accessibilityMonitorJob === launched) {
                    accessibilityMonitorJob = null
                }
            }
        }
    }

    private fun handleAccessibilityMonitorEvent(event: AccessibilityMonitorEvent) {
        val packageName = event.packageName.orEmpty()
        val locked = overlayController?.isTranslationLocked() == true
        diag("capture_monitor_event type=${event.typeName()} package=$packageName target=$activeContentPackage locked=$locked")
        if (packageName.isBlank()) {
            return
        }
        val action = OverlayAccessibilityPolicy.decide(
            packageName = packageName,
            targetPackage = activeContentPackage,
            isHomePackage = isHomePackage(packageName),
            isContentChange = event.isContentChange()
        )
        diag("overlay_accessibility_decision action=${action.name.lowercase()} package=$packageName target=$activeContentPackage locked=$locked")
        when (action) {
            OverlayAccessibilityAction.NONE -> Unit
            OverlayAccessibilityAction.CLEAR_HOME -> clearOverlayForReason("home")
            OverlayAccessibilityAction.CLEAR_PACKAGE_CHANGED -> clearOverlayForReason("package_changed")
            OverlayAccessibilityAction.CLEAR_PAGE_CHANGED -> clearOverlayForReason("page_changed")
        }
    }

    private fun clearOverlayForReason(reason: String) {
        val hadBaseline = currentBaselineHash != null
        val hadTarget = activeContentPackage != null
        overlayController?.setInteractionMode(InteractionMode.BROWSE)
        overlayController?.clearTranslationLayer()
        if (!hadBaseline && !hadTarget) {
            diag("overlay_clear_skip reason=$reason no_active_overlay")
            return
        }
        clearOverlayTracking()
        diag("overlay_clear reason=$reason hadBaseline=$hadBaseline hadTarget=$hadTarget")
    }

    private fun clearOverlayTracking() {
        activeContentPackage = null
        currentBaselineHash = null
    }

    private fun resolveActiveContentPackage(): String? {
        return AccessibilityMonitor.currentForegroundPackage
            ?.takeIf { it.isNotBlank() && !isHomePackage(it) }
    }

    private fun isHomePackage(packageName: String): Boolean {
        return packageName in homePackages
    }

    private fun resolveHomePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        @Suppress("DEPRECATION")
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    private fun ensureSystemUiReceiver() {
        if (systemUiReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemUiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(systemUiReceiver, filter)
        }
        systemUiReceiverRegistered = true
    }

    private fun unregisterSystemUiReceiver() {
        if (!systemUiReceiverRegistered) return
        runCatching { unregisterReceiver(systemUiReceiver) }
        systemUiReceiverRegistered = false
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()

        val stopIntent = Intent(this, CaptureForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.notification_running_title))
            .setContentText(getString(R.string.notification_running_text))
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_action_stop), stopPendingIntent)
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        val projectionStopped = captureController?.hasProjectionStopped() == true
        val preservedState = captureSessionStateStore.getState()
        diag("onDestroy projectionStopped=$projectionStopped projectionUnavailable=$projectionUnavailable state=${preservedState.id}")
        translateJob?.cancel()
        translateJob = null
        stopCaptureAvailabilityMonitor()
        unregisterSystemUiReceiver()

        overlayController?.hideAll()
        overlayController = null

        captureController?.stop()
        captureController = null

        val finalState = if (projectionStopped || preservedState == CaptureSessionState.REAUTH_REQUIRED) {
            CaptureSessionState.REAUTH_REQUIRED
        } else {
            CaptureSessionState.STOPPED
        }
        updateCaptureSessionState(finalState, "service_destroy")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        diag("onTaskRemoved fallback_clear")
        clearOverlayForReason("task_removed")
        super.onTaskRemoved(rootIntent)
    }

    private fun diag(message: String) {
        val line = "${System.currentTimeMillis()} $message"
        Log.e(TAG, line)
        val prefs = getSharedPreferences("mt_diag", MODE_PRIVATE)
        val history = prefs.getString("history", "") ?: ""
        val merged = (history + "\n" + line).trim()
        val trimmed = merged.takeLast(6000)
        prefs.edit()
            .putString("last", line)
            .putString("history", trimmed)
            .apply()
    }

    private fun updateCaptureSessionState(state: CaptureSessionState, reason: String) {
        captureSessionStateStore.update(state, reason)
        diag("capture_session_state state=${state.id} reason=$reason")
    }

    private fun stack(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().take(4000)
    }

    companion object {
        const val ACTION_START = "com.manga.translator.app.action.START"
        const val ACTION_STOP = "com.manga.translator.app.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "mt_service_channel"
        private const val NOTIFICATION_ID = 4041
        private const val TAG = "CaptureForegroundSvc"

        private const val CAPTURE_TIMEOUT_STREAK_THRESHOLD = 2
        private const val CAPTURE_HEALTH_CHECK_INTERVAL_MS = 1200L
        private const val UNAVAILABLE_TOAST_THROTTLE_MS = 1500L

        private const val SYSTEM_DIALOG_REASON = "reason"
        private const val REASON_HOME_KEY = "homekey"
    }
}
