package io.legado.app.help.readaloud.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json 为 Android 框架实现：单测走 Robolectric（否则 JVM 下为 Not mocked 桩）。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class AudioRuleEngineTest {

    @Test
    fun `意图解析 与 车道映射`() {
        val intents = AudioRuleStore.parseIntents(
            """
            {"intents":[
              {"intentId":"i_scene","type":"scene","anchors":["鸟鸣"],"priority":3,"volume":0.52,"timing":"loop"},
              {"intentId":"i_strong","type":"strong_sfx","anchors":["万箭齐发","万箭齐射"],"priority":6,"volume":0.88},
              {"intentId":"i_off","type":"sfx","anchors":["X"],"runtimeEnabled":false}
            ]}
            """.trimIndent()
        ).orEmpty()
        assertEquals(3, intents.size)
        assertEquals(2, intents.first { it.id == "i_strong" }.anchors.size)
        // 3 条中 1 条 runtimeEnabled=false → 构建后 2 条参与匹配
        assertEquals(2, AudioRuleStore.buildData(intents, emptyMap(), emptyMap(), emptyMap(), emptySet()).intentCount)
        assertEquals(DemoLanes.Lane.AMBIENCE, AudioRuleEngine.laneForType("scene"))
        assertEquals(DemoLanes.Lane.SFX, AudioRuleEngine.laneForType("strong_sfx"))
        assertEquals(DemoLanes.Lane.SFX, AudioRuleEngine.laneForType("emotion"))
    }

    @Test
    fun `AC 命中 意图排序（优先级降序）与分道`() {
        val intents = AudioRuleStore.parseIntents(
            """
            {"intents":[
              {"intentId":"i_scene","type":"scene","anchors":["鸟鸣"],"priority":3,"volume":0.52},
              {"intentId":"i_strong","type":"strong_sfx","anchors":["万箭齐发","万箭齐射"],"priority":6,"volume":0.88},
              {"intentId":"i_micro","type":"micro_sfx","anchors":["茶杯"],"priority":5,"volume":0.7}
            ]}
            """.trimIndent()
        ).orEmpty()
        val map = mapOf(
            "i_scene" to listOf("bird_call_02"),
            "i_strong" to listOf("arrow_volley_01"),
            "i_micro" to listOf("cup_01"),
        )
        val sounds = mapOf(
            "arrow_volley_01" to AudioRuleStore.SoundMeta("arrow_volley_01", "万箭齐发音效", listOf("万箭齐发音效", "万箭齐发")),
            "cup_01" to AudioRuleStore.SoundMeta("cup_01", "茶杯摆放音效", listOf("茶杯摆放音效")),
            "bird_call_02" to AudioRuleStore.SoundMeta("bird_call_02", "林鸟惊飞音效", listOf("林鸟惊飞音效")),
        )
        val text = "万箭齐发，他放下茶杯；远处似有鸟鸣。"
        val data = AudioRuleStore.buildData(intents, map, sounds, emptyMap(), emptySet())
        assertEquals(
            listOf("i_strong", "i_micro"),
            AudioRuleEngine.intentHits(data, DemoLanes.Lane.SFX, text).map { it.intentId },
        )
        assertEquals(
            listOf("i_scene"),
            AudioRuleEngine.intentHits(data, DemoLanes.Lane.AMBIENCE, text).map { it.intentId },
        )
        // 默认关闭（18+ 等）声音被排除：候选全排除 → 该意图整体跳过
        val filtered = AudioRuleStore.buildData(intents, map, sounds, emptyMap(), setOf("arrow_volley_01"))
        assertEquals(
            listOf("i_micro"),
            AudioRuleEngine.intentHits(filtered, DemoLanes.Lane.SFX, text).map { it.intentId },
        )
    }

    @Test
    fun `别名表解析 小写归并`() {
        val map = AudioRuleStore.parseAliasRules(
            """{"aliases":[{"alias":"万箭齐发","soundId":"arrow_volley_01"},{"alias":"Arrow","soundId":"x1"}]}"""
        )
        assertEquals("arrow_volley_01", map["万箭齐发"])
        assertEquals("x1", map["arrow"])
    }

    @Test
    fun `四条件兜底口径`() {
        assertTrue(AudioFallbackPolicy.shouldUseRules(hasPlan = false))
        assertTrue(AudioFallbackPolicy.shouldUseRules(hasPlan = true, aiFailed = true))
        assertTrue(AudioFallbackPolicy.shouldUseRules(hasPlan = true, aiConfigured = false))
        assertTrue(AudioFallbackPolicy.shouldUseRules(hasPlan = true, isFirstChapter = true))
        assertTrue(AudioFallbackPolicy.shouldUseRules(hasPlan = true, isNonContiguous = true))
        assertFalse(AudioFallbackPolicy.shouldUseRules(hasPlan = true))
    }
}
