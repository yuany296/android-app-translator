package com.manga.translator.app

import android.content.Context
import com.manga.translator.capture.ScreenCaptureController
import com.manga.translator.overlay.OverlayController
import com.manga.translator.overlay.erase.TieredEraseProcessor
import com.manga.translator.store.PageOverlayAdjustmentRepository
import com.manga.translator.store.SettingsRepository
import com.manga.translator.store.TranslationCacheRepository
import com.manga.translator.translate.OpenAiVisionClient

object ServiceLocator {
    private lateinit var appContext: Context

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var cacheRepository: TranslationCacheRepository
        private set
    lateinit var pageOverlayAdjustmentRepository: PageOverlayAdjustmentRepository
        private set
    lateinit var visionClient: OpenAiVisionClient
        private set
    lateinit var eraseProcessor: TieredEraseProcessor
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        settingsRepository = SettingsRepository(appContext)
        cacheRepository = TranslationCacheRepository(appContext)
        pageOverlayAdjustmentRepository = PageOverlayAdjustmentRepository(appContext)
        visionClient = OpenAiVisionClient()
        eraseProcessor = TieredEraseProcessor()
    }

    fun createCaptureController(): ScreenCaptureController = ScreenCaptureController(appContext)
    fun createOverlayController(): OverlayController = OverlayController(appContext)
}
