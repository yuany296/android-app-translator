package com.manga.translator.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manga.translator.app.ui.MtCard
import com.manga.translator.app.ui.MtDangerButton
import com.manga.translator.app.ui.MtDivider
import com.manga.translator.app.ui.theme.MtColors
import com.manga.translator.store.AppLanguage
import com.manga.translator.store.TranslationHistoryEntry
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class HistoryBubbleView(
    val original: String,
    val translated: String
)

private fun parseHistoryBubbles(json: String): List<HistoryBubbleView> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONObject(json).optJSONArray("bubbles") ?: JSONArray()
        val views = mutableListOf<HistoryBubbleView>()
        for (index in 0 until arr.length()) {
            val item = arr.optJSONObject(index) ?: continue
            views += HistoryBubbleView(
                original = item.optString("originalText", ""),
                translated = item.optString("translatedText", "")
            )
        }
        views
    }.getOrElse { emptyList() }
}

private fun formatTimestamp(ts: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))
}

/**
 * 翻译历史（设计稿 `12-改法-翻译历史`）。
 *
 * 每条记录收成两行：主行「韩语 → 简体中文 · 8 个气泡」加粗，次行时间与页面号放灰；
 * 删除收成垃圾桶图标，清空用浅红。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    entries: List<TranslationHistoryEntry>,
    onRefresh: () -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit
) {
    var expandedId by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

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
            if (entries.isEmpty()) {
                HistoryEmptyState()
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        // 历史列表卡用直角（2026-09-18 用户要求）。
                        MtCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(0.dp)
                        ) {
                            entries.forEachIndexed { index, entry ->
                                val expanded = expandedId == entry.id
                                val bubbles = remember(entry.id) {
                                    parseHistoryBubbles(entry.bubblesJson)
                                }
                                HistoryEntryRow(
                                    entry = entry,
                                    bubbles = bubbles,
                                    expanded = expanded,
                                    showDivider = index != entries.lastIndex,
                                    onToggle = {
                                        expandedId = if (expanded) null else entry.id
                                    },
                                    onDelete = { onDelete(entry.id) }
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
}

@Composable
private fun HistoryEntryRow(
    entry: TranslationHistoryEntry,
    bubbles: List<HistoryBubbleView>,
    expanded: Boolean,
    showDivider: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
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
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    bubbles.forEach { bubble ->
                        Text(
                            text = bubble.original,
                            fontSize = 13.sp,
                            color = MtColors.TextPrimary
                        )
                        Text(
                            text = "→ ${bubble.translated}",
                            fontSize = 13.sp,
                            color = MtColors.BrandInk
                        )
                    }
                }
            }
        }
        if (showDivider) MtDivider()
    }
}

/** 语言代码 → 应用内的语言名（ko → 韩文）；未知代码原样显示。 */
private fun langLabel(id: String): String =
    AppLanguage.entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }?.label ?: id

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
