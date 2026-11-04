package com.manga.translator.translate

import java.security.MessageDigest

/**
 * 术语内容指纹（2026-11-02）。
 *
 * 为什么不用 `GlossaryRepository.activeRevision()` 的 revision 求和：
 * revision 只是「改了几次」，两张表相加还会碰撞；翻译缓存需要的是
 * 「影响这次翻译的术语内容有没有变」。这里直接哈希生效词条的 source/target。
 *
 * 两种粒度：
 * - [active]：所有生效词条。用于页面级历史 key（`PageTranslationResolver.buildTranslateKey`）。
 * - [relevant]：只算这句原文里真的出现的词条。用于句子缓存，改一个无关术语不会误伤整库缓存。
 */
object GlossaryFingerprint {

    private const val VERSION = "g1"

    fun active(entries: List<Pair<String, String>>): String = hash(entries)

    fun relevant(text: String, entries: List<Pair<String, String>>): String =
        hash(entries.filter { (source, _) -> GlossaryTermMatch.matchesSourceTerm(text, source) })

    private fun hash(entries: List<Pair<String, String>>): String {
        val normalized = entries
            .map { (source, target) ->
                GlossaryTermMatch.normalize(source) to GlossaryTermMatch.normalize(target)
            }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .distinct()
            // 生效顺序不同但内容相同的两张表应当得到同一个指纹；排序后再哈希。
            .sortedWith(compareBy({ it.first }, { it.second }))
        if (normalized.isEmpty()) return ""

        val digest = MessageDigest.getInstance("SHA-1")
        normalized.forEach { (source, target) ->
            digest.update(source.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(target.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        val hex = digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
        return "$VERSION-$hex"
    }
}
