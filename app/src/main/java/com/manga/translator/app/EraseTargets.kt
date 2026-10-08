package com.manga.translator.app

import com.manga.translator.translate.Bubble
import java.util.Locale

/**
 * 某个检测到的区域「值不值得擦」。
 *
 * ## 背景（2026-09-30 用户反馈）
 *
 * 夹具页（`.verify/web/v-seam-none.html`，素材 `syn1a.png + syn1b.png`）右缘有一段竖排日文
 * 「これは縦書きです」。源语言设成**韩文**时 OCR 读不出它的内容，但它**照样被区域检测检出、
 * 照样被擦除** —— 结果原文消失、位置只留下白块和没擦净的笔画残渣（用户原话：「被擦掉了，
 * 但没有被识别正确」）。
 *
 * 实测日志（`logs/quality-20260930/outline/r4-seam-none.logcat`，traceId 75711843）：
 * ```
 * bubble_first_result_stats: regions=1 assignments=2 final=3
 * sentence_cache: ... total=2          ← 3 个区域里只有 2 个有实际内容
 * erase_patch_count: count=3           ← 3 个却全被擦了
 * ```
 *
 * 根因是**擦除与识别解耦**：擦除只看「这里检出了文字区域」，不看「识别出了什么」。于是
 * 只要区域被检出，无论有没有内容可覆盖，都会擦 —— **没有译文/原文可盖时擦了就是白块**。
 *
 * ## 判据
 *
 * 全部**不依赖任何语言假设**（用户提醒过「也有可能不是日文」：读不出字的区域还可能是
 * 英文、韩文艺术字、被误检的图案）：
 *
 * 1. 没识别出原文（`originalText` 空白）—— 擦了后面没有内容可盖，必然是白块；
 * 2. 原文是**极短的纯符号**（`|`、`ー`、`。`、`..` …）—— OCR 噪声，同样没有可渲染的内容；
 * 3. 原文是**极短的混合文字系统碎片**（见 [isFragmentNoise]）—— 2026-09-30 真机实测，
 *    右缘竖排日文在韩文模型下被读成 `J유t,`（4 字符横跨拉丁+谚文），这正是用户预判的
 *    「也有可能不是日文」形态：读不对时模型会硬凑出跨系统的乱码，而不是返回空。
 */
internal fun isWorthErasing(bubble: Bubble): Boolean {
    val text = bubble.originalText.trim()
    if (text.isEmpty()) return false
    if (text.length <= NOISE_MAX_LENGTH && text.none { it.isLetterOrDigit() }) return false
    if (isFragmentNoise(text)) return false
    return true
}

/** 「极短纯符号」的长度上限。取 2：单字符噪声（`|`）与双字符噪声（`..`、`!!`）都覆盖。 */
private const val NOISE_MAX_LENGTH = 2

/**
 * 「极短混合文字系统碎片」判据。
 *
 * ## 证据（2026-09-30 真机，`logs/quality-20260930/ja-probe/after3-seam-none.logcat`）
 *
 * 夹具页右缘竖排日文「これは縦書きです」在**源语言=韩文**下被读成：
 * ```
 * erase_target: idx=1 ... box=6x23@82,55 len=4 codes=[U+004A,U+C720,U+0074,U+002C] text=[J유t,]
 * ```
 * 4 个字符横跨拉丁（`J`、`t`）与谚文（`유`）两个文字系统 —— 韩文模型不认识竖排假名，
 * 硬凑出来的乱码。这正是「读不对」的典型形态：**不是空串，而是跨系统碎片**。
 *
 * ## 规则（刻意保守，只拦最有把握的）
 *
 * - 总长 ≤ [FRAGMENT_MAX_LENGTH]：真实短句（`OK!`、`하!`、`読む`）几乎总是**单一**文字系统；
 *   品牌混写（`아이폰 15 Pro`）虽混排但更长，长文本即使混排也照擦（有译文可盖，损失可控）。
 * - 字母横跨 ≥ 2 个**文字家族**，家族按「假名与汉字同属 CJK」合并 —— 日文 `読む` 合法混用
 *   假名+汉字，不能算两种。
 *
 * 两个方向的代价不对称：误判（把真内容当碎片）= 原文没擦、原样可见，**读者无损**；
 * 漏判（把碎片当内容）= 擦掉原文、盖上垃圾译文，**画面被破坏**。拿不准时一律不擦。
 */
