package com.manga.translator.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.manga.translator.app.ui.MtDivider
import com.manga.translator.app.ui.MtSwitch
import com.manga.translator.app.ui.theme.MtColors
import com.manga.translator.store.PageSnapshot
import com.manga.translator.store.SentenceSource
import com.manga.translator.store.SentenceTranslation
import com.manga.translator.store.SentenceTranslationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 句子（2026-10-31 起取代原来的「翻译历史」快照视图）。
 *
 * 为什么换成句子级：以前有两份互不相通的数据 —— 句子缓存（真正被复用，5000 条，没有界面）
 * 和翻译历史快照（纯展示，500 条，删了不影响缓存）。用户在界面上看到的是后者、实际生效的
 * 是前者，于是出现「删了记录，译文照旧」的落差。现在界面直接读句子表本身，
 * **看到的和生效的是同一份数据**。
 *
 * 「按句 / 按页」只是同一份数据两种聚合方式，不是两套存储：
 * - 按句：一行一句，带来源 / 时间 / 命中次数；
 * - 按页：一页一张卡片，组头写「站点名（没有就写第 N 页）· 句数 · 时间」。
 */

private fun sentenceKey(entry: SentenceTranslation): String = sentenceHistoryKey(entry)

private fun langLabel(id: String): String = historyLanguageLabel(id)

private fun subtitle(count: Int): String {
    val max = SentenceTranslationRepository.MAX_ENTRIES
    return if (count < max) "共 $count 条 · 最多保留 $max 条" else "已满 $max 条 · 新句子会替换最旧的"
}

private fun formatSnapshotBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    return if (kb < 1024.0) "%.0f KB".format(kb) else "%.1f MB".format(kb / 1024.0)
}

/** 相对时间：一天内说「N 分钟前」，更早给日期。时间未知返回空串（旧数据没有这个字段）。 */
private fun relativeTime(ts: Long): String {
    if (ts <= 0L) return ""
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
}

/**
 * 卡片头/句子行共用的来源文案（2026-10-31，2026-11-02 支持按来源分别显示）。
 *
 * 先给大类（网页文字 / 漫画图片 / 截图），再补站点。旧句子的 `originLabel` 可能是空值，
 * 这时只显示大类；截图链路没有站点，直接用「截图」而不是「漫画/图片 · 截图翻译」。
 */
private fun sourceLabel(entry: SentenceTranslation): String =
    historySourceLabel(entry.originLabel, entry.originPageHash)

private fun metaLine(
    entry: SentenceTranslation,
    showOrigin: Boolean,
    occurrence: SentencePageOccurrence? = null
): String {
    val parts = mutableListOf<String>()
    if (showOrigin) parts += sourceLabel(entry)
    if (occurrence != null) {
        if (occurrence.lastSeenAtMs > 0L) {
            parts += "本页最近 ${relativeTime(occurrence.lastSeenAtMs)}"
        }
        if (occurrence.hitCount > 0) parts += "本页命中 ${occurrence.hitCount} 次"
    } else {
        if (entry.createdAtMs > 0L) parts += "首次 ${formatHistoryShortTime(entry.createdAtMs)}"
        val last = maxOf(entry.lastHitAtMs, entry.createdAtMs)
        if (last > 0L && last != entry.createdAtMs) {
            parts += "最近 ${relativeTime(last)}"
        }
        if (entry.hitCount > 0) parts += "命中 ${entry.hitCount} 次"
    }
    parts += "${langLabel(entry.sourceLanguage)} → ${langLabel(entry.targetLanguage)}"
    return parts.joinToString(" · ")
}

private fun highlight(text: String, query: String): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        var hits = 0
        while (hits < 20) {
            val index = text.indexOf(q, cursor, ignoreCase = true)
            if (index < 0) break
            append(text.substring(cursor, index))
            withStyle(SpanStyle(color = MtColors.BrandInk, background = MtColors.BrandTint)) {
                append(text.substring(index, index + q.length))
            }
            cursor = index + q.length
            hits += 1
        }
        append(text.substring(cursor))
    }
}

