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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manga.translator.app.ui.MtBrowserIcon
import com.manga.translator.app.ui.MtHistoryIcon
import com.manga.translator.app.ui.MtHomeIcon
import com.manga.translator.app.ui.MtListIcon
import com.manga.translator.app.ui.theme.MangaTranslatorTheme
import com.manga.translator.app.ui.theme.MtColors
import com.manga.translator.store.GlossaryEntry
import com.manga.translator.store.SentenceCacheKey
import com.manga.translator.store.TranslationHistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 底部导航入口。「翻译设置」已并入首页（2026-09-17，4→3）；
 * 2026-09-28 新增「浏览器」tab（内嵌翻译入口，用户要求从首页卡片挪到底栏）。
 */
enum class MainTab(val label: String) {
    HOME("首页"),
    BROWSER("浏览器"),
    GLOSSARY("术语表"),
    HISTORY("翻译历史")
}

private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.HOME -> MtHomeIcon
    MainTab.BROWSER -> MtBrowserIcon
    MainTab.GLOSSARY -> MtListIcon
    MainTab.HISTORY -> MtHistoryIcon
}

/**
 * 底部导航：白底 + 顶部分隔线，选中项为浅蓝实心胶囊（图标与文字同时变主色）。
 * 视觉规格来源：设计稿 `01-术语表改版` / `14-改法-配色与组件规范` 底部导航；
 * 2026-09-28 起内嵌浏览器的「新标签页 / 历史记录页」也复用（设计稿 730751285322573）。
 */
