package io.legado.app.help.readaloud.analysis.v4

import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerBinderTest {

    private fun speaker(key: String, primary: String, aliases: List<String>) = RaLocalSpeaker(
        id = "analysis#$key",
        analysisId = "analysis",
        localKey = key,
        surfaceNames = aliases + primary,
        primaryName = primary,
        roleType = "核心",
    )

    private fun segment(seq: Int, surface: String, role: SpeechRoleType = SpeechRoleType.Character) =
        RaSegment(
            id = "s$seq",
            analysisId = "analysis",
            bookKey = "bk",
            chapterIndex = 0,
            paragraphIndex = 0,
            start = 0,
            end = 1,
            chapterPosition = 0,
            text = "x",
            roleType = role,
            seq = seq,
            surfaceName = surface,
            source = SpeechResolutionSource.Ai,
        )

    @Test
    fun `shared alias does not steal another speaker lines`() {
        val speakers = listOf(
            speaker("L1", "张三", listOf("老者")),
            speaker("L2", "李四", listOf("老者")),
        )
        val bound = SpeakerBinder.bind(
            listOf(segment(1, "张三"), segment(2, "李四")),
            speakers,
            mapOf("L1" to "c1", "L2" to "c2"),
            mapOf(1 to "张三", 2 to "李四"),
        )
        assertEquals(listOf("analysis#L1", "analysis#L2"), bound.map { it.localSpeakerId })
        assertEquals(listOf("c1", "c2"), bound.map { it.characterId })
    }

    @Test
    fun `narrator segment clears speaker but keeps sequence map intact`() {
        val bound = SpeakerBinder.bind(
            listOf(segment(1, "旁白", SpeechRoleType.Narrator), segment(2, "李四")),
            listOf(speaker("L2", "李四", emptyList())),
            mapOf("L2" to "c2"),
            mapOf(2 to "李四"),
        )
        assertNull(bound[0].localSpeakerId)
        assertNull(bound[0].characterId)
        assertEquals(0, bound[0].seq)
        assertEquals("analysis#L2", bound[1].localSpeakerId)
        assertEquals("c2", bound[1].characterId)
    }
}
