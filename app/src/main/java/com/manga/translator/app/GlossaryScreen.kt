package com.manga.translator.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.manga.translator.app.ui.MtDropdownItem
import com.manga.translator.app.ui.theme.MtColors
import com.manga.translator.store.GlossaryCollectionInfo
import com.manga.translator.store.GlossaryConflict
import com.manga.translator.store.GlossaryEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryCollectionsScreen(
    collections: List<GlossaryCollectionInfo>,
    conflicts: List<GlossaryConflict>,
    onOpenCollection: (String) -> Unit,
    onToggleActive: (String, Boolean) -> Unit,
    onMoveActive: (String, Int) -> Unit,
    onCreateCollection: (String) -> Unit,
    onImport: () -> Unit,
    onDeleteCollection: (String) -> Unit,
    onDuplicateCollection: (String, String) -> Boolean,
    onMergeCollection: (String, String) -> Boolean,
    onRenameCollection: (String, String) -> Boolean,
    onRefresh: () -> Unit
) {
    var creating by remember { mutableStateOf(false) }
    var deletingName by remember { mutableStateOf<String?>(null) }
    var renamingName by remember { mutableStateOf<String?>(null) }
    var duplicatingName by remember { mutableStateOf<String?>(null) }
    var mergingFrom by remember { mutableStateOf<String?>(null) }
    var showConflicts by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    var reordering by remember { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) {
            onRefresh()
            pullState.endRefresh()
        }
    }

    val activeList = remember(collections) {
        collections.filter { it.active }.sortedBy { it.priority }
    }
    val inactiveList = remember(collections) {
        collections.filterNot { it.active }.sortedBy { it.name }
    }
    val totalEntries = remember(collections) { collections.sumOf { it.entryCount } }
    val conflictsByCollection = remember(conflicts) {
        conflicts.flatMap { conflict -> conflict.variants.map { it.collection } }
            .groupingBy { it }
            .eachCount()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MtColors.PageBackground)
    ) {
        GlossaryAppBar(
            subtitle = "${collections.size} 张术语表 · ${activeList.size} 张生效 · " +
                "$totalEntries 条术语",
            onShowRules = { showRules = true }
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { creating = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp,
                        focusedElevation = 0.dp,
                        hoveredElevation = 0.dp,
                        disabledElevation = 0.dp
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "新建术语表", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier
                        .width(96.dp)
                        .height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MtColors.BorderStrong),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MtColors.TextOnBrandButton
                    )
                ) {
                    Text(text = "导入", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .nestedScroll(pullState.nestedScrollConnection)
            ) {
                if (collections.isEmpty()) {
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
                            text = "还没有术语表",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MtColors.TextPrimary
                        )
                        Text(
                            text = "新建一个术语表，或从 JSON 导入已有的术语表。",
                            fontSize = 12.sp,
                            color = MtColors.TextTertiary
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        if (activeList.isNotEmpty()) {
                            item(key = "__section_active") {
                                GlossarySectionHeader(
                                    title = "生效中",
                                    hint = "序号小的优先注入翻译",
                                    onSort = if (activeList.size > 1) {
                                        { reordering = true }
                                    } else {
                                        null
                                    }
                                )
                            }
                            item(key = "__card_active") {
                                GlossaryListCard {
                                    activeList.forEachIndexed { index, info ->
                                        if (index > 0) ListRowDivider()
                                        GlossaryCollectionRow(
                                            info = info,
                                            conflictCount = conflictsByCollection[info.name] ?: 0,
                                            canMoveUp = index > 0,
                                            canMoveDown = index < activeList.size - 1,
                                            onOpen = { onOpenCollection(info.name) },
                                            onToggle = { checked -> onToggleActive(info.name, checked) },
                                            onMoveUp = { onMoveActive(info.name, -1) },
                                            onMoveDown = { onMoveActive(info.name, 1) },
                                            onRename = { renamingName = info.name },
                                            onDuplicate = { duplicatingName = info.name },
                                            onMerge = { mergingFrom = info.name },
                                            onDelete = { deletingName = info.name },
                                            onShowConflicts = { showConflicts = true }
                                        )
                                    }
                                }
                            }
                        }
                        if (inactiveList.isNotEmpty()) {
                            item(key = "__section_inactive") {
                                GlossarySectionHeader(
                                    title = "未生效",
                                    hint = "保持关闭，不会注入翻译"
                                )
                            }
                            item(key = "__card_inactive") {
                                GlossaryListCard {
                                    inactiveList.forEachIndexed { index, info ->
                                        if (index > 0) ListRowDivider()
                                        GlossaryCollectionRow(
                                            info = info,
                                            conflictCount = conflictsByCollection[info.name] ?: 0,
                                            canMoveUp = false,
                                            canMoveDown = false,
                                            onOpen = { onOpenCollection(info.name) },
                                            onToggle = { checked -> onToggleActive(info.name, checked) },
                                            onMoveUp = {},
                                            onMoveDown = {},
                                            onRename = { renamingName = info.name },
                                            onDuplicate = { duplicatingName = info.name },
                                            onMerge = { mergingFrom = info.name },
                                            onDelete = { deletingName = info.name },
                                            onShowConflicts = { showConflicts = true }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }

    if (creating) {
        CreateCollectionDialog(
            onConfirm = { name ->
                onCreateCollection(name)
                creating = false
            },
            onDismiss = { creating = false }
        )
    }

    val renaming = renamingName
    if (renaming != null) {
        RenameCollectionDialog(
            initialName = renaming,
            onConfirm = { next ->
                if (onRenameCollection(renaming, next)) {
                    renamingName = null
                }
            },
            onDismiss = { renamingName = null }
        )
    }

    val duplicating = duplicatingName
    if (duplicating != null) {
        DuplicateCollectionDialog(
            sourceName = duplicating,
            existingNames = collections.map { it.name },
            onConfirm = { next ->
                val ok = onDuplicateCollection(duplicating, next)
                if (ok) duplicatingName = null
                ok
            },
            onDismiss = { duplicatingName = null }
        )
    }

    val merging = mergingFrom
    if (merging != null) {
        MergeCollectionDialog(
            sourceName = merging,
            targets = collections.filter { it.name != merging },
            onConfirm = { target ->
                val ok = onMergeCollection(merging, target)
                if (ok) mergingFrom = null
                ok
            },
            onDismiss = { mergingFrom = null }
        )
    }

    val deleting = deletingName
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deletingName = null },
            title = { Text("删除术语表") },
            text = { Text("确定删除术语表「$deleting」吗？其中的术语会一并删除，且会从生效术语表中移除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingName = null
                        onDeleteCollection(deleting)
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletingName = null }) { Text("取消") }
            }
        )
    }

    if (showConflicts && conflicts.isNotEmpty()) {
        GlossaryConflictDialog(conflicts = conflicts, onDismiss = { showConflicts = false })
    }

    if (showRules) {
        GlossaryRulesDialog(onDismiss = { showRules = false })
    }

    if (reordering) {
        ReorderActiveDialog(
            activeList = activeList,
            onMove = { name, delta -> onMoveActive(name, delta) },
            onDismiss = { reordering = false }
        )
    }
}

/** 顶部栏：标题 22 / 副标题 12，右侧「?」承载规则说明（原页面顶部那段灰字）。 */
@Composable
private fun GlossaryAppBar(subtitle: String, onShowRules: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "术语表",
                style = MaterialTheme.typography.titleLarge,
                color = MtColors.TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MtColors.TextTertiary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MtColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, MtColors.Border, RoundedCornerShape(12.dp))
                .clickable { onShowRules() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = "规则说明",
                tint = MtColors.TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 分组头：标题 15 / 说明 12，右侧可选「排序」胶囊。 */
@Composable
private fun GlossarySectionHeader(
    title: String,
    hint: String,
    onSort: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MtColors.TextPrimary
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MtColors.TextTertiary
            )
        }
        if (onSort != null) {
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .background(MtColors.BrandTint, RoundedCornerShape(10.dp))
                    .clickable { onSort() }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "排序",
                    style = MaterialTheme.typography.labelMedium,
                    color = MtColors.Brand
                )
            }
        }
    }
}

