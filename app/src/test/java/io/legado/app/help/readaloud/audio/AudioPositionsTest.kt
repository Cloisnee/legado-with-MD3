package io.legado.app.help.readaloud.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPositionsTest {

    @Test
    fun `前中后 片内中点映射`() {
        assertEquals(1f / 6f, AudioPositions.ratioOf("前"), 0.0001f)
        assertEquals(0.5f, AudioPositions.ratioOf("中"), 0.0001f)
        assertEquals(5f / 6f, AudioPositions.ratioOf("后"), 0.0001f)
        assertEquals(1f / 6f, AudioPositions.ratioOf("front"), 0.0001f)
        assertEquals(0f, AudioPositions.ratioOf(""), 0.0001f)
        assertEquals(0f, AudioPositions.ratioOf("乱写"), 0.0001f)
    }

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
    fun `三等分切分`() {
        val (f, m, b) = AudioPositions.splitThirds("abcdef")
        assertEquals(0..1, f)
        assertEquals(2..3, m)
        assertEquals(4..5, b)
        val (f1, m1, b1) = AudioPositions.splitThirds("a")
        assertEquals(0..0, f1)
        assertTrue(m1.isEmpty())
        assertTrue(b1.isEmpty())
        val (f0, m0, b0) = AudioPositions.splitThirds("")
        assertTrue(f0.isEmpty() && m0.isEmpty() && b0.isEmpty())
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