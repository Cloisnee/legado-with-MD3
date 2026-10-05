package io.legado.app.help.readaloud.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBgmPickerTest {

    private fun query(
        theme: String = "古风",
        scene: String = "战斗",
        mood: String = "紧张",
        speed: String = "快速",
    ) = AudioBgmPicker.Query(theme, scene, mood, speed)

    @Test
    fun `四段全中文名 精确命中`() {
        val q = query()
        val m = AudioBgmPicker.matchOf("古风-战斗-紧张-快速-循环-剑影", q)
        assertTrue(m.matched)
        assertTrue(m.strict)
        assertEquals(0, m.softDims)
    }

    @Test
    fun `任一已标注维度不命中即失败`() {
        val q = query()
        // 情绪不同（已标注）→ 失败
        assertFalse(AudioBgmPicker.matchOf("古风-战斗-平静-快速-循环-剑影", q).matched)
        // 题材不同（已标注）→ 失败
        assertFalse(AudioBgmPicker.matchOf("都市-战斗-紧张-快速-循环-街道", q).matched)
        // 场景不命中且该维已标注（客栈）→ 失败
        assertFalse(AudioBgmPicker.matchOf("古风-客栈-紧张-快速-循环-宴席", q).matched)
    }

    @Test
    fun `缺失维度宽松 并计入softDims`() {
        val q = query()
        // 无速度标注（全名/别名无 慢/中/快速）→ speed 宽松；其余三维修命中
        val m = AudioBgmPicker.matchOf("古风-战斗-紧张-循环-鼓点-压迫", q)
        assertTrue(m.matched)
        assertFalse(m.strict)
        assertEquals(1, m.softDims)
    }

    @Test
    fun `陈旧命名 子串命中且多宽松维`() {
        val q = query(theme = "仙侠", scene = "日常", mood = "舒缓", speed = "慢速")
        val m = AudioBgmPicker.matchOf("仙侠·舒缓·中", q)
        assertTrue(m.matched) // 场景/速度无标注 → 两维宽松
        assertEquals(2, m.softDims)
    }

    @Test
    fun `四段须全给 否则不匹配`() {
        assertFalse(AudioBgmPicker.matchOf("古风-战斗-紧张-快速", query(speed = "")).matched)
        assertFalse(AudioBgmPicker.matchOf("古风-战斗-紧张-快速", query(theme = "")).matched)
    }

    @Test
    fun `dimsOf 解析 顺序与不复用`() {
        assertEquals(listOf("古风"), AudioBgmPicker.dimsOf("古风"))
        // 悬疑(题材位) 取走后不再复用；紧张(场景位词表内) 与 中速(速度)
        assertEquals(listOf("悬疑", "紧张", "中速"), AudioBgmPicker.dimsOf("悬疑-紧张-中速-循环-氛围"))
    }
}
