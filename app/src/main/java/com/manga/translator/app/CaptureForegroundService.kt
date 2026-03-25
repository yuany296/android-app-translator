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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter

class CaptureForegroundService : Service() {
    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var captureController: ScreenCaptureController? = null
    private var overlayController: OverlayController? = null
    private var orchestrator: TranslateOrchestrator? = null
    private var translateJob: Job? = null
    private var pageMonitorJob: Job? = null
    private var captureAvailabilityJob: Job? = null

    private var captureTimeoutStreak = 0
    private var captureHealthTimeoutStreak = 0
    private var projectionUnavailable = false
    private var lastUnavailableToastAt = 0L
    private var lastUserCaptureAttemptAt = 0L

    private var systemUiReceiverRegistered = false
    private val systemUiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
            val reason = intent.getStringExtra(SYSTEM_DIALOG_REASON).orEmpty()
            if (reason == REASON_HOME_KEY) {
                diag("system_dialogs reason=$reason, clear overlay")
                overlayController?.setInteractionMode(InteractionMode.BROWSE)
                overlayController?.clearTranslationLayer()
                stopPageMonitor()
                enterProjectionUnavailable(shouldToast = false, reason = "homekey")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        diag("onStartCommand action=${intent?.action} startId=$startId")
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
                val fullyStarted = captureController != null && overlayController != null && orchestrator != null
                if (resultCode == 0 || data == null) {
                    if (fullyStarted) {
                        diag("invalid media projection grant ignored: session already running")
                        return START_STICKY
                    }
                    diag("invalid media projection grant, stop self")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (fullyStarted) {
                    if (!projectionUnavailable) {
                        diag("start action ignored: session already running")
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
            it.start(resultCode, data, displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.densityDpi)
        }
        overlayController = ServiceLocator.createOverlayController()
        orchestrator = TranslateOrchestrator(captureController!!, overlayController!!)
        ensureSystemUiReceiver()

        captureTimeoutStreak = 0
        captureHealthTimeoutStreak = 0
        projectionUnavailable = false
        stopCaptureAvailabilityMonitor()

        overlayController?.setInteractionMode(InteractionMode.BROWSE)
        overlayController?.setFloatingVisible(true)
        overlayController?.setFloatingState(FloatingState.IDLE, null)
        startCaptureAvailabilityMonitor()

        overlayController?.showFloatingButton {
            if (translateJob?.isActive == true) {
                return@showFloatingButton
            }

            val settings = ServiceLocator.settingsRepository.getSettings()
            if (!settings.enabled) {
                Toast.makeText(this, "翻译已关闭", Toast.LENGTH_SHORT).show()
                overlayController?.clearTranslationLayer()
                return@showFloatingButton
            }
            if (settings.ocrBaseUrl.isBlank() || settings.ocrApiKey.isBlank()) {
                Toast.makeText(this, "请先配置 OCR API", Toast.LENGTH_SHORT).show()
                overlayController?.clearTranslationLayer()
                return@showFloatingButton
            }
            if (projectionUnavailable) {
                Log.d("TranslationPerf", "skipped=projection_unavailable")
                showUnavailableToast("请切回已投射应用")
                return@showFloatingButton
            }

            overlayController?.setFloatingVisible(true)
            overlayController?.setInteractionMode(InteractionMode.BROWSE)
            stopPageMonitor()
            val current = orchestrator ?: return@showFloatingButton
            lastUserCaptureAttemptAt = SystemClock.elapsedRealtime()
            translateJob = serviceScope.launch {
                try {
                    overlayController?.setFloatingState(FloatingState.CAPTURING, "截图中")
                    val outcome = current.runOnce { state ->
                        if (state == FloatingState.TRANSLATING) {
                            overlayController?.setFloatingState(FloatingState.TRANSLATING, "翻译中")
                        }
                    }
                    onCaptureSuccess()

                    if (outcome.hasOverlay) {
                        val doneText = outcome.warningMessage ?: "完成"
                        overlayController?.setFloatingState(FloatingState.DONE, doneText)
                        outcome.warningMessage?.let {
                            Toast.makeText(this@CaptureForegroundService, it, Toast.LENGTH_SHORT).show()
                        }
                        startPageMonitor()
                    } else {
                        overlayController?.setFloatingState(FloatingState.DONE, "无文本")
                        stopPageMonitor()
                    }
                    delay(1200)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (err: Throwable) {
                    diag("runOnce failed: ${err.javaClass.simpleName}: ${err.message}")
                    Log.d("TranslationPerf", "capture_failure_type=${err.javaClass.simpleName}")
                    overlayController?.clearTranslationLayer()
                    stopPageMonitor()
                    if (isProjectionExpiredError(err)) {
                        enterProjectionUnavailable(shouldToast = false, reason = "projection_expired")
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
                    if (!projectionUnavailable) {
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
    }

    private fun resetActiveSession(reason: String) {
        diag("reset_active_session reason=$reason")
        translateJob?.cancel()
        translateJob = null
        stopPageMonitor()
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
        captureHealthTimeoutStreak = 0
    }

    private fun onCaptureSuccess() {
        captureTimeoutStreak = 0
        captureHealthTimeoutStreak = 0
        if (projectionUnavailable) {
            projectionUnavailable = false
            overlayController?.setFloatingVisible(true)
            overlayController?.setFloatingState(FloatingState.IDLE, null)
            Log.d("TranslationPerf", "capture_unavailable_exit reason=recovered")
            diag("capture_recover_success")
        }
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

    private fun enterProjectionUnavailable(shouldToast: Boolean, reason: String) {
        if (projectionUnavailable) return
        projectionUnavailable = true
        captureTimeoutStreak = 0
        captureHealthTimeoutStreak = 0
        overlayController?.setInteractionMode(InteractionMode.BROWSE)
        overlayController?.clearTranslationLayer()
        val requiresReauth = reason.contains("projection_expired")
        // 不再隐藏悬浮球，避免用户误以为服务退出；保留入口用于重新授权后恢复。
        overlayController?.setFloatingVisible(true)
        overlayController?.setFloatingState(FloatingState.ERROR, if (requiresReauth) "需重新授权" else "截图暂不可用")
        stopPageMonitor()
        Log.d("TranslationPerf", "capture_unavailable_enter reason=$reason")
        diag("projection_unavailable reason=$reason")
        if (shouldToast) {
            showUnavailableToast(if (requiresReauth) "请重新授权录屏" else "截图暂不可用，请稍后重试")
        }
    }

    private fun startCaptureAvailabilityMonitor() {
        if (captureAvailabilityJob?.isActive == true) return
        captureAvailabilityJob = serviceScope.launch {
            while (isActive) {
                delay(CAPTURE_HEALTH_CHECK_INTERVAL_MS)
                if (translateJob?.isActive == true || pageMonitorJob?.isActive == true) {
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastUserCaptureAttemptAt < CAPTURE_HEALTH_SUPPRESS_AFTER_USER_MS) {
                    continue
                }
                val controller = captureController ?: continue
                val alive = controller.isCaptureAlive()
                if (alive) {
                    captureHealthTimeoutStreak = 0
                    if (projectionUnavailable) {
                        projectionUnavailable = false
                        captureTimeoutStreak = 0
                        overlayController?.setFloatingVisible(true)
                        overlayController?.setFloatingState(FloatingState.IDLE, null)
                        Log.d("TranslationPerf", "capture_unavailable_exit reason=background_recovered")
                        diag("capture_recover_success")
                    }
                    continue
                }
                if (!projectionUnavailable) {
                    captureHealthTimeoutStreak += 1
                    diag("capture_health_timeout_streak=$captureHealthTimeoutStreak")
                    if (captureHealthTimeoutStreak >= CAPTURE_HEALTH_TIMEOUT_STREAK_THRESHOLD) {
                        diag("capture_health_timeout_threshold_reached action=enter_unavailable")
                        enterProjectionUnavailable(shouldToast = false, reason = "capture_unavailable_bg")
                        captureHealthTimeoutStreak = 0
                    }
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

    private fun startPageMonitor() {
        stopPageMonitor()
        Log.d("PageMonitor", "monitor start requested")
        val launched = serviceScope.launch {
            var safeStreak = 0
            var fastCandidate = false
            var previousHash: Long? = null
            var frameCount = 0
            val frameHistory = ArrayDeque<Int>()
            while (isActive) {
                delay(MONITOR_INTERVAL_MS)
                val controller = captureController ?: break
                val currentHash = runCatching {
                    val bitmap = controller.captureBitmap(timeoutMs = MONITOR_CAPTURE_TIMEOUT_MS)
                    try {
                        FrameHash.centerDHash(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }.getOrNull() ?: continue

                val prev = previousHash
                if (prev == null) {
                    previousHash = currentHash
                    Log.d("PageMonitor", "monitor started, baseline hash=$previousHash")
                    diag("page_monitor_seeded")
                    continue
                }

                frameCount += 1
                val distance = FrameHash.hammingDistance(prev, currentHash)
                if (frameHistory.size >= 8) {
                    frameHistory.removeFirst()
                }
                frameHistory.addLast(distance)
                Log.d(
                    "PageMonitor",
                    "frame $frameCount, distance=$distance, history=${frameHistory.joinToString(prefix = "[", postfix = "]")}"
                )
                diag("page_monitor_distance=$distance fastCandidate=$fastCandidate safeStreak=$safeStreak mode=interframe")

                val fastTrigger = distance >= PAGE_CHANGE_FAST_THRESHOLD
                fastCandidate = fastCandidate || distance >= PAGE_CHANGE_DISTANCE_THRESHOLD
                val settleTrigger = fastCandidate &&
                    distance <= PAGE_CHANGE_SETTLE_DISTANCE_THRESHOLD
                // 连续检测阶段允许轻微回落，减少临界值抖动导致的漏触发。
                val nextSafeStreak = if (distance >= PAGE_CHANGE_DISTANCE_THRESHOLD) safeStreak + 1 else 0
                val safeTrigger = nextSafeStreak >= PAGE_CHANGE_SAFE_FRAMES
                Log.d("PageMonitor", "fastTrigger=$fastTrigger, safeTrigger=$safeTrigger, settleTrigger=$settleTrigger")

                if (fastTrigger) {
                    diag("page_clear_path=fast distance=$distance")
                    Log.d("PageMonitor", "clearTranslationLayer called")
                    overlayController?.clearTranslationLayer()
                    break
                }
                if (settleTrigger) {
                    diag("page_clear_path=settle distance=$distance streak=$safeStreak")
                    Log.d("PageMonitor", "clearTranslationLayer called")
                    overlayController?.clearTranslationLayer()
                    break
                }

                safeStreak = nextSafeStreak
                diag("page_monitor_streak=$safeStreak")
                if (safeStreak >= PAGE_CHANGE_SAFE_FRAMES) {
                    diag("page_clear_path=safe distance=$distance streak=$safeStreak")
                    Log.d("PageMonitor", "clearTranslationLayer called")
                    overlayController?.clearTranslationLayer()
                    break
                }
                previousHash = currentHash
            }
        }

        pageMonitorJob = launched
        launched.invokeOnCompletion {
            if (pageMonitorJob === launched) {
                pageMonitorJob = null
            }
        }
    }

    private fun stopPageMonitor() {
        pageMonitorJob?.cancel()
        pageMonitorJob = null
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
        diag("onDestroy")
        translateJob?.cancel()
        translateJob = null
        stopPageMonitor()
        stopCaptureAvailabilityMonitor()
        unregisterSystemUiReceiver()

        overlayController?.hideAll()
        overlayController = null

        captureController?.stop()
        captureController = null

        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        diag("onTaskRemoved clear overlay")
        overlayController?.setInteractionMode(InteractionMode.BROWSE)
        overlayController?.clearTranslationLayer()
        enterProjectionUnavailable(shouldToast = false, reason = "task_removed")
        stopPageMonitor()
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

        private const val MONITOR_INTERVAL_MS = 300L
        private const val MONITOR_CAPTURE_TIMEOUT_MS = 1500L
        private const val PAGE_CHANGE_DISTANCE_THRESHOLD = 18
        private const val PAGE_CHANGE_FAST_THRESHOLD = 28
        private const val PAGE_CHANGE_SAFE_FRAMES = 2
        private const val PAGE_CHANGE_SETTLE_DISTANCE_THRESHOLD = 12

        private const val CAPTURE_TIMEOUT_STREAK_THRESHOLD = 2
        private const val CAPTURE_HEALTH_TIMEOUT_STREAK_THRESHOLD = 4
        private const val CAPTURE_HEALTH_CHECK_INTERVAL_MS = 1200L
        private const val CAPTURE_HEALTH_CHECK_TIMEOUT_MS = 900L
        private const val CAPTURE_HEALTH_SUPPRESS_AFTER_USER_MS = 6000L
        private const val UNAVAILABLE_TOAST_THROTTLE_MS = 1500L

        private const val SYSTEM_DIALOG_REASON = "reason"
        private const val REASON_HOME_KEY = "homekey"
    }
}

