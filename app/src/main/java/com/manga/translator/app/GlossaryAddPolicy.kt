package com.manga.translator.app

/**
 * 「添加术语」弹窗的提交判定（2026-10-27 用户报「无法添加术语」）。
 *
 * 事故现场：详情页底部只有一枚「添加术语」按钮。表单展开、用户填完内容后，
 * 最显眼的按钮仍然是「添加术语」—— 点它只会重置草稿（重新展开同一张表单），
 * 并不会保存；只有按键盘上的「完成」才会落库。用户按了那个按钮，输入被清空，
 * 表现出来的就是"术语加不进去"。
 *
 * 现在改成居中弹窗（原文 / 译文 / 保存 / 取消），保存动作与"新增入口"彻底分开；
 * 提交前统一走这里判定，错误直接显示在弹窗里（而不是静默关闭或只弹一个 toast）。
 * 判定口径与 [com.manga.translator.store.GlossaryRepository.add] 保持一致：
 * 去掉首尾空白后比较，区分大小写。
 */
object GlossaryAddPolicy {

    enum class Block {
        NONE,
        BLANK_SOURCE,
        BLANK_TARGET,
        DUPLICATE
    }

    /**
     * @param existingSources 当前术语表已有的原文（用于就地提示重复，
     *        避免提交后才由仓库拒绝，用户看不出原因）
     */
    fun block(
        source: String,
        target: String,
        existingSources: Collection<String>
    ): Block {
        val trimmedSource = source.trim()
        val trimmedTarget = target.trim()
        if (trimmedSource.isEmpty()) return Block.BLANK_SOURCE
        if (trimmedTarget.isEmpty()) return Block.BLANK_TARGET
        if (existingSources.any { it.trim() == trimmedSource }) return Block.DUPLICATE
        return Block.NONE
    }

    fun message(block: Block): String? = when (block) {
        Block.NONE -> null
        Block.BLANK_SOURCE -> "请填写原文"
        Block.BLANK_TARGET -> "请填写译文"
        Block.DUPLICATE -> "该原文已在术语表中"
    }
}
