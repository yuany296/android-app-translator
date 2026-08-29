package com.manga.translator.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.manga.translator.store.AiProvider
import com.manga.translator.store.AppLanguage
import com.manga.translator.store.AppSettings
import com.manga.translator.store.GlossaryEntry
import com.manga.translator.store.OcrEngine
import com.manga.translator.store.OcrSpeedMode
import com.manga.translator.store.TranslateEngine
import com.manga.translator.store.RenameProfileResult
import com.manga.translator.store.TranslationHistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Filled.Home),
    SETTINGS("翻译设置", Icons.Filled.Settings),
    GLOSSARY("术语表", Icons.Filled.List),
    HISTORY("翻译历史", Icons.Filled.Info)
}

/** ML Kit 本地翻译模型下载状态（首页手动下载入口）。 */
private sealed interface MlKitDownloadState {
    data object Checking : MlKitDownloadState
    data object Idle : MlKitDownloadState
    data object Downloading : MlKitDownloadState
    data object Ready : MlKitDownloadState
    data class Failed(val reason: String) : MlKitDownloadState
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleConfigScreen(
    initial: AppSettings,
    onSave: (AppSettings) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val repo = ServiceLocator.settingsRepository
    val scope = rememberCoroutineScope()

    val providers = remember { AiProvider.entries.filter { it != AiProvider.BAIDU } }

    // 首次进入：表单直接加载所选配置（与下拉框一致），无配置时才回退平面配置
    val initialProfile = remember {
        val names = repo.getProfileNames()
        val last = repo.getLastTranslateProfileName()
        val selected = if (last.isNotEmpty() && names.contains(last)) {
            last
        } else {
            names.firstOrNull() ?: ""
        }
        val settings = selected.takeIf { it.isNotEmpty() }?.let { repo.getProfile(it) }
        selected to settings
    }
    val initialForm = initialProfile.second ?: initial
    var profileNames by remember { mutableStateOf(repo.getProfileNames()) }
    var selectedProfile by remember { mutableStateOf(initialProfile.first) }
    var baseSettings by remember { mutableStateOf(initialForm) }
    var translateEngine by remember { mutableStateOf(initialForm.translateEngine) }
    var apiProvider by remember {
        mutableStateOf(AiProvider.fromId(initialForm.translateProvider))
    }
    var apiBaseUrl by remember { mutableStateOf(initialForm.translateBaseUrl) }
    var apiModel by remember { mutableStateOf(initialForm.translateModel) }
    var apiKey by remember { mutableStateOf(initialForm.translateApiKey) }
    var enabled by remember { mutableStateOf(initialForm.enabled) }
    var enableThinking by remember { mutableStateOf(initialForm.enableThinking) }
    var thinkingBudgetText by remember { mutableStateOf(initialForm.thinkingBudget.toString()) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    // 新建/编辑配置 表单对话框
    var formOpen by remember { mutableStateOf(false) }
    var formMode by remember { mutableStateOf("create") }
    var formStep by remember { mutableStateOf(0) }
    var formProvider by remember { mutableStateOf<AiProvider?>(null) }
    var formName by remember { mutableStateOf("") }
    var formBaseUrl by remember { mutableStateOf("") }
    var formApiKey by remember { mutableStateOf("") }
    var formModel by remember { mutableStateOf("") }
    var modelList by remember { mutableStateOf(emptyList<String>()) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var fetchingModels by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

    fun applySettings(s: AppSettings, profileName: String) {
        baseSettings = s
        selectedProfile = profileName
        translateEngine = s.translateEngine
        apiProvider = AiProvider.fromId(s.translateProvider)
        apiBaseUrl = s.translateBaseUrl
        apiModel = s.translateModel
        apiKey = s.translateApiKey
        enabled = s.enabled
        enableThinking = s.enableThinking
        thinkingBudgetText = s.thinkingBudget.toString()
    }

    fun loadProfile(name: String) {
        val profile = repo.getProfile(name) ?: return
        repo.setLastTranslateProfileName(name)
        onSave(profile)
        applySettings(profile, name)
    }

    fun persistProfile(profileName: String, s: AppSettings) {
        focusManager.clearFocus()
        keyboardController?.hide()
        repo.saveProfile(profileName, s)
        repo.setLastTranslateProfileName(profileName)
        onSave(s)
        baseSettings = s
        selectedProfile = profileName
        profileNames = repo.getProfileNames()
        applySettings(s, profileName)
    }

    fun fetchModels() {
        if (formApiKey.isBlank()) return
        fetchingModels = true
        formError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ServiceLocator.visionClient.fetchAvailableModels(
                        baseUrl = formBaseUrl.trim(),
                        apiKey = formApiKey.trim()
                    )
                }
            }
            fetchingModels = false
            result.onSuccess { list ->
                modelList = list
                if (formModel.isBlank() && list.isNotEmpty()) {
                    formModel = list.first()
                }
            }.onFailure {
                formError = "获取模型列表失败：${it.message}"
            }
        }
    }

