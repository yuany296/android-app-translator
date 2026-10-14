package com.manga.translator.store
/**
死代码。废弃。
 */
enum class OcrMode(
    val id: String,
    val label: String
) {
    REMOTE_OCR("remote_ocr", "远程OCR"),
    LOCAL_DETECT_AND_LLM_GROUP("local_detect_and_llm_group", "本地检测+LLM分组");

    companion object {
        fun fromId(value: String): OcrMode {
            return entries.firstOrNull { it.id == value.trim().lowercase() } ?: REMOTE_OCR
        }
    }
}
