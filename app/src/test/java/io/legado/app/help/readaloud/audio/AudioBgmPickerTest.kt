package io.legado.app.help.readaloud.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBgmPickerTest {

    private fun query(profile: String = "古风", mood: String = "悲情", intensity: String = "低") =
        AudioBgmPicker.Query(profile, mood, intensity)

    private fun remote(
        soundId: String,
        name: String,
        aliases: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        subType: String = "",
    ) = AudioRemoteCatalog.RemoteSound(
        soundId = soundId,
        name = name,
        aliases = aliases,
        category = "bgm",
        categoryName = "BGM",
        subType = subType,
        tags = tags,
    )

    @Test
    fun `中英 token 命中打分`() {
        val q = query()
        val good = AudioBgmPicker.scoreOf("history_ancient_sad_low_loop_古风庭院", q)
        val weak = AudioBgmPicker.scoreOf("urban_modern_calm_medium_loop_都市", q)
        assertTrue(good.usable)
        assertTrue(good.total > weak.total)
    }

    @Test
    fun `选曲 优先画像+情绪双命中`() {
        val q = query()
        val a = remote("a", "common_daily_calm_medium_loop_日常") // 无命中
        val b = remote("b", "history_daily_sad_low_loop_古风悲情") // profile+mood+intensity
        val c = remote("c", "urban_night_warm_low_loop_都市温馨") // 仅 intensity
        val pick = AudioBgmPicker.pickRemote(listOf(a, b, c), q)
        assertEquals("b", pick?.soundId)
    }

    @Test
    fun `无有效命中 返回 null`() {
        val q = query()
        val a = remote("a", "scifi_space_heroic_high_loop")
        assertNull(AudioBgmPicker.pickRemote(listOf(a), q))
    }

    @Test
    fun `中文命名条目 可命中`() {
        val q = query()
        val a = remote("a", "古风_客栈_叙事_悲情_低_循环")
        val b = remote("b", "现代_都市_紧张_高")
        val pick = AudioBgmPicker.pickRemote(listOf(a, b), q)
        assertEquals("a", pick?.soundId)
    }

    @Test
    fun `别名与标签参与检索`() {
        val q = query(profile = "幻想", mood = "平静")
        val a = remote("a", "BGM_增补_0009", aliases = listOf("BGM_增补_0009_幻想_森林_平静_中等"), tags = listOf("calm"))
        assertTrue(AudioBgmPicker.scoreOf(AudioBgmPicker.searchText(a), q).usable)
        // 画像=古风 与 幻想 不互通 → 不视为有效命中（仅强度不构成有效命中）
        val q2 = query(profile = "恐怖", mood = "悲情")
        assertTrue(!AudioBgmPicker.scoreOf(AudioBgmPicker.searchText(a), q2).usable)
    }
}
