package com.manga.translator.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.manga.translator.app.ui.MtCard
import com.manga.translator.app.ui.theme.MtColors
import com.manga.translator.store.AiProvider
import com.manga.translator.store.AppLanguage
import com.manga.translator.store.OcrEngine
import com.manga.translator.store.SentenceTranslation
import com.manga.translator.store.SentenceTranslationRepository
import com.manga.translator.store.TranslateEngine
import com.manga.translator.store.TranslationPageInfo
import com.manga.translator.store.originsOrLegacy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 历史详情里的绝对时间；旧数据没有时间时明确写「未知」，不显示 1970。 */
internal fun formatHistoryTime(ts: Long): String {
    if (ts <= 0L) return "未知"
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))
}

internal fun formatHistoryShortTime(ts: Long): String {
    if (ts <= 0L) return "未知"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}

internal fun formatPagePosition(index: Int, total: Int): String {
    return when {
        index >= 0 && total > 0 -> "第 ${index + 1}/${total} 页"
        index >= 0 -> "第 ${index + 1} 页"
        total > 0 -> "共 $total 页"
        else -> "页码未知"
    }
}

internal fun historyLanguageLabel(id: String): String =
    AppLanguage.entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }?.label ?: id

internal fun historyLanguagePair(source: String, target: String): String {
    val src = source.trim()
    val tgt = target.trim()
    if (src.isEmpty() && tgt.isEmpty()) return "语言未知"
    return "${historyLanguageLabel(src)} → ${historyLanguageLabel(tgt)}"
}

internal fun historyOcrEngineLabel(id: String): String {
    val raw = id.trim()
    if (raw.isEmpty()) return "未知"
    return OcrEngine.entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }?.label ?: raw
}

internal fun historyTranslateEngineLabel(id: String): String {
    val raw = id.trim()
    if (raw.isEmpty()) return "未知"
    return TranslateEngine.entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }?.label ?: raw
}

/** 卡片头用的短引擎名；完整说明（尤其 ML Kit 的长括号）留给详情弹层。 */
internal fun historyTranslateEngineShortLabel(id: String): String {
    val raw = id.trim()
    return when (TranslateEngine.entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }) {
        TranslateEngine.ML_KIT -> "ML Kit 本地"
        TranslateEngine.BAIDU -> "百度翻译"
        TranslateEngine.CLOUD -> "云端 API"
        null -> raw.ifBlank { "未知" }
    }
}

internal fun historyProviderLabel(id: String): String {
    val raw = id.trim()
    if (raw.isEmpty()) return ""
    return AiProvider.entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }?.label ?: raw
}

/** 云端翻译的「厂商 · 模型」；本地/百度引擎只显示引擎名。 */
internal fun historyTranslateDetail(info: TranslationPageInfo?): String {
    if (info == null) return "未知"
    val engineId = info.translateEngine.trim()
    val engine = historyTranslateEngineLabel(engineId)
    // 本地/百度引擎不经过厂商 API：此时再显示 OpenAI / gpt-4o-mini 会让历史看起来
    // 「明明是本地翻译却用了云端模型」。厂商/模型只在云端引擎下展示。
    if (!engineId.equals(TranslateEngine.CLOUD.id, ignoreCase = true)) return engine
    val provider = historyProviderLabel(info.translateProvider)
    val model = info.translateModel.trim()
    return when {
        provider.isNotBlank() && model.isNotBlank() -> "$engine · $provider · $model"
        model.isNotBlank() -> "$engine · $model"
        provider.isNotBlank() -> "$engine · $provider"
        else -> engine
    }
}

/** 术语状态：手工改过的译文优先于术语指纹。 */
internal fun historyGlossaryState(entry: SentenceTranslation): String {
    return when {
        entry.glossaryFingerprint == SentenceTranslationRepository.MANUAL_FINGERPRINT ->
            "用户手工修改（永不被术语覆盖）"
        entry.glossaryFingerprint.isBlank() -> "无生效术语命中"
        else -> "术语指纹 ${entry.glossaryFingerprint.take(12)}…"
    }
}

internal fun historySourceLabel(label: String, pageHash: String): String {
    val host = label.trim()
    val kind = com.manga.translator.store.sourceKindOfPageHash(pageHash).label
    return when {
        host == "截图翻译" -> "截图"
        host.isNotBlank() -> "$kind · $host"
        else -> kind
    }
}

/**
 * 页面级详情（2026-11-02）：把「按页」卡片里放不下的信息集中展示。
 *
 * 旧记录没有 [TranslationPageInfo] 时仍显示页面标识、句数和来源统计，
 * 并明确说明「旧记录没有页面级详情」，避免把空字段误读成页面真的没有 URL。
 */
