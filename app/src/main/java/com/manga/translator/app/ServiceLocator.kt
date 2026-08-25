package com.manga.translator.app

import android.content.Context
import com.manga.translator.overlay.BubbleColorExtractor
import com.manga.translator.overlay.BubbleRegionDetector
import com.manga.translator.capture.ScreenCaptureController
import com.manga.translator.overlay.OverlayController
import com.manga.translator.overlay.erase.CleanedImageProcessor
import com.manga.translator.overlay.erase.TieredEraseProcessor
import com.manga.translator.store.GlossaryRepository
import com.manga.translator.store.OcrEngine
import com.manga.translator.store.PageOverlayAdjustmentRepository
import com.manga.translator.store.SentenceTranslationRepository
import com.manga.translator.store.SettingsRepository
import com.manga.translator.store.TranslationHistoryRepository
import com.manga.translator.translate.LocalTextDetector
import com.manga.translator.translate.LocalTranslator
import com.manga.translator.translate.MlKitLocalTextDetector
import com.manga.translator.translate.MlKitLocalTranslator
import com.manga.translator.translate.OpenAiVisionClient
import com.manga.translator.translate.PaddleOcrLocalTextDetector
import com.manga.translator.translate.TextBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object ServiceLocator {
    private lateinit var appContext: Context

    /** 应用级后台作用域：用于历史记录等非关键写入，失败不影响主链路。 */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var sentenceTranslationRepository: SentenceTranslationRepository
        private set
    lateinit var glossaryRepository: GlossaryRepository
        private set
    lateinit var translationHistoryRepository: TranslationHistoryRepository
        private set
    lateinit var pageOverlayAdjustmentRepository: PageOverlayAdjustmentRepository
        private set
    lateinit var captureSessionStateStore: CaptureSessionStateStore
        private set
    lateinit var visionClient: OpenAiVisionClient
        private set
    lateinit var localTextDetector: LocalTextDetector
        private set
    lateinit var localTranslator: LocalTranslator
        private set
    lateinit var mlKitTextDetector: LocalTextDetector
        private set
    lateinit var paddleTextDetector: LocalTextDetector
        private set
    lateinit var eraseProcessor: TieredEraseProcessor
        private set
    lateinit var cleanedImageProcessor: CleanedImageProcessor
        private set
    lateinit var bubbleRegionDetector: BubbleRegionDetector
        private set
    lateinit var bubbleColorExtractor: BubbleColorExtractor
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        settingsRepository = SettingsRepository(appContext)
        sentenceTranslationRepository = SentenceTranslationRepository(appContext)
        glossaryRepository = GlossaryRepository(appContext)
        translationHistoryRepository = TranslationHistoryRepository(appContext)
        pageOverlayAdjustmentRepository = PageOverlayAdjustmentRepository(appContext)
        captureSessionStateStore = CaptureSessionStateStore(appContext)
        visionClient = OpenAiVisionClient()
        mlKitTextDetector = MlKitLocalTextDetector()
        paddleTextDetector = PaddleOcrLocalTextDetector(appContext)
        localTextDetector = EngineSelectingTextDetector(mlKitTextDetector, paddleTextDetector)
        localTranslator = MlKitLocalTranslator()
        eraseProcessor = TieredEraseProcessor()
        cleanedImageProcessor = CleanedImageProcessor()
        bubbleRegionDetector = BubbleRegionDetector()
        bubbleColorExtractor = BubbleColorExtractor()
    }

    fun createCaptureController(): ScreenCaptureController = ScreenCaptureController(appContext)
    fun createOverlayController(): OverlayController = OverlayController(appContext)
}

/**
 * 按设置选择本地 OCR 引擎；Paddle 引擎失败时回退 ML Kit。
 */
class EngineSelectingTextDetector(
    private val mlKit: LocalTextDetector,
    private val paddle: LocalTextDetector
) : LocalTextDetector {

    override fun detect(bitmap: android.graphics.Bitmap, sourceLanguageId: String): List<TextBlock> {
        val settings = ServiceLocator.settingsRepository.getSettings()
        val engine = OcrEngine.fromId(settings.ocrEngine)
        if (engine == OcrEngine.PADDLE) {
            val paddleBlocks = runCatching { paddle.detect(bitmap, sourceLanguageId) }
                .getOrElse { err ->
                    android.util.Log.w("EngineSelecting", "paddle_fallback_to_mlkit reason=${err.message?.take(80)}")
                    null
                }
            if (paddleBlocks != null && paddleBlocks.isNotEmpty()) {
                return paddleBlocks
            }
            android.util.Log.d("EngineSelecting", "paddle_empty_fallback_to_mlkit")
            return mlKit.detect(bitmap, sourceLanguageId)
        }
        return mlKit.detect(bitmap, sourceLanguageId)
    }

    override fun isReady(sourceLanguageId: String): Boolean {
        val settings = ServiceLocator.settingsRepository.getSettings()
        return if (OcrEngine.fromId(settings.ocrEngine) == OcrEngine.PADDLE) {
            paddle.isReady(sourceLanguageId)
        } else {
            mlKit.isReady(sourceLanguageId)
        }
    }

    override fun prewarm(sourceLanguageId: String) {
        val settings = ServiceLocator.settingsRepository.getSettings()
        if (OcrEngine.fromId(settings.ocrEngine) == OcrEngine.PADDLE) {
            paddle.prewarm(sourceLanguageId)
        } else {
            mlKit.prewarm(sourceLanguageId)
        }
    }
}
