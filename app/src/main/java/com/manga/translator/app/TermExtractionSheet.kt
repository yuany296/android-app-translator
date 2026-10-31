package com.manga.translator.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.manga.translator.app.ui.MtDivider
import com.manga.translator.app.ui.MtDropdownItem
import com.manga.translator.app.ui.MtDropdownMenu
import com.manga.translator.app.ui.MtPrimaryButton
import com.manga.translator.app.ui.theme.MtColors
import com.manga.translator.translate.TermSuggestion

/**
 * 「AI 提取术语」的候选确认弹层（画布定稿 2026-10-31）。
 *
 * 为什么不直接入库：AI 会给出人名、地名，也会给出它自己编的东西。术语表一旦被灌错，
 * 之后每一页翻译都要照着错的来（注入 prompt）。所以这里只出**候选**，
 * 每一条都能改译名、能取消勾选，用户按下「加入 N 个」才真正写进术语表。
 *
 * 状态全在本组件内（勾选 / 改过的译名），父级只管：AI 是否还在跑、错误信息、候选原始列表、
 * 当前目标表、目标表里已有哪些原文。这样父级不需要为「用户改了第 3 条的名字」而重组整页。
 */
@Composable
fun TermExtractionSheet(
    loading: Boolean,
    error: String?,
    suggestions: List<TermSuggestion>,
    sentenceCount: Int,
    collections: List<String>,
    targetCollection: String,
    existingSources: Set<String>,
    onTargetCollectionChange: (String) -> Unit,
    /** 返回 null 表示创建成功；否则返回给用户看的错误文案。 */
    onCreateCollection: (String) -> String?,
    onRetry: () -> Unit,
    onConfirm: (List<TermSuggestion>) -> Unit,
    onDismiss: () -> Unit
) {
    // 用户改过的译名：source -> target。新增候选时按 source 合并，已改过的不被覆盖。
    var drafts by remember(suggestions) {
        mutableStateOf(suggestions.associate { it.source to it.target })
    }
    // 默认全选，但**已经在这个表里的默认不勾**：勾上再点加入只会得到「跳过」，
    // 按钮上的数字应该直接等于「这次真正会新增几条」。要改已有词的译名，
    // 应当去术语表里改，而不是靠这里重复添加。行尾仍有「已存在」标，勾回去也不会出事。
    var excluded by remember(suggestions, existingSources) {
        mutableStateOf(
            suggestions
                .filter { it.source.lowercase() in existingSources }
                .map { it.source }
                .toSet()
        )
    }
    var menuOpen by remember { mutableStateOf(false) }
    // 「新建术语库」小弹窗：建完直接选中，不把用户赶回术语表页。
    var createOpen by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var createError by remember { mutableStateOf<String?>(null) }

    val picked = suggestions
        .filter { it.source !in excluded }
        .map { it.copy(target = drafts[it.source]?.trim().orEmpty()) }
        .filter { it.target.isNotEmpty() }

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
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DragHandle()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "提取术语",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MtColors.TextPrimary
                        )
                        Text(
                            text = if (loading) {
                                "AI 正在读这 $sentenceCount 句…"
                            } else if (error != null) {
                                "提取失败"
                            } else {
                                "这 $sentenceCount 句里找到 ${suggestions.size} 个候选"
                            },
                            fontSize = 12.sp,
                            color = MtColors.TextTertiary
                        )
                    }
                    Text(
                        text = "取消",
                        fontSize = 13.sp,
                        color = MtColors.TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }

                CollectionPicker(
                    collections = collections,
                    current = targetCollection,
                    expanded = menuOpen,
                    onExpandChange = { menuOpen = it },
                    onPick = {
                        menuOpen = false
                        onTargetCollectionChange(it)
                    },
                    onCreate = {
                        newName = ""
                        createError = null
                        createOpen = true
                    }
                )

                when {
                    loading -> LoadingBlock()
                    error != null -> FailBlock(error = error, onRetry = onRetry)
                    suggestions.isEmpty() -> EmptyBlock()
                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        items(suggestions, key = { it.source }) { item ->
                            TermCandidateRow(
                                source = item.source,
                                draft = drafts[item.source].orEmpty(),
                                checked = item.source !in excluded,
                                exists = item.source.lowercase() in existingSources,
                                onToggle = {
                                    excluded = if (item.source in excluded) {
                                        excluded - item.source
                                    } else {
                                        excluded + item.source
                                    }
                                },
                                onDraftChange = { drafts = drafts + (item.source to it) }
                            )
                            MtDivider()
                        }
                    }
                }

                if (!loading && error == null && suggestions.isNotEmpty()) {
                    if (picked.isEmpty()) {
                        // 一条没选时给一句话，别留一个灰按钮让人以为坏了。
                        val allExisting = suggestions.all { it.source.lowercase() in existingSources }
                        Text(
                            text = if (allExisting) {
                                "这 ${suggestions.size} 个都已经在术语表里了，不用重复添加"
                            } else {
                                "一条都没选 · 勾上要加的再点加入"
                            },
                            fontSize = 12.sp,
                            color = MtColors.TextTertiary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        MtPrimaryButton(
                            text = "加入 ${picked.size} 个",
                            onClick = { onConfirm(picked) }
                        )
                    }
                }
            }
        }
    }

    if (createOpen) {
        CreateCollectionDialog(
            name = newName,
            error = createError,
            onNameChange = {
                newName = it
                createError = null
            },
            onDismiss = { createOpen = false },
            onConfirm = {
                val trimmed = newName.trim()
                when {
                    trimmed.isEmpty() -> createError = "名字不能为空"
                    collections.any { it.equals(trimmed, ignoreCase = true) } ->
                        createError = "已经有同名术语表"
                    else -> {
                        val failure = onCreateCollection(trimmed)
                        if (failure != null) {
                            createError = failure
                        } else {
                            createOpen = false
                            onTargetCollectionChange(trimmed)
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun CreateCollectionDialog(
    name: String,
    error: String?,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MtColors.Surface)
                .imePadding()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "新建术语库",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MtColors.TextPrimary
            )
            Text(
                text = "建好会自动选中，不用退出弹层",
                fontSize = 12.sp,
                color = MtColors.TextTertiary
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MtColors.SurfaceMuted)
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = MtColors.TextPrimary),
                    cursorBrush = SolidColor(MtColors.Brand),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (name.isEmpty()) {
                            Text("例如：某部作品名", fontSize = 14.sp, color = MtColors.TextTertiary)
                        }
                        inner()
                    }
                )
            }
            if (error != null) {
                Text(text = error, fontSize = 12.sp, color = MtColors.DangerText)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "取消",
                    fontSize = 13.sp,
                    color = MtColors.TextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
                Box(modifier = Modifier.width(6.dp))
                Text(
                    text = "创建",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MtColors.BrandInk,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onConfirm)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun DragHandle() {
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
}

/**
 * 目标术语表选择。值选择类菜单宽度对齐锚点（用户 2026-09-18 定的口径），
 * 否则弹出来一个窄条会看不清表名。
 */
@Composable
private fun CollectionPicker(
    collections: List<String>,
    current: String,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onPick: (String) -> Unit,
    onCreate: () -> Unit
) {
    var anchorWidth by remember { mutableStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "加入术语表",
                fontSize = 13.sp,
                color = MtColors.TextSecondary,
                modifier = Modifier.weight(1f)
            )
            // 常驻入口：只有一个表（或一个表都没有）时也能新建，
            // 不塞进下拉里——下拉在单表时根本点不开。
            Text(
                text = "＋ 新建术语库",
                fontSize = 13.sp,
                color = MtColors.BrandInk,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCreate)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { anchorWidth = it.width }
                    .clip(RoundedCornerShape(12.dp))
                    .background(MtColors.SurfaceMuted)
                    .clickable(enabled = collections.size > 1) { onExpandChange(true) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = current.ifBlank { "还没建术语表" },
                    fontSize = 15.sp,
                    color = MtColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (collections.size > 1) {
                    Text(text = "⌄", fontSize = 15.sp, color = MtColors.TextTertiary)
                }
            }
            MtDropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandChange(false) },
                anchorWidthPx = anchorWidth
            ) {
                collections.forEach { name ->
                    MtDropdownItem(text = name, onClick = { onPick(name) })
                }
            }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = "读这几句，找专有名词", fontSize = 13.sp, color = MtColors.TextSecondary)
        Text(
            text = "通常 3~10 秒，取决于模型和思考开关",
            fontSize = 11.sp,
            color = MtColors.TextTertiary
        )
    }
}

