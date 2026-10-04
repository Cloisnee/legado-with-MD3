package io.legado.app.help.readaloud.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** P1.2：远程素材库（单链版）——搜索排序与车道名映射。 */
class AudioRemoteTest {

    private fun sound(id: String, name: String, aliases: List<String> = emptyList()) =
        AudioRemoteCatalog.RemoteSound(soundId = id, name = name, aliases = aliases)

    @Test
    fun `搜索 精确优先 前缀次之 包含最后`() {
        val list = listOf(
            sound("1", "开门"),
            sound("2", "开门声"),
            sound("3", "轻轻推开门"),
            sound("4", "关门"),
        )
        val hit = AudioRemoteCatalog.search(list, "开门")
        assertEquals(listOf("1", "2", "3"), hit.map { it.soundId })
        // 别名精确命中
        val aliasHit = AudioRemoteCatalog.search(
            listOf(sound("9", "掩门", aliases = listOf("开门"))),
            "开门",
        )
        assertEquals(listOf("9"), aliasHit.map { it.soundId })
    }

    @Test
    fun `搜索 空串返回全部`() {
        assertEquals(2, AudioRemoteCatalog.search(listOf(sound("a", "x"), sound("b", "y")), " ").size)
    }

    @Test
    fun `车道名映射`() {
        assertEquals("BGM", AudioRemoteCatalog.laneNameOf("bgm"))
        assertEquals("环境声", AudioRemoteCatalog.laneNameOf("amb"))
        assertEquals("ADULT", AudioRemoteCatalog.laneNameOf("adult"))
        assertEquals("音效", AudioRemoteCatalog.laneNameOf("sfx"))
    }
}