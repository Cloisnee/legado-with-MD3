package io.legado.app.help.readaloud.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B30 旁白角色剥离回归：名字判定（精确 + 章节后缀防御形态）与 seqmap 旁白序号收集。
 */
class NarratorRoleStripTest {

    @Test
    fun narratorNameIsDetected() {
        assertTrue(NarratorRoleStrip.isNarratorName("旁白"))
        assertTrue(NarratorRoleStrip.isNarratorName("  旁白 "))
        assertTrue(NarratorRoleStrip.isNarratorName("旁白【第108章】"))
    }

    @Test
    fun nonNarratorNameIsKept() {
        assertFalse(NarratorRoleStrip.isNarratorName(""))
        assertFalse(NarratorRoleStrip.isNarratorName("   "))
        assertFalse(NarratorRoleStrip.isNarratorName("旁白君"))
        assertFalse(NarratorRoleStrip.isNarratorName("旁白第108章"))
        assertFalse(NarratorRoleStrip.isNarratorName("梅清河等人【第108章】"))
    }

    @Test
    fun narratorSeqsAreCollected() {
        val seqMap = mapOf(1 to "旁白", 2 to "张三", 3 to "旁白【第9章】", 4 to "李四")
        assertEquals(setOf(1, 3), NarratorRoleStrip.narratorSeqs(seqMap))
    }
}
