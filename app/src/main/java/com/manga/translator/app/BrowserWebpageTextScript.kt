package com.manga.translator.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「网页文字翻译」的页面侧脚本（2026-10-14，参考 C:\homework\translator 扩展的
 * `content/modules/webpage-scanner.js` / `webpage-renderer.js`，按 WebView 场景收敛）。
 *
 * 与漫画图链路完全独立：不碰 `<img>`/`<canvas>`，只处理**文本节点** ——
 * 用于「漫画站之外还有一堆 UI 文字 / 章节列表 / 评论区是外语」的页面。
 *
 * 关键取舍（对齐参考项目）：
 * - 回填**直接替换 `nodeValue`**，不包 span —— 包 span 会破坏站点的 CSS / 布局 / hydration；
 * - 节点状态存页面侧注册表（`window.__mtWebpageText.nodes`），「恢复原文」按注册表回写；
 * - 回填有守卫：节点还在文档里、且当前值仍等于原文（页面自己改过就跳过，不覆盖用户看到的内容）。
 */
object BrowserWebpageTextScript {

    /** 单次收集的文本节点上限（参考项目 MAX_SCAN_NODES = 1500；我们再多一道字符预算）。 */
    const val MAX_NODES = 1200

    /** 每批最多几段 / 多少字符（谁先到谁切批；参考项目默认 32 段 / 3200 字符，我们取小批快回）。 */
    const val BATCH_MAX_ITEMS = 24
    const val BATCH_MAX_CHARS = 1600

    /**
     * 安装脚本（幂等）。`window.__mtWebpageText` 暴露：
     * - `collect(maxNodes)` → `JSON.stringify({count, items:[{id,text}]})`，同时重置注册表；
     * - `apply(json)`       → `JSON.stringify({applied})`，`json = [{id, t}]`；
     * - `restore()`         → `JSON.stringify({restored})`，把已回填的节点还原成原文。
     */
    val INSTALL = """
        (function() {
          if (window.__mtWebpageText) { return 'ok'; }
          var EXCLUDED = { SCRIPT:1, STYLE:1, NOSCRIPT:1, CODE:1, PRE:1, TEXTAREA:1, INPUT:1,
                           SELECT:1, OPTION:1, TITLE:1, META:1, LINK:1, IFRAME:1, SVG:1 };
          var MT_OWN_UI = '[data-mt-id],.__mt_overlay,.__mt_scene_root,.__mt_loading_root,.__mt_seam_root,.mt-overlay-root,.mt-loading-card,[data-mt-webpage]';
          function normalize(t) { return String(t == null ? '' : t).replace(/\s+/g, ' ').trim(); }
          function isHidden(el) {
            var style = window.getComputedStyle(el);
            if (!style || style.display === 'none' || style.visibility === 'hidden') { return true; }
            var rect = el.getBoundingClientRect();
            return el.offsetParent === null && rect.width === 0 && rect.height === 0;
          }
          function shouldSkipEl(el) {
            if (!el || el.nodeType !== 1) { return true; }
            if (EXCLUDED[el.tagName]) { return true; }
            if (el.isContentEditable) { return true; }
            if (el.closest && el.closest('[contenteditable]')) { return true; }
            if (el.closest && el.closest(MT_OWN_UI)) { return true; }
            return false;
          }
          function looksLikeUrl(t) {
            return /^(https?:\/\/|www\.|mailto:)/i.test(t) || /[\w.+-]+@[\w-]+\.[\w.]+/.test(t);
          }
          function looksLikeCode(t) {
            if (/[\{\};]/.test(t)) { return true; }
            if (/^(function|const|let|var|import|export|=>)/.test(t)) { return true; }
            return false;
          }
          var state = { nodes: [] };
          window.__mtWebpageText = {
            collect: function(maxNodes) {
              state.nodes = [];
              var limit = maxNodes || 1200;
              if (!document.body) { return JSON.stringify({ count: 0, items: [] }); }
              var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
              var items = [];
              var node;
              while ((node = walker.nextNode()) && state.nodes.length < limit) {
                var parent = node.parentElement;
                if (!parent || shouldSkipEl(parent)) { continue; }
                if (isHidden(parent)) { continue; }
                var text = normalize(node.nodeValue);
                if (!text) { continue; }
                if (/^[\s\p{P}\p{S}\d]+$/u.test(text)) { continue; }
                if (looksLikeUrl(text) || looksLikeCode(text)) { continue; }
                var id = state.nodes.length;
                state.nodes.push({ node: node, original: node.nodeValue, translated: null });
                items.push({ id: id, text: text });
              }
              return JSON.stringify({ count: state.nodes.length, items: items });
            },
            apply: function(json) {
              var items;
              try { items = JSON.parse(json); } catch (e) { return JSON.stringify({ applied: 0 }); }
              var applied = 0;
              for (var i = 0; i < items.length; i++) {
                var it = items[i];
                var rec = state.nodes[it.id];
                if (!rec || !rec.node || !rec.node.isConnected) { continue; }
                var current = rec.node.nodeValue;
                if (current == null || normalize(current) !== normalize(rec.original)) { continue; }
                var value = String(it.t == null ? '' : it.t);
                if (!value) { continue; }
                var lead = current.match(/^\s*/)[0];
                var trail = current.match(/\s*$/)[0];
                var next = lead + value + trail;
                rec.node.nodeValue = next;
                rec.translated = next;
                applied += 1;
              }
              return JSON.stringify({ applied: applied });
            },
            restore: function() {
              var restored = 0;
              for (var i = 0; i < state.nodes.length; i++) {
                var rec = state.nodes[i];
                if (!rec || !rec.node || !rec.node.isConnected) { continue; }
                if (rec.translated != null && rec.node.nodeValue === rec.translated) {
                  rec.node.nodeValue = rec.original;
                  restored += 1;
                }
                rec.translated = null;
              }
              return JSON.stringify({ restored: restored });
            }
          };
          return 'ok';
        })();
    """.trimIndent()