@Composable
fun SentencesScreen(
    entries: List<SentenceTranslation>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onEdit: (SentenceTranslation, String) -> Unit,
    onInvalidate: (List<SentenceTranslation>) -> Unit,
    onClear: () -> Unit,
    onExtractTerms: (List<SentenceTranslation>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var groupByPage by remember { mutableStateOf(false) }
    // null = 全部；网页文字 / 漫画图片两档（2026-10-31 用户反馈：网页记录太多，
    // 找一条漫画译文要划过几百条网页文字）。
    var sourceFilter by remember { mutableStateOf<SentenceSource?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmClear by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SentenceTranslation?>(null) }
    // 按页卡片折叠状态：默认只把超过 40 句的大页收起（网页会话/长章节），
    // 普通漫画页保持展开；用户点过的页用下面两个集合覆盖默认值。
    var collapsedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 切换「按句 / 按页」时会把分组标题插到列表最前面。LazyColumn 认 key，会把滚动位置
    // 锚在原来的第一条句子上，于是插进去的标题直接被顶到可视区外面 —— 表现为「按页点了没反应」。
    // 所以切换时要显式回到顶部。切来源筛选同理，并清掉跨筛选的选择。
    val listState = rememberLazyListState()
    LaunchedEffect(groupByPage, sourceFilter) {
        listState.scrollToItem(0)
        selected = emptySet()
    }

    val webCount = remember(entries) {
        entries.count { it.matchesSourceKind(SentenceSource.WEBPAGE_TEXT) }
    }
    val imageCount = remember(entries) {
        entries.count { it.matchesSourceKind(SentenceSource.IMAGE) }
    }
    val sourceFiltered = remember(entries, sourceFilter) {
        // 用 let 拿非空参数：sourceFilter 是 delegated state，Kotlin 不允许直接智能转换。
        val filter = sourceFilter
        filter?.let { kind -> entries.filter { it.matchesSourceKind(kind) } } ?: entries
    }
    val filtered = remember(sourceFiltered, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            sourceFiltered
        } else {
            sourceFiltered.filter {
                it.originalText.contains(q, ignoreCase = true) ||
                    it.translatedText.contains(q, ignoreCase = true)
            }
        }
    }
    val selectedEntries = remember(entries, selected) {
        entries.filter { sentenceKey(it) in selected }
    }
    // 需要提到 LazyColumn 外面：「按页」里一页 = 一张卡片，卡片头那个勾要能一次选中整页，
    // 底部操作条也要据此把「作废」说成「作废本页」。
    // 2026-11-02：按每句的 origins 展开，同一句在多页出现时会在多张卡片里各出现一次。
    // 排序优先用「这一页最近翻译时间」；旧页没有页面详情时才退回来源最近出现时间，
    // 避免只是命中了一次缓存就把很早以前翻的页顶到最前面。
    val pageGroups = remember(filtered) {
        buildSentencePageGroups(filtered).sortedByDescending { group ->
            ServiceLocator.translationPageRepository.find(group.pageHash)?.lastTranslatedAtMs
                ?: group.lastSeenAtMs
        }
    }
    var pageDetail by remember { mutableStateOf<SentencePageGroup?>(null) }

    // 页面缩略图（2026-11-02）：开关在翻译历史页，默认关；打开后只对之后新翻的页面生效。
    var snapshotEnabled by remember {
        mutableStateOf(ServiceLocator.settingsRepository.isHistorySnapshotEnabled())
    }
    var snapshotTick by remember { mutableStateOf(0) }
    var snapshotViewer by remember { mutableStateOf<PageSnapshot?>(null) }
    var snapshotManagerOpen by remember { mutableStateOf(false) }
    // 用「条数 + 最新一条的时间」当刷新键，避免每次组合都比较 5000 条句子列表。
    // entries 由 SentenceTranslationRepository.all() 按最近使用倒序返回，首条就是最新。
    val entriesVersion = entries.size to (entries.firstOrNull()?.let {
        maxOf(
            it.lastHitAtMs,
            it.createdAtMs,
            it.origins.maxOfOrNull { origin -> origin.lastSeenAtMs } ?: 0L
        )
    } ?: 0L)
    val snapshotStats by produceState<Pair<Int, Long>>(0 to 0L, entriesVersion, snapshotTick) {
        value = withContext(Dispatchers.IO) {
            ServiceLocator.pageSnapshotRepository.stats()
        }
    }
    val snapshotList by produceState<List<PageSnapshot>>(
        emptyList(),
        snapshotManagerOpen,
        snapshotTick,
        entriesVersion
    ) {
        value = if (snapshotManagerOpen) {
            withContext(Dispatchers.IO) {
                val repo = ServiceLocator.pageSnapshotRepository
                repo.all().filter { it.thumbnailDir.isNotBlank() && repo.thumbnailFile(it) != null }
            }
        } else {
            emptyList()
        }
    }

    fun isGroupCollapsed(key: String, size: Int): Boolean = when {
        key in expandedGroups -> false
        key in collapsedGroups -> true
        else -> size > 40
    }

    fun toggleGroupCollapsed(key: String, size: Int) {
        if (isGroupCollapsed(key, size)) {
            expandedGroups = expandedGroups + key
            collapsedGroups = collapsedGroups - key
        } else {
            collapsedGroups = collapsedGroups + key
            expandedGroups = expandedGroups - key
        }
    }

    fun setAllGroupsCollapsed(collapse: Boolean) {
        val keys = pageGroups.map { it.key }.toSet()
        if (collapse) {
            collapsedGroups = collapsedGroups + keys
            expandedGroups = expandedGroups - keys
        } else {
            expandedGroups = expandedGroups + keys
            collapsedGroups = collapsedGroups - keys
        }
    }

    val allGroupsCollapsed = pageGroups.isNotEmpty() && pageGroups.all {
        isGroupCollapsed(it.key, it.sentenceCount)
    }

    // 选中的正好就是某一整页（且不止一句）→ 底部按「作废本页」措辞。
    // 搜索状态下不算：那时「一页」只是命中的那几句，作废掉不等于作废整页，
    // 说「本页」会把范围说大。
    val selectionIsWholePage = remember(pageGroups, selected, selectedEntries, query) {
        query.isBlank() && selectedEntries.size > 1 && pageGroups.any { group ->
            group.sentenceCount == selectedEntries.size && group.sentenceKeys.all { it in selected }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 20.dp, top = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "翻译历史",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MtColors.TextPrimary
                )
                Text(
                    text = if (isLoading) "加载中…" else subtitle(entries.size),
                    fontSize = 12.sp,
                    color = MtColors.TextTertiary
                )
            }
            Text(
                text = "清空",
                fontSize = 13.sp,
                color = if (entries.isEmpty()) MtColors.TextTertiary else MtColors.DangerText,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = entries.isNotEmpty()) { confirmClear = true }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }

        // 页面缩略图开关（2026-11-02）：默认关；打开后每页翻译时自动存一张缩略原图，
        // 标记版按需生成。只影响之后新翻的页面，旧记录没有源图可补。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = "保存页面缩略图",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MtColors.TextPrimary
                )
                Text(
                    text = "原图 + 标记版 · 只对之后新翻的页面生效",
                    fontSize = 11.sp,
                    color = MtColors.TextTertiary
                )
            }
            MtSwitch(
                checked = snapshotEnabled,
                onCheckedChange = { enabled ->
                    snapshotEnabled = enabled
                    ServiceLocator.settingsRepository.setHistorySnapshotEnabled(enabled)
                }
            )
        }
        Text(
            text = "管理图片（${snapshotStats.first} 张 · ${formatSnapshotBytes(snapshotStats.second)}）",
            fontSize = 12.sp,
            color = MtColors.BrandInk,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { snapshotManagerOpen = true }
                .padding(vertical = 6.dp)
        )

        Box(modifier = Modifier.height(6.dp))

        SentenceSearchField(
            value = query,
            onValueChange = { query = it },
            onRefresh = onRefresh
        )

        Box(modifier = Modifier.height(8.dp))

        // 来源筛选（2026-10-31）：网页文字和漫画/图片分开看，避免找一条漫画译文
        // 要划过几百条网页 DOM 文本。计数直接写在标签里，切之前就知道各有多少。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GroupToggle("全部 ${entries.size}", sourceFilter == null) { sourceFilter = null }
            GroupToggle("网页文字 $webCount", sourceFilter == SentenceSource.WEBPAGE_TEXT) {
                sourceFilter = SentenceSource.WEBPAGE_TEXT
            }
            GroupToggle("漫画/图片 $imageCount", sourceFilter == SentenceSource.IMAGE) {
                sourceFilter = SentenceSource.IMAGE
            }
        }

        Box(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GroupToggle("按句", !groupByPage) { groupByPage = false }
            GroupToggle("按页", groupByPage) { groupByPage = true }
            Box(modifier = Modifier.weight(1f))
            if (groupByPage && pageGroups.isNotEmpty()) {
                Text(
                    text = if (allGroupsCollapsed) "展开全部" else "收起全部",
                    fontSize = 12.sp,
                    color = MtColors.TextTertiary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { setAllGroupsCollapsed(!allGroupsCollapsed) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
            if (filtered.isNotEmpty()) {
                val allSelected = filtered.all { sentenceKey(it) in selected }
                Text(
                    text = if (allSelected) "取消全选" else "全选",
                    fontSize = 12.sp,
                    color = MtColors.BrandInk,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            selected = if (allSelected) {
                                selected - filtered.map { sentenceKey(it) }.toSet()
                            } else {
                                selected + filtered.map { sentenceKey(it) }.toSet()
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }

        Box(modifier = Modifier.height(4.dp))

        if (filtered.isEmpty()) {
            EmptySentenceState(
                hasQuery = query.isNotBlank(),
                hasFilter = sourceFilter != null
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f), state = listState) {
                if (groupByPage) {
                    pageGroups.forEach { group ->
                        // 一页 = 一张可折叠卡片：来源 + 句数 + 本页最近时间。
                        // 同一句在多页出现时会出现在多张卡片里（2026-11-02 按 origins 展开），
                        // 因此 LazyColumn key 必须带 pageKey，否则不同页的同句会撞 key。
                        val pageKey = group.key
                        val pageKeys = group.sentenceKeys
                        val allInPageSelected = pageKeys.isNotEmpty() && pageKeys.all { it in selected }
                        val collapsed = isGroupCollapsed(pageKey, group.sentenceCount)
                        item(key = "g-$pageKey") {
                            PageGroupHeader(
                                sourceLabel = historySourceLabel(group.label, group.pageHash),
                                groupLastSeenAtMs = group.lastSeenAtMs,
                                hitCount = group.hitCount,
                                count = group.sentenceCount,
                                collapsed = collapsed,
                                allSelected = allInPageSelected,
                                // 页面快照按 pageHash 查（内存索引，只有可视区标题会被组合）。
                                pageHash = group.pageHash,
                                // 删除/清空图片后强制卡片头重查，否则 LazyColumn item 会被跳过。
                                snapshotVersion = snapshotTick,
                                onOpenSnapshot = { snapshotViewer = it },
                                onOpenDetails = { pageDetail = group },
                                onToggleCollapse = {
                                    toggleGroupCollapsed(pageKey, group.sentenceCount)
                                },
                                onToggleAll = {
                                    selected = if (allInPageSelected) {
                                        selected - pageKeys
                                    } else {
                                        selected + pageKeys
                                    }
                                }
                            )
                        }
                        // 2026-10-31 性能修复：展开后的句子必须继续走 LazyColumn 的
                        // items，而不是塞进卡片头这**一个** item 里的 Column。
                        // 旧实现展开 686 句网页组时，一次组合 686 个 SentenceRow，
                        // 点折叠/展开会卡住主线程；现在只有可视区附近的行会被组合。
                        if (!collapsed) {
                            itemsIndexed(
                                items = group.occurrences,
                                key = { _, occurrence ->
                                    "e-$pageKey-${sentenceHistoryKey(occurrence.entry)}"
                                }
                            ) { index, occurrence ->
                                PageGroupSentence(
                                    occurrence = occurrence,
                                    query = query,
                                    checked = sentenceHistoryKey(occurrence.entry) in selected,
                                    isLast = index == group.occurrences.lastIndex,
                                    onToggle = {
                                        val k = sentenceHistoryKey(occurrence.entry)
                                        selected = if (k in selected) selected - k else selected + k
                                    },
                                    onClick = { editing = occurrence.entry }
                                )
                            }
                        }
                    }
                } else {
                    items(filtered, key = { "e-${sentenceKey(it)}" }) { entry ->
                        SentenceRow(
                            entry = entry,
                            query = query,
                            checked = sentenceKey(entry) in selected,
                            showOrigin = true,
                            onToggle = {
                                val k = sentenceKey(entry)
                                selected = if (k in selected) selected - k else selected + k
                            },
                            onClick = { editing = entry }
                        )
                        MtDivider()
                    }
                }
            }
        }

        if (selectedEntries.isNotEmpty()) {
            SelectionBar(
                count = selectedEntries.size,
                wholePage = selectionIsWholePage,
                onInvalidate = {
                    onInvalidate(selectedEntries)
                    selected = emptySet()
                },
                onExtractTerms = {
                    onExtractTerms(selectedEntries)
                    // 点完就收起选择状态：提取结果在弹层里继续确认，
                    // 底部不该还挂着「已选 N 句」让人以为没点成功。
                    selected = emptySet()
                }
            )
        }
    }

    snapshotViewer?.let { snapshot ->
        PageSnapshotViewerDialog(
            snapshot = snapshot,
            onDelete = {
                ServiceLocator.pageSnapshotRepository.delete(snapshot.pageHash)
                snapshotViewer = null
                snapshotTick++
            },
            onDismiss = { snapshotViewer = null }
        )
    }

    if (snapshotManagerOpen) {
        PageSnapshotManagerDialog(
            snapshots = snapshotList,
            totalBytes = snapshotStats.second,
            onDelete = { pageHash ->
                ServiceLocator.pageSnapshotRepository.delete(pageHash)
                snapshotTick++
            },
            onClear = {
                ServiceLocator.pageSnapshotRepository.clear()
                snapshotManagerOpen = false
                snapshotTick++
            },
            onDismiss = { snapshotManagerOpen = false }
        )
    }

    pageDetail?.let { group ->
        TranslationPageDetailDialog(
            sourceLabel = historySourceLabel(group.label, group.pageHash),
            pageHash = group.pageHash,
            pageInfo = ServiceLocator.translationPageRepository.find(group.pageHash),
            sentenceCount = group.sentenceCount,
            hitCount = group.hitCount,
            firstSeenAtMs = group.firstSeenAtMs,
            lastSeenAtMs = group.lastSeenAtMs,
            hasSnapshot = ServiceLocator.pageSnapshotRepository.find(group.pageHash) != null,
            onDismiss = { pageDetail = null }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空句子") },
            text = {
                Text(
                    "将删除全部 ${entries.size} 条句子译文和对应的页面详情。清空后这些句子会重新" +
                        "调用翻译引擎，再翻同样内容需要重新请求（可能要花钱）。页面缩略图不受影响，" +
                        "可在「管理图片」里单独删除。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("确定", color = MtColors.DangerText) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }

    editing?.let { target ->
        SentenceEditDialog(
            entry = target,
            onDismiss = { editing = null },
            onSave = { newText ->
                editing = null
                onEdit(target, newText)
            }
        )
    }
}

/**
 * 「按页」的卡片头（2026-10-31）。
 *
 * 边框不再由 `MtCard` 包住整页句子：句子行已经拆成 LazyColumn 的独立 item，
 * 卡片头只画上/左/右三条边；展开时最后一行负责画底边，收起时头自己画底边。
 * 这样 686 句的网页组展开时也只有可视区附近的行会被组合。
 */
@Composable
private fun PageGroupHeader(
    sourceLabel: String,
    groupLastSeenAtMs: Long,
    hitCount: Int,
    count: Int,
    collapsed: Boolean,
    allSelected: Boolean,
    pageHash: String,
    snapshotVersion: Int,
    onOpenSnapshot: (PageSnapshot) -> Unit,
    onOpenDetails: () -> Unit,
    onToggleCollapse: () -> Unit,
    onToggleAll: () -> Unit
) {
    // find() 走进程内索引（put/delete 时同步更新），这里不需要再起协程读盘。
    val snapshot = ServiceLocator.pageSnapshotRepository.find(pageHash)
    // 本次进程里这页有没有用过多模态重读（2026-11-02）。
    val visionFallback = VisionFallbackTracker.wasUsed(pageHash)
    // 页面级详情：URL / 标题 / 页码 / 引擎 / 新翻与复用统计（2026-11-02）。
    // 新翻译会让 groupLastSeenAtMs 变化，借此刷新页面详情；snapshotVersion 覆盖删图。
    val info = remember(pageHash, groupLastSeenAtMs, snapshotVersion) {
        ServiceLocator.translationPageRepository.find(pageHash)
    }
    // 句子里的 label 可能是旧数据/兜底值；页面详情里的 host 更准。
    val effectiveSourceLabel = info?.label
        ?.takeIf { it.isNotBlank() }
        ?.let { historySourceLabel(it, pageHash) }
        ?: sourceLabel
    val timeText = relativeTime(
        info?.lastTranslatedAtMs?.takeIf { it > 0L } ?: groupLastSeenAtMs
    )
    // 只有真的记到了标题/URL 才显示第二行；否则 displayTitle 会回退成来源标签/页标识，
    // 截图页就会出现「截图」下面再写一遍「截图翻译」的重复。
    val titleLine = info
        ?.takeIf { it.title.isNotBlank() || it.url.isNotBlank() }
        ?.displayTitle()
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != effectiveSourceLabel && it != pageHash }
    val detailLine = buildList {
        if (info != null) {
            if (info.pageIndex >= 0 || info.pageTotal > 0) {
                add(formatPagePosition(info.pageIndex, info.pageTotal))
            }
            add("新翻 ${info.newCount} · 复用 ${info.cacheHitCount} · 跳过 ${info.skippedCount}")
            if (info.sourceLanguage.isNotBlank() || info.targetLanguage.isNotBlank()) {
                add(historyLanguagePair(info.sourceLanguage, info.targetLanguage))
            }
            historyTranslateEngineShortLabel(info.translateEngine)
                .takeIf { it != "未知" }
                ?.let { add(it) }
        } else if (hitCount > 0) {
            add("来源命中 $hitCount 次")
        }
        if (visionFallback) add("多模态重读")
    }.joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .pageCardBorder(drawTop = true, drawBottom = collapsed)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 这个勾 = 「选中本页全部」，比句子行里的勾小一号（18 vs 20），
        // 免得跟"某一句也被勾了"混起来。点勾只改选择，不折叠。
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (allSelected) MtColors.Brand else MtColors.SurfaceMuted)
                .clickable(onClick = onToggleAll),
            contentAlignment = Alignment.Center
        ) {
            if (allSelected) {
                Text("✓", fontSize = 11.sp, color = MtColors.Surface)
            }
        }
        Box(modifier = Modifier.size(9.dp))
        snapshot?.let { snap ->
            PageHeaderThumbnail(snapshot = snap, onClick = { onOpenSnapshot(snap) })
            Box(modifier = Modifier.size(8.dp))
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onToggleCollapse)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = buildString {
                        if (effectiveSourceLabel.isNotBlank()) {
                            append(effectiveSourceLabel)
                            append(" · ")
                        }
                        append(count)
                        append(" 句")
                        if (timeText.isNotBlank()) {
                            append(" · ")
                            append(timeText)
                        }
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (allSelected) MtColors.BrandInk else MtColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (titleLine != null) {
                    Text(
                        text = titleLine,
                        fontSize = 10.sp,
                        color = MtColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (detailLine.isNotBlank()) {
                    Text(
                        text = detailLine,
                        fontSize = 10.sp,
                        color = MtColors.TextTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = if (collapsed) "▸" else "▾",
                fontSize = 13.sp,
                color = MtColors.TextTertiary
            )
        }
        Box(modifier = Modifier.size(4.dp))
        Text(
            text = "详情",
            fontSize = 11.sp,
            color = MtColors.BrandInk,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onOpenDetails)
                .padding(horizontal = 4.dp, vertical = 4.dp)
        )
        Box(modifier = Modifier.size(2.dp))
        Text(
            text = if (allSelected) "取消本页" else "选中本页",
            fontSize = 11.sp,
            color = MtColors.BrandInk,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onToggleAll)
                .padding(horizontal = 2.dp, vertical = 2.dp)
        )
    }
}

/** 「按页」卡片头左侧的小图；点开看原图 / 标记版（2026-11-02）。 */
@Composable
private fun PageHeaderThumbnail(
    snapshot: PageSnapshot,
    onClick: () -> Unit
) {
    val file = remember(snapshot.pageHash, snapshot.thumbnailDir) {
        ServiceLocator.pageSnapshotRepository.thumbnailFile(snapshot)
    }
    val bitmap by produceState<Bitmap?>(null, file?.absolutePath, snapshot.timestampMs) {
        value = withContext(Dispatchers.IO) {
            file?.let { HistorySnapshotFactory.decode(it, targetWidth = 96) }
        }
    }
    if (file == null) return
    Box(
        modifier = Modifier
            .size(width = 26.dp, height = 38.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MtColors.BubbleBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "查看页面快照",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** 展开后的一行句子；独立成 LazyColumn item，避免整页一次组合。 */
@Composable
private fun PageGroupSentence(
    occurrence: SentencePageOccurrence,
    query: String,
    checked: Boolean,
    isLast: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pageCardBorder(drawTop = false, drawBottom = isLast)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            // 每行顶部一条分隔线；第一行的这条线就是卡片头与正文的分界。
            MtDivider()
            SentenceRow(
                entry = occurrence.entry,
                query = query,
                checked = checked,
                showOrigin = false,
                occurrence = occurrence,
                onToggle = onToggle,
                onClick = onClick
            )
        }
    }
}

/** 画卡片头/句子行的 1px 外框；背景在这里一起铺，避免和分隔线抢绘制顺序。 */
private fun Modifier.pageCardBorder(drawTop: Boolean, drawBottom: Boolean): Modifier = drawBehind {
    drawRect(color = MtColors.Surface)
    val stroke = 1.dp.toPx()
    if (drawTop) drawLine(MtColors.Border, Offset(0f, 0f), Offset(size.width, 0f), stroke)
    drawLine(MtColors.Border, Offset(0f, 0f), Offset(0f, size.height), stroke)
    drawLine(MtColors.Border, Offset(size.width, 0f), Offset(size.width, size.height), stroke)
    if (drawBottom) {
        drawLine(MtColors.Border, Offset(0f, size.height), Offset(size.width, size.height), stroke)
    }
}

@Composable
private fun GroupToggle(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(40.dp))
            .background(if (active) MtColors.BrandTint else MtColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (active) MtColors.BrandInk else MtColors.TextSecondary
        )
    }
}

@Composable
private fun SentenceSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MtColors.SurfaceMuted)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = MtColors.TextPrimary),
            cursorBrush = SolidColor(MtColors.Brand),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text("搜索原文或译文", fontSize = 13.sp, color = MtColors.TextTertiary)
                }
                inner()
            }
        )
        if (value.isNotEmpty()) {
            Text(
                text = "清除",
                fontSize = 12.sp,
                color = MtColors.TextTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onValueChange("") }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        } else {
            Text(
                text = "刷新",
                fontSize = 12.sp,
                color = MtColors.TextTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRefresh)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun SentenceRow(
    entry: SentenceTranslation,
    query: String,
    checked: Boolean,
    showOrigin: Boolean,
    occurrence: SentencePageOccurrence? = null,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (checked) MtColors.BrandTint else MtColors.Surface)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (checked) MtColors.Brand else MtColors.Surface)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text("✓", fontSize = 12.sp, color = MtColors.Surface)
            } else {
                Box(
                    modifier = Modifier
                        .size(19.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MtColors.SurfaceMuted)
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = highlight(entry.originalText, query),
                fontSize = 13.sp,
                color = MtColors.TextPrimary
            )
            Text(
                text = highlight(entry.translatedText, query),
                fontSize = 13.sp,
                color = if (checked) MtColors.BrandInk else MtColors.TextPrimary
            )
            Text(
                text = metaLine(entry, showOrigin, occurrence),
                fontSize = 11.sp,
                color = MtColors.TextTertiary
            )
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    wholePage: Boolean,
    onInvalidate: () -> Unit,
    onExtractTerms: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "已选 $count 句", fontSize = 13.sp, color = MtColors.TextPrimary)
        Box(modifier = Modifier.weight(1f))
        // 选中的正好是一整页时说「作废本页」：这是「按页」唯一比按句省事的地方，
        // 措辞得让人看得出范围是整页，而不是又勾了一堆句子。
        Text(
            text = if (wholePage) "作废本页" else "作废",
            fontSize = 13.sp,
            color = MtColors.DangerText,
            modifier = Modifier
                .clip(RoundedCornerShape(40.dp))
                .clickable(onClick = onInvalidate)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
        Box(modifier = Modifier.size(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(40.dp))
                .background(MtColors.Brand)
                .clickable(onClick = onExtractTerms)
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Text("提取术语", fontSize = 13.sp, color = MtColors.Surface)
        }
    }
}