/** 列表卡：白底 + 直角 + 1px 描边，行与行之间用内缩细线分隔（2026-09-18 用户要求改直角）。 */
@Composable
private fun GlossaryListCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MtColors.Surface, RoundedCornerShape(0.dp))
            .border(1.dp, MtColors.Border, RoundedCornerShape(0.dp)),
        content = content
    )
}

@Composable
private fun ListRowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(1.dp)
            .background(MtColors.Divider)
    )
}

@Composable
private fun GlossaryCollectionRow(
    info: GlossaryCollectionInfo,
    conflictCount: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
    onShowConflicts: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    SwipeRevealRow(onDelete = onDelete) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .clickable { onOpen() }
                .padding(start = 12.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RankBadge(info = info)
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = info.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (info.active) MtColors.TextPrimary else MtColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${info.entryCount} 条术语",
                        style = MaterialTheme.typography.bodySmall,
                        color = MtColors.TextTertiary
                    )
                    if (conflictCount > 0) {
                        ConflictTag(count = conflictCount, onClick = onShowConflicts)
                    }
                }
            }
            if (info.active) {
                Spacer(modifier = Modifier.width(12.dp))
                PriorityStepper(
                    canMoveUp = canMoveUp,
                    canMoveDown = canMoveDown,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = info.active,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MtColors.Brand,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = MtColors.SwitchOff,
                    uncheckedBorderColor = Color.Transparent
                )
            )
            Spacer(modifier = Modifier.width(4.dp))
            Box {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多操作",
                        tint = MtColors.TextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (conflictCount > 0) {
                        MtDropdownItem(
                            text = "查看冲突（$conflictCount）",
                            onClick = {
                                menuOpen = false
                                onShowConflicts()
                            }
                        )
                        HorizontalDivider()
                    }
                    MtDropdownItem(
                        text = "重命名",
                        onClick = {
                            menuOpen = false
                            onRename()
                        }
                    )
                    MtDropdownItem(
                        text = "复制为新术语表",
                        onClick = {
                            menuOpen = false
                            onDuplicate()
                        }
                    )
                    MtDropdownItem(
                        text = "合并到其他术语表…",
                        onClick = {
                            menuOpen = false
                            onMerge()
                        }
                    )
                    HorizontalDivider()
                    MtDropdownItem(
                        text = "删除",
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

/** 序号徽标：生效中显示优先级数字，未生效显示占位横杠（没有优先级语义）。 */
@Composable
private fun RankBadge(info: GlossaryCollectionInfo) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(
                color = if (info.active) MtColors.BrandTint else MtColors.SurfaceMuted,
                shape = RoundedCornerShape(10.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (info.active) "${info.priority}" else "–",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (info.active) MtColors.Brand else MtColors.TextTertiary
        )
    }
}

/** 行内冲突标签：点击即可跳到冲突详情。 */
@Composable
private fun ConflictTag(count: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(18.dp)
            .background(MtColors.DangerTint, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$count 处冲突",
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MtColors.DangerText
        )
    }
}

