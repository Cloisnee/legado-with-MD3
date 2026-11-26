package io.legado.app.help.readaloud.analysis.v4

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class NdjsonChapterStreamTest {

    private fun stream(
        chapters: Set<Int> = setOf(92, 93),
        onEntities: (JSONObject) -> Unit = {},
        onChapter: (Int, JSONObject) -> Unit = { _, _ -> },
    ) = NdjsonChapterStream(chapters, onEntities, onChapter)

    @Test
    fun `line split across chunks`() {
        val committed = mutableListOf<Int>()
        val parser = stream(onChapter = { ch, _ -> committed.add(ch) })
        parser.feed("{\"type\":\"entities\",\"entities\":[]}\n")
        parser.feed("{\"type\":\"chapter\",\"ch\":92,")
        parser.feed("\"seqmap\":{}}\n")
        val result = parser.finish()
        assertEquals(listOf(92), committed)
        assertEquals(setOf(93), result.missing)
    }

    @Test
    fun `fence and bad lines are ignored`() {
        val committed = mutableListOf<Int>()
        val parser = stream(onChapter = { ch, _ -> committed.add(ch) })
        parser.feed("```json\n")
        parser.feed("{\"type\":\"entities\",\"entities\":[]}\n")
        parser.feed("not a json line\n")
        parser.feed("{\"type\":\"chapter\",\"ch\":92}\n")
        parser.feed("```\n")
        val result = parser.finish()
        assertEquals(listOf(92), committed)
        assertEquals(1, result.badLines)
    }

    @Test
    fun `chapter before entities requests fallback`() {
        val parser = stream()
        parser.feed("{\"type\":\"chapter\",\"ch\":92}\n")
        val result = parser.finish()
        assertTrue(result.needsFallback)
        assertTrue(result.committed.isEmpty())
    }

    @Test
    fun `duplicate chapter keeps first and missing is correct`() {
        val seen = mutableListOf<Int>()
        val parser = stream(chapters = setOf(92, 93, 94), onChapter = { ch, _ -> seen.add(ch) })
        parser.feed("{\"type\":\"entities\",\"entities\":[]}\n")
        parser.feed("{\"type\":\"chapter\",\"ch\":92,\"v\":1}\n")
        parser.feed("{\"type\":\"chapter\",\"ch\":92,\"v\":2}\n")
        parser.feed("{\"type\":\"end\",\"chapters\":[92]}\n")
        val result = parser.finish()
        assertEquals(listOf(92), seen)
        assertTrue(result.sawEnd)
        assertEquals(setOf(93, 94), result.missing)
    }

    @Test
    fun `retry resets attempt but keeps committed`() {
        val committed = mutableListOf<Int>()
        val parser = stream(onChapter = { ch, _ -> committed.add(ch) })
        parser.feed("{\"type\":\"entities\",\"entities\":[]}\n")
        parser.feed("{\"type\":\"chapter\",\"ch\":92}\n")
        parser.resetAttempt()
        parser.feed("{\"type\":\"entities\",\"entities\":[]}\n")
        parser.feed("{\"type\":\"chapter\",\"ch\":92}\n")
        parser.feed("{\"type\":\"chapter\",\"ch\":93}\n")
        val result = parser.finish()
        assertEquals(listOf(92, 93), committed)
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun `whole json fallback`() {
        val committed = mutableListOf<Int>()
        val parser = stream(onChapter = { ch, _ -> committed.add(ch) })
        parser.feed("```json\n{\"entities\":[],\"chapters\":[{\"ch\":92},{\"ch\":93}]}\n```")
        val result = parser.finish()
        assertTrue(result.needsFallback || result.committed.isEmpty())
        assertTrue(parser.consumeWholeJson("{\"entities\":[],\"chapters\":[{\"ch\":92},{\"ch\":93}]}"))
        assertFalse(parser.finish().raw.isEmpty())
        assertEquals(listOf(92, 93), committed)
    }
    @Test
    fun `out of range chapter is rejected in streaming and whole json`() {
        val seen = mutableListOf<Int>()
        val parser = stream(chapters = setOf(1), onChapter = { ch, _ -> seen.add(ch) })
        parser.feed("{\"type\":\"entities\",\"entities\":[]}\n{\"type\":\"chapter\",\"ch\":2}\n")
        parser.consumeWholeJson("{\"entities\":[],\"chapters\":[{\"ch\":3},{\"ch\":1}]}")
        assertEquals(listOf(1), seen)
    }

    @Test
    fun `production commit only counts after explicit transaction acknowledgement`() {
        val parser = NdjsonChapterStream(setOf(1), onChapter = { _, _ -> }, autoAcknowledge = false)
        parser.feed("{\"type\":\"entities\",\"entities\":[]}\n{\"type\":\"chapter\",\"ch\":1}\n")
        assertTrue(parser.finish().committed.isEmpty())
        parser.acknowledge(1)
        assertEquals(setOf(1), parser.finish().committed)
        assertTrue(parser.finish().missing.isEmpty())
    }

}
