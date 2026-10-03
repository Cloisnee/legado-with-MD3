package io.legado.app.help.readaloud.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPositionsTest {

    @Test
    fun `命中位换算并钳制`() {
        assertEquals(0.05f, AudioPositions.ratioOfMatch(0, 10), 0.0001f)
        assertEquals(0.5f, AudioPositions.ratioOfMatch(5, 10), 0.0001f)
        assertEquals(0.9f, AudioPositions.ratioOfMatch(9, 10), 0.0001f)
        assertEquals(0.95f, AudioPositions.ratioOfMatch(50, 10), 0.0001f)
        assertEquals(0f, AudioPositions.ratioOfMatch(3, 0), 0.0001f)
        assertEquals(0f, AudioPositions.ratioOfMatch(-1, 10), 0.0001f)
    }

    @Test
    fun `片段起点与片内命中合成`() {
        assertEquals(0.1f, AudioPositions.ratioOfUnit(0, 100, 10, 10, 0f), 0.0001f)
        assertEquals(0.2f, AudioPositions.ratioOfUnit(0, 100, 10, 10, 1f), 0.0001f)
        assertEquals(0f, AudioPositions.ratioOfUnit(0, 100, 0, 10, 0f), 0.0001f)
        assertEquals(1f, AudioPositions.ratioOfUnit(0, 100, 100, 10, 1f), 0.0001f)
    }

    @Test
    fun `延迟估算`() {
        assertEquals(1250L, AudioPositions.delayMs(10, 0.5f, 4f))
        assertEquals(0L, AudioPositions.delayMs(10, 0f, 4f))
        assertEquals(0L, AudioPositions.delayMs(0, 0.5f, 4f))
        assertEquals(2375L, AudioPositions.delayMs(10, 1.2f, 4f))
    }
}