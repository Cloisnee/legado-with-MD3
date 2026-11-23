package io.legado.app.domain.usecase

import io.legado.app.domain.model.readaloud.v4.RaVoiceProfile
import io.legado.app.domain.model.readaloud.v4.RaVoiceProfileKind
import io.legado.app.domain.model.readaloud.v4.VoiceProfileSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceProfileSelectorTest {

    private fun profile(
        id: String,
        kind: RaVoiceProfileKind,
        range: String = "",
        locked: Boolean = false,
        createdAt: Long = 0L,
    ) = RaVoiceProfile(
        id = id,
        bookKey = "bk",
        characterId = "chr",
        kind = kind,
        label = id,
        rangeJson = range,
        locked = locked,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun `default profile is used when no range matches`() {
        val profiles = listOf(profile("default", RaVoiceProfileKind.DEFAULT))
        assertEquals("default", VoiceProfileSelector.select(profiles, 10, 2)?.id)
    }

    @Test
    fun `range profile wins over default`() {
        val profiles = listOf(
            profile("default", RaVoiceProfileKind.DEFAULT),
            profile(
                "disguise",
                RaVoiceProfileKind.DISGUISE,
                """{"from":{"chapter":5,"paragraph":0},"to":{"chapter":8,"paragraph":-1}}""",
            ),
        )
        assertEquals("disguise", VoiceProfileSelector.select(profiles, 6, 0)?.id)
        assertEquals("default", VoiceProfileSelector.select(profiles, 9, 0)?.id)
    }

    @Test
    fun `locked range wins regardless of kind`() {
        val range = """{"from":{"chapter":1,"paragraph":0},"to":{"chapter":-1,"paragraph":-1}}"""
        val profiles = listOf(
            profile("default", RaVoiceProfileKind.DEFAULT),
            profile("age", RaVoiceProfileKind.AGE_PERIOD, range),
            profile("custom", RaVoiceProfileKind.CUSTOM, range, locked = true),
        )
        assertEquals("custom", VoiceProfileSelector.select(profiles, 3, 0)?.id)
    }

    @Test
    fun `kind priority applies when both unlocked`() {
        val range = """{"from":{"chapter":1,"paragraph":0},"to":{"chapter":10,"paragraph":-1}}"""
        val profiles = listOf(
            profile("age", RaVoiceProfileKind.AGE_PERIOD, range),
            profile("body", RaVoiceProfileKind.BODY, range),
            profile("disguise", RaVoiceProfileKind.DISGUISE, range),
        )
        assertEquals("disguise", VoiceProfileSelector.select(profiles, 5, 0)?.id)
    }

    @Test
    fun `empty profiles returns null`() {
        assertNull(VoiceProfileSelector.select(emptyList(), 1, 1))
    }

    @Test
    fun `range parsing rejects blank and malformed`() {
        assertFalse(VoiceProfileSelector.rangeContains("", 1, 1))
        assertFalse(VoiceProfileSelector.rangeContains("not-json", 1, 1))
        assertTrue(
            VoiceProfileSelector.rangeContains(
                """{"from":{"chapter":2,"paragraph":3},"to":{"chapter":4,"paragraph":5}}""",
                2,
                3,
            ),
        )
    }
}
