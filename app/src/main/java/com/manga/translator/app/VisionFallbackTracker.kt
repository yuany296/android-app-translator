package com.manga.translator.app

/**
 * 本次进程内「哪些页用过多模态重读」的轻量记录（2026-11-02）。
 *
 * 目的：用户看到「框太大」时，不必再翻 logcat 找 `ocr_vision_fallback replace=true`；
 * 「翻译历史 → 按页」的卡片头/页面快照会直接标出「多模态重读」。
 *
 * 只记 pageHash，不存图、不落盘；进程重启后清空（页面元数据/快照仍在，只是少了这个标记）。
 * 上限 [MAX_PAGES] 页，超出淘汰最久未访问的，避免长会话无限增长。
 */
object VisionFallbackTracker {
    private const val MAX_PAGES = 200

    private val pages = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Boolean>?
        ): Boolean = size > MAX_PAGES
    }

    @Synchronized
    fun mark(pageHash: String) {
        val key = pageHash.trim()
        if (key.isEmpty()) return
        pages[key] = true
    }

    @Synchronized
    fun wasUsed(pageHash: String): Boolean {
        val key = pageHash.trim()
        return key.isNotEmpty() && pages.containsKey(key)
    }
}
