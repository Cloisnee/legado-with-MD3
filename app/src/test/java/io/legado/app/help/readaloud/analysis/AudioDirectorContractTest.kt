package io.legado.app.help.readaloud.analysis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json 为 Android 框架实现：单测走 Robolectric（否则 JVM 下为 Not mocked 桩）。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class AudioDirectorContractTest {

    private fun root(itemsJson: String): JSONObject =
        JSONObject("{\"items\":[$itemsJson]}")

    /** 段号(1基) → 片段数 */
    private val unitCounts = mapOf(1 to 4, 2 to 1, 3 to 1, 4 to 4, 10 to 2)

    @Test
    fun `合法返回 段片段定位与归一`() {
        val items = AudioDirectorContract.validate(
            root(
                """
                {"para":1,"frag":2,"type":"ambience","tag":"客栈大堂","desc":"大堂环境底噪"},
                {"para":4,"frag":3,"type":"音效","tag":"推开声","desc":"木门吱呀","delayMs":200},
                {"para":4,"frag":4,"type":"bgm","profile":"fantasy","mood":"sad","intensity":"medium","hold":12,"desc":"二胡哀伤"}
                """.trimIndent()
            ),
            unitCounts,
        ).data
        assertNotNull(items)
        assertEquals(3, items!!.size)
        val amb = items.first { it.type == "ambience" }
        assertEquals(1, amb.para)
        assertEquals(2, amb.frag)
        val sfx = items.first { it.type == "sfx" }
        assertEquals(4, sfx.frag)
        assertEquals(200L, sfx.delayMs)
        val bgm = items.first { it.type == "bgm" }
        assertEquals("幻想", bgm.profile)
        assertEquals("悲情", bgm.mood)
        assertEquals("中", bgm.intensity)
        assertEquals(12, bgm.hold)
    }

    @Test
    fun `片段号 缺省与越界钳制`() {
        val items = AudioDirectorContract.validate(
            root(
                """
                {"para":10,"type":"sfx","tag":"剑鸣","desc":"剑出鞘"},
                {"para":10,"frag":9,"type":"sfx","tag":"推门声","desc":"推开"}
                """.trimIndent()
            ),
            unitCounts,
        ).data
        assertNotNull(items)
        assertEquals(2, items!!.size)
        assertEquals(1, items[0].frag)
        assertEquals(2, items[1].frag)
    }

    @Test
    fun `非法条目剔除 其余保留`() {
        val items = AudioDirectorContract.validate(
            root(
                """
                {"para":99,"frag":1,"type":"sfx","tag":"越界"},
                {"para":1,"frag":1,"type":"sfx","tag":"茶杯碎裂","desc":"陶瓷碎裂"},
                {"para":2,"type":"wtf","tag":"坏类型"},
                {"para":3,"type":"sfx","tag":"推门声","desc":"木门推开"},
                {"para":3,"type":"sfx","tag":"剑鸣","desc":"剑出鞘"},
                {"para":2,"type":"sfx","tag":"脚步急促","desc":"急促脚步"},
                {"para":1,"type":"sfx","tag":"马蹄声","desc":"马蹄疾驰"}
                """.trimIndent()
            ),
            unitCounts,
        ).data
        assertNotNull(items)
        assertEquals(5, items!!.size)
        assertTrue(items.none { it.tag == "越界" || it.tag == "坏类型" })
    }

    @Test
    fun `空 items 判失败`() {
        val out = AudioDirectorContract.validate(root(""), unitCounts)
        assertNull(out.data)
        assertTrue(out.failReason.contains("为空"))
    }

    @Test
    fun `全空 或 错误过多 判失败`() {
        val out = AudioDirectorContract.validate(
            root(
                """
                {"para":1,"type":"nope"},
                {"para":2,"type":"nope"},
                {"para":3,"type":"nope"},
                {"para":4,"type":"nope"}
                """.trimIndent()
            ),
            unitCounts,
        )
        assertNull(out.data)
    }

    @Test
    fun `枚举非法 提示含值集`() {
        val out = AudioDirectorContract.validate(
            root("""{"para":1,"frag":1,"type":"bgm","profile":"x","mood":"y","intensity":"z"}"""),
            unitCounts,
        )
        assertNull(out.data)
        assertTrue(out.failReason.contains("画像"))
    }

    @Test
    fun `去重`() {
        val items = AudioDirectorContract.validate(
            root(
                """
                {"para":4,"frag":1,"type":"sfx","tag":"剑鸣","desc":"剑出鞘"},
                {"para":4,"frag":1,"type":"sfx","tag":"剑鸣","desc":"重复"},
                {"para":1,"frag":1,"type":"sfx","tag":"推门声","desc":"推门"}
                """.trimIndent()
            ),
            unitCounts,
        ).data
        assertNotNull(items)
        assertEquals(2, items!!.size)
    }
}