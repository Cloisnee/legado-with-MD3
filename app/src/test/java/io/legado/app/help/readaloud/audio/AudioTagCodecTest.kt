package io.legado.app.help.readaloud.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTagCodecTest {

    @Test
    fun `解析三种标签`() {
        val raw = "〖张三〗[[emo:愤怒]][[a:amb=客栈大堂]][[a:sfx=茶杯碎裂@200]][[a:bgm=悲情·低·12]]正文"
        val tags = AudioTagCodec.parse(raw)
        assertEquals(3, tags.size)
        assertEquals("amb", tags[0].type)
        assertEquals("客栈大堂", tags[0].value)
        assertEquals("茶杯碎裂", tags[1].name)
        assertEquals(200L, tags[1].delayMs)
        assertEquals("悲情·低·12", tags[2].value)
    }

    @Test
    fun `剥离不改其余文本`() {
        val raw = "[[emo:愤怒]]他说完就推门离开。[[a:sfx=关门声@120]]"
        val stripped = AudioTagCodec.strip(raw)
        assertEquals("[[emo:愤怒]]他说完就推门离开。", stripped)
    }

    @Test
    fun `extractRaw 原样保留`() {
        val raw = "正文[[a:sfx=推开声]][[a:amb=街道]]"
        assertEquals("[[a:sfx=推开声]][[a:amb=街道]]", AudioTagCodec.extractRaw(raw))
        assertEquals("", AudioTagCodec.extractRaw("纯正文"))
    }

    @Test
    fun `渲染 顺序与格式`() {
        val line = AudioTagCodec.renderLine(
            ambience = "山间清晨",
            sfx = listOf(AudioTagCodec.sfxValue("鸟惊声", 0), AudioTagCodec.sfxValue("脚步急促", 350)),
            bgm = "紧张·高·10",
        )
        assertEquals(
            "[[a:amb=山间清晨]][[a:sfx=鸟惊声]][[a:sfx=脚步急促@350]][[a:bgm=紧张·高·10]]",
            line,
        )
    }

    @Test
    fun `chips 文案`() {
        val raw = "[[a:amb=客栈大堂]][[a:sfx=茶杯碎裂@200]][[a:bgm=悲情·低·12]]"
        val text = AudioTagCodec.chipsText(raw)
        assertTrue(text.contains("环境·客栈大堂"))
        assertTrue(text.contains("音效·茶杯碎裂@200"))
        assertTrue(text.contains("BGM·悲情·低·12"))
        assertEquals("", AudioTagCodec.chipsText(""))
    }
}
