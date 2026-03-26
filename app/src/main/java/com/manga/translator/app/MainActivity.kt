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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.manga.translator.store.AiProvider
import com.manga.translator.store.AppSettings
import com.manga.translator.store.RenameProfileResult

enum class MainPage {
    LAUNCHER,
    CONFIG
}

class MainActivity : ComponentActivity() {
    private val settingsRepository by lazy { ServiceLocator.settingsRepository }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Log.i(TAG, "projectionLauncher resultCode=${result.resultCode} dataNull=${result.data == null}")
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                var page by remember { mutableStateOf(MainPage.LAUNCHER) }
                var profiles by remember { mutableStateOf(settingsRepository.getProfileNames()) }
                var configInitial by remember { mutableStateOf(settingsRepository.getSettings()) }
                var configLastProfile by remember { mutableStateOf(settingsRepository.getLastProfileName()) }
                var selectedOcrProfile by remember {
                    mutableStateOf(
                        resolveProfileSelection(
                            profileNames = profiles,
                            preferred = settingsRepository.getLastOcrProfileName(),
                            fallback = settingsRepository.getLastProfileName()
                        )
                    )
                }
                var selectedTranslateProfile by remember {
                    mutableStateOf(
                        resolveProfileSelection(
                            profileNames = profiles,
                            preferred = settingsRepository.getLastTranslateProfileName(),
                            fallback = settingsRepository.getLastProfileName()
                        )
                    )
                }

                // 处理CONFIG页面的返回键
                BackHandler(enabled = page == MainPage.CONFIG) {
                    Log.d(TAG, "Back pressed in CONFIG page, returning to LAUNCHER")
                    // 刷新主界面选择
                    profiles = settingsRepository.getProfileNames()
                    selectedOcrProfile = resolveProfileSelection(
                        profileNames = profiles,
                        preferred = settingsRepository.getLastOcrProfileName(),
                        fallback = settingsRepository.getLastProfileName()
                    )
                    selectedTranslateProfile = resolveProfileSelection(
                        profileNames = profiles,
                        preferred = settingsRepository.getLastTranslateProfileName(),
                        fallback = settingsRepository.getLastProfileName()
                    )
                    page = MainPage.LAUNCHER
                }

                fun refreshLauncherSelections() {
                    profiles = settingsRepository.getProfileNames()
                    selectedOcrProfile = resolveProfileSelection(
                        profileNames = profiles,
                        preferred = settingsRepository.getLastOcrProfileName(),
                        fallback = settingsRepository.getLastProfileName()
                    )
                    selectedTranslateProfile = resolveProfileSelection(
                        profileNames = profiles,
                        preferred = settingsRepository.getLastTranslateProfileName(),
                        fallback = settingsRepository.getLastProfileName()
                    )
                }

                fun applyOcrProfile(profileName: String) {
                    val profile = settingsRepository.getProfile(profileName)
                    if (profile == null) {
                        toast("OCR 配置不存在")
                        return
                    }
                    val current = settingsRepository.getSettings()
                    settingsRepository.saveSettings(
                        current.copy(
                            ocrProvider = profile.ocrProvider,
                            ocrBaseUrl = profile.ocrBaseUrl,
                            ocrModel = profile.ocrModel,
                            ocrApiKey = profile.ocrApiKey
                        )
                    )
                    settingsRepository.setLastOcrProfileName(profileName)
                    selectedOcrProfile = profileName
                    toast("已应用 OCR 配置：$profileName")
                }

                fun applyTranslateProfile(profileName: String) {
                    val profile = settingsRepository.getProfile(profileName)
                    if (profile == null) {
                        toast("翻译配置不存在")
                        return
                    }
                    val current = settingsRepository.getSettings()
                    settingsRepository.saveSettings(
                        current.copy(
                            translateProvider = profile.translateProvider,
                            translateBaseUrl = profile.translateBaseUrl,
                            translateModel = profile.translateModel,
                            translateApiKey = profile.translateApiKey
                        )
                    )
                    settingsRepository.setLastTranslateProfileName(profileName)
                    selectedTranslateProfile = profileName
                    toast("已应用翻译配置：$profileName")
                }

