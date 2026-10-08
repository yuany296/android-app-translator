package com.manga.translator.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.manga.translator.app.ui.MtCard
import com.manga.translator.app.ui.MtChevronDownIcon
import com.manga.translator.app.ui.MtDivider
import com.manga.translator.app.ui.MtDropdownItem
import com.manga.translator.app.ui.MtDropdownMenu
import com.manga.translator.app.ui.MtDangerButton
import com.manga.translator.app.ui.MtFilledField
import com.manga.translator.app.ui.MtListRow
import com.manga.translator.app.ui.MtOutlineButton
import com.manga.translator.app.ui.MtPlayIcon
import com.manga.translator.app.ui.MtPrimaryButton
import com.manga.translator.app.ui.MtSwapIcon
import com.manga.translator.app.ui.MtTonalButton
import com.manga.translator.app.ui.theme.MtColors
import com.manga.translator.store.AiProvider
import com.manga.translator.store.AppLanguage
import com.manga.translator.store.AppSettings
import com.manga.translator.store.OcrEngine
import com.manga.translator.store.OcrSpeedMode
import com.manga.translator.store.TranslateEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ML Kit 本地翻译模型下载状态（决定首页状态行的文案与入口）。 */
sealed interface MlKitDownloadState {
    data object Checking : MlKitDownloadState
    data object Idle : MlKitDownloadState
    data object Downloading : MlKitDownloadState
    data object Ready : MlKitDownloadState
    data class Failed(val reason: String) : MlKitDownloadState
}

/**
 * 首页（设计稿 `06-首页改法`）。
 *
 * 结构：页头 → 状态行（**只在需要时出现**）→ 语言对 → OCR服务 → 翻译服务 → 底部主按钮。
 *
 * 「翻译设置」页已并入本页（2026-09-17，4→3）；2026-09-28 底栏新增「浏览器」tab（内嵌翻译入口）。
 */