@Composable
internal fun MainBottomBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MtColors.Surface)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(color = MtColors.Border, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MainTab.entries.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            color = if (selected) MtColors.BrandTint else Color.Transparent,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelect(index) },
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = tab.icon(),
                        contentDescription = tab.label,
                        tint = if (selected) MtColors.BrandInk else MtColors.TextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = tab.label,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MtColors.BrandInk else MtColors.TextTertiary,
                        maxLines = 1
                    )
                }
            }
        }
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
            MangaTranslatorTheme {
                var pageIndex by rememberSaveable { mutableIntStateOf(0) }
                var settings by remember { mutableStateOf(settingsRepository.getSettings()) }
                var glossaryInfos by remember {
                    mutableStateOf(ServiceLocator.glossaryRepository.listInfos())
                }
                var glossaryConflicts by remember {
                    mutableStateOf(ServiceLocator.glossaryRepository.findConflicts())
                }
                var glossaryEntries by remember { mutableStateOf(emptyList<GlossaryEntry>()) }
                var glossaryDetailCollection by remember { mutableStateOf<String?>(null) }
                var historyEntries by remember { mutableStateOf(emptyList<TranslationHistoryEntry>()) }
                val scope = rememberCoroutineScope()

                fun refreshGlossaryList() {
                    glossaryInfos = ServiceLocator.glossaryRepository.listInfos()
                    glossaryConflicts = ServiceLocator.glossaryRepository.findConflicts()
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
                    containerColor = MtColors.PageBackground,
                    bottomBar = {
                        // 浏览器页有自己的底部操作栏；术语表详情页有自己的返回栏，均隐藏主底栏。
                        val hideBottomBar = pageIndex == MainTab.BROWSER.ordinal ||
                            (pageIndex == MainTab.GLOSSARY.ordinal && glossaryDetailCollection != null)
                        if (!hideBottomBar) {
                            MainBottomBar(
                                selectedIndex = pageIndex,
                                onSelect = { pageIndex = it }
                            )
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (pageIndex) {
                            MainTab.BROWSER.ordinal -> {
                                EmbeddedBrowserScreen(
                                    onClose = { pageIndex = MainTab.HOME.ordinal },
                                    onSelectTab = { pageIndex = it }
                                )
                                // 浏览器是自足的翻译入口（悬浮球 + ⋮ 菜单「截图翻译」只截 WebView
                                // 视口，免 MediaProjection / 悬浮窗授权），不再接首页的 startCaptureFlow。
                            }

                            MainTab.HOME.ordinal -> {
                                LauncherScreen(
                                    settings = settings,
                                    onSettingsChange = {
                                        settingsRepository.saveSettings(it)
                                        settings = it
                                    },
                                    onStart = { startCaptureFlow() },
                                    onStop = {
                                        stopService(Intent(this@MainActivity, CaptureForegroundService::class.java))
                                    }
                                )
                            }

                            MainTab.GLOSSARY.ordinal -> {
                                val detail = glossaryDetailCollection
                                if (detail == null) {
                                    GlossaryCollectionsScreen(
                                        collections = glossaryInfos,
                                        conflicts = glossaryConflicts,
                                        onOpenCollection = { name ->
                                            glossaryCollectionForIo = name
                                            glossaryDetailCollection = name
                                        },
                                        onToggleActive = { name, checked ->
                                            val current = ServiceLocator.glossaryRepository.getActiveCollections()
                                            val next = if (checked) {
                                                if (current.contains(name)) current else current + name
                                            } else {
                                                current.filterNot { it == name }
                                            }
                                            ServiceLocator.glossaryRepository.setActiveCollections(next)
                                            refreshGlossaryList()
                                        },
                                        onMoveActive = { name, delta ->
                                            ServiceLocator.glossaryRepository.moveActiveCollection(name, delta)
                                            refreshGlossaryList()
                                        },
                                        onCreateCollection = { name ->
                                            if (ServiceLocator.glossaryRepository.createCollection(name)) {
                                                refreshGlossaryList()
                                                glossaryCollectionForIo = name
                                                glossaryDetailCollection = name
                                                toast("术语表「$name」已创建")
                                            } else {
                                                toast("创建失败：名称已存在或已达上限")
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
                                        onDuplicateCollection = { source, newName ->
                                            val ok = ServiceLocator.glossaryRepository
                                                .duplicateCollection(source, newName)
                                            if (ok) {
                                                refreshGlossaryList()
                                                toast("已复制为「$newName」")
                                            } else {
                                                toast("复制失败：名称已存在或已达上限")
                                            }
                                            ok
                                        },
                                        onMergeCollection = { source, target ->
                                            val report = ServiceLocator.glossaryRepository
                                                .mergeCollections(source, target)
                                            if (report.sourceRemoved) {
                                                refreshGlossaryList()
                                                toast(
                                                    "已把「${report.from}」并入「${report.into}」：" +
                                                        "新增 ${report.added} 条，跳过 ${report.skipped} 条"
                                                )
                                            } else {
                                                toast("合并失败")
                                            }
                                            report.sourceRemoved
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
                                    val defaultName = remember(pending, glossaryInfos) {
                                        com.manga.translator.store.GlossaryRepository.uniqueCollectionName(
                                            glossaryInfos.map { it.name }, pending.defaultName
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
                                    },
                                    // 改译文（2026-10-15）：历史只是快照，真正决定「下次遇到这句用什么」
                                    // 的是句子缓存，所以两边一起改 —— 只动快照的话下次命中缓存还是旧的错译。
                                    // 2026-10-15 补：改一条**已作废**的句子 = 让它重新生效（仓库里清
                                    // `invalidated`），所以提示要明说，不能让用户以为改的是一条死条目。
                                    onEditTranslation = { entryId, bubbleIndex, newText ->
                                        scope.launch {
                                            val ref = withContext(Dispatchers.IO) {
                                                val updated = ServiceLocator.translationHistoryRepository
                                                    .updateBubbleTranslation(entryId, bubbleIndex, newText)
                                                    ?: return@withContext null
                                                ServiceLocator.sentenceTranslationRepository.put(
                                                    updated.sourceLanguage,
                                                    updated.targetLanguage,
                                                    updated.originalText,
                                                    newText
                                                )
                                                updated
                                            }
                                            if (ref != null) {
                                                refreshHistory()
                                                toast(
                                                    if (ref.wasInvalidated) {
                                                        "已重新生效 · 以后遇到这句都用它"
                                                    } else {
                                                        "已更新译文 · 以后遇到这句都用它"
                                                    }
                                                )
                                            } else {
                                                toast("这条记录已不存在")
                                            }
                                        }
                                    },
                                    // 作废单句：删掉这条缓存，下次遇到重新翻译（治「原文本身就被错拼」）。
                                    // 2026-10-15：同时把历史里这句标成「已作废」并刷新列表 —— 以前只删缓存，
                                    // 列表看上去一模一样，用户以为它还能用（用户反馈）。
                                    onInvalidateBubble = { entryId, bubbleIndex ->
                                        scope.launch {
                                            val removed = withContext(Dispatchers.IO) {
                                                val ref = ServiceLocator.translationHistoryRepository
                                                    .bubbleRefAt(entryId, bubbleIndex)
                                                    ?: return@withContext null
                                                ServiceLocator.sentenceTranslationRepository.remove(
                                                    ref.sourceLanguage,
                                                    ref.targetLanguage,
                                                    ref.originalText
                                                )
                                                // 标记与删缓存必须同一段落盘：只做一半会出现
                                                // 「界面说作废了、缓存还在」或反过来。
                                                ServiceLocator.translationHistoryRepository
                                                    .markBubbleInvalidated(entryId, bubbleIndex)
                                            }
                                            if (removed != null) refreshHistory()
                                            toast(
                                                when (removed) {
                                                    null -> "这条记录已不存在"
                                                    true -> "已作废这句 · 下次遇到会重新翻译"
                                                    false -> "已标为作废 · 这句本来就没进缓存"
                                                }
                                            )
                                        }
                                    },
                                    // 作废整页：这一页所有句子一起删，适合「好几处都被合并错」的情况。
                                    onInvalidatePage = { entryId ->
                                        scope.launch {
                                            val removed = withContext(Dispatchers.IO) {
                                                val refs = ServiceLocator.translationHistoryRepository
                                                    .pageBubbleRefs(entryId)
                                                    ?: return@withContext -1
                                                val count = ServiceLocator.sentenceTranslationRepository.remove(
                                                    refs.map {
                                                        SentenceCacheKey(
                                                            it.sourceLanguage,
                                                            it.targetLanguage,
                                                            it.originalText
                                                        )
                                                    }
                                                )
                                                ServiceLocator.translationHistoryRepository
                                                    .markAllInvalidated(entryId)
                                                count
                                            }
                                            if (removed != -1) refreshHistory()
                                            toast(
                                                when {
                                                    removed < 0 -> "这条记录已不存在"
                                                    removed > 0 -> "已作废本页 $removed 句 · 下次重新翻译"
                                                    else -> "已标为作废 · 本页没有可作废的缓存"
                                                }
                                            )
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
