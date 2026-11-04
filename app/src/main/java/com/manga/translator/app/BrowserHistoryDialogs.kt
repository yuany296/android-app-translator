package com.manga.translator.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manga.translator.app.ui.theme.MtColors
import com.manga.translator.store.BrowserHistoryEntry
import com.manga.translator.store.BrowserHistoryRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 浏览历史详情（2026-11-02）。
 *
 * 历史行只有 56dp，标题/URL 必然被省略；长按打开这里看完整内容：
 * 完整 URL、完整标题、首次/最近访问、访问次数，并可复制/删除。
 */
@Composable
internal fun BrowserHistoryDetailDialog(
    entry: BrowserHistoryEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = BrowserHistoryRepository.displayTitle(entry),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MtColors.TextPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = entry.url,
                    fontSize = 12.sp,
                    color = MtColors.BrandInk
                )
                HistoryDetailRow("站点", BrowserHistoryRepository.hostOf(entry.url).ifBlank { "未知" })
                HistoryDetailRow(
                    "页面标题",
                    entry.title.ifBlank { "未记录（页面没有给出标题）" }
                )
                HistoryDetailRow("访问次数", "${entry.visitCount} 次")
                HistoryDetailRow(
                    "首次访问",
                    formatHistoryDetailTime(
                        entry.firstVisitMs.takeIf { it > 0L } ?: entry.lastVisitMs
                    )
                )
                HistoryDetailRow("最近访问", formatHistoryDetailTime(entry.lastVisitMs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = { copyUrl(context, entry.url) }) {
                        Text("复制网址", fontSize = 13.sp)
                    }
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("删除记录", fontSize = 13.sp, color = MtColors.DangerText)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpen) { Text("打开", color = MtColors.BrandInk) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条历史记录？") },
            text = { Text(BrowserHistoryRepository.urlWithoutScheme(entry.url)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = MtColors.DangerText) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun HistoryDetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MtColors.TextTertiary,
            modifier = Modifier.weight(0.26f)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = MtColors.TextPrimary,
            modifier = Modifier.weight(0.74f)
        )
    }
}

internal fun formatHistoryDetailTime(ts: Long): String {
    if (ts <= 0L) return "未知"
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))
}

private fun copyUrl(context: Context, url: String) {
    val ok = runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("网址", url))
    }.isSuccess
    Toast.makeText(
        context,
        if (ok) "网址已复制" else "复制失败",
        Toast.LENGTH_SHORT
    ).show()
}