    fun openCreateWizard() {
        formMode = "create"
        formStep = 0
        formProvider = null
        formName = ""
        formBaseUrl = ""
        formApiKey = ""
        formModel = ""
        modelList = emptyList()
        fetchingModels = false
        formError = null
        formOpen = true
    }

    fun openEditForm() {
        val profile = repo.getProfile(selectedProfile) ?: return
        applySettings(profile, selectedProfile)
        formMode = "edit"
        formStep = 1
        formProvider = AiProvider.fromId(profile.translateProvider)
        formName = selectedProfile
        formBaseUrl = profile.translateBaseUrl
        formApiKey = profile.translateApiKey
        formModel = profile.translateModel
        modelList = emptyList()
        fetchingModels = false
        formError = null
        formOpen = true
    }

    fun saveForm() {
        val name = formName.trim()
        if (name.isEmpty()) return
        val provider = formProvider ?: AiProvider.fromId(baseSettings.translateProvider)
        val s = baseSettings.copy(
            translateProvider = provider.id,
            translateBaseUrl = formBaseUrl.trim(),
            translateModel = formModel.trim(),
            translateApiKey = formApiKey.trim()
        )
        if (formMode == "edit") {
            val original = selectedProfile
            if (original.isNotEmpty() && name != original) {
                repo.renameProfile(original, name)
            }
        }
        persistProfile(name, s)
        formOpen = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(text = "翻译引擎", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "云端 API：需配置下方翻译 API，联网翻译（大模型质量好，延迟 2~5s）。\n百度翻译：专用接口，整页一次请求最快（<1s）；标准版每月免费 500 万字符，需填写 APP ID/密钥。\nML Kit 本地：首次使用需联网下载模型（入口在首页），之后离线翻译；设备需安装 Google Play 服务。",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        TranslateEngineSelectorRow(
            selected = TranslateEngine.fromId(translateEngine),
            onSelected = { engine ->
                translateEngine = engine.id
                val updated = baseSettings.copy(translateEngine = engine.id)
                baseSettings = updated
                onSave(updated)
            }
        )
        if (TranslateEngine.fromId(translateEngine) == TranslateEngine.BAIDU) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "百度翻译配置", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "在 fanyi-api.baidu.com 开通「通用文本翻译」标准版（免费）后填写以下两项；未填写时翻译会提示。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = baseSettings.baiduTranslateAppId,
                onValueChange = { v ->
                    val updated = baseSettings.copy(baiduTranslateAppId = v)
                    baseSettings = updated
                    onSave(updated)
                },
                label = { Text("百度 APP ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = baseSettings.baiduTranslateSecretKey,
                onValueChange = { v ->
                    val updated = baseSettings.copy(baiduTranslateSecretKey = v)
                    baseSettings = updated
                    onSave(updated)
                },
                label = { Text("百度密钥（Secret Key）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "翻译 API 配置", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))

        Text(text = "配置档案")
        Spacer(modifier = Modifier.height(6.dp))
        ExposedDropdownMenuBox(
            expanded = dropdownExpanded,
            onExpandedChange = { dropdownExpanded = it }
        ) {
            OutlinedTextField(
                value = selectedProfile,
                onValueChange = {},
                readOnly = true,
                label = { Text("选择配置") },
                placeholder = { Text("暂无配置，请先新建") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                singleLine = true
            )
            DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false }
            ) {
                profileNames.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            dropdownExpanded = false
                            loadProfile(name)
                        }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { openCreateWizard() },
                modifier = Modifier.weight(1f)
            ) { Text("新建配置") }
            OutlinedButton(
                onClick = { openEditForm() },
                enabled = selectedProfile.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) { Text("编辑") }
            OutlinedButton(
                onClick = { deleting = true },
                enabled = selectedProfile.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) { Text("删除") }
        }
    }

    if (formOpen) {
        Dialog(
            onDismissRequest = { formOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (formStep == 1) {
                        IconButton(onClick = {
                            if (formMode == "create") formStep = 0 else formOpen = false
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (formMode == "create") "返回选厂商" else "返回"
                            )
                        }
                    }
                    Text(
                        text = when {
                            formMode == "edit" -> "编辑配置"
                            formStep == 0 -> "新建配置 · 选择厂商"
                            else -> "新建配置 · 填写信息"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { formOpen = false }) { Text("取消") }
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (formStep == 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        providers.forEach { item ->
                            OutlinedButton(
                                onClick = {
                                    formProvider = item
                                    formBaseUrl = item.defaultBaseUrl
                                    formApiKey = ""
                                    formModel = item.defaultModel
                                    modelList = emptyList()
                                    fetchingModels = false
                                    formError = null
                                    formStep = 1
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(item.label)
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        OutlinedTextField(
                            value = formName,
                            onValueChange = { formName = it },
                            label = { Text("配置名称") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = formBaseUrl,
                            onValueChange = { formBaseUrl = it },
                            label = { Text("Base URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = formApiKey,
                            onValueChange = { formApiKey = it },
                            label = { Text("API Key") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value = formModel,
                                    onValueChange = { formModel = it },
                                    label = { Text("Model（可输入或下拉选择）") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    trailingIcon = if (modelList.isNotEmpty()) {
                                        {
                                            IconButton(onClick = { modelMenuExpanded = true }) {
                                                Icon(
                                                    Icons.Filled.ArrowDropDown,
                                                    contentDescription = "选择模型"
                                                )
                                            }
                                        }
                                    } else {
                                        null
                                    }
                                )
                                DropdownMenu(
                                    expanded = modelMenuExpanded,
                                    onDismissRequest = { modelMenuExpanded = false }
                                ) {
                                    modelList.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model) },
                                            onClick = {
                                                formModel = model
                                                modelMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            OutlinedButton(
                                onClick = { fetchModels() },
                                enabled = formApiKey.isNotBlank() && !fetchingModels
                            ) {
                                if (fetchingModels) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("获取模型列表")
                                }
                            }
                        }
                        formError?.let { message ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { saveForm() },
                            enabled = formName.isNotBlank() &&
                                formBaseUrl.isNotBlank() &&
                                formApiKey.isNotBlank() &&
                                (formMode == "edit" ||
                                    !repo.getProfileNames().contains(formName.trim())),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (formMode == "edit") "保存" else "创建配置") }
                    }
                }
                }
            }
        }
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("删除配置") },
            text = { Text("确定删除配置「$selectedProfile」吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        repo.deleteProfile(selectedProfile)
                        profileNames = repo.getProfileNames()
                        val remaining = repo.getProfileNames()
                        if (remaining.isNotEmpty()) {
                            loadProfile(remaining.first())
                        } else {
                            applySettings(repo.getSettings(), "")
                        }
                        deleting = false
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) { Text("取消") }
            }
        )
    }
}


class MainActivity : ComponentActivity() {
    private val settingsRepository by lazy { ServiceLocator.settingsRepository }
    private val captureSessionStateStore by lazy { ServiceLocator.captureSessionStateStore }
    private var glossaryReloadTick by mutableStateOf(0)
    private var glossaryCollectionForIo = DEFAULT_GLOSSARY_COLLECTION
    private var importPending by mutableStateOf<ImportPending?>(null)

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Log.i(TAG, "projectionLauncher resultCode=${result.resultCode} dataNull=${result.data == null}")
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            val fallbackState = if (captureSessionStateStore.getState() == CaptureSessionState.REAUTH_REQUIRED) {
                CaptureSessionState.REAUTH_REQUIRED
            } else {
                CaptureSessionState.STOPPED
            }
            captureSessionStateStore.update(fallbackState, "projection_request_cancelled")
            toast("屏幕录制授权失败")
            return@registerForActivityResult
        }

        val intent = Intent(this, CaptureForegroundService::class.java).apply {
            action = CaptureForegroundService.ACTION_START
            putExtra(CaptureForegroundService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(CaptureForegroundService.EXTRA_RESULT_DATA, result.data)
        }
        startForegroundService(intent)
        Log.i(TAG, "startForegroundService called for capture service")
        toast("服务已启动")
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private val importDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val raw = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (raw.isNullOrBlank()) {
            toast("读取文件失败")
            return@registerForActivityResult
        }
        val displayName = queryDisplayName(uri) ?: "导入术语"
        val lower = displayName.lowercase()
        val isCsv = lower.endsWith(".csv")
        val baseName = when {
            isCsv -> displayName.removeSuffix(".csv").removeSuffix(".CSV")
            lower.endsWith(".json") -> displayName.removeSuffix(".json").removeSuffix(".JSON")
            else -> displayName
        }.ifBlank { "导入术语" }
        importPending = ImportPending(raw = raw, defaultName = baseName, isCsv = isCsv)
    }

    private val exportJsonLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val payload = ServiceLocator.glossaryRepository.exportJson(glossaryCollectionForIo)
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            true
        }.getOrDefault(false)
        toast(if (ok) "已导出 JSON" else "导出失败")
    }

    private val exportCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val payload = ServiceLocator.glossaryRepository.exportCsv(glossaryCollectionForIo)
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            true
        }.getOrDefault(false)
        toast(if (ok) "已导出 CSV" else "导出失败")
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private data class ImportPending(
        val raw: String,
        val defaultName: String,
        val isCsv: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                var pageIndex by rememberSaveable { mutableIntStateOf(0) }
                var settings by remember { mutableStateOf(settingsRepository.getSettings()) }
                var glossaryCollections by remember {
                    mutableStateOf(ServiceLocator.glossaryRepository.listCollections())
                }
                var glossaryCounts by remember { mutableStateOf(mapOf<String, Int>()) }
                var activeCollections by remember {
                    mutableStateOf(ServiceLocator.glossaryRepository.getActiveCollections())
                }
                var glossaryEntries by remember { mutableStateOf(emptyList<GlossaryEntry>()) }
                var glossaryDetailCollection by remember { mutableStateOf<String?>(null) }
                var historyEntries by remember { mutableStateOf(emptyList<TranslationHistoryEntry>()) }
                val scope = rememberCoroutineScope()

                fun refreshGlossaryList() {
                    glossaryCollections = ServiceLocator.glossaryRepository.listCollections()
                    glossaryCounts = glossaryCollections.associateWith { name ->
                        ServiceLocator.glossaryRepository.count(name)
                    }
                    activeCollections = ServiceLocator.glossaryRepository.getActiveCollections()
                }

                fun refreshGlossaryEntries() {
                    val detail = glossaryDetailCollection ?: return
                    glossaryEntries = ServiceLocator.glossaryRepository.getEntries(detail)
                }

                fun refreshGlossaryAll() {
                    refreshGlossaryList()
                    refreshGlossaryEntries()
                }

                fun refreshHistory() {
                    scope.launch {
                        historyEntries = withContext(Dispatchers.IO) {
                            ServiceLocator.translationHistoryRepository.all()
                        }
                    }
                }

                LaunchedEffect(pageIndex) {
                    if (pageIndex == MainTab.HISTORY.ordinal) {
                        historyEntries = withContext(Dispatchers.IO) {
                            ServiceLocator.translationHistoryRepository.all()
                        }
                    }
                }

                LaunchedEffect(glossaryReloadTick, glossaryDetailCollection) {
                    refreshGlossaryAll()
                }

                BackHandler(enabled = pageIndex == MainTab.GLOSSARY.ordinal && glossaryDetailCollection != null) {
                    glossaryDetailCollection = null
                }

                Scaffold(
                    bottomBar = {
                        val hideBottomBar = pageIndex == MainTab.GLOSSARY.ordinal && glossaryDetailCollection != null
                        if (!hideBottomBar) {
                            NavigationBar {
                                MainTab.entries.forEachIndexed { index, tab ->
                                    NavigationBarItem(
                                        selected = pageIndex == index,
                                        onClick = { pageIndex = index },
                                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                                        label = { Text(tab.label) }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (pageIndex) {
                            MainTab.HOME.ordinal -> {
                                LauncherScreen(
                                    settings = settings,
                                    onSettingsChange = {
                                        settingsRepository.saveSettings(it)
                                        settings = it
                                    },
                                    onOpenOverlayPermission = { openOverlayPermissionPage() },
                                    onStart = { startCaptureFlow() },
                                    onStop = { stopService(Intent(this@MainActivity, CaptureForegroundService::class.java)) },
onClearCache = {
                                        ServiceLocator.sentenceTranslationRepository.clear()
                                        toast("翻译缓存已清空")
                                    }
                                )
                            }

                            MainTab.SETTINGS.ordinal -> {
                                SimpleConfigScreen(
                                    initial = settings,
                                    onSave = {
                                        settingsRepository.saveSettings(it)
                                        settings = it
                                    }
                                )
                            }

                            MainTab.GLOSSARY.ordinal -> {
                                val detail = glossaryDetailCollection
                                if (detail == null) {
                                    GlossaryCollectionsScreen(
                                        collections = glossaryCollections,
                                        entryCounts = glossaryCounts,
                                        activeCollections = activeCollections,
                                        onOpenCollection = { name ->
                                            glossaryCollectionForIo = name
                                            glossaryDetailCollection = name
                                        },
                                        onActiveChange = { next ->
                                            ServiceLocator.glossaryRepository.setActiveCollections(next)
                                            activeCollections = ServiceLocator.glossaryRepository.getActiveCollections()
                                        },
                                        onCreateCollection = { name ->
                                            if (ServiceLocator.glossaryRepository.createCollection(name)) {
                                                refreshGlossaryList()
                                                glossaryCollectionForIo = name
                                                glossaryDetailCollection = name
                                                toast("术语表「$name」已创建")
                                            } else {
                                                toast("创建失败：名称已存在")
                                            }
                                        },
                                        onImport = {
                                            importDocumentLauncher.launch(arrayOf("*/*"))
                                        },
                                        onDeleteCollection = { name ->
                                            if (ServiceLocator.glossaryRepository.deleteCollection(name)) {
                                                refreshGlossaryList()
                                                toast("术语表已删除")
                                            } else {
                                                toast("删除失败")
                                            }
                                        },
                                        onRenameCollection = { oldName, newName ->
                                            val ok = ServiceLocator.glossaryRepository.renameCollection(oldName, newName)
                                            if (ok) {
                                                refreshGlossaryList()
                                                toast("已重命名")
                                            } else {
                                                toast("重命名失败：名称已存在")
                                            }
                                            ok
                                        },
                                        onRefresh = { refreshGlossaryList() }
                                    )
                                } else {
                                    GlossaryCollectionScreen(
                                        collection = detail,
                                        entries = glossaryEntries,
                                        onBack = { glossaryDetailCollection = null },
                                        onAdd = { source, target ->
                                            val ok = ServiceLocator.glossaryRepository.add(detail, source, target)
                                            if (ok) refreshGlossaryEntries() else toast("添加失败：已存在或已达上限")
                                            ok
                                        },
                                        onRename = { oldSource, newSource, target ->
                                            if (ServiceLocator.glossaryRepository.get(detail, newSource) != null) {
                                                toast("重命名失败：新原文已存在")
                                                false
                                            } else if (ServiceLocator.glossaryRepository.remove(detail, oldSource) &&
                                                ServiceLocator.glossaryRepository.add(detail, newSource, target)
                                            ) {
                                                refreshGlossaryEntries()
                                                true
                                            } else {
                                                toast("重命名失败")
                                                false
                                            }
                                        },
                                        onUpdate = { source, target ->
                                            val ok = ServiceLocator.glossaryRepository.update(detail, source, target)
                                            if (ok) refreshGlossaryEntries()
                                            ok
                                        },
                                        onRemove = { source ->
                                            ServiceLocator.glossaryRepository.remove(detail, source)
                                            refreshGlossaryEntries()
                                        },
                                        onDeleteMany = { sources ->
                                            if (sources.isNotEmpty()) {
                                                sources.forEach {
                                                    ServiceLocator.glossaryRepository.remove(detail, it)
                                                }
                                                refreshGlossaryEntries()
                                                toast("已删除 ${sources.size} 条")
                                            }
                                        },
                                        onExportJson = {
                                            exportJsonLauncher.launch(detail)
                                        },
                                        onExportCsv = {
                                            exportCsvLauncher.launch(detail)
                                        },
                                        onRefresh = { refreshGlossaryEntries() }
                                    )
                                }
                                importPending?.let { pending ->
                                    val defaultName = remember(pending, glossaryCollections) {
                                        com.manga.translator.store.GlossaryRepository.uniqueCollectionName(
                                            glossaryCollections, pending.defaultName
                                        )
                                    }
                                    ImportCollectionDialog(
                                        defaultName = defaultName,
                                        onConfirm = { name ->
                                            val created = ServiceLocator.glossaryRepository.createCollection(name)
                                            if (!created) {
                                                toast("创建失败：名称已存在")
                                                return@ImportCollectionDialog
                                            }
                                            val import = if (pending.isCsv) {
                                                ServiceLocator.glossaryRepository.importCsv(name, pending.raw)
                                            } else {
                                                ServiceLocator.glossaryRepository.importJson(name, pending.raw)
                                            }
                                            importPending = null
                                            refreshGlossaryList()
                                            glossaryCollectionForIo = name
                                            glossaryDetailCollection = name
                                            toast(
                                                "导入完成：新增 ${import.added} 条，跳过 ${import.skipped} 条" +
                                                    if (import.truncated) "（已达上限，部分未导入）" else ""
                                            )
                                        },
                                        onDismiss = { importPending = null }
                                    )
                                }
                            }

                            MainTab.HISTORY.ordinal -> {
                                HistoryScreen(
                                    entries = historyEntries,
                                    onRefresh = { refreshHistory() },
                                    onDelete = { id ->
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                ServiceLocator.translationHistoryRepository.delete(id)
                                            }
                                            refreshHistory()
                                        }
                                    },
                                    onClear = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                ServiceLocator.translationHistoryRepository.clear()
                                            }
                                            refreshHistory()
                                            toast("翻译历史已清空")
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openOverlayPermissionPage() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun startCaptureFlow() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限")
            openOverlayPermissionPage()
            return
        }
        val serviceRunning = CaptureSessionGate.isCaptureServiceRunning(this)
        val rawSnapshot = captureSessionStateStore.getSnapshot()
        val effectiveState = CaptureSessionGate.normalizeStoredState(
            storedState = rawSnapshot.state,
            serviceRunning = serviceRunning
        )
        if (effectiveState != rawSnapshot.state) {
            captureSessionStateStore.update(effectiveState, "stale_state_corrected_service_missing")
        }
        val sessionSnapshot = if (effectiveState == rawSnapshot.state) {
            rawSnapshot
        } else {
            rawSnapshot.copy(state = effectiveState, reason = "stale_state_corrected_service_missing")
        }
        Log.i(
            TAG,
            "startCaptureFlow sessionState=${sessionSnapshot.state.id} reason=${sessionSnapshot.reason} " +
                "updatedAt=${sessionSnapshot.updatedAtMs} serviceRunning=$serviceRunning"
        )
        if (!CaptureSessionPolicy.shouldRequestProjection(sessionSnapshot.state)) {
            Log.i(TAG, "startCaptureFlow skip_projection_request state=${sessionSnapshot.state.id}")
            toast(
                when (sessionSnapshot.state) {
                    CaptureSessionState.ACTIVE -> "服务已运行，直接使用悬浮球即可"
                    CaptureSessionState.UNAVAILABLE -> "服务仍在运行，当前截图暂不可用"
                    CaptureSessionState.REAUTH_REQUIRED -> "录屏授权已失效，请重新授权"
                    CaptureSessionState.STOPPED -> "服务未运行"
                }
            )
            return
        }
        captureSessionStateStore.update(sessionSnapshot.state, "projection_request_started")
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "MainActivityMT"
        const val DEFAULT_GLOSSARY_COLLECTION = "默认"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onOpenOverlayPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearCache: () -> Unit
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    var mlKitDownloadState by remember { mutableStateOf<MlKitDownloadState>(MlKitDownloadState.Checking) }
    LaunchedEffect(settings.sourceLanguage, settings.targetLanguage, settings.translateEngine) {
        mlKitDownloadState = MlKitDownloadState.Checking
        // 探测磁盘缓存：已下载则隐藏下载入口；未下载则显示下载按钮。
        val downloaded = ServiceLocator.localTranslator.isDownloaded(
            settings.sourceLanguage,
            settings.targetLanguage
        )
        mlKitDownloadState = if (downloaded) MlKitDownloadState.Ready else MlKitDownloadState.Idle
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(text = "安卓漫画翻译器", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))

LanguageSelectorRow(
            label = "源语言（OCR 识别）",
            selected = AppLanguage.fromId(settings.sourceLanguage),
            onSelected = { onSettingsChange(settings.copy(sourceLanguage = it.id)) }
        )
        Spacer(modifier = Modifier.height(10.dp))
        OcrEngineSelectorRow(
            selected = OcrEngine.fromId(settings.ocrEngine),
            onSelected = { onSettingsChange(settings.copy(ocrEngine = it.id)) }
        )
        if (OcrEngine.fromId(settings.ocrEngine) == OcrEngine.PADDLE) {
            Spacer(modifier = Modifier.height(10.dp))
            OcrSpeedModeSelectorRow(
                selected = OcrSpeedMode.fromId(settings.ocrSpeedMode),
                onSelected = { onSettingsChange(settings.copy(ocrSpeedMode = it.id)) }
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        LanguageSelectorRow(
            label = "目标语言（翻译输出）",
            selected = AppLanguage.fromId(settings.targetLanguage),
            onSelected = { onSettingsChange(settings.copy(targetLanguage = it.id)) }
        )

        if (TranslateEngine.fromId(settings.translateEngine) == TranslateEngine.ML_KIT) {
            Spacer(modifier = Modifier.height(14.dp))
            val pairLabel = "${AppLanguage.fromId(settings.sourceLanguage).label} → " +
                "${AppLanguage.fromId(settings.targetLanguage).label}"
            when (val st = mlKitDownloadState) {
                MlKitDownloadState.Checking -> OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("检查本地翻译模型中…")
                }
                MlKitDownloadState.Idle -> Button(
                    onClick = {
                        scope.launch {
                            mlKitDownloadState = MlKitDownloadState.Downloading
                            val err = ServiceLocator.localTranslator.downloadModel(
                                settings.sourceLanguage,
                                settings.targetLanguage
                            )
                            mlKitDownloadState = if (err == null) {
                                MlKitDownloadState.Ready
                            } else {
                                MlKitDownloadState.Failed(err)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("下载 $pairLabel 翻译模型（需联网）")
                }
                MlKitDownloadState.Downloading -> OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在下载 $pairLabel 翻译模型，可能需要几分钟…")
                }
                MlKitDownloadState.Ready -> Text(
                    text = "$pairLabel 翻译模型已就绪，可离线翻译",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                )
                is MlKitDownloadState.Failed -> {
                    Button(
                        onClick = {
                            scope.launch {
                                mlKitDownloadState = MlKitDownloadState.Downloading
                                val err = ServiceLocator.localTranslator.downloadModel(
                                    settings.sourceLanguage,
                                    settings.targetLanguage
                                )
                                mlKitDownloadState = if (err == null) {
                                    MlKitDownloadState.Ready
                                } else {
                                    MlKitDownloadState.Failed(err)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("重试下载 $pairLabel 翻译模型")
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = st.reason,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Button(onClick = onOpenOverlayPermission, modifier = Modifier.fillMaxWidth()) { Text("授予悬浮窗权限") }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("启动服务（请求录屏权限）") }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("停止服务") }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onClearCache, modifier = Modifier.fillMaxWidth()) { Text("清空 OCR 缓存") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OcrEngineSelectorRow(
    selected: OcrEngine,
    onSelected: (OcrEngine) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            label = { Text("OCR 引擎（本地识别）") },
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            OcrEngine.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        expanded = false
                        onSelected(item)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OcrSpeedModeSelectorRow(
    selected: OcrSpeedMode,
    onSelected: (OcrSpeedMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            label = { Text("PaddleOCR 速度模式") },
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            OcrSpeedMode.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        expanded = false
                        onSelected(item)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslateEngineSelectorRow(
    selected: TranslateEngine,
    onSelected: (TranslateEngine) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            label = { Text("翻译引擎") },
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            TranslateEngine.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        expanded = false
                        onSelected(item)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelectorRow(
    label: String,
    selected: AppLanguage,
    onSelected: (AppLanguage) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AppLanguage.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        expanded = false
                        onSelected(item)
                    }
                )
            }
        }
    }
}
