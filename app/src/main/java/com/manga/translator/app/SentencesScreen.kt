package com.manga.translator.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
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
import com.manga.translator.app.ui.theme.MtColors
import com.manga.translator.store.AppLanguage
import com.manga.translator.store.SentenceSource
import com.manga.translator.store.SentenceTranslation
import com.manga.translator.store.SentenceTranslationRepository
import com.manga.translator.store.sourceKind
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

private fun sentenceKey(entry: SentenceTranslation): String =
    "${entry.sourceLanguage}|${entry.targetLanguage}|${entry.originalText}"

private fun langLabel(id: String): String =
    AppLanguage.entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }?.label ?: id

private fun subtitle(count: Int): String {
    val max = SentenceTranslationRepository.MAX_ENTRIES
    return if (count < max) "共 $count 条 · 最多保留 $max 条" else "已满 $max 条 · 新句子会替换最旧的"
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

internal fun groupKey(label: String, pageHash: String): String = "$label\u0001$pageHash"

/**
 * 卡片头/句子行共用的来源文案（2026-10-31）。
 *
 * 先给大类（网页文字 / 漫画图片 / 截图），再补站点。旧句子的 `originLabel` 可能是空值，
 * 这时只显示大类；截图链路没有站点，直接用「截图」而不是「漫画/图片 · 截图翻译」。
 */
private fun sourceLabel(entry: SentenceTranslation): String {
    val host = entry.originLabel.trim()
    return when {
        host == "截图翻译" -> "截图"
        host.isNotBlank() -> "${entry.sourceKind().label} · $host"
        else -> entry.sourceKind().label
    }
}

private fun pageSourceLabel(entries: List<SentenceTranslation>): String {
    if (entries.isEmpty()) return ""
    return sourceLabel(entries.first())
}

private fun metaLine(entry: SentenceTranslation, showOrigin: Boolean): String {
    val parts = mutableListOf<String>()
    if (showOrigin) parts += sourceLabel(entry)
    val time = relativeTime(maxOf(entry.lastHitAtMs, entry.createdAtMs))
    if (time.isNotBlank()) parts += time
    if (entry.hitCount > 0) parts += "命中 ${entry.hitCount} 次"
    if (parts.isEmpty()) parts += "${langLabel(entry.sourceLanguage)} → ${langLabel(entry.targetLanguage)}"
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
        entries.count { it.sourceKind() == SentenceSource.WEBPAGE_TEXT }
    }
    val imageCount = entries.size - webCount
    val sourceFiltered = remember(entries, sourceFilter) {
        if (sourceFilter == null) entries else entries.filter { it.sourceKind() == sourceFilter }
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
    val pageGroups = remember(filtered) {
        filtered.groupBy { it.originLabel to it.originPageHash }
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
        val keys = pageGroups.keys.map { groupKey(it.first, it.second) }.toSet()
        if (collapse) {
            collapsedGroups = collapsedGroups + keys
            expandedGroups = expandedGroups - keys
        } else {
            expandedGroups = expandedGroups + keys
            collapsedGroups = collapsedGroups - keys
        }
    }

    val allGroupsCollapsed = pageGroups.isNotEmpty() && pageGroups.entries.all {
        isGroupCollapsed(groupKey(it.key.first, it.key.second), it.value.size)
    }

    // 选中的正好就是某一整页（且不止一句）→ 底部按「作废本页」措辞。
    // 搜索状态下不算：那时「一页」只是命中的那几句，作废掉不等于作废整页，
    // 说「本页」会把范围说大。
    val selectionIsWholePage = remember(pageGroups, selected, selectedEntries, query) {
        query.isBlank() && selectedEntries.size > 1 && pageGroups.values.any { group ->
            group.size == selectedEntries.size && group.all { sentenceKey(it) in selected }
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

        Box(modifier = Modifier.height(12.dp))

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
                    pageGroups.entries.forEach { group ->
                        val pageKey = groupKey(group.key.first, group.key.second)
                        val groupEntries = group.value
                        // 一页 = 一张可折叠卡片（2026-10-31 再次按用户反馈收紧）：
                        // 旧句子没有站点名时组头只写「N 句 · 时间」；现在来源分类稳定了，
                        // 组头先写「网页文字 / 漫画图片 / 截图」，再补站点、句数和时间。
                        // 超过 40 句的大页默认收起（网页会话/长章节），需要看时再展开。
                        //
                        // 组头左侧那个勾才是「按页」唯一按句做不到的事：一次选中整页。
                        // 一整页被 OCR 拼错导致全页翻歪时，不用勾十几次。
                        val newest = groupEntries.maxOf { maxOf(it.lastHitAtMs, it.createdAtMs) }
                        val timeText = relativeTime(newest)
                        val pageKeys = groupEntries.map { sentenceKey(it) }
                        val allInPageSelected = pageKeys.all { it in selected }
                        val collapsed = isGroupCollapsed(pageKey, groupEntries.size)
                        item(key = "g-$pageKey") {
                            PageGroupHeader(
                                sourceLabel = pageSourceLabel(groupEntries),
                                subtitle = timeText,
                                count = groupEntries.size,
                                collapsed = collapsed,
                                allSelected = allInPageSelected,
                                onToggleCollapse = {
                                    toggleGroupCollapsed(pageKey, groupEntries.size)
                                },
                                onToggleAll = {
                                    selected = if (allInPageSelected) {
                                        selected - pageKeys.toSet()
                                    } else {
                                        selected + pageKeys.toSet()
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
                                items = groupEntries,
                                key = { _, entry -> "e-${sentenceKey(entry)}" }
                            ) { index, entry ->
                                PageGroupSentence(
                                    entry = entry,
                                    query = query,
                                    checked = sentenceKey(entry) in selected,
                                    isLast = index == groupEntries.lastIndex,
                                    onToggle = {
                                        val k = sentenceKey(entry)
                                        selected = if (k in selected) selected - k else selected + k
                                    },
                                    onClick = { editing = entry }
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

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空句子") },
            text = {
                Text(
                    "将删除全部 ${entries.size} 条句子译文。清空后这些句子会重新调用翻译引擎，" +
                        "再翻同样内容需要重新请求（可能要花钱）。"
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
    subtitle: String,
    count: Int,
    collapsed: Boolean,
    allSelected: Boolean,
    onToggleCollapse: () -> Unit,
    onToggleAll: () -> Unit
) {
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
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onToggleCollapse)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = buildString {
                    if (sourceLabel.isNotBlank()) {
                        append(sourceLabel)
                        append(" · ")
                    }
                    append(count)
                    append(" 句")
                    if (subtitle.isNotBlank()) {
                        append(" · ")
                        append(subtitle)
                    }
                },
                fontSize = 11.sp,
                color = if (allSelected) MtColors.BrandInk else MtColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (collapsed) "▸" else "▾",
                fontSize = 13.sp,
                color = MtColors.TextTertiary
            )
        }
        Box(modifier = Modifier.size(6.dp))
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

/** 展开后的一行句子；独立成 LazyColumn item，避免整页一次组合。 */
@Composable
private fun PageGroupSentence(
    entry: SentenceTranslation,
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
                entry = entry,
                query = query,
                checked = checked,
                showOrigin = false,
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
                text = metaLine(entry, showOrigin),
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
                .clip(RoundedCornerShape(14.dp))
                .background(MtColors.Surface)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("改译文", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MtColors.TextPrimary)
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
                text = "改完以后遇到这句都用它",
                fontSize = 11.sp,
                color = MtColors.TextTertiary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = { if (draft.isNotBlank()) onSave(draft) }) { Text("保存") }
            }
        }
    }
}
