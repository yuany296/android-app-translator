package com.manga.translator.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manga.translator.app.ui.theme.MtColors
import com.manga.translator.store.GlossaryImportFormat
import com.manga.translator.store.GlossaryTextImport

/**
 * 术语表导入入口（2026-10-27 用户反馈「不知道要导入什么东西」）。
 *
 * 一次把三条路摆在同一个弹窗里：
 * 1. 选文件（SAF 已按 JSON / CSV / Markdown 文本过滤）；
 * 2. 直接粘贴内容；
 * 3. 内置示例与实时识别结果（JSON / Markdown / CSV 各一份）。
 *
 * 粘贴时即时显示「已识别：Markdown · 2 条术语」，识别不到就明确提示看示例，
 * 用户不需要先猜格式再试错。
 */
@Composable
internal fun GlossaryImportDialog(
    onPickFile: () -> Unit,
    onImportText: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var raw by remember { mutableStateOf("") }
    val format = remember(raw) { GlossaryTextImport.detect(raw) }
    val count = remember(raw) { GlossaryTextImport.previewCount(raw) }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun dismiss() {
        keyboardController?.hide()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text("导入术语表") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "支持 JSON、CSV、Markdown 表格。可以选文件，也可以直接粘贴内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MtColors.TextSecondary
                )
                OutlinedButton(
                    onClick = {
                        keyboardController?.hide()
                        onPickFile()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("选择文件（.json / .csv / .md / .txt）")
                }
                Text(
                    text = "或直接粘贴：",
                    style = MaterialTheme.typography.labelLarge,
                    color = MtColors.TextSecondary
                )
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("粘贴 JSON / Markdown / CSV") },
                    placeholder = {
                        Text(
                            text = "| 原文 | 译文 |\n| --- | --- |\n| Eren | 艾伦 |",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    },
                    minLines = 3,
                    maxLines = 6
                )
                ImportStatusLine(raw = raw, format = format, count = count)
                TextButton(
                    onClick = { raw = MARKDOWN_EXAMPLE },
                    contentPadding = PaddingValues(0.dp)
                ) { Text("填入 Markdown 示例") }
                HorizontalDivider()
                Text(
                    text = "格式示例",
                    style = MaterialTheme.typography.labelLarge,
                    color = MtColors.TextPrimary
                )
                ImportFormatExample(title = "Markdown 表格", code = MARKDOWN_EXAMPLE)
                ImportFormatExample(title = "JSON", code = JSON_EXAMPLE)
                ImportFormatExample(title = "CSV", code = CSV_EXAMPLE)
                Text(
                    text = "JSON 也支持 {\"Eren\":\"艾伦\"} 键值对；" +
                        "表头写 source/target 或 原文/译文 都可以。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MtColors.TextTertiary
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    keyboardController?.hide()
                    onImportText(raw)
                },
                enabled = count > 0
            ) {
                Text(if (count > 0) "导入 $count 条" else "导入文本")
            }
        },
        dismissButton = {
            TextButton(onClick = { dismiss() }) { Text("取消") }
        }
    )
}

@Composable
private fun ImportStatusLine(raw: String, format: GlossaryImportFormat, count: Int) {
    val (text, color) = when {
        raw.isBlank() -> "还没有内容" to MtColors.TextTertiary
        count > 0 -> "已识别：${format.label()} · $count 条术语" to MtColors.BrandInk
        else -> "没有识别到术语，请对照下方示例检查格式" to MtColors.DangerText
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color
    )
}

@Composable
private fun ImportFormatExample(title: String, code: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MtColors.TextSecondary
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MtColors.SurfaceMuted, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MtColors.TextPrimary
            )
        }
    }
}

internal fun GlossaryImportFormat.label(): String = when (this) {
    GlossaryImportFormat.JSON -> "JSON"
    GlossaryImportFormat.CSV -> "CSV"
    GlossaryImportFormat.MARKDOWN -> "Markdown"
    GlossaryImportFormat.UNKNOWN -> "未识别"
}

private const val JSON_EXAMPLE = "{\"glossary\":[\n" +
    "  {\"source\":\"Eren\",\"target\":\"艾伦\"},\n" +
    "  {\"source\":\"Levi\",\"target\":\"利威尔\"}\n" +
    "]}"

private const val MARKDOWN_EXAMPLE = "| 原文 | 译文 |\n" +
    "| --- | --- |\n" +
    "| Eren | 艾伦 |\n" +
    "| Levi | 利威尔 |"

private const val CSV_EXAMPLE = "source,target\n" +
    "Eren,艾伦\n" +
    "Levi,利威尔"
