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
    fun `四段全中文名 精确命中 L1`() {
        val q = query()
        val m = AudioBgmPicker.matchOf("古风-战斗-紧张-快速-循环-剑影", q)
        assertTrue(m.matched)
        assertEquals(1, m.level)
        assertTrue(m.strict)
        assertEquals(0, m.softDims)
    }

    @Test
    fun `情绪已标注但不命中 降级 L3（只锁题材+场景）`() {
        val q = query()
        // 情绪不同（已标注）→ L1 失败；L2 要求情绪过 → 仍失败；L3 只锁题材+场景 → 命中
        val m = AudioBgmPicker.matchOf("古风-战斗-平静-快速-循环-剑影", q)
        assertTrue(m.matched)
        assertEquals(3, m.level)
    }

    @Test
    fun `题材已标注但不命中 完全不命中（L3 也锁题材）`() {
        val q = query()
        val m = AudioBgmPicker.matchOf("都市-战斗-紧张-快速-循环-街道", q)
        assertFalse(m.matched)
    }

    @Test
    fun `场景已标注但不命中 完全不命中（L3 也锁场景）`() {
        val q = query()
        val m = AudioBgmPicker.matchOf("古风-客栈-紧张-快速-循环-宴席", q)
        assertFalse(m.matched)
    }

    @Test
    fun `速度已标注但不命中 降级 L2`() {
        val q = query()
        // 速度不同（已标注）→ L1 失败；L2 放宽速度 → 题材/场景/情绪全过 → L2 命中
        val m = AudioBgmPicker.matchOf("古风-战斗-紧张-慢速-循环-剑影", q)
        assertTrue(m.matched)
        assertEquals(2, m.level)
    }

    @Test
    fun `缺失维度宽松 并计入softDims（L1 内）`() {
        val q = query()
        // 无速度标注（全名/别名无 慢/中/快速）→ speed 宽松；其余三维命中 → L1
        val m = AudioBgmPicker.matchOf("古风-战斗-紧张-循环-鼓点-压迫", q)
        assertTrue(m.matched)
        assertEquals(1, m.level)
        assertFalse(m.strict)
        assertEquals(1, m.softDims)
    }

    @Test
    fun `陈旧命名 子串命中且多宽松维`() {
        val q = query(theme = "仙侠", scene = "日常", mood = "舒缓", speed = "慢速")
        val m = AudioBgmPicker.matchOf("仙侠·舒缓·中", q)
        assertTrue(m.matched) // 场景/速度无标注 → 两维宽松
        assertEquals(1, m.level)
        assertEquals(2, m.softDims)
    }

    @Test
    fun `四段须全给 否则不匹配`() {
        assertFalse(AudioBgmPicker.matchOf("古风-战斗-紧张-快速", query(speed = "")).matched)
        assertFalse(AudioBgmPicker.matchOf("古风-战斗-紧张-快速", query(theme = "")).matched)
    }

    @Test
    fun `查询侧通用 视为宽松维`() {
        // 题材=通用 → 该维宽松；可命中任意题材素材（题材不锁）
        val q = query(theme = "通用")
        val m = AudioBgmPicker.matchOf("都市-战斗-紧张-快速-循环-街道", q)
        assertTrue(m.matched)
        assertEquals(1, m.level)
        assertTrue(m.softDims >= 1)
    }

    @Test
    fun `素材侧通用 视为宽松维`() {
        // 素材题材=通用（"通用"不参与标注判定）→ 题材维宽松；可被任意题材查询命中
        val q = query(theme = "现代")
        val m = AudioBgmPicker.matchOf("通用-战斗-紧张-快速-循环-通用紧张", q)
        assertTrue(m.matched)
        assertEquals(1, m.level)
        assertTrue(m.softDims >= 1)
    }

    @Test
    fun `dimsOf 解析 顺序与不复用`() {
        assertEquals(listOf("古风"), AudioBgmPicker.dimsOf("古风"))
        // 悬疑(题材位) 取走后不再复用；紧张(场景位词表内) 与 中速(速度)
        assertEquals(listOf("悬疑", "紧张", "中速"), AudioBgmPicker.dimsOf("悬疑-紧张-中速-循环-氛围"))
    }
}