@Composable
fun LauncherScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val repo = ServiceLocator.settingsRepository

    var profileNames by remember { mutableStateOf(repo.getProfileNames()) }
    var selectedProfile by remember {
        mutableStateOf(
            repo.getLastTranslateProfileName().takeIf { it.isNotEmpty() }
                ?: repo.getProfileNames().firstOrNull().orEmpty()
        )
    }
    var profileSheetOpen by remember { mutableStateOf(false) }
    var baiduSheetOpen by remember { mutableStateOf(false) }
    var formOpen by remember { mutableStateOf(false) }
    // 百度两项凭据都填了才算配好（与状态行、卡片值共用）。
    val baiduConfigured = settings.baiduTranslateAppId.isNotBlank() &&
        settings.baiduTranslateSecretKey.isNotBlank()
    var formEditingName by remember { mutableStateOf("") }

    var running by remember { mutableStateOf(false) }
    var mlKitState by remember { mutableStateOf<MlKitDownloadState>(MlKitDownloadState.Checking) }

    // 服务是否在跑：用于主按钮在「启动服务 / 停止服务」之间切换。
    LaunchedEffect(Unit) {
        while (true) {
            running = CaptureSessionGate.isCaptureServiceRunning(context)
            delay(1500)
        }
    }

    // 2026-09-15：切换源语言 / OCR 引擎后就地预热 Paddle 模型，提前消化首次识别的等待。
    LaunchedEffect(settings.sourceLanguage, settings.ocrEngine) {
        if (OcrEngine.fromId(settings.ocrEngine) != OcrEngine.PADDLE) return@LaunchedEffect
        val detector = ServiceLocator.localTextDetector
        if (detector.isReady(settings.sourceLanguage)) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { detector.prewarm(settings.sourceLanguage) }
        }
    }

    // ML Kit 本地翻译模型状态（决定状态行内容）。
    LaunchedEffect(settings.sourceLanguage, settings.targetLanguage, settings.translateEngine) {
        if (TranslateEngine.fromId(settings.translateEngine) != TranslateEngine.ML_KIT) {
            mlKitState = MlKitDownloadState.Ready
            return@LaunchedEffect
        }
        mlKitState = MlKitDownloadState.Checking
        val downloaded = ServiceLocator.localTranslator.isDownloaded(
            settings.sourceLanguage,
            settings.targetLanguage
        )
        mlKitState = if (downloaded) MlKitDownloadState.Ready else MlKitDownloadState.Idle
    }

    val sourceLang = AppLanguage.fromId(settings.sourceLanguage)
    val targetLang = AppLanguage.fromId(settings.targetLanguage)
    val engine = TranslateEngine.fromId(settings.translateEngine)
    val pairLabel = "${sourceLang.label} → ${targetLang.label}"

    fun startDownload() {
        scope.launch {
            mlKitState = MlKitDownloadState.Downloading
            val error = ServiceLocator.localTranslator.downloadModel(
                settings.sourceLanguage,
                settings.targetLanguage
            )
            mlKitState = if (error == null) {
                MlKitDownloadState.Ready
            } else {
                MlKitDownloadState.Failed(error)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HomeHeader()

            HomeStatusLine(
                engine = engine,
                pairLabel = pairLabel,
                mlKitState = mlKitState,
                selectedProfile = selectedProfile,
                baiduConfigured = baiduConfigured,
                onOpenProfiles = { profileSheetOpen = true },
                onOpenBaiduConfig = { baiduSheetOpen = true },
                onDownloadModel = { startDownload() },
                onRetryDownload = { startDownload() }
            )

            LanguagePairCard(
                sourceLabel = sourceLang.label,
                targetLabel = targetLang.label,
                onPickSource = { onSettingsChange(settings.copy(sourceLanguage = it.id)) },
                onPickTarget = { onSettingsChange(settings.copy(targetLanguage = it.id)) },
                onSwap = {
                    onSettingsChange(
                        settings.copy(
                            sourceLanguage = settings.targetLanguage,
                            targetLanguage = settings.sourceLanguage
                        )
                    )
                }
            )

            SectionTitle("OCR服务")
            // 服务设置卡用直角（2026-09-18 用户要求）。
            MtCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(0.dp)) {
                SelectRow(
                    name = "OCR 引擎",
                    value = OcrEngine.fromId(settings.ocrEngine).label,
                    options = OcrEngine.entries.map { it.label },
                    showDivider = OcrEngine.fromId(settings.ocrEngine) == OcrEngine.PADDLE,
                    onSelected = { index ->
                        onSettingsChange(settings.copy(ocrEngine = OcrEngine.entries[index].id))
                    }
                )
                if (OcrEngine.fromId(settings.ocrEngine) == OcrEngine.PADDLE) {
                    SelectRow(
                        name = "速度模式",
                        value = OcrSpeedMode.fromId(settings.ocrSpeedMode).label,
                        options = OcrSpeedMode.entries.map { it.label },
                        onSelected = { index ->
                            onSettingsChange(settings.copy(ocrSpeedMode = OcrSpeedMode.entries[index].id))
                        }
                    )
                }
            }

            SectionTitle("翻译服务")
            // 服务设置卡用直角（2026-09-18 用户要求）。
            MtCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(0.dp)) {
                SelectRow(
                    name = "翻译引擎",
                    value = engine.label,
                    options = TranslateEngine.entries.map { it.label },
                    showDivider = true,
                    onSelected = { index ->
                        onSettingsChange(settings.copy(translateEngine = TranslateEngine.entries[index].id))
                    }
                )
                if (engine == TranslateEngine.CLOUD) {
                    MtListRow(
                        name = "配置档案",
                        value = selectedProfile.ifEmpty { "未配置" },
                        onClick = { profileSheetOpen = true }
                    )
                }
                if (engine == TranslateEngine.BAIDU) {
                    // 百度凭据：卡片里只留一行，点开底部弹层填
                    // （2026-09-18 方案 1 —— 两个输入框放在行内会把卡片撑得和别的行不协调，画布 24:8）。
                    MtListRow(
                        name = "百度配置",
                        value = if (baiduConfigured) "已配置" else "未配置",
                        onClick = { baiduSheetOpen = true }
                    )
                }
            }
        }

        HomeActionBar(
            running = running,
            onStart = onStart,
            onStop = onStop
        )
    }

    if (profileSheetOpen) {
        ProfileSheet(
            profiles = profileNames,
            selected = selectedProfile,
            onSelect = { name ->
                repo.getProfile(name)?.let { onSettingsChange(it) }
                repo.setLastTranslateProfileName(name)
                selectedProfile = name
                profileSheetOpen = false
            },
            onCreate = {
                profileSheetOpen = false
                formEditingName = ""
                formOpen = true
            },
            onEdit = { name ->
                profileSheetOpen = false
                formEditingName = name
                formOpen = true
            },
            onRename = { old, new ->
                repo.renameProfile(old, new)
                profileNames = repo.getProfileNames()
                if (selectedProfile == old) selectedProfile = new
            },
            onDuplicate = { name ->
                var target = "$name 副本"
                var index = 2
                while (profileNames.contains(target)) {
                    target = "$name 副本 $index"
                    index++
                }
                repo.getProfile(name)?.let { repo.saveProfile(target, it) }
                profileNames = repo.getProfileNames()
            },
            onDelete = { name ->
                repo.deleteProfile(name)
                profileNames = repo.getProfileNames()
                if (selectedProfile == name) {
                    val next = repo.getProfileNames().firstOrNull().orEmpty()
                    selectedProfile = next
                    val nextSettings = next.takeIf { it.isNotEmpty() }?.let { repo.getProfile(it) }
                    if (nextSettings != null) {
                        onSettingsChange(nextSettings)
                    } else {
                        // 删掉的是最后一条配置（2026-10-02：「当前使用」的行现在也有菜单，
                        // 才可能走到这里）：把翻译 API 配置一并清空。否则首页显示「配置档案：未配置」，
                        // 后台却还在用已删除档案的 baseUrl/key（页面上说的和实际做的不一致）。
                        // 注意 provider 记住的 key 不受影响（`saveProviderApiKey`），重新建同厂商档案会自动带回来。
                        onSettingsChange(
                            settings.copy(
                                translateProvider = AiProvider.OPENAI.id,
                                translateBaseUrl = AiProvider.OPENAI.defaultBaseUrl,
                                translateModel = AiProvider.OPENAI.defaultModel,
                                translateApiKey = "",
                                enableThinking = false
                            )
                        )
                    }
                }
            },
            onDismiss = { profileSheetOpen = false }
        )
    }

    if (formOpen) {
        ProfileFormScreen(
            editingName = formEditingName,
            existingNames = profileNames,
            initial = repo.getProfile(formEditingName) ?: settings,
            providerApiKey = { repo.getProviderApiKey(it) },
            onRememberApiKey = { providerId, key -> repo.saveProviderApiKey(providerId, key) },
            onSaved = { name, updated ->
                repo.saveProfile(name, updated)
                repo.setLastTranslateProfileName(name)
                profileNames = repo.getProfileNames()
                selectedProfile = name
                onSettingsChange(updated)
                formOpen = false
            },
            onDismiss = { formOpen = false }
        )
    }

    if (baiduSheetOpen) {
        BaiduConfigSheet(
            appId = settings.baiduTranslateAppId,
            secretKey = settings.baiduTranslateSecretKey,
            onSave = { appId, secretKey ->
                onSettingsChange(
                    settings.copy(
                        baiduTranslateAppId = appId,
                        baiduTranslateSecretKey = secretKey
                    )
                )
                baiduSheetOpen = false
            },
            onDismiss = { baiduSheetOpen = false }
        )
    }
}

