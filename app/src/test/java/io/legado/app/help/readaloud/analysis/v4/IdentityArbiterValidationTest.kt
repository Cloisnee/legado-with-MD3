package io.legado.app.help.readaloud.analysis.v4

import io.legado.app.domain.model.readaloud.v4.RaCharacterCard
import io.legado.app.domain.model.readaloud.v4.RaEvidence
import io.legado.app.domain.model.readaloud.v4.RaEvidenceScope
import io.legado.app.domain.model.readaloud.v4.RaEvidenceType
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class IdentityArbiterValidationTest {

    private fun question(): IdentityQuestion {
        val speaker = RaLocalSpeaker(
            id = "a#L1",
            analysisId = "a",
            localKey = "L1",
            surfaceNames = listOf("沈七"),
            primaryName = "沈七",
            roleType = "核心",
        )
        val card = RaCharacterCard(
            characterId = "chr_1",
            displayName = "沈孤鸿",
            aliases = emptyList(),
            roleType = "核心",
            gender = "男",
            ageBand = "男青年",
            firstChapter = 0,
            lastChapter = 0,
            timeline = emptyList(),
            relations = emptyList(),
            voiceProfiles = emptyList(),
            keyEvidence = emptyList(),
        )
        val evidence = RaEvidence(
            id = "ev1",
            bookKey = "bk",
            analysisId = "a",
            sourceChapter = 1,
            paragraphIndex = 0,
            start = 0,
            end = 6,
            excerpt = "沈七即沈孤鸿",
            type = RaEvidenceType.ALIAS_EQUAL,
            subjectLocalId = speaker.id,
            objectText = "沈孤鸿",
            scope = RaEvidenceScope.MAIN_TEXT,
            verified = true,
        )
        return IdentityQuestion(
            key = "Q1",
            chapterLabel = "第2章",
            speaker = speaker,
            evidence = listOf(evidence),
            contextText = "沈七即沈孤鸿",
            candidates = listOf(card),
        )
    }

    private fun raw(quote: String, duplicate: Boolean = false): String {
        val dup = if (duplicate) {
            "{\"q\":\"Q1\",\"verdict\":\"UNKNOWN\",\"confidence\":0.2,\"reason\":\"y\",\"evidence\":[]}"
        } else {
            ""
        }
        val second = if (dup.isEmpty()) "" else ",$dup"
        return """
            {"decisions":[
              {"q":"Q1","verdict":"SAME","characterId":"chr_1","confidence":0.99,"reason":"x",
               "evidence":[{"ch":1,"p":1,"quote":"$quote"}]}$second
            ]}
        """.trimIndent()
    }

    @Test
    fun `fake quote truncates same confidence to 06`() {
        val outcome = validateIdentityDecisions(raw("q"), listOf(question()))
        assertEquals(0.6f, outcome.data!!.single().confidence, 0.001f)
    }

    @Test
    fun `quote present in context keeps confidence`() {
        val outcome = validateIdentityDecisions(raw("沈七即沈孤鸿"), listOf(question()))
        assertEquals(0.99f, outcome.data!!.single().confidence, 0.001f)
    }

    @Test
    fun `duplicate q fails validation`() {
        assertNull(validateIdentityDecisions(raw("沈七即沈孤鸿", duplicate = true), listOf(question())).data)
    }

    @Test
    fun `missing q fails validation`() {
        val raw = """{"decisions":[]}"""
        assertNull(validateIdentityDecisions(raw, listOf(question())).data)
    }
}
