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

    @Test
    fun `合法返回 三轨解析与归一`() {
        val plan = AudioDirectorContract.validate(
            root(
                """
                {"para":1,"type":"ambience","tag":"客栈大堂","desc":"大堂环境底噪"},
                {"para":3,"type":"音效","tag":"推开声","desc":"木门吱呀","delayMs":200},
                {"para":5,"type":"bgm","profile":"fantasy","mood":"sad","intensity":"medium","hold":12,"desc":"二胡哀伤"}
                """.trimIndent()
            ),
            paraCount = 10,
        ).data
        assertNotNull(plan)
        assertEquals(1, plan!!.ambience.size)
        assertEquals(1, plan.bgm.size)
        assertEquals(1, plan.sfx.size)
        assertEquals("幻想", plan.bgm[0].profile)
        assertEquals("悲情", plan.bgm[0].mood)
        assertEquals("中", plan.bgm[0].intensity)
        assertEquals(12, plan.bgm[0].hold)
        assertEquals(200L, plan.sfx[0].delayMs)
    }

    @Test
    fun `非法条目剔除 其余保留`() {
        val plan = AudioDirectorContract.validate(
            root(
                """
                {"para":99,"type":"sfx","tag":"越界"},
                {"para":2,"type":"sfx","tag":"茶杯碎裂","desc":"陶瓷碎裂"},
                {"para":3,"type":"wtf","tag":"坏类型"},
                {"para":5,"type":"sfx","tag":"推门声","desc":"木门推开"},
                {"para":6,"type":"sfx","tag":"剑鸣","desc":"剑出鞘"},
                {"para":7,"type":"sfx","tag":"脚步急促","desc":"急促脚步"},
                {"para":8,"type":"sfx","tag":"马蹄声","desc":"马蹄疾驰"}
                """.trimIndent()
            ),
            paraCount = 10,
        ).data
        assertNotNull(plan)
        assertEquals(5, plan!!.sfx.size)
        assertEquals("茶杯碎裂", plan.sfx[0].tag)
        assertEquals(0, plan.bgm.size)
    }

    @Test
    fun `空 items 判失败`() {
        val out = AudioDirectorContract.validate(root(""), paraCount = 10)
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
            paraCount = 10,
        )
        assertNull(out.data)
    }

    @Test
    fun `枚举非法 提示含值集`() {
        val out = AudioDirectorContract.validate(
            root("""{"para":1,"type":"bgm","profile":"x","mood":"y","intensity":"z"}"""),
            paraCount = 10,
        )
        assertNull(out.data)
        assertTrue(out.failReason.contains("画像"))
    }

    @Test
    fun `去重 与 排序`() {
        val plan = AudioDirectorContract.validate(
            root(
                """
                {"para":5,"type":"sfx","tag":"剑鸣","desc":"剑出鞘"},
                {"para":5,"type":"sfx","tag":"剑鸣","desc":"重复"},
                {"para":1,"type":"sfx","tag":"推门声","desc":"推门"}
                """.trimIndent()
            ),
            paraCount = 10,
        ).data
        assertNotNull(plan)
        assertEquals(2, plan!!.sfx.size)
        assertEquals(1, plan.sfx[0].para)
        assertEquals(5, plan.sfx[1].para)
    }

    @Test
    fun `音效 pos 映射为句内比例`() {
        val plan = AudioDirectorContract.validate(
            root(
                """
                {"para":2,"type":"sfx","tag":"推门声","desc":"木门推开","pos":"中"},
                {"para":3,"type":"sfx","tag":"剑鸣","desc":"剑出鞘","pos":"乱写"}
                """.trimIndent()
            ),
            paraCount = 10,
        ).data
        assertNotNull(plan)
        assertEquals(0.5f, plan!!.sfx[0].posRatio, 0.0001f)
        assertEquals(0f, plan.sfx[1].posRatio, 0.0001f)
    }
}
