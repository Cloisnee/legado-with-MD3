package io.legado.app.help.readaloud.analysis

import io.legado.app.data.repository.ScriptLineRow
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptFileBackfillTest {

    private fun paragraphs(vararg texts: String): List<CanonicalSpeechParagraph> {
        var pos = 0
        return texts.mapIndexed { index, text ->
            val p = CanonicalSpeechParagraph(index, text, pos)
            pos += text.length + 1
            p
        }
    }

    private fun row(speaker: String, text: String, emotion: String = "") =
        ScriptLineRow(absIndex = 0, speaker = speaker, text = text, emotion = emotion)

    @Test
    fun `aligns sequential rows to paragraph positions`() {
        val paras = paragraphs("他抬起头。", "“你是谁？”他问道。")
        val rows = listOf(
            row("旁白", "他抬起头。"),
            row("旁白", "“你是谁？”"),
            row("他", "他问道。"),
        )

        val aligned = ScriptFileBackfill.align(paras, rows) ?: error("alignment should succeed")

        assertEquals(3, aligned.size)
        assertEquals(0, aligned[0].paragraphIndex)
        assertEquals(0, aligned[0].start)
        assertEquals(5, aligned[0].end)
        assertEquals(0, aligned[0].chapterPosition)
        assertEquals(1, aligned[1].paragraphIndex)
        assertEquals(0, aligned[1].start)
        assertEquals(6, aligned[1].end)
        assertEquals(6, aligned[1].chapterPosition)
        assertEquals(1, aligned[2].paragraphIndex)
        assertEquals(6, aligned[2].start)
        assertEquals(10, aligned[2].end)
        assertEquals(12, aligned[2].chapterPosition)
        assertEquals("他", aligned[2].speaker)
    }

    @Test
    fun `rejects ambiguous duplicate placements`() {
        val paras = paragraphs("abcdeabcde")
        val rows = listOf(
            row("旁白", "abc"),
            row("旁白", "de"),
        )

        assertNull(ScriptFileBackfill.align(paras, rows))
    }

    @Test
    fun `rejects when chapter content changed`() {
        val paras = paragraphs("全新的正文内容")
        val rows = listOf(row("旁白", "旧版本的台词"))

        assertNull(ScriptFileBackfill.align(paras, rows))
    }

    @Test
    fun `keeps zero length row anchored without breaking alignment`() {
        val paras = paragraphs("ab cd")
        val rows = listOf(
            row("旁白", "ab"),
            row("旁白", ""),
            row("旁白", "cd"),
        )

        val aligned = ScriptFileBackfill.align(paras, rows) ?: error("alignment should succeed")

        assertEquals(3, aligned.size)
        assertEquals("", aligned[1].text)
        assertEquals(2, aligned[1].start)
        assertEquals(3, aligned[2].start)
    }

    @Test
    fun `builds segments with roles emotion and identity`() {
        val paras = paragraphs("他抬起头。", "“你是谁？”他问道。")
        val rows = listOf(
            row("旁白", "他抬起头。"),
            row("旁白", "“你是谁？”"),
            row("他", "他问道。", emotion = "严肃"),
        )
        val aligned = ScriptFileBackfill.align(paras, rows) ?: error("alignment should succeed")
        val analysisId = SpeechIdentity.analysisId("book", 3, "hash", "v3-script-1")

        val segments = ScriptFileBackfill.toSegments(aligned, "book", 3, analysisId)

        assertEquals(3, segments.size)
        assertEquals(SpeechRoleType.Narrator, segments[0].roleType)
        assertEquals("", segments[0].characterName)
        assertEquals(3, segments[0].chapterIndex)
        assertEquals("book", segments[0].bookUrl)
        assertEquals(SpeechResolutionSource.Local, segments[0].source)
        assertEquals(SpeechRoleType.Character, segments[2].roleType)
        assertEquals("他", segments[2].characterName)
        assertEquals("严肃", segments[2].emotion)
        assertEquals(12, segments[2].chapterPosition)
        assertTrue(segments.all { it.analysisId == analysisId })
        assertEquals(
            segments.map { it.id }.toSet().size,
            segments.size,
        )
        assertEquals(
            SpeechIdentity.segmentId(analysisId, 1, 6, 10),
            segments[2].id,
        )
    }
}