    /** 第一步：安装（幂等）。单独一次求值 —— 别塞 eval，严格 CSP 的站点会拦。 */
    fun installJs(): String = INSTALL

    fun collectJs(maxNodes: Int = MAX_NODES): String =
        "window.__mtWebpageText.collect($maxNodes)"

    fun applyJs(itemsJson: String): String =
        "(function(){ return window.__mtWebpageText ? window.__mtWebpageText.apply(${JSONObject.quote(itemsJson)}) : JSON.stringify({applied:0}); })()"

    fun restoreJs(): String =
        "(function(){ return window.__mtWebpageText ? window.__mtWebpageText.restore() : JSON.stringify({restored:0}); })()"

    /** 收集结果里的一条：页面侧注册表里的下标 + 归一化后的文本。 */
    data class Item(val id: Int, val text: String)

    /** 解析 `collect` 的回传（外层 JSON 字符串被 WebView 再引号包裹过一层）。 */
    fun parseCollectResult(decoded: JSONObject?): List<Item> {
        val items = decoded?.optJSONArray("items") ?: return emptyList()
        val result = mutableListOf<Item>()
        for (index in 0 until items.length()) {
            val entry = items.optJSONObject(index) ?: continue
            val text = entry.optString("text").trim()
            if (text.isEmpty()) continue
            result += Item(id = entry.optInt("id", -1), text = text)
        }
        return result
    }

    /**
     * 切批：段数与字符数谁先到谁切（参考项目 `webpage-batches.js#buildWebpageBatches`）。
     * 超长单段自己成批，不和别人拼。
     */
    fun buildBatches(
        items: List<Item>,
        maxItems: Int = BATCH_MAX_ITEMS,
        maxChars: Int = BATCH_MAX_CHARS
    ): List<List<Item>> {
        if (items.isEmpty()) return emptyList()
        val batches = mutableListOf<List<Item>>()
        var current = mutableListOf<Item>()
        var currentChars = 0
        for (item in items) {
            val chars = item.text.length
            if (current.isNotEmpty() && (current.size >= maxItems || currentChars + chars > maxChars)) {
                batches += current
                current = mutableListOf()
                currentChars = 0
            }
            current += item
            currentChars += chars
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    /** `[{id, t}]` 的回填载荷。 */
    fun applyPayload(entries: List<Pair<Int, String>>): String {
        val array = JSONArray()
        entries.forEach { (id, text) ->
            array.put(JSONObject().put("id", id).put("t", text))
        }
        return array.toString()
    }
}
