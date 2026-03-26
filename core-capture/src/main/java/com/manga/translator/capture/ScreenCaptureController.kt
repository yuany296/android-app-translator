package com.manga.translator.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class ScreenCaptureController(private val context: Context) {
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var captureWidth: Int = 0
    private var captureHeight: Int = 0
    private var captureDensityDpi: Int = 0
    private val captureMutex = Mutex()
    private var firstFrameReady: Boolean = false
    private var readyReader: ImageReader? = null
    @Volatile
    private var projectionStopped: Boolean = false
    @Volatile
    private var lastCaptureSuccessAt: Long = 0L
    @Volatile
    private var lastCaptureFailureType: CaptureFailureType? = null

    fun start(resultCode: Int, data: android.content.Intent, width: Int, height: Int, densityDpi: Int) {
        stop()

        captureThread = HandlerThread("mt-capture-thread").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        captureWidth = width
        captureHeight = height
        captureDensityDpi = densityDpi
        projectionStopped = false
        lastCaptureSuccessAt = 0L
        lastCaptureFailureType = null

        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)
        Log.i(TAG, "mediaProjection=$mediaProjection")
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.e(TAG, "media_projection_onStop")
                projectionStopped = true
                try {
                    virtualDisplay?.release()
                } catch (_: Throwable) {
                }
                virtualDisplay = null

                try {
                    imageReader?.close()
                } catch (_: Throwable) {
                }
                imageReader = null
            }
        }
        mediaProjection?.registerCallback(projectionCallback!!, captureHandler)
        recreateCapturePipeline()
    }

    suspend fun captureBitmap(timeoutMs: Long = 5000L): Bitmap = captureMutex.withLock {
        try {
            withContext(Dispatchers.Default) {
                val reader = imageReader ?: throw CaptureException(
                    CaptureFailureType.NOT_STARTED,
                    "Capture is not started"
                )
                if (readyReader !== reader) {
                    firstFrameReady = false
                }
                if (!firstFrameReady) {
                    val ready = waitForFirstFrame(reader, FIRST_FRAME_WAIT_TIMEOUT_MS)
                    if (!ready) {
                        throw CaptureException(CaptureFailureType.TIMEOUT, "Screen capture timeout(first frame not ready)")
                    }
                    firstFrameReady = true
                    readyReader = reader
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        try {
                            val plane = image.planes.firstOrNull() ?: continue
                            val buffer: ByteBuffer = plane.buffer
                            val pixelStride = plane.pixelStride
                            val rowStride = plane.rowStride
                            val rowPadding = rowStride - pixelStride * image.width

                            val temp = Bitmap.createBitmap(
                                image.width + rowPadding / pixelStride,
                                image.height,
                                Bitmap.Config.ARGB_8888
                            )
                            temp.copyPixelsFromBuffer(buffer)
                            val result = Bitmap.createBitmap(temp, 0, 0, image.width, image.height)
                            temp.recycle()
                            markCaptureSuccess()
                            return@withContext result
                        } finally {
                            image.close()
                        }
                    }
                    delay(50)
                }
                throw CaptureException(CaptureFailureType.TIMEOUT, "Screen capture timeout")
            }
        } catch (error: Throwable) {
            val wrapped = asCaptureException(error, resolveFailureType(error))
            markCaptureFailure(wrapped.type)
            throw wrapped
        }
    }

    suspend fun captureBitmapWithRecovery(timeoutMs: Long = 5000L): Bitmap {
        return try {
            captureBitmap(timeoutMs)
        } catch (first: Throwable) {
            val firstType = resolveFailureType(first)
            if (firstType == CaptureFailureType.PROJECTION_EXPIRED) {
                throw asCaptureException(first, firstType)
            }
            if (firstType != CaptureFailureType.TIMEOUT) {
                throw asCaptureException(first, firstType)
            }
            Log.w(TAG, "capture_timeout_attempt")
            // Android 14+ 对同一 MediaProjection 实例重复 createVirtualDisplay 有严格限制，
            // 超时恢复仅做二次抓取重试，避免触发 projection 失效。
            delay(120)
            try {
                captureBitmap(timeoutMs).also {
                    Log.i(TAG, "capture_recover_success")
                }
            } catch (second: Throwable) {
                Log.e(TAG, "capture_recover_fail_retry: ${second.message}")
                val secondType = resolveFailureType(second)
                throw asCaptureException(second, secondType)
            }
        }
    }

    fun recreateCapturePipeline() {
        val projection = mediaProjection ?: error("Capture is not started")
        val width = captureWidth
        val height = captureHeight
        val densityDpi = captureDensityDpi
        if (width <= 0 || height <= 0 || densityDpi <= 0) {
            error("Invalid capture metrics")
        }
        Log.i(TAG, "recreate_pipeline_begin ts=${System.currentTimeMillis()}")

        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Throwable) {
        }
        imageReader = null

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        try {
            virtualDisplay = projection.createVirtualDisplay(
                "mt-virtual-display",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                captureHandler
            )
        } catch (security: SecurityException) {
            Log.e(TAG, "create_virtual_display_security: ${security.message}")
            throw CaptureException(
                CaptureFailureType.PROJECTION_EXPIRED,
                security.message ?: "MediaProjection security exception",
                security
            )
        }
        firstFrameReady = false
        readyReader = null
        projectionStopped = false
        Log.i(TAG, "capture_pipeline_recreated w=$width h=$height dpi=$densityDpi")
        Log.i(TAG, "pipeline_ready ts=${System.currentTimeMillis()}")
    }

    fun isCaptureAlive(): Boolean {
        return !projectionStopped &&
            mediaProjection != null &&
            virtualDisplay != null &&
            imageReader != null &&
            captureHandler != null &&
            captureThread?.isAlive == true
    }

    fun hasProjectionStopped(): Boolean = projectionStopped

    fun getLastCaptureSuccessAt(): Long = lastCaptureSuccessAt

    fun getLastCaptureFailureType(): CaptureFailureType? = lastCaptureFailureType

    fun toJpegDataUrl(bitmap: Bitmap, quality: Int = 75, maxWidth: Int = 1024): String {
        val source = if (bitmap.width > maxWidth && maxWidth > 0) {
            val targetHeight = ((maxWidth.toFloat() / bitmap.width.toFloat()) * bitmap.height)
                .toInt()
                .coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, maxWidth, targetHeight, true)
        } else {
            bitmap
        }

        val stream = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 95), stream)
        if (source !== bitmap) {
            source.recycle()
        }
        val bytes = stream.toByteArray()
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }

    fun stop() {
        projectionStopped = true
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Throwable) {
        }
        imageReader = null
        firstFrameReady = false
        readyReader = null

        try {
            projectionCallback?.let { callback ->
                mediaProjection?.unregisterCallback(callback)
            }
        } catch (_: Throwable) {
        }
        projectionCallback = null

        try {
            mediaProjection?.stop()
        } catch (_: Throwable) {
        }
        mediaProjection = null

        try {
            captureThread?.quitSafely()
        } catch (_: Throwable) {
        }
        captureThread = null
        captureHandler = null
        captureWidth = 0
        captureHeight = 0
        captureDensityDpi = 0
        Log.i(TAG, "stop_reset ts=${System.currentTimeMillis()} firstFrameReady=$firstFrameReady")
    }

    private suspend fun waitForFirstFrame(reader: ImageReader, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val image = reader.acquireLatestImage()
            if (image != null) {
                image.close()
                Log.i(TAG, "first_frame_received ts=${System.currentTimeMillis()}")
                return true
            }
            delay(30)
        }
        Log.w(TAG, "first_frame_wait_timeout timeoutMs=$timeoutMs")
        return false
    }

    private fun resolveFailureType(error: Throwable): CaptureFailureType {
        if (error is CaptureException) return error.type
        val message = error.message.orEmpty().lowercase()
        if ((error is SecurityException && message.contains("media projection")) ||
            message.contains("don't re-use the resultdata") ||
            message.contains("projection token expired") ||
            message.contains("token is no longer valid") ||
            message.contains("media projection stopped") ||
            message.contains("projection has been stopped") ||
            message.contains("non-current mediaprojection")
        ) {
            return CaptureFailureType.PROJECTION_EXPIRED
        }
        if (message.contains("timeout")) {
            return CaptureFailureType.TIMEOUT
        }
        if (message.contains("not started")) {
            return CaptureFailureType.NOT_STARTED
        }
        return CaptureFailureType.UNKNOWN
    }

    private fun asCaptureException(error: Throwable, type: CaptureFailureType): CaptureException {
        if (error is CaptureException) return error
        return when (type) {
            CaptureFailureType.PROJECTION_EXPIRED -> CaptureException(
                type,
                "Projection token expired, please restart service and grant screen capture again",
                error
            )

            CaptureFailureType.TIMEOUT -> CaptureException(
                type,
                "Screen capture timeout",
                error
            )

            CaptureFailureType.NOT_STARTED -> CaptureException(
                type,
                "Capture is not started",
                error
            )

            CaptureFailureType.UNKNOWN -> CaptureException(
                type,
                error.message ?: "Unknown capture error",
                error
            )
        }
    }

    private fun markCaptureSuccess() {
        lastCaptureSuccessAt = SystemClock.elapsedRealtime()
        lastCaptureFailureType = null
    }

    private fun markCaptureFailure(type: CaptureFailureType) {
        lastCaptureFailureType = type
    }

    private companion object {
        const val TAG = "ScreenCaptureCtrl"
        const val FIRST_FRAME_WAIT_TIMEOUT_MS = 2000L
    }
}

enum class CaptureFailureType {
    TIMEOUT,
    PROJECTION_EXPIRED,
    NOT_STARTED,
    UNKNOWN
}

class CaptureException(
    val type: CaptureFailureType,
    override val message: String,
    cause: Throwable? = null
) : IOException(message, cause)
