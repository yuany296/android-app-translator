package com.manga.translator.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
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
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "翻译历史（永久保存，共 ${entries.size} 条）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                Text("清空")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

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
                Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
                    Text(text = "暂无历史记录。翻译成功后会永久保存，翻回同一原文时不重复调用AI。")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.id }) { entry ->
                        val expanded = expandedId == entry.id
                        val bubbles = remember(entry.id) { parseHistoryBubbles(entry.bubblesJson) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedId = if (expanded) null else entry.id }
                                .padding(vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = formatTimestamp(entry.timestampMs),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "${bubbles.size} 个气泡 · ${entry.sourceLanguage} → ${entry.targetLanguage}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "页面 ${entry.pageHash.take(12)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                TextButton(onClick = { onDelete(entry.id) }) { Text("删除") }
                            }
                            if (expanded) {
                                bubbles.forEach { bubble ->
                                    Text(text = bubble.original, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "→ ${bubble.translated}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
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
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}