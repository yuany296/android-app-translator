package com.manga.translator.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.manga.translator.app.ui.MtCard
import com.manga.translator.app.ui.MtDangerButton
import com.manga.translator.app.ui.MtDivider
import com.manga.translator.app.ui.MtSearchIcon
import com.manga.translator.app.ui.theme.MtColors
import com.manga.translator.store.AppLanguage
import com.manga.translator.store.HistoryBubble
import com.manga.translator.store.TranslationHistoryEntry
import com.manga.translator.store.parseHistoryBubbles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 正在被修改的气泡：记录它属于哪条历史、第几个，以及打开弹层时的原文 / 译文。 */
private data class EditTarget(
    val entryId: String,
    val bubbleIndex: Int,
    val original: String,
    val translated: String,
    /** 这句当时是否处于「已作废」态（决定弹层要不要说明「保存=重新生效」）。 */
    val invalidated: Boolean = false
)

private fun formatTimestamp(ts: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))
}

/** 语言代码 → 应用内的语言名（ko → 韩文）；未知代码原样显示。 */
private fun langLabel(id: String): String =
    AppLanguage.entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }?.label ?: id

/**
 * 关键词命中判定（2026-10-15）：一条历史里任意一句的原文或译文包含关键词即算命中。
 *
 * 只看句子不看页号 —— 他找的是「那句翻错的话」，记得住内容、记不住页面哈希。
 */
private fun matchesQuery(entry: TranslationHistoryEntry, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return parseHistoryBubbles(entry.bubblesJson).any {
        it.originalText.contains(q, ignoreCase = true) ||
            it.translatedText.contains(q, ignoreCase = true)
    }
}

/**
 * 命中关键词高亮（品牌蓝 + 浅蓝底）。
 *
 * 最多标 20 处就停：历史里的句子可能很长，全部扫描在高频输入时会把输入框拖卡。
 */