                when (page) {
                    MainPage.LAUNCHER -> {
                        LauncherScreen(
                            profiles = profiles,
                            selectedOcrProfile = selectedOcrProfile,
                            selectedTranslateProfile = selectedTranslateProfile,
                            onSelectOcrProfile = { applyOcrProfile(it) },
                            onSelectTranslateProfile = { applyTranslateProfile(it) },
                            onOpenConfig = {
                                configInitial = settingsRepository.getSettings()
                                configLastProfile = settingsRepository.getLastProfileName()
                                page = MainPage.CONFIG
                            },
                            onOpenOverlayPermission = { openOverlayPermissionPage() },
                            onStart = { startCaptureFlow() },
                            onStop = { stopService(Intent(this, CaptureForegroundService::class.java)) },
                            onClearCache = {
                                ServiceLocator.cacheRepository.clearAll()
                                toast("缓存已清空")
                            }
                        )
                    }

                    MainPage.CONFIG -> {
                        ConfigScreen(
                            initial = configInitial,
                            initialProfiles = profiles,
                            initialLastProfile = configLastProfile,
                            onSave = {
                                settingsRepository.saveSettings(it)
                            },
                            onLoadProfile = { profileName -> settingsRepository.getProfile(profileName) },
                            onSaveProfile = { profileName, settings ->
                                settingsRepository.saveProfile(profileName, settings)
                                settingsRepository.getProfileNames()
                            },
                            onRenameProfile = { oldName, newName, settings ->
                                when (settingsRepository.renameProfile(oldName, newName)) {
                                    RenameProfileResult.SUCCESS -> {
                                        settingsRepository.saveProfile(newName, settings)
                                        RenameProfileResult.SUCCESS
                                    }

                                    RenameProfileResult.INVALID_NAME -> RenameProfileResult.INVALID_NAME
                                    RenameProfileResult.SOURCE_NOT_FOUND -> RenameProfileResult.SOURCE_NOT_FOUND
                                    RenameProfileResult.TARGET_EXISTS -> RenameProfileResult.TARGET_EXISTS
                                }
                            },
                            onDeleteProfile = { profileName ->
                                settingsRepository.deleteProfile(profileName)
                            },
                            onMessage = { message -> toast(message) },
                            onBack = {
                                refreshLauncherSelections()
                                page = MainPage.LAUNCHER
                            }
                        )
                    }
                }
            }
        }
    }

    private fun resolveProfileSelection(profileNames: List<String>, preferred: String, fallback: String): String {
        if (preferred.isNotBlank() && profileNames.contains(preferred)) return preferred
        if (fallback.isNotBlank() && profileNames.contains(fallback)) return fallback
        return profileNames.firstOrNull().orEmpty()
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
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "MainActivityMT"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherScreen(
    profiles: List<String>,
    selectedOcrProfile: String,
    selectedTranslateProfile: String,
    onSelectOcrProfile: (String) -> Unit,
    onSelectTranslateProfile: (String) -> Unit,
    onOpenConfig: () -> Unit,
    onOpenOverlayPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearCache: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(text = "安卓漫画翻译器（无后端）", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))

        ProfileSelectorRow(
            label = "OCR 配置",
            selected = selectedOcrProfile,
            profiles = profiles,
            placeholder = "请先进入配置页保存",
            onSelected = onSelectOcrProfile
        )

        Spacer(modifier = Modifier.height(10.dp))

        ProfileSelectorRow(
            label = "翻译配置",
            selected = selectedTranslateProfile,
            profiles = profiles,
            placeholder = "请先进入配置页保存",
            onSelected = onSelectTranslateProfile
        )

        Spacer(modifier = Modifier.height(14.dp))
        Button(onClick = onOpenConfig, modifier = Modifier.fillMaxWidth()) { Text("进入配置页") }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onOpenOverlayPermission, modifier = Modifier.fillMaxWidth()) { Text("授予悬浮窗权限") }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("启动服务（请求录屏权限）") }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("停止服务") }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onClearCache, modifier = Modifier.fillMaxWidth()) { Text("清空缓存") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSelectorRow(
    label: String,
    selected: String,
    profiles: List<String>,
    placeholder: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = profiles.isNotEmpty()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            if (enabled) {
                expanded = !expanded
            }
        }
    ) {
        OutlinedTextField(
            value = if (selected.isNotBlank()) selected else placeholder,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            profiles.forEach { profileName ->
                DropdownMenuItem(
                    text = { Text(profileName) },
                    onClick = {
                        expanded = false
                        onSelected(profileName)
                    }
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ConfigScreen(
    initial: AppSettings,
    initialProfiles: List<String>,
    initialLastProfile: String,
    onSave: (AppSettings) -> Unit,
    onLoadProfile: (String) -> AppSettings?,
    onSaveProfile: (String, AppSettings) -> List<String>,
    onRenameProfile: (String, String, AppSettings) -> RenameProfileResult,
    onDeleteProfile: (String) -> List<String>,
    onMessage: (String) -> Unit,
    onBack: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var apiProvider by remember { mutableStateOf(AiProvider.fromId(initial.translateProvider)) }
    var apiBaseUrl by remember { mutableStateOf(initial.translateBaseUrl.ifBlank { initial.ocrBaseUrl }) }
    var apiModel by remember { mutableStateOf(initial.translateModel.ifBlank { initial.ocrModel }) }
    var apiKey by remember { mutableStateOf(initial.translateApiKey.ifBlank { initial.ocrApiKey }) }

    var enabled by remember { mutableStateOf(initial.enabled) }
    var ignoreSimplified by remember { mutableStateOf(initial.ignoreSimplifiedChinese) }
    var enableThinking by remember { mutableStateOf(initial.enableThinking) }
    var thinkingBudgetText by remember { mutableStateOf(initial.thinkingBudget.toString()) }

    var profileName by remember { mutableStateOf(initialLastProfile) }
    var savedProfiles by remember { mutableStateOf(initialProfiles) }
    var selectedProfile by remember {
        mutableStateOf(
            when {
                initialLastProfile.isNotBlank() -> initialLastProfile
                initialProfiles.isNotEmpty() -> initialProfiles.first()
                else -> ""
            }
        )
    }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    var isCreateMode by remember { mutableStateOf(selectedProfile.isBlank()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    fun dismissKeyboardAndFocus() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    fun applyPreset(next: AiProvider, force: Boolean) {
        val previous = apiProvider
        apiProvider = next
        if (force || apiBaseUrl.isBlank() || apiBaseUrl == previous.defaultBaseUrl) {
            apiBaseUrl = next.defaultBaseUrl
        }
        if (force || apiModel.isBlank() || apiModel == previous.defaultModel) {
            apiModel = next.defaultModel
        }
        if (!next.supportsThinking) {
            enableThinking = false
        }
    }

    fun applySettings(settings: AppSettings) {
        apiProvider = AiProvider.fromId(settings.translateProvider)
        apiBaseUrl = settings.translateBaseUrl.ifBlank { settings.ocrBaseUrl }
        apiModel = settings.translateModel.ifBlank { settings.ocrModel }
        apiKey = settings.translateApiKey.ifBlank { settings.ocrApiKey }

        enabled = settings.enabled
        ignoreSimplified = settings.ignoreSimplifiedChinese
        enableThinking = settings.enableThinking
        thinkingBudgetText = settings.thinkingBudget.toString()
    }

    fun selectProfile(profile: String) {
        val trimmed = profile.trim()
        if (trimmed.isEmpty()) return
        selectedProfile = trimmed
        profileName = trimmed
        isCreateMode = false
        onLoadProfile(trimmed)?.let { loaded -> applySettings(loaded) }
    }

    fun enterCreateMode() {
        selectedProfile = ""
        profileName = ""
        isCreateMode = true
        profileMenuExpanded = false
        showDeleteConfirm = false
    }

    fun buildCurrentSettings(): AppSettings {
        val thinkingBudget = thinkingBudgetText.toIntOrNull()?.coerceIn(1024, 131072) ?: 8192
        return AppSettings(
            ocrProvider = apiProvider.id,
            ocrBaseUrl = apiBaseUrl,
            ocrModel = apiModel,
            ocrApiKey = apiKey,
            translateProvider = apiProvider.id,
            translateBaseUrl = apiBaseUrl,
            translateModel = apiModel,
            translateApiKey = apiKey,
            enabled = enabled,
            ignoreSimplifiedChinese = ignoreSimplified,
            enableThinking = apiProvider.supportsThinking && enableThinking,
            thinkingBudget = thinkingBudget
        )
    }

    @Composable
    fun sectionTitle(title: String) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(6.dp))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .pointerInput(Unit) { detectTapGestures(onTap = { dismissKeyboardAndFocus() }) }
            .padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(text = "配置页", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))

        Text(text = "配置档案")
        Spacer(modifier = Modifier.height(6.dp))
        ExposedDropdownMenuBox(
            expanded = profileMenuExpanded,
            onExpandedChange = {
                dismissKeyboardAndFocus()
                profileMenuExpanded = !profileMenuExpanded
            }
        ) {
            OutlinedTextField(
                value = selectedProfile,
                onValueChange = {},
                readOnly = true,
                label = { Text("选择已保存配置") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = profileMenuExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            DropdownMenu(expanded = profileMenuExpanded, onDismissRequest = { profileMenuExpanded = false }) {
                savedProfiles.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item) },
                        onClick = {
                            dismissKeyboardAndFocus()
                            selectProfile(item)
                            profileMenuExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = profileName,
            onValueChange = { profileName = it },
            label = { Text(if (isCreateMode) "新配置名称" else "配置名称（可改名）") },
            trailingIcon = {
                if (profileName.isNotBlank()) {
                    IconButton(onClick = { profileName = "" }) { Text("×") }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                dismissKeyboardAndFocus()
                enterCreateMode()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("新建配置") }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { showDeleteConfirm = true },
            enabled = !isCreateMode && selectedProfile.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("删除当前配置") }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (isCreateMode) "当前模式：新建" else "当前模式：编辑 $selectedProfile",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(12.dp))
        sectionTitle("API 配置")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiProvider.entries.forEach { item ->
                FilterChip(
                    selected = apiProvider == item,
                    onClick = { applyPreset(item, force = false) },
                    label = { Text(item.label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = apiBaseUrl,
            onValueChange = { apiBaseUrl = it },
            label = { Text("Base URL") },
            trailingIcon = {
                if (apiBaseUrl.isNotBlank()) {
                    IconButton(onClick = { apiBaseUrl = "" }) { Text("×") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { dismissKeyboardAndFocus() }),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = apiModel,
            onValueChange = { apiModel = it },
            label = { Text("Model") },
            trailingIcon = {
                if (apiModel.isNotBlank()) {
                    IconButton(onClick = { apiModel = "" }) { Text("×") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { dismissKeyboardAndFocus() }),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            trailingIcon = {
                if (apiKey.isNotBlank()) {
                    IconButton(onClick = { apiKey = "" }) { Text("×") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { dismissKeyboardAndFocus() }),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("启用翻译")
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("忽略简体中文")
            Switch(checked = ignoreSimplified, onCheckedChange = { ignoreSimplified = it })
        }

        if (apiProvider.supportsThinking) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("启用思考模式（更慢）")
                Switch(checked = enableThinking, onCheckedChange = { enableThinking = it })
            }
            OutlinedTextField(
                value = thinkingBudgetText,
                onValueChange = { input -> thinkingBudgetText = input.filter { it.isDigit() }.take(6) },
                label = { Text("Thinking Budget（建议 2048~32768）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { dismissKeyboardAndFocus() }),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            enableThinking = false
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                dismissKeyboardAndFocus()
                val current = buildCurrentSettings()
                val normalizedProfileName = profileName.trim().ifBlank { "${apiProvider.label}-${apiModel}" }
                val activeProfile = selectedProfile.trim()
                val exists = savedProfiles.contains(normalizedProfileName)

                if (isCreateMode && exists) {
                    onMessage("名称已存在")
                    return@Button
                }
                if (!isCreateMode && activeProfile.isNotBlank() && normalizedProfileName != activeProfile && exists) {
                    onMessage("名称已存在")
                    return@Button
                }

                onSave(current)
                profileName = normalizedProfileName

                if (isCreateMode || activeProfile.isBlank()) {
                    savedProfiles = onSaveProfile(normalizedProfileName, current)
                    selectedProfile = normalizedProfileName
                    isCreateMode = false
                    onMessage("已新建配置：$normalizedProfileName")
                    return@Button
                }

                if (normalizedProfileName == activeProfile) {
                    savedProfiles = onSaveProfile(activeProfile, current)
                    selectedProfile = activeProfile
                    onMessage("已更新配置：$activeProfile")
                    return@Button
                }

                when (onRenameProfile(activeProfile, normalizedProfileName, current)) {
                    RenameProfileResult.SUCCESS -> {
                        savedProfiles = onSaveProfile(normalizedProfileName, current)
                        selectedProfile = normalizedProfileName
                        isCreateMode = false
                        onMessage("已重命名：$activeProfile -> $normalizedProfileName")
                    }

                    RenameProfileResult.TARGET_EXISTS -> onMessage("名称已存在")
                    RenameProfileResult.SOURCE_NOT_FOUND -> onMessage("原配置不存在")
                    RenameProfileResult.INVALID_NAME -> onMessage("名称无效")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存配置") }

        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回主界面") }
        Spacer(modifier = Modifier.height(24.dp))

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("删除配置") },
                text = { Text("确认删除当前配置“$selectedProfile”？") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = selectedProfile.trim()
                            showDeleteConfirm = false
                            if (target.isBlank()) return@TextButton
                            savedProfiles = onDeleteProfile(target)
                            if (savedProfiles.isNotEmpty()) {
                                selectProfile(savedProfiles.first())
                            } else {
                                enterCreateMode()
                            }
                            onMessage("已删除配置：$target")
                        }
                    ) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                }
            )
        }
    }
}
