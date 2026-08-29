package com.manga.translator.store

/**
 * PaddleOCR 本地引擎的速度/质量模式。
 *
 * - FAST：优先速度，减少识别变体与 recovery 检测，适合性能敏感或常规清晰文字场景。
 * - BALANCED：默认，在速度与召回之间取平衡。
 * - QUALITY：优先召回，跑全变体与 recovery，适合艺术字/特殊字体/低对比度场景。
 */
enum class OcrSpeedMode(
    val id: String,
    val label: String
) {
    FAST("fast", "快速（优先速度）"),
    BALANCED("balanced", "均衡"),
    QUALITY("quality", "质量（优先召回）");

    companion object {
        fun fromId(value: String): OcrSpeedMode {
            return entries.firstOrNull { it.id == value.trim().lowercase() } ?: BALANCED
        }
    }
}
