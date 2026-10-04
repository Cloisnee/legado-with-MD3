package io.legado.app.help.readaloud.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** P1.4：合并跟随——词并入语义（去重 / 词模式 / 正则关闭口径）。 */
class AudioMergeFollowPatternTest {

    @Test
    fun `词并入 去重 且保持原词顺序`() {
        val (pattern, n) = AudioLibrary.mergeFollowPattern(
            targetPattern = "推门声|吱呀",
            targetIsRegex = false,
            incoming = listOf("开门声", "推门声", " ", "推大门", "开门声"),
        )
        assertEquals("推门声|吱呀|开门声|推大门", pattern)
        assertEquals(2, n)
    }

    @Test
    fun `目标为正则时关闭并丢弃原正则内容`() {
        val (pattern, n) = AudioLibrary.mergeFollowPattern(
            targetPattern = "^推.*声$",
            targetIsRegex = true,
            incoming = listOf("开门声", "推大门"),
        )
        assertEquals("开门声|推大门", pattern)
        assertEquals(2, n)
    }

    @Test
    fun `无新词时保留原样`() {
        val (pattern, n) = AudioLibrary.mergeFollowPattern(
            targetPattern = "推门声",
            targetIsRegex = false,
            incoming = listOf("推门声", "推门声"),
        )
        assertEquals("推门声", pattern)
        assertEquals(0, n)
    }

    @Test
    fun `词模式空目标只接收新词`() {
        val (pattern, n) = AudioLibrary.mergeFollowPattern(
            targetPattern = "",
            targetIsRegex = true,
            incoming = listOf("开门声"),
        )
        assertEquals("开门声", pattern)
        assertEquals(1, n)
    }
}
