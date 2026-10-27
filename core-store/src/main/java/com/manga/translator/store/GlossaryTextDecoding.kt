package com.manga.translator.store

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 术语文件解码（2026-10-27 导入增强）。
 *
 * 浏览器下载的 JSON/Markdown 基本都是 UTF-8；但中文 Windows 的 Excel 导出的 CSV
 * 常是 GBK，直接按 UTF-8 读会整片乱码。这里先按严格 UTF-8 解，失败了再回退 GBK，
 * 并顺手去掉 UTF-8 BOM（否则 JSONObject 会因为首字符不可见而解析失败）。
 */
object GlossaryTextDecoding {

    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == utf8Bom[0] &&
            bytes[1] == utf8Bom[1] &&
            bytes[2] == utf8Bom[2]
        ) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        val utf8 = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (utf8 != null) return utf8
        return runCatching { String(bytes, charset("GBK")) }
            .getOrElse { String(bytes, StandardCharsets.UTF_8) }
    }
}
