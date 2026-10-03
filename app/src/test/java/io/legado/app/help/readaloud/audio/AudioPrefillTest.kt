package io.legado.app.help.readaloud.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPrefillTest {

    private fun sug(lane: SynthLane, label: String, ratio: Float = 0f) =
        AudioLaneScan.Suggestion(lane, label, ratio)

    private fun seed(seg: Int, lane: SynthLane, label: String, ratio: Float = 0f) =
        AudioPrefill.PlanSeed(seg, lane, label, ratio)

    @Test
    fun `标记渲染 顺序与文案`() {
        val text = AudioPrefill.markersFor(
            listOf(
                sug(SynthLane.BGM, "战斗BOSS高燃"),
                sug(SynthLane.AMB, "客栈大堂"),
                sug(SynthLane.SFX, "推开声", 0.5f),
            ),
        )
        assertEquals("〔BGM建议：战斗BOSS高燃〕〔环境建议：客栈大堂〕〔音效建议：推开声〕", text)
        assertEquals("", AudioPrefill.markersFor(listOf(sug(SynthLane.SFX, "  "))))
    }

    @Test
    fun `兜底计划 三轨映射与位置携带`() {
        val plan = AudioPrefill.buildRulesPlan(
            listOf(
                seed(3, SynthLane.SFX, "推开声", 0.35f),
                seed(5, SynthLane.AMB, "客栈大堂"),
                seed(5, SynthLane.BGM, "紧张"),
            ),
        )
        assertEquals("rules", plan.source)
        assertEquals(1, plan.ambience.size)
        assertEquals("客栈大堂", plan.ambience[0].tag)
        assertEquals(1, plan.bgm.size)
        assertEquals("紧张", plan.bgm[0].tag)
        assertEquals(1, plan.sfx.size)
        assertEquals(3, plan.sfx[0].para)
        assertEquals(0.35f, plan.sfx[0].posRatio, 0.0001f)
    }

    @Test
    fun `空白建议与非法行号被剔除`() {
        val plan = AudioPrefill.buildRulesPlan(
            listOf(seed(1, SynthLane.SFX, ""), seed(0, SynthLane.SFX, "x")),
        )
        assertEquals(0, plan.itemCount)
    }
}