private fun highlightQuery(text: String, query: String): AnnotatedString {
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

/**
 * 翻译历史（设计稿 `12-改法-翻译历史`，2026-10-15 加搜索与译文修正）。
 *
 * 每条记录收成两行：主行「韩语 → 简体中文 · 8 个气泡」加粗，次行时间与页面号放灰；
 * 展开后逐句列出「原文 / 译文」，点译文即进入修改；删除收成垃圾桶图标，清空用浅红。
 *
 * 关于「改译文」为什么要连句子缓存一起动：这里只是翻译结果的快照，真正让下一页
 * 复用「这句该翻成什么」的是 `SentenceTranslationRepository`。只改快照不动缓存，
 * 下次遇到同一句还是抄旧的错译 —— 回调由 MainActivity 负责把两边一起改掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    entries: List<TranslationHistoryEntry>,
    onRefresh: () -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onEditTranslation: (entryId: String, bubbleIndex: Int, newText: String) -> Unit,
    onInvalidateBubble: (entryId: String, bubbleIndex: Int) -> Unit,
    onInvalidatePage: (entryId: String) -> Unit
) {
    var expandedId by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }

    val filtered = remember(entries, query) {
        if (query.isBlank()) entries else entries.filter { matchesQuery(it, query) }
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
                    text = "永久保存 · 共 ${entries.size} 条",
                    fontSize = 12.sp,
                    color = MtColors.TextTertiary
                )
            }
            MtDangerButton(
                text = "清空",
                onClick = { confirmClear = true },
                enabled = entries.isNotEmpty()
            )
        }

        Box(modifier = Modifier.height(12.dp))

        HistorySearchField(value = query, onValueChange = { query = it })

        if (query.isNotBlank()) {
            Box(modifier = Modifier.height(8.dp))
            Text(
                text = if (filtered.isEmpty()) "没有匹配的记录" else "找到 ${filtered.size} 条",
                fontSize = 12.sp,
                color = MtColors.TextSecondary
            )
        }

        Box(modifier = Modifier.height(14.dp))

        val pullState = rememberPullToRefreshState()
        LaunchedEffect(pullState.isRefreshing) {
            if (pullState.isRefreshing) {
                onRefresh()
                pullState.endRefresh()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(pullState.nestedScrollConnection)
        ) {
            if (filtered.isEmpty()) {
                if (entries.isEmpty()) {
                    HistoryEmptyState()
                } else {
                    HistoryNoResultState(query = query)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        // 历史列表卡用直角（2026-09-18 用户要求）。
                        MtCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(0.dp)
                        ) {
                            filtered.forEachIndexed { index, entry ->
                                val expanded = expandedId == entry.id
                                HistoryEntryRow(
                                    entry = entry,
                                    expanded = expanded,
                                    query = query,
                                    showDivider = index != filtered.lastIndex,
                                    onToggle = {
                                        expandedId = if (expanded) null else entry.id
                                    },
                                    onDelete = { onDelete(entry.id) },
                                    onEditBubble = { bubbleIndex, original, translated, invalidated ->
                                        editTarget = EditTarget(
                                            entryId = entry.id,
                                            bubbleIndex = bubbleIndex,
                                            original = original,
                                            translated = translated,
                                            invalidated = invalidated
                                        )
                                    },
                                    onInvalidatePage = { onInvalidatePage(entry.id) }
                                )
                            }
                        }
                    }
                    item { Box(modifier = Modifier.height(20.dp)) }
                }
            }
            PullToRefreshDefaults.Indicator(
                state = pullState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空翻译历史") },
            text = { Text("确定删除全部 ${entries.size} 条历史记录吗？清空后这些页面再次翻译会重新调用 API。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    }
                ) { Text("确定", color = MtColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }

    editTarget?.let { target ->
        EditTranslationSheet(
            original = target.original,
            initialTranslation = target.translated,
            invalidated = target.invalidated,
            onSave = { text ->
                editTarget = null
                onEditTranslation(target.entryId, target.bubbleIndex, text)
            },
            onInvalidate = {
                editTarget = null
                onInvalidateBubble(target.entryId, target.bubbleIndex)
            },
            onDismiss = { editTarget = null }
        )
    }
}

/**
 * 搜索框（2026-10-15 用户要求）。
 *
 * 用 Filled 底 + 框内放大镜，与首页输入框同一套视觉；有内容时右侧给清除键。
 */
@Composable
private fun HistorySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MtColors.SurfaceMuted)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = MtSearchIcon,
            contentDescription = null,
            tint = MtColors.TextTertiary,
            modifier = Modifier.size(18.dp)
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(text = "搜索原文或译文", fontSize = 14.sp, color = MtColors.TextTertiary)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = MtColors.TextPrimary),
                cursorBrush = SolidColor(MtColors.Brand),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onValueChange("") },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "清除搜索",
                    tint = MtColors.TextTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun HistoryEntryRow(
    entry: TranslationHistoryEntry,
    expanded: Boolean,
    query: String,
    showDivider: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEditBubble: (index: Int, original: String, translated: String, invalidated: Boolean) -> Unit,
    onInvalidatePage: () -> Unit
) {
    // 以 bubblesJson 为 key：改完译文后 json 变了，这里要跟着重新解析，否则展开区还是旧译文。
    val bubbles = remember(entry.id, entry.bubblesJson) {
        parseHistoryBubbles(entry.bubblesJson)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = if (expanded) 12.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "${langLabel(entry.sourceLanguage)} → ${langLabel(entry.targetLanguage)} · ${bubbles.size} 个气泡",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MtColors.TextPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = "${formatTimestamp(entry.timestampMs)} · 页面 ${entry.pageHash.take(12)}",
                        fontSize = 11.sp,
                        color = MtColors.TextTertiary,
                        maxLines = 1
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除这条记录",
                        tint = MtColors.TextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MtColors.BubbleBg)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    bubbles.forEachIndexed { index, bubble ->
                        if (index > 0) Box(modifier = Modifier.height(10.dp))
                        Text(
                            text = highlightQuery(bubble.originalText, query),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MtColors.TextPrimary
                        )
                        Box(modifier = Modifier.height(3.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                // 整行都可点，而不是只点末尾那个「改」字 —— 手指按得住。
                                .clickable {
                                    onEditBubble(
                                        index,
                                        bubble.originalText,
                                        bubble.translatedText,
                                        bubble.invalidated
                                    )
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = highlightQuery("→ ${bubble.translatedText}", query),
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                // 已作废的旧译文压暗：它只是留档，不再是「会遇到就用」的那句。
                                color = if (bubble.invalidated) MtColors.TextTertiary else MtColors.BrandInk,
                                modifier = Modifier.weight(1f)
                            )
                            // 已作废优先于「已修正」显示：两条标记同时挂着时，用户只需要知道
                            // 「这句已经不算数了」（2026-10-15 用户反馈「作废了还能改，我以为还能用」）。
                            if (bubble.invalidated) {
                                Text(
                                    text = "已作废",
                                    fontSize = 11.sp,
                                    color = MtColors.DangerTextSoft,
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                            } else if (bubble.edited) {
                                Text(
                                    text = "已修正",
                                    fontSize = 11.sp,
                                    color = MtColors.Success,
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                            }
                            Text(
                                // 入口文案跟着状态走：作废过的必须一眼看出「点它 = 让这句重新生效」。
                                text = if (bubble.invalidated) "改后生效" else "改",
                                fontSize = if (bubble.invalidated) 11.sp else 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (bubble.invalidated) MtColors.TextTertiary else MtColors.Brand,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                        }
                    }

                    Box(modifier = Modifier.height(10.dp))
                    MtDivider()
                    Box(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "作废本页缓存",
                            fontSize = 12.sp,
                            color = MtColors.DangerTextSoft,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onInvalidatePage)
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                        Box(modifier = Modifier.weight(1f))
                        Text(
                            text = "下次重新翻译",
                            fontSize = 11.sp,
                            color = MtColors.TextTertiary
                        )
                    }
                }
            }
        }
        if (showDivider) MtDivider()
    }
}

