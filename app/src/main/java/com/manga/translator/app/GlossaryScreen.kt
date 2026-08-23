package com.manga.translator.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.manga.translator.store.GlossaryEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryCollectionsScreen(
    collections: List<String>,
    entryCounts: Map<String, Int>,
    activeCollections: List<String>,
    onOpenCollection: (String) -> Unit,
    onActiveChange: (List<String>) -> Unit,
    onCreateCollection: (String) -> Unit,
    onImport: () -> Unit,
    onDeleteCollection: (String) -> Unit,
    onRenameCollection: (String, String) -> Boolean,
    onRefresh: () -> Unit
) {
    var creating by remember { mutableStateOf(false) }
    var deletingName by remember { mutableStateOf<String?>(null) }
    var renamingName by remember { mutableStateOf<String?>(null) }
    val pullState = rememberPullToRefreshState()
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) {
            onRefresh()
            pullState.endRefresh()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(text = "术语表", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "点击术语表进入术语管理；左划可露出右侧删除按钮；开关控制翻译时是否生效（可全部关闭）",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { creating = true }, modifier = Modifier.weight(1f)) { Text("新建术语表") }
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("导入") }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(pullState.nestedScrollConnection)
        ) {
            if (collections.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
                    Text(text = "暂无术语表。新建一个术语表或导入 JSON 开始整理术语。")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(collections, key = { it }) { name ->
                        SwipeRevealRow(onDelete = { deletingName = name }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenCollection(name) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = "${entryCounts[name] ?: 0} 条",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { renamingName = name }) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = "重命名",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = activeCollections.contains(name),
                                    onCheckedChange = {
                                        val next = if (activeCollections.contains(name)) {
                                            activeCollections.filterNot { it == name }
                                        } else {
                                            activeCollections + name
                                        }
                                        onActiveChange(next)
                                    }
                                )
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
                        DropdownMenuItem(
                            text = { Text("导出 JSON") },
                            onClick = {
                                exportMenuExpanded = false
                                onExportJson()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("导出 CSV") },
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