/**
 * 序号调整控件。视觉上是两枚 32×22 的浅底圆角块，外面套 40×34 的透明点击区，
 * 让手指能舒服点到（视觉与设计稿 1:1，命中区放大）。
 */
@Composable
private fun PriorityStepper(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Column(
        modifier = Modifier.width(40.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StepperButton(
            icon = Icons.Filled.KeyboardArrowUp,
            contentDescription = "提高优先级",
            enabled = canMoveUp,
            onClick = onMoveUp
        )
        StepperButton(
            icon = Icons.Filled.KeyboardArrowDown,
            contentDescription = "降低优先级",
            enabled = canMoveDown,
            onClick = onMoveDown
        )
    }
}

@Composable
private fun StepperButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 34.dp)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 22.dp)
                .background(MtColors.StepperBg, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) MtColors.TextSecondary else MtColors.StepperDisabled,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/** 规则说明：从页面顶部那段灰字挪到「?」里，避免占用第一屏。 */
@Composable
private fun GlossaryRulesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("规则说明") },
        text = {
            Text(
                text = "生效中的术语表会合并注入翻译；同一原文有多个译文时，序号小的获胜。\n\n" +
                    "点击条目进入管理，左划删除，⋮ 可重命名、复制、合并或删除。"
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}

/** 生效顺序集中调整：入口是分组头右侧的「排序」。 */
@Composable
private fun ReorderActiveDialog(
    activeList: List<GlossaryCollectionInfo>,
    onMove: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整生效顺序") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "序号小的先被采用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MtColors.TextTertiary
                )
                Spacer(modifier = Modifier.height(8.dp))
                activeList.forEachIndexed { index, info ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RankBadge(info = info)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = info.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MtColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        PriorityStepper(
                            canMoveUp = index > 0,
                            canMoveDown = index < activeList.size - 1,
                            onMoveUp = { onMove(info.name, -1) },
                            onMoveDown = { onMove(info.name, 1) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

@Composable
private fun GlossaryConflictDialog(
    conflicts: List<GlossaryConflict>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("术语冲突（${conflicts.size}）") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "这些原文在多张生效术语表里译文不一致。翻译时只有「生效」那一行会被采用；" +
                        "想换一个，就到列表里调整生效顺序。",
                    style = MaterialTheme.typography.bodySmall
                )
                conflicts.forEach { conflict ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = conflict.source,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    conflict.variants.forEach { variant ->
                        val effective = variant.collection == conflict.effectiveCollection &&
                            variant.target == conflict.effectiveTarget
                        Text(
                            text = (if (effective) "生效  " else "忽略  ") +
                                "${variant.collection}：${variant.target}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (effective) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = if (effective) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}

@Composable
private fun DuplicateCollectionDialog(
    sourceName: String,
    existingNames: List<String>,
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var name by remember(sourceName) {
        mutableStateOf(
            com.manga.translator.store.GlossaryRepository.uniqueCollectionName(
                existingNames,
                "$sourceName 副本"
            )
        )
    }
    val trimmed = name.trim()
    val duplicated = existingNames.contains(trimmed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("复制术语表") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("新术语表名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = duplicated
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (duplicated) {
                        "名称已存在，换一个。"
                    } else {
                        "会连同「$sourceName」的所有术语一起复制；副本默认不生效。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (duplicated) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty() && !duplicated
            ) { Text("复制") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun MergeCollectionDialog(
    sourceName: String,
    targets: List<GlossaryCollectionInfo>,
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var selected by remember(sourceName, targets) {
        mutableStateOf(targets.firstOrNull()?.name.orEmpty())
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .background(MtColors.Surface, RoundedCornerShape(22.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "合并术语表",
                style = MaterialTheme.typography.titleMedium,
                color = MtColors.TextPrimary
            )
            Text(
                text = "把「$sourceName」的术语并入下面选中的术语表。合并后「$sourceName」会被删除；" +
                    "两边都有的原文会跳过，保留目标表原来的译文。",
                style = MaterialTheme.typography.bodyMedium,
                color = MtColors.TextSecondary
            )
            if (targets.isEmpty()) {
                Text(
                    text = "至少要有两张术语表才能合并。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MtColors.Danger
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    targets.forEach { target ->
                        MergeTargetOption(
                            target = target,
                            selected = target.name == selected,
                            onSelect = { selected = target.name }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 92.dp, height = 44.dp)
                        .background(MtColors.SurfaceMuted, RoundedCornerShape(12.dp))
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "取消",
                        style = MaterialTheme.typography.labelLarge,
                        color = MtColors.TextOnMutedButton
                    )
                }
                Box(
                    modifier = Modifier
                        .size(width = 104.dp, height = 44.dp)
                        .background(
                            color = if (selected.isNotEmpty()) MtColors.Brand else MtColors.SwitchOff,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable(enabled = selected.isNotEmpty()) {
                            if (onConfirm(selected)) onDismiss()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "合并",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
            }
        }
    }
}

/** 合并目标选项：56 高、14 圆角，选中时浅蓝底 + 蓝描边 + 蓝色单选框。 */
@Composable
private fun MergeTargetOption(
    target: GlossaryCollectionInfo,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(
                color = if (selected) MtColors.BrandTint else MtColors.Surface,
                shape = RoundedCornerShape(14.dp)
            )
            .border(
                width = 1.dp,
                color = if (selected) MtColors.BrandBorder else MtColors.Border,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onSelect() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    width = 1.6.dp,
                    color = if (selected) MtColors.Brand else MtColors.SwitchOff,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(MtColors.Brand, CircleShape)
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = target.name,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) MtColors.BrandDeep else MtColors.TextOnMutedButton,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${target.entryCount} 条术语 · " +
                    if (target.active) "序号 ${target.priority}" else "未生效",
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MtColors.BrandTextSoft else MtColors.TextTertiary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryCollectionScreen(
    collection: String,
    entries: List<GlossaryEntry>,
    onBack: () -> Unit,
    onAdd: (String, String) -> Boolean,
    onRename: (String, String, String) -> Boolean,
    onUpdate: (String, String) -> Boolean,
    onRemove: (String) -> Unit,
    onDeleteMany: (List<String>) -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onRefresh: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    var editingSourceKey by remember { mutableStateOf<String?>(null) }
    var editingTargetKey by remember { mutableStateOf<String?>(null) }
    var sourceDraft by remember { mutableStateOf("") }
    var targetDraft by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var addSourceDraft by remember { mutableStateOf("") }
    var addTargetDraft by remember { mutableStateOf("") }
    var batchMode by remember { mutableStateOf(false) }
    var selectedSources by remember { mutableStateOf(setOf<String>()) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var exportMenuExpanded by remember { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) {
            onRefresh()
            pullState.endRefresh()
        }
    }

    val filteredEntries = remember(entries, query) {
        if (query.isBlank()) {
            entries
        } else {
            val normalized = query.trim()
            entries.filter { entry ->
                entry.source.contains(normalized, ignoreCase = true) ||
                    entry.target.contains(normalized, ignoreCase = true)
            }
        }
    }

    fun commitSourceEdit(entry: GlossaryEntry): Boolean {
        if (editingSourceKey != entry.source) return true
        val draft = sourceDraft.trim()
        val ok = if (draft.isNotEmpty() && draft != entry.source) {
            onRename(entry.source, draft, entry.target)
        } else {
            true
        }
        editingSourceKey = null
        return ok
    }

    fun commitTargetEdit(entry: GlossaryEntry) {
        if (editingTargetKey != entry.source) return
        val draft = targetDraft.trim()
        if (draft.isNotEmpty() && draft != entry.target) {
            onUpdate(entry.source, draft)
        }
        editingTargetKey = null
    }

    fun commitAdd() {
        val source = addSourceDraft.trim()
        val target = addTargetDraft.trim()
        if (source.isNotEmpty() && target.isNotEmpty()) {
            if (onAdd(source, target)) {
                adding = false
                addSourceDraft = ""
                addTargetDraft = ""
            }
        } else {
            adding = false
            addSourceDraft = ""
            addTargetDraft = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = collection,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (batchMode) {
                val allSelected = filteredEntries.isNotEmpty() &&
                    selectedSources.size == filteredEntries.size
                TextButton(onClick = {
                    selectedSources = if (allSelected) {
                        emptySet()
                    } else {
                        filteredEntries.map { it.source }.toSet()
                    }
                }) { Text(if (allSelected) "取消全选" else "全选") }
                TextButton(
                    onClick = { confirmDeleteSelected = true },
                    enabled = selectedSources.isNotEmpty()
                ) { Text("删除(${selectedSources.size})") }
            } else {
                Box {
                    OutlinedButton(onClick = { exportMenuExpanded = true }) { Text("导出") }
                    DropdownMenu(
                        expanded = exportMenuExpanded,
                        onDismissRequest = { exportMenuExpanded = false }
                    ) {
                        MtDropdownItem(
                            text = "导出 JSON",
                            onClick = {
                                exportMenuExpanded = false
                                onExportJson()
                            }
                        )
                        MtDropdownItem(
                            text = "导出 CSV",
                            onClick = {
                                exportMenuExpanded = false
                                onExportCsv()
                            }
                        )
                    }
                }
                Button(onClick = {
                    batchMode = true
                    selectedSources = emptySet()
                }, enabled = entries.isNotEmpty()) { Text("编辑") }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索（原文/译文）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(pullState.nestedScrollConnection)
        ) {
            if (filteredEntries.isEmpty() && !adding) {
                Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
                    Text(
                        text = if (query.isBlank()) {
                            "暂无术语。点击下方「添加」新增条目；点击条目可直接修改。"
                        } else {
                            "没有匹配「$query」的术语"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredEntries, key = { it.source }) { entry ->
                        val editingSource = editingSourceKey == entry.source
                        val editingTarget = editingTargetKey == entry.source
                        if (batchMode) {
                            val selected = selectedSources.contains(entry.source)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .clickable {
                                        selectedSources = if (selected) {
                                            selectedSources - entry.source
                                        } else {
                                            selectedSources + entry.source
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { checked ->
                                        selectedSources = if (checked) {
                                            selectedSources + entry.source
                                        } else {
                                            selectedSources - entry.source
                                        }
                                    }
                                )
                                Text(
                                    text = entry.source,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "→ ${entry.target}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            HorizontalDivider()
                            return@items
                        }
                        SwipeRevealRow(onDelete = { onRemove(entry.source) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (editingSource || editingTarget) {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (editingSource) {
                                    var sourceHadFocus by remember { mutableStateOf(false) }
                                    OutlinedTextField(
                                        value = sourceDraft,
                                        onValueChange = { sourceDraft = it },
                                        modifier = Modifier
                                            .weight(1f)
                                            .onFocusChanged { state ->
                                                if (!state.isFocused && sourceHadFocus) {
                                                    commitSourceEdit(entry)
                                                }
                                                sourceHadFocus = state.isFocused
                                            },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyLarge,
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                            onDone = {
                                                commitSourceEdit(entry)
                                                focusManager.clearFocus()
                                                keyboardController?.hide()
                                            }
                                        )
                                    )
                                } else {
                                    Text(
                                        text = entry.source,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                if (editingTargetKey != null) {
                                                    entries.firstOrNull { it.source == editingTargetKey }
                                                        ?.let { commitTargetEdit(it) }
                                                }
                                                if (editingSourceKey == null) {
                                                    editingSourceKey = entry.source
                                                    sourceDraft = entry.source
                                                }
                                            }
                                            .padding(vertical = 8.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                if (editingTarget) {
                                    var targetHadFocus by remember { mutableStateOf(false) }
                                    OutlinedTextField(
                                        value = targetDraft,
                                        onValueChange = { targetDraft = it },
                                        modifier = Modifier
                                            .weight(1f)
                                            .onFocusChanged { state ->
                                                if (!state.isFocused && targetHadFocus) {
                                                    commitTargetEdit(entry)
                                                }
                                                targetHadFocus = state.isFocused
                                            },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyLarge,
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                            onDone = {
                                                commitTargetEdit(entry)
                                                focusManager.clearFocus()
                                                keyboardController?.hide()
                                            }
                                        )
                                    )
                                } else {
                                    Text(
                                        text = "→ ${entry.target}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                if (editingSourceKey != null) {
                                                    val activeEntry = entries.firstOrNull {
                                                        it.source == editingSourceKey
                                                    }
                                                    if (activeEntry != null) {
                                                        val renamedOk = commitSourceEdit(activeEntry)
                                                        val newSource = if (
                                                            activeEntry.source == entry.source &&
                                                            renamedOk &&
                                                            sourceDraft.trim().isNotEmpty()
                                                        ) {
                                                            sourceDraft.trim()
                                                        } else {
                                                            entry.source
                                                        }
                                                        editingTargetKey = newSource
                                                        targetDraft = entry.target
                                                    }
                                                } else if (editingTargetKey == null) {
                                                    editingTargetKey = entry.source
                                                    targetDraft = entry.target
                                                }
                                            }
                                            .padding(vertical = 8.dp)
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
        if (adding) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = addSourceDraft,
                    onValueChange = { addSourceDraft = it },
                    label = { Text("原文") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Next)
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = addTargetDraft,
                    onValueChange = { addTargetDraft = it },
                    label = { Text("译文") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = {
                            commitAdd()
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    )
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                adding = true
                addSourceDraft = ""
                addTargetDraft = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("添加术语") }
    }

    if (confirmDeleteSelected) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text("删除术语") },
            text = { Text("确定删除选中的 ${selectedSources.size} 条术语吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteSelected = false
                        val toDelete = selectedSources.toList()
                        selectedSources = emptySet()
                        batchMode = false
                        onDeleteMany(toDelete)
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSelected = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun CreateCollectionDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建术语表") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("术语表名称（如：进击的巨人）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun RenameCollectionDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名术语表") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("新的术语表名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank() && name.trim() != initialName.trim()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun ImportCollectionDialog(
    defaultName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(defaultName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入为术语表") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("术语表名称（默认取文件名）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "导入的 JSON 会创建为新术语表。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}