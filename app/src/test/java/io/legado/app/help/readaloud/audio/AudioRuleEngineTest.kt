package io.legado.app.help.readaloud.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P1.2：仅用户条目规则（内置词典/意图已退役）；加词模式（多词字面）为主测点。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class AudioRuleEngineTest {

    private fun asset(
        name: String,
        pattern: String,
        isRegex: Boolean = false,
        category: String = "音效",
        scopeTitle: Boolean = false,
        scopeContent: Boolean = true,
        enabled: Boolean = true,
    ) = AudioLibrary.AudioAsset(
        name = name,
        relPath = "sfx/音效/$name.mp3",
        category = category,
        pattern = pattern,
        isRegex = isRegex,
        scopeTitle = scopeTitle,
        scopeContent = scopeContent,
        enabled = enabled,
    )

    @Test
    fun `加词模式 多词字面任一命中`() {
        val rules = AudioRuleEngine.compileUserRules(
            listOf(asset("开门声", "推门|开门、掩门;关门\n吱呀")),
        )
        assertEquals(1, rules.size)
        val r = rules[0]
        assertTrue(r.matches("他推开门的瞬间", false))
        assertTrue(r.matches("只听掩门一声轻响", false))
        assertFalse(r.matches("风雪很大", false))
        // "他推开门的瞬间" 中 "开门" 起点 = 2（“推门”不连续，不算命中）
        assertEquals(2, r.matchIndex("他推开门的瞬间"))
    }

    @Test
    fun `正则规则与非法正则`() {
        val rules = AudioRuleEngine.compileUserRules(
            listOf(
                asset("轰鸣", "轰(隆|的一声)", isRegex = true),
                asset("坏规则", "(((", isRegex = true),
            ),
        )
        assertEquals(1, rules.size)
        assertTrue(rules[0].matches("轰隆一声巨响", false))
        assertFalse(rules[0].matches("安静", false))
    }

    @Test
    fun `范围与车道`() {
        val rules = AudioRuleEngine.compileUserRules(
            listOf(
                asset("浙雨", "浙雨", scopeTitle = true, scopeContent = false),
                asset("夜曲", "夜曲", category = "BGM"),
                asset("雨夜", "雨夜", category = "环境声"),
            ),
        )
        val t = rules.first { it.name == "浙雨" }
        assertTrue(t.matches("浙雨", true))
        assertFalse(t.matches("浙雨", false))
        assertEquals(DemoLanes.Lane.BGM, rules.first { it.name == "夜曲" }.lane)
        assertEquals(DemoLanes.Lane.AMBIENCE, rules.first { it.name == "雨夜" }.lane)
    }

    @Test
    fun `停用条目与空模式不参与`() {
        assertEquals(0, AudioRuleEngine.compileUserRules(listOf(asset("x", "x", enabled = false))).size)
        assertEquals(0, AudioRuleEngine.compileUserRules(listOf(asset("y", "   "))).size)
    }
}