/**
 * 修改译文弹层（2026-10-15）。
 *
 * 两条出路分开写清楚：
 * - 知道正确译文 → 改上面输入框 + 「保存」，会同时写回句子缓存；
 * - 不知道 / 觉得原文本身就错（截页被错误合并）→ 用「作废这句缓存」，下次重新翻译。
 *
 * 2026-10-15 补：这句已经作废时（[invalidated]）弹层要主动说明「保存 = 重新生效」——
 * 用户反馈「作废之后还能改译文，我以为它还能用」，问题不在能不能改，而在没告诉他改的含义。
 */
@Composable
private fun EditTranslationSheet(
    original: String,
    initialTranslation: String,
    invalidated: Boolean,
    onSave: (String) -> Unit,
    onInvalidate: () -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(original, initialTranslation) { mutableStateOf(initialTranslation) }
    val focusRequester = remember { FocusRequester() }
    val sameAsOriginal = text.trim().isNotEmpty() && text.trim() == original.trim()
    val canSave = text.isNotBlank() && !sameAsOriginal

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (invalidated) "修改译文 · 已作废" else "修改译文",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MtColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "保存",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (canSave) MtColors.Brand else MtColors.TextTertiary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = canSave) { onSave(text.trim()) }
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }

                if (invalidated) {
                    // 作废过的句子必须先把话说清楚：下面这个输入框里的旧译文已经不算数了。
                    Text(
                        text = "这句已作废（下次遇到会重新翻译）。保存 = 让它重新生效，" +
                            "以后遇到这句都用你写的译文。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MtColors.DangerTextSoft,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MtColors.SurfaceMuted)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = "原文", fontSize = 11.sp, color = MtColors.TextSecondary)
                    Box(modifier = Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MtColors.SurfaceMuted)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = original,
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            color = MtColors.TextPrimary
                        )
                    }
                    Box(modifier = Modifier.height(6.dp))
                    Text(
                        text = "若这段原文本身有误（例如两块文字被拼在一起），改译文只治这一句，建议用下方「作废」。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MtColors.TextTertiary
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (invalidated) "译文（已作废的旧译文）" else "译文",
                        fontSize = 11.sp,
                        color = MtColors.TextSecondary
                    )
                    Box(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MtColors.SurfaceMuted)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                                color = MtColors.TextPrimary
                            ),
                            cursorBrush = SolidColor(MtColors.Brand),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                    }
                    Box(modifier = Modifier.height(8.dp))
                    Text(
                        text = when {
                            sameAsOriginal -> "译文不能和原文一样"
                            invalidated -> "保存后这句会重新生效，以后遇到都用它"
                            else -> "保存后，以后遇到这句都用这个译文"
                        },
                        fontSize = 11.sp,
                        color = if (sameAsOriginal) MtColors.Danger else MtColors.TextTertiary
                    )
                }

                MtDivider()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        // 已经作废过就不给再点：重复点没有新效果，留着只会让人以为「还没生效」。
                        .clickable(enabled = !invalidated, onClick = onInvalidate)
                        .padding(vertical = 2.dp)
                ) {
                    Column {
                        Text(
                            text = if (invalidated) "已作废这句缓存" else "作废这句缓存",
                            fontSize = 14.sp,
                            color = if (invalidated) MtColors.TextTertiary else MtColors.DangerTextSoft
                        )
                        Box(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (invalidated) {
                                "旧译文只留作追溯，下次遇到这句会重新翻译"
                            } else {
                                "下次遇到这句重新翻译"
                            },
                            fontSize = 12.sp,
                            color = MtColors.TextTertiary
                        )
                    }
                }
            }
        }
    }
}

/** 空状态：空箱插画 + 居中标题与说明（与术语表共用同一张图）。 */
@Composable
private fun HistoryEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_empty_box),
            contentDescription = null,
            modifier = Modifier.size(120.dp)
        )
        Text(
            text = "还没有翻译记录",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MtColors.TextPrimary
        )
        Text(
            text = "翻译成功后会永久保存，翻回同一原文不会重复调用 AI。",
            fontSize = 12.sp,
            color = MtColors.TextTertiary
        )
    }
}

/** 搜索无结果：复用同一张空箱图，文案换成「没搜到」。 */
@Composable
private fun HistoryNoResultState(query: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_empty_box),
            contentDescription = null,
            modifier = Modifier.size(120.dp)
        )
        Text(
            text = "没有找到「$query」",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MtColors.TextPrimary,
            maxLines = 1
        )
        Text(
            text = "试试别的关键词，或清空搜索框。",
            fontSize = 12.sp,
            color = MtColors.TextTertiary
        )
    }
}
