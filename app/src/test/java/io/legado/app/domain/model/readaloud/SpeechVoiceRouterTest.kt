package io.legado.app.domain.model.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechVoiceRouterTest {

    @Test
    fun `uses primary voice supported by backend`() {
        val primary = voice("server-primary", ReadAloudVoice.ENGINE_TTS_SERVER)
        val routed = SpeechVoiceRouter.route(
            cue = cue(primary),
            supportedEngineTypes = setOf(ReadAloudVoice.ENGINE_TTS_SERVER),
            defaultRoute = SpeechEngineRoute(ReadAloudVoice.ENGINE_TTS_SERVER, "default"),
        )

        assertEquals(primary, routed.voice)
        assertEquals(false, routed.usedFallback)
    }

    @Test
    fun `skips incompatible and unavailable voices before fallback`() {
        val system = voice("system", ReadAloudVoice.ENGINE_SYSTEM)
        val unavailable = voice("unavailable", ReadAloudVoice.ENGINE_TTS_SERVER).copy(available = false)
        val fallback = voice("fallback", ReadAloudVoice.ENGINE_TTS_SERVER)
        val routed = SpeechVoiceRouter.route(
            cue = cue(system, listOf(unavailable, fallback)),
            supportedEngineTypes = setOf(ReadAloudVoice.ENGINE_TTS_SERVER),
            defaultRoute = SpeechEngineRoute(ReadAloudVoice.ENGINE_TTS_SERVER, "default"),
        )

        assertEquals(fallback, routed.voice)
        assertTrue(routed.usedFallback)
    }

    @Test
    fun `creates runtime route when no configured voice is executable`() {
        val routed = SpeechVoiceRouter.route(
            cue = cue(null),
            supportedEngineTypes = setOf(ReadAloudVoice.ENGINE_TTS_SERVER),
            defaultRoute = SpeechEngineRoute(ReadAloudVoice.ENGINE_TTS_SERVER, "42"),
        )

        assertEquals(ReadAloudVoice.ENGINE_TTS_SERVER, routed.voice?.engineType)
        assertEquals("42", routed.voice?.engineId)
        assertTrue(routed.usedFallback)
    }

    private fun voice(id: String, type: String) = ReadAloudVoice(
        id = id,
        engineType = type,
        engineId = id,
        speakerId = "",
        displayName = id,
    )

    private fun cue(
        voice: ReadAloudVoice?,
        fallbacks: List<ReadAloudVoice> = emptyList(),
    ) = ReadAloudPlaybackCue(
        text = "text",
        chapterStart = 0,
        chapterEnd = 4,
        paragraphIndex = 0,
        voice = voice,
        fallbackVoices = fallbacks,
        roleType = SpeechRoleType.Narrator,
        characterId = null,
    )
}
