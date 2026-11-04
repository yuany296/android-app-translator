package com.manga.translator.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 翻译历史「按页」快照（2026-11-02 用户要求）。
 *
 * 背景：当前「翻译历史」按页视图读的是句子表（`SentenceTranslation`），
 * 句子表只有文字、没有图。用户希望在按页回看时还能看到当时那一页的原图，
 * 并且有时会拿它排查其他问题，所以做成通用能力，不只服务多模态重读。
 *
 * 设计：
 * - 以 `pageHash` 为键，一页最多一张**缩略原图**（360px 宽、最长 1400px、JPEG）；
 * - 同时存这页气泡的**几何**（只存 x/y/w/h，不重复存文字），查看「标记版」时
 *   在缩略图上实时画框，不额外落盘；
 * - 与句子表完全解耦：句子表清空/淘汰不影响图，图也可以单独删；
 * - 容量双闸：最多 [MAX_ENTRIES] 页 + 总字节 [MAX_BYTES]，超了从最旧开始丢图。
 */
data class PageSnapshot(
    val pageHash: String,
    val originLabel: String,
    val timestampMs: Long,
    /** 只含几何字段的 `{"bubbles":[{"x":..,"y":..,"w":..,"h":..,"contentX":..}]}`。 */
    val bubblesJson: String,
    val thumbnailDir: String,
    val thumbnailTopPercent: Float,
    val thumbnailHeightPercent: Float
)

/** 生成页面快照时传给仓库的数据（缩放/编码在 App 层完成，仓库不依赖 Bitmap）。 */
data class PageSnapshotImage(
    val jpeg: ByteArray,
    val topPercent: Float = 0f,
    val heightPercent: Float = 100f
)

/**
 * 画「标记版」用的页面气泡几何框。
 *
 * 旧记录没有 content_* 字段时回退到 x/y/w/h（与渲染层 `BubbleGeometry.contentRect` 同口径）。
 */
data class PageBubbleRect(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val contentX: Float,
    val contentY: Float,
    val contentW: Float,
    val contentH: Float
)

fun parsePageBubbleRects(bubblesJson: String): List<PageBubbleRect> {
    if (bubblesJson.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONObject(bubblesJson).optJSONArray("bubbles") ?: JSONArray()
        val list = mutableListOf<PageBubbleRect>()
        for (index in 0 until arr.length()) {
            val item = arr.optJSONObject(index) ?: continue
            val x = item.optDouble("x", 0.0).toFloat()
            val y = item.optDouble("y", 0.0).toFloat()
            val w = item.optDouble("w", 0.0).toFloat()
            val h = item.optDouble("h", 0.0).toFloat()
            if (w <= 0f || h <= 0f) continue
            list += PageBubbleRect(
                x = x,
                y = y,
                w = w,
                h = h,
                contentX = item.optDouble("contentX", x.toDouble()).toFloat(),
                contentY = item.optDouble("contentY", y.toDouble()).toFloat(),
                contentW = item.optDouble("contentW", w.toDouble()).toFloat(),
                contentH = item.optDouble("contentH", h.toDouble()).toFloat()
            )
        }
        list
    }.getOrElse { emptyList() }
}

