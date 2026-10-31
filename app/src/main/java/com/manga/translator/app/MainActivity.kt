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
import com.manga.translator.store.GlossaryImportFormat
import com.manga.translator.store.GlossaryTextDecoding
import com.manga.translator.store.GlossaryTextImport
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
    // 2026-10-31 用户裁定：底栏仍叫「翻译历史」。这一页的内容从「每页一条快照」
    // 换成了句子表本身，但对用户来说它就是这个功能的入口，名字不该跟着数据模型改。
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

/** 术语表导入 SAF 只展示这些类型，避免文件列表里全是图片/安装包。 */
private val GLOSSARY_IMPORT_MIME_TYPES = arrayOf(
    "application/json",
    "application/x-json",
    "text/plain",
    "text/csv",
    "text/comma-separated-values",
    "text/markdown",
    "text/x-markdown"
)

/** 去掉术语文件的已知扩展名，作为新术语表的默认名称。 */
private fun glossaryImportBaseName(displayName: String): String {
    val suffixes = listOf(".json", ".csv", ".md", ".markdown", ".txt")
    val lower = displayName.lowercase()
    val suffix = suffixes.firstOrNull { lower.endsWith(it) } ?: return displayName
    return displayName.dropLast(suffix.length)
}

/** 文件选择器在部分机型上不遵守 MIME 过滤，这里再按扩展名兜一道。 */
private fun isAllowedGlossaryFileName(displayName: String): Boolean {
    val extension = displayName.substringAfterLast('.', "").lowercase()
    return extension.isEmpty() ||
        extension == "json" ||
        extension == "csv" ||
        extension == "md" ||
        extension == "markdown" ||
        extension == "txt"
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
        val displayName = queryDisplayName(uri) ?: "导入术语"
        if (!isAllowedGlossaryFileName(displayName)) {
            Log.w(TAG, "glossary_import_file rejected reason=extension name=$displayName")
            toast("只支持 .json / .csv / .md / .txt 文件")
            return@registerForActivityResult
        }
        val raw = runCatching {
            contentResolver.openInputStream(uri)?.use {
                GlossaryTextDecoding.decode(it.readBytes())
            }
        }.getOrNull()
        if (raw.isNullOrBlank()) {
            toast("读取文件失败")
            return@registerForActivityResult
        }
        val preview = GlossaryTextImport.previewCount(raw)
        if (preview <= 0) {
            Log.w(TAG, "glossary_import_file rejected reason=no_terms bytes=${raw.length}")
            toast("文件里没有识别到术语，支持 JSON / CSV / Markdown 表格")
            return@registerForActivityResult
        }
        val baseName = glossaryImportBaseName(displayName).ifBlank { "导入术语" }
        val format = GlossaryTextImport.detect(raw)
        Log.i(TAG, "glossary_import_file name=$displayName format=$format terms=$preview")
        importPending = ImportPending(raw = raw, defaultName = baseName)
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
        val defaultName: String
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
                // 首次读盘完成前为 true，历史页据此显示「加载中」而不是空态（2026-10-22 用户反馈）。
                var historyLoading by remember { mutableStateOf(true) }
                // 2026-10-31：句子表（取代「翻译历史」快照视图）。界面直接读缓存本身，
                // 看到的和真正生效的是同一份数据。
                var sentenceEntries by remember { mutableStateOf(emptyList<com.manga.translator.store.SentenceTranslation>()) }
                var sentencesLoading by remember { mutableStateOf(true) }
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

                fun refreshSentences() {
                    scope.launch {
                        sentenceEntries = withContext(Dispatchers.IO) {
                            ServiceLocator.sentenceTranslationRepository.all()
                        }
                    }
                }

                // ---- AI 术语提取（2026-10-31）----
                // 用户在句子列表里勾几句 → 这里只做「拿候选」，入库要等他在弹层里确认。
                var termSheetOpen by remember { mutableStateOf(false) }
                var termLoading by remember { mutableStateOf(false) }
                var termError by remember { mutableStateOf<String?>(null) }
                var termSuggestions by remember {
                    mutableStateOf(emptyList<com.manga.translator.translate.TermSuggestion>())
                }
                var termSentenceCount by remember { mutableStateOf(0) }
                var termTargets by remember {
                    mutableStateOf(emptyList<com.manga.translator.store.SentenceTranslation>())
                }
                var termTarget by remember { mutableStateOf("") }
                var termExisting by remember { mutableStateOf(emptySet<String>()) }

                fun refreshTermExisting(collection: String) {
                    termExisting = runCatching {
                        ServiceLocator.glossaryRepository.getEntries(collection)
                            .map { it.source.lowercase() }
                            .toSet()
                    }.getOrDefault(emptySet())
                }

                suspend fun runTermExtraction(
                    targets: List<com.manga.translator.store.SentenceTranslation>,
                    toast: (String) -> Unit
                ) {
                    // 术语提取有自己的档案指针（首页「AI 术语提取」区块）：
                    // 空 = 跟随翻译配置。翻译走百度 / ML Kit 时这里仍可单独指一个云端档案。
                    val api = ServiceLocator.resolveTermApiConfig()
                    if (api == null) {
                        toast("术语提取要云端 API · 去首页「AI 术语提取」选个配置档案")
                        return
                    }
                    // 只把**原文**交给模型：它要判断哪个词是专有名词，看译文会被自己的译名带偏。
                    val sentences = targets.map { it.originalText.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                    if (sentences.isEmpty()) {
                        toast("选中的句子没有原文")
                        return
                    }
                    val collections = runCatching {
                        ServiceLocator.glossaryRepository.listCollections()
                    }.getOrDefault(emptyList())
                    val target = termTarget.ifBlank { collections.firstOrNull().orEmpty() }
                    termTarget = target
                    termSentenceCount = sentences.size
                    termTargets = targets
                    termSuggestions = emptyList()
                    termError = null
                    termLoading = true
                    termSheetOpen = true
                    if (target.isNotBlank()) refreshTermExisting(target)
                    try {
                        val result = withContext(Dispatchers.IO) {
                            ServiceLocator.visionClient.extractTerms(
                                provider = api.providerId,
                                baseUrl = api.baseUrl,
                                model = api.model,
                                apiKey = api.apiKey,
                                sentences = sentences,
                                sourceLanguageId = targets.first().sourceLanguage,
                                targetLanguageId = targets.first().targetLanguage,
                                enableThinking = api.enableThinking,
                                thinkingBudget = api.thinkingBudget
                            )
                        }
                        termSuggestions = result
                    } catch (err: Exception) {
                        termError = err.message?.takeIf { it.isNotBlank() } ?: "网络或模型返回异常"
                    } finally {
                        termLoading = false
                    }
                }

                // 一次性补齐旧句子的「来自哪一页 / 什么时候翻的」（2026-10-31，幂等）：
                // 不补的话「翻译历史 → 按页」会把改造前的句子全糊成一组「来源未知」。
                LaunchedEffect(Unit) {
                    val filled = withContext(Dispatchers.IO) {
                        runCatching { ServiceLocator.backfillSentenceOrigin() }
                            .onFailure { Log.w(TAG, "sentence_origin_backfill_fail", it) }
                            .getOrDefault(-1)
                    }
                    Log.i(TAG, "sentence_origin_backfill result=$filled")
                    if (filled > 0) refreshSentences()
                }

                LaunchedEffect(pageIndex) {
                    if (pageIndex == MainTab.HISTORY.ordinal) {
                        sentencesLoading = true
                        sentenceEntries = withContext(Dispatchers.IO) {
                            ServiceLocator.sentenceTranslationRepository.all()
                        }
                        sentencesLoading = false
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
                                        onImportFile = {
                                            importDocumentLauncher.launch(GLOSSARY_IMPORT_MIME_TYPES)
                                        },
                                        onImportText = { raw ->
                                            val preview = GlossaryTextImport.previewCount(raw)
                                            if (preview <= 0) {
                                                Log.w(TAG, "glossary_import_text rejected reason=no_terms chars=${raw.length}")
                                                toast("没有识别到术语，请检查粘贴内容的格式")
                                            } else {
                                                val format = GlossaryTextImport.detect(raw)
                                                Log.i(TAG, "glossary_import_text format=$format terms=$preview")
                                                importPending = ImportPending(
                                                    raw = raw,
                                                    defaultName = "粘贴的术语"
                                                )
                                            }
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
                                    val preview = remember(pending) {
                                        val format = GlossaryTextImport.detect(pending.raw)
                                        val count = GlossaryTextImport.previewCount(pending.raw)
                                        "已识别：${format.label()} · $count 条术语"
                                    }
                                    ImportCollectionDialog(
                                        defaultName = defaultName,
                                        preview = preview,
                                        onConfirm = { name ->
                                            val created = ServiceLocator.glossaryRepository.createCollection(name)
                                            if (!created) {
                                                toast("创建失败：名称已存在")
                                                return@ImportCollectionDialog
                                            }
                                            val import = ServiceLocator.glossaryRepository.importAuto(name, pending.raw)
                                            if (import.total == 0) {
                                                // 预检已经拦过大部分情况；这里兜底，避免留下一张空表。
                                                ServiceLocator.glossaryRepository.deleteCollection(name)
                                                Log.w(TAG, "glossary_import aborted reason=no_terms name=$name")
                                                toast("没有识别到术语，请检查格式")
                                                return@ImportCollectionDialog
                                            }
                                            importPending = null
                                            refreshGlossaryList()
                                            glossaryCollectionForIo = name
                                            glossaryDetailCollection = name
                                            Log.i(
                                                TAG,
                                                "glossary_import done name=$name format=${import.format} " +
                                                    "terms=${import.total} added=${import.added} skipped=${import.skipped}"
                                            )
                                            toast(
                                                "导入完成（${import.format.label()}）：新增 ${import.added} 条，" +
                                                    "跳过 ${import.skipped} 条" +
                                                    if (import.truncated) "（已达上限，部分未导入）" else ""
                                            )
                                        },
                                        onDismiss = { importPending = null }
                                    )
                                }
                            }

                            MainTab.HISTORY.ordinal -> {
                                SentencesScreen(
                                    entries = sentenceEntries,
                                    isLoading = sentencesLoading,
                                    onRefresh = { refreshSentences() },
                                    onEdit = { entry, newText ->
                                        scope.launch {
                                            val ok = withContext(Dispatchers.IO) {
                                                ServiceLocator.sentenceTranslationRepository.updateTranslation(
                                                    entry.sourceLanguage,
                                                    entry.targetLanguage,
                                                    entry.originalText,
                                                    newText
                                                )
                                            }
                                            refreshSentences()
                                            toast(if (ok) "已更新译文 · 以后遇到这句都用它" else "这条已不存在")
                                        }
                                    },
                                    onInvalidate = { targets ->
                                        scope.launch {
                                            val removed = withContext(Dispatchers.IO) {
                                                ServiceLocator.sentenceTranslationRepository.remove(
                                                    targets.map {
                                                        SentenceCacheKey(
                                                            it.sourceLanguage,
                                                            it.targetLanguage,
                                                            it.originalText
                                                        )
                                                    }
                                                )
                                            }
                                            refreshSentences()
                                            toast(
                                                if (removed > 0) {
                                                    "已作废 $removed 句 · 下次重新翻译"
                                                } else {
                                                    "这些句子已经不在缓存里"
                                                }
                                            )
                                        }
                                    },
                                    onClear = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                ServiceLocator.sentenceTranslationRepository.clear()
                                            }
                                            refreshSentences()
                                            toast("句子已清空 · 下次会重新翻译")
                                        }
                                    },
                                    onExtractTerms = { targets ->
                                        scope.launch { runTermExtraction(targets, toast = ::toast) }
                                    }
                                )
                            }
                        }
                    }
                }

                // AI 术语提取的候选确认弹层：确认之前一个字都不写进术语表。
                if (termSheetOpen) {
                    val termCollections = runCatching {
                        ServiceLocator.glossaryRepository.listCollections()
                    }.getOrDefault(emptyList())
                    TermExtractionSheet(
                        loading = termLoading,
                        error = termError,
                        suggestions = termSuggestions,
                        sentenceCount = termSentenceCount,
                        collections = termCollections,
                        targetCollection = termTarget,
                        existingSources = termExisting,
                        onTargetCollectionChange = { name ->
                            termTarget = name
                            refreshTermExisting(name)
                        },
                        onCreateCollection = { name ->
                            val created = runCatching {
                                ServiceLocator.glossaryRepository.createCollection(name)
                            }.getOrDefault(false)
                            if (created) {
                                // 新建后直接启用：术语只有落在「生效」的表里才会注入翻译。
                                // 不启用的话，用户刚建完、刚加完词，下一页却完全不生效。
                                runCatching {
                                    val active = ServiceLocator.glossaryRepository.getActiveCollections()
                                    if (!active.contains(name)) {
                                        ServiceLocator.glossaryRepository.setActiveCollections(active + name)
                                    }
                                }
                                glossaryReloadTick += 1
                                null
                            } else {
                                "建不了：名称已存在或已达数量上限"
                            }
                        },
                        onRetry = {
                            scope.launch { runTermExtraction(termTargets, toast = ::toast) }
                        },
                        onConfirm = { finalTerms ->
                            scope.launch {
                                val added = withContext(Dispatchers.IO) {
                                    var count = 0
                                    finalTerms.forEach { term ->
                                        val ok = runCatching {
                                            ServiceLocator.glossaryRepository.add(
                                                termTarget,
                                                term.source,
                                                term.target
                                            )
                                        }.getOrDefault(false)
                                        if (ok) count += 1
                                    }
                                    count
                                }
                                termSheetOpen = false
                                glossaryReloadTick += 1
                                refreshTermExisting(termTarget)
                                val skipped = finalTerms.size - added
                                toast(
                                    if (added == 0) {
                                        "没有新增 · 这些词术语表里已经有了"
                                    } else if (skipped > 0) {
                                        "已加入 $added 个 · 跳过 $skipped 个（已有或表已满）"
                                    } else {
                                        "已加入 $added 个 · 以后翻译会照着用"
                                    }
                                )
                            }
                        },
                        onDismiss = { termSheetOpen = false }
                    )
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
