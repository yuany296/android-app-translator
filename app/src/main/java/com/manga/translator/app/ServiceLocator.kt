package com.manga.translator.app

import android.content.Context
import android.util.Log
import com.manga.translator.overlay.BubbleColorExtractor
import com.manga.translator.overlay.BubbleInteriorScanner
import com.manga.translator.overlay.BubbleRegionDetector
import com.manga.translator.capture.ScreenCaptureController
import com.manga.translator.overlay.OverlayController
import com.manga.translator.overlay.erase.CleanedImageProcessor
import com.manga.translator.overlay.erase.TieredEraseProcessor
import com.manga.translator.store.GlossaryRepository
import com.manga.translator.store.BrowserFavoriteRepository
import com.manga.translator.store.BrowserHistoryRepository
import com.manga.translator.store.OcrEngine
import com.manga.translator.store.PageOverlayAdjustmentRepository
import com.manga.translator.store.SentenceTranslationRepository
import com.manga.translator.store.SettingsRepository
import com.manga.translator.store.TranslationHistoryRepository
import com.manga.translator.translate.BaiduTranslateClient
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
    lateinit var browserHistoryRepository: BrowserHistoryRepository
        private set
    lateinit var browserFavoriteRepository: BrowserFavoriteRepository
        private set
    lateinit var pageOverlayAdjustmentRepository: PageOverlayAdjustmentRepository
        private set
    lateinit var captureSessionStateStore: CaptureSessionStateStore
        private set
    lateinit var visionClient: OpenAiVisionClient
        private set
    lateinit var baiduTranslateClient: BaiduTranslateClient
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
    lateinit var bubbleInteriorScanner: BubbleInteriorScanner
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        settingsRepository = SettingsRepository(appContext)
        sentenceTranslationRepository = SentenceTranslationRepository(appContext)
        glossaryRepository = GlossaryRepository(appContext)
        translationHistoryRepository = TranslationHistoryRepository(appContext)
        browserHistoryRepository = BrowserHistoryRepository(appContext)
        browserFavoriteRepository = BrowserFavoriteRepository(appContext)
        pageOverlayAdjustmentRepository = PageOverlayAdjustmentRepository(appContext)
        captureSessionStateStore = CaptureSessionStateStore(appContext)
        visionClient = OpenAiVisionClient()
        baiduTranslateClient = BaiduTranslateClient()
        mlKitTextDetector = MlKitLocalTextDetector()
        paddleTextDetector = PaddleOcrLocalTextDetector(
            appContext,
            speedModeProvider = { settingsRepository.getSettings().ocrSpeedMode }
        )
        localTextDetector = EngineSelectingTextDetector(
            mlKitTextDetector,
            paddleTextDetector,
            visionOcr = { bitmap, sourceLanguageId ->
                // 多模态重读的 API 来源（2026-10-14 深夜定稿）：优先用「多模态重读」选的
                // 配置档案；没选就跟翻译配置走。模型名 = 档案的「多模态识别模型」，
                // 没填再跟随档案的翻译模型。
                val config = resolveVisionApiConfig()
                if (config == null) {
                    Log.w("EngineSelecting", "vision_ocr_no_config profile=${settingsRepository.getLastOcrProfileName()}")
                    emptyList()
                } else {
                    Log.i(
                        "EngineSelecting",
                        "vision_ocr_request profile=${settingsRepository.getLastOcrProfileName()} " +
                            "model=${config.model} provider=${config.providerId}"
                    )
                    visionClient.detectTextBlocks(
                        provider = config.providerId,
                        baseUrl = config.baseUrl,
                        model = config.model,
                        apiKey = config.apiKey,
                        dataUrl = encodeBitmapForVision(bitmap),
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                        ignoreSimplifiedChinese = false,
                        // 回读场景要快：不开思考。
                        enableThinking = false,
                        thinkingBudget = 1024
                    )
                }
            }
        )
        localTranslator = MlKitLocalTranslator()
        eraseProcessor = TieredEraseProcessor()
        cleanedImageProcessor = CleanedImageProcessor()
        bubbleRegionDetector = BubbleRegionDetector()
        bubbleColorExtractor = BubbleColorExtractor()
        bubbleInteriorScanner = BubbleInteriorScanner()
    }

    fun createCaptureController(): ScreenCaptureController = ScreenCaptureController(appContext)
    fun createOverlayController(): OverlayController = OverlayController(appContext)

    /**
     * 解析多模态重读（低质量回退读图）用的 API 配置；null = 云端 API 不可用，重读是 no-op。
     *
     * 2026-10-14 深夜定稿的口径：
     * - 「多模态重读」区块里**选了配置档案** → 用那个档案的厂商 / Base URL / Key
     *   （翻译选 ML Kit / 百度也不影响重读）；档案缺云端字段或已被删 → 不生效；
     * - **没选**（空指针）→ 完全跟随翻译配置：翻译走云端才生效（与历史行为一致）。
     */
    fun resolveVisionApiConfig(): VisionApiConfig? {
        val s = settingsRepository.getSettings()
        val visionProfileName = settingsRepository.getLastOcrProfileName()
        if (visionProfileName.isNotEmpty()) {
            val profile = settingsRepository.getProfile(visionProfileName)
                ?: return null // 指针指向的档案不存在（rename/delete 正常会清指针，这里兜底）
            if (profile.translateBaseUrl.isBlank() || profile.translateApiKey.isBlank()) return null
            return VisionApiConfig(
                providerId = profile.translateProvider,
                baseUrl = profile.translateBaseUrl,
                model = profile.visionModel.ifBlank { profile.translateModel },
                apiKey = profile.translateApiKey
            )
        }
        val cloudReady = com.manga.translator.store.TranslateEngine.fromId(s.translateEngine) ==
            com.manga.translator.store.TranslateEngine.CLOUD &&
            s.translateBaseUrl.isNotBlank() &&
            s.translateApiKey.isNotBlank()
        if (!cloudReady) return null
        return VisionApiConfig(
            providerId = s.translateProvider,
            baseUrl = s.translateBaseUrl,
            model = s.visionModel.ifBlank { s.translateModel },
            apiKey = s.translateApiKey
        )
    }

    /**
     * 把位图编码成多模态 OCR 用的 JPEG data URL。
     *
     * 像素量超过 [VISION_MAX_PIXELS] 时等比缩小（长图 webtoon 一页 720×6000+，
     * 原样编码既慢又超模型输入预算；4MP 足够 OCR 认清台词字号）。
     */
    private fun encodeBitmapForVision(bitmap: android.graphics.Bitmap): String {
        val scaled = if (bitmap.width.toLong() * bitmap.height > VISION_MAX_PIXELS) {
            val scale = kotlin.math.sqrt(VISION_MAX_PIXELS.toDouble() / (bitmap.width.toLong() * bitmap.height))
            android.graphics.Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val bos = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos)
        val bytes = bos.toByteArray()
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        if (scaled !== bitmap) scaled.recycle()
        return "data:image/jpeg;base64,$base64"
    }

    private const val VISION_MAX_PIXELS = 4_000_000L
}

