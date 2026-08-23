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
    var projectionStopListener: ((externalStop: Boolean) -> Unit)? = null

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
    @Volatile
    private var stopRequested: Boolean = false
    private var latestFrameSnapshot: FrameSnapshot? = null

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
        stopRequested = false

        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)
        Log.i(TAG, "mediaProjection=$mediaProjection")
        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                val externalStop = !stopRequested
                Log.e(TAG, "media_projection_onStop externalStop=$externalStop")
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
                projectionStopListener?.invoke(externalStop)
            }
        }
        mediaProjection?.registerCallback(projectionCallback!!, captureHandler)
        recreateCapturePipeline()
    }

    suspend fun captureBitmap(
        timeoutMs: Long = 5000L,
        traceId: String = SystemClock.elapsedRealtime().toString()
    ): Bitmap = captureMutex.withLock {
        try {
            withContext(Dispatchers.Default) {
                val reader = imageReader ?: throw CaptureException(
                    CaptureFailureType.NOT_STARTED,
                    "Capture is not started"
                )
                val captureStartMs = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "trace=$traceId capture_start timeoutMs=$timeoutMs firstFrameReady=$firstFrameReady imageReader=$reader"
                )
                if (readyReader !== reader) {
                    firstFrameReady = false
                }
                if (!firstFrameReady) {
                    val firstImage = waitForFirstFrame(reader, FIRST_FRAME_WAIT_TIMEOUT_MS, traceId)
                        ?: throw CaptureException(CaptureFailureType.TIMEOUT, "Screen capture timeout(first frame not ready)")
                    firstFrameReady = true
                    readyReader = reader
                    val firstBitmap = imageToBitmap(firstImage)
                    if (firstBitmap != null) {
                        val quality = analyzeFrameQuality(firstBitmap)
                        if (quality.suspicious) {
                            Log.w(
                                TAG,
                                "trace=$traceId stage=image_to_bitmap source=first_frame suspicious=true " +
                                    "mean=${quality.meanLuma} std=${quality.stddevLuma} dark=${quality.darkRatio} light=${quality.lightRatio}"
                            )
                            if (!firstBitmap.isRecycled) {
                                firstBitmap.recycle()
                            }
                        } else {
                            updateLatestFrameSnapshot(firstBitmap)
                            markCaptureSuccess()
                            Log.i(
                                TAG,
                                "trace=$traceId stage=image_to_bitmap source=first_frame costMs=${SystemClock.elapsedRealtime() - captureStartMs}"
                            )
                            return@withContext firstBitmap
                        }
                    }
                }
                val waitStartMs = SystemClock.elapsedRealtime()
                Log.d(TAG, "trace=$traceId stage=wait_frame start timeoutMs=$timeoutMs")
                val deadline = waitStartMs + timeoutMs
                while (SystemClock.elapsedRealtime() < deadline) {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val result = imageToBitmap(image)
                        if (result != null) {
                            val quality = analyzeFrameQuality(result)
                            if (quality.suspicious) {
                                Log.w(
                                    TAG,
                                    "trace=$traceId stage=wait_frame hit=reader suspicious=true waitedMs=${SystemClock.elapsedRealtime() - waitStartMs} " +
                                        "mean=${quality.meanLuma} std=${quality.stddevLuma} dark=${quality.darkRatio} light=${quality.lightRatio}"
                                )
                                if (!result.isRecycled) {
                                    result.recycle()
                                }
                            } else {
                                updateLatestFrameSnapshot(result)
                                markCaptureSuccess()
                                Log.d(
                                    TAG,
                                    "trace=$traceId stage=wait_frame hit=reader waitedMs=${SystemClock.elapsedRealtime() - waitStartMs}"
                                )
                                return@withContext result
                            }
                        }
                    }
                    delay(50)
                    val cached = tryUseRecentFrameSnapshot(traceId)
                    if (cached != null) {
                        markCaptureSuccess()
                        Log.d(
                            TAG,
                            "trace=$traceId stage=wait_frame hit=cached waitedMs=${SystemClock.elapsedRealtime() - waitStartMs}"
                        )
                        return@withContext cached
                    }
                }
                val cached = tryUseRecentFrameSnapshot(traceId)
                if (cached != null) {
                    markCaptureSuccess()
                    Log.d(
                        TAG,
                        "trace=$traceId stage=wait_frame hit=cached_after_timeout waitedMs=${SystemClock.elapsedRealtime() - waitStartMs}"
                    )
                    return@withContext cached
                }
                Log.e(
                    TAG,
                    "trace=$traceId stage=capture_timeout_final waitedMs=${SystemClock.elapsedRealtime() - waitStartMs}"
                )
                throw CaptureException(CaptureFailureType.TIMEOUT, "Screen capture timeout")
            }
        } catch (error: Throwable) {
            val wrapped = asCaptureException(error, resolveFailureType(error))
            markCaptureFailure(wrapped.type)
            throw wrapped
        }
    }

    suspend fun captureBitmapWithRecovery(
        timeoutMs: Long = 5000L,
        traceId: String = SystemClock.elapsedRealtime().toString()
    ): Bitmap {
        return try {
            captureBitmap(timeoutMs, traceId)
        } catch (first: Throwable) {
            val firstType = resolveFailureType(first)
            if (firstType == CaptureFailureType.PROJECTION_EXPIRED) {
                throw asCaptureException(first, firstType)
            }
            if (firstType != CaptureFailureType.TIMEOUT) {
                throw asCaptureException(first, firstType)
            }
            Log.w(TAG, "trace=$traceId capture_timeout_attempt")
            captureMutex.withLock {
                Log.w(TAG, "trace=$traceId stage=hard_recreate begin")
                recreateCapturePipeline()
                firstFrameReady = false
                readyReader = null
                Log.i(TAG, "trace=$traceId stage=hard_recreate done")
            }
            delay(120)
            val retryTimeoutMs = timeoutMs.coerceAtMost(RECOVERY_RETRY_TIMEOUT_MS).coerceAtLeast(600L)
            try {
                captureBitmap(retryTimeoutMs, traceId).also {
                    Log.i(TAG, "trace=$traceId capture_recover_success retryTimeoutMs=$retryTimeoutMs")
                }
            } catch (second: Throwable) {
                Log.e(TAG, "trace=$traceId capture_recover_fail_retry: ${second.message}")
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
        stopRequested = true
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
        clearLatestFrameSnapshot()

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

    private suspend fun waitForFirstFrame(
        reader: ImageReader,
        timeoutMs: Long,
        traceId: String
    ): android.media.Image? {
        val stageStartMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "trace=$traceId stage=wait_frame start scope=first timeoutMs=$timeoutMs")
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val image = reader.acquireLatestImage()
            if (image != null) {
                Log.i(
                    TAG,
                    "trace=$traceId stage=wait_frame hit=first waitedMs=${SystemClock.elapsedRealtime() - stageStartMs}"
                )
                return image  // 不关闭，由调用方直接读取像素后关闭
            }
            delay(30)
        }
        Log.w(TAG, "trace=$traceId first_frame_wait_timeout timeoutMs=$timeoutMs")
        return null
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        return try {
            val plane = image.planes.firstOrNull() ?: return null
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
            result
        } finally {
            image.close()
        }
    }

    private fun updateLatestFrameSnapshot(bitmap: Bitmap) {
        val quality = analyzeFrameQuality(bitmap)
        if (quality.suspicious) {
            Log.w(
                TAG,
                "stage=cache_frame skip=suspicious mean=${quality.meanLuma} std=${quality.stddevLuma} " +
                    "dark=${quality.darkRatio} light=${quality.lightRatio}"
            )
            return
        }
        val snapshotBitmap = try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Throwable) {
            null
        } ?: return

        latestFrameSnapshot?.bitmap?.let { old ->
            if (!old.isRecycled) {
                old.recycle()
            }
        }
        latestFrameSnapshot = FrameSnapshot(
            bitmap = snapshotBitmap,
            capturedAtMs = SystemClock.elapsedRealtime()
        )
    }

    private fun tryUseRecentFrameSnapshot(traceId: String): Bitmap? {
        val snapshot = latestFrameSnapshot ?: return null
        if (snapshot.bitmap.isRecycled) {
            latestFrameSnapshot = null
            return null
        }
        val ageMs = SystemClock.elapsedRealtime() - snapshot.capturedAtMs
        if (ageMs > RECENT_FRAME_MAX_AGE_MS) {
            Log.d(TAG, "trace=$traceId stage=use_cached_frame skip=expired ageMs=$ageMs")
            return null
        }
        return try {
            snapshot.bitmap.copy(Bitmap.Config.ARGB_8888, false)?.also {
                Log.d(TAG, "trace=$traceId stage=use_cached_frame hit=true ageMs=$ageMs")
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun clearLatestFrameSnapshot() {
        latestFrameSnapshot?.bitmap?.let { old ->
            if (!old.isRecycled) {
                old.recycle()
            }
        }
        latestFrameSnapshot = null
    }

    private fun analyzeFrameQuality(bitmap: Bitmap): FrameQuality {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return FrameQuality(0f, 0f, 1f, 1f, suspicious = true)
        }
        val stepX = maxOf(1, bitmap.width / FRAME_SAMPLE_GRID)
        val stepY = maxOf(1, bitmap.height / FRAME_SAMPLE_GRID)
        var samples = 0
        var lumaSum = 0.0
        var lumaSqSum = 0.0
        var darkCount = 0
        var lightCount = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                lumaSum += luma
                lumaSqSum += luma * luma
                if (luma <= FRAME_DARK_LUMA_THRESHOLD) darkCount += 1
                if (luma >= FRAME_LIGHT_LUMA_THRESHOLD) lightCount += 1
                samples += 1
                x += stepX
            }
            y += stepY
        }
        if (samples <= 0) {
            return FrameQuality(0f, 0f, 1f, 1f, suspicious = true)
        }
        val mean = (lumaSum / samples.toDouble()).toFloat()
        val variance = ((lumaSqSum / samples.toDouble()) - (lumaSum / samples.toDouble()) * (lumaSum / samples.toDouble()))
            .coerceAtLeast(0.0)
        val stddev = kotlin.math.sqrt(variance).toFloat()
        val darkRatio = darkCount.toFloat() / samples.toFloat()
        val lightRatio = lightCount.toFloat() / samples.toFloat()
        val suspicious = stddev < FRAME_MIN_STDDEV_LUMA &&
            (darkRatio >= FRAME_DOMINANT_RATIO || lightRatio >= FRAME_DOMINANT_RATIO)
        return FrameQuality(
            meanLuma = mean,
            stddevLuma = stddev,
            darkRatio = darkRatio,
            lightRatio = lightRatio,
            suspicious = suspicious
        )
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
        const val RECENT_FRAME_MAX_AGE_MS = 1200L
        const val RECOVERY_RETRY_TIMEOUT_MS = 2200L
        const val FRAME_SAMPLE_GRID = 24
        const val FRAME_DARK_LUMA_THRESHOLD = 24f
        const val FRAME_LIGHT_LUMA_THRESHOLD = 245f
        const val FRAME_MIN_STDDEV_LUMA = 9f
        const val FRAME_DOMINANT_RATIO = 0.985f
    }
}

private data class FrameSnapshot(
    val bitmap: Bitmap,
    val capturedAtMs: Long
)

private data class FrameQuality(
    val meanLuma: Float,
    val stddevLuma: Float,
    val darkRatio: Float,
    val lightRatio: Float,
    val suspicious: Boolean
)

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