// ---------------------------------------------------------------------------
// 首页局部组件
// ---------------------------------------------------------------------------

@Composable
private fun HomeHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "安卓漫画翻译器",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MtColors.TextPrimary
        )
        Text(
            text = "截图识别 → 翻译 → 覆写回漫画上",
            fontSize = 12.sp,
            color = MtColors.TextTertiary
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        color = MtColors.TextPrimary
    )
}

/**
 * 状态行：**只在需要用户感知/动手时出现**，一切正常时不占位。
 * 颜色即信号：蓝＝进行中 / 橙＝需要你动手 / 红＝出错 / 灰＝纯提示。
 */
@Composable
private fun HomeStatusLine(
    engine: TranslateEngine,
    pairLabel: String,
    mlKitState: MlKitDownloadState,
    selectedProfile: String,
    baiduConfigured: Boolean,
    onOpenProfiles: () -> Unit,
    onOpenBaiduConfig: () -> Unit,
    onDownloadModel: () -> Unit,
    onRetryDownload: () -> Unit
) {
    var text: String? = null
    var color: Color = MtColors.Brand
    var action: String? = null
    var onAction: (() -> Unit)? = null

    when (engine) {
        TranslateEngine.ML_KIT -> when (mlKitState) {
            MlKitDownloadState.Checking -> Unit // 检查中不打扰用户
            MlKitDownloadState.Ready -> Unit     // 正常态：不显示
            MlKitDownloadState.Idle -> {
                text = "$pairLabel 需要先下载翻译模型"
                color = MtColors.Warning
                action = "下载模型"
                onAction = onDownloadModel
            }
            MlKitDownloadState.Downloading -> {
                text = "正在下载 $pairLabel 模型，可能需要几分钟…"
                color = MtColors.Brand
            }
            is MlKitDownloadState.Failed -> {
                text = "下载失败：${mlKitState.reason}"
                color = MtColors.Danger
                action = "重试下载"
                onAction = onRetryDownload
            }
        }
        TranslateEngine.BAIDU -> if (!baiduConfigured) {
            text = "百度翻译还没填 APP ID / 密钥"
            color = MtColors.Danger
            action = "去填百度配置"
            onAction = onOpenBaiduConfig
        }
        TranslateEngine.CLOUD -> if (selectedProfile.isEmpty()) {
            text = "云端 API 还没配置，先新建一个配置档案"
            color = MtColors.Danger
            action = "去配置档案"
            onAction = onOpenProfiles
        }
        else -> Unit
    }

    val message = text ?: return

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            fontSize = 13.sp,
            color = color,
            modifier = Modifier.weight(1f)
        )
        if (action != null && onAction != null) {
            Text(
                text = action,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MtColors.BrandInk,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/** 语言对：左右两个 Filled 选择框，中间一个圆形交换键（无外框）。 */
@Composable
private fun LanguagePairCard(
    sourceLabel: String,
    targetLabel: String,
    onPickSource: (AppLanguage) -> Unit,
    onPickTarget: (AppLanguage) -> Unit,
    onSwap: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LanguageBox(
            label = sourceLabel,
            modifier = Modifier.weight(1f),
            onPick = onPickSource
        )
        Row(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MtColors.BrandTint)
                .clickable(onClick = onSwap),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = MtSwapIcon,
                contentDescription = "交换语言",
                tint = MtColors.BrandInk,
                modifier = Modifier.size(18.dp)
            )
        }
        LanguageBox(
            label = targetLabel,
            modifier = Modifier.weight(1f),
            onPick = onPickTarget
        )
    }
}

