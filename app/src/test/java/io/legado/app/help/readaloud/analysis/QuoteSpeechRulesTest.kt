package io.legado.app.help.readaloud.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteSpeechRulesTest {

    @Test
    fun `段首纯标点片并入后片`() {
        val text = "”。他说完就走了。"
        val units = QuoteSpeechRules.splitToUnits(text)
        assertEquals(1, units.size)
        assertEquals(text, text.substring(units[0].start, units[0].end))
    }

    @Test
    fun `整段纯标点不产出单元`() {
        val units = QuoteSpeechRules.splitToUnits("。”")
        assertTrue(units.isEmpty())
    }

    @Test
    fun `段尾纯标点仍并入前片`() {
        val units = QuoteSpeechRules.splitToUnits("他走了。”")
        assertEquals(1, units.size)
        assertEquals(0, units[0].start)
        assertEquals(5, units[0].end)
    }

    @Test
    fun `引号块独立成片不受影响`() {
        val text = "“你好。”他说"
        val units = QuoteSpeechRules.splitToUnits(text)
        assertEquals(2, units.size)
        assertEquals("“你好。”", text.substring(units[0].start, units[0].end))
        assertEquals("他说", text.substring(units[1].start, units[1].end))
    }
}
