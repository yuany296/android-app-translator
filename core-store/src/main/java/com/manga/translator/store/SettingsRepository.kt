package com.manga.translator.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.time.YearMonth

class SettingsRepository(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREF_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val quotaLock = Any()

    fun getSettings(): AppSettings {
        val legacyProvider = AiProvider.fromId(prefs.getString(KEY_PROVIDER, AiProvider.OPENAI.id).orEmpty())
        val legacyBaseUrl = prefs.getString(KEY_BASE_URL, "")?.trim().orEmpty()
        val legacyModel = prefs.getString(KEY_MODEL, "gpt-4o-mini")?.trim().orEmpty()
        val legacyApiKey = prefs.getString(KEY_API_KEY, "")?.trim().orEmpty()
        val ocrProvider = AiProvider.fromId(prefs.getString(KEY_OCR_PROVIDER, legacyProvider.id).orEmpty())
        val translateProvider = AiProvider.fromId(prefs.getString(KEY_TRANSLATE_PROVIDER, legacyProvider.id).orEmpty())
        return AppSettings(
            sourceLanguage = AppLanguage.fromId(prefs.getString(KEY_SOURCE_LANGUAGE, AppLanguage.ENGLISH.id).orEmpty()).id,
            targetLanguage = AppLanguage.fromId(prefs.getString(KEY_TARGET_LANGUAGE, AppLanguage.CHINESE_SIMPLIFIED.id).orEmpty()).id,
            ocrProvider = ocrProvider.id,
            ocrBaseUrl = prefs.getString(KEY_OCR_BASE_URL, legacyBaseUrl)?.trim().orEmpty(),
            ocrModel = prefs.getString(KEY_OCR_MODEL, legacyModel)?.trim().orEmpty(),
            ocrApiKey = prefs.getString(KEY_OCR_API_KEY, legacyApiKey)?.trim().orEmpty(),
            translateProvider = translateProvider.id,
            translateBaseUrl = prefs.getString(KEY_TRANSLATE_BASE_URL, legacyBaseUrl)?.trim().orEmpty(),
            translateModel = prefs.getString(KEY_TRANSLATE_MODEL, legacyModel)?.trim().orEmpty(),
            translateApiKey = prefs.getString(KEY_TRANSLATE_API_KEY, legacyApiKey)?.trim().orEmpty(),
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            ignoreSimplifiedChinese = prefs.getBoolean(KEY_IGNORE_SIMPLIFIED, false),
            enableThinking = prefs.getBoolean(KEY_ENABLE_THINKING, false),
            thinkingBudget = prefs.getInt(KEY_THINKING_BUDGET, DEFAULT_THINKING_BUDGET),
            ocrEngine = OcrEngine.fromId(prefs.getString(KEY_OCR_ENGINE, OcrEngine.ML_KIT.id).orEmpty()).id,
            ocrSpeedMode = OcrSpeedMode.fromId(prefs.getString(KEY_OCR_SPEED_MODE, OcrSpeedMode.BALANCED.id).orEmpty()).id,
            translateEngine = TranslateEngine.fromId(prefs.getString(KEY_TRANSLATE_ENGINE, TranslateEngine.CLOUD.id).orEmpty()).id,
            baiduTranslateAppId = prefs.getString(KEY_BAIDU_TRANSLATE_APP_ID, "")?.trim().orEmpty(),
            baiduTranslateSecretKey = prefs.getString(KEY_BAIDU_TRANSLATE_SECRET_KEY, "")?.trim().orEmpty()
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_SOURCE_LANGUAGE, AppLanguage.fromId(settings.sourceLanguage).id)
            .putString(KEY_TARGET_LANGUAGE, AppLanguage.fromId(settings.targetLanguage).id)
            .putString(KEY_OCR_PROVIDER, AiProvider.fromId(settings.ocrProvider).id)
            .putString(KEY_OCR_BASE_URL, normalizeBaseUrl(settings.ocrBaseUrl))
            .putString(KEY_OCR_MODEL, settings.ocrModel.trim())
            .putString(KEY_OCR_API_KEY, settings.ocrApiKey.trim())
            .putString(KEY_TRANSLATE_PROVIDER, AiProvider.fromId(settings.translateProvider).id)
            .putString(KEY_TRANSLATE_BASE_URL, normalizeBaseUrl(settings.translateBaseUrl))
            .putString(KEY_TRANSLATE_MODEL, settings.translateModel.trim())
            .putString(KEY_TRANSLATE_API_KEY, settings.translateApiKey.trim())
            // 兼容旧版本字段，默认同步为翻译配置。
            .putString(KEY_PROVIDER, AiProvider.fromId(settings.translateProvider).id)
            .putString(KEY_BASE_URL, normalizeBaseUrl(settings.translateBaseUrl))
            .putString(KEY_MODEL, settings.translateModel.trim())
            .putString(KEY_API_KEY, settings.translateApiKey.trim())
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putBoolean(KEY_IGNORE_SIMPLIFIED, settings.ignoreSimplifiedChinese)
            .putBoolean(KEY_ENABLE_THINKING, settings.enableThinking)
            .putInt(KEY_THINKING_BUDGET, settings.thinkingBudget.coerceIn(1024, 131072))
            .putString(KEY_OCR_ENGINE, OcrEngine.fromId(settings.ocrEngine).id)
            .putString(KEY_OCR_SPEED_MODE, OcrSpeedMode.fromId(settings.ocrSpeedMode).id)
            .putString(KEY_TRANSLATE_ENGINE, TranslateEngine.fromId(settings.translateEngine).id)
            .putString(KEY_BAIDU_TRANSLATE_APP_ID, settings.baiduTranslateAppId.trim())
            .putString(KEY_BAIDU_TRANSLATE_SECRET_KEY, settings.baiduTranslateSecretKey.trim())
            .apply()
    }

    fun getProfileNames(): List<String> {
        val root = loadProfilesJson()
        val names = mutableListOf<String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next().trim()
            if (name.isNotEmpty()) {
                names += name
            }
        }
        return names.sorted()
    }

    fun getProfile(name: String): AppSettings? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val root = loadProfilesJson()
        val profile = root.optJSONObject(trimmed) ?: return null
        return jsonToSettings(profile)
    }

    fun saveProfile(name: String, settings: AppSettings) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val root = loadProfilesJson()
        root.put(trimmed, settingsToJson(settings))
        prefs.edit()
            .putString(KEY_PROFILES_JSON, root.toString())
            .putString(KEY_LAST_PROFILE_NAME, trimmed)
            .apply()
    }

    fun renameProfile(oldName: String, newName: String): RenameProfileResult {
        val source = oldName.trim()
        val target = newName.trim()
        if (source.isEmpty() || target.isEmpty()) return RenameProfileResult.INVALID_NAME
        if (source == target) return RenameProfileResult.SUCCESS

        val root = loadProfilesJson()
        val payload = root.optJSONObject(source) ?: return RenameProfileResult.SOURCE_NOT_FOUND
        if (root.has(target)) return RenameProfileResult.TARGET_EXISTS

        root.remove(source)
        root.put(target, payload)

        val editor = prefs.edit().putString(KEY_PROFILES_JSON, root.toString())
        if (getLastProfileName() == source) {
            editor.putString(KEY_LAST_PROFILE_NAME, target)
        }
        if (getLastOcrProfileName() == source) {
            editor.putString(KEY_LAST_OCR_PROFILE_NAME, target)
        }
        if (getLastTranslateProfileName() == source) {
            editor.putString(KEY_LAST_TRANSLATE_PROFILE_NAME, target)
        }
        editor.apply()
        return RenameProfileResult.SUCCESS
    }

    fun deleteProfile(name: String): List<String> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return getProfileNames()
        val root = loadProfilesJson()
        if (!root.has(trimmed)) return getProfileNames()
        root.remove(trimmed)
        val editor = prefs.edit()
            .putString(KEY_PROFILES_JSON, root.toString())
        if (getLastProfileName() == trimmed) {
            editor.putString(KEY_LAST_PROFILE_NAME, "")
        }
        if (getLastOcrProfileName() == trimmed) {
            editor.putString(KEY_LAST_OCR_PROFILE_NAME, "")
        }
        if (getLastTranslateProfileName() == trimmed) {
            editor.putString(KEY_LAST_TRANSLATE_PROFILE_NAME, "")
        }
        editor.apply()
        return getProfileNames()
    }

    fun getLastProfileName(): String {
        return prefs.getString(KEY_LAST_PROFILE_NAME, "")?.trim().orEmpty()
    }

    fun getLastOcrProfileName(): String {
        return prefs.getString(KEY_LAST_OCR_PROFILE_NAME, "")?.trim().orEmpty()
    }

    fun setLastOcrProfileName(name: String) {
        prefs.edit().putString(KEY_LAST_OCR_PROFILE_NAME, name.trim()).apply()
    }

    fun getLastTranslateProfileName(): String {
        return prefs.getString(KEY_LAST_TRANSLATE_PROFILE_NAME, "")?.trim().orEmpty()
    }

    fun setLastTranslateProfileName(name: String) {
        prefs.edit().putString(KEY_LAST_TRANSLATE_PROFILE_NAME, name.trim()).apply()
    }

    fun getBaiduOcrUsage(limit: Int = BAIDU_OCR_MONTHLY_LIMIT): MonthlyQuotaUsage {
        synchronized(quotaLock) {
            val normalizedLimit = limit.coerceAtLeast(1)
            val currentMonth = YearMonth.now().toString()
            val storedMonth = prefs.getString(KEY_BAIDU_OCR_MONTH, "")?.trim().orEmpty()
            val storedCount = prefs.getInt(KEY_BAIDU_OCR_MONTH_COUNT, 0).coerceAtLeast(0)
            if (storedMonth == currentMonth) {
                return MonthlyQuotaUsage(month = currentMonth, used = storedCount, limit = normalizedLimit)
            }
            prefs.edit()
                .putString(KEY_BAIDU_OCR_MONTH, currentMonth)
                .putInt(KEY_BAIDU_OCR_MONTH_COUNT, 0)
                .apply()
            return MonthlyQuotaUsage(month = currentMonth, used = 0, limit = normalizedLimit)
        }
    }

    fun incrementBaiduOcrUsage(limit: Int = BAIDU_OCR_MONTHLY_LIMIT): MonthlyQuotaUsage {
        synchronized(quotaLock) {
            val current = getBaiduOcrUsage(limit)
            if (current.used >= current.limit) return current
            val nextUsed = current.used + 1
            prefs.edit()
                .putString(KEY_BAIDU_OCR_MONTH, current.month)
                .putInt(KEY_BAIDU_OCR_MONTH_COUNT, nextUsed)
                .apply()
            return current.copy(used = nextUsed)
        }
    }

    private fun normalizeBaseUrl(baseUrl: String): String {
        var normalized = baseUrl.trim().trimEnd('/')
        normalized = normalized.replace(Regex("/chat/completions$", RegexOption.IGNORE_CASE), "")
        normalized = normalized.replace(Regex("/responses$", RegexOption.IGNORE_CASE), "")
        return normalized
    }

    private fun loadProfilesJson(): JSONObject {
        val raw = prefs.getString(KEY_PROFILES_JSON, "{}").orEmpty()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun settingsToJson(settings: AppSettings): JSONObject {
        return JSONObject()
            .put("sourceLanguage", AppLanguage.fromId(settings.sourceLanguage).id)
            .put("targetLanguage", AppLanguage.fromId(settings.targetLanguage).id)
            .put("ocrProvider", AiProvider.fromId(settings.ocrProvider).id)
            .put("ocrBaseUrl", normalizeBaseUrl(settings.ocrBaseUrl))
            .put("ocrModel", settings.ocrModel.trim())
            .put("ocrApiKey", settings.ocrApiKey.trim())
            .put("translateProvider", AiProvider.fromId(settings.translateProvider).id)
            .put("translateBaseUrl", normalizeBaseUrl(settings.translateBaseUrl))
            .put("translateModel", settings.translateModel.trim())
            .put("translateApiKey", settings.translateApiKey.trim())
            // 保留旧字段供旧客户端读取
            .put("provider", AiProvider.fromId(settings.translateProvider).id)
            .put("baseUrl", normalizeBaseUrl(settings.translateBaseUrl))
            .put("model", settings.translateModel.trim())
            .put("apiKey", settings.translateApiKey.trim())
            .put("enabled", settings.enabled)
            .put("ignoreSimplifiedChinese", settings.ignoreSimplifiedChinese)
            .put("enableThinking", settings.enableThinking)
            .put("thinkingBudget", settings.thinkingBudget.coerceIn(1024, 131072))
            .put("ocrEngine", OcrEngine.fromId(settings.ocrEngine).id)
            .put("ocrSpeedMode", OcrSpeedMode.fromId(settings.ocrSpeedMode).id)
            .put("translateEngine", TranslateEngine.fromId(settings.translateEngine).id)
            .put("baiduTranslateAppId", settings.baiduTranslateAppId.trim())
            .put("baiduTranslateSecretKey", settings.baiduTranslateSecretKey.trim())
    }

    private fun jsonToSettings(json: JSONObject): AppSettings {
        val legacyProvider = AiProvider.fromId(json.optString("provider", AiProvider.OPENAI.id)).id
        val legacyBaseUrl = json.optString("baseUrl", "").trim()
        val legacyModel = json.optString("model", "gpt-4o-mini").trim()
        val legacyApiKey = json.optString("apiKey", "").trim()
        return AppSettings(
            sourceLanguage = AppLanguage.fromId(json.optString("sourceLanguage", AppLanguage.ENGLISH.id)).id,
            targetLanguage = AppLanguage.fromId(json.optString("targetLanguage", AppLanguage.CHINESE_SIMPLIFIED.id)).id,
            ocrProvider = AiProvider.fromId(json.optString("ocrProvider", legacyProvider)).id,
            ocrBaseUrl = json.optString("ocrBaseUrl", legacyBaseUrl).trim(),
            ocrModel = json.optString("ocrModel", legacyModel).trim(),
            ocrApiKey = json.optString("ocrApiKey", legacyApiKey).trim(),
            translateProvider = AiProvider.fromId(json.optString("translateProvider", legacyProvider)).id,
            translateBaseUrl = json.optString("translateBaseUrl", legacyBaseUrl).trim(),
            translateModel = json.optString("translateModel", legacyModel).trim(),
            translateApiKey = json.optString("translateApiKey", legacyApiKey).trim(),
            enabled = json.optBoolean("enabled", true),
            ignoreSimplifiedChinese = json.optBoolean("ignoreSimplifiedChinese", false),
            enableThinking = json.optBoolean("enableThinking", false),
            thinkingBudget = json.optInt("thinkingBudget", DEFAULT_THINKING_BUDGET).coerceIn(1024, 131072),
            ocrEngine = OcrEngine.fromId(json.optString("ocrEngine", OcrEngine.ML_KIT.id)).id,
            ocrSpeedMode = OcrSpeedMode.fromId(json.optString("ocrSpeedMode", OcrSpeedMode.BALANCED.id)).id,
            translateEngine = TranslateEngine.fromId(json.optString("translateEngine", TranslateEngine.CLOUD.id)).id,
            baiduTranslateAppId = json.optString("baiduTranslateAppId", "").trim(),
            baiduTranslateSecretKey = json.optString("baiduTranslateSecretKey", "").trim()
        )
    }

    private companion object {
        const val PREF_NAME = "mt_settings_secure"
        const val KEY_PROVIDER = "provider"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "api_key"
        const val KEY_SOURCE_LANGUAGE = "source_language"
        const val KEY_TARGET_LANGUAGE = "target_language"
        const val KEY_OCR_PROVIDER = "ocr_provider"
        const val KEY_OCR_BASE_URL = "ocr_base_url"
        const val KEY_OCR_MODEL = "ocr_model"
        const val KEY_OCR_API_KEY = "ocr_api_key"
        const val KEY_TRANSLATE_PROVIDER = "translate_provider"
        const val KEY_TRANSLATE_BASE_URL = "translate_base_url"
        const val KEY_TRANSLATE_MODEL = "translate_model"
        const val KEY_TRANSLATE_API_KEY = "translate_api_key"
        const val KEY_ENABLED = "enabled"
        const val KEY_IGNORE_SIMPLIFIED = "ignore_simplified"
        const val KEY_ENABLE_THINKING = "enable_thinking"
        const val KEY_THINKING_BUDGET = "thinking_budget"
        const val KEY_PROFILES_JSON = "profiles_json"
        const val KEY_LAST_PROFILE_NAME = "last_profile_name"
        const val KEY_LAST_OCR_PROFILE_NAME = "last_ocr_profile_name"
        const val KEY_LAST_TRANSLATE_PROFILE_NAME = "last_translate_profile_name"
        const val KEY_BAIDU_OCR_MONTH = "baidu_ocr_month"
        const val KEY_BAIDU_OCR_MONTH_COUNT = "baidu_ocr_month_count"
        const val KEY_OCR_ENGINE = "ocr_engine"
        const val KEY_OCR_SPEED_MODE = "ocr_speed_mode"
        const val KEY_TRANSLATE_ENGINE = "translate_engine"
        const val KEY_BAIDU_TRANSLATE_APP_ID = "baidu_translate_app_id"
        const val KEY_BAIDU_TRANSLATE_SECRET_KEY = "baidu_translate_secret_key"
        const val DEFAULT_THINKING_BUDGET = 8192
        const val BAIDU_OCR_MONTHLY_LIMIT = 500
    }
}

enum class RenameProfileResult {
    SUCCESS,
    INVALID_NAME,
    SOURCE_NOT_FOUND,
    TARGET_EXISTS
}

data class MonthlyQuotaUsage(
    val month: String,
    val used: Int,
    val limit: Int
) {
    val remaining: Int get() = (limit - used).coerceAtLeast(0)
}