@Composable
private fun LanguageBox(
    label: String,
    modifier: Modifier = Modifier,
    onPick: (AppLanguage) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableStateOf(0) }
    Box(modifier = modifier.onGloballyPositioned { anchorWidth = it.size.width }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MtColors.SurfaceMuted)
                .clickable { expanded = true }
                .padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 15.sp,
                color = MtColors.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            Icon(
                imageVector = MtChevronDownIcon,
                contentDescription = null,
                tint = MtColors.TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
        MtDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            anchorWidthPx = anchorWidth
        ) {
            AppLanguage.entries.forEach { item ->
                MtDropdownItem(
                    text = item.label,
                    onClick = {
                        expanded = false
                        onPick(item)
                    }
                )
            }
        }
    }
}

/** 设置行：点击弹出下拉选择（按设计稿的「名称 + 当前值 + 箭头」形态）。 */
@Composable
private fun SelectRow(
    name: String,
    value: String,
    options: List<String>,
    onSelected: (Int) -> Unit,
    showDivider: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { anchorWidth = it.size.width }
    ) {
        MtListRow(
            name = name,
            value = value,
            onClick = { expanded = true },
            showDivider = showDivider
        )
        MtDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            anchorWidthPx = anchorWidth
        ) {
            options.forEachIndexed { index, option ->
                MtDropdownItem(
                    text = option,
                    onClick = {
                        expanded = false
                        onSelected(index)
                    }
                )
            }
        }
    }
}

