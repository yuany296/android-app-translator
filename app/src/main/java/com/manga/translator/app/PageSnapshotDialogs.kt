package com.manga.translator.app

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.manga.translator.app.ui.MtCard
import com.manga.translator.app.ui.MtDivider
import com.manga.translator.app.ui.theme.MtColors
import com.manga.translator.store.PageSnapshot
import com.manga.translator.store.parsePageBubbleRects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 页面快照查看（2026-11-02）。
 *
 * 「原图」= 翻译时存下的缩略原图；「标记版」= 用页面快照里的气泡几何实时画框：
 * 蓝框是外框、红框是内容框（渲染盖板/擦字用的就是后者）。标记版不落盘。
 */
@Composable
internal fun PageSnapshotViewerDialog(
    snapshot: PageSnapshot,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val repo = ServiceLocator.pageSnapshotRepository
    val originalFile = remember(snapshot.pageHash, snapshot.thumbnailDir) {
        repo.thumbnailFile(snapshot)
    }
    var showMarked by remember(snapshot.pageHash) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val original by produceState<Bitmap?>(null, originalFile?.absolutePath, snapshot.timestampMs) {
        value = withContext(Dispatchers.IO) {
            originalFile?.let { HistorySnapshotFactory.decode(it) }
        }
    }
    val marked by produceState<Bitmap?>(null, original, showMarked) {
        val src = original
        value = if (showMarked && src != null) {
            withContext(Dispatchers.Default) {
                HistorySnapshotFactory.annotate(
                    original = src,
                    rects = parsePageBubbleRects(snapshot.bubblesJson),
                    topPercent = snapshot.thumbnailTopPercent,
                    heightPercent = snapshot.thumbnailHeightPercent
                )
            }
        } else {
            null
        }
    }
    val shown = if (showMarked) marked ?: original else original

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 16.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            MtCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .widthIn(max = 560.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "页面快照",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MtColors.TextPrimary
                        )
                        Text(
                            text = snapshotMeta(snapshot),
                            fontSize = 11.sp,
                            color = MtColors.TextTertiary,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = "关闭",
                        fontSize = 14.sp,
                        color = MtColors.BrandInk,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SnapshotChip("原图", selected = !showMarked) { showMarked = false }
                    SnapshotChip("标记版", selected = showMarked) { showMarked = true }
                    Text(
                        text = "蓝框=外框 · 红框=内容框",
                        fontSize = 11.sp,
                        color = MtColors.TextTertiary,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MtColors.BubbleBg)
                        .padding(6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = shown
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = if (showMarked) "标记版页面快照" else "原图页面快照",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = if (showMarked) "标记版生成失败" else "图片不存在或已被删除",
                            fontSize = 13.sp,
                            color = MtColors.TextTertiary
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        enabled = shown != null,
                        onClick = {
                            val bitmap = shown ?: return@TextButton
                            val ok = HistorySnapshotFactory.saveToGallery(
                                context = context,
                                bitmap = bitmap,
                                displayName = "manga_page_${snapshot.pageHash.take(8)}_" +
                                    if (showMarked) "marked.jpg" else "original.jpg"
                            )
                            Toast.makeText(
                                context,
                                if (ok) "已保存到相册" else "保存失败",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) { Text("保存到相册", fontSize = 13.sp) }
                    TextButton(
                        enabled = originalFile != null,
                        onClick = { confirmDelete = true }
                    ) { Text("删除图片", fontSize = 13.sp, color = MtColors.DangerText) }
                    Box(modifier = Modifier.weight(1f))
                    Text(
                        text = "标记版实时生成，不额外占空间",
                        fontSize = 10.sp,
                        color = MtColors.TextTertiary
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这张页面图？") },
            text = { Text("只删图片，不影响翻译历史和句子缓存。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) { Text("删除", color = MtColors.DangerText) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 页面图片管理：单张删除 / 清空全部。
 *
 * 只展示「按页历史」里真正存下来的图；不动句子历史。总量与张数在标题下显示，
 * 用户能直接看到开关打开后的占用。
 */
@Composable
internal fun PageSnapshotManagerDialog(
    snapshots: List<PageSnapshot>,
    totalBytes: Long,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 16.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            MtCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .widthIn(max = 560.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "页面图片",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MtColors.TextPrimary
                        )
                        Text(
                            text = "${snapshots.size} 张 · ${formatBytes(totalBytes)}",
                            fontSize = 11.sp,
                            color = MtColors.TextTertiary
                        )
                    }
                    Text(
                        text = "关闭",
                        fontSize = 14.sp,
                        color = MtColors.BrandInk,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }

                if (snapshots.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "还没有保存的页面图片。\n打开「保存页面缩略图」后新翻译的页面才会存图。",
                            fontSize = 12.sp,
                            color = MtColors.TextTertiary,
                            lineHeight = 18.sp
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(snapshots, key = { it.pageHash }) { snapshot ->
                            SnapshotManagerRow(
                                snapshot = snapshot,
                                onDelete = { onDelete(snapshot.pageHash) }
                            )
                            MtDivider()
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        enabled = snapshots.isNotEmpty(),
                        onClick = { confirmClear = true }
                    ) { Text("清空全部图片", fontSize = 13.sp, color = MtColors.DangerText) }
                    Box(modifier = Modifier.weight(1f))
                    Text(
                        text = "图片上限 ${com.manga.translator.store.PageSnapshotRepository.MAX_ENTRIES} 页 / " +
                            "${com.manga.translator.store.PageSnapshotRepository.MAX_BYTES / (1024 * 1024)}MB，" +
                            "超出自动淘汰最旧",
                        fontSize = 10.sp,
                        color = MtColors.TextTertiary
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部页面图片？") },
            text = { Text("将删除 ${snapshots.size} 张图，不影响翻译历史和句子缓存。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    }
                ) { Text("清空", color = MtColors.DangerText) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SnapshotManagerRow(
    snapshot: PageSnapshot,
    onDelete: () -> Unit
) {
    val file = remember(snapshot.pageHash, snapshot.thumbnailDir) {
        ServiceLocator.pageSnapshotRepository.thumbnailFile(snapshot)
    }
    val bitmap by produceState<Bitmap?>(null, file?.absolutePath, snapshot.timestampMs) {
        value = withContext(Dispatchers.IO) {
            file?.let { HistorySnapshotFactory.decode(it, targetWidth = 96) }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 42.dp, height = 58.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MtColors.BubbleBg),
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = snapshot.originLabel.ifBlank { "页面 ${snapshot.pageHash.take(8)}" },
                fontSize = 12.sp,
                color = MtColors.TextPrimary,
                maxLines = 1
            )
            Text(
                text = "${formatSnapshotTime(snapshot.timestampMs)} · ${formatBytes(file?.length() ?: 0L)}",
                fontSize = 10.sp,
                color = MtColors.TextTertiary,
                maxLines = 1
            )
        }
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = "删除这张图片",
            tint = MtColors.TextTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onDelete)
                .padding(8.dp)
                .size(18.dp)
        )
    }
}

@Composable
private fun SnapshotChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) MtColors.Surface else MtColors.TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MtColors.Brand else MtColors.SurfaceMuted)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

private fun snapshotMeta(snapshot: PageSnapshot): String {
    val source = snapshot.originLabel.ifBlank { "页面 ${snapshot.pageHash.take(8)}" }
    val vision = if (VisionFallbackTracker.wasUsed(snapshot.pageHash)) " · 多模态重读" else ""
    return "$source · ${formatSnapshotTime(snapshot.timestampMs)}$vision"
}

private fun formatSnapshotTime(ts: Long): String {
    if (ts <= 0L) return "时间未知"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.0f KB".format(kb)
    return "%.1f MB".format(kb / 1024.0)
}