@Composable
internal fun TranslationPageDetailDialog(
    sourceLabel: String,
    pageHash: String,
    pageInfo: TranslationPageInfo?,
    sentenceCount: Int,
    hitCount: Int,
    firstSeenAtMs: Long,
    lastSeenAtMs: Long,
    hasSnapshot: Boolean,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            MtCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f),
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
                            text = "页面详情",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MtColors.TextPrimary
                        )
                        Text(
                            text = pageInfo?.displayTitle() ?: pageHash.ifBlank { "来源未知" },
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

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DetailRow("来源", sourceLabel)
                    DetailRow("翻译方式", pageInfo?.kind?.label ?: "未知（旧记录）")
                    DetailRow("站点/来源标签", pageInfo?.label?.ifBlank { "未知" } ?: "未知")
                    DetailRow("页面标题", pageInfo?.title?.ifBlank { "未记录" } ?: "未记录")
                    DetailRow("URL", pageInfo?.url?.ifBlank { "未记录" } ?: "未记录")
                    DetailRow(
                        "页码",
                        pageInfo?.let { formatPagePosition(it.pageIndex, it.pageTotal) } ?: "未记录"
                    )
                    DetailRow("页标识", pageHash.ifBlank { "未记录" })
                    DetailRow(
                        "语言",
                        pageInfo?.let { historyLanguagePair(it.sourceLanguage, it.targetLanguage) }
                            ?: "未记录"
                    )
                    DetailRow(
                        "OCR 引擎（配置）",
                        pageInfo?.let { historyOcrEngineLabel(it.ocrEngine) } ?: "未记录"
                    )
                    DetailRow("翻译引擎", historyTranslateDetail(pageInfo))
                    DetailRow("首次记录", formatHistoryTime(pageInfo?.createdAtMs ?: firstSeenAtMs))
                    DetailRow("最近翻译", formatHistoryTime(pageInfo?.lastTranslatedAtMs ?: lastSeenAtMs))
                    DetailRow("翻译次数", pageInfo?.translateCount?.let { "$it 次" } ?: "未记录")
                    DetailRow(
                        "本页句子",
                        buildString {
                            append("$sentenceCount 句")
                            if (hitCount > 0) append(" · 来源命中 $hitCount 次")
                        }
                    )
                    if (pageInfo != null) {
                        DetailRow(
                            "本次统计",
                            buildString {
                                append("新翻 ${pageInfo.newCount} · 复用 ${pageInfo.cacheHitCount}")
                                // 旧页面详情没有分项字段（解析为 0）：只有分项确实有数据时才展示，
                                // 避免出现「复用 2（精确 0 / 近似 0 / 模糊 0）」这种自相矛盾的记录。
                                val breakdown = pageInfo.exactCount +
                                    pageInfo.compactCount + pageInfo.fuzzyCount
                                if (breakdown > 0) {
                                    append(
                                        "（精确 ${pageInfo.exactCount} / " +
                                            "近似 ${pageInfo.compactCount} / 模糊 ${pageInfo.fuzzyCount}）"
                                    )
                                }
                                append(
                                    " · 跳过 ${pageInfo.skippedCount} · " +
                                        "术语过期 ${pageInfo.glossaryStaleCount}"
                                )
                            }
                        )
                    }
                    DetailRow("页面缩略图", if (hasSnapshot) "有（可在卡片头点开）" else "没有")
                    if (pageInfo == null) {
                        Text(
                            text = "这条是旧记录：只有句子级来源，没有 URL / 引擎 / 页码等页面详情。",
                            fontSize = 11.sp,
                            color = MtColors.TextTertiary
                        )
                    }
                    Text(
                        text = "说明：本页句子包含首次翻译和命中缓存两种；「来源命中」是这句原文" +
                            "在本页命中缓存的次数。OCR 引擎记录的是配置值，实际识别可能因" +
                            "Paddle 失败回退或多模态重读而变化。",
                        fontSize = 11.sp,
                        color = MtColors.TextTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MtColors.TextTertiary,
            modifier = Modifier.weight(0.28f)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = MtColors.TextPrimary,
            modifier = Modifier.weight(0.72f)
        )
    }
}

/** 句子来源列表（按句 / 改译文弹层共用）。 */
internal fun sentenceOriginLines(entry: SentenceTranslation): List<String> {
    return entry.originsOrLegacy().map { origin ->
        val source = historySourceLabel(origin.label.ifBlank { entry.originLabel }, origin.pageHash)
        val time = if (origin.lastSeenAtMs > 0L) {
            "最近 ${formatHistoryShortTime(origin.lastSeenAtMs)}"
        } else {
            "时间未知"
        }
        val hit = if (origin.hitCount > 0) " · 本页命中 ${origin.hitCount} 次" else ""
        val match = when (origin.lastMatchKind.trim().uppercase()) {
            "EXACT" -> " · 最近精确命中"
            "COMPACT" -> " · 最近近似命中"
            "FUZZY" -> " · 最近模糊命中"
            "NEW" -> " · 首次翻译"
            else -> ""
        }
        val hash = origin.pageHash.take(12).ifBlank { "无页标识" }
        "$source · $time$hit$match · 页 $hash"
    }
}