/** 底部操作区：白卡紧贴底部导航，只留一个填充主按钮。 */
@Composable
private fun HomeActionBar(
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        MtCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 25.dp, vertical = 14.dp)
            ) {
                if (running) {
                    MtPrimaryButton(
                        text = "停止服务",
                        onClick = onStop,
                        container = MtColors.DangerTint,
                        contentColor = MtColors.DangerTextSoft
                    )
                } else {
                    MtPrimaryButton(
                        text = "启动服务",
                        onClick = onStart,
                        icon = MtPlayIcon
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 配置档案：底部弹层 + 新建/编辑表单
// ---------------------------------------------------------------------------

@Composable
private fun ProfileSheet(
    profiles: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(MtColors.Surface)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 拖拽条
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MtColors.Border)
                    )
                }
                Text(
                    text = "配置档案",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MtColors.TextPrimary
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    if (profiles.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = "还没有配置档案",
                                fontSize = 13.sp,
                                color = MtColors.TextTertiary
                            )
                        }
                    }
                    profiles.forEachIndexed { index, name ->
                        ProfileRow(
                            name = name,
                            active = name == selected,
                            showDivider = index != profiles.lastIndex,
                            onSelect = { onSelect(name) },
                            onEdit = { onEdit(name) },
                            onRename = { renameTarget = name },
                            onDuplicate = { onDuplicate(name) },
                            onDelete = { deleteTarget = name }
                        )
                    }
                }

                MtTonalButton(
                    text = "新建配置",
                    onClick = onCreate,
                    icon = Icons.Filled.Add,
                    modifier = Modifier.fillMaxWidth(),
                    height = 46.dp,
                    fontSize = 14
                )
            }
        }
    }

    renameTarget?.let { target ->
        ProfileNameDialog(
            title = "重命名配置",
            initial = target,
            confirmText = "保存",
            onConfirm = { newName ->
                if (newName.isNotBlank() && newName != target) onRename(target, newName.trim())
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除配置") },
            text = { Text("确定删除配置「$target」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    deleteTarget = null
                }) { Text("删除", color = MtColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ProfileRow(
    name: String,
    active: Boolean,
    showDivider: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clickable(onClick = onSelect),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = name,
                fontSize = 15.sp,
                color = MtColors.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            if (active) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "当前使用",
                    tint = MtColors.Brand,
                    modifier = Modifier.size(20.dp)
                )
            }
            // 「更多」菜单对**所有**行都显示（2026-10-02 用户反馈「我发现无法编辑配置」）：
            // 之前只有非当前使用的行才有这个入口，而新建配置后它立刻变成当前使用，
            // 结果「刚建的配置反而编辑不了」。「当前使用」现在只用勾标表示，不再挤掉菜单。
            Box {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "更多",
                    tint = MtColors.TextTertiary,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { menuOpen = true }
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MtDropdownItem(text = "编辑", onClick = {
                        menuOpen = false
                        onEdit()
                    })
                    MtDropdownItem(text = "重命名", onClick = {
                        menuOpen = false
                        onRename()
                    })
                    MtDropdownItem(text = "复制", onClick = {
                        menuOpen = false
                        onDuplicate()
                    })
                    MtDropdownItem(text = "删除", onClick = {
                        menuOpen = false
                        onDelete()
                    })
                }
            }
        }
        if (showDivider) MtDivider()
    }
}

@Composable
private fun ProfileNameDialog(
    title: String,
    initial: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = MtColors.TextPrimary),
                cursorBrush = SolidColor(MtColors.Brand),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MtColors.SurfaceMuted)
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 百度翻译凭据：底部弹层（画布 `24:55`）。
 *
 * 卡片里只留一行「百度配置」，两项凭据放这里填 —— 行内直铺两个输入框会把卡片撑得和别的行不协调
 * （2026-09-18 方案 1）。标签在框外，与配置表单一致。
 */
@Composable
private fun BaiduConfigSheet(
    appId: String,
    secretKey: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var idText by remember { mutableStateOf(appId) }
    var keyText by remember { mutableStateOf(secretKey) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(MtColors.Surface)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 拖拽条
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MtColors.Border)
                    )
                }
                Text(
                    text = "百度翻译配置",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MtColors.TextPrimary
                )
                Text(
                    text = "在百度翻译开放平台申请应用后填入，填好即可用免费额度。",
                    fontSize = 12.sp,
                    color = MtColors.TextTertiary
                )
                MtInputField(
                    label = "APP ID",
                    value = idText,
                    onValueChange = { idText = it },
                    labelOutside = true
                )
                MtInputField(
                    label = "密钥（Secret Key）",
                    value = keyText,
                    onValueChange = { keyText = it },
                    labelOutside = true
                )
                MtPrimaryButton(
                    text = "保存",
                    onClick = { onSave(idText.trim(), keyText.trim()) }
                )
            }
        }
    }
}

/**
 * 新建 / 编辑配置表单（设计稿 `19-新建配置表单`）。
 *
 * 选完厂商后 Base URL 与 Model 会自动带出，用户通常只需要粘贴 API Key。
 */