@Composable
private fun FailBlock(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = error, fontSize = 13.sp, color = MtColors.DangerText)
        Text(
            text = "重试",
            fontSize = 13.sp,
            color = MtColors.BrandInk,
            modifier = Modifier
                .clip(RoundedCornerShape(40.dp))
                .clickable(onClick = onRetry)
                .padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun EmptyBlock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = "这几句里没找到专有名词", fontSize = 13.sp, color = MtColors.TextSecondary)
        Text(
            text = "人名、地名、组织名才会有；普通词不算",
            fontSize = 11.sp,
            color = MtColors.TextTertiary
        )
    }
}

/**
 * 一条候选：勾选框 + 原文（只读）+ 译名（可改）+ 类型/已存在标记。
 *
 * 「已存在」的判定用**小写原文**比对，和术语表内部按 source 精确匹配的口径保持一致；
 * 已存在的默认仍然勾选 —— 用户可能就是想覆盖它，取消勾选的权利在他手上。
 */
@Composable
private fun TermCandidateRow(
    source: String,
    draft: String,
    checked: Boolean,
    exists: Boolean,
    onToggle: () -> Unit,
    onDraftChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (checked) MtColors.BrandTint else MtColors.Surface)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (checked) MtColors.Brand else MtColors.SurfaceMuted)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Text("✓", fontSize = 12.sp, color = MtColors.Surface)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = source,
                    fontSize = 13.sp,
                    color = MtColors.TextPrimary,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (exists) {
                    Box(modifier = Modifier.width(6.dp))
                    Text(
                        text = "已存在",
                        fontSize = 10.sp,
                        color = MtColors.Warning,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MtColors.SurfaceMuted)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MtColors.SurfaceMuted)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    singleLine = true,
                    enabled = checked,
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        color = if (checked) MtColors.TextPrimary else MtColors.TextTertiary
                    ),
                    cursorBrush = SolidColor(MtColors.Brand),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) {
                            Text("给个译名", fontSize = 14.sp, color = MtColors.TextTertiary)
                        }
                        inner()
                    }
                )
            }
        }
    }
}