class PageSnapshotRepository(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val thumbsRoot = File(context.filesDir, IMAGE_DIR_NAME)
    private val lock = Any()
    private var cache: List<PageSnapshot>? = null
    private var cleanedOrphans = false

    fun all(): List<PageSnapshot> = synchronized(lock) {
        ensureCleanupLocked()
        loadAll().sortedByDescending { it.timestampMs }
    }

    fun find(pageHash: String): PageSnapshot? = synchronized(lock) {
        if (pageHash.isBlank()) return null
        ensureCleanupLocked()
        loadAll().firstOrNull { it.pageHash == pageHash }
    }

    /**
     * 保存/更新一页快照。
     *
     * 同一 `pageHash` 重复翻译时覆盖旧图与时间；`bubblesJson` 只存几何，
     * 这样索引不会随台词文本膨胀。
     */
    fun put(
        pageHash: String,
        originLabel: String,
        bubblesJson: String,
        image: PageSnapshotImage
    ): Boolean {
        if (pageHash.isBlank() || image.jpeg.isEmpty()) return false
        synchronized(lock) {
            ensureCleanupLocked()
            val entries = loadAll()
            val existing = entries.firstOrNull { it.pageHash == pageHash }
            val dirName = existing?.thumbnailDir?.takeIf { it.isNotBlank() }
                ?: sanitizeDir(pageHash)
            val dir = File(thumbsRoot, dirName)
            val existed = dir.isDirectory
            val saved = runCatching {
                dir.mkdirs()
                val target = File(dir, IMAGE_FILE_NAME)
                val tmp = File(dir, "$IMAGE_FILE_NAME.tmp")
                tmp.outputStream().use { it.write(image.jpeg) }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    target.outputStream().use { it.write(image.jpeg) }
                    tmp.delete()
                }
                true
            }.getOrElse { false }
            if (!saved) {
                // 更新已有页失败时不要连旧图一起删掉。
                if (!existed) runCatching { dir.deleteRecursively() }
                return false
            }
            val entry = PageSnapshot(
                pageHash = pageHash,
                originLabel = originLabel.trim(),
                timestampMs = System.currentTimeMillis(),
                bubblesJson = bubblesJson,
                thumbnailDir = dirName,
                thumbnailTopPercent = image.topPercent.coerceIn(0f, 100f),
                thumbnailHeightPercent = image.heightPercent.coerceIn(0.1f, 100f)
            )
            val next = entries.filterNot { it.pageHash == pageHash } + entry
            val pruned = pruneToLimit(next, MAX_ENTRIES)
            val kept = pruned.mapTo(hashSetOf()) { it.pageHash }
            next.filterNot { it.pageHash in kept }.forEach { deleteImageLocked(it) }
            write(pruneImagesToCapLocked(pruned, MAX_BYTES))
            return true
        }
    }

    /** 删除某一页的图与几何；句子历史不受影响。 */
    fun delete(pageHash: String): Boolean {
        synchronized(lock) {
            val entries = loadAll()
            val target = entries.firstOrNull { it.pageHash == pageHash } ?: return false
            deleteImageLocked(target)
            write(entries.filterNot { it.pageHash == pageHash })
            return true
        }
    }

    /** 清空全部页面快照；返回删掉的张数。 */
    fun clear(): Int {
        synchronized(lock) {
            val count = loadAll().size
            if (count == 0) return 0
            runCatching { thumbsRoot.deleteRecursively() }
            write(emptyList())
            return count
        }
    }

    fun thumbnailFile(snapshot: PageSnapshot): File? {
        val dir = snapshot.thumbnailDir.trim()
        if (dir.isEmpty()) return null
        val f = File(File(thumbsRoot, File(dir).name), IMAGE_FILE_NAME)
        return f.takeIf { it.isFile && it.length() > 0L }
    }

    fun stats(): Pair<Int, Long> = synchronized(lock) {
        ensureCleanupLocked()
        val entries = loadAll()
        var count = 0
        var bytes = 0L
        entries.forEach { entry ->
            val f = thumbnailFile(entry) ?: return@forEach
            count += 1
            bytes += f.length()
        }
        count to bytes
    }

    private fun loadAll(): List<PageSnapshot> {
        cache?.let { return it }
        val parsed = if (!file.exists()) emptyList() else {
            runCatching { parseEntriesText(file.readText()) }.getOrElse { emptyList() }
        }
        cache = parsed
        return parsed
    }

    private fun write(entries: List<PageSnapshot>) {
        cache = entries
        file.parentFile?.mkdirs()
        file.writeText(toStoreJson(entries))
    }

    private fun deleteImageLocked(entry: PageSnapshot) {
        val dir = entry.thumbnailDir.trim()
        if (dir.isEmpty()) return
        runCatching { File(thumbsRoot, File(dir).name).deleteRecursively() }
    }

    private fun pruneImagesToCapLocked(entries: List<PageSnapshot>, maxBytes: Long): List<PageSnapshot> {
        var total = entries.sumOf { thumbnailFile(it)?.length() ?: 0L }
        if (total <= maxBytes) return entries
        val mutable = entries.toMutableList()
        val oldestFirst = mutable.withIndex()
            .filter { it.value.thumbnailDir.isNotBlank() }
            .sortedBy { it.value.timestampMs }
        for ((index, entry) in oldestFirst) {
            if (total <= maxBytes) break
            val size = thumbnailFile(entry)?.length() ?: 0L
            deleteImageLocked(entry)
            mutable[index] = entry.copy(thumbnailDir = "")
            total = (total - size).coerceAtLeast(0L)
        }
        return mutable
    }

    /** 删掉没有索引引用的图片目录；进程内只跑一次。 */
    private fun ensureCleanupLocked() {
        if (cleanedOrphans) return
        cleanedOrphans = true
        runCatching {
            if (!thumbsRoot.isDirectory) return
            val referenced = loadAll()
                .mapNotNull { it.thumbnailDir.trim().takeIf(String::isNotEmpty) }
                .map { File(it).name }
                .toHashSet()
            thumbsRoot.listFiles()?.forEach { child ->
                if (child.isDirectory && child.name !in referenced) {
                    child.deleteRecursively()
                }
            }
        }
    }

    private fun sanitizeDir(pageHash: String): String =
        pageHash.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(64)
            .ifBlank { "p_${System.currentTimeMillis()}" }

    companion object {
        const val FILE_NAME = "mt_page_snapshots.json"
        const val IMAGE_DIR_NAME = "page_snapshots"
        const val IMAGE_FILE_NAME = "original.jpg"

        /** 最多保留 500 页；句子表的上限是 5000 句，按页通常远少于 500。 */
        const val MAX_ENTRIES = 500

        /** 缩略图总容量上限 50MB；单张 20-60KB，500 页通常不到 30MB。 */
        const val MAX_BYTES = 50L * 1024 * 1024

        internal fun parseEntriesText(text: String): List<PageSnapshot> {
            if (text.isBlank()) return emptyList()
            return runCatching {
                val arr = JSONObject(text).optJSONArray("entries") ?: JSONArray()
                val entries = mutableListOf<PageSnapshot>()
                for (index in 0 until arr.length()) {
                    val item = arr.optJSONObject(index) ?: continue
                    val hash = item.optString("pageHash", "").trim()
                    if (hash.isEmpty()) continue
                    entries += PageSnapshot(
                        pageHash = hash,
                        originLabel = item.optString("originLabel", ""),
                        timestampMs = item.optLong("timestampMs", 0L),
                        bubblesJson = item.optString("bubblesJson", ""),
                        thumbnailDir = item.optString("thumbnailDir", ""),
                        thumbnailTopPercent = item.optDouble("thumbnailTopPercent", 0.0).toFloat(),
                        thumbnailHeightPercent = item.optDouble("thumbnailHeightPercent", 100.0)
                            .toFloat()
                            .coerceIn(0.1f, 100f)
                    )
                }
                entries
            }.getOrElse { emptyList() }
        }

        internal fun toStoreJson(entries: List<PageSnapshot>): String {
            val arr = JSONArray()
            entries.forEach { entry ->
                arr.put(
                    JSONObject()
                        .put("pageHash", entry.pageHash)
                        .put("originLabel", entry.originLabel)
                        .put("timestampMs", entry.timestampMs)
                        .put("bubblesJson", entry.bubblesJson)
                        .put("thumbnailDir", entry.thumbnailDir)
                        .put("thumbnailTopPercent", entry.thumbnailTopPercent.toDouble())
                        .put("thumbnailHeightPercent", entry.thumbnailHeightPercent.toDouble())
                )
            }
            return JSONObject().put("entries", arr).toString()
        }

        internal fun pruneToLimit(entries: List<PageSnapshot>, max: Int): List<PageSnapshot> {
            if (entries.size <= max) return entries
            // 同一页只保留一条：put 已按 pageHash 去重，这里兜底旧数据。
            return entries.sortedByDescending { it.timestampMs }.take(max)
        }
    }
}