@Composable
private fun ProfileFormScreen(
    editingName: String,
    existingNames: List<String>,
    initial: AppSettings,
    // 取某厂商记住的 API Key（空串 = 没记过）／把 API Key 记到某厂商名下。
    providerApiKey: (String) -> String,
    onRememberApiKey: (String, String) -> Unit,
    onSaved: (String, AppSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val isEdit = editingName.isNotEmpty()

    var name by remember { mutableStateOf(editingName) }
    var provider by remember { mutableStateOf(AiProvider.fromId(initial.translateProvider)) }
    // 新建时直接把厂商的默认 Base URL / Model 带出来（对应设计稿 19：选完厂商自动带出，用户通常只需粘 API Key）。
    var baseUrl by remember { mutableStateOf(initial.translateBaseUrl.ifBlank { provider.defaultBaseUrl }) }
    // API Key 跟随厂商（2026-09-18 用户要求）：编辑时用配置自己的 key，新建时用**该厂商**记住的 key，
    // 不再继承上一条配置的 key（否则会出现「填写一次就都是同一个 key」）。
    var apiKey by remember {
        mutableStateOf(if (isEdit) initial.translateApiKey else providerApiKey(provider.id))
    }
    var model by remember { mutableStateOf(initial.translateModel.ifBlank { provider.defaultModel }) }
    // 思考功能（2026-10-02 用户要求「加一个参数表示是否开启思考功能」）：
    // 有思考会慢很多，所以**新建默认关**；编辑时沿用该档案已保存的值。
    // 只有支持该参数的厂商（千问 / GLM）才允许打开，见 thinkingSupported。
    var thinking by remember { mutableStateOf(if (isEdit) initial.enableThinking else false) }
    val thinkingSupported = provider.supportsThinking
    var providerMenu by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val nameTaken = !isEdit && existingNames.contains(name.trim())
    val canSave = name.isNotBlank() && baseUrl.isNotBlank() && apiKey.isNotBlank() && !nameTaken

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MtColors.Surface)
        ) {
            // 顶栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(start = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MtColors.TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = if (isEdit) "编辑配置" else "新建配置 · 填写信息",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MtColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "取消",
                    fontSize = 14.sp,
                    color = MtColors.TextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "选完厂商后 Base URL 与 Model 会自动带出来，通常只需要粘贴 API Key。",
                    fontSize = 12.sp,
                    color = MtColors.TextTertiary
                )
                MtInputField(
                    label = "配置名称",
                    value = name,
                    onValueChange = { name = it },
                    labelOutside = true
                )

                var providerAnchorWidth by remember { mutableStateOf(0) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { providerAnchorWidth = it.size.width }
                ) {
                    MtFilledField(
                        label = "厂商",
                        value = provider.label,
                        onClick = { providerMenu = true },
                        trailing = {
                            Icon(
                                imageVector = MtChevronDownIcon,
                                contentDescription = null,
                                tint = MtColors.TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        labelOutside = true
                    )
                    MtDropdownMenu(
                        expanded = providerMenu,
                        onDismissRequest = { providerMenu = false },
                        anchorWidthPx = providerAnchorWidth
                    ) {
                        AiProvider.entries
                            .filter { it != AiProvider.BAIDU }
                            .forEach { item ->
                                MtDropdownItem(
                                    text = item.label,
                                    onClick = {
                                        providerMenu = false
                                        // API Key 跟随厂商：先把当前这把记到旧厂商名下，再带出新厂商记住的 key。
                                        if (apiKey.isNotBlank()) onRememberApiKey(provider.id, apiKey)
                                        apiKey = providerApiKey(item.id)
                                        provider = item
                                        baseUrl = item.defaultBaseUrl
                                        model = item.defaultModel
                                        // 切到不支持思考参数的厂商时把开关收回去，
                                        // 否则会存下一个永远不生效的 enableThinking=true。
                                        if (!item.supportsThinking) thinking = false
                                    }
                                )
                            }
                    }
                }

                MtInputField(
                    label = "Base URL",
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    labelOutside = true
                )
                MtInputField(
                    label = "API Key",
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    keyboardType = KeyboardType.Password,
                    masked = true,
                    labelOutside = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        MtInputField(
                            label = "Model",
                            value = model,
                            onValueChange = { model = it },
                            labelOutside = true
                        )
                    }
                    MtTonalButton(
                        text = if (fetching) "获取中…" else "获取模型列表",
                        onClick = {
                            if (apiKey.isBlank() || fetching) return@MtTonalButton
                            fetching = true
                            error = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        ServiceLocator.visionClient.fetchAvailableModels(
                                            baseUrl = baseUrl.trim(),
                                            apiKey = apiKey.trim()
                                        )
                                    }
                                }
                                fetching = false
                                result.onSuccess { list ->
                                    if (model.isBlank() && list.isNotEmpty()) model = list.first()
                                }.onFailure {
                                    error = "获取模型列表失败：${it.message}"
                                }
                            }
                        },
                        height = 48.dp
                    )
                }

                // 思考功能（2026-10-02）：模型「先思考再作答」更准但明显更慢，
                // 所以默认关、且只对支持该参数的厂商（千问 / GLM）可开。
                MtThinkingRow(
                    checked = thinkingSupported && thinking,
                    enabled = thinkingSupported,
                    hint = if (thinkingSupported) {
                        "开启后模型先思考再作答：更准，但明显更慢（默认关）。"
                    } else {
                        "当前厂商不支持开关（仅千问 / GLM 支持）。"
                    },
                    onCheckedChange = { thinking = it }
                )

                error?.let {
                    Text(text = it, fontSize = 12.sp, color = MtColors.Danger)
                }
                if (nameTaken) {
                    Text(
                        text = "已存在同名配置，请换一个名称。",
                        fontSize = 12.sp,
                        color = MtColors.Danger
                    )
                }

                MtPrimaryButton(
                    text = if (isEdit) "保存" else "创建配置",
                    onClick = {
                        val updated = initial.copy(
                            translateProvider = provider.id,
                            translateBaseUrl = baseUrl.trim(),
                            translateModel = model.trim(),
                            translateApiKey = apiKey.trim(),
                            // 思考开关随档案保存（thinkingBudget 沿用 initial 里的值，不在这里暴露）。
                            enableThinking = thinkingSupported && thinking
                        )
                        // 保存时把这把 key 记在该厂商名下，下次选到同一厂商就能自动带出来。
                        if (apiKey.isNotBlank()) onRememberApiKey(provider.id, apiKey)
                        onSaved(name.trim(), updated)
                    },
                    enabled = canSave,
                    icon = null
                )
                Box(modifier = Modifier.height(20.dp))
            }
        }
    }
}

