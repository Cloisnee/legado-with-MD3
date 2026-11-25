package io.legado.app.help.readaloud.analysis

import io.legado.app.domain.model.readaloud.v4.RaChapterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchSelectorTest {

    private fun allCached(range: IntRange) =
        range.associateWith { RaChapterState.CACHED }.toMutableMap()

    @Test
    fun `doc example one - forward then backward`() {
        val states = allCached(1..100)
        assertEquals((92..100).toList(), BatchSelector.nextBatch(states, 89, 89..91, 20, 10))
        (92..100).forEach { states[it] = RaChapterState.IDENTITY_READY }
        assertEquals((79..88).toList(), BatchSelector.nextBatch(states, 89, 89..91, 11, 10))
        (79..88).forEach { states[it] = RaChapterState.IDENTITY_READY }
        // 02 §7.3 示例原文写「第三批 [69]」；按同节算法与额度，第三批应为 [78]（69 不在连续段内）。
        assertEquals(listOf(78), BatchSelector.nextBatch(states, 89, 89..91, 1, 10))
    }

    @Test
    fun `doc example two - ready chapter breaks backward run`() {
        val states = allCached(1..100)
        states[84] = RaChapterState.IDENTITY_READY
        assertEquals((92..100).toList(), BatchSelector.nextBatch(states, 89, 89..91, 20, 10))
        (92..100).forEach { states[it] = RaChapterState.IDENTITY_READY }
        assertEquals((85..88).toList(), BatchSelector.nextBatch(states, 89, 89..91, 11, 10))
        (85..88).forEach { states[it] = RaChapterState.IDENTITY_READY }
        assertEquals((77..83).toList(), BatchSelector.nextBatch(states, 89, 89..91, 7, 10))
    }

    @Test
    fun `doc example three - no content chapter never enters batch`() {
        val states = allCached(1..100)
        states[84] = RaChapterState.NO_CONTENT
        assertEquals((92..100).toList(), BatchSelector.nextBatch(states, 89, 89..91, 20, 10))
        (92..100).forEach { states[it] = RaChapterState.IDENTITY_READY }
        assertEquals((85..88).toList(), BatchSelector.nextBatch(states, 89, 89..91, 11, 10))
        (85..88).forEach { states[it] = RaChapterState.IDENTITY_READY }
        assertEquals((77..83).toList(), BatchSelector.nextBatch(states, 89, 89..91, 7, 10))
    }

    @Test
    fun `fg window covers chapters after anchor`() {
        val states = allCached(1..10)
        assertEquals((1..5).toList(), BatchSelector.nextBatch(states, 6, 6..10, 10, 10))
    }

    @Test
    fun `budget smaller than run keeps prefix forward and suffix backward`() {
        val states = allCached(1..20)
        assertEquals((1..3).toList(), BatchSelector.nextBatch(states, 0, 0..0, 3, 10))
        states[15] = RaChapterState.IDENTITY_READY
        assertEquals((16..18).toList(), BatchSelector.nextBatch(states, 19, 19..20, 3, 10))
    }

    @Test
    fun `all ready returns null`() {
        val states = (1..5).associateWith { RaChapterState.IDENTITY_READY }
        assertNull(BatchSelector.nextBatch(states, 2, 2..3, 10, 10))
    }

    @Test
    fun `stale is analyzable`() {
        val states = mapOf(1 to RaChapterState.STALE, 2 to RaChapterState.IDENTITY_READY)
        assertEquals(listOf(1), BatchSelector.nextBatch(states, 0, 0..0, 5, 10))
    }

    @Test
    fun `max batch one`() {
        val states = allCached(1..5)
        assertEquals(listOf(4), BatchSelector.nextBatch(states, 2, 2..3, 10, 1))
    }

    @Test
    fun `token cap limits batch`() {
        val states = allCached(1..10)
        states[7] = RaChapterState.IDENTITY_READY
        states[8] = RaChapterState.IDENTITY_READY
        states[9] = RaChapterState.IDENTITY_READY
        states[10] = RaChapterState.IDENTITY_READY
        val tokens = (1..10).associateWith { 100 }
        assertEquals(
            (5..6).toList(),
            BatchSelector.nextBatch(states, 7, 7..8, 10, 10, tokens, outputTokenCap = 200),
        )
    }
}
