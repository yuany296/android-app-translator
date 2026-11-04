package com.manga.translator.store

/**
 * 句子译文的来源大类（2026-10-31，翻译历史筛选）。
 *
 * 为什么按「大类」而不是按具体站点：用户真正需要先区分的是「网页文字翻译」和
 * 「漫画/图片翻译」。具体站点仍由 [SentenceTranslation.originLabel] 表达，
 * 两者组合成「网页文字 · lezhin.com」这样的展示文案。
 *
 * 旧数据没有 `originPageHash`，按图片链路处理（网页文字翻译从引入起就写
 * `webpage-text`，不会落到空值里）；新建的网页文字会话写成
 * `webpage-text:<时间戳>`，所以判断用前缀。
 */
enum class SentenceSource(val label: String) {
    WEBPAGE_TEXT("网页文字"),
    IMAGE("漫画/图片")
}

/** 判断一条句子属于网页文字翻译还是漫画/图片翻译。 */
fun SentenceTranslation.sourceKind(): SentenceSource = sourceKindOfPageHash(originPageHash)

/** 按页面标识判断来源大类；按页分组时每一条来源可能来自不同页。 */
fun sourceKindOfPageHash(pageHash: String): SentenceSource {
    val page = pageHash.trim()
    return if (page == "webpage-text" || page.startsWith("webpage-text:")) {
        SentenceSource.WEBPAGE_TEXT
    } else {
        SentenceSource.IMAGE
    }
}
