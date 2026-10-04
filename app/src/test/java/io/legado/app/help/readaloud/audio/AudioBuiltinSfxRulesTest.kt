package io.legado.app.help.readaloud.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M3：内置音效规则（slim 回归）——解析 / 命中 / 容错。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class AudioBuiltinSfxRulesTest {

    private val sample = """{"schemaVersion":1,"source":"内置音效规则","rules":[
        {"name":"拍桌","pattern":"(?:一拍桌子|拍案而起)"},
        {"name":"摔门","pattern":"(?:摔门|摔上门)"}
    ]}"""

    @Test
    fun `命中返回现行名与起点`() {
        val pack = AudioBuiltinSfxRules.buildPack(sample) ?: error("buildPack 失败")
        val hit = pack.hit("他猛地一拍桌子站了起来")
        assertEquals("拍桌", hit?.name)
        assertEquals(3, hit?.start)
        assertNull(pack.hit("这段文本与规则无关"))
    }

    @Test
    fun `空规则或非法 JSON 返回 null`() {
        assertNull(AudioBuiltinSfxRules.buildPack("""{"rules":[]}"""))
        assertNull(AudioBuiltinSfxRules.buildPack("not-a-json"))
    }

    @Test
    fun `无关键词规则走常跑位`() {
        val pack = AudioBuiltinSfxRules.buildPack(
            """{"rules":[{"name":"铃","pattern":"(?:\\(叮\\))"}]}"""
        ) ?: error("buildPack 失败")
        assertEquals("铃", pack.hit("(叮)")?.name)
    }
}