/** 表单输入：浅灰底、无边框、圆角 12、高 60；11px 小标签常驻在 15px 值上方。 */
@Composable
private fun MtInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    masked: Boolean = false,
    labelOutside: Boolean = false
) {
    val focusRequester = remember { FocusRequester() }
    Column(modifier = Modifier.fillMaxWidth()) {
        // 标签放框外时，框里只留输入值（2026-09-18 用户要求）。
        if (labelOutside) {
            Text(text = label, fontSize = 13.sp, color = MtColors.TextSecondary, maxLines = 1)
            Box(modifier = Modifier.height(6.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (labelOutside) 52.dp else 60.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MtColors.SurfaceMuted)
                .clickable { focusRequester.requestFocus() }
                .padding(start = 16.dp, end = 12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            if (!labelOutside) {
                Text(text = label, fontSize = 11.sp, color = MtColors.TextSecondary, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = MtColors.TextPrimary),
                cursorBrush = SolidColor(MtColors.Brand),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (masked) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        }
    }
}

/**
 * 表单里的「思考功能」开关（2026-10-02）。
 *
 * 左侧标签 + 副标题（说明「更准但更慢」或「该厂商不支持」），右侧 Material3 Switch；
 * 配色沿用术语表页的开关（`GlossaryScreen` 的 collection 行），保证同一 App 内视觉一致。
 * 不支持的厂商（`enabled = false`）开关置灰，避免用户点开一个不生效的参数。
 */
@Composable
private fun MtThinkingRow(
    checked: Boolean,
    enabled: Boolean,
    hint: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (enabled) 56.dp else 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "思考功能",
                fontSize = 13.sp,
                color = if (enabled) MtColors.TextPrimary else MtColors.TextTertiary
            )
            Box(modifier = Modifier.height(2.dp))
            Text(
                text = hint,
                fontSize = 11.sp,
                color = MtColors.TextTertiary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MtColors.Brand,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = MtColors.SwitchOff,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}
