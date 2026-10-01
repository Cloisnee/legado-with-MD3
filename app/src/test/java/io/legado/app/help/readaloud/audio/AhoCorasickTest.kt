package io.legado.app.help.readaloud.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B33.3d · Aho-Corasick 多模式匹配（纯 JVM 单测）。 */
class AhoCorasickTest {

    @Test
    fun `多模式命中与位置`() {
        val ac = AhoCorasick(listOf("钟声", "心跳", "翻书", "兵器碰撞"))
        val text = "他听到钟声与心跳，翻书声起"
        val hits = ac.matchAll(text)
        assertEquals(setOf(0, 1, 2), hits.map { it.patternIndex }.toSet())
        val bell = hits.first { it.patternIndex == 0 }
        assertEquals("钟声", text.substring(bell.start, bell.endExclusive))
        val heart = hits.first { it.patternIndex == 1 }
        assertEquals("心跳", text.substring(heart.start, heart.endExclusive))
        val page = hits.first { it.patternIndex == 2 }
        assertEquals("翻书", text.substring(page.start, page.endExclusive))
    }

    @Test
    fun `重叠模式与失败链输出合并`() {
        val ac = AhoCorasick(listOf("AB", "BC", "ABC"))
        val flat = ac.matchAll("XABCY").map { it.patternIndex to it.start }.toSet()
        assertTrue(flat.contains(0 to 1)) // AB@1
        assertTrue(flat.contains(2 to 1)) // ABC@1
        assertTrue(flat.contains(1 to 2)) // BC@2
        assertEquals(3, flat.size)
    }

    @Test
    fun `无命中与空输入与空模式集`() {
        val ac = AhoCorasick(listOf("钟声"))
        assertEquals(0, ac.matchAll("风平浪静").size)
        assertEquals(0, ac.matchAll("").size)
        assertEquals(0, AhoCorasick(emptyList()).matchAll("随便").size)
    }
}
