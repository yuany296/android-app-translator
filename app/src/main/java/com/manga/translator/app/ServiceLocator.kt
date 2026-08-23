package com.manga.translator.app

import android.content.Context
import com.manga.translator.overlay.BubbleColorExtractor
import com.manga.translator.overlay.BubbleRegionDetector
import com.manga.translator.capture.ScreenCaptureController
import com.manga.translator.overlay.OverlayController
import com.manga.translator.overlay.erase.TieredEraseProcessor
import com.manga.translator.store.GlossaryRepository
import com.manga.translator.store.OcrCacheRepository
import com.manga.translator.store.PageOverlayAdjustmentRepository
import com.manga.translator.store.SettingsRepository
import com.manga.translator.store.TranslationHistoryRepository
import com.manga.translator.translate.LocalTextDetector
import com.manga.translator.translate.MlKitLocalTextDetector
import com.manga.translator.translate.OpenAiVisionClient

object ServiceLocator {
    private lateinit var appContext: Context

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var ocrCacheRepository: OcrCacheRepository
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
    lateinit var eraseProcessor: TieredEraseProcessor
        private set
    lateinit var bubbleRegionDetector: BubbleRegionDetector
        private set
    lateinit var bubbleColorExtractor: BubbleColorExtractor
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        settingsRepository = SettingsRepository(appContext)
        ocrCacheRepository = OcrCacheRepository(appContext)
        glossaryRepository = GlossaryRepository(appContext)
        translationHistoryRepository = TranslationHistoryRepository(appContext)
        pageOverlayAdjustmentRepository = PageOverlayAdjustmentRepository(appContext)
        captureSessionStateStore = CaptureSessionStateStore(appContext)
        visionClient = OpenAiVisionClient()
        localTextDetector = MlKitLocalTextDetector()
        eraseProcessor = TieredEraseProcessor()
        bubbleRegionDetector = BubbleRegionDetector()
        bubbleColorExtractor = BubbleColorExtractor()
    }

    fun createCaptureController(): ScreenCaptureController = ScreenCaptureController(appContext)
    fun createOverlayController(): OverlayController = OverlayController(appContext)
}