/** 多模态重读解析出的 API 配置（见 [ServiceLocator.resolveVisionApiConfig]）。 */
data class VisionApiConfig(
    val providerId: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String
)

/**
 * 按设置选择本地 OCR 引擎；Paddle 引擎失败时回退 ML Kit。
 *
 * 2026-10-14 定稿；2026-10-15 用户裁定移除「语言可能选错了」提示（本当配置语言的漫画里
 * 也会夹汉字/英文，按脚本族判「选错了」误报太多，用户原话「不应该加上这个提示」）：
 * 1. ~~语言不符只提示~~ —— 提示、姊妹模型探测、Toast 全部删除，语言完全按用户配置走；
 * 2. **低质量回退多模态**：本地结果过不了 [com.manga.translator.translate.OcrQualityGate]
 *    且多模态 API 可用（[ServiceLocator.resolveVisionApiConfig] 非 null：重读选了档案，
 *    或没选档案时翻译走云端）时，整图交给 `OpenAiVisionClient.detectTextBlocks` 重读，
 *    目标脚本字符更多才替换。空结果连续 3 次读不出就不再对「空」触发（防止满页
 *    无字漫画反复烧 API），任意一次有收获即复位。
 */
class EngineSelectingTextDetector(
    private val mlKit: LocalTextDetector,
    private val paddle: LocalTextDetector,
    private val visionOcr: VisionOcrBackend? = null
) : LocalTextDetector {

    /** 本进程内「本地空结果 → 多模态也没读出」的连续次数。 */
    private var emptyVisionMisses = 0

    override fun detect(bitmap: android.graphics.Bitmap, sourceLanguageId: String): List<TextBlock> {
        val settings = ServiceLocator.settingsRepository.getSettings()
        val local = detectWithEngineFallback(bitmap, sourceLanguageId)
        return maybeVisionFallback(bitmap, local, sourceLanguageId, settings)
    }

    /** 原有引擎选择：Paddle 失败/为空回退 ML Kit。 */
    private fun detectWithEngineFallback(bitmap: android.graphics.Bitmap, sourceLanguageId: String): List<TextBlock> {
        val settings = ServiceLocator.settingsRepository.getSettings()
        val engine = OcrEngine.fromId(settings.ocrEngine)
        if (engine == OcrEngine.PADDLE) {
            val paddleBlocks = runCatching { paddle.detect(bitmap, sourceLanguageId) }
                .getOrElse { err ->
                    android.util.Log.w(TAG, "paddle_fallback_to_mlkit reason=${err.message?.take(80)}")
                    null
                }
            if (paddleBlocks != null && paddleBlocks.isNotEmpty()) {
                return paddleBlocks
            }
            android.util.Log.d(TAG, "paddle_empty_fallback_to_mlkit")
        }
        return mlKit.detect(bitmap, sourceLanguageId)
    }

    /** 低质量回退多模态：API 可用（见 [ServiceLocator.resolveVisionApiConfig]）+ 闸门通过才发请求；目标脚本更多才替换。 */
    private fun maybeVisionFallback(
        bitmap: android.graphics.Bitmap,
        local: List<TextBlock>,
        languageId: String,
        settings: com.manga.translator.store.AppSettings
    ): List<TextBlock> {
        val backend = visionOcr ?: return local
        // API 来源与翻译引擎解耦（2026-10-14 深夜定稿）：重读选了档案就按档案来，
        // 翻译选 ML Kit / 百度也照样能重读；没选档案时才跟随翻译的云端配置。
        if (ServiceLocator.resolveVisionApiConfig() == null) return local
        if (!ServiceLocator.settingsRepository.isLowQualityVisionOcrEnabled()) return local
        val localEmpty = local.isEmpty()
        if (localEmpty && emptyVisionMisses >= EMPTY_VISION_GIVE_UP) return local
        if (!com.manga.translator.translate.OcrQualityGate.needsVisionFallback(local, languageId)) return local

        val started = android.os.SystemClock.elapsedRealtime()
        val vision = runCatching { backend.detect(bitmap, languageId) }
            .getOrElse { err ->
                android.util.Log.w(TAG, "ocr_vision_fallback fail reason=${err.message?.take(80)}")
                emptyList()
            }
        val tookMs = android.os.SystemClock.elapsedRealtime() - started
        val replace = com.manga.translator.translate.OcrQualityGate.visionBeatsLocal(vision, local, languageId)
        android.util.Log.i(
            TAG,
            "ocr_vision_fallback local=${local.size} vision=${vision.size} took=${tookMs}ms replace=$replace"
        )
        if (vision.isEmpty() && localEmpty) {
            emptyVisionMisses += 1
        } else if (vision.isNotEmpty()) {
            emptyVisionMisses = 0
        }
        return if (replace) vision else local
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

    companion object {
        private const val TAG = "EngineSelecting"

        /** 本地读空 + 多模态也读空，连续这么多次后不再对空结果触发（省 API）。 */
        private const val EMPTY_VISION_GIVE_UP = 3
    }
}

/** 多模态 OCR 的后端（整图进、文字块出，阻塞调用 —— 调用方都在 IO 线程）。 */
fun interface VisionOcrBackend {
    fun detect(bitmap: android.graphics.Bitmap, sourceLanguageId: String): List<TextBlock>
}
