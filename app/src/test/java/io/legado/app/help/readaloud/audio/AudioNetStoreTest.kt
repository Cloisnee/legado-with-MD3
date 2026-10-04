package io.legado.app.help.readaloud.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioNetStoreTest {

    @Test
    fun `说法归一 去柔和后缀`() {
        assertEquals("推门", AudioNetStore.norm("推门音效"))
        assertEquals("下雨", AudioNetStore.norm("下雨的声音"))
        assertEquals("开门", AudioNetStore.norm(" 开门 "))
        assertEquals("开门声", AudioNetStore.norm("开门声"))
        assertEquals("", AudioNetStore.norm("  "))
    }

    @Test
    fun `加词模式分词`() {
        assertEquals(
            listOf("推门", "开门", "掩门", "关门", "吱呀"),
            AudioNetStore.splitWords("推门|开门、掩门;关门\n吱呀"),
        )
        assertEquals(emptyList<String>(), AudioNetStore.splitWords("  "))
        assertEquals(listOf("收剑入鞘", "还刀归鞘"), AudioNetStore.splitWords("收剑入鞘，还刀归鞘"))
    }

    @Test
    fun `声形态变体`() {
        assertEquals(listOf("开门声", "开门音"), AudioNetStore.softVariants("开门"))
        assertEquals(listOf("推门", "推门音"), AudioNetStore.softVariants("推门声"))
        assertEquals(emptyList<String>(), AudioNetStore.softVariants("门"))
    }

    @Test
    fun `路径逐段编码`() {
        val p = AudioNetStore.encodePath("声效/音效/一声铃 01.mp3")
        assertTrue(p.startsWith("%E5%A3%B0%E6%95%88/%E9%9F%B3%E6%95%88/"))
        assertTrue(p.contains("%20"))
        assertEquals("/index/manifest.json", AudioNetStore.encodePath("/index/manifest.json"))
    }

    @Test
    fun `词网路径与小件分词`() {
        assertEquals(listOf("a", "b"), splitWordList("a;b"))
        assertEquals(SynthLane.SFX, AudioNetStore.laneOf("sfx"))
        assertEquals(SynthLane.SFX, AudioNetStore.laneOf("adult"))
        assertEquals(SynthLane.AMB, AudioNetStore.laneOf("amb"))
        assertEquals(SynthLane.BGM, AudioNetStore.laneOf("bgm"))
        assertEquals(null, AudioNetStore.laneOf("nope"))
        assertEquals("sfx/环境声", AudioNetStore.folderOf("amb"))
    }
}