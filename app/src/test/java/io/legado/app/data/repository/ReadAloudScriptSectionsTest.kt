package io.legado.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B22 剧本段落存储回归：
 *  - 解析→升序重组：乱序段（如 [103][104][105][101][102]）写回后必须升序且内容不丢；
 *  - 覆盖/新增段：替换只动目标章、新增插入到正确位置；
 *  - 段尾空行归一、同章多标记合并且内容不丢。
 */
class ReadAloudScriptSectionsTest {

    private fun markersOf(content: String): List<String> =
        Regex("^\\[chapter:(\\d+)]$", RegexOption.MULTILINE)
            .findAll(content).map { it.groupValues[1] }.toList()

    @Test
    fun serializeSortsDisorderedSectionsWithoutLosingContent() {
        val content = "[chapter:103]\na3\nb3\n[chapter:104]\na4\n[chapter:101]\na1\nb1\nc1"
        val (prefix, sections) = ScriptSectionStore.parse(content)
        assertTrue(prefix.isEmpty())
        assertEquals(listOf(103, 104, 101), sections.keys.toList())
        val out = ScriptSectionStore.serialize(prefix, sections)
        assertEquals(listOf("101", "103", "104"), markersOf(out))
        assertTrue(out.contains("b1"))
        assertTrue(out.contains("a3"))
        assertTrue(out.contains("a4"))
    }

    @Test
    fun replaceSectionKeepsOthersSorted() {
        val content = "[chapter:103]\nold\n[chapter:101]\na1\n[chapter:102]\na2"
        val (prefix, sections) = ScriptSectionStore.parse(content)
        sections[103] = mutableListOf("new1", "new2")
        val out = ScriptSectionStore.serialize(prefix, sections)
        assertEquals("[chapter:101]\na1\n[chapter:102]\na2\n[chapter:103]\nnew1\nnew2", out)
    }

    @Test
    fun newSectionInsertsInOrder() {
        val content = "[chapter:101]\na1\n[chapter:103]\na3"
        val (prefix, sections) = ScriptSectionStore.parse(content)
        sections[102] = mutableListOf("a2")
        val out = ScriptSectionStore.serialize(prefix, sections)
        assertEquals("[chapter:101]\na1\n[chapter:102]\na2\n[chapter:103]\na3", out)
    }

    @Test
    fun trailingBlankLinesOfSectionAreDropped() {
        val content = "[chapter:101]\na1\n\n[chapter:102]\na2\n"
        val (prefix, sections) = ScriptSectionStore.parse(content)
        val out = ScriptSectionStore.serialize(prefix, sections)
        assertEquals("[chapter:101]\na1\n[chapter:102]\na2", out)
    }

    @Test
    fun sameChapterMarkerMergesContent() {
        val content = "[chapter:101]\na1\n[chapter:101]\nb1"
        val (prefix, sections) = ScriptSectionStore.parse(content)
        assertEquals(listOf("a1", "b1"), sections[101])
        val out = ScriptSectionStore.serialize(prefix, sections)
        assertEquals("[chapter:101]\na1\nb1", out)
    }
}