@Composable
private fun EmptySentenceState(hasQuery: Boolean, hasFilter: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MtColors.SurfaceMuted)
        )
        Text(
            text = when {
                hasQuery -> "没搜到"
                hasFilter -> "这个分类还没有记录"
                else -> "还没有翻译过句子"
            },
            fontSize = 14.sp,
            color = MtColors.TextSecondary
        )
        Text(
            text = when {
                hasQuery -> "换个关键词试试"
                hasFilter -> "切到「全部」看看其他记录"
                else -> "翻一页之后，句子会出现在这里"
            },
            fontSize = 12.sp,
            color = MtColors.TextTertiary
        )
    }
}

@Composable
private fun SentenceEditDialog(
    entry: SentenceTranslation,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var draft by remember(entry) { mutableStateOf(entry.translatedText) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(14.dp))
                .background(MtColors.Surface)
                .padding(18.dp)
        ) {
            Text(
                "译文详情",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MtColors.TextPrimary
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(entry.originalText, fontSize = 12.sp, color = MtColors.TextSecondary)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MtColors.SurfaceMuted)
                        .padding(12.dp)
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = TextStyle(fontSize = 14.sp, color = MtColors.TextPrimary),
                        cursorBrush = SolidColor(MtColors.Brand),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    text = "改完以后遇到这句都用它（所有页面共享这份译文）",
                    fontSize = 11.sp,
                    color = MtColors.TextTertiary
                )
                SentenceDetailLine("语言", historyLanguagePair(entry.sourceLanguage, entry.targetLanguage))
                SentenceDetailLine("首次入库", formatHistoryTime(entry.createdAtMs))
                SentenceDetailLine(
                    "最近命中",
                    if (entry.lastHitAtMs > 0L) formatHistoryTime(entry.lastHitAtMs) else "还没有命中过"
                )
                SentenceDetailLine("命中次数", "${entry.hitCount} 次")
                SentenceDetailLine("术语", historyGlossaryState(entry))
                Text(
                    text = "来源（同一句在多页出现时会全部列出）",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MtColors.TextSecondary
                )
                val origins = sentenceOriginLines(entry)
                if (origins.isEmpty()) {
                    Text("来源未知（旧记录）", fontSize = 11.sp, color = MtColors.TextTertiary)
                } else {
                    origins.forEach { line ->
                        Text("• $line", fontSize = 11.sp, color = MtColors.TextTertiary)
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = { if (draft.isNotBlank()) onSave(draft) }) { Text("保存") }
            }
        }
    }
}

@Composable
private fun SentenceDetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MtColors.TextTertiary,
            modifier = Modifier.weight(0.24f)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = MtColors.TextPrimary,
            modifier = Modifier.weight(0.76f)
        )
    }
}
