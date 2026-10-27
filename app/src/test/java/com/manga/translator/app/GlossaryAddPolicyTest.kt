package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 新增术语弹窗的提交判定。
 *
 * 回归闸：用户 2026-10-27 报「无法添加术语」——底部内嵌表单填完点「添加术语」会被清空。
 * 现在改成居中弹窗（原文 / 译文 / 保存 / 取消），但判定本身必须在策略层钉住：
 * 空白、重复都不能提交，且错误文案要能就地显示。
 */
class GlossaryAddPolicyTest {

    private val existing = listOf("Eren", "Levi")

    @Test
    fun blankSource_isRejectedEvenWithWhitespace() {
        assertEquals(
            GlossaryAddPolicy.Block.BLANK_SOURCE,
            GlossaryAddPolicy.block("   ", "艾伦", existing)
        )
        assertEquals(
            GlossaryAddPolicy.Block.BLANK_SOURCE,
            GlossaryAddPolicy.block("", "", existing)
        )
    }

    @Test
    fun blankTarget_isRejected() {
        assertEquals(
            GlossaryAddPolicy.Block.BLANK_TARGET,
            GlossaryAddPolicy.block("Eren", "  ", existing)
        )
    }

    @Test
    fun duplicateSourceAfterTrim_isRejected() {
        assertEquals(
            GlossaryAddPolicy.Block.DUPLICATE,
            GlossaryAddPolicy.block("  Eren  ", "艾伦", existing)
        )
    }

    @Test
    fun sourceMatchingIsCaseSensitive_sameAsRepository() {
        assertEquals(
            GlossaryAddPolicy.Block.NONE,
            GlossaryAddPolicy.block("eren", "艾伦", existing)
        )
    }

    @Test
    fun freshPair_isAccepted() {
        assertEquals(
            GlossaryAddPolicy.Block.NONE,
            GlossaryAddPolicy.block("  Mikasa  ", "三笠", existing)
        )
    }

    @Test
    fun messagesCoverEveryBlock() {
        assertNull(GlossaryAddPolicy.message(GlossaryAddPolicy.Block.NONE))
        assertEquals("请填写原文", GlossaryAddPolicy.message(GlossaryAddPolicy.Block.BLANK_SOURCE))
        assertEquals("请填写译文", GlossaryAddPolicy.message(GlossaryAddPolicy.Block.BLANK_TARGET))
        assertEquals("该原文已在术语表中", GlossaryAddPolicy.message(GlossaryAddPolicy.Block.DUPLICATE))
    }
}
