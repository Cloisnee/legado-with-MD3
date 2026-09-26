package io.legado.app.help.readaloud.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B31 第1阶段「逐项 T/F 判定」组装回归：
 *  - F→旁白；T 引号块独立；其他 T 按句收（。！？ 或段尾）；保险丝（：结尾/纯符号）。
 */
class SpeechSelectionAssemblyTest {

    private fun para(idx: Int, vararg texts: String): SpeechSelectionAssembly.ParaUnits {
        var pos = 0
        val units = texts.map { t ->
            val u = SpeechSelectionAssembly.Unit(pos, pos + t.length, t)
            pos += t.length
            u
        }
        return SpeechSelectionAssembly.ParaUnits(idx, units)
    }

    private fun flags(vararg v: Boolean) = BooleanArray(v.size) { v[it] }

    @Test
    fun fOnlyProducesNoSpan() {
        val r = SpeechSelectionAssembly.assemble(
            listOf(para(0, "这些事都太突然，", "不是王九可以调查出来的，")),
            mapOf(0 to flags(false, false)),
        )
        assertEquals(0, r.spans.size)
        assertEquals(2, r.narratorUnits)
    }

    @Test
    fun plainRunGroupsUntilSentenceEnd() {
        val r = SpeechSelectionAssembly.assemble(
            listOf(para(0, "想到过去，", "她垂眸，", "眼底竟无端蔓延上几分迷惘。", "李四点了点头。")),
            mapOf(0 to flags(true, true, true, false)),
        )
        assertEquals(1, r.spans.size)
        assertEquals(0, r.spans[0].start)
        assertEquals("想到过去，她垂眸，眼底竟无端蔓延上几分迷惘。".length, r.spans[0].end)
    }

    @Test
    fun plainRunSplitsAtQuestionMarks() {
        val r = SpeechSelectionAssembly.assemble(
            listOf(para(0, "几天了？", "老太婆挂了有几天了呢？")),
            mapOf(0 to flags(true, true)),
        )
        assertEquals(2, r.spans.size)
    }

    @Test
    fun plainRunClosesAtParagraphEnd() {
        val r = SpeechSelectionAssembly.assemble(
            listOf(para(0, "不对，", "这脚印是新的")),
            mapOf(0 to flags(true, true)),
        )
        assertEquals(1, r.spans.size)
        assertEquals("不对，这脚印是新的".length, r.spans[0].end)
    }

    @Test
    fun quoteBlocksStayAtomicAndSeparate() {
        val r = SpeechSelectionAssembly.assemble(
            listOf(para(0, "“你好？”", "张三，", "“你好。”")),
            mapOf(0 to flags(true, false, true)),
        )
        assertEquals(2, r.spans.size)
        assertEquals(0, r.spans[0].start)
        assertEquals("“你好？”".length, r.spans[0].end)
        val secondStart = "“你好？”".length + "张三，".length
        assertEquals(secondStart, r.spans[1].start)
    }

    @Test
    fun colonEndingIsFusedToNarrator() {
        val r = SpeechSelectionAssembly.assemble(
            listOf(para(0, "朝她笑了笑：", "“抱歉，好奇而已。”")),
            mapOf(0 to flags(true, true)),
        )
        assertEquals(1, r.fusedColon)
        assertEquals(1, r.spans.size)
        assertEquals("朝她笑了笑：".length, r.spans[0].start)
    }

    @Test
    fun symbolOnlyIsFusedToNarrator() {
        val r = SpeechSelectionAssembly.assemble(
            listOf(para(0, "……")),
            mapOf(0 to flags(true)),
        )
        assertEquals(1, r.fusedSymbol)
        assertEquals(0, r.spans.size)
    }

    @Test
    fun flagsParseVariants() {
        assertTrue(
            SpeechSelectionAssembly.parseFlags(listOf("T", "F"), 2)
                ?.contentEquals(booleanArrayOf(true, false)) == true
        )
        assertTrue(
            SpeechSelectionAssembly.parseFlags("TFFT", 4)
                ?.contentEquals(booleanArrayOf(true, false, false, true)) == true
        )
        assertTrue(
            SpeechSelectionAssembly.parseFlags(listOf(true, false), 2)
                ?.contentEquals(booleanArrayOf(true, false)) == true
        )
        assertNull(SpeechSelectionAssembly.parseFlags("TF", 3))
        assertNull(SpeechSelectionAssembly.parseFlags(listOf("X"), 1))
        assertNotNull(SpeechSelectionAssembly.parseFlags(null, 0))
        assertNull(SpeechSelectionAssembly.parseFlags(null, 2))
    }

    @Test
    fun quoteWrapHelpers() {
        assertTrue(QuoteSpeechRules.isWrappedBlock("“你好。”"))
        assertFalse(QuoteSpeechRules.isWrappedBlock("几天了？"))
        assertEquals("你好。", QuoteSpeechRules.unwrapOuterBlock("“你好。”"))
        assertEquals("嗯……", QuoteSpeechRules.unwrapOuterBlock("“嗯……"))
        assertEquals("她比他小，", QuoteSpeechRules.unwrapOuterBlock("她比他小，"))
    }
}