internal fun isFragmentNoise(text: String): Boolean {
    if (text.length > FRAGMENT_MAX_LENGTH) return false
    val families = text.mapNotNull(::scriptFamily).toSet()
    return families.size >= 2
}

/** 「混合文字系统碎片」的长度上限：只拦 5 字符以内的超短混排。 */
private const val FRAGMENT_MAX_LENGTH = 5

/**
 * 字母所属的**文字家族**；非字母（数字/标点/空格）返回 null，不参与家族计数。
 * 假名与汉字合并为 CJK 一家（日文合法混用）；其余按 Unicode 脚本归家。
 */
private fun scriptFamily(ch: Char): String? {
    if (!ch.isLetter()) return null
    return when (val script = Character.UnicodeScript.of(ch.code)) {
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HAN -> "cjk"
        Character.UnicodeScript.COMMON,
        Character.UnicodeScript.INHERITED,
        Character.UnicodeScript.UNKNOWN -> null
        else -> script.name.lowercase()
    }
}

/**
 * 擦除诊断串：把被跳过的区域及其实测原文打出来，便于确认真实噪声形态
 * （合成素材上「读不出」的形态可能是空串，也可能是 `|` 这类符号，需要证据而不是猜测）。
 */
internal fun eraseTargetsDiag(bubbles: List<Bubble>): String {
    val skipped = bubbles.filterNot { isWorthErasing(it) }
    val skippedText = if (skipped.isEmpty()) {
        "none"
    } else {
        skipped.joinToString(separator = " ") { "[${it.originalText}]" }
    }
    return "total=${bubbles.size} erasable=${bubbles.size - skipped.size} skipped=${skipped.size} skipped_text=$skippedText"
}

/**
 * **逐区域**原文诊断：每个区域一条，无条件打印（不再只在「跳过」时打印）。
 *
 * ## 为什么要无条件打印
 *
 * 2026-09-30 真机实测（`logs/quality-20260930/ja-probe/after2-seam-none.logcat`，traceId 79098483）：
 * ```
 * erase_targets: total=3 erasable=3 skipped=0 skipped_text=none   ← 改动没生效
 * refine_boxes:  b1=40x362@753,303->40x362@753,286                ← 右缘那段竖排日文
 * sampled_colors: b1=#1A1A2E/#B9C9DD                              ← 有深色墨迹，不是空
 * ```
 * 结论：那个区域的 `originalText` **非空**，所以 [isWorthErasing] 放行了它。但旧诊断只在
 * 「被跳过」时打印文本，**恰好在这种形态下什么都打不出来** —— 判据阈值只能靠猜。
 *
 * 所以这里把每个区域的实测原文、长度、前若干码点、判定结果全打出来，一次跑完即可定阈值：
 * 是 `|` 这种 1 字符噪声，还是 `M`/`a` 这种单字母残片，还是整段乱码 —— 三种形态需要的判据
 * 完全不同（前两种可以跳、第三种绝对不能跳）。
 *
 * 码点（`U+xxxx`）一起打是为了去掉肉眼歧义：全角空格、竖线、`ー`(U+30FC)、组合用浊点
 * (U+3099) 在 logcat 里几乎看不出区别。
 */
internal fun eraseTargetDetailDiag(index: Int, bubble: Bubble): String {
    val text = bubble.originalText.trim()
    val codes = text.codePoints().limit(MAX_LOGGED_CODE_POINTS.toLong()).toArray()
        .joinToString(separator = ",") { String.format(Locale.ROOT, "U+%04X", it) }
    val escaped = text.replace("\r", "\\r").replace("\n", "\\n")
    return "idx=$index keep=${if (isWorthErasing(bubble)) "Y" else "N"} bg=${bubble.bgType} " +
        "box=${bubble.w.toInt()}x${bubble.h.toInt()}@${bubble.x.toInt()},${bubble.y.toInt()} " +
        "len=${text.length} codes=[$codes] text=[$escaped]"
}

/** 诊断里最多打印多少个码点：足够区分「单字符碎片」与「一小段文本」，又不至于把 logcat 撑爆。 */
private const val MAX_LOGGED_CODE_POINTS = 24
